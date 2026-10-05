r"""Build unvalidated CPU INT8 candidates from a floating-point language model.

This deliberately does not select, publish or replace a production model. Run
evaluate_multilingual.py separately with human-reference held-out speech before
comparing quality, size and performance. Example::

    python quantize_language_model.py --model te/model.onnx --language te \
        --recipe dynamic_matmul_int8 --recipe static_qdq_const_int8 \
        --calibration-manifest te-calibration.jsonl \
        --evaluation-manifest te-held-out.jsonl --output-dir candidates/te-v1

Manifests use the evaluator's JSONL feature format (language, text, features,
duration_seconds). For a language-specific model each manifest must contain
only the selected language. Static calibration needs at least two distinct
utterances and a disjoint held-out corpus. Corpus representativeness across
speakers, dialects, recording conditions and durations remains a data curation
requirement; passing these checks does not establish it.

The constant-weight static variants explicitly exclude runtime attention
MatMuls. The original static_qdq_int8 recipe also quantizes attention products
and is retained only to reproduce comparison builds. Shared activations can
still be quantized by neighboring weight operators even when a node is excluded.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import sys
from typing import Any, Sequence

import onnx
from onnx.external_data_helper import _get_all_tensors
import onnxruntime as ort
from onnxruntime.quantization import (
    CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType,
    quantize_dynamic, quantize_static,
)
from onnxruntime.quantization.shape_inference import quant_pre_process

from evaluate_multilingual import (
    LANGUAGES, EvaluationSample, corpus_fingerprint, load_features, load_manifest,
)


RECIPES: dict[str, dict[str, Any]] = {
    "dynamic_matmul_int8": {
        "method": "dynamic", "weight_type": "QInt8", "per_channel": True,
        "op_types_to_quantize": ["MatMul"], "reduce_range": False,
    },
    "static_qdq_int8": {
        "method": "static", "quant_format": "QDQ",
        "weight_type": "QInt8", "activation_type": "QInt8",
        "per_channel": True, "calibration_method": "MinMax",
        "op_types_to_quantize": ["Conv", "MatMul"], "reduce_range": False,
        "calibration_providers": ["CPUExecutionProvider"],
    },
}
for _label, _activation_type in (
    ("static_qdq_const_int8", "QInt8"),
    ("static_qdq_const_u8s8", "QUInt8"),
):
    RECIPES[_label] = {
        **RECIPES["static_qdq_int8"],
        "activation_type": _activation_type,
        "matmul_policy": "constant_B_initializer_only",
        "extra_options": {"MatMulConstBOnly": True},
    }
PREPROCESSING = {
    "skip_optimization": False, "skip_onnx_shape": False,
    "skip_symbolic_shape": True,
}
QUANTIZED_TYPES = {
    getattr(onnx.TensorProto, name)
    for name in ("INT8", "UINT8", "INT4", "UINT4", "INT2", "UINT2")
    if hasattr(onnx.TensorProto, name)
}


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def json_digest(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":")).encode()).hexdigest()


def _nodes(graph: onnx.GraphProto):
    for node in graph.node:
        yield node
        for attribute in node.attribute:
            if attribute.type == onnx.AttributeProto.GRAPH:
                yield from _nodes(attribute.g)
            elif attribute.type == onnx.AttributeProto.GRAPHS:
                for child in attribute.graphs:
                    yield from _nodes(child)


def inspect_float_source(path: str | Path) -> onnx.ModelProto:
    """Reject requantization, including quantization in nested graphs/constants."""
    path = Path(path).resolve()
    model = onnx.load_model(str(path), load_external_data=False)
    nodes = list(_nodes(model.graph))
    for function in model.functions:
        nodes.extend(function.node)
    quantized_nodes = sorted({node.op_type for node in nodes if (
        "quant" in node.op_type.lower() or node.op_type.startswith("QLinear")
        or node.op_type in {"MatMulInteger", "ConvInteger", "MatMulNBits",
                            "QGemm", "QAttention", "QEmbedLayerNormalization"}
    )})
    quantized_tensors = [tensor.name or "<constant>" for tensor in _get_all_tensors(model)
                         if tensor.data_type in QUANTIZED_TYPES]
    if quantized_nodes or quantized_tensors:
        raise ValueError(
            "Source is already quantized; use the original floating-point export. "
            f"Quantized ops: {quantized_nodes}; low-bit tensors: {quantized_tensors[:5]}"
        )
    initializer_names = {tensor.name for tensor in model.graph.initializer}
    inputs = {item.name: item.type.tensor_type for item in model.graph.input
              if item.name not in initializer_names}
    if set(inputs) != {"audio_signal", "length"}:
        raise ValueError("Source must have exactly audio_signal and length inputs")
    features, length = inputs["audio_signal"], inputs["length"]
    if features.elem_type != onnx.TensorProto.FLOAT or len(features.shape.dim) != 3:
        raise ValueError("audio_signal must be float32 [1, 80, T]")
    if features.shape.dim[1].dim_value != 80:
        raise ValueError("audio_signal must have 80 mel features")
    if features.shape.dim[0].dim_value not in (0, 1):
        raise ValueError("audio_signal must accept batch size 1")
    if length.elem_type != onnx.TensorProto.INT64 or len(length.shape.dim) != 1:
        raise ValueError("length must be int64 [1]")
    if length.shape.dim[0].dim_value not in (0, 1):
        raise ValueError("length must accept batch size 1")
    onnx.checker.check_model(str(path))
    return model


def nonconstant_matmul_exclusions(model: onnx.ModelProto) -> list[str]:
    """Explicitly protect attention products; QDQ ignores MatMulConstBOnly.

    In ORT 1.26 that option is implemented by ONNXQuantizer, not QDQQuantizer.
    Use nodes_to_exclude as the actual enforcement, and fail rather than risk
    quantizing a dynamic-input MatMul that cannot be identified by name.
    """
    initializers = {tensor.name for tensor in model.graph.initializer}
    excluded = []
    for node in _nodes(model.graph):
        if node.op_type == "MatMul" and (len(node.input) < 2 or node.input[1] not in initializers):
            if not node.name:
                raise ValueError("Nonconstant MatMul has no node name; cannot safely exclude it from QDQ")
            excluded.append(node.name)
    return sorted(set(excluded))


def artifact_record(path: str | Path) -> dict[str, Any]:
    """Fingerprint the ONNX file and every referenced external data file."""
    path = Path(path).resolve()
    model = onnx.load_model(str(path), load_external_data=False)
    assets: dict[Path, str] = {path: path.name}
    for tensor in _get_all_tensors(model):
        if tensor.data_location != onnx.TensorProto.EXTERNAL:
            continue
        locations = [entry.value for entry in tensor.external_data if entry.key == "location"]
        if len(locations) != 1 or not locations[0]:
            raise ValueError("External tensor must have exactly one location")
        assets[(path.parent / locations[0]).resolve()] = locations[0]
    files = [{"path": name, "size_bytes": asset.stat().st_size,
              "sha256": sha256_file(asset)}
             for asset, name in sorted(assets.items(), key=lambda pair: pair[1])]
    return {
        "path": str(path), "sha256": sha256_file(path), "files": files,
        "artifact_sha256": json_digest(files),
        "size_bytes": sum(item["size_bytes"] for item in files),
        "operator_counts": dict(sorted(Counter(node.op_type for node in _nodes(model.graph)).items())),
    }


def load_language_corpus(path: str | Path, language: str) -> list[EvaluationSample]:
    samples = load_manifest(path, allow_partial=True)
    if any(sample.language != language for sample in samples):
        raise ValueError(f"Manifest must contain only selected language {language!r}")
    return samples


def validate_calibration_split(
    calibration: Sequence[EvaluationSample], held_out: Sequence[EvaluationSample],
) -> None:
    if len(calibration) < 2:
        raise ValueError("Static calibration requires at least two distinct utterances")
    calibration_hashes = {sample.feature_sha256 for sample in calibration}
    if len(calibration_hashes) != len(calibration):
        raise ValueError("Calibration corpus contains duplicate feature tensors")
    if not held_out:
        raise ValueError("Static calibration requires a nonempty held-out evaluation corpus")
    if {sample.features for sample in calibration} & {sample.features for sample in held_out}:
        raise ValueError("Calibration and held-out evaluation corpora overlap by feature path")
    if calibration_hashes & {sample.feature_sha256 for sample in held_out}:
        raise ValueError("Calibration and held-out evaluation corpora overlap by feature content")


def corpus_record(path: str | Path, samples: Sequence[EvaluationSample]) -> dict[str, Any]:
    durations = [sample.duration_seconds for sample in samples]
    return {
        "manifest": str(Path(path).resolve()), "manifest_sha256": sha256_file(path),
        "corpus_sha256": corpus_fingerprint(samples), "sample_count": len(samples),
        "duration_seconds": sum(durations), "min_duration_seconds": min(durations),
        "max_duration_seconds": max(durations),
        "language_counts": dict(sorted(Counter(sample.language for sample in samples).items())),
        "representativeness": "not_assessed",
    }


class FeatureCalibrationReader(CalibrationDataReader):
    """Stream one validated utterance at a time to keep corpus memory bounded."""

    def __init__(self, samples: Sequence[EvaluationSample]):
        self.samples = samples
        self.rewind()

    def get_next(self):
        try:
            sample = next(self._samples)
        except StopIteration:
            return None
        features, length = load_features(sample.features)
        # Detect content changes after split/fingerprint validation.
        digest = hashlib.sha256()
        digest.update(str(features.shape).encode("ascii"))
        digest.update(features.tobytes(order="C"))
        if digest.hexdigest() != sample.feature_sha256:
            raise ValueError(f"Calibration features changed: {sample.features}")
        return {"audio_signal": features, "length": length}

    def rewind(self):
        self._samples = iter(self.samples)


def build_candidates(
    model_path: str | Path, output_dir: str | Path, language: str,
    recipes: Sequence[str], *, calibration_manifest: str | Path | None = None,
    evaluation_manifest: str | Path | None = None,
) -> dict[str, Any]:
    """Build requested candidates without ranking them or overwriting anything."""
    if language not in LANGUAGES:
        raise ValueError(f"Unsupported language: {language!r}")
    if not recipes or len(set(recipes)) != len(recipes) or any(r not in RECIPES for r in recipes):
        raise ValueError("Select one or more distinct supported recipes")
    output_dir = Path(output_dir).resolve()
    model_path = Path(model_path).resolve()
    if output_dir.exists():
        raise FileExistsError(f"Output directory already exists; choose a new directory: {output_dir}")
    inspect_float_source(model_path)
    calibration = load_language_corpus(calibration_manifest, language) if calibration_manifest else []
    held_out = load_language_corpus(evaluation_manifest, language) if evaluation_manifest else []
    if any(RECIPES[label]["method"] == "static" for label in recipes):
        if not calibration_manifest or not evaluation_manifest:
            raise ValueError("Static QDQ requires --calibration-manifest and --evaluation-manifest")
        validate_calibration_split(calibration, held_out)
    elif calibration and held_out:
        validate_calibration_split(calibration, held_out)
    recipe_environment = {
        "pipeline_version": 1, "onnx_version": onnx.__version__,
        "onnxruntime_version": ort.__version__, "preprocessing": PREPROCESSING,
    }
    report: dict[str, Any] = {
        "schema_version": 1, "status": "building", "language": language,
        "validation_status": "unvalidated", "selection": None,
        "source": artifact_record(model_path), "build_environment": recipe_environment,
        "calibration": corpus_record(calibration_manifest, calibration) if calibration_manifest else None,
        "held_out_evaluation": corpus_record(evaluation_manifest, held_out) if evaluation_manifest else None,
        "candidates": [],
        "notes": [
            "These candidates have not been evaluated for WER, CER, latency, memory or browser compatibility.",
            "No candidate is selected automatically. Evaluate source and candidates on the same held-out human-reference corpus and target hardware.",
            "More varied calibration speech may be needed; split integrity does not establish representative coverage.",
        ],
    }
    output_dir.mkdir(parents=True, exist_ok=False)
    report_path = output_dir / "candidate-report.json"

    def save_report():
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    save_report()
    work_dir = output_dir / ".build"
    work_dir.mkdir()
    try:
        preprocessed = work_dir / "preprocessed.onnx"
        quant_pre_process(model_path, preprocessed, **PREPROCESSING)
        report["preprocessed_source"] = artifact_record(preprocessed)
        report["preprocessed_source"]["retained"] = False
        # Preprocessing can change graph nodes but may not quantize the source.
        preprocessed_model = inspect_float_source(preprocessed)
        for label in recipes:
            recipe = {**recipe_environment, **RECIPES[label]}
            excluded = nonconstant_matmul_exclusions(preprocessed_model) if recipe.get("matmul_policy") else []
            if recipe.get("matmul_policy"):
                recipe["nodes_to_exclude"] = excluded
            candidate: dict[str, Any] = {
                "recipe": label, "recipe_config": recipe, "recipe_sha256": json_digest(recipe),
                "validation_status": "unvalidated", "build_status": "building",
                "calibration_corpus_sha256": report["calibration"]["corpus_sha256"]
                if recipe["method"] == "static" else None,
            }
            report["candidates"].append(candidate)
            candidate_path = output_dir / f"{language}-{label}.onnx"
            try:
                if label == "dynamic_matmul_int8":
                    quantize_dynamic(
                        preprocessed, candidate_path, per_channel=True, weight_type=QuantType.QInt8,
                        op_types_to_quantize=["MatMul"], reduce_range=False,
                        use_external_data_format=True,
                    )
                else:
                    quantize_static(
                        preprocessed, candidate_path, FeatureCalibrationReader(calibration),
                        quant_format=QuantFormat.QDQ,
                        activation_type=getattr(QuantType, recipe["activation_type"]),
                        weight_type=QuantType.QInt8, per_channel=True,
                        op_types_to_quantize=["Conv", "MatMul"], reduce_range=False,
                        nodes_to_exclude=excluded,
                        extra_options=recipe.get("extra_options", {}),
                        calibrate_method=CalibrationMethod.MinMax,
                        calibration_providers=["CPUExecutionProvider"],
                        use_external_data_format=True,
                    )
                onnx.checker.check_model(str(candidate_path))
                candidate["artifact"] = artifact_record(candidate_path)
                operators = candidate["artifact"]["operator_counts"]
                expected_op = "MatMulInteger" if label == "dynamic_matmul_int8" else "QuantizeLinear"
                if not operators.get(expected_op):
                    raise ValueError(f"Recipe did not produce {expected_op}; no useful candidate was built")
                candidate["build_status"] = "built"
            except Exception as exc:
                candidate["build_status"] = "failed"
                candidate["error"] = f"{type(exc).__name__}: {exc}"
            save_report()
        report["status"] = "built" if all(c["build_status"] == "built" for c in report["candidates"]) else "failed"
    except Exception as exc:
        report["status"] = "failed"
        report["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        # Only remove the private temporary directory created by this invocation.
        shutil.rmtree(work_dir)
        save_report()
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", type=Path, required=True, help="Original floating-point ONNX export")
    parser.add_argument("--language", choices=LANGUAGES, required=True)
    parser.add_argument("--output-dir", type=Path, required=True, help="New directory; existing directories are rejected")
    parser.add_argument("--recipe", action="append", choices=tuple(RECIPES), required=True,
                        help="Repeat to build multiple candidates; none is selected automatically")
    parser.add_argument("--calibration-manifest", type=Path)
    parser.add_argument("--evaluation-manifest", type=Path)
    args = parser.parse_args(argv)
    try:
        report = build_candidates(args.model, args.output_dir, args.language, args.recipe,
                                  calibration_manifest=args.calibration_manifest,
                                  evaluation_manifest=args.evaluation_manifest)
    except (ValueError, OSError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps({"status": report["status"], "validation_status": report["validation_status"],
                      "report": str(args.output_dir.resolve() / "candidate-report.json")}, indent=2))
    return 0 if report["status"] == "built" else 1


if __name__ == "__main__":
    raise SystemExit(main())
