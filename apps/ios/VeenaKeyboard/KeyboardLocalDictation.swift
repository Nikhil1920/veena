import AVFAudio
import UIKit
import os

/// In-place dictation: the keyboard records and transcribes by itself, so the
/// mic works in any app without a trip to Veena first (iOS 26 and later).
/// `KeyboardWebEngine` does the work; this class decides when it may be used
/// and watches over one dictation at a time.
///
/// It is one of two routes the mic can take. The other is the hand-off to the
/// Veena app (`KeyboardBridge`), which stays the route whenever this one
/// can't run: an older iOS, a model too large for a web view's process, a
/// keyboard short of memory, or a failure earlier in this keyboard's life.
final class KeyboardLocalDictation {

    enum Event {
        case started
        case level(Float)
        /// Recording ended (by `stop()` or the length limit); the text follows.
        case stopped
        case finished(String)
        /// The microphone never started: the hand-off can still take the dictation.
        case microphoneFailed
        case failed(String)
    }

    var onEvent: ((Event) -> Void)?

    private var engine: KeyboardWebEngine?
    private var active = false
    /// Set once this route fails, so this keyboard's later dictations go through the app.
    private var disabled = false
    private var watchdog: DispatchWorkItem?
    private var release: DispatchWorkItem?

    private static let logger = Logger(subsystem: "com.byanr.veena", category: "keyboard-local")

    /// What the keyboard itself must have to spare, as iOS ends one that
    /// passes its limit: about 10 MB for the web view and its page and 6 MB
    /// more while the model passes through, with margin. Short of it the mic
    /// uses the hand-off.
    private static let headroomForWebView = 24 * 1024 * 1024
    private static let headroomForModel = 12 * 1024 * 1024
    private static let microphoneTimeout: TimeInterval = 8
    private static let longestRecording: TimeInterval = 120
    /// A first dictation's model is still loading, and an iPhone may take as
    /// long to transcribe as the recording ran.
    private static let transcriptionTimeout: TimeInterval = 45
    private static let transcriptionTimeoutPerSecond = 1.5
    /// How long the model stays loaded after the keyboard goes away.
    private static let lingerHidden: TimeInterval = 60
    private static let lingerIdle: TimeInterval = 300

    #if DEBUG
    /// Test hook: simulators have no voice, so a Debug keyboard transcribes
    /// this recording, when it exists, instead of what its microphone heard.
    static let testAudio = KeyboardBridge.directory?.appendingPathComponent("test-audio.wav")
    #endif

    static var isSupported: Bool {
        guard #available(iOS 26.0, *) else { return false }
        return KeyboardWebEngine.isBundled
    }

    /// The model in-place dictation would run for `code`, if it can take it.
    private static func model(forLanguage code: String) -> SharedModelStore.Files? {
        guard let files = SharedModelStore.files(modelID: ModelCatalog.model(forLanguage: code).id),
              files.fitsInKeyboard else { return nil }
        return files
    }

    /// Whether dictations in `code` run in the keyboard right now.
    func isAvailable(languageCode code: String) -> Bool {
        guard Self.isSupported, !disabled, KeyboardSharedSettings.inPlaceDictation,
              KeyboardSharedSettings.inPlaceFailures < KeyboardBridge.inPlaceFailureLimit else { return false }
        // This process keeps the permission it read when it started, so the
        // app's own reading counts too. Without it the hand-off asks.
        guard AVAudioApplication.shared.recordPermission == .granted
                || KeyboardSharedSettings.appMicrophoneGranted else { return false }
        guard Self.model(forLanguage: code) != nil else { return false }
        // Where iOS doesn't say (0, the simulator), there is room.
        let headroom = os_proc_available_memory()
        return headroom == 0 || headroom >= (engine == nil ? Self.headroomForWebView : Self.headroomForModel)
    }

    /// Loads the web view and its page ahead of the first dictation, which
    /// otherwise starts recording a second or two after the mic is touched.
    /// The model still waits for that touch.
    func prepare(languageCode code: String, in host: UIView) {
        guard !active, isAvailable(languageCode: code) else { return }
        _ = engine(forLanguage: code, in: host)
    }

    private func engine(forLanguage code: String, in host: UIView) -> KeyboardWebEngine? {
        guard let model = Self.model(forLanguage: code) else { return nil }
        if let engine, engine.model.modelID != model.modelID {
            dropEngine()
        }
        let engine = engine ?? KeyboardWebEngine(host: host, model: model)
        self.engine = engine
        engine.onEvent = { [weak self] event in self?.handle(event) }
        return engine
    }

