# Veena Android voice keyboard feasibility

Assessment date: 20 September 2026.

**Recommendation: proceed with a dedicated voice-first input method (IME) inside the existing `veena-android` APK, reusing the current Rust/ONNX engine, mic capture and model downloads unchanged.** "STT wherever the user wants to type" on Android means an `InputMethodService`: it is the only mechanism that can insert text into arbitrary apps' text fields without overlay or accessibility permissions, and it is a proven shipping pattern for on-device dictation keyboards (Gboard voice typing; FUTO Voice Input ships a mic-first IME with downloadable local models on Play today). All the hard parts Veena already owns — an on-device IndicConformer engine with a JNI wrapper, 16 kHz mic capture, resumable verified model downloads — were deliberately built UI-agnostic and are reusable from an IME service without modification.

The two constraints that shape the design are (1) the engine is **batch CTC** (transcribe-after-release, no streaming partials), which matches the hold-to-talk convention already used on every Veena client, and (2) the **multilingual model is ~645 MiB in RAM**, which is heavy for a process that lingers behind every app. Both are manageable; neither blocks.

## What was verified

- Reviewed the full `apps/android` codebase: 25 Kotlin files, Kotlin + Compose, `minSdk 26` / `targetSdk 36`, single `:app` module, arm64-v8a only, MVVM with an Application-scoped `AppContainer` ([`VeenaApplication.kt`](../apps/android/app/src/main/java/com/byanr/veena/android/VeenaApplication.kt)).
- Confirmed the manifest declares only `RECORD_AUDIO` + `INTERNET` and contains **no service, no IME, no `method.xml`** — the keyboard is a pure addition ([`AndroidManifest.xml`](../apps/android/app/src/main/AndroidManifest.xml)).
- Confirmed the STT stack is fully decoupled from any Activity: `EngineManager` exposes a suspend API (`ensureLanguage`, `transcribe(FloatArray)`, `transcribeFile`) guarded by a mutex, driven by a `StateFlow<EngineState>`, and `MicRecorder` is a plain `AudioRecord` wrapper returning in-memory 16 kHz PCM ([`EngineManager.kt`](../apps/android/app/src/main/java/com/byanr/veena/android/engine/EngineManager.kt), [`MicRecorder.kt`](../apps/android/app/src/main/java/com/byanr/veena/android/audio/MicRecorder.kt)). Both live in `AppContainer`, reachable from any component in the same process — including an `InputMethodService`.
- Confirmed the engine artifacts and sizes: `librust_backend.so` (26 MB, checked in) wraps the shared Rust crate with ONNX Runtime; Telugu model ~125 MB download / ~149 MiB ONNX; multilingual (21 non-Telugu languages) ~479 MB download / ~645 MiB ONNX ([`ModelCatalog.kt`](../apps/android/app/src/main/java/com/byanr/veena/android/data/ModelCatalog.kt)). The exact INT8 graphs already run on-device in the shipping app, so Android CPU-execution-provider compatibility is proven, not assumed.
- Confirmed the batch-parity baseline in the prior assessment: all existing clients transcribe after recording stops; reference timings on Apple M4 are ~663/938 ms model load (Telugu/multilingual) and ~340/755 ms to transcribe a 23 s clip ([`browser-transcription-feasibility.md`](browser-transcription-feasibility.md), [`voice-models/browser-assets/README.md`](../tools/voice-models/browser-assets/README.md)).
- Checked Android platform behavior for the three governing constraints — microphone access from an IME, IME process lifetime/memory, and input-method/permission enable flows — against Android documentation and shipping precedents (below).
- Did **not** write any IME code, measure on-device IME latency/RSS, or submit anything to Play review. No application code was changed.

## Why an IME (and what was rejected)

