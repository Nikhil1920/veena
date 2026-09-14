# AGENTS.md

This file provides guidance to AI coding assistants working with code in this repository.

## Development Commands

**Prerequisites:**

- [Rust](https://rustup.rs/) (latest stable)
- [Bun](https://bun.sh/) package manager

**Core Development:**

```bash
# Install dependencies
bun install

# Run in development mode
bun run tauri dev
# If cmake error on macOS:
CMAKE_POLICY_VERSION_MINIMUM=3.5 bun run tauri dev

# Build for production
bun run tauri build

# Frontend only development
bun run dev        # Start Vite dev server
bun run build      # Build frontend (TypeScript + Vite)
bun run preview    # Preview built frontend
```

**Linting and Formatting (run before committing):**

```bash
bun run lint              # ESLint for frontend
bun run lint:fix          # ESLint with auto-fix
bun run format            # Prettier + cargo fmt
bun run format:check      # Check formatting without changes
bun run format:frontend   # Prettier only
bun run format:backend    # cargo fmt only
```

**Model Setup (Required for Development):**

```bash
mkdir -p src-tauri/resources/models
curl -o src-tauri/resources/models/silero_vad_v4.onnx https://blob.handy.computer/silero_vad_v4.onnx
```

**MUST READ [BUILD.md](BUILD.md) BEFORE attempting any build.** It contains platform-specific prerequisites, environment variables, and troubleshooting for the failures the build WILL hit (Windows `FTK1011`/MAX_PATH, Vulkan SDK, AppImage, etc.). Skipping BUILD.md is the most common way agents fail on this project — the short version is in "Project Quirks" below, but the full commands and fix sequences live in BUILD.md.

## Project Quirks & Non-Obvious Gotchas

Things that will trip you up if you don't look for them specifically. Read this before touching builds, bindings, or anything under `src-tauri/`.

### This is a rebranded fork of Handy

- The product is **Veena** (`tauri.conf.json`: `productName: "Veena"`, identifier `com.byanr.veena`, binary `veena`, lib `veena_app_lib`). User-facing strings and docs are Veena-branded; the name "Handy Keys" remains for the external handy-keys keyboard library it describes. Upstream is [cjpais/Handy](https://github.com/cjpais/Handy) (MIT). The updater endpoint (`dl.byanr.com`) is Veena's own.
- Veena-specific additions over upstream: the model catalog contains ONLY the two IndicConformer models (Telugu + 22-language Multilingual) in `managers/model.rs` — all upstream Handy models (Whisper, Parakeet, Moonshine, SenseVoice, GigaAM, Canary, Cohere) were removed. Every model downloads from `dl.byanr.com` (resumable, SHA256-verified, tar.gz-extracted for directory models) — nothing is bundled in the app bundle. Custom GGML `.bin` models are auto-discovered in the models dir.

### Build environment — the traps

- **Windows is the #1 failure point.** `transcribe-rs`/`whisper-rs-sys` compiles whisper.cpp with the Vulkan backend via CMake; deeply nested paths exceed Windows MAX_PATH → MSBuild `FTK1011` (FileTracker) errors. Required environment before any cargo build:
  - `CARGO_TARGET_DIR=C:\t` (or another short path)
  - `VULKAN_SDK` pointing at the installed Vulkan SDK
  - VS BuildTools `cmake` on `PATH` (often missing): `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin`
  - When switching target dirs, delete stale `whisper-rs-sys*` artifacts from the old one.
- **macOS:** use `CMAKE_POLICY_VERSION_MINIMUM=3.5 bun run tauri dev` (cmake policy error otherwise). The Apple Intelligence bridge (`swift/`) compiles only on macOS aarch64 via `build.rs`; stub elsewhere. `signingIdentity: "-"` means ad-hoc signing; `macOSPrivateApi: true` is intentional.
- **Linux:** AppImage bundling fails on Arch/rolling distros (bundled `linuxdeploy` strip is too old) — binary, deb, and rpm still build; use `bun run tauri build -- --bundles deb`. The raw binary cannot run standalone — it needs the resource files (tray icons, sounds, VAD model) co-located; install via the deb-extraction steps in BUILD.md. `main.rs` sets `WEBKIT_DISABLE_DMABUF_RENDERER=1` (DMABUF crash workaround, tauri#9394); the overlay requires `libgtk-layer-shell0` (disable with `VEENA_NO_GTK_LAYER_SHELL=1`). CI pins webkit2gtk 2.44 on Ubuntu 24.04.
- **Release builds are slow** (`lto = true`, `codegen-units = 1` in `[profile.release]`, defined in the workspace root `Cargo.toml`) — expect a long first build, especially with the whisper.cpp Vulkan shader compile.
- **Updater artifacts require signing:** `createUpdaterArtifacts: true`, so release builds need `TAURI_SIGNING_PRIVATE_KEY`. The private key is **not** in this repository — it lives outside the tree and is injected via the environment (locally or as a CI secret). The public key is embedded in `tauri.conf.json`.

### Dependencies that are NOT on crates.io

- `[patch.crates-io]` overrides `tauri-runtime`, `tauri-runtime-wry`, `tauri-utils` with the [cjpais/tauri fork](https://github.com/cjpais/tauri) (branch `handy-2.10.2`). Git deps: `rdev` (rustdesk-org), `vad-rs` (cjpais), `rodio` (cjpais), `tauri-nspanel` (ahkohd, macOS only). **Offline builds are impossible** — upstream GitHub availability is a build prerequisite.
- `rust_backend = { path = "../../../crates/rust-backend" }` is the shared Veena speech engine (`crates/rust-backend`, an `ort`-based ONNX crate in this workspace), also consumed by the iOS and Android apps.

### Generated code — never hand-edit

- `src/bindings.ts` is regenerated at app startup by tauri-specta **in debug builds only** (`lib.rs` setup, `#[cfg(debug_assertions)]`). Running `bun run tauri dev` / `cargo run` rewrites it. To change command types: add `#[specta::specta]` to the command, add it to `collect_commands!`, run the app, then commit the regenerated file. Release builds do NOT regenerate it.
- Tray menu strings are generated **at compile time** by `src-tauri/build.rs` from `src/i18n/locales/*/translation.json` (the English `tray` section defines the struct schema). New/changed tray strings = edit the locale JSON, then rebuild. The generated file lives in `OUT_DIR` and is not covered by `cargo fmt`.

### Runtime architecture quirks

- **Portable mode:** if a marker file named `portable` sits next to the executable, ALL user data (settings, models, recordings, SQLite history, logs, WebView2 cache) moves into a `Data/` directory beside the exe. Code must use the portable-aware wrappers (`portable::app_data_dir`, `app_log_dir`, `resolve_app_data`, `store_path`) — never call `app.path().app_data_dir()` directly for user data.
- The main window is created programmatically in `setup()` (not declared in `tauri.conf.json`'s `windows` array) precisely so portable mode can set the WebView2 `data_directory`. It starts hidden (`visible(false)`) and is shown later per settings/onboarding.
- **Two swappable keyboard backends** (`settings.keyboard_implementation`): rdev (`shortcut/tauri_impl.rs`) and handy-keys (`shortcut/handy_keys.rs`). The `cancel` shortcut is registered dynamically after startup, not with the others. Transcribe bindings route through `TranscriptionCoordinator`.
- **Single instance:** a second launch relays CLI flags (`--toggle-transcription`, `--toggle-post-process`, `--cancel`, `--start-hidden`, `--no-tray`, `--debug`) to the running instance via `tauri_plugin_single_instance`, then exits. `--debug`/`--start-hidden` are runtime-only overrides — they do NOT persist.
- `silero_vad_v4.onnx` is git-tracked under `src-tauri/resources/models` (do not delete it). GPU/accelerator enumeration is pre-warmed on a background thread at startup to avoid a UI freeze when the user opens Advanced settings.

### Frontend & tooling

- Vite dev server is `strictPort: 1420` and `tauri.conf.json`'s `devUrl` plus the Playwright tests all assume `http://localhost:1420` — the port must be free. There are two Vite entry points: the main app and the overlay (`src/overlay/index.html`).
- `bun install` runs `scripts/check-nix-deps.ts` (postinstall), which regenerates `.nix/bun.nix` from `bun.lock` via bun2nix (skipped on Windows; failures are safe to ignore outside Nix). Commit `bun.lock` + `.nix/bun.nix` + `.nix/bun-lock-hash` together. A Nix flake (`flake.nix`) is available for dev environments.
- ESLint enforces i18n (`eslint-plugin-i18next`): no hardcoded strings in JSX. New strings go into `src/i18n/locales/en/translation.json`; `bun run check:translations` validates locale parity.
- The Cargo lib name `veena_app_lib` keeps an intentional `_lib` suffix (avoids the Windows bin/lib name collision, rust-lang/cargo#8519). Do not rename.
- `.github/workflows/build.yml` is a reusable `workflow_call` template (no standalone CI entrypoint); it installs the Vulkan SDK on Windows/Ubuntu runners and pins webkit2gtk 2.44 on Ubuntu 24.04.

## Architecture Overview

Veena is a cross-platform desktop speech-to-text application built with Tauri 2.x (Rust backend + React/TypeScript frontend).

### Backend Structure (src-tauri/src/)

- `lib.rs` - Main entry point, Tauri setup, manager initialization
- `managers/` - Core business logic:
  - `audio.rs` - Audio recording and device management
  - `model.rs` - Model downloading and management
  - `transcription.rs` - Speech-to-text processing pipeline
  - `history.rs` - Transcription history storage
- `audio_toolkit/` - Low-level audio processing:
  - `audio/` - Device enumeration, recording, resampling
  - `vad/` - Voice Activity Detection (Silero VAD)
- `commands/` - Tauri command handlers for frontend communication
- `cli.rs` - CLI argument definitions (clap derive)
- `shortcut.rs` - Global keyboard shortcut handling
- `settings.rs` - Application settings management
- `overlay.rs` - Recording overlay window (platform-specific)
- `signal_handle.rs` - `send_transcription_input()` reusable function
- `utils.rs` - Platform detection helpers

### Frontend Structure (src/)

- `App.tsx` - Main component with onboarding flow
- `components/` - React UI components:
  - `settings/` - Settings UI
  - `model-selector/` - Model management interface
  - `onboarding/` - First-run experience
  - `overlay/` - Recording overlay UI
  - `update-checker/` - App update notifications
  - `shared/`, `ui/`, `icons/`, `footer/` - Shared components
- `hooks/useSettings.ts` - Settings state management hook
- `stores/settingsStore.ts` - Zustand store for settings
- `bindings.ts` - Auto-generated Tauri type bindings (via tauri-specta)
- `overlay/` - Recording overlay window entry point
- `lib/types.ts` - Shared TypeScript type definitions

### Key Architecture Patterns

**Manager Pattern:** Core functionality organized into managers (Audio, Model, Transcription) initialized at startup and managed via Tauri state.

**Command-Event Architecture:** Frontend → Backend via Tauri commands; Backend → Frontend via events.

**Pipeline Processing:** Audio → VAD → IndicConformer (ONNX/ORT) → Text output → Clipboard/Paste

**State Flow:** Zustand → Tauri Command → Rust State → Persistence (tauri-plugin-store)

### Technology Stack

**Core Libraries:**

- `whisper-rs` - Local Whisper inference with GPU acceleration
- `cpal` - Cross-platform audio I/O
- `vad-rs` - Voice Activity Detection
- `rdev` - Global keyboard shortcuts
- `rubato` - Audio resampling
- `rodio` - Audio playback for feedback sounds

### Application Flow

1. **Initialization:** App starts minimized to tray, loads settings, initializes managers
2. **Model Setup:** First-run downloads the recommended IndicConformer Multilingual model
3. **Recording:** Global shortcut triggers audio recording with VAD filtering
4. **Processing:** Audio sent to the selected IndicConformer model for transcription
5. **Output:** Text pasted to active application via system clipboard

### Settings System

Settings are stored using Tauri's store plugin with reactive updates:

- Keyboard shortcuts (configurable, supports push-to-talk)
- Audio devices (microphone/output selection)
- Model preferences (IndicConformer Telugu / Multilingual)
- Audio feedback and translation options

### Single Instance Architecture

The app enforces single instance behavior — launching when already running brings the settings window to front rather than creating a new process. Remote control flags (`--toggle-transcription`, etc.) work by launching a second instance that sends args to the running instance via `tauri_plugin_single_instance`, then exits.

## Internationalization (i18n)

All user-facing strings must use i18next translations. ESLint enforces this (no hardcoded strings in JSX).

**Adding new text:**

1. Add key to `src/i18n/locales/en/translation.json`
2. Use in component: `const { t } = useTranslation(); t('key.path')`

**File structure:**

```
src/i18n/
├── index.ts           # i18n setup (auto-discovers locales from the filesystem)
├── languages.ts       # Language metadata
└── locales/
    └── en/translation.json  # English (only UI locale)
```

The app is India-focused: **English is the only shipped UI locale** — all other locales were removed. Adding a language means creating `locales/{code}/translation.json` and an entry in `languages.ts` (see its doc comment). The `tray` section of the English file defines the struct for the compile-time-generated tray strings (`build.rs`).

For translation contribution guidelines, see [CONTRIBUTING_TRANSLATIONS.md](CONTRIBUTING_TRANSLATIONS.md).

## Code Style

**Rust:**

- Run `cargo fmt` and `cargo clippy` before committing
- Handle errors explicitly (avoid unwrap in production)
- Use descriptive names, add doc comments for public APIs

**TypeScript/React:**

- Strict TypeScript, avoid `any` types
- Functional components with hooks
- Tailwind CSS for styling
- Path aliases: `@/` → `./src/`

## CLI Parameters

Veena supports command-line parameters on all platforms for integration with scripts, window managers, and autostart configurations.

**Implementation:** `cli.rs` (definitions), `main.rs` (parsing), `lib.rs` (applying), `signal_handle.rs` (shared logic)

| Flag                     | Description                                                    |
| ------------------------ | -------------------------------------------------------------- |
| `--toggle-transcription` | Toggle recording on/off on a running instance                  |
| `--toggle-post-process`  | Toggle recording with post-processing on/off                   |
| `--cancel`               | Cancel the current operation on a running instance             |
| `--start-hidden`         | Launch without showing the main window (tray icon visible)     |
| `--no-tray`              | Launch without system tray (closing window quits the app)      |
| `--debug`                | Enable debug mode with verbose (Trace) logging                 |

**Key design decisions:**

- CLI flags are runtime-only overrides — they do NOT modify persisted settings
- Remote control flags work via `tauri_plugin_single_instance`: second instance sends args, then exits
- `send_transcription_input()` in `signal_handle.rs` is shared between signal handlers and CLI

## Debug Mode

Access debug features: `Cmd+Shift+D` (macOS) or `Ctrl+Shift+D` (Windows/Linux)

## Platform Notes

- **macOS**: Metal acceleration, accessibility permissions required for keyboard shortcuts
- **Windows**: Vulkan acceleration, code signing
- **Linux**: OpenBLAS + Vulkan, limited Wayland support, overlay uses GTK layer shell (disable with `VEENA_NO_GTK_LAYER_SHELL=1`)

## Troubleshooting

See the [Troubleshooting](README.md#troubleshooting) section in README.md.

## GitHub workflow for AI coding assistants

**MANDATORY. Before opening any PR, issue, or discussion in this repo: you MUST read the relevant template file and follow it strictly.** That includes sections that look "ceremonial" — checklists, AI Assistance disclosures, "Human Written Description". A generic Summary/Test-plan layout is not acceptable.

- **Opening a PR:** Read [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md). Every section listed there is mandatory. If a section requires a human-written paragraph (e.g. "Human Written Description"), leave a clear TODO placeholder and ask the human contributor to fill it in — do not invent their voice.
- **Opening an issue:** Read [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/). Blank issues are disabled; pick the right template (`bug_report.md` for bugs). Feature requests do not belong in issues — they go to [Discussions](https://github.com/cjpais/Handy/discussions) (see `.github/ISSUE_TEMPLATE/config.yml`).
- **Proposing a feature:** Handy is under a feature freeze. New features require community support gathered in [Discussions](https://github.com/cjpais/Handy/discussions) before any PR is opened — see the PR template's "Community Feedback" section.
- **Translations:** Follow [CONTRIBUTING_TRANSLATIONS.md](CONTRIBUTING_TRANSLATIONS.md).
- **Full contributor workflow:** [CONTRIBUTING.md](CONTRIBUTING.md).

**Commits:** Use conventional commit prefixes (`feat:`, `fix:`, `docs:`, `refactor:`, `chore:`). Focus the message on *why*, not *what*.
