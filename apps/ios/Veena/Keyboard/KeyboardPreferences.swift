import Foundation
import Observation

/// Observable mirror of the keyboard settings the app edits, so SwiftUI
/// controls can bind to them; every change lands in the App Group for the
/// keyboard to read.
@Observable
final class KeyboardPreferences {
    static let shared = KeyboardPreferences()

    /// How long the microphone stays ready after the last keyboard dictation.
    var sessionMinutes: Int {
        didSet { KeyboardSharedSettings.sessionMinutes = sessionMinutes }
    }

    /// The keyboard mic: tap to start/stop, or hold to talk.
    var micMode: MicMode {
        didSet { KeyboardSharedSettings.micMode = micMode.rawValue }
    }

    private init() {
        sessionMinutes = KeyboardSharedSettings.sessionMinutes
        micMode = MicMode(rawValue: KeyboardSharedSettings.micMode) ?? .tap
    }
}