    /// Starts recording. False when it can't; the caller then hands off.
    func start(languageCode code: String, in host: UIView) -> Bool {
        guard !active, isAvailable(languageCode: code),
              let engine = engine(forLanguage: code, in: host) else { return false }
        release?.cancel()
        active = true
        var testAudio = false
        #if DEBUG
        testAudio = Self.testAudio.map { FileManager.default.fileExists(atPath: $0.path) } ?? false
        #endif
        Self.logger.log("start \(Self.memory(), privacy: .public)")
        engine.start(job: UUID().uuidString, languageCode: code, testAudio: testAudio)
        watch(for: Self.microphoneTimeout) { [weak self] in
            Self.logger.error("the microphone didn't start in time")
            self?.fail(microphone: true, message: "")
        }
        return true
    }

    func stop() {
        guard active else { return }
        engine?.stop()
    }

    func cancel() {
        guard active else { return }
        active = false
        watchdog?.cancel()
        engine?.cancel()
        scheduleRelease(after: Self.lingerIdle)
    }

    /// The keyboard left the screen: nothing may keep recording, and the
    /// model's memory goes back soon unless the keyboard returns.
    func keyboardHidden() {
        cancel()
        scheduleRelease(after: Self.lingerHidden)
    }

    func keyboardShown() {
        scheduleRelease(after: Self.lingerIdle)
    }

    private func handle(_ event: KeyboardWebEngine.Event) {
        guard active else {
            // The web view failed between dictations: start afresh next time.
            if case .failed = event { dropEngine() }
            return
        }
        switch event {
        case .started:
            watch(for: Self.longestRecording) { [weak self] in self?.stop() }
            onEvent?(.started)
        case .level(let level):
            onEvent?(.level(level))
        case .stopped(let seconds):
            let limit = Self.transcriptionTimeout + seconds * Self.transcriptionTimeoutPerSecond
            watch(for: limit) { [weak self] in
                Self.logger.error("transcription didn't finish in time")
                self?.fail(microphone: false, message: "Transcription took too long")
            }
            onEvent?(.stopped)
        case .result(let text):
            Self.logger.log("finished \(Self.memory(), privacy: .public)")
            if KeyboardSharedSettings.inPlaceFailures != 0 {
                KeyboardSharedSettings.inPlaceFailures = 0
            }
            active = false
            watchdog?.cancel()
            scheduleRelease(after: Self.lingerIdle)
            onEvent?(.finished(text))
        case .failed(let microphone, let message):
            fail(microphone: microphone, message: message)
        }
    }

    /// Ends the dictation and, for this keyboard's life, this route with it:
    /// whatever went wrong once (iOS refusing the microphone, the engine's
    /// process ended for memory) would likely go wrong again, and the hand-off
    /// still works. Failures in a row are counted across keyboards, so an
    /// iPhone on which this never works stops trying.
    private func fail(microphone: Bool, message: String) {
        guard active else { return }
        Self.logger.error("failed \(Self.memory(), privacy: .public)")
        active = false
        disabled = true
        KeyboardSharedSettings.inPlaceFailures += 1
        watchdog?.cancel()
        dropEngine()
        onEvent?(microphone ? .microphoneFailed : .failed(message))
    }

    private func dropEngine() {
        release?.cancel()
        engine?.tearDown()
        engine = nil
    }

    /// What iOS holds against the keyboard's memory limit, for the log.
    private static func memory() -> String {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return "[memory unknown]" }
        let megabyte = 1_048_576.0
        return String(
            format: "[footprint %.1f MB, peak %.1f MB, headroom %.1f MB]",
            Double(info.phys_footprint) / megabyte,
            Double(info.ledger_phys_footprint_peak) / megabyte,
            Double(os_proc_available_memory()) / megabyte
        )
    }

    private func watch(for interval: TimeInterval, _ expired: @escaping () -> Void) {
        watchdog?.cancel()
        let work = DispatchWorkItem(block: expired)
        watchdog = work
        DispatchQueue.main.asyncAfter(deadline: .now() + interval, execute: work)
    }

    private func scheduleRelease(after interval: TimeInterval) {
        release?.cancel()
        guard engine != nil else { return }
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.active else { return }
            Self.logger.log("releasing the speech engine")
            self.dropEngine()
        }
        release = work
        DispatchQueue.main.asyncAfter(deadline: .now() + interval, execute: work)
    }
}
