package com.byanr.veena.android.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal tar reader for the ustar/GNU tarballs the model CDN produces:
 * plain files and directories only, 512-byte headers. GNU long-name ('L')
 * and pax ('x'/'g') metadata blocks are consumed and skipped.
 */
object TarGz {

    private const val BLOCK = 512

    fun extract(tarGzFile: File, targetDir: File) {
        targetDir.mkdirs()
        GZIPInputStream(tarGzFile.inputStream().buffered()).use { gzip ->
            val tar = gzip.buffered()
            val header = ByteArray(BLOCK)
            var pendingLongName: String? = null

            while (readFully(tar, header) == BLOCK) {
                if (header[0] == 0.toByte()) break // end-of-archive block

                var name = cString(header, 0, 100)
                val size = parseTarNumber(header, 124, 12)
                val typeFlag = header[156]
                val prefix = cString(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"

                when (typeFlag) {
                    'L'.code.toByte() -> {
                        val longName = ByteArray(size.toInt())
                        readFully(tar, longName)
                        pendingLongName = cString(longName, 0, longName.size)
                        skipPadding(tar, size)
                        continue
                    }
                    'x'.code.toByte(), 'g'.code.toByte() -> {
                        skipFully(tar, size)
                        skipPadding(tar, size)
                        continue
                    }
                }

                if (pendingLongName != null) {
                    name = pendingLongName
                    pendingLongName = null
                }

                val safeName = sanitize(name)
                // Skip macOS AppleDouble/resource-fork junk shipped in some archives.
                if (safeName.isEmpty() || safeName.split("/").any { it.startsWith("._") || it == ".DS_Store" }) {
                    skipFully(tar, size)
                    skipPadding(tar, size)
                    continue
                }
                val outFile = File(targetDir, safeName)

                when (typeFlag) {
                    '5'.code.toByte(), '/'.code.toByte() -> outFile.mkdirs()
                    '0'.code.toByte(), 0.toByte() -> {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> copyN(tar, out, size) }
                    }
                    else -> skipFully(tar, size) // symlinks/devices not expected
                }
                skipPadding(tar, size)
            }
        }
    }

    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() }
            ?: offset + length
        return bytes.copyOfRange(offset, end).toString(Charsets.UTF_8).trim(' ', '\n')
    }

    private fun sanitize(name: String): String {
        var cleaned = name.replace('\\', '/')
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1)
        return cleaned.split("/")
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
    }

    private fun parseTarNumber(header: ByteArray, offset: Int, length: Int): Long {
        if (header[offset].toInt() and 0x80 != 0) {
            // GNU base-256 encoding
            var value = header[offset].toLong() and 0x7F
            for (i in 1 until length) {
                value = (value shl 8) or (header[offset + i].toLong() and 0xFF)
            }
            return value
        }
        val text = cString(header, offset, length).trim('\u0000')
        return text.toLongOrNull(radix = 8) ?: 0L
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return read
            read += n
        }
        return read
    }

    private fun copyN(input: InputStream, out: OutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("Unexpected end of tar archive")
            out.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n <= 0) {
                if (input.read() < 0) throw IOException("Unexpected end of tar archive")
                remaining -= 1
            } else {
                remaining -= n
            }
        }
    }

    private fun skipPadding(input: InputStream, bytesRead: Long) {
        val rem = bytesRead % BLOCK
        if (rem != 0L) skipFully(input, BLOCK - rem)
    }
}
