"""
Multilingual ASR inference using the exported encoder+CTC ONNX model.

Supports all 22 Indian languages from IndicConformer-600M-Multilingual.

Usage:
    python transcribe_multilingual.py <audio_file> [language_code]
    python transcribe_multilingual.py telugu_test_audio.wav te

Language codes: as, bn, brx, doi, gu, hi, kn, kok, ks, mai, ml, mni, mr, ne, or, pa, sa, sat, sd, ta, te, ur
"""

import json
import os
import sys
from pathlib import Path
from typing import Optional

import numpy as np
import onnxruntime as ort
import soundfile as sf
import torch
import torchaudio.transforms as T


class MultilingualASR:
    def __init__(
        self,
        model_dir: str = "models/multilingual_ctc",
        model_name: str = "encoder_ctc_multilingual.onnx",
    ):
        self.model_dir = Path(model_dir)
        self.model_path = self.model_dir / model_name
        self._sample_rate = 16000
        self._n_mels = 80
        self._window_length = 320
        self._hop_length = 160
        self._fft_length = 512
        self._preemphasis = 0.97
        self._log_guard = 1e-9
        self._blank_id = 256

        # Load ONNX model
        if not self.model_path.exists():
            raise FileNotFoundError(f"Model not found: {self.model_path}")
        self.session = ort.InferenceSession(
            str(self.model_path),
            providers=["CUDAExecutionProvider" if ort.get_device() == "GPU" else "CPUExecutionProvider"]
        )

        # Load vocab and language masks
        vocab_path = self.model_dir / "vocab.json"
        masks_path = self.model_dir / "language_masks.json"
        if not vocab_path.exists() or not masks_path.exists():
            raise FileNotFoundError(
                f"vocab.json or language_masks.json missing in {self.model_dir}"
            )
        with open(vocab_path) as f:
            self.vocab: dict[str, list[str]] = json.load(f)
        with open(masks_path) as f:
            self.language_masks: dict[str, list[int]] = json.load(f)

    @staticmethod
    def _apply_preemphasis(audio: torch.Tensor, coeff: float) -> torch.Tensor:
        return torch.cat((audio[:, :1], audio[:, 1:] - coeff * audio[:, :-1]), dim=1)

    @staticmethod
    def _normalize_per_feature(features: torch.Tensor, length: int) -> torch.Tensor:
        feat = features[:, :, :length]
        mean = feat.mean(dim=1, keepdim=True)
        std = feat.std(dim=1, unbiased=True, keepdim=True)
        features[:, :, :length] = (feat - mean) / (std + 1e-9)
        return features

    def extract_features(self, audio: np.ndarray, sr: int) -> tuple[np.ndarray, np.ndarray]:
        # Resample to 16kHz
        if sr != self._sample_rate:
            from librosa import resample
            audio = resample(audio, orig_sr=sr, target_sr=self._sample_rate)

        audio_t = torch.from_numpy(audio).float().unsqueeze(0)
        audio_t = self._apply_preemphasis(audio_t, self._preemphasis)

        mel = T.MelSpectrogram(
            sample_rate=self._sample_rate,
            n_fft=self._fft_length,
            win_length=self._window_length,
            hop_length=self._hop_length,
            f_min=0.0,
            f_max=8000.0,
            n_mels=self._n_mels,
            window_fn=torch.hann_window,
            power=2.0,
            center=True,
            norm="slaney",
            mel_scale="slaney",
        )
        feats = mel(audio_t)
        feats = torch.log(feats + self._log_guard)

        T_frame = feats.shape[2]
        feats = self._normalize_per_feature(feats, T_frame)

        return feats.cpu().numpy().astype(np.float32), np.array([T_frame], dtype=np.int64)

    def decode_language(self, logprobs: np.ndarray, lang: str) -> str:
        """CTC greedy decoding for a specific language."""
        mask = self.language_masks[lang]

        # Mask to per-language vocab, then log_softmax
        lp = torch.from_numpy(logprobs[0, :, mask]).log_softmax(dim=-1)

        indices = lp.argmax(dim=-1)
        collapsed = indices.unique_consecutive()

        tokens = [self.vocab[lang][x.item()] for x in collapsed if x.item() != self._blank_id]
        return "".join(tokens).replace("▁", " ").strip()

    def transcribe(self, audio: np.ndarray, sr: int, lang: str = "te") -> str:
        if lang not in self.vocab:
            raise ValueError(
                f"Unsupported language '{lang}'. Choose from: {list(self.vocab.keys())}"
            )

        feats, lengths = self.extract_features(audio, sr)

        input_names = [i.name for i in self.session.get_inputs()]
        feeds = dict(zip(input_names, [feats, lengths]))
        logprobs = self.session.run(None, feeds)[0]

        return self.decode_language(logprobs, lang)

    def transcribe_file(self, wav_path: str, lang: str = "te") -> str:
        audio, sr = sf.read(wav_path)
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        return self.transcribe(audio.astype(np.float32), sr, lang)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    wav_path = sys.argv[1]
    lang = sys.argv[2] if len(sys.argv) > 2 else "te"

    try:
        asr = MultilingualASR()
        text = asr.transcribe_file(wav_path, lang)
        print(f"[{lang}] {text}")
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
