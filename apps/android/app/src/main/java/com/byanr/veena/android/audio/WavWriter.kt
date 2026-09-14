package com.byanr.veena.android.audio

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/** Minimal 16-bit PCM mono WAV writer. */
object WavWriter {

    fun writeHeader(out: OutputStream, dataBytes: Long, sampleRate: Int) {
        val dataSize = dataBytes.toInt()
        val byteRate = sampleRate * 2 // 16-bit mono
        val header = ByteArray(44)
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        fun putText(offset: Int, text: String) {
            text.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }

        putText(0, "RIFF")
        putInt(4, 36 + dataSize)
        putText(8, "WAVE")
        putText(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // mono
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, 2) // block align
        putShort(34, 16) // bits per sample
        putText(36, "data")
        putInt(40, dataSize)
        out.write(header)
    }

    /** Rewrites the RIFF/data sizes once the sample payload length is known. */
    fun patchSizes(file: File, dataBytes: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.writeInt(Integer.reverseBytes((36 + dataBytes).toInt()))
            raf.seek(40)
            raf.writeInt(Integer.reverseBytes(dataBytes.toInt()))
        }
    }
}
