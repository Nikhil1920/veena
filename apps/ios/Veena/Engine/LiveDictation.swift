import Foundation

/// The engine's live session for one recording (see `live.rs` in the engine
/// crate). Its calls block, and it takes one at a time;
/// `RustEngine.openLive` creates it.
final class LiveTranscriber {
    private var session: OpaquePointer?
    private let release: () -> Void

    init(session: OpaquePointer, release: @escaping () -> Void) {
        self.session = session
        self.release = release
    }

    deinit { close() }

    /// Adds `audio` (16 kHz mono) to the recording and returns the recording
    /// so far as text. Its last words can still change.
    func preview(_ audio: [Float]) throws -> String {
        try text(adding: audio, live_session_preview)
    }

    /// Adds `audio` and returns the transcript: the text `RustEngine.transcribe`
    /// gives for the whole recording, with all but its last 25 s window
    /// already done.
    func finish(_ audio: [Float]) throws -> String {
        try text(adding: audio, live_session_finish)
    }

    private func text(
        adding audio: [Float],
        _ read: (OpaquePointer?) -> UnsafeMutablePointer<CChar>?
    ) throws -> String {
        guard let session else { throw EngineError.transcriptionFailed }
        if !audio.isEmpty { live_session_push(session, audio, audio.count) }
        return try RustEngine.string(from: read(session))
    }

    func close() {
        guard let session else { return }
        self.session = nil
        live_session_destroy(session)
        release()
    }
}

/// Text for a dictation while it is being recorded. From creation it keeps
/// handing newly recorded audio to the engine and reports each preview
/// through `onText`; `finish` ends it with the transcript.
///
/// Previews are a convenience on top of the dictation, never a way for it to
/// fail: if the live session cannot start or breaks, `onText` goes quiet and
/// `finish` returns nil, and the caller transcribes the recording as it
/// would without live text.
final class LiveDictation {

    /// Set by `finish` to the whole recording.
    private final class Recorded {
        private let lock = NSLock()
        private var stored: [Float]?

        var audio: [Float]? {
            get { lock.withLock { stored } }
            set { lock.withLock { stored = newValue } }
        }
    }

    private let recorded: Recorded
    private let task: Task<String?, Never>

    /// - Parameter audioFrom: the samples recorded so far from an index on,
    ///   as 16 kHz mono floats.
    init(
        engine: RustEngine,
        models: ModelRepository,
        languageCode: String,
        audioFrom: @escaping (Int) -> [Float],
        onText: @escaping @MainActor (String) -> Void
    ) {
        let recorded = Recorded()
        self.recorded = recorded
        task = Task.detached(priority: .userInitiated) {
            guard let session = try? engine.openLive(languageCode: languageCode, models: models) else {
                return nil
            }
            defer { session.close() }

            let clock = ContinuousClock()
            var previewed = 0
            var pause = Self.poll
            while true {
                let wakeAt = clock.now + pause
                while recorded.audio == nil, clock.now < wakeAt {
                    do { try await Task.sleep(for: Self.poll) } catch { return nil }
                }
                if Task.isCancelled { return nil }
                if let all = recorded.audio {
                    return try? session.finish(Array(all[min(previewed, all.count)...]))
                }

                let fresh = audioFrom(previewed)
                guard fresh.count >= Self.minFreshSamples else {
                    pause = Self.poll
                    continue
                }
                previewed += fresh.count
                let startedAt = clock.now
                guard let text = try? session.preview(fresh) else { return nil }
                await onText(text)
                // A slow phone spends at most two thirds of its time on
                // previews, which also bounds how long a preview in flight
                // can hold up the transcript.
                pause = max(Self.minPause, (clock.now - startedAt) / 2)
            }
        }
    }

    deinit { task.cancel() }

    /// Ends the previews and returns the transcript of `audio`, the whole
    /// recording; nil when live transcription failed along the way or the
    /// calling task was cancelled.
    func finish(_ audio: [Float]) async -> String? {
        recorded.audio = audio
        return await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            task.cancel()
        }
    }

    /// Ends the previews and drops the dictation.
    func cancel() async {
        task.cancel()
        _ = await task.value
    }

    private static let poll = Duration.milliseconds(50)
    /// A preview is worth running for 200 ms of new audio.
    private static let minFreshSamples = 3_200
    private static let minPause = Duration.milliseconds(200)
}
