import AVFoundation

/// The keyboard session's microphone. The audio engine runs for the whole
/// session — a running input is what keeps the app alive in the background,
/// and iOS won't let a background app turn the microphone on later — but
/// samples are kept only between `beginCapture()` and `endCapture()`.
final class KeyboardAudioCapture {

    private let engine = AVAudioEngine()
    private let lock = NSLock()
    private var samples: [Float] = []
    private var capturing = false
    private var recentLevel: Float = 0
    private(set) var isRunning = false

    /// Called on the main queue when the engine stops on its own (route or
    /// configuration change) and could not be restarted.
    var onStopped: (() -> Void)?

    private var configurationObserver: NSObjectProtocol?

    init() {
        configurationObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: .main
        ) { [weak self] _ in
            self?.restartAfterConfigurationChange()
        }
    }

    deinit {
        if let configurationObserver {
            NotificationCenter.default.removeObserver(configurationObserver)
        }
    }

    func start() throws {
        guard !isRunning else { return }
        try installTap()
        engine.prepare()
        do {
            try engine.start()
        } catch {
            engine.inputNode.removeTap(onBus: 0)
            throw error
        }
        isRunning = true
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        cancelCapture()
    }

    func beginCapture() {
        lock.lock()
        samples.removeAll(keepingCapacity: true)
        recentLevel = 0
        capturing = true
        lock.unlock()
    }

    /// Stops keeping samples and returns what was captured since `beginCapture()`.
    func endCapture() -> [Float] {
        lock.lock()
        defer { lock.unlock() }
        capturing = false
        recentLevel = 0
        let captured = samples
        samples.removeAll()
        return captured
    }

    func cancelCapture() {
        _ = endCapture()
    }

    /// RMS of the latest buffer while capturing, 0 otherwise.
    var level: Float {
        lock.lock()
        defer { lock.unlock() }
        return recentLevel
    }

    var capturedDuration: TimeInterval {
        lock.lock()
        defer { lock.unlock() }
        return Double(samples.count) / 16_000
    }

    private func installTap() throws {
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            throw RecorderError.inputUnavailable
        }
        guard let target = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: 16_000,
            channels: 1,
            interleaved: false
        ) else {
            throw RecorderError.inputUnavailable
        }
        guard let converter = AVAudioConverter(from: inputFormat, to: target) else {
            throw RecorderError.conversionUnavailable
        }

        input.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, _ in
            guard let self, self.isCapturing else { return }
            let ratio = 16_000 / inputFormat.sampleRate
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
            guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }

            var fedInput = false
            var conversionError: NSError?
            let status = converter.convert(to: out, error: &conversionError) { _, outStatus in
                if fedInput {
                    outStatus.pointee = .noDataNow
                    return nil
                }
                fedInput = true
                outStatus.pointee = .haveData
                return buffer
            }
            if status == .haveData {
                self.append(out)
            }
        }
    }

    private var isCapturing: Bool {
        lock.lock()
        defer { lock.unlock() }
        return capturing
    }

    private func append(_ buffer: AVAudioPCMBuffer) {
        guard let channel = buffer.floatChannelData?[0] else { return }
        let count = Int(buffer.frameLength)
        guard count > 0 else { return }
        let slice = UnsafeBufferPointer(start: channel, count: count)
        let sum = slice.reduce(Float(0)) { $0 + $1 * $1 }
        lock.lock()
        if capturing {
            samples.append(contentsOf: slice)
            recentLevel = (sum / Float(count)).squareRoot()
        }
        lock.unlock()
    }

    /// A new route (headphones, Bluetooth) stops the engine and may change the
    /// input format: reinstall the tap for the new format and carry on.
    private func restartAfterConfigurationChange() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        do {
            try installTap()
            engine.prepare()
            try engine.start()
        } catch {
            engine.inputNode.removeTap(onBus: 0)
            isRunning = false
            cancelCapture()
            onStopped?()
        }
    }
}
