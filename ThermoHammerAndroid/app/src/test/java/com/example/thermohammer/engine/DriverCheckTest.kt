package com.example.thermohammer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The driver check's verdict: what is kept, for which install and system build, and what the user is told. */
class DriverCheckTest {
    private val key = "1731000000000 Xiaomi/amber_global/amber:14/UP1A.231005.007/V816.0.17.0.UKWMIXM:user/release-keys"

    @Test
    fun `a passed check is kept and lets the accelerator run`() {
        assertEquals(DriverCheck.Passed, DriverCheck.cached(DriverCheck.record(key, DriverCheck.Passed), key))
        assertNull(DriverCheck.problem(DriverCheck.Passed, "NPU"))
    }

    @Test
    fun `a check whose process crashed is kept with the step it was on, and keeps the accelerator off`() {
        val died = DriverCheck.Died("YOLOX · 640×640")
        val back = DriverCheck.cached(DriverCheck.record(key, died), key)
        assertEquals(died, back)
        assertEquals("this phone's NPU driver crashed in the safety check (YOLOX · 640×640): the NPU is not used", DriverCheck.problem(back!!, "NPU"))
        assertEquals(DriverCheck.Died(null), DriverCheck.cached(DriverCheck.record(key, DriverCheck.Died(null)), key))
        assertEquals("this phone's GPU driver crashed in the safety check: the GPU is not used", DriverCheck.problem(DriverCheck.Died(null), "GPU"))
    }

    @Test
    fun `a check that hung is kept too`() {
        val hung = DriverCheck.Hung("FFNet-40S · Cityscapes · 2048×1024")
        assertEquals(hung, DriverCheck.cached(DriverCheck.record(key, hung), key))
        assertEquals("this phone's NPU driver did not answer in the safety check (FFNet-40S · Cityscapes · 2048×1024): the NPU is not used",
            DriverCheck.problem(hung, "NPU"))
    }

    @Test
    fun `a check that could not be made teaches nothing and is asked again`() {
        assertNull(DriverCheck.record(key, DriverCheck.Unavailable))
        assertNull(DriverCheck.problem(DriverCheck.Unavailable, "GPU"))
    }

    @Test
    fun `an app update or a system update asks again`() {
        val saved = DriverCheck.record(key, DriverCheck.Died("YOLOX · 640×640"))
        assertNull("app update", DriverCheck.cached(saved, key.replace("1731000000000", "1731999999999")))
        assertNull("system update", DriverCheck.cached(saved, key.replace("V816.0.17.0", "V816.0.18.0")))
    }

    @Test
    fun `nothing kept, or something unreadable, runs the check`() {
        assertNull(DriverCheck.cached(null, key))
        assertNull(DriverCheck.cached("", key))
        assertNull(DriverCheck.cached(key, key))
        assertNull(DriverCheck.cached("$key\nsomething else", key))
    }

    // ApplicationExitInfo: 5 CRASH_NATIVE, 4 CRASH, 2 SIGNALED, 13 OTHER; 3 LOW_MEMORY, 10 USER_REQUESTED, 16 PACKAGE_UPDATED

    @Test
    fun `a check's process that went away crashed, unless Android says it was closed`() {
        for (reason in listOf(5, 4, 2, 13)) assertEquals(DriverCheck.Died("YOLOX"), DriverCheck.afterDeath("YOLOX", reason))
        assertEquals("no record: taken as a crash", DriverCheck.Died(null), DriverCheck.afterDeath(null, null))
        for (reason in listOf(3, 10, 11, 16)) assertEquals(DriverCheck.Unavailable, DriverCheck.afterDeath("YOLOX", reason))
    }
}
