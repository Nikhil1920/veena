"""Acquire a small IndicVoices pilot from AI4Bharat's public, annotated archives.

Stream archive prefixes rather than downloading whole multi-GB releases. Official
train recordings supply calibration; official valid recordings supply evaluation.
Speakers and recordings are disjoint across these local splits. Selection is a
bounded convenience sample, not a representative benchmark or final test set.

Audio is cut using human annotation timestamps. Reference cleanup follows the
publisher's TGZ/create_indicvoices.py.old; raw annotations are retained per row.
Production features are exported by browser-reference's Rust export_features.
No dataset gate is accepted and no account/contact information is transmitted.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import io
import json
from pathlib import Path
import re
import string
import subprocess
import tarfile
import time
import urllib.request

import numpy as np
import soundfile as sf

from evaluate_multilingual import load_features, load_manifest

ROOT = Path(__file__).resolve().parent
LANGUAGE_NAMES = {
    "hi": "Hindi", "bn": "Bengali", "mr": "Marathi", "te": "Telugu",
    "ta": "Tamil", "gu": "Gujarati", "ur": "Urdu", "kn": "Kannada",
    "or": "Odia", "ml": "Malayalam", "pa": "Punjabi", "as": "Assamese",
    "brx": "Bodo", "doi": "Dogri", "kok": "Konkani", "ks": "Kashmiri",
    "mai": "Maithili", "mni": "Manipuri", "ne": "Nepali", "sa": "Sanskrit",
    "sat": "Santali", "sd": "Sindhi",
}
SOURCE_CARD = "https://huggingface.co/datasets/ai4bharat/IndicVoices"
SOURCE_CODE = "https://github.com/AI4Bharat/IndicVoices/blob/d50726d3123bc5066994b94bf6c0cfbc5ab961d8/TGZ/create_indicvoices.py.old"
ARCHIVE_ROOT = "https://iv-release.objectstore.e2enetworks.net/dmu_release"
LICENSE = "https://creativecommons.org/licenses/by/4.0/"


def clean_reference(text: str) -> str:
    """Publisher's reference cleanup, with raw text retained for auditing."""
    text = re.sub(r"\[[^\]]*\]|\([^)]*\)|\[|\]", " ", text)
    substitutions = {
        "॥": " ", "۔": " ", "।": " ", "‘": "", "–": " ", "’": " ",
        "ʼ": "", "°": " ", "¬": " ", "ۭ": " ", "۪": " ", "‑": " ",
        "—": " ", "\u200b": "", "\u200c": "", "\u200d": "", "´": "", ",": "",
        "\u200e": "", "\u200f": "", "“": "", "”": "",
    } | {character: " " for character in set(string.punctuation) - {","}}
    text = text.translate(str.maketrans(substitutions))
    return " ".join(re.sub(r"[a-zA-Z]", " ", text).split())


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def recording_group(recording_id: str) -> str:
    """Keep the two channels/participants of one conversation in the same split."""
    return re.sub(r"_[01]$", "", recording_id)


def write_json(path: Path, data: object) -> None:
    temporary = path.with_suffix(path.suffix + ".partial")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def write_jsonl(path: Path, rows: list[dict]) -> None:
    temporary = path.with_suffix(path.suffix + ".partial")
    temporary.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    temporary.replace(path)


class CountedResponse:
    def __init__(self, response):
        self.response = response
        self.bytes_read = 0

    def read(self, count=-1):
        data = self.response.read(count)
        self.bytes_read += len(data)
        return data


