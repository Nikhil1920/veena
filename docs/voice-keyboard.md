# Veena voice keyboard — Android and iOS

Updated 31 October 2026. Supersedes the scope of
[`android-voice-keyboard-feasibility.md`](android-voice-keyboard-feasibility.md)
(Android-only, September 2026), which remains the background for the Android
IME's design.

**Goal:** a keyboard mic — like the one in the top-right of Gboard — that
dictates into any app with Veena's on-device models.

## Verdict

| Platform | Feasible? | What ships |
|---|---|---|
| Android | Yes | **Veena Voice Keyboard**, a voice-first input method you switch to with 🌐; and **Veena voice input**, which other keyboards' mic keys open directly. |
| iOS | Yes | **Veena keyboard extension**. On iOS 26 and later, in Telugu, it records and transcribes by itself (added 21 November 2026). Otherwise its mic hands recording to the Veena app; the app transcribes on device and the text comes back to the keyboard. |

What is *not* possible on either platform: making **Gboard's** or **iOS's own**
mic key use Veena. Gboard and Samsung Keyboard always use Google/Samsung voice
typing; the iOS dictation key is Apple's. Users switch to Veena's keyboard
instead (🌐), or — on Android — use a keyboard whose mic key accepts another
voice input app.

## Android

Both input methods live in the app's process and share its engine, models and
settings (`ime/`):

- **`VeenaImeService` — Veena Voice Keyboard.** Unchanged in concept since the
  feasibility doc: language chip, hold-to-talk (or tap) mic, globe, delete.
- **`VeenaVoiceInputService` — Veena voice input (new).** Declared with a single
  `voice`-mode, auxiliary subtype (`res/xml/method_voice.xml`). Keyboards that
  support third-party voice input find it through
  `InputMethodManager.getShortcutInputMethodsAndSubtypes()` and switch to it
  from their mic key. It starts listening as soon as it opens, inserts the text
  on the next tap, and switches back with `switchToPreviousInputMethod()`. It is
  the same panel as the keyboard (a subclass), with a "back to your keyboard"
  key in place of the globe. Shipping precedent: FUTO Voice Input.
  - Works with keyboards that hand their mic to another voice IME — verified
    with HeliBoard 4.2; FUTO's compatibility list also names SwiftKey,
    Grammarly, FUTO Keyboard, FlorisBoard, AnySoftKeyboard, Unexpected Keyboard
    and the AOSP keyboard.
  - Android checks **system** voice IMEs first, so while *Google Voice Typing*
    is enabled, keyboards pick it. Users turn it off in the same keyboard list
    (the tour says so).

Fixes made alongside, all of which affected the shipped keyboard:

- **Shared engine, two languages.** The app and the keyboard each have their
  own language but share one `EngineManager`, so after a keyboard dictation the
  app transcribed in the keyboard's language. Transcription now always names its
  language and loads it under the engine lock; languages on the multilingual
  model switch without a reload.
- **Unload left a "ready" engine with no model.** The keyboard's unload didn't
  reset the engine state, so app dictation failed with "Engine not ready". The
  unload now resets state, waits while the app is on screen, and runs on a
  process-wide timer instead of `onDestroy` — switching keyboards destroys the
  service, and the mic-key flow would otherwise reload the model every time.
- **URL fields were treated as passwords** (`TYPE_TEXT_VARIATION_URI` has the
  same bits as `TYPE_NUMBER_VARIATION_PASSWORD`), turning the mic off in browser
  address bars. Variations are now checked within their input class (unit test
  added).
- **Edge-to-edge (Android 15+):** the IME window extends under the navigation
  bar, which covered the globe, mic and delete keys. The panel now pads for it.
- The keyboard's "Open Veena" link reaches a running app (`onNewIntent`).

### Guided tour

