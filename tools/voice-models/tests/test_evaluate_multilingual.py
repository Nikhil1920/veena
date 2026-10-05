"""Focused correctness tests, including isolated real ONNX Runtime evaluation."""
from __future__ import annotations

import importlib.util
from dataclasses import asdict
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import evaluate_multilingual as evaluation


def write_assets(directory: Path, blank_id: int = 5632) -> None:
    nonblank = [index for index in range(evaluation.NUM_CLASSES) if index != blank_id]
    masks, vocab = {}, {}
    for index, language in enumerate(evaluation.LANGUAGES):
        enabled = set(nonblank[index * 256:(index + 1) * 256] + [blank_id])
        masks[language] = [position in enabled for position in range(evaluation.NUM_CLASSES)]
        vocab[language] = ["▁word", "x"] + [f"token{i}" for i in range(254)] + ["|"]
    (directory / "vocab.json").write_text(json.dumps(vocab), encoding="utf-8")
    (directory / "language_masks.json").write_text(json.dumps(masks), encoding="utf-8")
    (directory / "decoder_config.json").write_text(json.dumps({"blank_id": blank_id, "num_classes": 5633}))


def valid_report(sample_count=20) -> dict:
    metrics = {"sample_count": sample_count, "error_count": 0, "wer": .1, "cer": .05}
    return {"status": "ok", "corpus_sha256": "same-corpus", "model_size_bytes": 100,
            "load_time_seconds": .1, "peak_rss_bytes": 1000,
            "measurement": {"threads": 1}, "coverage": {"all_22_languages": True},
            "aggregate": {"error_count": 0, "latency_p50_seconds": .5,
                          "latency_p95_seconds": 1., "rtf": .1},
            "per_language": {language: dict(metrics) for language in evaluation.LANGUAGES}}


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.feature = self.root / "sample.npy"
        np.save(self.feature, np.zeros((1, 80, 4), dtype=np.float32))
        self.manifest = self.root / "corpus.jsonl"
        self.row = {"language": "te", "text": " మానవ  వచనం ",
                    "features": "sample.npy", "duration_seconds": 1.5}

    def write(self, rows):
        self.manifest.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows), encoding="utf-8")

    def test_all_22_required_and_partial_is_explicit(self):
        self.write([self.row])
        with self.assertRaisesRegex(ValueError, "requires all 22"):
            evaluation.load_manifest(self.manifest)
        sample = evaluation.load_manifest(self.manifest, allow_partial=True)[0]
        self.assertEqual(sample.text, "మానవ వచనం")
        self.assertEqual(Path(sample.features), self.feature.resolve())
        self.assertEqual(len(sample.feature_sha256), 64)
        self.write([dict(self.row, language=language) for language in evaluation.LANGUAGES])
        self.assertEqual(len(evaluation.load_manifest(self.manifest)), 22)

    def test_reject_invalid_references_languages_and_durations(self):
        for field, value in [("text", "  "), ("text", None), ("language", "en"),
                             ("duration_seconds", 0), ("duration_seconds", -1),
                             ("duration_seconds", True), ("duration_seconds", float("nan")),
                             ("duration_seconds", float("inf")), ("features", None)]:
            with self.subTest(field=field, value=value):
                self.write([dict(self.row, **{field: value})])
                with self.assertRaises(ValueError):
                    evaluation.load_manifest(self.manifest, allow_partial=True)

    def test_empty_and_duplicate_ids_rejected(self):
        self.write([])
        with self.assertRaisesRegex(ValueError, "no samples"):
            evaluation.load_manifest(self.manifest, allow_partial=True)
        self.write([dict(self.row, id="duplicate"), dict(self.row, id="duplicate")])
        with self.assertRaisesRegex(ValueError, "unique"):
            evaluation.load_manifest(self.manifest, allow_partial=True)

    def test_numeric_shape_dtype_and_finite_validation(self):
        bad = [np.zeros((1, 80, 4), dtype=np.float64),
               np.zeros((80, 4), dtype=np.float32),
               np.zeros((1, 80, 0), dtype=np.float32),
               np.zeros((1, 79, 4), dtype=np.float32),
               np.full((1, 80, 4), np.nan, dtype=np.float32),
               np.full((1, 80, 4), np.inf, dtype=np.float32),
               np.array(1, dtype=np.float32)]
        for array in bad:
            with self.subTest(shape=array.shape, dtype=array.dtype):
                np.save(self.feature, array)
                with self.assertRaises(ValueError):
                    evaluation.load_features(self.feature)

    def test_npz_trims_integer_length_and_rejects_invalid_lengths(self):
        path = self.root / "sample.npz"
        array = np.zeros((1, 80, 4), dtype=np.float32)
        np.savez(path, features=array, length=np.array([2], dtype=np.int64))
        features, length = evaluation.load_features(path)
        self.assertEqual(features.shape, (1, 80, 2))
        self.assertEqual(length.tolist(), [2])
        self.assertTrue(features.flags.c_contiguous)
        for invalid in (0, 5, -1, 2.0, True, [2, 3]):
            np.savez(path, features=array, length=np.asarray(invalid))
            with self.subTest(length=invalid), self.assertRaises(ValueError):
                evaluation.load_features(path)
        np.savez(path, features=array)
        with self.assertRaisesRegex(ValueError, "features and length"):
            evaluation.load_features(path)

    def test_fingerprint_is_content_sensitive(self):
        self.write([self.row])
        before = evaluation.corpus_fingerprint(evaluation.load_manifest(self.manifest, allow_partial=True))
        array = np.zeros((1, 80, 4), dtype=np.float32)
        array[0, 0, 0] = 1
        np.save(self.feature, array)
        after = evaluation.corpus_fingerprint(evaluation.load_manifest(self.manifest, allow_partial=True))
        self.assertNotEqual(before, after)

    def test_effective_npz_length_changes_feature_and_corpus_fingerprints(self):
        path = self.root / "sample.npz"
        array = np.zeros((1, 80, 4), dtype=np.float32)
        self.write([dict(self.row, features=path.name)])
        np.savez(path, features=array, length=np.array([4], dtype=np.int64))
        full = evaluation.load_manifest(self.manifest, allow_partial=True)
        self.assertEqual(full[0].feature_sha256, evaluation._feature_digest(array))
        np.savez(path, features=array, length=np.array([2], dtype=np.int64))
        shortened = evaluation.load_manifest(self.manifest, allow_partial=True)
        self.assertNotEqual(full[0].feature_sha256, shortened[0].feature_sha256)
        self.assertNotEqual(evaluation.corpus_fingerprint(full), evaluation.corpus_fingerprint(shortened))


