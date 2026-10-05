"""Create deterministic audio stress probes; these are NOT real far-field recordings.

Retains the human transcript and provenance of each source utterance. Use this
separately on calibration and held-out manifests; never mix speakers or splits.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

import numpy as np
from scipy.signal import fftconvolve
import soundfile as sf

ROOT = Path(__file__).resolve().parent
CONDITIONS = ("quiet_12db", "quiet_24db", "noise_20db", "noise_10db", "room_350ms")


def perturb(audio: np.ndarray, rate: int, condition: str, seed: int) -> np.ndarray:
    if condition not in CONDITIONS or rate <= 0 or audio.ndim != 1 or not len(audio) or not np.isfinite(audio).all():
        raise ValueError("Expected a supported condition and finite nonempty mono audio")
    random = np.random.default_rng(seed)
    audio = audio.astype(np.float64)
    if condition.startswith("quiet_"):
        # Quantizing to PCM16 in main also exposes the low-level resolution loss.
        result = audio * 10 ** (-int(condition.split("_")[1][:-2]) / 20)
    elif condition.startswith("noise_"):
        snr = int(condition.split("_")[1][:-2])
        noise = random.standard_normal(len(audio))
        noise /= max(float(np.sqrt(np.mean(noise ** 2))), 1e-12)
        result = audio + noise * np.sqrt(np.mean(audio ** 2)) * 10 ** (-snr / 20)
    else:
        # Artificial exponentially decaying impulse response, RT60 = .35 s.
        # This is only a repeatable reverberation probe, not a measured room RIR.
        times = np.arange(int(rate * .35)) / rate
        impulse = random.standard_normal(len(times)) * np.exp(-np.log(1000) * times / .35)
        impulse /= max(float(np.linalg.norm(impulse)), 1e-12)
        impulse *= .4
        impulse[0] += 1
        result = fftconvolve(audio, impulse)
        result *= np.sqrt(np.mean(audio ** 2)) / max(float(np.sqrt(np.mean(result ** 2))), 1e-12)
    # Attenuate only to prevent clipping; do not normalize quiet conditions back up.
    peak = float(np.max(np.abs(result)))
    if peak > .999:
        result *= .999 / peak
    return result.astype(np.float32)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True, help="Corpus JSONL with relative audio paths and human references")
    parser.add_argument("--output", type=Path, required=True, help="New directory; refuses overwrites")
    parser.add_argument("--conditions", choices=CONDITIONS, nargs="+", default=list(CONDITIONS))
    parser.add_argument("--exporter", type=Path, default=ROOT.parents[1] / "target/release/export_features")
    args = parser.parse_args()
    if not args.exporter.is_file():
        parser.error("Build the Rust export_features binary first")
    if args.output.exists():
        parser.error("Output already exists; choose a new directory")
    rows = [json.loads(line) for line in args.manifest.read_text().splitlines() if line.strip()]
    if not rows:
        parser.error("Source manifest is empty")
    args.output.mkdir(parents=True)
    for condition in dict.fromkeys(args.conditions):
        directory = args.output / condition
        (directory / "audio").mkdir(parents=True)
        (directory / "features").mkdir()
        results = []
        for row in rows:
            source = (args.manifest.parent / row["audio"]).resolve()
            audio, rate = sf.read(source, dtype="float32", always_2d=True)
            key = hashlib.sha256((row["id"] + condition).encode()).hexdigest()
            changed = perturb(audio.mean(axis=1), rate, condition, int(key[:16], 16))
            audio_path = directory / "audio" / f"{key[:20]}.wav"
            feature_path = directory / "features" / f"{key[:20]}.npy"
            sf.write(audio_path, changed, rate, subtype="PCM_16")
            subprocess.run([str(args.exporter.resolve()), str(audio_path), str(feature_path)], check=True, capture_output=True, text=True)
            result = {**row, "id": f"{row['id']}:{condition}", "source_utterance_id": row["id"],
                      "audio": str(audio_path.relative_to(directory)), "features": str(feature_path.relative_to(directory)),
                      "original_clip_audio_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                      "audio_sha256": hashlib.sha256(audio_path.read_bytes()).hexdigest(),
                      "duration_seconds": len(changed) / rate, "condition": condition,
                      "stress_seed": int(key[:16], 16), "synthetic_stress_probe": True}
            results.append(result)
        (directory / "manifest.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in results))
        print(f"{condition}: {len(results)} probes", flush=True)
    (args.output / "source.json").write_text(json.dumps({
        "source_manifest": str(args.manifest.resolve()),
        "source_manifest_sha256": hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
        "conditions": args.conditions, "status": "synthetic_stress_probes_only",
        "limitations": "Synthetic quiet/noise/reverberation probes cannot establish real off-mic accuracy. Natural speaking rate comes from the unmodified spontaneous-speech corpus. No synthetic sample counts as an independent speaker or utterance.",
    }, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
