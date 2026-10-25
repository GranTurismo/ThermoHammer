package com.example.thermohammer.engine.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runner, with plain-Kotlin kernels standing in for the native ones (the BenchCore kernels themselves are
 * tested by BenchCore/test/run.sh and, on a device, by androidTest CpuNativeTest).
 */
class CpuBenchTest {

    private class Spin(private val n: Int) : Kernel("SPIN") {
        var closed = false
        override fun run(): Long { var x = 0L; for (i in 0 until n) x += i * 31L xor x; return x }
        override fun close() { closed = true }
    }

    private class Drifting : Kernel("DRIFT") {
        private var calls = 0L
        override fun run(): Long = calls++
    }

    @Test
    fun `runner reports throughput for single and multi-core tests and frees every kernel`() {
        val made = mutableListOf<Spin>()
        val bench = CpuBench(threads = 2) { test -> test.kernelIds.map { { Spin(20_000).also { k -> synchronized(made) { made += k } } } } }
        var reports = 0
        val run = bench.run(CpuTest.DATA, { true }) { reports++ }
        assertEquals(CpuTest.DATA.labels, run.rates.keys.toList())
        assertTrue(run.rates.values.all { it > 0 })
        assertTrue(run.value > 0)
        val multi = bench.run(CpuTest.MULTI, { true }) {}
        assertTrue(multi.rates.values.all { it > 0 })
        assertTrue(reports > 0)
        assertTrue("every kernel closed", made.all { it.closed })
    }

    @Test
    fun `a kernel whose result changes fails the test`() {
        val bench = CpuBench(threads = 2) { test -> test.kernelIds.map { { Drifting() } } }
        assertThrows(IllegalStateException::class.java) { bench.run(CpuTest.DATA, { true }) {} }
    }
}
