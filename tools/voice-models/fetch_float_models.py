"""Fetch pinned original floating-point weights for mobile quantization experiments."""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

from prepare_language_models import ROOT, download_verified, read_tokens, write_json

PRIORITY = ["hi", "bn", "mr", "te", "ta", "gu", "ur", "kn", "or", "ml", "pa", "as",
            "mai", "sat", "ks", "ne", "sd", "doi", "kok", "mni", "brx", "sa"]


def retry(action):
    for attempt in range(4):
        try:
            return action()
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 3:
                raise
            time.sleep(5 * 2 ** attempt)


def small_asset(asset: dict, path: Path) -> None:
    if path.exists():
        data = path.read_bytes()
    else:
        request = urllib.request.Request(asset["url"], headers={"User-Agent": "VeenaQuantization/1.0"})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read()
    git_hash = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
    if len(data) != asset["size_bytes"] or git_hash != asset["git_blob_sha1"]:
        raise ValueError(f"Pinned Git blob/size mismatch: {path}")
    path.write_bytes(data)


def fetch(entry: dict, output: Path) -> dict:
    language = entry["code"]
    source = entry.get("openvoiceos_fp32")
    if not source:
        # Assamese uses the complete 5633-class export and its own tokenizer.
        source = entry.get("community_fp32")
        if not source:
            raise ValueError(f"No complete pinned floating-point source for {language}")
        directory = output / language
        directory.mkdir(parents=True, exist_ok=True)
        model = retry(lambda: download_verified(source, directory / "model.onnx"))
        assets = entry.get("community_fp32_assets", {})
        decoder = entry.get("community_fp32_decoder", {})
        if "vocab.json" not in assets or not decoder:
            raise ValueError(f"Pinned source-specific decoder metadata missing for {language}")
        vocabulary_path = directory / "source-vocab.json"
        retry(lambda: small_asset(assets["vocab.json"], vocabulary_path))
        vocabulary = json.loads(vocabulary_path.read_text(encoding="utf-8"))
        indices, blank, classes = decoder["indices"], decoder["blank_idx"], decoder["num_classes"]
        if (classes != 5633 or blank != 5632 or not isinstance(vocabulary, list)
                or len(vocabulary) != classes - 1 or not all(isinstance(token, str) for token in vocabulary)
                or len(indices) != 257 or len(set(indices)) != 257 or indices[-1] != blank
                or any(isinstance(index, bool) or not isinstance(index, int) or not 0 <= index < classes for index in indices)):
            raise ValueError("Invalid global CTC vocabulary or language mapping")
        tokens = [vocabulary[index] if index != blank else decoder["blank_token"] for index in indices]
        write_json(directory / "decoder_config.json", {"num_classes": classes, "blank_idx": blank})
        write_json(directory / "language_masks.json", {language: indices})
        write_json(directory / "vocab.json", {language: tokens})
        source_record = {**source, "files": [{"path": "model.onnx", "size_bytes": source["size_bytes"],
                                             "sha256": source["sha256"]}]}
        write_json(directory / "source.json", {"source": source_record, "decoder_assets": assets,
                   "decoder_mapping": decoder, "language": language,
                   "status": "original_fp32_for_quantization", "quality_status": "unvalidated"})
        return {"language": language, "model": str(model.resolve()), "status": "weights_and_decoder_verified"}
    directory = output / language
    directory.mkdir(parents=True, exist_ok=True)
    for asset in source["files"]:
        retry(lambda asset=asset: download_verified(asset, directory / asset["path"]))
    for name, asset in entry["openvoiceos_assets"].items():
        retry(lambda name=name, asset=asset: small_asset(asset, directory / name))
    tokens = read_tokens(directory / "vocab.txt")
    if len(tokens) != 257 or tokens[-1] != "<blk>":
        raise ValueError("Expected a local 256-token vocabulary followed by CTC blank")
    write_json(directory / "decoder_config.json", {"num_classes": 257, "blank_idx": 256})
    write_json(directory / "language_masks.json", {language: list(range(257))})
    write_json(directory / "vocab.json", {language: tokens})
    write_json(directory / "source.json", {"source": source, "decoder_assets": entry["openvoiceos_assets"],
               "language": language, "status": "original_fp32_for_quantization", "quality_status": "unvalidated"})
    return {"language": language, "model": str((directory / "model.onnx").resolve()), "status": "weights_and_decoder_verified"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--languages", nargs="+", default=PRIORITY[:8], help="Explicit language codes, or all; defaults to the first eight priorities")
    parser.add_argument("--sources", type=Path, default=ROOT / "model_sources.json")
    parser.add_argument("--output", type=Path, default=ROOT / "candidates/mobile-fp32")
    parser.add_argument("--jobs", type=int, choices=range(1, 4), default=2)
    args = parser.parse_args()
    catalog = json.loads(args.sources.read_text())
    entries = {entry["code"]: entry for entry in catalog["languages"]}
    selected = PRIORITY if args.languages == ["all"] else list(dict.fromkeys(args.languages))
    if set(selected) - entries.keys():
        parser.error("Unknown language code")
    results = []
    with ThreadPoolExecutor(max_workers=args.jobs) as executor:
        tasks = {executor.submit(fetch, entries[language], args.output): language for language in selected}
        for task in as_completed(tasks):
            language = tasks[task]
            try:
                result = task.result()
            except Exception as error:
                result = {"language": language, "status": "failed", "error": str(error)}
            results.append(result)
            print(json.dumps(result), flush=True)
    args.output.mkdir(parents=True, exist_ok=True)
    write_json(args.output / "fetch-report.json", {"priority_order": selected, "results": results})
    return int(any(result["status"] == "failed" for result in results))


if __name__ == "__main__":
    raise SystemExit(main())
