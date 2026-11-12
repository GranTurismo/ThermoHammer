package com.example.thermohammer.ui.share

import com.example.thermohammer.data.ShareLinks
import com.example.thermohammer.data.StressCurve
import com.example.thermohammer.data.deviceDisplayName
import com.example.thermohammer.engine.BenchmarkResult
import com.example.thermohammer.engine.StampV2
import com.example.thermohammer.network.BenchmarkDetailDto
import com.example.thermohammer.network.BenchmarkDto
import com.example.thermohammer.network.DeviceHammerStamp
import com.example.thermohammer.network.HammerDto

/**
 * One run as the share card and the presentation screen show it — the user's own or one from the leaderboard.
 * [os] "Android 16", [date] "12 Nov 2026", [chip] the SoC when the app knows it; [url] where the link points.
 */
sealed interface RunCard {
    val device: String
    val os: String
    val date: String?
    val chip: String?
    val verified: Boolean
    val url: String
    val mine: Boolean
    /** The settings line of the presentation screen. */
    val settings: String

    /** "Android 16 · 12 Nov 2026 · SM8750" — the card's sub-line. */
    val subLine: String get() = listOfNotNull(os, date, chip?.takeIf { it.isNotBlank() }).joinToString(" · ")

    /** The text that goes with the image through the share sheet. */
    val shareText: String

    /** One of the six sections: CPU, MEM, GPU, UX, AI or LLM. */
    data class Section(val name: String, val points: Int)

    data class Benchmark(
        override val device: String, override val os: String, override val date: String?, override val chip: String?,
        override val verified: Boolean, override val url: String, override val mine: Boolean,
        val total: Int,
        val sections: List<Section>
    ) : RunCard {
        override val settings: String get() = "FULL BENCHMARK · ${sections.size} SECTIONS"
        override val shareText: String get() = ShareLinks.benchmarkText(device, total, url, mine)
        /** A bar's full length: the largest section, but never under 12,000 points. */
        val barMax: Int get() = maxOf(BAR_FLOOR, sections.maxOfOrNull { it.points } ?: 0)
    }

    data class Stress(
        override val device: String, override val os: String, override val date: String?, override val chip: String?,
        override val verified: Boolean, override val url: String, override val mine: Boolean,
        val capacity: Double,
        val minutes: Int,
        val load: String,                 // CPU · GPU · CPU+GPU
        val threads: String?,             // "MULTI THREAD" / "1 THREAD"; null for a GPU-only run
        val curve: List<Double>,          // % of baseline, evenly spaced over the run
        val onsetSec: Int?                // null = never throttled
    ) : RunCard {
        override val settings: String get() = listOfNotNull("$minutes-MIN $load LOAD", threads).joinToString(" · ")
        override val shareText: String get() = ShareLinks.stressText(device, capacity, minutes, url, mine)
        val label: String get() = "SPEED KEPT · $minutes-MIN $load LOAD"
        val onsetText: String get() = onsetSec?.let { "Throttled after ${StressCurve.mmss(it)}" } ?: "Never throttled"
        /** The curve's top: 110 %, or higher when the run went above it. */
        val yMax: Double get() = maxOf(110.0, curve.maxOrNull() ?: 0.0)
    }

    companion object {
        const val BAR_FLOOR = 12_000
        val SECTION_ORDER = listOf("CPU", "MEM", "GPU", "UX", "AI", "LLM")

        /** The six sections in their order, without the ones the run doesn't have (0 points). */
        fun sections(cpu: Int, mem: Int, gpu: Int, ux: Int, ai: Int, llm: Int): List<Section> =
            SECTION_ORDER.zip(listOf(cpu, mem, gpu, ux, ai, llm)).filter { it.second > 0 }.map { Section(it.first, it.second) }

        fun ownBenchmark(result: BenchmarkResult, device: String, osVersion: String, chip: String?, timestamp: Long,
                         serverId: Int?): Benchmark = Benchmark(
            device = device, os = "Android $osVersion", date = ShareLinks.date(timestamp), chip = chip,
            verified = result.isVerified, url = ShareLinks.runUrl(serverId), mine = true, total = result.total,
            sections = sections(result.cpuScore, result.memScore, result.gpuScore, result.uxScore, result.aiScore, result.llmScore))

        /** A leaderboard run: only verified runs are on the board. [detail] adds the date when it has loaded. */
        fun leaderboardBenchmark(entry: BenchmarkDto, detail: BenchmarkDetailDto?): Benchmark = Benchmark(
            device = deviceDisplayName(entry.deviceManufacturer, entry.deviceModel),
            os = "${ShareLinks.osName(entry.os)} ${entry.osVersion}", date = ShareLinks.serverDate(detail?.createdAt),
            chip = entry.socModel, verified = true, url = ShareLinks.runUrl(entry.id), mine = false,
            total = detail?.totalScore ?: entry.totalScore,
            sections = sections(entry.cpuScore, entry.memScore, entry.score, entry.uxScore, entry.aiScore, entry.llmScore))

        /** The user's own finished stress run: [stamps] and [baselineIps] as the verdict has them. */
        fun ownStress(device: String, osVersion: String, chip: String?, timestamp: Long, serverId: Int?, verified: Boolean,
                      capacity: Double, durationType: Int, stressMode: Int, threadingType: Int, stamps: List<StampV2>,
                      baselineIps: Double, onsetSec: Int?): Stress = Stress(
            device = device, os = "Android $osVersion", date = ShareLinks.date(timestamp), chip = chip, verified = verified,
            url = ShareLinks.stressUrl(serverId), mine = true, capacity = capacity, minutes = ShareLinks.minutes(durationType),
            load = ShareLinks.load(stressMode), threads = threads(stressMode, threadingType),
            curve = StressCurve.downsample(StressCurve.percentOf(stamps.map { it.ipsTotal }, baselineIps)), onsetSec = onsetSec)

        /** A leaderboard stress run, its curve from `/stamps/{id}` (% of its first-5-s median). */
        fun leaderboardStress(entry: HammerDto, stamps: List<DeviceHammerStamp>): Stress {
            val ms = stamps.map { it.elapsedMs }
            val percent = StressCurve.fromScores(ms, stamps.map { it.score })
            return Stress(
                device = deviceDisplayName(entry.deviceManufacturer, entry.deviceModel),
                os = "${ShareLinks.osName(entry.os)} ${entry.osVersion}", date = ShareLinks.serverDate(entry.createdAt),
                chip = entry.socModel, verified = true, url = ShareLinks.stressUrl(entry.id), mine = false,
                capacity = entry.stabilityPercentage, minutes = ShareLinks.minutes(entry.type), load = ShareLinks.load(entry.stressMode),
                threads = threads(entry.stressMode ?: 0, entry.testThreadingType ?: 1),
                curve = StressCurve.downsample(percent), onsetSec = StressCurve.onsetSec(ms, percent))
        }

        private fun threads(stressMode: Int, threadingType: Int): String? =
            if (stressMode == 1) null else if (threadingType == 0) "1 THREAD" else "MULTI THREAD"
    }
}
