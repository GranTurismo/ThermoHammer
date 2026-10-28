package com.example.thermohammer.engine

import com.example.thermohammer.engine.StressProbes.TempZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalZonesTest {

    // Qualcomm's battery current limiter reports through the thermal framework too — none of it is a temperature
    @Test fun batteryLimiterZonesAreNotTemperatures() {
        assertFalse(StressProbes.isTemperatureZone("socd"))
        assertFalse(StressProbes.isTemperatureZone("pm8550b-ibat-lvl0"))
        assertFalse(StressProbes.isTemperatureZone("pm8550b-vbat-lvl0"))
        assertFalse(StressProbes.isTemperatureZone("pm8550-bcl-lvl1"))
        assertTrue(StressProbes.isTemperatureZone("cpu-1-0-0"))
        assertTrue(StressProbes.isTemperatureZone("battery"))
        assertTrue(StressProbes.isTemperatureZone("soc"))
    }

    // 36 % battery: socd reads 64 and climbs as the battery drains — the SoC reading must come from the CPU sensors
    @Test fun socTemperatureIgnoresSocd() {
        val zones = listOf(TempZone("socd", 64f), TempZone("cpu-0-0-0", 41.5f), TempZone("cpuss-1", 43.2f),
            TempZone("battery", 33f), TempZone("xo-therm", 36f))
        assertEquals(43.2f, StressProbes.cpuAdjacentTemp(zones)!!, 0.001f)
    }

    @Test fun socTemperatureFollowsTheSensorsDown() {
        val hot = listOf(TempZone("socd", 64f), TempZone("cpu-0-0-0", 78f))
        val cooled = listOf(TempZone("socd", 65f), TempZone("cpu-0-0-0", 45f))
        assertEquals(78f, StressProbes.cpuAdjacentTemp(hot)!!, 0.001f)
        assertEquals(45f, StressProbes.cpuAdjacentTemp(cooled)!!, 0.001f)
    }

    @Test fun substringsDontPassForTheCpu() {
        // "btmcpu_therm" (a board thermistor) isn't the CPU: with no CPU sensor the hottest zone is used
        val zones = listOf(TempZone("btmcpu_therm", 30f), TempZone("xo-therm", 35f))
        assertEquals(35f, StressProbes.cpuAdjacentTemp(zones)!!, 0.001f)
    }

    @Test fun noZonesNoReading() {
        assertNull(StressProbes.cpuAdjacentTemp(emptyList()))
        assertNull(StressProbes.cpuAdjacentTemp(listOf(TempZone("socd", 70f))))
    }
}
