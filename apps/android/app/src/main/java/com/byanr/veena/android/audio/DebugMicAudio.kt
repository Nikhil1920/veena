package com.byanr.veena.android.audio

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import java.io.File

/**
 * Test hook for debuggable builds only: a `files/keyboard-test-audio.wav`
 * (pushed with `adb shell run-as`) stands in for the microphone, since
 * emulators have no voice to dictate with. It plays from the moment it is
 * loaded, at the pace a microphone would deliver it.
 */
class DebugMicAudio private constructor(
    /** The whole file as 16 kHz mono samples. */
    val samples: FloatArray,
) {
    private val startedAt = SystemClock.elapsedRealtime()

    /** The samples "recorded" so far from index [from] on. */
    fun samplesFrom(from: Int): FloatArray {
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val heard = (elapsedMs * SAMPLES_PER_MS).coerceAtMost(samples.size.toLong()).toInt()
        return samples.copyOfRange(minOf(from, heard), heard)
    }

    companion object {
        private const val SAMPLES_PER_MS = MicRecorder.SAMPLE_RATE / 1000

        /** Null in release builds and when no test file has been pushed. */
        fun load(context: Context): DebugMicAudio? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            val file = File(context.filesDir, "keyboard-test-audio.wav")
            if (!file.exists()) return null
            return runCatching { AudioFileDecoder.decodeToMono16k(file.absolutePath).first }
                .getOrNull()
                ?.let(::DebugMicAudio)
        }
    }
}
