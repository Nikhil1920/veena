package com.byanr.veena.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "veena_prefs")

enum class MicMode { HOLD, TAP }

data class AppSettings(
    val languageCode: String?,
    val micMode: MicMode,
    /** Null means the keyboard falls back to [AppSettings.languageCode]. */
    val imeLanguageCode: String?,
    val imeMicMode: MicMode,
    /** The Dictate screen's "set up the voice keyboard" card was dismissed. */
    val keyboardBannerDismissed: Boolean,
)

class Prefs(private val context: Context) {

    private val languageKey = stringPreferencesKey("language_code")
    private val micModeKey = stringPreferencesKey("mic_mode")
    private val imeLanguageKey = stringPreferencesKey("ime_language_code")
    private val imeMicModeKey = stringPreferencesKey("ime_mic_mode")
    private val keyboardBannerDismissedKey = booleanPreferencesKey("keyboard_banner_dismissed")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            languageCode = prefs[languageKey],
            micMode = prefs[micModeKey]?.let { stored ->
                MicMode.entries.firstOrNull { it.name == stored }
            } ?: MicMode.HOLD,
            imeLanguageCode = prefs[imeLanguageKey],
            imeMicMode = prefs[imeMicModeKey]?.let { stored ->
                MicMode.entries.firstOrNull { it.name == stored }
            } ?: MicMode.HOLD,
            keyboardBannerDismissed = prefs[keyboardBannerDismissedKey] ?: false,
        )
    }

    suspend fun setLanguage(code: String) {
        context.dataStore.edit { it[languageKey] = code }
    }

    suspend fun setMicMode(mode: MicMode) {
        context.dataStore.edit { it[micModeKey] = mode.name }
    }

    suspend fun setImeLanguage(code: String) {
        context.dataStore.edit { it[imeLanguageKey] = code }
    }

    suspend fun setImeMicMode(mode: MicMode) {
        context.dataStore.edit { it[imeMicModeKey] = mode.name }
    }

    suspend fun dismissKeyboardBanner() {
        context.dataStore.edit { it[keyboardBannerDismissedKey] = true }
    }
}
