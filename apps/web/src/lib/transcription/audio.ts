export const MAX_SECONDS = 30 * 60;

/** PCM WAV output retains audio locally for playback and retry. */
export function encodeWav(pcm: Float32Array): Blob {
	const data = new ArrayBuffer(44 + pcm.length * 2);
	const view = new DataView(data);
	const label = (offset: number, text: string) =>
		[...text].forEach((char, i) =>
			view.setUint8(offset + i, char.charCodeAt(0))
		);
	label(0, "RIFF");
	view.setUint32(4, 36 + pcm.length * 2, true);
	label(8, "WAVE");
	label(12, "fmt ");
	view.setUint32(16, 16, true);
	view.setUint16(20, 1, true);
	view.setUint16(22, 1, true);
	view.setUint32(24, 16000, true);
	view.setUint32(28, 32000, true);
	view.setUint16(32, 2, true);
	view.setUint16(34, 16, true);
	label(36, "data");
	view.setUint32(40, pcm.length * 2, true);
	for (let i = 0; i < pcm.length; i++)
		view.setInt16(
			44 + i * 2,
			Math.max(-1, Math.min(1, pcm[i])) * (pcm[i] < 0 ? 32768 : 32767),
			true
		);
	return new Blob([data], { type: "audio/wav" });
}

/** Avoid decoding/resampling already-normalized WAVs, including the test fixture. */
export function readPcmWav(buffer: ArrayBuffer): Float32Array | undefined {
	const view = new DataView(buffer);
	if (
		view.byteLength < 44 ||
		view.getUint32(0, false) !== 0x52494646 ||
		view.getUint32(8, false) !== 0x57415645
	)
		return;
	let format = 0,
		channels = 0,
		rate = 0,
		bits = 0;
	for (let at = 12; at + 8 <= view.byteLength; ) {
		const tag = view.getUint32(at, false);
		const size = view.getUint32(at + 4, true);
		const start = at + 8;
		if (start + size > view.byteLength)
			throw new Error("This WAV file is incomplete.");
		if (tag === 0x666d7420 && size >= 16) {
			format = view.getUint16(start, true);
			channels = view.getUint16(start + 2, true);
			rate = view.getUint32(start + 4, true);
			bits = view.getUint16(start + 14, true);
		}
		if (
			tag === 0x64617461 &&
			rate === 16000 &&
			channels > 0 &&
			((format === 1 && [16, 24, 32].includes(bits)) ||
				(format === 3 && bits === 32))
		) {
			const stride = bits / 8;
			const samples = Math.floor(size / (stride * channels));
			if (samples > MAX_SECONDS * 16000)
				throw new Error("Please choose an audio file shorter than 30 minutes.");
			const pcm = new Float32Array(samples);
			for (let i = 0; i < samples; i++) {
				let sum = 0;
				for (let c = 0; c < channels; c++) {
					const offset = start + (i * channels + c) * stride;
					if (format === 3) sum += view.getFloat32(offset, true);
					else if (bits === 16) sum += view.getInt16(offset, true) / 32768;
					else if (bits === 32) sum += view.getInt32(offset, true) / 2147483648;
					else {
						let value =
							view.getUint8(offset) |
							(view.getUint8(offset + 1) << 8) |
							(view.getUint8(offset + 2) << 16);
						if (value & 0x800000) value |= ~0xffffff;
						sum += value / 8388608;
					}
				}
				pcm[i] = sum / channels;
				if (!Number.isFinite(pcm[i]))
					throw new Error("The audio contains invalid samples.");
			}
			return pcm;
		}
		at = start + size + (size % 2);
	}
}

