# rust_backend — Veena speech engine

Shared transcription engine for every Veena app. It runs IndicConformer ONNX
models (Telugu single-language and the 22-language multilingual model) through
`ort`, with the mel-spectrogram preprocessing, CTC decoding, and a C ABI
(`include/rust_backend.h`) consumed by:

- **apps/desktop** — Cargo path dependency inside the Tauri backend.
- **apps/ios** — statically linked via `RustBackend.xcframework` (built below).
- **apps/android** — `librust_backend.so` loaded through a JNI wrapper.

Model archives are **not** part of this repository; apps download them at
runtime, and the export pipeline lives in `tools/voice-models`.

## Desktop

Nothing to do — `apps/desktop/src-tauri` depends on this crate by path and
the root Cargo workspace builds it together with the app.

## iOS

Install the targets once:

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
```

Build both slices and pack them into an XCFramework (Apple Silicon simulators
only — `ort` ships no prebuilt runtime for `x86_64-apple-ios`):

```bash
cd crates/rust-backend
cargo build --release --target aarch64-apple-ios
cargo build --release --target aarch64-apple-ios-sim

rm -rf /tmp/RustBackend-build && mkdir -p /tmp/RustBackend-build/ios-arm64 /tmp/RustBackend-build/ios-arm64-simulator
cp target/aarch64-apple-ios/release/librust_backend.a /tmp/RustBackend-build/ios-arm64/
cp target/aarch64-apple-ios-sim/release/librust_backend.a /tmp/RustBackend-build/ios-arm64-simulator/
cp include/rust_backend.h /tmp/RustBackend-build/

rm -rf ../../apps/ios/frameworks/RustBackend.xcframework
xcodebuild -create-xcframework \
  -library /tmp/RustBackend-build/ios-arm64/librust_backend.a \
  -headers /tmp/RustBackend-build/ \
  -library /tmp/RustBackend-build/ios-arm64-simulator/librust_backend.a \
  -headers /tmp/RustBackend-build/ \
  -output ../../apps/ios/frameworks/RustBackend.xcframework
rm -rf /tmp/RustBackend-build
```

## Android

Put the NDK LLVM toolchain on `PATH` (see `.cargo/config.toml`), then:

```bash
cd crates/rust-backend
cargo build --release --target aarch64-linux-android
cp ../../target/aarch64-linux-android/release/librust_backend.so \
   ../../apps/android/app/src/main/jniLibs/arm64-v8a/
```

The `.so` is a build artifact — it is never committed; `apps/android` builds
against whatever you place in `jniLibs`.

## Tests

`cargo test` (run from this directory) transcribes the Telugu sample in
`tools/voice-models` against real model files. Download the model archives
first — see `tools/voice-models/README.md`. Tests skip themselves when the
multilingual model is absent.
