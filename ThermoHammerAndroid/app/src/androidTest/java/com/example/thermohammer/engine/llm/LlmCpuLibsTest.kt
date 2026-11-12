package com.example.thermohammer.engine.llm

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two CPU kernel builds on a real CPU or the emulator: what the kernel says the CPU has, the i8mm library loading
 * only where it says i8mm, the two builds answering one prompt alike, and the device probes (Vulkan, OpenCL) returning
 * text instead of crashing. Rates go to the instrumentation status as "dotprod_prefill", "i8mm_prefill" and so on — they
 * are this device's, and an emulator's are not a phone's.
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
        val r = LlmProbe.run(listOf(DotprodLib, I8mmLib), threads = 4, batchThreads = 4, isActive = { true })
        val status = Bundle().apply {
            putString("forced", if (forced && !CpuFeatures.supportsI8mm(hw[0], hw[1])) "yes: the kernel reports no i8mm" else "no")
            putString("probe", LlmProbe.lines(r).joinToString("\n"))
            r.runs.forEach { putString("${it.variant}_rates", "prefill %.2f decode %.2f build %.1f s".format(it.prefill, it.decode, it.buildS)) }
        }
        for (run in r.runs) assertTrue("${run.variant}: ${run.error}", run.error == null && run.prefill > 0 && run.decode > 0)
        val agreement = r.runs[1].agreement
        assertNotNull(agreement)
        assertTrue("the i8mm build's logits correlate %.4f with the dot-product build's".format(agreement), agreement!! >= LlmProbe.MIN_AGREEMENT)
        instrumentation.sendStatus(0, status)
    }

    @Test
    fun theDotProductBuildAloneStillRuns() {
        assumeTrue(LlmNative.loaded && LlmNative.cpuSupported())
        val r = LlmProbe.run(listOf(DotprodLib), threads = 4, batchThreads = 4, isActive = { true })
        assertEquals(1, r.runs.size)
        assertTrue(r.runs[0].error ?: "", r.runs[0].error == null && r.runs[0].prefill > 0 && r.runs[0].decode > 0)
        assertFalse(r.runs[0].agreement != null)
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
