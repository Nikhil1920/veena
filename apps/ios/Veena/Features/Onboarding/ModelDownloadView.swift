import SwiftUI
import os

/// Shows download progress for a voice model, with retry/cancel handling.
/// Mirrors Android ModelDownloadScreen.kt headline + onSuccess pop logic.
struct ModelDownloadView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    let languageCode: String
    let mode: LanguagePickerView.Mode
    /// Optional callbacks to let a presenting sheet/navigation handle pop.
    /// When nil, the view falls back to `dismiss()` / no-op for onboarding.
    var onSuccess: (() -> Void)? = nil
    var onDismiss: (() -> Void)? = nil

    @State private var started = false

    private var def: ModelDef {
        ModelCatalog.model(forLanguage: languageCode)
    }

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Text(mode == .onboarding ? "Downloading your voice model" : "Downloading language model")
                .font(.title2.bold())
                .multilineTextAlignment(.center)
            Text(def.displayName)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            stateView
            Spacer()
        }
        .padding(.horizontal, 32)
        .navigationBarBackButtonHidden(true)
        .task {
            guard !started else { return }
            started = true
            await startDownload()
        }
    }

    @ViewBuilder
    private var stateView: some View {
        switch environment.models.downloadState(def) {
        case .downloading(let downloaded, let total):
            VStack(spacing: 10) {
                ProgressView(value: total > 0 ? Double(downloaded) / Double(total) : 0)
                Text("\(formatMb(downloaded)) / \(formatMb(total)) MB")
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        case .verifying:
            VStack(spacing: 10) {
                ProgressView()
                Text("Verifying…").font(.footnote).foregroundStyle(.secondary)
            }
        case .failed(let message):
            VStack(spacing: 16) {
                Text("Download failed: \(message)")
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                Button("Retry") { Task { await startDownload() } }
                    .buttonStyle(.borderedProminent)
                Button("Back") { goBack() }
            }
        case .cancelled:
            VStack(spacing: 16) {
                Text("Download cancelled").font(.footnote).foregroundStyle(.secondary)
                Button("Retry") { Task { await startDownload() } }
                    .buttonStyle(.borderedProminent)
                Button("Back") { goBack() }
            }
        case .idle:
            ProgressView()
        }
    }

    private func startDownload() async {
        Logger(subsystem: "com.byanr.veena", category: "ui").log("ModelDownloadView.startDownload(\(self.languageCode, privacy: .public))")
        do {
            try await environment.models.download(def)
            if mode == .keyboard {
                KeyboardSharedSettings.keyboardLanguageCode = languageCode
            } else {
                environment.languageCode = languageCode
            }
            finish()
        } catch is CancellationError {
            // stay; user can retry or back out
        } catch {
            // state carries the failure message
        }
    }

    private func finish() {
        if let onSuccess {
            onSuccess()
            return
        }
        // Legacy path for SettingsView / onboarding without callback
        switch mode {
        case .onboarding:
            // RootView swaps to MainTabs as soon as languageCode is set; nothing to pop.
            break
        case .settings, .dictate, .keyboard:
            // Pop the download screen back to its parent list.
            // In a NavigationStack this is `dismiss()` when pushed; the
            // SettingsView's path handles it via `path.removeLast()`, but
            // calling dismiss is safe for sheet contexts.
            dismiss()
        }
    }

    private func goBack() {
        if let onDismiss {
            onDismiss()
            return
        }
        switch mode {
        case .onboarding:
            // Nothing to pop when download failed before language was set.
            break
        case .settings, .dictate, .keyboard:
            dismiss()
        }
    }

    private func formatMb(_ bytes: Int64) -> String {
        "\(Int((Double(bytes) / 1_000_000).rounded()))"
    }
}