| Approach | Verdict | Reason |
|---|---|---|
| **A. Dedicated voice-first IME in the Veena APK (recommended)** | Proceed | Only mechanism that inserts text into any app's focused field via `InputConnection.commitText`; no overlays, no accessibility service; Gboard/FUTO precedent; reuses the entire existing stack in-process. |
| B. Full QWERTY keyboard + mic key (LatinIME/FlorisBoard fork) | Defer | A full keyboard with Indic typing/autocorrect is a separate product's worth of layout, IME logic and maintenance (FUTO maintains a whole LatinIME fork for this). Revisit after the voice IME proves usage. |
| C. Floating mic overlay + clipboard/accessibility paste | Reject | `SYSTEM_ALERT_WINDOW`/accessibility for text insertion is Play-policy-hostile, fragile across apps, and worse UX than a keyboard. |
| D. `RecognitionService` only (serve other apps' `RecognizerIntent`) | Insufficient alone | Gboard's voice typing does not consume third-party recognizers, so this reaches only niche clients. Cheap to add later as a bonus surface (FUTO Voice Input exposes both). |
| E. Hook into Gboard/Google's voice typing | Not possible | No third-party integration point exists. |

An IME is also the *privacy-positive* option: audio is captured only while the keyboard view is visible and the mic key is held, processed entirely on device, and nothing is uploaded — the same posture as the dictation app.

## Platform constraints that govern the design

### 1. Microphone access from an IME

Android 10+ restricts microphone access to apps that are in the foreground (or hold a microphone-type foreground service). A bound, shown IME is treated as a foreground-visibility process — this is how Gboard voice typing works — but once the input view is hidden, capture is blocked. Veena's natural design fits inside this boundary: **record only between mic-key press and release while the input view is shown**. The IME inherits the app's granted `RECORD_AUDIO` (same UID/process), so no new permission model is needed; the remaining work is graceful handling of (a) permission not yet granted, (b) the user's global mic-off privacy toggle, and (c) per-field suppression (below). OEM variance on Android 9–16 mic-while-IME behavior is a device-matrix test item, not a design change.

### 2. IME process lifetime and memory

An IME process is bound while in use and drops to cached priority once hidden; `lmkd` may kill it under memory pressure, and the system cold-restarts it on the next text-field focus. Consequences the design must own:

- **Cold start matters.** Keyboard first-frame must be fast (keep the input view light) and the model must **lazy-load on first mic press**, showing a brief loading state — never block the keyboard UI on a 149–645 MiB load.
- **Idle unload.** Destroy the native session after an idle timeout (e.g. 10 minutes without dictation) via `EngineManager.shutdown()` so a hidden IME doesn't pin hundreds of MiB behind every app.
- **Multilingual footprint is the biggest technical risk.** ONNX Runtime holds the INT8 graph in RAM (~645 MiB plus activations/arena). On the 4–6 GB devices common in Veena's market, keeping the multilingual model resident in an IME process will invite LMK kills and slow reloads. Mitigations, in order: (a) make the keyboard bias toward single-language models; (b) as a follow-up workstream, export per-language single models from the existing `voice-models` pipeline (AI4Bharat publishes per-language IndicConformer checkpoints; the Telugu export already proves the path — confirm per-language availability in the spike); (c) measure RSS on a 4 GB device before promising multilingual-in-keyboard.
- **Same-process, single-engine.** Keep the IME in the default process sharing `AppContainer`'s single `EngineManager` — one loaded model, one `.so`, no IPC — and accept that app-side instability could affect typing; the IME code path touches no Activity/ViewModel code.

### 3. Enable flow and friction

Using the keyboard requires three one-time user actions: enabling the IME in system settings (`Settings.ACTION_INPUT_METHOD_SETTINGS`), selecting it (`InputMethodManager.showInputMethodPicker()`), and granting mic (in the main app). Model download is a fourth unless already done. This is standard for every third-party keyboard, but it must be actively walked through: add a "Voice keyboard" screen to the app (reachable from Settings and onboarding) that tracks each gate's state, deep-links to the right system screen, and offers a live test field. The IME itself must render a useful card (not a dead mic) when a gate is missing: "Open Veena to grant microphone" / "Download the Hindi model to dictate" → launch `MainActivity` with a deep link.

## Recommended architecture

1. **`ime/VeenaImeService : InputMethodService`** declared with `android.permission.BIND_INPUT_METHOD`, `android:view.im` meta-data pointing to `res/xml/method.xml`, `supportsSwitchingToNextInputMethod="true"`, and a settings activity pointing at the new in-app screen. Manifest diff is one `<service>` block; nothing else changes.
2. **Voice-first input view**: large mic button (hold-to-talk default, tap-to-toggle option — matching the app, iOS and desktop), language chip, RMS level indicator, a globe key to switch back to the user's previous keyboard, backspace, and a settings gear. No letter keys in v1. Plain Views or Compose-with-lifecycle-plumbing are both viable; whichever is chosen, gate on measured cold-start time (Compose in an IME needs `ViewTreeLifecycleOwner` wiring and costs first-frame time on low-end devices).
3. **Language via IME subtypes.** Register one subtype per *downloaded* language at runtime (`InputMethodManager.setAdditionalInputMethodSubtypes`), so the system keyboard-switcher shows only usable languages; switching subtype calls `EngineManager.ensureLanguage(code)` on next use. Store an independent `imeLanguageCode` preference so the keyboard's language can differ from the app's.
4. **Dictation flow**: press → `MicRecorder` starts (no WAV file, no History write for IME dictations — see privacy) → animated recording state with elapsed time and RMS → release → brief "transcribing" state → `currentInputConnection.commitText(result + " ")` → `finishComposingText()`. Handle null `InputConnection`, app-side `sendKeyEvent`-only fields, and cancellation (swipe off the mic key aborts without committing, matching the app's cancel affordance).
5. **Sensitive fields**: suppress the mic entirely (visually disabled with explanation) when `InputType` is a password/credential/number-password variation or the field comes from a WebView marked sensitive. Never dictate into invisible-password fields. This is both good privacy and Play-review hygiene.
6. **Engine lifecycle**: lazy-load on first press; reuse across dictations; idle-unload; on model-missing, fall back to the download card. Long-dictation enhancement (Phase 2): reuse the web app's energy-based `splitAudio` chunking to emit interim text via `setComposingText` and commit per chunk — streaming-*looking* UX without a streaming model.
7. **Optional Phase 3 surfaces**: a `RecognitionService` implementation (exposes Veena to `RecognizerIntent` clients), and per-language model exports to shrink the keyboard's memory profile.

### Effort estimate (one engineer)

| Phase | Scope | Estimate |
|---|---|---|
| 0 — Spike | IME skeleton, mic from IME on 3 devices, commit text with Telugu model, subtype plumbing | 2–4 days |
| 1 — MVP | Enable-flow screen, language subtypes, permission/model gating cards, hold-to-talk UI, sensitive-field suppression, error states | 2–3 weeks |
| 2 — Hardening | Idle unload + memory measurement (incl. 4 GB device with multilingual), cold-start optimization, chunked interim text, device matrix (Android 9–16, major OEMs), Play Data-safety preparation | 2–3 weeks |
| 3 — Optional | `RecognitionService`; per-language single-model exports via `voice-models` | 2–4 weeks |

Production v1 (Phases 0–2): roughly **4–6 weeks** of engineering plus device testing.

## Performance expectations

Short utterances are the keyboard's workload, and CTC encoder cost scales with audio length. Extrapolating from the M4 reference (23 s ⇒ ~340 ms Telugu / ~755 ms multilingual): a 5 s utterance is ~75/165 ms on M4; assuming 3–5× slower CPU-thread throughput on mid-range Android silicon, expect roughly **0.3–0.8 s (Telugu) and 0.7–1.5 s (multilingual)** per short dictation, plus 1–3 s one-time model load per process lifetime. That lands in Gboard-like territory ("release → brief beat → text appears") and is consistent with the wait users already accept in the dictation app. These are estimates from a desktop reference — the Phase 2 measurement milestone exists to replace them with on-device numbers, and low-end-device latency is the number to watch.

## Privacy, security and store policy

- **On-device only**: the IME path performs no network I/O; models are already local. This is the strongest possible story for the store disclosure.
- **IMEs see focused-field context**: Play requires IME apps to declare data access honestly and expects prominent disclosure for mic capture. Prepare the Data-safety entries ("audio is processed on device, never uploaded, not retained") and an in-app disclosure before the enable flow.
- **Retention**: IME dictations must not be written to History or disk by default (`MicRecorder` needs a no-file mode — its WAV path is currently hardcoded into the recording loop). An opt-in "keep keyboard dictations in history" setting can come later.
- **Hygiene**: disable mic on password/credential fields; respect `EditorInfo` hints; never log field contents; strip the hardcoded HuggingFace token from `tools/voice-models/export_multilingual_ctc_onnx.py` (unrelated finding from this review — it is a live credential in the repo).

## Feature coverage

| Keyboard capability | Feasibility | Notes |
|---|---|---|
| Dictate into any app's text field | High — architecture proven | `commitText` via `InputConnection`; engine/mic/download stack reused as-is |
| All 22 languages | Telugu high; other 21 workable | Same models; multilingual memory footprint is the risk to measure |
| Hold-to-talk / tap-to-talk | High | Parity with app, iOS and desktop conventions |
| Interim text while speaking | Phase 2 (emulated) | Chunked batch transcription → `setComposingText`; true streaming would need a new model |
| Language switching in keyboard | High | Runtime-registered IME subtypes for downloaded languages |
| Works offline | High | Entirely local once models are downloaded |
| Password/sensitive fields | Handled by suppression | Mic disabled on credential variations |
| Live partial transcripts / streaming ASR | Not in v1 | CTC batch engine; matches parity baseline of all current clients |
| Full typing (QWERTY/Indic) | Out of scope v1 | Future decision; OSS-keyboard fork is the known path |

## Risks and open questions

1. **Multilingual model RAM in IME process** (highest technical risk) — measure RSS + reload cadence on 4 GB devices in Phase 0/2; per-language exports are the structural fix.
2. **Mic-while-IME on specific OEM/Android combinations** — device matrix test; mitigation is capture-only-while-visible plus clear error UX.
3. **Low-end inference latency** — benchmark a representative ₹10–15k device; if multilingual exceeds ~2 s per utterance, restrict keyboard defaults to single-language models.
4. **Onboarding friction (4 gates)** — invest in the guided enable screen; measure funnel drop-off post-launch.
5. **Play review for IME + mic** — prominent disclosure and Data-safety prep before rollout; on-device-only processing is the favorable fact.
6. **Compose-in-IME cold start** — decide View vs Compose after the Phase 0 measurement.
7. **`MicRecorder` WAV coupling** — small refactor for a fileless mode (also useful to the main app's cancel path).

## Sources

- [Android privacy: microphone access](https://developer.android.com/privacy-and-security/sensitive-information) — foreground requirement for mic access
- [Android memory allocation & process lifecycle](https://developer.android.com/topic/performance/memory-management) and [Oom Adjuster design](https://source.android.com/docs/core/architecture/lmkd) — IME process priority and `lmkd` behavior
- [FUTO Voice Input](https://futo.org) and [FUTO Keyboard docs](https://docs.keyboard.futo.org) — shipping precedent for a mic-first IME with downloadable on-device models (LatinIME fork, [source](https://gitlab.futo.org))
- [SpeechRecognizer / RecognitionService API](https://developer.android.com/reference/android/speech/SpeechRecognizer) — the optional third-party recognizer surface
