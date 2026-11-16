package com.example.thermohammer.telemetry

import android.content.Context

/**
 * No-op telemetry stub for debug and bench builds so non-production builds never collect data or affect speed.
 */
object AppTelemetry {
    fun init(context: Context, deviceModel: String, socModel: String) {}
    fun setPhaseContext(mode: String, section: String) {}
    fun clearPhaseContext() {}
    fun logStressStarted(mode: String, durationMin: Int, threading: String) {}
    fun logStressFinished(mode: String, durationMin: Int, status: String, verified: Boolean, capacity: Double) {}
    fun logBenchmarkStarted(partial: Boolean) {}
    fun logBenchmarkFinished(status: String, totalScore: Int, verified: Boolean, durationSec: Int) {}
    fun logResultUploaded(kind: String, success: Boolean) {}
}
