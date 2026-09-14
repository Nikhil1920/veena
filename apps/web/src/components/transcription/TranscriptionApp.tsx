import { useCallback, useEffect, useRef, useState } from "react";
import { LANGUAGES } from "../../config/languages.mjs";
import { DOWNLOADS } from "../../config/site.mjs";
import {
	TranscriptionClient,
	type Result,
} from "../../lib/transcription/client";
import {
	decodeFile,
	encodeWav,
	Microphone,
} from "../../lib/transcription/audio";
import {
	MODELS,
	modelForLanguage,
	type ModelId,
	type Progress,
} from "../../lib/transcription/models";
import {
	isModelCached,
	removeModels,
} from "../../lib/transcription/model-storage";
import {
	deleteEntry,
	listHistory,
	retainHistory,
	saveEntry,
	type Entry,
} from "../../lib/transcription/history";
import "../../styles/transcription.css";

const formatTime = (seconds: number) =>
	`${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, "0")}`;
const describeError = (error: unknown) =>
	error instanceof Error ? error.message : String(error);
type Settings = {
	retention: number;
	append: boolean;
	customWords: string;
};

function AudioPlayback({ audio }: { audio: Blob }) {
	const element = useRef<HTMLAudioElement>(null);

	useEffect(() => {
		const value = URL.createObjectURL(audio);
		if (element.current) element.current.src = value;
		return () => URL.revokeObjectURL(value);
	}, [audio]);
	return <audio ref={element} controls aria-label="Play recording" />;
}

function Icon({
	name,
}: {
	name: "mic" | "download" | "shield" | "stop" | "play";
}) {
	const paths = {
		mic: (
			<>
				<rect x="9" y="2" width="6" height="12" rx="3" />
				<path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8" />
			</>
		),
		download: (
			<>
				<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5" />
			</>
		),
		shield: (
			<>
				<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z" />
				<path d="m8 12 3 3 5-6" />
			</>
		),
		stop: <rect x="5" y="5" width="14" height="14" rx="2" />,
		play: <path d="m9 5 10 7-10 7V5Z" />,
	};
	return (
		<svg
			width="20"
			height="20"
			viewBox="0 0 24 24"
			fill="none"
			stroke="currentColor"
			strokeWidth="1.7"
			strokeLinecap="round"
			strokeLinejoin="round"
			aria-hidden="true"
		>
			{paths[name]}
		</svg>
	);
}

