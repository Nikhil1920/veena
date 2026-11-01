import Foundation
import CryptoKit
import os

private enum StreamEvent {
    case response(HTTPURLResponse)
    case chunk(Data)
}

private final class StreamDelegate: NSObject, URLSessionDataDelegate {
    private let continuation: AsyncThrowingStream<StreamEvent, Error>.Continuation

    init(continuation: AsyncThrowingStream<StreamEvent, Error>.Continuation) {
        self.continuation = continuation
        super.init()
    }

    func urlSession(
        _ session: URLSession, dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        if let http = response as? HTTPURLResponse {
            continuation.yield(.response(http))
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        continuation.yield(.chunk(data))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        continuation.finish(throwing: error)
    }
}

enum DownloadState: Equatable {
    case idle
    case downloading(downloaded: Int64, total: Int64)
    case verifying
    case failed(String)
    case cancelled
}

struct InstalledModel {
    let def: ModelDef
    let dir: URL
    let sizeBytes: Int64
}

/// Downloads model tarballs from dl.byanr.com (the same channel the desktop
/// app uses), verifies them, and unpacks them under
/// Application Support/models/<id>/. Downloads resume from .partial files.
@Observable
final class ModelRepository {

    private static let logger = Logger(subsystem: "com.byanr.veena", category: "models")

    private(set) var states: [String: DownloadState] = [:]

    /// Called after a model is installed or deleted.
    @ObservationIgnored var onInstalledModelsChanged: (() -> Void)?

    private let modelsDir: URL
    private static let completeMarker = ".complete"

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        modelsDir = support.appendingPathComponent("models", isDirectory: true)
        try? FileManager.default.createDirectory(at: modelsDir, withIntermediateDirectories: true)
    }

    func modelDir(_ def: ModelDef) -> URL {
        modelsDir.appendingPathComponent(def.id, isDirectory: true)
    }

    func isDownloaded(_ def: ModelDef) -> Bool {
        FileManager.default.fileExists(atPath: modelDir(def).appendingPathComponent(Self.completeMarker).path)
    }