Settings → Voice keyboard (and a dismissible "Dictate in any app" card on the
Dictate screen) opens a step-by-step tour: welcome → turn the keyboard on →
microphone → keyboard language → switch to Veena and try it (live checks for
"Veena is the active keyboard" and "Dictation works") → optionally turn on Veena
voice input for your keyboard's mic key → summary with the mic-button mode.
Every step re-checks its status when the user comes back from a system screen;
returning users land on the first unfinished step.

## iOS

### Why a hand-off

Keyboard extensions cannot record audio, and they run under a tight memory cap
that a 150–650 MiB model would blow through. So the keyboard is UI plus a
remote control; the Veena app records and transcribes. This is how shipping
dictation keyboards (Wispr Flow and others) work, and Apple DTS has confirmed
both halves: App Review allows a keyboard to open its containing app
(guideline 4.4.1 forbids launching *other* apps), and the supported way to do
it is calling `open(_:options:completionHandler:)` on the `UIApplication`
found up the responder chain. Since iOS 26 that requires **Allow Full Access**,
which the App Group needs anyway. There is no API to bring the user back
automatically.

### How it works

- **Contract** — `Shared/KeyboardBridge.swift`, compiled into both targets. Two
  small JSON files in the App Group container (`group.com.byanr.veena`), each
  with one writer: the app owns `state.json` (phase, heartbeat, level, result),
  the keyboard owns `commands.json` (start/stop/cancel with a request ID).
  Darwin notifications wake the other side; settings and the list of installed
  languages live in the group's `UserDefaults`.
- **Session** — `Veena/Keyboard/KeyboardSessionController.swift`. The first mic
  tap with no live session opens `veena://keyboard/session`. The app turns the
  microphone on (`.playAndRecord`, mixable so other audio keeps playing,
  `UIBackgroundModes: audio`) and shows how to go back ("◀ App" in the status
  bar, or a swipe along the bottom edge). The keyboard the user returns to
  starts the dictation by itself. While the session lives the app keeps the
  input running in the background, but only keeps samples between start and
  stop; later taps start instantly. The session ends after 5/15/60 minutes
  without dictation (setting), on an audio interruption, or from the app.
  iOS shows the microphone indicator throughout.
- **Keyboard** — `VeenaKeyboard/`: language chip (installed languages only),
  mic with level ring and timer, cancel, globe (only where iOS doesn't draw
  one), delete with repeat, space, return. Tap-to-talk by default; hold-to-talk
  in Settings. Notices cover missing Full Access, microphone permission, a
  missing model, and an ended session. Secure fields always get the system
  keyboard, so passwords never reach it.
- **Engine** — the app's `RustEngine` is shared with in-app dictation; every
  transcription names its language (same fix as Android). Keyboard audio never
  touches disk and keyboard dictations stay out of history; the transcript
  passes through the shared container and is cleared ten seconds later.

### Dictating without the app (iOS 26 and later, Telugu)

