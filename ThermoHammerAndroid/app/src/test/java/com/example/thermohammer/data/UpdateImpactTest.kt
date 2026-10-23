package com.example.thermohammer.data

import com.example.thermohammer.data.UpdateImpact.Insight
import com.example.thermohammer.data.UpdateImpact.Verdict
import com.example.thermohammer.engine.ValidityFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateImpactTest {

    private val phone = "Google Pixel 9 Pro"
    private var clock = 0L

    private fun run(
        capacity: Double,
        osBuild: String,
        osVersion: String = "16",
        model: String = phone,
        duration: Int = 0,
        threading: Int = 1,
        mode: Int = 0,
        flags: Int = 0,
        id: String = "r${++clock}",
        ts: Long = clock * 1000
    ) = RunRecord(
        id = id, timestamp = ts, deviceModel = model, osVersion = osVersion, osBuild = osBuild,
        securityPatch = "p-$osBuild", durationType = duration, threadingType = threading,
        stressMode = mode, capacity = capacity, validityFlags = flags
    )

    private fun insight(history: List<RunRecord>, build: String, version: String = "16", model: String = phone) =
        UpdateImpact.insight(history, model, version, build, "p-$build")

    // ── When there is nothing honest to say ──────────────────────────────────

    @Test fun noHistoryMeansNothingToSay() = assertNull(insight(emptyList(), "B2"))

    @Test fun runsOnlyOnTheCurrentOsGiveNoBaseline() {
        assertNull(insight(listOf(run(90.0, "B2"), run(91.0, "B2")), "B2"))
    }

    @Test fun onlyDirtyRunsBeforeTheUpdateGiveNoBaseline() {
        val dirty = run(70.0, "B1", flags = ValidityFlag.WARM_STARTED)
        assertNull(insight(listOf(dirty), "B2"))
    }

    @Test fun anotherPhonesRunsAreIgnored() {
        assertNull(insight(listOf(run(90.0, "B1", model = "Samsung Galaxy S25")), "B2"))
    }

    // ── The prompt: updated, not tested since ────────────────────────────────

    @Test fun updatedAndNotRetestedGivesAPromptPointingAtTheLastCleanRun() {
        val older = run(80.0, "B0")                       // created first → older timestamp
        val last = run(91.0, "B1", duration = 1, threading = 0)
        val r = insight(listOf(older, last), "B2")
        assertTrue(r is Insight.Prompt)
        r as Insight.Prompt
        assertEquals(last.id, r.last.id)          // the settings to repeat come from this run
        assertEquals("B2", r.nowOsBuild)
        assertEquals("p-B2", r.nowPatch)
    }

    @Test fun aDirtyRunAfterTheUpdateStillLeavesThePromptUp() {
        val h = listOf(run(91.0, "B1"), run(60.0, "B2", flags = ValidityFlag.POWER_EVENT))
        assertTrue(insight(h, "B2") is Insight.Prompt)
    }

    @Test fun aRunWithDifferentSettingsAfterTheUpdateDoesNotAnswerTheQuestion() {
        val h = listOf(run(91.0, "B1", duration = 0), run(85.0, "B2", duration = 2))
        val r = insight(h, "B2")
        assertTrue(r is Insight.Prompt)
        assertEquals(0, (r as Insight.Prompt).last.durationType)
    }

    // ── The result ───────────────────────────────────────────────────────────

    @Test fun clearDropIsReportedAsSlower() {
        val r = insight(listOf(run(91.0, "B1"), run(85.0, "B2")), "B2") as Insight.Result
        assertEquals(-6.0, r.deltaPts, 1e-9)
        assertEquals(Verdict.SLOWER, r.verdict)
    }

    @Test fun clearGainIsReportedAsFaster() {
        val r = insight(listOf(run(80.0, "B1"), run(87.0, "B2")), "B2") as Insight.Result
        assertEquals(Verdict.FASTER, r.verdict)
    }

    @Test fun smallDifferenceIsNotCalledAChange() {
        val r = insight(listOf(run(91.0, "B1"), run(89.0, "B2")), "B2") as Insight.Result
        assertEquals(-2.0, r.deltaPts, 1e-9)
        assertEquals(Verdict.NO_CLEAR_CHANGE, r.verdict)
    }

    @Test fun exactlyAtTheNoiseFloorCounts() {
        val r = insight(listOf(run(91.0, "B1"), run(88.0, "B2")), "B2") as Insight.Result
        assertEquals(Verdict.SLOWER, r.verdict)       // −3.0 meets the 3.0 floor
    }

    @Test fun noisyRunsRaiseTheBarSoAnApparentDropIsNotOverclaimed() {
        // before runs swing 84..91 (spread 7): a 5-point drop is inside that noise
        val h = listOf(run(90.0, "B1"), run(84.0, "B1"), run(91.0, "B1"), run(85.0, "B2"))
        val r = insight(h, "B2") as Insight.Result
        assertEquals(7.0, r.thresholdPts, 1e-9)
        assertEquals(Verdict.NO_CLEAR_CHANGE, r.verdict)
    }

    @Test fun medianIsUsedSoOneOutlierDoesNotDecideTheVerdict() {
        val h = listOf(run(90.0, "B1"), run(91.0, "B1"), run(60.0, "B1"),   // median 90
                       run(89.0, "B2"), run(90.0, "B2"), run(88.0, "B2"))  // median 89
        val r = insight(h, "B2") as Insight.Result
        assertEquals(90.0, r.before.median, 1e-9)
        assertEquals(89.0, r.after.median, 1e-9)
    }

    @Test fun onlyTheNewestThreeRunsPerSideAreUsed() {
        val h = (1..5).map { run(50.0 + it, "B1") } + (1..5).map { run(80.0 + it, "B2") }
        val r = insight(h, "B2") as Insight.Result
        assertEquals(3, r.before.runs.size)
        assertEquals(3, r.after.runs.size)
        assertEquals(55.0, r.before.latest.capacity, 1e-9)    // newest first
    }

    @Test fun oneRunPerSideIsMarkedWeak_twoEachIsNot() {
        val weak = insight(listOf(run(91.0, "B1"), run(85.0, "B2")), "B2") as Insight.Result
        assertTrue(weak.weak)
        val ok = insight(listOf(run(91.0, "B1"), run(90.0, "B1"), run(85.0, "B2"), run(86.0, "B2")), "B2") as Insight.Result
        assertFalse(ok.weak)
    }

    @Test fun settingsAreMatchedLikeForLike() {
        // A 15-min run before must not be compared with a 5-min run after.
        val h = listOf(run(70.0, "B1", duration = 1), run(91.0, "B1", duration = 0),
                       run(90.0, "B2", duration = 0))
        val r = insight(h, "B2") as Insight.Result
        assertEquals(91.0, r.before.median, 1e-9)
    }

    @Test fun gpuAndCpuRunsAreNeverMixed() {
        val h = listOf(run(60.0, "B1", mode = 1), run(91.0, "B1", mode = 0), run(90.0, "B2", mode = 0))
        val r = insight(h, "B2") as Insight.Result
        assertEquals(91.0, r.before.median, 1e-9)
    }

    @Test fun platformNoClockDataFlagAloneDoesNotDisqualifyARun() {
        val f = ValidityFlag.FREQ_MISSING
        val r = insight(listOf(run(91.0, "B1", flags = f), run(85.0, "B2", flags = f)), "B2")
        assertTrue(r is Insight.Result)
    }

    @Test fun anyOtherFlagDisqualifiesARun() {
        val bad = ValidityFlag.FREQ_MISSING or ValidityFlag.ENV_CAPPED
        assertFalse(UpdateImpact.isComparable(run(90.0, "B1", flags = bad)))
        assertTrue(UpdateImpact.isComparable(run(90.0, "B1", flags = ValidityFlag.FREQ_MISSING)))
    }

    // ── Several updates in a row ─────────────────────────────────────────────

    @Test fun afterTwoUpdatesTheComparisonIsAgainstTheMostRecentEarlierOs() {
        val h = listOf(run(95.0, "B0"), run(88.0, "B1"), run(87.0, "B2"))
        val r = insight(h, "B2") as Insight.Result
        assertEquals(88.0, r.before.median, 1e-9)      // B1, not B0
        assertTrue(r.key.endsWith("->16|B2"))
    }

    @Test fun skippingAVersionStillFindsTheLastTestedOne() {
        val h = listOf(run(95.0, "B0"), run(80.0, "B3"))   // never tested on B1/B2
        val r = insight(h, "B3") as Insight.Result
        assertEquals(95.0, r.before.median, 1e-9)
    }

    // ── What counts as "an update" ───────────────────────────────────────────

    @Test fun aNewBuildOfTheSameAndroidReleaseIsAnUpdate() {
        val h = listOf(run(91.0, "BP4A.251205.006"))
        assertTrue(insight(h, "BP4A.260105.004") is Insight.Prompt)
    }

    @Test fun theSameBuildIsNotAnUpdate() {
        assertNull(insight(listOf(run(91.0, "B1")), "B1"))
    }

    @Test fun aNewReleaseNumberIsAnUpdateEvenIfBuildIsUnknown() {
        val old = run(91.0, "").copy(osBuild = null, osVersion = "15")
        assertTrue(UpdateImpact.insight(listOf(old), phone, "16", null) is Insight.Prompt)
    }

    // ── Dismissal & history upkeep ───────────────────────────────────────────

    @Test fun dismissingThePromptDoesNotHideTheLaterResult() {
        val prompt = insight(listOf(run(91.0, "B1")), "B2")!!
        val result = insight(listOf(run(91.0, "B1"), run(85.0, "B2")), "B2")!!
        assertEquals(prompt.key, result.key)                 // same transition…
        assertNotEquals(prompt.dismissKey, result.dismissKey) // …but dismissed separately
    }

    @Test fun historyIsCappedKeepingTheNewestRuns() {
        var h = emptyList<RunRecord>()
        for (i in 1..50) h = UpdateImpact.appendCapped(h, run(i.toDouble(), "B1"), max = 40)
        assertEquals(40, h.size)
        assertEquals(11.0, h.first().capacity, 1e-9)
        assertEquals(50.0, h.last().capacity, 1e-9)
    }

    @Test fun savingTheSameRunTwiceDoesNotDuplicateIt() {
        val r = run(90.0, "B1", id = "same")
        val h = UpdateImpact.appendCapped(UpdateImpact.appendCapped(emptyList(), r), r)
        assertEquals(1, h.size)
    }
}
