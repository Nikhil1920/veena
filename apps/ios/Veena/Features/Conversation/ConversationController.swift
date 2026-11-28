import AVFoundation
import SwiftData
import UIKit

/// Captions of a conversation for the Conversation screen, which deaf and
/// hard-of-hearing people use to follow what is said around them.
///
/// `AppEnvironment` owns it, so the conversation outlives the tab rebuild a
/// language change causes. `RootView` tells it when the screen is in front
/// (`update`); it listens then, unless the user paused. While listening, the
/// microphone's audio goes to the engine's conversation captions
/// (`ConversationTranscriber`) from a loop off the main thread, and nothing
/// of it is kept. With saving on, the conversation is one History entry,
/// without audio, rewritten as it grows.
@Observable
final class ConversationController {

    enum Status: Equatable {
        /// The screen is closed or the app is in the background.
        case idle
        /// Listening, while the language's model loads.
        case loading(languageName: String)
        case listening
        case paused
        case microphoneDenied
        case failed(String)
    }

    private(set) var items: [ConversationItem] = []
    /// The utterance still being spoken; its words can still change.
    private(set) var partial = ""
    private(set) var status: Status = .idle
    /// The user paused listening; it stays paused until they resume.
    private(set) var isPaused = false
    /// Listening stopped on a failure, shown in `status`, and stays stopped
    /// until the user taps play, reopens the screen or picks a language.
    private(set) var isStalled = false
    /// A call or another app took the microphone; listening resumes when
    /// it is handed back.
    private(set) var isInterrupted = false

    /// The play button shows: listening is off and the user can turn it on.
    var canResume: Bool { isPaused || isStalled || isInterrupted }

    /// Point size of the captions.
    var fontSize: Double {
        didSet { UserDefaults.standard.set(fontSize, forKey: "conversationFontSize") }
    }

    /// Keep each conversation as a History entry.
    var saveToHistory: Bool {
        didSet {
            UserDefaults.standard.set(saveToHistory, forKey: "saveConversations")
            if saveToHistory { saveHistory() }
        }
    }

    static let fontSizes: ClosedRange<Double> = 20...56
    static let fontSizeStep: Double = 2
    /// Opening the screen after this long without a new item starts a new
    /// conversation.
    static let staleAfter: TimeInterval = 30 * 60

    /// The history store's main context; RootView sets it.
    @ObservationIgnored var modelContext: ModelContext?

    @ObservationIgnored private let engine: RustEngine
    @ObservationIgnored private let models: ModelRepository
    @ObservationIgnored private let recorder: MicRecorder
    @ObservationIgnored private let keyboardSession: KeyboardSessionController

    // What `update` was told, and what stops listening besides it.
    @ObservationIgnored private var screenActive = false
    @ObservationIgnored private var languageCode: String?
    @ObservationIgnored private var requestingPermission = false

    /// One stretch of listening in one language.
    private final class Run {
        let languageCode: String
        let mic: ConversationMic
        var loop: Task<Void, Never>?

        init(languageCode: String, mic: ConversationMic) {
            self.languageCode = languageCode
            self.mic = mic
        }
    }

    @ObservationIgnored private var run: Run?
    /// The latest run's loop. The next run starts once it has flushed, so
    /// lines never arrive out of order.
    @ObservationIgnored private var lastLoop: Task<Void, Never>?
    /// The History entry this conversation is saved to.
    @ObservationIgnored private var historyID: UUID?
    @ObservationIgnored private var interruptionObserver: NSObjectProtocol?
    #if DEBUG
    @ObservationIgnored private var testAudio: DebugMicStream?
    @ObservationIgnored private var testAudioLoaded = false
    #endif

