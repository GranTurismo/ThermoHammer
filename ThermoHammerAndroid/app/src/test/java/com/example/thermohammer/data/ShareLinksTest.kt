package com.example.thermohammer.data

import com.example.thermohammer.engine.StampV2
import com.example.thermohammer.network.BenchmarkDetailDto
import com.example.thermohammer.network.BenchmarkDto
import com.example.thermohammer.network.DeviceHammerStamp
import com.example.thermohammer.network.HammerDto
import com.example.thermohammer.ui.share.RunCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** The links and texts every platform shares the same way (features-spec §1). */
class ShareLinksTest {

    @Test fun runLinksFallBackToTheSiteWithoutAServerId() {
        assertEquals("https://thermohammer.gtgroup.dev/run/123/", ShareLinks.runUrl(123))
        assertEquals("https://thermohammer.gtgroup.dev/stress/45/", ShareLinks.stressUrl(45))
        assertEquals("https://thermohammer.gtgroup.dev/cert/9/", ShareLinks.certUrl(9))
        assertEquals("https://thermohammer.gtgroup.dev/", ShareLinks.runUrl(null))
        assertEquals("https://thermohammer.gtgroup.dev/", ShareLinks.stressUrl(null))
        assertEquals("https://thermohammer.gtgroup.dev/", ShareLinks.runUrl(0))
    }

    @Test fun displayLinkHasNoSchemeAndNoTrailingSlash() {
        assertEquals("thermohammer.gtgroup.dev/run/123", ShareLinks.display(ShareLinks.runUrl(123)))
        assertEquals("thermohammer.gtgroup.dev", ShareLinks.display(ShareLinks.runUrl(null)))
    }

    @Test fun ownBenchmarkText() {
        assertEquals("My HONOR 600 Pro scored 36,512 on the ThermoHammer benchmark. Can your phone beat it? " +
            "https://thermohammer.gtgroup.dev/run/7/",
            ShareLinks.benchmarkText("HONOR 600 Pro", 36512, ShareLinks.runUrl(7), mine = true, locale = Locale.US))
    }

    @Test fun leaderboardBenchmarkText() {
        assertEquals("Samsung Galaxy S25 FE scored 21,004 on ThermoHammer. Can your phone beat it? https://thermohammer.gtgroup.dev/run/3/",
            ShareLinks.benchmarkText("Samsung Galaxy S25 FE", 21004, ShareLinks.runUrl(3), mine = false, locale = Locale.US))
    }

    @Test fun stressTextsRoundTheCapacity() {
        assertEquals("My Google Pixel 9 Pro kept 87% of its speed through a 15-minute ThermoHammer stress test. " +
            "Can your phone do better? https://thermohammer.gtgroup.dev/",
            ShareLinks.stressText("Google Pixel 9 Pro", 86.6, 15, ShareLinks.stressUrl(null), mine = true))
        assertEquals("HONOR 600 Pro kept 92% of its speed through a 5-minute ThermoHammer stress test. " +
            "https://thermohammer.gtgroup.dev/stress/12/",
            ShareLinks.stressText("HONOR 600 Pro", 92.4, 5, ShareLinks.stressUrl(12), mine = false))
    }

    @Test fun certificateText() {
        assertEquals("Health certificate for my HONOR 600 Pro: https://thermohammer.gtgroup.dev/cert/5/",
            ShareLinks.certificateText("HONOR 600 Pro", ShareLinks.certUrl(5)))
    }

