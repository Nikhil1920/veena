import Foundation
import Observation
import os

/// What the keyboard model needs from its input view controller.
protocol KeyboardHost: AnyObject {
    var hasFullAccess: Bool { get }
    var textBeforeCursor: String? { get }
    func insertText(_ text: String)
    func openContainingApp(_ url: URL, completion: @escaping (Bool) -> Void)
}

/// The keyboard's side of the hand-off described in `KeyboardBridge`: it
/// sends start/stop/cancel to the Veena app, mirrors the app's progress for
/// the UI, and inserts the text that comes back.
///
/// When the app has no live session (it is suspended and can't turn the
/// microphone on from the background), the mic opens Veena instead; the user
/// returns here and the keyboard starts the dictation by itself.
@Observable
final class KeyboardModel {

    enum Phase: Equatable {
        case idle
        /// Sent the user to Veena to turn the microphone on.
        case waking
        /// Start sent; waiting for the app to pick it up.
        case connecting
        case recording(startedAt: Date)
        case transcribing
    }

    enum Notice: Equatable {
        case fullAccess
        case microphonePermission
        case modelMissing(languageCode: String?)
        case microphoneBusy
        case sessionEnded
        case openVeena
        case noSpeech
        case failed(String)
    }

    private(set) var phase: Phase = .idle
    private(set) var notice: Notice?
    private(set) var level: Float = 0
    /// The app holds the microphone for the keyboard right now.
    private(set) var sessionLive = false
    /// A tap on the mic starts recording without opening the app: a live
    /// session, or the app is on screen and can turn the mic on itself.
    private(set) var appReachable = false
    private(set) var sessionEndsAt: Date?
    private(set) var languageCode: String?
    private(set) var installedLanguages: [String] = []
    private(set) var holdToTalk = false
    /// The dictation was auto-started after a trip to the app, so a hold-mode
    /// user has no finger on the key: it finishes with a tap.
    private(set) var tapToFinish = false
    var showsLanguagePicker = false
    /// Devices with a Home button have no system globe key under the keyboard.
    var needsGlobeKey = true
    /// The return key's caption, following the field ("send", "search"...);
    /// nil shows the return symbol.
    var returnKeyLabel: String?

    @ObservationIgnored weak var host: KeyboardHost?

    @ObservationIgnored private var requestID: UUID?
    @ObservationIgnored private var connectDeadline: Date?
    @ObservationIgnored private var pendingDeadline: Date?
    /// When this keyboard last came on screen. A pending start is only taken
    /// up by a keyboard that appeared after it was made: the one the user
    /// returns to, not the one still fading out while Veena opens.
    @ObservationIgnored private var appearedAt = Date.distantFuture
    @ObservationIgnored private var stopWhenRecording = false
    @ObservationIgnored private var stopSent = false
    @ObservationIgnored private var stateObserver: DarwinNotificationObserver?
    @ObservationIgnored private var pollTimer: Timer?
    @ObservationIgnored private var pollInterval: TimeInterval?
    @ObservationIgnored private var noticeClearWork: DispatchWorkItem?

    private static let logger = Logger(subsystem: "com.byanr.veena", category: "keyboard-extension")

    /// How long a sent start may go unanswered before the keyboard assumes
    /// the app is suspended and opens it instead.
    private static let connectTimeout: TimeInterval = 2.5
    /// How long a returning keyboard waits for the session the user started.
    private static let pendingWait: TimeInterval = 3

    // MARK: Lifecycle

    func appear() {
        Self.logger.log("appear: phase=\(String(describing: self.phase), privacy: .public) pending=\(KeyboardSharedSettings.pendingStart != nil)")
        refreshSettings()
        guard host?.hasFullAccess == true else {
            notice = .fullAccess
            return
        }
        if notice == .fullAccess { notice = nil }
        KeyboardSharedSettings.lastSeenWithFullAccess = Date()
        appearedAt = Date()

        stateObserver = DarwinNotificationObserver(name: KeyboardBridge.stateNotification) { [weak self] in
            self?.readState()
        }
        if KeyboardSharedSettings.pendingStart != nil {
            pendingDeadline = Date().addingTimeInterval(Self.pendingWait)
        }
        readState()
    }

