import AVFoundation
import Observation

/// Thin AVAudioPlayer wrapper exposing the currently playing file.
@Observable
final class AudioPlayer {
    private(set) var playingPath: String?

    private var player: AVAudioPlayer?

    func toggle(path: String) {
        if player != nil, playingPath == path {
            if player!.isPlaying {
                player!.pause()
                playingPath = nil
            } else {
                player!.play()
                playingPath = path
            }
            return
        }
        stop()
        do {
            let p = try AVAudioPlayer(contentsOf: URL(fileURLWithPath: path))
            p.delegate = PlayerDelegate.shared
            PlayerDelegate.shared.current = p
            PlayerDelegate.shared.onFinish = { [weak self] in
                self?.playingPath = nil
                self?.player = nil
            }
            p.play()
            player = p
            playingPath = path
        } catch {
            playingPath = nil
        }
    }

    func stop() {
        player?.stop()
        player = nil
        playingPath = nil
        PlayerDelegate.shared.current = nil
    }

    func release() {
        stop()
    }
}

private final class PlayerDelegate: NSObject, AVAudioPlayerDelegate {
    static let shared = PlayerDelegate()
    weak var current: AVAudioPlayer?
    var onFinish: (() -> Void)?

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        if current === player {
            current = nil
            onFinish?()
        }
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        if current === player {
            current = nil
            onFinish?()
        }
    }
}
