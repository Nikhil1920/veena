import SwiftUI

/// Language selection used both for first-run onboarding and later changes
/// from Settings. The parent owns the navigation path and the download
/// destination.
struct LanguagePickerView: View {
    enum Mode: Hashable {
        case onboarding
        case settings
        case dictate
        /// The keyboard's language, which never changes the app's.
        case keyboard
    }

    @Environment(AppEnvironment.self) private var environment
    let mode: Mode
    /// Called when a language whose model is not yet on disk is chosen.
    let needsDownload: (String) -> Void
    /// Keyboard mode: called after a downloaded language became the keyboard's.
    var onKeyboardPick: ((String) -> Void)? = nil

    var body: some View {
        List {
            if mode == .onboarding {
                Section {
                    EmptyView()
                } footer: {
                    Text("Pick the language you'll dictate in. Its voice model downloads next, and you can switch languages anytime in Settings.")
                }
            } else if mode == .dictate {
                Section {
                    EmptyView()
                } footer: {
                    Text("A new language downloads its voice model the first time you pick it.")
                }
            } else if mode == .keyboard {
                Section {
                    EmptyView()
                } footer: {
                    Text("The keyboard can use a different language than the app. Its model downloads once, and dictation runs fully offline.")
                }
            }
            Section {
                ForEach(languages) { language in
                    Button {
                        pick(language)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(language.name)
                                    .foregroundStyle(.primary)
                                    .font(.body)
                                Text(language.nativeName)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if language.code == selectedCode {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(Color.accentColor)
                            } else {
                                Image(systemName: "chevron.right")
                                    .font(.footnote.weight(.semibold))
                                    .foregroundStyle(.tertiary)
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.large)
    }

    private var title: String {
        switch mode {
        case .onboarding: return "Welcome to Veena"
        case .settings: return "Language"
        case .dictate: return "Choose your language"
        case .keyboard: return "Keyboard language"
        }
    }

    private var selectedCode: String? {
        mode == .keyboard ? KeyboardSharedSettings.effectiveKeyboardLanguage : environment.languageCode
    }

    private func pick(_ language: Language) {
        let def = ModelCatalog.model(forLanguage: language.code)
        if environment.models.isDownloaded(def) {
            if mode == .keyboard {
                KeyboardSharedSettings.keyboardLanguageCode = language.code
                onKeyboardPick?(language.code)
            } else {
                environment.languageCode = language.code
            }
        } else {
            needsDownload(language.code)
        }
    }
}
