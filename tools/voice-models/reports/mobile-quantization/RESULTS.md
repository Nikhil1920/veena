# Mobile quantization results

44 local candidate bundles cover all 22 scheduled languages. They use the existing mobile loader contract and support downloading selected languages. These are screened test candidates; no physical-phone release acceptance is claimed.

One model per language (balanced INT8, 21 languages; none for Manipuri) is hosted at `https://dl.byanr.com/veena-byanr/models/` (see [Hosted downloads](../../README.md#hosted-downloads)) · [Checksums](https://dl.byanr.com/veena-byanr/models/SHA256SUMS)

## Size and accuracy

| Variant | Download per language (decimal MB) | Raw model (MB) | Clean gate vs shipped | Clean gate vs FP32 |
|---|---:|---:|---:|---:|
| Compact INT4 | 94.82–94.85 | ≈102.97 | 19/22 | 13/22 |
| Balanced INT8 | 130.28–130.37 | ≈146.75 | 21/22 | 20/22 |

The fixed pilot gate permits at most one percentage point of WER and CER regression per language on the same 20 human-labeled clips. Passing does not establish statistical confidence or performance on new speakers. Every candidate score is bound to model and decoder SHA256 digests verified before and after inference. Historical FP32/shipped baseline reports retain explicit unbound status; their numerical comparisons are preserved, without inventing retrospective digests.

The current shared download is 478.58 MB (675.85 MB raw). Dedicated Telugu is 124.77 MB (156.40 MB raw): balanced Telugu has a slightly larger download despite a smaller raw model.

Languages below follow the [2011 India Census mother-tongue ranking](https://censusindia.gov.in/nada/index.php/catalog/10191). These are not current worldwide population estimates.

| Language | Shipped WER | Compact WER | Balanced WER | Compact vs shipped | Balanced vs shipped |
|---|---:|---:|---:|---|---|
| Hindi | 23.83% | 15.23% | 13.76% | Pass | Pass |
| Bengali | 9.34% | 7.69% | 7.69% | Pass | Pass |
| Marathi | 17.24% | 14.73% | 14.42% | Pass | Pass |
| Telugu | 25.00% | 25.00% | 23.91% | Pass | Pass |
| Tamil | 39.31% | 40.00% | 38.62% | Pass | Pass |
| Gujarati | 13.06% | 12.31% | 11.57% | Pass | Pass |
| Urdu | 10.11% | 11.99% | 9.74% | FAIL | Pass |
| Kannada | 29.96% | 25.10% | 25.91% | Pass | Pass |
| Odia | 18.77% | 18.09% | 18.43% | Pass | Pass |
| Malayalam | 52.59% | 47.01% | 48.61% | Pass | Pass |
| Punjabi | 13.23% | 9.85% | 11.69% | Pass | Pass |
| Assamese | 11.11% | 10.00% | 8.89% | Pass | Pass |
| Maithili | 26.45% | 25.90% | 23.97% | Pass | Pass |
| Santali | 55.35% | 42.07% | 39.11% | Pass | Pass |
| Kashmiri | 36.12% | 31.77% | 29.43% | Pass | Pass |
| Nepali | 15.27% | 11.45% | 11.83% | Pass | Pass |
| Sindhi | 22.34% | 19.21% | 19.21% | Pass | Pass |
| Dogri | 40.51% | 32.73% | 30.92% | Pass | Pass |
| Konkani | 24.09% | 24.65% | 23.81% | Pass | Pass |
| Manipuri | 19.83% | 21.55% | 22.84% | FAIL | FAIL |
| Bodo | 28.87% | 29.93% | 29.23% | FAIL | Pass |
| Sanskrit | 32.47% | 27.27% | 26.62% | Pass | Pass |

Compact Urdu and Bodo fail the shipped-model gate; both Manipuri variants fail it. Manipuri FP32 itself is worse than the shipped model on this pilot, so the smaller source model contributes to that regression. Balanced Tamil and Santali also fail the stricter comparison to their original FP32 source, even though they pass against the shipped model. No failed candidate is silently promoted.

## Off-microphone and natural speech

The first eight languages were screened on quiet PCM16 (−24 dB), additive white noise (10 dB SNR), and artificial room reverberation (350 ms RT60). All 96 model/condition reports completed successfully. 47 of 48 candidate-vs-shipped comparisons passed; balanced Telugu on the noise probe was the exception (32.97% WER versus 31.52% shipped). Compact Telugu passed all three probes and matched shipped WER on original clips.

These 480 transformed clips reuse the original 160 utterances and are not independent additional speakers or real far-field phone recordings. The downloaded spontaneous speech retains natural speaking rates. An exploratory fastest-five-clips-per-language analysis generally favors the language-specific models, but rate is confounded with speaker, content, pauses, and noise; it is not a causal fast-speech benchmark.

The complete source frontend uses a 25 ms window; the app uses 20 ms and different padding/normalization details. A controlled matched-PCM frontend comparison improved WER in five priority languages, worsened Tamil and Gujarati, and left Urdu unchanged. Pooled WER changed only 17.47%→17.31%; Tamil worsened by 3.10 points. App preprocessing remains unchanged.

[Stress results](robustness/summary.json) · [Speaking-rate analysis](speaking-rate-screen.json) · [Frontend comparison](../frontend-ab/priority-summary.json)

## Native runtime and host timing

All 44 models loaded and transcribed through the production Rust backend with ONNX Runtime 1.24.2. All 44 transcripts matched Python ONNX Runtime 1.26 on the same real clips. This checks the local 257-class decoders and INT4 MatMulNBits kernel on this host. The inspected Android library also reports 1.24.2; a physical Android/iOS device was not attached.

The following separate timing run stopped concurrent model jobs, used one real clip per language, two warmups and five measured repetitions, and rotated model order. Values are median preprocessing + inference + decoding time with the production CPU defaults on the Mac. They do not predict phone latency or representative tail latency.

| Language | Clip seconds | Shipped ms | Compact ms | Balanced ms |
|---|---:|---:|---:|---:|
| Hindi | 5.45 | 192.1 | 83.7 | 78.3 |
| Bengali | 2.75 | 110.1 | 47.2 | 41.4 |
| Marathi | 11.94 | 460.2 | 174.6 | 172.0 |
| Telugu | 5.06 | 69.9 | 79.6 | 70.2 |
| Tamil | 7.74 | 270.5 | 116.2 | 109.1 |
| Gujarati | 11.39 | 427.7 | 165.5 | 158.8 |
| Urdu | 2.75 | 120.4 | 47.1 | 35.6 |
| Kannada | 2.18 | 90.0 | 36.0 | 35.5 |

For the seven priority languages using the shared model, candidates were about 2.3–3.4× faster on these paired clips. Native process peak RSS fell from roughly 1.41–1.51 GB to 0.31–0.57 GB compact or 0.43–0.68 GB balanced. Telugu compact was about 14% slower than its existing dedicated model, while balanced was about equal; both used less process memory in this host pilot.

[Native compatibility](native-smoke/summary.json) · [Timing and memory records](native-timing/summary.json)

## Data, reproducibility and validation

Public publisher-provided [IndicVoices](https://huggingface.co/datasets/ai4bharat/IndicVoices) archives supplied 220 calibration clips (49 speakers, 21.1 minutes) and 440 evaluation clips (130 speakers, 54.1 minutes). Train/valid selections have zero speaker, conversation-group or audio-hash overlap. Evaluation includes 323 extempore and 117 conversation clips. Human references and source attribution are retained. This convenience pilot is not population-representative, and the original checkpoint used IndicVoices during development; it is not a checkpoint-unseen test set.

The 44 candidate clean screens contain 880 successful model/utterance evaluations. Final validation passed 103 Python tests and two Rust CLI tests; the strengthened FP32 guard also accepted all 22 real source graphs. Original builder files are archived and hashed with every bundle because the source guard was tightened after weights were built. Existing weights and build records were preserved.

Full static QDQ and constant-weight static variants severely degraded Telugu recognition and were rejected. Compact uses block-32 INT4 constant matrix weights with equivalent pointwise-convolution rewrites; balanced uses per-channel dynamic INT8 and preserves the final CTC head in FP32. Depthwise/strided convolutions remain floating point.

Each archive includes its own vocabulary/mask, verified model, source/license attribution, original build recipe/code, bound clean score, native smoke and checksums. The app-required legacy filename contains “int8” even for INT4; the candidate manifest declares the actual quantization. Total storage for all 44 downloadable alternatives is 4.95 GB; installations should select languages and variants rather than install every alternative.

[Reproduction workflow](../../README.md) · Corpus audit (`corpus/indicvoices-v1-pilot/final-audit.json`, generated locally by `acquire_evaluation_corpus.py`) · [Python tests](final-tests.log) · [Rust tests](final-rust-tests.log)
