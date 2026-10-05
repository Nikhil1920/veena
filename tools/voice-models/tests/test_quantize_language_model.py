"""Synthetic graph checks for build safety and quantization recipes, not ASR quality."""

from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import quantize_language_model as quantizer


def make_model(path: Path) -> None:
    rng = np.random.default_rng(7)
    graph = helper.make_graph(
        [
            helper.make_node("Conv", ["audio_signal", "conv_weight"], ["convolved"], name="conv"),
            helper.make_node("Transpose", ["convolved"], ["frames"], perm=[0, 2, 1]),
            helper.make_node("MatMul", ["frames", "projection"], ["logprobs"], name="projection"),
        ],
        "synthetic_ctc_interface",
        [helper.make_tensor_value_info("audio_signal", TensorProto.FLOAT, [1, 80, "time"]),
         helper.make_tensor_value_info("length", TensorProto.INT64, [1])],
        [helper.make_tensor_value_info("logprobs", TensorProto.FLOAT, [1, "time", 5])],
        [numpy_helper.from_array((rng.standard_normal((8, 80, 1)) * 0.03).astype(np.float32), "conv_weight"),
         numpy_helper.from_array((rng.standard_normal((8, 5)) * 0.03).astype(np.float32), "projection")],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 16)], ir_version=9)
    onnx.save(model, path)


class QuantizeLanguageModelTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.model = self.root / "source.onnx"
        make_model(self.model)
        self.rng = np.random.default_rng(19)

    def tearDown(self):
        self.directory.cleanup()

    def manifest(self, name: str, *, count: int = 2, language: str = "te") -> Path:
        rows = []
        for index in range(count):
            features = self.rng.normal(size=(1, 80, 16 + index * 4)).astype(np.float32)
            feature_path = self.root / f"{name}-{index}.npy"
            np.save(feature_path, features)
            rows.append({"id": f"{name}-{index}", "language": language, "text": "పరీక్ష",
                         "features": feature_path.name, "duration_seconds": features.shape[-1] * .01})
        path = self.root / f"{name}.jsonl"
        path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))
        return path

    def test_builds_both_recipes_without_selecting_a_model(self):
        calibration = self.manifest("calibration")
        held_out = self.manifest("held-out", count=1)
        original_sha = quantizer.sha256_file(self.model)
        output = self.root / "candidates"
        report = quantizer.build_candidates(
            self.model, output, "te", ["dynamic_matmul_int8", "static_qdq_int8"],
            calibration_manifest=calibration, evaluation_manifest=held_out,
        )
        self.assertEqual(report["status"], "built", report)
        self.assertEqual(report["validation_status"], "unvalidated")
        self.assertIsNone(report["selection"])
        self.assertEqual(original_sha, quantizer.sha256_file(self.model))
        self.assertEqual(report["calibration"]["sample_count"], 2)
        self.assertFalse((output / ".build").exists())
        self.assertTrue((output / "candidate-report.json").exists())
        feeds = {"audio_signal": self.rng.normal(size=(1, 80, 23)).astype(np.float32),
                 "length": np.array([23], dtype=np.int64)}
        for candidate in report["candidates"]:
            self.assertEqual(candidate["build_status"], "built")
            self.assertEqual(candidate["validation_status"], "unvalidated")
            self.assertEqual(len(candidate["recipe_sha256"]), 64)
            model_path = Path(candidate["artifact"]["path"])
            self.assertEqual(candidate["artifact"]["sha256"], quantizer.sha256_file(model_path))
            session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
            result = session.run(None, feeds)[0]
            self.assertEqual(result.shape, (1, 23, 5))
            self.assertTrue(np.isfinite(result).all())
            with self.assertRaisesRegex(ValueError, "already quantized"):
                quantizer.inspect_float_source(model_path)
        dynamic, static = report["candidates"]
        self.assertIsNone(dynamic["calibration_corpus_sha256"])
        self.assertEqual(static["calibration_corpus_sha256"], report["calibration"]["corpus_sha256"])
        self.assertIn("MatMulInteger", dynamic["artifact"]["operator_counts"])
        self.assertIn("QuantizeLinear", static["artifact"]["operator_counts"])

    def test_static_requires_a_held_out_corpus_and_multiple_calibration_clips(self):
        calibration = self.manifest("calibration", count=1)
        held_out = self.manifest("held-out", count=1)
        for held_out_path, message in [(None, "evaluation-manifest"), (held_out, "two distinct")]:
            with self.subTest(held_out=held_out_path):
                with self.assertRaisesRegex(ValueError, message):
                    quantizer.build_candidates(
                        self.model, self.root / "out", "te", ["static_qdq_int8"],
                        calibration_manifest=calibration, evaluation_manifest=held_out_path,
                    )
                self.assertFalse((self.root / "out").exists())

    def test_overlapping_corpora_rejected_even_when_files_have_different_names(self):
        calibration = self.manifest("calibration")
        held_out = self.manifest("held-out", count=1)
        (self.root / "held-out-0.npy").write_bytes((self.root / "calibration-0.npy").read_bytes())
        with self.assertRaisesRegex(ValueError, "overlap by feature content"):
            quantizer.build_candidates(self.model, self.root / "out", "te", ["static_qdq_int8"],
                                      calibration_manifest=calibration, evaluation_manifest=held_out)
        self.assertFalse((self.root / "out").exists())

    def test_duplicate_calibration_and_wrong_language_rejected(self):
        calibration = self.manifest("calibration")
        held_out = self.manifest("held-out", count=1)
        (self.root / "calibration-1.npy").write_bytes((self.root / "calibration-0.npy").read_bytes())
        with self.assertRaisesRegex(ValueError, "duplicate feature"):
            quantizer.build_candidates(self.model, self.root / "out", "te", ["static_qdq_int8"],
                                      calibration_manifest=calibration, evaluation_manifest=held_out)
        with self.assertRaisesRegex(ValueError, "only selected language"):
            quantizer.load_language_corpus(held_out, "hi")

    def test_existing_output_directory_is_never_overwritten(self):
        output = self.root / "out"
        output.mkdir()
        sentinel = output / "existing.txt"
        sentinel.write_text("keep this")
        with self.assertRaises(FileExistsError):
            quantizer.build_candidates(self.model, output, "te", ["dynamic_matmul_int8"])
        self.assertEqual(sentinel.read_text(), "keep this")

    def test_low_bit_initializer_is_rejected_without_quantized_operators(self):
        model = onnx.load(self.model)
        model.graph.initializer.append(numpy_helper.from_array(np.array([7], dtype=np.uint8), "compressed"))
        onnx.save(model, self.model)
        with self.assertRaisesRegex(ValueError, "already quantized"):
            quantizer.inspect_float_source(self.model)

    def test_quantized_operator_inside_subgraph_is_rejected(self):
        model = onnx.load(self.model)
        branch = helper.make_graph(
            [helper.make_node("DynamicQuantizeLinear", ["audio_signal"], ["q", "s", "z"])],
            "nested", [], [],
        )
        model.graph.node.append(helper.make_node("If", ["condition"], ["nested_output"], then_branch=branch))
        onnx.save(model, self.model)
        with self.assertRaisesRegex(ValueError, "already quantized"):
            quantizer.inspect_float_source(self.model)

    def test_external_weights_are_included_in_artifact_fingerprint(self):
        model = onnx.load(self.model)
        external = self.root / "external.onnx"
        onnx.save_model(model, str(external), save_as_external_data=True,
                        all_tensors_to_one_file=True, location="weights.data", size_threshold=0)
        record = quantizer.artifact_record(external)
        self.assertEqual(len(record["files"]), 2)
        self.assertEqual(record["size_bytes"], external.stat().st_size + (self.root / "weights.data").stat().st_size)
        old_digest = record["artifact_sha256"]
        with (self.root / "weights.data").open("ab") as source:
            source.write(b"changed")
        self.assertNotEqual(old_digest, quantizer.artifact_record(external)["artifact_sha256"])

    def test_failed_build_records_failure_without_approval_or_selection(self):
        with patch.object(quantizer, "quantize_dynamic", side_effect=RuntimeError("deliberate failure")):
            report = quantizer.build_candidates(self.model, self.root / "out", "te", ["dynamic_matmul_int8"])
        self.assertEqual(report["status"], "failed")
        self.assertEqual(report["candidates"][0]["build_status"], "failed")
        self.assertIn("deliberate failure", report["candidates"][0]["error"])
        self.assertIsNone(report["selection"])

    def test_const_static_recipes_keep_runtime_attention_matmul_unquantized(self):
        model = onnx.load(self.model)
        model.graph.node.extend([
            helper.make_node("Transpose", ["audio_signal"], ["query"], perm=[0, 2, 1]),
            helper.make_node("MatMul", ["query", "audio_signal"], ["attention_scores"], name="attention_qk"),
        ])
        model.graph.output.append(helper.make_tensor_value_info("attention_scores", TensorProto.FLOAT, [1, "time", "time"]))
        onnx.save(model, self.model)
        self.assertEqual(quantizer.nonconstant_matmul_exclusions(model), ["attention_qk"])
        calibration = self.manifest("calibration")
        held_out = self.manifest("held-out", count=1)
        report = quantizer.build_candidates(
            self.model, self.root / "const-candidates", "te",
            ["static_qdq_const_int8", "static_qdq_const_u8s8"],
            calibration_manifest=calibration, evaluation_manifest=held_out,
        )
        self.assertEqual(report["status"], "built", report)
        feeds = {"audio_signal": self.rng.normal(size=(1, 80, 23)).astype(np.float32),
                 "length": np.array([23], dtype=np.int64)}
        for candidate in report["candidates"]:
            self.assertEqual(candidate["recipe_config"]["nodes_to_exclude"], ["attention_qk"])
            self.assertEqual(candidate["recipe_config"]["extra_options"], {"MatMulConstBOnly": True})
            path = candidate["artifact"]["path"]
            graph = onnx.load(path)
            self.assertTrue(any(n.name == "attention_qk" and n.op_type == "MatMul" for n in graph.graph.node))
            self.assertFalse(any(n.op_type == "QuantizeLinear" and "attention_scores" in n.input for n in graph.graph.node))
            session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
            attention = session.run(["attention_scores"], feeds)[0]
            # Shared tensors may still carry quantization from neighboring weight
            # operators; the assertion above protects this MatMul's own output.
            self.assertEqual(attention.shape, (1, 23, 23))
            self.assertTrue(np.isfinite(attention).all())

    def test_unnamed_dynamic_matmul_fails_closed(self):
        model = onnx.load(self.model)
        model.graph.node.append(helper.make_node("MatMul", ["x", "y"], ["z"]))
        with self.assertRaisesRegex(ValueError, "no node name"):
            quantizer.nonconstant_matmul_exclusions(model)


if __name__ == "__main__":
    unittest.main()
