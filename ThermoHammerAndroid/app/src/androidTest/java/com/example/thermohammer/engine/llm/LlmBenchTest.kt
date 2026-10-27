package com.example.thermohammer.engine.llm

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.engine.LlmAccel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The LLM test runs end to end through JNI: the seeded model builds, the CPU run measures both phases, and the
 * GPU run either measures them or says why it can't. The rates go to the instrumentation status.
 */
@RunWith(AndroidJUnit4::class)
class LlmBenchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bench = LlmBench(context)

    private fun report(key: String, value: String) =
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, value) })

    @Test
    fun largestModelTable() {
        assumeTrue(LlmNative.loaded)
        assertEquals(8, LlmNative.largestModel(8e9))
        assertEquals(3, LlmNative.largestModel(3e9))
        assertEquals(0, LlmNative.largestModel(0.5e9))
        assertTrue(LlmNative.weightBytes() in 1_700_000_000L..1_900_000_000L)
    }

    @Test
    fun cpuMeasuresPrefillAndDecode() {
        assumeTrue("needs arm64 and 6 GB: ${bench.problem()}", bench.problem() == null)
        val phases = HashSet<LlmPhase>()
        val run = bench.run(LlmAccel.CPU, 4, { true }) { phases += it.phase }
        requireNotNull(run)
        assertNull(run.error, run.error)
        assertTrue("prefill ${run.prefill}", run.prefill > 0)
        assertTrue("decode ${run.decode}", run.decode > 0)
        assertTrue(phases.containsAll(listOf(LlmPhase.BUILD, LlmPhase.WARMUP, LlmPhase.PREFILL, LlmPhase.DECODE)))
        report("llm cpu", "prefill %.1f, decode %.1f tok/s, %d threads".format(run.prefill, run.decode, run.threads))
    }

    @Test
    fun gpuRunsOrSaysWhy() {
        assumeTrue(bench.problem() == null)
        val why = bench.gpuProblem()
        if (why != null) { report("llm gpu", "skipped: $why"); return }
        val run = requireNotNull(bench.run(LlmAccel.GPU, 4, { true }) {})
        report("llm gpu", run.error ?: "prefill %.1f, decode %.1f tok/s on %s (%s)".format(run.prefill, run.decode,
            bench.gpuName(), bench.gpuApi()))
        assertTrue(run.error ?: "", run.decode > 0)
    }

    @Test
    fun gpuAgreesWithTheCpu() {
        assumeTrue(bench.problem() == null && bench.gpuProblem() == null)
        val error = arrayOfNulls<String>(1)
        val cpu = LlmNative.create(false, 4, LlmNative.SEED, error)
        assertTrue(error[0] ?: "", cpu != 0L)
        assertTrue(LlmNative.error(cpu), LlmNative.prefill(cpu, LlmNative.PROMPT) > 0)
        LlmNative.destroy(cpu)
        val gpu = LlmNative.create(true, 4, LlmNative.SEED, error)
        assertTrue(error[0] ?: "", gpu != 0L)
        val s = LlmNative.prefill(gpu, LlmNative.PROMPT)
        val agreement = LlmNative.agreement(gpu)
        report("llm agreement", "%s on %s: %.6f%s".format(bench.gpuApi(), bench.gpuName(), agreement,
            if (s < 0) " — " + LlmNative.error(gpu) else ""))
        LlmNative.destroy(gpu)
        assertTrue("agreement $agreement", s > 0 && agreement >= 0.9)
    }

    @Test
    fun stopCancelsALongPrompt() {
        assumeTrue(bench.problem() == null)
        val t0 = System.nanoTime()
        var active = true
        val run = bench.run(LlmAccel.CPU, 4, { active }) { if (it.phase == LlmPhase.PREFILL) active = false }
        assertNull(run)                                         // a stopped run gives no result
        assertTrue((System.nanoTime() - t0) / 1e9 < 60)
    }
}
