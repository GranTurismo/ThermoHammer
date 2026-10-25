package com.example.thermohammer.engine

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The benchmark's "is the phone quiet?" probe. Rates go to the instrumentation status as "share_n".
 * Run it once on an idle device (≈1) and once with CPU hogs going, e.g.
 * `adb shell 'for i in 1 2 3 4; do (while :; do :; done) & done'` (well below 0.9).
 */
@RunWith(AndroidJUnit4::class)
class CpuShareTest {

    @Test
    fun measuresTheShareOfTheCpuThisAppGets() {
        val status = Bundle()
        repeat(5) { i ->
            val share = StressProbes.cpuShare()
            assertTrue("share $share", share in 0f..1f)
            status.putString("share_$i", "%.3f".format(share))
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status)
    }
}
