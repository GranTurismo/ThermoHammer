package com.example.thermohammer.engine.ai

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiTest
import com.example.thermohammer.engine.CpuAffinity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The AI CPU settings through the real LiteRT on a device: each either runs (a rate, the same outputs as the default's,
 * a note on where LiteRT's threads ended up) or fails with a message, and the thread that ran it always has its own
 * mask back. Run alone and only where XNNPACK's kernels can execute: the Apple-silicon emulator advertises SME2 that
 * XNNPACK trusts and the guest can't run. Rates go to the instrumentation status as "cpu_<setting>".
 */
@RunWith(AndroidJUnit4::class)
class AiCpuSettingsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    /** The hint session needs no model: it either starts (and takes reports and a close) or says why not. */
    @Test
    fun anAdpfSessionStartsOrSaysWhyNot() {
        assumeTrue("ADPF needs Android 12", AdpfHint.supported())
        val status = Bundle()
        try {
            AdpfHint.start(app, intArrayOf(CpuAffinity.tid())).use { hint ->
                repeat(5) { hint.report(2_000_000L) }
                status.putString("adpf", "session started and took 5 reports")
            }
        } catch (e: IllegalStateException) {
            status.putString("adpf", "no session: ${e.message}")
        }
        instrumentation.sendStatus(0, status)
    }

    @Test
    fun everyCpuSettingRunsOrFailsCleanlyAndTheThreadIsGivenBack() {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 8)
        val bench = AiBench(app)
        val (input, _) = AiInputs(app).input(AiTest.CLASSIFY)
        val before = CpuAffinity.mask()
        val status = Bundle()
        var reference: List<ByteArray>? = null
        for (setting in AiSettings.cpu(listOf(7, 6, 5, 4), 8, AdpfHint.supported())) {
            try {
                val m = bench.run(AiTest.CLASSIFY, AiAccel.CPU, input, 0.5, { true }, true, setting) { _, _ -> }
                if (setting.isDefault) reference = m.outputs
                val agree = reference?.let { AiAgreement.of(AiTest.CLASSIFY, m.outputs, it) }
                status.putString("cpu_${setting.id}", "%.1f/s · agreement with the default %s · %s".format(m.ips, agree?.let { "%.3f".format(it) } ?: "n/a", m.note ?: "no note"))
                assertTrue("${setting.id} measured a rate", m.ips > 0)
            } catch (e: Exception) {
                status.putString("cpu_${setting.id}", "failed: ${e.message}")
            }
            assertEquals("${setting.id}: the thread has its own mask back", before, CpuAffinity.mask())
        }
        instrumentation.sendStatus(0, status)
    }
}
