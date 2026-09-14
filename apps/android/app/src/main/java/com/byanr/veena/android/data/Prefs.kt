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
)

class Prefs(private val context: Context) {

    private val languageKey = stringPreferencesKey("language_code")
    private val micModeKey = stringPreferencesKey("mic_mode")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            languageCode = prefs[languageKey],
            micMode = prefs[micModeKey]?.let { stored ->
                MicMode.entries.firstOrNull { it.name == stored }
            } ?: MicMode.HOLD,
        )
    }

    suspend fun setLanguage(code: String) {
        context.dataStore.edit { it[languageKey] = code }
    }

    suspend fun setMicMode(mode: MicMode) {
        context.dataStore.edit { it[micModeKey] = mode.name }
    }
}
