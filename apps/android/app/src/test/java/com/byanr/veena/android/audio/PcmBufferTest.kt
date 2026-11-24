package com.byanr.veena.android.audio

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmBufferTest {

    @Test
    fun keepsEverySampleAcrossChunkBoundaries() {
        val buffer = PcmBuffer(chunkSize = 1000)
        val expected = ArrayList<Short>()
        val random = Random(7)
        // Reads of odd sizes, so they straddle chunk edges; one exact fill too.
        for (count in listOf(1, 999, 1000, 2047, 3, 0, 4096, 1)) {
            val read = ShortArray(4096) { random.nextInt().toShort() }
            buffer.append(read, count)
            for (i in 0 until count) expected.add(read[i])
        }
        assertEquals(expected.size, buffer.size)
        assertArrayEquals(expected.toShortArray(), buffer.toShortArray())
        for (i in expected.indices) assertEquals(expected[i], buffer[i])
    }

    @Test
    fun clearStartsAFreshRecording() {
        val buffer = PcmBuffer(chunkSize = 4)
        buffer.append(shortArrayOf(1, 2, 3, 4, 5, 6), 6)
        buffer.clear()
        assertEquals(0, buffer.size)
        assertArrayEquals(ShortArray(0), buffer.toShortArray())

        buffer.append(shortArrayOf(9, 8, 7), 2)
        assertArrayEquals(shortArrayOf(9, 8), buffer.toShortArray())
    }
}
