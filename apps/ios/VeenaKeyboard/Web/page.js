// The page inside the keyboard's hidden web view. It records the microphone
// (iOS refuses the keyboard itself, but lets the web view's media process
// record under Veena's microphone permission) and hands the audio to the
// engine worker. KeyboardWebEngine.swift calls veenaStart/veenaStop/
// veenaCancel and receives the events posted here.

const post = (message) => webkit.messageHandlers.veena.postMessage(message);

// MARK: Engine worker

let worker;
let nextCall = 1;
const calls = new Map();

function engine() {
	if (worker) return worker;
	worker = new Worker("engine.js", { type: "module" });
	worker.onmessage = ({ data }) => {
		const call = calls.get(data.id);
		if (!call) return;
		calls.delete(data.id);
		if (data.ok) call.resolve(data.result);
		else call.reject(new Error(data.message));
	};
	worker.onerror = (event) => {
		const failure = new Error(event.message || "The speech engine stopped");
		for (const call of calls.values()) call.reject(failure);
		calls.clear();
		worker.terminate();
		worker = undefined;
		modelKey = undefined;
		modelReady = undefined;
	};
	return worker;
}

function call(type, body, transfer = []) {
	return new Promise((resolve, reject) => {
		const id = nextCall++;
		calls.set(id, { resolve, reject });
		engine().postMessage({ id, type, ...body }, transfer);
	});
}

let modelKey;
let modelReady;

/** Loads the model unless it is loaded or loading; resolves once it can transcribe. */
function ensureModel(model, language) {
	const key = `${model.key}:${language}`;
	if (modelReady && modelKey === key) return modelReady;
	modelKey = key;
	const loading = call("load", { model, language });
	modelReady = loading;
	loading.then(
		(info) => {
			if (!info.reused) post({ type: "model", ...info });
		},
		() => {
			if (modelReady === loading) modelReady = modelKey = undefined;
		}
	);
	return loading;
}

// MARK: Microphone

let job;
let generation = 0;
let recording = false;
let context;
let stream;
let source;
let node;
let chunks = [];
let length = 0;
let testAudio;
let startModel;

function closeMicrophone() {
	try {
		if (node) node.disconnect();
		if (source) source.disconnect();
	} catch (error) {}
	if (stream) stream.getTracks().forEach((track) => track.stop());
	if (context) context.close().catch(() => {});
	context = stream = source = node = undefined;
}

async function veenaStart(config) {
	if (recording) return;
	recording = true;
	const mine = ++generation;
	job = config.job;
	chunks = [];
	length = 0;
	testAudio = config.testAudio;
	// The model loads while the user speaks.
	startModel = ensureModel(config.model, config.language);
	startModel.catch(() => {});
	let opened;
	let opening;
	try {
		const audio = {
			channelCount: 1,
			echoCancellation: false,
			noiseSuppression: false,
			autoGainControl: false,
		};
		opened = await navigator.mediaDevices.getUserMedia({ audio });
		if (mine === generation) {
			opening = new AudioContext();
			await opening.resume();
		}
		if (mine !== generation) {
			// Cancelled while the microphone was opening: a later start owns the shared state.
			opened.getTracks().forEach((track) => track.stop());
			if (opening) opening.close().catch(() => {});
			return;
		}
		stream = opened;
		context = opening;
		source = context.createMediaStreamSource(stream);
		node = context.createScriptProcessor(4096, 1, 1);
		let lastLevel = 0;
		node.onaudioprocess = (event) => {
			if (!recording || mine !== generation) return;
			const input = event.inputBuffer.getChannelData(0);
			chunks.push(new Float32Array(input));
			length += input.length;
			const now = performance.now();
			if (now - lastLevel < 80) return;
			lastLevel = now;
			let power = 0;
			for (const value of input) power += value * value;
			post({ type: "level", job: config.job, rms: Math.sqrt(power / input.length) });
		};
		source.connect(node);
		node.connect(context.destination);
		post({
			type: "started",
			job: config.job,
			rate: context.sampleRate,
			device: stream.getAudioTracks()[0].label,
		});
	} catch (error) {
		// Whatever opened before the failure, whoever owns the shared state now.
		if (opened && opened !== stream) opened.getTracks().forEach((track) => track.stop());
		if (opening && opening !== context) opening.close().catch(() => {});
		if (mine !== generation) return;
		closeMicrophone();
		if (testAudio) {
			// Test audio stands in for a microphone the simulator may not have.
			post({ type: "started", job: config.job, rate: 0, device: "test audio" });
			return;
		}
		recording = false;
		post({
			type: "failed",
			job: config.job,
			stage: "microphone",
			name: (error && error.name) || "Error",
			message: (error && error.message) || String(error),
		});
	}
}

