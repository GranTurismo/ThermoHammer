package com.example.thermohammer.ui.review

import android.app.Activity
import android.util.Log
import com.example.thermohammer.data.ReviewPolicy
import com.example.thermohammer.data.ReviewPromptStore
import com.google.android.play.core.review.ReviewManagerFactory

/** Google Play's in-app rating card, asked for when [ReviewPolicy] allows it. */
object ReviewPrompt {
    private const val TAG = "ReviewPrompt"

    /** Call as the result of a run that just finished is closed: the run is over and nothing is interrupted. */
    fun onResultClosed(activity: Activity?, verified: Boolean, complete: Boolean) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        val store = ReviewPromptStore(activity)
        val now = System.currentTimeMillis()
        if (!ReviewPolicy.shouldAsk(now, store.lastAskedAt, store.asks, verified, complete)) {
            Log.d(TAG, "not asking: verified=$verified complete=$complete asks=${store.asks} lastAskedAt=${store.lastAskedAt}")
            return
        }
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) {
                // no Play Store on the phone, or a build that did not come from it: nothing was asked, so nothing is counted
                Log.i(TAG, "rating card unavailable: ${request.exception}")
                return@addOnCompleteListener
            }
            if (activity.isFinishing || activity.isDestroyed) return@addOnCompleteListener
            store.recordAsk(now)
            // Play does not say whether the card was shown or what the user did with it
            manager.launchReviewFlow(activity, request.result).addOnCompleteListener { Log.i(TAG, "rating flow finished") }
        }
    }
}
