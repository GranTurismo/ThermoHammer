package com.example.thermohammer.data

import kotlin.math.roundToInt

/**
 * A stress run's throughput as % of its baseline — the curve the share card, the presentation screen and a saved
 * [RunRecord] draw. Pure logic, no Android.
 */
object StressCurve {

    /** At most this many points are kept: plenty for a 1080-px card, small enough to store with every run. */
    const val MAX_POINTS = 120

    /** Below this share of the baseline for [ONSET_STAMPS] stamps in a row = throttled (as StressEngine scores it). */
    private const val ONSET_RATIO = 90.0
    private const val ONSET_STAMPS = 3

    /** Each 1-s stamp's throughput as % of [baseline]; empty without a baseline. */
    fun percentOf(throughput: List<Long>, baseline: Double): List<Double> =
        if (baseline <= 0) emptyList() else throughput.map { it / baseline * 100.0 }

    /**
     * [values] cut into at most [max] equal slices of time, each slice its mean, rounded to 1 decimal — evenly spaced
     * points over the whole run. Shorter input is only rounded.
     */
    fun downsample(values: List<Double>, max: Int = MAX_POINTS): List<Double> {
        if (values.size <= max) return values.map { round1(it) }
        return List(max) { i ->
            val from = (i.toLong() * values.size / max).toInt()
            val to = ((i + 1).toLong() * values.size / max).toInt().coerceAtLeast(from + 1)
            round1(values.subList(from, to).average())
        }
    }

    /**
     * A leaderboard run's curve from its `/stamps/{id}` scores: % of the median of its first 5 seconds (the server
     * keeps no baseline). Falls back to the first 5 stamps when none are that early.
     */
    fun fromScores(elapsedMs: List<Int>, scores: List<Long>): List<Double> {
        if (scores.isEmpty()) return emptyList()
        val early = scores.filterIndexed { i, _ -> elapsedMs.getOrElse(i) { Int.MAX_VALUE } < 5_000 }.ifEmpty { scores.take(5) }
        val base = median(early.map { it.toDouble() })
        return if (base <= 0) emptyList() else scores.map { it / base * 100.0 }
    }

    /** First stamp where the run held below 90 % for 3 stamps in a row, in seconds; null = never throttled. */
    fun onsetSec(elapsedMs: List<Int>, percent: List<Double>): Int? {
        for (i in 0..percent.size - ONSET_STAMPS) {
            if ((0 until ONSET_STAMPS).all { percent[i + it] < ONSET_RATIO }) return elapsedMs.getOrElse(i) { 0 } / 1000
        }
        return null
    }

    /** "1:23" — minutes and seconds. */
    fun mmss(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
