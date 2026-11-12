package com.example.thermohammer.data

import android.content.Context

/**
 * This phone's name for people: the name on the box the server resolved its code to ("HONOR 600 Pro" for VKP-NX9) once
 * the app has seen it (Phone Health, the leaderboard), otherwise what it reports ("HONOR VKP-NX9"). No request of its own.
 */
object MyDeviceName {
    private const val PREFS = "device_name"
    private const val KEY = "board_name"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }
            ?: BenchmarkRecord.thisDeviceModel()

    fun remember(context: Context, boardName: String?) {
        if (boardName.isNullOrBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, boardName).apply()
    }
}
