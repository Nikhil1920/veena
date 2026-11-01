import AVFoundation
import UIKit
import os

/// Runs dictation for the Veena keyboard extension, which can't record.
///
/// A *session* is a window in which the app keeps the microphone running:
/// that keeps it alive in the background, so the keyboard's start/stop
/// commands are served without bringing the app forward. The keyboard starts
/// one by opening `veena://keyboard/session` (the user then returns to the
/// app they were typing in); dictating into a field inside Veena starts one
/// implicitly. It ends after `KeyboardSharedSettings.sessionMinutes` without
/// dictation, on an audio interruption, or when the user turns it off.
/// Keyboard dictations never touch disk or history.
@Observable
final class KeyboardSessionController {

    private(set) var isLive = false
    private(set) var endsAt: Date?
    private(set) var phase: KeyboardSessionState.Phase = .off
    /// Why the last attempt to start a session failed, for the session screen.
    private(set) var startFailure: KeyboardFailure.Reason?
    /// Dictations that came back with text, for the setup tour's test step.
    private(set) var completedDictations = 0

    /// True while Veena's own recorder holds the microphone.
    var isAppRecording: () -> Bool = { false }

    private let engine: RustEngine
    private let models: ModelRepository
    private let capture = KeyboardAudioCapture()
    private var state = KeyboardSessionState()
    private var processedCommands = Set<UUID>()
    private var active: (requestID: UUID, languageCode: String)?
    private var appInForeground: Bool
    /// When the last transcript was handed to the keyboard; it is cleared
    /// from the shared state shortly after.
    private var resultPostedAt: Date?
    private var timer: Timer?
    private var timerInterval: TimeInterval?
    private var commandObserver: DarwinNotificationObserver?
    private var observers: [NSObjectProtocol] = []

    private static let logger = Logger(subsystem: "com.byanr.veena", category: "keyboard")
    /// Shorter captures are taps, not speech (matches the app's 0.4 s floor).
    private static let minimumSamples = 6_400
    private static let maximumRecording: TimeInterval = 300
    /// A waiting keyboard picks a result up within a poll or two.
    private static let resultLifetime: TimeInterval = 10

    #if DEBUG
    /// Test hook: `simctl launch ... -keyboardTestAudio <name>` makes keyboard
    /// dictations transcribe Documents/<name> instead of the microphone
    /// (simulators have no voice). Remembered across the relaunches Settings
    /// changes cause; `-keyboardTestAudio none` clears it.
    private static let testAudioName: String? = {
        let defaults = UserDefaults.standard
        let args = ProcessInfo.processInfo.arguments
        if let flag = args.firstIndex(of: "-keyboardTestAudio"), args.indices.contains(flag + 1) {
            let name = args[flag + 1]
            defaults.set(name == "none" ? nil : name, forKey: "debug.keyboardTestAudio")
        }
        return defaults.string(forKey: "debug.keyboardTestAudio")
    }()
    #endif

