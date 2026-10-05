"""Evaluate multilingual CTC ONNX models against human-reference feature corpora.

Manifest JSONL rows contain ``language``, nonempty human-reference ``text``,
``features`` (a path relative to the manifest), and positive ``duration_seconds``.
Features are float32 [1, 80, T] .npy arrays, or .npz files containing ``features``
and an integer ``length``. Arrays are validated and trimmed to their valid length.

Example::

    python evaluate_multilingual.py --model models/model.onnx \
        --model-dir models/indicconformer-multi --manifest held-out.jsonl \
        --baseline-report baseline.json --output candidate-report.json

Metrics use NFC Unicode normalization and collapsed whitespace, retaining case
and punctuation. CER includes normalized spaces. Latencies measure session.run
only; RTF divides median inference time per utterance by audio duration. Reports
are corpus measurements, not claims of general accuracy or statistical confidence.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import tempfile
import time
import unicodedata
from typing import Any, Sequence

import numpy as np

LANGUAGES = (
    "as", "bn", "brx", "doi", "gu", "hi", "kn", "kok", "ks", "mai", "ml",
    "mni", "mr", "ne", "or", "pa", "sa", "sat", "sd", "ta", "te", "ur",
)
NUM_CLASSES = 5633
DEFAULT_BLANK_ID = 5632
REPORT_VERSION = 1
DECODER_ASSET_NAMES = ("decoder_config.json", "language_masks.json", "vocab.json")


@dataclass(frozen=True)
class EvaluationSample:
    language: str
    text: str
    features: str
    duration_seconds: float
    sample_id: str
    feature_sha256: str


def normalize_text(text: str) -> str:
    return " ".join(unicodedata.normalize("NFC", text).split())


def edit_distance(reference: Sequence, hypothesis: Sequence) -> int:
    """Levenshtein distance with memory proportional to the shorter sequence."""
    if len(reference) < len(hypothesis):
        reference, hypothesis = hypothesis, reference
    previous = list(range(len(hypothesis) + 1))
    for i, left in enumerate(reference, 1):
        current = [i]
        for j, right in enumerate(hypothesis, 1):
            current.append(min(current[-1] + 1, previous[j] + 1,
                               previous[j - 1] + (left != right)))
        previous = current
    return previous[-1]


def load_features(path: str | Path) -> tuple[np.ndarray, np.ndarray]:
    """Load trusted numeric arrays without pickle and reject malformed features."""
    path = Path(path)
    if path.suffix.lower() == ".npy":
        features = np.load(path, allow_pickle=False)
        length = features.shape[-1] if features.ndim else 0
    elif path.suffix.lower() == ".npz":
        with np.load(path, allow_pickle=False) as archive:
            if "features" not in archive.files or "length" not in archive.files:
                raise ValueError(".npz must contain features and length")
            features = archive["features"]
            stored_length = archive["length"]
            if stored_length.shape not in [(), (1,)] or stored_length.dtype.kind not in "iu":
                raise ValueError("length must be an integer scalar or one-element vector")
            length = int(stored_length.reshape(-1)[0])
    else:
        raise ValueError("features must be a .npy or .npz file")
    if features.dtype != np.dtype(np.float32):
        raise ValueError("features must have float32 dtype")
    if features.ndim != 3 or features.shape[:2] != (1, 80) or features.shape[2] == 0:
        raise ValueError("features must have shape [1, 80, T] with T > 0")
    if not np.isfinite(features).all():
        raise ValueError("features contain NaN or infinity")
    if length <= 0 or length > features.shape[2]:
        raise ValueError("length must be in [1, T]")
    return np.ascontiguousarray(features[:, :, :length]), np.array([length], dtype=np.int64)


def _feature_digest(features: np.ndarray) -> str:
    digest = hashlib.sha256()
    digest.update(str(features.shape).encode("ascii"))
    digest.update(features.tobytes(order="C"))
    return digest.hexdigest()


def _required_languages(languages: Sequence[str] | None) -> tuple[str, ...]:
    selected = tuple(LANGUAGES if languages is None else languages)
    if (not selected or any(not isinstance(language, str) or language not in LANGUAGES for language in selected)
            or len(set(selected)) != len(selected)):
        raise ValueError("required_languages must be unique official language codes")
    return tuple(sorted(selected))


def load_manifest(path: str | Path, allow_partial: bool = False,
                  required_languages: Sequence[str] | None = None) -> list[EvaluationSample]:
    """Validate a corpus, including arrays; by default require all 22 languages."""
    required = _required_languages(required_languages)
    path = Path(path).resolve()
    samples: list[EvaluationSample] = []
    ids: set[str] = set()
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError("row must be a JSON object")
                language = row.get("language")
                if language not in required:
                    raise ValueError(f"unsupported or unrequested language: {language!r}")
                reference = row.get("text")
                if not isinstance(reference, str) or not normalize_text(reference):
                    raise ValueError("text must be a nonempty human reference")
                duration = row.get("duration_seconds")
                if isinstance(duration, bool) or not isinstance(duration, (int, float)):
                    raise ValueError("duration_seconds must be a positive number")
                if not math.isfinite(duration) or duration <= 0:
                    raise ValueError("duration_seconds must be finite and positive")
                feature_path = row.get("features")
                if not isinstance(feature_path, str) or not feature_path:
                    raise ValueError("features must name an array file")
                resolved = (path.parent / feature_path).resolve()
                features, _ = load_features(resolved)
                sample_id = row.get("id", f"line-{line_number}")
                if not isinstance(sample_id, str) or not sample_id.strip() or sample_id in ids:
                    raise ValueError("id must be a nonempty unique string")
                ids.add(sample_id)
                samples.append(EvaluationSample(language, normalize_text(reference),
                                                str(resolved), float(duration), sample_id,
                                                _feature_digest(features)))
            except (ValueError, TypeError, OSError, KeyError) as exc:
                raise ValueError(f"{path}:{line_number}: {exc}") from exc
    if not samples:
        raise ValueError("evaluation manifest has no samples")
    missing = sorted(set(required) - {sample.language for sample in samples})
    if missing and not allow_partial:
        raise ValueError(f"evaluation requires {'all 22 languages' if len(required) == 22 else ', '.join(required)}; missing {', '.join(missing)}; "
                         "use --allow-partial for development-only reports")
    return samples


def corpus_fingerprint(samples: Sequence[EvaluationSample]) -> str:
    rows = [{"language": sample.language, "text": sample.text,
             "feature_sha256": sample.feature_sha256,
             "duration_seconds": sample.duration_seconds} for sample in samples]
    # Order matters because it can affect cache and warmup behavior.
    return hashlib.sha256(json.dumps(rows, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


class CTCDecoder:
    """Decode binary masks or sorted per-language index lists with a global blank."""

    def __init__(self, model_dir: str | Path):
        root = Path(model_dir)
        self.vocab = json.loads((root / "vocab.json").read_text(encoding="utf-8"))
        masks = json.loads((root / "language_masks.json").read_text(encoding="utf-8"))
        config_path = root / "decoder_config.json"
        config = json.loads(config_path.read_text()) if config_path.exists() else {}
        if "blank_id" in config and "blank_idx" in config and config["blank_id"] != config["blank_idx"]:
            raise ValueError("conflicting blank_id and blank_idx")
        self.num_classes = config.get("num_classes", NUM_CLASSES)
        if isinstance(self.num_classes, bool) or not isinstance(self.num_classes, int) or self.num_classes < 2:
            raise ValueError("decoder num_classes must be an integer >= 2")
        self.blank_id = config.get("blank_id", config.get("blank_idx", self.num_classes - 1))
        if isinstance(self.blank_id, bool) or not isinstance(self.blank_id, int) or not 0 <= self.blank_id < self.num_classes:
            raise ValueError("decoder blank_id is outside the global vocabulary")
        self.indices: dict[str, np.ndarray] = {}
        self.tokens: dict[str, dict[int, str]] = {}
        if not isinstance(self.vocab, dict) or not isinstance(masks, dict) or not self.vocab:
            raise ValueError("vocab and masks must be nonempty language dictionaries")
        if set(self.vocab) != set(masks) or set(self.vocab) - set(LANGUAGES):
            raise ValueError("vocab and mask language keys must agree and be official codes")
        for language in self.vocab:
            mask = masks.get(language)
            vocab = self.vocab.get(language)
            if not isinstance(mask, list):
                raise ValueError(f"{language}: mask must be a binary array or token index list")
            if len(mask) == self.num_classes and all(isinstance(value, (bool, int)) and value in (0, 1) for value in mask):
                if any(not isinstance(value, (bool, int)) or value not in (0, 1) for value in mask):
                    raise ValueError(f"{language}: full language mask must be binary")
                ids = np.flatnonzero(np.asarray(mask, dtype=bool))
            else:
                if (not mask or any(isinstance(value, bool) or not isinstance(value, int) or not 0 <= value < self.num_classes for value in mask)
                        or mask != sorted(set(mask)) or mask[-1] != self.blank_id):
                    raise ValueError(f"{language}: token indices must be sorted, unique, in bounds, and end with blank")
                ids = np.asarray(mask, dtype=np.int64)
            nonblank = ids[ids != self.blank_id]
            if not len(nonblank) or self.blank_id not in ids:
                raise ValueError(f"{language}: mask must enable tokens and the blank")
            if not isinstance(vocab, list) or len(vocab) not in (len(nonblank), len(ids)) or not all(isinstance(token, str) for token in vocab):
                raise ValueError(f"{language}: vocabulary must match the active tokens, optionally followed by blank")
            self.indices[language] = ids
            self.tokens[language] = dict(zip(nonblank.tolist(), vocab[:len(nonblank)]))

    def decode(self, logits: np.ndarray, language: str) -> str:
        if language not in self.indices:
            raise ValueError(f"unsupported language: {language}")
        if logits.ndim != 3 or logits.shape[0] != 1 or logits.shape[1] == 0 or logits.shape[2] != self.num_classes:
            raise ValueError(f"logits must have shape [1, T, {self.num_classes}]")
        allowed = self.indices[language]
        selected = logits[0][:, allowed]
        if np.isnan(logits).any() or np.isposinf(logits).any() or not np.isfinite(selected).any(axis=-1).all():
            raise ValueError("model returned invalid nonfinite logits")
        predicted = allowed[np.argmax(selected, axis=-1)]
        previous = None
        tokens = []
        for value in predicted.tolist():
            if value != previous and value != self.blank_id:
                token = self.tokens[language][value]
                if token and token != "<unk>":
                    tokens.append(token)
            previous = value
        return normalize_text("".join(tokens).replace("▁", " "))


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def model_artifact_record(model_path: str | Path) -> dict[str, Any]:
    """Hash the ONNX protobuf and every referenced external tensor file.

    The canonical files digest matches quantize_language_model.artifact_record;
    this implementation is independent because quantization imports this module.
    """
    import onnx
    from onnx.external_data_helper import _get_all_tensors

    model_path = Path(model_path).resolve()
    model = onnx.load_model(str(model_path), load_external_data=False)
    assets = {model_path: model_path.name}
    for tensor in _get_all_tensors(model):
        if tensor.data_location == onnx.TensorProto.EXTERNAL:
            locations = [entry.value for entry in tensor.external_data if entry.key == "location"]
            if len(locations) != 1 or not locations[0]:
                raise ValueError("external ONNX tensor has no unique location")
            assets[(model_path.parent / locations[0]).resolve()] = locations[0]
    del model
    files = [{"path": name, "size_bytes": path.stat().st_size, "sha256": _sha256_file(path)}
             for path, name in sorted(assets.items(), key=lambda pair: pair[1])]
    return {"sha256": next(row["sha256"] for row in files if row["path"] == model_path.name),
            "files": files,
            "artifact_sha256": hashlib.sha256(json.dumps(files, ensure_ascii=False, sort_keys=True,
                                                          separators=(",", ":")).encode("utf-8")).hexdigest(),
            "size_bytes": sum(row["size_bytes"] for row in files)}


def model_artifact_size(model_path: str | Path) -> int:
    """Count the ONNX protobuf and referenced external tensor files once."""
    return model_artifact_record(model_path)["size_bytes"]


def decoder_asset_records(model_dir: str | Path) -> dict[str, Any]:
    """Bind decoder bytes, including absence of the optional configuration file."""
    root = Path(model_dir)
    records = {}
    for name in DECODER_ASSET_NAMES:
        path = root / name
        if name == "decoder_config.json" and not path.exists():
            records[name] = None
        else:
            records[name] = {"size_bytes": path.stat().st_size, "sha256": _sha256_file(path)}
    return records


def _verify_artifacts(spec: dict[str, Any], model_record: dict[str, Any],
                      decoder_records: dict[str, Any]) -> None:
    if model_artifact_record(spec["model"]) != model_record:
        raise ValueError("model artifact changed during evaluation")
    if decoder_asset_records(spec["model_dir"]) != decoder_records:
        raise ValueError("decoder assets changed during evaluation")


def _percentile(values: Sequence[float], quantile: float) -> float | None:
    return float(np.percentile(values, quantile)) if values else None


def _summarize(records: list[dict[str, Any]]) -> dict[str, Any]:
    success = [row for row in records if "error" not in row]
    latency = [duration for row in success for duration in row["inference_seconds"]]
    words = sum(row["reference_words"] for row in success)
    characters = sum(row["reference_characters"] for row in success)
    duration = sum(row["duration_seconds"] for row in success)
    total_inference = sum(row["inference_median_seconds"] for row in success)
    return {
        "attempted_samples": len(records), "sample_count": len(success),
        "error_count": len(records) - len(success),
        "word_errors": sum(row["word_errors"] for row in success),
        "reference_words": words,
        "wer": sum(row["word_errors"] for row in success) / words if words else None,
        "character_errors": sum(row["character_errors"] for row in success),
        "reference_characters": characters,
        "cer": sum(row["character_errors"] for row in success) / characters if characters else None,
        "duration_seconds": duration, "inference_measurements": len(latency),
        "latency_p50_seconds": _percentile(latency, 50),
        "latency_p95_seconds": _percentile(latency, 95),
        "inference_seconds": total_inference,
        "rtf": total_inference / duration if duration else None,
    }


def _worker_evaluate(spec: dict[str, Any]) -> dict[str, Any]:
    import resource
    import onnxruntime as ort

    # This worker always computes its own binding, including when an older
    # already-running parent invokes it without expected artifact records.
    model_record = model_artifact_record(spec["model"])
    decoder_records = decoder_asset_records(spec["model_dir"])
    for key, actual in (("model_artifact", model_record), ("decoder_assets", decoder_records)):
        if key in spec and spec[key] != actual:
            raise ValueError(f"{key} changed before evaluation worker loaded it")
    decoder = CTCDecoder(spec["model_dir"])
    options = ort.SessionOptions()
    options.intra_op_num_threads = spec["threads"]
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    started = time.perf_counter()
    session = ort.InferenceSession(spec["model"], sess_options=options,
                                   providers=["CPUExecutionProvider"])
    load_time = time.perf_counter() - started
    inputs = session.get_inputs()
    feature_inputs = [value for value in inputs if value.type == "tensor(float)" and len(value.shape) == 3]
    length_inputs = [value for value in inputs if value.type in ("tensor(int64)", "tensor(int32)") and len(value.shape) == 1]
    if len(inputs) != 2 or len(feature_inputs) != 1 or len(length_inputs) != 1:
        raise ValueError("expected one float32 [1,80,T] input and one integer [1] length input")
    outputs = session.get_outputs()
    candidates = [i for i, value in enumerate(outputs) if len(value.shape) == 3 and value.shape[-1] == decoder.num_classes]
    if len(candidates) != 1:
        candidates = [i for i, value in enumerate(outputs) if value.name in ("logprobs", "log_probs", "logits")]
    if len(candidates) != 1:
        raise ValueError("cannot uniquely identify model CTC logits output")
    logits_index = candidates[0]
    records = []
    for sample in spec["samples"]:
        record = {"id": sample["sample_id"], "language": sample["language"],
                  "reference": sample["text"], "duration_seconds": sample["duration_seconds"]}
        try:
            features, lengths = load_features(sample["features"])
            if _feature_digest(features) != sample["feature_sha256"]:
                raise ValueError("features changed after manifest validation")
            if length_inputs[0].type == "tensor(int32)":
                lengths = lengths.astype(np.int32)
            feeds = {feature_inputs[0].name: features, length_inputs[0].name: lengths}
            for _ in range(spec["warmup"]):
                session.run(None, feeds)
            times = []
            for _ in range(spec["repeats"]):
                started = time.perf_counter()
                result = session.run(None, feeds)
                times.append(time.perf_counter() - started)
            predicted = decoder.decode(np.asarray(result[logits_index]), sample["language"])
            reference = sample["text"]
            record.update({"prediction": predicted, "reference_words": len(reference.split()),
                           "word_errors": edit_distance(reference.split(), predicted.split()),
                           "reference_characters": len(reference),
                           "character_errors": edit_distance(reference, predicted),
                           "inference_seconds": times,
                           "inference_median_seconds": statistics.median(times)})
        except Exception as exc:
            record["error"] = f"{type(exc).__name__}: {exc}"
        records.append(record)
    _verify_artifacts(spec, model_record, decoder_records)
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return {
        "model_artifact": model_record, "model_size_bytes": model_record["size_bytes"],
        "decoder_assets": decoder_records,
        "artifact_binding": {"status": "verified", "method": "sha256",
                             "checked_before_inference": True, "checked_after_inference": True,
                             "scope": "worker model protobuf, external weights, and decoder assets"},
        "load_time_seconds": load_time,
        "peak_rss_bytes": int(rss if sys.platform == "darwin" else rss * 1024),
        "runtime": {"name": "onnxruntime", "version": ort.__version__,
                    "provider": "CPUExecutionProvider", "platform": platform.platform(),
                    "machine": platform.machine(), "processor": platform.processor()},
        "samples": records,
        "aggregate": _summarize(records),
        "per_language": {language: _summarize([row for row in records if row["language"] == language])
                         for language in LANGUAGES},
    }


def _finite_metric(value: Any) -> bool:
    return isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0


def compare_reports(baseline: dict[str, Any], candidate: dict[str, Any], *,
                    max_wer_regression: float = .01,
                    max_cer_regression: float = .01,
                    min_samples_per_language: int = 20) -> dict[str, Any]:
    """Gate each requested language; absolute ratio thresholds (.01 = 1 point)."""
    if not _finite_metric(max_wer_regression) or not _finite_metric(max_cer_regression):
        raise ValueError("regression thresholds must be finite, nonnegative ratios")
    if isinstance(min_samples_per_language, bool) or not isinstance(min_samples_per_language, int) or min_samples_per_language < 1:
        raise ValueError("min_samples_per_language must be a positive integer")
    required = _required_languages(candidate.get("required_languages"))
    reasons = []
    if required != _required_languages(baseline.get("required_languages")):
        reasons.append("baseline and candidate required languages differ")
    for name, report in (("baseline", baseline), ("candidate", candidate)):
        if report.get("status") != "ok" or report.get("aggregate", {}).get("error_count", 1) != 0:
            reasons.append(f"{name} has evaluation errors")
        if report.get("artifact_binding", {}).get("status") == "failed":
            reasons.append(f"{name} artifact verification failed")
        covered = report.get("coverage", {}).get("languages")
        if covered is None:
            covered = list(LANGUAGES) if report.get("coverage", {}).get("all_22_languages", False) else []
        if not set(required).issubset(covered):
            reasons.append(f"{name} does not cover every required language")
    if not candidate.get("corpus_sha256") or candidate.get("corpus_sha256") != baseline.get("corpus_sha256"):
        reasons.append("baseline and candidate corpora differ")
    if candidate.get("measurement") != baseline.get("measurement"):
        reasons.append("baseline and candidate measurement settings differ")
    per_language = {}
    for language in required:
        left = baseline.get("per_language", {}).get(language, {})
        right = candidate.get("per_language", {}).get(language, {})
        entry: dict[str, Any] = {"passed": False}
        valid = (left.get("sample_count", 0) >= min_samples_per_language and right.get("sample_count", 0) >= min_samples_per_language
                 and left.get("sample_count") == right.get("sample_count")
                 and left.get("error_count", 1) == 0 and right.get("error_count", 1) == 0
                 and all(_finite_metric(report.get(metric)) for report in (left, right) for metric in ("wer", "cer")))
        if valid:
            entry["wer_regression"] = right["wer"] - left["wer"]
            entry["cer_regression"] = right["cer"] - left["cer"]
            entry["passed"] = (entry["wer_regression"] <= max_wer_regression + 1e-12
                               and entry["cer_regression"] <= max_cer_regression + 1e-12)
            if not entry["passed"]:
                entry["reason"] = "quality regression exceeds the configured absolute tolerance"
        else:
            entry["reason"] = "insufficient, invalid, mismatched, or failed language samples"
        per_language[language] = entry
    quality_passed = not reasons and all(row["passed"] for row in per_language.values())
    performance = {}
    for metric in ("model_size_bytes", "load_time_seconds", "peak_rss_bytes"):
        left, right = baseline.get(metric), candidate.get(metric)
        performance[f"{metric}_ratio"] = right / left if _finite_metric(left) and left > 0 and _finite_metric(right) else None
    for metric in ("latency_p50_seconds", "latency_p95_seconds", "rtf"):
        left = baseline.get("aggregate", {}).get(metric)
        right = candidate.get("aggregate", {}).get(metric)
        performance[f"{metric}_ratio"] = right / left if _finite_metric(left) and left > 0 and _finite_metric(right) else None
    return {"quality_passed": quality_passed, "quality_gate_passed": quality_passed, "release_ready": False,
            "artifact_binding": {name: report.get("artifact_binding", {}).get("status", "unbound")
                                 for name, report in (("baseline", baseline), ("candidate", candidate))},
            "min_samples_per_language": min_samples_per_language, "required_languages": list(required),
            "max_wer_regression": max_wer_regression, "max_cer_regression": max_cer_regression,
            "reasons": reasons, "per_language": per_language, "performance_ratios": performance,
            "note": "Quality gate results apply only to this corpus. Historical reports without worker artifact verification remain unbound even if the numerical comparison passes. Release requires representative human-reference review and target-device performance acceptance; neither is asserted here."}


def evaluate_model(model: str | Path, model_dir: str | Path, manifest: str | Path, *,
                   output: str | Path | None = None, threads: int = 1,
                   warmup: int = 1, repeats: int = 1, allow_partial: bool = False,
                   baseline_report: str | Path | dict[str, Any] | None = None,
                   max_wer_regression: float = .01,
                   max_cer_regression: float = .01,
                   min_samples_per_language: int = 20,
                   required_languages: Sequence[str] | None = None) -> dict[str, Any]:
    """Evaluate in a fresh process so peak RSS is not inherited from quantization."""
    for name, value, minimum in (("threads", threads, 1), ("warmup", warmup, 0), ("repeats", repeats, 1), ("min_samples_per_language", min_samples_per_language, 1)):
        if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
            raise ValueError(f"{name} must be an integer >= {minimum}")
    if not _finite_metric(max_wer_regression) or not _finite_metric(max_cer_regression):
        raise ValueError("regression thresholds must be finite, nonnegative ratios")
    model = Path(model).resolve()
    if not model.is_file():
        raise FileNotFoundError(model)
    required = _required_languages(required_languages)
    samples = load_manifest(manifest, allow_partial=allow_partial, required_languages=required)
    present = sorted({sample.language for sample in samples})
    model_record = model_artifact_record(model)
    report: dict[str, Any] = {
        "report_version": REPORT_VERSION, "model": str(model),
        "required_languages": list(required), "evaluated_languages": present,
        "all_22_covered": len(present) == len(LANGUAGES),
        "model_size_bytes": model_record["size_bytes"],
        "manifest": str(Path(manifest).resolve()), "corpus_sha256": corpus_fingerprint(samples),
        "coverage": {"languages": present, "missing_languages": sorted(set(required) - set(present)),
                     "required_languages": list(required), "all_required_languages": set(required).issubset(present),
                     "all_22_languages": len(present) == len(LANGUAGES), "sample_count": len(samples)},
        "measurement": {"threads": threads, "inter_op_threads": 1,
                        "warmup_per_sample": warmup, "repeats_per_sample": repeats,
                        "provider": "CPUExecutionProvider", "latency_scope": "session.run only",
                        "load_scope": "ONNX Runtime InferenceSession construction",
                        "memory_scope": "isolated evaluation process peak resident set, including Python and runtime",
                        "text_normalization": "Unicode NFC; collapse whitespace; preserve case and punctuation; CER includes spaces"},
        "release_ready": False, "quality_gate_passed": False,
        "limitations": "Human-reference provenance and corpus representativeness are supplied by the caller; this report does not establish general accuracy or statistical confidence.",
    }
    spec = {"model": str(model), "model_dir": str(Path(model_dir).resolve()),
            "samples": [asdict(sample) for sample in samples], "threads": threads,
            "warmup": warmup, "repeats": repeats, "model_artifact": model_record}
    try:
        spec["decoder_assets"] = decoder_asset_records(model_dir)
    except OSError:
        # Preserve the worker error report for missing decoder files.
        pass
    with tempfile.TemporaryDirectory(prefix="veena-eval-") as temp:
        request = Path(temp) / "request.json"
        result = Path(temp) / "result.json"
        request.write_text(json.dumps(spec, ensure_ascii=False), encoding="utf-8")
        env = dict(os.environ)
        for variable in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "VECLIB_MAXIMUM_THREADS", "NUMEXPR_NUM_THREADS"):
            env[variable] = str(threads)
        child = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--_worker", str(request), str(result)],
                               capture_output=True, text=True, env=env)
        if child.returncode != 0 or not result.exists():
            message = child.stderr.strip()[-4000:] or f"evaluation worker exited {child.returncode}"
            records = [{"id": sample.sample_id, "language": sample.language, "error": message,
                        "reference": sample.text, "duration_seconds": sample.duration_seconds}
                       for sample in samples]
            report.update({"status": "error", "error": message, "samples": records,
                           "artifact_binding": {"status": "failed", "method": "sha256",
                                                "checked_before_inference": False,
                                                "checked_after_inference": False},
                           "aggregate": _summarize(records),
                           "per_language": {language: _summarize([row for row in records if row["language"] == language]) for language in LANGUAGES},
                           "load_time_seconds": None, "peak_rss_bytes": None})
        else:
            report.update(json.loads(result.read_text(encoding="utf-8")))
            report["status"] = "ok" if report["aggregate"]["error_count"] == 0 else "error"
    if baseline_report is not None:
        baseline = baseline_report if isinstance(baseline_report, dict) else json.loads(Path(baseline_report).read_text(encoding="utf-8"))
        report["comparison"] = compare_reports(baseline, report, max_wer_regression=max_wer_regression,
                                                max_cer_regression=max_cer_regression,
                                                min_samples_per_language=min_samples_per_language)
        report["quality_gate_passed"] = report["comparison"]["quality_gate_passed"]
        report["release_ready"] = report["comparison"]["release_ready"]
    if output is not None:
        output = Path(output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return report


def main() -> int:
    if len(sys.argv) == 4 and sys.argv[1] == "--_worker":
        result = _worker_evaluate(json.loads(Path(sys.argv[2]).read_text(encoding="utf-8")))
        Path(sys.argv[3]).write_text(json.dumps(result, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        return 0
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--model-dir", required=True, type=Path, help="directory containing vocab.json and language_masks.json")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--baseline-report", type=Path)
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--required-languages", nargs="+", choices=LANGUAGES, help="required corpus/model languages; default all 22")
    parser.add_argument("--min-samples-per-language", type=int, default=20)
    parser.add_argument("--allow-partial", action="store_true", help="permit missing required languages for development reports; quality gates still require full requested coverage")
    parser.add_argument("--max-wer-regression", type=float, default=.01, help="absolute ratio; .01 means one percentage point")
    parser.add_argument("--max-cer-regression", type=float, default=.01, help="absolute ratio; .01 means one percentage point")
    args = parser.parse_args()
    try:
        report = evaluate_model(**vars(args))
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    print(json.dumps({"status": report["status"], "report": str(args.output.resolve()),
                      "coverage": report["coverage"], "quality_gate_passed": report["quality_gate_passed"], "release_ready": report["release_ready"]}, indent=2))
    return 0 if report["status"] == "ok" and (args.baseline_report is None or report["quality_gate_passed"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