    func disappear() {
        Self.logger.log("disappear: phase=\(String(describing: self.phase), privacy: .public)")
        appearedAt = .distantFuture
        // A hidden keyboard must never leave the microphone recording.
        if let requestID, phase == .connecting || isRecording {
            send(.cancel, requestID: requestID)
        }
        if phase != .waking {
            reset()
        }
        stateObserver = nil
        setPolling(nil)
    }

    // MARK: Settings

    func refreshSettings() {
        installedLanguages = KeyboardSharedSettings.installedLanguageCodes
        languageCode = KeyboardSharedSettings.effectiveKeyboardLanguage
        holdToTalk = KeyboardSharedSettings.micMode == "hold"
    }

    func selectLanguage(_ code: String) {
        KeyboardSharedSettings.keyboardLanguageCode = code
        showsLanguagePicker = false
        refreshSettings()
    }

    // MARK: Mic

    var isRecording: Bool {
        if case .recording = phase { return true }
        return false
    }

    var isBusy: Bool {
        phase == .waking || phase == .connecting || phase == .transcribing
    }

    func micTapped() {
        guard ensureFullAccess() else { return }
        switch phase {
        case .idle:
            begin()
        case .recording:
            stop()
        case .connecting:
            cancel()
        case .waking, .transcribing:
            break
        }
    }

    func micPressed() {
        guard ensureFullAccess() else { return }
        switch phase {
        case .idle:
            begin()
        case .recording where tapToFinish:
            stop()
        default:
            break
        }
    }

    func micReleased() {
        switch phase {
        case .recording where !tapToFinish:
            stop()
        case .connecting where !tapToFinish:
            stopWhenRecording = true // Released before the app started recording.
        default:
            break
        }
    }

    func cancel() {
        if let requestID {
            send(.cancel, requestID: requestID)
        }
        reset()
    }

    func openVeena() {
        host?.openContainingApp(KeyboardBridge.setupURL) { _ in }
    }

    private func ensureFullAccess() -> Bool {
        guard host?.hasFullAccess == true else {
            notice = .fullAccess
            return false
        }
        return true
    }

    private func begin() {
        dismissNotice()
        guard let code = languageCode, installedLanguages.contains(code) else {
            notice = .modelMissing(languageCode: languageCode)
            return
        }
        tapToFinish = false
        if KeyboardBridge.readState()?.acceptsStart() == true {
            sendStart(languageCode: code)
        } else {
            wake(languageCode: code)
        }
    }

    private func sendStart(languageCode code: String) {
        Self.logger.log("start \(code, privacy: .public)")
        let id = UUID()
        requestID = id
        stopWhenRecording = false
        send(.start, requestID: id, languageCode: code)
        connectDeadline = Date().addingTimeInterval(Self.connectTimeout)
        phase = .connecting
        setPolling(0.1)
    }

    private func stop() {
        guard let requestID else { return }
        send(.stop, requestID: requestID)
        stopSent = true
        phase = .transcribing
        level = 0
    }

    /// Opens Veena to turn the microphone on; the dictation starts when the
    /// user comes back (see `readState`).
    private func wake(languageCode code: String) {
        Self.logger.log("waking Veena for \(code, privacy: .public)")
        KeyboardSharedSettings.pendingStart = PendingKeyboardStart(languageCode: code)
        phase = .waking
        host?.openContainingApp(KeyboardBridge.sessionURL) { [weak self] opened in
            Self.logger.log("open Veena: \(opened)")
            DispatchQueue.main.async {
                guard let self, !opened else { return }
                KeyboardSharedSettings.pendingStart = nil
                self.phase = .idle
                self.notice = .openVeena
            }
        }
    }

    private func send(_ action: KeyboardCommand.Action, requestID: UUID, languageCode: String? = nil) {
        KeyboardBridge.send(KeyboardCommand(
            requestID: requestID,
            action: action,
            languageCode: languageCode ?? self.languageCode ?? ""
        ))
    }

    private func reset() {
        requestID = nil
        connectDeadline = nil
        stopWhenRecording = false
        stopSent = false
        tapToFinish = false
        level = 0
        phase = .idle
        setPolling(stateObserver == nil ? nil : 1)
    }

    // MARK: App state