def eligible_segments(metadata: dict, minimum_seconds: float, maximum_seconds: float) -> list[dict]:
    if not isinstance(metadata.get("speaker_id"), str) or not metadata["speaker_id"]:
        return []
    segments = []
    for index, (verbatim, normalized) in enumerate(zip(metadata.get("verbatim", []), metadata.get("normalized", []))):
        if verbatim.get("speaker_id") != 0 or normalized.get("speaker_id") != 0:
            continue
        start, end = verbatim.get("start"), verbatim.get("end")
        if not all(isinstance(value, (int, float)) and np.isfinite(value) for value in (start, end)):
            continue
        if start < 0 or not minimum_seconds <= end - start <= maximum_seconds:
            continue
        raw = normalized.get("text", "")
        if not isinstance(raw, str) or re.search(r"unintelligible|inaudible|unclear", raw, re.IGNORECASE):
            continue
        text = clean_reference(raw)
        if len(text.split()) < 3:
            continue
        # Do not silently pair annotation arrays with different boundaries.
        if abs(float(normalized.get("start", -1)) - start) > .001 or abs(float(normalized.get("end", -1)) - end) > .001:
            continue
        segments.append({"segment_index": index, "start_seconds": float(start), "end_seconds": float(end),
                         "text": text, "source_normalized_text": raw,
                         "source_verbatim_text": verbatim.get("text", "")})
    return segments


