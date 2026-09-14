import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
	computeFeatures,
	decodeCTC,
	splitAudio,
	joinTranscripts,
} from "../src/lib/transcription/features.ts";
import { readPcmWav, encodeWav } from "../src/lib/transcription/audio.ts";

const arrayBuffer = (data) =>
	data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);

test("real Telugu fixture matches production Rust preprocessing", async () => {
	const audio = readPcmWav(
		arrayBuffer(await readFile("public/audio/telugu_test_audio_16k.wav"))
	);
	assert.equal(audio.length, 367616);
	const { features, frames } = computeFeatures(audio);
	const expected = new Float32Array(
		arrayBuffer(await readFile("tests/fixtures/telugu-16k-features.f32le"))
	);
	assert.equal(frames, 2298);
	assert.equal(features.length, expected.length);
	let maxError = 0;
	for (let i = 0; i < features.length; i++)
		maxError = Math.max(maxError, Math.abs(features[i] - expected[i]));
	assert.ok(maxError < 0.0002, `Native/browser feature difference ${maxError}`);
});

test("CTC retains repeated tokens separated by blanks and applies language mask", () => {
	const rows = new Float32Array([0, 8, 0, 0, 9, 1, 0, 0, 8, 0, 9, 0, 8, 0, 0]);
	assert.equal(
		decodeCTC(rows, 3, { blank: 2, mask: [0, 1, 2], tokens: ["▁మా", "▁వీణ"] }),
		"వీణ వీణ మా"
	);
});

test("silence produces finite normalized features", () => {
	const { features } = computeFeatures(new Float32Array(16000));
	assert.ok(features.every(Number.isFinite));
});

test("long recordings are bounded and preserve overlap; short fixture stays intact", () => {
	const short = new Float32Array(367616);
	assert.equal(splitAudio(short)[0], short);
	const long = Float32Array.from({ length: 16000 * 70 }, (_, i) =>
		Math.sin(i / 10)
	);
	const parts = splitAudio(long);
	assert.ok(parts.length >= 3);
	assert.ok(parts.every((part) => part.length <= 16000 * 25));
	assert.deepEqual(parts[0].slice(-8000), parts[1].slice(0, 8000));
	assert.equal(
		joinTranscripts("మా దుకాణానికి ఒక వ్యక్తి", "ఒక వ్యక్తి వచ్చి పది"),
		"మా దుకాణానికి ఒక వ్యక్తి వచ్చి పది"
	);
});

test("PCM WAV round trip and malformed input", async () => {
	const pcm = Float32Array.from([0, -1, 1, 0.5, -0.5]);
	const decoded = readPcmWav(await encodeWav(pcm).arrayBuffer());
	for (let i = 0; i < pcm.length; i++)
		assert.ok(Math.abs(pcm[i] - decoded[i]) <= 1 / 32768);
	assert.equal(readPcmWav(new ArrayBuffer(4)), undefined);
	const invalid = await encodeWav(pcm).arrayBuffer();
	new DataView(invalid).setUint32(40, 100000, true);
	assert.throws(() => readPcmWav(invalid), /incomplete/);
});
