package com.byanr.veena.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * The microphone as a stream of 16 kHz mono audio, for listening that goes
 * on for as long as a screen is open. Unlike [MicRecorder] it keeps only the
 * audio not yet taken with [drain], so memory stays flat however long it
 * runs.
 *
 * In debuggable builds a [DebugMicAudio] can stand in for the microphone:
 * its file plays at microphone pace, then silence follows.
 */
class MicStream {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    // Guarded by [lock].
    private val pending = ArrayDeque<FloatArray>()
    private var pendingSamples = 0

    private var record: AudioRecord? = null
    private var readJob: Job? = null

    private var testAudio: FloatArray? = null
    /** Samples of [testAudio] already "heard", silence past its end included. */
    private var testPosition = 0L
    private var testStartedAt = 0L

    /** RMS of the latest audio; 0 when stopped. */
    @Volatile
    var level: Float = 0f
        private set

    val isRunning: Boolean get() = record != null || testStartedAt != 0L

    /**
     * Starts listening. With [test] audio the microphone stays closed and the
     * file carries on from where it last stopped.
     */
    @SuppressLint("MissingPermission") // callers must hold RECORD_AUDIO
    suspend fun start(test: DebugMicAudio?) = withContext(Dispatchers.IO) {
        check(!isRunning) { "Already listening" }
        synchronized(lock) {
            pending.clear()
            pendingSamples = 0
        }
        if (test != null) {
            if (testAudio !== test.samples) {
                testAudio = test.samples
                testPosition = 0
            }
            testStartedAt = SystemClock.elapsedRealtime() - testPosition / SAMPLES_PER_MS
            return@withContext
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minBuffer, 32_768),
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            error("Microphone unavailable")
        }
        audioRecord.startRecording()
        record = audioRecord
        readJob = scope.launch { readLoop(audioRecord) }
    }

    private suspend fun CoroutineScope.readLoop(audioRecord: AudioRecord) {
        val buffer = ShortArray(READ_SAMPLES)
        while (isActive) {
            val n = audioRecord.read(buffer, 0, buffer.size)
            if (n <= 0) {
                delay(20)
                continue
            }
            val chunk = FloatArray(n) { buffer[it] / 32768f }
            level = rms(chunk)
            synchronized(lock) {
                pending.addLast(chunk)
                pendingSamples += n
                // A consumer that fell far behind loses the oldest audio, not memory.
                while (pendingSamples > MAX_PENDING_SAMPLES) {
                    pendingSamples -= pending.removeFirst().size
                }
            }
        }
    }

    /** The audio heard since the last call, as 16 kHz mono floats. */
    fun drain(): FloatArray {
        testAudio?.let { audio ->
            if (testStartedAt == 0L) return FloatArray(0)
            val heard = (SystemClock.elapsedRealtime() - testStartedAt) * SAMPLES_PER_MS
            val out = FloatArray((heard - testPosition).coerceIn(0, MAX_PENDING_SAMPLES.toLong()).toInt()) {
                val index = testPosition + it
                if (index < audio.size) audio[index.toInt()] else 0f
            }
            testPosition = heard
            if (out.isNotEmpty()) level = rms(out)
            return out
        }
        return synchronized(lock) {
            val out = FloatArray(pendingSamples)
            var offset = 0
            for (chunk in pending) {
                chunk.copyInto(out, offset)
                offset += chunk.size
            }
            pending.clear()
            pendingSamples = 0
            out
        }
    }

    /** Stops listening and returns the audio not yet drained. */
    suspend fun stop(): FloatArray = withContext(Dispatchers.IO) {
        val rest: FloatArray
        if (testStartedAt != 0L) {
            rest = drain()
            testStartedAt = 0L
        } else {
            val audioRecord = record ?: return@withContext FloatArray(0)
            record = null
            readJob?.cancelAndJoin()
            readJob = null
            audioRecord.stop()
            audioRecord.release()
            rest = drain()
        }
        level = 0f
        rest
    }

    fun shutdown() {
        scope.cancel()
        record?.release()
        record = null
        testStartedAt = 0L
    }

    private fun rms(audio: FloatArray): Float {
        var sum = 0.0
        for (sample in audio) sum += sample * sample
        return if (audio.isEmpty()) 0f else sqrt(sum / audio.size).toFloat()
    }

    private companion object {
        const val SAMPLE_RATE = MicRecorder.SAMPLE_RATE
        const val SAMPLES_PER_MS = SAMPLE_RATE / 1000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** 100 ms reads. */
        const val READ_SAMPLES = SAMPLE_RATE / 10
        const val MAX_PENDING_SAMPLES = 60 * SAMPLE_RATE
    }
}
