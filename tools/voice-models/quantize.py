from __future__ import annotations

import argparse
import logging
import os
import shutil
import tempfile
import warnings
from dataclasses import dataclass
from pathlib import Path

from onnxruntime.quantization import (
    CalibrationDataReader,
    QuantFormat,
    QuantType,
    quantize_dynamic,
    quantize_static,
)

from onnx_transcribe import StandaloneASRCTC


MODEL_NAME = "indicconformer_stt_te_ctc.onnx"
DEFAULT_OUTPUT_NAME = "indicconformer_stt_te_ctc_quantized.onnx"
DEFAULT_SAMPLE_AUDIO = "telugu_test_audio.wav"
DECODER_SUPPORT_FILES = (
    "decoder_config.json", "tokenizer_te.model", "tokenizer_te.vocab", "vocab.txt")


class AudioCalibrationDataReader(CalibrationDataReader):
    def __init__(self, calibration_data, input_names):
        self.calibration_data = calibration_data
        self.input_names = input_names
        self.index = 0

    def get_next(self):
        if self.index >= len(self.calibration_data):
            return None
        audio_features, audio_length = self.calibration_data[self.index]
        self.index += 1
        return {
            self.input_names[0]: audio_features,
            self.input_names[1]: audio_length,
        }


@dataclass(frozen=True)
class CandidateResult:
    label: str
    path: Path
    size_bytes: int
    transcript: str | None
    exact_match: bool
    error: str | None = None


def _project_root() -> Path:
    return Path(__file__).resolve().parent


def _models_dir() -> Path:
    return _project_root() / "models"


def _copy_decoder_support_files(target_dir: Path) -> None:
    source_dir = _models_dir()
    for file_name in DECODER_SUPPORT_FILES:
        source_path = source_dir / file_name
        if source_path.exists():
            shutil.copy2(source_path, target_dir / file_name)


def _reference_transcript(sample_audio: Path) -> tuple[str, StandaloneASRCTC]:
    reference = StandaloneASRCTC(model_dir=str(
        _models_dir()), model_name=MODEL_NAME)
    transcript = reference.transcribe_file(str(sample_audio))
    return transcript, reference


def _candidate_transcript(model_dir: Path, model_name: str, sample_audio: Path) -> str:
    candidate = StandaloneASRCTC(
        model_dir=str(model_dir), model_name=model_name)
    return candidate.transcribe_file(str(sample_audio))


def _quantize_dynamic(model_input: Path, model_output: Path, *, op_types_to_quantize=None, per_channel=False):
    kwargs = {
        "weight_type": QuantType.QInt8,
        "per_channel": per_channel,
    }
    if op_types_to_quantize is not None:
        kwargs["op_types_to_quantize"] = op_types_to_quantize
    quantize_dynamic(str(model_input), str(model_output), **kwargs)


def _quantize_static(model_input: Path, model_output: Path, reference_asr: StandaloneASRCTC, sample_audio: Path):
    audio, sr = reference_asr.preprocess_audio_file(str(sample_audio))
    features, lengths = reference_asr.calculate_audio_features(audio, sr)
    input_names = [inp.name for inp in reference_asr.session.get_inputs()]
    calibration_reader = AudioCalibrationDataReader(
        [(features, lengths)], input_names)
    quantize_static(
        model_input=str(model_input),
        model_output=str(model_output),
        calibration_data_reader=calibration_reader,
        quant_format=QuantFormat.QDQ,
        activation_type=QuantType.QUInt8,
        weight_type=QuantType.QInt8,
        per_channel=True,
    )


def _evaluate_candidate(label: str, build_fn, reference_text: str, sample_audio: Path) -> CandidateResult:
    candidate_fd, candidate_name = tempfile.mkstemp(
        prefix=f"onnx-quantize-{label}-", suffix=".onnx")
    os.close(candidate_fd)
    candidate_path = Path(candidate_name)
    try:
        build_fn(candidate_path)
        with tempfile.TemporaryDirectory(prefix="onnx-quantize-eval-") as eval_dir_name:
            eval_dir = Path(eval_dir_name)
            _copy_decoder_support_files(eval_dir)
            shutil.copy2(candidate_path, eval_dir / candidate_path.name)
            transcript = _candidate_transcript(
                eval_dir, candidate_path.name, sample_audio)
        size_bytes = candidate_path.stat().st_size
        return CandidateResult(
            label=label,
            path=candidate_path,
            size_bytes=size_bytes,
            transcript=transcript,
            exact_match=transcript == reference_text,
        )
    except Exception as exc:
        return CandidateResult(
            label=label,
            path=candidate_path,
            size_bytes=0,
            transcript=None,
            exact_match=False,
            error=str(exc),
        )


