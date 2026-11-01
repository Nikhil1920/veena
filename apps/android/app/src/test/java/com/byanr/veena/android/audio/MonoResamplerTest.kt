package com.byanr.veena.android.audio

import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MonoResamplerTest {

    /** The previous whole-buffer decoder: downmix everything, then interpolate. */
    private fun batch(pcm: ShortArray, channels: Int, rate: Int): FloatArray {
        val mono = FloatArray(pcm.size / channels) { i ->
            var sum = 0f
            for (ch in 0 until channels) sum += pcm[i * channels + ch] / 32768f
            sum / channels
        }
        if (rate == 16_000) return mono
        val ratio = 16_000.0 / rate
        return FloatArray((mono.size * ratio).toInt()) { i ->
            val src = i / ratio
            val i0 = src.toInt()
            val frac = (src - i0).toFloat()
            val s0 = if (i0 < mono.size) mono[i0] else 0f
            val s1 = if (i0 + 1 < mono.size) mono[i0 + 1] else 0f
            s0 + frac * (s1 - s0)
        }
    }

    /** Feeds the codec-style blocks of random sizes, like MediaCodec output. */
    private fun streamed(pcm: ShortArray, channels: Int, rate: Int): FloatArray {
        val resampler = MonoResampler(outputRate = 16_000, expectedSamples = 0)
        val random = Random(rate + channels)
        var frame = 0
        val frames = pcm.size / channels
        while (frame < frames) {
            val count = minOf(random.nextInt(1, 2_000), frames - frame)
            resampler.append(
                ShortBuffer.wrap(pcm, frame * channels, count * channels).slice(),
                channels,
                rate,
            )
            frame += count
        }
        return resampler.finish()
    }

    private fun signal(frames: Int, channels: Int): ShortArray {
        val random = Random(frames)
        return ShortArray(frames * channels) { random.nextInt(-32768, 32768).toShort() }
    }

    @Test
    fun streamingMatchesBatchForCommonRates() {
        for (rate in listOf(8_000, 16_000, 22_050, 44_100, 48_000)) {
            for (channels in 1..2) {
                val pcm = signal(frames = rate * 3 + 7, channels = channels)
                val expected = batch(pcm, channels, rate)
                val actual = streamed(pcm, channels, rate)
                assertEquals("length at $rate Hz x$channels", expected.size, actual.size)
                assertArrayEquals("samples at $rate Hz x$channels", expected, actual, 1e-6f)
            }
        }
    }

    @Test
    fun followsARateChangeMidStream() {
        // HE-AAC style: 1 s at the 22.05 kHz core rate, then 2 s at 44.1 kHz.
        val hz = 440.0
        fun tone(rate: Int, seconds: Int, startTime: Double) =
            ShortArray(rate * seconds) { i ->
                (sin(2 * PI * hz * (startTime + i.toDouble() / rate)) * 16_000).toInt().toShort()
            }
        val resampler = MonoResampler(outputRate = 16_000, expectedSamples = 0)
        resampler.append(ShortBuffer.wrap(tone(22_050, 1, 0.0)), channels = 1, rate = 22_050)
        resampler.append(ShortBuffer.wrap(tone(44_100, 2, 1.0)), channels = 1, rate = 44_100)
        val out = resampler.finish()

        assertEquals(48_000.0, out.size.toDouble(), 2.0)
        // Every output lands on the tone at its own time, before and after the switch.
        for (i in 0 until out.size - 2) {
            val expected = sin(2 * PI * hz * i / 16_000) * 16_000 / 32_768
            assertEquals("sample $i", expected, out[i].toDouble(), 0.01)
        }
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertEquals(0, MonoResampler(outputRate = 16_000, expectedSamples = 0).finish().size)
    }
}
