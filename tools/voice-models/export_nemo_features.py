"""Export deterministic evaluation features matching IndicConformer's NeMo frontend.

Implements the pinned AI4Bharat/NeMo FilterbankFeatures evaluation algorithm and
Telugu checkpoint config, without importing or executing downloaded source code.
Source: https://github.com/AI4Bharat/NeMo/blob/8dce88cf8e94963e2033c3137f7b9993b51db88a/nemo/collections/asr/parts/preprocessing/features.py
The original algorithm is Apache-2.0 licensed; this standalone implementation
preserves its numeric order, framing, guards, and valid-length normalization.

Use --rust-comparison-dir to export the existing Rust frontend on the very same
resampled 16 kHz PCM. This separates frontend changes from resampler differences.
These are experiment artifacts; no application frontend is modified.
"""
from __future__ import annotations

import argparse
from functools import lru_cache
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

import librosa
import numpy as np
import soundfile as sf
import torch

CONFIG = {
    'sample_rate': 16000, 'window_samples': 400, 'hop_samples': 160,
    'fft_length': 512, 'window': 'hann', 'periodic_window': False,
    'center': True, 'stft_boundary_padding': 'reflect', 'preemphasis': .97,
    'mel_bins': 80, 'mel_scale': 'slaney', 'mel_normalization': 'slaney',
    'fmin': 0, 'fmax': 8000, 'power': 2, 'log_add': 2 ** -24,
    'std_unbiased': True, 'normalization_additive_epsilon': 1e-5,
    'dither_eval': 0, 'pad_to': 0, 'frame_splicing': 1,
}


def sha256(path: Path) -> str:
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


@lru_cache(maxsize=1)
def _buffers():
    window = torch.hann_window(400, periodic=False, dtype=torch.float32)
    mel = torch.tensor(librosa.filters.mel(sr=16000, n_fft=512, n_mels=80,
                                         fmin=0, fmax=8000, norm='slaney'),
                       dtype=torch.float32).unsqueeze(0)
    return window, mel


def sequence_length(samples: int) -> int:
    # NeMo get_seq_len: floor((samples + 2*(n_fft//2) - n_fft)/hop) + 1.
    return samples // 160 + 1


@torch.no_grad()
def compute_features(audio: np.ndarray, valid_samples: int | None = None) -> tuple[np.ndarray, np.ndarray]:
    """Accept mono 16 kHz floats; return [1,80,T] and true frame length.

    valid_samples supports a zero-padded utterance as in NeMo batching. Statistics
    use only valid frames, and invalid tail frames are zeroed after normalization.
    """
    samples = np.asarray(audio, dtype=np.float32)
    if samples.ndim != 1 or samples.size <= 256 or not np.isfinite(samples).all():
        raise ValueError('Expected finite mono 16 kHz audio longer than 256 samples for reflect padding')
    if valid_samples is None:
        valid_samples = samples.size
    if isinstance(valid_samples, bool) or not isinstance(valid_samples, (int, np.integer)) or not 160 <= valid_samples <= samples.size:
        raise ValueError('valid_samples must be an integer in [160, audio length]')
    length = sequence_length(int(valid_samples))
    window, mel = _buffers()
    signal = torch.from_numpy(np.ascontiguousarray(samples)).unsqueeze(0)
    signal = torch.cat((signal[:, :1], signal[:, 1:] - .97 * signal[:, :-1]), dim=1)
    spectrum = torch.stft(signal, n_fft=512, hop_length=160, win_length=400,
                          window=window, center=True, pad_mode='reflect', return_complex=True)
    # Retain NeMo's sqrt-then-square ordering instead of replacing it with abs².
    magnitude = torch.sqrt(torch.view_as_real(spectrum).pow(2).sum(-1))
    log_mel = torch.log(torch.matmul(mel, magnitude.pow(2.0)) + 2 ** -24)
    valid = log_mel[:, :, :length]
    mean = valid.mean(dim=2, keepdim=True)
    std = valid.std(dim=2, keepdim=True, unbiased=True) + 1e-5
    features = (log_mel - mean) / std
    features[:, :, length:] = 0
    array = features.numpy().copy()
    if not np.isfinite(array).all():
        raise ValueError('Frontend produced nonfinite features')
    return array, np.array([length], dtype=np.int64)


