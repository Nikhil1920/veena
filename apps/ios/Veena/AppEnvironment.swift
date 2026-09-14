import Foundation
import Observation

enum MicMode: String {
    case hold
    case tap
}

/// App-wide state: settings (persisted in UserDefaults), the engine, the
/// model repository, and the recorder. Shared via the SwiftUI environment.
@Observable
final class AppEnvironment {

    let engine = RustEngine()
    let models = ModelRepository()
    let recorder = MicRecorder()
    let player = AudioPlayer()

    var languageCode: String? {
        didSet {
            UserDefaults.standard.set(languageCode, forKey: "languageCode")
            Task { [weak self] in
                await self?.warmEngine()
            }
        }
    }

    var micMode: MicMode {
        didSet { UserDefaults.standard.set(micMode.rawValue, forKey: "micMode") }
    }

    init() {
        languageCode = UserDefaults.standard.string(forKey: "languageCode")
        micMode = MicMode(rawValue: UserDefaults.standard.string(forKey: "micMode") ?? "") ?? .hold
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
