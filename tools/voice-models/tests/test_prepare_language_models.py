"""Contract and artifact-integrity tests using tiny synthetic ONNX graphs."""
from __future__ import annotations

import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_language_models as preparation


class LanguagePackageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.tokens = [f'token-{i}' for i in range(5632)] + ['<blk>']
        self.tokens[4], self.tokens[17] = '▁తెలుగు', 'పదం'
        self.model_path = self.root / 'candidate.onnx'

    def model(self, active=(4, 17, 5632), language='te'):
        mask = np.full(len(self.tokens), -np.inf, dtype=np.float32)
        mask[list(active)] = 0
        inputs = [
            helper.make_tensor_value_info('audio_signal', TensorProto.FLOAT, ['batch', 80, 'time']),
            helper.make_tensor_value_info('length', TensorProto.INT64, ['batch']),
        ]
        output = helper.make_tensor_value_info('logprobs', TensorProto.FLOAT, ['batch', 'time', len(self.tokens)])
        nodes = [
            helper.make_node('ReduceMean', ['audio_signal'], ['mean'], axes=[1], keepdims=0),
            helper.make_node('Unsqueeze', ['mean', 'axes'], ['logits']),
            helper.make_node('Add', ['logits', 'mask'], ['logprobs']),
        ]
        initializers = [numpy_helper.from_array(mask, 'mask'),
                        numpy_helper.from_array(np.array([2], dtype=np.int64), 'axes')]
        graph = helper.make_graph(nodes, 'synthetic-masked-ctc', inputs, [output], initializer=initializers)
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid('', 16)], ir_version=8)
        helper.set_model_props(model, {'comment': f'ai4bharat indicconformer, {language}, CTC-only, masked, int8 MatMul-only',
                                       'normalize_type': 'per_feature', 'subsampling_factor': '4'})
        return model

    def save(self, model=None):
        onnx.save(self.model() if model is None else model, self.model_path)
        return self.model_path

    def asset(self, path):
        return {'url': 'https://example.test/pinned/model.int8.onnx',
                'size_bytes': path.stat().st_size, 'sha256': preparation.sha256(path),
                'revision': '9721eb71eea141fae0982cfcdb9dd2e3d4953c4a'}

    def test_final_mask_selects_global_ids_and_ignores_unconnected_mask(self):
        model = self.model()
        model.graph.initializer.append(numpy_helper.from_array(np.zeros(len(self.tokens), np.float32), 'unconnected_mask'))
        contract, indices = preparation.inspect_model(self.save(model), 'te', self.tokens)
        self.assertEqual(indices, [4, 17, 5632])
        self.assertEqual(contract['blank_idx'], 5632)
        self.assertEqual(contract['active_classes'], 3)
        self.assertEqual([self.tokens[i] for i in indices], ['▁తెలుగు', 'పదం', '<blk>'])

    def test_rejects_malformed_input_output_and_language_contracts(self):
        mutations = {
            'feature dtype': lambda m: setattr(m.graph.input[0].type.tensor_type, 'elem_type', TensorProto.DOUBLE),
            'feature bins': lambda m: setattr(m.graph.input[0].type.tensor_type.shape.dim[1], 'dim_value', 64),
            'length dtype': lambda m: setattr(m.graph.input[1].type.tensor_type, 'elem_type', TensorProto.INT32),
            'output dtype': lambda m: setattr(m.graph.output[0].type.tensor_type, 'elem_type', TensorProto.DOUBLE),
            'vocab dimension': lambda m: setattr(m.graph.output[0].type.tensor_type.shape.dim[2], 'dim_value', 100),
            'language mismatch': lambda m: helper.set_model_props(m, {'comment': 'ai4bharat indicconformer, hi, CTC-only'}),
            'missing final Add': lambda m: setattr(m.graph.node[-1], 'op_type', 'Mul'),
        }
        for label, mutate in mutations.items():
            with self.subTest(label):
                model = self.model()
                mutate(model)
                with self.assertRaises((ValueError, onnx.checker.ValidationError)):
                    preparation.inspect_model(self.save(model), 'te', self.tokens)

    def test_rejects_missing_blank_all_active_and_blank_only_masks(self):
        for active in ((4, 17), (5632,), range(len(self.tokens))):
            with self.subTest(active_count=len(active)):
                with self.assertRaisesRegex(ValueError, 'Language mask'):
                    preparation.inspect_model(self.save(self.model(active=active)), 'te', self.tokens)

    def test_rejects_nan_positive_infinity_and_nonzero_mask_values(self):
        for invalid in (np.nan, np.inf, 1.0):
            with self.subTest(value=invalid):
                model = self.model()
                mask = numpy_helper.to_array(model.graph.initializer[0]).copy()
                mask[5] = invalid
                model.graph.initializer[0].CopyFrom(numpy_helper.from_array(mask, 'mask'))
                with self.assertRaisesRegex(ValueError, 'output language mask'):
                    preparation.inspect_model(self.save(model), 'te', self.tokens)

    def test_rejects_external_tensor_storage(self):
        model = self.model()
        tensor = model.graph.initializer[0]
        tensor.ClearField('raw_data')
        tensor.data_location = TensorProto.EXTERNAL
        item = tensor.external_data.add()
        item.key, item.value = 'location', 'missing.weights'
        with self.assertRaisesRegex(ValueError, 'external tensors'):
            preparation.inspect_model(self.save(model), 'te', self.tokens)

    def test_rejects_wrong_shared_blank_token(self):
        self.tokens[-1] = 'ordinary-token'
        with self.assertRaisesRegex(ValueError, 'shared blank'):
            preparation.inspect_model(self.save(), 'te', self.tokens)

    def test_read_tokens_retains_ids_unicode_and_embedded_spaces(self):
        path = self.root / 'tokens.txt'
        path.write_text('▁తెలుగు 0\nwith space 1\n<blk> 2\n', encoding='utf-8')
        self.assertEqual(preparation.read_tokens(path), ['▁తెలుగు', 'with space', '<blk>'])
        for malformed in ('a 0\nb 0\n', 'a 0\nb 2\n', 'a -1\n'):
            with self.subTest(malformed=malformed):
                path.write_text(malformed)
                with self.assertRaises(ValueError):
                    preparation.read_tokens(path)

    def test_download_reuses_only_verified_cached_bytes(self):
        source = self.root / 'source.bin'
        source.write_bytes(b'correct-model')
        asset = self.asset(source)
        with patch.object(preparation.urllib.request, 'urlopen') as request:
            self.assertEqual(preparation.download_verified(asset, source), source)
            request.assert_not_called()
            source.write_bytes(b'corrupt-model')
            with self.assertRaisesRegex(ValueError, 'Cached file failed verification'):
                preparation.download_verified(asset, source)
            request.assert_not_called()
            self.assertEqual(source.read_bytes(), b'corrupt-model')

    def test_download_rejects_hash_and_size_mismatch_without_committing_partial(self):
        source = self.root / 'source.bin'
        source.write_bytes(b'expected-model')
        asset = self.asset(source)
        target = self.root / 'downloads/model.onnx'
        for downloaded in (b'invalid-model!', b'too-short'):
            with self.subTest(downloaded=downloaded):
                with patch.object(preparation.urllib.request, 'urlopen', return_value=io.BytesIO(downloaded)):
                    with self.assertRaisesRegex(ValueError, 'size/SHA256 verification'):
                        preparation.download_verified(asset, target)
                self.assertFalse(target.exists())
                self.assertFalse(target.with_suffix('.onnx.partial').exists())
        with patch.object(preparation.urllib.request, 'urlopen', return_value=io.BytesIO(source.read_bytes())):
            self.assertEqual(preparation.download_verified(asset, target), target)
        self.assertEqual(target.read_bytes(), source.read_bytes())

    def test_archive_bytes_are_deterministic_and_metadata_is_normalized(self):
        directory = self.root / 'model-package'
        directory.mkdir()
        (directory / 'z.json').write_text('{"te":"తెలుగు"}', encoding='utf-8')
        (directory / 'a.onnx').write_bytes(b'synthetic-model')
        first, second = self.root / 'first.tar.gz', self.root / 'second.tar.gz'
        preparation.package_archive(directory, first)
        for path in directory.iterdir():
            os.utime(path, (1234567, 1234567))
            path.chmod(0o600)
        preparation.package_archive(directory, second)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with tarfile.open(first, 'r:gz') as archive:
            self.assertEqual(archive.getnames(), ['model-package/a.onnx', 'model-package/z.json'])
            for member in archive.getmembers():
                self.assertEqual((member.uid, member.gid, member.mtime), (0, 0, 0))
                self.assertEqual((member.uname, member.gname, member.mode), ('', '', 0o644))
        self.assertFalse(first.with_suffix('.gz.partial').exists())

    def test_prepare_language_preserves_own_tokens_and_marks_candidate_unvalidated(self):
        cache, output = self.root / 'cache', self.root / 'packs'
        (cache / 'te').mkdir(parents=True)
        output.mkdir()
        model_path = cache / 'te/model.int8.onnx'
        onnx.save(self.model(), model_path)
        asset = self.asset(model_path)
        entry = {'code': 'te', 'name': 'Telugu', 'community_int8': asset}
        catalog = {'shared_assets': {'tokens': {'sha256': 'token-source-sha'}},
                   'sources': {'community': {'revision': asset['revision']}}}
        result = preparation.prepare_language(entry, catalog, self.tokens, cache, output, archive=True)
        directory = Path(result['directory'])
        self.assertEqual(json.loads((directory / 'language_masks.json').read_text()), {'te': [4, 17, 5632]})
        self.assertEqual(json.loads((directory / 'vocab.json').read_text()), {'te': ['▁తెలుగు', 'పదం', '<blk>']})
        self.assertEqual(json.loads((directory / 'decoder_config.json').read_text()), {'num_classes': 5633, 'blank_idx': 5632})
        self.assertEqual(result['id'], 'indicconformer-te-int8-9721eb71')
        self.assertFalse(result['release_ready'])
        self.assertEqual(result['status'], 'candidate_unvalidated')
        provenance = json.loads((directory / 'source.json').read_text())
        self.assertFalse(provenance['publish'])
        self.assertEqual(provenance['source']['sha256'], preparation.sha256(directory / preparation.MODEL_NAME))
        self.assertEqual(result['archive_sha256'], preparation.sha256(Path(result['archive'])))
        with tarfile.open(result['archive']) as archive:
            self.assertEqual(len(archive.getmembers()), 5)
        repeat = preparation.prepare_language(entry, catalog, self.tokens, cache, output, archive=True)
        self.assertEqual(result['archive_sha256'], repeat['archive_sha256'])


if __name__ == '__main__':
    unittest.main()
