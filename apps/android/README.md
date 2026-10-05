# Veena Android

Offline speech-to-text for the 22 scheduled languages of India. Hold to speak
(or import a recording), transcribe on-device with IndicConformer models
through the shared engine in [`crates/rust-backend`](../../crates/rust-backend),
and review past transcriptions in a local history.

- Kotlin + Jetpack Compose, minSdk 26, `arm64-v8a` only.
- The engine is linked as a prebuilt `librust_backend.so`; the thin JNI
  wrapper lives in `app/src/main/cpp/` (`rust_backend_jni.cpp`).
- A voice-first keyboard (`ime/`, `VeenaImeService`) brings the same on-device
  dictation to any app's text field; enable it from Settings → Voice keyboard.
  See [`docs/android-voice-keyboard-feasibility.md`](../../docs/android-voice-keyboard-feasibility.md)
  for the design.
- Model archives download on first launch from Veena's model CDN
  (see `data/ModelCatalog.kt`) — nothing model-related is bundled.

## Building

1. Build the engine library and place it in `jniLibs` (see
   [`crates/rust-backend/README.md`](../../crates/rust-backend/README.md) for
   NDK/PATH setup):

   ```bash
   cd crates/rust-backend
   cargo build --release --target aarch64-linux-android
   mkdir -p ../../apps/android/app/src/main/jniLibs/arm64-v8a
   cp ../../target/aarch64-linux-android/release/librust_backend.so \
      ../../apps/android/app/src/main/jniLibs/arm64-v8a/
   ```

   `librust_backend.so` is a build artifact and is never committed — the app
   does not assemble without it.

2. Open this directory in Android Studio (or run `./gradlew` here) and build
   as usual.

## Signing

Release signing reads `apps/android/keystore.properties` (gitignored); paths
are relative to `apps/android`:

```properties
storeFile=../../../keys/my-release-key.keystore
storePassword=…
keyAlias=key0
keyPassword=…
```

Then `./gradlew :app:bundleRelease` writes the signed bundle to
`app/build/outputs/bundle/release/app-release.aab`. Without the file the
bundle is built unsigned. Use Android Studio's JDK if the system one is newer
than Gradle supports:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
Never commit keystores or the signed `.aab`/`.apk` output.
