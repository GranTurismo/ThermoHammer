package com.example.thermohammer.engine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTuneReportTest {

    /** What the native search hands back: [header(10)] then (phase, pinned, threads, tokens, seconds) per trial. */
    private fun raw(pinnable: Int = 4, decodePinned: Int = 0, promptPinned: Int = 0, decodeSkipped: Int = 0, promptSkipped: Int = 0,
                    decodeThreads: Int = 6, promptThreads: Int = 8, mainCore: Int = -1, seconds: Double = 14.2,
                    trials: List<DoubleArray> = emptyList()): DoubleArray =
        doubleArrayOf(pinnable.toDouble(), decodePinned.toDouble(), promptPinned.toDouble(), decodeSkipped.toDouble(), promptSkipped.toDouble(),
            decodeThreads.toDouble(), promptThreads.toDouble(), mainCore.toDouble(), seconds, trials.size.toDouble()) + trials.fold(DoubleArray(0)) { a, t -> a + t }

    private fun trial(phase: Int, pinned: Int, threads: Int, seconds: Double) =
        doubleArrayOf(phase.toDouble(), pinned.toDouble(), threads.toDouble(), if (phase == 0) 6.0 else 64.0, seconds)

    private val cores = listOf(7, 4, 5, 6)

    @Test
    fun `the numbers decode into trials with rates in tokens per second`() {
        val r = LlmTuneReport.parse(raw(trials = listOf(trial(0, 0, 8, 0.6), trial(0, 1, 4, 0.4), trial(1, 0, 8, 1.0), trial(1, 1, 4, 0.8))), cores)!!
        assertEquals(4, r.trials.size)
        assertEquals(10.0, r.trials[0].rate, 1e-9)          // 6 tokens in 0.6 s
        assertEquals(15.0, r.trials[1].rate, 1e-9)
        assertEquals(64.0, r.trials[2].rate, 1e-9)           // a 64-token prompt in 1.0 s
        assertEquals(80.0, r.trials[3].rate, 1e-9)
        assertTrue(r.trials[0].decode && !r.trials[0].pinned); assertTrue(r.trials[1].pinned); assertFalse(r.trials[2].decode)
        assertEquals(6, r.decodeThreads); assertEquals(8, r.promptThreads); assertEquals(14.2, r.seconds, 1e-9)
    }

    @Test
    fun `a result of the wrong shape, or none, decodes to nothing`() {
        assertNull(LlmTuneReport.parse(null, cores))
        assertNull(LlmTuneReport.parse(DoubleArray(5), cores))
        assertNull("claims two trials but has none", LlmTuneReport.parse(raw().also { it[9] = 2.0 }, cores))
        assertNull(LlmTuneReport.parse(raw(trials = listOf(trial(0, 0, 8, 0.6))) + 1.0, cores))
        assertNotNull(LlmTuneReport.parse(raw(), cores))
    }

    @Test
    fun `the pinned best's gain over the unpinned best is the ratio of their best rates`() {
        val r = LlmTuneReport.parse(raw(decodePinned = 1, trials = listOf(trial(0, 0, 8, 0.6), trial(0, 0, 7, 0.5), trial(0, 1, 4, 0.4), trial(0, 1, 3, 0.45))), cores)!!
        assertEquals("15.00 over 12.00", 1.25, r.gain(true)!!, 1e-9)
        assertNull("no prompt trials", r.gain(false))
    }

    @Test
    fun `the report shows every trial and which result was used`() {
        val r = LlmTuneReport.parse(raw(decodePinned = 1, promptPinned = 0, decodeThreads = 4, promptThreads = 8, mainCore = 7,
            trials = listOf(trial(0, 0, 8, 0.6), trial(0, 0, 7, 0.5), trial(0, 1, 4, 0.4), trial(1, 0, 8, 1.0), trial(1, 1, 4, 1.05))), cores)!!
            .copy(singleCoreThreads = mapOf(11 to 4, 12 to 5, 13 to 6))
        val lines = r.lines()
        assertEquals("LLM CPU threads · tuned in 14.2 s · pinning tried on cores 7,4,5,6 (4 usable, fastest first)", lines[0])
        assertEquals("  decode tok/s · unpinned: 8 thr 10.00, 7 thr 12.00 · pinned: 4 thr 15.00 · used: pinned, 4 threads (×1.25 of the best unpinned)", lines[1])
        assertEquals("  prompt tok/s · unpinned: 8 thr 64.00 · pinned: 4 thr 60.95 · used: unpinned, 8 threads (pinned ×0.95 of it, under ×1.05)", lines[2])
        assertEquals("  pinning check · the calling thread sat alone on cpu7 · 3 threads of the process (the pools' workers and the caller) were each limited to one core (4,5,6)", lines[3])
    }

    @Test
    fun `an unpinned result says why, and no pinning check is printed when nothing was pinned`() {
        val skipped = LlmTuneReport.parse(raw(decodeSkipped = 1, promptSkipped = 1, trials = listOf(trial(0, 0, 8, 2.0), trial(1, 0, 8, 3.0))), cores)!!
        assertEquals(3, skipped.lines().size)
        assertTrue(skipped.lines()[1], skipped.lines()[1].endsWith("used: unpinned, 6 threads (no pinned pass: the unpinned one took too long)"))
        val none = LlmTuneReport.parse(raw(pinnable = 0, trials = listOf(trial(0, 0, 8, 0.6))), emptyList())!!
        assertTrue(none.lines()[1], none.lines()[1].contains("no core could be used for pinning"))
        assertFalse(none.pinned)
        assertTrue(none.lines()[0], none.lines()[0].contains("(0 usable"))
    }

    @Test
    fun `a pinned run whose calling thread isn't on one core says so`() {
        val r = LlmTuneReport.parse(raw(decodePinned = 1, mainCore = -1, trials = listOf(trial(0, 1, 4, 0.4))), cores)!!
        assertTrue(r.lines().last(), r.lines().last().contains("on no single core"))
    }
}
