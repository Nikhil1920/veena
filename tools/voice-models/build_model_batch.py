"""Build and screen unpublished per-language mobile ASR candidates.

The first eight priority languages are the default. Builds are sequential and
resume only after checking source weights, recipe, decoder assets and output
bytes. Accuracy is reported separately against FP32 and the shipped model.
Passing a small screening corpus never promotes a candidate to a release.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import tempfile

import onnx
import onnxruntime as ort

from evaluate_multilingual import compare_reports, evaluate_model
from quantize_int4 import quantize_model, sha256
from quantize_language_model import artifact_record
from quantize_mobile_mixed import build_model

PRIORITY = ('hi', 'bn', 'mr', 'te', 'ta', 'gu', 'ur', 'kn', 'or', 'ml', 'pa',
            'as', 'mai', 'sat', 'ks', 'ne', 'sd', 'doi', 'kok', 'mni', 'brx', 'sa')
VARIANTS = {
    'compact-int4': {'bits': 4, 'block_size': 32, 'accuracy_level': 4,
                     'pointwise_conv': True, 'keep_ctc_head_float': False},
    'balanced-int8': {'bits': 8, 'per_channel': True, 'pointwise_conv': True,
                      'keep_ctc_head_float': True},
}
REQUIRED_DECODER = ('decoder_config.json', 'language_masks.json', 'vocab.json')
OPTIONAL_DECODER = ('vocab.txt', 'config.json', 'tokens.txt')


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.partial')
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + '\n')
    temporary.replace(path)


def verified_source(source_dir: Path) -> dict:
    provenance = json.loads((source_dir / 'source.json').read_text())
    recorded = provenance.get('source', provenance)
    actual = artifact_record(source_dir / 'model.onnx')
    expected = {item['path']: item for item in recorded['files']}
    if len(expected) != len(actual['files']):
        raise ValueError('Source manifest does not enumerate every graph/weight asset')
    for asset in actual['files']:
        path = (source_dir / asset['path']).resolve()
        if not path.is_relative_to(source_dir.resolve()):
            raise ValueError('External weights must stay within the source directory')
        previous = expected.get(asset['path'])
        if previous is None or any(previous[key] != asset[key] for key in ('size_bytes', 'sha256')):
            raise ValueError(f"Source SHA256/size mismatch: {asset['path']}")
    for name in REQUIRED_DECODER:
        if not (source_dir / name).is_file():
            raise ValueError(f'Missing required decoder asset: {name}')
    names = ['source.json', *REQUIRED_DECODER]
    names.extend(name for name in OPTIONAL_DECODER if (source_dir / name).is_file())
    actual['decoder_assets'] = {
        name: {'size_bytes': (source_dir / name).stat().st_size,
               'sha256': sha256(source_dir / name)} for name in names
    }
    return actual


def recipe_settings(variant: str) -> dict:
    return {'variant': variant, **VARIANTS[variant], 'onnx_version': onnx.__version__,
            'onnxruntime_version': ort.__version__,
            'builder_sha256': {name: sha256(Path(__file__).with_name(name))
                               for name in ('quantize_int4.py', 'quantize_mobile_mixed.py')}}


def verify_resume(output_dir: Path, source: dict, settings: dict) -> dict:
    try:
        record = json.loads((output_dir / 'build.json').read_text())
    except (OSError, ValueError) as error:
        raise ValueError('Existing output has no valid build record; refusing resume') from error
    if record.get('settings') != settings:
        raise ValueError('Existing build settings changed; refusing resume')
    if (record.get('source_artifact_sha256') != source['artifact_sha256']
            or record.get('source_sha256') != source['sha256']
            or record.get('decoder_assets') != source['decoder_assets']):
        raise ValueError('Existing build source graph/weights/decoder changed; refusing resume')
    model = output_dir / 'model.onnx'
    if (not model.is_file() or model.stat().st_size != record.get('output_bytes')
            or sha256(model) != record.get('output_sha256')):
        raise ValueError('Existing output SHA256/size mismatch; refusing resume')
    for name, expected in source['decoder_assets'].items():
        path = output_dir / name
        if (not path.is_file() or path.stat().st_size != expected['size_bytes']
                or sha256(path) != expected['sha256']):
            raise ValueError(f'Existing decoder/provenance asset changed: {name}')
    if record.get('release_ready') is not False or record.get('status') != 'candidate_unvalidated':
        raise ValueError('Unexpected publication state in build record')
    return record


def build_candidate(source_dir: Path, output_dir: Path, language: str, variant: str,
                    *, resume: bool = False, source: dict | None = None) -> dict:
    source_dir, output_dir = source_dir.resolve(), output_dir.resolve()
    source = source if source is not None else verified_source(source_dir)
    settings = recipe_settings(variant)
    if output_dir.exists():
        if not resume:
            raise ValueError(f'Output exists; use --resume for strict verification: {output_dir}')
        return verify_resume(output_dir, source, settings)
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f'.{variant}-', dir=output_dir.parent) as temporary:
        staging = Path(temporary) / 'candidate'
        staging.mkdir()
        model = staging / 'model.onnx'
        if variant == 'compact-int4':
            quantization = quantize_model(source_dir / 'model.onnx', model, block_size=32,
                                         accuracy_level=4, quantize_pointwise_conv=True,
                                         expected_sha256=source['sha256'],
                                         expected_artifact_sha256=source['artifact_sha256'])
        elif variant == 'balanced-int8':
            verification = Path(temporary) / 'source-verification.json'
            write_json(verification, {'files': source['files']})
            quantization = build_model(source_dir / 'model.onnx', model,
                                       verification=verification, keep_ctc_head_float=True)
        else:
            raise ValueError(f'Unknown variant: {variant}')
        for name in source['decoder_assets']:
            shutil.copyfile(source_dir / name, staging / name)
        quantization['output'] = str(output_dir / 'model.onnx')
        record = {'status': 'candidate_unvalidated', 'release_ready': False,
                  'language': language, 'variant': variant, 'settings': settings,
                  'source_sha256': source['sha256'], 'source_artifact_sha256': source['artifact_sha256'],
                  'source_files': source['files'], 'source_bytes': source['size_bytes'],
                  'decoder_assets': source['decoder_assets'],
                  'output': str(output_dir / 'model.onnx'), 'output_bytes': model.stat().st_size,
                  'output_sha256': sha256(model), 'quantization': quantization,
                  'latency_qualified': False, 'fresh_confirmation_corpus_required': True}
        (staging / 'model.build.json').unlink(missing_ok=True)
        write_json(staging / 'build.json', record)
        verify_resume(staging, source, settings)
        staging.rename(output_dir)
    return record


def screen_candidate(output_dir: Path, language: str, variant: str, reports_root: Path) -> dict:
    baseline_path = reports_root / 'priority' / f'{language}-fp32.json'
    baseline = json.loads(baseline_path.read_text())
    shipped_path = (reports_root / 'te' / 'current-te.json' if language == 'te'
                    else reports_root / 'priority' / f'{language}-current-shared.json')
    shipped = json.loads(shipped_path.read_text())
    report = evaluate_model(output_dir / 'model.onnx', output_dir, baseline['manifest'],
                            threads=2, warmup=0, repeats=1, required_languages=[language],
                            baseline_report=baseline, max_wer_regression=.01,
                            max_cer_regression=.01, min_samples_per_language=20)
    report['comparison_against_fp32'] = report['comparison']
    report['comparison_against_shipped'] = compare_reports(
        shipped, report, max_wer_regression=.01, max_cer_regression=.01, min_samples_per_language=20)
    report.update({'candidate_variant': variant, 'status_classification': 'candidate_unvalidated',
                   'release_ready': False, 'latency_qualified': False,
                   'timing_note': 'Concurrent preparation and single-pass CPU screening; target-phone speed is unqualified.',
                   'selection_note': 'Pilot used for candidate screening. Fresh speaker-disjoint confirmation data and mobile measurements are required. No automatic selection.'})
    destination = reports_root / 'batch' / f'{language}-{variant}.json'
    write_json(destination, report)
    return {'report': str(destination.resolve()), 'aggregate': report['aggregate'],
            'status': report['status'],
            'fp32_quality_gate_passed': report['comparison_against_fp32']['quality_gate_passed'],
            'shipped_quality_gate_passed': report['comparison_against_shipped']['quality_gate_passed']}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--languages', nargs='+', choices=PRIORITY, default=list(PRIORITY[:8]))
    parser.add_argument('--variants', nargs='+', choices=VARIANTS, default=list(VARIANTS))
    parser.add_argument('--source-root', type=Path, default=Path('candidates/mobile-fp32'))
    parser.add_argument('--output-root', type=Path, default=Path('candidates/mobile-builds'))
    parser.add_argument('--reports-root', type=Path, default=Path('reports/mobile-quantization'))
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--skip-evaluation', action='store_true', help='Build only when held-out corpus/baseline is unavailable')
    args = parser.parse_args()
    languages = [language for language in PRIORITY if language in args.languages]
    variants = list(dict.fromkeys(args.variants))
    rows = []
    for language in languages:
        source_dir = args.source_root / language
        source = verified_source(source_dir)
        for variant in variants:
            output_dir = args.output_root / language / variant
            print(f'Building {language} {variant}', flush=True)
            record = build_candidate(source_dir, output_dir, language, variant,
                                     resume=args.resume, source=source)
            row = {'language': language, 'variant': variant, 'output_bytes': record['output_bytes'],
                   'output_sha256': record['output_sha256'], 'model': record['output']}
            if not args.skip_evaluation:
                row['evaluation'] = screen_candidate(output_dir, language, variant, args.reports_root)
            rows.append(row)
            write_json(args.output_root / 'batch-summary.json', {
                'status': 'candidate_unvalidated', 'release_ready': False,
                'languages_requested': languages, 'variants': variants, 'latency_qualified': False,
                'fresh_confirmation_corpus_required': True, 'results': rows})
            print(json.dumps(row, ensure_ascii=False), flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