def collect_split(language: str, split: str, target_count: int, output: Path, exporter: Path,
                  forbidden_speakers: set[str], forbidden_recordings: set[str], *,
                  clips_per_speaker: int, maximum_records: int, maximum_download_bytes: int,
                  minimum_seconds: float, maximum_seconds: float) -> tuple[list[dict], dict]:
    source_split = "train" if split == "calibration" else "valid"
    url = f"{ARCHIVE_ROOT}/v1_{LANGUAGE_NAMES[language]}_{source_split}.tgz"
    rows: list[dict] = []
    speaker_counts: Counter = Counter()
    pending: dict[str, tuple[dict, list[dict], str]] = {}
    target_directory = output / language / split
    target_directory.mkdir(parents=True, exist_ok=True)
    audio_directory = target_directory / "audio"
    feature_directory = target_directory / "features"
    audio_directory.mkdir(exist_ok=True)
    feature_directory.mkdir(exist_ok=True)
    started = time.monotonic()
    recordings_seen = 0
    source_provenance: dict = {"archive_url": url, "official_split": source_split,
                               "license": "CC-BY-4.0", "license_url": LICENSE}
    request = urllib.request.Request(url, headers={"User-Agent": "Veena-ASR-Pilot/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        stream = CountedResponse(response)
        source_provenance["archive_etag"] = response.headers.get("ETag")
        source_provenance["archive_content_length"] = int(response.headers.get("Content-Length", 0))
        with tarfile.open(fileobj=stream, mode="r|gz") as archive:
            for member in archive:
                if stream.bytes_read > maximum_download_bytes or recordings_seen >= maximum_records:
                    break
                if not member.isfile():
                    continue
                stem = str(Path(member.name).with_suffix(""))
                if member.name.endswith(".json") and member.size < 2_000_000:
                    recordings_seen += 1
                    raw = archive.extractfile(member).read()
                    metadata = json.loads(raw)
                    if metadata.get("language") != LANGUAGE_NAMES[language]:
                        raise ValueError(f"archive label mismatch: {member.name}")
                    speaker_id = metadata.get("speaker_id")
                    recording_id = Path(stem).name
                    if speaker_id in forbidden_speakers or recording_group(recording_id) in forbidden_recordings:
                        continue
                    if speaker_counts[speaker_id] >= clips_per_speaker:
                        continue
                    # Prefer spontaneous, conversational speech for the reported use case.
                    scenario = str(metadata.get("scenario", "")).lower()
                    if "read" in scenario:
                        continue
                    segments = eligible_segments(metadata, minimum_seconds, maximum_seconds)
                    if segments:
                        pending[stem] = (metadata, segments, sha256_bytes(raw))
                elif member.name.endswith(".wav") and stem in pending:
                    metadata, segments, annotation_hash = pending.pop(stem)
                    if member.size > 100_000_000:
                        continue
                    raw_audio = archive.extractfile(member).read()
                    samples, sample_rate = sf.read(io.BytesIO(raw_audio), dtype="float32", always_2d=True)
                    if not np.isfinite(samples).all():
                        raise ValueError(f"nonfinite source audio: {member.name}")
                    samples = samples.mean(axis=1)
                    recording_id = Path(stem).name
                    source_audio_hash = sha256_bytes(raw_audio)
                    speaker_id = metadata["speaker_id"]
                    for segment in segments:
                        if len(rows) >= target_count or speaker_counts[speaker_id] >= clips_per_speaker:
                            break
                        start = int(segment["start_seconds"] * sample_rate)
                        end = int(segment["end_seconds"] * sample_rate)
                        if end > len(samples) or start >= end:
                            continue
                        clip = samples[start:end]
                        sample_id = f"iv-v1-{language}-{source_split}-{recording_id}-{segment['segment_index']:04d}"
                        audio_path = audio_directory / f"{sample_id}.wav"
                        feature_path = feature_directory / f"{sample_id}.npy"
                        sf.write(audio_path, clip, sample_rate, subtype="PCM_16")
                        if feature_path.exists():
                            feature_path.unlink()
                        result = subprocess.run([str(exporter), str(audio_path), str(feature_path)],
                                                capture_output=True, text=True)
                        if result.returncode:
                            raise RuntimeError(f"feature export failed: {result.stderr.strip()}")
                        load_features(feature_path)
                        rows.append({"id": sample_id, "language": language, "text": segment["text"],
                                     "features": str(feature_path.relative_to(target_directory)),
                                     "audio": str(audio_path.relative_to(target_directory)),
                                     "duration_seconds": len(clip) / sample_rate,
                                     "sample_rate": sample_rate, "speaker_id": speaker_id,
                                     "recording_id": recording_id, "recording_group_id": recording_group(recording_id), "source_dataset": "AI4Bharat/IndicVoices",
                                     "source_split": source_split, "source_archive_url": url,
                                     "source_member": member.name, "source_audio_sha256": source_audio_hash,
                                     "source_annotation_sha256": annotation_hash,
                                     "audio_sha256": sha256_bytes(audio_path.read_bytes()),
                                     "source_scenario": metadata.get("scenario"),
                                     "reference_provenance": "Publisher human annotation; publisher-style cleanup; raw references retained",
                                     "license": "CC-BY-4.0", **segment})
                        speaker_counts[speaker_id] += 1
                    print(json.dumps({"language": language, "split": split, "samples": len(rows),
                                      "target": target_count, "speakers": len(speaker_counts)}, sort_keys=True), flush=True)
                    if len(rows) >= target_count:
                        break
        source_provenance.update({"compressed_bytes_read": stream.bytes_read,
                                  "archive_download_complete": False,
                                  "source_records_inspected": recordings_seen,
                                  "elapsed_seconds": time.monotonic() - started,
                                  "samples": len(rows), "speakers": len(speaker_counts)})
    if len(rows) != target_count:
        raise ValueError(f"{language}/{split}: found {len(rows)}/{target_count} eligible clips within download budget")
    write_jsonl(target_directory / "manifest.jsonl", rows)
    write_json(target_directory / "source.json", source_provenance)
    load_manifest(target_directory / "manifest.jsonl", required_languages=[language])
    return rows, source_provenance


def acquire_language(language: str, args) -> dict:
    directory = args.output / language
    summary_path = directory / "summary.json"
    if summary_path.exists():
        summary = json.loads(summary_path.read_text())
        if summary.get("calibration_samples") == args.calibration_samples and summary.get("evaluation_samples") == args.evaluation_samples:
            expected_selection = {"clips_per_speaker": args.clips_per_speaker, "minimum_seconds": args.minimum_seconds, "maximum_seconds": args.maximum_seconds}
            if summary.get("selection") != expected_selection:
                raise ValueError("cached corpus uses different selection settings; use a new output directory")
            if summary.get("feature_exporter_sha256") != args.exporter_sha256:
                raise ValueError("cached features have different or unrecorded exporter version; use a new output directory")
            for split in ("calibration", "evaluation"):
                load_manifest(directory / split / "manifest.jsonl", required_languages=[language])
            return summary
    common = {"clips_per_speaker": args.clips_per_speaker, "maximum_records": args.maximum_records,
              "maximum_download_bytes": args.maximum_download_mb * 1024 * 1024,
              "minimum_seconds": args.minimum_seconds, "maximum_seconds": args.maximum_seconds}
    calibration, calibration_source = collect_split(language, "calibration", args.calibration_samples,
                                                     args.output, args.exporter, set(), set(), **common)
    calibration_speakers = {row["speaker_id"] for row in calibration}
    calibration_recordings = {recording_group(row["recording_id"]) for row in calibration}
    evaluation, evaluation_source = collect_split(language, "evaluation", args.evaluation_samples,
                                                 args.output, args.exporter, calibration_speakers,
                                                 calibration_recordings, **common)
    evaluation_speakers = {row["speaker_id"] for row in evaluation}
    evaluation_recordings = {recording_group(row["recording_id"]) for row in evaluation}
    if calibration_speakers & evaluation_speakers or calibration_recordings & evaluation_recordings:
        raise ValueError("calibration/evaluation overlap")
    if {row["audio_sha256"] for row in calibration} & {row["audio_sha256"] for row in evaluation}:
        raise ValueError("duplicate calibration/evaluation audio")
    summary = {"language": language, "name": LANGUAGE_NAMES[language], "status": "pilot_ready",
               "calibration_samples": len(calibration), "evaluation_samples": len(evaluation),
               "calibration_speakers": len(calibration_speakers), "evaluation_speakers": len(evaluation_speakers),
               "calibration_seconds": sum(row["duration_seconds"] for row in calibration),
               "evaluation_seconds": sum(row["duration_seconds"] for row in evaluation),
               "speaker_overlap": False, "recording_overlap": False, "recording_group_overlap": False, "audio_hash_overlap": False,
               "calibration_source": calibration_source, "evaluation_source": evaluation_source,
               "frontend": "rust_backend::preprocess::compute_features",
               "feature_exporter_sha256": args.exporter_sha256,
               "selection": {"clips_per_speaker": args.clips_per_speaker, "minimum_seconds": args.minimum_seconds, "maximum_seconds": args.maximum_seconds},
               "representative_benchmark": False, "release_ready": False,
               "limitations": ["Small archive-prefix convenience sample, not representative of accents, districts, microphones, distances, or speaking rates.",
                               "Official train/valid splits; pretrained-model training/selection overlap cannot be independently excluded.",
                               "Natural source recordings do not constitute a controlled off-mic/far-field test.",
                               "Only the target speaker's source diarization ID 0 is retained, following publisher preparation."]}
    write_json(summary_path, summary)
    return summary



def build_combined_manifests(output: Path) -> dict:
    """Combine completed language pilots and audit speaker/content separation."""
    available = sorted(path.parent.name for path in output.glob("*/summary.json")
                       if path.parent.name in LANGUAGE_NAMES)
    combined = {}
    for split in ("calibration", "evaluation"):
        rows = []
        for language in available:
            manifest = output / language / split / "manifest.jsonl"
            for line in manifest.read_text(encoding="utf-8").splitlines():
                row = json.loads(line)
                for field in ("features", "audio"):
                    row[field] = str((manifest.parent / row[field]).resolve().relative_to(output.resolve()))
                row["recording_group_id"] = recording_group(row["recording_id"])
                rows.append(row)
        combined[split] = rows
    for field in ("speaker_id", "recording_group_id", "audio_sha256"):
        left = {row[field] for row in combined["calibration"]}
        right = {row[field] for row in combined["evaluation"]}
        if left & right:
            raise ValueError(f"global calibration/evaluation overlap: {field}")
    for split, rows in combined.items():
        write_jsonl(output / f"{split}.jsonl", rows)
        if rows:
            load_manifest(output / f"{split}.jsonl", required_languages=available)
    summary = {
        "languages": available, "all_22_languages": len(available) == 22,
        "calibration_samples": len(combined["calibration"]),
        "evaluation_samples": len(combined["evaluation"]),
        "calibration_speakers": len({row["speaker_id"] for row in combined["calibration"]}),
        "evaluation_speakers": len({row["speaker_id"] for row in combined["evaluation"]}),
        "calibration_seconds": sum(row["duration_seconds"] for row in combined["calibration"]),
        "evaluation_seconds": sum(row["duration_seconds"] for row in combined["evaluation"]),
        "speaker_overlap": False, "recording_group_overlap": False, "audio_hash_overlap": False,
        "representative_benchmark": False, "release_ready": False,
    }
    write_json(output / "corpus-summary.json", summary)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--languages", nargs="+", choices=tuple(LANGUAGE_NAMES), default=list(LANGUAGE_NAMES))
    parser.add_argument("--output", type=Path, default=ROOT / "corpus" / "indicvoices-v1-pilot")
    parser.add_argument("--exporter", type=Path, default=ROOT.parents[1] / "target" / "release" / "export_features")
    parser.add_argument("--calibration-samples", type=int, default=10)
    parser.add_argument("--evaluation-samples", type=int, default=20)
    parser.add_argument("--clips-per-speaker", type=int, default=5)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--maximum-records", type=int, default=100)
    parser.add_argument("--maximum-download-mb", type=int, default=400)
    parser.add_argument("--minimum-seconds", type=float, default=2)
    parser.add_argument("--maximum-seconds", type=float, default=15)
    args = parser.parse_args()
    if len(set(args.languages)) != len(args.languages):
        parser.error("languages must not contain duplicate codes")
    if any(value <= 0 for value in (args.calibration_samples, args.evaluation_samples, args.clips_per_speaker,
                                    args.workers, args.maximum_records, args.maximum_download_mb, args.minimum_seconds)):
        parser.error("sample counts, limits, threads, and minimum duration must be positive")
    if args.maximum_seconds < args.minimum_seconds:
        parser.error("maximum duration must be at least the minimum duration")
    args.output = args.output.resolve()
    args.exporter = args.exporter.resolve()
    if not args.exporter.is_file():
        parser.error("build browser-reference's export_features binary first")
    args.output.mkdir(parents=True, exist_ok=True)
    exporter_hash = sha256_bytes(args.exporter.read_bytes())
    args.exporter_sha256 = exporter_hash
    write_json(args.output / "PROVENANCE.json", {
        "dataset": "IndicVoices", "publisher": "AI4Bharat", "source_card": SOURCE_CARD,
        "feature_exporter_path": str(args.exporter), "feature_exporter_sha256": exporter_hash,
        "source_preparation_code": SOURCE_CODE, "license": "CC-BY-4.0", "license_url": LICENSE,
        "attribution": "Tahir Javed et al., IndicVoices: Towards building an Inclusive Multilingual Speech Dataset for Indian Languages, Findings of ACL 2024, pp. 10740–10782.",
        "paper": "https://arxiv.org/abs/2403.01926",
        "changes": "Cropped human-annotated time spans, source-style reference cleanup, mono PCM16 WAV conversion, production frontend feature extraction, bounded local pilot selection.",
        "selection": "First eligible source recordings; at most configured clips per anonymous speaker; source train for calibration, source valid for evaluation; read scenarios excluded.",
        "review": "Not a representative final benchmark. No claim of all dialects, natural-speed distribution, or controlled off-mic coverage.",
    })
    completed = []
    failures = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        pending = {pool.submit(acquire_language, language, args): language for language in args.languages}
        for future in as_completed(pending):
            language = pending[future]
            try:
                result = future.result()
                completed.append(result)
                print(json.dumps({"language": language, "status": "pilot_ready", "directory": str(args.output / language)}), flush=True)
            except Exception as exc:
                error = {"language": language, "error": f"{type(exc).__name__}: {exc}"}
                failures.append(error)
                print(json.dumps(error), flush=True)
            write_json(args.output / "acquisition-summary.json", {"requested_languages": args.languages, "completed": completed, "failures": failures})
    build_combined_manifests(args.output)
    if sha256_bytes(args.exporter.read_bytes()) != exporter_hash:
        raise RuntimeError("feature exporter changed during acquisition; do not mix frontend versions")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
