"""
Export the IndicConformer-600M-Multilingual encoder + CTC decoder as a single ONNX file.

This merges encoder.onnx and ctc_decoder.onnx into one model that takes
audio features (mel spectrogram) and outputs CTC logprobs for all 22 languages.

Usage:
    huggingface-cli login  # must have access to ai4bharat/indic-conformer-600m-multilingual
    python export_multilingual_ctc_onnx.py
"""

import json
import os
import sys
from pathlib import Path

import onnx
from onnx import compose, save as onnx_save
from huggingface_hub import snapshot_download

REPO_ID = "ai4bharat/indic-conformer-600m-multilingual"
OUTPUT_DIR = Path("models/multilingual_ctc")


def main():
    from huggingface_hub import get_token
    hf_token = get_token()  # HF_TOKEN environment variable or hf auth login.

    print(f"Downloading {REPO_ID} ...")
    model_path = snapshot_download(REPO_ID, token=hf_token)
    print(f"Model downloaded to {model_path}")

    assets = Path(model_path) / "assets"

    # Load encoder and CTC decoder ONNX models
    encoder = onnx.load(str(assets / "encoder.onnx"))
    ctc_decoder = onnx.load(str(assets / "ctc_decoder.onnx"))

    print("Encoder inputs:", [i.name for i in encoder.graph.input])
    print("Encoder outputs:", [o.name for o in encoder.graph.output])
    print("CTC decoder inputs:", [i.name for i in ctc_decoder.graph.input])
    print("CTC decoder outputs:", [o.name for o in ctc_decoder.graph.output])

    # Connect encoder output 'outputs' -> ctc_decoder input 'encoder_output'
    # Only keep 'logprobs' as final output (discard 'encoded_lengths')
    combined = compose.merge_models(
        encoder,
        ctc_decoder,
        io_map=[("outputs", "encoder_output")],
        outputs=["logprobs"],
    )

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    onnx_path = OUTPUT_DIR / "encoder_ctc_multilingual.onnx"
    onnx_save(combined, str(onnx_path))
    print(f"Combined ONNX saved to {onnx_path}")

    # Copy over vocab and language masks for runtime decoding
    import shutil
    for f in ["vocab.json", "language_masks.json"]:
        src = assets / f
        if src.exists():
            shutil.copy2(str(src), str(OUTPUT_DIR / f))
            print(f"Copied {f}")

    # Also copy the preprocessor if needed
    preproc = assets / "preprocessor.ts"
    if preproc.exists():
        shutil.copy2(str(preproc), str(OUTPUT_DIR / "preprocessor.ts"))
        print("Copied preprocessor.ts")

    # Print model info
    print("\nCombined model inputs:")
    for i in combined.graph.input:
        print(f"  {i.name}: {i.type}")
    print("Combined model outputs:")
    for o in combined.graph.output:
        print(f"  {o.name}: {o.type}")

    # Validate with a dummy run
    import numpy as np
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path))
    input_name = sess.get_inputs()[0].name
    length_name = sess.get_inputs()[1].name
    dummy_feats = np.random.randn(1, 80, 200).astype(np.float32)
    dummy_len = np.array([200], dtype=np.int64)
    logprobs = sess.run(
        None, {input_name: dummy_feats, length_name: dummy_len})[0]
    print(f"\nValidation: logprobs shape = {logprobs.shape}")
    print(
        f"  Vocab classes (total across all languages): {logprobs.shape[-1]}")

    print(f"\nAll assets saved to {OUTPUT_DIR.resolve()}")
    print("Files:")
    for f in sorted(OUTPUT_DIR.iterdir()):
        print(f"  {f.name}")


if __name__ == "__main__":
    main()
