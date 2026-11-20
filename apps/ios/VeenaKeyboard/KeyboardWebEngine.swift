import UIKit
import WebKit
import os

/// The hidden web view behind in-place dictation (`Web/`). Its page records
/// the microphone and runs Veena's model; this class feeds it the model's
/// files and relays its events.
///
/// Two limits of a keyboard make the web view the place for both jobs. iOS
/// refuses a keyboard extension the microphone, but lets a web view's media
/// process record under the containing app's permission. And iOS ends a
/// keyboard that grows past a few dozen megabytes, while the web view's
/// content process is a separate one with room for the model.
final class KeyboardWebEngine: NSObject {

    enum Event {
        case started
        case level(Float)
        /// Recording ended, with this many seconds of audio, and transcription began.
        case stopped(seconds: Double)
        case result(String)
        case failed(microphone: Bool, message: String)
    }

    static let scheme = "veena-keyboard"
    private static let origin = "\(scheme)://dictation/"
    private static let logger = Logger(subsystem: "com.byanr.veena", category: "keyboard-local")

    /// Whether this build carries the web runtime (see `scripts/fetch-keyboard-runtime.sh`).
    static let isBundled: Bool = {
        guard let web = KeyboardWebFiles.bundleRoot else { return false }
        return FileManager.default.fileExists(
            atPath: web.appendingPathComponent("ort/ort-wasm-simd-threaded.wasm").path
        )
    }()

    /// Events of the job passed to `start`; earlier jobs' are dropped.
    var onEvent: ((Event) -> Void)?

    let model: SharedModelStore.Files
    private let webView: WKWebView
    private let files: KeyboardWebFiles
    private var pageReady = false
    private var pendingStart: String?
    private var job: String?

    init(host: UIView, model: SharedModelStore.Files) {
        self.model = model
        files = KeyboardWebFiles(model: model)
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.setURLSchemeHandler(files, forURLScheme: Self.scheme)
        webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 2, height: 2), configuration: configuration)
        super.init()
        configuration.userContentController.add(WeakMessageHandler(self), name: "veena")
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isUserInteractionEnabled = false
        // In the view tree but out of sight: a detached web view may not record.
        webView.alpha = 0.02
        webView.accessibilityElementsHidden = true
        host.addSubview(webView)
        webView.load(URLRequest(url: URL(string: Self.origin + "index.html")!))
    }

    /// Starts recording; the model loads meanwhile if it hasn't yet.
    func start(job: String, languageCode: String, testAudio: Bool) {
        self.job = job
        var config: [String: Any] = [
            "job": job,
            "language": languageCode,
            "model": [
                "key": model.modelID,
                "onnx": ["url": "model/onnx", "size": model.onnxBytes],
                "vocab": "model/vocab",
                "config": "model/config",
                "masks": model.masks == nil ? NSNull() : "model/masks",
            ] as [String: Any],
        ]
        if testAudio {
            config["testAudio"] = "test-audio"
        }
        guard let data = try? JSONSerialization.data(withJSONObject: config),
              let json = String(data: data, encoding: .utf8) else { return }
        let script = "veenaStart(\(json))"
        if pageReady {
            webView.evaluateJavaScript(script)
        } else {
            pendingStart = script
        }
    }

    func stop() {
        guard pendingStart == nil else {
            // Stopped before the page loaded: nothing was recorded.
            pendingStart = nil
            job = nil
            onEvent?(.result(""))
            return
        }
        webView.evaluateJavaScript("veenaStop()")
    }

    func cancel() {
        job = nil
        pendingStart = nil
        if pageReady {
            webView.evaluateJavaScript("veenaCancel()")
        }
    }

    /// Ends the web view's processes, which gives the model's memory back.
    func tearDown() {
        onEvent = nil
        job = nil
        pendingStart = nil
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "veena")
        webView.removeFromSuperview()
        files.invalidate()
    }

    private func handle(_ body: [String: Any]) {
        guard let type = body["type"] as? String else { return }
        switch type {
        case "ready":
            pageReady = true
            if let pendingStart {
                self.pendingStart = nil
                webView.evaluateJavaScript(pendingStart)
            }
            return
        case "model":
            Self.logger.log("model loaded in \(body["ms"] as? Int ?? -1) ms")
            return
        default:
            break
        }
        guard let job, body["job"] as? String == job else { return }
        switch type {
        case "started":
            Self.logger.log("recording from \((body["device"] as? String) ?? "?", privacy: .public) at \(body["rate"] as? Double ?? 0) Hz")
            onEvent?(.started)
        case "level":
            onEvent?(.level(Float((body["rms"] as? Double) ?? 0)))
        case "stopped":
            let seconds = (body["seconds"] as? Double) ?? 0
            Self.logger.log("recorded \(seconds) s")
            onEvent?(.stopped(seconds: seconds))
        case "result":
            Self.logger.log("transcribed \(body["seconds"] as? Double ?? 0) s in \(body["ms"] as? Int ?? 0) ms")
            self.job = nil
            onEvent?(.result((body["text"] as? String) ?? ""))
        case "failed":
            let stage = (body["stage"] as? String) ?? ""
            let name = (body["name"] as? String) ?? ""
            let message = (body["message"] as? String) ?? ""
            Self.logger.error("\(stage, privacy: .public) failed: \(name, privacy: .public) \(message, privacy: .public)")
            self.job = nil
            onEvent?(.failed(microphone: stage == "microphone", message: message))
        default:
            break
        }
    }
}

