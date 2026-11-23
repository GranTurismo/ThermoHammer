package com.example.thermohammer.engine.llm

import kotlin.concurrent.thread
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// LLM PROBE — is the i8mm CPU kernel build safe to run the CPU test in on THIS phone, and what else the phone's GPU
// stack offers.
//
//   libbenchllm.so's CPU kernels use the Arm dot product; libbenchllm_i8mm.so's also use i8mm, which a CPU that has it
//   runs a prompt on in fewer instructions. A CPU whose kernel feature words say it has i8mm gets a few-millisecond
//   SELF-TEST in both builds (bc_llm_kernel_selftest: a tiny seeded model reads a prompt and one more token) and the
//   CPU test runs in the i8mm build when its logits agree with the dot-product build's ([choose]). Nothing is timed:
//   a timing race between the two 3B-model builds took about 14 s of CPU work just before the scored CPU test and
//   started it on a warm phone, and the phone is on the scoreboard by what it does cold. The rates the CPU test then
//   measures are in the report beside the runs of builds without i8mm, so whether it helped is read from those.
//
//   Where the GPU isn't a Vulkan-only one the probe also runs llama.cpp's Vulkan backend on an Adreno, REPORT ONLY,
//   AFTER every scored test (the LLM test is the last of them): the scored GPU backend stays OpenCL there (see runGpu).
//
//   Plain Kotlin over [LlmCpuLib] and [LlmGpuLib], so the protocol and its numbers are tested on the JVM with fakes.
// ─────────────────────────────────────────────────────────────────────────────

/** One CPU kernel build as the probe drives it: the same C API LlmNative and LlmNativeI8mm each expose. */
internal interface LlmCpuLib {
    val variant: String           // "dotprod" or "i8mm"
    val library: String           // "libbenchllm.so"
    fun create(threads: Int, seed: Long, error: Array<String?>): Long
    fun setThreads(handle: Long, threads: Int, batchThreads: Int)
    /** The cores the threads may be pinned to (fastest first) and which pools are; a library that can't pin takes none. */
    fun setCores(handle: Long, cores: IntArray, pinDecode: Boolean, pinPrompt: Boolean): Int = 0
    /** The unpinned thread search; null when it fails. */
    fun tuneThreads(handle: Long, listener: LlmNative.TuneListener): IntArray? = null
    /** The thread search over `cores`, pinned and not: LlmTuneReport decodes it; null when it fails. */
    fun tuneThreadsPinned(handle: Long, cores: IntArray, listener: LlmPinTuneListener): DoubleArray? = null
    fun prefill(handle: Long, tokens: Int): Double
    fun reset(handle: Long)
    fun next(handle: Long, token: IntArray?): Double
    fun cancel(handle: Long)
    fun error(handle: Long): String
    fun lastSample(handle: Long): FloatArray
    fun destroy(handle: Long)
    /** The few-millisecond kernel self-test (BenchCore's bc_llm_kernel_selftest): [LlmProbe.SELFTEST_LOGITS] logits, or null with the reason in error[0]. */
    fun kernelSelftest(threads: Int, error: Array<String?>): FloatArray? { error[0] = "this library has no self-test"; return null }
}

/** One GPU backend as the probe drives it: LlmNative with its backend choice set (a fake in the tests). */
internal interface LlmGpuLib {
    val api: String
    fun create(error: Array<String?>): Long
    fun deviceName(): String
    fun prefill(handle: Long, tokens: Int): Double
    fun reset(handle: Long)
    fun next(handle: Long, token: IntArray?): Double
    fun cancel(handle: Long)
    fun error(handle: Long): String
    fun agreement(handle: Long): Double
    fun destroy(handle: Long)
    /** Undoes the backend choice (also when the model couldn't be built). */
    fun release()
}

