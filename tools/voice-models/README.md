# voice-models — model pipeline for Veena

Python tooling that exports, quantizes, and validates the IndicConformer ONNX
models used by every Veena app (desktop, iOS, Android, web). The inference
runtime itself lives in Rust: [`crates/rust-backend`](../../crates/rust-backend).

## Layout

| Path | Purpose |
| --- | --- |
| `models/` | Small config/tokenizer assets (decoder configs, vocabularies). Model **binaries are never committed** — they are gated Hugging Face exports or runtime downloads. |
| `telugu_test_audio.wav` | Shared 22.98 s Telugu reference recording used by these scripts, the engine's `cargo test`, and the web app's demo fixture. |

## Scripts

| Script | Purpose |
| --- | --- |
| `export_multilingual_ctc_onnx.py` | Export IndicConformer-600M-Multilingual (encoder + CTC head) from Hugging Face into a single ONNX file. Requires `huggingface-cli login` with access to `ai4bharat/indic-conformer-600m-multilingual`. |
| `transcribe.py` | Baseline Telugu inference (PyTorch). |
| `onnx_transcribe.py` | Telugu inference through the exported/quantized ONNX model. |
| `transcribe_multilingual.py` | Multilingual ONNX inference for all 22 scheduled languages (`as bn brx doi gu hi kn kok ks mai ml mni mr ne or pa sa sat sd ta te ur`). |
| `quantize.py` | ONNX quantization passes used to produce the int8 artifacts. |
| `prepare_browser_assets.py` | Download and SHA256-verify the two production archives (from `dl.byanr.com`) into `browser-assets/` for browser-inference validation. Output is generated, not committed. |
| `compare_browser_features.py` | Compare native ONNX Runtime outputs when fed production Rust features vs browser features (parity check for the web worker). |

## Setup

Requires Python 3.11+. Uses [uv](https://docs.astral.sh/uv/):

```bash
cd tools/voice-models
uv sync
```

## Producing the deployed artifacts

The deployed model archives are built by running the export → quantize →
archive steps above and are hosted at `dl.byanr.com/veena-byanr/`; the apps'
model catalogs (`apps/*/…/ModelCatalog*`) reference those downloads. The
engine integration test in `crates/rust-backend` skips the multilingual case
unless the extracted artifacts are present under `models/`.
