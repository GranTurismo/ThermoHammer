package com.example.thermohammer.data

import com.example.thermohammer.engine.ValidityFlag
import kotlin.math.max

/**
 * One finished, scored run — kept on the device (never uploaded) so that after an OS update
 * the app can compare the phone with ITSELF. No stamps: summary only.
 */
data class RunRecord(
    val id: String,
    val timestamp: Long,
    val deviceModel: String,
    val osVersion: String,
    val osBuild: String? = null,        // Build.ID — changes with every monthly update
    val securityPatch: String? = null,
    val durationType: Int,              // 0=5min 1=15min 2=30min
    val threadingType: Int,             // 0=single 1=multi
    val stressMode: Int = 0,            // 0=CPU 1=GPU 2=COMBINED
    val capacity: Double,               // deliveredCapacity — the leaderboard metric
    val onsetSec: Int? = null,          // null = never throttled
    val validityFlags: Int = 0,
    val confidence: Int = 0
) {
    val osKey: String get() = UpdateImpact.osKey(osVersion, osBuild)
}

/**
 * "Did the update change my phone?" — pure logic, no Android, no I/O.
 *
 * Fairness rules (each one exists to stop a misleading verdict):
 *  · same phone model only — a restored backup on a new phone must not count
 *  · same test settings only — 5-min vs 15-min runs are different tests
 *  · clean runs only — a run flagged warm-start / charging / battery-saver is not evidence
 *  · medians of up to [MAX_RUNS_PER_SIDE] runs, and a change must exceed normal run-to-run
 *    noise before it is called slower or faster
 */
object UpdateImpact {

    /** Typical run-to-run spread of the metric on one phone, in capacity points. */
    const val NOISE_FLOOR_PTS = 3.0
    const val MAX_RUNS_PER_SIDE = 3
    const val MAX_HISTORY = 40

    enum class Verdict { SLOWER, FASTER, NO_CLEAR_CHANGE }

    /** A set of comparable runs measured on one OS build, newest first. */
    data class Side(val runs: List<RunRecord>) {
        val latest: RunRecord get() = runs.first()
        val median: Double
            get() {
                val v = runs.map { it.capacity }.sorted()
                val m = v.size / 2
                return if (v.size % 2 == 1) v[m] else (v[m - 1] + v[m]) / 2.0
            }
        /** max − min of the runs; 0 for a single run. */
        val spread: Double
            get() = if (runs.size < 2) 0.0 else runs.maxOf { it.capacity } - runs.minOf { it.capacity }
    }

    sealed interface Insight {
        /** Identifies one OS transition ("before build → current build"). */
        val key: String
        val dismissKey: String

        /** The phone updated and has no usable test since — offer to repeat the old one. */
        data class Prompt(
            override val key: String,
            val last: RunRecord,
            val nowOsVersion: String,
            val nowOsBuild: String?,
            val nowPatch: String?
        ) : Insight {
            override val dismissKey get() = "prompt:$key"
        }

        data class Result(
            override val key: String,
            val before: Side,
            val after: Side,
            val deltaPts: Double,
            val thresholdPts: Double,
            val verdict: Verdict
        ) : Insight {
            override val dismissKey get() = "result:$key"
            /** One run on a side is a weak comparison — the UI says so. */
            val weak: Boolean get() = before.runs.size < 2 || after.runs.size < 2
        }
    }

    fun osKey(osVersion: String, osBuild: String?) = "$osVersion|${osBuild.orEmpty()}"

    /**
     * A run is usable as evidence when nothing but the platform's constant "no clock data"
     * flag is set. (FREQ_MISSING is a property of the device, identical on both sides.)
     */
    fun isComparable(r: RunRecord): Boolean =
        (r.validityFlags and ValidityFlag.FREQ_MISSING.inv()) == 0

    private fun sameSettings(a: RunRecord, b: RunRecord) =
        a.durationType == b.durationType &&
            a.threadingType == b.threadingType &&
            a.stressMode == b.stressMode

    /**
     * What, if anything, to tell the user about the current OS versus their earlier runs.
     * Returns null when there is nothing honest to say (no history, or no clean baseline).
     */
    fun insight(
        history: List<RunRecord>,
        deviceModel: String,
        osVersion: String,
        osBuild: String?,
        securityPatch: String? = null
    ): Insight? {
        val curKey = osKey(osVersion, osBuild)
        val mine = history.filter { it.deviceModel == deviceModel }.sortedByDescending { it.timestamp }

        // Most recent clean run measured on a different OS build — the baseline.
        val baseline = mine.firstOrNull { it.osKey != curKey && isComparable(it) } ?: return null
        val key = "${baseline.osKey}->$curKey"

        val after = mine
            .filter { it.osKey == curKey && isComparable(it) && sameSettings(it, baseline) }
            .take(MAX_RUNS_PER_SIDE)

        if (after.isEmpty()) {
            return Insight.Prompt(key, baseline, osVersion, osBuild, securityPatch)
        }

        val before = mine
            .filter { it.osKey == baseline.osKey && isComparable(it) && sameSettings(it, baseline) }
            .take(MAX_RUNS_PER_SIDE)

        val beforeSide = Side(before)
        val afterSide = Side(after)
        val delta = afterSide.median - beforeSide.median
        val threshold = max(NOISE_FLOOR_PTS, max(beforeSide.spread, afterSide.spread))
        val verdict = when {
            delta <= -threshold -> Verdict.SLOWER
            delta >= threshold -> Verdict.FASTER
            else -> Verdict.NO_CLEAR_CHANGE
        }
        return Insight.Result(key, beforeSide, afterSide, delta, threshold, verdict)
    }

    /** Append a run, drop duplicates by id, keep only the newest [max]. */
    fun appendCapped(existing: List<RunRecord>, run: RunRecord, max: Int = MAX_HISTORY): List<RunRecord> =
        (existing.filter { it.id != run.id } + run).sortedBy { it.timestamp }.takeLast(max)
}
