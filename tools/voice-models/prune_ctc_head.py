"""Create a 257-class FP32 language head from a verified 5633-class CTC export.

Only a terminal Conv1d -> Transpose -> LogSoftmax head is supported. Selected
weight rows and bias entries are copied exactly. Log probabilities are
renormalized over the selected classes; masked greedy CTC predictions are
preserved mathematically. This does not measure ASR quality or mobile speed.

Example::

    python prune_ctc_head.py --source candidates/mobile-fp32/as/model.onnx \
        --language as --output-dir candidates/mobile-fp32-local/as
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import tempfile

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

from quantize_language_model import artifact_record, sha256_file
from quantize_int4 import validate_fp32_source


def _load_source(path: Path, artifact: dict) -> onnx.ModelProto:
    paths = [(path.parent / item["path"]).resolve() for item in artifact["files"]]
    if any(not item.is_relative_to(path.parent) for item in paths):
        raise ValueError("External tensor files must be inside the source directory")
    if any(item.stat().st_nlink > 1 for item in paths):
        # ONNX intentionally rejects external files with multiple hard links.
        # A private byte copy preserves that check and leaves source links alone.
        with tempfile.TemporaryDirectory(prefix="veena-prune-ctc-") as directory:
            root = Path(directory)
            for item in paths:
                target = root / item.relative_to(path.parent)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(item, target)
            return onnx.load(root / path.name, load_external_data=True)
    return onnx.load(path, load_external_data=True)


def prune_model(source: str | Path, output_dir: str | Path, language: str, *,
                assets_dir: str | Path | None = None,
                expected_sha256: str | None = None) -> dict:
    source, output_dir = Path(source).resolve(), Path(output_dir).resolve()
    assets_dir = Path(assets_dir).resolve() if assets_dir is not None else source.parent
    if output_dir.exists():
        raise FileExistsError(f"Output directory already exists: {output_dir}")
    artifact = artifact_record(source)
    if expected_sha256 is not None and artifact["sha256"] != expected_sha256:
        raise ValueError("Source ONNX SHA256 does not match expected value")
    config = json.loads((assets_dir / "decoder_config.json").read_text(encoding="utf-8"))
    masks = json.loads((assets_dir / "language_masks.json").read_text(encoding="utf-8"))
    vocab = json.loads((assets_dir / "vocab.json").read_text(encoding="utf-8"))
    classes = config.get("num_classes")
    blank = config.get("blank_idx", config.get("blank_id"))
    if classes != 5633 or blank != 5632:
        raise ValueError("This utility requires the original 5633-class head with blank 5632")
    indices = masks.get(language)
    if (not isinstance(indices, list) or len(indices) != 257
            or any(isinstance(i, bool) or not isinstance(i, int) or not 0 <= i < classes for i in indices)
            or len(set(indices)) != 257 or indices[-1] != blank):
        raise ValueError("Language mask must list 256 unique class indices followed by global blank")
    tokens = vocab.get(language)
    if not isinstance(tokens, list) or len(tokens) not in (256, 257) or not all(isinstance(t, str) for t in tokens):
        raise ValueError("Source vocabulary must align with the selected 256 tokens and optional blank")
    tokens = tokens[:256] + [tokens[256] if len(tokens) == 257 else "<blk>"]
    model = _load_source(source, artifact)
    validate_fp32_source(model, require_matmul=False)
    original_inputs = [value.SerializeToString() for value in model.graph.input]
    if len(model.graph.output) != 1:
        raise ValueError("Expected one CTC log-probability output")
    output = model.graph.output[0]
    if output.type.tensor_type.elem_type != TensorProto.FLOAT or len(output.type.tensor_type.shape.dim) != 3:
        raise ValueError("CTC output must be float32 [batch, time, classes]")
    if output.type.tensor_type.shape.dim[-1].dim_value != classes:
        raise ValueError("CTC output class dimension disagrees with the decoder")
    producers = {name: node for node in model.graph.node for name in node.output}
    softmax = producers.get(output.name)
    if softmax is None or softmax.op_type != "LogSoftmax" or len(softmax.input) != 1:
        raise ValueError("Expected terminal LogSoftmax")
    softmax_attrs = {a.name: helper.get_attribute_value(a) for a in softmax.attribute}
    if softmax_attrs.get("axis", -1) not in (-1, 2):
        raise ValueError("LogSoftmax must normalize the class axis")
    transpose = producers.get(softmax.input[0])
    if transpose is None or transpose.op_type != "Transpose" or len(transpose.input) != 1:
        raise ValueError("Expected Transpose immediately before LogSoftmax")
    transpose_attrs = {a.name: helper.get_attribute_value(a) for a in transpose.attribute}
    if list(transpose_attrs.get("perm", [])) != [0, 2, 1]:
        raise ValueError("Expected NCT to NTC head transpose")
    conv = producers.get(transpose.input[0])
    if conv is None or conv.op_type != "Conv" or len(conv.input) != 3:
        raise ValueError("Expected terminal Conv with constant weights and bias")
    conv_attrs = {a.name: helper.get_attribute_value(a) for a in conv.attribute}
    if conv_attrs.get("group", 1) != 1:
        raise ValueError("Grouped CTC heads cannot be pruned by selecting output rows")
    initializers = {tensor.name: tensor for tensor in model.graph.initializer}
    weight, bias = initializers.get(conv.input[1]), initializers.get(conv.input[2])
    if (weight is None or bias is None or weight.data_type != TensorProto.FLOAT or bias.data_type != TensorProto.FLOAT
            or len(weight.dims) != 3 or weight.dims[0] != classes or weight.dims[2] != 1 or list(bias.dims) != [classes]):
        raise ValueError("Head must have FP32 [5633, channels, 1] weights and [5633] bias")
    if any(sum(name in node.input for node in model.graph.node) != 1 for name in (weight.name, bias.name)):
        raise ValueError("Head initializers are shared with another operator")
    if sum(conv.output[0] in node.input for node in model.graph.node) != 1:
        raise ValueError("CTC Conv output has consumers outside the terminal head")
    if sum(transpose.output[0] in node.input for node in model.graph.node) != 1:
        raise ValueError("CTC transpose output has consumers outside the terminal head")
    selected_weights = np.ascontiguousarray(numpy_helper.to_array(weight)[indices])
    selected_bias = np.ascontiguousarray(numpy_helper.to_array(bias)[indices])
    weight.CopyFrom(numpy_helper.from_array(selected_weights, weight.name))
    bias.CopyFrom(numpy_helper.from_array(selected_bias, bias.name))
    class_axes = {conv.output[0]: 1, transpose.output[0]: 2, softmax.output[0]: 2}
    for value in [*model.graph.output, *model.graph.value_info]:
        if value.name in class_axes and len(value.type.tensor_type.shape.dim) == 3:
            value.type.tensor_type.shape.dim[class_axes[value.name]].dim_value = 257
    if original_inputs != [value.SerializeToString() for value in model.graph.input]:
        raise ValueError("Pruning unexpectedly changed model inputs")
    onnx.checker.check_model(model)
    output_dir.mkdir(parents=True, exist_ok=False)
    target = output_dir / "model.onnx"
    onnx.save_model(model, target, save_as_external_data=False)
    for name, content in {
        "decoder_config.json": {"num_classes": 257, "blank_idx": 256},
        "language_masks.json": {language: list(range(257))},
        "vocab.json": {language: tokens},
    }.items():
        (output_dir / name).write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report = {
        "status": "head_pruned_quality_unvalidated", "release_ready": False,
        "language": language, "source_artifact": artifact,
        "source_decoder_assets": {name: {"path": str(assets_dir / name), "sha256": sha256_file(assets_dir / name)}
                                  for name in ("decoder_config.json", "language_masks.json", "vocab.json")},
        "output_artifact": artifact_record(target), "selected_global_indices": indices,
        "source_num_classes": 5633, "output_num_classes": 257, "local_blank_idx": 256,
        "terminal_conv": conv.name, "input_contract_preserved": True,
        "weight_rows_copied_exactly": True, "bias_entries_copied_exactly": True,
        "behavior": "Masked greedy CTC predictions are preserved mathematically; output log probabilities are renormalized over the selected classes.",
    }
    origin = assets_dir / "source.json"
    if origin.exists():
        report["upstream_source"] = json.loads(origin.read_text(encoding="utf-8"))
    upstream_details = report.get("upstream_source", {}).get("source", {})
    report["source"] = {
        "files": report["output_artifact"]["files"],
        "transformation": "exact FP32 CTC head row selection from the recorded upstream source",
        "upstream_artifact_sha256": artifact["artifact_sha256"],
        "upstream_revision": upstream_details.get("revision"),
        "license": upstream_details.get("license"),
    }
    (output_dir / "source.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--language", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--assets-dir", type=Path)
    parser.add_argument("--expected-sha256")
    args = parser.parse_args()
    report = prune_model(args.source, args.output_dir, args.language,
                         assets_dir=args.assets_dir, expected_sha256=args.expected_sha256)
    print(json.dumps({"status": report["status"], "output": report["output_artifact"]["path"],
                      "size_bytes": report["output_artifact"]["size_bytes"], "release_ready": False}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
