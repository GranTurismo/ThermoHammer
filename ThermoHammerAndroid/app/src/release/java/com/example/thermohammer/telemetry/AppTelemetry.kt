package com.example.thermohammer.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlin.math.roundToInt

/**
 * Production-only Firebase Analytics and Crashlytics wrapper.
 * Never called from worker loops or measured ticks — only at run boundaries and uploads.
 */
object AppTelemetry {
    @Volatile
    private var analytics: FirebaseAnalytics? = null

    private fun clipParam(value: String, maxLen: Int = 100): String =
        if (value.length <= maxLen) value else value.take(maxLen)

    private fun logEvent(name: String, block: Bundle.() -> Unit) {
        val fa = analytics ?: return
        try {
            fa.logEvent(name, Bundle().apply(block))
        } catch (_: Exception) {
        }
    }

    fun init(context: Context, deviceModel: String, socModel: String) {
        if (analytics != null) return
        try {
            val appCtx = context.applicationContext
            val fa = FirebaseAnalytics.getInstance(appCtx)
            analytics = fa

            val version = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: "?"
            val memInfo = ActivityManager.MemoryInfo()
            (appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memInfo)
            val ramGb = (memInfo.totalMem / 1e9).roundToInt()

            fa.setUserProperty("app_platform", "android")
            fa.setUserProperty("device_model", clipParam(deviceModel, 36))
            if (socModel.isNotBlank()) fa.setUserProperty("soc_or_cpu", clipParam(socModel, 36))
            fa.setUserProperty("ram_gb", ramGb.toString())
            fa.setUserProperty("os_version", clipParam("Android ${Build.VERSION.RELEASE}", 36))
            fa.setUserProperty("app_version", clipParam(version, 36))

            val crash = FirebaseCrashlytics.getInstance()
            crash.setCustomKey("app_platform", "android")
            crash.setCustomKey("device_model", deviceModel)
            if (socModel.isNotBlank()) crash.setCustomKey("soc_or_cpu", socModel)
            crash.setCustomKey("active_mode", "idle")
            crash.setCustomKey("active_section", "none")
        } catch (_: Exception) {
        }
    }

    fun setPhaseContext(mode: String, section: String) {
        try {
            val crash = FirebaseCrashlytics.getInstance()
            crash.setCustomKey("active_mode", mode)
            crash.setCustomKey("active_section", section)
        } catch (_: Exception) {
        }
    }

    fun clearPhaseContext() = setPhaseContext("idle", "none")

    fun logStressStarted(mode: String, durationMin: Int, threading: String) {
        setPhaseContext("stress", "stress_$mode")
        logEvent("stress_started") {
            putString("mode", clipParam(mode))
            putLong("duration_min", durationMin.toLong())
            putString("threads", clipParam(threading))
        }
    }

    fun logStressFinished(mode: String, durationMin: Int, status: String, verified: Boolean, capacity: Double) {
        clearPhaseContext()
        logEvent("stress_finished") {
            putString("mode", clipParam(mode))
            putLong("duration_min", durationMin.toLong())
            putString("status", clipParam(status))
            putLong("verified", if (verified) 1L else 0L)
            putDouble("delivered_capacity", capacity)
        }
    }

    fun logBenchmarkStarted(partial: Boolean) {
        setPhaseContext("benchmark", "preparing")
        logEvent("benchmark_started") {
            putString("mode", if (partial) "partial" else "full")
        }
    }

    fun logBenchmarkFinished(status: String, totalScore: Int, verified: Boolean, durationSec: Int) {
        clearPhaseContext()
        logEvent("benchmark_finished") {
            putString("status", clipParam(status))
            putLong("total_score", totalScore.toLong())
            putLong("verified", if (verified) 1L else 0L)
            putLong("duration_s", durationSec.toLong())
        }
    }

    fun logResultUploaded(kind: String, success: Boolean) {
        logEvent("result_uploaded") {
            putString("kind", clipParam(kind))
            putLong("success", if (success) 1L else 0L)
        }
    }
}
