package com.example.thermohammer.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The website's links and the texts that go with a shared run — the same on Android, iOS and Windows (Windows says
 * "PC" where the phones say "phone"). Pure logic, no Android.
 */
object ShareLinks {
    const val SITE = "https://thermohammer.gtgroup.dev"
    const val PLAY = "https://play.google.com/store/apps/details?id=com.granturismo.thermohammer"
    const val APP_STORE = "https://apps.apple.com/app/thermohammer/id6791439615"

    /** A benchmark run's page; the site's front page while the run has no server id (never a broken link). */
    fun runUrl(serverId: Int?): String = if (serverId != null && serverId > 0) "$SITE/run/$serverId/" else "$SITE/"

    fun stressUrl(serverId: Int?): String = if (serverId != null && serverId > 0) "$SITE/stress/$serverId/" else "$SITE/"

    fun certUrl(serverId: Int): String = "$SITE/cert/$serverId/"

    /** How a link is printed on an image: "thermohammer.gtgroup.dev/run/123" — no scheme, no trailing slash. */
    fun display(url: String): String = url.substringAfter("://").trimEnd('/')

    /** Thousands separators as the app shows them everywhere ("%,d"). */
    fun points(total: Int, locale: Locale = Locale.getDefault()): String = String.format(locale, "%,d", total)

    fun benchmarkText(device: String, total: Int, url: String, mine: Boolean, locale: Locale = Locale.getDefault()): String =
        if (mine) "My $device scored ${points(total, locale)} on the ThermoHammer benchmark. Can your phone beat it? $url"
        else "$device scored ${points(total, locale)} on ThermoHammer. Can your phone beat it? $url"

    fun stressText(device: String, capacity: Double, minutes: Int, url: String, mine: Boolean): String {
        val kept = "$device kept ${capacity.roundToInt()}% of its speed through a $minutes-minute ThermoHammer stress test."
        return if (mine) "My $kept Can your phone do better? $url" else "$kept $url"
    }

    fun certificateText(device: String, url: String): String = "Health certificate for my $device: $url"

    /** A stress run's length: the duration type every store and the server use (0 = 5 min, 1 = 15, 2 = 30). */
    fun minutes(durationType: Int): Int = when (durationType) { 1 -> 15; 2 -> 30; else -> 5 }

    /** The load of a stress mode (0 CPU, 1 GPU, 2 both), as the card's label names it. */
    fun load(stressMode: Int?): String = when (stressMode) { 1 -> "GPU"; 2 -> "CPU+GPU"; else -> "CPU" }

    /** "12 Nov 2026" — the date on a card, in English like the rest of it. */
    fun date(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).apply { timeZone = zone }.format(Date(millis))

    /** A server time ("2026-10-27T18:20:11Z", with or without fraction / offset) as a card date (its own day); null if unreadable. */
    fun serverDate(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        val day = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(iso) ?: return null
        val (y, m, d) = day.destructured
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val month = months.getOrNull(m.toInt() - 1) ?: return null
        return "${d.toInt()} $month $y"
    }

    /** A server time (UTC, "2026-10-27T18:20:11Z") as epoch millis; null if unreadable. */
    fun serverMillis(iso: String?): Long? {
        if (iso == null || iso.length < 19) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(iso.substring(0, 19))?.time
        } catch (_: Exception) { null }
    }

    /** The server's OsPlatform: 1 iOS, 2 Android, 3 macOS, 4 Windows. */
    fun osName(os: Int): String = when (os) { 2 -> "Android"; 3 -> "macOS"; 4 -> "Windows"; else -> "iOS" }
}
