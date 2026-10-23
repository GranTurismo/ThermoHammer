package com.example.thermohammer.ui.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XRayMapperTest {

    private fun c(name: String) = XRayMapper.classify(name)

    // ── Classification: names as real kernels report them ────────────────────

    @Test fun qualcommZones() {
        assertEquals(XRayRegion.CPU, c("cpu-0-0-usr"))
        assertEquals(XRayRegion.CPU, c("cpuss-1-usr"))
        assertEquals(XRayRegion.CPU, c("tsens_tz_sensor12"))
        assertEquals(XRayRegion.GPU, c("gpuss-0-usr"))
        assertEquals(XRayRegion.NPU, c("nspss-0"))
        assertEquals(XRayRegion.NPU, c("q6-hvx-usr"))
        assertEquals(XRayRegion.MEM, c("ddr-usr"))
        assertEquals(XRayRegion.MODEM, c("mdmss-0-usr"))
        assertEquals(XRayRegion.MODEM, c("pa-therm0"))
        assertEquals(XRayRegion.BOARD, c("xo-therm"))
        assertEquals(XRayRegion.BOARD, c("quiet-therm"))
        assertEquals(XRayRegion.BATTERY, c("battery"))
        assertEquals(XRayRegion.CHARGER, c("usb-therm"))
        assertEquals(XRayRegion.SKIN, c("sdm-skin-therm"))
    }

    @Test fun tensorZones() {
        assertEquals(XRayRegion.CPU, c("BIG"))
        assertEquals(XRayRegion.CPU, c("MID"))
        assertEquals(XRayRegion.CPU, c("LITTLE"))
        assertEquals(XRayRegion.GPU, c("G3D"))
        assertEquals(XRayRegion.NPU, c("TPU"))
        assertEquals(XRayRegion.CAMERA, c("ISP"))
        assertEquals(XRayRegion.SKIN, c("VIRTUAL-SKIN"))
        assertEquals(XRayRegion.CHARGER, c("usb_pwr_therm"))
        assertEquals(XRayRegion.CHARGER, c("qi_therm"))
        assertEquals(XRayRegion.MODEM, c("modem"))
    }

    @Test fun mediaTekZones() {
        assertEquals(XRayRegion.CPU, c("mtktscpu"))
        assertEquals(XRayRegion.GPU, c("mtktsgpu"))
        assertEquals(XRayRegion.BATTERY, c("mtktsbattery"))
        assertEquals(XRayRegion.MODEM, c("mtktsbtsmdpa"))
        assertEquals(XRayRegion.SKIN, c("shell_back"))
        assertEquals(XRayRegion.SKIN, c("shell_frame"))
        assertEquals(XRayRegion.BOARD, c("board_ntc"))
        assertEquals(XRayRegion.CHARGER, c("tz_chg"))
    }

    @Test fun classificationIsCaseAndSeparatorInsensitive() {
        assertEquals(XRayRegion.CPU, c("CPU_0"))
        assertEquals(XRayRegion.GPU, c("Gpu.Therm"))
    }

    // ── The "never guess" rule ───────────────────────────────────────────────

    @Test fun skinBeatsSiliconNames() {
        // would match "msm" (board) / "cpu" if SKIN were not checked first
        assertEquals(XRayRegion.SKIN, c("skin-msm-therm"))
        assertEquals(XRayRegion.SKIN, c("cpu-skin"))
    }

    @Test fun unrecognisedZonesStayUnplaced() {
        assertNull(c("wlan-usr"))
        assertNull(c("video-usr"))
        assertNull(c("pm8150b-tz"))
        assertNull(c("disp_therm"))
        assertNull(c("aoss0-usr"))
        assertNull(c(""))
        assertNull(c("---"))
    }

    @Test fun substringsInsideLongerWordsDoNotMatch() {
        assertNull(c("btmcpu_therm"))   // contains "cpu" but is not the CPU
        assertNull(c("midpoint"))       // contains "mid"
        assertNull(c("bigger_thing"))   // contains "big"
    }

    // ── Frame assembly ───────────────────────────────────────────────────────

    @Test fun hottestSensorWinsAndCountsAreKept() {
        val f = XRayMapper.frame(
            listOf("cpu-0-0-usr" to 52f, "cpu-1-2-usr" to 67.4f, "cpu-0-1-usr" to 55f),
            batteryTempC = 0f
        )
        val cpu = f.readings.getValue(XRayRegion.CPU)
        assertEquals(67.4f, cpu.value, 0.001f)
        assertEquals("cpu-1-2-usr", cpu.sensorName)
        assertEquals(3, cpu.sensorCount)
    }

    @Test fun batteryManagerReplacesBatteryZones() {
        val f = XRayMapper.frame(listOf("battery" to 30f), batteryTempC = 36.5f)
        val b = f.readings.getValue(XRayRegion.BATTERY)
        assertEquals(36.5f, b.value, 0.001f)
        assertEquals("BatteryManager", b.sensorName)
        assertEquals(1, b.sensorCount)
    }

    @Test fun batteryZoneIsUsedWhenBatteryManagerHasNothing() {
        val f = XRayMapper.frame(listOf("battery" to 31f), batteryTempC = 0f)
        assertEquals(31f, f.readings.getValue(XRayRegion.BATTERY).value, 0.001f)
    }

    @Test fun implausibleReadingsAreDropped() {
        val f = XRayMapper.frame(
            listOf("cpu-0" to 0f, "gpu-0" to -273f, "ddr" to 255f, "skin" to 33f, "wlan-usr" to 0f),
            batteryTempC = 0f
        )
        assertEquals(setOf(XRayRegion.SKIN), f.readings.keys)
        assertTrue("dead sensors must not be listed as unplaced either", f.unplaced.isEmpty())
    }

    @Test fun unplacedZonesAreReportedHottestFirst() {
        val f = XRayMapper.frame(listOf("wlan-usr" to 40f, "video-usr" to 52f), 0f)
        assertEquals(listOf("video-usr", "wlan-usr"), f.unplaced.map { it.first })
        assertEquals(0, f.instrumented)
    }

    @Test fun noSensorsAtAllGivesAnEmptyFrame() {
        val f = XRayMapper.frame(emptyList(), 0f)
        assertEquals(0, f.instrumented)
        assertTrue(f.unplaced.isEmpty())
    }

    // ── Skin headroom fallback ───────────────────────────────────────────────

    @Test fun headroomLightsSkinWhenNoSkinZoneIsReadable() {
        val f = XRayMapper.frame(emptyList(), 0f, skinHeadroom = 0.62f)
        val skin = f.readings.getValue(XRayRegion.SKIN)
        assertEquals(XRayUnit.HEADROOM, skin.unit)
        assertEquals(0.62f, skin.value, 0.001f)
        assertEquals("PowerManager", skin.sensorName)
    }

    @Test fun realSkinZoneBeatsHeadroom() {
        val f = XRayMapper.frame(listOf("VIRTUAL-SKIN" to 38f), 0f, skinHeadroom = 0.9f)
        val skin = f.readings.getValue(XRayRegion.SKIN)
        assertEquals(XRayUnit.CELSIUS, skin.unit)
        assertEquals(38f, skin.value, 0.001f)
    }

    @Test fun invalidHeadroomIsIgnored() {
        assertEquals(0, XRayMapper.frame(emptyList(), 0f, skinHeadroom = Float.NaN).instrumented)
        assertEquals(0, XRayMapper.frame(emptyList(), 0f, skinHeadroom = -1f).instrumented)
        assertEquals(0, XRayMapper.frame(emptyList(), 0f, skinHeadroom = null).instrumented)
    }

    @Test fun headroomRampIsItsOwnDistanceToThrottle() {
        assertEquals(0.4f, XRayRegion.SKIN.rampFor(0.4f, XRayUnit.HEADROOM), 0.001f)
        assertEquals(1f, XRayRegion.SKIN.rampFor(1.7f, XRayUnit.HEADROOM), 0f)
        assertEquals(XRayRegion.SKIN.ramp(39f), XRayRegion.SKIN.rampFor(39f, XRayUnit.CELSIUS), 0f)
    }

    @Test fun peakDoesNotCompareAcrossUnits() {
        val headroom = XRayMapper.frame(emptyList(), 0f, skinHeadroom = 0.9f)
        val celsius = XRayMapper.frame(listOf("skin" to 33f), 0f)
        val peak = XRayMapper.foldPeak(XRayMapper.foldPeak(emptyMap(), headroom), celsius)
        assertEquals(XRayUnit.CELSIUS, peak.getValue(XRayRegion.SKIN).unit)
        assertEquals(33f, peak.getValue(XRayRegion.SKIN).value, 0.001f)
    }

    // ── Scale ────────────────────────────────────────────────────────────────

    @Test fun rampIsClampedToTheRegionScale() {
        assertEquals(0f, XRayRegion.CPU.ramp(10f), 0f)
        assertEquals(1f, XRayRegion.CPU.ramp(200f), 0f)
        assertEquals(0.5f, XRayRegion.SKIN.ramp(39f), 0.001f)   // midpoint of 28..50
    }

    @Test fun sameTemperatureReadsHotterOnTighterScales() {
        assertTrue(XRayRegion.BATTERY.ramp(45f) > XRayRegion.CPU.ramp(45f))
    }

    // ── Peak hold ────────────────────────────────────────────────────────────

    @Test fun peakKeepsTheHotterReadingPerRegion() {
        val first = XRayMapper.frame(listOf("cpu-0" to 60f, "gpu-0" to 50f), 0f)
        val second = XRayMapper.frame(listOf("cpu-0" to 55f, "gpu-0" to 58f), 0f)
        val peak = XRayMapper.foldPeak(XRayMapper.foldPeak(emptyMap(), first), second)
        assertEquals(60f, peak.getValue(XRayRegion.CPU).value, 0.001f)
        assertEquals(58f, peak.getValue(XRayRegion.GPU).value, 0.001f)
    }

    @Test fun peakKeepsRegionsThatDisappear() {
        val withGpu = XRayMapper.frame(listOf("gpu-0" to 58f), 0f)
        val withoutGpu = XRayMapper.frame(listOf("cpu-0" to 41f), 0f)
        val peak = XRayMapper.foldPeak(XRayMapper.foldPeak(emptyMap(), withGpu), withoutGpu)
        assertEquals(setOf(XRayRegion.GPU, XRayRegion.CPU), peak.keys)
    }

    @Test fun emptyFrameLeavesPeakUntouched() {
        val seeded = XRayMapper.foldPeak(emptyMap(), XRayMapper.frame(listOf("cpu-0" to 60f), 0f))
        assertEquals(seeded, XRayMapper.foldPeak(seeded, XRayMapper.frame(emptyList(), 0f)))
    }
}
