"""Numerical framing checks against an independent NumPy/librosa formulation."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

import librosa
import numpy as np
import soundfile as sf
import torch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import export_nemo_features as frontend


class NemoFeatureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)

    def test_matches_independent_numpy_librosa_spectrum_and_unbiased_normalization(self):
        signal = np.random.default_rng(19).normal(0, .03, 7231).astype(np.float32)
        actual, length = frontend.compute_features(signal)
        emphasized = np.r_[signal[:1], signal[1:] - np.float32(.97) * signal[:-1]]
        stft = librosa.stft(emphasized, n_fft=512, hop_length=160, win_length=400,
                            window=np.hanning(400).astype(np.float32), center=True, pad_mode='reflect')
        power = np.sqrt(stft.real ** 2 + stft.imag ** 2) ** 2
        mel = librosa.filters.mel(sr=16000, n_fft=512, n_mels=80, fmin=0, fmax=8000, norm='slaney') @ power
        logged = np.log(mel + np.float32(2 ** -24))
        reference = (logged - logged.mean(axis=1, keepdims=True)) / (logged.std(axis=1, keepdims=True, ddof=1) + np.float32(1e-5))
        self.assertEqual(actual.shape, (1, 80, 46))
        self.assertEqual(length.tolist(), [46])
        np.testing.assert_allclose(actual[0], reference, atol=3e-5, rtol=3e-5)

    def test_sequence_lengths_follow_centered_fft_formula(self):
        for samples in (257, 319, 320, 16000, 16001, 16159, 16160):
            with self.subTest(samples=samples):
                features, length = frontend.compute_features(np.zeros(samples, dtype=np.float32))
                self.assertEqual(length[0], samples // 160 + 1)
                self.assertEqual(features.shape[-1], length[0])
                self.assertTrue(np.isfinite(features).all())

    def test_valid_length_controls_statistics_and_zeroes_padded_tail(self):
        signal = np.random.default_rng(1).normal(size=2000).astype(np.float32)
        signal[1600:] = 0
        features, length = frontend.compute_features(signal, valid_samples=1600)
        self.assertEqual(length.tolist(), [11])
        self.assertTrue(np.all(features[:, :, 11:] == 0))
        np.testing.assert_allclose(features[:, :, :11].mean(axis=2), 0, atol=3e-6)
        self.assertTrue(np.all(features[:, :, :11].std(axis=2, ddof=1) < 1))

    def test_eval_is_deterministic_and_invalid_audio_is_rejected(self):
        signal = np.random.default_rng(2).normal(size=1000).astype(np.float32)
        np.testing.assert_array_equal(frontend.compute_features(signal)[0], frontend.compute_features(signal)[0])
        for invalid in (np.zeros(256), np.zeros((1, 300)), np.full(300, np.nan), np.full(300, np.inf)):
            with self.assertRaises(ValueError):
                frontend.compute_features(invalid)
        for length in (0, 159, 1001, True):
            with self.assertRaises(ValueError):
                frontend.compute_features(signal, valid_samples=length)

    def test_manifest_export_preserves_labels_and_source_provenance(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            audio = root / 'input.wav'
            sf.write(audio, np.random.default_rng(2).normal(0, .02, 8000), 8000, subtype='FLOAT')
            row = {'id': 'one', 'language': 'te', 'text': 'ఒక పరీక్ష', 'audio': audio.name,
                   'features': 'unused.npy', 'duration_seconds': 1, 'sample_rate': 8000,
                   'speaker_id': 'speaker-one', 'source_verbatim_text': 'వేరే అసలు పాఠం'}
            manifest = root / 'manifest.jsonl'
            manifest.write_text(json.dumps(row, ensure_ascii=False) + '\n')
            output = root / 'nemo400'
            report = frontend.export_manifest(manifest, output)
            exported = json.loads((output / 'manifest.jsonl').read_text())
            for key in ('id', 'text', 'speaker_id', 'source_verbatim_text'):
                self.assertEqual(exported[key], row[key])
            self.assertEqual(exported['original_sample_rate'], 8000)
            self.assertEqual(exported['sample_rate'], 16000)
            self.assertEqual(report['samples'], 1)
            with np.load(output / exported['features']) as data:
                self.assertEqual(data['features'].dtype, np.float32)
                self.assertEqual(data['length'].tolist(), [101])
                self.assertTrue(np.isfinite(data['features']).all())
            with self.assertRaises(FileExistsError):
                frontend.export_manifest(manifest, output)


if __name__ == '__main__':
    unittest.main()
