"""Build experimental mobile CPU INT4 MatMul weights directly from FP32 ONNX.

This is a size/quality experiment, not a validated mobile release. By default,
convolution weights remain floating point. --quantize-pointwise-conv rewrites
safe pointwise Conv1d operations to MatMul first; other convolutions and
activation-to-activation MatMuls remain floating point. The output
requires ONNX Runtime's com.microsoft::MatMulNBits CPU kernel; test the exact
Android/iOS runtime build and real held-out speech before choosing it.

accuracy_level=4 permits internal INT8 activation computation when the runtime
supports it. Use accuracy_level=1 to compare FP32 computation with the same INT4
weights. Neither setting establishes a mobile latency improvement by itself.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import logging
from pathlib import Path
import shutil
import tempfile

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

QUANTIZED_OPERATORS = {
    'QuantizeLinear', 'DequantizeLinear', 'DynamicQuantizeLinear', 'MatMulInteger',
    'ConvInteger', 'MatMulNBits', 'GatherBlockQuantized', 'MatMulIntegerToFloat',
    'DynamicQuantizeMatMul', 'QGemm', 'QAttention', 'QEmbedLayerNormalization',
    'QMoE',
}
QUANTIZED_TENSOR_TYPES = {
    getattr(TensorProto, name) for name in (
        'INT8', 'UINT8', 'INT4', 'UINT4', 'INT2', 'UINT2',
        'FLOAT8E4M3FN', 'FLOAT8E4M3FNUZ', 'FLOAT8E5M2', 'FLOAT8E5M2FNUZ',
        'FLOAT8E8M0', 'FLOAT4E2M1',
    ) if hasattr(TensorProto, name)
}


def sha256(path: Path) -> str:
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def _attributes(body):
    for node in body.node:
        yield from node.attribute
    # Function default attributes can also contain tensor or graph values.
    yield from getattr(body, 'attribute_proto', ())


def graphs(graph):
    yield graph
    for attribute in _attributes(graph):
        if attribute.type == onnx.AttributeProto.GRAPH:
            yield from graphs(attribute.g)
        elif attribute.type == onnx.AttributeProto.GRAPHS:
            for subgraph in attribute.graphs:
                yield from graphs(subgraph)


def _body_tensors(body):
    yield from getattr(body, 'initializer', ())
    for sparse in getattr(body, 'sparse_initializer', ()):
        yield sparse.values
        yield sparse.indices
    for attribute in _attributes(body):
        if attribute.HasField('t'):
            yield attribute.t
        yield from attribute.tensors
        if attribute.HasField('sparse_tensor'):
            yield attribute.sparse_tensor.values
            yield attribute.sparse_tensor.indices
        for sparse in attribute.sparse_tensors:
            yield sparse.values
            yield sparse.indices


def validate_fp32_source(model: onnx.ModelProto, *, require_matmul: bool = True) -> dict:
    """Refuse low-bit weights/ops, including constants and local function bodies.

    INT32/INT64 shape and indexing constants remain valid floating-model inputs.
    """
    operations = Counter()
    eligible = 0
    weight_bytes = 0
    bodies = [body for root in (model.graph, *model.functions) for body in graphs(root)]
    for graph in bodies:
        initializers = {value.name: value for value in getattr(graph, 'initializer', ())}
        for node in graph.node:
            operations[node.op_type] += 1
            if (node.op_type in QUANTIZED_OPERATORS or 'quant' in node.op_type.lower()
                    or node.op_type.startswith(('QLinear', 'QOrdered'))):
                raise ValueError(f'Source is already quantized ({node.op_type}); use original FP32 weights')
            if node.op_type == 'MatMul' and len(node.input) >= 2 and node.input[1] in initializers:
                weight = initializers[node.input[1]]
                if weight.data_type != TensorProto.FLOAT:
                    raise ValueError(f'MatMul weight {weight.name} is not original FP32')
                if len(weight.dims) == 2:
                    eligible += 1
                    weight_bytes += 4 * weight.dims[0] * weight.dims[1]
        for tensor in _body_tensors(graph):
            if tensor.external_data:
                raise ValueError('Expected a self-contained FP32 source; external tensors are unsupported')
            if tensor.data_type in QUANTIZED_TENSOR_TYPES:
                raise ValueError(f'Source is already quantized (low-bit tensor {tensor.name!r}); use original FP32 weights')
    if require_matmul and not eligible:
        raise ValueError('No constant two-dimensional FP32 MatMul weights to quantize')
    onnx.checker.check_model(model)
    return {'operations': dict(operations), 'eligible_matmuls': eligible,
            'eligible_weight_bytes': weight_bytes}


def rewrite_pointwise_convolutions(model: onnx.ModelProto, *,
                                   exclude_node_names: set[str] | None = None) -> dict:
    """Replace only provably equivalent float32 pointwise Conv1d operations.

    NCT --Transpose--> NTC --MatMul(C,M)--> NTM --Add(M)--> NTM --Transpose--> NMT.
    Grouped/depthwise, spatial kernels, striding, dilation and padding stay Conv.
    Original initializers are left for the quantizer's unused-weight cleanup.
    """
    converted, weight_bytes, skipped = [], 0, 0
    for graph in list(graphs(model.graph)):
        initializers = {value.name: value for value in graph.initializer}
        used = {value.name for value in [*graph.input, *graph.output, *graph.initializer]}
        used.update(name for node in graph.node for name in [node.name, *node.input, *node.output])

        def fresh(stem):
            name, counter = stem, 0
            while name in used:
                counter += 1
                name = f'{stem}_{counter}'
            used.add(name)
            return name

        nodes = []
        for node in graph.node:
            if node.op_type != 'Conv':
                nodes.append(node)
                continue
            if exclude_node_names and node.name in exclude_node_names:
                nodes.append(node)
                skipped += 1
                continue
            attributes = {item.name: helper.get_attribute_value(item) for item in node.attribute}
            weight = initializers.get(node.input[1]) if len(node.input) >= 2 else None
            safe = (
                node.domain in ('', 'ai.onnx') and len(node.input) in (2, 3) and len(node.output) == 1
                and weight is not None and weight.data_type == TensorProto.FLOAT
                and len(weight.dims) == 3 and weight.dims[2] == 1
                and attributes.get('group', 1) == 1
                and attributes.get('strides', [1]) == [1]
                and attributes.get('dilations', [1]) == [1]
                and attributes.get('pads', [0, 0]) == [0, 0]
                and attributes.get('kernel_shape', [1]) == [1]
                and attributes.get('auto_pad', b'NOTSET') in (b'NOTSET', 'NOTSET')
            )
            bias_name = node.input[2] if len(node.input) == 3 else ''
            if safe and bias_name:
                bias = initializers.get(bias_name)
                safe = bias is not None and bias.data_type == TensorProto.FLOAT and list(bias.dims) == [weight.dims[0]]
            if not safe:
                nodes.append(node)
                skipped += 1
                continue
            stem = fresh(f'{node.name or "Conv"}_pointwise')
            matrix_name = fresh(f'{stem}_weight')
            matrix = np.ascontiguousarray(numpy_helper.to_array(weight)[:, :, 0].T)
            graph.initializer.append(numpy_helper.from_array(matrix, matrix_name))
            channels_last, multiplied = fresh(f'{stem}_ntc'), fresh(f'{stem}_matmul')
            nodes.append(helper.make_node('Transpose', [node.input[0]], [channels_last], name=fresh(f'{stem}_to_ntc'), perm=[0, 2, 1]))
            nodes.append(helper.make_node('MatMul', [channels_last, matrix_name], [multiplied], name=fresh(f'{stem}_projection')))
            result_name = multiplied
            if bias_name:
                result_name = fresh(f'{stem}_biased')
                nodes.append(helper.make_node('Add', [multiplied, bias_name], [result_name], name=fresh(f'{stem}_bias')))
            nodes.append(helper.make_node('Transpose', [result_name], list(node.output), name=fresh(f'{stem}_to_nct'), perm=[0, 2, 1]))
            converted.append(node.name)
            weight_bytes += matrix.nbytes
        del graph.node[:]
        graph.node.extend(nodes)
    onnx.checker.check_model(model)
    return {'converted_convs': len(converted), 'converted_weight_bytes': weight_bytes,
            'converted_nodes': converted, 'untouched_convs': skipped}


def quantize_model(source: str | Path, output: str | Path, *, block_size: int = 32,
                   accuracy_level: int = 4, expected_sha256: str | None = None,
                   quantize_pointwise_conv: bool = False,
                   expected_artifact_sha256: str | None = None) -> dict:
    source, output = Path(source).resolve(), Path(output).resolve()
    if block_size not in (32, 64):
        raise ValueError('Experimental block size must be 32 or 64')
    if accuracy_level not in (1, 4):
        raise ValueError('Accuracy level must be 1 (FP32 compute) or 4 (allow INT8 compute)')
    if source == output:
        raise ValueError('Output must not overwrite the FP32 source')
    if output.exists():
        raise FileExistsError(f'Output already exists: {output}')
    from quantize_language_model import artifact_record
    source_artifact = artifact_record(source)
    source_sha = source_artifact['sha256']
    if expected_sha256 is not None and source_sha != expected_sha256:
        raise ValueError('FP32 source failed SHA256 verification')
    if expected_artifact_sha256 is not None and source_artifact['artifact_sha256'] != expected_artifact_sha256:
        raise ValueError('FP32 source graph/weights failed artifact SHA256 verification')
    paths = [(source.parent / item['path']).resolve() for item in source_artifact['files']]
    if any(not path.is_relative_to(source.parent) for path in paths):
        raise ValueError('External weights must reside within the model directory')
    if any(path.stat().st_nlink > 1 for path in paths):
        with tempfile.TemporaryDirectory(prefix='veena-int4-fp32-') as temporary:
            private = Path(temporary)
            for path in paths:
                target = private / path.relative_to(source.parent)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
            model = onnx.load(private / source.name, load_external_data=True)
    else:
        model = onnx.load(source, load_external_data=True)
    source_info = validate_fp32_source(model, require_matmul=not quantize_pointwise_conv)
    original_io = [item.SerializeToString() for item in [*model.graph.input, *model.graph.output]]
    rewrite = rewrite_pointwise_convolutions(model) if quantize_pointwise_conv else None
    prepared_info = validate_fp32_source(model) if rewrite is not None else source_info
    try:
        import onnxruntime as ort
        from onnxruntime.quantization.matmul_nbits_quantizer import MatMulNBitsQuantizer
        from onnxruntime.quantization.quant_utils import QuantFormat
    except ModuleNotFoundError as error:
        raise RuntimeError('INT4 export requires ONNX Runtime with MatMulNBits quantization and onnx-ir') from error
    logging.getLogger('onnxruntime.quantization.matmul_nbits_quantizer').setLevel(logging.WARNING)
    quantizer = MatMulNBitsQuantizer(model, bits=4, block_size=block_size,
                                   is_symmetric=True, accuracy_level=accuracy_level,
                                   quant_format=QuantFormat.QOperator,
                                   op_types_to_quantize=('MatMul',))
    quantizer.process()
    result = quantizer.model.model
    actual_io = [item.SerializeToString() for item in [*result.graph.input, *result.graph.output]]
    if original_io != actual_io:
        raise ValueError('Quantization unexpectedly changed input/output contracts')
    operations = Counter(node.op_type for graph in graphs(result.graph) for node in graph.node)
    if operations['MatMulNBits'] != prepared_info['eligible_matmuls']:
        raise ValueError('Quantization did not convert the expected constant MatMul weights')
    onnx.checker.check_model(result)
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_suffix(output.suffix + '.partial')
    try:
        quantizer.model.save_model_to_file(str(partial), use_external_data_format=False)
        partial.replace(output)
    finally:
        partial.unlink(missing_ok=True)
    report = {
        'status': 'experimental_unvalidated', 'release_ready': False,
        'source': str(source), 'source_sha256': source_sha, 'source_artifact': source_artifact,
        'source_bytes': source_artifact['size_bytes'],
        'output': str(output), 'output_sha256': sha256(output), 'output_bytes': output.stat().st_size,
        'size_fraction_of_fp32': output.stat().st_size / source_artifact['size_bytes'],
        'method': 'FP32 to symmetric blockwise INT4 constant-MatMul weights',
        'block_size': block_size, 'accuracy_level': accuracy_level,
        'activation_compute': 'runtime may use INT8 internally' if accuracy_level == 4 else 'FP32',
        'pointwise_conv_rewrite': rewrite, 'input_output_contract_preserved': True,
        'source_graph': source_info, 'prepared_graph': prepared_info, 'output_operations': dict(operations),
        'onnx_version': onnx.__version__, 'onnxruntime_version': ort.__version__,
        'required_operator': 'com.microsoft::MatMulNBits',
        'validation': 'ONNX checker and unchanged I/O; real mobile latency/quality unvalidated',
    }
    output.with_suffix('.build.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--block-size', type=int, choices=(32, 64), default=32)
    parser.add_argument('--accuracy-level', type=int, choices=(1, 4), default=4)
    parser.add_argument('--expected-sha256')
    parser.add_argument('--expected-artifact-sha256', help='Fingerprint of the ONNX graph and every external weight file')
    parser.add_argument('--quantize-pointwise-conv', action='store_true', help='Rewrite safe FP32 pointwise Conv1d as MatMul before INT4 quantization')
    args = parser.parse_args()
    report = quantize_model(args.source, args.output, block_size=args.block_size,
                            accuracy_level=args.accuracy_level, expected_sha256=args.expected_sha256,
                            quantize_pointwise_conv=args.quantize_pointwise_conv,
                            expected_artifact_sha256=args.expected_artifact_sha256)
    print(json.dumps(report, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
