package com.example.thermohammer.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * On-device history of finished runs, used only for the "before / after an OS update"
 * comparison. Nothing here is uploaded. Corrupt or unreadable data degrades to "no history".
 */
class RunHistoryStore(context: Context) {
    private val file = File(context.filesDir, "run_history.json")
    private val prefs = context.getSharedPreferences("update_impact", Context.MODE_PRIVATE)
    private val gson = Gson()

    @Synchronized
    fun all(): List<RunRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<RunRecord>>() {}.type
            val loaded: List<RunRecord> = gson.fromJson(file.readText(), type) ?: emptyList()
            // Gson bypasses Kotlin null-safety: drop any row that decoded without its essentials.
            loaded.filter { it.id.present() && it.deviceModel.present() && it.osVersion.present() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(run: RunRecord) {
        try {
            val updated = UpdateImpact.appendCapped(all(), run)
            // Write-then-rename so a crash mid-write cannot leave half a file behind.
            val tmp = File(file.parentFile, "run_history.json.tmp")
            tmp.writeText(gson.toJson(updated))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) { /* history is best-effort; never break a finished run */ }
    }

    fun isDismissed(insight: UpdateImpact.Insight): Boolean =
        prefs.getStringSet(KEY_DISMISSED, emptySet())?.contains(insight.dismissKey) == true

    fun dismiss(insight: UpdateImpact.Insight) {
        // getStringSet's result must not be mutated in place — copy first.
        val next = (prefs.getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet()).toMutableSet()
        next.add(insight.dismissKey)
        prefs.edit().putStringSet(KEY_DISMISSED, next.toList().takeLast(50).toSet()).apply()
    }

    /** True null-check that the compiler cannot fold away on a "non-null" type Gson may have broken. */
    private fun Any?.present() = this != null

    private companion object {
        const val KEY_DISMISSED = "dismissed"
    }
}
