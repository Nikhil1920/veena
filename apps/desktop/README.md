# Veena Desktop

Offline speech-to-text for the 22 scheduled languages of India, as a desktop
app for macOS, Windows, and Linux. Press a shortcut, speak in any Indic
language, and the text is typed into whatever app is focused — entirely
on-device.

Veena Desktop is a fork of [Handy](https://github.com/cjpais/Handy) by
CJ Pais (MIT), re-targeted from Whisper to Veena's IndicConformer ONNX models
through the shared engine in [`crates/rust-backend`](../../crates/rust-backend).
A lot of credit for the app's architecture belongs to the Handy project and
its contributors.

## Highlights

- **Offline Indic transcription** — Telugu and multilingual (22-language)
  IndicConformer models run locally through `ort`; no cloud, no telemetry.
- **Global push-to-talk** — configurable global shortcut, handy-keys support,
  optional overlay and tray feedback.
- **Post-processing** — optional LLM-based cleanup of raw transcripts.
- **Privacy** — recordings, history, and settings stay in your app data
  directory; portable mode keeps everything next to the executable.

## Development

Prerequisites and per-platform build instructions: [BUILD.md](BUILD.md).
Working in the codebase (commands, architecture notes, traps):
[AGENTS.md](AGENTS.md) — start there.

```bash
bun install
bun run tauri dev      # macOS cmake quirk: CMAKE_POLICY_VERSION_MINIMUM=3.5
```

The Rust backend builds as part of the repository-wide Cargo workspace; the
engine crate is pulled in automatically.

## Translating the app

See [CONTRIBUTING_TRANSLATIONS.md](CONTRIBUTING_TRANSLATIONS.md).

## License

MIT — see the repository root [LICENSE](../../LICENSE).
