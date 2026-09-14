"""Prepare Veena's existing model assets for browser feasibility validation.

Run with the voice-models Python environment. Original model artifacts are not
changed. The multilingual production archive is downloaded and SHA256-checked.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import tarfile
import urllib.request
from collections import Counter
from pathlib import Path

import onnx


ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "browser-assets"
MULTI_URL = "https://dl.byanr.com/veena-byanr/indicconformer_multi_ctc_int8.tar.gz"
MULTI_SHA256 = "0c885b33ff0df71fa4a91171a7c46486d337c4503302076a1ada8ca730c42f94"


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def file_info(path: Path) -> dict:
    return {
        "path": str(path.relative_to(OUTPUT)),
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def inspect_graph(path: Path) -> dict:
    model = onnx.load(str(path), load_external_data=False)

    def tensors(values):
        result = []
        for value in values:
            tensor = value.type.tensor_type
            result.append({
                "name": value.name,
                "type": onnx.TensorProto.DataType.Name(tensor.elem_type),
                "shape": [
                    d.dim_value if d.HasField("dim_value") else d.dim_param
                    for d in tensor.shape.dim
                ],
            })
        return result

    return {
        "irVersion": model.ir_version,
        "opsets": [{"domain": o.domain, "version": o.version} for o in model.opset_import],
        "inputs": tensors(model.graph.input),
        "outputs": tensors(model.graph.output),
        "operators": dict(sorted(Counter(
            f"{n.domain or 'ai.onnx'}::{n.op_type}" for n in model.graph.node
        ).items())),
        "externalInitializers": sum(bool(t.external_data) for t in model.graph.initializer),
    }


def extract(archive: Path, destination: Path, names: set[str]) -> list[Path]:
    destination.mkdir(parents=True, exist_ok=True)
    extracted = []
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            name = Path(member.name).name
            if not member.isfile() or name not in names:
                continue
            target = destination / name
            with source.extractfile(member) as infile, target.open("wb") as outfile:
                shutil.copyfileobj(infile, outfile)
            extracted.append(target)
    missing = names - {p.name for p in extracted}
    if missing:
        raise RuntimeError(f"Missing model assets in {archive}: {sorted(missing)}")
    return extracted


def main() -> None:
    OUTPUT.mkdir(exist_ok=True)
    archives = OUTPUT / "archives"
    archives.mkdir(exist_ok=True)
    multi_archive = archives / "indicconformer_multi_ctc_int8.tar.gz"
    if not multi_archive.exists():
        partial = multi_archive.with_suffix(multi_archive.suffix + ".partial")
        request = urllib.request.Request(MULTI_URL, headers={"User-Agent": "Mozilla/5.0 VeenaAssetPreparation/1.0"})
        with urllib.request.urlopen(request) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
        if sha256(partial) != MULTI_SHA256:
            raise RuntimeError("Multilingual archive SHA256 does not match desktop catalogue")
        partial.replace(multi_archive)
    elif sha256(multi_archive) != MULTI_SHA256:
        raise RuntimeError("Existing multilingual archive SHA256 does not match desktop catalogue")

    telugu_archive = ROOT / "models/indicconformer_stt_te_ctc_static_qdq_int8.tar.gz"
    manifest = {"models": []}
    for model_id, archive, names in [
        ("indicconformer-te", telugu_archive, {
            "indicconformer_stt_te_ctc_static_qdq_int8.onnx", "decoder_config.json", "tokenizer_te.vocab",
        }),
        ("indicconformer-multi", multi_archive, {
            "indicconformer_multi_ctc_int8.onnx", "decoder_config.json", "vocab.json", "language_masks.json",
        }),
    ]:
        paths = extract(archive, OUTPUT / model_id, names)
        model = next(p for p in paths if p.suffix == ".onnx")
        entry = {
            "id": model_id,
            "archiveBytes": archive.stat().st_size,
            "archiveSha256": sha256(archive),
            "files": [file_info(path) for path in sorted(paths)],
            "graph": inspect_graph(model),
        }
        manifest["models"].append(entry)
        print(json.dumps(entry, ensure_ascii=False), flush=True)
    manifest_path = OUTPUT / "asset-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"Manifest: {manifest_path}", flush=True)


if __name__ == "__main__":
    main()
