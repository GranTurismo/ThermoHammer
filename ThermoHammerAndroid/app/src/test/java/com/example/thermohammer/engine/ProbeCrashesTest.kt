package com.example.thermohammer.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeCrashesTest {

    @Test
    fun `a leftover marker becomes a dropped setting for the same install only`() {
        assertEquals(emptySet<String>(), ProbeCrashes.after(null, emptySet(), 7))
        val after = ProbeCrashes.after("7:GPU:opengl", emptySet(), 7)
        assertEquals(setOf("7:GPU:opengl"), after)
        assertEquals(setOf("GPU:opengl"), ProbeCrashes.keys(after, 7))
        assertEquals("an app update retries it", emptySet<String>(), ProbeCrashes.keys(after, 8))
    }

    @Test
    fun `markers of another shape or install are ignored and earlier records are kept`() {
        assertEquals(emptySet<String>(), ProbeCrashes.after("7:CLASSIFY:GPU:x", emptySet(), 7))
        assertEquals(emptySet<String>(), ProbeCrashes.after("6:GPU:opengl", emptySet(), 7))
        assertEquals(setOf("7:GPU:a", "7:NPU:b"), ProbeCrashes.after("7:NPU:b", setOf("7:GPU:a"), 7))
        assertEquals(setOf("GPU:a"), ProbeCrashes.keys(setOf("7:GPU:a", "6:NPU:b", "garbage"), 7))
    }

    @Test
    fun `the LLM probe uses the same record`() {
        assertEquals(setOf("LLM:i8mm"), ProbeCrashes.keys(ProbeCrashes.after("12:LLM:i8mm", emptySet(), 12), 12))
    }

    // ApplicationExitInfo's reasons: 4 CRASH, 5 CRASH_NATIVE, 6 ANR, 7 INITIALIZATION_FAILURE; 10 USER_REQUESTED, 13 OTHER, 2 SIGNALED, 3 LOW_MEMORY

    @Test
    fun `a recorded crash counts even when the run was being stopped`() {
        // a crashing app is stopped by Android on its way down: that stop must not hide the crash
        for (reason in listOf(4, 5, 6, 7)) {
            assertEquals(true, ProbeCrashes.leftByCrash(reason, stopRequested = true))
            assertEquals(true, ProbeCrashes.leftByCrash(reason, stopRequested = false))
        }
    }

    @Test
    fun `a marker left after a requested stop is not a crash when the process was merely closed`() {
        for (reason in listOf(10, 11, 13, 2, 3, 1)) assertEquals(false, ProbeCrashes.leftByCrash(reason, stopRequested = true))
        assertEquals("no record of how it ended (Android 10 and older)", false, ProbeCrashes.leftByCrash(null, stopRequested = true))
    }

    @Test
    fun `a process that died inside a test without being asked to stop left a crash marker`() {
        for (reason in listOf(2, 3, 13, 10)) assertEquals(true, ProbeCrashes.leftByCrash(reason, stopRequested = false))
        assertEquals(true, ProbeCrashes.leftByCrash(null, stopRequested = false))
    }
}
