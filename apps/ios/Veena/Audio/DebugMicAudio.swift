#if DEBUG
import Foundation

/// Test hook: `simctl launch ... -micTestAudio <name>` makes dictations in the
/// app hear `Documents/<name>` instead of the microphone (simulators have no
/// voice). It plays from the moment it is loaded, at the pace a microphone
/// would deliver it.
final class DebugMicAudio {
    /// The whole file as 16 kHz mono samples.
    let samples: [Float]
    private let startedAt = Date()

    private init(samples: [Float]) {
        self.samples = samples
    }

    /// Nil unless the app was launched with a test file that exists.
    static func load() -> DebugMicAudio? {
        // UserDefaults reads `-micTestAudio <name>` from the launch arguments.
        guard let name = UserDefaults.standard.string(forKey: "micTestAudio") else { return nil }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        guard let (samples, _) = try? AudioFileDecoder.decodeToMono16k(url: docs.appendingPathComponent(name)) else {
            return nil
        }
        return DebugMicAudio(samples: samples)
    }

    /// The samples "recorded" so far from index `start` on.
    func samples(from start: Int) -> [Float] {
        let heard = min(Int(Date().timeIntervalSince(startedAt) * 16_000), samples.count)
        return Array(samples[min(start, heard)..<heard])
    }
}

/// The same test hook for the Conversation screen, which listens on and off
/// for as long as it is open: the file plays at the pace a microphone would
/// deliver it while listening (a pause pauses it too), then silence.
final class DebugMicStream {
    private let samples: [Float]
    private let lock = NSLock()
    // Guarded by `lock`.
    private var position = 0
    private var listeningSince = Date()
    private var deliveredSinceStart = 0

    private init(samples: [Float]) {
        self.samples = samples
    }

    /// Nil unless the app was launched with a test file that exists.
    static func load() -> DebugMicStream? {
        DebugMicAudio.load().map { DebugMicStream(samples: $0.samples) }
    }

    /// Listening (re)started: the file carries on from where it stopped.
    func begin() {
        lock.withLock {
            listeningSince = Date()
            deliveredSinceStart = 0
        }
    }

    /// The audio "heard" since the last call: the file, then silence.
    func read() -> [Float] {
        lock.withLock {
            let due = Int(Date().timeIntervalSince(listeningSince) * 16_000) - deliveredSinceStart
            guard due > 0 else { return [] }
            deliveredSinceStart += due
            let end = min(position + due, samples.count)
            var audio = Array(samples[position..<end])
            position = end
            audio.append(contentsOf: repeatElement(0, count: due - audio.count))
            return audio
        }
    }
}
#endif
