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
- `Veena/Engine/` — the engine wrapper, and `LiveDictation`, which shows a
  dictation's text in the Dictate box while it is still being recorded
  (Settings → Live Transcription turns it off). Keyboard dictations show
  their text only once finished.
- `Veena/Features/Conversation/` — the Conversation tab: full-screen
  captions of the people around you, one line per utterance, with typed
  replies shown just as big (like Live Transcribe). It listens while the
  screen is in front; the engine's conversation captions
  (`Engine/ConversationTranscriber.swift`) do the work, and no audio is kept.
  With saving on, each conversation is one History entry.
- `VeenaKeyboard/` — the voice keyboard extension. Its mic takes one of two
  routes. On iOS 26 and later the keyboard records and transcribes by itself
  (`KeyboardLocalDictation`) when its language has a small model on the
  device: Telugu's own, or a language-specific one downloaded from Settings →
  Keyboard (`ModelCatalog.keyboardModel`). A hidden web view runs the page in
  `VeenaKeyboard/Web/`, which records the microphone and runs the model on
  ONNX Runtime Web. Otherwise it hands dictation to the app
  (`Veena/Keyboard/`), which records in the background and sends the text back
  through the App Group.
- `Shared/` — what the app and the keyboard have in common, compiled into
  both targets: the hand-off contract (`KeyboardBridge.swift`) and where the
  models are (`SharedModelStore.swift`).
- `scripts/fetch-keyboard-runtime.sh` — downloads the pinned ONNX Runtime Web
  build into `VeenaKeyboard/Web/ort/`.
- `APP_STORE_LISTING.md` — App Store metadata.

The keyboard's design, setup tour and limits are described in
[`docs/voice-keyboard.md`](../../docs/voice-keyboard.md).

## Building

1. Build the engine XCFramework (see
   [`crates/rust-backend/README.md`](../../crates/rust-backend/README.md)) and
   place it at `frameworks/RustBackend.xcframework`. The framework is a build
   artifact and is never committed.
2. Fetch the web runtime the keyboard's own dictation runs on. Like the
   XCFramework it is a build artifact and is never committed; without it the
   keyboard still builds and always hands off to the app.

   ```bash
   apps/ios/scripts/fetch-keyboard-runtime.sh
   ```
3. Generate the Xcode project and open it:

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

That covers dictations the app records. For the ones the keyboard records by
itself, a Debug keyboard transcribes `keyboard/test-audio.wav` in the App Group
container (16-bit PCM) instead of what its microphone heard:

```bash
group=$(xcrun simctl get_app_container <udid> com.byanr.veena groups | cut -f2)
cp sample.wav "$group/keyboard/test-audio.wav"
```

The keyboard logs each step, with its own memory, under the subsystem
`com.byanr.veena`, category `keyboard-local`.

### Testing dictation in the Simulator

Like `-keyboardTestAudio`, the app's own mic button can hear a file. In Debug
builds, `-micTestAudio <name>` makes dictations hear `Documents/<name>`
instead of the microphone, played at the pace a microphone would deliver it,
so the live text (`Veena/Engine/LiveDictation.swift`) can be watched as it
grows. The Conversation tab hears the same file while it listens (pausing
pauses it), then silence:

```bash
xcrun simctl launch <udid> com.byanr.veena -micTestAudio sample.wav
```

### Testing the review prompt

After a dictation in the app, once someone dictates regularly (10
dictations on 3 days, keyboard ones included), Veena asks the App Store for
its rating sheet, at most every 60 days (`Veena/Data/ReviewPrompt.swift`).
Builds run from Xcode always show the sheet when asked (Submit does nothing);
TestFlight builds never show it. In Debug builds, `-reviewPromptTest YES`
makes every dictation ask:

```bash
xcrun simctl launch <udid> com.byanr.veena -reviewPromptTest YES -micTestAudio sample.wav
```

Model archives are downloaded on first launch from Veena's model CDN; nothing
model-related is bundled. They are unpacked into `models/` in the App Group
container, where the keyboard reads them too. Versions before 1.2 kept them
in the app's Application Support; the app moves them over on its first launch.
