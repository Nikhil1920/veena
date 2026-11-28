import Foundation

/// One entry in a conversation: a finished utterance, or a reply the user
/// typed for the other person to read.
struct ConversationItem: Identifiable, Equatable {
    enum Kind: Equatable {
        case speech
        case typed
    }

    let id = UUID()
    let kind: Kind
    let time: Date
    let text: String
}

/// How a conversation is laid out: on screen, in History and when shared.
/// Mirrors veena-android's conversation export.
enum ConversationTranscript {

    /// A time is shown before the first item and after a gap this long.
    static let timestampGap: TimeInterval = 2 * 60

    static func needsTimestamp(at time: Date, after previous: Date?) -> Bool {
        guard let previous else { return true }
        return time.timeIntervalSince(previous) >= timestampGap
    }

    /// "9:26 am", in the user's locale.
    static func timeText(_ date: Date) -> String {
        date.formatted(date: .omitted, time: .shortened)
    }

    /// The conversation as plain text: a time line where the screen shows
    /// one after a pause, speech one utterance per line, and each typed reply
    /// as its own paragraph. The start time is left to whatever shows the
    /// text (History has it). Blank lines go before time lines and around
    /// typed replies:
    ///
    ///     Hello.
    ///     I am speaking.
    ///
    ///     Typed response: And I am typing
    ///
    ///     So this is being transcribed.
    ///
    ///     9:31 am
    ///     Okay.
    static func text(for items: [ConversationItem]) -> String {
        var lines: [String] = []
        func blankLine() {
            if let last = lines.last, !last.isEmpty { lines.append("") }
        }

        var previous: ConversationItem?
        for item in items {
            let timestamp = previous != nil && needsTimestamp(at: item.time, after: previous?.time)
            if timestamp {
                blankLine()
                lines.append(timeText(item.time))
            }
            switch item.kind {
            case .speech:
                if previous?.kind == .typed && !timestamp { blankLine() }
                lines.append(item.text)
            case .typed:
                if !timestamp { blankLine() }
                lines.append("Typed response: \(item.text)")
            }
            previous = item
        }
        return lines.joined(separator: "\n")
    }
}
