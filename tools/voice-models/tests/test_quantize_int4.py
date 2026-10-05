"""Exercise true FP32-to-INT4 export and prevent accidental requantization."""
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import quantize_int4 as int4


class Int4Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'float.onnx'
        self.output = self.root / 'int4.onnx'

    def model(self):
        weight = np.random.default_rng(17).normal(0, .2, size=(64, 8)).astype(np.float32)
        graph = helper.make_graph(
            [helper.make_node('MatMul', ['x', 'weight'], ['y'], name='projection')],
            'fp32-fixture',
            [helper.make_tensor_value_info('x', TensorProto.FLOAT, [2, 64])],
            [helper.make_tensor_value_info('y', TensorProto.FLOAT, [2, 8])],
            [numpy_helper.from_array(weight, 'weight')],
        )
        return helper.make_model(graph, opset_imports=[helper.make_opsetid('', 16)], ir_version=8)

    def save(self, model=None):
        onnx.save(self.model() if model is None else model, self.source)
        return self.source

    def convolution(self, *, bias=True, **attributes):
        weight = np.random.default_rng(17).normal(0, .2, size=(8, 64, 1)).astype(np.float32)
        initializers = [numpy_helper.from_array(weight, 'weight')]
        inputs = ['x', 'weight']
        if bias:
            inputs.append('bias')
            initializers.append(numpy_helper.from_array(np.arange(8, dtype=np.float32) * .01, 'bias'))
        graph = helper.make_graph(
            [helper.make_node('Conv', inputs, ['y'], name='projection', **attributes)],
            'conv-fixture', [helper.make_tensor_value_info('x', TensorProto.FLOAT, [2, 64, 7])],
            [helper.make_tensor_value_info('y', TensorProto.FLOAT, [2, 8, 7])], initializers,
        )
        return helper.make_model(graph, opset_imports=[helper.make_opsetid('', 16)], ir_version=8)

    def test_pointwise_rewrite_matches_fp32_with_and_without_bias(self):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        feeds = {'x': np.random.default_rng(4).normal(size=(2, 64, 7)).astype(np.float32)}
        for with_bias in (True, False):
            with self.subTest(bias=with_bias):
                model = self.convolution(bias=with_bias, kernel_shape=[1], strides=[1], pads=[0, 0], group=1, dilations=[1])
                before = ort.InferenceSession(model.SerializeToString(), options, providers=['CPUExecutionProvider']).run(None, feeds)[0]
                info = int4.rewrite_pointwise_convolutions(model)
                self.assertEqual(info['converted_convs'], 1)
                self.assertEqual(info['untouched_convs'], 0)
                after = ort.InferenceSession(model.SerializeToString(), options, providers=['CPUExecutionProvider']).run(None, feeds)[0]
                np.testing.assert_allclose(after, before, rtol=2e-6, atol=2e-6)

    def test_pointwise_rewrite_leaves_nontrivial_convolutions_unchanged(self):
        cases = [{'strides': [2]}, {'pads': [1, 1]}, {'group': 2},
                 {'dilations': [2]}, {'auto_pad': 'SAME_UPPER'}, {'kernel_shape': [3]}]
        for attributes in cases:
            with self.subTest(attributes=attributes):
                model = self.convolution(**attributes)
                before = model.SerializeToString()
                self.assertEqual(int4.rewrite_pointwise_convolutions(model)['converted_convs'], 0)
                self.assertEqual(model.SerializeToString(), before)
        model = self.convolution()
        model.graph.initializer[0].CopyFrom(numpy_helper.from_array(np.zeros((8, 64, 3), dtype=np.float32), 'weight'))
        self.assertEqual(int4.rewrite_pointwise_convolutions(model)['converted_convs'], 0)

    def test_pointwise_only_graph_can_be_quantized_without_original_matmul(self):
        self.save(self.convolution())
        report = int4.quantize_model(self.source, self.output, block_size=64,
                                     quantize_pointwise_conv=True)
        self.assertEqual(report['pointwise_conv_rewrite']['converted_convs'], 1)
        self.assertEqual(report['output_operations']['MatMulNBits'], 1)
        self.assertNotIn('Conv', report['output_operations'])

    def test_real_block32_export_loads_and_preserves_approximate_computation(self):
        self.save()
        original_hash = int4.sha256(self.source)
        report = int4.quantize_model(self.source, self.output, block_size=32, accuracy_level=4,
                                     expected_sha256=original_hash)
        self.assertEqual(int4.sha256(self.source), original_hash)
        self.assertEqual(report['output_operations']['MatMulNBits'], 1)
        self.assertEqual(report['status'], 'experimental_unvalidated')
        self.assertFalse(report['release_ready'])
        self.assertTrue(self.output.with_suffix('.build.json').exists())
        model = onnx.load(self.output)
        node = next(node for node in model.graph.node if node.op_type == 'MatMulNBits')
        attributes = {item.name: helper.get_attribute_value(item) for item in node.attribute}
        self.assertEqual(node.domain, 'com.microsoft')
        self.assertEqual((attributes['bits'], attributes['block_size'], attributes['accuracy_level']), (4, 32, 4))
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        feeds = {'x': np.random.default_rng(23).normal(size=(2, 64)).astype(np.float32)}
        baseline = ort.InferenceSession(str(self.source), options, providers=['CPUExecutionProvider']).run(None, feeds)[0]
        quantized = ort.InferenceSession(str(self.output), options, providers=['CPUExecutionProvider']).run(None, feeds)[0]
        self.assertEqual(quantized.shape, baseline.shape)
        self.assertTrue(np.isfinite(quantized).all())
        self.assertLess(np.linalg.norm(quantized - baseline) / np.linalg.norm(baseline), .2)
        with self.assertRaisesRegex(ValueError, 'already quantized'):
            int4.quantize_model(self.output, self.root / 'requantized.onnx')

    def test_rejects_qdq_source(self):
        model = self.model()
        model.graph.node[0].output[0] = 'prequantized'
        model.graph.node.extend([
            helper.make_node('QuantizeLinear', ['prequantized', 'scale', 'zero'], ['q']),
            helper.make_node('DequantizeLinear', ['q', 'scale', 'zero'], ['y']),
        ])
        model.graph.initializer.extend([
            numpy_helper.from_array(np.array(.1, np.float32), 'scale'),
            numpy_helper.from_array(np.array(0, np.uint8), 'zero'),
        ])
        onnx.checker.check_model(model)
        with self.assertRaisesRegex(ValueError, 'already quantized'):
            int4.validate_fp32_source(model)

    def test_rejects_other_quantized_operators_alongside_float_matmul(self):
        for operation in ('QGemm', 'QAttention', 'QEmbedLayerNormalization',
                          'QOrderedMatMul', 'DynamicQuantizeLSTM'):
            with self.subTest(operation=operation):
                model = self.model()
                model.opset_import.append(helper.make_opsetid('com.microsoft', 1))
                model.graph.node.append(helper.make_node(
                    operation, ['x', 'weight'], ['quantized_branch'], domain='com.microsoft'))
                onnx.checker.check_model(model)
                with self.assertRaisesRegex(ValueError, f'already quantized \\({operation}\\)'):
                    int4.validate_fp32_source(model)

    def test_both_builders_refuse_existing_qgemm_without_writing_output(self):
        from quantize_mobile_mixed import build_model
        model = self.model()
        model.opset_import.append(helper.make_opsetid('com.microsoft', 1))
        model.graph.input.append(helper.make_tensor_value_info('quantized_input', TensorProto.UINT8, [2, 64]))
        model.graph.initializer.extend([
            numpy_helper.from_array(np.ones((64, 8), dtype=np.int8), 'packed_weight'),
            numpy_helper.from_array(np.array(.1, dtype=np.float32), 'input_scale'),
            numpy_helper.from_array(np.array(0, dtype=np.uint8), 'input_zero'),
            numpy_helper.from_array(np.array(.01, dtype=np.float32), 'weight_scale'),
            numpy_helper.from_array(np.array(0, dtype=np.int8), 'weight_zero'),
        ])
        model.graph.node.append(helper.make_node(
            'QGemm', ['quantized_input', 'input_scale', 'input_zero', 'packed_weight',
                      'weight_scale', 'weight_zero'], ['quantized_output'], domain='com.microsoft'))
        model.graph.output.append(helper.make_tensor_value_info('quantized_output', TensorProto.FLOAT, [2, 8]))
        onnx.checker.check_model(model)
        self.save(model)
        original = self.source.read_bytes()
        for build in (int4.quantize_model, build_model):
            with self.subTest(builder=build.__module__):
                with self.assertRaisesRegex(ValueError, 'already quantized \\(QGemm\\)'):
                    build(self.source, self.output)
                self.assertFalse(self.output.exists())
                self.assertEqual(self.source.read_bytes(), original)

    def test_rejects_quantization_in_local_function_body(self):
        model = self.model()
        function = helper.make_function(
            'fixture', 'AttentionBlock', ['input'], ['output'],
            [helper.make_node('QAttention', ['input'], ['output'], domain='com.microsoft')],
            opset_imports=[helper.make_opsetid('com.microsoft', 1)])
        model.functions.append(function)
        model.opset_import.append(helper.make_opsetid('fixture', 1))
        model.graph.node.append(helper.make_node('AttentionBlock', ['x'], ['function_result'], domain='fixture'))
        onnx.checker.check_model(model)
        with self.assertRaisesRegex(ValueError, 'already quantized \\(QAttention\\)'):
            int4.validate_fp32_source(model)

    def test_rejects_low_bit_weight_hidden_behind_cast(self):
        for dtype in (np.int8, np.uint8):
            with self.subTest(dtype=dtype):
                model = self.model()
                model.graph.node[0].output[0] = 'float_projection'
                model.graph.initializer.append(numpy_helper.from_array(np.eye(8, dtype=dtype), 'low_bit_weight'))
                model.graph.node.extend([
                    helper.make_node('Cast', ['low_bit_weight'], ['cast_weight'], to=TensorProto.FLOAT),
                    helper.make_node('MatMul', ['float_projection', 'cast_weight'], ['y']),
                ])
                onnx.checker.check_model(model)
                with self.assertRaisesRegex(ValueError, 'already quantized \\(low-bit tensor'):
                    int4.validate_fp32_source(model)

    def test_rejects_low_bit_constant_in_function_body(self):
        model = self.model()
        function = helper.make_function(
            'fixture', 'LowBitConstant', [], ['output'], [
                helper.make_node('Constant', [], ['packed'],
                                 value=numpy_helper.from_array(np.eye(8, dtype=np.int8), 'packed_weight')),
                helper.make_node('Cast', ['packed'], ['output'], to=TensorProto.FLOAT),
            ], opset_imports=[helper.make_opsetid('', 16)])
        model.functions.append(function)
        model.opset_import.append(helper.make_opsetid('fixture', 1))
        model.graph.node.append(helper.make_node('LowBitConstant', [], ['function_weight'], domain='fixture'))
        onnx.checker.check_model(model)
        with self.assertRaisesRegex(ValueError, 'already quantized \\(low-bit tensor'):
            int4.validate_fp32_source(model)

    def test_rejects_quantization_in_nested_graph(self):
        model = self.model()
        model.opset_import.append(helper.make_opsetid('com.microsoft', 1))
        quantized = helper.make_graph(
            [helper.make_node('QGemm', ['x', 'weight'], ['branch_out'], domain='com.microsoft')],
            'quantized_branch', [], [helper.make_tensor_value_info('branch_out', TensorProto.FLOAT, [2, 8])])
        floating = helper.make_graph(
            [helper.make_node('Identity', ['y'], ['branch_out'])], 'float_branch', [],
            [helper.make_tensor_value_info('branch_out', TensorProto.FLOAT, [2, 8])])
        model.graph.input.append(helper.make_tensor_value_info('condition', TensorProto.BOOL, []))
        model.graph.node.append(helper.make_node('If', ['condition'], ['selected'],
                                                then_branch=quantized, else_branch=floating))
        onnx.checker.check_model(model)
        with self.assertRaisesRegex(ValueError, 'already quantized \\(QGemm\\)'):
            int4.validate_fp32_source(model)

    def test_accepts_integer_shape_and_index_constants(self):
        model = self.model()
        model.graph.node[0].output[0] = 'projected'
        model.graph.initializer.extend([
            numpy_helper.from_array(np.array([2, 8], dtype=np.int64), 'shape'),
            numpy_helper.from_array(np.array([0], dtype=np.int32), 'indices'),
        ])
        model.graph.node.extend([
            helper.make_node('Reshape', ['projected', 'shape'], ['y']),
            helper.make_node('Gather', ['shape', 'indices'], ['first_dimension']),
        ])
        onnx.checker.check_model(model)
        before = model.SerializeToString()
        self.assertEqual(int4.validate_fp32_source(model)['eligible_matmuls'], 1)
        self.assertEqual(model.SerializeToString(), before)

    def test_rejects_non_fp32_weight(self):
        model = self.model()
        tensor = model.graph.initializer[0]
        tensor.CopyFrom(numpy_helper.from_array(np.ones((64, 8), dtype=np.float16), 'weight'))
        with self.assertRaisesRegex(ValueError, 'not original FP32'):
            int4.validate_fp32_source(model)

    def test_rejects_external_weights(self):
        model = self.model()
        tensor = model.graph.initializer[0]
        tensor.ClearField('raw_data')
        tensor.data_location = TensorProto.EXTERNAL
        external = tensor.external_data.add()
        external.key, external.value = 'location', 'weights.bin'
        with self.assertRaisesRegex(ValueError, 'external tensors'):
            int4.validate_fp32_source(model)

    def test_export_loads_and_fingerprints_verified_external_weights(self):
        from quantize_language_model import artifact_record
        model = self.model()
        onnx.save_model(model, self.source, save_as_external_data=True, all_tensors_to_one_file=True,
                        location='model.weights', size_threshold=0)
        expected = artifact_record(self.source)
        report = int4.quantize_model(self.source, self.output, expected_artifact_sha256=expected['artifact_sha256'])
        self.assertEqual(report['source_bytes'], expected['size_bytes'])
        self.assertEqual(len(report['source_artifact']['files']), 2)
        weights = self.root / 'model.weights'
        data = bytearray(weights.read_bytes()); data[0] ^= 1; weights.write_bytes(data)
        with self.assertRaisesRegex(ValueError, 'artifact SHA256'):
            int4.quantize_model(self.source, self.root / 'rejected.onnx',
                                expected_artifact_sha256=expected['artifact_sha256'])

    def test_rejects_graph_without_constant_weights(self):
        model = self.model()
        model.graph.input.append(helper.make_tensor_value_info('weight', TensorProto.FLOAT, [64, 8]))
        del model.graph.initializer[:]
        with self.assertRaisesRegex(ValueError, 'No constant'):
            int4.validate_fp32_source(model)

    def test_source_hash_mismatch_and_output_guardrails(self):
        self.save()
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            int4.quantize_model(self.source, self.output, expected_sha256='0' * 64)
        self.assertFalse(self.output.exists())
        with self.assertRaisesRegex(ValueError, 'overwrite'):
            int4.quantize_model(self.source, self.source)
        for keyword, value in [('block_size', 24), ('accuracy_level', 2)]:
            with self.subTest(keyword=keyword):
                with self.assertRaises(ValueError):
                    int4.quantize_model(self.source, self.output, **{keyword: value})
        self.output.write_bytes(b'preserve-existing-output')
        with self.assertRaises(FileExistsError):
            int4.quantize_model(self.source, self.output)
        self.assertEqual(self.output.read_bytes(), b'preserve-existing-output')


if __name__ == '__main__':
    unittest.main()
