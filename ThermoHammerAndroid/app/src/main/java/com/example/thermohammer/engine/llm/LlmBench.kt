package com.example.thermohammer.engine.llm

import android.app.ActivityManager
import android.content.Context
import com.example.thermohammer.engine.BenchmarkScore
import com.example.thermohammer.engine.LlmAccel
import com.example.thermohammer.engine.LlmRun
import com.example.thermohammer.engine.ThreadTasks
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
//   one rule); the GPU (OpenCL on Adreno, Vulkan on the others) first runs a
//   prompt and a reply to compile and warm its kernels, and its answer to the
//   prompt has to agree with the CPU's (BenchCore/llm).
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
        // version 5's model is Gemma 3 1B (0.6 GB of weights): a phone sold as 4 GB (about 3.6 GB reported) has room
        private const val NEED_RAM = 3_000_000_000L
        const val MODEL = "GEMMA 3 1B · Q4_0"
    }

    private fun memory(): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }

    /** Why the model can't run on this phone at all, or null. */
    fun problem(): String? {
        if (!LlmNative.loaded) return "needs a 64-bit Arm phone"
        if (!LlmNative.cpuSupported()) return "the CPU lacks the ARMv8.2 dot-product instructions the model's kernels use"
        val total = memory().totalMem
        if (total < NEED_RAM) return "needs a phone with 4 GB of RAM (this one has %.1f GB)".format(total / 1e9)
        return null
    }

    /** Why the GPU can't run it (the CPU still can), or null. */
    fun gpuProblem(): String? = if (gpuName().isEmpty()) "no GPU driver llama.cpp can use (OpenCL or Vulkan)" else null

    fun gpuName(): String = if (LlmNative.loaded) LlmNative.gpuName() else ""

    fun gpuApi(): String = if (LlmNative.loaded) LlmNative.gpuApi() else ""

    /** Memory the app could take now: what's free plus what the system can reclaim at once. */
    fun freeBytes(): Long = memory().availMem

    fun largestModel(bytes: Long): Int = if (LlmNative.loaded) LlmNative.largestModel(bytes.toDouble()) else 0

    /**
     * Prefill and decode tokens/s on [accel]; a run that fails keeps its reason. Null if the test was stopped.
     * The CPU run happens in [lib] (the dot-product build unless the probe found the i8mm one clearly faster). With
     * [cores] (the fast cores, fastest first) the CPU's thread search also tries pinning the threads to them and
     * keeps that only if it is clearly faster; without, it is the search the test always had.
     */
    internal fun run(accel: LlmAccel, threads: Int, isActive: () -> Boolean, lib: LlmCpuLib = DotprodLib,
                     cores: List<Int> = emptyList(), model: Int = LlmNative.MODEL_LLAMA32_3B,
                     onProgress: (LlmProgress) -> Unit): LlmRun? {
        val gpu = accel == LlmAccel.GPU
        val api: LlmCpuLib = if (gpu) DotprodLib else lib      // the C API is the same either way; only the library differs
        val where = if (gpu) "GPU · ${gpuApi().uppercase()}" else "CPU"
        if (gpu) LlmNative.setCacheDir(File(context.cacheDir, "llm-opencl").path)
        // another model's shape (test builds' version-5 probe) in both libraries: the CPU's reference for the GPU's
        // answer is handed to the dot-product one; put back below, whatever happens
        val otherModel = model != LlmNative.MODEL_LLAMA32_3B
        if (otherModel) { LlmNative.setModel(model); if (lib === I8mmLib) LlmNativeI8mm.setModel(model) }
        try {
            return runModel(accel, threads, isActive, lib, cores, gpu, api, where, if (otherModel) "0.6 GB" else "1.8 GB", onProgress)
        } finally {
            if (otherModel) { LlmNative.setModel(LlmNative.MODEL_LLAMA32_3B); if (lib === I8mmLib) LlmNativeI8mm.setModel(LlmNative.MODEL_LLAMA32_3B) }
        }
    }

    private fun runModel(accel: LlmAccel, threads: Int, isActive: () -> Boolean, lib: LlmCpuLib, cores: List<Int>,
                         gpu: Boolean, api: LlmCpuLib, where: String, size: String, onProgress: (LlmProgress) -> Unit): LlmRun? {
        onProgress(LlmProgress(LlmPhase.BUILD, 0f, -1.0, "$where · BUILDING THE MODEL · $size OF WEIGHTS FROM A SEED"))
        val error = arrayOfNulls<String>(1)
        val h = if (gpu) LlmNative.create(true, threads, LlmNative.SEED, error) else lib.create(threads, LlmNative.SEED, error)
        if (h == 0L) return LlmRun(accel, 0.0, 0.0, error = error[0] ?: "the model could not be built", kernels = lib.variant)
        val done = AtomicBoolean(false)
        // a prompt on a slow CPU takes many seconds: a stopped run cancels it from here
        thread(isDaemon = true, name = "llm-cancel") {
            while (!done.get()) {
                if (!isActive()) { api.cancel(h); break }
                Thread.sleep(100)
            }
        }
        try {
            fun failed() = if (!isActive()) null else LlmRun(accel, 0.0, 0.0, error = api.error(h).ifEmpty { "failed" }, kernels = lib.variant)
            var decodeThreads = threads
            var batchThreads = threads
            var tune: LlmTuneReport? = null
            if (gpu) {
                onProgress(LlmProgress(LlmPhase.WARMUP, 0f, -1.0, "$where · COMPILING AND WARMING THE KERNELS"))
                if (LlmNative.prefill(h, LlmNative.PROMPT) < 0) return failed()
                LlmNative.reset(h)
                repeat(WARMUP_TOKENS) { if (LlmNative.next(h, null) < 0) return failed() }
            } else {
                onProgress(LlmProgress(LlmPhase.WARMUP, 0f, -1.0, "$where · FINDING THE FASTEST THREAD COUNT"))
                if (cores.size >= 2) {
                    val raw = lib.tuneThreadsPinned(h, cores.toIntArray()) { phase, pinned, n, trial, total ->
                        val text = if (trial == 0) "FINDING THE FASTEST THREAD COUNT · WARMING UP"
                            else "TUNING ${if (phase == 0) "DECODE" else "PREFILL"} · TRYING $n OF $total THREADS${if (pinned != 0) " · PINNED TO THE FAST CORES" else ""}"
                        onProgress(LlmProgress(LlmPhase.WARMUP, 0f, -1.0, "$where · $text"))
                    }
                    val report = LlmTuneReport.parse(raw, cores) ?: return failed()
                    decodeThreads = report.decodeThreads
                    batchThreads = report.promptThreads
                    tune = report
                } else {
                    val tuned = lib.tuneThreads(h) { phase, n, trial, total ->
                        val text = if (trial == 0) "FINDING THE FASTEST THREAD COUNT · WARMING UP"
                            else "TUNING ${if (phase == 0) "DECODE" else "PREFILL"} · TRYING $n OF $total THREADS"
                        onProgress(LlmProgress(LlmPhase.WARMUP, 0f, -1.0, "$where · $text"))
                    } ?: return failed()
                    decodeThreads = tuned[0]
                    batchThreads = tuned[1]
                }
                onProgress(LlmProgress(LlmPhase.WARMUP, 0f, -1.0, "$where · $decodeThreads THREADS DECODE · $batchThreads PREFILL"))
            }
            val threadsNote = if (gpu) "" else " · $decodeThreads THREADS${if (tune?.decodePinned == true) " PINNED" else ""}"

            var prefillS = 0.0
            var prompts = 0
            while (prompts == 0 || prefillS < PREFILL_S) {
                if (!isActive()) return null
                onProgress(LlmProgress(LlmPhase.PREFILL, (prefillS / PREFILL_S / 2).toFloat().coerceAtMost(0.5f),
                    if (prompts > 0) prompts * LlmNative.PROMPT / prefillS else -1.0,
                    "$where · PREFILL · ${LlmNative.PROMPT}-TOKEN PROMPT", prompts = prompts))
                val s = api.prefill(h, LlmNative.PROMPT)
                if (s < 0) return failed()
                // the GPU's answer is checked against the CPU's, which lives in the library that ran it: when that was
                // the i8mm one, its answer is handed over (the dot-product library's check would otherwise find none)
                if (!gpu && prompts == 0 && lib !== DotprodLib) runCatching { LlmNative.setReference(LlmNative.SEED, LlmNative.PROMPT, lib.lastSample(h)) }
                // what the kernel says about the process's threads, once the first prompt has run (the search's retired
                // pools are freed by then): the check that the pinning took effect, one thread per core
                if (prompts == 0 && tune?.pinned == true) tune = tune.copy(singleCoreThreads =
                    ThreadTasks.singleCore(ThreadTasks.masks(ThreadTasks.ids())).filterValues { it in cores })
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
                api.reset(h)
                val reply = ArrayList<Int>(REPLY)
                for (i in 0 until REPLY) {
                    val s = api.next(h, token)
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
                threads = if (gpu) 0 else decodeThreads, batchThreads = if (gpu) 0 else batchThreads,
                kernels = if (gpu) "" else lib.variant, tune = tune)
        } finally {
            done.set(true)
            api.destroy(h)         // also gives the calling thread its own cores back when the threads were pinned
        }
    }
}
