package com.example.thermohammer.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.File

/**
 * Low-level readers for the measurement pipeline. Every function is defensive:
 * sysfs nodes are SELinux-gated per device/build, so a missing file returns a
 * neutral value instead of throwing.
 */
object StressProbes {

    data class TempZone(val name: String, val tempC: Float)

    data class CoreFreq(
        val core: Int,
        val curKHz: Long,    // scaling_cur_freq — delivered clock
        val capKHz: Long,    // scaling_max_freq — imposed ceiling (throttle evidence)
        val hwMaxKHz: Long,  // cpuinfo_max_freq — hardware maximum
        val online: Boolean
    )

    data class TaskStat(val utime: Long, val stime: Long, val processor: Int)

    data class BattSnap(
        val level: Int,
        val tempC: Float,
        val voltageMv: Int,
        val currentUa: Int?,     // instantaneous µA (abs value), null if unsupported
        val energyNwh: Long?,    // cumulative nWh, null if unsupported
        val charging: Boolean
    )

    private val cpuRoot = File("/sys/devices/system/cpu")
    private const val CLK_TCK = 100.0 // jiffies per second on Android kernels

    // ── CPU frequency / topology ────────────────────────────────────────────────

    private fun readLongFile(dir: File, name: String): Long =
        try { File(dir, name).readText().trim().toLongOrNull() ?: 0L }
        catch (_: Exception) { 0L }

    private fun readStringFile(dir: File, name: String): String =
        try { File(dir, name).readText().trim() } catch (_: Exception) { "" }

    fun readCpuFreqs(coreCount: Int): List<CoreFreq> =
        (0 until coreCount).map { core ->
            val cpuDir = File(cpuRoot, "cpu$core")
            val cfDir = File(cpuDir, "cpufreq")
            // cpu0 typically has no "online" file (boot core is always online)
            val onlineFlag = readStringFile(cpuDir, "online").let { it != "0" }
            val cur = readLongFile(cfDir, "scaling_cur_freq")
            CoreFreq(
                core = core,
                curKHz = cur,
                capKHz = readLongFile(cfDir, "scaling_max_freq"),
                hwMaxKHz = readLongFile(cfDir, "cpuinfo_max_freq"),
                online = onlineFlag && cur > 0
            )
        }

    /** Parse "0-3" / "0,1,2" / "0-3,4-7" cpu list syntax. */
    fun parseCpuList(s: String): List<Int> =
        s.split(',').flatMap { part ->
            val bounds = part.trim().split('-').mapNotNull { it.toIntOrNull() }
            if (bounds.size == 2) (bounds[0]..bounds[1]).toList() else bounds
        }

    /**
     * Cluster topology from cpufreq/related_cpus — cores sharing a clock domain.
     * Falls back to grouping by identical cpuinfo_max_freq, then to one cluster.
     */
    fun readTopology(coreCount: Int): ClusterTopology {
        val groups = LinkedHashMap<String, List<Int>>()
        for (c in 0 until coreCount) {
            val rel = readStringFile(File(cpuRoot, "cpu$c/cpufreq"), "related_cpus")
            val members = parseCpuList(rel).filter { it in 0 until coreCount }
                .ifEmpty { listOf(c) }.sorted()
            groups[members.joinToString(",")] = members
        }
        var clusters = groups.values.distinct().sortedBy { it.first() }
        if (clusters.isEmpty()) clusters = listOf((0 until coreCount).toList())

        val maxPerCluster = clusters.map { cl ->
            cl.maxOf { c -> readLongFile(File(cpuRoot, "cpu$c/cpufreq"), "cpuinfo_max_freq") }
        }
        return ClusterTopology(clusters, maxPerCluster)
    }

    fun readGovernor(coreCount: Int): String {
        for (c in 0 until coreCount) {
            val g = readStringFile(File(cpuRoot, "cpu$c/cpufreq"), "scaling_governor")
            if (g.isNotEmpty()) return g
        }
        return ""
    }

    fun anyFreqReadable(coreCount: Int): Boolean =
        (0 until coreCount).any { c ->
            readLongFile(File(cpuRoot, "cpu$c/cpufreq"), "scaling_cur_freq") > 0
        }

    // ── Per-thread CPU accounting ───────────────────────────────────────────────

    /**
     * /proc/self/task/<tid>/stat — fields after comm (which may contain spaces):
     * f[0] = state (field 3), utime = field 14 → idx 11, stime = field 15 → idx 12,
     * processor (last CPU run on) = field 39 → idx 36.
     */
    fun readTaskStat(tid: Int): TaskStat? =
        try {
            val txt = File("/proc/self/task/$tid/stat").readText()
            val rparen = txt.lastIndexOf(')')
            if (rparen < 0) null else {
                val f = txt.substring(rparen + 1).trim().split(Regex("\\s+"))
                TaskStat(
                    utime = f.getOrNull(11)?.toLongOrNull() ?: 0L,
                    stime = f.getOrNull(12)?.toLongOrNull() ?: 0L,
                    processor = f.getOrNull(36)?.toIntOrNull() ?: -1
                )
            }
        } catch (_: Exception) { null }

