import Foundation

/// Where the voice models live. They sit in the App Group container rather
/// than the app's own, so the keyboard can read them: its in-place dictation
/// runs the model itself instead of handing the audio to the app.
enum SharedModelStore {

    static let completeMarker = ".complete"

    /// The largest model the keyboard dictates with by itself. It runs in a
    /// web view's process, which takes about four times the file while
    /// loading: Telugu's model (156 MB) and each language's own (147 MB) fit,
    /// the multilingual one (676 MB) would not.
    static let largestKeyboardModelBytes = 250 * 1024 * 1024

    /// nil when the App Group container is unreachable (in the keyboard:
    /// Full Access is off).
    static let directory: URL? = FileManager.default
        .containerURL(forSecurityApplicationGroupIdentifier: KeyboardBridge.appGroupID)?
        .appendingPathComponent("models", isDirectory: true)

    /// The files of one installed model.
    struct Files {
        let modelID: String
        let onnx: URL
        let onnxBytes: Int
        let vocab: URL
        let decoderConfig: URL
        /// Per-language output classes; only the multilingual model has them.
        let masks: URL?

        /// Small enough for the keyboard to run without the app.
        var fitsInKeyboard: Bool { onnxBytes <= SharedModelStore.largestKeyboardModelBytes }
    }

    /// The tarball wraps a model's files in a top-level directory; the
    /// loaders want that directory.
    static func contentDirectory(of modelDir: URL) -> URL {
        guard let children = try? FileManager.default.contentsOfDirectory(
            at: modelDir, includingPropertiesForKeys: [.isDirectoryKey]
        ) else { return modelDir }
        let visible = children.filter { !$0.lastPathComponent.hasPrefix(".") }
        if visible.contains(where: { !$0.hasDirectoryPath && $0.pathExtension == "onnx" }) {
            return modelDir
        }
        let inner = visible.filter { $0.hasDirectoryPath }
        return inner.count == 1 ? inner[0] : modelDir
    }

    /// The installed model `modelID`, or nil when it isn't fully downloaded.
    static func files(modelID: String) -> Files? {
        guard let modelDir = directory?.appendingPathComponent(modelID, isDirectory: true),
              FileManager.default.fileExists(atPath: modelDir.appendingPathComponent(completeMarker).path)
        else { return nil }
        let dir = contentDirectory(of: modelDir)
        let children = ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { !$0.lastPathComponent.hasPrefix(".") }
        func first(_ ext: String) -> URL? {
            children.first { $0.pathExtension.lowercased() == ext }
        }
        let masks = dir.appendingPathComponent("language_masks.json")
        let multilingual = FileManager.default.fileExists(atPath: masks.path)
        let vocab = multilingual ? dir.appendingPathComponent("vocab.json") : first("vocab")
        guard let onnx = first("onnx"), let vocab,
              let bytes = (try? onnx.resourceValues(forKeys: [.fileSizeKey]))?.fileSize
        else { return nil }
        return Files(
            modelID: modelID,
            onnx: onnx,
            onnxBytes: bytes,
            vocab: vocab,
            decoderConfig: dir.appendingPathComponent("decoder_config.json"),
            masks: multilingual ? masks : nil
        )
    }
}