Added 21 November 2026, after [Hex](https://github.com/anomalyco/hex) showed
the way round the microphone rule: iOS refuses a keyboard extension the
microphone, but a `WKWebView` inside one may call `getUserMedia`, and
WebKit's media process then records under the containing app's permission.
Hex transcribes with Apple's speech models. Veena has to run its own, which
is the second problem: iOS ends a keyboard that grows past roughly 60–90 MB,
and the Telugu model needs several hundred. Mapping the weights from disk got
the native engine down to about 45 MB before any audio and 20 MB more per
2 s of it, which is still too much. So the model runs in the web view as
well: its content process is a separate one, with room.

- **Web side** — `VeenaKeyboard/Web/`. `page.js` records (the capture Hex
  uses) and resamples to 16 kHz. `engine.js`, a worker, runs the model on ONNX
  Runtime Web's WASM build and decodes; `features.js` is a port of
  `apps/web`'s pipeline, held to the same fixtures by
  `apps/web/tests/keyboard-engine.test.mjs`. `onnx.js` rewrites the model so
  its weights are copied once instead of twice.
- **Keyboard side** — `KeyboardWebEngine.swift` hosts the hidden web view and
  serves it the page from the bundle and the model from the App Group, in
  1 MB pieces. `KeyboardLocalDictation.swift` decides when this route may be
  used and watches over each dictation.
- **Models** moved from the app's Application Support to `models/` in the App
  Group container so the keyboard can read them (`Shared/SharedModelStore.swift`).
- **Route order** — a live session in the app first (native speed), then the
  keyboard itself, then opening Veena. The keyboard takes a dictation only
  with Full Access, the microphone allowed in Veena, a model of at most 250 MB
  (the multilingual one is 676 MB: every other language still hands off),
  32 MB of memory to spare, and Settings → Keyboard → *Dictate without
  opening Veena* on.
- **When it fails** — a dictation that never started recording goes to the
  app as before. Any failure sends that keyboard's later dictations through
  the app, and two in a row stop the route on that iPhone until the setting
  is turned on again. If iOS ends the engine's process for memory, the
  keyboard itself is unharmed.
- **Limits** — the model loads when the mic is first tapped (1.3–3.2 s in the
  simulator, while the user is already speaking) and stays for a minute after
  the keyboard leaves, five after the last dictation. A recording stops at
  two minutes. One thread only: a page served from the keyboard's own scheme
  gets no shared memory.

Measured in the iOS 27.1 simulator (Apple M4, Debug build), which enforces
no memory limit: the keyboard itself rests near 35 MB with the web view
loaded and peaks at 41 MB while the model passes through; the web view's
process peaks near 610 MB while loading and settles near 350–400 MB.
Transcribing takes about 0.3–0.45 of the audio's length (23 s in 4.4 s, 6 s
in 1.7 s). **Not measured on an iPhone:** whether that process is allowed
its memory, how fast an iPhone runs it, and the capture itself, which so far
rests on Hex's reports from iPhones and on the simulator.

### Guided tour

A sheet (`Features/Keyboard/KeyboardSetupView.swift`), reachable from a
dismissible card on Dictate, from Settings → Keyboard, and from the keyboard's
"Open Veena" buttons: welcome → add the keyboard and Allow Full Access (Open
Settings lands on the app's page with its Keyboards entry) → microphone →
keyboard language (download more without changing the app's language) → try it
in a live field → how the hand-off works in other apps, with the session-length
setting. Status comes from the system keyboard list and from the keyboard
checking in once it runs with Full Access. Turning those switches on in
Settings can make iOS relaunch the app, so the tour remembers its step and
reopens there.

### Shipping notes

- Register the App Group `group.com.byanr.veena` for both
  `com.byanr.veena` and `com.byanr.veena.keyboard` (Xcode's automatic signing
  does this once a team is set).
- App Review: explain the background-audio use (keyboard dictation sessions)
  and the Full Access request in the review notes; the keyboard makes no
  network requests.

## Verification (simulator and emulator, 31 Oct 2026)

- **iOS 26.5 simulator** (iPhone Air): full tour; dictation into the tour's test
  field; into Messages with a live session; and the cold hand-off from Messages
  (Veena opens → "◀ Messages" → dictation auto-starts → Telugu text inserted),
  both with the app suspended and with it not running. Dark and light keyboard
  appearance checked.
- **Android 15 emulator:** tour end to end; Hindi dictation through the Veena
  keyboard; HeliBoard's toolbar mic → Veena voice input → text inserted →
  HeliBoard back. Unit tests pass.
- Simulators have no voice, so both apps have debug-only hooks that swap the
  microphone samples for a file: iOS `-keyboardTestAudio <file in Documents>`
  (DEBUG builds), Android `files/keyboard-test-audio.wav` (debuggable builds).
- **Not yet done:** physical devices, real-microphone dictation through the
  keyboards, battery cost of long iOS sessions, App Store / Play review.

## Possible follow-ups

- Android: answer `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` with a Veena
  dialog, so apps with their own mic buttons can use Veena too.
- iOS: a Live Activity showing the live session on the Lock Screen and Dynamic
  Island, with an off switch.
- Both: streaming-looking partial text by transcribing in chunks while
  recording.
