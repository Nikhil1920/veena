// The iOS keyboard runs a port of this app's pipeline in its hidden web view
// (apps/ios/VeenaKeyboard/Web). These tests hold the port to the same
// fixtures, and cover the ONNX rewrite only the keyboard needs.
import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
	computeFeatures,
	decodeCTC,
	splitAudio,
	joinTranscripts,
} from "../../ios/VeenaKeyboard/Web/features.js";
import { externalize } from "../../ios/VeenaKeyboard/Web/onnx.js";
import { readPcmWav } from "../src/lib/transcription/audio.ts";

const arrayBuffer = (data) =>
	data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);

test("keyboard features match production Rust preprocessing", async () => {
	const audio = readPcmWav(
		arrayBuffer(await readFile("public/audio/telugu_test_audio_16k.wav"))
	);
	const { features, frames } = computeFeatures(audio);
	const expected = new Float32Array(
		arrayBuffer(await readFile("tests/fixtures/telugu-16k-features.f32le"))
	);
	assert.equal(frames, 2298);
	assert.equal(features.length, expected.length);
	let maxError = 0;
	for (let i = 0; i < features.length; i++)
		maxError = Math.max(maxError, Math.abs(features[i] - expected[i]));
	assert.ok(maxError < 0.0002, `Native/keyboard feature difference ${maxError}`);
});

test("keyboard decoding, windows and stitching match the web app", () => {
	const rows = new Float32Array([0, 8, 0, 0, 9, 1, 0, 0, 8, 0, 9, 0, 8, 0, 0]);
	assert.equal(
		decodeCTC(rows, 3, { blank: 2, mask: [0, 1, 2], tokens: ["▁మా", "▁వీణ"] }),
		"వీణ వీణ మా"
	);
	const long = Float32Array.from({ length: 16000 * 70 }, (_, i) =>
		Math.sin(i / 10)
	);
	const parts = splitAudio(long, 15);
	assert.ok(parts.every((part) => part.length <= 16000 * 15));
	assert.deepEqual(parts[0].slice(-8000), parts[1].slice(0, 8000));
	assert.equal(
		joinTranscripts("మా దుకాణానికి ఒక వ్యక్తి", "ఒక వ్యక్తి వచ్చి పది"),
		"మా దుకాణానికి ఒక వ్యక్తి వచ్చి పది"
	);
});

// A minimal protobuf writer and reader for the model the next test builds.
const encoder = new TextEncoder();
const decoder = new TextDecoder();
const varint = (value) => {
	const out = [];
	while (value >= 128) {
		out.push((value % 128) | 128);
		value = Math.floor(value / 128);
	}
	out.push(value);
	return out;
};
const field = (number, body) => [(number << 3) | 2, ...varint(body.length), ...body];
const readVarint = (bytes, at) => {
	let value = 0;
	let scale = 1;
	for (;;) {
		const byte = bytes[at++];
		value += (byte & 127) * scale;
		if (byte < 128) return [value, at];
		scale *= 128;
	}
};
function* fields(bytes, start, end) {
	let at = start;
	while (at < end) {
		let key;
		[key, at] = readVarint(bytes, at);
		let from = at;
		if ((key & 7) === 0) [, at] = readVarint(bytes, at);
		else {
			let size;
			[size, at] = readVarint(bytes, at);
			from = at;
			at += size;
		}
		yield { number: key >> 3, from, to: at };
	}
}

test("externalize points large weights back into the model file", () => {
	const large = Array.from({ length: 5000 }, (_, i) => i % 251);
	const small = [1, 2, 3, 4];
	const tensor = (name, raw) =>
		field(5, [8, 2, 16, 3, ...field(8, [...encoder.encode(name)]), ...field(9, raw)]);
	const graph = [
		...field(1, field(1, [...encoder.encode("node")])),
		...tensor("large", large),
		...tensor("small", small),
		...field(2, [...encoder.encode("graph")]),
	];
	const model = Uint8Array.from([
		8,
		8,
		...field(2, [...encoder.encode("veena")]),
		...field(7, graph),
		...field(8, field(1, [])),
	]);

	const rewritten = externalize(model, "model.onnx");
	assert.ok(rewritten.length < 200, "the large weight left the model");

	const tensors = {};
	for (const top of fields(rewritten, 0, rewritten.length)) {
		if (top.number !== 7) continue;
		for (const item of fields(rewritten, top.from, top.to)) {
			if (item.number !== 5) continue;
			const found = { external: {} };
			for (const part of fields(rewritten, item.from, item.to)) {
				const body = rewritten.subarray(part.from, part.to);
				if (part.number === 8) found.name = decoder.decode(body);
				if (part.number === 9) found.raw = [...body];
				if (part.number === 14) found.location = body[0];
				if (part.number === 13) {
					const [key, value] = [...fields(rewritten, part.from, part.to)].map((entry) =>
						decoder.decode(rewritten.subarray(entry.from, entry.to))
					);
					found.external[key] = value;
				}
			}
			tensors[found.name] = found;
		}
	}

	assert.deepEqual(tensors.small.raw, small);
	assert.equal(tensors.small.location, undefined);
	assert.equal(tensors.large.raw, undefined);
	assert.equal(tensors.large.location, 1);
	assert.equal(tensors.large.external.location, "model.onnx");
	const offset = Number(tensors.large.external.offset);
	const length = Number(tensors.large.external.length);
	assert.deepEqual([...model.subarray(offset, offset + length)], large);
});
