package com.byanr.veena.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromptStateTest {

    private val regular = ReviewPromptState(
        dictations = 10,
        activeDays = 3,
        lastActiveDay = 100,
        lastRequestedDay = ReviewPromptState.NEVER,
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
    fun waitsBetweenRequests() {
        val asked = regular.copy(lastRequestedDay = 100)
        assertFalse(asked.isDue(today = 100))
        assertFalse(asked.isDue(today = 159))
        assertTrue(asked.isDue(today = 160))
    }
}
