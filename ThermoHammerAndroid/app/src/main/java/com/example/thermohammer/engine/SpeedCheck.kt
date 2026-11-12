package com.example.thermohammer.engine

import java.util.Locale
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// FIND MY LOST SPEED — a 30-second CPU check that measures, says what is
// holding the phone back, and measures again after the user changed a setting.
// Pure logic, no Android: SpeedCheckRunner measures, the overlay shows it.
// iOS and Windows repeat the same numbers and texts (features-spec §2).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One 30-second check: [perSecond] work units per second in each 1-s window (a unit = one round of the multi-core CPU
 * test's six kernels), and the phone's state when it ended. [thermalStatus] is Android's own level
 * (PowerManager.THERMAL_STATUS_*: 0 none, 1 light, 2 moderate, 3 severe …), −1 where Android can't tell (before 10).
 */
data class SpeedMeasurement(
    val perSecond: List<Double>,
    val powerSaver: Boolean,
    val charging: Boolean,
    val thermalStatus: Int,
    val timestamp: Long
) {
    val burst: Double get() = SpeedCheck.burst(perSecond)
    val sustained: Double get() = SpeedCheck.sustained(perSecond)
    val drop: Double get() = SpeedCheck.drop(burst, sustained)
}

object SpeedCheck {
    const val SECONDS = 30
    /** A sustained speed this far under the burst is lost speed. */
    const val DROP_LIMIT = 0.25
    /** PowerManager.THERMAL_STATUS_LIGHT: up to here the phone is cool. */
    const val THERMAL_LIGHT = 1
    const val FASTER_FROM = 1.10
    const val SLOWER_FROM = 0.90
    /** A first check older than this is too old to compare a second one with. */
    const val EXPIRY_MS = 60 * 60 * 1000L

    /** Seconds 1–5: the mean of the first five windows. */
    fun burst(perSecond: List<Double>): Double = perSecond.take(5).takeIf { it.isNotEmpty() }?.average() ?: 0.0

    /** Seconds 11–30: the median of the windows after the first ten (all of them for a run cut short). */
    fun sustained(perSecond: List<Double>): Double = median(perSecond.drop(10).ifEmpty { perSecond })

    /** 1 − sustained / burst; 0 without a burst. */
    fun drop(burst: Double, sustained: Double): Double = if (burst <= 0) 0.0 else 1 - sustained / burst

    fun isExpired(m: SpeedMeasurement, now: Long): Boolean = now - m.timestamp > EXPIRY_MS

    // ── Diagnosis ────────────────────────────────────────────────────────────

    enum class Kind { POWER_SAVING, HELD_BACK_WHILE_COOL, HEAT, NO_LOST_SPEED }

    /** One cause: its title, what to do, and (held back while cool) where the setting is on this brand. */
    data class Cause(val kind: Kind, val title: String, val text: String, val steps: List<String> = emptyList())

    data class Diagnosis(val causes: List<Cause>, val note: String?)

    /** The last benchmark's Phone Health: [percent] of the median [model], and whether it was SLOWER / MUCH_SLOWER. */
    data class HealthNote(val percent: Int, val model: String, val slower: Boolean)

    /**
     * Every cause that applies, strongest first: power saving, held back while cool, heat — or none found. [health] adds
     * a note line when the last benchmark came out slower than the model's median.
     */
    fun diagnose(m: SpeedMeasurement, manufacturer: String, health: HealthNote? = null): Diagnosis {
        val causes = ArrayList<Cause>()
        if (m.powerSaver) causes += Cause(Kind.POWER_SAVING, "POWER SAVING IS ON",
            "Battery saver caps the CPU. Turn it off and check again.")
        if (m.drop >= DROP_LIMIT) {
            causes += if (m.thermalStatus <= THERMAL_LIGHT) Cause(Kind.HELD_BACK_WHILE_COOL, "HELD BACK WHILE COOL",
                "Your phone slows down after a short burst even though it isn't hot — a power setting is holding it back, not heat.",
                listOf(vendorSteps(manufacturer), "Names differ between versions — look for the closest match."))
            else Cause(Kind.HEAT, "HEAT",
                "It slowed down because it got hot. Take it out of its case, unplug it, let it cool, and check again.")
        }
        if (causes.isEmpty()) causes += Cause(Kind.NO_LOST_SPEED, "NO LOST SPEED FOUND",
            "Your phone kept ${kept(m)}% of its burst speed. Nothing is holding it back.")
        val note = health?.takeIf { it.slower && it.percent < 100 }
            ?.let { "Your last benchmark was ${100 - it.percent}% below the median ${it.model}." }
        return Diagnosis(causes, note)
    }

