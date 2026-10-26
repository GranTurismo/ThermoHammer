package com.example.thermohammer.engine.llm

import android.app.ActivityManager
import android.content.Context
import com.example.thermohammer.engine.BenchmarkScore
import com.example.thermohammer.engine.LlmAccel
import com.example.thermohammer.engine.LlmRun
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// LLM BENCHMARK — a 3B language model through llama.cpp, on the CPU and the GPU.
//
//   The model has Llama 3.2 3B's exact shape in 4-bit (Q4_0, 1.8 GB), its
//   weights generated from a seed when the test starts (BenchCore/llm): nothing
//   is downloaded, the app carries no weights, and every op takes the real
//   model's time. Building it is not timed. Then:
//     prefill — the 512-token prompt in one batch, again until 3 s are measured;
//     decode  — 32-token replies, token by token, until 4 s are measured.
//   The CPU first tries thread counts (phones' core layouts differ too much for
//   one rule); the GPU (OpenCL, Adreno) first runs a prompt and a reply to
//   compile and warm its kernels.
// ─────────────────────────────────────────────────────────────────────────────

enum class LlmPhase { BUILD, WARMUP, PREFILL, DECODE }

/** Live state for the stage: the phase, how far this accelerator is (0..1), tokens/s so far, the reply so far. */
data class LlmProgress(val phase: LlmPhase, val fraction: Float, val rate: Double, val detail: String,
                       val reply: List<Int> = emptyList(), val prompts: Int = 0)

class LlmBench(private val context: Context) {

    companion object {
        const val PREFILL_S = 3.0
        const val DECODE_S = 4.0
        const val REPLY = 32
        private const val WARMUP_TOKENS = 4
        private const val NEED_RAM = 5_000_000_000L     // phones sold as 6 GB report about 5.5 GB
        const val MODEL = "LLAMA 3.2 3B · Q4_0"
    }

