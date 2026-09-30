package com.example.thermohammer.data

import android.content.Context
import com.example.thermohammer.engine.StampV2
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class PendingTestResult(
    val id: String, // Unique local identifier (or "online_<id>" for fetched runs)
    val timestamp: Long,
    val durationSeconds: Int,
    val testDurationType: Int, // 0=5min, 1=15min, 2=30min
    val testThreadingType: Int = 1, // 0=Single, 1=Multi
    val minStability: Float,
    val finalStability: Float,          // = deliveredCapacity (sustained AUC %)
    val worstThermalState: Int, // 0=Nominal, 1=Fair, 2=Serious, 3=Critical
    val stamps: List<StampV2>,
    val deviceModel: String,
    val deviceManufacturer: String,
    val osVersion: String,
    val sessionId: Int,
    val encryptionKey: String,
    val initialBatteryLevel: Int = 0,
    val finalBatteryLevel: Int = 0,
    val initialBatteryTemp: Float = 0f,
    val finalBatteryTemp: Float = 0f,
    // ── Schema v2 — measurement-evidence fields ──
    val schemaVersion: Int = 2,
    val baselineScore: Long = 0,        // calibration p95 IPS
    val sustainedRatio: Float = 0f,     // median last-quartile / baseline
    val deliveredCapacity: Float = 0f,  // AUC vs baseline
    val throttleOnsetSec: Int = -1,     // -1 = never throttled
    val timeInThrottlePct: Float = 0f,
    val perfPerWatt: Float = 0f,        // 0 = unmeasured
    val thermalEfficiency: Float = 0f,  // deliveredCapacity / ΔT
    val confidence: Int = 0,
    val validityFlags: Int = 0,         // ValidityFlag bitmask — 0 = verified
    val socModel: String = "",
    val clusterTopology: String = "",   // e.g. "4x1804|3x2457|1x2956"
    val governor: String = "",
    // ── GPU channel (0 = CPU-only run) ──
    val stressMode: Int = 0,            // StressMode.value — 0 CPU, 1 GPU, 2 COMBINED
    val gpuBaselineFps: Double = 0.0,
    val gpuDeliveredCapacity: Float = 0f,
    val gpuSustainedRatio: Float = 0f,
    val gpuName: String = ""
)

class PendingResultStore(context: Context) {
    private val file = File(context.filesDir, "pending_results.json")
    private val gson = Gson()

    fun saveResult(result: PendingTestResult) {
        val list = getAllResults().toMutableList()
        list.add(result)
        file.writeText(gson.toJson(list))
    }

    fun getAllResults(): List<PendingTestResult> {
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<PendingTestResult>>() {}.type
            val all: List<PendingTestResult> = gson.fromJson(file.readText(), type) ?: emptyList()
            // Drop legacy (schema v1) entries: their stamps carry {elapsedMs, score,
            // thermalState} which deserializes into StampV2 as all-zero fields.
            // A genuine v2 stamp always has tNs ≈ seconds·1e9 and ipsTotal > 0.
            all.filter { r ->
                r.stamps.isEmpty() || r.stamps.any { it.tNs > 0L && it.ipsTotal > 0L }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteResult(id: String) {
        val list = getAllResults().filter { it.id != id }
        file.writeText(gson.toJson(list))
    }
}
