import SwiftUI
import SwiftData

/// Full transcript for one history entry with playback, copy, and delete.
struct HistoryDetailView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    let entry: HistoryEntry

    var body: some View {
        VStack(spacing: 16) {
            Text(entry.createdAt.formatted(date: .long, time: .standard))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)

            ScrollView {
                Text(entry.transcript)
                    .font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .background(VeenaColors.backgroundElement)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            HStack(spacing: 12) {
                if audioExists {
                    Button {
                        environment.player.toggle(path: entry.audioPath)
                    } label: {
                        Label(
                            environment.player.playingPath == entry.audioPath ? "Pause" : "Play",
                            systemImage: environment.player.playingPath == entry.audioPath ? "pause.fill" : "play.fill"
                        )
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(VeenaColors.accent)
                }

                Button {
                    UIPasteboard.general.string = entry.transcript
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                }
                .buttonStyle(.bordered)
            }
            .padding(.bottom, 24)
        }
        .padding(.horizontal, 16)
        .navigationTitle("Recording")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(role: .destructive) {
                    deleteEntry()
                } label: {
                    Image(systemName: "trash")
                }
            }
        }
    }

    private var audioExists: Bool {
        !entry.audioPath.isEmpty && FileManager.default.fileExists(atPath: entry.audioPath)
    }

    private func deleteEntry() {
        if !entry.audioPath.isEmpty {
            try? FileManager.default.removeItem(atPath: entry.audioPath)
        }
        modelContext.delete(entry)
        try? modelContext.save()
        dismiss()
    }
}
