import AVFoundation
import Foundation

/// What the keyboard needs before it can dictate, as far as the app can see.
struct KeyboardSetupStatus: Equatable {
    /// nil when iOS doesn't say which keyboards are enabled.
    var keyboardAdded: Bool?
    /// The keyboard has run with Full Access at least once (it records that in
    /// the App Group; without Full Access it can't write there).
    var fullAccessSeen: Bool
    var microphone: AVAudioApplication.recordPermission
    var languageCode: String?
    var modelInstalled: Bool

    static func read(models: ModelRepository) -> KeyboardSetupStatus {
        let code = KeyboardSharedSettings.effectiveKeyboardLanguage
        return KeyboardSetupStatus(
            keyboardAdded: keyboardAdded(),
            fullAccessSeen: KeyboardSharedSettings.lastSeenWithFullAccess != nil,
            microphone: AVAudioApplication.shared.recordPermission,
            languageCode: code,
            modelInstalled: code.map { models.isDownloaded(ModelCatalog.model(forLanguage: $0)) } ?? false
        )
    }

    var isComplete: Bool {
        keyboardAdded != false && fullAccessSeen && microphone == .granted && modelInstalled
    }

    /// The system lists enabled keyboards (third-party ones by bundle ID)
    /// under the global AppleKeyboards preference.
    private static func keyboardAdded() -> Bool? {
        guard let keyboards = UserDefaults.standard.stringArray(forKey: "AppleKeyboards") else { return nil }
        return keyboards.contains { $0.hasPrefix(KeyboardBridge.keyboardBundleID) }
    }
}
