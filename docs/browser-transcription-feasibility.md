# Veena browser transcription feasibility

Assessment date: 10 September 2026.

**Implementation update, 10 September 2026:** The desktop browser beta is now implemented in `veena-web` at `/transcribe/`. Both production models ran successfully against the existing 22.976-second Telugu recording. Telugu inference took 1.48 seconds and multilingual inference took 2.90 seconds on the validation desktop. Fresh reload and Telugu inference also passed with the local delivery server stopped. See [implementation and validation notes](../apps/web/BROWSER_TRANSCRIPTION.md) for measured results and remaining limits. The assessment below records the evidence available before implementation; its unverified statements are historical.

**Recommendation: proceed with a desktop-browser proof of concept using Veena’s existing IndicConformer models.** A user can open an HTTPS website, download model assets into browser storage, and transcribe locally without installing an application, extension, Python, or a local server. This is a credible architecture for the core transcription experience. Production performance of these exact models in a browser remains unverified.

Full desktop-app parity is a different requirement: general system-wide shortcuts, typing into another application, native overlays, system audio muting, and external scripts cannot be reproduced by an ordinary website. Optional AI cleanup also needs separate treatment because the existing implementations use remote APIs, localhost services, or native Apple Intelligence.

## What was verified

- Reviewed the desktop, iOS, Android, shared Rust, model export, and current website code. The active desktop dependency is the sibling `crates/rust-backend` crate; the nested desktop copy is not the authoritative engine.
- Inspected the ONNX graph contained in the local Telugu archive. Its archive size matches the live download’s reported size. This establishes graph structure, not inference compatibility or speed.
- Checked live HTTP headers for the website and model downloads, including a one-byte range GET of the multilingual artifact.
- Checked primary ONNX Runtime, GPUWeb, browser-vendor, and MDN documentation.
- Did **not** run browser inference, measure browser memory/latency, or inspect the full multilingual ONNX binary. No application code or deployment was changed.

## Feature coverage

| Existing feature | Browser feasibility | Work or boundary |
|---|---|---|
| Record, stop, transcribe | High architectural confidence | HTTPS microphone permission, local PCM capture, worker-based processing |
| Telugu and all 22 scheduled Indian languages | Feasible in principle; exact-model runtime validation required | Preserve existing models, vocabularies and language masks |
| Choose transcription language | High | Preserve explicit language selection |
| Import an audio file | High for supported formats | Local file picker/drop zone; verify codec coverage; add incremental decoding for large files |
| Edit, append, copy, clear and export text | High | In-page UI and user-initiated clipboard/download actions |
| Local history, audio playback, retry, saved entries and retention | High | IndexedDB/OPFS or equivalent; account for quota and eviction |
| Voice activity detection and waveform feedback | Feasible | Port the audio pipeline and validate Silero VAD separately |
| Custom-word correction and filler filtering | High | Port the existing local text algorithms |
| Reuse model and app offline | High, while cached data remains available | Service worker and persistent-cache workflow; installation optional |
| Optional LLM cleanup | Separate model/runtime project for browser-only execution | Current API, localhost and Apple Intelligence integrations do not satisfy the proposed browser-only requirement |
| Global hotkeys, automatic paste into arbitrary applications, auto-submit, custom scripts, tray/native overlay | Not available with full parity in a normal website | Retain native desktop app for these workflows |
| Continue after tab closure or OS suspension | Not reliable | Save progress and support recovery; browser workers are not permanent background services |

The existing applications transcribe **after recording stops**. Live partial transcripts are not an existing parity requirement. Current IndicConformer output does not supply transcription segments, and both catalog models disable translation. Automatic language detection and speaker diarization are not implemented by this pipeline; the desktop’s “auto” language passes the engine default rather than detecting speech language.

Local code evidence: [desktop recording flow](apps/desktop/src-tauri/src/actions.rs:524), [IndicConformer language/output handling](apps/desktop/src-tauri/src/managers/transcription.rs:643), [mobile file import](apps/android/app/src/main/java/com/byanr/veena/android/ui/dictate/DictateViewModel.kt:81), [text correction](apps/desktop/src-tauri/src/managers/transcription.rs:715), [LLM providers](apps/desktop/src-tauri/src/settings.rs:524).

