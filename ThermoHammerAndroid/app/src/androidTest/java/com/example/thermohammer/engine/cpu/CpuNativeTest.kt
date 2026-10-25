package com.example.thermohammer.engine.cpu

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The BenchCore kernels on a device: every kernel repeats its checksum, and each CPU test's value (geometric mean of
 * its kernels' ops/s) goes to the instrumentation status as "<TEST>", with the crypto path.
 */
@RunWith(AndroidJUnit4::class)
class CpuNativeTest {

    @Test
    fun everyKernelIsDeterministicAndMeasured() {
        for (id in 0..CpuNative.SHA256) {
            val a = NativeKernel(id)
            val b = NativeKernel(id)
            val first = a.run()
            assertEquals(a.name, first, a.run())
            assertEquals("${a.name} (fresh instance)", first, b.run())
            a.close(); b.close()
        }
        val status = Bundle().apply { putString("crypto", CpuNative.cryptoPath()) }
        val bench = CpuBench()
        for (test in CpuTest.entries) {
            val run = bench.run(test, { true }) {}
            assertTrue(run.value > 0)
            status.putString(test.name, "%.2f · %s".format(run.value, run.rates.entries.joinToString { (k, v) -> "$k %.1f".format(v) }))
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status)
    }
}
