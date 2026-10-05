# voice-models — model pipeline for Veena

Python tooling that exports, quantizes, and validates the IndicConformer ONNX
models used by every Veena app (desktop, iOS, Android, web). The inference
runtime itself lives in Rust: [`crates/rust-backend`](../../crates/rust-backend).

It also builds and evaluates the smaller **language-specific** mobile models
(one download per language instead of the 479 MB shared model); see
[Language-specific mobile models](#language-specific-mobile-models) below.

## Layout

| Path | Purpose |
| --- | --- |
| `models/` | Small config/tokenizer assets (decoder configs, vocabularies). Model **binaries are never committed** — they are gated Hugging Face exports or runtime downloads. |
| `telugu_test_audio.wav` | Shared 22.98 s Telugu reference recording used by these scripts, the engine's `cargo test`, and the web app's demo fixture. |
| `model_sources.json` | Pinned upstream revisions, hashes, formats and licenses for every source model. |
| `reports/` | Committed evaluation evidence: per-utterance scores, builder-source snapshots, robustness/timing summaries. Start at [`reports/mobile-quantization/RESULTS.md`](reports/mobile-quantization/RESULTS.md). |
| `tests/` | Unit tests (`uv run python -m unittest discover -s tests -v`). |
| `candidates/`, `corpus/`, `browser-assets/` | Generated weights and licensed audio. Gitignored. |

## Scripts

| Script | Purpose |
| --- | --- |
| `export_multilingual_ctc_onnx.py` | Export IndicConformer-600M-Multilingual (encoder + CTC head) from Hugging Face into a single ONNX file. Reads credentials from `HF_TOKEN` or `hf auth login`; requires access to `ai4bharat/indic-conformer-600m-multilingual`. |
| `transcribe.py` | Baseline Telugu inference (PyTorch). |
| `onnx_transcribe.py` | Telugu inference through the exported/quantized ONNX model. |
| `transcribe_multilingual.py` | Multilingual ONNX inference for all 22 scheduled languages (`as bn brx doi gu hi kn kok ks mai ml mni mr ne or pa sa sat sd ta te ur`). |
| `quantize.py` | ONNX quantization passes used to produce the int8 artifacts. |
| `prepare_browser_assets.py` | Download and SHA256-verify the two production archives (from `dl.byanr.com`) into `browser-assets/` for browser-inference validation. Output is generated, not committed. |
| `compare_browser_features.py` | Compare native ONNX Runtime outputs when fed production Rust features vs browser features (parity check for the web worker). |
| `fetch_float_models.py` | Fetch pinned original floating-point weights for mobile quantization experiments. |
| `prune_ctc_head.py` | Create a 257-class FP32 language head from a verified 5633-class CTC export. |
| `acquire_evaluation_corpus.py` | Acquire a small IndicVoices pilot from AI4Bharat's public, annotated archives. |
| `export_nemo_features.py` | Export deterministic evaluation features matching IndicConformer's NeMo frontend. |
| `quantize_language_model.py` | Build unvalidated CPU INT8 candidates from a floating-point language model. |
| `quantize_mobile_mixed.py` | Experimental dynamic INT8 for constant MatMul and safe pointwise Conv1d. |
| `quantize_int4.py` | Build experimental mobile CPU INT4 MatMul weights directly from FP32 ONNX. |
| `build_model_batch.py` | Build and screen unpublished per-language mobile ASR candidates. |
| `evaluate_multilingual.py` | Evaluate multilingual CTC ONNX models against human-reference feature corpora. |
| `make_audio_stress_set.py` | Create deterministic audio stress probes; these are NOT real far-field recordings. |
| `screen_audio_robustness.py` | Screen mobile candidates on reproducible synthetic off-microphone probes. |
| `benchmark_native_models.py` | Repeat a real clip through Veena's production Rust backend on this host. |
| `prepare_language_models.py` | Fetch pinned language-specific INT8 candidates and package them for Veena. |
| `package_mobile_candidates.py` | Create deterministic, unpublished mobile candidate bundles and a review manifest. |

## Setup

Requires Python 3.11+. Uses [uv](https://docs.astral.sh/uv/):

```bash
cd tools/voice-models
uv sync                 # add --extra int4 for the INT4 tooling
```

The Rust helpers (`export_features`, `model_smoke`) live in
[`tools/browser-reference`](../browser-reference) and build into the
workspace `target/` directory, which is where these scripts look by default:

```bash
cargo build --release -p veena-browser-reference --bins
```

## Producing the deployed artifacts

The deployed model archives are built by running the export → quantize →
archive steps above and are hosted at `dl.byanr.com/veena-byanr/`; the apps'
model catalogs (`apps/*/…/ModelCatalog*`) reference those downloads. The
engine integration test in `crates/rust-backend` skips the multilingual case
unless the extracted artifacts are present under `models/`.

## Language-specific mobile models

This workspace builds and compares language-specific CTC models for Android and iOS. App model-download plumbing is unchanged. Start with Hindi, Bengali, Marathi, Telugu, Tamil, Gujarati, Urdu, and Kannada; sources and corpus acquisition cover all 22 scheduled languages.

The work order follows India's 2011 Census mother-tongue speaker ranking, rather than a claim about current worldwide speaker counts. See the [official C-16 population-by-mother-tongue table](https://censusindia.gov.in/nada/index.php/catalog/10191).

### Current findings

The completed delivery has **44 local bundles for all 22 languages**: compact downloads are **94.82–94.85 MB**, and balanced downloads are **130.28–130.37 MB**. Compact passes the initial shipped-model quality gate in 19/22 languages; balanced in 21/22. See [complete results](reports/mobile-quantization/RESULTS.md) for exact scores, hashes and exceptions.

### Hosted downloads

One model per language is hosted under `veena-byanr/models/`: the
**balanced INT8** variant, for 21 of the 22 languages. **No app catalog
points at them yet.**

```
https://dl.byanr.com/veena-byanr/models/indicconformer-<lang>-balanced-int8-candidate-v1.tar.gz   # the bundle
https://dl.byanr.com/veena-byanr/models/indicconformer-<lang>-balanced-int8-candidate-v1.json     # its manifest
https://dl.byanr.com/veena-byanr/models/SHA256SUMS                                                # checksums
```

`<lang>` is one of `as bn brx doi gu hi kn kok ks mai ml mr ne or pa sa sat sd ta te ur`.

Why balanced over compact INT4: on the same clips, pooled across the 21
languages, balanced has 0.74 pp lower WER (paired bootstrap 95% CI −1.30 to
−0.20) and 0.20 pp lower CER. It stays within 1 pp of its FP32 source in 20
languages, compared with 13 for compact. The cost is about 35 MB more per
download (≈130 MB vs ≈95 MB). Compact bundles remain reproducible locally (see
below) but are not hosted.

**Manipuri (`mni`) has no language-specific model.** Both of its variants are
worse than the current shared model on this pilot, so Manipuri stays on the
shared model.

Experiments are candidates, not app-catalog replacements. Reports under `reports/mobile-quantization/` record human-reference WER/CER, size, load time, latency, real-time factor and process peak memory. Measurements made while other jobs ran are quality screens; use isolated, repeated runs on physical phones for performance acceptance. No phone was attached during this session.

The previous quantization script only checked one Telugu transcript. Do not use exact agreement on that clip to approve a release. The new evaluator checks each requested language separately and never calls a model release-ready automatically. The corpus now contains 220 calibration and 440 evaluation utterances across all 22 languages, with zero overlap in speakers, conversation groups, or audio hashes between splits.

### Reproduce

Use the project environment (`uv sync`; add `--extra int4` for experimental INT4). All commands below run from `tools/voice-models`.

```sh
# Download pinned original FP32 weights, verifying every large tensor file.
uv run python fetch_float_models.py --languages hi bn mr te ta gu ur kn

# Build the production audio frontend exporter.
cargo build --release -p veena-browser-reference --bin export_features

# Inspect corpus acquisition options, including language order and sample counts.
uv run python acquire_evaluation_corpus.py --help

# Calibration-free per-channel INT8 weight projections.
uv run python quantize_language_model.py \
  --model candidates/mobile-fp32/te/model.onnx --language te \
  --recipe dynamic_matmul_int8 --output-dir candidates/te-dynamic-new

# Smaller experimental INT4, including equivalent pointwise-convolution rewrites.
uv run --extra int4 python quantize_int4.py --help

# Build both compact INT4 and balanced INT8 for the first eight languages.
# Baseline reports must already exist under reports/mobile-quantization/priority/.
uv run --extra int4 python build_model_batch.py --output-root candidates/mobile-builds-new

# Quiet/noisy/reverberant probes of the exact shipped frontend and candidates.
uv run --extra int4 python screen_audio_robustness.py --output candidates/robustness-new

# Compare against the same held-out corpus and runtime settings.
uv run python evaluate_multilingual.py \
  --model candidates/mobile-fp32/te/model.onnx \
  --model-dir candidates/mobile-fp32/te \
  --manifest corpus/indicvoices-v1-pilot/te/evaluation/manifest.jsonl \
  --required-languages te --threads 2 --warmup 2 --repeats 5 \
  --output candidates/te-fp32-evaluation.json

# Use the same settings and add --baseline-report for a quantized candidate.
uv run python evaluate_multilingual.py --help
uv run python -m unittest discover -s tests -v
```

`quantize_language_model.py` refuses already-quantized input, existing output directories, and calibration/evaluation overlap. Static QDQ requires both manifests and at least two distinct calibration utterances; this is a split-integrity check, not evidence of representative calibration. In the first actual Telugu trial, full static QDQ and both constant-weight static recipes severely degraded recognition. They are rejected regardless of their size.

`quantize_int4.py` rewrites only safe float32, ungrouped, unpadded, stride-one, kernel-one convolutions. The transformation is tested for FP32 equivalence before quantization. INT4 requires `com.microsoft::MatMulNBits` in the exact mobile ONNX Runtime build; it does not imply Core ML or NNAPI acceleration. Reduced storage does not necessarily mean faster inference.

The 44 completed builds retain their original recipe hashes. Their exact builder source is archived under `reports/mobile-quantization/build-source/`. The current validator additionally rejects partially quantized operators and low-bit tensors in nested/function bodies; all existing weights and build records are unchanged. Strict build `--resume` intentionally refuses changed builder code. Use a fresh output directory for new recipes.

To reproduce the local delivery bundles from the completed, checksum-bound evaluations:

```sh
uv run --extra int4 python package_mobile_candidates.py \
  --all-languages --require-bound-reports \
  --builder-source-root reports/mobile-quantization/build-source \
  --output-root candidates/mobile-packages-reproduced
```

Each archive has a unique language/variant directory, its own decoder, evaluated-weight hashes, score snapshot, source attribution, and exact builder code. Both quantization types use the historical filename `indicconformer_multi_ctc_int8.onnx` because the existing Android and iOS loaders require that name for their multilingual decoder route. The package manifest records the actual INT4 or INT8 variant. No catalog plumbing needs changing to parse these bundles.

### Speech data and distant-microphone checks

`acquire_evaluation_corpus.py` obtains publisher-provided IndicVoices archives. Calibration uses `train`; evaluation uses `valid`. It selects natural conversation/extempore speech, retains human normalized and verbatim annotations, and checks speaker, conversation/recording and audio-content separation. The initial pilot uses 10 calibration and 20 validation clips per language. This is a convenience regression set, not a representative population benchmark or an independent unseen test of the original checkpoint: the checkpoint itself used IndicVoices validation during development.

Retained WAVs, manifests and provenance are in `corpus/indicvoices-v1-pilot/`. Every evaluation row has `language`, human-reference `text`, numeric `features`, and `duration_seconds`; audio and source metadata are retained for audit. Numeric features are float32 `[1,80,T]` `.npy` or `.npz` with an integer `length`. Default evaluation expects all 22 languages; `--required-languages te` intentionally tests a single pack, while `--allow-partial` produces development-only partial coverage.

```sh
uv run python make_audio_stress_set.py \
  --manifest corpus/indicvoices-v1-pilot/te/evaluation/manifest.jsonl \
  --output candidates/te-stress-new \
  --conditions quiet_24db noise_10db room_350ms
```

Stress probes preserve each original transcript and speaker split. Quiet PCM16, additive white noise and artificial room reverberation are repeatable synthetic tests, not recordings from real phone microphones at a distance. They must not be counted as independent additional speakers. Natural speaking rate is preserved in the downloaded spontaneous speech. Quantization is evaluated for additional quality loss; improving the underlying model's real far-field recognition may require representative training and microphone-path testing.

### Frontend compatibility

The verified official mono and multilingual checkpoint configurations specify a 400-sample (25 ms) symmetric Hann window, 160 hop, FFT512, reflection padding, preemphasis .97, 80 Slaney mel bands, `log(x + 2^-24)`, and time-wise unbiased standard deviation plus `1e-5`. Veena's current Rust frontend uses a 320-sample window, zero boundary padding and different epsilons. `export_nemo_features.py` builds a separate experimental corpus for comparison; mobile defaults have not been changed. A matched-PCM comparison across 160 natural-speech clips improved WER in five priority languages, worsened Tamil and Gujarati, and left Urdu unchanged. Pooled WER changed only from 17.47% to 17.31%; Tamil worsened by 3.10 percentage points. This does not justify a global frontend change. See `reports/frontend-ab/priority-summary.json` for the full comparison; it changes the complete frontend, not just window length.

Never swap tokenizers between model families. The 600M shared model's vocabulary differs from the monolingual models even when global token indices are equal. Sources with local 257-class heads use their own vocabulary and blank256; legacy combined heads have5633 classes and blank5632. The evaluator supports both via decoder configuration. `prepare_language_models.py` packages third-party INT8 baselines using their actual output masks and source tokenizer, and marks them unvalidated.

All 44 compact/balanced models across all 22 languages passed a real-clip smoke through the production Rust `Transcriber::new_multilingual` and linked ONNX Runtime 1.24.2. All transcripts matched Python ONNX Runtime 1.26 on the same clips. This verifies the local-head decoder and INT4 `MatMulNBits` contract on this host; Android/iOS physical-device tests remain required. The Android binary's inspected version string is also 1.24.2, but that binary has not been executed here.

For repeatable host measurements, build the separate smoke binary and stop concurrent model jobs first:

```sh
cargo build --release -p veena-browser-reference --bin model_smoke
uv run --extra int4 python benchmark_native_models.py --output candidates/native-timing-new
```

This measures model load, process peak RSS, and preprocessing/inference/decoding on one repeated public clip per language. It uses the production constructor's CPU thread defaults. It is a host timing pilot, not a phone-speed or representative latency claim.

### Sources and attribution

- [AI4Bharat IndicConformerASR](https://github.com/AI4Bharat/IndicConformerASR): original MIT model checkpoints.
- [AI4Bharat IndicVoices](https://huggingface.co/datasets/ai4bharat/IndicVoices), CC BY4.0; [Javed et al., 2024](https://arxiv.org/abs/2403.01926). Archive links and per-recording provenance are retained with the corpus. Preserve attribution when distributing derived material.
- [OpenVoiceOS ONNX exports](https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-te-onnx): compact local CTC heads; 21 complete languages. Assamese uses the separately pinned full-head source.
- [sulabhkatiyar FP32 exports](https://huggingface.co/sulabhkatiyar/indicconformer-120m-onnx), repository CC BY4.0, and [parismitaglobalsolutions INT8 baselines](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx). Exact revisions, hashes, formats and license metadata are in `model_sources.json`.
- [ONNX Runtime quantization](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html).

Generated weights and licensed audio are ignored by Git. Do not publish failed candidates or replace production catalogue entries based only on this pilot. The exporter reads Hugging Face credentials from `HF_TOKEN` or `hf auth login`; no access token is embedded in source.

### Original Telugu smoke-test transcript

This pre-existing text is retained as a smoke-test reference, not treated as independently verified human evaluation labels.

తెలుగు స్పీచ్ టు టెక్స్ట్ టెస్ట్ ఆడియో వన్ మా దుకాణానికి ఒక వ్యక్తి వచ్చి పది బస్తాలు యూరియా తీసుకున్నారు రెండు ప్యాకెట్ల నూనె తీసుకున్నారు మరియు నాలుగు ప్యాకెట్ల విత్తనాలు సొరకాయ ఇత్తనాలు తీసుకువెళ్లారు
