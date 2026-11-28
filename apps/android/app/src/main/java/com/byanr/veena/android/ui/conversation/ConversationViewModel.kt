package com.byanr.veena.android.ui.conversation

import android.app.Application
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byanr.veena.android.VeenaApplication
import com.byanr.veena.android.audio.DebugMicAudio
import com.byanr.veena.android.audio.MicStream
import com.byanr.veena.android.data.ConversationItem
import com.byanr.veena.android.data.Conversations
import com.byanr.veena.android.data.HistoryEntry
import com.byanr.veena.android.data.SOURCE_CONVERSATION
import com.byanr.veena.android.data.SpeechLine
import com.byanr.veena.android.data.TypedReply
import com.byanr.veena.android.engine.CaptionUpdate
import com.byanr.veena.android.engine.ConversationCaptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

sealed interface ListenState {
    /** The screen is not on view. */
    data object Stopped : ListenState
    data class Loading(val languageCode: String) : ListenState
    data object Listening : ListenState
    /** The user paused listening; it stays paused until they resume. */
    data object Paused : ListenState
    data class Failed(val message: String) : ListenState
}

/**
 * The Conversation screen's transcript and listening. Scoped to the
 * activity, so the conversation survives leaving the tab; it listens only
 * while the screen is on view ([onVisible] / [onHidden]).
 *
 * The microphone streams into the engine's conversation captions, which
 * cut it into utterances at pauses: each finished utterance becomes a
 * [SpeechLine], and the one still being spoken shows as [partial]. No audio
 * is kept. While saving is on, the conversation is one History entry,
 * written again as it grows.
 */
class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as VeenaApplication).container
    private val engine = container.engine
    private val history = container.history
    private val prefs = container.prefs
    private val mic = MicStream()

    private val _items = MutableStateFlow<List<ConversationItem>>(emptyList())
    val items: StateFlow<List<ConversationItem>> = _items

    /** The utterance still being spoken; its words can still change. */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    private val _state = MutableStateFlow<ListenState>(ListenState.Stopped)
    val state: StateFlow<ListenState> = _state

    /** Microphone RMS while listening, a few updates a second. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private var paused = false
    /** The screen's language while it is on view. */
    private var visibleLanguage: String? = null

    /** The latest listening coroutine (or the one stopping it); each waits for the last. */
    private var listenJob: Job? = null
    private var listenLanguage: String? = null

    private var testAudio: DebugMicAudio? = null
    private var testAudioLoaded = false

    private var nextId = 1L
    private var conversationLanguage: String? = null
    private var historyId: Long? = null
    /** Bumped by [clear], so a save in flight doesn't claim the new conversation's row. */
    private var generation = 0
    private val saves = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (request in saves) save()
        }
        // Turning saving on saves the conversation so far.
        viewModelScope.launch {
            prefs.settings.map { it.saveConversations }.distinctUntilChanged().collect {
                if (it) saves.trySend(Unit)
            }
        }
    }

    /** The screen came on view in [languageCode] (and may hold the microphone). */
    fun onVisible(languageCode: String) {
        visibleLanguage = languageCode
        val last = _items.value.lastOrNull()
        if (last != null && System.currentTimeMillis() - last.time > Conversations.STALE_AFTER_MS) {
            clear()
        }
        if (paused) _state.value = ListenState.Paused else listen(languageCode)
    }

    fun onHidden() {
        visibleLanguage = null
        stopListening(ListenState.Stopped)
    }

    fun togglePause() {
        if (paused || _state.value is ListenState.Failed) {
            paused = false
            visibleLanguage?.let(::listen)
        } else {
            paused = true
            stopListening(ListenState.Paused)
        }
    }

    fun sendTyped(text: String) {
        val reply = text.trim()
        if (reply.isEmpty()) return
        add(TypedReply(nextId++, System.currentTimeMillis(), reply))
    }

    /** Starts a new conversation (and History entry); the old one stays saved. */
    fun clear() {
        _items.value = emptyList()
        conversationLanguage = null
        historyId = null
        generation++
    }

    /** The conversation as text, for sharing and copying. */
    fun exportText(): String = Conversations.text(_items.value, ::formatTime)

    fun formatTime(time: Long): String =
        DateFormat.getTimeFormat(getApplication()).format(Date(time))

    private fun listen(languageCode: String) {
        if (listenJob?.isActive == true && listenLanguage == languageCode) return
        val previous = listenJob
        listenLanguage = languageCode
        listenJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            runListening(languageCode)
        }
    }

    private fun stopListening(next: ListenState) {
        val previous = listenJob
        listenLanguage = null
        listenJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            _state.value = next
        }
    }

    private suspend fun runListening(languageCode: String) {
        _state.value = ListenState.Loading(languageCode)
        var captions: ConversationCaptions? = null
        try {
            captions = engine.openConversation(languageCode)
            if (!testAudioLoaded) {
                testAudio = withContext(Dispatchers.IO) { DebugMicAudio.load(getApplication()) }
                testAudioLoaded = true
            }
            mic.start(testAudio)
            _state.value = ListenState.Listening
            coroutineScope {
                launch {
                    while (true) {
                        _level.value = mic.level
                        delay(LEVEL_INTERVAL_MS)
                    }
                }
                var failures = 0
                while (true) {
                    val startedAt = SystemClock.elapsedRealtime()
                    try {
                        apply(captions.step(mic.drain()))
                        failures = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // The engine dropped the utterance it failed on; give up
                        // only if it keeps failing.
                        if (++failures >= MAX_FAILURES) throw t
                    }
                    // A slow phone spends at most two thirds of its time decoding.
                    val tookMs = SystemClock.elapsedRealtime() - startedAt
                    delay(maxOf(MIN_PAUSE_MS, tookMs / 2))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _state.value = ListenState.Failed(t.message ?: "Listening stopped")
        } finally {
            withContext(NonCancellable) {
                val rest = mic.stop()
                _level.value = 0f
                captions?.let {
                    // What was being said when listening stopped still gets its line.
                    runCatching { apply(it.flush(rest)) }
                    it.close()
                }
                _partial.value = ""
            }
        }
    }

    private fun apply(update: CaptionUpdate) {
        val now = System.currentTimeMillis()
        update.finished.forEach { add(SpeechLine(nextId++, now, it)) }
        _partial.value = update.partial
    }

    private fun add(item: ConversationItem) {
        if (_items.value.isEmpty()) {
            conversationLanguage = listenLanguage ?: visibleLanguage
        }
        _items.value = _items.value + item
        saves.trySend(Unit)
    }

    private suspend fun save() {
        val items = _items.value
        if (items.isEmpty() || !prefs.settings.first().saveConversations) return
        val savingGeneration = generation
        val entry = HistoryEntry(
            id = historyId ?: 0,
            createdAt = items.first().time,
            languageCode = conversationLanguage ?: "",
            transcript = Conversations.text(items, ::formatTime),
            audioPath = "",
            durationMs = items.last().time - items.first().time,
            source = SOURCE_CONVERSATION,
        )
        val id = try {
            withContext(Dispatchers.IO) { history.upsert(entry) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return // Saving is best-effort; the next item tries again.
        }
        if (id != -1L && savingGeneration == generation) historyId = id
    }

    override fun onCleared() {
        mic.shutdown()
    }

    private companion object {
        const val MIN_PAUSE_MS = 100L
        const val LEVEL_INTERVAL_MS = 100L
        const val MAX_FAILURES = 5
    }
}
