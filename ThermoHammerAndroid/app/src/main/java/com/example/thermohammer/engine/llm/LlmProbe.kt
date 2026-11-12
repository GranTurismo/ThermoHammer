package com.example.thermohammer.engine.llm

import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// LLM PROBE — which CPU kernel build is faster on THIS phone, and what else the phone's GPU stack offers.
//
//   libbenchllm.so's CPU kernels use the Arm dot product; libbenchllm_i8mm.so's also use i8mm, which a CPU that has it
//   runs a prompt on in fewer instructions. Which is faster here isn't known until it's measured, so before the CPU
//   test each build gets the same short test — build the seeded model, read one prompt, write one reply — and the
//   CPU test runs in the i8mm build only if it is clearly faster ([choose]: at least 5% over both phases, not
//   clearly slower in either) AND its answer to the prompt agrees with the dot-product build's (its logits
//   correlate with theirs), the rule the GPU has to meet against the CPU. Otherwise the CPU test is what it always
//   was. Every rate goes into the report either way.
//
//   Where the GPU isn't a Vulkan-only one the probe also runs llama.cpp's Vulkan backend on an Adreno, REPORT ONLY:
//   the scored GPU backend stays OpenCL there (see runGpu).
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
}

/** One build's short test (rates 0 and [error] set when it couldn't run). */
data class LlmProbeRun(
    val variant: String,
    val library: String,
    val prefill: Double = 0.0,            // tokens/s reading the probe's prompt
    val decode: Double = 0.0,             // tokens/s writing the reply
    val buildS: Double = 0.0,             // seconds to build the seeded model (not part of either rate)
    val agreement: Double? = null,        // correlation of its prompt's logits with the first build's (null for the first)
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
    val runs: List<LlmProbeRun> = emptyList(),
    val promptTokens: Int = 0,
    val replyTokens: Int = 0,
    val threads: Int = 0,                 // decode threads the builds were compared on
    val batchThreads: Int = 0,            // prompt threads
    val seconds: Double = 0.0,
    val skipped: Map<String, String> = emptyMap(),   // variant → why it wasn't tried (no i8mm here, crashed before, library missing)
    val cpuFeatures: String? = null,
    val vulkan: List<String> = emptyList(),
    val opencl: List<String> = emptyList(),
    val chosen: String = "dotprod",       // the kernel build the CPU test ran in
    val why: String? = null,              // why that one
    val pinnedCores: List<Int> = emptyList(),        // the cores both builds were pinned to for the comparison (empty: unpinned)
    val topology: String? = null,         // CpuTopology.line()
    val vulkanBuild: String? = null,      // which Vulkan shader families the library was built with
    val backendLog: List<String> = emptyList(),      // what ggml's GPU backends said about the device they chose (its own device line)
    val gpu: LlmGpuProbe? = null,
    val gpuSkipped: String? = null        // why Vulkan wasn't also run on an Adreno
)

object LlmProbe {
    const val WARMUP_TOKENS = 8           // the first prompt builds the compute graph: it sizes nothing
    const val SIZING_TOKENS = 16          // the second one sizes the probe's prompt
    const val REPLY = 12
    const val TARGET_PREFILL_S = 2.0      // a prompt this long per build; with its model build and reply about 7 s each on a flagship
    /** The probe's own limit: a build isn't started if it would likely end past this (the first build's time is the guess). */
    const val BUDGET_S = 20.0
    const val MIN_PROMPT = 32
    const val MAX_PROMPT = 512            // BC_LLM_PROMPT
    /** Logits at least this correlated with the first build's, as the GPU's have to be with the CPU's. */
    const val MIN_AGREEMENT = 0.9
    private const val SEED = 3L           // BC_LLM_SEED: the same weights in every build