    private func readState() {
        let now = Date()
        let state = KeyboardBridge.readState()
        sessionLive = state.map { $0.phase != .off && $0.isAlive(at: now) } ?? false
        appReachable = state?.acceptsStart(at: now) ?? false
        sessionEndsAt = sessionLive ? state?.sessionEndsAt : nil

        let awaitingSession = resumePendingStart(state, now: now)

        guard let requestID else {
            setPolling(awaitingSession ? 0.1 : 1)
            return
        }

        if let state, state.requestID == requestID {
            switch state.phase {
            case .recording where stopSent:
                break // The app hasn't seen the stop yet; stay on "transcribing".
            case .recording:
                phase = .recording(startedAt: state.recordingStartedAt ?? now)
                level = state.level
                if stopWhenRecording {
                    stopWhenRecording = false
                    stop()
                }
            case .transcribing:
                phase = .transcribing
                level = 0
            case .idle, .off:
                if let failure = state.failure, failure.requestID == requestID {
                    show(failure)
                    reset()
                } else if let result = state.result, result.requestID == requestID {
                    reset()
                    insert(result.text)
                } else if !isRecording && phase != .transcribing {
                    reset() // Cancelled.
                }
            }
        } else if phase == .connecting, let connectDeadline, now > connectDeadline {
            // The app never picked the start up: it was suspended between
            // heartbeats or is gone. Turn the microphone on the long way.
            let code = languageCode ?? ""
            reset()
            wake(languageCode: code)
            return
        }

        if isRecording || phase == .transcribing, state?.isAlive(at: now) != true {
            reset()
            notice = .sessionEnded
        }
    }

    /// Starts the dictation the user asked for before being sent to Veena,
    /// once the session they started there is live. Returns true while still
    /// waiting for that session.
    private func resumePendingStart(_ state: KeyboardSessionState?, now: Date) -> Bool {
        guard let pending = KeyboardSharedSettings.pendingStart, requestID == nil,
              appearedAt > pending.createdAt, appearedAt != .distantFuture else { return false }
        guard now.timeIntervalSince(pending.createdAt) < KeyboardBridge.pendingStartLifetime else {
            KeyboardSharedSettings.pendingStart = nil
            if phase == .waking { phase = .idle }
            return false
        }
        if let state, state.phase != .off, state.isAlive(at: now) {
            KeyboardSharedSettings.pendingStart = nil
            pendingDeadline = nil
            sendStart(languageCode: pending.languageCode)
            tapToFinish = true
            return false
        }
        if let pendingDeadline, now > pendingDeadline {
            Self.logger.log("pending start expired without a session")
            // Back without a live session (the user turned it off, or never
            // allowed the microphone): leave the mic as a plain button again.
            KeyboardSharedSettings.pendingStart = nil
            self.pendingDeadline = nil
            if phase == .waking { phase = .idle }
            return false
        }
        return true
    }

    private func show(_ failure: KeyboardFailure) {
        switch failure.reason {
        case .microphonePermission: notice = .microphonePermission
        case .modelMissing: notice = .modelMissing(languageCode: failure.languageCode)
        case .microphoneUnavailable: notice = .microphoneBusy
        case .sessionEnded: notice = .sessionEnded
        case .transcriptionFailed: notice = .failed(failure.message ?? "Transcription failed")
        }
    }

    private func insert(_ text: String) {
        var output = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !output.isEmpty else {
            showTransient(.noSpeech)
            return
        }
        if let last = host?.textBeforeCursor?.last, !last.isWhitespace {
            output = " " + output
        }
        host?.insertText(output + " ")
    }

    // MARK: Notices and polling

    func dismissNotice() {
        noticeClearWork?.cancel()
        if notice != .fullAccess { notice = nil }
    }

    private func showTransient(_ transient: Notice) {
        notice = transient
        noticeClearWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            if self?.notice == transient { self?.notice = nil }
        }
        noticeClearWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }

    /// Darwin notifications carry each change; polling covers the level meter
    /// between them and notices an app that went away.
    private func setPolling(_ interval: TimeInterval?) {
        guard interval != pollInterval else { return }
        pollTimer?.invalidate()
        pollTimer = nil
        pollInterval = interval
        guard let interval else { return }
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            self?.readState()
        }
        RunLoop.main.add(timer, forMode: .common)
        pollTimer = timer
    }
}