    /** How much of its burst speed the phone kept, %, at most 100. */
    fun kept(m: SpeedMeasurement): Int = ((1 - m.drop) * 100).roundToInt().coerceIn(0, 100)

    /** Where each brand keeps its performance setting (Build.MANUFACTURER, any case). */
    fun vendorSteps(manufacturer: String): String = when (manufacturer.trim().lowercase(Locale.ROOT)) {
        "honor", "huawei" -> "Settings › Battery › Performance mode — turn it on. On an HONOR 600 Pro this doubled the CPU score (2.08×)."
        "xiaomi", "redmi", "poco" -> "Settings › Battery — choose Performance mode if it's offered, and turn Battery saver off."
        "samsung" -> "Settings › Battery (or Device care) › Performance profile — choose Standard; Light slows the CPU."
        "oneplus", "oppo", "realme" -> "Settings › Battery › More settings › High performance mode — turn it on."
        "vivo", "iqoo" -> "Settings › Battery — turn power saving off and choose high performance if it's offered."
        "google" -> "Settings › Battery › Battery Saver — turn it off. Pixels have no separate performance mode."
        else -> "Settings › Battery — turn off power saving and pick the highest performance mode your phone offers."
    }

    // ── Second check ─────────────────────────────────────────────────────────

    enum class Change { FASTER, SLOWER, NO_CHANGE }

    data class Comparison(val ratio: Double, val change: Change) {
        fun title(locale: Locale = Locale.getDefault()): String = when (change) {
            Change.FASTER -> String.format(locale, "%.2f× FASTER", ratio)
            Change.SLOWER -> "SLOWER"
            Change.NO_CHANGE -> "NO CHANGE"
        }

        val text: String
            get() = when (change) {
                Change.FASTER -> "You got back ${((ratio - 1) * 100).roundToInt()}% speed."
                Change.SLOWER -> "It's slower now — change the setting back."
                Change.NO_CHANGE -> "That setting wasn't holding your phone back."
            }
    }

    /** sustained₂ / sustained₁: from 1.10 faster, to 0.90 slower, between them no change. */
    fun compare(first: SpeedMeasurement, second: SpeedMeasurement): Comparison {
        val ratio = if (first.sustained > 0) second.sustained / first.sustained else 1.0
        return Comparison(ratio, when {
            ratio >= FASTER_FROM -> Change.FASTER
            ratio <= SLOWER_FROM -> Change.SLOWER
            else -> Change.NO_CHANGE
        })
    }

    /**
     * A stress run that lost speed while cool — a power setting, not heat: it kept under 80 % and, where it began to
     * throttle, the phone was at most at its light thermal level. The stamps keep ThermalState (NOMINAL, FAIR, …),
     * where FAIR covers Android's light and moderate, so "at most light" reads as at most FAIR there.
     */
    fun lostSpeedWhileCool(capacity: Double, onsetSec: Int?, stamps: List<StampV2>): Boolean {
        if (capacity >= 80 || onsetSec == null || stamps.isEmpty()) return false
        val atOnset = stamps.firstOrNull { it.elapsedMs / 1000 >= onsetSec } ?: stamps.last()
        return atOnset.thermalStatus <= ThermalState.FAIR.ordinal
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}