class DecoderTests(unittest.TestCase):
    def test_local_257_class_language_head(self):
        (self.root / "decoder_config.json").write_text(json.dumps({"num_classes": 257, "blank_idx": 256}))
        (self.root / "language_masks.json").write_text(json.dumps({"te": list(range(257))}))
        (self.root / "vocab.json").write_text(json.dumps({"te": ["▁word", "x"] + [""] * 254 + ["<blank>"]}))
        decoder = evaluation.CTCDecoder(self.root)
        logits = np.full((1, 4, 257), -10., dtype=np.float32)
        for step, token in enumerate([0, 0, 256, 1]):
            logits[0, step, token] = 2
        self.assertEqual(decoder.decode(logits, "te"), "wordx")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        write_assets(self.root)

    def test_binary_masks_shared_blank_and_ctc_repeat_collapse(self):
        decoder = evaluation.CTCDecoder(self.root)
        logits = np.full((1, 5, 5633), -10., dtype=np.float32)
        for step, token in enumerate([0, 0, 5632, 0, 1]):
            logits[0, step, token] = 2
        logits[0, :, 400] = 100  # Foreign-language maxima must not leak through.
        self.assertEqual(decoder.decode(logits, "as"), "word wordx")

    def test_configurable_global_blank_is_not_local_vocab_index(self):
        write_assets(self.root, blank_id=17)
        decoder = evaluation.CTCDecoder(self.root)
        logits = np.full((1, 3, 5633), -10., dtype=np.float32)
        for step, token in enumerate([0, 17, 0]):
            logits[0, step, token] = 2
        self.assertEqual(decoder.decode(logits, "as"), "word word")

    def test_reject_index_lists_as_masks_and_invalid_logits(self):
        decoder = evaluation.CTCDecoder(self.root)
        with self.assertRaisesRegex(ValueError, "shape"):
            decoder.decode(np.zeros((1, 5633), dtype=np.float32), "as")
        with self.assertRaisesRegex(ValueError, "nonfinite"):
            decoder.decode(np.full((1, 1, 5633), np.nan), "as")
        path = self.root / "language_masks.json"
        masks = json.loads(path.read_text())
        masks["as"] = [1, 0] + list(range(2, 256)) + [5632]
        path.write_text(json.dumps(masks))
        with self.assertRaisesRegex(ValueError, "sorted, unique"):
            evaluation.CTCDecoder(self.root)

    def test_single_language_index_pack_and_baked_negative_infinity_mask(self):
        vocab = json.loads((self.root / "vocab.json").read_text())
        vocab["as"][0] = "<unk>"
        vocab["as"][1] = "▁word"
        (self.root / "vocab.json").write_text(json.dumps({"as": vocab["as"]}))
        (self.root / "language_masks.json").write_text(json.dumps({"as": list(range(256)) + [5632]}))
        decoder = evaluation.CTCDecoder(self.root)
        logits = np.full((1, 3, 5633), -np.inf, dtype=np.float32)
        logits[0, 0, 0] = 0
        logits[0, 1, 5632] = 0
        logits[0, 2, 1] = 0
        self.assertEqual(decoder.decode(logits, "as"), "word")
        logits[0, 0, 300] = np.inf
        with self.assertRaisesRegex(ValueError, "nonfinite"):
            decoder.decode(logits, "as")

    def test_unicode_normalization_and_error_distance(self):
        self.assertEqual(evaluation.normalize_text(" e\u0301  x\n"), "é x")
        self.assertEqual(evaluation.edit_distance(["one", "two"], ["one", "three", "four"]), 2)
        self.assertEqual(evaluation.edit_distance("", "abc"), 3)


