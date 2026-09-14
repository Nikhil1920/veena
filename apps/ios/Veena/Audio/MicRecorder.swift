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

        samples.removeAll(keepingCapacity: true)
        startedAt = Date()
        self.outputURL = outputURL
        try? FileManager.default.removeItem(at: outputURL)
        wavFile = try AVAudioFile(
            forWriting: outputURL,
            settings: target.settings,
            commonFormat: .pcmFormatFloat32,
            interleaved: false
        )

        let captureFormat = inputFormat
        input.installTap(onBus: 0, bufferSize: 4096, format: captureFormat) { [weak self] buffer, _ in
            guard let self else { return }
            let ratio = 16_000 / captureFormat.sampleRate
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
