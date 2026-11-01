import SwiftUI
import UIKit

/// The keyboard panel. Mirrors the Android voice keyboard: a language chip on
/// top, a large mic between the globe and delete keys, and a space/return row
/// so short edits don't need another keyboard.
struct KeyboardRootView: View {
    let model: KeyboardModel
    unowned let controller: KeyboardViewController

    @Environment(\.verticalSizeClass) private var verticalSizeClass

    private var compact: Bool { verticalSizeClass == .compact }

    var body: some View {
        VStack(spacing: 6) {
            topBar
                .frame(height: compact ? 30 : 34)
            centerArea
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            micRow
                .frame(height: compact ? 60 : 80)
            bottomRow
                .frame(height: compact ? 36 : 42)
        }
        .padding(.horizontal, 6)
        .padding(.top, 6)
        .padding(.bottom, 4)
        .overlay {
            if model.showsLanguagePicker {
                LanguagePickerOverlay(model: model)
            }
        }
    }

    // MARK: Top bar

    private var topBar: some View {
        HStack(spacing: 8) {
            Button {
                model.refreshSettings()
                model.showsLanguagePicker = true
            } label: {
                HStack(spacing: 3) {
                    Text(languageLabel)
                        .font(.system(size: 13))
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                }
                .foregroundStyle(KeyColors.text)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(KeyColors.special))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Change language, \(languageLabel)")

            Spacer(minLength: 4)

            if model.sessionLive {
                SessionPill(endsAt: model.sessionEndsAt)
            }

            Button(action: model.openVeena) {
                Image(systemName: "gearshape")
                    .font(.system(size: 15))
                    .foregroundStyle(KeyColors.text)
                    .frame(width: 34, height: 30)
                    .background(Capsule().fill(KeyColors.special))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Open Veena")
        }
    }

    private var languageLabel: String {
        guard let code = model.languageCode else { return "No language" }
        guard let language = language(byCode: code) else { return code }
        return "\(language.nativeName) · \(language.name)"
    }

    // MARK: Status / notices

    @ViewBuilder
    private var centerArea: some View {
        if let notice = model.notice {
            NoticeCard(notice: notice, model: model, compact: compact)
        } else {
            StatusReadout(model: model, compact: compact)
        }
    }

    // MARK: Mic row

    private var micRow: some View {
        HStack(spacing: 0) {
            Group {
                if model.needsGlobeKey {
                    GlobeKey(controller: controller)
                        .frame(width: 48, height: 42)
                } else {
                    Color.clear.frame(width: 48, height: 42)
                }
            }
            Spacer()
            ZStack {
                MicKey(model: model, size: compact ? 54 : 70)
                if model.phase == .connecting || model.isRecording {
                    Button(action: model.cancel) {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(KeyColors.text)
                            .frame(width: 36, height: 36)
                            .background(Circle().fill(KeyColors.special))
                    }
                    .buttonStyle(.plain)
                    .offset(x: compact ? -70 : -84)
                    .accessibilityLabel("Cancel dictation")
                }
            }
            Spacer()
            RepeatingKey(action: { [unowned controller] in controller.deleteBackward() }) {
                Image(systemName: "delete.left")
                    .font(.system(size: 18))
            }
            .frame(width: 48, height: 42)
            .accessibilityLabel("Delete")
        }
    }

    // MARK: Space / return

    private var bottomRow: some View {
        HStack(spacing: 6) {
            KeyButton(fill: KeyColors.letter, action: { [unowned controller] in controller.insertSpace() }) {
                Text("space").font(.system(size: 15))
            }
            KeyButton(fill: KeyColors.special, action: { [unowned controller] in controller.insertReturn() }) {
                if let label = model.returnKeyLabel {
                    Text(label).font(.system(size: 15))
                } else {
                    Image(systemName: "return").font(.system(size: 17))
                }
            }
            .frame(width: 92)
        }
    }
}

// MARK: - Pieces

/// Colors close to the system keyboard's, in both appearances.
enum KeyColors {
    static let letter = Color(light: .white, dark: UIColor(white: 0.42, alpha: 1))
    static let special = Color(
        light: UIColor(red: 0.67, green: 0.69, blue: 0.73, alpha: 1),
        dark: UIColor(white: 0.27, alpha: 1)
    )
    static let text = Color(light: .black, dark: .white)
    static let secondaryText = Color(
        light: UIColor(white: 0.25, alpha: 1),
        dark: UIColor(white: 0.78, alpha: 1)
    )
    static let card = Color(light: UIColor(white: 1, alpha: 0.85), dark: UIColor(white: 0.2, alpha: 0.9))
}

private struct KeyButton<Label: View>: View {
    let fill: Color
    let action: () -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .foregroundStyle(KeyColors.text)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: 8)
                        .fill(fill)
                        .shadow(color: .black.opacity(0.25), radius: 0, x: 0, y: 1)
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A key that fires once on touch-down, then repeats while held (delete).
private struct RepeatingKey<Label: View>: View {
    let action: () -> Void
    @ViewBuilder let label: () -> Label
    @State private var timer: Timer?
    @State private var pressed = false

