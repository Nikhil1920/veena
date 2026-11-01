package com.byanr.veena.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromptStateTest {

    private val regular = ReviewPromptState(
        dictations = 10,
        activeDays = 3,
        lastActiveDay = 100,
        timesShown = 0,
        lastShownDay = ReviewPromptState.NEVER,
        rated = false,
    )

    @Test
    fun dueForRegularUsers() {
        assertTrue(regular.isDue(today = 100))
    }

    @Test
    fun notDueUntilUsedEnough() {
        assertFalse(regular.copy(dictations = 9).isDue(today = 100))
        assertFalse(regular.copy(activeDays = 2).isDue(today = 100))
    }

    @Test
    fun neverDueAfterRating() {
        assertFalse(regular.copy(rated = true).isDue(today = 100))
    }

    @Test
    fun waitsBetweenPromptsAndStopsAfterTheLast() {
        val shownOnce = regular.copy(timesShown = 1, lastShownDay = 100)
        assertFalse(shownOnce.isDue(today = 159))
        assertTrue(shownOnce.isDue(today = 160))
        assertFalse(regular.copy(timesShown = 2, lastShownDay = 0).isDue(today = 500))
    }
}