class ComparisonTests(unittest.TestCase):
    def test_quality_gates_every_language_and_does_not_claim_release(self):
        baseline, candidate = valid_report(), valid_report()
        candidate["model_size_bytes"] = 25
        comparison = evaluation.compare_reports(baseline, candidate)
        self.assertTrue(comparison["quality_gate_passed"])
        self.assertFalse(comparison["release_ready"])
        self.assertEqual(comparison["performance_ratios"]["model_size_bytes_ratio"], .25)
        self.assertEqual(comparison["artifact_binding"], {"baseline": "unbound", "candidate": "unbound"})
        candidate["per_language"]["ur"]["wer"] += .011
        comparison = evaluation.compare_reports(baseline, candidate)
        self.assertFalse(comparison["quality_gate_passed"])
        self.assertFalse(comparison["per_language"]["ur"]["passed"])

    def test_thresholds_are_absolute_percentage_point_ratios(self):
        baseline, candidate = valid_report(), valid_report()
        candidate["per_language"]["te"]["wer"] = .11
        self.assertTrue(evaluation.compare_reports(baseline, candidate)["quality_gate_passed"])
        candidate["per_language"]["te"]["cer"] = .0601
        self.assertFalse(evaluation.compare_reports(baseline, candidate)["quality_gate_passed"])

    def test_errors_partial_coverage_corpus_and_settings_mismatch_cannot_win(self):
        baseline = valid_report()
        variants = []
        for change in ("error", "partial", "corpus", "settings", "nan", "missing", "too_few", "binding_failed"):
            candidate = valid_report()
            if change == "error": candidate["status"] = "error"
            if change == "partial": candidate["coverage"]["all_22_languages"] = False
            if change == "corpus": candidate["corpus_sha256"] = "other"
            if change == "settings": candidate["measurement"]["threads"] = 2
            if change == "nan": candidate["per_language"]["te"]["wer"] = float("nan")
            if change == "missing": del candidate["per_language"]["te"]
            if change == "too_few": candidate["per_language"]["te"]["sample_count"] = 1
            if change == "binding_failed": candidate["artifact_binding"] = {"status": "failed"}
            variants.append((change, candidate))
        for change, candidate in variants:
            with self.subTest(change=change):
                self.assertFalse(evaluation.compare_reports(baseline, candidate)["quality_gate_passed"])
        for threshold in (float("nan"), float("inf"), -.01, True):
            with self.subTest(threshold=threshold), self.assertRaises(ValueError):
                evaluation.compare_reports(baseline, baseline, max_wer_regression=threshold)

    def test_summarization_excludes_failed_samples_and_weights_by_reference(self):
        rows = [{"reference_words": 1, "word_errors": 1, "reference_characters": 2,
                 "character_errors": 1, "duration_seconds": 1,
                 "inference_seconds": [.1, .3], "inference_median_seconds": .2},
                {"reference_words": 9, "word_errors": 0, "reference_characters": 18,
                 "character_errors": 0, "duration_seconds": 3,
                 "inference_seconds": [.2, .4], "inference_median_seconds": .3},
                {"error": "inference failed", "duration_seconds": 100}]
        summary = evaluation._summarize(rows)
        self.assertEqual(summary["sample_count"], 2)
        self.assertEqual(summary["attempted_samples"], 3)
        self.assertEqual(summary["error_count"], 1)
        self.assertAlmostEqual(summary["wer"], .1)
        self.assertAlmostEqual(summary["cer"], .05)
        self.assertAlmostEqual(summary["rtf"], .125)
        self.assertAlmostEqual(summary["latency_p50_seconds"], .25)
        self.assertAlmostEqual(summary["latency_p95_seconds"], .385)


