package com.byanr.veena.android.engine

import com.byanr.veena.android.data.ModelCatalog
import com.byanr.veena.android.data.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed class EngineState {
    data object NotLoaded : EngineState()
    data object Loading : EngineState()
    data class Ready(val languageCode: String) : EngineState()
    data class Error(val message: String) : EngineState()
}

/**
 * Owns the native transcriber for the currently selected language. The
 * Telugu model is a single-language graph; every other language loads the
 * multilingual graph and passes the language code per utterance.
 *
 * The app and the voice keyboard share this engine, each with its own
 * language, so transcription always names its language and loads it first
 * if the other side switched the engine away.
 */
class EngineManager(private val models: ModelRepository) {

    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    val state: StateFlow<EngineState> = _state

    private var handle: Long = 0L
    private var loadedLanguage: String? = null
    private var loadedModelId: String? = null
    private val mutex = Mutex()

    // Outlives any one keyboard service: switching keyboards destroys the
    // service, and the next mic tap shouldn't pay for a model reload.
    private val unloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var unloadJob: Job? = null

    suspend fun ensureLanguage(languageCode: String) = mutex.withLock {
        loadLocked(languageCode)
    }

    /**
     * Makes [languageCode] the active language. Languages sharing a model
     * (every non-Telugu language uses the multilingual graph) switch without
     * a reload, since the language is passed per utterance.
     */
    private suspend fun loadLocked(languageCode: String) {
        val modelId = ModelCatalog.modelForLanguage(languageCode).id
        if (handle != 0L && loadedModelId == modelId) {
            if (loadedLanguage != languageCode || _state.value !is EngineState.Ready) {
                loadedLanguage = languageCode
                _state.value = EngineState.Ready(languageCode)
            }
            return
        }

        _state.value = EngineState.Loading
        try {
            val modelDir: File = models.ensureModelForLanguage(languageCode)
            val created = withContext(Dispatchers.IO) {
                destroyLocked()
                val multilingual = File(modelDir, "language_masks.json").exists()
                if (multilingual) {
                    RustBackend.transcriberCreateMultilingual(
                        File(modelDir, MODEL_FILE_MULTI).absolutePath,
                        File(modelDir, "decoder_config.json").absolutePath,
                        File(modelDir, "vocab.json").absolutePath,
                        File(modelDir, "language_masks.json").absolutePath,
                    )
                } else {
                    fun modelFile(vararg extensions: String): File =
                        modelDir.listFiles()
                            ?.filter { it.isFile && !it.name.startsWith("._") }
                            ?.firstOrNull { it.extension.lowercase() in extensions }
                            ?: error("No ${extensions.joinToString("/")} file in ${modelDir.absolutePath}")
                    val onnx = modelFile("onnx")
                    val vocab = modelFile("vocab")
                    RustBackend.transcriberCreate(
                        onnx.absolutePath,
                        File(modelDir, "decoder_config.json").absolutePath,
                        vocab.absolutePath,
                    )
                }
            }
            if (created == 0L) {
                throw IllegalStateException("Failed to create native transcriber (see logcat)")
            }
            handle = created
            loadedLanguage = languageCode
            loadedModelId = modelId
            _state.value = EngineState.Ready(languageCode)
        } catch (t: Throwable) {
            destroyLocked()
            _state.value = EngineState.Error(t.message ?: "Failed to load model")
            throw t
        }
    }

    /** Transcribes 16 kHz mono samples in [languageCode]; returns the recognized text. */
    suspend fun transcribe(
        audio: FloatArray,
        sampleRate: Int = 16_000,
        languageCode: String,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): String =
        withLanguage(languageCode, onProgress) { progress ->
            RustBackend.transcribeAudio(handle, audio, sampleRate, languageCode, progress)
        }

    /**
     * Transcribes an audio file in [languageCode]. WAV files go straight to
     * the engine; other containers are decoded via MediaCodec first.
     */
    suspend fun transcribeFile(
        path: String,
        languageCode: String,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
        decoder: (String) -> Pair<FloatArray, Int>,
    ): String =
        withLanguage(languageCode, onProgress) { progress ->
            val file = File(path)
            if (file.extension.equals("wav", ignoreCase = true)) {
                RustBackend.transcribeFile(handle, file.absolutePath, languageCode, progress)
            } else {
                val (samples, sampleRate) = decoder(file.absolutePath)
                RustBackend.transcribeAudio(handle, samples, sampleRate, languageCode, progress)
            }
        }

    /**
     * Runs one native transcription with [languageCode] loaded, holding the
     * lock so nobody switches in between. Cancelling the calling coroutine
     * stops the engine before its next 25 s window.
     */
    private suspend fun withLanguage(
        languageCode: String,
        onProgress: ((completed: Int, total: Int) -> Unit)?,
        block: (TranscriptionProgress) -> String?,
    ): String =
        mutex.withLock {
            if (!models.isDownloaded(ModelCatalog.modelForLanguage(languageCode))) {
                throw IllegalStateException("Engine not ready")
            }
            loadLocked(languageCode)
            withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                val progress = TranscriptionProgress { completed, total ->
                    onProgress?.invoke(completed, total)
                    job.isActive
                }
                val text = block(progress)
                ensureActive()
                text ?: throw IllegalStateException("Transcription failed (see logcat)")
            }
        }

    private fun destroyLocked() {
        if (handle != 0L) {
            RustBackend.transcriberDestroy(handle)
            handle = 0L
            loadedLanguage = null
            loadedModelId = null
        }
    }

    /** Frees the native model; the next load or transcription brings it back. */
    suspend fun shutdown() = mutex.withLock {
        destroyLocked()
        _state.value = EngineState.NotLoaded
    }

    /**
     * Frees the model after [delayMs] unless [cancelScheduledUnload] comes
     * first. While [keepLoaded] holds when the delay ends, it waits another
     * round.
     */
    fun scheduleUnload(delayMs: Long, keepLoaded: () -> Boolean = { false }) {
        unloadJob?.cancel()
        unloadJob = unloadScope.launch {
            do {
                delay(delayMs)
            } while (keepLoaded())
            shutdown()
        }
    }

    fun cancelScheduledUnload() {
        unloadJob?.cancel()
        unloadJob = null
    }

    companion object {
        private const val MODEL_FILE_MULTI = "indicconformer_multi_ctc_int8.onnx"
    }
}
