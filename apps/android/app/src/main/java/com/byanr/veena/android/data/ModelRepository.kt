package com.byanr.veena.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data object Verifying : DownloadState()
    data class Failed(val message: String) : DownloadState()
    data object Cancelled : DownloadState()
}

data class InstalledModel(
    val def: ModelDef,
    val dir: File,
    val sizeBytes: Long,
)

/**
 * Downloads model tarballs from dl.byanr.com (the same channel the desktop
 * app uses), verifies them, and unpacks them under filesDir/models/<id>/.
 * Downloads resume from .partial files via HTTP Range.
 */
class ModelRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: Flow<Map<String, DownloadState>> = _downloadStates

    fun modelDir(def: ModelDef): File = File(modelsDir, def.id)

    fun isDownloaded(def: ModelDef): Boolean {
        val dir = modelDir(def)
        return dir.isDirectory && File(dir, COMPLETE_MARKER).exists()
    }

    fun installedModels(): List<InstalledModel> =
        ModelCatalog.ALL.filter { isDownloaded(it) }.map { def ->
            val dir = modelDir(def)
            InstalledModel(def, dir, dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() })
        }

    /**
     * Resolves the directory that directly contains the model files for a
     * language, downloading the model first if needed.
     */
    suspend fun ensureModelForLanguage(code: String): File {
        val def = ModelCatalog.modelForLanguage(code)
        if (!isDownloaded(def)) {
            download(def)
        }
        return contentDir(def)
    }

    /** The tarball wraps files in a top-level directory; the loader wants that dir. */
    fun contentDir(def: ModelDef): File {
        val dir = modelDir(def)
        val children = dir.listFiles()?.filter { it.name != COMPLETE_MARKER && !it.name.startsWith("._") } ?: emptyList()
        val onnx = children.firstOrNull { it.isFile && it.extension == "onnx" }
        if (onnx != null) return dir
        val inner = children.filter { it.isDirectory }
        return if (inner.size == 1) inner[0] else dir
    }

    suspend fun download(def: ModelDef) = withContext(Dispatchers.IO) {
        val dest = modelDir(def)
        val partial = File(modelsDir, "${def.id}.tar.gz.partial")
        val stateUp = { s: DownloadState ->
            _downloadStates.value = _downloadStates.value + (def.id to s)
        }

        try {
            stateUp(
                DownloadState.Downloading(
                    partial.takeIf { it.exists() }?.length() ?: 0L,
                    def.sizeBytes,
                )
            )

            val request = Request.Builder().url(def.url).apply {
                if (partial.exists() && partial.length() > 0) {
                    header("Range", "bytes=${partial.length()}-")
                }
            }.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} for ${def.url}")
                }
                val resumed = response.code == 206
                if (!resumed && partial.exists()) {
                    partial.delete() // server ignored Range; restart clean
                }
                val body = response.body ?: throw IOException("Empty response body")

                val out = if (resumed) {
                    java.io.FileOutputStream(partial, true)
                } else {
                    partial.outputStream()
                }
                out.use { stream ->
                    val source = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        stream.write(buffer, 0, n)
                        val total = partial.length()
                        if (total - lastReport > 512 * 1024) {
                            lastReport = total
                            stateUp(DownloadState.Downloading(total, def.sizeBytes))
                        }
                    }
                    stream.flush()
                }
            }

            stateUp(DownloadState.Verifying)
            if (def.sha256 != null) {
                val actual = sha256(partial)
                if (!actual.equals(def.sha256, ignoreCase = true)) {
                    partial.delete()
                    throw IOException(
                        "Checksum mismatch (expected ${def.sha256.take(12)}…, got ${actual.take(12)}…)"
                    )
                }
            }

            val extracting = File(modelsDir, "${def.id}.extracting")
            extracting.deleteRecursively()
            TarGz.extract(partial, extracting)
            dest.deleteRecursively()

            val unpackedRoot = extracting
            val children = unpackedRoot.listFiles() ?: emptyArray()
            val sourceDir = when {
                children.size == 1 && children[0].isDirectory -> children[0]
                else -> unpackedRoot
            }
            if (sourceDir.listFiles()
                    ?.none { it.isFile && it.extension == "onnx" && !it.name.startsWith("._") } != false
            ) {
                throw IOException("Model archive did not contain an .onnx file")
            }

            if (sourceDir == unpackedRoot) {
                dest.mkdirs()
                unpackedRoot.copyRecursively(dest, overwrite = true)
                unpackedRoot.deleteRecursively()
            } else {
                sourceDir.renameTo(dest)
                unpackedRoot.deleteRecursively()
            }
            File(dest, COMPLETE_MARKER).writeText(def.url)
            partial.delete()
            stateUp(DownloadState.Idle)
        } catch (e: kotlinx.coroutines.CancellationException) {
            stateUp(DownloadState.Cancelled)
            throw e
        } catch (e: Exception) {
            stateUp(DownloadState.Failed(e.message ?: "Download failed"))
            throw e
        }
    }

    fun delete(def: ModelDef) {
        modelDir(def).deleteRecursively()
        File(modelsDir, "${def.id}.tar.gz.partial").delete()
    }

    fun downloadState(def: ModelDef): Flow<DownloadState> =
        _downloadStates.map { it[def.id] ?: DownloadState.Idle }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val COMPLETE_MARKER = ".complete"
    }
}
