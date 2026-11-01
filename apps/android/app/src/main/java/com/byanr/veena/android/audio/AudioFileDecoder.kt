package com.byanr.veena.android.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.floor

/**
 * Decodes any platform-supported audio container (m4a, mp3, ogg, …) into
 * 16 kHz mono floats for the engine. Ported from the previous Expo module.
 *
 * Each decoded block is downmixed and resampled as it arrives, so only the
 * 16 kHz output is held in memory. Buffering the full decoded PCM first
 * needed ~230 MB of heap for an 8-minute stereo 48 kHz MP3, which is about
 * the per-app limit on 4 GB phones.
 */
object AudioFileDecoder {

    private const val TARGET_RATE = 16_000

    fun decodeToMono16k(path: String): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot open audio file: ${e.message}")
        }

        try {
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            val trackFormat = format
                ?: throw IllegalArgumentException("No audio track found in file")

            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            extractor.selectTrack(audioTrackIndex)

            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val output = MonoResampler(
                outputRate = TARGET_RATE,
                expectedSamples = (durationUs * TARGET_RATE / 1_000_000L).toInt() + TARGET_RATE,
            )

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            try {
                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuffer = codec.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIndex >= 0) {
                        if (info.size > 0) {
                            // Per-buffer format: sample rate and channel count can
                            // differ from the container's (e.g. HE-AAC).
                            val bufferFormat = codec.getOutputFormat(outIndex)
                            val pcm = codec.getOutputBuffer(outIndex)!!
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                            output.append(
                                pcm,
                                channels = bufferFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                                rate = bufferFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            )
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            val samples = output.finish()
            if (samples.isEmpty()) {
                throw IllegalArgumentException("Decoded audio is empty")
            }
            return Pair(samples, TARGET_RATE)
        } finally {
            extractor.release()
        }
    }
}

/**
 * Incremental downmix plus linear-interpolation resampling: output sample
 * `i` sits at input position `i * inputRate / outputRate`, and is emitted
 * as soon as the input sample after that position arrives.
 *
 * The input rate may change between buffers (HE-AAC can switch from its
 * 22.05 kHz core to 44.1 kHz once SBR kicks in). The first sample at the new
 * rate comes one old period after the last, so it is reached at the old
 * rate; it then starts a new run counted from 0 in the new rate's samples.
 */
internal class MonoResampler(private val outputRate: Int, expectedSamples: Int) {
    private var out = FloatArray(expectedSamples.coerceAtLeast(outputRate))
    private var outSize = 0
    private var inputRate = 0
    /** Samples in the current run of [inputRate]. */
    private var inputCount = 0L
    /** Position of the run's first output, [runStart], in the run's samples. */
    private var phase = 0.0
    private var runStart = 0
    private var previous = 0f

    fun append(pcm: ShortBuffer, channels: Int, rate: Int) {
        var frames = pcm.remaining() / channels
        if (frames == 0) return
        if (inputCount == 0L) {
            inputRate = rate
        } else if (rate != inputRate) {
            push(downmix(pcm, channels))
            frames--
            phase = (sourcePosition() - (inputCount - 1)) * rate / inputRate
            runStart = outSize
            inputCount = 1
            inputRate = rate
        }
        repeat(frames) { push(downmix(pcm, channels)) }
    }

    private fun downmix(pcm: ShortBuffer, channels: Int): Float {
        var sum = 0f
        repeat(channels) { sum += pcm.get() / 32768f }
        return sum / channels
    }

    /**
     * Returns `inputs * outputRate / inputRate` samples (rounded down), draining
     * the tail by interpolating towards silence past the last input sample.
     */
    fun finish(): FloatArray {
        if (inputCount == 0L) return FloatArray(0)
        val total = runStart + floor((inputCount - phase) * outputRate / inputRate).toInt()
        while (outSize < total) {
            val frac = (sourcePosition() - (inputCount - 1)).toFloat()
            emit(previous - frac * previous)
        }
        return out.copyOf(total)
    }

    private fun push(sample: Float) {
        // Output positions in [inputCount - 1, inputCount) interpolate
        // between the previous sample and this one.
        while (sourcePosition() < inputCount) {
            val frac = (sourcePosition() - (inputCount - 1)).toFloat()
            emit(previous + frac * (sample - previous))
        }
        previous = sample
        inputCount++
    }

    private fun sourcePosition(): Double =
        phase + (outSize - runStart).toDouble() * inputRate / outputRate

    private fun emit(value: Float) {
        if (outSize == out.size) out = out.copyOf(out.size + out.size / 2)
        out[outSize++] = value
    }
}