## Model and runtime assessment

The current shared pipeline is:

`audio → mono/16 kHz resampling → 80-bin log-mel features → ONNX encoder + CTC head → language mask + greedy CTC decoding → local text correction`

The model accepts float features and an int64 length; it does not take raw microphone audio. Preprocessing uses pre-emphasis, a 512-point FFT, a 320-sample window, a 160-sample hop, and per-feature normalization. Matching these operations matters for transcription quality. See [preprocessing](/Users/anr/projects/veena/rust_backend/src/preprocess.rs:217), [inference](/Users/anr/projects/veena/rust_backend/src/lib.rs:161), and [language-dependent decoding](/Users/anr/projects/veena/rust_backend/src/lib.rs:122).

| Artifact | Verified network download | Additional evidence |
|---|---:|---|
| Dedicated Telugu | 124,774,521 bytes, about 125 MB / 119 MiB | Local archive contains a 156,403,691-byte ONNX graph, about 156 MB / 149 MiB |
| Multilingual | 478,579,709 bytes, about 479 MB / 456 MiB | Production HEAD and range GET agree; uncompressed model size and browser allocation are unmeasured |

The Android/iOS Telugu catalog is slightly out of date at 124,831,706 bytes; the desktop’s 149 MB resembles the uncompressed model’s MiB size. Use a versioned browser model manifest with exact download bytes and hashes. Language selection for a non-Telugu language currently requires the shared multilingual model, not a separate small language pack. Do not require both downloads upfront.

At an ideal sustained 10 Mbps, the current archives require about 100 seconds for Telugu and 383 seconds for multilingual; at 50 Mbps, about 20 and 77 seconds. These are bandwidth arithmetic, excluding initialization and network overhead. A different browser export may change these sizes.

