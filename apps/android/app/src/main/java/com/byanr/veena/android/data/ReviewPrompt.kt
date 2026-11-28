package com.byanr.veena.android.data

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.time.LocalDate

/**
 * When to ask Google Play for its in-app rating sheet: only once someone
 * dictates regularly, and at most every [DAYS_BETWEEN_REQUESTS] days. Play
 * decides whether the sheet actually appears (it limits how often, and skips
 * people who have already reviewed) and never tells the app, so asking is
 * all Veena can count. Days are epoch days. Mirrors iOS ReviewPrompt.
 */
data class ReviewPromptState(
    val dictations: Int,
    val activeDays: Int,
    val lastActiveDay: Long,
    val lastRequestedDay: Long,
) {
    fun isDue(today: Long): Boolean =
        dictations >= MIN_DICTATIONS &&
            activeDays >= MIN_ACTIVE_DAYS &&
            (lastRequestedDay == NEVER || today - lastRequestedDay >= DAYS_BETWEEN_REQUESTS)

    companion object {
        const val NEVER = -1L
        const val MIN_DICTATIONS = 10
        const val MIN_ACTIVE_DAYS = 3
        const val DAYS_BETWEEN_REQUESTS = 60

        fun today(): Long = LocalDate.now().toEpochDay()

        /**
         * Test hook for debuggable builds only: while `files/review-prompt-test`
         * exists, every dictation in the app asks for a review.
         */
        fun testHookEnabled(context: Context): Boolean =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                File(context.filesDir, "review-prompt-test").exists()
    }
}
