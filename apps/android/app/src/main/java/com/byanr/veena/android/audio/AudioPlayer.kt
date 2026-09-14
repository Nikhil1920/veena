package com.byanr.veena.android.audio

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Thin MediaPlayer wrapper exposing a single playing-file StateFlow. */
class AudioPlayer {

    private val _playingPath = MutableStateFlow<String?>(null)
    val playingPath: StateFlow<String?> = _playingPath

    private var player: MediaPlayer? = null

    fun toggle(path: String) {
        val current = player
        if (current != null && _playingPath.value == path) {
            if (current.isPlaying) {
                current.pause()
                _playingPath.value = null
            } else {
                current.start()
                _playingPath.value = path
            }
            return
        }

        release()
        player = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener {
                _playingPath.value = null
            }
            setOnErrorListener { _, _, _ ->
                _playingPath.value = null
                true
            }
            prepare()
            start()
        }
        _playingPath.value = path
    }

    fun release() {
        player?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        player = null
        _playingPath.value = null
    }
}
