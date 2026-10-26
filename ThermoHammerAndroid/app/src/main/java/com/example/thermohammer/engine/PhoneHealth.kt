package com.example.thermohammer.engine

import com.example.thermohammer.data.BenchmarkRecord
import com.example.thermohammer.engine.cpu.CpuTest
import com.example.thermohammer.network.ModelStatsDto
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// PHONE HEALTH — "is my phone as fast as it should be?" A run against the
// verified runs of the same model (GET /benchmark/model) and against this
// phone's own earlier runs. Every part of the score is compared with the
// model's median; the parts that fall short are grouped by what usually causes
// them, so the answer is a reason, not just a percentage. iOS repeats this
// (Benchmark/PhoneHealth.swift).
// ─────────────────────────────────────────────────────────────────────────────

object PhoneHealth {
    /** Verified runs of a model needed before a run is compared with them. */
    const val MIN_RUNS = 3
    /** A part this far below the model's median is a finding. */
    private const val SHORT = 0.85
    /** This far below the phone's own best run is a finding. */
    private const val OWN_DROP = 0.92

    enum class Verdict { FASTER, ON_PAR, SLOWER, MUCH_SLOWER, UNKNOWN }

    /** severity: 0 note, 1 worth fixing, 2 the main reason. */
    data class Finding(val text: String, val severity: Int)

    data class Report(
        val verdict: Verdict,
        val headline: String,
        val percent: Int?,                // this run ÷ the model's median, %
        val runs: Int,                    // verified runs of the model compared with
        val total: Int,
        val median: Int?, val p25: Int?, val p75: Int?, val best: Int?,
        val findings: List<Finding>
    )

    /** The run's parts, each 6000 on the reference phone — named as the server names them (BenchmarkScoring.Parts). */
    fun parts(r: BenchmarkResult): Map<String, Int> {
        fun gpu(t: BenchmarkTest) = r.tests.firstOrNull { it.test == t }?.points ?: 0
        return buildMap {
            r.cpu?.let { c ->
                put("cpu.math", c.points(CpuTest.MATH)); put("cpu.data", c.points(CpuTest.DATA))
                put("cpu.crypto", c.points(CpuTest.CRYPTO)); put("cpu.multi", c.points(CpuTest.MULTI))
            }
            r.mem?.let { m -> put("mem.ram", m.ramPoints); put("mem.storage", m.storagePoints); put("mem.db", m.dbPoints) }
            put("gpu.lake", gpu(BenchmarkTest.LAKE_VALLEY)); put("gpu.neon", gpu(BenchmarkTest.NEON_CITY))
            put("gpu.rt", gpu(BenchmarkTest.NEON_CITY_RT))
            r.ux?.let { u ->
                put("ux.web", u.webPoints); put("ux.scroll", u.scrollPoints); put("ux.gallery", u.galleryPoints)
                put("ux.photo", u.photoPoints); put("ux.video", u.videoPoints); put("ux.export", u.exportPoints); put("ux.qr", u.qrPoints)
            }
            r.ai?.let { a ->
                put("ai.cpu", a.accelScore(AiAccel.CPU)); put("ai.gpu", a.accelScore(AiAccel.GPU)); put("ai.npu", a.accelScore(AiAccel.NPU))
            }
            r.llm?.let { l -> put("llm.prefill", l.prefillPoints); put("llm.decode", l.decodePoints) }
        }
    }

    /** What a short part usually means — parts sharing a cause are reported once, by their worst. */
    private enum class Cause(val text: String) {
        HEAT("The GPU tests ran %d%% below other units — it throttled. Let the phone cool and take the case off before the next run."),
        BUSY("The CPU tests ran %d%% below other units — something else was using the processor, or a power-saving mode was on."),
        STORAGE("Storage ran %d%% below other units — flash slows down as it fills. Freeing some space usually brings it back."),
        WEB("The browser tests ran %d%% below other units — update Android System WebView and Chrome from the Play Store."),
        VIDEO("Video decoding ran %d%% below other units — a codec ran in software. A system update may restore the hardware decoder."),
        EXPORT("Video export ran %d%% below other units — the encoder was slower or its output worse. A hot phone or a system update can do that."),
        NPU("The NPU ran %d%% below other units — this build or firmware didn't reach it the way theirs did."),
        LLM("The language model ran %d%% below other units — usually less free memory or background apps.")
    }

    private fun cause(part: String): Cause = when {
        part.startsWith("gpu.") || part == "ux.photo" || part == "ai.gpu" -> Cause.HEAT
        part == "mem.storage" || part == "mem.db" -> Cause.STORAGE
        part == "ux.web" || part == "ux.scroll" || part == "ux.gallery" -> Cause.WEB
        part == "ux.video" -> Cause.VIDEO
        part == "ux.export" -> Cause.EXPORT
        part == "ai.npu" -> Cause.NPU
        part.startsWith("llm.") -> Cause.LLM
        else -> Cause.BUSY                                   // cpu.*, mem.ram, ux.qr, ai.cpu
    }

