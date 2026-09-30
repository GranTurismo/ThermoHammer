package com.example.thermohammer.engine

// ── Run phases ─────────────────────────────────────────────────────────────────
// A test is a sequence of phases, not a single loop:
// PRE-FLIGHT gates → WARM-UP (unrecorded) → CALIBRATION (boost window) → MEASURED.

enum class RunPhase(val displayName: String) {
    IDLE("IDLE"),
    COOLDOWN("COOLDOWN"),
    WARMUP("WARM-UP"),
    CALIBRATION("CALIBRATING"),
    MEASURED("STRESS ACTIVE")
}

// ── Stress modes — which engine channels are under load ────────────────────────

enum class StressMode(val displayName: String, val value: Int) {
    CPU("CPU ONLY", 0),
    GPU("GPU 3D", 1),       // headless GLES3 sustained-rendering benchmark
    COMBINED("CPU + GPU", 2);
    val usesCpu get() = this != GPU
    val usesGpu get() = this != CPU
}

// ── Per-sample throttling attribution ─────────────────────────────────────────
// Why did throughput drop in this window? Classified from frequency caps,
// scheduled time and core residency — not assumed to be "thermal".

enum class Attribution(val code: Int) {
    NONE(0),
    DVFS_CAP(1),    // scaling_max_freq below cpuinfo_max_freq — imposed thermal ceiling
    GOVERNOR(2),    // cur freq low while cap unchanged — governor/EAS down-clock
    MIGRATION(3),   // work residency shifted onto a slower cluster
    HOTPLUG(4),     // core(s) taken offline
    CONTENTION(5),  // workers starved by external load — NOT throttling
    UNKNOWN(6);     // frequency data unavailable, cannot attribute

    val isThermal: Boolean
        get() = this == DVFS_CAP || this == GOVERNOR || this == MIGRATION || this == HOTPLUG

