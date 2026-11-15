package com.example.thermohammer.data

import android.content.Context

/**
 * When to ask for a store rating. The app asks as the result of a run that just finished is closed, and only after a
 * run worth being pleased with: verified, with every part scored. Google Play decides whether its rating card really
 * appears and tells the app nothing about it, so the limits are the app's own: a gap between asks and a cap on them.
 * The iOS app (ReviewPrompt.swift) and the Windows app (Engine/ReviewPolicy.cs) follow the same rule.
 */
object ReviewPolicy {
    const val MIN_GAP_MS = 120L * 24 * 60 * 60 * 1000
    const val MAX_ASKS = 3

    /**
     * [lastAskedAt] is 0 when the app never asked. A clock set back (now earlier than the last ask) counts as too soon.
     */
    fun shouldAsk(now: Long, lastAskedAt: Long, asks: Int, verified: Boolean, complete: Boolean): Boolean =
        verified && complete && asks < MAX_ASKS && (lastAskedAt <= 0L || now - lastAskedAt >= MIN_GAP_MS)
}

/** When the app last asked for a rating, and how many times so far. */
class ReviewPromptStore(context: Context) {
    private val prefs = context.getSharedPreferences("review_prompt", Context.MODE_PRIVATE)

    val lastAskedAt: Long get() = prefs.getLong(KEY_LAST, 0L)
    val asks: Int get() = prefs.getInt(KEY_ASKS, 0)

    fun recordAsk(now: Long) = prefs.edit().putLong(KEY_LAST, now).putInt(KEY_ASKS, asks + 1).apply()

    private companion object {
        const val KEY_LAST = "last_asked_at"
        const val KEY_ASKS = "asks"
    }
}
