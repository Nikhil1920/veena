# Browser transcription

The `/speech-to-text/{language}/` desktop workspace runs the existing production IndicConformer models locally using ONNX Runtime Web 1.29.0 on WASM CPU. Audio capture, decoding, preprocessing, inference, text replacement and history remain on the user's device. The server delivers application assets and two allowlisted model archives; it has no audio upload or transcription endpoint.

## Included

- Microphone capture with level feedback and device selection; local audio picker and drag/drop; bundled Telugu sample.
- Page-selected models with no model picker: dedicated Telugu model (124,774,521 download bytes) and shared multilingual model (478,579,709 bytes), with all 22 existing language vocabularies and masks.
- Explicit Download model consent, displayed archive size and progress. Saved models reopen automatically; cache-only loading never falls back to a network download.
- Streaming, resumable downloads into OPFS, SHA-256 verification, streamed gzip/tar extraction, and cached model reuse. Users can also remove partial downloads.
- Worker inference, progress, chunk partial text and cancellation. Failed/interrupted inference retains the original audio in IndexedDB for retry.
- Editable transcripts, exact replacement rules, append, copy, text export, audio playback, local history, retry, saved entries and configurable retention.
- Language selection navigates to the corresponding indexable language page; saved language preferences never override the URL.
- Offline application/runtime and language-page caching without installation, registered after the model is ready. Cached app versions stay consistent until all existing tabs close; updates activate on a subsequent visit. Clearing site data also clears models and history.
- Mobile visitors see Android/iPhone app links before model download; unsupported desktop capabilities show the app fallback.

## Run and deploy

Use Node.js 22.6 or newer and pnpm. `pnpm install` installs the pinned runtime; `pnpm dev` prepares runtime assets and starts Astro with the same-origin model proxy. Development mode intentionally does not install the offline service worker.

For a production-like local session:

```sh
pnpm build
pnpm preview
```

Open `http://127.0.0.1:4321/speech-to-text/telugu/`. The legacy `/transcribe/` route remains available with a canonical URL pointing to the Telugu page. Preview uses the existing sibling model archives when available and otherwise streams from the production model CDN. A normal deployment streams from the CDN. The downloads stay out of the site bundle.

Production is `https://veena.byanr.com` on the Cloudflare Pages project `veena` (`https://veena-1dl.pages.dev`). Run `pnpm deploy` to build and deploy; `pnpm preview:cloudflare` runs the same Pages build locally. Astro produces one `dist/` asset directory, with a Pages `_worker.js` that handles only `/models/*`, and no Sites manifest or duplicate asset directories.

The Worker streams only the two allowlisted native download URLs:

- `https://dl.byanr.com/veena-byanr/indicconformer_stt_te_ctc_static_qdq_int8.tar.gz`
- `https://dl.byanr.com/veena-byanr/indicconformer_multi_ctc_int8.tar.gz`

The browser requests these through `/models/{archive}` because dl.byanr.com currently does not send browser CORS headers. GET, HEAD and single byte-range requests are forwarded without buffering the whole archive. The web assets contain ONNX Runtime WASM (the execution engine) and a sample recording, but no speech-model weights or archives. Models are downloaded only after consent and retained in the visitor's browser storage.

The static `_headers` file provides COOP/COEP/CORP headers on documents and worker/runtime assets for up to four WASM threads. Header changes also invalidate the offline cache. The model route never accepts audio uploads. Cloudflare serves all other paths from static assets, including the generated 404 page.

On 22 September 2026, the Pages deployment was verified with successful static and runtime responses, Telugu and multilingual archive HEAD responses, and resumable byte-range downloads. The previous ChatGPT Site was deleted. The old Cloudflare Worker remains available on its workers.dev address, with its custom-domain binding removed.

## Verified on 10 September 2026

Real browser interactions used the production build in the Codex desktop browser on this Apple M4 / 16 GiB Mac. No inference or transcript mocks were used. Timings exclude initial download/load and input decoding and are measurements from one machine.

