package com.byanr.veena.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationsTest {

    private val minute = 60_000L
    private fun time(at: Long) = "t${at / minute}"

    @Test
    fun textGroupsSpeechAndSetsRepliesApart() {
        val items = listOf(
            SpeechLine(1, 0, "Hello."),
            SpeechLine(2, 10_000, "I am speaking."),
            TypedReply(3, 20_000, "And I am typing"),
            SpeechLine(4, 30_000, "So this is being transcribed."),
            SpeechLine(5, 40_000, "Okay."),
        )
        assertEquals(
            "Hello.\nI am speaking.\n\nTyped response: And I am typing\n\n" +
                "So this is being transcribed.\nOkay.",
            Conversations.text(items, ::time),
        )
    }

    @Test
    fun aLongPauseShowsTheTimeAgain() {
        val items = listOf(
            SpeechLine(1, 0, "Before."),
            SpeechLine(2, 2 * minute - 1, "Still the same minute."),
            SpeechLine(3, 5 * minute, "After."),
        )
        assertEquals(
            "Before.\nStill the same minute.\n\nt5\nAfter.",
            Conversations.text(items, ::time),
        )
    }

    @Test
    fun emptyConversationIsEmptyText() {
        assertEquals("", Conversations.text(emptyList(), ::time))
    }

    @Test
    fun speechRunsStartAfterRepliesAndPauses() {
        val speech = SpeechLine(1, 0, "a")
        val reply = TypedReply(2, 0, "b")
        assertTrue(Conversations.startsSpeechRun(null, 0))
        assertFalse(Conversations.startsSpeechRun(speech, minute))
        assertTrue(Conversations.startsSpeechRun(reply, 1))
        assertTrue(Conversations.startsSpeechRun(speech, 3 * minute))
    }
}
