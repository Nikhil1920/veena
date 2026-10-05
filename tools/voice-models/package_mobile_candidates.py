"""Create deterministic, unpublished mobile candidate bundles and a review manifest.

The existing Android and iOS engines detect language_masks.json and require the
historical filename indicconformer_multi_ctc_int8.onnx for that decoder route.
Both INT4 and INT8 packages therefore use that filename, with the actual recipe
recorded explicitly. No application catalog or published URL is changed.

From voice-models, after bound evaluation reports are available:
  .venv/bin/python package_mobile_candidates.py --all-languages \\
      --require-bound-reports --output-root candidates/mobile-packages-bound \\
      --builder-source-root reports/mobile-quantization/build-source

Use --resume only when every input/report is unchanged. Updated evaluation
snapshots require a fresh output directory; existing archives are never replaced.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import stat
import tarfile
import tempfile

from build_model_batch import (OPTIONAL_DECODER, PRIORITY, REQUIRED_DECODER,
                               VARIANTS, recipe_settings, verified_source,
                               verify_resume, write_json)
from quantize_int4 import sha256
from quantize_language_model import artifact_record

APP_MODEL_FILENAME = 'indicconformer_multi_ctc_int8.onnx'
ASSET_NAMES = {'source.json', *REQUIRED_DECODER, *OPTIONAL_DECODER}
PACKAGE_VERSION = 1
BUILDER_SOURCE_ROOT = Path(__file__).resolve().parent


def json_bytes(value: dict) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True,
                       allow_nan=False) + '\n').encode()


def regular_file(path: Path) -> Path:
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError(f'Only regular files may be packaged: {path}')
    return path


def file_record(value: Path | bytes) -> dict:
    if isinstance(value, bytes):
        return {'size_bytes': len(value), 'sha256': hashlib.sha256(value).hexdigest()}
    regular_file(value)
    return {'size_bytes': value.stat().st_size, 'sha256': sha256(value)}


def builder_inputs(variant: str, build_path: Path,
                   snapshot_root: Path | None) -> tuple[dict, dict, dict[str, bytes]]:
    """Override only source-code identity; numerical settings/runtime stay current."""
    settings = recipe_settings(variant)
    directory = (snapshot_root if snapshot_root is not None else BUILDER_SOURCE_ROOT).resolve()
    names = sorted(settings['builder_sha256'])
    content = {name: regular_file(directory / name).read_bytes() for name in names}
    records = {name: file_record(raw) for name, raw in content.items()}
    digests = {name: record['sha256'] for name, record in records.items()}
    settings['builder_sha256'] = digests
    files = {f'build-source-{name}': raw for name, raw in content.items()}
    provenance = {'origin': 'archived_snapshot' if snapshot_root is not None else 'current_source',
                  'source_root': str(directory),
                  'files': {name: {'archive_file': f'build-source-{name}', **record}
                            for name, record in records.items()}}
    if snapshot_root is not None:
        raw_manifest = regular_file(directory / 'manifest.json').read_bytes()
        manifest = json.loads(raw_manifest)
        if (manifest.get('schema_version') != 1 or manifest.get('files') != records
                or manifest.get('builder_sha256') != digests):
            raise ValueError('Archived builder snapshot manifest does not match its code bytes')
        build_digest = sha256(regular_file(build_path))
        if not any(item.get('sha256') == build_digest for item in manifest.get('verified_build_records', [])):
            raise ValueError('Build record is not verified by the archived builder snapshot manifest')
        files['build-source-manifest.json'] = raw_manifest
        provenance['snapshot_manifest'] = {'archive_file': 'build-source-manifest.json', **file_record(raw_manifest)}
        provenance['verified_build_record_sha256'] = build_digest
    return settings, provenance, files


def validate_local_decoder(directory: Path, language: str) -> None:
    config = json.loads((directory / 'decoder_config.json').read_text())
    masks = json.loads((directory / 'language_masks.json').read_text())
    vocab = json.loads((directory / 'vocab.json').read_text())
    if config.get('num_classes') != 257 or config.get('blank_idx') != 256:
        raise ValueError('Mobile candidates require a local 257-class decoder with blank 256')
    if masks != {language: list(range(257))}:
        raise ValueError('Candidate language mask must contain exactly its own 257 local indices')
    if (set(vocab) != {language} or len(vocab[language]) != 257
            or not all(isinstance(token, str) for token in vocab[language])):
        raise ValueError('Candidate vocabulary must contain exactly its own 257 tokens')


def attribution(provenance: dict) -> dict:
    source = provenance.get('source', provenance)
    if not source.get('repository') and 'upstream_source' in provenance:
        return attribution(provenance['upstream_source'])
    repository, revision, license_name = (source.get(key) for key in ('repository', 'revision', 'license'))
    if not all(isinstance(value, str) and value for value in (repository, revision, license_name)):
        raise ValueError('Pinned source repository, revision and license attribution are required')
    base = f'https://huggingface.co/{repository}'
    return {'original_model_publisher': 'AI4Bharat',
            'original_model_project_url': 'https://github.com/AI4Bharat/IndicConformer',
            'export_repository': repository, 'export_revision': revision,
            'export_source_url': f'{base}/tree/{revision}',
            'license_declared_by_export': license_name,
            'license_metadata_url': f'{base}/blob/{revision}/README.md',
            'license_terms_url': {'mit': 'https://opensource.org/license/mit',
                                  'cc-by-4.0': 'https://creativecommons.org/licenses/by/4.0/'}.get(license_name.lower()),
            'modifications': 'Quantized from verified FP32 weights; pointwise convolution rewrite and complete recipe recorded in build.json. Any upstream head pruning is recorded in source.json.'}


def report_binding(score: dict, model_artifact: dict, decoder_assets: dict) -> dict:
    recorded_model, recorded_decoder = score.get('model_artifact'), score.get('decoder_assets')
    if recorded_model is None and recorded_decoder is None:
        return {'status': 'unbound_historical_report',
                'reason': 'This report predates model and decoder digests. Historical gate outcomes are retained but no bound gate result is asserted.'}
    if not isinstance(recorded_model, dict) or not isinstance(recorded_decoder, dict):
        raise ValueError('Quality report has incomplete artifact/decoder binding')
    binding = score.get('artifact_binding', {})
    if (binding.get('status') != 'verified' or binding.get('method') != 'sha256'
            or binding.get('checked_before_inference') is not True
            or binding.get('checked_after_inference') is not True):
        raise ValueError('Quality report lacks verified pre/post inference artifact binding')
    required_model = ('sha256', 'artifact_sha256', 'size_bytes', 'files')
    if any(recorded_model.get(key) != model_artifact[key] for key in required_model):
        raise ValueError('Quality report model artifact digest does not match this candidate')
    expected_decoder = {name: decoder_assets[name] for name in REQUIRED_DECODER}
    if recorded_decoder != expected_decoder:
        raise ValueError('Quality report decoder digests do not match this candidate')
    return {'status': 'bound', 'model_artifact_sha256': model_artifact['artifact_sha256'],
            'decoder_assets': expected_decoder}


def quality_snapshot(reports_root: Path, directory: Path, language: str,
                     variant: str, build: dict, model_artifact: dict) -> tuple[dict, dict[str, bytes]]:
    score_path = reports_root / 'batch' / f'{language}-{variant}.json'
    quality = {'screening_status': 'not_evaluated', 'fp32_quality_gate_passed': None,
               'shipped_quality_gate_passed': None, 'score_report': None,
               'native_smoke_report': None, 'native_compatibility': 'not_run'}
    snapshots = {}
    if score_path.exists():
        raw = regular_file(score_path).read_bytes()
        score = json.loads(raw)
        if (Path(score['model']).resolve() != (directory / 'model.onnx').resolve()
                or score['model_size_bytes'] != build['output_bytes']
                or score.get('required_languages') != [language]
                or score.get('candidate_variant') != variant):
            raise ValueError(f'Quality report does not describe this candidate: {score_path}')
        fp32 = score.get('comparison_against_fp32', {}).get('quality_gate_passed')
        shipped = score.get('comparison_against_shipped', {}).get('quality_gate_passed')
        if score.get('status') != 'ok':
            status = 'failed_evaluation'
        elif fp32 is False and shipped is False:
            status = 'failed_fp32_and_shipped_gates'
        elif fp32 is False:
            status = 'failed_fp32_gate'
        elif shipped is False:
            status = 'failed_shipped_gate'
        elif fp32 is True and shipped is True:
            status = 'passed_pilot_gates'
        else:
            status = 'incomplete_quality_gates'
        binding = report_binding(score, model_artifact, build['decoder_assets'])
        bound = binding['status'] == 'bound'
        quality.update({'screening_status': status if bound else 'unbound_historical_report',
                        'reported_screening_status': status,
                        'reported_fp32_quality_gate_passed': fp32,
                        'reported_shipped_quality_gate_passed': shipped,
                        'reported_gate_failures': [name for name, result in [('fp32', fp32), ('shipped', shipped)] if result is False],
                        'fp32_quality_gate_passed': fp32 if bound else None,
                        'shipped_quality_gate_passed': shipped if bound else None,
                        'score_report': {'path': str(score_path.resolve()),
                                         'archive_file': 'quality-screening.json',
                                         **file_record(raw)},
                        'aggregate': {key: score.get('aggregate', {}).get(key) for key in
                                      ('sample_count', 'error_count', 'wer', 'cer',
                                       'word_errors', 'reference_words',
                                       'character_errors', 'reference_characters')},
                        'score_binding': binding})
        snapshots['quality-screening.json'] = raw
    native_path = reports_root / 'native-smoke' / f'{language}-{variant}.json'
    if native_path.exists():
        raw = regular_file(native_path).read_bytes()
        native = json.loads(raw)
        if (native.get('model_sha256') != build['output_sha256']
                or native.get('decoder_asset_sha256') != {name: build['decoder_assets'][name]['sha256'] for name in REQUIRED_DECODER}
                or native.get('language') != language or native.get('variant') != variant):
            raise ValueError(f'Native smoke report does not describe this candidate: {native_path}')
        quality['native_compatibility'] = native['status']
        quality['native_smoke_report'] = {'path': str(native_path.resolve()),
                                         'archive_file': 'native-smoke.json', **file_record(raw)}
        snapshots['native-smoke.json'] = raw
    return quality, snapshots


def write_archive(path: Path, package_id: str, files: dict[str, Path | bytes]) -> None:
    """USTAR avoids PAX metadata; fixed headers and gzip mtime make bytes repeatable."""
    with path.open('xb') as stream:
        with gzip.GzipFile(filename='', mode='wb', fileobj=stream, compresslevel=6, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w', format=tarfile.USTAR_FORMAT) as archive:
                root = tarfile.TarInfo(package_id + '/')
                root.type, root.mode, root.mtime = tarfile.DIRTYPE, 0o755, 0
                archive.addfile(root)
                for name, value in sorted(files.items()):
                    if Path(name).name != name or name in ('', '.', '..'):
                        raise ValueError('Archive assets must be plain filenames')
                    metadata = file_record(value)
                    entry = tarfile.TarInfo(f'{package_id}/{name}')
                    entry.size, entry.mode, entry.mtime = metadata['size_bytes'], 0o644, 0
                    if isinstance(value, bytes):
                        archive.addfile(entry, io.BytesIO(value))
                    else:
                        with regular_file(value).open('rb') as source:
                            archive.addfile(entry, source)


def verify_archive(path: Path, package_id: str, expected: dict[str, dict]) -> None:
    """Verify every uncompressed byte and the exact one-directory app layout."""
    seen = set()
    with tarfile.open(path, 'r:gz') as archive:
        members = archive.getmembers()
        if len(members) != len(expected) + 1:
            raise ValueError('Archive has unexpected or missing entries')
        for member in members:
            if member.name == package_id and member.isdir():
                continue
            prefix = package_id + '/'
            if not member.isfile() or not member.name.startswith(prefix):
                raise ValueError('Archive must contain one root directory and regular files')
            name = member.name[len(prefix):]
            if name not in expected or name in seen:
                raise ValueError('Archive has duplicate, nested, or unexpected files')
            seen.add(name)
            source = archive.extractfile(member)
            if source is None:
                raise ValueError('Cannot read archive entry')
            with source:
                digest = hashlib.file_digest(source, 'sha256').hexdigest()
            if {'size_bytes': member.size, 'sha256': digest} != expected[name]:
                raise ValueError(f'Archive content checksum mismatch: {name}')
        if seen != set(expected):
            raise ValueError('Archive omits required files')


def package_candidate(directory: Path, source_dir: Path, output_root: Path,
                      reports_root: Path, language: str, variant: str,
                      *, resume: bool = False, source: dict | None = None,
                      require_bound_reports: bool = False,
                      builder_source_root: Path | None = None) -> dict:
    if language not in PRIORITY or variant not in VARIANTS:
        raise ValueError('Unknown language or quantization variant')
    directory, source_dir = directory.resolve(), source_dir.resolve()
    source = source if source is not None else verified_source(source_dir)
    if set(source['decoder_assets']) - ASSET_NAMES:
        raise ValueError('Unexpected decoder/provenance filename')
    # The existing build verifier checks source graph/shards, recipe, model and all assets.
    settings, builder_provenance, builder_files = builder_inputs(variant, directory / 'build.json', builder_source_root)
    build = verify_resume(directory, source, settings)
    if build.get('language') != language or build.get('variant') != variant:
        raise ValueError('Build language/variant does not match requested package')
    validate_local_decoder(directory, language)
    model = regular_file(directory / 'model.onnx')
    model_artifact = artifact_record(model)
    if len(model_artifact['files']) != 1:
        raise ValueError('Candidate ONNX must be self-contained before mobile packaging')
    package_id = f'indicconformer-{language}-{variant}-candidate-v{PACKAGE_VERSION}'
    quality, snapshots = quality_snapshot(reports_root, directory, language, variant, build, model_artifact)
    if require_bound_reports and quality.get('score_binding', {}).get('status') != 'bound':
        raise ValueError('A bound quality report is required before packaging this candidate')
    provenance = json.loads((directory / 'source.json').read_text())
    upstream = provenance.get('source', provenance)
    if not upstream.get('license'):
        raise ValueError('Source license metadata is required')
    files: dict[str, Path | bytes] = {APP_MODEL_FILENAME: model, 'build.json': directory / 'build.json'}
    files.update({name: directory / name for name in source['decoder_assets']})
    files.update(snapshots)
    files.update(builder_files)
    candidate = {
        'package_version': PACKAGE_VERSION, 'id': package_id,
        'language': language, 'variant': variant,
        'status': 'candidate_unvalidated', 'release_ready': False,
        'published': False, 'selected': False,
        'model': {'archive_file': APP_MODEL_FILENAME, 'original_file': 'model.onnx',
                  'size_bytes': build['output_bytes'], 'sha256': build['output_sha256']},
        'decoder': {'route': 'Transcriber::new_multilingual', 'multilingual': True,
                    'supported_languages': [language], 'num_classes': 257, 'blank_idx': 256,
                    'filename_note': 'The legacy int8 filename is required by the existing Android/iOS loaders; variant describes the actual quantization.'},
        'source_artifact_sha256': build['source_artifact_sha256'],
        'source_license': upstream['license'],
        'attribution': attribution(provenance),
        'provenance_file': 'source.json', 'build_record_file': 'build.json',
        'builder_sources': builder_provenance,
        'quality': quality,
        'latency_qualified': False,
        'timing_note': 'Host screening is not physical-phone performance acceptance. No automatic candidate selection.',
        'fresh_confirmation_corpus_required': True,
    }
    files['candidate.json'] = json_bytes(candidate)
    expected = {name: file_record(value) for name, value in sorted(files.items())}
    specification = hashlib.sha256(json_bytes({'id': package_id, 'files': expected})).hexdigest()
    output_root.mkdir(parents=True, exist_ok=True)
    destination = output_root / f'{package_id}.tar.gz'
    sidecar = output_root / f'{package_id}.json'
    if destination.exists() or sidecar.exists():
        if not resume:
            raise ValueError('Package output exists; use --resume for strict verification')
        previous = json.loads(regular_file(sidecar).read_text())
        if (previous.get('package_spec_sha256') != specification
                or previous.get('archive') != {'filename': destination.name, **file_record(destination)}):
            raise ValueError('Existing package or its inputs changed; refusing resume')
        verify_archive(destination, package_id, expected)
        return previous
    with tempfile.TemporaryDirectory(prefix=f'.{package_id}-', dir=output_root) as temporary:
        staging = Path(temporary) / destination.name
        write_archive(staging, package_id, files)
        verify_archive(staging, package_id, expected)
        record = {**candidate, 'archive': {'filename': destination.name, **file_record(staging)},
                  'archive_root': package_id, 'unpacked_bytes': sum(item['size_bytes'] for item in expected.values()),
                  'files': expected, 'package_spec_sha256': specification,
                  'archive_format': 'gzip mtime=0, level=6; USTAR uid=gid=mtime=0; sorted files',
                  'app_catalog_candidate': {'id': package_id, 'filename': destination.name,
                                            'sha256': sha256(staging), 'size_bytes': staging.stat().st_size,
                                            'multilingual': True, 'published_url': None}}
        staging.rename(destination)
        write_json(sidecar, record)
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    language_group = parser.add_mutually_exclusive_group()
    language_group.add_argument('--languages', nargs='+', choices=PRIORITY)
    language_group.add_argument('--all-languages', action='store_true')
    parser.add_argument('--variants', nargs='+', choices=VARIANTS, default=list(VARIANTS))
    parser.add_argument('--candidates-root', type=Path, default=Path('candidates/mobile-builds'))
    parser.add_argument('--source-root', type=Path, default=Path('candidates/mobile-fp32'))
    parser.add_argument('--local-source-root', type=Path, default=Path('candidates/mobile-fp32-local'))
    parser.add_argument('--reports-root', type=Path, default=Path('reports/mobile-quantization'))
    parser.add_argument('--output-root', type=Path, default=Path('candidates/mobile-packages'))
    parser.add_argument('--builder-source-root', type=Path,
                        help='Exact archived builder code plus manifest.json; only builder code hashes override current recipe settings')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--require-bound-reports', action='store_true',
                        help='Refuse missing/historical unbound quality reports; failed but bound candidates remain explicitly marked')
    parser.add_argument('--skip-missing', action='store_true', help='Record requested builds not yet present without inventing packages')
    args = parser.parse_args()
    selected = PRIORITY if args.all_languages else (args.languages or PRIORITY[:8])
    requested = [language for language in PRIORITY if language in selected]
    existing_path = args.output_root / 'review-manifest.json'
    previous = json.loads(existing_path.read_text()) if existing_path.exists() else {}
    if previous and not args.resume:
        raise ValueError('Review manifest exists; use --resume to add verified packages')
    rows = {row['id']: row for row in previous.get('candidates', [])}
    missing = []
    for language in requested:
        source_dir = args.source_root / language
        if language == 'as' and (args.local_source_root / language / 'model.onnx').is_file():
            source_dir = args.local_source_root / language
        source = None
        for variant in dict.fromkeys(args.variants):
            directory = args.candidates_root / language / variant
            if not (directory / 'build.json').exists() and args.skip_missing:
                missing.append({'language': language, 'variant': variant, 'status': 'build_not_available'})
                continue
            if source is None:
                source = verified_source(source_dir)
            record = package_candidate(directory, source_dir, args.output_root,
                                       args.reports_root, language, variant,
                                       resume=args.resume, source=source,
                                       require_bound_reports=args.require_bound_reports,
                                       builder_source_root=args.builder_source_root)
            rows[record['id']] = record
            print(json.dumps({'id': record['id'], 'archive_bytes': record['archive']['size_bytes'],
                              'model_bytes': record['model']['size_bytes'],
                              'quality': record['quality']['screening_status'],
                              'reported_gate_failures': record['quality'].get('reported_gate_failures')}, ensure_ascii=False), flush=True)
    ordered = sorted(rows.values(), key=lambda row: (PRIORITY.index(row['language']), list(VARIANTS).index(row['variant'])))
    # Existing records are retained only after checking that their archives still match.
    for row in ordered:
        archive = args.output_root / row['archive']['filename']
        if file_record(archive) != {key: row['archive'][key] for key in ('size_bytes', 'sha256')}:
            raise ValueError(f'Existing review archive changed: {archive}')
    write_json(existing_path, {'status': 'candidate_unvalidated', 'release_ready': False,
                              'published': False, 'selected_candidate': None,
                              'languages_requested': requested, 'missing_builds': missing,
                              'candidate_count': len(ordered),
                              'total_archive_bytes': sum(row['archive']['size_bytes'] for row in ordered),
                              'candidates': ordered})
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
