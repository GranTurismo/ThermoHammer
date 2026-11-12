package com.example.thermohammer.engine.llm

import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// LLM PROBE — the CPU kernel builds side by side, short, and NOT SCORED.
//
//   libbenchllm.so's CPU kernels use the Arm dot product; libbenchllm_i8mm.so's also use i8mm, which a CPU that has it
//   runs a prompt on in fewer instructions. Which is faster on this phone isn't known until it's measured, so after
//   the scored runs each build gets the same short test — build the seeded model, read one prompt, write one reply —
//   and its rates go into the report. A candidate's result never feeds the score in this stage: the score is what it
//   always was, from the dot-product library. The i8mm build also has to answer the prompt like the dot-product one
//   (its logits correlate with theirs), the rule the GPU has to meet against the CPU.
//
//   Plain Kotlin over [LlmCpuLib], so the protocol and its numbers are tested on the JVM with fake libraries.
// ─────────────────────────────────────────────────────────────────────────────

/** One CPU kernel build as the probe drives it: the same C API LlmNative and LlmNativeI8mm each expose. */
internal interface LlmCpuLib {
    val variant: String           // "dotprod" or "i8mm"
    val library: String           // "libbenchllm.so"
    fun create(threads: Int, seed: Long, error: Array<String?>): Long
    fun setThreads(handle: Long, threads: Int, batchThreads: Int)
    fun prefill(handle: Long, tokens: Int): Double
    fun reset(handle: Long)
    fun next(handle: Long, token: IntArray?): Double
    fun cancel(handle: Long)
    fun error(handle: Long): String
    fun lastSample(handle: Long): FloatArray
    fun destroy(handle: Long)
}

internal object DotprodLib : LlmCpuLib {
    override val variant = "dotprod"
    override val library = "libbenchllm.so"
    override fun create(threads: Int, seed: Long, error: Array<String?>) = LlmNative.create(false, threads, seed, error)
    override fun setThreads(handle: Long, threads: Int, batchThreads: Int) = LlmNative.setThreads(handle, threads, batchThreads)
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

/** What the probe measured, and what it found about the phone: all reported, none scored. */
data class LlmProbeResult(
    val runs: List<LlmProbeRun> = emptyList(),
    val promptTokens: Int = 0,
    val replyTokens: Int = 0,
    val threads: Int = 0,                 // decode threads, from the scored CPU run
    val batchThreads: Int = 0,            // prompt threads
    val seconds: Double = 0.0,
    val skipped: Map<String, String> = emptyMap(),   // variant → why it wasn't tried (no i8mm here, crashed before, library missing)
    val cpuFeatures: String? = null,
    val vulkan: List<String> = emptyList(),
    val opencl: List<String> = emptyList()
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
     */
    internal fun run(libs: List<LlmCpuLib>, threads: Int, batchThreads: Int, isActive: () -> Boolean,
                     nanoTime: () -> Long = System::nanoTime, around: (LlmCpuLib, Boolean) -> Unit = { _, _ -> },
                     budgetS: Double = BUDGET_S, onStep: (String, Float) -> Unit = { _, _ -> }): LlmProbeResult {
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
            val label = "${lib.variant.uppercase()} KERNELS · NOT SCORED"
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
        return LlmProbeResult(runs, prompt, REPLY, threads, batchThreads, (nanoTime() - started) / 1e9, skipped)
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
        out += "LLM probe · NOT SCORED · %.1f s · CPU kernels, a %d-token prompt on %d threads and a %d-token reply on %d".format(
            p.seconds, p.promptTokens, p.batchThreads, p.replyTokens, p.threads)
        for (r in p.runs) {
            out += if (r.error != null) "  CPU ${r.variant} · ${r.library} · 0 (${r.error})"
            else "  CPU %s · %s · prefill %.2f tok/s%s · decode %.2f tok/s%s%s · built in %.1f s".format(r.variant, r.library, r.prefill,
                if (first != null && r !== first && first.prefill > 0) " (×%.2f)".format(r.prefill / first.prefill) else "", r.decode,
                if (first != null && r !== first && first.decode > 0) " (×%.2f)".format(r.decode / first.decode) else "",
                r.agreement?.let { a -> " · answers agree %.4f%s".format(a, if (a < MIN_AGREEMENT) " — DIFFERENT" else "") } ?: "", r.buildS)
        }
        for ((variant, why) in p.skipped) out += "  CPU $variant · not tried: $why"
        p.cpuFeatures?.let { out += it }
        out += p.vulkan
        out += p.opencl
        return out
    }
}
