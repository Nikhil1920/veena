import SwiftUI

/// The Conversation screen's gear: caption size, language, saving, and
/// sharing, copying or clearing the conversation.
struct ConversationSettingsSheet: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var path = NavigationPath()
    @State private var copied = false
    @State private var confirmClear = false

    private enum Route: Hashable {
        case picker
        case download(String)
    }

    var body: some View {
        @Bindable var conversation = environment.conversation
        let isEmpty = conversation.items.isEmpty
        NavigationStack(path: $path) {
            Form {
                Section("Text size") {
                    HStack(spacing: 14) {
                        stepButton("minus", by: -1, label: "Smaller")
                            .disabled(conversation.fontSize <= ConversationController.fontSizes.lowerBound)
                        Slider(
                            value: $conversation.fontSize,
                            in: ConversationController.fontSizes,
                            step: ConversationController.fontSizeStep
                        )
                        .accessibilityLabel("Text size")
                        .accessibilityValue("\(Int(conversation.fontSize)) points")
                        stepButton("plus", by: 1, label: "Larger")
                            .disabled(conversation.fontSize >= ConversationController.fontSizes.upperBound)
                    }
                    Text("What people say looks like this.")
                        .font(.system(size: CGFloat(conversation.fontSize)))
                        .foregroundStyle(VeenaColors.textPrimary)
                        .padding(.vertical, 4)
                }

                Section {
                    NavigationLink(value: Route.picker) {
                        HStack {
                            Text("Language")
                            Spacer()
                            Text(environment.selectedLanguage?.name ?? "")
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Section {
                    Toggle("Save conversations to History", isOn: $conversation.saveToHistory)
                } footer: {
                    Text("Each conversation is one History entry. Only the text is kept, never the audio.")
                }

                Section {
                    ShareLink(item: conversation.transcriptText) {
                        Label("Share conversation", systemImage: "square.and.arrow.up")
                    }
                    .disabled(isEmpty)
                    Button {
                        UIPasteboard.general.string = conversation.transcriptText
                        copied = true
                    } label: {
                        Label(copied ? "Copied" : "Copy conversation",
                              systemImage: copied ? "checkmark" : "doc.on.doc")
                    }
                    .disabled(isEmpty)
                    Button(role: .destructive) {
                        confirmClear = true
                    } label: {
                        Label("Clear conversation", systemImage: "trash")
                    }
                    .disabled(isEmpty)
                    .clearConversationConfirmation(
                        isPresented: $confirmClear,
                        saveToHistory: conversation.saveToHistory
                    ) {
                        environment.conversation.startNewConversation()
                        dismiss()
                    }
                }
            }
            .navigationTitle("Conversation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .picker:
                    // A downloaded language is picked in place; the tabs
                    // rebuild for it, closing this sheet.
                    LanguagePickerView(mode: .dictate) { code in
                        path.append(Route.download(code))
                    }
                case .download(let code):
                    ModelDownloadView(
                        languageCode: code,
                        mode: .dictate,
                        onSuccess: {
                            environment.languageCode = code
                            dismiss()
                        },
                        onDismiss: {
                            if !path.isEmpty { path.removeLast() }
                        }
                    )
                }
            }
            .task(id: copied) {
                guard copied else { return }
                try? await Task.sleep(for: .seconds(1.5))
                copied = false
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func stepButton(_ symbol: String, by steps: Double, label: String) -> some View {
        Button {
            let conversation = environment.conversation
            let size = conversation.fontSize + steps * ConversationController.fontSizeStep
            conversation.fontSize = min(max(size, ConversationController.fontSizes.lowerBound),
                                        ConversationController.fontSizes.upperBound)
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .semibold))
                .frame(width: 32, height: 32)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(label)
    }
}

extension View {
    /// Asks before clearing the conversation. Shared by the gear sheet and
    /// the Paused status line; the wording matches veena-android's
    /// ClearConversationDialog.
    func clearConversationConfirmation(
        isPresented: Binding<Bool>,
        saveToHistory: Bool,
        onClear: @escaping () -> Void
    ) -> some View {
        confirmationDialog("Clear this conversation?", isPresented: isPresented, titleVisibility: .visible) {
            Button("Clear", role: .destructive, action: onClear)
        } message: {
            Text(saveToHistory
                 ? "It stays in History. A new conversation starts here."
                 : "Saving to History is off, so this conversation will be gone.")
        }
    }
}
