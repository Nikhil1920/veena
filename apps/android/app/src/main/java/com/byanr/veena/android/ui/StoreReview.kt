package com.byanr.veena.android.ui

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import kotlinx.coroutines.CancellationException

/**
 * Shows Google Play's rating sheet over the app, so people can rate Veena
 * without leaving it. Play decides whether it appears: it limits how often,
 * skips people who have already reviewed, and only shows it in installs from
 * Google Play (internal app sharing shows it with Submit disabled). It never
 * says whether the sheet appeared, so this returns quietly either way.
 * Mirrors iOS's requestReview in DictateView.
 */
suspend fun Activity.requestPlayReview() {
    val manager = ReviewManagerFactory.create(this)
    try {
        manager.launchReview(this, manager.requestReview())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Not installed from Play, Play is out of date, or offline: carry on.
    }
}
