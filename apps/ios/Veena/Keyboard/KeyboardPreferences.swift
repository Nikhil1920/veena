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

    private init() {
        sessionMinutes = KeyboardSharedSettings.sessionMinutes
    }
}
