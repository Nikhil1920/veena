package com.byanr.veena.android.ime

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.byanr.veena.android.MainActivity
import com.byanr.veena.android.audio.AudioFileDecoder
import com.byanr.veena.android.audio.MicRecorder
import com.byanr.veena.android.data.LANGUAGES
import com.byanr.veena.android.data.MicMode
import com.byanr.veena.android.data.ModelCatalog
import com.byanr.veena.android.data.ModelRepository
import com.byanr.veena.android.data.Prefs
import com.byanr.veena.android.data.ReviewPromptState
import com.byanr.veena.android.engine.EngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Why [ImeSession.startDictation] did or did not begin recording. */
sealed interface StartOutcome {
    data object Started : StartOutcome
    data class ModelNotInstalled(val languageCode: String) : StartOutcome
    data class MicUnavailable(val reason: String) : StartOutcome
    data class Failed(val reason: String) : StartOutcome
}

/** What the keyboard UI should render for the current dictation. */
sealed interface ImeDictationState {
    data object Idle : ImeDictationState
    data object PreparingModel : ImeDictationState
    data class Recording(val elapsedMs: Long, val level: Float) : ImeDictationState
    data object Transcribing : ImeDictationState
    data class Error(val message: String) : ImeDictationState
}

/**
 * Dictation session behind the voice keyboard. Mirrors the in-app dictate
 * flow (prepare model → record → transcribe) but owns its own [MicRecorder]
 * and never writes audio to disk or history. Start failures are reported via
 * [StartOutcome] and leave [state] untouched; only stop failures surface as
 * [ImeDictationState.Error].
 */
class ImeSession(
    context: Context,
    private val engine: EngineManager,
    private val models: ModelRepository,
    private val prefs: Prefs,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val recorder = MicRecorder()

    private val _state = MutableStateFlow<ImeDictationState>(ImeDictationState.Idle)
    val state: StateFlow<ImeDictationState> = _state

    private val _languageCode = MutableStateFlow(DEFAULT_LANGUAGE)
    val languageCode: StateFlow<String> = _languageCode

    private val _micMode = MutableStateFlow(MicMode.HOLD)
    val micMode: StateFlow<MicMode> = _micMode

    private var tickerJob: Job? = null
    /** The language the current recording was started in. */
    private var recordingLanguage = DEFAULT_LANGUAGE

    init {
        scope.launch {
            prefs.settings.collect { settings ->
                _languageCode.value = settings.imeLanguageCode
                    ?: settings.languageCode
                    ?: DEFAULT_LANGUAGE
                _micMode.value = settings.micMode
            }
        }
    }

    /** Downloaded language codes, ordered like [LANGUAGES]. */
    fun downloadedLanguages(): List<String> =
        LANGUAGES.map { it.code }.filter { models.isDownloaded(ModelCatalog.modelForLanguage(it)) }

    suspend fun setLanguage(code: String) {
        prefs.setImeLanguage(code)
    }

    suspend fun startDictation(): StartOutcome = mutex.withLock {
        engine.cancelScheduledUnload()

        if (recorder.isRecording) return@withLock StartOutcome.Started

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withLock StartOutcome.MicUnavailable("Microphone permission not granted")
        }

        val code = _languageCode.value
        if (!models.isDownloaded(ModelCatalog.modelForLanguage(code))) {
            return@withLock StartOutcome.ModelNotInstalled(code)
        }

        _state.value = ImeDictationState.PreparingModel
        recordingLanguage = code
        try {
            engine.ensureLanguage(code)
        } catch (t: Throwable) {
            _state.value = ImeDictationState.Idle
            return@withLock StartOutcome.Failed(t.message ?: "Failed to load model")
        }

        try {
            recorder.start(null) // Keyboard dictations stay out of disk and history.
        } catch (t: Throwable) {
            _state.value = ImeDictationState.Idle
            return@withLock StartOutcome.Failed(t.message ?: "Microphone unavailable")
        }

        val startedAt = System.currentTimeMillis()
        _state.value = ImeDictationState.Recording(0L, 0f)
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (_state.value is ImeDictationState.Recording) {
                delay(TICK_MS)
                if (!recorder.isRecording) break // Stop/cancel won the race; keep its state.
                _state.value = ImeDictationState.Recording(
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    level = recorder.liveRms,
                )
            }
        }
        StartOutcome.Started
    }

    suspend fun stopDictation(): String? = mutex.withLock {
        if (!recorder.isRecording) return@withLock null

        tickerJob?.cancelAndJoin()
        tickerJob = null

        val recording = try {
            recorder.stop()
        } catch (t: Throwable) {
            _state.value = ImeDictationState.Error(t.message ?: "Failed to stop recording")
            return@withLock null
        }

        _state.value = ImeDictationState.Transcribing
        try {
            val audio = debugTestAudio()
                ?: FloatArray(recording.samples.size) { recording.samples[it] / 32768f }
            val text = engine.transcribe(audio, recording.sampleRate, recordingLanguage)
            _state.value = ImeDictationState.Idle
            if (text.isNotBlank()) {
                _completedDictations.value += 1
                scope.launch { prefs.recordDictation(ReviewPromptState.today()) }
            }
            text
        } catch (t: Throwable) {
            _state.value = ImeDictationState.Error(t.message ?: "Transcription failed")
            null
        }
    }

    fun cancelDictation() {
        // Deliberately no early recorder check: a cancel arriving while the
        // model is still preparing must wait out the mutex and stop the
        // recording that starts afterwards; the in-lock check no-ops safely.
        scope.launch {
            mutex.withLock {
                if (!recorder.isRecording) return@withLock
                tickerJob?.cancel()
                tickerJob = null
                try {
                    recorder.stop() // Samples are discarded.
                } catch (_: Throwable) {
                    // Recorder already stopped; there is nothing to salvage.
                }
                _state.value = ImeDictationState.Idle
            }
        }
    }

    /**
     * Test hook for debuggable builds only: a `files/keyboard-test-audio.wav`
     * (pushed with `adb shell run-as`) stands in for the microphone, since
     * emulators have no voice to dictate with.
     */
    private fun debugTestAudio(): FloatArray? {
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val file = File(appContext.filesDir, "keyboard-test-audio.wav")
        if (!file.exists()) return null
        return runCatching { AudioFileDecoder.decodeToMono16k(file.absolutePath).first }.getOrNull()
    }

    /**
     * Unloads the engine after [delayMs] without dictation; the next start
     * cancels this. The app shares the engine, so it stays loaded while the
     * app is on screen.
     */
    fun scheduleIdleUnload(delayMs: Long = IDLE_UNLOAD_MS) {
        engine.scheduleUnload(delayMs) { MainActivity.isInForeground }
    }

    /**
     * Ends this session for good (the service is going away). The engine is
     * left for the idle unload: switching keyboards destroys the service, and
     * the next mic tap — another keyboard's, for the voice input — should find
     * the model still loaded.
     */
    fun shutdown() {
        scope.launch {
            mutex.withLock {
                tickerJob?.cancel()
                if (recorder.isRecording) {
                    runCatching { recorder.stop() } // Samples are discarded.
                }
            }
            recorder.shutdown()
            scheduleIdleUnload()
            scope.cancel()
        }
    }

    companion object {
        private val _completedDictations = MutableStateFlow(0)

        /** Dictations that produced text, for the setup tour's test step. */
        val completedDictations: StateFlow<Int> = _completedDictations

        private const val DEFAULT_LANGUAGE = "te"
        private const val TICK_MS = 100L
        private const val IDLE_UNLOAD_MS = 600_000L
    }
}
