package com.example.thermohammer.data

import com.example.thermohammer.data.BenchmarkUpdateImpact.Insight
import com.example.thermohammer.data.BenchmarkUpdateImpact.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BenchmarkUpdateImpactTest {

    private val phone = "HONOR VKP-NX9"
    private var clock = 0L

    /** A verified, complete run scoring [total] (CPU [cpu], the rest split evenly). */
    private fun run(total: Int, version: String, build: String?, model: String? = phone, flags: Int = 0, cpu: Int = 6000,
                    llm: Int = 6000) = BenchmarkRecord(
        id = "r${++clock}", timestamp = clock * 1000, version = 4, score = total, test1Fps = 1.0, test1LowFps = 1.0, test1Points = 1,
        test2Fps = 1.0, test2LowFps = 1.0, test2Points = 1, cpuScore = cpu, memScore = 6000, gpuScore = 6000, uxScore = 6000,
        aiScore = 6000, llmScore = llm, gpuName = "Adreno", validityFlags = flags, osVersion = version, osBuild = build,
        deviceModel = model)

    private fun insight(h: List<BenchmarkRecord>, version: String = "16", build: String? = "B2", model: String = phone) =
        BenchmarkUpdateImpact.insight(h, model, version, build)

    // ── nothing to say ──

    @Test fun noHistory() = assertNull(insight(emptyList()))

    @Test fun onlyRunsOnTheCurrentOs() = assertNull(insight(listOf(run(30000, "16", "B2"), run(30500, "16", "B2"))))

    @Test fun unverifiedOrIncompleteRunsAreNoEvidence() {
        assertNull(insight(listOf(run(30000, "15", "B1", flags = 4))))
        assertNull(insight(listOf(run(30000, "15", "B1", llm = 0))))
    }

    @Test fun anotherPhonesRunsAreIgnoredButUnnamedOldRunsCount() {
        assertNull(insight(listOf(run(30000, "15", "B1", model = "Samsung SM-S931B"))))
        assertTrue(insight(listOf(run(30000, "15", "B1", model = null))) is Insight.Prompt)
    }

    @Test fun aMissingBuildIsTheSameOsWhenTheVersionMatches() {
        // saved before builds were kept: no prompt from missing data
        assertNull(insight(listOf(run(30000, "16", null))))
        assertNull(insight(listOf(run(30000, "16", "B2")), build = null))
    }

    // ── the prompt ──

    @Test fun updatedAndNotRunSinceAsksForARun() {
        val older = run(30000, "14", "B0")                   // made first → older timestamp
        val last = run(31000, "15", "B1")
        val p = insight(listOf(older, last)) as Insight.Prompt
        assertEquals(last, p.last)
        assertEquals("15", p.before.version)
        assertEquals("Android 15 → 16. Run the benchmark again to see if the update changed your phone.", BenchmarkUpdateImpact.promptText(p))
        assertEquals("prompt:15|B1->16|B2", p.dismissKey)
    }

    @Test fun aMonthlyUpdateNamesTheBuilds() {
        val p = insight(listOf(run(31000, "16", "B1"))) as Insight.Prompt
        assertEquals("Android 16 (B1) → 16 (B2). Run the benchmark again to see if the update changed your phone.",
            BenchmarkUpdateImpact.promptText(p))
    }

    // ── the comparison ──

    @Test fun mediansOfUpToThreeNewestRunsASide() {
        val h = listOf(run(10000, "15", "B1"),                              // the 4th newest before: left out
            run(30000, "15", "B1"), run(31000, "15", "B1"), run(32000, "15", "B1"),
            run(28000, "16", "B2"), run(29000, "16", "B2"))
        val r = insight(h) as Insight.Result
        assertEquals(31000.0, r.before.median, 0.0)
        assertEquals(28500.0, r.after.median, 0.0)
        assertEquals(28500.0 / 31000 - 1, r.delta, 1e-12)
        assertEquals(Verdict.SLOWER, r.verdict)
        assertEquals("8.1% slower than before the update", BenchmarkUpdateImpact.resultText(r, Locale.US))
        assertEquals(false, r.weak)
    }

    @Test fun underThreePercentIsNoClearChange() {
        val r = insight(listOf(run(30000, "15", "B1"), run(29200, "16", "B2"))) as Insight.Result
        assertEquals(Verdict.NO_CLEAR_CHANGE, r.verdict)
        assertNull(BenchmarkUpdateImpact.resultText(r))
        assertTrue(r.weak)
    }

    @Test fun overThreePercentFasterIsFaster() {
        val r = insight(listOf(run(30000, "15", "B1"), run(31000, "16", "B2"))) as Insight.Result
        assertEquals(Verdict.FASTER, r.verdict)
        assertEquals("3.3% faster than before the update", BenchmarkUpdateImpact.resultText(r, Locale.US))
    }

    @Test fun sectionsWhereBothSidesHaveThem() {
        val r = insight(listOf(run(30000, "15", "B1", cpu = 6000), run(28000, "16", "B2", cpu = 4800))) as Insight.Result
        assertEquals(listOf("CPU", "MEM", "GPU", "UX", "AI", "LLM"), r.sections.map { it.name })
        assertEquals(-0.2, r.sections.first { it.name == "CPU" }.delta, 1e-12)
        assertEquals(0.0, r.sections.first { it.name == "GPU" }.delta, 0.0)
    }

    @Test fun eachTransitionIsDismissedOnItsOwn() {
        val a = insight(listOf(run(30000, "15", "B1"), run(29000, "16", "B2")))!!
        val b = insight(listOf(run(30000, "15", "B1"), run(29000, "16", "B2"), run(28000, "17", "B3")), "17", "B3")!!
        assertEquals("result:15|B1->16|B2", a.dismissKey)
        assertNotEquals(a.dismissKey, b.dismissKey)
    }
}