    companion object {
        fun of(code: Int): Attribution = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

// ── Validity bitfield (persisted with every run) ───────────────────────────────

object ValidityFlag {
    const val WARM_STARTED    = 1 shl 0  // capped already during calibration
    const val INTERFERENCE    = 1 shl 1  // >10% of samples starved by other load
    const val POWER_EVENT     = 1 shl 2  // charger plugged mid-run
    const val FREQ_MISSING    = 1 shl 3  // cpufreq sysfs unreadable
    const val SHORT_RUN       = 1 shl 4  // ended before 90% of target duration
    const val ENV_CAPPED      = 1 shl 5  // battery saver active during run
    const val SUSPICIOUS_BOOST = 1 shl 6 // huge peak with no cap drop — possible benchmark whitelisting
    const val COOLDOWN_TIMEOUT = 1 shl 7 // cooldown phase ended without reaching target temp
    const val BASELINE_EXCEEDED = 1 shl 8 // measured run sustained >105% of calibration baseline
                                        // — the "peak" was captured under degraded conditions
                                        //   (capped, starved, or parked on little cores)
}

// ── Per-stamp environment bitfield ─────────────────────────────────────────────

object EnvFlag {
    const val CHARGING           = 1 shl 0
    const val BRIGHTNESS_CHANGED = 1 shl 1
    const val BATTERY_SAVER      = 1 shl 2
}

// ── Stamp v2 — the unit of recorded truth (emitted ~1 Hz during MEASURED) ─────

data class StampV2(
    val tNs: Long,                             // monotonic ns since measured-run start
    val windowNs: Long = 1_000_000_000L,       // actual measured window length
    val ipsTotal: Long,                        // true iterations/sec across all workers
    val ipsPerCluster: List<Long> = emptyList(),
    val capRatioAvg: Float = 1f,               // mean over cores of scaling_max/cpuinfo_max
    val minCapRatio: Float = 1f,               // worst core's imposed ceiling
    val freqRatioAvg: Float = 1f,              // mean of scaling_cur/cpuinfo_max
    val freqRatioPerCore: List<Int> = emptyList(), // % of hw max per core
    val offlineCores: Int = 0,
    val schedFracAvg: Float = 1f,              // mean worker scheduled-time fraction
    val cpuTempMilliC: Int = 0,                // warmest CPU-adjacent zone
    val skinTempMilliC: Int = -1,              // -1 = no identifiable skin zone
    val battTempMilliC: Int = 0,
    val battCurrentUa: Int? = null,            // |µA| for perf/W — null if unsupported
    val battVoltageMv: Int = 0,
    val thermalHeadroom: Float? = null,        // PowerManager.getThermalHeadroom()
    val thermalStatus: Int = 0,                // 0..3 ThermalState ordinal
    val attribution: Int = 0,                  // Attribution.code
    val envFlags: Int = 0,                     // EnvFlag bitmask
    // ── GPU channel (−1 = channel not running) ──
    val gpuFps: Double = -1.0,                 // frames/sec in this window (offscreen 1080p)
    val gpuFrameP95Ms: Float = -1f,            // p95 frame time in ms
    val gpuFreqRatio: Float = -1f              // devfreq cur/max, −1 = unreadable
) {
    // ── Back-compat accessors so legacy analytics keep working on StampV2 ──
    val elapsedMs: Int get() = (tNs / 1_000_000L).toInt()
    val score: Long get() = ipsTotal
    val thermalState: Int get() = thermalStatus
}

// ── Pre-flight gates ───────────────────────────────────────────────────────────

data class GateResult(
    val id: String,
    val title: String,
    val blocking: Boolean,
    val passed: Boolean,
    val detail: String
)

data class PreflightReport(
    val gates: List<GateResult>,
    val canRun: Boolean,                 // all blocking gates passed
    val validityHints: Int               // ValidityFlag bits already known before start
)

// ── CPU cluster topology (from cpufreq/related_cpus) ──────────────────────────

data class ClusterTopology(
    val clusters: List<List<Int>>,      // core indices sharing a clock domain
    val maxKHzPerCluster: List<Long>
) {
    val totalCores: Int get() = clusters.sumOf { it.size }

    fun clusterOf(core: Int): Int = clusters.indexOfFirst { core in it }

    /** Cluster index with the lowest hardware max — the "little" cluster. */
    val slowestClusterIdx: Int
        get() = maxKHzPerCluster.indices.minByOrNull { maxKHzPerCluster[it] } ?: -1

    /** Compact descriptor, e.g. "4x1804|3x2457|1x2956" (cores x MHz per cluster). */
    fun describe(): String =
        clusters.mapIndexed { i, c -> "${c.size}x${maxKHzPerCluster.getOrElse(i) { 0L } / 1000}" }
            .joinToString("|")
}

// ── Final scorecard — replaces the single "stability" number ──────────────────

data class Scorecard(
    val baselineIps: Double = 0.0,            // p95 of calibration-window IPS
    val sustainedRatio: Float = 100f,         // median(last quartile) / baseline
    val deliveredCapacity: Float = 100f,      // AUC: Σ(ips·dt) / (baseline·T)
    val minSustained: Float? = null,          // min 5s-mean over thermal-attributed samples; null = never throttled
    val throttleOnsetSec: Int? = null,        // first <90% sustained ≥3s
    val timeInThrottlePct: Float = 0f,        // % of stamps with thermal attribution
    val perfPerWatt: Float? = null,           // mean IPS / mean mW
    val thermalEfficiency: Float? = null,     // deliveredCapacity / ΔT(°C)
    val confidence: Int = 100,
    val validityFlags: Int = 0,
    val warmStarted: Boolean = false,
    // ── GPU channel scorecard (null/0 = not measured) ──
    val gpuBaselineFps: Double = 0.0,          // p95 of calibration-window FPS
    val gpuDeliveredCapacity: Float = 0f,      // AUC vs GPU baseline
    val gpuSustainedRatio: Float = 0f,         // median last-quartile FPS / baseline
    val gpuMinSustained: Float? = null,
    val gpuName: String = "",
    val gpuAttribution: Int = 0
) {
    /** Verified = clean run eligible for the global leaderboard. */
    val isVerified: Boolean get() = validityFlags == 0
}
