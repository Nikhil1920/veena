import SwiftUI

/// Mic behavior, language switching with model management, the keyboard,
/// about info, and open-source and feedback links.
struct SettingsView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @State private var path = NavigationPath()
    @State private var keyboardStatus: KeyboardSetupStatus?

    enum Route: Hashable {
        case picker
        case download(String)
        case keyboardPicker
        case keyboardDownload(String)
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

                    Text((environment.micMode == .hold
                          ? "Press and hold the mic, release to transcribe."
                          : "Tap to start, tap again to transcribe.")
                         + " The Veena keyboard's mic works the same way.")
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

                keyboardSection

                Section("Downloaded Models") {
                    ForEach(ModelCatalog.all) { def in
                        modelRow(def)
                    }
                }

                Section("About") {
                    aboutRow("Version", VeenaLinks.appVersion)
                    aboutRow("Model", "IndicConformer (AI4Bharat), int8")
                    aboutRow("Sample Rate", "16,000 Hz")
                    aboutRow("Engine", "Rust · ONNX Runtime")
                }

                openSourceSection

                supportSection

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
                case .keyboardPicker:
                    LanguagePickerView(mode: .keyboard, needsDownload: { code in
                        path.append(Route.keyboardDownload(code))
                    }, onKeyboardPick: { _ in
                        path = NavigationPath()
                        refreshKeyboardStatus()
                    })
                case .keyboardDownload(let code):
                    ModelDownloadView(
                        languageCode: code,
                        mode: .keyboard,
                        onSuccess: {
                            path = NavigationPath()
                            refreshKeyboardStatus()
                        },
                        onDismiss: {
                            if !path.isEmpty { path.removeLast() }
                        }
                    )
                }
            }
        }
        .onAppear(perform: refreshKeyboardStatus)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { refreshKeyboardStatus() }
        }
    }

    private func refreshKeyboardStatus() {
        keyboardStatus = KeyboardSetupStatus.read(models: environment.models)
    }

    // MARK: Keyboard

    @ViewBuilder
    private var keyboardSection: some View {
        @Bindable var preferences = KeyboardPreferences.shared
        let session = environment.keyboardSession
        let ready = keyboardStatus?.isComplete == true
        Section {
            Button {
                environment.showKeyboardSetup = true
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Veena keyboard")
                            .foregroundStyle(Color.primary)
                        Text(ready ? "Ready in every app" : "Dictate in any app with your voice")
                            .font(.footnote)
                            .foregroundStyle(Color.secondary)
                    }
                    Spacer()
                    Text(ready ? "Guide" : "Set up")
                        .font(.subheadline.weight(.semibold))
                }
            }

            NavigationLink(value: Route.keyboardPicker) {
                HStack {
                    Text("Keyboard language")
                    Spacer()
                    Text(keyboardStatus?.languageCode.flatMap { language(byCode: $0)?.name } ?? "Not set")
                        .foregroundStyle(.secondary)
                }
            }

            Picker("Keep mic ready for", selection: $preferences.sessionMinutes) {
                ForEach(KeyboardBridge.sessionLengthChoices, id: \.self) { minutes in
                    Text(minutes >= 60 ? "1 hour" : "\(minutes) minutes").tag(minutes)
                }
            }

            if session.isLive {
                HStack {
                    Label {
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            Text(sessionLabel(endsAt: session.endsAt, now: context.date))
                                .monospacedDigit()
                        }
                    } icon: {
                        Image(systemName: "mic.fill").foregroundStyle(VeenaColors.ready)
                    }
                    Spacer()
                    Button("Turn off", role: .destructive) { session.endSession() }
                        .font(.subheadline)
                }
            }
        } header: {
            Text("Keyboard")
        } footer: {
            Text("The keyboard hands your voice to this app, which transcribes it on your iPhone. After you dictate, the mic stays ready for the time above.")
        }
    }

    // MARK: Open source and support

    private var openSourceSection: some View {
        Section {
            linkRow("Source code", "github.com/Nikhil1920/veena",
                    icon: "chevron.left.forwardslash.chevron.right", url: VeenaLinks.source)
            linkRow("Report a bug or request a feature", "Open an issue on GitHub",
                    icon: "ladybug", url: VeenaLinks.newIssue)
            linkRow("License", "MIT License", icon: "doc.text", url: VeenaLinks.license)
            linkRow("Speech models", "IndicConformer by AI4Bharat",
                    icon: "sparkles", url: VeenaLinks.indicConformer)
        } header: {
            Text("Open Source")
        } footer: {
            Text("Veena is free and open source under the MIT License. No ads, no account, and your voice never leaves your iPhone. Anyone can read the code, report problems, or contribute.")
        }
    }

    private var supportSection: some View {
        Section("Support Veena") {
            Button {
                openURL(VeenaLinks.appStoreReview)
                environment.markRated()
            } label: {
                linkLabel("Rate Veena", "Leave a review on the App Store", icon: "star")
            }
            linkRow("Send feedback", VeenaLinks.email, icon: "envelope", url: VeenaLinks.feedbackEmail)
                .contextMenu {
                    Button("Copy Email Address", systemImage: "doc.on.doc") {
                        UIPasteboard.general.string = VeenaLinks.email
                    }
                }
            ShareLink(
                item: VeenaLinks.website,
                message: Text("Veena: offline speech to text for 22 Indian languages.")
            ) {
                linkLabel("Share Veena", "Tell a friend", icon: "square.and.arrow.up")
            }
            linkRow("Website", "veena.byanr.com", icon: "globe", url: VeenaLinks.website)
            linkRow("Privacy policy", nil, icon: "hand.raised", url: VeenaLinks.privacy)
        }
    }

    private func linkRow(_ title: String, _ subtitle: String?, icon: String, url: URL) -> some View {
        Link(destination: url) {
            linkLabel(title, subtitle, icon: icon)
        }
    }

    private func linkLabel(_ title: String, _ subtitle: String?, icon: String) -> some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(Color.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Color.secondary)
                }
            }
        } icon: {
            Image(systemName: icon)
        }
    }

    private func sessionLabel(endsAt: Date?, now: Date) -> String {
        guard let endsAt else { return "Keyboard mic on" }
        let remaining = max(0, Int(endsAt.timeIntervalSince(now)))
        return String(format: "Keyboard mic on · %d:%02d", remaining / 60, remaining % 60)
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
