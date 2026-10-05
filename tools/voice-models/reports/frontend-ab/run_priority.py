"""Quality-only matched-PCM frontend A/B for the first eight languages."""
from pathlib import Path
import json
import hashlib
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
import torch
from export_nemo_features import export_manifest
from evaluate_multilingual import evaluate_model

LANGUAGES = ('hi', 'bn', 'mr', 'te', 'ta', 'gu', 'ur', 'kn')
OUT = Path(__file__).resolve().parent
CORPUS = ROOT / 'corpus' / 'frontend-ab'
NOTE = 'Concurrent model build/evaluation activity; single pass with no warmup; recognition-quality A/B only. No latency or device-performance qualification.'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')


def summarize(language, rust_report, nemo_report, *, reused=False):
    left, right = read(rust_report), read(nemo_report)
    assert left['status'] == right['status'] == 'ok'
    assert left['model'] == right['model']
    assert left['aggregate']['sample_count'] == right['aggregate']['sample_count'] == 20
    a = [(row['id'], row['reference']) for row in left['samples']]
    b = [(row['id'], row['reference']) for row in right['samples']]
    assert a == b, 'A/B utterance IDs or references differ'
    manifests = []
    for report in (left, right):
        manifest = Path(report['manifest'])
        rows = [json.loads(line) for line in manifest.read_text().splitlines()]
        for row in rows:
            audio = (manifest.parent / row['audio']).resolve()
            assert hashlib.sha256(audio.read_bytes()).hexdigest() == row['audio_sha256'], 'Stored PCM hash differs from audio bytes'
        manifests.append([(row['id'], row['audio_sha256']) for row in rows])
    assert manifests[0] == manifests[1], 'A/B PCM differs'
    changed = sum(x['prediction'] != y['prediction'] for x, y in zip(left['samples'], right['samples']))
    return {'language': language, 'samples': 20, 'model': left['model'],
            'rust320_report': str(rust_report), 'nemo400_report': str(nemo_report),
            'rust320_wer': left['aggregate']['wer'], 'nemo400_wer': right['aggregate']['wer'],
            'wer_delta_percentage_points': 100 * (right['aggregate']['wer'] - left['aggregate']['wer']),
            'rust320_cer': left['aggregate']['cer'], 'nemo400_cer': right['aggregate']['cer'],
            'cer_delta_percentage_points': 100 * (right['aggregate']['cer'] - left['aggregate']['cer']),
            'reference_words': left['aggregate']['reference_words'],
            'reference_characters': left['aggregate']['reference_characters'],
            'rust320_word_errors': left['aggregate']['word_errors'],
            'nemo400_word_errors': right['aggregate']['word_errors'],
            'rust320_character_errors': left['aggregate']['character_errors'],
            'nemo400_character_errors': right['aggregate']['character_errors'],
            'changed_predictions': changed, 'same_pcm_verified': True,
            'existing_telugu_run_reused': reused, 'latency_qualified': False,
            'release_ready': False}


def save_summary(rows):
    words = sum(row['reference_words'] for row in rows)
    chars = sum(row['reference_characters'] for row in rows)
    summary = {'status': 'complete' if len(rows) == len(LANGUAGES) else 'in_progress',
               'languages': rows, 'required_languages': list(LANGUAGES),
               'sample_count': sum(row['samples'] for row in rows),
               'wer_improved_languages': [row['language'] for row in rows if row['wer_delta_percentage_points'] < -1e-10],
               'wer_worsened_languages': [row['language'] for row in rows if row['wer_delta_percentage_points'] > 1e-10],
               'wer_unchanged_languages': [row['language'] for row in rows if abs(row['wer_delta_percentage_points']) <= 1e-10],
               'pooled_rust320_wer': sum(row['rust320_word_errors'] for row in rows) / words if words else None,
               'pooled_nemo400_wer': sum(row['nemo400_word_errors'] for row in rows) / words if words else None,
               'pooled_rust320_cer': sum(row['rust320_character_errors'] for row in rows) / chars if chars else None,
               'pooled_nemo400_cer': sum(row['nemo400_character_errors'] for row in rows) / chars if chars else None,
               'recognition_quality_only': True, 'latency_qualified': False, 'release_ready': False,
               'runtime_defaults_changed': False,
               'settings': {'threads': 2, 'warmup': 0, 'repeats': 1, 'provider': 'CPUExecutionProvider'},
               'interpretation': 'Positive deltas mean the NeMo400 frontend worsened this sample. Multiple frontend details differ (window, guards, normalization); this is not a window-length-only ablation.',
               'limitations': ['20 convenience-sampled spontaneous clips per language; not an independently representative benchmark.',
                               'Identical resampled 16kHz PCM, references, model and decoding per comparison.',
                               'No controlled distant-microphone recordings or target-device speed conclusions.',
                               'Different feature tensors make normal quantization baseline corpus fingerprints intentionally unequal; no quantization release gate is claimed.']}
    write(OUT / 'priority-summary.json', summary)


def main():
    torch.set_num_threads(1)
    rows = []
    for language in LANGUAGES:
        if language == 'te':
            existing = ROOT / 'candidates' / 'frontend-ab-te'
            row = summarize(language, existing / 'evaluation-rust320-samepcm.json', existing / 'evaluation-nemo400.json', reused=True)
        else:
            source_manifest = ROOT / 'corpus' / 'indicvoices-v1-pilot' / language / 'evaluation' / 'manifest.jsonl'
            nemo_dir, rust_dir = CORPUS / language / 'nemo400', CORPUS / language / 'rust320-matched'
            if not (nemo_dir / 'manifest.jsonl').exists():
                export_manifest(source_manifest, nemo_dir, rust_comparison_dir=rust_dir,
                                rust_exporter=ROOT.parents[1] / 'target' / 'release' / 'export_features')
            directory = ROOT / 'candidates' / 'mobile-fp32' / language
            reports = []
            for name, manifest in [('rust320-matched', rust_dir / 'manifest.jsonl'), ('nemo400', nemo_dir / 'manifest.jsonl')]:
                report_path = OUT / f'{language}-{name}-fp32.json'
                if not report_path.exists():
                    report = evaluate_model(directory / 'model.onnx', directory, manifest,
                                            required_languages=[language], threads=2, warmup=0, repeats=1)
                    report['measurement']['latency_qualified'] = False
                    report['measurement']['performance_note'] = NOTE
                    write(report_path, report)
                reports.append(report_path)
            row = summarize(language, reports[0], reports[1])
        rows.append(row)
        save_summary(rows)
        print(json.dumps({key: row[key] for key in ('language', 'rust320_wer', 'nemo400_wer', 'wer_delta_percentage_points', 'cer_delta_percentage_points', 'changed_predictions')}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
