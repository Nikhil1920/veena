import SwiftUI
import SwiftData

/// List of past dictation sessions and imported recordings; transcripts can
/// be copied straight from a row.
struct HistoryListView: View {
    @Environment(AppEnvironment.self) private var environment
    @Query(sort: \HistoryEntry.createdAt, order: .reverse)
    private var entries: [HistoryEntry]
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if entries.isEmpty {
                    ContentUnavailableView(
                        "No recordings yet",
                        systemImage: "clock.arrow.circlepath",
                        description: Text("Dictations and imported files will appear here")
                    )
                } else {
                    List(entries) { entry in
                        NavigationLink(value: entry.id) {
                            HistoryRow(entry: entry)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("History")
            .navigationDestination(for: UUID.self) { id in
                if let entry = entries.first(where: { $0.id == id }) {
                    HistoryDetailView(entry: entry)
                }
            }
        }
    }
}

struct HistoryRow: View {
    let entry: HistoryEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(language(byCode: entry.languageCode)?.name ?? entry.languageCode)
                    .font(.caption2)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(VeenaColors.backgroundSelected))
                    .foregroundStyle(.secondary)

                if entry.source == "import" {
                    Text("imported").font(.caption2).foregroundStyle(.secondary)
                } else if entry.durationMs > 0 {
                    Text(String(format: "%.1fs", entry.durationMs / 1000))
                        .font(.caption2).foregroundStyle(.secondary)
                }

                Spacer()
                Text(entry.createdAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            HStack(alignment: .top) {
                Text(entry.transcript)
                    .font(.subheadline)
                    .lineLimit(2)
                Spacer(minLength: 4)
                Button {
                    UIPasteboard.general.string = entry.transcript
                } label: {
                    Image(systemName: "doc.on.doc")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 4)
    }
}
