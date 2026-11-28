import Foundation
import SwiftData

@Model
final class HistoryEntry {
    @Attribute(.unique) var id: UUID
    var createdAt: Date
    var languageCode: String
    var transcript: String
    var audioPath: String
    var durationMs: Double
    /// "mic" for live dictation, "import" for a picked audio file,
    /// "conversation" for the Conversation screen (no audio).
    var source: String

    init(
        id: UUID = UUID(),
        createdAt: Date = Date(),
        languageCode: String,
        transcript: String,
        audioPath: String,
        durationMs: Double,
        source: String
    ) {
        self.id = id
        self.createdAt = createdAt
        self.languageCode = languageCode
        self.transcript = transcript
        self.audioPath = audioPath
        self.durationMs = durationMs
        self.source = source
    }
}

extension HistoryEntry {
    static let conversationSource = "conversation"

    var isConversation: Bool { source == Self.conversationSource }

    /// The transcript as a row shows it: a conversation's lines run on.
    var previewText: String {
        guard isConversation else { return transcript }
        return transcript.split(separator: "\n").joined(separator: " ")
    }
}