    init(
        engine: RustEngine,
        models: ModelRepository,
        recorder: MicRecorder,
        keyboardSession: KeyboardSessionController
    ) {
        self.engine = engine
        self.models = models
        self.recorder = recorder
        self.keyboardSession = keyboardSession
        let defaults = UserDefaults.standard
        let savedSize = defaults.object(forKey: "conversationFontSize") as? Double
        fontSize = savedSize.map { min(max($0, Self.fontSizes.lowerBound), Self.fontSizes.upperBound) } ?? 32
        saveToHistory = defaults.object(forKey: "saveConversations") as? Bool ?? true

        // A call or another app taking the microphone stops listening.
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard let self,
                  let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw),
                  run != nil || isInterrupted else { return }
            isInterrupted = type == .began
            reconcile()
        }
    }

    deinit {
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
        }
    }

    // MARK: Screen

    /// Whether the microphone is on for the conversation.
    var isListening: Bool { run != nil }

    /// The transcript as text, for sharing and History.
    var transcriptText: String { ConversationTranscript.text(for: items) }

    /// RMS of the latest audio heard, 0 when not listening.
    func micLevel() -> Float { run?.mic.level ?? 0 }

    /// Called with whether the Conversation screen is in front (its tab is
    /// selected and the app is not in the background) and the app's
    /// language. Listening follows: it starts or stops, or moves to a new
    /// language after flushing the old one.
    func update(screenActive active: Bool, languageCode code: String?) {
        let opened = active && !screenActive
        let languageChanged = code != languageCode
        screenActive = active
        languageCode = code
        if opened || languageChanged {
            isStalled = false
        }
        if opened {
            isInterrupted = false
            if let last = items.last, Date().timeIntervalSince(last.time) > Self.staleAfter {
                startNewConversation()
            }
        }
        reconcile(restart: languageChanged)
    }

    /// The pause/play button.
    func togglePause() {
        if isStalled || isInterrupted {
            isStalled = false
            isInterrupted = false
            isPaused = false
        } else {
            isPaused.toggle()
        }
        reconcile()
    }

    /// Adds a reply the user typed for the other person to read.
    func addTyped(_ text: String) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        items.append(ConversationItem(kind: .typed, time: Date(), text: text))
        saveHistory()
    }

    /// Clears the screen. What comes next is a new conversation, and a new
    /// History entry.
    func startNewConversation() {
        items.removeAll()
        partial = ""
        historyID = nil
    }

    // MARK: Listening

    /// Starts or stops listening to match the screen, the pause button and
    /// interruptions. `restart` moves a running conversation to the current
    /// language.
    private func reconcile(restart: Bool = false) {
        guard screenActive, !isPaused, !isStalled, !isInterrupted, let code = languageCode else {
            let wasListening = run != nil
            stopListening()
            if wasListening && !recorder.isRecording && !keyboardSession.isLive {
                try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            }
            if isPaused {
                status = .paused
            } else if isStalled {
                // The failure stays on screen.
            } else if isInterrupted && screenActive {
                status = .failed("Another app is using the microphone.")
            } else {
                status = .idle
            }
            return
        }
        if let run, run.languageCode == code, !restart { return }
        stopListening()
        startListening(in: code)
    }

    private func startListening(in code: String) {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            break
        case .undetermined:
            status = .idle
            guard !requestingPermission else { return }
            requestingPermission = true
            AVAudioApplication.requestRecordPermission { granted in
                DispatchQueue.main.async {
                    self.requestingPermission = false
                    if granted {
                        self.reconcile()
                    } else {
                        self.status = .microphoneDenied
                    }
                }
            }
            return
        default:
            status = .microphoneDenied
            return
        }
        guard !recorder.isRecording else {
            stall("Finish the dictation on the Dictate tab first.")
            return
        }
        // The keyboard session holds the microphone through its own engine.
        keyboardSession.endSession()

        #if DEBUG
        if !testAudioLoaded {
            testAudioLoaded = true
            testAudio = DebugMicStream.load()
        }
        let mic = ConversationMic(testAudio: testAudio)
        #else
        let mic = ConversationMic()
        #endif
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
            try mic.start()
        } catch {
            stall("The microphone is unavailable. \(error.localizedDescription)")
            return
        }

        let run = Run(languageCode: code, mic: mic)
        mic.onStopped = { [weak self, weak run] in
            guard let self, let run, self.run === run else { return }
            stall("The microphone stopped.")
        }
        self.run = run
        status = modelLoaded(for: code) ? .listening : .loading(languageName: Self.languageName(code))
        UIApplication.shared.isIdleTimerDisabled = true

        let previous = lastLoop
        let loop = Task.detached(priority: .userInitiated) { [self] in
            await previous?.value
            await listen(run)
        }
        run.loop = loop
        lastLoop = loop
    }

    private func stopListening() {
        guard let run else { return }
        self.run = nil
        run.mic.stop()
        run.loop?.cancel()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    /// The listening loop, off the main thread: hand the engine what the
    /// microphone heard, step, show what changed, wait. Once cancelled it
    /// flushes, so the utterance in progress still ends up on screen.
    private func listen(_ run: Run) async {
        let transcriber: ConversationTranscriber
        do {
            transcriber = try engine.openConversation(languageCode: run.languageCode, models: models)
        } catch {
            await MainActor.run { failed(run, error) }
            return
        }
        defer { transcriber.close() }
        await MainActor.run {
            if self.run === run { status = .listening }
        }

        let clock = ContinuousClock()
        while !Task.isCancelled {
            transcriber.push(run.mic.drain())
            let startedAt = clock.now
            let update = transcriber.step()
            // A slow phone spends at most two thirds of its time decoding.
            let pause = max(Self.minPause, (clock.now - startedAt) / 2)
            if let update {
                await MainActor.run { apply(update, from: run) }
            }
            try? await Task.sleep(for: pause)
        }

        transcriber.push(run.mic.drain())
        let last = transcriber.flush()
        await MainActor.run {
            if let last { apply(last, from: run) }
            if self.run == nil { partial = "" }
        }
    }

    private static let minPause = Duration.milliseconds(100)

    private func apply(_ update: ConversationTranscriber.Update, from run: Run) {
        let now = Date()
        items.append(contentsOf: update.finished.map {
            ConversationItem(kind: .speech, time: now, text: $0)
        })
        // A stopped run's partial is stale; its flush ends that utterance.
        if self.run === run { partial = update.partial }
        if !update.finished.isEmpty { saveHistory() }
    }

    private func failed(_ run: Run, _ error: Error) {
        guard self.run === run else { return }
        let name = Self.languageName(run.languageCode)
        if case EngineError.notReady = error {
            stall("The \(name) model isn't downloaded. Tap the language to download it.")
        } else {
            stall("Couldn't start listening in \(name).")
        }
    }

    /// Stops listening until the user asks again, and says why.
    private func stall(_ message: String) {
        stopListening()
        isStalled = true
        status = .failed(message)
    }

    /// Whether the engine has `code`'s model loaded already. Until it has,
    /// the screen says the model is loading; meanwhile audio waits in the
    /// microphone's queue.
    private func modelLoaded(for code: String) -> Bool {
        guard case .ready(let loaded) = engine.state else { return false }
        return ModelCatalog.model(forLanguage: loaded).id == ModelCatalog.model(forLanguage: code).id
    }

    private static func languageName(_ code: String) -> String {
        language(byCode: code)?.name ?? code
    }

    // MARK: History

    /// Writes the conversation to its History entry, creating it with the
    /// first item.
    private func saveHistory() {
        guard saveToHistory, let modelContext, let first = items.first, let last = items.last else { return }
        let transcript = transcriptText
        let durationMs = last.time.timeIntervalSince(first.time) * 1000
        let id = historyID ?? UUID()
        historyID = id
        let existing = try? modelContext.fetch(
            FetchDescriptor<HistoryEntry>(predicate: #Predicate { $0.id == id })
        ).first
        if let existing {
            existing.transcript = transcript
            existing.durationMs = durationMs
        } else {
            modelContext.insert(HistoryEntry(
                id: id,
                createdAt: first.time,
                languageCode: languageCode ?? "",
                transcript: transcript,
                audioPath: "",
                durationMs: durationMs,
                source: HistoryEntry.conversationSource
            ))
        }
        try? modelContext.save()
    }
}