    var body: some View {
        label()
            .foregroundStyle(KeyColors.text)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(pressed ? KeyColors.letter : KeyColors.special)
                    .shadow(color: .black.opacity(0.25), radius: 0, x: 0, y: 1)
            )
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        action()
                        let delay = Timer(timeInterval: 0.45, repeats: false) { _ in
                            let repeating = Timer(timeInterval: 0.08, repeats: true) { _ in action() }
                            RunLoop.main.add(repeating, forMode: .common)
                            timer = repeating
                        }
                        RunLoop.main.add(delay, forMode: .common)
                        timer = delay
                    }
                    .onEnded { _ in
                        pressed = false
                        timer?.invalidate()
                        timer = nil
                    }
            )
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { action() }
    }
}

/// The system "next keyboard" key: tap switches keyboards, a long press
/// lists them. Only UIKit can forward the touch events this needs.
private struct GlobeKey: UIViewRepresentable {
    unowned let controller: UIInputViewController

    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(type: .system)
        button.setImage(
            UIImage(systemName: "globe", withConfiguration: UIImage.SymbolConfiguration(pointSize: 18)),
            for: .normal
        )
        button.tintColor = .label
        button.backgroundColor = UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(white: 0.27, alpha: 1)
                : UIColor(red: 0.67, green: 0.69, blue: 0.73, alpha: 1)
        }
        button.layer.cornerRadius = 8
        button.accessibilityLabel = "Next keyboard"
        button.addTarget(
            controller,
            action: #selector(UIInputViewController.handleInputModeList(from:with:)),
            for: .allTouchEvents
        )
        return button
    }

    func updateUIView(_ uiView: UIButton, context: Context) {}
}

private struct MicKey: View {
    let model: KeyboardModel
    let size: CGFloat
    @State private var pressing = false

    var body: some View {
        ZStack {
            if model.isRecording {
                Circle()
                    .fill(VeenaColors.recording.opacity(0.28))
                    .frame(width: size, height: size)
                    .scaleEffect(1.12 + CGFloat(min(model.level * 4, 1)) * 0.22)
                    .animation(.easeOut(duration: 0.12), value: model.level)
            }
            Circle()
                .fill(model.isRecording ? VeenaColors.recording : VeenaColors.accent)
                .frame(width: size, height: size)
                .overlay {
                    if model.isBusy {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: icon)
                            .font(.system(size: size * 0.36, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                }
                .opacity(model.notice == .fullAccess ? 0.5 : 1)
        }
        .contentShape(Circle())
        .gesture(holdGesture, including: model.holdToTalk ? .all : .subviews)
        .onTapGesture {
            if !model.holdToTalk { model.micTapped() }
        }
        .accessibilityLabel(model.isRecording ? "Stop and insert" : "Dictate")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { model.micTapped() }
    }

    private var icon: String {
        if model.isRecording && (!model.holdToTalk || model.tapToFinish) {
            return "stop.fill"
        }
        return "mic.fill"
    }

    private var holdGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { _ in
                guard !pressing else { return }
                pressing = true
                model.micPressed()
            }
            .onEnded { _ in
                pressing = false
                model.micReleased()
            }
    }
}

/// "Mic on · 4:12": the app is holding the microphone for this keyboard.
private struct SessionPill: View {
    let endsAt: Date?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            HStack(spacing: 5) {
                Circle()
                    .fill(VeenaColors.ready)
                    .frame(width: 7, height: 7)
                Text(label(now: context.date))
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(KeyColors.secondaryText)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Capsule().fill(KeyColors.special.opacity(0.6)))
        }
        .accessibilityLabel("Veena microphone is on")
    }

    private func label(now: Date) -> String {
        guard let endsAt else { return "Mic on" }
        let remaining = max(0, Int(endsAt.timeIntervalSince(now)))
        return String(format: "Mic on · %d:%02d", remaining / 60, remaining % 60)
    }
}

private struct StatusReadout: View {
    let model: KeyboardModel
    let compact: Bool

