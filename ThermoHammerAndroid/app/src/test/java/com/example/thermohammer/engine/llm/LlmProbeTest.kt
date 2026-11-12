package com.example.thermohammer.engine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProbeTest {

    /** A CPU kernel build with canned speeds and logits: no native code, so the probe's protocol runs on the JVM. */
    private class FakeLib(
        override val variant: String, private val prefillRate: Double, private val decodeRate: Double,
        private val logits: FloatArray = FloatArray(64) { (it * 7 % 13).toFloat() },
        private val createError: String? = null, private val failPrefillOver: Int = Int.MAX_VALUE,
        private val onCreate: () -> Unit = {}
    ) : LlmCpuLib {
        override val library = "libfake_$variant.so"
        val prefills = ArrayList<Int>()
        val destroyed = ArrayList<Long>()
        val threadCalls = ArrayList<Pair<Int, Int>>()
        val coreCalls = ArrayList<List<Int>>()
        var cancelled = 0
        private var next = 1L
        private var lastError = ""
        override fun create(threads: Int, seed: Long, error: Array<String?>): Long {
            onCreate()
            if (createError != null) { error[0] = createError; return 0 }
            return next++
        }
        override fun setThreads(handle: Long, threads: Int, batchThreads: Int) { threadCalls += threads to batchThreads }
        override fun setCores(handle: Long, cores: IntArray, pinDecode: Boolean, pinPrompt: Boolean): Int { coreCalls += cores.toList(); return cores.size }
        override fun prefill(handle: Long, tokens: Int): Double {
            prefills += tokens
            if (tokens > failPrefillOver) { lastError = "out of memory"; return -1.0 }
            return tokens / prefillRate
        }
        override fun reset(handle: Long) {}
        override fun next(handle: Long, token: IntArray?) = 1.0 / decodeRate
        override fun cancel(handle: Long) { cancelled++ }
        override fun error(handle: Long) = lastError
        override fun lastSample(handle: Long) = logits
        override fun destroy(handle: Long) { destroyed += handle }
    }

    private fun run(vararg libs: LlmCpuLib, active: () -> Boolean = { true }, events: MutableList<String> = ArrayList()) =
        LlmProbe.run(libs.toList(), 6, 5, active, around = { lib, start -> events += "${lib.variant}${if (start) "+" else "-"}" })

    @Test
    fun `the prompt is sized to about two seconds in multiples of 16 within the model's limits`() {
        assertEquals(96, LlmProbe.promptFor(45.0))
        assertEquals(160, LlmProbe.promptFor(78.0))
        assertEquals("a slow CPU still reads a minimum", 32, LlmProbe.promptFor(5.0))
        assertEquals("a fast one is held to the model's context", 512, LlmProbe.promptFor(800.0))
        assertEquals(32, LlmProbe.promptFor(0.0))
        assertEquals(32, LlmProbe.promptFor(Double.NaN))
        assertEquals(32, LlmProbe.promptFor(-3.0))
    }

    @Test
    fun `logit samples correlate like the GPU's check against the CPU's`() {
        val a = FloatArray(100) { it * 0.1f }
        assertEquals(1.0, LlmProbe.correlation(a, a), 1e-9)
        assertEquals(1.0, LlmProbe.correlation(a, FloatArray(100) { it * 0.2f + 3f }), 1e-6)
        assertEquals(-1.0, LlmProbe.correlation(a, FloatArray(100) { -it * 0.1f }), 1e-6)
        assertEquals("a flat output has no correlation", 0.0, LlmProbe.correlation(a, FloatArray(100)), 0.0)
        assertEquals(0.0, LlmProbe.correlation(a, FloatArray(10)), 0.0)
        assertEquals(0.0, LlmProbe.correlation(FloatArray(0), FloatArray(0)), 0.0)
    }

    @Test
    fun `every build gets the same prompt, sized by the first, and its rates come out as measured`() {
        val dot = FakeLib("dotprod", prefillRate = 45.0, decodeRate = 21.0)
        val i8 = FakeLib("i8mm", prefillRate = 90.0, decodeRate = 22.0)
        val r = run(dot, i8)
        assertEquals(2, r.runs.size)
        assertEquals(96, r.promptTokens)
        assertEquals(listOf(8, 16, 96), dot.prefills)
        assertEquals("the second build reads the first's prompt, not one sized for itself", listOf(8, 16, 96), i8.prefills)
        assertEquals(45.0, r.runs[0].prefill, 1e-9); assertEquals(21.0, r.runs[0].decode, 1e-9)
        assertEquals(90.0, r.runs[1].prefill, 1e-9); assertEquals(22.0, r.runs[1].decode, 1e-9)
        assertNull("the first build is the reference", r.runs[0].agreement)
        assertEquals(1.0, r.runs[1].agreement!!, 1e-9)
        assertEquals(listOf(6 to 5), dot.threadCalls)
        assertEquals(6, r.threads); assertEquals(5, r.batchThreads)
        assertEquals(LlmProbe.REPLY, r.replyTokens)
        assertEquals("every model is freed", listOf(1L), dot.destroyed); assertEquals(listOf(1L), i8.destroyed)
    }

    @Test
    fun `a build whose answer to the prompt differs is reported as different`() {
        val dot = FakeLib("dotprod", 45.0, 21.0)
        val wrong = FakeLib("i8mm", 90.0, 22.0, logits = FloatArray(64) { ((it * 37) % 29).toFloat() })
        val r = run(dot, wrong)
        assertTrue(r.runs[1].agreement!! < LlmProbe.MIN_AGREEMENT)
        assertTrue(LlmProbe.lines(r).any { it.contains("DIFFERENT") })
    }

    @Test
    fun `a build that can't make its model or fails a prompt is a row with its reason and the rest carry on`() {
        val noModel = FakeLib("dotprod", 45.0, 21.0, createError = "not enough memory")
        val bigFail = FakeLib("i8mm", 90.0, 22.0, failPrefillOver = 64)
        val events = ArrayList<String>()
        val r = run(noModel, bigFail, events = events)
        assertEquals("not enough memory", r.runs[0].error)
        assertEquals("out of memory", r.runs[1].error)
        assertEquals(0.0, r.runs[1].prefill, 0.0)
        assertEquals("both start and stop are announced, failing or not", listOf("dotprod+", "dotprod-", "i8mm+", "i8mm-"), events)
        assertEquals(listOf(1L), bigFail.destroyed)
        assertTrue(noModel.destroyed.isEmpty())
    }

    @Test
    fun `the first build failing leaves the second to size the prompt`() {
        val dot = FakeLib("dotprod", 45.0, 21.0, createError = "no")
        val i8 = FakeLib("i8mm", 78.0, 22.0)
        val r = run(dot, i8)
        assertEquals(160, r.promptTokens)
        assertNull("with no reference there is nothing to agree with", r.runs[1].agreement)
    }

    @Test
    fun `a second build that would end past the budget isn't started`() {
        var now = 0L
        val slow = FakeLib("dotprod", 9.0, 5.0, onCreate = { now += 14_000_000_000L })     // a 14 s build on a slow CPU
        val i8 = FakeLib("i8mm", 90.0, 22.0)
        val r = LlmProbe.run(listOf(slow, i8), 6, 5, { true }, nanoTime = { now })
        assertEquals(1, r.runs.size)
        assertTrue(r.skipped.getValue("i8mm"), r.skipped.getValue("i8mm").startsWith("out of time"))
        assertTrue(i8.prefills.isEmpty() && i8.destroyed.isEmpty())
        assertTrue(LlmProbe.lines(r).any { it.startsWith("  CPU i8mm · not tried: out of time") })

        var t = 0L                                                                         // a flagship: 3 s builds, both fit
        val fast = FakeLib("dotprod", 45.0, 21.0, onCreate = { t += 3_000_000_000L })
        val fast8 = FakeLib("i8mm", 90.0, 22.0, onCreate = { t += 3_000_000_000L })
        val both = LlmProbe.run(listOf(fast, fast8), 6, 5, { true }, nanoTime = { t })
        assertEquals(2, both.runs.size)
        assertTrue(both.skipped.isEmpty())
    }

    @Test
    fun `a stopped run returns what it has and frees what it built`() {
        val dot = FakeLib("dotprod", 45.0, 21.0)
        val i8 = FakeLib("i8mm", 90.0, 22.0)
        var calls = 0
        val r = run(dot, i8, active = { ++calls <= 1 })   // the second library's turn finds the run stopped
        assertEquals(1, r.runs.size)
        assertTrue(i8.prefills.isEmpty())
        assertTrue(run(FakeLib("dotprod", 1.0, 1.0), active = { false }).runs.isEmpty())
    }

    @Test
    fun `the report says which build is how much faster and what wasn't tried`() {
        val r = run(FakeLib("dotprod", 45.0, 21.0), FakeLib("i8mm", 90.0, 22.05))
            .copy(skipped = mapOf("sve" to "nothing"), cpuFeatures = "CPU features · asimddp yes", vulkan = listOf("Vulkan · loader 1.3.0 · 1 device"),
                opencl = listOf("OpenCL · 1 platform"), chosen = "i8mm", why = "×1.42 over the dot-product build")
        val lines = LlmProbe.lines(r)
        assertTrue(lines[0], lines[0].startsWith("LLM probe · ") && lines[0].contains("a 96-token prompt on 5 threads and a 12-token reply on 6, unpinned") &&
            lines[0].endsWith("the CPU test ran in the i8mm build: ×1.42 over the dot-product build"))
        assertTrue(lines[1], lines[1].startsWith("  CPU dotprod · libfake_dotprod.so · prefill 45.00 tok/s · decode 21.00 tok/s · built in ") && !lines[1].contains("USED"))
        assertTrue(lines[2], lines[2].contains("CPU i8mm · libfake_i8mm.so · prefill 90.00 tok/s (×2.00) · decode 22.05 tok/s (×1.05) · answers agree 1.0000"))
        assertTrue("the build the score came from is marked", lines[2].endsWith("USED FOR THE SCORE"))
        assertEquals("  CPU sve · not tried: nothing", lines[3])
        assertEquals(listOf("CPU features · asimddp yes", "Vulkan · loader 1.3.0 · 1 device", "OpenCL · 1 platform"), lines.drop(4))
    }

    @Test
    fun `the probe pins every build to the same cores and says so`() {
        val dot = FakeLib("dotprod", 45.0, 21.0); val i8 = FakeLib("i8mm", 90.0, 22.0)
        val r = LlmProbe.run(listOf(dot, i8), 4, 4, { true }, cores = listOf(7, 4, 5, 6))
        assertEquals(listOf(listOf(7, 4, 5, 6)), dot.coreCalls)
        assertEquals("the same cores for each build, so the comparison is one of kernels", dot.coreCalls, i8.coreCalls)
        assertEquals(listOf(7, 4, 5, 6), r.pinnedCores)
        assertTrue(LlmProbe.lines(r)[0], LlmProbe.lines(r)[0].contains("pinned to cores 7,4,5,6"))
        val plain = run(FakeLib("dotprod", 45.0, 21.0))
        assertTrue("with no cores nothing is pinned", plain.pinnedCores.isEmpty())
    }

    @Test
    fun `a phone with only the dot-product build says why nothing was probed and still reports what it offers`() {
        val lines = LlmProbe.lines(LlmProbeResult(skipped = mapOf("i8mm" to "this CPU doesn't report i8mm (AT_HWCAP2 bit 13)"),
            cpuFeatures = "CPU features · asimddp yes · i8mm no"))
        assertEquals(listOf("LLM probe · not run: the dot-product build is the only CPU build that applies on this phone",
            "  CPU i8mm · not tried: this CPU doesn't report i8mm (AT_HWCAP2 bit 13)", "CPU features · asimddp yes · i8mm no"), lines)
    }

    @Test
    fun `a build that failed shows as zero with the reason`() {
        val r = run(FakeLib("dotprod", 1.0, 1.0, createError = "no model"))
        assertEquals("  CPU dotprod · libfake_dotprod.so · 0 (no model)", LlmProbe.lines(r)[1])
    }

    // ── which kernel build the CPU test runs in ─────────────────────────────────

    private fun runs(prefill: Double, decode: Double, agreement: Double? = 0.9999, error: String? = null) = listOf(
        LlmProbeRun("dotprod", "libbenchllm.so", 45.0, 20.0),
        LlmProbeRun("i8mm", "libbenchllm_i8mm.so", prefill, decode, agreement = agreement, error = error))

    @Test
    fun `the i8mm build is chosen when it is clearly faster over both phases and agrees`() {
        val c = LlmProbe.choose(runs(prefill = 90.0, decode = 20.4))      // prompt ×2.0, reply ×1.02: geometric mean ×1.43
        assertEquals("i8mm", c.variant)
        assertTrue(c.why, c.why.contains("×1.43"))
        assertEquals("a gain from the prompt alone counts", "i8mm", LlmProbe.choose(runs(prefill = 50.0, decode = 20.0)).variant)   // ×1.054
    }

    @Test
    fun `a gain under five percent, or noise, keeps the dot-product build`() {
        val c = LlmProbe.choose(runs(prefill = 46.0, decode = 20.5))
        assertEquals("dotprod", c.variant)
        assertTrue(c.why, c.why.contains("gains only"))
        assertEquals("dotprod", LlmProbe.choose(runs(prefill = 47.0, decode = 20.0)).variant)       // ×1.044
    }

    @Test
    fun `a build that is clearly slower in either phase can't win however fast the other is`() {
        val c = LlmProbe.choose(runs(prefill = 120.0, decode = 18.0))      // prompt ×2.67 but reply ×0.90
        assertEquals("dotprod", c.variant)
        assertTrue(c.why, c.why.contains("slower at writing the reply"))
        assertEquals("dotprod", LlmProbe.choose(runs(prefill = 40.0, decode = 30.0)).variant)
        assertEquals("within the margin of noise it isn't 'clearly slower'", "i8mm", LlmProbe.choose(runs(prefill = 100.0, decode = 19.1)).variant)   // reply ×0.955
    }

    @Test
    fun `answers that differ, a failed build or a missing reference keep the dot-product build`() {
        assertEquals("dotprod", LlmProbe.choose(runs(200.0, 40.0, agreement = 0.5)).variant)
        assertTrue(LlmProbe.choose(runs(200.0, 40.0, agreement = 0.5)).why.contains("differs"))
        assertEquals("dotprod", LlmProbe.choose(runs(200.0, 40.0, agreement = LlmProbe.MIN_AGREEMENT - 0.01)).variant)
        assertEquals("i8mm", LlmProbe.choose(runs(200.0, 40.0, agreement = LlmProbe.MIN_AGREEMENT)).variant)
        assertEquals("dotprod", LlmProbe.choose(runs(0.0, 0.0, error = "out of memory")).variant)
        assertEquals("dotprod", LlmProbe.choose(runs(200.0, 40.0, agreement = null)).variant)
        assertEquals("dotprod", LlmProbe.choose(emptyList()).variant)
        assertEquals("dotprod", LlmProbe.choose(runs(200.0, 40.0).take(1)).variant)
        // the dot-product build's own run failed: no baseline, so no switch on a guess
        assertEquals("dotprod", LlmProbe.choose(listOf(LlmProbeRun("dotprod", "a", error = "boom"), runs(200.0, 40.0)[1])).variant)
    }

    @Test
    fun `the whole probe picks the build the numbers say`() {
        val r = run(FakeLib("dotprod", 45.0, 21.0), FakeLib("i8mm", 90.0, 22.0))
        assertEquals("i8mm", LlmProbe.choose(r.runs).variant)
        val slow = run(FakeLib("dotprod", 45.0, 21.0), FakeLib("i8mm", 45.5, 21.0))
        assertEquals("dotprod", LlmProbe.choose(slow.runs).variant)
    }

    // ── Vulkan on an Adreno: report only ─────────────────────────────────────────

    /** A GPU backend with canned rates, for the probe's protocol. */
    private class FakeGpu(private val prefillRate: Double, private val decodeRate: Double, private val createError: String? = null,
                          private val failPrefill: Boolean = false, private val agree: Double = 0.9999) : LlmGpuLib {
        override val api = "Vulkan"
        val prefills = ArrayList<Int>(); var released = 0; var destroyed = 0
        override fun create(error: Array<String?>): Long { if (createError != null) { error[0] = createError; return 0 }; return 1 }
        override fun deviceName() = "Adreno (TM) 830"
        override fun prefill(handle: Long, tokens: Int): Double { prefills += tokens; return if (failPrefill) -1.0 else tokens / prefillRate }
        override fun reset(handle: Long) {}
        override fun next(handle: Long, token: IntArray?) = 1.0 / decodeRate
        override fun cancel(handle: Long) {}
        override fun error(handle: Long) = "the GPU's results differ from the CPU's (correlation 0.31)"
        override fun agreement(handle: Long) = agree
        override fun destroy(handle: Long) { destroyed++ }
        override fun release() { released++ }
    }

    @Test
    fun `Vulkan on an Adreno reads the 512 prompt twice and times the second, then undoes its backend choice`() {
        val gpu = FakeGpu(prefillRate = 180.0, decodeRate = 14.0)
        val events = ArrayList<String>()
        val g = LlmProbe.runGpu(gpu, Triple("OpenCL", 300.0, 12.0), { true }, around = { events += if (it) "+" else "-" })
        assertEquals(listOf(512, 512), gpu.prefills)
        assertEquals(180.0, g.prefill, 1e-9); assertEquals(14.0, g.decode, 1e-9)
        assertEquals(0.9999, g.agreement!!, 1e-9)
        assertEquals("Adreno (TM) 830", g.device); assertEquals("OpenCL", g.vsApi); assertEquals(300.0, g.vsPrefill, 0.0)
        assertEquals(listOf("+", "-"), events)
        assertEquals(1, gpu.destroyed); assertEquals("the backend choice is always undone", 1, gpu.released)
        assertNull(g.error)
    }

    @Test
    fun `a Vulkan model that can't be built or answers wrongly is a row with its reason`() {
        val noModel = FakeGpu(1.0, 1.0, createError = "no Vulkan device")
        val a = LlmProbe.runGpu(noModel, Triple("OpenCL", 1.0, 1.0), { true })
        assertEquals("no Vulkan device", a.error); assertEquals(1, noModel.released); assertEquals(0, noModel.destroyed)
        val wrong = FakeGpu(100.0, 10.0, failPrefill = true)
        val b = LlmProbe.runGpu(wrong, Triple("OpenCL", 1.0, 1.0), { true })
        assertTrue(b.error!!, b.error!!.contains("differ from the CPU's"))
        assertEquals(1, wrong.destroyed); assertEquals(1, wrong.released)
    }

    @Test
    fun `the report sets Vulkan against the scored backend and says it is not scored`() {
        val g = LlmProbe.runGpu(FakeGpu(150.0, 15.0), Triple("OpenCL", 300.0, 12.0), { true })
        val lines = LlmProbe.lines(LlmProbeResult(gpu = g, vulkanBuild = "Vulkan shaders built with: cooperative matrix no · integer dot product (the int8 matrix kernels) no · bf16 no",
            backendLog = listOf("ggml_vulkan: 0 = Adreno (TM) 830 (Qualcomm) | uma: 1 | fp16: 1 | bf16: 0 | fp4: 0 | warp size: 64 | shared memory: 32768 | int dot: 0 | matrix cores: none"),
            topology = "CPU topology · by cpuinfo_max_freq · 4.32 GHz: cpu6-7 · 3.53 GHz: cpu0-5 · pinning order 6,7,0,1,2,3,4,5"))
        val gpu = lines.first { it.startsWith("  GPU Vulkan on Adreno (TM) 830") }
        assertTrue(gpu, gpu.contains("REPORT ONLY, the scored GPU backend is OpenCL") && gpu.contains("prefill 150.00 tok/s (×0.50 of OpenCL's)") && gpu.contains("decode 15.00 tok/s (×1.25)"))
        assertTrue(lines.any { it.startsWith("Vulkan shaders built with:") })
        assertTrue(lines.any { it == "  llama.cpp backend: ggml_vulkan: 0 = Adreno (TM) 830 (Qualcomm) | uma: 1 | fp16: 1 | bf16: 0 | fp4: 0 | warp size: 64 | shared memory: 32768 | int dot: 0 | matrix cores: none" })
        assertTrue(lines.any { it.startsWith("CPU topology ·") })
        val skipped = LlmProbe.lines(LlmProbeResult(gpuSkipped = "crashed the app here last time"))
        assertTrue(skipped.contains("  GPU Vulkan on an Adreno · not tried: crashed the app here last time"))
    }

    @Test
    fun `Vulkan is also run only on an Adreno whose scored backend is OpenCL and whose Vulkan works`() {
        assertTrue(LlmProbe.vulkanOnAdrenoDecision("QUALCOMM Adreno(TM) 830", "OpenCL", vulkanDevice = true, crashed = false, gpuRan = true).use)
        val mali = LlmProbe.vulkanOnAdrenoDecision("Mali-G1-Ultra MC12", "Vulkan", true, false, true)
        assertFalse(mali.use); assertTrue(mali.why!!, mali.why!!.contains("nothing to compare"))
        assertFalse("an Adreno already on Vulkan has nothing to compare", LlmProbe.vulkanOnAdrenoDecision("Adreno 830", "Vulkan", true, false, true).use)
        assertFalse(LlmProbe.vulkanOnAdrenoDecision("Adreno 830", "OpenCL", false, false, true).use)
        assertEquals("crashed the app here last time", LlmProbe.vulkanOnAdrenoDecision("Adreno 830", "OpenCL", true, true, true).why)
        assertFalse("no scored GPU run to set it against", LlmProbe.vulkanOnAdrenoDecision("Adreno 830", "OpenCL", true, false, false).use)
        assertFalse(LlmProbe.vulkanOnAdrenoDecision("", "", false, false, false).use)
    }

    // ── which builds run ─────────────────────────────────────────────────────

    private val hw = 0xFFFFFFFFL                       // every low HWCAP bit: the dot product and FP16 are there
    private val i8mm = 1L shl 13

    @Test
    fun `the i8mm build is chosen only where every condition holds`() {
        var asked = 0
        val ok = LlmProbe.i8mmDecision(hw, i8mm, crashed = false) { asked++; null }
        assertTrue(ok.use); assertNull(ok.why); assertEquals(1, asked)
    }

    @Test
    fun `a phone without i8mm never asks for the library`() {
        var asked = 0
        val d = LlmProbe.i8mmDecision(hw, 0L, crashed = false) { asked++; null }
        assertFalse(d.use)
        assertTrue(d.why!!, d.why!!.contains("i8mm"))
        assertEquals("the library isn't even loaded", 0, asked)
    }

    @Test
    fun `no feature words, no base kernels, a crash or a library that won't load each keep the dot product build`() {
        assertFalse(LlmProbe.i8mmDecision(null, null, false) { null }.use)
        assertFalse(LlmProbe.i8mmDecision(hw, null, false) { null }.use)
        val noDot = LlmProbe.i8mmDecision(hw and (1L shl 20).inv(), i8mm, false) { null }
        assertFalse(noDot.use); assertTrue(noDot.why!!, noDot.why!!.contains("dot product"))
        val crashed = LlmProbe.i8mmDecision(hw, i8mm, crashed = true) { null }
        assertFalse(crashed.use); assertEquals("crashed the app here last time", crashed.why)
        val missing = LlmProbe.i8mmDecision(hw, i8mm, false) { "dlopen failed: library not found" }
        assertFalse(missing.use); assertTrue(missing.why!!, missing.why!!.contains("dlopen failed"))
        assertNotNull(missing.why)
    }
}
