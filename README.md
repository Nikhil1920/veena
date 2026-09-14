# Veena

Offline speech-to-text for all 22 scheduled languages of India. Speak in any
Indic language — Telugu, Hindi, Tamil, and nineteen more — and get text on
your device. No cloud, no account, no telemetry: IndicConformer models run
locally through ONNX Runtime.

This monorepo contains every Veena product plus the shared engine and model
tooling that keep them in sync.

## Repository map

| Path | What it is | Stack |
| --- | --- | --- |
| [`crates/rust-backend`](crates/rust-backend) | Shared speech engine: mel features, ONNX inference, CTC decoding, C ABI. Consumed by desktop, iOS, and Android. | Rust (`ort`) |
| [`apps/desktop`](apps/desktop) | Global push-to-talk dictation app: press a shortcut, speak, text is typed into any app. | Tauri 2 · React · TypeScript |
| [`apps/ios`](apps/ios) | iPhone dictation app with on-device models and local history. | SwiftUI · XcodeGen |
| [`apps/android`](apps/android) | Android dictation app; engine linked as a prebuilt `.so` through JNI. | Kotlin · Jetpack Compose |
| [`apps/web`](apps/web) | Marketing site and browser transcription workspace (`/transcribe/`) — the same models running as WebAssembly/WASM runtime in the browser. | Astro · React · TypeScript |
| [`tools/voice-models`](tools/voice-models) | Model pipeline: export, quantize, and validate the IndicConformer ONNX artifacts the apps download. | Python (uv) |
| [`tools/browser-reference`](tools/browser-reference) | Rust reference implementation used to validate browser inference parity. | Rust |
| [`docs/`](docs) | Cross-cutting technical notes (e.g. the browser-transcription feasibility assessment). | — |

## Quick starts

```bash
# Desktop (macOS / Windows / Linux) — bun + Rust toolchain
cd apps/desktop && bun install && bun run tauri dev

# Website
cd apps/web && pnpm install && pnpm dev

# Engine tests (need model files, see tools/voice-models/README.md)
cd crates/rust-backend && cargo test

# iOS / Android — see apps/ios/README.md and apps/android/README.md;
# both link the engine built from crates/rust-backend
```

Per-app requirements and troubleshooting live in each app's `README.md` /
`BUILD.md`; the desktop app's `AGENTS.md` is the deepest guide to its
codebase.

## Models

Model weights are **not** in this repository. Apps download quantized
IndicConformer archives (Telugu single-language and 22-language multilingual)
at runtime; `tools/voice-models` documents how those archives are produced
from AI4Bharat's [IndicConformer](https://ai4bharat.iitm.ac.in) models.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Translating the desktop app?
[`apps/desktop/CONTRIBUTING_TRANSLATIONS.md`](apps/desktop/CONTRIBUTING_TRANSLATIONS.md).

## License

[MIT](LICENSE). The desktop app is a fork of
[Handy](https://github.com/cjpais/Handy) by CJ Pais — much of its
architecture is his work, and the fork carries his MIT notice. Model weights
derive from AI4Bharat's IndicConformer models (see `tools/voice-models`).