    @Test fun datesAreDayMonthYearInEnglish() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("12 Nov 2026", ShareLinks.date(1_794_441_600_000L, utc))     // 2026-11-12T00:00Z
        assertEquals("27 Oct 2026", ShareLinks.serverDate("2026-10-27T18:20:11.123Z"))
        assertNull(ShareLinks.serverDate(null))
        assertNull(ShareLinks.serverDate("yesterday"))
    }

    @Test fun durationsAndLoads() {
        assertEquals(listOf(5, 15, 30), listOf(0, 1, 2).map { ShareLinks.minutes(it) })
        assertEquals(listOf("CPU", "GPU", "CPU+GPU", "CPU"), listOf(0, 1, 2, null).map { ShareLinks.load(it) })
    }

    // ── the card models ──

    @Test fun sectionsKeepTheirOrderAndSkipMissingOnes() {
        val s = RunCard.sections(cpu = 6000, mem = 5000, gpu = 0, ux = 7000, ai = 3000, llm = 0)
        assertEquals(listOf("CPU", "MEM", "UX", "AI"), s.map { it.name })
    }

    @Test fun barsReachTwelveThousandUnlessASectionIsBigger() {
        val small = card(RunCard.sections(6000, 5000, 4000, 7000, 3000, 2000))
        assertEquals(12_000, small.barMax)
        val big = card(RunCard.sections(16000, 5000, 4000, 7000, 3000, 2000))
        assertEquals(16_000, big.barMax)
    }

    @Test fun leaderboardBenchmarkCard() {
        val entry = BenchmarkDto(id = 7, deviceManufacturer = "HONOR", deviceModel = "HONOR 600 Pro", socModel = "SM8750",
            gpuName = "Adreno 830", os = 2, osVersion = "16", score = 6000, uxScore = 6100, cpuScore = 6900, aiScore = 3100,
            memScore = 5800, llmScore = 4300, totalScore = 32200, test1Score = 1, test2Score = 1, test3Score = 1, test1Fps = 1.0,
            test2Fps = 1.0, test3Fps = 1.0, rayTracing = true, runs = 1)
        val detail = BenchmarkDetailDto(7, "2026-10-27T18:20:11Z", "HONOR", "HONOR 600 Pro", "SM8750", "Adreno 830", 2, "16",
            "BP2A", "2.0.4", 32200, emptyList())
        val c = RunCard.leaderboardBenchmark(entry, detail)
        assertEquals("HONOR 600 Pro", c.device)
        assertEquals("Android 16 · 27 Oct 2026 · SM8750", c.subLine)
        assertEquals("https://thermohammer.gtgroup.dev/run/7/", c.url)
        assertEquals(listOf("CPU", "MEM", "GPU", "UX", "AI", "LLM"), c.sections.map { it.name })
        assertEquals(6000, c.sections[2].points)                 // the board's "score" is the GPU section
    }

    @Test fun leaderboardStressCardBuildsItsCurveFromTheStamps() {
        val entry = HammerDto(id = 12, stamps = null, type = 1, deviceManufacturer = "Google", deviceModel = "Pixel 9 Pro", os = 2,
            osVersion = "16", stabilityPercentage = 81.4, testThreadingType = 1, stressMode = null)
        val stamps = (1..10).map { DeviceHammerStamp(it * 1000, if (it <= 6) 1000L else 700L, 0) }
        val c = RunCard.leaderboardStress(entry, stamps)
        assertEquals("Google Pixel 9 Pro", c.device)
        assertEquals(15, c.minutes)
        assertEquals("SPEED KEPT · 15-MIN CPU LOAD", c.label)
        assertEquals(listOf(100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 70.0, 70.0, 70.0, 70.0), c.curve)
        assertEquals(7, c.onsetSec)
        assertEquals("Throttled after 0:07", c.onsetText)
        assertEquals("https://thermohammer.gtgroup.dev/stress/12/", c.url)
        assertEquals("Google Pixel 9 Pro kept 81% of its speed through a 15-minute ThermoHammer stress test. " +
            "https://thermohammer.gtgroup.dev/stress/12/", c.shareText)
    }

    @Test fun ownStressCard() {
        val stamps = (1..4).map { StampV2(tNs = it * 1_000_000_000L, ipsTotal = if (it < 3) 200L else 150L) }
        val c = RunCard.ownStress("HONOR 600 Pro", "16", "SM8750", 0L, serverId = null, verified = true, capacity = 87.5,
            durationType = 0, stressMode = 2, threadingType = 1, stamps = stamps, baselineIps = 200.0, onsetSec = null)
        assertEquals(listOf(100.0, 100.0, 75.0, 75.0), c.curve)
        assertEquals("Never throttled", c.onsetText)
        assertEquals("5-MIN CPU+GPU LOAD · MULTI THREAD", c.settings)
        assertEquals(110.0, c.yMax, 0.0)
        assertEquals("https://thermohammer.gtgroup.dev/", c.url)
    }

    private fun card(sections: List<RunCard.Section>) = RunCard.Benchmark("D", "Android 16", null, null, true,
        ShareLinks.runUrl(null), true, sections.sumOf { it.points }, sections)
}
