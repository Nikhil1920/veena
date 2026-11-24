package com.byanr.veena.android.ui.dictate

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byanr.veena.android.VeenaApplication
import com.byanr.veena.android.audio.AudioFileDecoder
import com.byanr.veena.android.audio.AudioPlayer
import com.byanr.veena.android.audio.DebugMicAudio
import com.byanr.veena.android.audio.Recording
import com.byanr.veena.android.data.HistoryEntry
import com.byanr.veena.android.data.Prefs
import com.byanr.veena.android.data.ReviewPromptState
import com.byanr.veena.android.engine.EngineState
import com.byanr.veena.android.engine.LiveDictation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DictateViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as VeenaApplication).container
    private val engine = container.engine
    private val recorder = container.recorder
    private val historyDao = container.history
    private val recordingsDir = container.recordingsDir
    private val prefs = container.prefs

    private val currentLanguageCode = MutableStateFlow("te")
    private var liveTranscription = true

    // The keyboard shares the engine: its loads and failures in another
    // model don't block the app, which loads its own model on demand.
    val engineState: StateFlow<EngineState> =
        combine(engine.state, currentLanguageCode) { state, code -> engine.stateFor(code, state) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                engine.stateFor(currentLanguageCode.value),
            )
    val player = AudioPlayer()

    val isRecording = MutableStateFlow(false)
    val isTranscribing = MutableStateFlow(false)
    // Fraction of a long transcription done; null while unknown, and for clips
    // short enough to run in a single window.
    val transcribeProgress = MutableStateFlow<Float?>(null)
    // Cancel was requested; the engine stops at the end of its current window.
    val isCancelling = MutableStateFlow(false)
    private var transcription: Job? = null
    val text = MutableStateFlow("")
    // Increments whenever text is appended by the app (dictation/import) — lets
    // the UI auto-scroll to the newest output without reacting to manual edits.
    val textRevision = MutableStateFlow(0)
    // Text of the recording in progress, shown until its transcript replaces
    // it; empty when there is none. Its last words can still change.
    val liveText = MutableStateFlow("")
    private var live: LiveDictation? = null
    private var testAudio: DebugMicAudio? = null
    val lastRecording = MutableStateFlow<Recording?>(null)
    val error = MutableStateFlow<String?>(null)

    val isEngineReady: Boolean
        get() = engineState.value is EngineState.Ready

    suspend fun startRecording() {
        if (isRecording.value) return
        if (!isEngineReady) throw IllegalStateException("Model not ready yet")
        val file = File(recordingsDir, "rec_${System.currentTimeMillis()}.wav")
        testAudio = withContext(Dispatchers.IO) { DebugMicAudio.load(getApplication()) }
        recorder.start(file)
        isRecording.value = true
        // No suspension between the flag and the session: a stop that sees
        // the recording also sees its live text.
        if (liveTranscription) {
            val audioFrom = testAudio?.let { it::samplesFrom } ?: recorder::samplesFrom
            live = LiveDictation(viewModelScope, engine, currentLanguage(), audioFrom) {
                liveText.value = it
            }
        }
    }

    suspend fun stopRecordingAndTranscribe() {
        if (!isRecording.value) return
        isRecording.value = false
        val live = live.also { live = null }

        val recording = try {
            recorder.stop()
        } catch (t: Throwable) {
            dropLiveText(live)
            error.value = t.message ?: "Failed to stop recording"
            return
        }
        // The screen only plays the file back; keeping the samples here would
        // hold the whole recording in memory through the next one.
        lastRecording.value = recording.copy(samples = ShortArray(0))
        if (recording.durationMs < MIN_RECORDING_MS) {
            // Too short to contain speech — skip the inference pass.
            dropLiveText(live)
            return
        }

        transcribeSamples(recording.samples, recording.sampleRate, live) { transcript ->
            HistoryEntry(
                createdAt = System.currentTimeMillis(),
                languageCode = currentLanguage(),
                transcript = transcript,
                audioPath = recording.file.absolutePath,
                durationMs = recording.durationMs,
                source = SOURCE_MIC,
            )
        }
    }

    fun transcribeImportedFile(uri: Uri) {
        if (!isEngineReady) {
            error.value = "Model not ready yet"
            return
        }
        transcription = viewModelScope.launch {
            isTranscribing.value = true
            var imported: File? = null
            try {
                val (file, displayName) = withContext(Dispatchers.IO) {
                    copyToCache(uri)
                }
                imported = file
                val transcript = engine.transcribeFile(file.absolutePath, currentLanguage(), ::reportProgress) { path ->
                    AudioFileDecoder.decodeToMono16k(path)
                }
                if (transcript.isNotBlank()) {
                    appendText(transcript)
                    recordDictation()
                    historyDao.insert(
                        HistoryEntry(
                            createdAt = System.currentTimeMillis(),
                            languageCode = currentLanguage(),
                            transcript = transcript,
                            audioPath = file.absolutePath,
                            durationMs = 0,
                            source = SOURCE_IMPORT,
                        )
                    )
                } else {
                    error.value = "No speech recognized in $displayName"
                    withContext(Dispatchers.IO) { file.delete() }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { imported?.delete() }
                throw e
            } catch (t: Throwable) {
                error.value = t.message ?: "Failed to transcribe file"
            } finally {
                finishTranscription()
            }
        }
    }

    /** Stops the running transcription; nothing is kept from a cancelled run. */
    fun cancelTranscription() {
        val job = transcription ?: return
        isCancelling.value = true
        job.cancel()
    }

    fun setText(value: String) {
        text.value = value
    }

    fun appendText(value: String) {
        text.value = if (text.value.isBlank()) value else "${text.value} $value"
        textRevision.value += 1
    }

    fun clearText() {
        text.value = ""
        error.value = null
    }

    fun dismissError() {
        error.value = null
    }

    /**
     * Transcribes a finished recording. [live] has already transcribed most
     * of it while it was being made; without it, or if it failed, the whole
     * recording is transcribed now.
     */
    private suspend fun transcribeSamples(
        samples: ShortArray,
        sampleRate: Int,
        live: LiveDictation?,
        entryFor: suspend (String) -> HistoryEntry,
    ) {
        isTranscribing.value = true
        transcription = currentCoroutineContext().job
        try {
            val audio = testAudio?.samples ?: FloatArray(samples.size) { samples[it] / 32768f }
            val transcript = live?.finish(audio)
                ?: engine.transcribe(audio, sampleRate, currentLanguage(), ::reportProgress)
            if (transcript.isNotBlank()) {
                appendText(transcript)
                recordDictation()
                historyDao.insert(entryFor(transcript))
            } else {
                error.value = "No speech recognized"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            error.value = t.message ?: "Transcription failed"
        } finally {
            finishTranscription()
        }
    }

    /** Counts toward the "Enjoying Veena?" prompt; never holds up the transcript. */
    private fun recordDictation() {
        viewModelScope.launch { prefs.recordDictation(ReviewPromptState.today()) }
    }

    private fun reportProgress(completed: Int, total: Int) {
        if (total > 1) transcribeProgress.value = completed.toFloat() / total
    }

    private suspend fun dropLiveText(live: LiveDictation?) {
        live?.cancel()
        liveText.value = ""
    }

    private fun finishTranscription() {
        liveText.value = ""
        isTranscribing.value = false
        isCancelling.value = false
        transcribeProgress.value = null
        transcription = null
    }

    private suspend fun copyToCache(uri: Uri): Pair<File, String> {
        val resolver = getApplication<Application>().contentResolver
        val name = queryDisplayName(uri) ?: "import_${System.currentTimeMillis()}"
        val ext = name.substringAfterLast('.', "wav").ifBlank { "wav" }
        val dest = File(recordingsDir, "imp_${System.currentTimeMillis()}.$ext")
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Cannot open selected file")
        return Pair(dest, name)
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }

    init {
        viewModelScope.launch {
            prefs.settings.collect {
                currentLanguageCode.value = it.languageCode ?: "te"
                liveTranscription = it.liveTranscription
            }
        }
    }

    private fun currentLanguage(): String = currentLanguageCode.value

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    companion object {
        private const val MIN_RECORDING_MS = 400L
        const val SOURCE_MIC = "mic"
        const val SOURCE_IMPORT = "import"
    }
}
