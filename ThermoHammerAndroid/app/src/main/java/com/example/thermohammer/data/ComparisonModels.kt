package com.example.thermohammer.data

import com.example.thermohammer.engine.ThermalState
import java.util.Locale

data class ComparisonAnalysis(
    val runA: PendingTestResult,
    val runB: PendingTestResult,
    val peakScoreA: Double,
    val peakScoreB: Double,
    val avgScoreA: Double,
    val avgScoreB: Double,
    val peakScoreDeltaPct: Float,
    val avgScoreDeltaPct: Float,
    val finalStabilityDeltaPct: Float,
    val throttleOnsetTimeA: Int?, // seconds to drop below 90%
    val throttleOnsetTimeB: Int?,
    val worstThermalA: ThermalState,
    val worstThermalB: ThermalState,
    val batteryDrainA: Float, // % per min
    val batteryDrainB: Float,
    val tempRiseA: Float, // °C per min
    val tempRiseB: Float,
    val winner: ComparisonWinner,
    val summaryText: String
)

enum class ComparisonWinner {
    RUN_A, RUN_B, EQUAL
}

object ComparisonEngine {
    fun analyze(runA: PendingTestResult, runB: PendingTestResult): ComparisonAnalysis {
        val stampsA = runA.stamps
        val stampsB = runB.stamps

        val peakA = if (stampsA.isNotEmpty()) stampsA.maxOf { it.score.toDouble() } else maxOf(100.0, runA.finalStability.toDouble() * 2500.0)
        val peakB = if (stampsB.isNotEmpty()) stampsB.maxOf { it.score.toDouble() } else maxOf(100.0, runB.finalStability.toDouble() * 2500.0)
        val peakDelta = if (peakA > 0) (((peakB - peakA) / peakA) * 100.0).toFloat() else 0f

        val avgA = if (stampsA.isNotEmpty()) stampsA.map { it.score.toDouble() }.average() else maxOf(90.0, runA.finalStability.toDouble() * 2200.0)
        val avgB = if (stampsB.isNotEmpty()) stampsB.map { it.score.toDouble() }.average() else maxOf(90.0, runB.finalStability.toDouble() * 2200.0)
        val avgDelta = if (avgA > 0) (((avgB - avgA) / avgA) * 100.0).toFloat() else 0f

        val stabDelta = runB.finalStability - runA.finalStability

        // Find throttling onset (first stamp where score drops below 90% of peak)
        val onsetA = if (stampsA.isNotEmpty()) stampsA.firstOrNull { it.score.toDouble() < peakA * 0.9 }?.elapsedMs?.let { (it / 1000).toInt() } else null
        val onsetB = if (stampsB.isNotEmpty()) stampsB.firstOrNull { it.score.toDouble() < peakB * 0.9 }?.elapsedMs?.let { (it / 1000).toInt() } else null

        val durationMinA = maxOf(1f, runA.durationSeconds / 60f)
        val durationMinB = maxOf(1f, runB.durationSeconds / 60f)

        val batDrainA = maxOf(0, runA.initialBatteryLevel - runA.finalBatteryLevel) / durationMinA
        val batDrainB = maxOf(0, runB.initialBatteryLevel - runB.finalBatteryLevel) / durationMinB

        val tempRiseA = maxOf(0f, runA.finalBatteryTemp - runA.initialBatteryTemp) / durationMinA
        val tempRiseB = maxOf(0f, runB.finalBatteryTemp - runB.initialBatteryTemp) / durationMinB

        val worstA = ThermalState.entries.getOrElse(runA.worstThermalState) { ThermalState.NOMINAL }
        val worstB = ThermalState.entries.getOrElse(runB.worstThermalState) { ThermalState.CRITICAL }

        // Determine winner
        var scoreA = 0
        var scoreB = 0
        if (runA.finalStability > runB.finalStability) scoreA += 2 else if (runB.finalStability > runA.finalStability) scoreB += 2
        if (avgA > avgB) scoreA += 2 else if (avgB > avgA) scoreB += 2
        if (batDrainA < batDrainB) scoreA += 1 else if (batDrainB < batDrainA) scoreB += 1
        if (tempRiseA < tempRiseB) scoreA += 1 else if (tempRiseB < tempRiseA) scoreB += 1

        val winner = when {
            scoreA > scoreB -> ComparisonWinner.RUN_A
            scoreB > scoreA -> ComparisonWinner.RUN_B
            else -> ComparisonWinner.EQUAL
        }

        val nameA = "${runA.deviceManufacturer} ${runA.deviceModel}"
        val nameB = "${runB.deviceManufacturer} ${runB.deviceModel}"

        val summary = buildString {
            if (winner == ComparisonWinner.RUN_A) {
                append("$nameA demonstrated superior thermal stability (${String.format(Locale.US, "%.1f", runA.finalStability)}% vs ${String.format(Locale.US, "%.1f", runB.finalStability)}%). ")
            } else if (winner == ComparisonWinner.RUN_B) {
                append("$nameB demonstrated superior thermal stability (${String.format(Locale.US, "%.1f", runB.finalStability)}% vs ${String.format(Locale.US, "%.1f", runA.finalStability)}%). ")
            } else {
                append("Both runs demonstrated equivalent thermal performance. ")
            }

            if (onsetA != null && onsetB != null) {
                if (onsetA > onsetB) {
                    append("Run A delayed thermal throttling by ${onsetA - onsetB}s longer than Run B. ")
                } else if (onsetB > onsetA) {
                    append("Run B delayed thermal throttling by ${onsetB - onsetA}s longer than Run A. ")
                }
            } else if (onsetA == null && onsetB != null) {
                append("Run A maintained sustained performance without significant throttling, whereas Run B throttled at ${onsetB}s. ")
            } else if (onsetB == null && onsetA != null) {
                append("Run B maintained sustained performance without significant throttling, whereas Run A throttled at ${onsetA}s. ")
            }
        }

        return ComparisonAnalysis(
            runA = runA,
            runB = runB,
            peakScoreA = peakA,
            peakScoreB = peakB,
            avgScoreA = avgA,
            avgScoreB = avgB,
            peakScoreDeltaPct = peakDelta,
            avgScoreDeltaPct = avgDelta,
            finalStabilityDeltaPct = stabDelta,
            throttleOnsetTimeA = onsetA,
            throttleOnsetTimeB = onsetB,
            worstThermalA = worstA,
            worstThermalB = worstB,
            batteryDrainA = batDrainA,
            batteryDrainB = batDrainB,
            tempRiseA = tempRiseA,
            tempRiseB = tempRiseB,
            winner = winner,
            summaryText = summary
        )
    }
}