def _format_mb(size_bytes: int) -> str:
    return f"{size_bytes / 1024 ** 2:.2f} MB"


def run_quantization(sample_audio: Path, output_model: Path, require_exact: bool = False) -> int:
    warnings.filterwarnings(
        "ignore", message="Please consider to run pre-processing before quantization.*")
    warnings.filterwarnings(
        "ignore", message="Inference failed or unsupported type to quantize.*")
    logging.getLogger().setLevel(logging.ERROR)

    reference_text, reference_asr = _reference_transcript(sample_audio)
    original_path = _models_dir() / MODEL_NAME
    original_size = original_path.stat().st_size

    candidates = [
        ("dynamic_int8", lambda path: _quantize_dynamic(
            original_path, path, per_channel=False)),
        ("matmul_int8", lambda path: _quantize_dynamic(original_path,
         path, op_types_to_quantize=["MatMul"], per_channel=False)),
        ("static_qdq_int8", lambda path: _quantize_static(
            original_path, path, reference_asr, sample_audio)),
    ]

    results: list[CandidateResult] = []
    for label, builder in candidates:
        results.append(_evaluate_candidate(
            label, builder, reference_text, sample_audio))

    successful = [result for result in results if result.error is None]
    exact_matches = [result for result in successful if result.exact_match]

    if exact_matches:
        selected = min(exact_matches, key=lambda result: result.size_bytes)
        selection_note = "exact transcript match"
    elif successful:
        selected = min(successful, key=lambda result: result.size_bytes)
        selection_note = "no candidate matched exactly; selected smallest available model"
    else:
        for result in results:
            print(f"{result.label}: ERROR {result.error}")
        return 1

    if require_exact and not selected.exact_match:
        print("No quantized candidate matched the reference transcript exactly.")
        for result in results:
            status = "OK" if result.error is None else f"ERROR {result.error}"
            print(f"{result.label}: {status}")
        return 2

    shutil.copy2(selected.path, output_model)

    print(f"Reference transcript: {reference_text}")
    print(f"Original model: {original_path} ({_format_mb(original_size)})")
    for result in results:
        if result.error is not None:
            print(f"{result.label}: ERROR {result.error}")
            continue
        match_flag = "match" if result.exact_match else "diff"
        print(
            f"{result.label}: {_format_mb(result.size_bytes)} [{match_flag}]")
    print(f"Selected: {selected.label} ({selection_note}) -> {output_model}")
    print(f"Selected size: {_format_mb(output_model.stat().st_size)}")
    if not selected.exact_match:
        print("Warning: the selected quantized model does not reproduce the reference transcript exactly.")

    for result in results:
        try:
            result.path.unlink(missing_ok=True)
        except OSError:
            pass

    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Quantize the Telugu ONNX model and validate transcript fidelity.")
    parser.add_argument("--sample-audio", default=DEFAULT_SAMPLE_AUDIO,
                        help="WAV file used to verify transcript fidelity.")
    parser.add_argument("--output-model", default=str(_models_dir() /
                        DEFAULT_OUTPUT_NAME), help="Path to write the selected quantized model.")
    parser.add_argument("--require-exact", action="store_true",
                        help="Fail if no quantized candidate matches the reference transcript exactly.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    sample_audio = Path(args.sample_audio)
    output_model = Path(args.output_model)
    if not sample_audio.exists():
        raise FileNotFoundError(f"Sample audio not found: {sample_audio}")
    output_model.parent.mkdir(parents=True, exist_ok=True)
    return run_quantization(sample_audio=sample_audio, output_model=output_model, require_exact=args.require_exact)


if __name__ == "__main__":
    raise SystemExit(main())
