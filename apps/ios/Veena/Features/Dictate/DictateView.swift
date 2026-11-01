import SwiftUI
import AVFoundation
import SwiftData
import UniformTypeIdentifiers

/// Main dictation screen: transcript box with Copy/Clear, mic button with
/// hold-to-speak (default) or tap-to-speak, and audio-file import.
/// Mirrors veena-android DictateScreen.kt (incl. elapsed timer, pulse,
/// auto-scroll, language chip, and HOLD vs TAP permission handling).
struct DictateView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.modelContext) private var modelContext
    @State private var text = ""
    @State private var textRevision = 0
    @State private var isRecording = false
    @State private var isTranscribing = false
    @State private var lastRecording: Recording?
    @State private var errorMessage: String?
    @State private var showImporter = false
    @State private var showLanguagePicker = false
    @State private var elapsedMs: Int64 = 0
    @State private var startedAt = Date()
    @State private var pulsePhase = false
    @State private var keyboardReady = true
    @AppStorage("keyboardBannerDismissed") private var keyboardBannerDismissed = false

    var body: some View {
        VStack(spacing: 16) {
            header

            if !keyboardReady && !keyboardBannerDismissed {
                keyboardBanner
            }

            statusPill

            transcriptBox

            if let lastRecording, FileManager.default.fileExists(atPath: lastRecording.fileURL.path) {
                playbackPill(lastRecording)
            }

            controls

            hintLabel
        }
        .padding(.horizontal, 24)
        .background(VeenaColors.background.ignoresSafeArea())
        .alert("Veena", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.audio]
        ) { result in
            if case .success(let url) = result {
                importAudio(url)
            }
        }
        .sheet(isPresented: $showLanguagePicker) {
            NavigationStack {
                DictateLanguagePickerSheet(isPresented: $showLanguagePicker)
            }
        }
        // Elapsed timer while recording
        .task(id: isRecording) {
            if isRecording {
                startedAt = Date()
                while isRecording && !Task.isCancelled {
                    elapsedMs = Int64(Date().timeIntervalSince(startedAt) * 1000)
                    try? await Task.sleep(nanoseconds: 200_000_000)
                }
            } else {
                elapsedMs = 0
            }
        }
        // Pulse animation driver
        .onAppear { withAnimation(.easeInOut(duration: 0.65).repeatForever(autoreverses: true)) { pulsePhase = true } }
        .onAppear { keyboardReady = KeyboardSetupStatus.read(models: environment.models).isComplete }
        .onChange(of: environment.showKeyboardSetup) { _, showing in
            if !showing { keyboardReady = KeyboardSetupStatus.read(models: environment.models).isComplete }
        }
        .onChange(of: isRecording) { _, recording in
            if recording {
                pulsePhase = false
                withAnimation(.easeInOut(duration: 0.65).repeatForever(autoreverses: true)) { pulsePhase = true }
            }
        }
        // UI-test hook: `simctl launch ... -transcribe <name>` runs a file
        // from Documents/ through the same import path the picker uses.
        .task {
            let args = ProcessInfo.processInfo.arguments
            guard let flag = args.firstIndex(of: "-transcribe"),
                  args.indices.contains(flag + 1) else { return }
            let name = args[flag + 1]
            for _ in 0..<60 {
                if environment.engine.state.isReady { break }
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            guard environment.engine.state.isReady else { return }
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let url = docs.appendingPathComponent(name)
            guard FileManager.default.fileExists(atPath: url.path) else { return }
            importAudio(url)
        }
    }

    // MARK: Header — title + language chip (mirrors Android languageByCode chip)

    private var header: some View {
        VStack(spacing: 6) {
            Text("Veena")
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(VeenaColors.textPrimary)
            if let lang = environment.selectedLanguage {
                Button { showLanguagePicker = true } label: {
                    HStack(spacing: 2) {
                        Text("\(lang.nativeName) · \(lang.name)")
                            .font(.system(size: 13))
                        Image(systemName: "chevron.down")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundStyle(VeenaColors.textSecondary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(VeenaColors.backgroundElement))
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 24)
    }

    // MARK: Keyboard setup banner — until the keyboard is set up or dismissed

    private var keyboardBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "keyboard")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(VeenaColors.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text("Dictate in any app")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(VeenaColors.textPrimary)
                Text("Add the Veena keyboard")
                    .font(.footnote)
                    .foregroundStyle(VeenaColors.textSecondary)
            }
            Spacer()
            Button("Set up") { environment.showKeyboardSetup = true }
                .font(.subheadline.weight(.semibold))
            Button {
                keyboardBannerDismissed = true
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(VeenaColors.textSecondary)
            }
            .accessibilityLabel("Dismiss")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12).fill(VeenaColors.backgroundElement))
    }

    // MARK: Status pill (with pulsing dot, elapsed timer — mirrors Android)

    private var engineState: RustEngine.State { environment.engine.state }

    private var statusPill: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
                .opacity(isRecording ? (pulsePhase ? 0.9 : 0.35) : 1)
            Text(statusText)
                .font(.system(size: 13))
                .foregroundStyle(VeenaColors.textSecondary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .background(Capsule().fill(VeenaColors.backgroundElement))
    }

    private var statusColor: Color {
        if isRecording { return VeenaColors.recording }
        if isTranscribing { return VeenaColors.initializing }
        if case .ready = engineState { return VeenaColors.ready }
        if case .failed = engineState { return VeenaColors.recording }
        return VeenaColors.initializing
    }

    private var statusText: String {
        if isRecording { return "Recording · \(formatDuration(elapsedMs))" }
        if isTranscribing { return "Transcribing..." }
        if case .ready = engineState { return "Ready" }
        if case .failed = engineState { return "Model failed to load" }
        return "Loading model..."
    }

    // MARK: Transcript box with auto-scroll + Copy/Clear with icons

    private var transcriptBox: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    ZStack(alignment: .topLeading) {
                        TextEditor(text: $text)
                            .scrollContentBackground(.hidden)
                            .foregroundStyle(VeenaColors.textPrimary)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .font(.body)
                            .lineSpacing(4)
                            .scrollDisabled(true)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, minHeight: 140, alignment: .topLeading)
                        if text.isEmpty {
                            Text("Transcribed text will appear here...")
                                .foregroundStyle(Color(uiColor: .placeholderText))
                                .padding(.horizontal, 17)
                                .padding(.top, 16)
                                .allowsHitTesting(false)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    Color.clear.frame(height: 1).id("transcriptBottom")
                }
                .background(VeenaColors.backgroundElement)
                .onChange(of: textRevision) { _, _ in
                    DispatchQueue.main.async {
                        withAnimation(.easeOut(duration: 0.2)) {
                            proxy.scrollTo("transcriptBottom", anchor: .bottom)
                        }
                    }
                }
            }

            HStack {
                Button {
                    UIPasteboard.general.string = text
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                        .font(.subheadline)
                }
                .disabled(text.isEmpty)

                Spacer()

                Button {
                    text = ""
                } label: {
                    Label("Clear", systemImage: "trash")
                        .font(.subheadline)
                }
                .disabled(text.isEmpty)
            }
            .foregroundStyle(VeenaColors.textSecondary)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(VeenaColors.backgroundElement)
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .frame(maxHeight: .infinity)
    }

    private func playbackPill(_ recording: Recording) -> some View {
        Button {
            environment.player.toggle(path: recording.fileURL.path)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: environment.player.playingPath == recording.fileURL.path ? "pause.fill" : "play.fill")
                    .font(.caption)
                Text(environment.player.playingPath == recording.fileURL.path ? "Pause Audio" : "Play Last Recording · \(formatDuration(Int64(recording.duration * 1000)))")
                    .font(.footnote.weight(.semibold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
            .background(Capsule().fill(VeenaColors.accent))
        }
    }

    private var controls: some View {
        HStack(spacing: 28) {
            Button {
                showImporter = true
            } label: {
                Image(systemName: "folder")
                    .font(.title3)
                    .foregroundStyle(VeenaColors.textSecondary)
                    .frame(width: 56, height: 56)
                    .background(Circle().fill(VeenaColors.backgroundElement))
            }

            micButton
        }
        .padding(.bottom, 4)
    }

    /// Hold-to-speak by default; tap-to-speak when chosen in Settings.
    private var micButton: some View {
        let ready: Bool = {
            if case .ready = engineState { return true }
            return false
        }()
        let disabled = !ready || isTranscribing

        // Unified enabled flag — hold gesture only when HOLD and not disabled
        let holdEnabled = environment.micMode == .hold && !disabled

        return ZStack {
            if isRecording {
                Circle()
                    .fill(VeenaColors.recording)
                    .frame(width: 94, height: 94)
                    .opacity(pulsePhase ? 0.32 : 0.12)
            }
            Circle()
                .fill(micFill(ready: ready))
                .frame(width: 72, height: 72)
                .overlay {
                    if isTranscribing {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: "mic.fill")
                            .font(.system(size: 26))
                            .foregroundStyle(.white)
                    }
                }
                .opacity(disabled && !isRecording ? 0.5 : 1)
        }
        .onTapGesture {
            guard environment.micMode == .tap else { return }
            if isRecording {
                stopAndTranscribe()
            } else if ready && !isTranscribing {
                beginRecording()
            }
        }
        .simultaneousGesture(holdGesture(enabled: holdEnabled))
    }

    private func holdGesture(enabled: Bool) -> some Gesture {
        LongPressGesture(minimumDuration: 0.05, maximumDistance: 60)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .local))
            .onChanged { value in
                switch value {
                case .first(true):
                    if enabled && !isRecording {
                        beginRecording()
                    }
                default:
                    break
                }
            }
            .onEnded { _ in
                if isRecording && environment.micMode == .hold {
                    stopAndTranscribe()
                }
            }
    }

    private func micFill(ready: Bool) -> Color {
        if isRecording { return VeenaColors.recording }
        return ready ? VeenaColors.accent : VeenaColors.backgroundSelected
    }

    private var hintLabel: some View {
        Text(hintText)
            .font(.caption)
            .foregroundStyle(VeenaColors.textSecondary)
            .multilineTextAlignment(.center)
            .padding(.bottom, 8)
    }

    private var hintText: String {
        switch environment.micMode {
        case .hold:
            return isRecording ? "Release to transcribe" : "Hold to record, release to transcribe"
        case .tap:
            return isRecording ? "Tap again to stop" : "Tap to record & transcribe"
        }
    }

    // MARK: Actions — mirror Android permission HOLD vs TAP distinction

    private func beginRecording() {
        // Pre-check: engine must be ready (hold gesture already checks, but TAP path also needs it)
        guard case .ready = environment.engine.state else {
            errorMessage = "Model not ready yet"
            return
        }
        guard !isTranscribing else { return }

        let status = AVAudioSession.sharedInstance().recordPermission
        switch status {
        case .granted:
            startEngineRecording()
        case .denied:
            errorMessage = "Microphone permission is required for dictation"
        case .undetermined:
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                DispatchQueue.main.async {
                    guard granted else {
                        errorMessage = "Microphone permission is required for dictation"
                        return
                    }
                    // HOLD mode: finger that triggered the prompt is long gone — don't auto-start
                    if environment.micMode == .tap {
                        startEngineRecording()
                    }
                }
            }
        @unknown default:
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                DispatchQueue.main.async {
                    if granted && environment.micMode == .tap {
                        startEngineRecording()
                    } else if !granted {
                        errorMessage = "Microphone permission is required for dictation"
                    }
                }
            }
        }
    }

    private func startEngineRecording() {
        // The keyboard session holds the microphone through its own engine.
        environment.keyboardSession.endSession()
        do {
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, options: [.defaultToSpeaker, .allowBluetooth])
            try AVAudioSession.sharedInstance().setActive(true)
            let url = recordingsDirectory.appendingPathComponent("rec_\(Int(Date().timeIntervalSince1970 * 1000)).wav")
            try environment.recorder.start(outputURL: url)
            isRecording = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func stopAndTranscribe() {
        guard isRecording else { return }
        isRecording = false

        guard let recording = environment.recorder.stop() else {
            isTranscribing = false
            return
        }
        lastRecording = recording

        // Skip inference for too-short recordings (Android MIN 400ms)
        guard recording.duration > 0.4 else {
            isTranscribing = false
            return
        }

        isTranscribing = true

        let code = environment.languageCode ?? ""
        Task.detached(priority: .userInitiated) { [weak environment] in
            guard let environment else { return }
            do {
                let transcript = try environment.engine.transcribe(
                    recording.samples, languageCode: code, models: environment.models
                )
                if !transcript.isBlank {
                    await MainActor.run { [weak environment] in
                        guard let environment else { return }
                        append(transcript)
                        saveHistory(environment, transcript: transcript, recording: recording, source: "mic")
                    }
                } else {
                    await MainActor.run {
                        errorMessage = "No speech recognized"
                    }
                }
            } catch {
                await MainActor.run {
                    errorMessage = error.localizedDescription
                }
            }
            await MainActor.run {
                isTranscribing = false
            }
        }
    }

    private func importAudio(_ url: URL) {
        guard case .ready = engineState else {
            errorMessage = "Model not ready yet"
            return
        }
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }

        let displayName = url.lastPathComponent
        let ext = url.pathExtension.isEmpty ? "wav" : url.pathExtension
        let dest = recordingsDirectory.appendingPathComponent("imp_\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)")
        do {
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.copyItem(at: url, to: dest)
        } catch {
            errorMessage = error.localizedDescription
            return
        }

        isTranscribing = true
        let fileURL = dest
        let code = environment.languageCode ?? ""
        Task.detached(priority: .userInitiated) { [weak environment] in
            guard let environment else { return }
            do {
                let transcript = try environment.engine.transcribeFile(
                    at: fileURL, languageCode: code, models: environment.models
                ) { u in
                    try AudioFileDecoder.decodeToMono16k(url: u)
                }
                await MainActor.run { [weak environment] in
                    guard let environment else { return }
                    if !transcript.isBlank {
                        append(transcript)
                        saveHistory(environment, transcript: transcript, importedURL: fileURL, source: "import")
                    } else {
                        errorMessage = "No speech recognized in \(displayName)"
                        try? FileManager.default.removeItem(at: fileURL)
                    }
                }
            } catch {
                await MainActor.run {
                    errorMessage = error.localizedDescription
                }
            }
            await MainActor.run {
                isTranscribing = false
            }
        }
    }

    private func append(_ value: String) {
        text = text.isBlank ? value : "\(text) \(value)"
        textRevision += 1
    }

    private func saveHistory(
        _ environment: AppEnvironment,
        transcript: String,
        recording: Recording? = nil,
        importedURL: URL? = nil,
        source: String
    ) {
        let entry = HistoryEntry(
            createdAt: Date(),
            languageCode: environment.languageCode ?? "",
            transcript: transcript,
            audioPath: recording?.fileURL.path ?? importedURL?.path ?? "",
            durationMs: (recording.map { $0.duration } ?? 0) * 1000,
            source: source
        )
        modelContext.insert(entry)
        try? modelContext.save()
    }

    private var recordingsDirectory: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("recordings", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func formatDuration(_ ms: Int64) -> String {
        let totalSeconds = ms / 1000
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

/// Sheet that mirrors Android's picker/{mode} + download/{mode}/{lang} for mode= dictate
private struct DictateLanguagePickerSheet: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @Binding var isPresented: Bool
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            LanguagePickerView(mode: .dictate) { code in
                let def = ModelCatalog.model(forLanguage: code)
                if environment.models.isDownloaded(def) {
                    environment.languageCode = code
                    isPresented = false
                } else {
                    path.append(code)
                }
            }
            .navigationDestination(for: String.self) { code in
                ModelDownloadView(languageCode: code, mode: .dictate, onSuccess: {
                    environment.languageCode = code
                    isPresented = false
                }, onDismiss: {
                    // pop only the download, keep picker
                    if !path.isEmpty { path.removeLast() }
                })
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { isPresented = false }
                }
            }
        }
    }
}

extension String {
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
