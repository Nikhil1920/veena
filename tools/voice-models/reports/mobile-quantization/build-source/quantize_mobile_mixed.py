"""Experimental dynamic INT8 for constant MatMul and safe pointwise Conv1d.

Start from verified FP32 weights. Pointwise convolutions are rewritten using the
same tested algebra as quantize_int4; strided and depthwise convolutions stay
floating point. No ConvInteger operators are introduced. Mobile speed and
robustness still require measurements on the target device and held-out speech.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import shutil
import tempfile

import onnx
from onnx.external_data_helper import _get_all_tensors
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic
from onnxruntime.quantization.onnx_model import ONNXModel

from quantize_int4 import graphs, rewrite_pointwise_convolutions, sha256, validate_fp32_source


def find_ctc_head(model: onnx.ModelProto) -> dict:
    """Find a local-vocabulary CTC projection through its exact output path.

    Fail closed unless output [N,T,classes] is LogSoftmax(Transpose(Conv1d)).
    This intentionally does not infer a head from a suggestive node name.
    """
    error = 'Unsupported CTC head contract: expected pointwise Conv -> NTC Transpose -> LogSoftmax'
    if not model.graph.output:
        raise ValueError(error)
    output = model.graph.output[0]
    dimensions = output.type.tensor_type.shape.dim
    if len(dimensions) != 3 or dimensions[2].dim_value <= 1:
        raise ValueError(error)
    classes = dimensions[2].dim_value
    producers = {name: node for node in model.graph.node for name in node.output}
    weights = {tensor.name: tensor for tensor in model.graph.initializer}
    logsoftmax = producers.get(output.name)
    if logsoftmax is None or logsoftmax.op_type != 'LogSoftmax' or len(logsoftmax.input) != 1:
        raise ValueError(error)
    attributes = {a.name: onnx.helper.get_attribute_value(a) for a in logsoftmax.attribute}
    if attributes.get('axis', -1) not in (-1, 2):
        raise ValueError(error)
    transpose = producers.get(logsoftmax.input[0])
    if transpose is None or transpose.op_type != 'Transpose' or len(transpose.input) != 1:
        raise ValueError(error)
    attributes = {a.name: onnx.helper.get_attribute_value(a) for a in transpose.attribute}
    if attributes.get('perm') != [0, 2, 1]:
        raise ValueError(error)
    head = producers.get(transpose.input[0])
    if head is None or head.op_type != 'Conv' or len(head.input) not in (2, 3) or not head.name:
        raise ValueError(error)
    if sum(node.name == head.name for graph in graphs(model.graph) for node in graph.node) != 1:
        raise ValueError(error)
    weight = weights.get(head.input[1])
    attributes = {a.name: onnx.helper.get_attribute_value(a) for a in head.attribute}
    if (head.domain not in ('', 'ai.onnx') or weight is None
            or weight.data_type != onnx.TensorProto.FLOAT or len(weight.dims) != 3
            or list(weight.dims)[::2] != [classes, 1]
            or attributes.get('group', 1) != 1
            or attributes.get('strides', [1]) != [1]
            or attributes.get('dilations', [1]) != [1]
            or attributes.get('pads', [0, 0]) != [0, 0]
            or attributes.get('kernel_shape', [1]) != [1]
            or attributes.get('auto_pad', b'NOTSET') not in (b'NOTSET', 'NOTSET')):
        raise ValueError(error)
    return {'node': head.name, 'weight': weight.name, 'classes': classes,
            'weight_bytes': 4 * classes * weight.dims[1]}


def build_model(source: Path, output: Path, *, verification: Path | None = None,
                keep_ctc_head_float: bool = False) -> dict:
    source, output = source.resolve(), output.resolve()
    if source == output or output.exists():
        raise ValueError('Refusing to replace source or existing output')
    metadata = onnx.load(source, load_external_data=False)
    source_files = {source}
    for tensor in _get_all_tensors(metadata):
        if tensor.data_location == onnx.TensorProto.EXTERNAL:
            locations = [entry.value for entry in tensor.external_data if entry.key == 'location']
            if len(locations) != 1:
                raise ValueError('External tensor must have exactly one location')
            path = (source.parent / locations[0]).resolve()
            if not path.is_relative_to(source.parent):
                raise ValueError('External weights must reside within the model directory')
            source_files.add(path)
    source_assets = [{'path': str(path), 'size_bytes': path.stat().st_size, 'sha256': sha256(path)}
                     for path in sorted(source_files)]
    if verification:
        expected = {row['path']: row for row in json.loads(verification.read_text())['files']}
        for actual in source_assets:
            name = str(Path(actual['path']).relative_to(source.parent))
            reference = expected.get(name)
            if reference is None or (actual['size_bytes'], actual['sha256']) != (reference['size_bytes'], reference['sha256']):
                raise ValueError(f'FP32 source failed size/SHA256 verification: {name}')
    # Cache assets may deliberately share an inode. ONNX refuses external-data
    # hardlinks, so materialize verified bytes privately rather than disabling
    # its external-file checks or modifying the original cache.
    if any(path.stat().st_nlink > 1 for path in source_files):
        with tempfile.TemporaryDirectory(prefix='veena-fp32-copy-') as temporary:
            private = Path(temporary)
            for path in source_files:
                target = private / path.relative_to(source.parent)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
            model = onnx.load(private / source.name, load_external_data=True)
    else:
        model = onnx.load(source, load_external_data=True)
    source_graph = validate_fp32_source(model, require_matmul=False)
    original_io = [value.SerializeToString() for value in [*model.graph.input, *model.graph.output]]
    ctc_head = find_ctc_head(model) if keep_ctc_head_float else None
    rewrite = rewrite_pointwise_convolutions(
        model, exclude_node_names={ctc_head['node']} if ctc_head else None)
    wrapper = ONNXModel(model)
    wrapper.clean_initializers()
    prepared_graph = validate_fp32_source(model)
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_suffix(output.suffix + '.partial')
    try:
        quantize_dynamic(model, str(partial), op_types_to_quantize=['MatMul'], per_channel=True,
                         weight_type=QuantType.QInt8, use_external_data_format=False,
                         extra_options={'MatMulConstBOnly': True})
        quantized = onnx.load(partial)
        actual_io = [value.SerializeToString() for value in [*quantized.graph.input, *quantized.graph.output]]
        if original_io != actual_io:
            raise ValueError('Quantization changed the input/output contract')
        operations = Counter(node.op_type for graph in graphs(quantized.graph) for node in graph.node)
        if operations['ConvInteger'] or operations['QLinearConv']:
            raise ValueError('Unexpected quantized convolution')
        if operations['MatMulInteger'] != prepared_graph['eligible_matmuls']:
            raise ValueError('Unexpected constant MatMul coverage')
        if ctc_head and not any(node.name == ctc_head['node'] and node.op_type == 'Conv'
                                for node in quantized.graph.node):
            raise ValueError('CTC head did not remain a floating-point convolution')
        onnx.checker.check_model(quantized)
        partial.replace(output)
    finally:
        partial.unlink(missing_ok=True)
    report = {'status': 'experimental_unvalidated', 'release_ready': False,
              'recipe': 'dynamic_per_channel_int8_matmul_and_pointwise_conv',
              'keep_ctc_head_float': keep_ctc_head_float, 'float_ctc_head': ctc_head,
              'source_assets': source_assets, 'source_verified': verification is not None,
              'source_bytes': sum(row['size_bytes'] for row in source_assets),
              'source_graph': source_graph, 'pointwise_rewrite': rewrite,
              'prepared_graph': prepared_graph, 'output_operations': dict(operations),
              'output': str(output), 'output_bytes': output.stat().st_size, 'output_sha256': sha256(output),
              'onnxruntime_version': ort.__version__, 'input_output_contract_preserved': True,
              'conv_integer_used': False, 'timing_qualified': False}
    output.with_suffix('.build.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verification', type=Path)
    parser.add_argument('--keep-ctc-head-float', action='store_true',
                        help='Preserve the verified final local-vocabulary CTC Conv in FP32')
    args = parser.parse_args()
    print(json.dumps(build_model(args.source, args.output, verification=args.verification,
                                keep_ctc_head_float=args.keep_ctc_head_float), indent=2))


if __name__ == '__main__':
    main()
