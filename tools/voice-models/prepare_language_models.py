"""Fetch pinned language-specific INT8 candidates and package them for Veena.

These are evaluation candidates, never automatic replacements for shipped models.
No downloaded Python/notebook code is executed. The model's own output mask and
the source's own tokenizer are used: the 600M tokenizer is NOT interchangeable.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile
import urllib.request

import numpy as np
import onnx

ROOT = Path(__file__).resolve().parent
MODEL_NAME = "indicconformer_multi_ctc_int8.onnx"


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def download_verified(asset: dict, destination: Path) -> Path:
    """Reuse only verified files; commit a new download atomically."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if destination.stat().st_size == asset["size_bytes"] and sha256(destination) == asset["sha256"]:
            return destination
        raise ValueError(f"Cached file failed verification: {destination}; remove it before retrying")
    partial = destination.with_suffix(destination.suffix + ".partial")
    try:
        request = urllib.request.Request(asset["url"], headers={"User-Agent": "VeenaModelEvaluation/1.0"})
        with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
        if partial.stat().st_size != asset["size_bytes"] or sha256(partial) != asset["sha256"]:
            raise ValueError(f"Downloaded content failed size/SHA256 verification: {destination.name}")
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)
    return destination


def read_tokens(path: Path) -> list[str]:
    tokens: dict[int, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        token, index = line.rsplit(" ", 1)
        index = int(index)
        if index in tokens:
            raise ValueError(f"Duplicate token ID: {index}")
        tokens[index] = token
    if sorted(tokens) != list(range(len(tokens))):
        raise ValueError("Token IDs must be contiguous starting at zero")
    return [tokens[index] for index in range(len(tokens))]


def inspect_model(path: Path, language: str, tokens: list[str]) -> tuple[dict, list[int]]:
    model = onnx.load(path, load_external_data=False)
    if any(t.external_data for t in model.graph.initializer):
        raise ValueError("Source candidate unexpectedly requires external tensors")
    onnx.checker.check_model(model)
    inputs = {value.name: value.type.tensor_type for value in model.graph.input}
    if set(inputs) != {"audio_signal", "length"}:
        raise ValueError("Unexpected input names")
    features, lengths = inputs["audio_signal"], inputs["length"]
    if features.elem_type != onnx.TensorProto.FLOAT or len(features.shape.dim) != 3 or features.shape.dim[1].dim_value != 80:
        raise ValueError("Expected float32 features [batch,80,time]")
    if lengths.elem_type != onnx.TensorProto.INT64 or len(lengths.shape.dim) != 1:
        raise ValueError("Expected int64 lengths [batch]")
    if len(model.graph.output) != 1 or model.graph.output[0].name != "logprobs":
        raise ValueError("Expected a single logprobs output")
    output = model.graph.output[0].type.tensor_type
    if output.elem_type != onnx.TensorProto.FLOAT or len(output.shape.dim) != 3 or output.shape.dim[2].dim_value != len(tokens):
        raise ValueError("Output vocabulary shape does not match tokenizer")
    metadata = {item.key: item.value for item in model.metadata_props}
    comment = metadata.get("comment", "")
    if f", {language}," not in comment and f"indicconformer_stt_{language}_hybrid" not in comment:
        raise ValueError("Model language metadata does not match requested language")
    # Obtain the mask from the final output node, not assumed 256-token offsets.
    output_node = next(node for node in model.graph.node if "logprobs" in node.output)
    initializers = {tensor.name: tensor for tensor in model.graph.initializer}
    if output_node.op_type != "Add":
        raise ValueError("Expected a baked language mask on the final output")
    masks = []
    for name in output_node.input:
        if name in initializers:
            value = onnx.numpy_helper.to_array(initializers[name])
            if value.dtype == np.float32 and value.shape == (len(tokens),) and np.all((value == 0) | np.isneginf(value)):
                masks.append(value)
    if len(masks) != 1:
        raise ValueError("Cannot unambiguously identify the output language mask")
    indices = np.flatnonzero(np.isfinite(masks[0])).tolist()
    blank = len(tokens) - 1
    if blank not in indices or len(indices) < 2 or len(indices) == len(tokens):
        raise ValueError("Language mask must contain its vocabulary and shared blank")
    if tokens[blank] not in ("<blk>", "<blank>"):
        raise ValueError("Expected shared blank as final tokenizer entry")
    return {"inputs": ["audio_signal", "length"], "output": "logprobs",
            "num_classes": len(tokens), "blank_idx": blank, "active_classes": len(indices),
            "metadata": metadata, "validation": "ONNX checker and decoder contract only"}, indices


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def package_archive(directory: Path, archive: Path) -> None:
    """Deterministic gzip/tar metadata makes produced SHA256 reproducible."""
    partial = archive.with_suffix(archive.suffix + ".partial")
    try:
        with partial.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0, compresslevel=6) as gz:
            with tarfile.open(fileobj=gz, mode="w|") as tar:
                for path in sorted(directory.iterdir()):
                    info = tar.gettarinfo(str(path), arcname=f"{directory.name}/{path.name}")
                    info.uid = info.gid = info.mtime = 0
                    info.uname = info.gname = ""
                    info.mode = 0o644
                    with path.open("rb") as source:
                        tar.addfile(info, source)
        partial.replace(archive)
    finally:
        partial.unlink(missing_ok=True)