export default function TranscriptionApp({
	initialLanguage = "te",
	embedded = false,
}: {
	initialLanguage?: string;
	embedded?: boolean;
}) {
	const pageLanguage =
		LANGUAGES.find((item) => item.code === initialLanguage) ??
		LANGUAGES.find((item) => item.code === "te")!;
	const language = pageLanguage.code;
	const selectedModel = modelForLanguage(language);
	const Container = embedded ? "div" : "main";
	const client = useRef<TranscriptionClient>(new TranscriptionClient());
	const microphone = useRef<Microphone>(new Microphone());
	const [loadedModel, setLoadedModel] = useState<ModelId>();
	const [cached, setCached] = useState<ModelId[]>([]);
	const [cacheChecked, setCacheChecked] = useState(false);
	const [busy, setBusy] = useState<
		"load" | "transcribe" | "microphone" | undefined
	>();
	const [recording, setRecording] = useState(false);
	const [seconds, setSeconds] = useState(0);
	const [level, setLevel] = useState(0);
	const [progress, setProgress] = useState<Progress>({
		phase: "Download the model once to start transcribing.",
	});
	const [error, setError] = useState("");
	const [notice, setNotice] = useState("");
	const [supported, setSupported] = useState(true);
	const [mobile, setMobile] = useState(false);
	const [online, setOnline] = useState(true);
	const [offlineReady, setOfflineReady] = useState(false);
	const [text, setText] = useState("");
	const [partial, setPartial] = useState("");
	const [result, setResult] = useState<Result>();
	const [audio, setAudio] = useState<Blob>();
	const [entries, setEntries] = useState<Entry[]>([]);
	const [activeId, setActiveId] = useState<string>();
	const [append, setAppend] = useState(false);
	const [retention, setRetention] = useState(50);
	const [customWords, setCustomWords] = useState("");
	const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);
	const [deviceId, setDeviceId] = useState("");
	const operation = useRef(false);
	const jobGeneration = useRef(0);
	const captureAttempt = useRef(0);
	const fileInput = useRef<HTMLInputElement>(null);
	const recordButton = useRef<HTMLButtonElement>(null);
	const focusRecorder = useRef(false);
	const finishRecording = useRef<() => void>(() => {});
	const locked = !!busy || recording;
	const ready = loadedModel === selectedModel;

	const refreshHistory = useCallback(
		async () => setEntries(await listHistory()),
		[]
	);
	const refreshModels = useCallback(async () => {
		const ids = Object.keys(MODELS) as ModelId[];
		const status = await Promise.all(ids.map(isModelCached));
		const available = ids.filter((_, i) => status[i]);
		setCached(available);
		return available;
	}, []);

	const loadModel = useCallback(
		async (allowDownload = false) => {
			if (operation.current) return;
			const generation = ++jobGeneration.current;
			operation.current = true;
			setBusy("load");
			setError("");
			setNotice("");
			setProgress(
				allowDownload
					? {
							phase: "Downloading model",
							completed: 0,
							total: MODELS[selectedModel].bytes,
						}
					: { phase: "Opening your saved model" }
			);
			focusRecorder.current = allowDownload;
			try {
				if (allowDownload)
					await navigator.storage.persist?.().catch(() => false);
				if (generation !== jobGeneration.current) return;
				await client.current.load(selectedModel, setProgress, allowDownload);
				if (generation !== jobGeneration.current) return;
				setLoadedModel(selectedModel);
				await refreshModels();
				setProgress({
					phase: "Ready to record. Your audio stays on this device.",
				});
				if ("serviceWorker" in navigator && import.meta.env.PROD) {
					void navigator.serviceWorker
						.register("/transcription-sw.js", { scope: "/" })
						.then(() => navigator.serviceWorker.ready)
						.then(() => setOfflineReady(true))
						.catch(() =>
							setNotice(
								"Keep this page open to transcribe. Offline page access could not be saved."
							)
						);
				}
			} catch (problem) {
				if (generation === jobGeneration.current) {
					setLoadedModel(undefined);
					setError(describeError(problem));
					await refreshModels();
				}
			} finally {
				setBusy(undefined);
				operation.current = false;
			}
		},
		[selectedModel, refreshModels]
	);

	useEffect(() => {
		const canRun =
			window.isSecureContext &&
			typeof navigator.storage?.getDirectory === "function" &&
			typeof window.Worker === "function" &&
			typeof window.WebAssembly === "object" &&
			typeof window.DecompressionStream === "function";
		setSupported(canRun);
		const isMobile =
			/Android|iPhone|iPad|iPod/i.test(navigator.userAgent) ||
			(navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
		setMobile(isMobile);
		setOnline(navigator.onLine);
		const connectivity = () => setOnline(navigator.onLine);
		window.addEventListener("online", connectivity);
		window.addEventListener("offline", connectivity);
		try {
			const saved = JSON.parse(
				localStorage.getItem("veena-browser-settings") || "null"
			) as Settings | null;
			if (saved) {
				setRetention(
					[10, 50, 100].includes(saved.retention) ? saved.retention : 50
				);
				setAppend(!!saved.append);
				setCustomWords(saved.customWords || "");
			}
		} catch {
			/* A fresh session still works when browser preferences are unavailable. */
		}
		let disposed = false;
		void refreshModels().then((available) => {
			if (disposed) return;
			setCacheChecked(true);
			if (canRun && !isMobile && available.includes(selectedModel))
				void loadModel(false);
		});
		void refreshHistory().catch(() =>
			setNotice(
				"Browser history is unavailable. You can still copy or export your transcript."
			)
		);
		const currentClient = client.current,
			currentMic = microphone.current,
			currentGeneration = jobGeneration;
		return () => {
			disposed = true;
			currentGeneration.current++;
			currentClient.stop();
			currentMic.dispose();
			window.removeEventListener("online", connectivity);
			window.removeEventListener("offline", connectivity);
		};
	}, [refreshHistory, refreshModels, loadModel, selectedModel]);

	useEffect(() => {
		if (ready && !busy && focusRecorder.current) {
			recordButton.current?.focus();
			focusRecorder.current = false;
		}
	}, [ready, busy]);

	useEffect(() => {
		if (!recording) return;
		const started = Date.now();
		setSeconds(0);
		const timer = setInterval(
			() => setSeconds((Date.now() - started) / 1000),
			200
		);
		return () => clearInterval(timer);
	}, [recording]);

	useEffect(() => {
		if (!locked) return;
		const preventClose = (event: BeforeUnloadEvent) => {
			event.preventDefault();
			event.returnValue = "";
		};
		window.addEventListener("beforeunload", preventClose);
		return () => window.removeEventListener("beforeunload", preventClose);
	}, [locked]);

	function saveSettings(next: Partial<Settings>) {
		try {
			localStorage.setItem(
				"veena-browser-settings",
				JSON.stringify({ retention, append, customWords, ...next })
			);
		} catch {
			setNotice("Your browser could not save preferences.");
		}
	}

	async function transcribe(
		pcm: Float32Array,
		source: Blob,
		title: string,
		speechLanguage = language,
		generation = jobGeneration.current
	) {
		setBusy("transcribe");
		setError("");
		setNotice("");
		setPartial("");
		setAudio(source);
		setResult(undefined);
		setActiveId(undefined);
		if (!append) setText("");
		const draft: Entry = {
			id: crypto.randomUUID(),
			date: Date.now(),
			title,
			language: speechLanguage,
			model: selectedModel,
			text: "",
			rawText: "",
			audio: source,
			duration: pcm.length / 16000,
			elapsed: 0,
			saved: false,
			status: "processing",
		};
		// Preserve captured audio before inference so errors and reloads are recoverable.
		await saveEntry(draft).catch(() =>
			setNotice(
				"Recording could not be saved in browser history. Keep this page open until you export your transcript."
			)
		);
		try {
			if (generation !== jobGeneration.current)
				throw new Error("Transcription cancelled.");
			const response = await client.current.transcribe(
				pcm,
				speechLanguage,
				setProgress,
				setPartial
			);
			if (generation !== jobGeneration.current)
				throw new Error("Transcription cancelled.");
			// Exact user-defined replacements are local and never sent to a service.
			let corrected = response.text;
			for (const line of customWords.split("\n")) {
				const [from, to] = line.split("=>").map((value) => value.trim());
				if (from && to) corrected = corrected.replaceAll(from, to);
			}
			const complete = append && text ? `${text}\n${corrected}` : corrected;
			setText(complete);
			setResult(response);
			setAudio(source);
			const entry: Entry = {
				...draft,
				model: response.model,
				status: "complete",
				text: corrected,
				rawText: response.text,
				audio: source,
				duration: response.audioSeconds,
				elapsed: response.elapsed,
			};
			setActiveId(append ? undefined : entry.id);
			try {
				await saveEntry(entry);
				await retainHistory(retention);
				await refreshHistory();
			} catch {
				setNotice(
					"Transcript ready. History could not be saved; copy or download your text."
				);
			}
			setProgress({
				phase: corrected
					? "Transcription complete"
					: "No speech was detected. Try speaking closer to the microphone.",
			});
		} catch (problem) {
			setError(describeError(problem));
			client.current.stop();
			setLoadedModel(undefined);
			setActiveId(draft.id);
			await saveEntry({
				...draft,
				status: "failed",
				error: describeError(problem),
			})
				.then(() => retainHistory(retention))
				.then(refreshHistory)
				.catch(() =>
					setNotice(
						"Your audio is still available for playback. Browser history could not be saved."
					)
				);
		} finally {
			setBusy(undefined);
			setPartial("");
			operation.current = false;
		}
	}

	async function importFile(file: File) {
		if (operation.current || recording || !ready) return;
		const generation = ++jobGeneration.current;
		operation.current = true;
		setBusy("transcribe");
		setError("");
		setProgress({ phase: "Reading audio on your device" });
		try {
			const pcm = await decodeFile(file);
			if (generation !== jobGeneration.current)
				throw new Error("Transcription cancelled.");
			await transcribe(pcm, file, file.name, language, generation);
		} catch (problem) {
			setError(describeError(problem));
			setBusy(undefined);
			operation.current = false;
		}
	}

	async function runSample() {
		if (operation.current || recording || !ready) return;
		const generation = ++jobGeneration.current;
		operation.current = true;
		setBusy("transcribe");
		setError("");
		setProgress({ phase: "Reading the Telugu sample" });
		try {
			const response = await fetch("/audio/telugu_test_audio_16k.wav");
			if (!response.ok) throw new Error("The sample could not be loaded.");
			const source = await response.blob();
			const pcm = await decodeFile(source);
			if (generation !== jobGeneration.current)
				throw new Error("Transcription cancelled.");
			await transcribe(pcm, source, "Telugu test recording", "te", generation);
		} catch (problem) {
			setError(describeError(problem));
			setBusy(undefined);
			operation.current = false;
		}
	}

	async function startRecording() {
		if (operation.current || recording || !ready) return;
		const attempt = ++captureAttempt.current;
		operation.current = true;
		setBusy("microphone");
		setError("");
		try {
			await microphone.current.start(
				setLevel,
				() => finishRecording.current(),
				deviceId
			);
			if (attempt !== captureAttempt.current) return;
			setDevices(
				(await navigator.mediaDevices.enumerateDevices()).filter(
					(device) => device.kind === "audioinput"
				)
			);
			if (attempt !== captureAttempt.current) return;
			setRecording(true);
			setProgress({ phase: "Listening. Stop when you’re finished." });
		} catch (problem) {
			if (attempt !== captureAttempt.current) return;
			setError(
				problem instanceof DOMException && problem.name === "NotAllowedError"
					? "Microphone access was denied. Allow it in browser settings, or choose an audio file."
					: describeError(problem)
			);
			microphone.current.dispose();
		} finally {
			if (attempt === captureAttempt.current) {
				operation.current = false;
				setBusy(undefined);
			}
		}
	}

	async function stopRecording() {
		if (operation.current) return;
		const generation = ++jobGeneration.current;
		operation.current = true;
		setRecording(false);
		setBusy("transcribe");
		setLevel(0);
		try {
			const pcm = await microphone.current.stop();
			await transcribe(
				pcm,
				encodeWav(pcm),
				"Microphone recording",
				language,
				generation
			);
		} catch (problem) {
			setError(describeError(problem));
			setBusy(undefined);
			operation.current = false;
		}
	}
	finishRecording.current = () => void stopRecording();

	function cancel() {
		jobGeneration.current++;
		if (recording || busy === "microphone") {
			captureAttempt.current++;
			microphone.current.dispose();
			setRecording(false);
			setLevel(0);
			operation.current = false;
			setBusy(undefined);
			setProgress({ phase: "Recording cancelled." });
			return;
		}
		if (busy === "load" && !progress.phase.startsWith("Loading"))
			client.current.abortDownload();
		else {
			client.current.stop();
			setLoadedModel(undefined);
		}
		setProgress({ phase: "Cancelled. Downloaded models remain saved." });
	}

	async function editTranscript(value: string) {
		setText(value);
		if (activeId) {
			const entry = entries.find((item) => item.id === activeId);
			if (entry) {
				const updated = { ...entry, text: value };
				setEntries((items) =>
					items.map((item) => (item.id === activeId ? updated : item))
				);
				await saveEntry(updated).catch(() =>
					setNotice("Your edit could not be saved. Copy or export the text.")
				);
			}
		}
	}

	async function openEntry(entry: Entry) {
		if (locked) return;
		setActiveId(entry.id);
		setText(entry.text);
		setAudio(entry.audio);
		setResult({
			text: entry.rawText,
			elapsed: entry.elapsed,
			audioSeconds: entry.duration,
			parts: 1,
			model: entry.model ?? modelForLanguage(entry.language),
		});
		setNotice("");
		setError(
			entry.status === "failed"
				? entry.error ||
						"Transcription failed. Load the model and retry this recording."
				: entry.status === "processing"
					? "This recording was interrupted. Load the model and retry."
					: ""
		);
	}

	function exportText() {
		const url = URL.createObjectURL(
			new Blob([text], { type: "text/plain;charset=utf-8" })
		);
		const link = document.createElement("a");
		link.href = url;
		link.download = `veena-${new Date().toISOString().slice(0, 10)}.txt`;
		link.click();
		setTimeout(() => URL.revokeObjectURL(url), 1000);
	}

	return (
		<div className={`transcription-app ${embedded ? "is-embedded" : ""}`}>
			{!embedded && (
				<header className="workspace-header">
					<a href="/" className="workspace-brand">
						<img src="/brand/veena-mark.png" alt="" width="44" height="44" />
						veena
					</a>
					<div className="workspace-title">Transcribe</div>
					<span className="workspace-privacy">Audio stays on your device</span>
					<a href="/download">Get the app</a>
				</header>
			)}
			<Container
				id={embedded ? undefined : "main-content"}
				className="workspace-main"
			>
				<div className="workspace-intro">
					<div>
						<h1>{pageLanguage.name} speech to text</h1>
						<p>
							Speak in {pageLanguage.name}. Keep your words, and your audio, on
							your device.
						</p>
					</div>
					<a className="language-directory" href="/speech-to-text/">
						All 22 languages
					</a>
				</div>
				<div className="workspace-toolbar">
					<div className="language-control">
						<label htmlFor="speech-language">Speech language</label>
						<select
							id="speech-language"
							value={language}
							disabled={locked}
							onChange={(event) => {
								const destination = LANGUAGES.find(
									(item) => item.code === event.target.value
								);
								if (destination && destination.code !== language)
									window.location.assign(
										`/speech-to-text/${destination.slug}/`
									);
							}}
						>
							{LANGUAGES.map((item) => (
								<option key={item.code} value={item.code}>
									{item.name} / {item.nativeName}
								</option>
							))}
						</select>
					</div>
					<span className="connection-label">
						<Icon name="shield" />
						{!online
							? "You’re offline"
							: ready
								? offlineReady
									? "Ready offline"
									: "Model ready"
								: "Private by design"}
					</span>
				</div>
				{mobile ? (
					<section className="device-message">
						<h2>Take Veena with you.</h2>
						<p>
							Browser transcription is currently for desktops. Use the app on
							your phone for reliable recording and offline transcription.
						</p>
						<div className="button-row">
							<a className="primary-button" href={DOWNLOADS.playStore}>
								Get Android app
							</a>
							<a className="secondary-button" href={DOWNLOADS.appStore}>
								Get iPhone app
							</a>
						</div>
					</section>
				) : !supported ? (
					<section className="device-message">
						<h2>This browser can’t run Veena locally.</h2>
						<p>
							Open this page in a current desktop Chrome or Edge browser, or use
							the Veena app.
						</p>
						<a className="primary-button" href="/download">
							Get Veena
						</a>
					</section>
				) : (
					<>
						{!ready && (
							<section
								className="download-panel"
								aria-label="Download model"
								aria-busy={busy === "load"}
							>
								<div className="download-content">
									<div className="download-heading">
										<Icon name="download" />
										<h2>
											{busy === "load"
												? cached.includes(selectedModel)
													? "Opening your saved model"
													: "Getting ready for your voice"
												: "Download model"}
										</h2>
									</div>
									<p className="setup-description">
										You only need to do this once. The model stays in this
										browser so you can turn speech into text on your device.
									</p>
									{busy === "load" ? (
										<div className="download-progress">
											<div className="progress-label" role="status">
												<span>{progress.phase}</span>
												{progress.total !== undefined && (
													<strong>
														{Math.min(
															100,
															Math.round(
																((progress.completed ?? 0) / progress.total) *
																	100
															)
														)}
														%
													</strong>
												)}
											</div>
											<progress
												aria-label="Model download progress"
												value={
													progress.total === undefined
														? undefined
														: (progress.completed ?? 0)
												}
												max={progress.total ?? 1}
											/>
											<p>
												{progress.total && progress.total > 1000
													? `${((progress.completed ?? 0) / 1e6).toFixed(1)} MB of ${Math.round(progress.total / 1e6)} MB`
													: "Keep this page open. This can take a moment."}
											</p>
											<button className="quiet-button" onClick={cancel}>
												Cancel download
											</button>
										</div>
									) : (
										<>
											<p className="model-size">
												<strong>
													{Math.round(MODELS[selectedModel].bytes / 1e6)} MB
												</strong>{" "}
												download <span>Saved for your next visit</span>
											</p>
											{error && (
												<div className="error-message" role="alert">
													{error}
												</div>
											)}
											<div className="setup-actions">
												<button
													className="primary-button"
													onClick={() =>
														void loadModel(!cached.includes(selectedModel))
													}
													disabled={locked || !cacheChecked}
												>
													<Icon name="download" />
													{cached.includes(selectedModel)
														? "Open saved model"
														: "Download model"}
												</button>
												<a className="app-alternative" href="/download">
													Or use the app
												</a>
											</div>
										</>
									)}
									<p className="storage-note">
										Clearing your browser’s saved data removes the model.
									</p>
								</div>
							</section>
						)}
						{(ready || text || audio) && (
							<div className="workspace-grid">
								<aside className="record-panel">
									<div className="panel-heading">
										<h2>Start with your voice</h2>
									</div>
									<p className="record-language">
										<span lang={language} dir="auto">
											{pageLanguage.nativeName}
										</span>
										Speak naturally. Stop when you’re done.
									</p>
									<div
										className={`recorder ${recording ? "is-recording" : ""}`}
									>
										<div className="audio-meter" aria-hidden="true">
											{Array.from({ length: 29 }, (_, i) => (
												<span
													key={i}
													style={{
														height: `${4 + (recording ? Math.min(54, level * 650) * (0.3 + Math.abs(Math.sin(i * 1.7))) : 4 * Math.abs(Math.sin(i * 2.3)))}px`,
													}}
												/>
											))}
										</div>
										<div className="record-time">
											{formatTime(recording ? seconds : 0)}
										</div>
										<button
											ref={recordButton}
											className={`record-button ${recording ? "stop-button" : ""}`}
											disabled={!ready || !!busy}
											onClick={() =>
												recording ? void stopRecording() : void startRecording()
											}
										>
											<Icon name={recording ? "stop" : "mic"} />
											{recording ? "Stop & transcribe" : "Start recording"}
										</button>
										<p>
											{recording
												? "Keep this tab open while recording."
												: "Up to 30 minutes per recording"}
										</p>
									</div>
									{devices.length > 1 && (
										<>
											<label htmlFor="microphone-device">Microphone</label>
											<select
												id="microphone-device"
												value={deviceId}
												disabled={locked}
												onChange={(event) => setDeviceId(event.target.value)}
											>
												<option value="">System default</option>
												{devices.map((device) => (
													<option key={device.deviceId} value={device.deviceId}>
														{device.label || "Microphone"}
													</option>
												))}
											</select>
										</>
									)}
									<div
										className="file-zone"
										onDragOver={(event) => event.preventDefault()}
										onDrop={(event) => {
											event.preventDefault();
											const file = event.dataTransfer.files[0];
											if (file) void importFile(file);
										}}
									>
										<input
											ref={fileInput}
											id="audio-file"
											aria-label="Choose audio file"
											type="file"
											accept="audio/*,.wav,.mp3,.m4a,.ogg,.flac,.webm"
											disabled={!ready || locked}
											onChange={(event) => {
												const file = event.target.files?.[0];
												if (file) void importFile(file);
												event.target.value = "";
											}}
										/>
										<button
											className="secondary-button full-width"
											disabled={!ready || locked}
											onClick={() => fileInput.current?.click()}
										>
											Choose audio file
										</button>
										<p>
											Or drop it here · WAV, MP3, M4A and more
											<br />
											Up to 200 MB and 30 minutes
										</p>
									</div>
									{language === "te" && (
										<button
											className="sample-button"
											onClick={() => void runSample()}
											disabled={!ready || locked}
										>
											Try the 23-second Telugu sample <Icon name="play" />
										</button>
									)}
								</aside>
								<section className="text-panel">
									<div className="panel-heading">
										<h2>Your transcript</h2>
										<span>Edit, copy or save</span>
									</div>
									<div className="status-area" role="status" aria-live="polite">
										<span
											className={busy ? "status-spinner" : "status-dot"}
											aria-hidden="true"
										>
											{busy ? "" : ""}
										</span>
										<span>{progress.phase}</span>
									</div>
									{busy && progress.total !== undefined && (
										<progress
											aria-label={progress.phase}
											value={progress.completed ?? 0}
											max={progress.total}
										/>
									)}
									{ready && error && (
										<div className="error-message" role="alert">
											{error}
										</div>
									)}
									<textarea
										id="transcript"
										aria-label="Transcript"
										lang={
											entries.find((entry) => entry.id === activeId)
												?.language ?? language
										}
										value={partial || text}
										readOnly={locked}
										onChange={(event) =>
											void editTranscript(event.target.value)
										}
										placeholder={`Your ${pageLanguage.name} words will appear here.\nRecord your voice or choose an audio file to begin.`}
										dir={
											/[\u0600-\u06ff]/u.test(text) &&
											["ur", "ks", "sd"].includes(language)
												? "rtl"
												: "auto"
										}
									/>
									<div className="transcript-meta">
										<span>
											{text.trim() ? text.trim().split(/\s+/u).length : 0} words
										</span>
										{result && (
											<span data-testid="transcription-timing">
												{formatTime(result.audioSeconds)} audio ·{" "}
												{(result.elapsed / 1000).toFixed(2)}s processing ·{" "}
												{result.parts} {result.parts === 1 ? "part" : "parts"}
											</span>
										)}
									</div>
									{audio && <AudioPlayback audio={audio} />}
									<div className="transcript-actions">
										<div className="button-row">
											<button
												className="primary-button"
												disabled={!text || locked}
												onClick={() =>
													void navigator.clipboard
														.writeText(text)
														.then(() => setNotice("Transcript copied."))
														.catch(() =>
															setNotice(
																"Select the transcript and copy it with your keyboard."
															)
														)
												}
											>
												Copy text
											</button>
											<button
												className="secondary-button"
												disabled={!text || locked}
												onClick={exportText}
											>
												Download text
											</button>
											<button
												className="quiet-button"
												disabled={!text || locked}
												onClick={() => {
													setText("");
													setAudio(undefined);
													setResult(undefined);
													setActiveId(undefined);
												}}
											>
												Clear
											</button>
										</div>
										{locked && (
											<button className="cancel-button" onClick={cancel}>
												Cancel
											</button>
										)}
									</div>
									<label className="checkbox-label">
										<input
											type="checkbox"
											checked={append}
											disabled={locked}
											onChange={(event) => {
												setAppend(event.target.checked);
												saveSettings({ append: event.target.checked });
											}}
										/>
										Append new recordings to this transcript
									</label>
									{notice && (
										<p className="notice" role="status">
											{notice}
										</p>
									)}
								</section>
							</div>
						)}
						{(ready || entries.length > 0) && (
							<>
								<section className="history-panel">
									<div className="panel-heading">
										<div>
											<h2>On this browser</h2>
											<p>Your recordings and transcripts stay here.</p>
										</div>
										<label className="retention-label">
											Keep latest{" "}
											<select
												aria-label="History limit"
												value={retention}
												disabled={locked}
												onChange={(event) => {
													const value = Number(event.target.value);
													setRetention(value);
													saveSettings({ retention: value });
													void retainHistory(value).then(refreshHistory);
												}}
											>
												{[10, 50, 100].map((n) => (
													<option key={n} value={n}>
														{n}
													</option>
												))}
											</select>
										</label>
									</div>
									{entries.length === 0 ? (
										<div className="history-empty">
											Your first transcription will be saved here.
										</div>
									) : (
										<ul className="history-list">
											{entries.map((entry) => (
												<li
													key={entry.id}
													className={activeId === entry.id ? "active" : ""}
												>
													<button
														className="history-open"
														disabled={locked}
														onClick={() => void openEntry(entry)}
													>
														<strong>{entry.title}</strong>
														<span>
															{entry.text ||
																(entry.status === "failed"
																	? "Transcription failed · audio saved for retry"
																	: entry.status === "processing"
																		? "Unfinished transcription · audio saved for retry"
																		: "No speech detected")}
														</span>
														<small>
															{new Date(entry.date).toLocaleString()} ·{" "}
															{formatTime(entry.duration)} ·{" "}
															{
																LANGUAGES.find((l) => l.code === entry.language)
																	?.name
															}
														</small>
													</button>
													<div className="history-controls">
														<button
															className="quiet-button"
															disabled={locked}
															aria-label={`${entry.saved ? "Unsave" : "Save"} ${entry.title}`}
															onClick={() =>
																void saveEntry({
																	...entry,
																	saved: !entry.saved,
																}).then(refreshHistory)
															}
														>
															{entry.saved ? "★ Saved" : "☆ Save"}
														</button>
														<button
															className="quiet-button"
															disabled={
																locked ||
																entry.language !== language ||
																!ready ||
																selectedModel !==
																	(entry.model ??
																		modelForLanguage(entry.language))
															}
															onClick={() => {
																const generation = ++jobGeneration.current;
																operation.current = true;
																setBusy("transcribe");
																void decodeFile(entry.audio)
																	.then((pcm) => {
																		if (generation !== jobGeneration.current)
																			throw new Error(
																				"Transcription cancelled."
																			);
																		return transcribe(
																			pcm,
																			entry.audio,
																			entry.title,
																			entry.language,
																			generation
																		);
																	})
																	.catch((problem) => {
																		setError(describeError(problem));
																		setBusy(undefined);
																		operation.current = false;
																	});
															}}
														>
															Retry
														</button>
														<button
															className="quiet-button"
															disabled={locked}
															aria-label={`Delete ${entry.title}`}
															onClick={() =>
																void deleteEntry(entry.id).then(() => {
																	if (activeId === entry.id)
																		setActiveId(undefined);
																	return refreshHistory();
																})
															}
														>
															Delete
														</button>
													</div>
												</li>
											))}
										</ul>
									)}
								</section>
								<details className="local-settings">
									<summary>Local preferences & storage</summary>
									<div className="settings-grid">
										<div>
											<label htmlFor="custom-words">Text replacements</label>
											<p>
												One replacement per line:{" "}
												<code>recognised phrase =&gt; preferred phrase</code>
											</p>
											<textarea
												id="custom-words"
												value={customWords}
												disabled={locked}
												onChange={(event) => {
													setCustomWords(event.target.value);
													saveSettings({ customWords: event.target.value });
												}}
												placeholder="Veena app => Veena"
											/>
										</div>
										<div>
											<h3>Downloaded models</h3>
											<p>
												{cached.length
													? cached.map((id) => MODELS[id].name).join(", ")
													: "No models downloaded yet."}
											</p>
											<p>
												Clearing browser data removes models and history. Saved
												history entries are excluded from the retention limit.
											</p>
											<button
												className="secondary-button"
												disabled={locked}
												onClick={() => {
													client.current.stop();
													setLoadedModel(undefined);
													void removeModels()
														.then(refreshModels)
														.then(() =>
															setNotice(
																"Downloaded models removed. Your transcripts are still here."
															)
														)
														.catch((problem) =>
															setError(describeError(problem))
														);
												}}
											>
												Remove downloaded models
											</button>
										</div>
									</div>
								</details>
							</>
						)}
					</>
				)}
				<footer className="workspace-footer">
					<span>
						<Icon name="shield" /> No account or audio uploads
					</span>
					<span>
						Need to dictate into other apps?{" "}
						<a href="/download">Use Veena for desktop.</a>
					</span>
				</footer>
			</Container>
		</div>
	);
}
