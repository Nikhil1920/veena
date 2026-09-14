import SwiftUI

/// Mic behavior, language switching with model management, and about info.
struct SettingsView: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var path = NavigationPath()

    enum Route: Hashable {
        case picker
        case download(String)
    }

    var body: some View {
        NavigationStack(path: $path) {
            Form {
                Section("Microphone Behavior") {
                    Picker("Mic Mode", selection: Binding(
                        get: { environment.micMode },
                        set: { environment.micMode = $0 }
                    )) {
                        Text("Hold to Record").tag(MicMode.hold)
                        Text("Tap to Record").tag(MicMode.tap)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()

                    Text(environment.micMode == .hold
                         ? "Press and hold the mic, release to transcribe"
                         : "Tap to start, tap again to transcribe")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Language") {
                    NavigationLink(value: Route.picker) {
                        HStack {
                            Text("Dictation language")
                            Spacer()
                            VStack(alignment: .trailing) {
                                Text(environment.selectedLanguage?.name ?? "Select language")
                                if let native = environment.selectedLanguage?.nativeName {
                                    Text(native).font(.footnote).foregroundStyle(.secondary)
                                }
                            }
                            .foregroundStyle(.secondary)
                        }
                    }
                }

                Section("Downloaded Models") {
                    ForEach(ModelCatalog.all) { def in
                        modelRow(def)
                    }
                }

                Section("About") {
                    aboutRow("Model", "IndicConformer (AI4Bharat), int8")
                    aboutRow("Sample Rate", "16,000 Hz")
                    aboutRow("Engine", "Rust · ONNX Runtime")
                    aboutRow("Version", "1.0.0")
                }

                Section {
                    Text("Veena supports \(languages.count) scheduled languages of India.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .picker:
                    LanguagePickerView(mode: .settings) { code in
                        path.append(Route.download(code))
                    }
                case .download(let code):
                    ModelDownloadView(
                        languageCode: code,
                        mode: .settings,
                        onSuccess: {
                            environment.languageCode = code
                            // Pop download + picker, back to Settings (mirrors Android popBackStack("settings"))
                            path = NavigationPath()
                        },
                        onDismiss: {
                            if !path.isEmpty { path.removeLast() }
                        }
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func modelRow(_ def: ModelDef) -> some View {
        let installed = environment.models.installedModels().first { $0.def.id == def.id }
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(def.displayName).font(.subheadline)
                Text(installed.map { "On device · \(formatBytes($0.sizeBytes))" } ?? "Not downloaded")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if installed != nil {
                Button("Delete", role: .destructive) {
                    environment.models.delete(def)
                }
                .font(.subheadline)
            }
        }
    }

    private func aboutRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value)
        }
        .font(.subheadline)
    }

    private func formatBytes(_ bytes: Int64) -> String {
        switch bytes {
        case 1_000_000_000...: String(format: "%.1f GB", Double(bytes) / 1_000_000_000)
        case 1_000_000...: String(format: "%.0f MB", Double(bytes) / 1_000_000)
        default: String(format: "%.0f KB", Double(bytes) / 1_000)
        }
    }
}
