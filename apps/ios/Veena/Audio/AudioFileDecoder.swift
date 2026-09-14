import AVFoundation

/// Decodes any AVFoundation-supported audio container (m4a, mp3, wav, …)
/// into 16 kHz mono floats for the engine.
enum AudioFileDecoder {

    static func decodeToMono16k(url: URL) throws -> ([Float], UInt32) {
        let source = try AVAudioFile(forReading: url)
        let sourceFormat = source.processingFormat
        guard let target = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: 16_000,
            channels: 1,
            interleaved: false
        ), let converter = AVAudioConverter(from: sourceFormat, to: target) else {
            throw DecoderError.unavailable
        }

        var samples: [Float] = []
        let chunkFrames: AVAudioFrameCount = 32_768

        while true {
            guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: chunkFrames) else {
                throw DecoderError.unavailable
            }
            var reachedEnd = false
            var conversionError: NSError?
            let status = converter.convert(to: out, error: &conversionError) { _, outStatus in
                if reachedEnd {
                    outStatus.pointee = .endOfStream
                    return nil
                }
                guard let chunk = try? AVAudioPCMBuffer(
                    pcmFormat: sourceFormat,
                    frameCapacity: chunkFrames
                ) else {
                    outStatus.pointee = .endOfStream
                    return nil
                }
                do {
                    try source.read(into: chunk, frameCount: chunkFrames)
                } catch {
                    outStatus.pointee = .endOfStream
                    return nil
                }
                if chunk.frameLength == 0 {
                    reachedEnd = true
                    outStatus.pointee = .endOfStream
                    return nil
                }
                outStatus.pointee = .haveData
                return chunk
            }

            switch status {
            case .haveData, .inputRanDry:
                if let channel = out.floatChannelData?[0] {
                    samples.append(
                        contentsOf: UnsafeBufferPointer(start: channel, count: Int(out.frameLength))
                    )
                }
                if status == .inputRanDry && reachedEnd { /* loop once more for EOS */ }
            case .endOfStream:
                if let channel = out.floatChannelData?[0] {
                    samples.append(
                        contentsOf: UnsafeBufferPointer(start: channel, count: Int(out.frameLength))
                    )
                }
                return (samples, 16_000)
            @unknown default:
                return (samples, 16_000)
            }
            if reachedEnd && status == .inputRanDry {
                // Converter drained; next pass returns endOfStream.
                continue
            }
        }
    }

    enum DecoderError: LocalizedError {
        case unavailable
        var errorDescription: String? { "Audio decoding unavailable" }
    }
}