    /** The prompt that takes about [TARGET_PREFILL_S] at `tokensPerS`, in multiples of 16 within the model's limits. */
    fun promptFor(tokensPerS: Double): Int {
        if (!(tokensPerS > 0)) return MIN_PROMPT
        return min(MAX_PROMPT, max(MIN_PROMPT, (tokensPerS * TARGET_PREFILL_S / 16).roundToInt() * 16))
    }

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
     * Runs every library in `libs` the same way, in order, and returns their rates. `threads` and `batchThreads` are
     * what the scored CPU run settled on, so the builds differ in kernels only. The first library sizes the prompt
     * and its logits are what the others' are compared with. A library that fails is a row with its reason; a stopped
     * run (`isActive` false) returns what it has. `onStep(label, fraction)` keeps the stage moving; `around(lib, true)`
     * is told just before a library builds its model and `around(lib, false)` just after it is freed, so the caller
     * can keep a crash marker for exactly the library that is running. A library that wouldn't finish inside
     * `budgetS` (judged by how long the first took) isn't started: the probe adds to the run no more than that.
     * With `cores` (the fastest cores, fastest first) every build's threads are pinned to the first n of them, the same
     * way in each, so the comparison doesn't depend on where the system happens to put the threads.
     */
    internal fun run(libs: List<LlmCpuLib>, threads: Int, batchThreads: Int, isActive: () -> Boolean,
                     nanoTime: () -> Long = System::nanoTime, around: (LlmCpuLib, Boolean) -> Unit = { _, _ -> },
                     budgetS: Double = BUDGET_S, cores: List<Int> = emptyList(),
                     onStep: (String, Float) -> Unit = { _, _ -> }): LlmProbeResult {
        val started = nanoTime()
        val runs = ArrayList<LlmProbeRun>()
        val skipped = LinkedHashMap<String, String>()
        var prompt = 0
        var reference: FloatArray? = null
        var firstS = 0.0
        for ((i, lib) in libs.withIndex()) {
            if (!isActive()) break
            val elapsed = (nanoTime() - started) / 1e9
            if (i > 0 && elapsed + firstS > budgetS) {
                skipped[lib.variant] = "out of time: %.0f s in, and the first build took %.0f s of %.0f".format(elapsed, firstS, budgetS)
                continue
            }
            val label = "${lib.variant.uppercase()} KERNELS · CHECKING WHICH IS FASTER"
            onStep("$label · BUILDING THE MODEL", i / libs.size.toFloat())
            val error = arrayOfNulls<String>(1)
            around(lib, true)
            val t0 = nanoTime()
            val h = lib.create(threads, SEED, error)
            val build = (nanoTime() - t0) / 1e9
            if (h == 0L) {
                around(lib, false)
                runs += LlmProbeRun(lib.variant, lib.library, buildS = build, error = error[0] ?: "the model could not be built")
                continue
            }
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            thread(isDaemon = true, name = "llm-probe-cancel") {      // a stopped run cancels a prompt from here, as LlmBench's does
                while (!done.get()) { if (!isActive()) { lib.cancel(h); break }; Thread.sleep(100) }
            }
            try {
                fun fail(what: String) = runs.add(LlmProbeRun(lib.variant, lib.library, buildS = build,
                    error = if (!isActive()) "stopped" else lib.error(h).ifEmpty { what }))
                if (cores.isNotEmpty()) lib.setCores(h, cores.toIntArray(), true, true)
                lib.setThreads(h, threads, batchThreads)
                onStep("$label · WARMING UP", (i + 0.2f) / libs.size)
                if (lib.prefill(h, WARMUP_TOKENS) < 0) { fail("the warm-up prompt failed"); continue }
                val sizing = lib.prefill(h, SIZING_TOKENS)
                if (sizing < 0) { fail("the warm-up prompt failed"); continue }
                if (prompt == 0) prompt = promptFor(SIZING_TOKENS / sizing)
                onStep("$label · READING A $prompt-TOKEN PROMPT", (i + 0.4f) / libs.size)
                val s = lib.prefill(h, prompt)
                if (s < 0) { fail("the prompt failed"); continue }
                val sample = lib.lastSample(h)
                val agreement = reference?.let { correlation(it, sample) }
                if (reference == null) reference = sample
                onStep("$label · WRITING A $REPLY-TOKEN REPLY", (i + 0.7f) / libs.size)
                lib.reset(h)
                repeat(2) { lib.next(h, null) }                      // the first tokens warm the decode path
                var seconds = 0.0
                var failed = false
                repeat(REPLY) { if (!failed) { val x = lib.next(h, null); if (x < 0) failed = true else seconds += x } }
                if (failed) { fail("the reply failed"); continue }
                runs += LlmProbeRun(lib.variant, lib.library, prompt / s, REPLY / seconds, build, agreement)
            } finally {
                done.set(true)
                lib.destroy(h)
                around(lib, false)
                if (i == 0) firstS = (nanoTime() - started) / 1e9
            }
        }
        return LlmProbeResult(runs, prompt, REPLY, threads, batchThreads, (nanoTime() - started) / 1e9, skipped,
            pinnedCores = cores.take(threads.coerceAtLeast(batchThreads)))
    }