/** llama.cpp's Vulkan backend as a GPU the probe can run, on any phone whose Vulkan driver it lists (an Adreno's scored backend is OpenCL). */
internal object VulkanGpuLib : LlmGpuLib {
    override val api = "Vulkan"
    /** Whether llama.cpp's Vulkan backend lists a GPU here (asked by naming the backend and reading what it would run on). */
    fun available(): Boolean = try { LlmNative.setGpuApi(api); LlmNative.gpuApi() == api } finally { LlmNative.setGpuApi("") }
    override fun create(error: Array<String?>): Long { LlmNative.setGpuApi(api); return LlmNative.create(true, 4, LlmNative.SEED, error) }
    override fun deviceName(): String = LlmNative.gpuName()
    override fun prefill(handle: Long, tokens: Int) = LlmNative.prefill(handle, tokens)
    override fun reset(handle: Long) = LlmNative.reset(handle)
    override fun next(handle: Long, token: IntArray?) = LlmNative.next(handle, token)
    override fun cancel(handle: Long) = LlmNative.cancel(handle)
    override fun error(handle: Long) = LlmNative.error(handle)
    override fun agreement(handle: Long) = LlmNative.agreement(handle)
    override fun destroy(handle: Long) = LlmNative.destroy(handle)
    override fun release() = LlmNative.setGpuApi("")
}

internal object DotprodLib : LlmCpuLib {
    override val variant = "dotprod"
    override val library = "libbenchllm.so"
    override fun create(threads: Int, seed: Long, error: Array<String?>) = LlmNative.create(false, threads, seed, error)
    override fun setThreads(handle: Long, threads: Int, batchThreads: Int) = LlmNative.setThreads(handle, threads, batchThreads)
    override fun setCores(handle: Long, cores: IntArray, pinDecode: Boolean, pinPrompt: Boolean) = LlmNative.setCores(handle, cores, pinDecode, pinPrompt)
    override fun tuneThreads(handle: Long, listener: LlmNative.TuneListener) = LlmNative.tuneThreads(handle, listener)
    override fun tuneThreadsPinned(handle: Long, cores: IntArray, listener: LlmPinTuneListener) = LlmNative.tuneThreadsPinned(handle, cores, listener)
    override fun prefill(handle: Long, tokens: Int) = LlmNative.prefill(handle, tokens)
    override fun reset(handle: Long) = LlmNative.reset(handle)
    override fun next(handle: Long, token: IntArray?) = LlmNative.next(handle, token)
    override fun cancel(handle: Long) = LlmNative.cancel(handle)
    override fun error(handle: Long) = LlmNative.error(handle)
    override fun lastSample(handle: Long) = LlmNative.lastSample(handle)
    override fun destroy(handle: Long) = LlmNative.destroy(handle)
    override fun kernelSelftest(threads: Int, error: Array<String?>) = LlmNative.kernelSelftest(threads, error)
}

internal object I8mmLib : LlmCpuLib {
    override val variant = "i8mm"
    override val library = "libbenchllm_i8mm.so"
    override fun create(threads: Int, seed: Long, error: Array<String?>) = LlmNativeI8mm.create(false, threads, seed, error)
    override fun setThreads(handle: Long, threads: Int, batchThreads: Int) = LlmNativeI8mm.setThreads(handle, threads, batchThreads)
    override fun setCores(handle: Long, cores: IntArray, pinDecode: Boolean, pinPrompt: Boolean) = LlmNativeI8mm.setCores(handle, cores, pinDecode, pinPrompt)
    override fun tuneThreads(handle: Long, listener: LlmNative.TuneListener) = LlmNativeI8mm.tuneThreads(handle, listener)
    override fun tuneThreadsPinned(handle: Long, cores: IntArray, listener: LlmPinTuneListener) = LlmNativeI8mm.tuneThreadsPinned(handle, cores, listener)
    override fun prefill(handle: Long, tokens: Int) = LlmNativeI8mm.prefill(handle, tokens)
    override fun reset(handle: Long) = LlmNativeI8mm.reset(handle)
    override fun next(handle: Long, token: IntArray?) = LlmNativeI8mm.next(handle, token)
    override fun cancel(handle: Long) = LlmNativeI8mm.cancel(handle)
    override fun error(handle: Long) = LlmNativeI8mm.error(handle)
    override fun lastSample(handle: Long) = LlmNativeI8mm.lastSample(handle)
    override fun destroy(handle: Long) = LlmNativeI8mm.destroy(handle)
    override fun kernelSelftest(threads: Int, error: Array<String?>) = LlmNativeI8mm.kernelSelftest(threads, error)
}