    func installedModels() -> [InstalledModel] {
        ModelCatalog.all.filter { isDownloaded($0) }.map { def in
            let dir = modelDir(def)
            let size = (try? FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey]))?
                .compactMap { $0 as? URL }
                .filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true }
                .reduce(Int64(0)) { sum, url in
                    sum + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
                } ?? 0
            return InstalledModel(def: def, dir: dir, sizeBytes: size)
        }
    }

    /// The tarball wraps files in a top-level directory; the loader wants that dir.
    func contentDir(_ def: ModelDef) -> URL {
        let dir = modelDir(def)
        guard let children = try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.isDirectoryKey]
        ) else { return dir }
        let visible = children.filter { !$0.lastPathComponent.hasPrefix(".") && !$0.lastPathComponent.hasPrefix("._") }
        if visible.contains(where: { !$0.hasDirectoryPath && $0.pathExtension == "onnx" }) {
            return dir
        }
        let inner = visible.filter { $0.hasDirectoryPath }
        return inner.count == 1 ? inner[0] : dir
    }

    /// Downloads the model for a language if needed; returns the content dir.
    func ensureModel(forLanguage code: String) async throws -> URL {
        let def = ModelCatalog.model(forLanguage: code)
        if !isDownloaded(def) {
            Self.logger.log("ensureModel(\(code, privacy: .public)) needs download")
            try await download(def)
        }
        return contentDir(def)
    }

    func download(_ def: ModelDef) async throws {
        guard !isDownloaded(def) else {
            Self.logger.log("download(\(def.id, privacy: .public)) skipped: already installed")
            await setState(.idle)
            return
        }

        let dest = modelDir(def)
        let partial = modelsDir.appendingPathComponent("\(def.id).tar.gz.partial")
        let gunzipped = modelsDir.appendingPathComponent("\(def.id).tar")
        let extracting = modelsDir.appendingPathComponent("\(def.id).extracting", isDirectory: true)

        // SwiftUI reads `states` on the main thread while this runs on the
        // cooperative pool; writing it here directly races and crashes.
        func setState(_ s: DownloadState) async {
            await MainActor.run { self.states[def.id] = s }
        }

        Self.logger.log("download(\(def.id, privacy: .public)) starting")

        do {
            let already = (try? partial.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            await setState(.downloading(downloaded: Int64(already), total: def.sizeBytes))

            var request = URLRequest(url: def.url)
            if already > 0 {
                request.setValue("bytes=\(already)-", forHTTPHeaderField: "Range")
            }

            // createFile truncates an existing file, which would drop the
            // bytes the Range request above resumes after.
            if already == 0 {
                FileManager.default.createFile(atPath: partial.path, contents: nil)
            }
            var out = try FileHandle(forWritingTo: partial)
            defer { try? out.close() }

            var downloaded = Int64(0)
            var lastReport = Int64(0)

            for try await event in Self.stream(request: request) {
                try Task.checkCancellation()
                switch event {
                case let .response(http):
                    if http.statusCode == 206 {
                        _ = try out.seekToEnd()
                        downloaded = Int64(already)
                    } else if already > 0 {
                        // Server ignored the Range; restart from scratch.
                        try out.close()
                        try? FileManager.default.removeItem(at: partial)
                        FileManager.default.createFile(atPath: partial.path, contents: nil)
                        out = try FileHandle(forWritingTo: partial)
                        downloaded = 0
                    }
                case let .chunk(data):
                    try out.write(contentsOf: data)
                    downloaded += Int64(data.count)
                    if downloaded - lastReport > 512 * 1024 {
                        lastReport = downloaded
                        await setState(.downloading(downloaded: downloaded, total: def.sizeBytes))
                    }
                }
            }

            await setState(.verifying)
            if let expected = def.sha256 {
                let actual = try Self.sha256(of: partial)
                if actual.caseInsensitiveCompare(expected) != .orderedSame {
                    try? FileManager.default.removeItem(at: partial)
                    throw ModelError.checksum(expected: String(expected.prefix(12)), actual: String(actual.prefix(12)))
                }
            }

            try? FileManager.default.removeItem(at: extracting)
            try FileManager.default.createDirectory(at: extracting, withIntermediateDirectories: true)

            try? FileManager.default.removeItem(at: gunzipped)
            do {
                try Gzip.gunzip(source: partial, destination: gunzipped)
                try TarGz.extract(tarFile: gunzipped, into: extracting)
            } catch {
                try? FileManager.default.removeItem(at: gunzipped)
                try? FileManager.default.removeItem(at: extracting)
                throw error
            }
            try? FileManager.default.removeItem(at: gunzipped)
            Self.logger.log("download(\(def.id, privacy: .public)) extracted")

            try? FileManager.default.removeItem(at: dest)

            let children = try FileManager.default.contentsOfDirectory(
                at: extracting, includingPropertiesForKeys: [.isDirectoryKey])
            let sourceDir: URL
            if children.count == 1, children[0].hasDirectoryPath {
                sourceDir = children[0]
            } else {
                sourceDir = extracting
            }

            let hasOnnx = (try? FileManager.default.contentsOfDirectory(at: sourceDir, includingPropertiesForKeys: nil))?
                .contains { $0.pathExtension == "onnx" && !$0.lastPathComponent.hasPrefix("._") } == true
            guard hasOnnx == true else {
                throw ModelError.archiveLayout
            }

            if sourceDir == extracting {
                try FileManager.default.moveItem(at: extracting, to: dest)
            } else {
                try FileManager.default.moveItem(at: sourceDir, to: dest)
                try? FileManager.default.removeItem(at: extracting)
            }
            FileManager.default.createFile(
                atPath: dest.appendingPathComponent(Self.completeMarker).path, contents: Data(def.url.absoluteString.utf8))
            try? FileManager.default.removeItem(at: partial)
            await setState(.idle)
            onInstalledModelsChanged?()
            Self.logger.log("download(\(def.id, privacy: .public)) finished")
        } catch let error as CancellationError {
            await setState(.cancelled)
            throw error
        } catch {
            Self.logger.error("download(\(def.id, privacy: .public)) failed: \(error.localizedDescription, privacy: .public)")
            await setState(.failed(error.localizedDescription))
            throw error
        }
    }

    func delete(_ def: ModelDef) {
        try? FileManager.default.removeItem(at: modelDir(def))
        try? FileManager.default.removeItem(at: modelsDir.appendingPathComponent("\(def.id).tar.gz.partial"))
        states[def.id] = .idle
        onInstalledModelsChanged?()
    }

    func downloadState(_ def: ModelDef) -> DownloadState {
        states[def.id] ?? .idle
    }

    /// Streams a response body as Data chunks via a URLSession delegate —
    /// URLSession.AsyncBytes iterates per byte, far too slow for ~500 MB.
    private static func stream(request: URLRequest) -> AsyncThrowingStream<StreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let delegate = StreamDelegate(continuation: continuation)
            let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
            let task = session.dataTask(with: request)
            task.resume()
            continuation.onTermination = { _ in
                task.cancel()
                session.finishTasksAndInvalidate()
            }
        }
    }

    private static func sha256(of url: URL) throws -> String {
        var digest = SHA256()
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        while let chunk = try handle.read(upToCount: 1024 * 1024), !chunk.isEmpty {
            digest.update(data: chunk)
        }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

enum ModelError: LocalizedError {
    case checksum(expected: String, actual: String)
    case archiveLayout

    var errorDescription: String? {
        switch self {
        case let .checksum(expected, actual):
            return "Checksum mismatch (expected \(expected)…, got \(actual)…)"
        case .archiveLayout:
            return "Model archive did not contain an .onnx file"
        }
    }
}
