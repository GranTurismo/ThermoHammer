package com.example.thermohammer.data

import com.example.thermohammer.network.BenchmarkDetailDto
import java.util.Locale

/** Which of the two compared runs leads on one number. */
enum class Lead { A, B, EVEN, NONE }

/**
 * Who is ahead on one number of two runs, and by how much: [advantage] is how many percent the better result beats the
 * other (null when only one run has a result, or the other one is zero). Results closer than [EVEN_WITHIN] are even.
 */
data class Duel(val winner: Lead, val advantage: Double? = null) {
    companion object {
        const val EVEN_WITHIN = 0.01
        val NONE = Duel(Lead.NONE)

        /**
         * [better] is the server's "higher" or "lower"; anything else is text or context, not a contest. A missing number
         * means the test didn't run on that device: the other one is ahead, but not by "100 %".
         */
        fun of(a: Double?, b: Double?, better: String?): Duel {
            if (better != "higher" && better != "lower") return NONE
            val lower = better == "lower"
            fun has(v: Double?) = v != null && !v.isNaN() && (if (lower) v >= 0 else v > 0)
            val hasA = has(a)
            val hasB = has(b)
            if (!hasA && !hasB) return NONE
            if (!hasB) return Duel(Lead.A)
            if (!hasA) return Duel(Lead.B)
            val x = a!!
            val y = b!!
            if (x == y) return Duel(Lead.EVEN, 0.0)
            val aAhead = if (lower) x < y else x > y
            val hi = maxOf(x, y)
            val lo = minOf(x, y)
            if (lo <= 0.0) return Duel(if (aAhead) Lead.A else Lead.B)   // 0 % dropped against 2 %: ahead, no ratio
            val pct = (hi / lo - 1) * 100
            return if (hi / lo - 1 < EVEN_WITHIN) Duel(Lead.EVEN, pct) else Duel(if (aAhead) Lead.A else Lead.B, pct)
        }

        /** Points and scores: more is better, 0 means nothing was scored. */
        fun scores(a: Int?, b: Int?): Duel = of(a?.toDouble(), b?.toDouble(), "higher")
    }
}

data class ComparedRow(val label: String, val a: String?, val b: String?, val duel: Duel)

data class ComparedGroup(val title: String, val pointsA: Int?, val pointsB: Int?, val duel: Duel, val rows: List<ComparedRow>) {
    val shareA: Float get() = share(pointsA, pointsB)
    val shareB: Float get() = share(pointsB, pointsA)
}

data class ComparedSection(
    val key: String, val title: String, val scoreA: Int?, val scoreB: Int?, val duel: Duel, val groups: List<ComparedGroup>
) {
    /** Each side's score as a share of the larger one: what the bars and the radar draw. */
    val shareA: Float get() = share(scoreA, scoreB)
    val shareB: Float get() = share(scoreB, scoreA)
}

private fun share(x: Int?, y: Int?): Float {
    val top = maxOf(x ?: 0, y ?: 0)
    return if (top <= 0) 0f else (x ?: 0).coerceAtLeast(0).toFloat() / top
}

/**
 * Two leaderboard runs side by side: every section, group and row of both, matched by the server's keys, titles and
 * labels (so a measurement the server adds later is compared too), each with who is ahead and by how much.
 */
class BenchmarkComparison private constructor(
    val a: BenchmarkDetailDto,
    val b: BenchmarkDetailDto,
    val total: Duel,
    val sections: List<ComparedSection>
) {
    val winsA: Int get() = sections.count { it.duel.winner == Lead.A }
    val winsB: Int get() = sections.count { it.duel.winner == Lead.B }
    val even: Int get() = sections.count { it.duel.winner == Lead.EVEN }

    /** Each run's total as a share of both together: the split bar. */
    val totalShareA: Float get() = (a.totalScore.coerceAtLeast(0) + b.totalScore.coerceAtLeast(0)).let { if (it == 0) 0.5f else a.totalScore.coerceAtLeast(0).toFloat() / it }

    /** The same two runs the other way round. */
    fun swapped(): BenchmarkComparison = of(b, a)

    /** One line on the overall result, e.g. "HONOR 600 Pro scores 23% higher overall". */
    fun headline(nameA: String, nameB: String): String = when (total.winner) {
        Lead.EVEN -> "Dead heat: within 1% of each other overall"
        Lead.A -> total.advantage?.let { "$nameA scores ${percentText(it)} higher overall" } ?: "$nameA wins: the other has no total"
        Lead.B -> total.advantage?.let { "$nameB scores ${percentText(it)} higher overall" } ?: "$nameB wins: the other has no total"
        Lead.NONE -> "Neither run has a total score"
    }

    /** How the sections went, from the side of whoever leads, e.g. "Ahead in 4 of 6 sections · 1 even". */
    fun sectionsLine(): String {
        val lead = maxOf(winsA, winsB)
        val counted = sections.count { it.duel.winner != Lead.NONE }
        val base = if (winsA == winsB) "Sections split $winsA–$winsB" else "Ahead in $lead of $counted sections"
        return if (even > 0) "$base · $even even" else base
    }

    companion object {
        fun of(a: BenchmarkDetailDto, b: BenchmarkDetailDto): BenchmarkComparison {
            val sections = union(a.sections, b.sections) { it.key }.map { (sa, sb) ->
                val groups = union(sa?.groups.orEmpty(), sb?.groups.orEmpty()) { it.title }.map { (ga, gb) ->
                    val rows = union(ga?.rows.orEmpty(), gb?.rows.orEmpty()) { it.label }.map { (ra, rb) ->
                        ComparedRow((ra ?: rb)!!.label, ra?.value, rb?.value, Duel.of(ra?.number, rb?.number, ra?.better ?: rb?.better))
                    }
                    ComparedGroup((ga ?: gb)!!.title, ga?.points, gb?.points, Duel.scores(ga?.points, gb?.points), rows)
                }
                val s = (sa ?: sb)!!
                ComparedSection(s.key, s.title, sa?.score, sb?.score, Duel.scores(sa?.score, sb?.score), groups)
            }
            return BenchmarkComparison(a, b, Duel.scores(a.totalScore, b.totalScore), sections)
        }

        /** Both lists matched by [key]: A's order first, then whatever only B has. */
        private fun <T, K> union(a: List<T>, b: List<T>, key: (T) -> K): List<Pair<T?, T?>> {
            val byB = b.associateBy(key)
            val seen = HashSet<K>()
            val out = ArrayList<Pair<T?, T?>>()
            for (x in a) { val k = key(x); if (seen.add(k)) out += x to byB[k] }
            for (y in b) { val k = key(y); if (seen.add(k)) out += null to y }
            return out
        }
    }
}

/** "23%" or "4.2%": one decimal while it's small. */
fun percentText(pct: Double): String = if (pct >= 9.95) "%.0f%%".format(Locale.US, pct) else "%.1f%%".format(Locale.US, pct)

/** "+23%": the badge next to the better result. */
fun advantageText(pct: Double): String = "+" + percentText(pct)