extension KeyboardWebEngine: WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {

    func webView(
        _ webView: WKWebView,
        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
        initiatedByFrame frame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: @escaping @MainActor (WKPermissionDecision) -> Void
    ) {
        // The page is ours and Veena already holds the system's permission.
        decisionHandler(type == .microphone && origin.protocol == Self.scheme ? .grant : .deny)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        pageFailed(error.localizedDescription)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        pageFailed(error.localizedDescription)
    }

    /// iOS ended the web view's content process, most likely for memory.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        pageFailed("The speech engine was stopped by iOS")
    }

    private func pageFailed(_ message: String) {
        Self.logger.error("web view failed: \(message, privacy: .public)")
        pageReady = false
        pendingStart = nil
        job = nil
        onEvent?(.failed(microphone: false, message: message))
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any] else { return }
        handle(body)
    }
}

private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?

    init(_ target: WKScriptMessageHandler) {
        self.target = target
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(controller, didReceive: message)
    }
}

/// Serves the page its scripts (from the keyboard's bundle) and the model
/// (from the App Group). Everything goes out in small pieces, one at a time,
/// so no more than a piece of the model is ever in the keyboard's memory.
private final class KeyboardWebFiles: NSObject, WKURLSchemeHandler {

    static let bundleRoot = Bundle.main.url(forResource: "Web", withExtension: nil)

    private static let pieceBytes = 1024 * 1024
    private let model: SharedModelStore.Files
    private let queue = DispatchQueue(label: "com.byanr.veena.keyboard.web-files", qos: .userInitiated)
    /// Tasks still wanted by the web view; main thread only.
    private var live = Set<ObjectIdentifier>()

    init(model: SharedModelStore.Files) {
        self.model = model
    }

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url, let (file, type) = resolve(url) else {
            task.didFailWithError(URLError(.fileDoesNotExist))
            return
        }
        let id = ObjectIdentifier(task)
        live.insert(id)
        let range = Self.range(in: url)
        queue.async { [weak self] in
            guard let handle = try? FileHandle(forReadingFrom: file),
                  let size = try? handle.seekToEnd() else {
                DispatchQueue.main.async {
                    guard let self, self.live.remove(id) != nil else { return }
                    task.didFailWithError(URLError(.fileDoesNotExist))
                }
                return
            }
            let start = min(UInt64(range?.lowerBound ?? 0), size)
            let end = min(UInt64(range?.upperBound ?? Int(size)), size)
            let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: [
                "Content-Type": type,
                "Content-Length": String(end - start),
                "Cache-Control": "no-store",
            ])!
            var sentResponse = false
            var offset = start
            var wanted = true
            while wanted {
                // The pool frees each piece as it goes; without it they pile
                // up until the whole file has passed through.
                autoreleasepool {
                    try? handle.seek(toOffset: offset)
                    let count = Int(min(UInt64(Self.pieceBytes), end - offset))
                    let piece = count > 0 ? ((try? handle.read(upToCount: count)) ?? Data()) : Data()
                    offset += UInt64(piece.count)
                    let last = offset >= end || piece.isEmpty
                    wanted = false
                    // One piece at a time: the next is read once this one is handed over.
                    DispatchQueue.main.sync {
                        guard let self, self.live.contains(id) else { return }
                        if !sentResponse {
                            task.didReceive(response)
                        }
                        if !piece.isEmpty {
                            task.didReceive(piece)
                        }
                        if last {
                            task.didFinish()
                            self.live.remove(id)
                        } else {
                            wanted = true
                        }
                    }
                    sentResponse = true
                }
            }
            try? handle.close()
        }
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {
        live.remove(ObjectIdentifier(task))
    }

    /// The web view is gone: its tasks must not be answered any more.
    func invalidate() {
        live.removeAll()
    }

    /// The file behind a URL and its content type.
    private func resolve(_ url: URL) -> (URL, String)? {
        let path = url.path.split(separator: "/").map(String.init)
        guard !path.isEmpty, !path.contains(".."), !path.contains(where: { $0.hasPrefix(".") }) else { return nil }
        if path[0] == "model" {
            guard path.count == 2 else { return nil }
            switch path[1] {
            case "onnx": return (model.onnx, "application/octet-stream")
            case "vocab": return (model.vocab, "text/plain; charset=utf-8")
            case "config": return (model.decoderConfig, "application/json")
            case "masks": return model.masks.map { ($0, "application/json") }
            default: return nil
            }
        }
        #if DEBUG
        if path == ["test-audio"] {
            return KeyboardLocalDictation.testAudio.map { ($0, "audio/wav") }
        }
        #endif
        guard let root = Self.bundleRoot else { return nil }
        let file = path.reduce(root) { $0.appendingPathComponent($1) }
        let type: String
        switch file.pathExtension {
        case "html": type = "text/html; charset=utf-8"
        case "js", "mjs": type = "text/javascript; charset=utf-8"
        case "wasm": type = "application/wasm"
        default: return nil
        }
        return (file, type)
    }

    /// `?start=&end=`: the byte range the engine asked for.
    private static func range(in url: URL) -> Range<Int>? {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ name: String) -> Int? {
            items.first { $0.name == name }?.value.flatMap(Int.init)
        }
        guard let start = value("start"), let end = value("end"), start >= 0, end >= start else { return nil }
        return start..<end
    }
}