/** One CPU build's kernel self-test: how long it took, and how its answers compare with the first build's (null for the first). */
data class LlmKernelCheck(
    val variant: String,
    val library: String,
    val ms: Double = 0.0,
    val promptAgreement: Double? = null,      // correlation of its prompt logits with the first build's
    val nextAgreement: Double? = null,        // …and of the logits of the token after it
    val error: String? = null
)

/** The Vulkan backend on an Adreno, run once beside the scored OpenCL one: REPORT ONLY, it never feeds a score. */
data class LlmGpuProbe(
    val api: String,                      // "Vulkan"
    val device: String,                   // the device llama.cpp named for it
    val prefill: Double = 0.0,            // tokens/s reading a 512-token prompt, the second time (the first compiles its shaders)
    val decode: Double = 0.0,
    val buildS: Double = 0.0,
    val agreement: Double? = null,        // correlation with the CPU's answer to the same prompt (the library checks it itself)
    val vsApi: String? = null,            // the scored backend it is set against, and that one's rates
    val vsPrefill: Double = 0.0,
    val vsDecode: Double = 0.0,
    val seconds: Double = 0.0,
    val error: String? = null
)

/**
 * What the probe measured, and what it found about the phone. The CPU kernel build it chose ([chosen]) is the one the
 * CPU test ran in; everything else here is reported only.
 */
data class LlmProbeResult(
    val checks: List<LlmKernelCheck> = emptyList(),
    val seconds: Double = 0.0,
    val skipped: Map<String, String> = emptyMap(),   // variant → why it wasn't tried (no i8mm here, crashed before, library missing)
    val cpuFeatures: String? = null,
    val vulkan: List<String> = emptyList(),
    val opencl: List<String> = emptyList(),
    val chosen: String = "dotprod",       // the kernel build the CPU test ran in
    val why: String? = null,              // why that one
    val topology: String? = null,         // CpuTopology.line()
    val vulkanBuild: String? = null,      // which Vulkan shader families the library was built with
    val backendLog: List<String> = emptyList(),      // what ggml's GPU backends said about the device they chose (its own device line)
    val gpu: LlmGpuProbe? = null,
    val gpuSkipped: String? = null        // why Vulkan wasn't also run on an Adreno
)

object LlmProbe {
    const val REPLY = 12
    /** Logits at least this correlated with the first build's, as the GPU's have to be with the CPU's. */
    const val MIN_AGREEMENT = 0.9
    /** BC_LLM_SELFTEST_LOGITS: the prompt's logits, then the next token's (1024 each). */
    const val SELFTEST_LOGITS = 2048
    /**
     * What the i8mm build's self-test answers must reach against the dot-product build's, for the prompt and the next
     * token each. The two builds do the same arithmetic in a different order (an Apple M-series host measures 0.999998);
     * a build whose instructions are wrong gives noise. Stricter than [MIN_AGREEMENT] because no timing follows it.
     */
    const val SELFTEST_MIN_AGREEMENT = 0.99

