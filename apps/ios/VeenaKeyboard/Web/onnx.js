// Rewrites an ONNX model so its weights are read from where they already are.
//
// Veena's models keep their weights inside the .onnx file. Loaded as they
// come, the runtime copies the whole file into its memory and then copies the
// weights out of that copy. `externalize` returns the same model with every
// large weight replaced by a reference (file, offset, length) back into the
// original bytes; mounted as the model's external data, those bytes are then
// copied once. For the Telugu model that takes the engine's peak from about
// 800 MB to about half.
//
// Only the protobuf fields involved are understood (onnx.proto):
//   ModelProto.graph = 7, GraphProto.initializer = 5,
//   TensorProto.raw_data = 9, .external_data = 13, .data_location = 14.

const GRAPH = 7;
const INITIALIZER = 5;
const RAW_DATA = 9;
const EXTERNAL_DATA = 13;
const DATA_LOCATION = 14;
const LENGTH_DELIMITED = 2;
/** Smaller weights stay where they are: a reference costs about as much. */
const SMALLEST_MOVED = 1024;

/** The varint at `at` and the position after it. */
function readVarint(bytes, at) {
	let value = 0;
	let scale = 1;
	for (;;) {
		const byte = bytes[at++];
		value += (byte & 0x7f) * scale;
		if (byte < 0x80) return [value, at];
		scale *= 128;
	}
}

function writeVarint(value) {
	const out = [];
	while (value >= 128) {
		out.push((value % 128) | 0x80);
		value = Math.floor(value / 128);
	}
	out.push(value);
	return Uint8Array.from(out);
}

/** The fields of the message at [start, end): each one's number, wire type and extent. */
function* fields(bytes, start, end) {
	let at = start;
	while (at < end) {
		const fieldStart = at;
		let key;
		[key, at] = readVarint(bytes, at);
		const number = Math.floor(key / 8);
		const wire = key % 8;
		let dataStart = at;
		if (wire === 0) {
			[, at] = readVarint(bytes, at);
		} else if (wire === 1) {
			at += 8;
		} else if (wire === LENGTH_DELIMITED) {
			let size;
			[size, at] = readVarint(bytes, at);
			dataStart = at;
			at += size;
		} else if (wire === 5) {
			at += 4;
		} else {
			throw new Error("The model file isn't in a format the keyboard reads");
		}
		if (at > end) throw new Error("The model file is cut short");
		yield { number, wire, fieldStart, dataStart, dataEnd: at };
	}
}

/** `parts` as one length-delimited field. Field numbers here fit one key byte. */
function wrap(number, parts) {
	const size = parts.reduce((sum, part) => sum + part.length, 0);
	return [Uint8Array.of((number << 3) | LENGTH_DELIMITED), writeVarint(size), ...parts];
}

const encoder = new TextEncoder();

/** One TensorProto.external_data entry: a StringStringEntryProto { key = 1, value = 2 }. */
function entry(key, value) {
	return wrap(EXTERNAL_DATA, [
		...wrap(1, [encoder.encode(key)]),
		...wrap(2, [encoder.encode(String(value))]),
	]);
}

/**
 * The model in `bytes` with its large weights read from `location` instead:
 * mount `bytes` under that name as the model's external data.
 */
export function externalize(bytes, location) {
	const model = [];
	for (const top of fields(bytes, 0, bytes.length)) {
		if (top.number !== GRAPH || top.wire !== LENGTH_DELIMITED) {
			model.push(bytes.subarray(top.fieldStart, top.dataEnd));
			continue;
		}
		const graph = [];
		for (const item of fields(bytes, top.dataStart, top.dataEnd)) {
			if (item.number !== INITIALIZER || item.wire !== LENGTH_DELIMITED) {
				graph.push(bytes.subarray(item.fieldStart, item.dataEnd));
				continue;
			}
			const tensor = [];
			for (const field of fields(bytes, item.dataStart, item.dataEnd)) {
				const size = field.dataEnd - field.dataStart;
				if (field.number === RAW_DATA && field.wire === LENGTH_DELIMITED && size >= SMALLEST_MOVED) {
					tensor.push(
						...entry("location", location),
						...entry("offset", field.dataStart),
						...entry("length", size),
						Uint8Array.of(DATA_LOCATION << 3, 1) // data_location = EXTERNAL
					);
				} else {
					tensor.push(bytes.subarray(field.fieldStart, field.dataEnd));
				}
			}
			graph.push(...wrap(INITIALIZER, tensor));
		}
		model.push(...wrap(GRAPH, graph));
	}
	const out = new Uint8Array(model.reduce((sum, part) => sum + part.length, 0));
	let at = 0;
	for (const part of model) {
		out.set(part, at);
		at += part.length;
	}
	return out;
}