def load_audio(path: Path) -> tuple[np.ndarray, int]:
    samples, rate = sf.read(path, dtype='float32', always_2d=True)
    mono = samples.mean(axis=1, dtype=np.float32)
    if rate != 16000:
        # Pinned NeMo AudioSegment calls librosa.core.resample; make the installed
        # librosa default explicit and record it in generated provenance.
        mono = librosa.resample(mono, orig_sr=rate, target_sr=16000, res_type='soxr_hq')
    return np.ascontiguousarray(mono, dtype=np.float32), rate


def export_manifest(manifest: Path, output: Path, *, rust_comparison_dir: Path | None = None,
                    rust_exporter: Path | None = None) -> dict:
    manifest, output = manifest.resolve(), output.resolve()
    if (output / 'manifest.jsonl').exists():
        raise FileExistsError(f'Manifest already exists: {output / "manifest.jsonl"}')
    if rust_comparison_dir is not None:
        rust_comparison_dir = rust_comparison_dir.resolve()
        if rust_exporter is None or not rust_exporter.is_file():
            raise ValueError('A built Rust feature exporter is required for matched-PCM comparison')
        if (rust_comparison_dir / 'manifest.jsonl').exists():
            raise FileExistsError('Rust comparison manifest already exists')
        (rust_comparison_dir / 'features').mkdir(parents=True, exist_ok=True)
    (output / 'features').mkdir(parents=True, exist_ok=True)
    (output / 'audio16k').mkdir(parents=True, exist_ok=True)
    rows, rust_rows = [], []
    for number, line in enumerate(manifest.read_text(encoding='utf-8').splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        original = (manifest.parent / row['audio']).resolve()
        pcm, original_rate = load_audio(original)
        original_id = str(row.get('id', number))
        filename = original_id if re.fullmatch(r'[A-Za-z0-9_.-]+', original_id) and original_id not in ('.', '..') else f'{number}-{hashlib.sha256(original_id.encode()).hexdigest()[:12]}'
        normalized_audio = output / 'audio16k' / f'{filename}.wav'
        feature_path = output / 'features' / f'{filename}.npz'
        sf.write(normalized_audio, pcm, 16000, subtype='FLOAT')
        features, length = compute_features(pcm)
        np.savez(feature_path, features=features, length=length)
        updated = dict(row)
        updated.update(features=str(feature_path.relative_to(output)), audio=str(normalized_audio.relative_to(output)),
                       original_audio=str(original), original_audio_sha256=sha256(original),
                       original_sample_rate=original_rate, sample_rate=16000,
                       audio_sha256=sha256(normalized_audio), frontend='nemo400_eval',
                       frontend_config=CONFIG)
        rows.append(updated)
        if rust_comparison_dir is not None:
            rust_feature = rust_comparison_dir / 'features' / f'{filename}.npy'
            subprocess.run([str(rust_exporter.resolve()), str(normalized_audio), str(rust_feature)],
                           check=True, stdout=subprocess.DEVNULL)
            matched = dict(updated)
            matched.update(features=str(rust_feature.relative_to(rust_comparison_dir)),
                           audio=os.path.relpath(normalized_audio, rust_comparison_dir),
                           frontend='rust320_same_nemo_resampled_pcm')
            matched.pop('frontend_config')
            rust_rows.append(matched)
    if not rows:
        raise ValueError('Source manifest is empty')
    def save_rows(directory, items):
        (directory / 'manifest.jsonl').write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in items), encoding='utf-8')
    save_rows(output, rows)
    if rust_comparison_dir is not None:
        save_rows(rust_comparison_dir, rust_rows)
    report = {'source_manifest': str(manifest), 'source_manifest_sha256': sha256(manifest),
              'samples': len(rows), 'frontend': CONFIG, 'resampler': 'librosa soxr_hq',
              'librosa_version': librosa.__version__, 'torch_version': torch.__version__,
              'output_manifest': str(output / 'manifest.jsonl'),
              'matched_pcm_rust_manifest': str(rust_comparison_dir / 'manifest.jsonl') if rust_comparison_dir else None,
              'source_revision': '8dce88cf8e94963e2033c3137f7b9993b51db88a',
              'labels_preserved': True, 'application_modified': False}
    (output / 'frontend-export.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--rust-comparison-dir', type=Path)
    parser.add_argument('--rust-exporter', type=Path, default=Path(__file__).resolve().parents[2] / 'target/release/export_features')
    args = parser.parse_args()
    torch.set_num_threads(1)
    result = export_manifest(args.manifest, args.output_dir, rust_comparison_dir=args.rust_comparison_dir,
                             rust_exporter=args.rust_exporter)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
