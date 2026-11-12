package com.example.thermohammer.engine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProbeTest {

    /** The self-test answer of a build whose arithmetic is right: the same in every build, up to `noise` (relative) of rounding. */
    private fun answer(noise: Double = 0.0, salt: Int = 0) = FloatArray(LlmProbe.SELFTEST_LOGITS) {
        val base = (it % 1024) * 7 % 13 + (it / 1024) * 3.0
        (base + noise * ((it * 31 + salt) % 17 - 8)).toFloat()
    }

    /** A CPU kernel build with a canned self-test answer: no native code, so the probe's protocol runs on the JVM. */
    private class FakeLib(
        override val variant: String, private val logits: FloatArray? = null, private val failure: String? = null,
        private val throws: Throwable? = null, private val onSelftest: () -> Unit = {}
    ) : LlmCpuLib {
        override val library = "libfake_$variant.so"
        val selftests = ArrayList<Int>()
        override fun create(threads: Int, seed: Long, error: Array<String?>): Long = error("the self-test builds no 3B model")
        override fun setThreads(handle: Long, threads: Int, batchThreads: Int) = error("the self-test times no threads")
        override fun prefill(handle: Long, tokens: Int): Double = error("nothing is timed")
        override fun reset(handle: Long) = error("nothing is timed")
        override fun next(handle: Long, token: IntArray?): Double = error("nothing is timed")
        override fun cancel(handle: Long) {}
        override fun error(handle: Long) = ""
        override fun lastSample(handle: Long): FloatArray = error("not used")
        override fun destroy(handle: Long) {}
        override fun kernelSelftest(threads: Int, error: Array<String?>): FloatArray? {
            selftests += threads
            onSelftest()
            throws?.let { throw it }
            if (logits == null) { error[0] = failure ?: "the self-test failed"; return null }
            return logits
        }
    }

    private fun check(base: LlmCpuLib, candidate: LlmCpuLib, active: () -> Boolean = { true }, events: MutableList<String> = ArrayList(), nanoTime: () -> Long = System::nanoTime) =
        LlmProbe.checkKernels(base, candidate, 4, active, nanoTime, around = { lib, start -> events += "${lib.variant}${if (start) "+" else "-"}" })

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

    // ── the i8mm build's self-test ─────────────────────────────────────────────

    @Test
    fun `answers that agree choose the i8mm build, and each build runs its self-test once on the threads asked`() {
        val dot = FakeLib("dotprod", answer()); val i8 = FakeLib("i8mm", answer(noise = 1e-4, salt = 5))
        val events = ArrayList<String>()
        val r = check(dot, i8, events = events)
        assertEquals("i8mm", r.chosen)
        assertTrue(r.why!!, r.why!!.contains("agree"))
        assertEquals(listOf(4), dot.selftests); assertEquals(listOf(4), i8.selftests)
        assertEquals("each build leaves its crash marker only while it runs", listOf("dotprod+", "dotprod-", "i8mm+", "i8mm-"), events)
        assertEquals(listOf("dotprod", "i8mm"), r.checks.map { it.variant })
        assertNull("the first build is the reference", r.checks[0].promptAgreement)
        assertTrue(r.checks[1].promptAgreement!! > 0.999 && r.checks[1].nextAgreement!! > 0.999)
    }

    @Test
    fun `nothing is timed against the 3B model, only the self-test's own milliseconds are reported`() {
        var t = 0L
        val dot = FakeLib("dotprod", answer(), onSelftest = { t += 5_000_000 })
        val i8 = FakeLib("i8mm", answer(), onSelftest = { t += 7_000_000 })
        val r = check(dot, i8, nanoTime = { t })
        assertEquals(5.0, r.checks[0].ms, 1e-9); assertEquals(7.0, r.checks[1].ms, 1e-9)
        assertEquals(0.012, r.seconds, 1e-9)
    }

    @Test
    fun `an answer that differs keeps the dot-product build, whichever half differs`() {
        val garbage = FloatArray(LlmProbe.SELFTEST_LOGITS) { ((it * 2654435761L) % 1000).toFloat() }
        val a = check(FakeLib("dotprod", answer()), FakeLib("i8mm", garbage))
        assertEquals("dotprod", a.chosen); assertTrue(a.why!!, a.why!!.contains("differ"))
        // right for the prompt, wrong for the next token (the matrix-vector kernels)
        val half = answer().also { for (i in 1024 until 2048) it[i] = garbage[i] }
        val b = check(FakeLib("dotprod", answer()), FakeLib("i8mm", half))
        assertEquals("dotprod", b.chosen)
        assertTrue(b.checks[1].promptAgreement!! > 0.999 && b.checks[1].nextAgreement!! < LlmProbe.SELFTEST_MIN_AGREEMENT)
        // …and the other way round
        val other = answer().also { for (i in 0 until 1024) it[i] = garbage[i] }
        assertEquals("dotprod", check(FakeLib("dotprod", answer()), FakeLib("i8mm", other)).chosen)
    }

    @Test
    fun `a flat or non-matching answer has no correlation and is not chosen`() {
        assertEquals("dotprod", check(FakeLib("dotprod", answer()), FakeLib("i8mm", FloatArray(LlmProbe.SELFTEST_LOGITS))).chosen)
        assertEquals("a short answer isn't an answer", "dotprod", check(FakeLib("dotprod", answer()), FakeLib("i8mm", FloatArray(100) { it.toFloat() })).chosen)
    }

    @Test
    fun `the threshold is at the documented line`() {
        val at = answer()
        // the answer plus `k` times noise: the bigger k, the worse the worse half correlates with `at`
        fun noisy(k: Double) = FloatArray(LlmProbe.SELFTEST_LOGITS) { (at[it] + k * (((it * 2654435761L) % 2001) - 1000) / 1000.0).toFloat() }
        fun worstHalf(v: FloatArray) = minOf(LlmProbe.correlation(at.copyOfRange(0, 1024), v.copyOfRange(0, 1024)),
            LlmProbe.correlation(at.copyOfRange(1024, 2048), v.copyOfRange(1024, 2048)))
        var low = 0.0; var high = 100.0                   // worstHalf(noisy(low)) ≥ the threshold > worstHalf(noisy(high))
        repeat(60) { val mid = (low + high) / 2; if (worstHalf(noisy(mid)) >= LlmProbe.SELFTEST_MIN_AGREEMENT) low = mid else high = mid }
        assertTrue("the bisection found the line ($low)", low > 0.1 && low < 50)
        assertEquals("i8mm", check(FakeLib("dotprod", at), FakeLib("i8mm", noisy(low * 0.9))).chosen)
        assertEquals("dotprod", check(FakeLib("dotprod", at), FakeLib("i8mm", noisy(high * 1.1))).chosen)
    }

    @Test
    fun `a self-test that fails or throws, in either build, keeps the dot-product build with the reason`() {
        val failed = check(FakeLib("dotprod", answer()), FakeLib("i8mm", failure = "llama.cpp could not build the self-test model"))
        assertEquals("dotprod", failed.chosen); assertTrue(failed.why!!, failed.why!!.contains("could not build the self-test model"))
        assertEquals("llama.cpp could not build the self-test model", failed.checks[1].error)
        val thrown = check(FakeLib("dotprod", answer()), FakeLib("i8mm", throws = UnsatisfiedLinkError("no kernelSelftest")))
        assertEquals("dotprod", thrown.chosen); assertEquals("no kernelSelftest", thrown.checks[1].error)
        val reference = check(FakeLib("dotprod", failure = "no memory"), FakeLib("i8mm", answer()))
        assertEquals("with no reference there is nothing to agree with", "dotprod", reference.chosen)
        assertTrue(reference.why!!, reference.why!!.contains("dot-product build's own self-test failed"))
    }

    @Test
    fun `a self-test that throws still takes its crash marker down`() {
        val events = ArrayList<String>()
        check(FakeLib("dotprod", answer()), FakeLib("i8mm", throws = IllegalStateException("boom")), events = events)
        assertEquals(listOf("dotprod+", "dotprod-", "i8mm+", "i8mm-"), events)
    }

    @Test
    fun `a stopped run doesn't start the next build`() {
        val dot = FakeLib("dotprod", answer()); val i8 = FakeLib("i8mm", answer())
        var asked = 0
        val r = check(dot, i8, active = { asked++ < 1 })
        assertEquals(listOf(4), dot.selftests); assertTrue(i8.selftests.isEmpty())
        assertEquals("dotprod", r.chosen)
        assertTrue(check(FakeLib("dotprod", answer()), FakeLib("i8mm", answer()), active = { false }).checks.isEmpty())
    }

    @Test
    fun `choose over canned checks`() {
        fun c(variant: String, p: Double? = null, n: Double? = null, error: String? = null) = LlmKernelCheck(variant, "lib", 5.0, p, n, error)
        assertEquals("i8mm", LlmProbe.choose(listOf(c("dotprod"), c("i8mm", 0.99999, 0.99998))).variant)
        assertEquals("i8mm", LlmProbe.choose(listOf(c("dotprod"), c("i8mm", LlmProbe.SELFTEST_MIN_AGREEMENT, LlmProbe.SELFTEST_MIN_AGREEMENT))).variant)
        assertEquals("dotprod", LlmProbe.choose(listOf(c("dotprod"), c("i8mm", LlmProbe.SELFTEST_MIN_AGREEMENT - 0.001, 1.0))).variant)
        assertEquals("dotprod", LlmProbe.choose(listOf(c("dotprod"), c("i8mm", 1.0, Double.NaN))).variant)
        assertEquals("dotprod", LlmProbe.choose(listOf(c("dotprod"), c("i8mm"))).variant)               // never compared
        assertEquals("dotprod", LlmProbe.choose(emptyList()).variant)
        assertEquals("dotprod", LlmProbe.choose(listOf(c("dotprod"))).variant)
        assertEquals("dotprod", LlmProbe.choose(listOf(c("i8mm", 1.0, 1.0))).variant)                    // no reference build
    }

    @Test
    fun `the report says which build ran the CPU test and how the answers compared`() {
        val r = check(FakeLib("dotprod", answer()), FakeLib("i8mm", answer(noise = 1e-4)))
            .copy(skipped = mapOf("sve" to "nothing"), cpuFeatures = "CPU features · asimddp yes", vulkan = listOf("Vulkan · loader 1.3.0 · 1 device"),
                opencl = listOf("OpenCL · 1 platform"))
        val lines = LlmProbe.lines(r)
        assertTrue(lines[0], lines[0].startsWith("LLM probe · kernel self-test, ") && lines[0].contains("nothing is timed against the 3B model") &&
            lines[0].contains("the CPU test ran in the i8mm build: its self-test answers agree"))
        assertTrue(lines[1], lines[1].startsWith("  CPU dotprod · libfake_dotprod.so · self-test ") && !lines[1].contains("USED") && !lines[1].contains("agree"))
        assertTrue(lines[2], lines[2].contains("CPU i8mm · libfake_i8mm.so · self-test ") && lines[2].contains("answers agree: prompt 1.0") && lines[2].contains("next token 1.0") && !lines[2].contains("DIFFERENT"))
        assertTrue("the build the score came from is marked", lines[2].endsWith("USED FOR THE SCORE"))
        assertEquals("  CPU sve · not tried: nothing", lines[3])
        assertEquals(listOf("CPU features · asimddp yes", "Vulkan · loader 1.3.0 · 1 device", "OpenCL · 1 platform"), lines.drop(4))
    }

    @Test
    fun `a different answer is shown as different and the dot-product build as the one used`() {
        val garbage = FloatArray(LlmProbe.SELFTEST_LOGITS) { ((it * 2654435761L) % 1000).toFloat() }
        val lines = LlmProbe.lines(check(FakeLib("dotprod", answer()), FakeLib("i8mm", garbage)))
        assertTrue(lines[0], lines[0].contains("the CPU test ran in the dotprod build: the i8mm build's answers differ"))
        assertTrue(lines[2], lines[2].contains("— DIFFERENT") && !lines[2].contains("USED"))
        assertTrue(lines[1], lines[1].endsWith("USED FOR THE SCORE"))
    }

    @Test
    fun `a phone with only the dot-product build says why nothing was checked and still reports what it offers`() {
        val lines = LlmProbe.lines(LlmProbeResult(skipped = mapOf("i8mm" to "this CPU doesn't report i8mm (AT_HWCAP2 bit 13)"),
            cpuFeatures = "CPU features · asimddp yes · i8mm no"))
        assertEquals(listOf("LLM probe · no kernel self-test: the dot-product build is the only CPU build that applies on this phone",
            "  CPU i8mm · not tried: this CPU doesn't report i8mm (AT_HWCAP2 bit 13)", "CPU features · asimddp yes · i8mm no"), lines)
    }

    @Test
    fun `a build whose self-test failed shows the reason`() {
        val r = check(FakeLib("dotprod", answer()), FakeLib("i8mm", failure = "no model"))
        assertEquals("  CPU i8mm · libfake_i8mm.so · self-test failed (no model)", LlmProbe.lines(r)[2])
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
