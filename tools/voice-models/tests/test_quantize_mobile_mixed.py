"""Check the external-weight mobile INT8 path without quantized convolutions."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from quantize_mobile_mixed import build_model, find_ctc_head, sha256


class MobileMixedTests(unittest.TestCase):
    def test_ctc_head_is_detected_by_path_and_kept_exact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            rng = np.random.default_rng(11)
            head_weight = rng.normal(0, .1, (8, 64, 1)).astype(np.float32)
            graph = helper.make_graph([
                helper.make_node('Conv', ['x', 'encoder_weight'], ['h'], name='encoder', kernel_shape=[1]),
                helper.make_node('Conv', ['h', 'head_weight'], ['nct'], name='last_projection', kernel_shape=[1]),
                helper.make_node('Transpose', ['nct'], ['ntc'], perm=[0, 2, 1]),
                helper.make_node('LogSoftmax', ['ntc'], ['logprobs'], axis=-1),
            ], 'ctc-source',
                [helper.make_tensor_value_info('x', TensorProto.FLOAT, [1, 64, 5])],
                [helper.make_tensor_value_info('logprobs', TensorProto.FLOAT, [1, 5, 8])],
                [numpy_helper.from_array(rng.normal(0, .1, (64, 64, 1)).astype(np.float32), 'encoder_weight'),
                 numpy_helper.from_array(head_weight, 'head_weight')])
            model = helper.make_model(graph, opset_imports=[helper.make_opsetid('', 16)], ir_version=8)
            source, output = root / 'model.onnx', root / 'mixed.onnx'
            onnx.save(model, source)
            report = build_model(source, output, keep_ctc_head_float=True)
            self.assertEqual(report['float_ctc_head']['node'], 'last_projection')
            self.assertEqual(report['pointwise_rewrite']['converted_convs'], 1)
            self.assertEqual(report['output_operations']['MatMulInteger'], 1)
            self.assertEqual(report['output_operations']['Conv'], 1)
            weights = {t.name: t for t in onnx.load(output).graph.initializer}
            np.testing.assert_array_equal(numpy_helper.to_array(weights['head_weight']), head_weight)
            options = ort.SessionOptions(); options.intra_op_num_threads = 1
            result = ort.InferenceSession(str(output), options, providers=['CPUExecutionProvider']).run(
                None, {'x': rng.normal(size=(1, 64, 5)).astype(np.float32)})[0]
            self.assertTrue(np.isfinite(result).all())
            np.testing.assert_allclose(np.exp(result).sum(axis=-1), 1, atol=1e-6)
            for broken in ('axis', 'transpose', 'classes'):
                malformed = onnx.ModelProto(); malformed.CopyFrom(model)
                if broken == 'axis':
                    malformed.graph.node[-1].attribute[0].i = 1
                elif broken == 'transpose':
                    malformed.graph.node[-2].attribute[0].ints[:] = [0, 1, 2]
                else:
                    malformed.graph.output[0].type.tensor_type.shape.dim[2].dim_value = 9
                with self.subTest(broken=broken), self.assertRaisesRegex(ValueError, 'CTC head contract'):
                    find_ctc_head(malformed)

    def test_verified_external_hardlinked_source_quantizes_and_runs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'model.onnx'
            graph = helper.make_graph(
                [helper.make_node('Conv', ['x', 'weight', 'bias'], ['y'], name='pointwise', kernel_shape=[1])],
                'mobile-source',
                [helper.make_tensor_value_info('x', TensorProto.FLOAT, [1, 64, 5])],
                [helper.make_tensor_value_info('y', TensorProto.FLOAT, [1, 8, 5])],
                [numpy_helper.from_array(np.random.default_rng(3).normal(0, .2, (8, 64, 1)).astype(np.float32), 'weight'),
                 numpy_helper.from_array(np.zeros(8, np.float32), 'bias')],
            )
            model = helper.make_model(graph, opset_imports=[helper.make_opsetid('', 16)], ir_version=8)
            onnx.save_model(model, source, save_as_external_data=True, all_tensors_to_one_file=True,
                            location='model.onnx_data', size_threshold=0)
            external = root / 'model.onnx_data'
            os.link(external, root / 'cache-hardlink')
            verification = root / 'source.json'
            verification.write_text(json.dumps({'files': [
                {'path': p.name, 'size_bytes': p.stat().st_size, 'sha256': sha256(p)}
                for p in (source, external)]}))
            old_hash = sha256(external)
            output = root / 'mixed.onnx'
            report = build_model(source, output, verification=verification)
            self.assertTrue(report['source_verified'])
            self.assertFalse(report['release_ready'])
            self.assertFalse(report['conv_integer_used'])
            self.assertEqual(report['pointwise_rewrite']['converted_convs'], 1)
            self.assertEqual(report['output_operations']['MatMulInteger'], 1)
            self.assertEqual(sha256(external), old_hash)
            self.assertEqual(external.stat().st_nlink, 2)
            options = ort.SessionOptions(); options.intra_op_num_threads = 1
            result = ort.InferenceSession(str(output), options, providers=['CPUExecutionProvider']).run(
                None, {'x': np.random.default_rng(7).normal(size=(1, 64, 5)).astype(np.float32)})[0]
            self.assertEqual(result.shape, (1, 8, 5))
            self.assertTrue(np.isfinite(result).all())
            bad = json.loads(verification.read_text()); bad['files'][1]['sha256'] = '0' * 64
            verification.write_text(json.dumps(bad))
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                build_model(source, root / 'rejected.onnx', verification=verification)
            self.assertFalse((root / 'rejected.onnx').exists())
            with self.assertRaisesRegex(ValueError, 'already quantized'):
                build_model(output, root / 'requantized.onnx')
            with self.assertRaisesRegex(ValueError, 'existing output'):
                build_model(source, output)


if __name__ == '__main__':
    unittest.main()