    private fun flagReasons(flags: Int): List<String> = buildList {
        if (flags and ValidityFlag.POWER_EVENT != 0) add("a charger was connected")
        if (flags and ValidityFlag.ENV_CAPPED != 0) add("battery saver was on")
        if (flags and (ValidityFlag.COOLDOWN_TIMEOUT or ValidityFlag.WARM_STARTED) != 0) add("it started warm")
        if (flags and ValidityFlag.INTERFERENCE != 0) add("other work was running")
    }

    /**
     * [stats]: the model's verified runs (null offline); [history]: this phone's saved runs of this scoring version,
     * [current] among them or not; [osVersion]: what this run ran on.
     */
    fun assess(result: BenchmarkResult, model: String, stats: ModelStatsDto?, history: List<BenchmarkRecord>,
               current: BenchmarkRecord?, osVersion: String): Report {
        val findings = ArrayList<Finding>()
        val total = result.total
        val reasons = flagReasons(result.validityFlags)
        if (reasons.isNotEmpty()) findings += Finding("Not a verified run: ${reasons.joinToString(", ")} — it doesn't reach the " +
            "leaderboard, and it may explain a lower score.", 1)

        val spread = stats?.total
        val compared = stats != null && spread != null && stats.runs >= MIN_RUNS && spread.median > 0
        var verdict = Verdict.UNKNOWN
        var percent: Int? = null
        val headline: String
        if (compared) {
            val ratio = total.toDouble() / spread!!.median
            percent = (ratio * 100).roundToInt()
            verdict = when {
                ratio >= 1.05 -> Verdict.FASTER
                ratio >= 0.95 -> Verdict.ON_PAR
                ratio >= SHORT -> Verdict.SLOWER
                else -> Verdict.MUCH_SLOWER
            }
            headline = when (verdict) {
                Verdict.FASTER -> "Faster than most $model units"
                Verdict.ON_PAR -> "As fast as a $model should be"
                Verdict.SLOWER -> "A little slower than other $model units"
                else -> "Slower than a $model should be"
            }
            // the parts that fall short, by cause, worst first
            val mine = parts(result)
            val medians = stats!!.parts.orEmpty()
            mine.mapNotNull { (k, v) -> medians[k]?.takeIf { it > 0 }?.let { m -> k to v.toDouble() / m } }
                .filter { it.second < SHORT }
                .groupBy { cause(it.first) }
                .map { (c, list) -> c to list.minOf { it.second } }
                .sortedBy { it.second }
                .take(3)
                .forEachIndexed { i, (c, r) -> findings += Finding(c.text.format(((1 - r) * 100).roundToInt()), if (i == 0) 2 else 1) }
            if (verdict == Verdict.ON_PAR || verdict == Verdict.FASTER) {
                if (findings.none { it.severity >= 1 }) findings += Finding("Every section is where other units are: nothing is holding it back.", 0)
            }
            // an OS version doing better or worse on this model
            val byOs = stats.byOsVersion.orEmpty()
            val mineOs = byOs.firstOrNull { it.osVersion == osVersion }
            val base = mineOs?.takeIf { it.runs >= 2 }?.medianTotal ?: spread.median
            byOs.filter { it.osVersion != osVersion && it.runs >= 2 && base > 0 }
                .map { it to (it.medianTotal.toDouble() / base - 1) }
                .filter { kotlin.math.abs(it.second) >= 0.05 }
                .maxByOrNull { kotlin.math.abs(it.second) }
                ?.let { (o, d) ->
                    findings += Finding("On Android ${o.osVersion} this model scores %d%% %s (%d runs) than on Android %s.".format(
                        (kotlin.math.abs(d) * 100).roundToInt(), if (d > 0) "higher" else "lower", o.runs, osVersion), 0)
                }
        } else {
            headline = if (stats == null) "Connect to compare with other $model units"
                else "Not enough verified runs of $model yet (${stats.runs} of $MIN_RUNS)"
        }

        // this phone's own earlier runs
        val earlier = history.filter { it.id != current?.id && it.validityFlags == 0 && it.score > 0 }
        earlier.maxByOrNull { it.score }?.let { best ->
            val date = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(best.timestamp))
            if (total < best.score * OWN_DROP) {
                val update = best.osVersion?.takeIf { it != osVersion }?.let { " — before the update to Android $osVersion" } ?: ""
                findings += Finding("%d%% below this phone's best run (%s, %,d)%s.".format(
                    ((1 - total.toDouble() / best.score) * 100).roundToInt(), date, best.score, update), if (compared) 1 else 2)
            } else if (total >= best.score) {
                findings += Finding("This phone's best run so far.", 0)
            }
        }
        return Report(verdict, headline, percent, stats?.runs ?: 0, total, spread?.median, spread?.p25, spread?.p75,
            spread?.best, findings)
    }
}
