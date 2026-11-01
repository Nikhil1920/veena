package com.byanr.veena.android.ime

/**
 * "Veena voice input": the voice input method other keyboards' mic keys
 * switch to (SwiftKey, HeliBoard, FUTO Keyboard, AnySoftKeyboard... find it
 * through its "voice" subtype in `res/xml/method_voice.xml`). It is the
 * same panel as the Veena keyboard, but starts listening as soon as it opens
 * and returns to the calling keyboard after inserting the text. Gboard and
 * Samsung Keyboard only ever use their own voice typing.
 */
class VeenaVoiceInputService : VeenaImeService() {
    override val isVoiceInput: Boolean = true
}