    /** Pearson correlation of two logit samples; 0 when they don't line up or one is flat. */
    fun correlation(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var ma = 0.0; var mb = 0.0
        for (i in a.indices) { ma += a[i]; mb += b[i] }
        ma /= a.size; mb /= a.size
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; ab += x * y; aa += x * x; bb += y * y }
        return if (aa > 0 && bb > 0) ab / sqrt(aa * bb) else 0.0
    }

    /**
     * The kernel self-test in `base` (the dot-product build: the reference) and then in `candidate` (the i8mm build),
     * `threads` each — a few milliseconds apiece, nothing timed. `around(lib, true)` is told just before a library
     * runs and `around(lib, false)` just after, so the caller can keep a crash marker for exactly the library that is
     * running. The candidate is chosen by [choose]. A stopped run (`isActive` false) returns what it has.
     */
    internal fun checkKernels(base: LlmCpuLib, candidate: LlmCpuLib, threads: Int, isActive: () -> Boolean,
                              nanoTime: () -> Long = System::nanoTime, around: (LlmCpuLib, Boolean) -> Unit = { _, _ -> }): LlmProbeResult {
        val started = nanoTime()
        val checks = ArrayList<LlmKernelCheck>()
        var reference: FloatArray? = null
        for (lib in listOf(base, candidate)) {
            if (!isActive()) break
            val error = arrayOfNulls<String>(1)
            around(lib, true)
            val t0 = nanoTime()
            val logits = try { lib.kernelSelftest(threads, error) } catch (e: Throwable) {
                error[0] = e.message ?: e.javaClass.simpleName; null
            } finally { around(lib, false) }
            val ms = (nanoTime() - t0) / 1e6
            if (logits == null || logits.size != SELFTEST_LOGITS) {
                checks += LlmKernelCheck(lib.variant, lib.library, ms, error = error[0] ?: "the self-test returned no logits")
                continue
            }
            val ref = reference
            if (ref == null) reference = logits
            val half = SELFTEST_LOGITS / 2
            checks += LlmKernelCheck(lib.variant, lib.library, ms,
                ref?.let { correlation(it.copyOfRange(0, half), logits.copyOfRange(0, half)) },
                ref?.let { correlation(it.copyOfRange(half, SELFTEST_LOGITS), logits.copyOfRange(half, SELFTEST_LOGITS)) })
        }
        val choice = choose(checks)
        return LlmProbeResult(checks, (nanoTime() - started) / 1e9, chosen = choice.variant, why = choice.why)
    }

    /** The kernel build the CPU test runs in, and why. */
    data class Choice(val variant: String, val why: String)

    /**
     * The i8mm build where its self-test ran and its answers — to the prompt and to the next token — agree with the
     * dot-product build's ([SELFTEST_MIN_AGREEMENT]); in every other case the dot-product build, which is what the
     * test always ran. Nothing here asks which is faster: see the header comment.
     */
    fun choose(checks: List<LlmKernelCheck>): Choice {
        val base = checks.firstOrNull { it.variant == "dotprod" }
        val i8 = checks.firstOrNull { it.variant == "i8mm" } ?: return Choice("dotprod", "the i8mm build's self-test wasn't run")
        if (base == null) return Choice("dotprod", "no dot-product answers to compare the i8mm build's with")
        if (base.error != null) return Choice("dotprod", "the dot-product build's own self-test failed (${base.error})")
        if (i8.error != null) return Choice("dotprod", "the i8mm build's self-test failed (${i8.error})")
        val prompt = i8.promptAgreement ?: return Choice("dotprod", "the i8mm build's answers weren't compared")
        val next = i8.nextAgreement ?: return Choice("dotprod", "the i8mm build's answers weren't compared")
        if (!(prompt >= SELFTEST_MIN_AGREEMENT) || !(next >= SELFTEST_MIN_AGREEMENT))
            return Choice("dotprod", "the i8mm build's answers differ (correlation %.4f for the prompt, %.4f for the next token)".format(prompt, next))
        return Choice("i8mm", "its self-test answers agree with the dot-product build's (prompt %.5f, next token %.5f), %.0f ms".format(prompt, next, i8.ms))
    }

    // ── Vulkan on an Adreno: a comparison, never a score ─────────────────────────────────────────────────────

    const val GPU_PROMPT = 512            // BC_LLM_PROMPT: the length the CPU's reference answer exists for
    private const val GPU_WARM_TOKENS = 2

    /** Whether Vulkan is also run on an Adreno (the scored GPU is its OpenCL), and if not why not. */
    fun vulkanOnAdrenoDecision(gpuName: String, scoredApi: String, vulkanDevice: Boolean, crashed: Boolean, gpuRan: Boolean): Decision = when {
        !gpuName.contains("adreno", ignoreCase = true) || scoredApi != "OpenCL" ->
            Decision(false, "the scored GPU backend here is $scoredApi on ${gpuName.ifBlank { "no GPU" }}: nothing to compare it with")
        !gpuRan -> Decision(false, "the scored GPU run didn't finish")
        !vulkanDevice -> Decision(false, "llama.cpp's Vulkan backend lists no GPU on this phone")
        crashed -> Decision(false, "crashed the app here last time")
        else -> Decision(true)
    }

    /**
     * Builds the model on [lib]'s backend, reads the 512-token prompt twice (the first run compiles the shaders and
     * is checked against the CPU's answer by the library itself) and writes a short reply; the second prompt and the
     * reply are the rates. `vs` is the scored backend's api and rates, kept with the result for the report.
     */
    internal fun runGpu(lib: LlmGpuLib, vs: Triple<String, Double, Double>, isActive: () -> Boolean,
                        nanoTime: () -> Long = System::nanoTime, around: (Boolean) -> Unit = {},
                        onStep: (String, Float) -> Unit = { _, _ -> }): LlmGpuProbe {
        val started = nanoTime()
        fun result(device: String, build: Double, prefill: Double = 0.0, decode: Double = 0.0, agreement: Double? = null, error: String? = null) =
            LlmGpuProbe(lib.api, device, prefill, decode, build, agreement, vs.first, vs.second, vs.third, (nanoTime() - started) / 1e9, error)
        val label = "GPU · ${lib.api.uppercase()} · REPORT ONLY"
        onStep("$label · BUILDING THE MODEL", 0f)
        val error = arrayOfNulls<String>(1)
        around(true)
        val t0 = nanoTime()
        val h = try { lib.create(error) } catch (e: Throwable) { around(false); lib.release(); throw e }
        val build = (nanoTime() - t0) / 1e9
        val device = lib.deviceName()
        if (h == 0L) {
            around(false); lib.release()
            return result(device, build, error = error[0] ?: "the model could not be built")
        }
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        thread(isDaemon = true, name = "llm-gpu-probe-cancel") {
            while (!done.get()) { if (!isActive()) { lib.cancel(h); break }; Thread.sleep(100) }
        }
        try {
            fun fail(what: String) = result(device, build, error = if (!isActive()) "stopped" else lib.error(h).ifEmpty { what })
            onStep("$label · COMPILING THE SHADERS", 0.3f)
            if (lib.prefill(h, GPU_PROMPT) < 0) return fail("the first prompt failed")
            val agreement = lib.agreement(h)
            onStep("$label · READING A $GPU_PROMPT-TOKEN PROMPT", 0.55f)
            val s = lib.prefill(h, GPU_PROMPT)
            if (s < 0) return fail("the prompt failed")
            onStep("$label · WRITING A $REPLY-TOKEN REPLY", 0.8f)
            lib.reset(h)
            repeat(GPU_WARM_TOKENS) { if (lib.next(h, null) < 0) return fail("the warm-up reply failed") }
            var seconds = 0.0
            repeat(REPLY) { val x = lib.next(h, null); if (x < 0) return fail("the reply failed"); seconds += x }
            return result(device, build, GPU_PROMPT / s, REPLY / seconds, agreement)
        } finally {
            done.set(true)
            lib.destroy(h)
            around(false)
            lib.release()
        }
    }

    /** Whether the i8mm build is tried, and if not why not. */
    data class Decision(val use: Boolean, val why: String? = null)

    /**
     * The i8mm build runs only where the kernel says every core has i8mm (and the dot product and FP16 the build also
     * uses), it didn't take the app down before, and its library is in this build and loads. A CPU without i8mm never
     * loads it: `loadError` isn't even asked. `hwcap` and `hwcap2` are null when the native library isn't there.
     */
    fun i8mmDecision(hwcap: Long?, hwcap2: Long?, crashed: Boolean, loadError: () -> String?): Decision = when {
        hwcap == null || hwcap2 == null -> Decision(false, "the CPU's feature words aren't available (no 64-bit Arm library)")
        !CpuFeatures.supportsBaseKernels(hwcap) -> Decision(false, "the CPU lacks the dot product and FP16 the kernels are built with")
        !CpuFeatures.supportsI8mm(hwcap, hwcap2) -> Decision(false, "this CPU doesn't report i8mm (AT_HWCAP2 bit 13)")
        crashed -> Decision(false, "crashed the app here last time")
        else -> loadError()?.let { Decision(false, "libbenchllm_i8mm.so didn't load: $it") } ?: Decision(true)
    }

    /** Report lines: the rates, each build against the first, whether the answers agree, what wasn't tried; then what the phone offers. */
    fun lines(p: LlmProbeResult): List<String> {
        val out = ArrayList<String>()
        out += if (p.checks.isEmpty()) "LLM probe · no kernel self-test: the dot-product build is the only CPU build that applies on this phone"
            else "LLM probe · kernel self-test, %.0f ms in all (a tiny seeded model reads a 24-token prompt, then one more token; nothing is timed against the test model) · the CPU test ran in the %s build: %s".format(
                p.seconds * 1000, p.chosen, p.why ?: "")
        for (r in p.checks) {
            out += if (r.error != null) "  CPU ${r.variant} · ${r.library} · self-test failed (${r.error})"
            else "  CPU %s · %s · self-test %.0f ms%s%s".format(r.variant, r.library, r.ms,
                if (r.promptAgreement != null && r.nextAgreement != null)
                    " · answers agree: prompt %.5f, next token %.5f%s".format(r.promptAgreement, r.nextAgreement,
                        if (r.promptAgreement < SELFTEST_MIN_AGREEMENT || r.nextAgreement < SELFTEST_MIN_AGREEMENT) " — DIFFERENT" else "") else "",
                if (p.checks.size > 1 && r.variant == p.chosen) " · USED FOR THE SCORE" else "")
        }
        for ((variant, why) in p.skipped) out += "  CPU $variant · not tried: $why"
        p.topology?.let { out += it }
        p.cpuFeatures?.let { out += it }
        p.gpu?.let { g ->
            out += if (g.error != null) "  GPU ${g.api} on ${g.device} · REPORT ONLY · 0 (${g.error})"
            else "  GPU %s on %s · REPORT ONLY, the scored GPU backend is %s · prefill %.2f tok/s%s · decode %.2f tok/s%s%s · built in %.1f s · %.1f s in all".format(
                g.api, g.device, g.vsApi ?: "?", g.prefill, if (g.vsPrefill > 0) " (×%.2f of %s's)".format(g.prefill / g.vsPrefill, g.vsApi) else "",
                g.decode, if (g.vsDecode > 0) " (×%.2f)".format(g.decode / g.vsDecode) else "",
                g.agreement?.let { a -> " · answers agree %.4f%s".format(a, if (a < MIN_AGREEMENT) " — DIFFERENT" else "") } ?: "", g.buildS, g.seconds)
        }
        p.gpuSkipped?.let { out += "  GPU Vulkan on an Adreno · not tried: $it" }
        p.vulkanBuild?.let { out += it }
        out += p.vulkan
        out += p.backendLog.map { "  llama.cpp backend: $it" }
        out += p.opencl
        return out
    }
}
