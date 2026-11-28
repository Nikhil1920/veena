package com.byanr.veena.android.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What changed in a conversation since the last step (see `conversation.rs`). */
data class CaptionUpdate(
    /** Final text of each utterance that ended, oldest first. */
    val finished: List<String>,
    /** The utterance still being spoken; its words can still change. */
    val partial: String,
    /** Whether someone is speaking. */
    val speaking: Boolean,
) {
    companion object {
        fun parse(json: String): CaptionUpdate {
            val obj = JSONObject(json)
            val finished = obj.getJSONArray("finished")
            return CaptionUpdate(
                finished = List(finished.length()) { finished.getString(it) },
                partial = obj.getString("partial"),
                speaking = obj.getBoolean("speaking"),
            )
        }
    }
}

/**
 * The engine's captions for one conversation in one language: one line per
 * utterance, for as long as audio keeps coming. Takes one call at a time;
 * [EngineManager.openConversation] creates it.
 */
class ConversationCaptions internal constructor(
    private val conversation: Long,
    private val release: suspend () -> Unit,
) {
    private var closed = false

    /**
     * Adds [audio] (16 kHz mono) and decodes what is due: the final text of
     * utterances that ended, and a preview of the one in progress.
     */
    suspend fun step(audio: FloatArray): CaptionUpdate = call(audio, RustBackend::conversationStep)

    /** Adds [audio] and ends the utterance in progress, for a pause or a stop. */
    suspend fun flush(audio: FloatArray): CaptionUpdate = call(audio, RustBackend::conversationFlush)

    private suspend fun call(audio: FloatArray, update: (Long) -> String?): CaptionUpdate =
        withContext(Dispatchers.Default) {
            check(!closed) { "Conversation already closed" }
            if (audio.isNotEmpty()) RustBackend.conversationPush(conversation, audio)
            val json = update(conversation)
                ?: throw IllegalStateException("Transcription failed (see logcat)")
            CaptionUpdate.parse(json)
        }

    suspend fun close() {
        if (closed) return
        closed = true
        RustBackend.conversationDestroy(conversation)
        release()
    }
}
