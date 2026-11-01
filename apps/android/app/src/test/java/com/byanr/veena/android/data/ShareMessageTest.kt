package com.byanr.veena.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ShareMessageTest {
    @Test
    fun everyLanguageHasAPitch() {
        LANGUAGES.forEach { assertNotNull("missing pitch for ${it.code}", sharePitch(it.code)) }
    }

    @Test
    fun messageLeadsWithTheLanguageThenEnglishThenLink() {
        val parts = shareMessage(languageByCode("te"), "https://example.com").split("\n\n")
        assertEquals(3, parts.size)
        assertEquals(sharePitch("te"), parts[0])
        assertEquals(true, parts[1].contains("Telugu"))
        assertEquals("https://example.com", parts[2])
    }

    @Test
    fun withoutALanguageTheMessageIsEnglishOnly() {
        val parts = shareMessage(null, "https://example.com").split("\n\n")
        assertEquals(2, parts.size)
    }
}