| Check                                                                                                         | Result                                                                  |
| ------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| Existing 22.976-second, mono 16 kHz WAV; dedicated Telugu                                                     | Exact native/reference transcript; 1.48 s inference                     |
| Same fixture; multilingual                                                                                    | Exact existing Rust test expected transcript; 2.90 s inference          |
| Import same WAV through file picker; multilingual                                                             | Same expected result; 2.86 s inference                                  |
| Stop local server, verify connection refusal, reload page, create new model session, transcribe cached sample | Passed; 1.48 s inference, same Telugu transcript                        |
| Cancel active inference, reload model, retry saved recording                                                  | Audio retained; retry produced the expected Telugu transcript in 1.55 s |
| Type checking, targeted ESLint, production build                                                              | Passed; only deprecation/unused hints in Astro diagnostics              |
| Native preprocessing, CTC, silence, chunk bounds, PCM WAV regression tests                                    | 5 passed                                                                |

Browser preprocessing produces 183,840 float32 values shaped `[1,80,2298]`. Against production Rust preprocessing, maximum absolute difference is 0.000112772 and mean absolute difference is 7.796e-7, below the 0.0002 regression threshold. The golden tensor and transcripts are in `tests/fixtures/`; the test audio is `public/audio/telugu_test_audio_16k.wav`.

The quantized models are sensitive to small numerical and runtime differences. The current native multilingual run has an extra vowel (`సొరకాాయ`) where both the browser and established Rust test expectation produce `సొరకాయ`. This does not imply generally identical recognition across native and browser runtimes.

`pnpm test:transcription` runs the numerical/decoder regression suite. `pnpm test:browser` provides a real-model Playwright suite for both models, offline reload and mobile gating (requires Chromium installed for Playwright). The September 10 results above were checked directly through the browser UI. See the updated verification below for the automated suite.

## Verified on 12 September 2026

The redesigned setup and language-page flow passed all nine Playwright scenarios:

- Telugu and Hindi show the archive size and app alternative, with no model/runtime requests before Download model is clicked.
- The page language overrides stale saved preferences, and selecting another language navigates to its page.
- Download progress, cancellation, server-error retry, and locked language changes work.
- All 22 language pages expose one server-rendered h1, indexable metadata, and the correct canonical URL; mobile visitors receive app links without downloading models.
- The production Telugu model matches the reference transcript after file import and after an offline reload. Removing its cached files restores the download prompt without making a model request.
- The production multilingual worker matches the supplied Telugu fixture, and navigating from Hindi to Bengali reuses the shared cached model. This checks the worker against the available reference audio, not Hindi or Bengali recognition accuracy.

The larger model uses a normal temporary Chromium profile in the test suite, since Chromium's incognito file system can exhaust its separate memory limit during extraction. Model downloads, extraction, and inference are real. Only the network-failure tests intercept model responses.

The five audio regression tests, Astro type checking, targeted linting, formatting, and production build also passed. Full-repository ESLint remains blocked by pre-existing parser errors in the five design-iteration pages (`src/pages/1.astro` through `5.astro`).

## Beta boundaries

Recognition was evaluated only with the supplied Telugu recording. Other languages, accents/noise, non-WAV codecs, actual microphone hardware, peak memory, Windows and other browsers require further validation. All 22 language paths are implemented; a 22-language accuracy claim has not been established.

Input limits are 30 minutes and 200 MiB. Inference uses windows of at most 25 seconds, silence-aware boundaries, 0.5-second overlap and simple word stitching. Compressed input still uses whole-file browser decoding. These are input caps, not a validated long-recording capacity; boundary quality and lower-memory devices remain unmeasured. Interrupted inference retries the recording rather than resuming the last chunk.

The beta uses energy-based silence handling instead of native Silero VAD, and exact text replacement instead of native fuzzy custom-word correction and filler/stutter filtering. GPU inference, LLM cleanup, custom model import, global shortcuts, native overlays and automatic paste into other applications are not included. Use the desktop app for OS integration.
