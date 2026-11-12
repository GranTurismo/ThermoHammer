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
        var cancelled = 0
        private var next = 1L
        private var lastError = ""
        override fun create(threads: Int, seed: Long, error: Array<String?>): Long {
            onCreate()
            if (createError != null) { error[0] = createError; return 0 }
            return next++
        }
        override fun setThreads(handle: Long, threads: Int, batchThreads: Int) { threadCalls += threads to batchThreads }
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
                opencl = listOf("OpenCL · 1 platform"))
        val lines = LlmProbe.lines(r)
        assertTrue(lines[0], lines[0].startsWith("LLM probe · NOT SCORED · ") && lines[0].contains("a 96-token prompt on 5 threads and a 12-token reply on 6"))
        assertTrue(lines[1], lines[1].startsWith("  CPU dotprod · libfake_dotprod.so · prefill 45.00 tok/s · decode 21.00 tok/s · built in "))
        assertTrue(lines[2], lines[2].contains("CPU i8mm · libfake_i8mm.so · prefill 90.00 tok/s (×2.00) · decode 22.05 tok/s (×1.05) · answers agree 1.0000"))
        assertEquals("  CPU sve · not tried: nothing", lines[3])
        assertEquals(listOf("CPU features · asimddp yes", "Vulkan · loader 1.3.0 · 1 device", "OpenCL · 1 platform"), lines.drop(4))
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
