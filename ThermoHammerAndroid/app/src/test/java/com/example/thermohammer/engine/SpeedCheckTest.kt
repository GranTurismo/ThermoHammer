package com.example.thermohammer.engine

import com.example.thermohammer.data.HealthMemo
import com.example.thermohammer.data.HealthMemoStore
import com.example.thermohammer.engine.SpeedCheck.Change
import com.example.thermohammer.engine.SpeedCheck.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SpeedCheckTest {

    /** 30 windows: [burst] for the first ten seconds, then [sustained]. */
    private fun windows(burst: Double, sustained: Double) = List(30) { if (it < 10) burst else sustained }

    private fun m(w: List<Double>, saver: Boolean = false, thermal: Int = 0, ts: Long = 0L) =
        SpeedMeasurement(w, powerSaver = saver, charging = false, thermalStatus = thermal, timestamp = ts)

    // ── the numbers ──

    @Test fun burstIsTheMeanOfSecondsOneToFive() {
        val w = listOf(100.0, 200.0, 300.0, 400.0, 500.0) + List(25) { 1.0 }
        assertEquals(300.0, SpeedCheck.burst(w), 1e-9)
    }

    @Test fun sustainedIsTheMedianOfSecondsElevenToThirty() {
        val w = List(10) { 1000.0 } + List(19) { 500.0 } + listOf(9000.0)      // one spike doesn't move a median
        assertEquals(500.0, SpeedCheck.sustained(w), 1e-9)
        val even = List(10) { 0.0 } + List(10) { 400.0 } + List(10) { 600.0 }
        assertEquals(500.0, SpeedCheck.sustained(even), 1e-9)
    }

    @Test fun dropIsOneMinusSustainedOverBurst() {
        val x = m(windows(1000.0, 480.0))
        assertEquals(0.52, x.drop, 1e-9)
        assertEquals(48, SpeedCheck.kept(x))
        assertEquals(0.0, SpeedCheck.drop(0.0, 10.0), 0.0)
    }

    @Test fun keptNeverReadsAboveAHundred() = assertEquals(100, SpeedCheck.kept(m(windows(900.0, 1000.0))))

    // ── the diagnosis ──

    @Test fun nothingFound() {
        val d = SpeedCheck.diagnose(m(windows(1000.0, 900.0)), "Google")
        assertEquals(listOf(Kind.NO_LOST_SPEED), d.causes.map { it.kind })
        assertEquals("NO LOST SPEED FOUND", d.causes[0].title)
        assertEquals("Your phone kept 90% of its burst speed. Nothing is holding it back.", d.causes[0].text)
        assertNull(d.note)
    }

    @Test fun powerSavingComesFirstAndCombinesWithHeldBack() {
        val d = SpeedCheck.diagnose(m(windows(1000.0, 500.0), saver = true, thermal = 1), "HONOR")
        assertEquals(listOf(Kind.POWER_SAVING, Kind.HELD_BACK_WHILE_COOL), d.causes.map { it.kind })
        assertEquals("POWER SAVING IS ON", d.causes[0].title)
        assertEquals("Battery saver caps the CPU. Turn it off and check again.", d.causes[0].text)
    }

    @Test fun aQuarterLostWhileCoolIsAPowerSetting() {
        val d = SpeedCheck.diagnose(m(windows(1000.0, 750.0), thermal = 0), "samsung")
        val c = d.causes.single()
        assertEquals(Kind.HELD_BACK_WHILE_COOL, c.kind)
        assertEquals("Your phone slows down after a short burst even though it isn't hot — a power setting is holding it back, not heat.", c.text)
        assertEquals(listOf("Settings › Battery (or Device care) › Performance profile — choose Standard; Light slows the CPU.",
            "Names differ between versions — look for the closest match."), c.steps)
    }

    @Test fun justUnderAQuarterIsNothing() {
        assertEquals(Kind.NO_LOST_SPEED, SpeedCheck.diagnose(m(windows(1000.0, 751.0)), "x").causes.single().kind)
    }

    @Test fun aQuarterLostWhileHotIsHeat() {
        val c = SpeedCheck.diagnose(m(windows(1000.0, 600.0), thermal = 2), "Google").causes.single()
        assertEquals(Kind.HEAT, c.kind)
        assertEquals("It slowed down because it got hot. Take it out of its case, unplug it, let it cool, and check again.", c.text)
    }

    @Test fun unknownThermalCountsAsCool() {
        assertEquals(Kind.HELD_BACK_WHILE_COOL, SpeedCheck.diagnose(m(windows(1000.0, 600.0), thermal = -1), "x").causes.single().kind)
    }

    @Test fun vendorStepsByManufacturerAnyCase() {
        assertTrue(SpeedCheck.vendorSteps("HONOR").contains("2.08×"))
        assertEquals(SpeedCheck.vendorSteps("huawei"), SpeedCheck.vendorSteps("HONOR"))
        assertEquals(SpeedCheck.vendorSteps("Xiaomi"), SpeedCheck.vendorSteps("POCO"))
        assertEquals(SpeedCheck.vendorSteps("OnePlus"), SpeedCheck.vendorSteps("realme"))
        assertEquals(SpeedCheck.vendorSteps("vivo"), SpeedCheck.vendorSteps("iQOO"))
        assertTrue(SpeedCheck.vendorSteps("Google").startsWith("Settings › Battery › Battery Saver"))
        assertEquals("Settings › Battery — turn off power saving and pick the highest performance mode your phone offers.",
            SpeedCheck.vendorSteps("Nothing"))
    }

    @Test fun slowerPhoneHealthAddsANote() {
        val note = SpeedCheck.HealthNote(percent = 82, model = "HONOR 600 Pro", slower = true)
        val d = SpeedCheck.diagnose(m(windows(1000.0, 950.0)), "HONOR", note)
        assertEquals("Your last benchmark was 18% below the median HONOR 600 Pro.", d.note)
        assertNull(SpeedCheck.diagnose(m(windows(1000.0, 950.0)), "HONOR", note.copy(slower = false)).note)
    }

    @Test fun healthNoteOnlyForTheNewestRun() {
        val memo = HealthMemo("run-2", "MUCH_SLOWER", 70, 12, "HONOR 600 Pro", 0L)
        assertEquals(SpeedCheck.HealthNote(70, "HONOR 600 Pro", true), HealthMemoStore.noteFor(memo, "run-2"))
        assertNull(HealthMemoStore.noteFor(memo, "run-3"))
        assertFalse(HealthMemoStore.noteFor(memo.copy(verdict = "ON_PAR"), "run-2")!!.slower)
        assertNull(HealthMemoStore.noteFor(memo.copy(percent = null), "run-2"))
    }

    // ── checking again ──

    @Test fun tenPercentFasterOrMore() {
        val c = SpeedCheck.compare(m(windows(1000.0, 400.0)), m(windows(1000.0, 832.0)))
        assertEquals(Change.FASTER, c.change)
        assertEquals("2.08× FASTER", c.title(Locale.US))
        assertEquals("You got back 108% speed.", c.text)
        assertEquals(Change.FASTER, SpeedCheck.compare(m(windows(1.0, 100.0)), m(windows(1.0, 110.0))).change)
    }

    @Test fun tenPercentSlowerOrMore() {
        val c = SpeedCheck.compare(m(windows(1000.0, 500.0)), m(windows(1000.0, 450.0)))
        assertEquals(Change.SLOWER, c.change)
        assertEquals("SLOWER", c.title(Locale.US))
        assertEquals("It's slower now — change the setting back.", c.text)
    }

    @Test fun inBetweenIsNoChange() {
        val c = SpeedCheck.compare(m(windows(1000.0, 500.0)), m(windows(1000.0, 540.0)))
        assertEquals(Change.NO_CHANGE, c.change)
        assertEquals("That setting wasn't holding your phone back.", c.text)
    }

    @Test fun firstCheckExpiresAfterAnHour() {
        val first = m(windows(1.0, 1.0), ts = 0L)
        assertFalse(SpeedCheck.isExpired(first, 60 * 60 * 1000L))
        assertTrue(SpeedCheck.isExpired(first, 60 * 60 * 1000L + 1))
    }

    // ── the stress verdict's banner ──

    private fun stamps(thermalAt: (Int) -> Int) = (1..60).map { StampV2(tNs = it * 1_000_000_000L, ipsTotal = 1, thermalStatus = thermalAt(it)) }

    @Test fun lostSpeedWhileCoolNeedsUnderEightyAndACoolOnset() {
        val cool = stamps { 0 }
        assertTrue(SpeedCheck.lostSpeedWhileCool(70.0, 20, cool))
        assertFalse(SpeedCheck.lostSpeedWhileCool(80.0, 20, cool))
        assertFalse(SpeedCheck.lostSpeedWhileCool(70.0, null, cool))
        val hotLate = stamps { if (it >= 30) 2 else 1 }
        assertTrue(SpeedCheck.lostSpeedWhileCool(70.0, 20, hotLate))        // FAIR at the onset: still "light"
        assertFalse(SpeedCheck.lostSpeedWhileCool(70.0, 40, hotLate))       // SERIOUS at the onset: heat
    }
}
