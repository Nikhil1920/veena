package com.byanr.veena.android.engine

import com.byanr.veena.android.data.ModelCatalog
import com.byanr.veena.android.data.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    data class Loading(val languageCode: String) : EngineState()
    data class Ready(val languageCode: String) : EngineState()
    data class Error(val languageCode: String, val message: String) : EngineState()
}

/**
 * Owns the native transcriber for the currently selected language. The
 * Telugu model is a single-language graph; every other language loads the
 * multilingual graph and passes the language code per utterance.
 *
 * The app and the voice keyboard share this engine, each with its own
 * language, so transcription always names its language and loads it first
 * if the other side switched the engine away. The lock covers loading only:
 * transcriptions run concurrently (the native engine takes turns per
 * window), so a keyboard dictation never waits out a long import. A model
 * replaced mid-transcription is freed when its last transcription ends.
 */
class EngineManager(private val models: ModelRepository) {

    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    val state: StateFlow<EngineState> = _state

    /** A native transcriber and the transcriptions running on it. */
    private class LoadedModel(val handle: Long, val modelId: String) {
        var users = 0
        var retired = false
    }

    // Guarded by [mutex].
    private var current: LoadedModel? = null
    private var loadedLanguage: String? = null
    private val mutex = Mutex()

    // Outlives any one keyboard service: switching keyboards destroys the
    // service, and the next mic tap shouldn't pay for a model reload.
    private val unloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Guarded by its own monitor: keyboard services schedule and cancel
    // from different threads.
    private val unloadLock = Any()
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
        if (current?.modelId == modelId) {
            if (loadedLanguage != languageCode || _state.value !is EngineState.Ready) {
                loadedLanguage = languageCode
                _state.value = EngineState.Ready(languageCode)
            }
            return
        }

        _state.value = EngineState.Loading(languageCode)
        try {
            val modelDir: File = models.ensureModelForLanguage(languageCode)
            val created = withContext(Dispatchers.IO) {
                retireLocked()
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
            current = LoadedModel(created, modelId)
            loadedLanguage = languageCode
            _state.value = EngineState.Ready(languageCode)
        } catch (t: Throwable) {
            retireLocked()
            _state.value = EngineState.Error(languageCode, t.message ?: "Failed to load model")
            throw t
        }
    }

    /**
     * The engine as a caller working in [languageCode] sees it: another
     * model's load, failure or readiness doesn't concern it, since
     * transcription loads its model on demand.
     */
    fun stateFor(languageCode: String, state: EngineState = this.state.value): EngineState {
        val ours = ModelCatalog.modelForLanguage(languageCode)
        val shown = when (state) {
            is EngineState.NotLoaded -> return state
            is EngineState.Loading -> state.languageCode
            is EngineState.Ready -> state.languageCode
            is EngineState.Error -> state.languageCode
        }
        return when {
            ModelCatalog.modelForLanguage(shown).id == ours.id -> state
            models.isDownloaded(ours) -> EngineState.Ready(languageCode)
            else -> EngineState.NotLoaded
        }
    }

    /** Transcribes 16 kHz mono samples in [languageCode]; returns the recognized text. */
    suspend fun transcribe(
        audio: FloatArray,
        sampleRate: Int = 16_000,
        languageCode: String,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): String =
        withLanguage(languageCode, onProgress) { handle, progress ->
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
        withLanguage(languageCode, onProgress) { handle, progress ->
            val file = File(path)
            if (file.extension.equals("wav", ignoreCase = true)) {
                RustBackend.transcribeFile(handle, file.absolutePath, languageCode, progress)
            } else {
                val (samples, sampleRate) = decoder(file.absolutePath)
                RustBackend.transcribeAudio(handle, samples, sampleRate, languageCode, progress)
            }
        }

    /**
     * Runs one native transcription on the model for [languageCode], loading
     * it first if needed. The lock is held only while loading; the model
     * stays alive until the transcription ends even if another caller
     * switches models meanwhile. Cancelling the calling coroutine stops the
     * engine before its next 25 s window.
     */
    private suspend fun withLanguage(
        languageCode: String,
        onProgress: ((completed: Int, total: Int) -> Unit)?,
        block: (handle: Long, TranscriptionProgress) -> String?,
    ): String {
        val model = mutex.withLock {
            if (!models.isDownloaded(ModelCatalog.modelForLanguage(languageCode))) {
                throw IllegalStateException("Engine not ready")
            }
            loadLocked(languageCode)
            checkNotNull(current).also { it.users++ }
        }
        try {
            return withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                val progress = TranscriptionProgress { completed, total ->
                    onProgress?.invoke(completed, total)
                    job.isActive
                }
                val text = block(model.handle, progress)
                ensureActive()
                text ?: throw IllegalStateException("Transcription failed (see logcat)")
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    model.users--
                    if (model.retired && model.users == 0) RustBackend.transcriberDestroy(model.handle)
                }
            }
        }
    }

    /**
     * Drops the current model: freed now if idle, else by the last
     * transcription still running on it.
     */
    private fun retireLocked() {
        val model = current ?: return
        current = null
        loadedLanguage = null
        if (model.users == 0) {
            RustBackend.transcriberDestroy(model.handle)
        } else {
            model.retired = true
        }
    }

    /** Frees the native model; the next load or transcription brings it back. */
    suspend fun shutdown() = mutex.withLock {
        retireLocked()
        _state.value = EngineState.NotLoaded
    }

    /**
     * Frees the model after [delayMs] unless [cancelScheduledUnload] comes
     * first. While [keepLoaded] holds when the delay ends, it waits another
     * round.
     */
    fun scheduleUnload(delayMs: Long, keepLoaded: () -> Boolean = { false }) = synchronized(unloadLock) {
        unloadJob?.cancel()
        unloadJob = unloadScope.launch {
            do {
                delay(delayMs)
            } while (keepLoaded())
            shutdown()
        }
    }

    fun cancelScheduledUnload() = synchronized(unloadLock) {
        unloadJob?.cancel()
        unloadJob = null
    }

    companion object {
        private const val MODEL_FILE_MULTI = "indicconformer_multi_ctc_int8.onnx"
    }
}