    /** At least this much faster (geometric mean of the prompt and reply rates) for the i8mm build to be used. */
    const val MIN_GAIN = 1.05
    /** …and not slower than this fraction of the dot-product build in either phase. */
    const val MAX_LOSS = 0.95

    /** The kernel build the CPU test runs in, and why. */
    data class Choice(val variant: String, val why: String)

    /**
     * The i8mm build only where it measured clearly faster than the dot-product build, in both phases together
     * ([MIN_GAIN]) and in neither clearly slower ([MAX_LOSS]), and its prompt answer agrees with the dot-product
     * build's ([MIN_AGREEMENT]); in every other case the dot-product build, which is what the test always ran.
     */
    fun choose(runs: List<LlmProbeRun>): Choice {
        val base = runs.firstOrNull { it.variant == "dotprod" }
        val i8 = runs.firstOrNull { it.variant == "i8mm" } ?: return Choice("dotprod", "the i8mm build wasn't measured")
        if (base == null || base.error != null || base.prefill <= 0 || base.decode <= 0)
            return Choice("dotprod", "no dot-product rates to compare the i8mm build with")
        if (i8.error != null) return Choice("dotprod", "the i8mm build failed (${i8.error})")
        val agreement = i8.agreement ?: return Choice("dotprod", "the i8mm build's answer wasn't compared")
        if (agreement < MIN_AGREEMENT) return Choice("dotprod", "the i8mm build's answer differs (correlation %.2f)".format(agreement))
        val prefill = i8.prefill / base.prefill
        val decode = i8.decode / base.decode
        if (prefill < MAX_LOSS || decode < MAX_LOSS)
            return Choice("dotprod", "the i8mm build is slower at %s (×%.2f)".format(if (prefill < decode) "reading the prompt" else "writing the reply", min(prefill, decode)))
        val gain = sqrt(prefill * decode)
        return if (gain >= MIN_GAIN) Choice("i8mm", "×%.2f over the dot-product build (prompt ×%.2f, reply ×%.2f)".format(gain, prefill, decode))
        else Choice("dotprod", "the i8mm build gains only ×%.2f (under ×%.2f)".format(gain, MIN_GAIN))
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
        val first = p.runs.firstOrNull { it.error == null }
        out += if (p.runs.isEmpty()) "LLM probe · not run: the dot-product build is the only CPU build that applies on this phone"
            else "LLM probe · %.1f s · CPU kernels, a %d-token prompt on %d threads and a %d-token reply on %d%s · the CPU test ran in the %s build: %s".format(
                p.seconds, p.promptTokens, p.batchThreads, p.replyTokens, p.threads,
                if (p.pinnedCores.isEmpty()) ", unpinned" else ", pinned to cores ${p.pinnedCores.joinToString(",")}", p.chosen, p.why ?: "")
        for (r in p.runs) {
            out += if (r.error != null) "  CPU ${r.variant} · ${r.library} · 0 (${r.error})"
            else "  CPU %s · %s · prefill %.2f tok/s%s · decode %.2f tok/s%s%s · built in %.1f s%s".format(r.variant, r.library, r.prefill,
                if (first != null && r !== first && first.prefill > 0) " (×%.2f)".format(r.prefill / first.prefill) else "", r.decode,
                if (first != null && r !== first && first.decode > 0) " (×%.2f)".format(r.decode / first.decode) else "",
                r.agreement?.let { a -> " · answers agree %.4f%s".format(a, if (a < MIN_AGREEMENT) " — DIFFERENT" else "") } ?: "", r.buildS,
                if (p.runs.size > 1 && r.variant == p.chosen) " · USED FOR THE SCORE" else "")
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