    private fun memory(): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }

    /** Why the model can't run on this phone at all, or null. */
    fun problem(): String? {
        if (!LlmNative.loaded) return "needs a 64-bit Arm phone"
        if (!LlmNative.cpuSupported()) return "the CPU lacks the ARMv8.2 dot-product instructions the model's kernels use"
        val total = memory().totalMem
        if (total < NEED_RAM) return "needs a phone with 6 GB of RAM (this one has %.1f GB)".format(total / 1e9)
        return null
    }

    /** Why the GPU can't run it (the CPU still can), or null. */
    fun gpuProblem(): String? {
        val name = gpuName()
        return when {
            name.isEmpty() -> "no OpenCL GPU driver"
            !name.contains("Adreno", ignoreCase = true) -> "llama.cpp's GPU code is written for Adreno GPUs ($name)"
            else -> null
        }
    }

    fun gpuName(): String = if (LlmNative.loaded) LlmNative.gpuName() else ""

    /** Memory the app could take now: what's free plus what the system can reclaim at once. */
    fun freeBytes(): Long = memory().availMem

    fun largestModel(bytes: Long): Int = if (LlmNative.loaded) LlmNative.largestModel(bytes.toDouble()) else 0

    /** Prefill and decode tokens/s on [accel]; a run that fails keeps its reason. Null if the test was stopped. */
    fun run(accel: LlmAccel, threads: Int, isActive: () -> Boolean, onProgress: (LlmProgress) -> Unit): LlmRun? {
        val gpu = accel == LlmAccel.GPU
        val where = if (gpu) "GPU · OPENCL" else "CPU"
        if (gpu) LlmNative.setCacheDir(File(context.cacheDir, "llm-opencl").path)
        onProgress(LlmProgress(LlmPhase.BUILD, 0f, -1.0, "$where · BUILDING THE MODEL · 1.8 GB OF WEIGHTS FROM A SEED"))
        val error = arrayOfNulls<String>(1)
        val h = LlmNative.create(gpu, threads, LlmNative.SEED, error)
        if (h == 0L) return LlmRun(accel, 0.0, 0.0, error = error[0] ?: "the model could not be built")
        val done = AtomicBoolean(false)
        // a prompt on a slow CPU takes many seconds: a stopped run cancels it from here
        thread(isDaemon = true, name = "llm-cancel") {
            while (!done.get()) {
                if (!isActive()) { LlmNative.cancel(h); break }
                Thread.sleep(100)
            }
        }
        try {
            fun failed() = if (!isActive()) null else LlmRun(accel, 0.0, 0.0, error = LlmNative.error(h).ifEmpty { "failed" })
            var decodeThreads = threads
            var batchThreads = threads
            if (gpu) {
                onProgress(LlmProgress(LlmPhase.WARMUP, 0f, -1.0, "$where · COMPILING AND WARMING THE KERNELS"))
                if (LlmNative.prefill(h, LlmNative.PROMPT) < 0) return failed()
                LlmNative.reset(h)
                repeat(WARMUP_TOKENS) { if (LlmNative.next(h, null) < 0) return failed() }
            } else {
                onProgress(LlmProgress(LlmPhase.WARMUP, 0f, -1.0, "$where · FINDING THE FASTEST THREAD COUNT"))
                val tuned = LlmNative.tuneThreads(h) ?: return failed()
                decodeThreads = tuned[0]
                batchThreads = tuned[1]
                onProgress(LlmProgress(LlmPhase.WARMUP, 0f, -1.0, "$where · ${tuned[0]} THREADS DECODE · ${tuned[1]} PREFILL"))
            }
            val threadsNote = if (gpu) "" else " · $decodeThreads THREADS"

            var prefillS = 0.0
            var prompts = 0
            while (prompts == 0 || prefillS < PREFILL_S) {
                if (!isActive()) return null
                onProgress(LlmProgress(LlmPhase.PREFILL, (prefillS / PREFILL_S / 2).toFloat().coerceAtMost(0.5f),
                    if (prompts > 0) prompts * LlmNative.PROMPT / prefillS else -1.0,
                    "$where · PREFILL · ${LlmNative.PROMPT}-TOKEN PROMPT", prompts = prompts))
                val s = LlmNative.prefill(h, LlmNative.PROMPT)
                if (s < 0) return failed()
                prefillS += s
                prompts++
            }
            val prefill = prompts * LlmNative.PROMPT / prefillS
            onProgress(LlmProgress(LlmPhase.PREFILL, 0.5f, prefill, "$where · PREFILL · ${LlmNative.PROMPT}-TOKEN PROMPT", prompts = prompts))

            var decodeS = 0.0
            var tokens = 0
            val token = IntArray(1)
            var shown = 0L
            while (tokens == 0 || decodeS < DECODE_S) {
                if (!isActive()) return null
                LlmNative.reset(h)
                val reply = ArrayList<Int>(REPLY)
                for (i in 0 until REPLY) {
                    val s = LlmNative.next(h, token)
                    if (s < 0) return failed()
                    decodeS += s
                    tokens++
                    reply += token[0]
                    val now = System.nanoTime()
                    if (now - shown > 80_000_000L || i == REPLY - 1) {
                        shown = now
                        onProgress(LlmProgress(LlmPhase.DECODE, 0.5f + (decodeS / DECODE_S / 2).toFloat().coerceAtMost(0.5f),
                            tokens / decodeS, "$where · DECODE · TOKEN BY TOKEN$threadsNote", reply = reply.toList(), prompts = prompts))
                    }
                }
            }
            return LlmRun(accel, BenchmarkScore.round2(prefill), BenchmarkScore.round2(tokens / decodeS),
                threads = if (gpu) 0 else decodeThreads, batchThreads = if (gpu) 0 else batchThreads)
        } finally {
            done.set(true)
            LlmNative.destroy(h)
        }
    }
}
