// The speech engine for in-place keyboard dictation: Veena's model on ONNX
// Runtime Web, in a worker of the keyboard's hidden web view. It runs here
// because iOS caps a keyboard's own memory far below what the model needs,
// while the web view's content process has room for it.

import * as ort from "./ort/ort.wasm.min.mjs";
import { computeFeatures, decodeCTC, joinTranscripts, splitAudio } from "./features.js";
import { externalize } from "./onnx.js";

ort.env.wasm.wasmPaths = {
	mjs: new URL("./ort/ort-wasm-simd-threaded.mjs", import.meta.url).href,
	wasm: new URL("./ort/ort-wasm-simd-threaded.wasm", import.meta.url).href,
};
// One thread: more need shared memory, which a web view doesn't give a page
// served from the keyboard's own scheme.
ort.env.wasm.numThreads = 1;
ort.env.wasm.proxy = false;
ort.env.logLevel = "error";

/** The model arrives in ranges, so the keyboard never holds more than one. */
const RANGE_BYTES = 4 * 1024 * 1024;
/**
 * Shorter than the apps' 25 s windows: the encoder's memory grows with the
 * square of a window's length, and what it takes is never given back.
 */
const WINDOW_SECONDS = 15;

let session;
let loadedKey;
let decoder;

async function fetchRanges(file) {
	const bytes = new Uint8Array(file.size);
	for (let start = 0; start < file.size; start += RANGE_BYTES) {
		const end = Math.min(file.size, start + RANGE_BYTES);
		const response = await fetch(`${file.url}?start=${start}&end=${end}`);
		if (!response.ok) throw new Error(`Model read failed (${response.status})`);
		const part = new Uint8Array(await response.arrayBuffer());
		if (part.length !== end - start) throw new Error("Model read came back short");
		bytes.set(part, start);
	}
	return bytes;
}

async function fetchText(url) {
	const response = await fetch(url);
	if (!response.ok) throw new Error(`Model read failed (${response.status})`);
	return response.text();
}

/** `language`'s tokens (and classes, for the multilingual model). */
async function readDecoder(model, language) {
	const blank = JSON.parse(await fetchText(model.config)).blank_idx ?? 5632;
	if (!model.masks) {
		const tokens = (await fetchText(model.vocab))
			.trimEnd()
			.split(/\r?\n/)
			.slice(1)
			.map((line) => line.split("\t")[0]);
		return { blank, tokens };
	}
	const vocab = JSON.parse(await fetchText(model.vocab));
	const raw = JSON.parse(await fetchText(model.masks))[language];
	if (!raw?.length || !vocab[language]) throw new Error("The model lacks this language");
	const mask = typeof raw[0] === "boolean" ? raw.flatMap((on, i) => (on ? [i] : [])) : raw;
	return { blank, tokens: vocab[language], mask };
}

async function load({ model, language }) {
	const started = performance.now();
	const key = `${model.key}:${language}`;
	if (session && loadedKey === key) return { reused: true };
	if (session && loadedKey?.split(":")[0] !== model.key) {
		await session.release();
		session = undefined;
	}
	loadedKey = undefined;
	decoder = await readDecoder(model, language);
	if (!session) {
		const bytes = await fetchRanges(model.onnx);
		// The weights are read out of `bytes` in place (see onnx.js).
		session = await ort.InferenceSession.create(externalize(bytes, "model.onnx"), {
			executionProviders: ["wasm"],
			graphOptimizationLevel: "all",
			externalData: [{ path: "model.onnx", data: bytes }],
			// Pre-packed weights are a second copy of the model in memory.
			extra: { session: { disable_prepacking: "1" } },
		});
		// Give the file's bytes back now rather than whenever they are collected.
		bytes.buffer.transfer?.(0);
	}
	loadedKey = key;
	return {
		reused: false,
		ms: Math.round(performance.now() - started),
	};
}

async function transcribe({ audio }) {
	if (!session || !decoder) throw new Error("The model isn't loaded");
	const started = performance.now();
	const parts = splitAudio(audio, WINDOW_SECONDS);
	let text = "";
	for (const part of parts) {
		let energy = 0;
		for (const value of part) energy += value * value;
		if (energy / part.length < 1e-9) continue;
		const { features, frames } = computeFeatures(part);
		const signal = new ort.Tensor("float32", features, [1, 80, frames]);
		const length = new ort.Tensor("int64", BigInt64Array.of(BigInt(frames)), [1]);
		let output;
		try {
			output = await session.run({ audio_signal: signal, length });
			const logits = output[session.outputNames[0]];
			text = joinTranscripts(text, decodeCTC(logits.data, logits.dims[2], decoder));
		} finally {
			signal.dispose();
			length.dispose();
			if (output) for (const tensor of Object.values(output)) tensor.dispose();
		}
	}
	return {
		text,
		ms: Math.round(performance.now() - started),
		seconds: audio.length / 16000,
		parts: parts.length,
	};
}

// One request at a time, in order: a transcription asked for while the model
// is still loading waits for it.
let queue = Promise.resolve();
self.onmessage = ({ data: request }) => {
	queue = queue.then(async () => {
		try {
			const result = request.type === "load" ? await load(request) : await transcribe(request);
			self.postMessage({ id: request.id, ok: true, result });
		} catch (error) {
			self.postMessage({
				id: request.id,
				ok: false,
				message: error instanceof Error ? error.message : String(error),
			});
		}
	});
};
