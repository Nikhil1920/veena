import AVFAudio
import Foundation
import Observation
import UIKit

enum MicMode: String {
    case hold
    case tap
}

/// App-wide state: settings (persisted in UserDefaults), the engine, the
/// model repository, the recorder, and the keyboard session. Shared via the
/// SwiftUI environment.
@Observable
final class AppEnvironment {

    let engine = RustEngine()
    let models = ModelRepository()
    let recorder = MicRecorder()
    let player = AudioPlayer()
    let keyboardSession: KeyboardSessionController

    var languageCode: String? {
        didSet {
            UserDefaults.standard.set(languageCode, forKey: "languageCode")
            KeyboardSharedSettings.appLanguageCode = languageCode
            Task { [weak self] in
                await self?.warmEngine()
            }
        }
    }

    /// The mic button's mode, shared with the keyboard through the App Group.
    var micMode: MicMode {
        didSet {
            UserDefaults.standard.set(micMode.rawValue, forKey: "micMode")
            KeyboardSharedSettings.micMode = micMode.rawValue
        }
    }

    /// Show text while a dictation in the app is still being recorded.
    var liveTranscription: Bool {
        didSet { UserDefaults.standard.set(liveTranscription, forKey: "liveTranscription") }
    }

    /// The keyboard opened the app to start a session (veena://keyboard/session).
    var showKeyboardSession = false
    /// Keyboard setup was asked for from outside the Settings tab.
    var showKeyboardSetup = false
    @ObservationIgnored private var activeObserver: NSObjectProtocol?

    init() {
        keyboardSession = KeyboardSessionController(engine: engine, models: models)
        languageCode = UserDefaults.standard.string(forKey: "languageCode")
        micMode = MicMode(rawValue: UserDefaults.standard.string(forKey: "micMode") ?? "") ?? .hold
        liveTranscription = UserDefaults.standard.object(forKey: "liveTranscription") as? Bool ?? true
        KeyboardSharedSettings.micMode = micMode.rawValue

        keyboardSession.isAppRecording = { [recorder] in recorder.isRecording }
        models.onInstalledModelsChanged = { [weak self] in self?.publishKeyboardLanguages() }
        publishKeyboardLanguages()
        // Becoming active follows every answer to the microphone prompt and
        // every trip to Settings, the two places the permission changes.
        publishMicrophonePermission()
        activeObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.publishMicrophonePermission()
        }
        // iOS can relaunch the app while the user is in Settings turning the
        // keyboard on; bring the tour back where they left it.
        showKeyboardSetup = languageCode != nil && KeyboardSetupView.savedStep != nil
    }

    /// Tells the keyboard which languages it can offer and which one the app uses.
    func publishKeyboardLanguages() {
        KeyboardSharedSettings.appLanguageCode = languageCode
        KeyboardSharedSettings.installedLanguageCodes = languages.map(\.code).filter {
            models.isDownloaded(ModelCatalog.model(forLanguage: $0))
        }
    }

    /// Tells the keyboard whether Veena may record, which it needs to know
    /// before dictating by itself.
    private func publishMicrophonePermission() {
        KeyboardSharedSettings.appMicrophoneGranted = AVAudioApplication.shared.recordPermission == .granted
    }

    /// veena://keyboard/session and veena://keyboard/setup, opened by the keyboard.
    func handle(_ url: URL) {
        guard url.scheme == "veena", url.host == "keyboard" else { return }
        switch url.path {
        case "/session":
            keyboardSession.startSession()
            showKeyboardSetup = false // One modal at a time; the session screen wins.
            showKeyboardSession = true
        case "/setup":
            showKeyboardSetup = true
        default:
            break
        }
    }

    var selectedLanguage: Language? {
        languageCode.flatMap { language(byCode: $0) }
    }

    /// Loads (or reloads) the engine for the selected language.
    /// Mirrors Android AppViewModel.warmEngine() + LaunchedEffect(languageCode).
    func warmEngine() async {
        guard let code = languageCode else { return }
        try? await engine.ensureLanguage(code, models: models)
    }

    /// Synchronous wrapper for didSet (fire-and-forget).
    @MainActor
    func warmEngineSync() {
        Task { await warmEngine() }
    }
}
