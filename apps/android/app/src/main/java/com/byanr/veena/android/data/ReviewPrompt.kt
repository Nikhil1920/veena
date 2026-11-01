package com.byanr.veena.android.data

import java.time.LocalDate

/**
 * Usage behind the "Enjoying Veena?" card on the Dictate screen. The card
 * only appears for people who dictate regularly, at most [MAX_TIMES_SHOWN]
 * times, [DAYS_BETWEEN_PROMPTS] apart, and never again once they've gone to
 * the store to rate (the stores don't tell apps who has already reviewed).
 * Days are epoch days.
 */
data class ReviewPromptState(
    val dictations: Int,
    val activeDays: Int,
    val lastActiveDay: Long,
    val timesShown: Int,
    val lastShownDay: Long,
    val rated: Boolean,
) {
    fun isDue(today: Long): Boolean =
        !rated &&
            dictations >= MIN_DICTATIONS &&
            activeDays >= MIN_ACTIVE_DAYS &&
            timesShown < MAX_TIMES_SHOWN &&
            (lastShownDay == NEVER || today - lastShownDay >= DAYS_BETWEEN_PROMPTS)

    companion object {
        const val NEVER = -1L
        const val MIN_DICTATIONS = 10
        const val MIN_ACTIVE_DAYS = 3
        const val MAX_TIMES_SHOWN = 2
        const val DAYS_BETWEEN_PROMPTS = 60

        fun today(): Long = LocalDate.now().toEpochDay()
    }
}
