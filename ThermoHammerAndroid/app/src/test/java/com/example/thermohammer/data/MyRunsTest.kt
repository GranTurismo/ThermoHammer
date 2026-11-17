package com.example.thermohammer.data

import com.example.thermohammer.data.MyRuns.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyRunsTest {

    private val phone = "HONOR VKP-NX9"
    private var clock = 0L

    private fun run(total: Int, model: String? = phone, flags: Int = 0, submitted: Boolean = false, version: Int = 4,
                    serverId: Int? = null) = BenchmarkRecord(
        id = "r${++clock}", timestamp = clock * 1000, version = version, score = total, test1Fps = 1.0, test1LowFps = 1.0,
        test1Points = 1, test2Fps = 1.0, test2LowFps = 1.0, test2Points = 1, gpuName = "Adreno", validityFlags = flags,
        submitted = submitted, serverId = serverId, deviceModel = model)

    private fun build(all: List<BenchmarkRecord>) = MyRuns.build(all, phone, version = 4)

    @Test fun noRuns() {
        val h = build(emptyList())
        assertTrue(h.rows.isEmpty())
        assertNull(h.best)
        assertNull(h.latest)
        assertEquals(listOf("Your runs are kept only on this phone: the last 100."), MyRuns.footnotes(h))
    }

    @Test fun newestFirstWithTheChangeFromTheRunBefore() {
        val a = run(30000); val b = run(33000); val c = run(31350)
        val h = build(listOf(a, b, c))
        assertEquals(listOf(c, b, a), h.rows.map { it.record })
        assertEquals(-5.0, h.rows[0].changePct!!, 1e-9)
        assertEquals(10.0, h.rows[1].changePct!!, 1e-9)
        assertNull(h.rows[2].changePct)
        assertEquals(listOf(a, b, c), h.chronological.map { it.record })
        assertEquals(c, h.latest)
    }

    @Test fun storedOutOfOrderStillListsByTime() {
        val a = run(30000); val b = run(31000)
        assertEquals(listOf(b, a), build(listOf(b, a)).rows.map { it.record })
    }

    @Test fun bestIsTheHighestAndTheEarlierOnATie() {
        val a = run(30000); val b = run(32000); val c = run(32000)
        val h = build(listOf(a, b, c))
        assertEquals(b, h.best)
        assertEquals(listOf(false, true, false), h.chronological.map { it.isBest })
    }

    @Test fun anUnverifiedRunCanBeTheBestButIsCountedApart() {
        val a = run(30000); val b = run(34000, flags = 4)
        val h = build(listOf(a, b))
        assertEquals(b, h.best)
        assertEquals(1, h.verified)
    }

    @Test fun statusAndWhatCanBeSubmitted() {
        val sent = run(30000, submitted = true, serverId = 12)
        val unsent = run(30000)
        val unverified = run(30000, flags = 1)
        val unverifiedButSent = run(30000, flags = 1, submitted = true)   // an older app's submit: what the server took
        val h = build(listOf(sent, unsent, unverified, unverifiedButSent)).chronological
        assertEquals(listOf(Status.SUBMITTED, Status.NOT_SUBMITTED, Status.UNVERIFIED, Status.SUBMITTED), h.map { it.status })
        assertEquals(listOf(false, true, false, false), h.map { it.canSubmit })
    }

    @Test fun olderVersionsAndOtherPhonesAreLeftOutAndCounted() {
        val old = run(9000, version = 3)
        val other = run(40000, model = "Samsung SM-S931B")
        val unnamed = run(30000, model = null)              // saved before 2.1: made on this phone
        val mine = run(31000)
        val h = build(listOf(old, other, unnamed, mine))
        assertEquals(listOf(mine, unnamed), h.rows.map { it.record })
        assertEquals(1, h.olderVersion)
        assertEquals(1, h.otherPhone)
        assertEquals(mine, h.best)                          // the other phone's 40,000 is not this phone's best
        assertEquals(listOf(
            "1 earlier run used an older version of the benchmark, so it isn't shown: its score doesn't compare.",
            "1 run was made on another phone (restored from a backup), so it isn't shown.",
            "Your runs are kept only on this phone: the last 100."), MyRuns.footnotes(h))
    }

    @Test fun pluralFootnotesAndThePcWording() {
        val h = build(listOf(run(1, version = 3), run(2, version = 2), run(3, model = "A"), run(4, model = "B")))
        assertEquals(listOf(
            "2 earlier runs used an older version of the benchmark, so they aren't shown: their scores don't compare.",
            "2 runs were made on another PC (restored from a backup), so they aren't shown.",
            "Your runs are kept only on this PC: the last 100."), MyRuns.footnotes(h, "PC"))
    }

    @Test fun aZeroScoreBeforeHasNoChange() {
        val h = build(listOf(run(0), run(30000)))
        assertNull(h.rows[0].changePct)
    }

    @Test fun changeText() {
        assertEquals("+2.1%", MyRuns.changeText(2.06))
        assertEquals("−4.0%", MyRuns.changeText(-3.96))
        assertEquals("±0.0%", MyRuns.changeText(0.04))
        assertEquals("±0.0%", MyRuns.changeText(-0.04))
    }

    @Test fun theStoreKeepsWhatTheScreenPromises() = assertEquals(100, MyRuns.KEPT)

    @Test fun isCompleteIsNotNeededToBeListed() = assertFalse(build(listOf(run(5000))).rows.isEmpty())
}
