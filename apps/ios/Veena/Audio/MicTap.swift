import AVFoundation

/// The microphone tap shared by the app's recorder and the keyboard session:
/// hands over input audio as 16 kHz mono floats, the engine's format.
enum MicTap {

    static let format = AVAudioFormat(
        commonFormat: .pcmFormatFloat32,
        sampleRate: 16_000,
        channels: 1,
        interleaved: false
    )!

    /// Installs a tap on `input`'s bus 0. Buffers are converted only while
    /// `isActive` holds; `onAudio` runs on the audio thread.
    static func install(
        on input: AVAudioInputNode,
        isActive: @escaping () -> Bool = { true },
        onAudio: @escaping (AVAudioPCMBuffer) -> Void
    ) throws {
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            throw RecorderError.inputUnavailable
        }
        guard let converter = AVAudioConverter(from: inputFormat, to: format) else {
            throw RecorderError.conversionUnavailable
        }

        input.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { buffer, _ in
            guard isActive() else { return }
            let ratio = format.sampleRate / inputFormat.sampleRate
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
            guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }

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
            // `.inputRanDry` means the output had room to spare, not that it
            // is empty: it still holds this buffer's audio.
            if status == .haveData || status == .inputRanDry, out.frameLength > 0 {
                onAudio(out)
            }
        }
    }
}
