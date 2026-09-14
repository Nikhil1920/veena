import type { ModelId, Progress } from "./models";

export type Result = {
	text: string;
	elapsed: number;
	audioSeconds: number;
	parts: number;
	model: ModelId;
};
type Pending = {
	resolve: (value: Record<string, unknown>) => void;
	reject: (error: Error) => void;
	progress?: (p: Progress) => void;
	partial?: (text: string) => void;
};

export class TranscriptionClient {
	private worker?: Worker;
	private sequence = 0;
	private pending = new Map<number, Pending>();
	private getWorker() {
		if (!this.worker) {
			this.worker = new Worker(
				new URL("./transcription.worker.ts", import.meta.url),
				{ type: "module" }
			);
			this.worker.onmessage = ({ data }) => {
				const pending = this.pending.get(data.id);
				if (!pending) return;
				if (data.type === "progress") pending.progress?.(data);
				else if (data.type === "partial") pending.partial?.(data.text);
				else {
					this.pending.delete(data.id);
					if (data.type === "error") pending.reject(new Error(data.error));
					else pending.resolve(data);
				}
			};
			this.worker.onerror = (event) => {
				this.stop(
					new Error(
						event.message ||
							"The transcription worker stopped. Reload the model and retry."
					)
				);
			};
		}
		return this.worker;
	}
	private request(
		data: Record<string, unknown>,
		progress?: Pending["progress"],
		transfer: Transferable[] = [],
		partial?: Pending["partial"]
	) {
		const id = ++this.sequence;
		return new Promise<Record<string, unknown>>((resolve, reject) => {
			this.pending.set(id, { resolve, reject, progress, partial });
			try {
				this.getWorker().postMessage({ id, ...data }, transfer);
			} catch (error) {
				this.pending.delete(id);
				reject(error);
			}
		});
	}
	load(model: ModelId, progress: Pending["progress"], allowDownload = false) {
		return this.request({ type: "load", model, allowDownload }, progress);
	}
	async transcribe(
		pcm: Float32Array,
		language: string,
		progress: Pending["progress"],
		partial?: Pending["partial"]
	) {
		const audio = pcm.slice().buffer;
		return (await this.request(
			{ type: "transcribe", audio, language },
			progress,
			[audio],
			partial
		)) as unknown as Result;
	}
	abortDownload() {
		this.worker?.postMessage({ type: "abort" });
	}
	stop(error = new Error("Transcription cancelled.")) {
		this.worker?.terminate();
		this.worker = undefined;
		for (const pending of this.pending.values()) pending.reject(error);
		this.pending.clear();
	}
}