@unittest.skipUnless(importlib.util.find_spec("onnx") and importlib.util.find_spec("onnxruntime"), "ONNX dependencies unavailable")
class RuntimeIntegrationTests(unittest.TestCase):
    def setUp(self):
        import onnx
        from onnx import TensorProto, helper, numpy_helper

        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        write_assets(self.root)
        logits = np.full((1, 4, 5633), -5., dtype=np.float32)
        for index in range(22):
            logits[0, 0, index * 256] = 5
            logits[0, 2, index * 256] = 5
        logits[0, 1, 5632] = 5
        logits[0, 3, 5632] = 5
        graph = helper.make_graph([helper.make_node("Identity", ["constant_logits"], ["logprobs"])], "toy_ctc",
                                  [helper.make_tensor_value_info("features", TensorProto.FLOAT, [1, 80, "T"]),
                                   helper.make_tensor_value_info("length", TensorProto.INT64, [1])],
                                  [helper.make_tensor_value_info("logprobs", TensorProto.FLOAT, [1, 4, 5633])],
                                  [numpy_helper.from_array(logits, name="constant_logits")])
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], ir_version=10)
        self.model = self.root / "model.onnx"
        onnx.save_model(model, self.model, save_as_external_data=True,
                        all_tensors_to_one_file=True, location="weights.data", size_threshold=0)
        np.save(self.root / "sample.npy", np.zeros((1, 80, 8), dtype=np.float32))
        self.manifest = self.root / "corpus.jsonl"
        self.manifest.write_text("\n".join(json.dumps({"language": language, "text": "word word",
                                                      "features": "sample.npy", "duration_seconds": 1})
                                                  for language in evaluation.LANGUAGES))

    def test_real_subprocess_metrics_and_external_tensor_size(self):
        report = evaluation.evaluate_model(self.model, self.root, self.manifest,
                                            warmup=1, repeats=2, min_samples_per_language=1,
                                            output=self.root / "report.json")
        self.assertEqual(report["status"], "ok", report.get("error"))
        self.assertEqual(report["aggregate"]["sample_count"], 22)
        self.assertEqual(report["aggregate"]["inference_measurements"], 44)
        self.assertEqual(report["aggregate"]["wer"], 0)
        self.assertEqual(report["aggregate"]["cer"], 0)
        self.assertTrue(report["coverage"]["all_22_languages"])
        self.assertGreater(report["peak_rss_bytes"], 0)
        self.assertGreater(report["load_time_seconds"], 0)
        self.assertEqual(report["model_size_bytes"], self.model.stat().st_size + (self.root / "weights.data").stat().st_size)
        self.assertFalse(report["quality_gate_passed"])
        self.assertEqual(report["model_artifact"], evaluation.model_artifact_record(self.model))
        self.assertEqual(report["decoder_assets"], evaluation.decoder_asset_records(self.root))
        self.assertEqual(report["artifact_binding"]["status"], "verified")
        self.assertTrue(report["artifact_binding"]["checked_before_inference"])
        self.assertTrue(report["artifact_binding"]["checked_after_inference"])
        candidate = evaluation.evaluate_model(self.model, self.root, self.manifest,
                                               warmup=1, repeats=2, baseline_report=report,
                                               min_samples_per_language=1)
        self.assertTrue(candidate["quality_gate_passed"])
        self.assertFalse(candidate["release_ready"])

    def worker_spec(self):
        return {"model": str(self.model), "model_dir": str(self.root), "threads": 1,
                "warmup": 0, "repeats": 1,
                "samples": [asdict(sample) for sample in evaluation.load_manifest(self.manifest)][:1]}

    def test_worker_binds_artifacts_without_new_parent_spec_fields(self):
        result = evaluation._worker_evaluate(self.worker_spec())
        self.assertEqual(result["artifact_binding"]["status"], "verified")
        self.assertEqual(result["model_artifact"], evaluation.model_artifact_record(self.model))
        self.assertEqual(result["decoder_assets"], evaluation.decoder_asset_records(self.root))

    def test_model_digest_matches_builder_contract_and_includes_external_weights(self):
        from quantize_language_model import artifact_record
        result = evaluation.model_artifact_record(self.model)
        expected = artifact_record(self.model)
        for key in result:
            self.assertEqual(result[key], expected[key])
        weights = self.root / "weights.data"
        contents = bytearray(weights.read_bytes())
        contents[0] ^= 1
        weights.write_bytes(contents)
        changed = evaluation.model_artifact_record(self.model)
        self.assertEqual(result["sha256"], changed["sha256"])
        self.assertEqual(result["size_bytes"], changed["size_bytes"])
        self.assertNotEqual(result["artifact_sha256"], changed["artifact_sha256"])

    def test_worker_rejects_parent_artifact_mismatch_before_session_load(self):
        spec = self.worker_spec()
        spec["model_artifact"] = evaluation.model_artifact_record(self.model)
        spec["decoder_assets"] = evaluation.decoder_asset_records(self.root)
        (self.root / "vocab.json").write_text((self.root / "vocab.json").read_text() + " ")
        with mock.patch("onnxruntime.InferenceSession") as constructor:
            with self.assertRaisesRegex(ValueError, "decoder_assets changed before"):
                evaluation._worker_evaluate(spec)
        constructor.assert_not_called()

    def test_worker_rejects_artifact_mutation_during_inference(self):
        import onnxruntime as ort
        real_constructor = ort.InferenceSession
        for filename in ("weights.data", "vocab.json", "decoder_config.json"):
            path = self.root / filename
            original = path.read_bytes()
            with self.subTest(filename=filename):
                def construct(*args, **kwargs):
                    session = real_constructor(*args, **kwargs)
                    wrapped = mock.Mock(wraps=session)

                    def run(*run_args, **run_kwargs):
                        result = session.run(*run_args, **run_kwargs)
                        path.write_bytes(original + b" ")
                        return result

                    wrapped.run.side_effect = run
                    return wrapped

                with mock.patch("onnxruntime.InferenceSession", side_effect=construct):
                    with self.assertRaisesRegex(ValueError, "changed during evaluation"):
                        evaluation._worker_evaluate(self.worker_spec())
                path.write_bytes(original)

    def test_optional_decoder_config_absence_is_bound(self):
        (self.root / "decoder_config.json").unlink()
        result = evaluation._worker_evaluate(self.worker_spec())
        self.assertIsNone(result["decoder_assets"]["decoder_config.json"])
        self.assertEqual(result["artifact_binding"]["status"], "verified")
        (self.root / "decoder_config.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "decoder assets changed"):
            evaluation._verify_artifacts(self.worker_spec(), result["model_artifact"], result["decoder_assets"])

    def test_worker_rejects_changed_effective_length(self):
        array = np.zeros((1, 80, 8), dtype=np.float32)
        feature_path = self.root / "sample.npz"
        np.savez(feature_path, features=array, length=np.array([8], dtype=np.int64))
        self.manifest.write_text(self.manifest.read_text().replace("sample.npy", "sample.npz"))
        spec = self.worker_spec()
        np.savez(feature_path, features=array, length=np.array([4], dtype=np.int64))
        report = evaluation._worker_evaluate(spec)
        self.assertEqual(report["aggregate"]["sample_count"], 0)
        self.assertEqual(report["aggregate"]["error_count"], 1)
        self.assertIn("features changed after manifest validation", report["samples"][0]["error"])

    def test_required_single_language_pack_can_pass_its_corpus_quality_gate(self):
        for name in ("vocab.json", "language_masks.json"):
            path = self.root / name
            source = json.loads(path.read_text())
            path.write_text(json.dumps({"as": source["as"]}))
        self.manifest.write_text(json.dumps({"language": "as", "text": "word word",
                                            "features": "sample.npy", "duration_seconds": 1}))
        baseline = evaluation.evaluate_model(self.model, self.root, self.manifest,
                                               required_languages=["as"], warmup=0,
                                               min_samples_per_language=1)
        self.assertEqual(baseline["required_languages"], ["as"])
        self.assertFalse(baseline["all_22_covered"])
        candidate = evaluation.evaluate_model(self.model, self.root, self.manifest,
                                                required_languages=["as"], warmup=0,
                                                min_samples_per_language=1,
                                                baseline_report=baseline)
        self.assertTrue(candidate["quality_gate_passed"])
        self.assertFalse(candidate["release_ready"])
        partial = evaluation.evaluate_model(self.model, self.root, self.manifest,
                                              required_languages=["as", "te"], allow_partial=True,
                                              warmup=0, min_samples_per_language=1,
                                              baseline_report=baseline)
        self.assertFalse(partial["quality_gate_passed"])

    def test_worker_failure_is_reported_and_cannot_pass(self):
        (self.root / "vocab.json").unlink()
        report = evaluation.evaluate_model(self.model, self.root, self.manifest, warmup=0)
        self.assertEqual(report["status"], "error")
        self.assertEqual(report["aggregate"]["sample_count"], 0)
        self.assertEqual(report["aggregate"]["error_count"], 22)
        self.assertIsNone(report["aggregate"]["wer"])
        self.assertFalse(report["quality_gate_passed"])
        self.assertFalse(report["release_ready"])
        self.assertEqual(report["artifact_binding"]["status"], "failed")


if __name__ == "__main__":
    unittest.main()
