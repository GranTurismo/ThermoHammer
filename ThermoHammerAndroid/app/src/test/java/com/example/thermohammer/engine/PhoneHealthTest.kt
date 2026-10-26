package com.example.thermohammer.engine

import com.example.thermohammer.data.BenchmarkRecord
import com.example.thermohammer.network.ModelStatsDto
import com.example.thermohammer.network.OsVersionStatsDto
import com.example.thermohammer.network.ScoreSpreadDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHealthTest {

    private fun gpuRun(lake: Int, neon: Int, flags: Int = 0): BenchmarkResult {
        fun t(test: BenchmarkTest, pts: Int) = BenchmarkTestResult(test = test, avgFps = 1.0, lowFps = 1.0, frames = 100,
            stabilityPct = 90f, startTempC = null, endTempC = null, points = pts)
        val tests = listOf(t(BenchmarkTest.LAKE_VALLEY, lake), t(BenchmarkTest.NEON_CITY, neon), BenchmarkTestResult.unsupported(BenchmarkTest.NEON_CITY_RT))
        return BenchmarkResult(tests, gpuScore = BenchmarkScore.overall(tests), gpuName = "Adreno", validityFlags = flags)
    }

    private fun stats(runs: Int, median: Int, parts: Map<String, Int>, byOs: List<OsVersionStatsDto> = emptyList()) =
        ModelStatsDto("Honor 600 Pro", runs, ScoreSpreadDto(median, median * 9 / 10, median * 11 / 10, median * 12 / 10),
            emptyMap(), parts, byOs)

    private fun record(id: String, score: Int, os: String, flags: Int = 0) = BenchmarkRecord(id = id, timestamp = 0L,
        version = BenchmarkScore.VERSION, score = score, test1Fps = 0.0, test1LowFps = 0.0, test1Points = 0, test2Fps = 0.0,
        test2LowFps = 0.0, test2Points = 0, gpuName = "Adreno", validityFlags = flags, osVersion = os)

    @Test
    fun `a run well below its model names the throttled GPU as the reason`() {
        val run = gpuRun(lake = 6000, neon = 2000)                     // Neon City at a third of the model's median
        val report = PhoneHealth.assess(run, "Honor 600 Pro", stats(5, 6000, mapOf("gpu.lake" to 6000, "gpu.neon" to 6000)),
            emptyList(), null, "16")
        assertEquals(PhoneHealth.Verdict.MUCH_SLOWER, report.verdict)
        assertEquals(run.total * 100 / 6000, report.percent)
        assertTrue(report.findings.first().text.startsWith("The GPU tests ran 67% below"))
        assertEquals(2, report.findings.first().severity)
    }

    @Test
    fun `a run on par says nothing is holding it back`() {
        val run = gpuRun(6000, 6000)
        val report = PhoneHealth.assess(run, "Honor 600 Pro", stats(4, run.total, mapOf("gpu.lake" to 6000, "gpu.neon" to 6000)),
            emptyList(), null, "16")
        assertEquals(PhoneHealth.Verdict.ON_PAR, report.verdict)
        assertTrue(report.findings.single().text.startsWith("Every section"))
    }

    @Test
    fun `too few runs or no connection compare with the phone's own runs only`() {
        val run = gpuRun(6000, 6000)
        val few = PhoneHealth.assess(run, "Pixel 9", stats(2, 4000, emptyMap()), emptyList(), null, "16")
        assertEquals(PhoneHealth.Verdict.UNKNOWN, few.verdict)
        assertEquals("Not enough verified runs of Pixel 9 yet (2 of 3)", few.headline)
        val offline = PhoneHealth.assess(run, "Pixel 9", null, emptyList(), null, "16")
        assertEquals("Connect to compare with other Pixel 9 units", offline.headline)
    }

    @Test
    fun `a drop since an update and an unverified run are both reported`() {
        val run = gpuRun(4000, 4000, flags = ValidityFlag.POWER_EVENT)
        val history = listOf(record("a", run.total * 2, "15"), record("b", run.total * 3, "15", flags = ValidityFlag.INTERFERENCE))
        val report = PhoneHealth.assess(run, "Pixel 9", null, history, null, "16")
        assertTrue(report.findings[0].text.startsWith("Not a verified run: a charger was connected"))
        assertTrue(report.findings[1].text.startsWith("50% below this phone's best run"))   // the unverified run doesn't count
        assertTrue(report.findings[1].text.endsWith("before the update to Android 16."))
    }

    @Test
    fun `an OS version that does better on this model is pointed out`() {
        val run = gpuRun(6000, 6000)
        val byOs = listOf(OsVersionStatsDto("16", 3, run.total), OsVersionStatsDto("17", 4, run.total * 110 / 100))
        val report = PhoneHealth.assess(run, "Honor 600 Pro", stats(7, run.total, mapOf("gpu.lake" to 6000), byOs),
            emptyList(), null, "16")
        assertTrue(report.findings.any { it.text.startsWith("On Android 17 this model scores 10% higher (4 runs)") })
    }
}
