package com.example.thermohammer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun ask(lastAskedAt: Long = 0L, asks: Int = 0, verified: Boolean = true, complete: Boolean = true, at: Long = now) =
        ReviewPolicy.shouldAsk(at, lastAskedAt, asks, verified, complete)

    @Test fun asksAfterTheFirstGoodRun() = assertTrue(ask())

    @Test fun neverAfterAnUnverifiedRun() = assertFalse(ask(verified = false))

    @Test fun neverAfterARunWithAPartMissing() = assertFalse(ask(complete = false))

    @Test fun waitsOutTheGap() {
        assertFalse(ask(lastAskedAt = now - 1 * day, asks = 1))
        assertFalse(ask(lastAskedAt = now - 119 * day, asks = 1))
        assertFalse(ask(lastAskedAt = now - ReviewPolicy.MIN_GAP_MS + 1, asks = 1))
    }

    @Test fun asksAgainOnceTheGapIsOver() {
        assertTrue(ask(lastAskedAt = now - ReviewPolicy.MIN_GAP_MS, asks = 1))
        assertTrue(ask(lastAskedAt = now - 200 * day, asks = 2))
    }

    @Test fun stopsAfterThreeAsks() {
        assertFalse(ask(lastAskedAt = now - 400 * day, asks = ReviewPolicy.MAX_ASKS))
        assertFalse(ask(lastAskedAt = now - 400 * day, asks = ReviewPolicy.MAX_ASKS + 1))
    }

    @Test fun aClockSetBackCountsAsTooSoon() = assertFalse(ask(lastAskedAt = now + 5 * day, asks = 1))

    @Test fun theGapIsFourMonths() = assertTrue(ReviewPolicy.MIN_GAP_MS == 120 * day)
}
