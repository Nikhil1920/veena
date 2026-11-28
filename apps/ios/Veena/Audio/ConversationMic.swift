import AVFoundation

/// The Conversation screen's microphone. Unlike `MicRecorder` it keeps
/// nothing: the tap queues 16 kHz mono audio until the listening loop
/// drains it, about every 100 ms. The queue is capped, so a stalled engine
/// costs at most `maxQueuedSamples` of memory however long it stalls.
final class ConversationMic {

    private let engine = AVAudioEngine()
    private let lock = NSLock()
    // Guarded by `lock`.
    private var queue: [Float] = []
    private var recentLevel: Float = 0
    private(set) var isRunning = false
    #if DEBUG
    private let testAudio: DebugMicStream?
    #endif

    /// Called on the main queue when a route change stopped the engine and
    /// it could not be restarted.
    var onStopped: (() -> Void)?
    private var configurationObserver: NSObjectProtocol?

    /// 30 s; older audio is dropped first.
    private static let maxQueuedSamples = 30 * 16_000

    #if DEBUG
    /// - Parameter testAudio: heard instead of the microphone (simulators
    ///   have no voice); see `DebugMicStream`.
    init(testAudio: DebugMicStream?) {
        self.testAudio = testAudio
        observeConfigurationChanges()
    }
    #else
    init() {
        observeConfigurationChanges()
    }
    #endif

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
        #if DEBUG
        testAudio?.begin()
        #endif
    }

    /// Stops listening. What was queued stays for one last `drain`.
    func stop() {
        guard isRunning else { return }
        isRunning = false
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        lock.withLock { recentLevel = 0 }
    }

    /// The audio heard since the last call.
    func drain() -> [Float] {
        #if DEBUG
        if let testAudio {
            lock.withLock { queue.removeAll(keepingCapacity: true) }
            let audio = isRunning ? testAudio.read() : []
            lock.withLock { recentLevel = isRunning ? Self.rms(audio) : 0 }
            return audio
        }
        #endif
        return lock.withLock {
            let audio = queue
            queue.removeAll(keepingCapacity: true)
            return audio
        }
    }

    /// RMS of the latest audio, 0 when stopped.
    var level: Float {
        lock.withLock { recentLevel }
    }

    private func installTap() throws {
        try MicTap.install(on: engine.inputNode) { [weak self] buffer in
            self?.append(buffer)
        }
    }

    private func append(_ buffer: AVAudioPCMBuffer) {
        guard let channel = buffer.floatChannelData?[0] else { return }
        let count = Int(buffer.frameLength)
        guard count > 0 else { return }
        let slice = UnsafeBufferPointer(start: channel, count: count)
        let level = Self.rms(slice)
        lock.withLock {
            queue.append(contentsOf: slice)
            let excess = queue.count - Self.maxQueuedSamples
            if excess > 0 { queue.removeFirst(excess) }
            #if DEBUG
            if testAudio != nil { return }
            #endif
            recentLevel = level
        }
    }

    private static func rms<C: Collection>(_ samples: C) -> Float where C.Element == Float {
        guard !samples.isEmpty else { return 0 }
        let sum = samples.reduce(Float(0)) { $0 + $1 * $1 }
        return (sum / Float(samples.count)).squareRoot()
    }

    private func observeConfigurationChanges() {
        configurationObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: .main
        ) { [weak self] _ in
            self?.restartAfterConfigurationChange()
        }
    }

    /// A new route (headphones, Bluetooth) stops the engine and may change
    /// the input format: reinstall the tap for the new format and carry on.
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
            onStopped?()
        }
    }
}
