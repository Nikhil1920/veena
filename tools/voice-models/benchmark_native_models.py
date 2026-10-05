"""Repeat a real clip through Veena's production Rust backend on this host.

Run after other model preparation jobs finish. This is a small host CPU timing
experiment, not a physical-phone benchmark or representative latency estimate.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys

from build_model_batch import PRIORITY, write_json
from quantize_int4 import sha256

ROOT = Path(__file__).resolve().parent


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--languages', nargs='+', choices=PRIORITY, default=list(PRIORITY[:8]))
    parser.add_argument('--binary', type=Path, default=ROOT.parents[1] / 'target/release/model_smoke')
    parser.add_argument('--sample-index', type=int, default=0)
    parser.add_argument('--warmup', type=int, default=2)
    parser.add_argument('--runs', type=int, default=5)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.sample_index < 0 or args.warmup < 0 or args.runs < 2:
        parser.error('Require a nonnegative sample index/warmup and at least two measured runs')
    if not args.binary.is_file() or args.output.exists():
        parser.error('Build model_smoke and choose a new output directory')
    args.output.mkdir(parents=True)
    summary = {'status': 'host_native_timing_pilot', 'release_ready': False,
               'physical_phone_tested': False, 'warmup': args.warmup, 'runs': args.runs,
               'limitations': 'One public natural-speech clip per language, repeated in one session. '
                              'Production constructor CPU defaults on this host; no phone performance claim. '
                              'Model order rotates between languages. Peak RSS includes the whole native process.',
               'results': []}
    for index, language in enumerate(language for language in PRIORITY if language in args.languages):
        manifest = ROOT / 'corpus/indicvoices-v1-pilot' / language / 'evaluation/manifest.jsonl'
        rows = [json.loads(line) for line in manifest.read_text().splitlines() if line.strip()]
        row = rows[args.sample_index]
        audio = (manifest.parent / row['audio']).resolve()
        variants = ['shipped', 'compact-int4', 'balanced-int8']
        variants = variants[index % 3:] + variants[:index % 3]
        for variant in variants:
            if variant == 'shipped':
                directory = ROOT / 'browser-assets' / ('indicconformer-te' if language == 'te' else 'indicconformer-multi')
                model = directory / ('indicconformer_stt_te_ctc_static_qdq_int8.onnx' if language == 'te' else 'indicconformer_multi_ctc_int8.onnx')
            else:
                directory = ROOT / 'candidates/mobile-builds' / language / variant
                model = directory / 'model.onnx'
            single = variant == 'shipped' and language == 'te'
            vocab = directory / ('tokenizer_te.vocab' if single else 'vocab.json')
            files = [model, directory / 'decoder_config.json', vocab, audio]
            if not single:
                files.append(directory / 'language_masks.json')
            hashes = {str(path): sha256(path) for path in files}
            command = [str(args.binary.resolve()), '--model', str(model), '--decoder-config', str(directory / 'decoder_config.json'),
                       '--vocab', str(vocab), '--audio', str(audio), '--language', language,
                       '--warmup', str(args.warmup), '--runs', str(args.runs)]
            command += ['--single-language'] if single else ['--language-masks', str(directory / 'language_masks.json')]
            if sys.platform == 'darwin':
                command = ['/usr/bin/time', '-l', *command]
            completed = subprocess.run(command, capture_output=True, text=True, check=True)
            if hashes != {str(path): sha256(path) for path in files}:
                raise ValueError('Benchmark model, decoder or audio changed during execution')
            report = json.loads(completed.stdout)
            durations = [run['preprocess_inference_decode_ms'] for run in report['runs']]
            memory = re.search(r'(\d+)\s+maximum resident set size', completed.stderr)
            report.update({'variant': variant, 'source_utterance_id': row['id'],
                           'verified_input_sha256': hashes, 'median_total_ms': statistics.median(durations),
                           'peak_rss_bytes': int(memory.group(1)) if memory else None,
                           'rtf': statistics.median(durations) / 1000 / row['duration_seconds'],
                           'physical_phone_tested': False})
            destination = args.output / f'{language}-{variant}.json'
            write_json(destination, report)
            result = {key: report[key] for key in ('variant', 'median_total_ms', 'peak_rss_bytes', 'model_load_ms', 'rtf', 'onnxruntime_version')}
            result.update(language=language, report=str(destination.resolve()), duration_seconds=row['duration_seconds'])
            summary['results'].append(result)
            write_json(args.output / 'summary.json', summary)
            print(json.dumps(result), flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