    /** First "cpu " line of /proc/stat → [busyJiffies, totalJiffies]. */
    fun readSystemCpu(): LongArray? =
        try {
            val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") }
                ?: return null
            val v = line.substring(4).trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (v.size < 8) return null
            val idle = v[3] + v[4]               // idle + iowait
            val busy = v[0] + v[1] + v[2] + v[5] + v[6] + (v.getOrNull(7) ?: 0L) // user nice sys irq softirq steal
            longArrayOf(busy, busy + idle)
        } catch (_: Exception) { null }

    /** Busy fraction over an interval between two readSystemCpu() snapshots. */
    fun busyFraction(a: LongArray?, b: LongArray?): Float? {
        if (a == null || b == null || b[1] <= a[1]) return null
        return ((b[0] - a[0]).toFloat() / (b[1] - a[1]).toFloat()).coerceIn(0f, 1f)
    }

    fun scheduledFraction(deltaJiffies: Long, deltaWallNs: Long): Float {
        if (deltaWallNs <= 0) return 1f
        val wallJiffies = deltaWallNs / 1e9 * CLK_TCK
        return (deltaJiffies / wallJiffies).toFloat().coerceIn(0f, 1.2f)
    }

    // ── Thermal zones ───────────────────────────────────────────────────────────

    private val cpuZoneHints = listOf(
        "cpu", "soc", "tsens", "apuss", "cluster", "big", "little",
        "kryo", "mtktscpu", "exynos", "dynamiq", "a5", "a7"
    )
    private val skinZoneHints = listOf("skin", "shell", "board", "pa_therm", "xo_therm", "quiet")

    fun readThermalZones(): List<TempZone> {
        val zones = mutableListOf<TempZone>()
        try {
            val dir = File("/sys/class/thermal")
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.startsWith("thermal_zone")) {
                    val typeFile = File(file, "type")
                    val tempFile = File(file, "temp")
                    if (typeFile.exists() && tempFile.exists()) {
                        try {
                            val type = typeFile.readText().trim()
                            val raw = tempFile.readText().trim().toFloatOrNull() ?: return@forEach
                            val temp = if (raw > 1000f || raw < -1000f) raw / 1000f else raw
                            if (temp in -40f..150f) zones.add(TempZone(type, temp))
                        } catch (_: Exception) { /* skip zone */ }
                    }
                }
            }
        } catch (_: Exception) { /* sysfs restricted */ }
        return zones
    }

    private fun matchesHints(name: String, hints: List<String>): Boolean {
        val n = name.lowercase()
        return hints.any { n.contains(it) }
    }

    /** Warmest CPU-adjacent zone temperature, or null if none readable. */
    fun cpuAdjacentTemp(zones: List<TempZone>): Float? {
        val cpu = zones.filter { matchesHints(it.name, cpuZoneHints) }
        return (cpu.ifEmpty { zones }).maxOfOrNull { it.tempC }
    }

    fun skinTemp(zones: List<TempZone>): Float? =
        zones.firstOrNull { matchesHints(it.name, skinZoneHints) }?.tempC

    // ── Battery / power ─────────────────────────────────────────────────────────

    fun battery(context: Context): BattSnap {
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) { null }

        var level = 0
        var tempC = 0f
        var voltageMv = 0
        var charging = false
        if (intent != null) {
            val lvl = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (lvl >= 0 && scale > 0) level = (lvl * 100f / scale).toInt()
            tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || plugged > 0
        }

        var currentUa: Int? = null
        var energyNwh: Long? = null
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val c = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (c != Int.MIN_VALUE) currentUa = kotlin.math.abs(c)
            val e = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            if (e != Long.MIN_VALUE && e > 0) energyNwh = e
        } catch (_: Exception) { /* unsupported properties */ }

        return BattSnap(level, tempC, voltageMv, currentUa, energyNwh, charging)
    }

    // ── Thermal headroom (API 29+) ──────────────────────────────────────────────

    fun headroom(pm: PowerManager): Float? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val h = pm.getThermalHeadroom(0)
                if (h >= 0f && !h.isNaN()) h else null
            } catch (_: Exception) { null }
        } else null

    fun thermalStatus(pm: PowerManager): ThermalState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.FAIR
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                else -> ThermalState.NOMINAL
            }
        }
        return ThermalState.NOMINAL
    }

    // ── Screen brightness (environment context) ─────────────────────────────────

    fun screenBrightness(context: Context): Int? =
        try { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }
        catch (_: Exception) { null }
}
