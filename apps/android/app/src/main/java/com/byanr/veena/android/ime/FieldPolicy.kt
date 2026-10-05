package com.byanr.veena.android.ime

import android.text.InputType

/**
 * True when [inputType] (an `EditorInfo.inputType` value) is a password-style
 * field: text, visible-text, web or number password. The keyboard disables
 * the mic for these — dictation must never see their contents.
 */
fun isSensitiveField(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return variation == (InputType.TYPE_TEXT_VARIATION_PASSWORD and InputType.TYPE_MASK_VARIATION) ||
        variation == (InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD and InputType.TYPE_MASK_VARIATION) ||
        variation == (InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD and InputType.TYPE_MASK_VARIATION) ||
        variation == (InputType.TYPE_NUMBER_VARIATION_PASSWORD and InputType.TYPE_MASK_VARIATION)
}
