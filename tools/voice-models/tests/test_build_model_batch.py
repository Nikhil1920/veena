"""Exercise real tiny quantization builds and strict resumability."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_model_batch import build_candidate, verified_source
from quantize_language_model import artifact_record


class BatchTests(unittest.TestCase):
    def test_both_builds_resume_only_with_identical_source_settings_and_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'source'; source.mkdir()
            rng = np.random.default_rng(55)
            graph = helper.make_graph([
                helper.make_node('Conv', ['x', 'encoder'], ['h'], name='encoder_conv', kernel_shape=[1]),
                helper.make_node('Conv', ['h', 'head'], ['nct'], name='head_conv', kernel_shape=[1]),
                helper.make_node('Transpose', ['nct'], ['ntc'], perm=[0, 2, 1]),
                helper.make_node('LogSoftmax', ['ntc'], ['logprobs'], axis=-1),
            ], 'ctc', [helper.make_tensor_value_info('x', TensorProto.FLOAT, [1, 64, 5])],
                [helper.make_tensor_value_info('logprobs', TensorProto.FLOAT, [1, 5, 8])],
                [numpy_helper.from_array(rng.normal(0, .1, (64, 64, 1)).astype(np.float32), 'encoder'),
                 numpy_helper.from_array(rng.normal(0, .1, (8, 64, 1)).astype(np.float32), 'head')])
            model = helper.make_model(graph, opset_imports=[helper.make_opsetid('', 16)], ir_version=8)
            onnx.save_model(model, source / 'model.onnx', save_as_external_data=True,
                            all_tensors_to_one_file=True, location='weights.bin', size_threshold=0)
            original = artifact_record(source / 'model.onnx')
            (source / 'source.json').write_text(json.dumps({'source': {'files': original['files']}}))
            for name, content in {'decoder_config.json': {'num_classes': 8, 'blank_idx': 7},
                                  'language_masks.json': {'hi': list(range(8))},
                                  'vocab.json': {'hi': ['', 'a', 'b', 'c', 'd', 'e', 'f', '']}}.items():
                (source / name).write_text(json.dumps(content))
            for variant in ('compact-int4', 'balanced-int8'):
                with self.subTest(variant=variant):
                    output = root / variant
                    built = build_candidate(source, output, 'hi', variant)
                    self.assertFalse(built['release_ready'])
                    self.assertEqual(built['source_artifact_sha256'], original['artifact_sha256'])
                    self.assertEqual(build_candidate(source, output, 'hi', variant, resume=True), built)
                    self.assertTrue((output / 'decoder_config.json').is_file())
                    with self.assertRaisesRegex(ValueError, 'Output exists'):
                        build_candidate(source, output, 'hi', variant)
                    old = (output / 'model.onnx').read_bytes()
                    (output / 'model.onnx').write_bytes(old + b'tamper')
                    with self.assertRaisesRegex(ValueError, 'output SHA256'):
                        build_candidate(source, output, 'hi', variant, resume=True)
                    (output / 'model.onnx').write_bytes(old)
                    path = output / 'vocab.json'; previous = path.read_text(); path.write_text('{}')
                    with self.assertRaisesRegex(ValueError, 'decoder/provenance asset changed'):
                        build_candidate(source, output, 'hi', variant, resume=True)
                    path.write_text(previous)
                    path = output / 'build.json'; record = json.loads(path.read_text())
                    record['settings']['bits'] = 2; path.write_text(json.dumps(record))
                    with self.assertRaisesRegex(ValueError, 'settings changed'):
                        build_candidate(source, output, 'hi', variant, resume=True)
            weights = source / 'weights.bin'
            weights.write_bytes(weights.read_bytes() + b'tamper')
            with self.assertRaisesRegex(ValueError, 'Source SHA256/size mismatch'):
                verified_source(source)


if __name__ == '__main__':
    unittest.main()
