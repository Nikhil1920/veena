import FFT from "fft.js";

const f = Math.fround;
const FFT_SIZE = 512;
const WINDOW = 320;
const HOP = 160;
const BINS = 80;
const fft = new FFT(FFT_SIZE);
const window = Float32Array.from({ length: WINDOW }, (_, n) =>
	f(0.5 * (1 - Math.cos((2 * Math.PI * n) / (WINDOW - 1))))
);

const hzToMel = (hz: number) =>
	hz < 1000 ? (3 * hz) / 200 : 15 + (27 * Math.log(hz / 1000)) / Math.log(6.4);
const melToHz = (mel: number) =>
	mel < 15
		? (200 * mel) / 3
		: 1000 * Math.exp(((mel - 15) * Math.log(6.4)) / 27);
const points = Float32Array.from({ length: BINS + 2 }, (_, i) =>
	melToHz((i * hzToMel(8000)) / (BINS + 1))
);
const filters = Array.from({ length: BINS }, (_, i) => {
	const weights: [number, number][] = [];
	const [left, center, right] = [points[i], points[i + 1], points[i + 2]];
	for (let k = 0; k <= FFT_SIZE / 2; k++) {
		const hz = (k * 16000) / FFT_SIZE;
		const weight =
			Math.max(
				0,
				Math.min((hz - left) / (center - left), (right - hz) / (right - center))
			) *
			(2 / (right - left));
		if (weight > 0) weights.push([k, f(weight)]);
	}
	return weights;
});

/** Mirrors rust_backend::preprocess at 16 kHz; layout [1, 80, frames]. */
export function computeFeatures(audio: Float32Array) {
	if (audio.length < HOP)
		throw new Error("Record at least a fraction of a second of audio.");
	const frames = Math.floor(audio.length / HOP) + 1;
	const features = new Float32Array(BINS * frames);
	const emphasized = new Float32Array(audio.length);
	emphasized[0] = audio[0];
	for (let i = 1; i < audio.length; i++)
		emphasized[i] = f(audio[i] - f(f(0.97) * audio[i - 1]));
	const input = new Float64Array(FFT_SIZE);
	const spectrum = fft.createComplexArray();
	const power = new Float32Array(FFT_SIZE / 2 + 1);
	for (let t = 0; t < frames; t++) {
		input.fill(0);
		for (let n = 0; n < WINDOW; n++) {
			const index = t * HOP + n - WINDOW / 2;
			if (index >= 0 && index < emphasized.length)
				input[n] = f(emphasized[index] * window[n]);
		}
		fft.realTransform(spectrum, input);
		for (let k = 0; k < power.length; k++) {
			const real = f(spectrum[2 * k]);
			const imag = f(spectrum[2 * k + 1]);
			power[k] = f(f(real * real) + f(imag * imag));
		}
		for (let mel = 0; mel < BINS; mel++) {
			let value = 0;
			for (const [k, weight] of filters[mel])
				value = f(value + f(power[k] * weight));
			features[mel * frames + t] = f(Math.log(f(value + f(1e-9))));
		}
	}
	for (let mel = 0; mel < BINS; mel++) {
		const offset = mel * frames;
		let sum = 0;
		for (let t = 0; t < frames; t++) sum += features[offset + t];
		const mean = sum / frames;
		let variance = 0;
		for (let t = 0; t < frames; t++)
			variance += (features[offset + t] - mean) ** 2;
		const std = Math.max(Math.sqrt(variance / (frames - 1)), 1.1920929e-7);
		for (let t = 0; t < frames; t++)
			features[offset + t] = (features[offset + t] - mean) / std;
	}
	return { features, frames };
}

export type Decoder = { blank: number; tokens: string[]; mask?: number[] };

export function decodeCTC(
	logits: Float32Array,
	classes: number,
	decoder: Decoder
) {
	let previous = decoder.blank;
	const text: string[] = [];
	for (let offset = 0; offset < logits.length; offset += classes) {
		let best = -Infinity;
		let prediction = decoder.blank;
		let token = -1;
		const count = decoder.mask?.length ?? classes;
		for (let i = 0; i < count; i++) {
			const klass = decoder.mask ? decoder.mask[i] : i;
			const value = logits[offset + klass];
			if (decoder.mask ? value > best : value >= best) {
				best = value;
				prediction = klass;
				token = decoder.mask ? i : klass - 5121;
			}
		}
		if (prediction !== decoder.blank && prediction !== previous) {
			const piece = decoder.tokens[token];
			if (piece && piece !== "<unk>") text.push(piece);
		}
		previous = prediction;
	}
	return text.join("").replaceAll("▁", " ").trim();
}

/** Silence-aware windows bound attention memory. Short clips stay unchanged. */
export function splitAudio(audio: Float32Array, seconds = 25): Float32Array[] {
	const max = seconds * 16000;
	if (audio.length <= max) return [audio];
	const parts: Float32Array[] = [];
	let start = 0;
	while (start < audio.length) {
		let end = Math.min(start + max, audio.length);
		if (end < audio.length) {
			let lowest = Infinity;
			const target = end;
			for (
				let candidate = target - 3 * 16000;
				candidate < target;
				candidate += 1600
			) {
				let energy = 0;
				for (let i = candidate; i < candidate + 1600; i++)
					energy += audio[i] ** 2;
				if (energy < lowest) {
					lowest = energy;
					end = candidate + 800;
				}
			}
		}
		parts.push(audio.slice(start, end));
		if (end === audio.length) break;
		start = end - 8000;
	}
	return parts;
}

export function joinTranscripts(previous: string, next: string) {
	if (!previous) return next;
	const left = previous.split(/\s+/u);
	const right = next.split(/\s+/u);
	for (
		let overlap = Math.min(12, left.length, right.length);
		overlap > 0;
		overlap--
	) {
		if (left.slice(-overlap).join(" ") === right.slice(0, overlap).join(" ")) {
			return [...left, ...right.slice(overlap)].join(" ");
		}
	}
	return `${previous} ${next}`.trim();
}
