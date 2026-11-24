// Audio features, CTC decoding and long-audio windowing for the keyboard's
// in-place dictation. Ported from apps/web/src/lib/transcription/features.ts,
// which mirrors crates/rust-backend (preprocess.rs, decode.rs, chunk.rs).
// Keep all of them in sync.

const f = Math.fround;
const FFT_SIZE = 512;
const WINDOW = 320;
const HOP = 160;
const BINS = 80;

const hann = Float32Array.from({ length: WINDOW }, (_, n) =>
	f(0.5 * (1 - Math.cos((2 * Math.PI * n) / (WINDOW - 1))))
);

const hzToMel = (hz) =>
	hz < 1000 ? (3 * hz) / 200 : 15 + (27 * Math.log(hz / 1000)) / Math.log(6.4);
const melToHz = (mel) =>
	mel < 15 ? (200 * mel) / 3 : 1000 * Math.exp(((mel - 15) * Math.log(6.4)) / 27);
const points = Float32Array.from({ length: BINS + 2 }, (_, i) =>
	melToHz((i * hzToMel(8000)) / (BINS + 1))
);
const filters = Array.from({ length: BINS }, (_, i) => {
	const weights = [];
	const [left, center, right] = [points[i], points[i + 1], points[i + 2]];
	for (let k = 0; k <= FFT_SIZE / 2; k++) {
		const hz = (k * 16000) / FFT_SIZE;
		const weight =
			Math.max(0, Math.min((hz - left) / (center - left), (right - hz) / (right - center))) *
			(2 / (right - left));
		if (weight > 0) weights.push([k, f(weight)]);
	}
	return weights;
});

// In-place radix-2 FFT of FFT_SIZE points.
const reversed = Uint16Array.from({ length: FFT_SIZE }, (_, i) => {
	let r = 0;
	for (let bit = 1, x = i; bit < FFT_SIZE; bit <<= 1, x >>= 1) r = (r << 1) | (x & 1);
	return r;
});
const cosines = Float64Array.from({ length: FFT_SIZE / 2 }, (_, k) =>
	Math.cos((2 * Math.PI * k) / FFT_SIZE)
);
const sines = Float64Array.from({ length: FFT_SIZE / 2 }, (_, k) =>
	-Math.sin((2 * Math.PI * k) / FFT_SIZE)
);

function transform(real, imag) {
	for (let i = 0; i < FFT_SIZE; i++) {
		const j = reversed[i];
		if (j > i) {
			const swap = real[i];
			real[i] = real[j];
			real[j] = swap;
		}
	}
	imag.fill(0);
	for (let size = 2; size <= FFT_SIZE; size <<= 1) {
		const half = size >> 1;
		const step = FFT_SIZE / size;
		for (let start = 0; start < FFT_SIZE; start += size) {
			for (let k = 0; k < half; k++) {
				const a = start + k;
				const b = a + half;
				const wr = cosines[k * step];
				const wi = sines[k * step];
				const tr = real[b] * wr - imag[b] * wi;
				const ti = real[b] * wi + imag[b] * wr;
				real[b] = real[a] - tr;
				imag[b] = imag[a] - ti;
				real[a] += tr;
				imag[a] += ti;
			}
		}
	}
}

/** 80-bin log-mel features of 16 kHz audio, laid out [1, 80, frames]. */
export function computeFeatures(audio) {
	if (audio.length < HOP) throw new Error("Too little audio to transcribe.");
	const frames = Math.floor(audio.length / HOP) + 1;
	const features = new Float32Array(BINS * frames);
	const emphasized = new Float32Array(audio.length);
	emphasized[0] = audio[0];
	for (let i = 1; i < audio.length; i++)
		emphasized[i] = f(audio[i] - f(f(0.97) * audio[i - 1]));
	const real = new Float64Array(FFT_SIZE);
	const imag = new Float64Array(FFT_SIZE);
	const power = new Float32Array(FFT_SIZE / 2 + 1);
	for (let t = 0; t < frames; t++) {
		real.fill(0);
		for (let n = 0; n < WINDOW; n++) {
			const index = t * HOP + n - WINDOW / 2;
			if (index >= 0 && index < emphasized.length) real[n] = f(emphasized[index] * hann[n]);
		}
		transform(real, imag);
		for (let k = 0; k < power.length; k++) {
			const re = f(real[k]);
			const im = f(imag[k]);
			power[k] = f(f(re * re) + f(im * im));
		}
		for (let mel = 0; mel < BINS; mel++) {
			let value = 0;
			for (const [k, weight] of filters[mel]) value = f(value + f(power[k] * weight));
			features[mel * frames + t] = f(Math.log(f(value + f(1e-9))));
		}
	}
	for (let mel = 0; mel < BINS; mel++) {
		const offset = mel * frames;
		let sum = 0;
		for (let t = 0; t < frames; t++) sum += features[offset + t];
		const mean = sum / frames;
		let variance = 0;
		for (let t = 0; t < frames; t++) variance += (features[offset + t] - mean) ** 2;
		const std = Math.max(Math.sqrt(variance / (frames - 1)), 1.1920929e-7);
		for (let t = 0; t < frames; t++) features[offset + t] = (features[offset + t] - mean) / std;
	}
	return { features, frames };
}

/**
 * Greedy CTC decode. `decoder` is { blank, tokens, mask? }: with a mask (the
 * multilingual model) only that language's classes compete and tokens follow
 * mask order; without one the Telugu model's tokens start at class 5121.
 */
export function decodeCTC(logits, classes, decoder) {
	let previous = decoder.blank;
	const text = [];
	for (let offset = 0; offset < logits.length; offset += classes) {
		let best = -Infinity;
		let prediction = decoder.blank;
		let token = -1;
		const count = decoder.mask ? decoder.mask.length : classes;
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

/** Windows of at most `seconds`, cut at the quietest 100 ms of each one's last 3 s. */
export function splitAudio(audio, seconds = 25) {
	const max = seconds * 16000;
	if (audio.length <= max) return [audio];
	const parts = [];
	let start = 0;
	while (start < audio.length) {
		let end = Math.min(start + max, audio.length);
		if (end < audio.length) {
			let lowest = Infinity;
			const target = end;
			for (let candidate = target - 3 * 16000; candidate < target; candidate += 1600) {
				let energy = 0;
				for (let i = candidate; i < candidate + 1600; i++) energy += audio[i] ** 2;
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

/** Appends `next`, dropping the words the 500 ms overlap produced twice. */
export function joinTranscripts(previous, next) {
	if (!previous) return next;
	if (!next) return previous;
	const left = previous.split(/\s+/u);
	const right = next.split(/\s+/u);
	for (let overlap = Math.min(3, left.length, right.length); overlap > 0; overlap--) {
		if (left.slice(-overlap).join(" ") === right.slice(0, overlap).join(" ")) {
			return [...left, ...right.slice(overlap)].join(" ");
		}
	}
	return `${previous} ${next}`.trim();
}
