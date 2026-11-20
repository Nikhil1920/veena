package com.byanr.veena.android.ime

import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class ComposingPreviewTest {

    /** A text field reduced to what [ComposingPreview] uses: text before the cursor. */
    private class Field(var committed: String = "") {
        var composing = ""
        val text get() = committed + composing

        val connection: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "setComposingText" -> true.also { composing = args[0].toString() }
                "commitText" -> true.also {
                    committed += args[0].toString()
                    composing = ""
                }
                "finishComposingText" -> true.also { restartInput() }
                "getTextBeforeCursor" -> text.takeLast(args[0] as Int)
                "deleteSurroundingText" -> true.also {
                    committed = committed.dropLast(args[0] as Int)
                }
                else -> error("unexpected ${method.name}")
            }
        } as InputConnection

        /** What the field does to composing text when input restarts. */
        fun restartInput() {
            committed += composing
            composing = ""
        }
    }

    @Test
    fun eachPreviewReplacesTheOneBefore() {
        val field = Field("Hello ")
        val preview = ComposingPreview()
        preview.show(field.connection, "నమ")
        preview.show(field.connection, "నమస్తే")
        assertEquals("Hello నమస్తే", field.text)
    }

    @Test
    fun theTranscriptReplacesThePreview() {
        val field = Field("Hello ")
        val preview = ComposingPreview()
        preview.show(field.connection, "నమస్త")
        preview.commit(field.connection, "నమస్తే ")
        assertEquals("Hello నమస్తే ", field.text)
        assertEquals("", field.composing)
    }

    @Test
    fun aTranscriptWithoutAPreviewIsJustInserted() {
        val field = Field("Hello ")
        ComposingPreview().commit(field.connection, "నమస్తే ")
        assertEquals("Hello నమస్తే ", field.text)
    }

    @Test
    fun clearingTakesThePreviewBackOut() {
        val field = Field("Hello ")
        val preview = ComposingPreview()
        preview.show(field.connection, "నమస్తే")
        preview.clear(field.connection)
        assertEquals("Hello ", field.text)

        // Nothing shown: the field is left alone.
        preview.clear(field.connection)
        assertEquals("Hello ", field.text)
    }

    @Test
    fun aPreviewLeftBehindByARestartIsStillReplaced() {
        val field = Field("Hello ")
        val preview = ComposingPreview()
        preview.show(field.connection, "నమ")
        field.restartInput()
        preview.onInputRestarted()

        preview.show(field.connection, "నమస్తే")
        assertEquals("Hello నమస్తే", field.text)

        field.restartInput()
        preview.onInputRestarted()
        preview.commit(field.connection, "నమస్తే అందరికీ ")
        assertEquals("Hello నమస్తే అందరికీ ", field.text)
    }

    @Test
    fun aRestartThatChangedTheTextIsLeftAlone() {
        val field = Field("Hello ")
        val preview = ComposingPreview()
        preview.show(field.connection, "నమ")
        field.restartInput()
        field.committed = "Search results"
        preview.onInputRestarted()

        preview.clear(field.connection)
        assertEquals("Search results", field.text)
    }

    @Test
    fun aNewFieldStartsWithoutAPreview() {
        val first = Field()
        val preview = ComposingPreview()
        preview.show(first.connection, "నమ")
        preview.onInputFinished()

        val second = Field("Hello ")
        preview.clear(second.connection)
        assertEquals("Hello ", second.text)
    }
}
