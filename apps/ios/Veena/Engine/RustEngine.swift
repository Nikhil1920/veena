import Foundation

/// Owns the native transcriber for the currently selected language. The
/// Telugu model is a single-language graph; every other language loads the
/// multilingual graph and passes the language code per utterance.
///
/// The app and the keyboard share this engine. The lock covers loading
/// only: transcriptions run concurrently (the native engine takes turns per
/// window), so a keyboard dictation never waits out a long import. A model
/// replaced mid-transcription is freed when its last transcription ends.
@Observable
final class RustEngine {

    enum State: Equatable {
        case notLoaded
        case loading(languageCode: String)
        case ready(languageCode: String)
        case failed(languageCode: String, String)

        var isReady: Bool {
            if case .ready = self { return true }
            return false
        }

        var languageCode: String? {
            switch self {
            case .notLoaded: return nil
            case .loading(let code), .ready(let code), .failed(let code, _): return code
            }
        }
    }

    /// Written on the main thread only, where SwiftUI reads it.
    private(set) var state: State = .notLoaded

    /// A native transcriber and the transcriptions running on it.
    private final class LoadedModel {
        let handle: OpaquePointer
        let modelID: String
        var users = 0
        var retired = false

        init(handle: OpaquePointer, modelID: String) {
            self.handle = handle
            self.modelID = modelID
        }
    }

    // Guarded by `lock`.
    private var current: LoadedModel?
    private var loadedLanguage: String?
    private let lock = NSRecursiveLock()

