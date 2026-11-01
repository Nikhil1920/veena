package com.byanr.veena.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byanr.veena.android.data.AppSettings
import com.byanr.veena.android.data.DownloadState
import com.byanr.veena.android.data.ModelCatalog
import com.byanr.veena.android.data.ModelDef
import com.byanr.veena.android.data.ReviewPromptState
import com.byanr.veena.android.engine.EngineState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-wide state: settings, engine readiness, and the orchestration of
 * model downloads (first-run onboarding and later language switches).
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as VeenaApplication).container
    val prefs = container.prefs
    val models = container.models
    val engine = container.engine

    val settings: StateFlow<AppSettings?> = prefs.settings
        .map { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val engineState: StateFlow<EngineState> = engine.state

    /**
     * One-shot request to land on the voice-keyboard screen, set when the app is
     * launched from the IME (see MainActivity.EXTRA_OPEN_VOICE_KEYBOARD) and
     * consumed by the nav graph once the navigation has happened.
     */
    val voiceKeyboardRequested = MutableStateFlow(false)

    fun requestVoiceKeyboard() {
        voiceKeyboardRequested.value = true
    }

    fun consumeVoiceKeyboardRequest() {
        voiceKeyboardRequested.value = false
    }

    /**
     * Whether the Dictate screen shows the "Enjoying Veena?" card. Decided once
     * per launch, so the card never appears in the middle of a session.
     */
    val reviewPromptDue = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            reviewPromptDue.value = prefs.reviewPrompt.first().isDue(ReviewPromptState.today())
        }
    }

    private var reviewPromptCounted = false

    /** Counts one showing per launch, however often the Dictate tab is revisited. */
    fun onReviewPromptShown() {
        if (reviewPromptCounted) return
        reviewPromptCounted = true
        viewModelScope.launch { prefs.markReviewPromptShown(ReviewPromptState.today()) }
    }

    fun dismissReviewPrompt() {
        reviewPromptDue.value = false
    }

    /** The user went to the store to rate Veena; stop asking. */
    fun markRated() {
        reviewPromptDue.value = false
        viewModelScope.launch { prefs.markRated() }
    }

    private var downloadJob: Job? = null

    fun downloadStateFor(def: ModelDef): StateFlow<DownloadState> =
        models.downloadState(def)
            .stateIn(viewModelScope, SharingStarted.Eagerly, DownloadState.Idle)

    fun download(def: ModelDef, onDone: (Boolean) -> Unit) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                models.download(def)
                onDone(true)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                onDone(false)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun setLanguage(code: String) {
        viewModelScope.launch { prefs.setLanguage(code) }
    }

    /** The keyboard's dictation language is independent of the app's. */
    fun setImeLanguage(code: String) {
        viewModelScope.launch { prefs.setImeLanguage(code) }
    }

    fun setMicMode(mode: com.byanr.veena.android.data.MicMode) {
        viewModelScope.launch { prefs.setMicMode(mode) }
    }

    fun dismissKeyboardBanner() {
        viewModelScope.launch { prefs.dismissKeyboardBanner() }
    }

    /** Loads (or reloads) the engine for the selected language; no-op when current. */
    fun warmEngine() {
        val code = settings.value?.languageCode ?: return
        viewModelScope.launch {
            try {
                engine.ensureLanguage(code)
            } catch (_: Throwable) {
                // Engine state carries the error; the UI surfaces it.
            }
        }
    }
}
