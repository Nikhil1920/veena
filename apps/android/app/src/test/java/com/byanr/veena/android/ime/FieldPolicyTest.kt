package com.byanr.veena.android.ime

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {

    @Test
    fun textPasswordIsSensitive() {
        assertTrue(
            isSensitiveField(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
        )
    }

    @Test
    fun visiblePasswordIsSensitive() {
        assertTrue(
            isSensitiveField(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )
        )
    }

    @Test
    fun webPasswordIsSensitive() {
        assertTrue(
            isSensitiveField(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        )
    }

    @Test
    fun numberPasswordIsSensitive() {
        assertTrue(
            isSensitiveField(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            )
        )
    }

    @Test
    fun plainTextIsNotSensitive() {
        assertFalse(
            isSensitiveField(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            )
        )
    }

    @Test
    fun emailFieldIsNotSensitive() {
        assertFalse(
            isSensitiveField(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
        )
    }

    @Test
    fun multiLineFieldIsNotSensitive() {
        assertFalse(
            isSensitiveField(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            )
        )
    }

    @Test
    fun plainNumberFieldIsNotSensitive() {
        assertFalse(
            isSensitiveField(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL
            )
        )
    }

    @Test
    fun phoneFieldIsNotSensitive() {
        assertFalse(isSensitiveField(InputType.TYPE_CLASS_PHONE))
    }
}
