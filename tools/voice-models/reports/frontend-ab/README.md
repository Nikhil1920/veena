# Priority-language frontend comparison

The NeMo400 frontend does **not consistently improve** recognition across these eight language pilots. WER improves in Hindi, Bengali, Marathi, Telugu, and Kannada; it worsens in Tamil and Gujarati; Urdu is unchanged. Tamil regresses by 3.10 percentage points. The pooled change is small and hides these language differences.

Each comparison uses 20 human-annotated conversational/extempore clips, the same FP32 model and decoder, and exactly the same resampled 16 kHz PCM. Saved audio SHA256 values were checked against the audio bytes and between paired manifests. Telugu reuses the existing run; the other seven use their own downloaded FP32 language models. Original pilot audio, features, and runtime defaults are unchanged.

| Language | Rust320 WER | NeMo400 WER | WER change (pp) | Rust320 CER | NeMo400 CER |
|---|---:|---:|---:|---:|---:|
| hi | 14.00% | 12.78% | -1.23 | 5.59% | 5.01% |
| bn | 7.97% | 6.87% | -1.10 | 2.60% | 2.26% |
| mr | 14.73% | 13.79% | -0.94 | 3.86% | 3.81% |
| te | 23.55% | 23.19% | -0.36 | 5.49% | 5.49% |
| ta | 36.55% | 39.66% | +3.10 | 8.69% | 9.09% |
| gu | 11.94% | 12.31% | +0.37 | 3.76% | 3.97% |
| ur | 9.74% | 9.74% | +0.00 | 2.98% | 2.89% |
| kn | 25.91% | 25.51% | -0.40 | 5.69% | 5.91% |

Across all 160 clips, pooled WER changes from 17.47% to 17.31%, and pooled CER from 5.07% to 5.06%. These are measurements on this convenience sample, not population accuracy estimates.

This compares complete frontend implementations, including the window, log guard, and normalization details. It does not isolate window length alone. Both sides use matched PCM to remove resampling as a cause of the paired changes.

Runs used CPUExecutionProvider, two intra-op threads, no warmup, and one pass while other model jobs were active. The reports explicitly exclude latency and target-device performance qualification. The corpus has no controlled distant-microphone recordings, and the result does not establish an off-mic fix or justify switching every language to the new frontend.

Detailed results and provenance: `priority-summary.json`, the per-language `*-fp32.json` reports, and the generated feature manifests under `corpus/frontend-ab/`. Run `run_priority.py` to verify/reuse this completed comparison; existing source and paired waveform hashes are validated.
