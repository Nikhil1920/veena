# Veena iOS

Offline speech-to-text for the 22 scheduled languages of India. Record or
import audio, transcribe on-device with IndicConformer models through the
shared engine in [`crates/rust-backend`](../../crates/rust-backend), and keep
a local history.

## Project layout

- `project.yml` — the single source of truth for the Xcode project
  ([XcodeGen](https://github.com/yonaskolb/XcodeGen) definition).
- `Veena/` — application sources. `Veena/Bridging/rust_backend.h` is the
  app-side copy of the engine's C ABI header.
- `VeenaKeyboard/` — the voice keyboard extension. Keyboards can't record, so
  its mic hands dictation to the app (`Veena/Keyboard/`), which records in the
  background and sends the text back through the App Group.
- `Shared/` — the keyboard ↔ app contract (`KeyboardBridge.swift`), compiled
  into both targets.
- `APP_STORE_LISTING.md` — App Store metadata.

The keyboard's design, setup tour and limits are described in
[`docs/voice-keyboard.md`](../../docs/voice-keyboard.md).

## Building

1. Build the engine XCFramework (see
   [`crates/rust-backend/README.md`](../../crates/rust-backend/README.md)) and
   place it at `frameworks/RustBackend.xcframework`. The framework is a build
   artifact and is never committed.
2. Generate the Xcode project and open it:

   ```bash
   brew install xcodegen   # once
   cd apps/ios
   xcodegen generate
   open Veena.xcodeproj
   ```

`Veena.xcodeproj` is generated from `project.yml` and not tracked — change
`project.yml`, not the project file.

Signing: the app and the keyboard share the App Group
`group.com.byanr.veena` (`Veena/Veena.entitlements`,
`VeenaKeyboard/VeenaKeyboard.entitlements`). Set your team on both targets and
let Xcode register the group for `com.byanr.veena` and
`com.byanr.veena.keyboard`. Simulator builds need no team; build arm64 only
(`ARCHS=arm64`), since the engine has no x86_64 simulator slice.

### Testing the keyboard in the Simulator

Simulators have no voice. In Debug builds, launching the app with
`-keyboardTestAudio <name>` makes keyboard dictations transcribe
`Documents/<name>` instead of the microphone; the setting survives the
relaunches Settings changes cause (`-keyboardTestAudio none` clears it):

```bash
xcrun simctl launch <udid> com.byanr.veena -keyboardTestAudio sample.wav
```

Model archives are downloaded on first launch from Veena's model CDN; nothing
model-related is bundled.
