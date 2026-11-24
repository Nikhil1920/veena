package com.byanr.veena.android.audio

/**
 * The samples of one recording, in fixed-size primitive chunks: 2 bytes per
 * sample, and growing never copies what is already stored. An
 * `ArrayList<Short>` boxed each sample (about 20 bytes), so about 10
 * minutes of recording filled a phone's Java heap.
 */
internal class PcmBuffer(private val chunkSize: Int = CHUNK_SAMPLES) {

    private val chunks = ArrayList<ShortArray>()

    var size = 0
        private set

    fun append(src: ShortArray, count: Int) {
        var copied = 0
        while (copied < count) {
            val offset = size % chunkSize
            if (offset == 0) chunks.add(ShortArray(chunkSize))
            val n = minOf(count - copied, chunkSize - offset)
            src.copyInto(chunks.last(), offset, copied, copied + n)
            copied += n
            size += n
        }
    }

    operator fun get(index: Int): Short {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("$index of $size")
        return chunks[index / chunkSize][index % chunkSize]
    }

    fun toShortArray(): ShortArray {
        val out = ShortArray(size)
        var pos = 0
        for (chunk in chunks) {
            val n = minOf(chunkSize, size - pos)
            chunk.copyInto(out, pos, 0, n)
            pos += n
        }
        return out
    }

    /** Drops every chunk, so the last recording no longer holds memory. */
    fun clear() {
        chunks.clear()
        size = 0
    }

    companion object {
        /** About 4 s at 16 kHz (128 KB). */
        const val CHUNK_SAMPLES = 64 * 1024
    }
}
