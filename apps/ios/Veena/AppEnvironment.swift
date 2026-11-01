import Foundation
import Observation

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

    var micMode: MicMode {
        didSet { UserDefaults.standard.set(micMode.rawValue, forKey: "micMode") }
    }

    /// The keyboard opened the app to start a session (veena://keyboard/session).
    var showKeyboardSession = false
    /// Keyboard setup was asked for from outside the Settings tab.
    var showKeyboardSetup = false

    init() {
        keyboardSession = KeyboardSessionController(engine: engine, models: models)
        languageCode = UserDefaults.standard.string(forKey: "languageCode")
        micMode = MicMode(rawValue: UserDefaults.standard.string(forKey: "micMode") ?? "") ?? .hold

        keyboardSession.isAppRecording = { [recorder] in recorder.isRecording }
        models.onInstalledModelsChanged = { [weak self] in self?.publishKeyboardLanguages() }
        publishKeyboardLanguages()
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
