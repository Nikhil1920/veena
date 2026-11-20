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

    /// Whether the keyboard dictates by itself where it can.
    var inPlaceDictation: Bool {
        didSet {
            KeyboardSharedSettings.inPlaceDictation = inPlaceDictation
            // Turning it on again is how the user asks for another try.
            KeyboardSharedSettings.inPlaceFailures = 0
        }
    }

    /// In-place dictation needs iOS 26 (see `KeyboardLocalDictation`).
    static var offersInPlaceDictation: Bool {
        if #available(iOS 26.0, *) { return true }
        return false
    }

    /// Whether the keyboard, set to `code`, dictates without the app.
    func dictatesInPlace(languageCode code: String?) -> Bool {
        guard Self.offersInPlaceDictation, inPlaceDictation, let code else { return false }
        guard let def = ModelCatalog.keyboardModel(forLanguage: code) else { return false }
        return SharedModelStore.files(modelID: def.id)?.fitsInKeyboard == true
    }

    private init() {
        sessionMinutes = KeyboardSharedSettings.sessionMinutes
        inPlaceDictation = KeyboardSharedSettings.inPlaceDictation
    }
}