def prepare_language(entry: dict, catalog: dict, tokens: list[str], cache: Path, output: Path, archive: bool) -> dict:
    language = entry["code"]
    source = entry["community_int8"]
    model = download_verified(source, cache / language / "model.int8.onnx")
    contract, indices = inspect_model(model, language, tokens)
    model_id = f"indicconformer-{language}-int8-{source['revision'][:8]}"
    directory = output / model_id
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / MODEL_NAME
    if target.exists():
        if sha256(target) != source["sha256"]:
            raise ValueError(f"Existing output has different model bytes: {target}")
    else:
        try:
            os.link(model, target)
        except OSError:
            shutil.copy2(model, target)
    write_json(directory / "decoder_config.json", {"num_classes": len(tokens), "blank_idx": contract["blank_idx"]})
    # Index arrays are supported by Veena's multilingual decoder. Preserve global IDs.
    write_json(directory / "language_masks.json", {language: indices})
    write_json(directory / "vocab.json", {language: [tokens[index] for index in indices]})
    provenance = {"status": "candidate_unvalidated", "language": language, "source": source,
                  "tokenizer": catalog["shared_assets"]["tokens"], "publish": False,
                  "license_sources": catalog["sources"], "contract": contract}
    write_json(directory / "source.json", provenance)
    result = {"id": model_id, "language": language, "name": entry["name"],
              "status": "candidate_unvalidated", "release_ready": False,
              "decoder": "multilingual", "model_file": MODEL_NAME,
              "model_sha256": source["sha256"], "model_bytes": target.stat().st_size,
              "installed_bytes": sum(path.stat().st_size for path in directory.iterdir()),
              "directory": str(directory.resolve()), "contract": contract}
    if archive:
        archive_path = output / f"{model_id}.tar.gz"
        package_archive(directory, archive_path)
        result.update(archive=str(archive_path.resolve()), archive_bytes=archive_path.stat().st_size,
                      archive_sha256=sha256(archive_path))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--languages", nargs="+", required=True, help="Language codes or 'all'; applications should download only chosen languages")
    parser.add_argument("--sources", type=Path, default=ROOT / "model_sources.json")
    parser.add_argument("--cache", type=Path, default=ROOT / "candidates/source-cache")
    parser.add_argument("--output", type=Path, default=ROOT / "candidates/language-packs")
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--archive", action="store_true", help="Also produce deterministic tar.gz packages")
    args = parser.parse_args()
    if args.jobs < 1 or args.jobs > 4:
        parser.error("--jobs must be between 1 and 4")
    catalog = json.loads(args.sources.read_text())
    entries = {entry["code"]: entry for entry in catalog["languages"]}
    languages = list(entries) if args.languages == ["all"] else list(dict.fromkeys(args.languages))
    if set(languages) - entries.keys():
        parser.error(f"Unknown language codes: {sorted(set(languages) - entries.keys())}")
    token_file = download_verified(catalog["shared_assets"]["tokens"], args.cache / "tokens.txt")
    tokens = read_tokens(token_file)
    args.output.mkdir(parents=True, exist_ok=True)
    results, failures = [], []
    with ThreadPoolExecutor(max_workers=args.jobs) as executor:
        futures = {executor.submit(prepare_language, entries[language], catalog, tokens, args.cache, args.output, args.archive): language for language in languages}
        for future in as_completed(futures):
            language = futures[future]
            try:
                result = future.result()
                results.append(result)
                print(f"{language}: verified {result['model_bytes']:,} model bytes; candidate only", flush=True)
            except Exception as error:
                failures.append({"language": language, "error": str(error)})
                print(f"{language}: ERROR {error}", flush=True)
    report = {"status": "candidate_unvalidated", "release_ready": False,
              "delivery": "download_selected_languages", "source_revision": catalog["sources"]["community"]["revision"],
              "requested_languages": languages, "models": sorted(results, key=lambda result: result["language"]), "failures": failures}
    write_json(args.output / "candidate-manifest.json", report)
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main())
