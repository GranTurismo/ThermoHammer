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
}
