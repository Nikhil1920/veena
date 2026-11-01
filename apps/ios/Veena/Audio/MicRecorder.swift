import AVFoundation

struct Recording {
    let samples: [Float]
    let sampleRate: Double
    let fileURL: URL
    let duration: TimeInterval
    let rms: Float
}

/// Captures microphone audio, converting to 16 kHz mono floats in memory
/// while writing the same stream to a WAV file for history playback.
@Observable
final class MicRecorder {

    private(set) var isRecording = false

    private let engine = AVAudioEngine()
    private var samples: [Float] = []
    private var wavFile: AVAudioFile?
    private var outputURL: URL?
    private var startedAt = Date()

    func start(outputURL: URL) throws {
        guard !isRecording else { return }

        samples.removeAll(keepingCapacity: true)
        startedAt = Date()
        self.outputURL = outputURL
        try? FileManager.default.removeItem(at: outputURL)
        wavFile = try AVAudioFile(
            forWriting: outputURL,
            settings: MicTap.format.settings,
            commonFormat: .pcmFormatFloat32,
            interleaved: false
        )

        do {
            try MicTap.install(on: engine.inputNode) { [weak self] buffer in
                self?.append(buffer)
            }
        } catch {
            wavFile = nil
            throw error
        }

        engine.prepare()
        try engine.start()
        isRecording = true
    }

    func stop() -> Recording? {
        guard isRecording else { return nil }
        isRecording = false

        engine.inputNode.removeTap(onBus: 0)
        engine.stop()

        let snapshot = samples
        let sum = snapshot.reduce(Float(0)) { $0 + $1 * $1 }
        let rms = snapshot.isEmpty ? 0 : (sum / Float(snapshot.count)).squareRoot()
        let duration = Date().timeIntervalSince(startedAt)

        wavFile = nil
        let url = outputURL
        outputURL = nil

        return Recording(
            samples: snapshot,
            sampleRate: 16_000,
            fileURL: url ?? URL(fileURLWithPath: "/dev/null"),
            duration: duration,
            rms: rms
        )
    }

    private func append(_ buffer: AVAudioPCMBuffer) {
        guard let channel = buffer.floatChannelData?[0] else { return }
        let count = Int(buffer.frameLength)
        let slice = UnsafeBufferPointer(start: channel, count: count)
        samples.append(contentsOf: slice)
        try? wavFile?.write(from: buffer)
    }
}

enum RecorderError: LocalizedError {
    case inputUnavailable
    case conversionUnavailable

    var errorDescription: String? {
        switch self {
        case .inputUnavailable: return "Microphone unavailable"
        case .conversionUnavailable: return "Audio format conversion unavailable"
        }
    }
}
