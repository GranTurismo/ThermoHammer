package com.example.thermohammer.engine.llm

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.engine.CpuAffinity
import com.example.thermohammer.engine.LlmAccel
import com.example.thermohammer.engine.ThreadTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The LLM test's stage-2 pieces on a device or the emulator: the CPU test with its thread search pinned and unpinned
 * (the calling thread given its own mask back, the report of every trial), the test in the i8mm library when it is
 * handed to it, and what llama.cpp's backends say about their GPU. An emulator's rates and cores are not a phone's.
 */
@RunWith(AndroidJUnit4::class)
class LlmStage2DeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val cores = (7 downTo 0).toList()

    private fun status(vararg kv: Pair<String, String>) = instrumentation.sendStatus(0, Bundle().apply { kv.forEach { putString(it.first, it.second) } })

    private fun cpuRun(lib: LlmCpuLib): com.example.thermohammer.engine.LlmRun {
        val before = CpuAffinity.mask()
        val started = System.nanoTime()
        val run = requireNotNull(LlmBench(app).run(LlmAccel.CPU, 4, { true }, lib, cores) {})
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(run.error ?: "the CPU test ran", run.error == null && run.prefill > 0 && run.decode > 0)
        assertEquals("the calling thread has its own mask back", before, CpuAffinity.mask())
        val tune = requireNotNull(run.tune) { "the thread search reported" }
        assertTrue("every thread of the process is free again: ${ThreadTasks.singleCore(ThreadTasks.masks(ThreadTasks.ids())).filterValues { it in cores }}",
            ThreadTasks.singleCore(ThreadTasks.masks(ThreadTasks.ids())).filterValues { it in cores }.isEmpty())
        status("kernels" to run.kernels, "total_seconds" to "%.1f".format(seconds),
            "rates" to "prefill %.2f decode %.2f · %d threads (prompt %d)".format(run.prefill, run.decode, run.threads, run.batchThreads),
            "tune" to tune.lines().joinToString("\n"))
        return run
    }

    @Test
    fun theCpuTestRunsWithItsPinnedSearchAndLeavesNothingPinned() {
        assumeTrue(LlmNative.loaded && LlmNative.cpuSupported() && Runtime.getRuntime().availableProcessors() >= 8)
        val run = cpuRun(DotprodLib)
        assertEquals("dotprod", run.kernels)
    }

    /** `-e forceI8mm true`, as in LlmCpuLibsTest: only for a host whose hypervisor hides an i8mm the hardware has. */
    @Test
    fun theCpuTestRunsInTheI8mmLibraryWhenItIsHandedToIt() {
        assumeTrue(LlmNative.loaded && LlmNative.cpuSupported() && Runtime.getRuntime().availableProcessors() >= 8)
        val hw = LlmNative.hwcaps()
        val forced = InstrumentationRegistry.getArguments().getString("forceI8mm") == "true"
        assumeTrue("this CPU doesn't report i8mm", forced || CpuFeatures.supportsI8mm(hw[0], hw[1]))
        assertTrue("libbenchllm_i8mm.so must load: ${LlmNativeI8mm.loadError}", LlmNativeI8mm.loaded)
        val run = cpuRun(I8mmLib)
        assertEquals("i8mm", run.kernels)
    }

    @Test
    fun theBackendsDeviceLinesAreCapturedAndTheBuildsShaderFamiliesNamed() {
        assumeTrue(LlmNative.loaded)
        val gpu = LlmNative.gpuName()                       // starts the backends: that is when they print their device lines
        val log = LlmNative.backendLog()
        val build = LlmNative.vulkanBuildFeatures()
        assertTrue(build, build.startsWith("Vulkan shaders built with: cooperative matrix "))
        val vulkan = LlmNative.vulkanReport(gpu)
        status("gpu" to gpu, "vulkan_build" to build, "backend_log" to log, "vulkan_available" to VulkanGpuLib.available().toString())
        // ggml's own debug-level Vulkan lines are captured: the device line where it found a GPU ("ggml_vulkan: 0 = … |
        // matrix cores: …"), otherwise its "No devices found." (it skips software devices such as the emulator's SwiftShader)
        assertTrue("ggml's Vulkan lines are in the log:\n$log", log.lines().any { it.startsWith("ggml_vulkan:") })
        if (!log.contains("No devices found")) assertTrue("the device line is there:\n$log", log.lines().any { it.startsWith("ggml_vulkan: 0 = ") && it.contains("matrix cores:") })
        status("vulkan_probe" to vulkan)
        assertEquals("naming a backend that isn't there leaves no GPU, naming none restores it", gpu, run {
            LlmNative.setGpuApi("NoSuchBackend"); val none = LlmNative.gpuName(); LlmNative.setGpuApi("")
            assertEquals("", none); LlmNative.gpuName()
        })
        assertNotNull(log)
    }
}
