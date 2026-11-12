package com.example.thermohammer.engine.llm

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two CPU kernel builds on a real CPU or the emulator: what the kernel says the CPU has, the i8mm library loading
 * only where it says i8mm, the two builds answering one prompt on the real model alike, and the device probes (Vulkan,
 * OpenCL) returning text instead of crashing. (The few-millisecond self-test is in LlmStage2DeviceTest.)
 */
@RunWith(AndroidJUnit4::class)
class LlmCpuLibsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun cpuFeaturesAndTheBuildsThatMayRun() {
        assumeTrue(LlmNative.loaded)
        val hw = LlmNative.hwcaps()
        assertEquals(2, hw.size)
        val status = Bundle().apply { putString("cpu_features", CpuFeatures.line(hw[0], hw[1])) }
        val decision = LlmProbe.i8mmDecision(hw[0], hw[1], crashed = false) { LlmNativeI8mm.loadError }
        status.putString("i8mm_decision", if (decision.use) "use" else "no: ${decision.why}")
        // a CPU without i8mm must not even load the library: the decision above only asks for it when i8mm is reported
        assertEquals(CpuFeatures.supportsI8mm(hw[0], hw[1]) && LlmNativeI8mm.loaded, decision.use)
        instrumentation.sendStatus(0, status)
    }

    /**
     * `-e forceI8mm true` runs the i8mm library although the kernel doesn't report i8mm — only for a host known to have
     * the instructions whose hypervisor hides the feature (an Apple-silicon emulator): the app itself never does this.
     * A SIGILL here then means the emulated CPU really lacks them.
     */
    @Test
    fun theTwoBuildsAnswerTheSamePrompt() {
        assumeTrue(LlmNative.loaded && LlmNative.cpuSupported())
        val hw = LlmNative.hwcaps()
        val forced = InstrumentationRegistry.getArguments().getString("forceI8mm") == "true"
        assumeTrue("this CPU doesn't report i8mm", forced || CpuFeatures.supportsI8mm(hw[0], hw[1]))
        assertTrue("libbenchllm_i8mm.so must load here: ${LlmNativeI8mm.loadError}", LlmNativeI8mm.loaded)
        // the real 3B model in both builds, one short prompt each: nothing is timed, this checks the answers
        val dot = answer(DotprodLib)
        val i8 = answer(I8mmLib)
        val agreement = LlmProbe.correlation(dot, i8)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("forced", if (forced && !CpuFeatures.supportsI8mm(hw[0], hw[1])) "yes: the kernel reports no i8mm" else "no")
            putString("agreement", "the i8mm build's logits correlate %.6f with the dot-product build's (%d logits)".format(agreement, dot.size))
        })
        assertEquals(dot.size, i8.size)
        assertTrue("the i8mm build's logits correlate %.4f with the dot-product build's".format(agreement), agreement >= LlmProbe.MIN_AGREEMENT)
    }

    /** The sampled logits of a 32-token prompt on the real model in `lib`. */
    private fun answer(lib: LlmCpuLib): FloatArray {
        val error = arrayOfNulls<String>(1)
        val h = lib.create(4, LlmNative.SEED, error)
        assertTrue("${lib.variant}: ${error[0]}", h != 0L)
        try {
            lib.setThreads(h, 4, 4)
            assertTrue("${lib.variant}: ${lib.error(h)}", lib.prefill(h, 32) > 0)
            return lib.lastSample(h)
        } finally { lib.destroy(h) }
    }

    @Test
    fun theDotProductBuildAloneStillRuns() {
        assumeTrue(LlmNative.loaded && LlmNative.cpuSupported())
        val sample = answer(DotprodLib)
        assertTrue("a logit sample is kept", sample.size > 1000 && sample.all { it.isFinite() })
    }

    @Test
    fun theDeviceProbesReturnTextAndNeverCrash() {
        assumeTrue(LlmNative.loaded)
        val vulkan = LlmNative.vulkanReport(LlmNative.gpuName())
        val opencl = LlmNative.openClReport()
        assertTrue(vulkan, vulkan.startsWith("Vulkan ·"))
        assertTrue(opencl, opencl.startsWith("OpenCL ·"))
        instrumentation.sendStatus(0, Bundle().apply { putString("vulkan", vulkan); putString("opencl", opencl) })
    }
}
