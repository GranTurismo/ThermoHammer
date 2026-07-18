package com.example.thermohammer.data

import android.content.Context
import com.example.thermohammer.network.DeviceHammerStamp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class PendingTestResult(
    val id: String, // Unique local identifier
    val timestamp: Long,
    val durationSeconds: Int,
    val testDurationType: Int, // 0=5min, 1=15min, 2=30min
    val minStability: Float,
    val finalStability: Float,
    val worstThermalState: Int, // 0=Nominal, 1=Fair, 2=Serious, 3=Critical
    val stamps: List<DeviceHammerStamp>,
    val deviceModel: String,
    val deviceManufacturer: String,
    val osVersion: String,
    val sessionId: Int,
    val encryptionKey: String
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
            gson.fromJson(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteResult(id: String) {
        val list = getAllResults().filter { it.id != id }
        file.writeText(gson.toJson(list))
    }
}
