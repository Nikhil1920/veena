"""Exercise reproducible app-compatible bundles and reject changed/unsafe inputs."""
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_model_batch import REQUIRED_DECODER, build_candidate
import package_mobile_candidates as packager
from package_mobile_candidates import (APP_MODEL_FILENAME, file_record, package_candidate,
                                       validate_local_decoder, verify_archive)
from quantize_language_model import artifact_record


class PackagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source'; self.source.mkdir()
        self.candidate = self.root / 'candidate'
        self.reports = self.root / 'reports'; (self.reports / 'batch').mkdir(parents=True)
        rng = np.random.default_rng(21)
        graph = helper.make_graph([
            helper.make_node('Conv', ['x', 'encoder'], ['h'], name='encoder_conv', kernel_shape=[1]),
            helper.make_node('Conv', ['h', 'head'], ['nct'], name='head_conv', kernel_shape=[1]),
            helper.make_node('Transpose', ['nct'], ['ntc'], perm=[0, 2, 1]),
            helper.make_node('LogSoftmax', ['ntc'], ['logprobs'], axis=-1),
        ], 'ctc', [helper.make_tensor_value_info('x', TensorProto.FLOAT, [1, 32, 5])],
            [helper.make_tensor_value_info('logprobs', TensorProto.FLOAT, [1, 5, 257])],
            [numpy_helper.from_array(rng.normal(0, .1, (32, 32, 1)).astype(np.float32), 'encoder'),
             numpy_helper.from_array(rng.normal(0, .1, (257, 32, 1)).astype(np.float32), 'head')])
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid('', 16)], ir_version=8)
        onnx.save_model(model, self.source / 'model.onnx')
        original = artifact_record(self.source / 'model.onnx')
        (self.source / 'source.json').write_text(json.dumps({'source': {
            'files': original['files'], 'license': 'MIT', 'repository': 'example/model',
            'revision': '0123456789abcdef'}}))
        for name, value in {
            'decoder_config.json': {'num_classes': 257, 'blank_idx': 256},
            'language_masks.json': {'hi': list(range(257))},
            'vocab.json': {'hi': ['<unk>', *['a'] * 255, '<blk>']},
        }.items():
            (self.source / name).write_text(json.dumps(value))
        self.build = build_candidate(self.source, self.candidate, 'hi', 'compact-int4')
        self.score_path = self.reports / 'batch' / 'hi-compact-int4.json'
        self.score_path.write_text(json.dumps({
            'model': str(self.candidate / 'model.onnx'), 'model_size_bytes': self.build['output_bytes'],
            'required_languages': ['hi'], 'candidate_variant': 'compact-int4', 'status': 'ok',
            'model_artifact': artifact_record(self.candidate / 'model.onnx'),
            'decoder_assets': {name: self.build['decoder_assets'][name] for name in REQUIRED_DECODER},
            'artifact_binding': {'status': 'verified', 'method': 'sha256',
                                 'checked_before_inference': True, 'checked_after_inference': True},
            'comparison_against_fp32': {'quality_gate_passed': False},
            'comparison_against_shipped': {'quality_gate_passed': True},
            'aggregate': {'wer': .2, 'cer': .05, 'sample_count': 20}}))

    def package(self, output='packages', **kwargs):
        return package_candidate(self.candidate, self.source, self.root / output,
                                 self.reports, 'hi', 'compact-int4', **kwargs)

    def builder_snapshot(self):
        snapshot = self.root / 'builder-snapshot'; snapshot.mkdir()
        records = {}
        for name in self.build['settings']['builder_sha256']:
            raw = (packager.BUILDER_SOURCE_ROOT / name).read_bytes()
            (snapshot / name).write_bytes(raw)
            records[name] = file_record(raw)
        manifest = {'schema_version': 1, 'files': records,
                    'builder_sha256': {name: record['sha256'] for name, record in records.items()},
                    'verified_build_records': [{'path': str(self.candidate / 'build.json'),
                                               'sha256': file_record(self.candidate / 'build.json')['sha256']}]}
        (snapshot / 'manifest.json').write_text(json.dumps(manifest))
        return snapshot

    def test_historical_builder_snapshot_survives_current_code_change(self):
        snapshot = self.builder_snapshot()
        modified = self.root / 'new-code'; modified.mkdir()
        for name in self.build['settings']['builder_sha256']:
            (modified / name).write_bytes((snapshot / name).read_bytes() + b'\n# newer input guard\n')
        original_build = (self.candidate / 'build.json').read_bytes()
        with mock.patch.object(packager, 'BUILDER_SOURCE_ROOT', modified):
            with self.assertRaisesRegex(ValueError, 'settings changed'):
                self.package('current')
            packaged = self.package('historical', builder_source_root=snapshot)
        self.assertEqual((self.candidate / 'build.json').read_bytes(), original_build)
        self.assertEqual(packaged['builder_sources']['origin'], 'archived_snapshot')
        for name, expected_hash in self.build['settings']['builder_sha256'].items():
            self.assertEqual(packaged['builder_sources']['files'][name]['sha256'], expected_hash)
            self.assertIn(f'build-source-{name}', packaged['files'])
        self.assertIn('build-source-manifest.json', packaged['files'])

    def test_builder_snapshot_byte_or_record_mismatch_rejected(self):
        snapshot = self.builder_snapshot()
        path = snapshot / 'quantize_int4.py'; old = path.read_bytes()
        path.write_bytes(old + b'\n# changed\n')
        with self.assertRaisesRegex(ValueError, 'snapshot manifest does not match'):
            self.package(builder_source_root=snapshot)
        path.write_bytes(old)
        path = snapshot / 'manifest.json'
        manifest = json.loads(path.read_text()); manifest['verified_build_records'] = []
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'Build record is not verified'):
            self.package(builder_source_root=snapshot)

    def test_builder_snapshot_does_not_override_numerical_recipe_or_runtime(self):
        snapshot = self.builder_snapshot()
        original_settings = packager.recipe_settings
        for key, value in [('block_size', 64), ('onnxruntime_version', '0.0.0')]:
            def changed_settings(variant, key=key, value=value):
                settings = original_settings(variant); settings[key] = value
                return settings
            with mock.patch.object(packager, 'recipe_settings', changed_settings):
                with self.assertRaisesRegex(ValueError, 'settings changed'):
                    self.package(builder_source_root=snapshot)

    def test_deterministic_single_root_loader_alias_and_explicit_failed_gate(self):
        first, second = self.package('one'), self.package('two')
        self.assertEqual(first, second)
        self.assertEqual(first['archive']['sha256'], second['archive']['sha256'])
        self.assertEqual(first['quality']['screening_status'], 'failed_fp32_gate')
        self.assertFalse(first['release_ready'])
        self.assertFalse(first['published'])
        self.assertTrue(first['decoder']['multilingual'])
        self.assertEqual(first['attribution']['license_terms_url'], 'https://opensource.org/license/mit')
        archive_path = self.root / 'one' / first['archive']['filename']
        with tarfile.open(archive_path, 'r:gz') as archive:
            members = archive.getmembers()
            self.assertEqual({member.name.split('/')[0] for member in members}, {first['id']})
            model = archive.extractfile(f"{first['id']}/{APP_MODEL_FILENAME}").read()
            self.assertEqual(model, (self.candidate / 'model.onnx').read_bytes())
            self.assertEqual(sum(member.name.endswith('.onnx') for member in members), 1)
            self.assertTrue(all(member.mtime == 0 and member.uid == 0 and member.gid == 0 for member in members))
        self.assertEqual(archive_path.read_bytes()[4:8], b'\0\0\0\0')

    def test_tampered_model_decoder_or_original_source_fails_closed(self):
        for path in (self.candidate / 'model.onnx', self.candidate / 'vocab.json',
                     self.source / 'model.onnx'):
            with self.subTest(path=path):
                old = path.read_bytes()
                if path.parent == self.source:
                    altered = onnx.load(path)
                    altered.doc_string = 'tampered but still a valid protobuf'
                    onnx.save(altered, path)
                else:
                    path.write_bytes(old + b'tampered')
                with self.assertRaises(ValueError):
                    self.package()
                path.write_bytes(old)
        self.assertFalse((self.root / 'packages').exists())

    def test_wrong_candidate_score_is_not_attached(self):
        score = json.loads(self.score_path.read_text())
        score['candidate_variant'] = 'balanced-int8'
        self.score_path.write_text(json.dumps(score))
        with self.assertRaisesRegex(ValueError, 'Quality report does not describe'):
            self.package()

    def test_old_report_is_explicitly_unbound_without_inferred_hashes(self):
        score = json.loads(self.score_path.read_text())
        del score['model_artifact']; del score['decoder_assets']
        score['comparison_against_fp32']['quality_gate_passed'] = True
        self.score_path.write_text(json.dumps(score))
        quality = self.package()['quality']
        self.assertEqual(quality['screening_status'], 'unbound_historical_report')
        self.assertIsNone(quality['fp32_quality_gate_passed'])
        self.assertIsNone(quality['shipped_quality_gate_passed'])
        self.assertEqual(quality['reported_screening_status'], 'passed_pilot_gates')
        self.assertNotIn('model_artifact_sha256', quality['score_binding'])

    def test_same_size_stale_model_or_decoder_report_fails_closed(self):
        original = json.loads(self.score_path.read_text())
        for target in ['model', 'decoder']:
            score = json.loads(json.dumps(original))
            if target == 'model':
                score['model_artifact']['sha256'] = '0' * 64
            else:
                score['decoder_assets']['vocab.json']['sha256'] = '0' * 64
            self.score_path.write_text(json.dumps(score))
            with self.assertRaisesRegex(ValueError, 'digest'):
                self.package()

    def test_digests_without_execution_binding_cannot_pass(self):
        score = json.loads(self.score_path.read_text())
        score['artifact_binding']['checked_after_inference'] = False
        self.score_path.write_text(json.dumps(score))
        with self.assertRaisesRegex(ValueError, 'pre/post inference'):
            self.package()

    def test_require_bound_reports_rejects_missing_or_historical_scores(self):
        score = json.loads(self.score_path.read_text())
        del score['model_artifact']; del score['decoder_assets']
        self.score_path.write_text(json.dumps(score))
        with self.assertRaisesRegex(ValueError, 'bound quality report is required'):
            self.package(require_bound_reports=True)
        self.score_path.unlink()
        with self.assertRaisesRegex(ValueError, 'bound quality report is required'):
            self.package(require_bound_reports=True)

    def test_asset_symlink_is_rejected_even_when_bytes_match(self):
        path = self.candidate / 'vocab.json'
        path.unlink(); path.symlink_to(self.source / 'vocab.json')
        with self.assertRaisesRegex(ValueError, 'Only regular files'):
            self.package()

    def test_strict_resume_detects_changed_archive_and_changed_score(self):
        record = self.package()
        self.assertEqual(self.package(resume=True), record)
        with self.assertRaisesRegex(ValueError, 'output exists'):
            self.package()
        archive = self.root / 'packages' / record['archive']['filename']
        old = archive.read_bytes(); archive.write_bytes(old + b'tampered')
        with self.assertRaisesRegex(ValueError, 'Existing package or its inputs changed'):
            self.package(resume=True)
        archive.write_bytes(old)
        self.score_path.write_text(self.score_path.read_text() + '\n')
        with self.assertRaisesRegex(ValueError, 'Existing package or its inputs changed'):
            self.package(resume=True)

    def test_nonlocal_or_wrong_language_decoder_rejected(self):
        with self.assertRaisesRegex(ValueError, 'own 257 local indices'):
            validate_local_decoder(self.candidate, 'te')
        path = self.candidate / 'decoder_config.json'
        path.write_text(json.dumps({'num_classes': 5633, 'blank_idx': 5632}))
        with self.assertRaisesRegex(ValueError, 'local 257-class'):
            validate_local_decoder(self.candidate, 'hi')

    def test_nested_tar_member_rejected_without_extracting(self):
        archive = self.root / 'unsafe.tar.gz'
        with tarfile.open(archive, 'w:gz') as output:
            root = tarfile.TarInfo('package'); root.type = tarfile.DIRTYPE; output.addfile(root)
            entry = tarfile.TarInfo('package/../evil'); entry.size = 1
            output.addfile(entry, io.BytesIO(b'x'))
        with self.assertRaisesRegex(ValueError, 'nested, or unexpected'):
            verify_archive(archive, 'package', {'model.onnx': file_record(b'x')})


if __name__ == '__main__':
    unittest.main()