    var body: some View {
        VStack(spacing: 4) {
            switch model.phase {
            case .recording(let startedAt):
                TimelineView(.periodic(from: startedAt, by: 0.5)) { context in
                    Text(elapsed(since: startedAt, now: context.date))
                        .font(.system(size: compact ? 16 : 20, weight: .semibold).monospacedDigit())
                        .foregroundStyle(VeenaColors.recording)
                }
                hint(model.holdToTalk && !model.tapToFinish ? "Release to insert" : "Tap ■ to insert")
            case .transcribing:
                hint("Transcribing…")
            case .connecting:
                hint("Starting…")
            case .waking:
                hint("Opening Veena to turn the mic on…")
            case .idle:
                if model.appReachable {
                    hint(model.holdToTalk ? "Hold the mic and speak" : "Tap the mic and speak")
                } else {
                    hint("Tap the mic to dictate")
                    if !compact {
                        Text("The first time, Veena opens for a moment to turn the mic on")
                            .font(.system(size: 11))
                            .foregroundStyle(KeyColors.secondaryText)
                            .multilineTextAlignment(.center)
                    }
                }
            }
        }
        .padding(.horizontal, 12)
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundStyle(KeyColors.secondaryText)
            .multilineTextAlignment(.center)
    }

    private func elapsed(since start: Date, now: Date) -> String {
        let seconds = max(0, Int(now.timeIntervalSince(start)))
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

private struct NoticeCard: View {
    let notice: KeyboardModel.Notice
    let model: KeyboardModel
    let compact: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(KeyColors.text)
                if let detail, !compact {
                    Text(detail)
                        .font(.system(size: 11))
                        .foregroundStyle(KeyColors.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
            if showsOpenButton {
                Button("Open Veena", action: model.openVeena)
                    .font(.system(size: 13, weight: .semibold))
                    .buttonStyle(.borderedProminent)
                    .tint(VeenaColors.accent)
            }
            if notice != .fullAccess {
                Button(action: model.dismissNotice) {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(KeyColors.secondaryText)
                        .frame(width: 24, height: 24)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 10).fill(KeyColors.card))
        .padding(.horizontal, 4)
    }

    private var title: String {
        switch notice {
        case .fullAccess: return "Turn on Allow Full Access for Veena"
        case .microphonePermission: return "Allow the microphone in Veena"
        case .modelMissing(let code):
            if let code, let language = language(byCode: code) {
                return "Download the \(language.name) model in Veena"
            }
            return "Download a voice model in Veena"
        case .microphoneBusy: return "The microphone is busy"
        case .sessionEnded: return "Veena turned the mic off"
        case .openVeena: return "Couldn't reach Veena"
        case .noSpeech: return "Didn't catch that — try again"
        case .failed: return "Transcription failed"
        }
    }

    private var detail: String? {
        switch notice {
        case .fullAccess:
            return "Settings › General › Keyboard › Keyboards › Veena. The keyboard hands your voice to the Veena app on this iPhone; nothing leaves the device."
        case .microphonePermission:
            return "Dictation records through the Veena app."
        case .modelMissing:
            return "Keyboard dictation runs on the models in the Veena app."
        case .microphoneBusy:
            return "Finish recording in the Veena app first."
        case .sessionEnded:
            return "Tap the mic to turn it on again."
        case .openVeena:
            return "Open the Veena app once, then come back and tap the mic."
        case .noSpeech:
            return nil
        case .failed(let message):
            return message
        }
    }

    private var showsOpenButton: Bool {
        switch notice {
        case .microphonePermission, .modelMissing, .openVeena: return true
        default: return false
        }
    }
}

private struct LanguagePickerOverlay: View {
    let model: KeyboardModel

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Keyboard language")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(KeyColors.text)
                Spacer()
                Button("Done") { model.showsLanguagePicker = false }
                    .font(.system(size: 14, weight: .semibold))
                    .tint(VeenaColors.accent)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)

            ScrollView {
                VStack(spacing: 0) {
                    ForEach(model.installedLanguages, id: \.self) { code in
                        Button {
                            model.selectLanguage(code)
                        } label: {
                            HStack {
                                Text(label(for: code))
                                    .font(.system(size: 15))
                                    .foregroundStyle(KeyColors.text)
                                Spacer()
                                if code == model.languageCode {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(VeenaColors.accent)
                                }
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    Button(action: model.openVeena) {
                        HStack {
                            Image(systemName: "plus.circle")
                            Text(model.installedLanguages.isEmpty
                                 ? "No models yet — download one in Veena"
                                 : "Add a language in Veena")
                            Spacer()
                        }
                        .font(.system(size: 14))
                        .foregroundStyle(VeenaColors.accent)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(light: UIColor(white: 0.96, alpha: 1), dark: UIColor(white: 0.14, alpha: 1)))
        )
        .padding(4)
    }

    private func label(for code: String) -> String {
        guard let language = language(byCode: code) else { return code }
        return "\(language.nativeName) · \(language.name)"
    }
}
