import Foundation

/// The engine's conversation captions (see `conversation.rs` in the engine
/// crate): audio goes in for as long as the conversation lasts, and comes
/// back as one line of text per utterance. Its calls block, and it takes one
/// at a time; `RustEngine.openConversation` creates it.
final class ConversationTranscriber {

    /// What changed since the last step.
    struct Update: Decodable, Equatable {
        /// Final text of each utterance that ended, oldest first.
        var finished: [String]
        /// The utterance still being spoken; its words can still change.
        var partial: String
        /// Whether an utterance is in progress.
        var speaking: Bool
    }

    private var conversation: OpaquePointer?
    private let release: () -> Void

    init(conversation: OpaquePointer, release: @escaping () -> Void) {
        self.conversation = conversation
        self.release = release
    }

    deinit { close() }

    /// Adds 16 kHz mono audio. Cheap: decoding waits for `step`.
    func push(_ audio: [Float]) {
        guard let conversation, !audio.isEmpty else { return }
        conversation_push(conversation, audio, audio.count)
    }

    /// Decodes what is due; nil if that failed (the engine drops an
    /// utterance it could not decode and carries on).
    func step() -> Update? {
        update(conversation_step)
    }

    /// Ends the utterance in progress and returns its final text.
    func flush() -> Update? {
        update(conversation_flush)
    }

    private func update(_ read: (OpaquePointer?) -> UnsafeMutablePointer<CChar>?) -> Update? {
        guard let conversation, let json = try? RustEngine.string(from: read(conversation)) else {
            return nil
        }
        return try? JSONDecoder().decode(Update.self, from: Data(json.utf8))
    }

    func close() {
        guard let conversation else { return }
        self.conversation = nil
        conversation_destroy(conversation)
        release()
    }
}
