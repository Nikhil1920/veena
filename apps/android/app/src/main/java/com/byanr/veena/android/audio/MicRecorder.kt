package com.byanr.veena.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.sqrt

data class Recording(
    val samples: ShortArray,
    val sampleRate: Int,
    val file: File,
    val durationMs: Long,
    val rms: Float,
)

/**
 * Captures 16 kHz mono PCM from the microphone into memory while appending
 * to a WAV file on disk, so dictation sessions keep their audio for history.
 */
class MicRecorder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var record: AudioRecord? = null
    private var readJob: CompletableJob? = null
    private var wavFile: File? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    private val samples = ArrayList<Short>(16_000 * 10)
    private var startedAt = 0L

    @SuppressLint("MissingPermission") // callers must hold RECORD_AUDIO
    suspend fun start(outputFile: File) = withContext(Dispatchers.IO) {
        check(!isRecording) { "Already recording" }

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

        samples.clear()
        startedAt = System.currentTimeMillis()
        wavFile = outputFile
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { WavWriter.writeHeader(it, 0, SAMPLE_RATE) }

        record = audioRecord
        audioRecord.startRecording()
        isRecording = true

        readJob = Job()
        val job = readJob!!
        scope.launch {
            try {
                FileOutputStream(outputFile, true).use { wavStream ->
                    val buffer = ShortArray(2048)
                    val bytes = ByteArray(buffer.size * 2)
                    while (!job.isCancelled) {
                        val n = audioRecord.read(buffer, 0, buffer.size)
                        if (n <= 0) {
                            delay(20)
                            continue
                        }
                        synchronized(samples) {
                            for (i in 0 until n) samples.add(buffer[i])
                        }
                        for (i in 0 until n) {
                            val v = buffer[i].toInt()
                            bytes[i * 2] = (v and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                        wavStream.write(bytes, 0, n * 2)
                    }
                }
            } catch (_: IOException) {
                // Disk issues shouldn't crash the recording loop; the in-memory
                // copy still feeds transcription.
            } finally {
                job.complete()
            }
        }
    }

    suspend fun stop(): Recording = withContext(Dispatchers.IO) {
        val audioRecord =
            record ?: error("Recording not started")
        val job = readJob

        isRecording = false
        record = null
        readJob = null

        audioRecord.stop()
        audioRecord.release()

        job?.cancel()
        job?.join()

        val snapshot = synchronized(samples) { samples.toShortArray() }
        var sum = 0.0
        for (s in snapshot) {
            val v = s / 32768.0
            sum += v * v
        }
        val rms = if (snapshot.isNotEmpty()) sqrt(sum / snapshot.size).toFloat() else 0f

        val file = wavFile
        wavFile = null
        if (file != null && file.exists()) {
            try {
                WavWriter.patchSizes(file, snapshot.size * 2L)
            } catch (_: IOException) {
                // Header patch is best-effort; data is already on disk.
            }
        }

        Recording(
            samples = snapshot,
            sampleRate = SAMPLE_RATE,
            file = file ?: File("/dev/null"),
            durationMs = System.currentTimeMillis() - startedAt,
            rms = rms,
        )
    }

    fun shutdown() {
        scope.cancel()
        record?.release()
        record = null
        isRecording = false
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
