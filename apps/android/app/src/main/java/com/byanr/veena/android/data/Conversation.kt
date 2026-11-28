package com.byanr.veena.android.data

/** One entry in a conversation's transcript. */
sealed interface ConversationItem {
    val id: Long
    /** When it was added, in epoch milliseconds. */
    val time: Long
    val text: String
}

/** What someone said: one utterance, as transcribed once it ended. */
data class SpeechLine(override val id: Long, override val time: Long, override val text: String) :
    ConversationItem

/** A reply typed on the phone, shown large for the other person to read. */
data class TypedReply(override val id: Long, override val time: Long, override val text: String) :
    ConversationItem

object Conversations {
    /** A pause this long between items puts the time above the next one. */
    const val TIMESTAMP_GAP_MS = 2 * 60 * 1000L

    /** A conversation left this long is over: the next visit starts a new one. */
    const val STALE_AFTER_MS = 30 * 60 * 1000L

    /** Whether the time shows above an item added at [time] after [previous]. */
    fun showsTime(previous: ConversationItem?, time: Long): Boolean =
        previous == null || time - previous.time >= TIMESTAMP_GAP_MS

    /** Whether a speech line added at [time] after [previous] starts a run, under a label. */
    fun startsSpeechRun(previous: ConversationItem?, time: Long): Boolean =
        showsTime(previous, time) || previous is TypedReply

    /**
     * The conversation as plain text, for History, sharing and copying:
     * speech one utterance per line, typed replies in paragraphs of their
     * own, and the time above items that follow a pause of a few minutes.
     * The start time is left to whatever shows the text (History has it).
     */
    fun text(items: List<ConversationItem>, formatTime: (Long) -> String): String {
        val out = StringBuilder()
        var previous: ConversationItem? = null
        for (item in items) {
            val showsTime = showsTime(previous, item.time)
            if (out.isNotEmpty()) {
                val paragraph = showsTime || item is TypedReply || previous is TypedReply
                out.append(if (paragraph) "\n\n" else "\n")
            }
            if (showsTime && previous != null) out.append(formatTime(item.time)).append('\n')
            if (item is TypedReply) out.append("Typed response: ")
            out.append(item.text)
            previous = item
        }
        return out.toString()
    }
}
