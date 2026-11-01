package com.byanr.veena.android.ime

import android.text.InputType

/**
 * True when [inputType] (an `EditorInfo.inputType` value) is a password-style
 * field: text, visible-text, web or number password. The keyboard disables
 * the mic for these — dictation must never see their contents.
 *
 * Variations only mean something within their class: the number-password
 * bits equal the text URI bits, so a URL field must not read as a password.
 */
fun isSensitiveField(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT ->
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}
