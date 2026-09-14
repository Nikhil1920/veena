import Foundation

/// Owns the native transcriber for the currently selected language. The
/// Telugu model is a single-language graph; every other language loads the
/// multilingual graph and passes the language code per utterance.
@Observable
final class RustEngine {

    enum State: Equatable {
        case notLoaded
        case loading
        case ready(languageCode: String)
        case failed(String)

        var isReady: Bool {
            if case .ready = self { return true }
            return false
        }
    }

    private(set) var state: State = .notLoaded

    private var handle: OpaquePointer?
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

            if let loaded = self.loadedLanguage, loaded == code, self.handle != nil,
               case .ready = self.state {
                return
            }

            self.state = .loading
            do {
                try self.createHandle(modelDir: dir, languageCode: code)
                self.state = .ready(languageCode: code)
            } catch {
                self.destroyLocked()
                self.state = .failed(error.localizedDescription)
                throw error
            }
        }.value
    }

    private func createHandle(modelDir: URL, languageCode: String) throws {
        destroyLocked()

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
        handle = newHandle
        loadedLanguage = languageCode
    }

    /// Transcribes 16 kHz mono samples.
    func transcribe(_ samples: [Float], sampleRate: UInt32 = 16_000) throws -> String {
        try withHandle { lang, handle in
            let result = transcriber_transcribe_audio_lang(handle, samples, samples.count, sampleRate, lang)
            return try Self.string(from: result)
        }
    }

    /// Transcribes an audio file. WAV goes straight to the engine; other
    /// containers are decoded to 16 kHz mono floats first.
    func transcribeFile(at url: URL, decoder: (URL) throws -> ([Float], UInt32)) throws -> String {
        try withHandle { lang, handle in
            let isWav = url.pathExtension.lowercased() == "wav"
            let result: UnsafeMutablePointer<CChar>?
            if isWav {
                result = transcriber_transcribe_file_lang(handle, url.path, lang)
            } else {
                let (samples, rate) = try decoder(url)
                result = transcriber_transcribe_audio_lang(handle, samples, samples.count, rate, lang)
            }
            return try Self.string(from: result)
        }
    }

    private func withHandle<T>(_ body: (String?, OpaquePointer) throws -> T) throws -> T {
        lock.lock()
        defer { lock.unlock() }
        guard let handle, let lang = loadedLanguage, case .ready = state else {
            throw EngineError.notReady
        }
        return try body(lang, handle)
    }

    private func destroyLocked() {
        if let handle {
            transcriber_destroy(handle)
            self.handle = nil
            loadedLanguage = nil
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
