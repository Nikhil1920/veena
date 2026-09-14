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
    /// "mic" for live dictation, "import" for a picked audio file.
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