**Use ONNX Runtime Web directly.** It supports local CPU execution through WebAssembly and GPU execution through WebGPU. The existing ONNX assets and simple CTC decoder make this a better fit than changing model families. Transformers.js and whisper.cpp demonstrate that browser ASR is established, but a Whisper demo would not validate Veena’s accuracy or its 22-language behavior. [ONNX Runtime Web](https://onnxruntime.ai/docs/tutorials/web/), [Transformers.js](https://huggingface.co/docs/transformers.js/index).

**Start with a WASM correctness baseline; validate WebGPU as an optimization path.** The inspected Telugu graph is opset 16, uses dynamic time and batch dimensions, and contains 1,674 `QuantizeLinear` and 2,277 `DequantizeLinear` nodes, alongside convolution and matrix multiplication. “Static” in its filename refers to quantization; its input duration is dynamic. The inspected WebGPU operator matrix includes `DequantizeLinear` but does not list `QuantizeLinear`. Graph transformations, operator fallback and release-specific support must be tested; this is evidence against assuming a clean GPU drop-in, not proof the model cannot run in a browser. [WebGPU operator matrix](https://github.com/microsoft/onnxruntime/blob/main/js/web/docs/webgpu-operators.md).

If WASM is too slow, test an export designed for the pinned WebGPU runtime, potentially FP16 or a compatible quantization format. Recheck memory, download size and recognition accuracy. GPU support does not guarantee fast inference if substantial computation falls back to CPU or repeatedly transfers intermediate results. The multilingual graph needs its own audit and test before an all-language launch commitment.

Avoid treating the current Rust/native library as a browser binary. Extract its pure audio preprocessing and decoding into a browser-compatible module, potentially Rust-to-WASM, and replace native ONNX/file/audio integration with ONNX Runtime Web and browser APIs. Alternatively port preprocessing to TypeScript with numerical comparisons against the native implementation. The existing Astro/React website can host this UI, but currently has no inference runtime; the desktop UI also needs adapters for its Tauri-dependent state and commands.

## Browser architecture and hosting

1. Serve an app page or dedicated app origin over HTTPS. Check device/browser capabilities and storage before the large download.
2. Download only the selected model with progress, cancellation, integrity validation, versioning and recovery. Cache model files and metadata in browser-managed storage.
3. Capture local PCM with `getUserMedia`/AudioWorklet, or decode a user-selected local file. Perform inference in a dedicated Web Worker so the UI remains responsive.
4. Run preprocessing, ONNX inference, language masking, CTC decoding and text correction locally. Persist transcript/audio history according to user settings.
5. Cache the app and runtime with a service worker for offline return visits. Show a recovery flow when browser data has been cleared or evicted.

A static site and model CDN remain necessary for delivery and updates; a transcription backend is unnecessary. Audio and transcripts should stay local throughout this flow. There is still download bandwidth cost. Do not silently use a cloud inference fallback when device checks fail.

Multithreaded WASM needs cross-origin isolation, generally `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp`, with compatible subresources. WebGPU alone does not inherently require this isolation. Use a dedicated worker for GPU inference: ORT’s `env.wasm.proxy` is not compatible with its WebGPU provider. [ORT flags](https://onnxruntime.ai/docs/tutorials/web/env-flags-and-session-options.html), [isolation guide](https://web.dev/articles/cross-origin-isolation-guide).

**Verified deployment changes are needed:** on 10 September 2026, `veena.byanr.com` returned neither COOP nor COEP. The multilingual model’s HEAD and range GET responses, requested with `Origin: https://veena.byanr.com`, returned no `Access-Control-Allow-Origin`; the Telugu HEAD also lacked it. The current cross-origin model URL therefore needs CORS configuration for a browser fetch, or same-origin model serving. The range GET successfully returned HTTP 206, so byte-range delivery is available. Set the relevant access/cache headers on range and error responses as well. Consider a dedicated app origin if isolation conflicts with marketing integrations.

For the browser distribution, prefer directly addressable ONNX/support files or shards with appropriate HTTP compression and a manifest. This avoids keeping the entire compressed archive, extracted model and runtime copies in memory together. External ONNX weights, if present in a future export, need explicit handling in the browser. [ORT large models](https://onnxruntime.ai/docs/tutorials/web/large-models.html).

## Constraints that affect product promises

**Memory and long recordings.** Download size is not peak RAM usage. Runtime initialization, model copies, graph optimizations, decoded PCM, features, logits and activations all contribute. ORT Web documents a 4 GiB WASM address-space ceiling for its current path, with practical failure possible earlier. Do not infer that a 479 MB download fits every desktop. Measure peak memory with the exact shipped runtime and artifacts. [ORT large-model limits](https://onnxruntime.ai/docs/tutorials/web/large-models.html).

The native engine originally processed a whole utterance; it now uses the web worker's silence-aware windows too (`crates/rust-backend/src/chunk.rs`). Long-file browser support needs bounded-duration inference chunks, preferably with silence-aware boundaries and overlap, plus tested boundary stitching. Normalization is currently utterance-dependent, so chunking can change output and must be validated. Whole-file browser decoding can independently exhaust memory even if inference is chunked: `decodeAudioData` requires complete file data. True long-file support also needs incremental decoding/demuxing or a temporary explicit duration limit. A duration-limited first beta would not yet constitute full long-file parity. [decodeAudioData](https://developer.mozilla.org/en-US/docs/Web/API/BaseAudioContext/decodeAudioData).

**Offline storage.** Reuse after download is achievable; permanent retention is not guaranteed. Request persistent storage, estimate quota, handle rejection and provide a way to remove models. User clearing, browser eviction and private-mode behavior must be handled. A service worker enables offline access without a mandatory PWA installation. [Storage quotas and eviction](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria), [Service Worker API](https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API).

**Lifecycle.** Keep the tab open during transcription. Browsers may freeze or discard pages, and service workers are not durable transcription daemons. Persist long-job progress so interrupted work can recover. [Chrome page lifecycle](https://developer.chrome.com/docs/web-platform/page-lifecycle-api).

**Capture and native integration.** Microphone capture needs HTTPS and permission. User-selected files can be processed locally. Copy/export works with browser-specific activation rules; general OS-wide hotkeys and automatic typing into arbitrary native applications are outside a standalone website’s capabilities. If meeting/system-audio capture is later requested, it requires separate support testing: `getDisplayMedia` audio varies by browser, OS and shared surface and is not guaranteed. [Microphone API](https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia), [Clipboard API](https://developer.mozilla.org/en-US/docs/Web/API/Clipboard_API), [Keyboard Lock](https://developer.chrome.com/docs/capabilities/web-apis/keyboard-lock), [screen capture](https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getDisplayMedia).

**Avoid browser-managed speech recognition as the foundation.** Experimental `SpeechRecognition.processLocally` exists, but it uses browser-managed language packs rather than our model assets. Default recognition may use a remote service. It does not establish Veena’s language/model/accuracy parity. [processLocally](https://developer.mozilla.org/en-US/docs/Web/API/SpeechRecognition/processLocally), [language availability](https://developer.mozilla.org/en-US/docs/Web/API/SpeechRecognition/available_static).

## Recommended support policy

Start by certifying current Chrome/Edge on Windows x64 and macOS. Treat Safari, Firefox and Linux as additional tested combinations, rather than universally unsupported. GPUWeb’s status updated 13 August 2026 reports WebGPU shipping in Safari on OS 26, Firefox on Windows and Apple Silicon macOS, and selected Chromium Linux GPU configurations. Older ONNX browser support tables lag this rollout. Browser API support and exact ONNX graph support remain separate checks. [GPUWeb implementation status](https://github.com/gpuweb/gpuweb/wiki/Implementation-Status).

Gate execution on successful runtime/model initialization and a short local benchmark. Inspect an actual GPU adapter and limits when using WebGPU; `navigator.gpu` alone is insufficient. WASM can preserve access on systems without a usable GPU only where measured latency and memory are acceptable. If a device fails, explain the result and offer the native app. Do not require browser flags or command-line setup for the supported experience.

On mobile, show the iOS/Android app recommendation before a large model download, as requested. Mobile browser inference is technically possible on some devices, but memory, heat, battery and background reliability make desktop-first a sensible product scope. Use device capability checks alongside the mobile policy; a desktop label does not guarantee adequate resources. An 8 GB Windows integrated-GPU laptop and an 8 GB Apple Silicon Mac are useful lower-end test targets, not verified minimum requirements; include 16 GB machines as well.

## Proof of concept and decision gates

**First milestone: prove exact-model feasibility before building the full interface.** Test both the Telugu and multilingual production models with a pinned ONNX Runtime Web release. Inventory the multilingual graph, run a WASM baseline, profile WebGPU node placement/fallback, and compare recognition to the native engine using identical audio and preprocessing. A Telugu-only test cannot validate the 22-language requirement.

**Measure cold download/load separately from warm inference.** On Windows integrated-GPU and Apple Silicon machines, test 5–10-second dictation, 60-second speech, repeated transcriptions, silence, noisy speech, non-16 kHz input, language switching, imported formats and a long recording. Include all 22 language vocab/mask paths and a representative accuracy corpus per language. Record load failures, peak memory, stop-to-text latency, real-time factor and recognition error against the native baseline.

Proposed product gates, not measured results: warm 10-second dictation should return within roughly 3 seconds on the admitted desktop tier; long-file throughput should beat real time without accumulating memory or losing words at chunk boundaries. Final thresholds should reflect acceptable user experience and the measured device distribution.

**Then verify the actual website flow:** initial download, cancel/retry, reload from cache, airplane-mode reload and transcription, user-cleared storage, insufficient quota, microphone denial, unavailable GPU, device loss, tab interruption and history recovery. Confirm that no audio/transcript is transmitted during local operation.

**Delivery estimate:** roughly 3–5 engineering days for an initial two-model feasibility spike, assuming representative hardware and model assets are available; around 2–4 further weeks for a focused desktop beta if models work without major export changes. Broad browser certification, robust long-file codec support, all-language accuracy evaluation and a separate browser LLM can extend this substantially. These are planning estimates, not a delivery commitment.

The release should promise “local transcription in your desktop browser” only after these gates pass. It can cover the core mobile-style transcription workspace and portable desktop text features; users who need dictation directly into other applications still need the native desktop app.
