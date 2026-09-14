import os
import json
from typing import Tuple

import numpy as np
import onnxruntime as ort
import soundfile as sf
import torch
import torchaudio.transforms as T
from librosa import resample


class StandaloneASRCTC:
    def __init__(self, model_dir: str = "models", model_name: str = "indicconformer_stt_te_ctc.onnx"):
        """Initialize the ASR system from saved components."""
        self.model_dir = model_dir
        self.model_name = model_name
        self.device = "cuda" if ort.get_device() == "GPU" else "cpu"
        self._sample_rate = 16000
        self._num_mel_bins = 80
        self._window_length = 320
        self._hop_length = 160
        self._fft_length = 512
        self._preemphasis = 0.97
        self._log_guard = 1e-9

        # Ensure model_dir exists
        if not os.path.exists(model_dir):
            raise FileNotFoundError(
                f"Model directory {model_dir} does not exist")

        # Load ONNX model
        onnx_path = os.path.join(
            model_dir, self.model_name)
        if not os.path.exists(onnx_path):
            raise FileNotFoundError(f"ONNX model file {onnx_path} not found")
        self.session = ort.InferenceSession(
            onnx_path,
            providers=["CUDAExecutionProvider" if self.device ==
                       "cuda" else "CPUExecutionProvider"]
        )

        # Load decoder config
        decoder_config_path = os.path.join(model_dir, "decoder_config.json")
        if not os.path.exists(decoder_config_path):
            raise FileNotFoundError(
                f"Decoder config {decoder_config_path} not found")
        with open(decoder_config_path, "r") as f:
            self.decoder_config = json.load(f)

        self.blank_id = int(self.decoder_config.get("blank_idx", 5632))
        self.label_map = self._load_label_map()

    def _load_label_map(self) -> dict[int, str]:
        """Load the CTC label map used by the exported Telugu model."""

        vocab_path = os.path.join(self.model_dir, "tokenizer_te.vocab")
        if not os.path.exists(vocab_path):
            raise FileNotFoundError(f"Vocab file {vocab_path} not found")

        label_map: dict[int, str] = {}
        with open(vocab_path, "r", encoding="utf-8") as vocab_file:
            for label_id, line in enumerate(vocab_file):
                if label_id == 0:
                    continue
                token = line.split("\t", 1)[0].strip()
                label_map[5120 + label_id] = token
        return label_map

    @staticmethod
    def _apply_preemphasis(audio: torch.Tensor, coefficient: float) -> torch.Tensor:
        if audio.shape[1] <= 1 or coefficient is None:
            return audio
        return torch.cat((audio[:, :1], audio[:, 1:] - coefficient * audio[:, :-1]), dim=1)

    @staticmethod
    def _normalize_per_feature(features: torch.Tensor, lengths: torch.Tensor) -> torch.Tensor:
        normalized = features.clone()
        for batch_idx in range(features.shape[0]):
            valid_length = int(lengths[batch_idx].item())
            if valid_length <= 0:
                continue
            valid_features = features[batch_idx, :, :valid_length]
            mean = valid_features.mean(dim=1, keepdim=True)
            std = valid_features.std(dim=1, unbiased=True, keepdim=True)
            normalized[batch_idx, :, :valid_length] = (
                valid_features - mean) / (std + 1e-9)
            if valid_length < features.shape[2]:
                normalized[batch_idx, :, valid_length:] = 0.0
        return normalized

    def calculate_audio_features(self, audio: np.ndarray, sr: int) -> \
            Tuple[np.ndarray, np.ndarray]:
        # Resample to 16kHz if needed
        if sr != self._sample_rate:
            audio = resample(audio, orig_sr=sr, target_sr=self._sample_rate)

        # Convert to torch tensor and match the NeMo frontend as closely as possible:
        # pre-emphasis, mel spectrogram, log compression, then per-feature normalization.
        audio_tensor = torch.from_numpy(audio).float().unsqueeze(0)
        audio_tensor = self._apply_preemphasis(audio_tensor, self._preemphasis)
        mel_transform = T.MelSpectrogram(
            sample_rate=self._sample_rate,
            n_fft=self._fft_length,
            win_length=self._window_length,
            hop_length=self._hop_length,
            f_min=0.0,
            f_max=8000.0,
            n_mels=self._num_mel_bins,
            window_fn=torch.hann_window,
            power=2.0,
            normalized=False,
            center=True,
            norm="slaney",
            mel_scale="slaney",
        )
        feature_tensor = mel_transform(audio_tensor)
        feature_tensor = torch.log(feature_tensor + self._log_guard)

        audio_length = torch.tensor(
            [feature_tensor.shape[2]], dtype=torch.int64)
        feature_tensor = self._normalize_per_feature(
            feature_tensor, audio_length)

        audio_features = feature_tensor.cpu().numpy().astype(np.float32)

        # Validate shape
        expected_shape = (1, self._num_mel_bins, audio_features.shape[2])
        if audio_features.shape != expected_shape:
            raise ValueError(
                f"Expected audio features shape {expected_shape}, got {audio_features.shape}")

        return audio_features, audio_length.cpu().numpy()

    def preprocess_audio_file(self, wav_path: str) -> Tuple[np.ndarray, np.ndarray]:
        """Preprocess WAV file to its numppy array."""
        if not os.path.exists(wav_path):
            raise FileNotFoundError(f"WAV file {wav_path} not found")

        # Read audio
        audio, sr = sf.read(wav_path)
        if audio.ndim > 1:
            audio = audio.mean(axis=1)  # Convert to mono
        audio = audio.astype(np.float32)
        return audio, sr

    def run_inference(self, audio_features: np.ndarray, audio_length: np.ndarray) -> np.ndarray:
        """Run ONNX inference."""
        input_names = [inp.name for inp in self.session.get_inputs()]
        if len(input_names) != 2:
            raise ValueError(
                f"Expected 2 inputs, got {len(input_names)}: {input_names}")

        # Ensure input names match expected
        expected_input_name = "audio_signal"
        if input_names[0] != expected_input_name:
            print(
                f"Warning: First input name is {input_names[0]}, expected {expected_input_name}")

        inputs = {
            input_names[0]: audio_features,
            input_names[1]: audio_length
        }

        try:
            outputs = self.session.run(None, inputs)
            return outputs[0]  # Assuming logits are the first output
        except Exception as e:
            raise RuntimeError(f"ONNX inference failed: {str(e)}")

    def decode_output(self, logits: np.ndarray) -> str:
        """Decode CTC output using greedy decoding."""
        # Convert logits to numpy if needed
        if isinstance(logits, torch.Tensor):
            logits = logits.numpy()

        # Get predictions (greedy decoding)
        predictions = np.argmax(logits, axis=-1).squeeze(0)

        # CTC decoding: remove blanks and repeats
        decoded: list[int] = []
        previous = self.blank_id
        for prediction in predictions:
            if prediction != self.blank_id and prediction != previous:
                decoded.append(int(prediction))
            previous = int(prediction)

        try:
            text = ''.join(
                self.label_map.get(token_id, '<UNK>')
                for token_id in decoded
            )
            return text.replace('▁', ' ').strip()
        except Exception as e:
            raise RuntimeError(f"Decoding failed: {str(e)}")

    def transcribe_file(self, wav_path: str) -> str:
        """Transcribe a single WAV file."""
        if not os.path.exists(wav_path):
            raise FileNotFoundError(f"WAV file {wav_path} not found")

        # Preprocess audio and run transcription
        audio, sr = self.preprocess_audio_file(wav_path)
        return self.transcribe(audio, sr)

    def transcribe(self, audio: np.ndarray, sr: int = 16000) -> str:
        """Full transcription pipeline."""
        try:
            # 1. Preprocess audio
            audio_features, audio_length = self.calculate_audio_features(
                audio, sr)
            print(
                f"Audio features shape: {audio_features.shape}, Length: {audio_length}")

            # 2. Run inference
            logits = self.run_inference(audio_features, audio_length)

            print(f"Logits shape: {logits.shape}")

            # 3. Decode output
            text = self.decode_output(logits)

            return text
        except Exception as e:
            raise RuntimeError(f"Transcription failed: {str(e)}")
