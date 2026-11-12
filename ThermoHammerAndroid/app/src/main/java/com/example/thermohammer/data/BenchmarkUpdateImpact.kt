package com.example.thermohammer.data

import java.util.Locale
import kotlin.math.abs

/**
 * "Did the update slow my phone?" for benchmark runs — the stress test's [UpdateImpact], with the same rules and look,
 * over the benchmark history. Pure logic, no Android, no I/O.
 *
 *  · this phone's verified, complete runs of the current benchmark version only (a run saved before 2.1 has no model:
 *    it was made on this phone)
 *  · OS key = version + build; a run without a build counts as the same OS when the version matches, so missing data
 *    never asks for a re-run
 *  · medians of up to [MAX_RUNS_PER_SIDE] newest runs a side; under [NO_CHANGE_WITHIN] is no clear change
 */
object BenchmarkUpdateImpact {

    const val NO_CHANGE_WITHIN = 0.03
    const val MAX_RUNS_PER_SIDE = 3

    enum class Verdict { SLOWER, FASTER, NO_CLEAR_CHANGE }

    /** An OS as the card names it. */
    data class Os(val version: String, val build: String?)

    /** Runs measured on one OS, newest first. */
    data class Side(val os: Os, val runs: List<BenchmarkRecord>) {
        val median: Double get() = median(runs.map { it.score.toDouble() })
    }

    /** One section's median before and after; [delta] = after / before − 1. */
    data class SectionChange(val name: String, val before: Double, val after: Double) {
        val delta: Double get() = after / before - 1
    }

    sealed interface Insight {
        /** Identifies one OS transition ("before → now"). */
        val key: String
        val dismissKey: String

        /** The phone updated and has no benchmark run since: offer to run it. [last] is the newest run before. */
        data class Prompt(override val key: String, val before: Os, val now: Os, val last: BenchmarkRecord) : Insight {
            override val dismissKey get() = "prompt:$key"
        }

        data class Result(
            override val key: String,
            val before: Side,
            val after: Side,
            val delta: Double,
            val verdict: Verdict,
            val sections: List<SectionChange>
        ) : Insight {
            override val dismissKey get() = "result:$key"
            /** One run on a side is a weak comparison — the card says so. */
            val weak: Boolean get() = before.runs.size < 2 || after.runs.size < 2
        }
    }

    fun osKey(version: String, build: String?) = "$version|${build.orEmpty()}"

    /** A record measured on [version] / [build] — a missing build on either side counts as the same. */
    fun sameOs(r: BenchmarkRecord, version: String, build: String?): Boolean =
        r.osVersion == version && (r.osBuild == null || build == null || r.osBuild == build)

    /** Evidence: verified, every section scored, on this phone (or saved before records named the phone), OS known. */
    fun eligible(r: BenchmarkRecord, deviceModel: String): Boolean =
        r.validityFlags == 0 && r.isComplete && (r.deviceModel == null || r.deviceModel == deviceModel) && r.osVersion != null

    /**
     * What, if anything, to tell the user about the OS now ([osVersion] / [osBuild]) against their earlier benchmark runs.
     * [history]: the current benchmark version's runs (BenchmarkHistoryStore.current()).
     */
    fun insight(history: List<BenchmarkRecord>, deviceModel: String, osVersion: String, osBuild: String?): Insight? {
        val mine = history.filter { eligible(it, deviceModel) }.sortedByDescending { it.timestamp }
        // the newest run measured on another OS — the "before"
        val last = mine.firstOrNull { !sameOs(it, osVersion, osBuild) } ?: return null
        val before = Os(last.osVersion!!, last.osBuild)
        val now = Os(osVersion, osBuild)
        val key = "${osKey(before.version, before.build)}->${osKey(osVersion, osBuild)}"

        val after = mine.filter { sameOs(it, osVersion, osBuild) }.take(MAX_RUNS_PER_SIDE)
        if (after.isEmpty()) return Insight.Prompt(key, before, now, last)

        val beforeRuns = mine.filter { sameOs(it, before.version, before.build) && !sameOs(it, osVersion, osBuild) }
            .take(MAX_RUNS_PER_SIDE)
        val b = Side(before, beforeRuns)
        val a = Side(now, after)
        val delta = if (b.median > 0) a.median / b.median - 1 else 0.0
        val verdict = when {
            abs(delta) < NO_CHANGE_WITHIN -> Verdict.NO_CLEAR_CHANGE
            delta < 0 -> Verdict.SLOWER
            else -> Verdict.FASTER
        }
        return Insight.Result(key, b, a, delta, verdict, sectionChanges(beforeRuns, after))
    }

    /** CPU, MEM, GPU, UX, AI, LLM — each where both sides scored it. */
    fun sectionChanges(before: List<BenchmarkRecord>, after: List<BenchmarkRecord>): List<SectionChange> {
        val sections = listOf<Pair<String, (BenchmarkRecord) -> Int>>("CPU" to { it.cpuScore }, "MEM" to { it.memScore },
            "GPU" to { it.gpuScore }, "UX" to { it.uxScore }, "AI" to { it.aiScore }, "LLM" to { it.llmScore })
        return sections.mapNotNull { (name, of) ->
            val b = before.map(of).filter { it > 0 }
            val a = after.map(of).filter { it > 0 }
            if (b.isEmpty() || a.isEmpty()) null else SectionChange(name, median(b.map { it.toDouble() }), median(a.map { it.toDouble() }))
        }
    }

    /** "Android 15 → 16. Run the benchmark again to see if the update changed your phone." */
    fun promptText(p: Insight.Prompt): String =
        "Android ${osLabel(p.before, p.now)} → ${osLabel(p.now, p.before)}. Run the benchmark again to see if the update changed your phone."

    /** "4.2% slower than before the update"; null for no clear change. */
    fun resultText(r: Insight.Result, locale: Locale = Locale.getDefault()): String? = when (r.verdict) {
        Verdict.NO_CLEAR_CHANGE -> null
        Verdict.SLOWER -> String.format(locale, "%.1f%% slower than before the update", abs(r.delta) * 100)
        Verdict.FASTER -> String.format(locale, "%.1f%% faster than before the update", abs(r.delta) * 100)
    }

    /** The version, and the build too when the version alone doesn't tell the two apart (a monthly update). */
    fun osLabel(os: Os, other: Os): String =
        if (os.version == other.version && os.build != null) "${os.version} (${os.build})" else os.version

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}
