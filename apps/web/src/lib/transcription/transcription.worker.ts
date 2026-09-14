/// <reference lib="webworker" />
import * as ort from "onnxruntime-web/wasm";
import {
	computeFeatures,
	decodeCTC,
	joinTranscripts,
	splitAudio,
	type Decoder,
} from "./features";
import { loadModelFiles } from "./model-storage";
import { MODELS, type ModelId, type Progress } from "./models";

ort.env.wasm.wasmPaths = {
	mjs: new URL("/runtime/ort-wasm-simd-threaded.mjs", self.location.origin)
		.href,
	wasm: new URL("/runtime/ort-wasm-simd-threaded.wasm", self.location.origin)
		.href,
};
ort.env.wasm.numThreads = self.crossOriginIsolated
	? Math.min(4, navigator.hardwareConcurrency || 2)
	: 1;
ort.env.wasm.proxy = false;
ort.env.logLevel = "error";

let session: ort.InferenceSession | undefined;
let modelId: ModelId | undefined;
let files: Record<string, File> = {};
let blank = 5632;
let controller = new AbortController();
let busy = false;

async function decoder(language: string): Promise<Decoder> {
	if (modelId === "telugu") {
		if (language !== "te")
			throw new Error("Download the multilingual model for this language.");
		const tokens = (await files["tokenizer_te.vocab"].text())
			.trimEnd()
			.split(/\r?\n/)
			.slice(1)
			.map((line) => line.split("\t")[0]);
		return { blank, tokens };
	}
	const vocab = JSON.parse(await files["vocab.json"].text());
	const masks = JSON.parse(await files["language_masks.json"].text());
	const raw = masks[language] as number[] | boolean[];
	if (!raw?.length || !vocab[language])
		throw new Error("This model does not support the selected language.");
	const mask =
		typeof raw[0] === "boolean"
			? raw.flatMap((value, i) => (value ? [i] : []))
			: (raw as number[]);
	return { blank, tokens: vocab[language], mask };
}

self.onmessage = async (event: MessageEvent) => {
	const request = event.data;
	if (request.type === "abort") {
		controller.abort();
		return;
	}
	if (busy) {
		self.postMessage({
			id: request.id,
			type: "error",
			error: "Another operation is still running.",
		});
		return;
	}
	busy = true;
	const progress = (value: Progress) =>
		self.postMessage({ id: request.id, type: "progress", ...value });
	try {
		if (request.type === "load") {
			const id = request.model as ModelId;
			if (!MODELS[id]) throw new Error("Unknown model.");
			controller = new AbortController();
			const started = performance.now();
			if (modelId !== id || !session) {
				await session?.release();
				session = undefined;
				modelId = undefined;
				const loaded = await loadModelFiles(
					id,
					progress,
					controller.signal,
					request.allowDownload === true
				);
				files = loaded.files;
				blank =
					JSON.parse(await files["decoder_config.json"].text()).blank_idx ??
					5632;
				progress({
					phase: loaded.cached ? "Loading saved model" : "Loading model",
				});
				const file = files[MODELS[id].files[0]];
				const bytes = await file.arrayBuffer();
				session = await ort.InferenceSession.create(bytes, {
					executionProviders: ["wasm"],
					graphOptimizationLevel: "all",
				});
				if (controller.signal.aborted) {
					await session.release();
					session = undefined;
					controller.signal.throwIfAborted();
				}
				modelId = id;
			}
			self.postMessage({
				id: request.id,
				type: "loaded",
				model: id,
				elapsed: performance.now() - started,
				threads: ort.env.wasm.numThreads,
			});
		} else if (request.type === "transcribe") {
			if (!session) throw new Error("Download and load a model first.");
			const started = performance.now();
			const pcm = new Float32Array(request.audio);
			if (pcm.length > 16000 * 60 * 30)
				throw new Error("Please use an audio file shorter than 30 minutes.");
			const decode = await decoder(request.language);
			const parts = splitAudio(pcm);
			let text = "";
			for (let i = 0; i < parts.length; i++) {
				progress({
					phase:
						parts.length > 1
							? `Transcribing part ${i + 1} of ${parts.length}`
							: "Transcribing on your device",
					completed: i,
					total: parts.length,
				});
				let energy = 0;
				for (const value of parts[i]) energy += value * value;
				if (energy / parts[i].length < 1e-9) continue;
				const { features, frames } = computeFeatures(parts[i]);
				const audio = new ort.Tensor("float32", features, [1, 80, frames]);
				const length = new ort.Tensor(
					"int64",
					BigInt64Array.of(BigInt(frames)),
					[1]
				);
				let output: ort.InferenceSession.ReturnType | undefined;
				try {
					output = await session.run({ audio_signal: audio, length });
					const logits = output[session.outputNames[0]];
					const part = decodeCTC(
						logits.data as Float32Array,
						logits.dims[2],
						decode
					);
					text = joinTranscripts(text, part);
					self.postMessage({ id: request.id, type: "partial", text });
				} finally {
					audio.dispose();
					length.dispose();
					if (output)
						for (const tensor of Object.values(output)) tensor.dispose();
				}
			}
			self.postMessage({
				id: request.id,
				type: "result",
				text,
				elapsed: performance.now() - started,
				audioSeconds: pcm.length / 16000,
				parts: parts.length,
				model: modelId,
			});
		}
	} catch (error) {
		self.postMessage({
			id: request.id,
			type: "error",
			error: error instanceof Error ? error.message : String(error),
		});
	} finally {
		busy = false;
	}
};
