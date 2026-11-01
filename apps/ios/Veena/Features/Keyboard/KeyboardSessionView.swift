import AVFoundation
import SwiftUI

/// Shown when the keyboard opens Veena to turn the microphone on. iOS has no
/// way to send the user back automatically, so this screen's job is to say
/// how: the "◀ App" button in the top-left corner, or a swipe along the
/// bottom edge.
struct KeyboardSessionView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var pulse = false

    private var session: KeyboardSessionController { environment.keyboardSession }

    var body: some View {
        VStack(spacing: 20) {
            HStack {
                Spacer()
                Button("Close") { dismiss() }
                    .font(.body.weight(.semibold))
            }
            .padding(.top, 8)

            Spacer(minLength: 0)
            content
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 24)
        .background(VeenaColors.background.ignoresSafeArea())
        .onAppear {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { pulse = true }
        }
    }

    @ViewBuilder
    private var content: some View {
        if KeyboardSharedSettings.installedLanguageCodes.isEmpty {
            message(
                icon: "arrow.down.circle",
                title: "Download a voice model first",
                body: "Keyboard dictation uses the models in the Veena app. Pick your language and download its model, then go back and tap the mic again."
            )
            Button("Choose a language") { dismiss() }
                .buttonStyle(.borderedProminent)
        } else if session.isLive {
            live
        } else if session.startFailure == .microphonePermission {
            if AVAudioApplication.shared.recordPermission == .denied {
                message(
                    icon: "mic.slash",
                    title: "Microphone access is off",
                    body: "Veena listens for the keyboard through this app. Turn on Microphone for Veena in Settings, then come back."
                )
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .buttonStyle(.borderedProminent)
            } else {
                message(
                    icon: "mic",
                    title: "Allow the microphone",
                    body: "Veena listens for the keyboard through this app. Nothing is recorded until you tap the keyboard's mic."
                )
            }
        } else if session.startFailure == .microphoneUnavailable {
            message(
                icon: "mic.slash",
                title: "The microphone is busy",
                body: "Another recording is using it. Finish that, then try again."
            )
            Button("Try again") { session.startSession() }
                .buttonStyle(.borderedProminent)
        } else {
            ProgressView()
        }
    }

    private var live: some View {
        VStack(spacing: 22) {
            ZStack {
                Circle()
                    .fill(VeenaColors.ready.opacity(0.18))
                    .frame(width: 128, height: 128)
                    .scaleEffect(pulse ? 1.08 : 0.92)
                Circle()
                    .fill(VeenaColors.ready)
                    .frame(width: 84, height: 84)
                    .overlay {
                        Image(systemName: "mic.fill")
                            .font(.system(size: 34))
                            .foregroundStyle(.white)
                    }
            }

            VStack(spacing: 8) {
                Text("The keyboard mic is on")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text("Go back to your app and start speaking — dictation starts as soon as you're there. Tap ■ on the keyboard when you're done.")
                    .font(.body)
                    .foregroundStyle(VeenaColors.textSecondary)
                    .multilineTextAlignment(.center)
            }

            ReturnHint()

            Text(footnote)
                .font(.footnote)
                .foregroundStyle(VeenaColors.textSecondary)
                .multilineTextAlignment(.center)

            Button("Turn off the mic", role: .destructive) {
                session.endSession()
                dismiss()
            }
            .font(.subheadline)
        }
    }

    private var footnote: String {
        let minutes = KeyboardSharedSettings.sessionMinutes
        let length = minutes >= 60 ? "an hour" : "\(minutes) minutes"
        return "Veena keeps the mic ready for \(length) after your last dictation, and only keeps what you say between tapping the mic and tapping stop. iOS shows the orange dot while it's on."
    }

    private func message(icon: String, title: String, body: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 44))
                .foregroundStyle(VeenaColors.accent)
            Text(title)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
            Text(body)
                .font(.body)
                .foregroundStyle(VeenaColors.textSecondary)
                .multilineTextAlignment(.center)
        }
    }
}

/// A drawing of the two ways back to the previous app: the "◀ App" button
/// iOS puts in the status bar, and a swipe along the home indicator.
struct ReturnHint: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                HStack(spacing: 2) {
                    Image(systemName: "arrowtriangle.left.fill")
                        .font(.system(size: 9))
                    Text("Messages")
                        .font(.system(size: 13, weight: .semibold))
                }
                .foregroundStyle(VeenaColors.accent)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Capsule().fill(VeenaColors.backgroundSelected))
                Text("Tap the back button in the top-left corner")
                    .font(.subheadline)
            }
            HStack(spacing: 12) {
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(VeenaColors.textPrimary.opacity(0.7))
                        .frame(width: 70, height: 5)
                    Image(systemName: "arrow.right")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(VeenaColors.accent)
                        .offset(x: 26, y: -12)
                }
                .frame(width: 92, height: 26)
                Text("or swipe right along the bottom edge")
                    .font(.subheadline)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(VeenaColors.backgroundElement))
    }
}
