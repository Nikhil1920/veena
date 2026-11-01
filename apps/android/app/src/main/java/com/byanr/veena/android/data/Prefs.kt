package com.byanr.veena.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "veena_prefs")

enum class MicMode { HOLD, TAP }

data class AppSettings(
    val languageCode: String?,
    /** The mic button's mode, shared by the app and the voice keyboard. */
    val micMode: MicMode,
    /** Null means the keyboard falls back to [AppSettings.languageCode]. */
    val imeLanguageCode: String?,
    /** The Dictate screen's "set up the voice keyboard" card was dismissed. */
    val keyboardBannerDismissed: Boolean,
)

class Prefs(private val context: Context) {

    private val languageKey = stringPreferencesKey("language_code")
    private val micModeKey = stringPreferencesKey("mic_mode")
    private val imeLanguageKey = stringPreferencesKey("ime_language_code")
    private val keyboardBannerDismissedKey = booleanPreferencesKey("keyboard_banner_dismissed")
    private val reviewDictationsKey = intPreferencesKey("review_dictations")
    private val reviewActiveDaysKey = intPreferencesKey("review_active_days")
    private val reviewLastActiveDayKey = longPreferencesKey("review_last_active_day")
    private val reviewTimesShownKey = intPreferencesKey("review_times_shown")
    private val reviewLastShownDayKey = longPreferencesKey("review_last_shown_day")
    private val reviewRatedKey = booleanPreferencesKey("review_rated")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            languageCode = prefs[languageKey],
            micMode = prefs[micModeKey]?.let { stored ->
                MicMode.entries.firstOrNull { it.name == stored }
            } ?: MicMode.HOLD,
            imeLanguageCode = prefs[imeLanguageKey],
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

    suspend fun dismissKeyboardBanner() {
        context.dataStore.edit { it[keyboardBannerDismissedKey] = true }
    }

    val reviewPrompt: Flow<ReviewPromptState> = context.dataStore.data.map { prefs ->
        ReviewPromptState(
            dictations = prefs[reviewDictationsKey] ?: 0,
            activeDays = prefs[reviewActiveDaysKey] ?: 0,
            lastActiveDay = prefs[reviewLastActiveDayKey] ?: ReviewPromptState.NEVER,
            timesShown = prefs[reviewTimesShownKey] ?: 0,
            lastShownDay = prefs[reviewLastShownDayKey] ?: ReviewPromptState.NEVER,
            rated = prefs[reviewRatedKey] ?: false,
        )
    }

    /** A dictation (app, import, or voice keyboard) produced text on [today] (an epoch day). */
    suspend fun recordDictation(today: Long) {
        context.dataStore.edit {
            it[reviewDictationsKey] = (it[reviewDictationsKey] ?: 0) + 1
            if (it[reviewLastActiveDayKey] != today) {
                it[reviewActiveDaysKey] = (it[reviewActiveDaysKey] ?: 0) + 1
                it[reviewLastActiveDayKey] = today
            }
        }
    }

    suspend fun markReviewPromptShown(today: Long) {
        context.dataStore.edit {
            it[reviewTimesShownKey] = (it[reviewTimesShownKey] ?: 0) + 1
            it[reviewLastShownDayKey] = today
        }
    }

    /** The user went to the store to rate; the review prompt never comes back. */
    suspend fun markRated() {
        context.dataStore.edit { it[reviewRatedKey] = true }
    }
}
