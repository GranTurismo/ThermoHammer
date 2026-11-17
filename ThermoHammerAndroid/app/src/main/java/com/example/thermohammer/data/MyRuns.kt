package com.example.thermohammer.data

import com.example.thermohammer.engine.BenchmarkScore

/**
 * MY RUNS: this phone's own benchmark history, as the screen lists it. Pure logic, no Android; iOS and Windows
 * follow the same rules.
 *
 *  · only runs of the current benchmark version (older versions scored different work, so they don't compare)
 *  · only runs made on this phone model; a run saved without a model (before 2.1) counts as this phone, like
 *    BenchmarkUpdateImpact. A backup restored onto another phone must not mix two phones into one history.
 *  · newest first, each with its change from the run before it
 *  · best = the highest total (the earlier run on a tie), the same "best on this phone" the home card shows
 */
object MyRuns {
    /** The kept history's length (BenchmarkHistoryStore). */
    const val KEPT = 100

    enum class Status { SUBMITTED, NOT_SUBMITTED, UNVERIFIED }

    data class Row(
        val record: BenchmarkRecord,
        val changePct: Double?,      // against the run before it; null for the first run
        val isBest: Boolean,
        val status: Status
    ) {
        val canSubmit: Boolean get() = status == Status.NOT_SUBMITTED
    }

    data class History(
        val rows: List<Row>,         // newest first
        val olderVersion: Int,       // runs not shown: an older benchmark version
        val otherPhone: Int          // runs not shown: made on another phone model
    ) {
        val best: BenchmarkRecord? get() = rows.firstOrNull { it.isBest }?.record
        val latest: BenchmarkRecord? get() = rows.firstOrNull()?.record
        val verified: Int get() = rows.count { it.record.validityFlags == 0 }
        /** Oldest first: what the chart draws, left to right. */
        val chronological: List<Row> get() = rows.reversed()
    }

    fun build(all: List<BenchmarkRecord>, deviceModel: String, version: Int = BenchmarkScore.VERSION): History {
        val current = all.filter { it.version == version }
        val mine = current.filter { it.deviceModel == null || it.deviceModel == deviceModel }.sortedBy { it.timestamp }
        val best = mine.maxWithOrNull(compareBy<BenchmarkRecord> { it.score }.thenByDescending { it.timestamp })
        val rows = mine.mapIndexed { i, r ->
            val before = mine.getOrNull(i - 1)
            Row(
                record = r,
                changePct = before?.takeIf { it.score > 0 }?.let { (r.score - it.score) * 100.0 / it.score },
                isBest = r.id == best?.id,
                status = when {
                    r.submitted -> Status.SUBMITTED
                    r.validityFlags != 0 -> Status.UNVERIFIED
                    else -> Status.NOT_SUBMITTED
                }
            )
        }.reversed()
        return History(rows, olderVersion = all.size - current.size, otherPhone = current.size - mine.size)
    }

    /** "+2.1%", "−4.0%", "±0.0%" — the change from the run before, one decimal. */
    fun changeText(pct: Double): String {
        val rounded = Math.round(pct * 10) / 10.0
        return when {
            rounded > 0 -> "+%.1f%%".format(java.util.Locale.US, rounded)
            rounded < 0 -> "−%.1f%%".format(java.util.Locale.US, -rounded)
            else -> "±0.0%"
        }
    }

    /** The notes under the list: what is not shown, and where the history lives. */
    fun footnotes(h: History, device: String = "phone"): List<String> = buildList {
        if (h.olderVersion > 0) add(if (h.olderVersion == 1) "1 earlier run used an older version of the benchmark, so it isn't shown: its score doesn't compare."
            else "${h.olderVersion} earlier runs used an older version of the benchmark, so they aren't shown: their scores don't compare.")
        if (h.otherPhone > 0) add(if (h.otherPhone == 1) "1 run was made on another $device (restored from a backup), so it isn't shown."
            else "${h.otherPhone} runs were made on another $device (restored from a backup), so they aren't shown.")
        add("Your runs are kept only on this $device: the last $KEPT.")
    }
}
