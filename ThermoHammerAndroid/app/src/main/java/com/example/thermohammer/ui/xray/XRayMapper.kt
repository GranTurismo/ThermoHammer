package com.example.thermohammer.ui.xray

/**
 * Thermal X-ray data model — pure logic, no Compose.
 *
 * Android exposes thermal zones as free-form kernel names ("cpu-1-2-usr", "BIG",
 * "mtktscpu", "VIRTUAL-SKIN"…). This file decides which part of the phone a zone
 * belongs to. The rule it never breaks: a zone we do not recognise stays
 * *unplaced* — it is never guessed onto the schematic.
 */

/** A part of the phone the X-ray can light up. Declaration order = callout order. */
enum class XRayRegion(
    val label: String,
    /** °C at the cool end of the ramp (teal). */
    val scaleLoC: Float,
    /** °C at the molten end of the ramp. A visual scale, not a throttle limit. */
    val scaleHiC: Float
) {
    CPU("CPU / SOC", 30f, 95f),
    GPU("GPU", 30f, 95f),
    NPU("NPU / DSP", 30f, 95f),
    MEM("MEMORY", 30f, 90f),
    CAMERA("CAMERA / ISP", 28f, 80f),
    MODEM("MODEM / RF", 30f, 90f),
    BOARD("LOGIC BOARD", 28f, 75f),
    BATTERY("BATTERY", 25f, 55f),
    CHARGER("CHARGE / USB", 25f, 60f),
    SKIN("SKIN", 28f, 50f);

    /** Position on the thermal ramp (0 = cool teal … 1 = molten) for this region's own scale. */
    fun ramp(tempC: Float): Float =
        ((tempC - scaleLoC) / (scaleHiC - scaleLoC)).coerceIn(0f, 1f)

    /** Same, for a reading in either unit. Headroom is already a 0..1 distance-to-throttle. */
    fun rampFor(value: Float, unit: XRayUnit): Float = when (unit) {
        XRayUnit.CELSIUS -> ramp(value)
        XRayUnit.HEADROOM -> value.coerceIn(0f, 1f)
    }
}

enum class XRayUnit {
    CELSIUS,
    /** PowerManager.getThermalHeadroom(): unitless, 1.0 = the platform's severe-throttling threshold. */
    HEADROOM
}

/** The hottest sensor backing one region, with provenance for the callout. */
data class XRayReading(
    val region: XRayRegion,
    val value: Float,
    val unit: XRayUnit,
    val sensorName: String,   // raw zone name behind [value]
    val sensorCount: Int      // how many sensors were folded into this region
)

data class XRayFrame(
    val readings: Map<XRayRegion, XRayReading>,
    /** Valid zones that matched no region — listed elsewhere, never drawn. */
    val unplaced: List<Pair<String, Float>>
) {
    val instrumented: Int get() = readings.size
}

object XRayMapper {

    /** Readings outside this window are dead/garbage sensors (0 °C, −273, 255…). */
    fun isPlausible(tempC: Float): Boolean = tempC > 1f && tempC < 125f

    fun isPlausibleHeadroom(h: Float?): Boolean = h != null && !h.isNaN() && h >= 0f && h <= 3f

    private fun tokens(name: String): List<String> =
        name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /**
     * Zone name → region, or null when unrecognised. Matching is on whole name
     * tokens (plus a few known compound prefixes/suffixes) rather than raw
     * substrings, so "btmcpu_therm" is not mistaken for the CPU.
     * Order matters: SKIN and BATTERY are checked before the silicon names so
     * "sdm-skin-therm" never lands on the SoC.
     */
    fun classify(zoneName: String): XRayRegion? {
        val t = tokens(zoneName)
        if (t.isEmpty()) return null
        fun exact(vararg s: String) = t.any { it in s }
        fun prefix(vararg p: String) = t.any { tok -> p.any { tok.startsWith(it) } }
        fun suffix(vararg s: String) = t.any { tok -> s.any { tok.endsWith(it) } }

        return when {
            exact("skin", "shell") -> XRayRegion.SKIN
            prefix("batt") || suffix("battery") || exact("bms") -> XRayRegion.BATTERY
            exact("usb", "chg", "charger", "charge", "qi", "wls", "wlc") || prefix("usb") ||
                suffix("chg") -> XRayRegion.CHARGER
            exact("modem", "pa", "rf", "wtr", "qtm", "mmw") || prefix("mdm", "modem") ||
                suffix("mdpa", "nrpa") -> XRayRegion.MODEM
            prefix("npu", "nsp", "apuss") || exact("tpu", "apu", "hvx", "cdsp", "hexagon") ->
                XRayRegion.NPU
            prefix("gpu") || exact("g3d", "mali", "adreno", "kgsl") || suffix("gpu") ->
                XRayRegion.GPU
            prefix("ddr") || exact("dram", "lpddr", "memory", "mem") -> XRayRegion.MEM
            prefix("cam") || exact("isp") -> XRayRegion.CAMERA
            exact("xo", "board", "quiet", "ntc", "neutral", "msm") -> XRayRegion.BOARD
            prefix("cpu", "tsens", "cluster") ||
                exact("soc", "big", "mid", "little", "kryo", "exynos", "dynamiq", "mtktscpu") ->
                XRayRegion.CPU
            else -> null
        }
    }

    /**
     * Fold the engine's live telemetry into one frame.
     * [batteryTempC] comes from BatteryManager — the one public, ungated sensor —
     * and replaces any zone that also classified as BATTERY (same thermistor).
     * [skinHeadroom] is the platform's own skin-thermal estimate; it lights the SKIN
     * rim only when no real skin zone is readable, and is shown in its own unit.
     */
    fun frame(
        zones: List<Pair<String, Float>>,
        batteryTempC: Float,
        skinHeadroom: Float? = null
    ): XRayFrame {
        val buckets = LinkedHashMap<XRayRegion, MutableList<Pair<String, Float>>>()
        val unplaced = mutableListOf<Pair<String, Float>>()

        for ((name, temp) in zones) {
            if (!isPlausible(temp)) continue
            val region = classify(name)
            if (region == null) unplaced += name to temp
            else buckets.getOrPut(region) { mutableListOf() } += name to temp
        }
        if (isPlausible(batteryTempC)) {
            buckets[XRayRegion.BATTERY] = mutableListOf("BatteryManager" to batteryTempC)
        }

        val readings = LinkedHashMap<XRayRegion, XRayReading>()
        for (region in XRayRegion.entries) {
            val list = buckets[region]
            if (list != null) {
                val hottest = list.maxBy { it.second }
                readings[region] =
                    XRayReading(region, hottest.second, XRayUnit.CELSIUS, hottest.first, list.size)
            } else if (region == XRayRegion.SKIN && isPlausibleHeadroom(skinHeadroom)) {
                readings[region] =
                    XRayReading(region, skinHeadroom!!, XRayUnit.HEADROOM, "PowerManager", 1)
            }
        }

        return XRayFrame(readings, unplaced.sortedByDescending { it.second })
    }

    /** Peak-hold: keep, per region, the hotter of the stored and the incoming reading. */
    fun foldPeak(
        peak: Map<XRayRegion, XRayReading>,
        frame: XRayFrame
    ): Map<XRayRegion, XRayReading> {
        if (frame.readings.isEmpty()) return peak
        val out = LinkedHashMap(peak)
        for ((region, incoming) in frame.readings) {
            val held = out[region]
            // Different units are not comparable — the newer reading simply wins.
            if (held == null || held.unit != incoming.unit || incoming.value > held.value) {
                out[region] = incoming
            }
        }
        return out
    }
}
