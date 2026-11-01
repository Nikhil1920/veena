import AVFoundation
import SwiftUI

/// Guided tour for the Veena keyboard: what it does, adding it with Full
/// Access, the microphone, the keyboard's language, a live test field, and
/// how the hand-off to the app works in other apps. Each step shows live
/// status, re-checked whenever the user comes back from Settings.
struct KeyboardSetupView: View {

    enum Step: Int, CaseIterable {
        case welcome, addKeyboard, microphone, language, tryIt, otherApps
    }

    private enum Route: Hashable {
        case picker
        case download(String)
    }

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL

    @State private var step: Step
    @State private var status: KeyboardSetupStatus?
    @State private var path = NavigationPath()
    @State private var tryText = ""
    @State private var dictationsAtTryStart = 0
    @FocusState private var tryFieldFocused: Bool

    init() {
        _step = State(initialValue: Self.savedStep ?? .welcome)
    }

    // MARK: Resuming
    //
    // Turning on the keyboard or Full Access in Settings can make iOS relaunch
    // the app, which drops the sheet. The tour remembers its step so the app
    // can bring it back where the user left off.

    private static let stepKey = "keyboardTour.step"
    private static let savedAtKey = "keyboardTour.savedAt"

    /// The step of a tour left open in the last half hour, if any.
    static var savedStep: Step? {
        let defaults = UserDefaults.standard
        guard let savedAt = defaults.object(forKey: savedAtKey) as? Date,
              Date().timeIntervalSince(savedAt) < 30 * 60,
              defaults.object(forKey: stepKey) != nil else { return nil }
        return Step(rawValue: defaults.integer(forKey: stepKey))
    }