export async function decodeFile(file: Blob): Promise<Float32Array> {
	if (file.size > 200 * 1024 * 1024)
		throw new Error("Please choose a file under 200 MB.");
	const data = await file.arrayBuffer();
	const wav = readPcmWav(data);
	if (wav) return wav;
	const context = new AudioContext({ sampleRate: 16000 });
	try {
		const decoded = await context.decodeAudioData(data);
		if (decoded.duration > MAX_SECONDS)
			throw new Error("Please choose an audio file shorter than 30 minutes.");
		const offline = new OfflineAudioContext(
			1,
			Math.ceil(decoded.duration * 16000),
			16000
		);
		const source = offline.createBufferSource();
		source.buffer = decoded;
		source.connect(offline.destination);
		source.start();
		return (await offline.startRendering()).getChannelData(0).slice();
	} catch (error) {
		if (error instanceof DOMException)
			throw new Error(
				"This browser could not read that audio format. Try a WAV, MP3 or M4A file.",
				{ cause: error }
			);
		throw error;
	} finally {
		await context.close();
	}
}

export class Microphone {
	private context?: AudioContext;
	private stream?: MediaStream;
	private node?: AudioWorkletNode;
	private chunks: Float32Array[] = [];
	private length = 0;
	private generation = 0;
	async start(
		level: (rms: number) => void,
		limit: () => void,
		deviceId?: string
	) {
		const generation = ++this.generation;
		this.chunks = [];
		this.length = 0;
		try {
			const stream = await navigator.mediaDevices.getUserMedia({
				audio: {
					channelCount: 1,
					sampleRate: 16000,
					echoCancellation: true,
					noiseSuppression: true,
					...(deviceId ? { deviceId: { exact: deviceId } } : {}),
				},
			});
			if (generation !== this.generation) {
				stream.getTracks().forEach((track) => track.stop());
				throw new DOMException("Recording cancelled.", "AbortError");
			}
			this.stream = stream;
			this.context = new AudioContext({ sampleRate: 16000 });
			await this.context.audioWorklet.addModule("/audio/recorder.worklet.js");
			if (generation !== this.generation)
				throw new DOMException("Recording cancelled.", "AbortError");
			this.node = new AudioWorkletNode(this.context, "veena-recorder");
			this.node.port.onmessage = ({ data }) => {
				if (!(data instanceof Float32Array) || !this.context) return;
				const pcm = data as Float32Array;
				this.chunks.push(pcm);
				this.length += pcm.length;
				let power = 0;
				for (const value of pcm) power += value * value;
				level(Math.sqrt(power / pcm.length));
				if (this.length >= this.context!.sampleRate * MAX_SECONDS) limit();
			};
			this.context.createMediaStreamSource(this.stream).connect(this.node);
			const muted = this.context.createGain();
			muted.gain.value = 0;
			this.node.connect(muted).connect(this.context.destination);
			await this.context.resume();
		} catch (error) {
			if (generation === this.generation) this.dispose();
			throw error;
		}
	}
	async stop() {
		const sampleRate = this.context?.sampleRate ?? 16000;
		// Flush the final worklet block before disconnecting.
		if (this.node) {
			await new Promise<void>((resolve) => {
				const timer = setTimeout(resolve, 200);
				this.node!.port.addEventListener("message", function flush(event) {
					if (event.data?.flushed) {
						clearTimeout(timer);
						resolve();
					}
				});
				this.node!.port.postMessage("flush");
			});
		}
		const pcm = new Float32Array(this.length);
		let offset = 0;
		for (const chunk of this.chunks) {
			pcm.set(chunk, offset);
			offset += chunk.length;
		}
		this.dispose();
		this.chunks = [];
		if (sampleRate === 16000) return pcm;
		const offline = new OfflineAudioContext(
			1,
			Math.ceil((pcm.length * 16000) / sampleRate),
			16000
		);
		const source = offline.createBufferSource();
		const buffer = offline.createBuffer(1, pcm.length, sampleRate);
		buffer.copyToChannel(pcm, 0);
		source.buffer = buffer;
		source.connect(offline.destination);
		source.start();
		return (await offline.startRendering()).getChannelData(0).slice();
	}
	dispose() {
		this.generation++;
		this.stream?.getTracks().forEach((track) => track.stop());
		if (this.node) {
			this.node.port.onmessage = null;
			this.node.port.close();
			this.node.disconnect();
		}
		void this.context?.close().catch(() => {});
		this.node = undefined;
		this.context = undefined;
		this.stream = undefined;
	}
}
