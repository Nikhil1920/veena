package com.byanr.veena.android.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any platform-supported audio container (m4a, mp3, ogg, …) into
 * 16 kHz mono floats for the engine. Ported from the previous Expo module.
 */
object AudioFileDecoder {

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

            val info = MediaCodec.BufferInfo()
            val pcm = ByteArrayOutputStream()
            var sawInputEos = false
            var sawOutputEos = false
            var outputFormat: MediaFormat? = null

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
                        val outBuffer = codec.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(info.size)
                        outBuffer.get(chunk)
                        outBuffer.clear()
                        pcm.write(chunk)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outputFormat = codec.outputFormat
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            val pcmBytes = pcm.toByteArray()
            if (pcmBytes.isEmpty()) {
                throw IllegalArgumentException("Decoded audio is empty")
            }

            val decodedRate = outputFormat?.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ?: trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = outputFormat?.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                ?: trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val shorts = ByteBuffer.wrap(pcmBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .let { buf -> ShortArray(buf.remaining()).also(buf::get) }

            val mono = if (channelCount > 1) {
                FloatArray(shorts.size / channelCount) { i ->
                    var sum = 0f
                    for (ch in 0 until channelCount) {
                        sum += shorts[i * channelCount + ch] / 32768f
                    }
                    sum / channelCount
                }
            } else {
                FloatArray(shorts.size) { shorts[it] / 32768f }
            }

            val at16k = if (decodedRate != 16_000) resample(mono, decodedRate, 16_000) else mono
            return Pair(at16k, 16_000)
        } finally {
            extractor.release()
        }
    }

    private fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        val ratio = outputRate.toDouble() / inputRate.toDouble()
        val outLength = (input.size * ratio).toInt()
        return FloatArray(outLength) { i ->
            val src = i / ratio
            val i0 = src.toInt()
            val frac = (src - i0).toFloat()
            val s0 = if (i0 < input.size) input[i0] else 0f
            val s1 = if (i0 + 1 < input.size) input[i0 + 1] else 0f
            s0 + frac * (s1 - s0)
        }
    }
}