/** The recording at 16 kHz, which is what the model hears. */
async function resample(pcm, rate) {
	if (rate === 16000) return pcm;
	const offline = new OfflineAudioContext(1, Math.ceil((pcm.length * 16000) / rate), 16000);
	const buffer = offline.createBuffer(1, pcm.length, rate);
	buffer.copyToChannel(pcm, 0);
	const player = offline.createBufferSource();
	player.buffer = buffer;
	player.connect(offline.destination);
	player.start();
	return (await offline.startRendering()).getChannelData(0).slice();
}

/** A 16-bit PCM WAV as mono samples and their rate. */
function readWav(data) {
	const view = new DataView(data);
	let channels = 1;
	let rate = 16000;
	for (let at = 12; at + 8 <= view.byteLength; ) {
		const tag = view.getUint32(at, false);
		const size = view.getUint32(at + 4, true);
		const start = at + 8;
		if (tag === 0x666d7420) {
			channels = view.getUint16(start + 2, true);
			rate = view.getUint32(start + 4, true);
		}
		if (tag === 0x64617461) {
			const frames = Math.floor(Math.min(size, view.byteLength - start) / (2 * channels));
			const pcm = new Float32Array(frames);
			for (let i = 0; i < frames; i++) {
				let sum = 0;
				for (let c = 0; c < channels; c++)
					sum += view.getInt16(start + (i * channels + c) * 2, true) / 32768;
				pcm[i] = sum / channels;
			}
			return { pcm, rate };
		}
		at = start + size + (size % 2);
	}
	throw new Error("The test audio isn't a PCM WAV file");
}

async function veenaStop() {
	if (!recording) return;
	recording = false;
	const mine = generation;
	const current = job;
	let rate = context ? context.sampleRate : 16000;
	closeMicrophone();
	let pcm = new Float32Array(length);
	let offset = 0;
	for (const chunk of chunks) {
		pcm.set(chunk, offset);
		offset += chunk.length;
	}
	chunks = [];
	length = 0;
	try {
		if (testAudio) {
			const response = await fetch(testAudio);
			if (!response.ok) throw new Error("The test audio is missing");
			({ pcm, rate } = readWav(await response.arrayBuffer()));
		}
		const seconds = pcm.length / rate;
		post({ type: "stopped", job: current, seconds });
		// Shorter than a word: a tap, not speech (the app's floor too).
		if (seconds < 0.4) {
			post({ type: "result", job: current, text: "" });
			return;
		}
		const audio = await resample(pcm, rate);
		await startModel;
		if (mine !== generation) return;
		const result = await call("transcribe", { audio }, [audio.buffer]);
		if (mine !== generation) return;
		post({ type: "result", job: current, text: result.text, ms: result.ms, seconds: result.seconds });
	} catch (error) {
		if (mine !== generation) return;
		post({
			type: "failed",
			job: current,
			stage: "engine",
			name: (error && error.name) || "Error",
			message: (error && error.message) || String(error),
		});
	}
}

function veenaCancel() {
	generation++;
	recording = false;
	chunks = [];
	length = 0;
	closeMicrophone();
}

post({ type: "ready" });
