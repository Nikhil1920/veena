"""Screen mobile candidates on reproducible synthetic off-microphone probes.

These tests do not simulate a physical phone or establish real far-field quality.
The original human references are retained; transformed clips are not additional
independent samples. Use a fresh output directory for each complete experiment.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys

from build_model_batch import PRIORITY, write_json
from evaluate_multilingual import compare_reports, evaluate_model
from quantize_language_model import artifact_record
from quantize_int4 import sha256

ROOT = Path(__file__).resolve().parent
CONDITIONS = ('quiet_24db', 'noise_10db', 'room_350ms')


def prepare_legacy_telugu_decoder(destination: Path) -> None:
    """Express the shipped single-language decoder as the evaluator's JSON form.

    The production Telugu decoder reserves IDs5120..5375, skips local <unk>,
    and uses blank5632. Read the actual shipped vocabulary, never another
    checkpoint's tokenizer. The model and production assets are left untouched.
    """
    source = ROOT / 'browser-assets/indicconformer-te'
    config_path, tokens_path = source / 'decoder_config.json', source / 'tokenizer_te.vocab'
    config = json.loads(config_path.read_text())
    tokens = [line.split('\t')[0] for line in tokens_path.read_text().splitlines()]
    if config != {'num_classes': 5633, 'blank_idx': 5632} or len(tokens) != 256 or tokens[0] != '<unk>':
        raise ValueError('Unexpected shipped Telugu decoder contract')
    write_json(destination / 'decoder_config.json', config)
    write_json(destination / 'vocab.json', {'te': [*tokens, '<blk>']})
    write_json(destination / 'language_masks.json', {'te': [*range(5120, 5376), 5632]})
    write_json(destination / 'source.json', {'purpose': 'Evaluation adapter for shipped Telugu decoder',
                                           'vocabulary': str(tokens_path), 'vocabulary_sha256': sha256(tokens_path),
                                           'decoder_config_sha256': sha256(config_path)})


def model_paths(language: str, te_decoder_dir: Path) -> dict[str, tuple[Path, Path]]:
    source = ROOT / 'candidates' / ('mobile-fp32-local' if language == 'as' else 'mobile-fp32') / language
    shipped = ROOT / 'browser-assets' / ('indicconformer-te' if language == 'te' else 'indicconformer-multi')
    shipped_name = ('indicconformer_stt_te_ctc_static_qdq_int8.onnx' if language == 'te'
                    else 'indicconformer_multi_ctc_int8.onnx')
    result = {'fp32': (source / 'model.onnx', source),
              'shipped': (shipped / shipped_name, te_decoder_dir if language == 'te' else shipped)}
    for variant in ('compact-int4', 'balanced-int8'):
        directory = ROOT / 'candidates/mobile-builds' / language / variant
        result[variant] = (directory / 'model.onnx', directory)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--languages', nargs='+', choices=PRIORITY, default=list(PRIORITY[:8]))
    parser.add_argument('--conditions', nargs='+', choices=CONDITIONS, default=list(CONDITIONS))
    parser.add_argument('--corpus-root', type=Path, default=ROOT / 'corpus/indicvoices-v1-pilot')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error('Output exists; choose a new directory to preserve previous experiments')
    languages = [language for language in PRIORITY if language in args.languages]
    output.mkdir(parents=True)
    te_decoder_dir = output / 'decoders/shipped-te'
    if 'te' in languages:
        prepare_legacy_telugu_decoder(te_decoder_dir)
    # Fail before generating data if any candidate or original manifest is absent.
    for language in languages:
        if not (args.corpus_root / language / 'evaluation/manifest.jsonl').is_file():
            parser.error(f'Missing evaluation manifest: {language}')
        for model, directory in model_paths(language, te_decoder_dir).values():
            if not model.is_file() or any(not (directory / name).is_file()
                                        for name in ('decoder_config.json', 'vocab.json', 'language_masks.json')):
                parser.error(f'Missing model/decoder: {model}')
    summary = {'status': 'synthetic_quality_screen', 'release_ready': False,
               'latency_qualified': False, 'languages': languages, 'conditions': args.conditions,
               'limitations': 'Synthetic probes only; no physical phone or real far-field recordings. '
                              'Transformed clips reuse the same speakers and human references. '
                              'Single-pass timings under concurrent work are unqualified.',
               'results': []}
    for language in languages:
        stress = output / 'corpus' / language
        subprocess.run([sys.executable, str(ROOT / 'make_audio_stress_set.py'),
                        '--manifest', str((args.corpus_root / language / 'evaluation/manifest.jsonl').resolve()),
                        '--output', str(stress), '--conditions', *args.conditions], check=True)
        paths = model_paths(language, te_decoder_dir)
        artifacts = {name: artifact_record(model) for name, (model, _) in paths.items()}
        for condition in args.conditions:
            reports = {}
            for name, (model, directory) in paths.items():
                report = evaluate_model(model, directory, stress / condition / 'manifest.jsonl',
                                        threads=2, warmup=0, repeats=1, required_languages=[language])
                report.update({'source_artifact': artifacts[name], 'condition': condition,
                               'latency_qualified': False, 'synthetic_stress_probe': True,
                               'timing_note': summary['limitations']})
                if name in ('compact-int4', 'balanced-int8'):
                    for baseline in ('fp32', 'shipped'):
                        report[f'comparison_against_{baseline}'] = compare_reports(
                            reports[baseline], report, max_wer_regression=.01,
                            max_cer_regression=.01, min_samples_per_language=20)
                destination = output / 'reports' / language / f'{condition}-{name}.json'
                write_json(destination, report)
                reports[name] = report
                row = {'language': language, 'condition': condition, 'model': name,
                       'report': str(destination), 'aggregate': report['aggregate'],
                       'status': report['status']}
                for baseline in ('fp32', 'shipped'):
                    comparison = report.get(f'comparison_against_{baseline}')
                    if comparison is not None:
                        row[f'{baseline}_quality_gate_passed'] = comparison['quality_gate_passed']
                summary['results'].append(row)
                write_json(output / 'summary.json', summary)
                print(json.dumps(row, ensure_ascii=False), flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
