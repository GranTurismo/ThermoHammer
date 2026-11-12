package com.example.thermohammer.data

import android.content.Context
import android.util.Log
import com.example.thermohammer.engine.SpeedCheck
import com.example.thermohammer.engine.SpeedMeasurement
import com.google.gson.Gson

/** The lost-speed check in progress: the first measurement and, once the user checked again, the second. */
data class SpeedCheckSession(val first: SpeedMeasurement, val second: SpeedMeasurement? = null)

/**
 * The latest lost-speed check, one prefs entry — so "check again" still works after the user went to Settings and back,
 * even after the app was closed. A first check older than an hour is dropped.
 */
class SpeedCheckStore(context: Context) {
    private val prefs = context.getSharedPreferences("speed_check", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(now: Long = System.currentTimeMillis()): SpeedCheckSession? {
        val json = prefs.getString(KEY, null) ?: return null
        return try {
            val s = gson.fromJson(json, SpeedCheckSession::class.java)
            // Gson bypasses Kotlin null-safety: a session without its windows is no session
            s?.takeIf { usable(it.first) && (it.second == null || usable(it.second)) && !SpeedCheck.isExpired(it.first, now) }
        } catch (e: Exception) {
            Log.w("SpeedCheck", "saved check unreadable: $e")
            null
        }
    }

    fun save(session: SpeedCheckSession) = prefs.edit().putString(KEY, gson.toJson(session)).apply()

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun usable(m: SpeedMeasurement?): Boolean = m != null && (m.perSecond as List<Double>?)?.isNotEmpty() == true

    private companion object {
        const val KEY = "session"
    }
}

/**
 * The newest Phone Health answer, as the result screen showed it: what the lost-speed check's note line and the
 * certificate's health line quote without asking the server again. [verdict] is PhoneHealth.Verdict's name.
 */
data class HealthMemo(val recordId: String?, val verdict: String, val percent: Int?, val runs: Int, val model: String,
                      val timestamp: Long)

object HealthMemoStore {
    private const val PREFS = "phone_health"
    private const val KEY = "last"

    fun save(context: Context, memo: HealthMemo) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, Gson().toJson(memo)).apply()

    fun load(context: Context): HealthMemo? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { Gson().fromJson(it, HealthMemo::class.java) }
            ?.takeIf { (it.verdict as String?) != null && (it.model as String?) != null }
    } catch (_: Exception) { null }

    /** The note for the lost-speed check: only for the newest benchmark run, and only when it came out slower. */
    fun noteFor(memo: HealthMemo?, newestRecordId: String?): SpeedCheck.HealthNote? {
        if (memo == null || memo.recordId == null || memo.recordId != newestRecordId) return null
        val percent = memo.percent ?: return null
        return SpeedCheck.HealthNote(percent, memo.model, memo.verdict == "SLOWER" || memo.verdict == "MUCH_SLOWER")
    }
}
