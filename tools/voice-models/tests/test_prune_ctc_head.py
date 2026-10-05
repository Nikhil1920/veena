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
from prune_ctc_head import prune_model
import fetch_float_models


class PruneCTCHeadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "model.onnx"
        self.indices = list(range(5120, 5376)) + [5632]
        self.rng = np.random.default_rng(53)
        weights = self.rng.normal(0, .3, (5633, 3, 1)).astype(np.float32)
        bias = self.rng.normal(0, .01, (5633,)).astype(np.float32)
        graph = helper.make_graph([
            helper.make_node("Conv", ["audio_signal", "head_weight", "head_bias"], ["head"], name="ctc_head"),
            helper.make_node("Transpose", ["head"], ["ntc"], perm=[0, 2, 1]),
            helper.make_node("LogSoftmax", ["ntc"], ["logprobs"], axis=-1),
        ], "head", [helper.make_tensor_value_info("audio_signal", TensorProto.FLOAT, [1, 3, "time"]),
                      helper.make_tensor_value_info("length", TensorProto.INT64, [1])],
            [helper.make_tensor_value_info("logprobs", TensorProto.FLOAT, [1, "time", 5633])],
            [numpy_helper.from_array(weights, "head_weight"), numpy_helper.from_array(bias, "head_bias")])
        onnx.save(helper.make_model(graph, opset_imports=[helper.make_opsetid("", 16)], ir_version=9), self.source)
        for name, value in {"decoder_config.json": {"num_classes": 5633, "blank_idx": 5632},
                            "language_masks.json": {"te": self.indices},
                            "vocab.json": {"te": [f"token-{i}" for i in range(256)] + ["<blk>"]}}.items():
            (self.root / name).write_text(json.dumps(value))

    def tearDown(self):
        self.temp.cleanup()

    def test_exact_weight_rows_and_masked_greedy_ctc_predictions_preserved(self):
        output = self.root / "local"
        source_bytes = self.source.read_bytes()
        report = prune_model(self.source, output, "te")
        self.assertEqual(self.source.read_bytes(), source_bytes)
        self.assertEqual(report["output_num_classes"], 257)
        self.assertFalse(report["release_ready"])
        original = onnx.load(self.source)
        reduced = onnx.load(output / "model.onnx")
        for full_tensor, local_tensor in zip(original.graph.initializer, reduced.graph.initializer):
            np.testing.assert_array_equal(numpy_helper.to_array(local_tensor), numpy_helper.to_array(full_tensor)[self.indices])
        feed = {"audio_signal": self.rng.normal(size=(1, 3, 19)).astype(np.float32),
                "length": np.array([19], dtype=np.int64)}
        full_session = ort.InferenceSession(str(self.source), providers=["CPUExecutionProvider"])
        local_session = ort.InferenceSession(str(output / "model.onnx"), providers=["CPUExecutionProvider"])
        full = full_session.run(None, feed)[0][0][:, self.indices]
        local = local_session.run(None, feed)[0][0]
        np.testing.assert_array_equal(full.argmax(axis=-1), local.argmax(axis=-1))
        renormalized = full - np.log(np.exp(full).sum(axis=-1, keepdims=True))
        np.testing.assert_allclose(local, renormalized, rtol=1e-5, atol=1e-5)
        config = json.loads((output / "decoder_config.json").read_text())
        self.assertEqual(config, {"num_classes": 257, "blank_idx": 256})

    def test_output_directory_is_never_overwritten(self):
        with self.assertRaises(FileExistsError):
            prune_model(self.source, self.root, "te")

    def test_invalid_mapping_and_source_hash_are_rejected_before_writes(self):
        with self.assertRaisesRegex(ValueError, "SHA256"):
            prune_model(self.source, self.root / "bad", "te", expected_sha256="0" * 64)
        (self.root / "language_masks.json").write_text(json.dumps({"te": list(range(257))}))
        with self.assertRaisesRegex(ValueError, "global blank"):
            prune_model(self.source, self.root / "bad", "te")
        self.assertFalse((self.root / "bad").exists())

    def test_unknown_head_topology_is_rejected(self):
        model = onnx.load(self.source)
        model.graph.node[-1].op_type = "Softmax"
        onnx.save(model, self.source)
        with self.assertRaisesRegex(ValueError, "terminal LogSoftmax"):
            prune_model(self.source, self.root / "bad", "te")

    def test_assamese_fetch_fallback_emits_own_verified_global_decoder(self):
        vocabulary = [f"own-token-{i}" for i in range(5632)]
        entry = {"code": "as", "community_fp32": {"url": "unused", "size_bytes": 35, "sha256": "0" * 64},
                 "community_fp32_assets": {"vocab.json": {"url": "unused"}},
                 "community_fp32_decoder": {"num_classes": 5633, "blank_idx": 5632,
                     "indices": list(range(256)) + [5632], "blank_token": "<blk>"}}

        def download(_asset, destination):
            destination.write_bytes(b"verified by mocked downloader")
            return destination

        def asset(_asset, destination):
            destination.write_text(json.dumps(vocabulary))

        with patch.object(fetch_float_models, "download_verified", side_effect=download), \
                patch.object(fetch_float_models, "small_asset", side_effect=asset):
            result = fetch_float_models.fetch(entry, self.root / "fetched")
        self.assertEqual(result["status"], "weights_and_decoder_verified")
        directory = self.root / "fetched/as"
        self.assertEqual(json.loads((directory / "vocab.json").read_text())["as"], vocabulary[:256] + ["<blk>"])
        self.assertEqual(json.loads((directory / "language_masks.json").read_text())["as"], list(range(256)) + [5632])


if __name__ == "__main__":
    unittest.main()
