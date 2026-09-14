import SwiftUI

/// Language selection used both for first-run onboarding and later changes
/// from Settings. The parent owns the navigation path and the download
/// destination.
struct LanguagePickerView: View {
    enum Mode: Hashable {
        case onboarding
        case settings
        case dictate
    }

    @Environment(AppEnvironment.self) private var environment
    let mode: Mode
    /// Called when a language whose model is not yet on disk is chosen.
    let needsDownload: (String) -> Void

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
                            if language.code == environment.languageCode {
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
        }
    }

    private func pick(_ language: Language) {
        let def = ModelCatalog.model(forLanguage: language.code)
        if environment.models.isDownloaded(def) {
            environment.languageCode = language.code
        } else {
            needsDownload(language.code)
        }
    }
}