    /// Loads (or reloads) the engine for a language; no-op when current.
    /// Runs off the main thread: session creation blocks for seconds.
    func ensureLanguage(_ code: String, models: ModelRepository) async throws {
        // Download (if needed) before taking the lock — it can take minutes.
        let dir = try await models.ensureModel(forLanguage: code)
        try await Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            self.lock.lock()
            defer { self.lock.unlock() }
            try self.loadLocked(languageCode: code, modelDir: dir)
        }.value
    }

    /// Makes `code` the active language. Languages sharing a model (every
    /// non-Telugu language uses the multilingual graph) switch without a
    /// reload: the language is passed per utterance.
    private func loadLocked(languageCode code: String, modelDir: URL) throws {
        let modelID = ModelCatalog.model(forLanguage: code).id
        if let current, current.modelID == modelID {
            if loadedLanguage != code {
                loadedLanguage = code
                publish(.ready(languageCode: code))
            }
            return
        }

        publish(.loading(languageCode: code))
        do {
            retireLocked()
            current = LoadedModel(handle: try createHandle(modelDir: modelDir), modelID: modelID)
            loadedLanguage = code
            publish(.ready(languageCode: code))
        } catch {
            retireLocked()
            publish(.failed(languageCode: code, error.localizedDescription))
            throw error
        }
    }

    /// Main-queue writes keep their order, so the last state published wins.
    private func publish(_ new: State) {
        DispatchQueue.main.async { self.state = new }
    }

    /// The engine as a caller working in `code` sees it: another model's
    /// load, failure or readiness doesn't concern it, since transcription
    /// loads its model on demand.
    func state(forLanguage code: String, models: ModelRepository) -> State {
        let ours = ModelCatalog.model(forLanguage: code)
        guard let shown = state.languageCode else { return state }
        if ModelCatalog.model(forLanguage: shown).id == ours.id { return state }
        return models.isDownloaded(ours) ? .ready(languageCode: code) : .notLoaded
    }

    private func createHandle(modelDir: URL) throws -> OpaquePointer {
        let masksURL = modelDir.appendingPathComponent("language_masks.json")
        let configURL = modelDir.appendingPathComponent("decoder_config.json")

        let newHandle: OpaquePointer?
        if FileManager.default.fileExists(atPath: masksURL.path) {
            newHandle = transcriber_create_multilingual(
                modelDir.appendingPathComponent("indicconformer_multi_ctc_int8.onnx").path,
                configURL.path,
                modelDir.appendingPathComponent("vocab.json").path,
                masksURL.path
            )
        } else {
            let files = (try? FileManager.default.contentsOfDirectory(at: modelDir, includingPropertiesForKeys: nil)) ?? []
            let onnx = files.first {
                !$0.lastPathComponent.hasPrefix("._") && $0.pathExtension.lowercased() == "onnx"
            }
            let vocab = files.first {
                !$0.lastPathComponent.hasPrefix("._") && $0.pathExtension.lowercased() == "vocab"
            }
            guard let onnx, let vocab else {
                throw EngineError.missingFiles(modelDir.path)
            }
            newHandle = transcriber_create(onnx.path, configURL.path, vocab.path)
        }

        guard let newHandle else {
            throw EngineError.creationFailed
        }
        return newHandle
    }

    /// Long audio is transcribed in windows of up to 25 s. Called on the
    /// transcribing thread with (completed, total) windows: once with none
    /// completed, then after each window.
    typealias ProgressHandler = (_ completed: Int, _ total: Int) -> Void

    /// Transcribes 16 kHz mono samples in `languageCode`, loading its model
    /// first if another language is active (the app and the keyboard share
    /// this engine). The model must already be downloaded. Blocks: call it
    /// off the main thread. Cancelling the calling task stops the engine
    /// before its next window and throws `CancellationError`.
    func transcribe(
        _ samples: [Float],
        languageCode: String,
        models: ModelRepository,
        sampleRate: UInt32 = 16_000,
        progress: ProgressHandler? = nil
    ) throws -> String {
        try withHandle(languageCode: languageCode, models: models) { lang, handle in
            try Self.run(progress) { callback, context in
                transcriber_transcribe_audio_progress(
                    handle, samples, samples.count, sampleRate, lang, callback, context
                )
            }
        }
    }

    /// Transcribes an audio file in `languageCode`. WAV goes straight to the
    /// engine; other containers are decoded to 16 kHz mono floats first.
    /// Cancellable like `transcribe`.
    func transcribeFile(
        at url: URL,
        languageCode: String,
        models: ModelRepository,
        progress: ProgressHandler? = nil,
        decoder: (URL) throws -> ([Float], UInt32)
    ) throws -> String {
        try withHandle(languageCode: languageCode, models: models) { lang, handle in
            if url.pathExtension.lowercased() == "wav" {
                return try Self.run(progress) { callback, context in
                    transcriber_transcribe_file_progress(handle, url.path, lang, callback, context)
                }
            }
            let (samples, rate) = try decoder(url)
            return try Self.run(progress) { callback, context in
                transcriber_transcribe_audio_progress(
                    handle, samples, samples.count, rate, lang, callback, context
                )
            }
        }
    }

    /// Runs one synchronous engine call with a C callback that forwards
    /// progress and asks the engine to stop once the calling task is cancelled.
    private static func run(
        _ progress: ProgressHandler?,
        _ body: (TranscriberProgressFn, UnsafeMutableRawPointer) -> UnsafeMutablePointer<CChar>?
    ) throws -> String {
        let box = ProgressBox(progress)
        let result = withExtendedLifetime(box) {
            body(forwardProgress, Unmanaged.passUnretained(box).toOpaque())
        }
        if Task.isCancelled {
            if let result { transcriber_free_string(result) }
            throw CancellationError()
        }
        return try string(from: result)
    }

    private final class ProgressBox {
        let handler: ProgressHandler?
        init(_ handler: ProgressHandler?) { self.handler = handler }
    }

    /// Runs on the calling task's thread, so `Task.isCancelled` sees that task.
    private static let forwardProgress: TranscriberProgressFn = { context, completed, total in
        guard let context else { return true }
        Unmanaged<ProgressBox>.fromOpaque(context).takeUnretainedValue().handler?(completed, total)
        return !Task.isCancelled
    }

    /// Runs `body` on the model for `languageCode`, loading it first if
    /// needed. The lock is held only while loading; the model stays alive
    /// until `body` returns even if another caller switches models meanwhile.
    private func withHandle<T>(
        languageCode: String,
        models: ModelRepository,
        _ body: (String, OpaquePointer) throws -> T
    ) throws -> T {
        let model = try acquire(languageCode: languageCode, models: models)
        defer {
            lock.lock()
            model.users -= 1
            if model.retired && model.users == 0 {
                transcriber_destroy(model.handle)
            }
            lock.unlock()
        }
        return try body(languageCode, model.handle)
    }

    /// Loads the model for `languageCode` and counts the caller as its user.
    private func acquire(languageCode: String, models: ModelRepository) throws -> LoadedModel {
        lock.lock()
        defer { lock.unlock() }
        let def = ModelCatalog.model(forLanguage: languageCode)
        guard models.isDownloaded(def) else {
            throw EngineError.notReady
        }
        try loadLocked(languageCode: languageCode, modelDir: models.contentDir(def))
        guard let current else {
            throw EngineError.notReady
        }
        current.users += 1
        return current
    }

    /// Drops the current model: freed now if idle, else by the last
    /// transcription still running on it.
    private func retireLocked() {
        guard let model = current else { return }
        current = nil
        loadedLanguage = nil
        if model.users == 0 {
            transcriber_destroy(model.handle)
        } else {
            model.retired = true
        }
    }

    static func string(from pointer: UnsafeMutablePointer<CChar>?) throws -> String {
        defer {
            if let pointer { transcriber_free_string(pointer) }
        }
        guard let pointer else {
            throw EngineError.transcriptionFailed
        }
        return String(cString: pointer)
    }
}

enum EngineError: LocalizedError {
    case notReady
    case creationFailed
    case transcriptionFailed
    case missingFiles(String)

    var errorDescription: String? {
        switch self {
        case .notReady: return "Model not ready yet"
        case .creationFailed: return "Failed to create native transcriber"
        case .transcriptionFailed: return "Transcription failed"
        case .missingFiles(let path): return "No model files found in \(path)"
        }
    }
}
