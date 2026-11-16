package com.example.thermohammer.engine.ai

import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiRun
import com.example.thermohammer.engine.AiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuFallbackTest {
    /** CPU and NPU rates per model, in AiTest's order. */
    private fun runs(cpu: List<Double>, npu: List<Double>) = AiTest.entries.flatMapIndexed { i, t ->
        listOf(AiRun(t, AiAccel.CPU, cpu[i]), AiRun(t, AiAccel.GPU, cpu[i] / 2), AiRun(t, AiAccel.NPU, npu[i]))
    }
    private fun npu(runs: List<AiRun>) = runs.filter { it.accel == AiAccel.NPU }

    // the Xiaomi 11T's CPU, and an NPU that wins four models, loses detection and loses sound badly (0.83× overall)
    private val cpu = listOf(228.66, 13.93, 3.09, 2.85, 32.34, 1570.24)
    private val slowOnTwo = listOf(330.0, 10.2, 4.45, 4.1, 46.5, 160.0)

    @Test
    fun `an NPU clearly faster than the CPU counts, proven or not`() {
        val r = runs(cpu, cpu.map { it * 3 })
        assertEquals(r, NpuFallback.check(r))
        assertEquals(3.0, NpuFallback.speedup(r)!!, 1e-9)
    }

    @Test
    fun `an NPU no faster than the CPU, with no evidence it ran, is a fallback - every run scores 0`() {
        val r = runs(cpu, slowOnTwo)
        assertTrue(NpuFallback.speedup(r)!! < NpuFallback.MIN_SPEEDUP)
        val checked = npu(NpuFallback.check(r))
        assertTrue(checked.all { it.ips == 0.0 })
        assertEquals(0.83, NpuFallback.speedup(r)!!, 0.005)
        assertEquals("no faster than the CPU (%.2f×): LiteRT fell back to it, so it scores 0 · it ran at %.2f/s".format(NpuFallback.speedup(r), 330.0),
            checked.first().error)
    }

    @Test
    fun `runs proven to be the NPU's keep their rates, slow ones too`() {
        val r = runs(cpu, slowOnTwo)
        val checked = npu(NpuFallback.check(r, proven = AiTest.entries.toSet()))
        assertEquals(slowOnTwo, checked.map { it.ips })
        assertTrue(checked.all { it.error == null })
    }

    @Test
    fun `only the unproven runs are taken for a fallback`() {
        val r = runs(cpu, slowOnTwo)
        val checked = npu(NpuFallback.check(r, proven = setOf(AiTest.CLASSIFY, AiTest.SOUND)))
        assertEquals(330.0, checked.first { it.test == AiTest.CLASSIFY }.ips, 0.0)
        assertEquals(160.0, checked.first { it.test == AiTest.SOUND }.ips, 0.0)
        assertEquals(0.0, checked.first { it.test == AiTest.DETECT }.ips, 0.0)
        assertTrue(checked.first { it.test == AiTest.DETECT }.error!!.startsWith("no faster than the CPU"))
    }

    @Test
    fun `a run already scored 0 for a wrong answer keeps its own reason and doesn't count against the others`() {
        // the scored loop zeroes a wrong answer before this rule sees the runs (AiAgreement.wrongAnswer)
        val wrong = AiAgreement.wrongAnswer(46.5, 0.02)
        val r = runs(cpu, slowOnTwo).map { if (it.accel == AiAccel.NPU && it.test == AiTest.DEPTH) it.copy(ips = 0.0, error = wrong) else it }
        val checked = npu(NpuFallback.check(r, proven = AiTest.entries.toSet() - AiTest.DEPTH))
        assertEquals(wrong, checked.first { it.test == AiTest.DEPTH }.error)
        assertEquals(5, checked.count { it.ips > 0 })
    }

    @Test
    fun `the CPU and GPU runs are never touched, and runs that already failed stay as they are`() {
        val failed = runs(cpu, slowOnTwo).map { if (it.accel == AiAccel.NPU && it.test == AiTest.DEPTH) it.copy(ips = 0.0, error = "Failed to compile model") else it }
        val checked = NpuFallback.check(failed)
        assertEquals(failed.filter { it.accel != AiAccel.NPU }, checked.filter { it.accel != AiAccel.NPU })
        assertEquals("Failed to compile model", checked.first { it.accel == AiAccel.NPU && it.test == AiTest.DEPTH }.error)
    }

    @Test
    fun `nothing to compare leaves the runs alone`() {
        val noNpu = runs(cpu, List(6) { 0.0 })
        assertNull(NpuFallback.speedup(noNpu))
        assertEquals(noNpu, NpuFallback.check(noNpu))
    }
}
