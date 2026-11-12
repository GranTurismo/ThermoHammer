package com.example.thermohammer.engine.ai

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tuning probe's GPU settings through the real LiteRT on a device: each one either runs or fails with a message
 * (never takes the app down), the copy times come back, and LiteRT's own log says which API the default ran on. The
 * CPU isn't used (the emulator advertises SME2 it can't execute, which XNNPACK trusts), so outputs are compared with
 * the default's. Rates go to the instrumentation status as "gpu_<setting>".
 */
@RunWith(AndroidJUnit4::class)
class AiTuningDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Test
    fun everyGpuSettingRunsOrFailsCleanly() {
        val bench = AiBench(app)
        assumeTrue("LiteRT offers no GPU here", AiAccel.GPU in bench.available())
        val (input, _) = AiInputs(app).input(AiTest.CLASSIFY)
        val status = Bundle()
        var reference: List<ByteArray>? = null
        var defaultRan = false
        for (setting in AiSettings.gpu()) {
            val since = System.currentTimeMillis()
            try {
                val m = bench.run(AiTest.CLASSIFY, AiAccel.GPU, input, 0.5, { true }, true, setting) { _, _ -> }
                if (setting.isDefault) { reference = m.outputs; defaultRan = true }
                val agree = reference?.let { AiAgreement.of(AiTest.CLASSIFY, m.outputs, it) }
                status.putString("gpu_${setting.id}", "%.1f/s · copy in %.2f ms, out %.2f ms · agreement with the default %s".format(
                    m.ips, m.writeMs, m.readMs, agree?.let { "%.3f".format(it) } ?: "n/a"))
                assertTrue("${setting.id} measured a rate", m.ips > 0 && m.writeMs >= 0 && m.readMs >= 0)
            } catch (e: Exception) {
                status.putString("gpu_${setting.id}", "failed: ${e.message}")
            }
            if (setting.isDefault) {
                status.putString("gpu_default_backend", LiteRtLog.gpuBackend(bench.litertLines(since - 500, Regex("OpenCL not supported on this platform"))))
            }
        }
        instrumentation.sendStatus(0, status)
        assertTrue("the default setting is what the app has always run: it must run", defaultRan)
    }
}
