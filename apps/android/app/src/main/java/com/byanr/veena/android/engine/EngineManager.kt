package com.byanr.veena.android.engine

import com.byanr.veena.android.data.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
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
 */
class EngineManager(private val models: ModelRepository) {

    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    val state: StateFlow<EngineState> = _state

    private var handle: Long = 0L
    private var loadedLanguage: String? = null
    private val mutex = Mutex()

    suspend fun ensureLanguage(languageCode: String) = mutex.withLock {
        if (handle != 0L && loadedLanguage == languageCode &&
            _state.value is EngineState.Ready
        ) {
            return@withLock
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
            _state.value = EngineState.Ready(languageCode)
        } catch (t: Throwable) {
            destroyLocked()
            _state.value = EngineState.Error(t.message ?: "Failed to load model")
            throw t
        }
    }

    /** Transcribes 16 kHz mono samples; returns the recognized text. */
    suspend fun transcribe(audio: FloatArray, sampleRate: Int = 16_000): String =
        withHandle { lang ->
            RustBackend.transcribeAudio(handle, audio, sampleRate, lang)
                ?: throw IllegalStateException("Transcription failed (see logcat)")
        }

    /**
     * Transcribes an audio file. WAV files go straight to the engine; other
     * containers are decoded via MediaCodec first.
     */
    suspend fun transcribeFile(path: String, decoder: (String) -> Pair<FloatArray, Int>): String =
        withHandle { lang ->
            val file = File(path)
            val result = if (file.extension.equals("wav", ignoreCase = true)) {
                RustBackend.transcribeFile(handle, file.absolutePath, lang)
            } else {
                val (samples, sampleRate) = decoder(file.absolutePath)
                RustBackend.transcribeAudio(handle, samples, sampleRate, lang)
            }
            result ?: throw IllegalStateException("Transcription failed (see logcat)")
        }

    private suspend fun <T> withHandle(block: (String?) -> T): T {
        val lang = loadedLanguage
        val current = handle
        if (current == 0L || _state.value !is EngineState.Ready || lang == null) {
            throw IllegalStateException("Engine not ready")
        }
        return mutex.withLock {
            withContext(Dispatchers.Default) { block(lang) }
        }
    }

    private fun destroyLocked() {
        if (handle != 0L) {
            RustBackend.transcriberDestroy(handle)
            handle = 0L
            loadedLanguage = null
        }
    }

    fun shutdown() {
        if (handle != 0L) {
            RustBackend.transcriberDestroy(handle)
            handle = 0L
            loadedLanguage = null
        }
    }

    companion object {
        private const val MODEL_FILE_MULTI = "indicconformer_multi_ctc_int8.onnx"
    }
}
