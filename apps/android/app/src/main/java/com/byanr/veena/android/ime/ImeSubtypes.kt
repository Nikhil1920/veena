package com.byanr.veena.android.ime

import android.content.ComponentName
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.byanr.veena.android.VeenaApplication
import com.byanr.veena.android.data.LANGUAGES
import com.byanr.veena.android.data.ModelCatalog

/**
 * Registers one system subtype per downloaded language so the keyboard
 * switcher only offers languages the device can actually decode.
 */
object ImeSubtypes {

    fun sync(context: Context): List<String> {
        val models = (context.applicationContext as VeenaApplication).container.models
        val downloaded = ModelCatalog.ALL.filter { models.isDownloaded(it) }
        val codes = LANGUAGES.map { it.code }.filter { code ->
            downloaded.any { it == ModelCatalog.modelForLanguage(code) }
        }

        val subtypes = codes.map { code ->
            InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeLocale(code)
                .setIsAuxiliary(false)
                .build()
        }.toTypedArray()

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // IMMS keys IMEs by the short form ("pkg/.relative.Class"); the long
        // flattenToString never matches and the registration is dropped.
        val imiId = ComponentName(context, VeenaImeService::class.java).flattenToShortString()
        imm.setAdditionalInputMethodSubtypes(imiId, subtypes)
        return codes
    }
}