    private static func save(_ step: Step?) {
        let defaults = UserDefaults.standard
        if let step {
            defaults.set(step.rawValue, forKey: stepKey)
            defaults.set(Date(), forKey: savedAtKey)
        } else {
            defaults.removeObject(forKey: stepKey)
            defaults.removeObject(forKey: savedAtKey)
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                if step != .welcome {
                    progressHeader
                }
                ScrollView {
                    stepContent
                        .padding(.horizontal, 24)
                        .padding(.vertical, 20)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .scrollDismissesKeyboard(.interactively)
                footer
            }
            .background(VeenaColors.background.ignoresSafeArea())
            .navigationTitle("Veena Keyboard")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .picker:
                    LanguagePickerView(mode: .keyboard, needsDownload: { code in
                        path.append(Route.download(code))
                    }, onKeyboardPick: { _ in
                        path = NavigationPath()
                        refresh()
                    })
                case .download(let code):
                    ModelDownloadView(
                        languageCode: code,
                        mode: .keyboard,
                        onSuccess: {
                            path = NavigationPath()
                            refresh()
                        },
                        onDismiss: {
                            if !path.isEmpty { path.removeLast() }
                        }
                    )
                }
            }
        }
        .onAppear {
            refresh()
            Self.save(step)
        }
        .onDisappear { Self.save(nil) }
        .onChange(of: step) { _, newStep in Self.save(newStep) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { refresh() }
            // Leaving for Settings: refresh the timestamp so a relaunch resumes here.
            if phase == .background { Self.save(step) }
        }
        // While the user is off adding the keyboard or trying it, keep the
        // checklist live: the keyboard reports Full Access as soon as it shows.
        .task(id: step) {
            guard step == .addKeyboard || step == .tryIt else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                refresh()
            }
        }
    }

    private func refresh() {
        status = KeyboardSetupStatus.read(models: environment.models)
    }

    // MARK: Progress

    private var progressHeader: some View {
        let total = Step.allCases.count - 1
        return VStack(alignment: .leading, spacing: 8) {
            Text("Step \(step.rawValue) of \(total)")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(VeenaColors.textSecondary)
            HStack(spacing: 4) {
                ForEach(1...total, id: \.self) { index in
                    Capsule()
                        .fill(index <= step.rawValue ? VeenaColors.accent : VeenaColors.backgroundSelected)
                        .frame(height: 4)
                }
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
    }

    // MARK: Steps

    @ViewBuilder
    private var stepContent: some View {
        switch step {
        case .welcome: welcome
        case .addKeyboard: addKeyboard
        case .microphone: microphone
        case .language: languageStep
        case .tryIt: tryIt
        case .otherApps: otherApps
        }
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 14) {
                iconBadge("keyboard")
                iconBadge("mic.fill")
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 12)

            title("Speak instead of typing, in any app")
            bodyText("The Veena keyboard puts a mic in Messages, WhatsApp, Notes, Mail — anywhere you type. What you say is transcribed on this iPhone by the same offline models as the Veena app.")

            VStack(alignment: .leading, spacing: 12) {
                bullet("wifi.slash", "Works offline, after the one-time model download")
                bullet("character.bubble", "Your languages: all 22 that Veena speaks")
                bullet("lock.shield", "Private: audio and text never leave your iPhone")
            }
            .padding(.top, 4)

            bodyText("Setup takes about a minute: add the keyboard in Settings, allow the microphone, and try it here.")
                .padding(.top, 4)
        }
    }

    private var addKeyboard: some View {
        VStack(alignment: .leading, spacing: 18) {
            title("Add the keyboard")
            bodyText("Tap **Open Settings**, then **Keyboards**. Turn on **Veena**, then **Allow Full Access**.")

            SettingsMock()

            VStack(alignment: .leading, spacing: 10) {
                statusRow(
                    "Veena keyboard added",
                    state: status?.keyboardAdded.map { $0 ? .done : .pending } ?? .unknown
                )
                statusRow(
                    "Full Access",
                    state: status?.fullAccessSeen == true ? .done : .unknown,
                    note: status?.fullAccessSeen == true ? nil : "Confirmed the first time you use the keyboard"
                )
            }

            DisclosureGroup("Why Full Access?") {
                Text("iOS doesn't let keyboards use the microphone, so the Veena keyboard asks the Veena app to listen and hand back the text. Full Access is the permission that lets the keyboard and the app talk to each other on your iPhone. Veena never sends your audio or what you type anywhere, and keeps nothing after it inserts the text.")
                    .font(.subheadline)
                    .foregroundStyle(VeenaColors.textSecondary)
                    .padding(.top, 6)
            }
            .font(.subheadline.weight(.semibold))
            .tint(VeenaColors.accent)
        }
    }

    private var microphone: some View {
        VStack(alignment: .leading, spacing: 18) {
            iconBadge("mic.fill")
            title("Allow the microphone")
            bodyText("When you tap the keyboard's mic, the Veena app does the listening, so it needs microphone access. Veena only keeps what you say between tapping the mic and tapping stop.")
            statusRow("Microphone", state: microphoneState, note: status?.microphone == .denied
                      ? "Turn on Microphone for Veena in Settings" : nil)
        }
    }

    private var languageStep: some View {
        let installed = KeyboardSharedSettings.installedLanguageCodes
        return VStack(alignment: .leading, spacing: 18) {
            iconBadge("character.bubble")
            title("Choose the keyboard's language")
            bodyText("The keyboard can dictate in a different language than the app. You can switch any time from the language button on the keyboard.")

            VStack(spacing: 0) {
                ForEach(installed, id: \.self) { code in
                    Button {
                        KeyboardSharedSettings.keyboardLanguageCode = code
                        refresh()
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(language(byCode: code)?.name ?? code).foregroundStyle(VeenaColors.textPrimary)
                                Text(language(byCode: code)?.nativeName ?? "")
                                    .font(.footnote)
                                    .foregroundStyle(VeenaColors.textSecondary)
                            }
                            Spacer()
                            if code == status?.languageCode {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(VeenaColors.accent)
                            }
                        }
                        .padding(.vertical, 10)
                        .padding(.horizontal, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Divider().padding(.leading, 14)
                }
                Button {
                    path.append(Route.picker)
                } label: {
                    Label("Add another language", systemImage: "plus.circle")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 12)
                        .padding(.horizontal, 14)
                }
            }
            .background(RoundedRectangle(cornerRadius: 12).fill(VeenaColors.backgroundElement))

            if status?.modelInstalled != true {
                statusRow("Voice model", state: .pending, note: "Add a language to download its model")
            }
        }
    }

    private var tryIt: some View {
        VStack(alignment: .leading, spacing: 18) {
            title("Try it here")
            VStack(alignment: .leading, spacing: 10) {
                numbered(1, "Tap the box below.")
                numbered(2, "Touch and hold 🌐 on the keyboard and pick **Veena**.")
                numbered(3, "Tap the mic, speak, then tap ■ to insert.")
            }

            TextField("Dictate here…", text: $tryText, axis: .vertical)
                .lineLimit(3...6)
                .focused($tryFieldFocused)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 12).fill(VeenaColors.backgroundElement))

            VStack(alignment: .leading, spacing: 10) {
                statusRow("Keyboard connected with Full Access", state: status?.fullAccessSeen == true ? .done : .pending)
                statusRow(
                    "Dictation works",
                    state: environment.keyboardSession.completedDictations > dictationsAtTryStart ? .done : .pending
                )
            }
        }
        .onAppear {
            dictationsAtTryStart = environment.keyboardSession.completedDictations
        }
    }

    private var otherApps: some View {
        @Bindable var settings = KeyboardPreferences.shared
        return VStack(alignment: .leading, spacing: 18) {
            title("Using it in other apps")
            bodyText("The first time you tap the mic in another app, Veena opens for a moment to turn the microphone on — iOS only lets an app start the mic while it's on screen. Go back and start speaking:")
            ReturnHint()
            bodyText("After that the mic stays ready, so later taps start right away. iOS shows the orange dot while it's on; you can turn it off from the keyboard's app screen or here in Settings.")

            VStack(alignment: .leading, spacing: 8) {
                Text("Keep the mic ready for")
                    .font(.subheadline.weight(.semibold))
                Picker("Keep the mic ready for", selection: $settings.sessionMinutes) {
                    ForEach(KeyboardBridge.sessionLengthChoices, id: \.self) { minutes in
                        Text(minutes >= 60 ? "1 hour" : "\(minutes) min").tag(minutes)
                    }
                }
                .pickerStyle(.segmented)
                Text("after your last dictation")
                    .font(.footnote)
                    .foregroundStyle(VeenaColors.textSecondary)
            }
        }
    }

    // MARK: Footer

    private var footer: some View {
        VStack(spacing: 10) {
            primaryButton
            if step != .welcome {
                Button("Back") { move(-1) }
                    .font(.subheadline)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 10)
        .padding(.bottom, 16)
    }

    @ViewBuilder
    private var primaryButton: some View {
        switch step {
        case .welcome:
            prominent("Set up the keyboard") { move(1) }
        case .addKeyboard:
            if status?.keyboardAdded == true {
                prominent("Next") { move(1) }
                Button("Open Settings again", action: openSettings).font(.subheadline)
            } else {
                prominent("Open Settings", action: openSettings)
                Button("I've added it — next") { move(1) }.font(.subheadline)
            }
        case .microphone:
            switch status?.microphone {
            case .granted:
                prominent("Next") { move(1) }
            case .denied:
                prominent("Open Settings", action: openSettings)
                Button("Skip for now") { move(1) }.font(.subheadline)
            default:
                prominent("Allow microphone") {
                    AVAudioApplication.requestRecordPermission { _ in
                        DispatchQueue.main.async {
                            refresh()
                            if status?.microphone == .granted { move(1) }
                        }
                    }
                }
            }
        case .language:
            if status?.modelInstalled == true {
                prominent("Next") { move(1) }
            } else {
                prominent("Add a language") { path.append(Route.picker) }
            }
        case .tryIt:
            prominent("Next") {
                tryFieldFocused = false
                move(1)
            }
        case .otherApps:
            prominent("Done") { dismiss() }
        }
    }

    private func move(_ delta: Int) {
        guard let next = Step(rawValue: step.rawValue + delta) else { return }
        refresh()
        withAnimation(.easeInOut(duration: 0.2)) { step = next }
    }

    private func openSettings() {
        // The app's own Settings page lists its keyboard under "Keyboards".
        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
    }

    private var microphoneState: StatusState {
        switch status?.microphone {
        case .granted: return .done
        case .denied: return .failed
        default: return .pending
        }
    }

    // MARK: Building blocks

    private enum StatusState { case done, pending, unknown, failed }

    private func statusRow(_ label: String, state: StatusState, note: String? = nil) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Group {
                switch state {
                case .done:
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(VeenaColors.ready)
                case .pending:
                    Image(systemName: "circle").foregroundStyle(VeenaColors.textSecondary)
                case .unknown:
                    Image(systemName: "questionmark.circle").foregroundStyle(VeenaColors.textSecondary)
                case .failed:
                    Image(systemName: "xmark.circle.fill").foregroundStyle(VeenaColors.recording)
                }
            }
            .font(.system(size: 18))
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.subheadline.weight(.semibold))
                if let note {
                    Text(note).font(.footnote).foregroundStyle(VeenaColors.textSecondary)
                }
            }
        }
    }

    private func title(_ text: String) -> some View {
        Text(text)
            .font(.title2.bold())
            .foregroundStyle(VeenaColors.textPrimary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func bodyText(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.body)
            .foregroundStyle(VeenaColors.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func bullet(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(VeenaColors.accent)
                .frame(width: 24)
            Text(text).font(.subheadline)
        }
    }

    private func numbered(_ number: Int, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.footnote.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Circle().fill(VeenaColors.accent))
            Text(text).font(.subheadline)
        }
    }

    private func iconBadge(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 28, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 64, height: 64)
            .background(RoundedRectangle(cornerRadius: 18).fill(VeenaColors.accent))
    }

    private func prominent(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
        }
        .buttonStyle(.borderedProminent)
        .tint(VeenaColors.accent)
    }
}

/// A drawing of the app's page in Settings with the two switches to turn on.
private struct SettingsMock: View {
    var body: some View {
        VStack(spacing: 10) {
            row {
                Image(systemName: "keyboard")
                    .foregroundStyle(.white)
                    .frame(width: 26, height: 26)
                    .background(RoundedRectangle(cornerRadius: 6).fill(Color.gray))
                Text("Keyboards")
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(VeenaColors.textSecondary)
            }
            .background(RoundedRectangle(cornerRadius: 12).fill(VeenaColors.backgroundElement))
            VStack(spacing: 0) {
                row {
                    Text("Veena")
                    Spacer()
                    toggle
                }
                Divider().padding(.leading, 14)
                row {
                    Text("Allow Full Access")
                    Spacer()
                    toggle
                }
            }
            .background(RoundedRectangle(cornerRadius: 12).fill(VeenaColors.backgroundElement))
        }
        .font(.subheadline)
    }

    private func row<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        HStack(spacing: 10, content: content)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
    }

    private var toggle: some View {
        Capsule()
            .fill(VeenaColors.ready)
            .frame(width: 44, height: 26)
            .overlay(alignment: .trailing) {
                Circle().fill(.white).frame(width: 22, height: 22).padding(2)
            }
    }
}
