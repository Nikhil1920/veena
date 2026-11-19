package com.byanr.veena.android.ime

import android.view.inputmethod.InputConnection

/**
 * A dictation's live text in the focused field. It is kept as the field's
 * composing text, so each update, and finally the transcript, replaces the
 * one before in place.
 */
class ComposingPreview {

    /** The preview the field holds, if any. */
    private var shown: String? = null

    /**
     * The field restarted input and with it dropped the composing state:
     * [shown] now sits before the cursor as ordinary text.
     */
    private var detached = false

    fun show(connection: InputConnection, text: String) {
        if (text == shown && !detached) return
        reclaim(connection)
        connection.setComposingText(text, 1)
        shown = text
    }

    /** Replaces the preview, if there is one, with the final [text]. */
    fun commit(connection: InputConnection, text: String) {
        reclaim(connection)
        connection.commitText(text, 1)
        connection.finishComposingText()
        shown = null
    }

    /** Takes the preview back out of the field. */
    fun clear(connection: InputConnection) {
        if (shown == null) return
        reclaim(connection)
        connection.commitText("", 1)
        shown = null
    }

    fun onInputRestarted() {
        if (shown != null) detached = true
    }

    /** The preview stays behind in a field input has moved away from. */
    fun onInputFinished() {
        shown = null
        detached = false
    }

    /**
     * Deletes a detached preview, so what is written next replaces it rather
     * than follows it. If the field has changed the text meanwhile, it is
     * left alone.
     */
    private fun reclaim(connection: InputConnection) {
        if (!detached) return
        detached = false
        val old = shown.orEmpty()
        if (old.isNotEmpty() && connection.getTextBeforeCursor(old.length, 0)?.toString() == old) {
            connection.deleteSurroundingText(old.length, 0)
        }
    }
}
