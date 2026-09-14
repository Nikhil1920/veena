import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { MODELS, type ModelId, type Progress } from "./models";

async function directory(id: ModelId) {
	const root = await navigator.storage.getDirectory();
	const parent = await root.getDirectoryHandle("veena-models", {
		create: true,
	});
	return parent.getDirectoryHandle(id, { create: true });
}

async function readFile(dir: FileSystemDirectoryHandle, name: string) {
	return (await dir.getFileHandle(name)).getFile();
}

export async function isModelCached(id: ModelId): Promise<boolean> {
	try {
		const dir = await directory(id);
		const ready = JSON.parse(await (await readFile(dir, "ready.json")).text());
		if (ready.sha256 !== MODELS[id].sha256) return false;
		for (const name of MODELS[id].files) {
			if ((await readFile(dir, name)).size !== ready.sizes[name]) return false;
		}
		return true;
	} catch {
		return false;
	}
}

export async function removeModels() {
	const root = await navigator.storage.getDirectory();
	await root
		.removeEntry("veena-models", { recursive: true })
		.catch((error: DOMException) => {
			if (error.name !== "NotFoundError") throw error;
		});
}

/** Download to disk rather than retaining archive + weights + ORT copies in RAM. */
async function download(
	id: ModelId,
	dir: FileSystemDirectoryHandle,
	progress: (p: Progress) => void,
	signal: AbortSignal
) {
	const model = MODELS[id];
	const handle = await dir.getFileHandle("download.partial", { create: true });
	let partial = await handle.getFile();
	let offset = partial.size <= model.bytes ? partial.size : 0;
	const hash = sha256.create();
	if (offset) {
		progress({
			phase: "Checking saved download",
			completed: offset,
			total: model.bytes,
		});
		const reader = partial.stream().getReader();
		while (true) {
			const { done, value } = await reader.read();
			if (done) break;
			signal.throwIfAborted();
			hash.update(value);
		}
	}
	if (offset < model.bytes) {
		const response = await fetch(`/models/${model.archive}`, {
			headers: offset ? { Range: `bytes=${offset}-` } : {},
			signal,
		});
		if (!response.ok || !response.body)
			throw new Error(
				`Model download failed (${response.status}). Please retry when online.`
			);
		if (
			offset &&
			(response.status !== 206 ||
				!response.headers.get("Content-Range")?.startsWith(`bytes ${offset}-`))
		) {
			hash.destroy();
			await response.body.cancel();
			await dir.removeEntry("download.partial");
			return download(id, dir, progress, signal);
		}
		const writer = await handle.createWritable({
			keepExistingData: offset > 0,
		});
		await writer.seek(offset);
		const reader = response.body.getReader();
		let lastProgress = 0;
		try {
			while (true) {
				const { done, value } = await reader.read();
				if (done) break;
				signal.throwIfAborted();
				offset += value.length;
				if (offset > model.bytes)
					throw new Error(
						"Model download size does not match the expected version."
					);
				hash.update(value);
				await writer.write(value);
				if (performance.now() - lastProgress > 100 || offset === model.bytes) {
					progress({
						phase: "Downloading model",
						completed: offset,
						total: model.bytes,
					});
					lastProgress = performance.now();
				}
			}
		} finally {
			await writer.close();
			reader.releaseLock();
		}
	}
	if (offset !== model.bytes)
		throw new Error("The model download was interrupted. Retry to resume it.");
	if (bytesToHex(hash.digest()) !== model.sha256) {
		await dir.removeEntry("download.partial");
		throw new Error(
			"The model failed its integrity check. Please download it again."
		);
	}
	partial = await handle.getFile();
	return partial;
}

/** Read only allowlisted tar members; stream the large ONNX member to OPFS. */
async function extract(
	archive: File,
	id: ModelId,
	dir: FileSystemDirectoryHandle,
	signal: AbortSignal
) {
	const reader = archive
		.stream()
		.pipeThrough(new DecompressionStream("gzip"))
		.getReader();
	let chunk: Uint8Array = new Uint8Array();
	let position = 0;
	async function consume(
		count: number,
		write: (part: Uint8Array) => Promise<void>
	) {
		while (count > 0) {
			signal.throwIfAborted();
			if (position === chunk.length) {
				const result = await reader.read();
				if (result.done) throw new Error("The model archive is incomplete.");
				chunk = result.value;
				position = 0;
			}
			const take = Math.min(count, chunk.length - position);
			await write(chunk.subarray(position, position + take));
			position += take;
			count -= take;
		}
	}
	const sizes: Record<string, number> = {};
	const decoder = new TextDecoder();
	try {
		while (true) {
			const header = new Uint8Array(512);
			let at = 0;
			await consume(512, async (part) => {
				header.set(part, at);
				at += part.length;
			});
			if (header.every((byte) => byte === 0)) break;
			const name =
				decoder
					.decode(header.subarray(0, 100))
					.split("\0")[0]
					.split("/")
					.pop() ?? "";
			const size =
				parseInt(
					decoder.decode(header.subarray(124, 136)).replaceAll("\0", "").trim(),
					8
				) || 0;
			if (!Number.isSafeInteger(size) || size < 0 || size > 2_000_000_000)
				throw new Error("Unsupported model archive entry.");
			if ((MODELS[id].files as readonly string[]).includes(name)) {
				const writer = await (
					await dir.getFileHandle(name, { create: true })
				).createWritable();
				try {
					await consume(size, async (part) => {
						await writer.write(part as Uint8Array<ArrayBuffer>);
					});
				} finally {
					await writer.close();
				}
				sizes[name] = size;
			} else {
				await consume(size, async () => {});
			}
			await consume((512 - (size % 512)) % 512, async () => {});
		}
	} finally {
		await reader.cancel().catch(() => {});
	}
	for (const name of MODELS[id].files)
		if (!sizes[name]) throw new Error(`The model archive is missing ${name}.`);
	const writer = await (
		await dir.getFileHandle("ready.json", { create: true })
	).createWritable();
	await writer.write(JSON.stringify({ sha256: MODELS[id].sha256, sizes }));
	await writer.close();
	await dir.removeEntry("download.partial");
}

export async function loadModelFiles(
	id: ModelId,
	progress: (p: Progress) => void,
	signal: AbortSignal,
	allowDownload = false
) {
	const cached = await isModelCached(id);
	signal.throwIfAborted();
	if (!cached && !allowDownload) {
		throw new Error(
			"The saved model is no longer available. Download it again to continue."
		);
	}
	const dir = await directory(id);
	if (!cached) {
		const estimate = await navigator.storage.estimate();
		if (
			estimate.quota &&
			estimate.quota - (estimate.usage ?? 0) < MODELS[id].bytes * 2.5
		) {
			throw new Error(
				"Not enough browser storage for this model. Free some space or use the Veena app."
			);
		}
		const archive = await download(id, dir, progress, signal);
		progress({ phase: "Preparing downloaded model" });
		await extract(archive, id, dir, signal);
	}
	signal.throwIfAborted();
	const files: Record<string, File> = {};
	for (const name of MODELS[id].files) files[name] = await readFile(dir, name);
	return { files, cached };
}
