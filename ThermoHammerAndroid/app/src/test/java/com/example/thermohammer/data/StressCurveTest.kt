package com.example.thermohammer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StressCurveTest {

    @Test fun percentOfBaseline() {
        assertEquals(listOf(100.0, 50.0), StressCurve.percentOf(listOf(200L, 100L), 200.0))
        assertTrue(StressCurve.percentOf(listOf(200L), 0.0).isEmpty())
    }

    @Test fun shortCurvesAreOnlyRounded() {
        assertEquals(listOf(99.9, 80.0), StressCurve.downsample(listOf(99.94, 80.04)))
    }

    @Test fun longRunsBecome120EvenSlicesOfTime() {
        val thirtyMinutes = List(1800) { i -> if (i < 900) 100.0 else 60.0 }
        val c = StressCurve.downsample(thirtyMinutes)
        assertEquals(120, c.size)
        assertEquals(100.0, c.first(), 0.0)
        assertEquals(60.0, c.last(), 0.0)
        assertEquals(60, c.count { it == 100.0 })              // the drop lands exactly half way
    }

    @Test fun unevenLengthsStillGive120Points() {
        val c = StressCurve.downsample(List(301) { it.toDouble() })
        assertEquals(120, c.size)
        assertEquals(0.5, c.first(), 0.0)                      // mean of 0 and 1
        assertTrue(c.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun leaderboardCurveIsPercentOfTheFirstFiveSecondsMedian() {
        val ms = listOf(1000, 2000, 3000, 4000, 5000, 6000)
        val scores = listOf(100L, 110L, 90L, 100L, 50L, 50L)    // median of the first four: 100
        val c = StressCurve.fromScores(ms, scores)
        listOf(100.0, 110.0, 90.0, 100.0, 50.0, 50.0).forEachIndexed { i, v -> assertEquals(v, c[i], 1e-9) }
    }

    @Test fun leaderboardCurveWithoutEarlyStampsUsesTheFirstFive() {
        val c = StressCurve.fromScores(listOf(6000, 7000), listOf(200L, 100L))
        assertEquals(listOf(4 / 3.0 * 100, 2 / 3.0 * 100), c.map { it })
    }

    @Test fun onsetNeedsThreeStampsBelowNinety() {
        val ms = listOf(1000, 2000, 3000, 4000, 5000, 6000)
        assertNull(StressCurve.onsetSec(ms, listOf(100.0, 89.0, 89.0, 95.0, 89.0, 89.0)))
        assertEquals(4, StressCurve.onsetSec(ms, listOf(100.0, 95.0, 92.0, 89.0, 85.0, 80.0)))
    }

    @Test fun mmss() {
        assertEquals("1:23", StressCurve.mmss(83))
        assertEquals("0:05", StressCurve.mmss(5))
    }
}