    init(engine: RustEngine, models: ModelRepository) {
        self.engine = engine
        self.models = models
        appInForeground = UIApplication.shared.applicationState != .background
        #if DEBUG
        _ = Self.testAudioName // Remember the launch argument before a relaunch drops it.
        #endif

        // Commands sent before this launch are history, not requests.
        processedCommands = Set(KeyboardBridge.readCommands().map(\.id))
        commandObserver = DarwinNotificationObserver(name: KeyboardBridge.commandNotification) { [weak self] in
            self?.processCommands()
        }
        capture.onStopped = { [weak self] in
            Self.logger.error("microphone stopped after a route change")
            self?.endSession()
        }

        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            appInForeground = true
            processCommands()
            publish()
        })
        observers.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            appInForeground = false
            publish()
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                .flatMap(AVAudioSession.InterruptionType.init(rawValue:))
            if type == .began {
                Self.logger.log("audio interruption ended the keyboard session")
                self?.endSession()
            }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.endSession()
        })

        publish()
    }

    deinit {
        for observer in observers {
            NotificationCenter.default.removeObserver(observer)
        }
        timer?.invalidate()
    }

    // MARK: Session

    /// Turns the microphone on for keyboard dictation, or extends a live
    /// session. Must run while the app is in the foreground.
    @discardableResult
    func startSession() -> Bool {
        startFailure = nil
        if isLive {
            extend()
            publish()
            return true
        }
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            break
        case .undetermined:
            // The prompt is up; the session starts once it's answered yes.
            startFailure = .microphonePermission
            AVAudioApplication.requestRecordPermission { granted in
                DispatchQueue.main.async {
                    if granted {
                        self.startSession()
                    }
                }
            }
            return false
        default:
            startFailure = .microphonePermission
            return false
        }
        if isAppRecording() {
            startFailure = .microphoneUnavailable
            return false
        }

        do {
            let session = AVAudioSession.sharedInstance()
            // Mixable, so music or a video in the app being typed into keeps
            // playing while the microphone waits.
            try session.setCategory(
                .playAndRecord,
                mode: .default,
                options: [.mixWithOthers, .defaultToSpeaker, .allowBluetoothA2DP]
            )
            try session.setActive(true)
            try capture.start()
        } catch {
            Self.logger.error("session start failed: \(error.localizedDescription, privacy: .public)")
            startFailure = .microphoneUnavailable
            return false
        }

        Self.logger.log("keyboard session started")
        isLive = true
        extend()
        setPhase(.idle)
        preloadKeyboardLanguage()
        return true
    }

    /// Turns the microphone off; the keyboard has to bring the app forward
    /// again to start the next session.
    func endSession() {
        guard isLive else { return }
        if let active {
            fail(active.requestID, .sessionEnded, languageCode: active.languageCode)
        }
        capture.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        isLive = false
        endsAt = nil
        Self.logger.log("keyboard session ended")
        setPhase(.off)
    }

    private func extend() {
        endsAt = Date().addingTimeInterval(TimeInterval(KeyboardSharedSettings.sessionMinutes * 60))
    }

    /// Loads the keyboard's model now so the first dictation doesn't wait for it.
    private func preloadKeyboardLanguage() {
        guard let code = KeyboardSharedSettings.effectiveKeyboardLanguage,
              models.isDownloaded(ModelCatalog.model(forLanguage: code)) else { return }
        let engine = engine
        let models = models
        Task.detached(priority: .utility) {
            try? await engine.ensureLanguage(code, models: models)
        }
    }

    // MARK: Commands

    private func processCommands() {
        let now = Date()
        let commands = KeyboardBridge.readCommands()
        // The keyboard keeps only recent commands; forget IDs that fell off.
        processedCommands.formIntersection(commands.map(\.id))
        for command in commands where !processedCommands.contains(command.id) {
            processedCommands.insert(command.id)
            guard now.timeIntervalSince(command.issuedAt) < KeyboardBridge.commandLifetime else { continue }
            Self.logger.log("keyboard command: \(command.action.rawValue, privacy: .public)")
            switch command.action {
            case .start: start(command)
            case .stop: stop(requestID: command.requestID)
            case .cancel: cancel(requestID: command.requestID)
            }
        }
    }

    private func start(_ command: KeyboardCommand) {
        if let active, active.requestID != command.requestID {
            capture.cancelCapture() // A newer start supersedes an orphaned dictation.
            self.active = nil
        }
        // In the foreground the app may turn the microphone on by itself.
        if !isLive && !(appInForeground && startSession()) {
            fail(command.requestID, startFailure ?? .sessionEnded, languageCode: command.languageCode)
            return
        }
        guard !isAppRecording() else {
            fail(command.requestID, .microphoneUnavailable, languageCode: command.languageCode)
            return
        }
        guard models.isDownloaded(ModelCatalog.model(forLanguage: command.languageCode)) else {
            fail(command.requestID, .modelMissing, languageCode: command.languageCode)
            return
        }

        active = (command.requestID, command.languageCode)
        capture.beginCapture()
        extend()
        state.requestID = command.requestID
        state.recordingStartedAt = Date()
        state.result = nil
        state.failure = nil
        setPhase(.recording)
    }

    private func stop(requestID: UUID) {
        guard let active, active.requestID == requestID, phase == .recording else { return }
        var samples = capture.endCapture()
        #if DEBUG
        if let name = Self.testAudioName {
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            if let (decoded, _) = try? AudioFileDecoder.decodeToMono16k(url: docs.appendingPathComponent(name)) {
                samples = decoded
            }
        }
        #endif
        setPhase(.transcribing)

        let engine = engine
        let models = models
        let code = active.languageCode
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let outcome: Result<String, Error>
            if samples.count < Self.minimumSamples {
                outcome = .success("")
            } else {
                outcome = Result { try engine.transcribe(samples, languageCode: code, models: models) }
            }
            DispatchQueue.main.async {
                self?.finish(requestID: requestID, outcome: outcome)
            }
        }
    }

    private func cancel(requestID: UUID) {
        guard let active, active.requestID == requestID else { return }
        self.active = nil
        capture.cancelCapture()
        // A transcription already under way finishes unseen: finish() drops it.
        state.result = nil
        state.failure = nil
        setPhase(isLive ? .idle : .off)
    }

    private func finish(requestID: UUID, outcome: Result<String, Error>) {
        guard active?.requestID == requestID else { return }
        active = nil
        state.requestID = requestID
        switch outcome {
        case .success(let text):
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            state.result = KeyboardResult(requestID: requestID, text: trimmed)
            resultPostedAt = Date()
            state.failure = nil
            if !trimmed.isEmpty {
                completedDictations += 1
                ReviewPrompt.recordDictation()
            }
        case .failure(let error):
            Self.logger.error("transcription failed: \(error.localizedDescription, privacy: .public)")
            state.result = nil
            state.failure = KeyboardFailure(
                requestID: requestID,
                reason: .transcriptionFailed,
                message: error.localizedDescription
            )
        }
        extend()
        setPhase(isLive ? .idle : .off)
    }

    private func fail(_ requestID: UUID, _ reason: KeyboardFailure.Reason, languageCode: String?) {
        Self.logger.log("keyboard dictation failed: \(reason.rawValue, privacy: .public)")
        if active?.requestID == requestID {
            capture.cancelCapture()
            active = nil
        }
        state.requestID = requestID
        state.result = nil
        state.failure = KeyboardFailure(requestID: requestID, reason: reason, languageCode: languageCode)
        setPhase(isLive ? .idle : .off)
    }

    // MARK: State publishing

    private func setPhase(_ newPhase: KeyboardSessionState.Phase) {
        phase = newPhase
        state.phase = newPhase
        if newPhase != .recording {
            state.recordingStartedAt = nil
        }
        publish()
    }

    /// Writes the state for the keyboard and keeps the heartbeat going while
    /// the app can take commands: fast while recording (the level meter),
    /// once a second otherwise, not at all once the app is about to suspend.
    private func publish() {
        state.heartbeat = Date()
        state.appInForeground = appInForeground
        state.sessionEndsAt = endsAt
        state.level = phase == .recording ? capture.level : 0
        KeyboardBridge.writeState(state)

        let interval: TimeInterval? = phase == .recording ? 0.1 : (appInForeground || isLive ? 1 : nil)
        guard interval != timerInterval else { return }
        timer?.invalidate()
        timer = nil
        timerInterval = interval
        guard let interval else { return }
        let newTimer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            self?.tick()
        }
        RunLoop.main.add(newTimer, forMode: .common)
        timer = newTimer
    }

    private func tick() {
        let now = Date()
        // Transcripts don't linger in the shared container.
        if state.result != nil, let resultPostedAt,
           now.timeIntervalSince(resultPostedAt) > Self.resultLifetime {
            state.result = nil
            self.resultPostedAt = nil
        }
        if isLive, phase == .idle, let endsAt, now >= endsAt {
            endSession()
            return
        }
        if phase == .recording, let active,
           let startedAt = state.recordingStartedAt,
           now.timeIntervalSince(startedAt) > Self.maximumRecording {
            stop(requestID: active.requestID)
            return
        }
        publish()
    }
}
