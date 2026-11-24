package com.example.thermohammer.engine.ai

import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiTest
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// AI TUNING — which way of running the models is fastest on THIS phone's GPU or NPU.
//
//   LiteRT's defaults are tuned for the phones Google tests: on a GPU that isn't an Adreno the delegate picks its
//   API by itself (OpenCL, or OpenGL ES when the driver hides OpenCL from apps), and on a MediaTek NPU the ops
//   its compiler can't take (YOLOX's ArgMax and Exp) run on one CPU thread. On the CPU, XNNPACK's threads go
//   wherever the system puts them: a phone with fast and slow cores can do better with them on the fast ones, or
//   with the system told the work is latency-critical (ADPF). Nothing says which is best, so a
//   short probe before the scored runs tries each setting on two models, and the scored runs use the winner.
//   A setting only wins by being clearly faster (MIN_GAIN) without losing on either probe model (MAX_LOSS) and
//   with outputs that still agree with the CPU's (MIN_AGREEMENT) — a fast wrong answer doesn't count. The same model files run in every case: only how LiteRT executes them differs.
//
//   Everything here is plain Kotlin (no LiteRT types), so the rules are tested on the JVM; AiBench turns an
//   AiSetting into LiteRT options, StressEngine runs the probe, BenchmarkReport prints the rows.
// ─────────────────────────────────────────────────────────────────────────────

/** The GPU API LiteRT's delegate runs on: automatic is OpenCL where the phone offers it, else OpenGL ES. */
enum class GpuBackend(val label: String) { AUTOMATIC("automatic"), OPENCL("OpenCL"), OPENGL("OpenGL") }

enum class GpuStorage(val label: String) { DEFAULT("default"), BUFFER("buffers"), TEXTURE_2D("2D textures") }

/** LiteRT's own choice, or FP16 asked for: nothing in 2.3.0's sources says which of FP16 and FP32 its default is on a Mali. */
enum class GpuPrecision(val label: String) {
    DEFAULT("default"), FP16("FP16"),
    /** Test builds' precision probe only (AiSettings.GPU_PRECISION_PROBE): never chosen for a scored run. */
    FP32("FP32"), FP16_FP32_ACCUM("FP16, FP32 accumulation")
}

/** One way to run the models on an accelerator. The default is what the app has always done (and what Qualcomm phones still get). */
data class AiSetting(
    val id: String,
    val label: String,
    val gpuBackend: GpuBackend = GpuBackend.AUTOMATIC,
    val gpuStorage: GpuStorage = GpuStorage.DEFAULT,
    val gpuPrecision: GpuPrecision = GpuPrecision.DEFAULT,
    /** GPU: fully-connected and convolution inputs quantized to 8 bits so the GPU's integer dot product runs them (LiteRT: needs constant-tensor sharing). */
    val srcQuantized: Boolean = false,
    /** NPU: threads for the ops the NPU's compiler doesn't take — LiteRT's own default is one. */
    val cpuThreads: Int? = null,
    /** NPU: the ops it doesn't take go to the GPU delegate first and only then to the CPU. */
    val gpuTakesRest: Boolean = false,
    /**
     * CPU: the calling thread is limited to these cores (the fast ones) while the model is built, so the threads
     * XNNPACK starts for it inherit that mask; empty leaves the threads where the system puts them.
     */
    val cpuPin: List<Int> = emptyList(),
    /** CPU: an ADPF hint session (PerformanceHintManager, Android 12+) over the threads that run the model, told how long each inference took. */
    val adpf: Boolean = false,
    /** GPU: LiteRT caps the infinities an FP16 overflow makes at the largest finite value (test builds' precision probe only). */
    val gpuInfCapping: Boolean = false
) {
    val isDefault: Boolean get() = id == DEFAULT_ID

    companion object { const val DEFAULT_ID = "default" }
}

object AiSettings {
    val DEFAULT = AiSetting(AiSetting.DEFAULT_ID, "default")

    /**
     * Test builds only (StressEngine.aiGpuPrecisionProbe): every model on the GPU again with more precise arithmetic
     * than LiteRT's default, each answer checked against the CPU's. Every Adreno GPU has answered 5–6 of the 6 models
     * wrongly since the check arrived (2.1.5); this says whether FP16 arithmetic is why. Never scored.
     */
    val GPU_PRECISION_PROBE = listOf(
        AiSetting("fp32", "FP32", gpuPrecision = GpuPrecision.FP32),
        AiSetting("fp16acc32", "FP16 with FP32 accumulation", gpuPrecision = GpuPrecision.FP16_FP32_ACCUM),
        AiSetting("infcap", "default precision, infinities capped", gpuInfCapping = true)
    )

    /** Models the GPU settings are tried on: MobileNet is dominated by launching ~100 small kernels, Real-ESRGAN by arithmetic. */
    val GPU_MODELS = listOf(AiTest.CLASSIFY, AiTest.UPSCALE)

    /** Models the MediaTek NPU settings are tried on: YOLOX is the one with ops the NPU can't take, YAMNet one that is all NPU (a guard against regressions). */
    val NPU_MODELS = listOf(AiTest.DETECT, AiTest.SOUND)

    /** Threads the CPU test uses: the same count for what the NPU leaves to the CPU. */
    const val CPU_THREADS = 4

    /** Tried on GPUs that aren't Adreno (Mali, Immortalis, Xclipse, PowerVR …). */
    fun gpu(): List<AiSetting> = listOf(
        DEFAULT,
        AiSetting("opencl", "OpenCL backend", gpuBackend = GpuBackend.OPENCL),
        AiSetting("opengl", "OpenGL ES backend", gpuBackend = GpuBackend.OPENGL),
        AiSetting("buffers", "buffer storage", gpuStorage = GpuStorage.BUFFER),
        AiSetting("textures", "2D texture storage", gpuStorage = GpuStorage.TEXTURE_2D),
        AiSetting("fp16", "FP16 precision", gpuPrecision = GpuPrecision.FP16),
        AiSetting("int8-input", "8-bit input quantization", srcQuantized = true))

    /** Models the CPU settings are tried on: MobileNet is many small ops (placement and clocks matter), MiDaS bigger convolutions; both run many inferences in a probe's 0.8 s. */
    val CPU_MODELS = listOf(AiTest.CLASSIFY, AiTest.DEPTH)

    /**
     * Tried on every phone's CPU test (they are vendor-neutral): where the XNNPACK threads run and whether the system
     * is told the work is latency-critical. `fastCores` are the CPU's fast cores, fastest first (CpuTopology; empty when
     * unknown); `totalCores` how many cores the phone has; `adpf` whether a hint session can be made (Android 12+).
     * Pinning is offered only when there are cores to choose from — at least [CPU_THREADS] fast ones, and some that
     * aren't among them. Empty when nothing but the default is possible.
     */
    fun cpu(fastCores: List<Int>, totalCores: Int, adpf: Boolean): List<AiSetting> {
        val pin = fastCores.take(CPU_THREADS)
        val canPin = pin.size == CPU_THREADS && totalCores > CPU_THREADS
        val list = ArrayList<AiSetting>()
        list += DEFAULT
        if (canPin) list += AiSetting("pin-fast", "threads on the $CPU_THREADS fastest cores (${pin.joinToString(",")})", cpuPin = pin)
        if (adpf) list += AiSetting("adpf", "performance hint session", adpf = true)
        if (canPin && adpf) list += AiSetting("pin-fast+adpf", "fastest cores and a performance hint session", cpuPin = pin, adpf = true)
        return if (list.size > 1) list else emptyList()
    }

    /** Tried on MediaTek NeuroPilot: LiteRT 2.3.0's Kotlin API has no MediaTek options, so only what runs beside the NPU can change. */
    fun mediatekNpu(): List<AiSetting> = listOf(
        DEFAULT,
        AiSetting("cpu-threads", "$CPU_THREADS CPU threads for the rest", cpuThreads = CPU_THREADS),
        AiSetting("gpu-rest", "GPU takes the rest", cpuThreads = CPU_THREADS, gpuTakesRest = true))

    /**
     * True for a GPU the probe applies to: any known renderer (an unknown one keeps the defaults). Adreno GPUs too since
     * version 5: LiteRT's default OpenCL path on them never writes its answers into the app's output buffers (Honor 600
     * Pro, Adreno 830, 2026-11-23: the outputs read the same before any run and after twelve; OpenGL answers right), so
     * they need the probe's rule that a default with wrong answers gives way to the fastest setting that answers right.
     */
    fun gpuApplies(renderer: String): Boolean = renderer.isNotBlank()
}

// ── what a probe measured ────────────────────────────────────────────────────

/** One model on one setting: rate, how its outputs compare with the reference, and the time the copies in and out take. */
data class AiProbeResult(
    val ips: Double,
    val agreement: Double = 1.0,
    val writeMs: Double = -1.0,           // copying the input in (the timed loop doesn't: it's written once)
    val readMs: Double = -1.0,            // copying the outputs back (read once, after the loop)
    val error: String? = null,
    val note: String? = null              // CPU placement: where LiteRT's threads ended up
)

/** What a probe run hands back for one model: its rate, its outputs (to compare), the copy times, or why it failed. */
class AiSample(val ips: Double, val outputs: List<ByteArray> = emptyList(), val writeMs: Double = -1.0,
               val readMs: Double = -1.0, val error: String? = null, val note: String? = null)

data class AiTuningRow(
    val setting: AiSetting,
    val results: Map<AiTest, AiProbeResult> = emptyMap(),
    val skipped: String? = null           // why it wasn't tried: crashed before, clearly slower on the first model, out of time
)

data class AiTuning(
    val accel: AiAccel,
    val device: String,                   // what it was tried on: the GPU's name, the NPU runtime
    val models: List<AiTest>,             // the models it was tried on
    val rows: List<AiTuningRow>,
    val chosen: AiSetting,                // what the scored runs used
    val reference: String,                // what outputs were compared with: "CPU", or "default" when the CPU run failed
    val seconds: Double,                  // how long the probe took
    val backend: String? = null           // GPU: the API the default run was on, from LiteRT's log
)

/** An NPU's share of a model: ops the vendor compiler took, and what stayed on the CPU (LiteRT's own log lines). */
data class AiDelegation(
    val test: AiTest,
    val selected: Int,
    val total: Int,
    val partitions: Int,
    val notOnNpu: List<String>,
    /** False: LiteRT's log had no "Partitioned subgraph" line for this model (the counts are 0; any ops named are all that is known). */
    val logged: Boolean = true
)

// ── outputs agreeing ─────────────────────────────────────────────────────────

object AiAgreement {
    /** Pearson correlation of two outputs' bytes (as unsigned numbers); two identical constant outputs agree fully. */
    fun correlation(a: ByteArray, b: ByteArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var ma = 0.0; var mb = 0.0
        for (i in a.indices) { ma += a[i].toInt() and 0xFF; mb += b[i].toInt() and 0xFF }
        ma /= a.size; mb /= a.size
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) {
            val x = (a[i].toInt() and 0xFF) - ma; val y = (b[i].toInt() and 0xFF) - mb
            ab += x * y; aa += x * x; bb += y * y
        }
        if (aa == 0.0 && bb == 0.0) return if (a.contentEquals(b)) 1.0 else 0.0
        return if (aa > 0 && bb > 0) ab / sqrt(aa * bb) else 0.0
    }

    /** YOLOX's third output is a class index per candidate box: where nothing was found the argmax is noise, so it isn't compared. */
    private fun compared(test: AiTest, outputs: Int): List<Int> = (0 until if (test == AiTest.DETECT) min(2, outputs) else outputs).toList()

    /** The worst correlation among the outputs that mean something; 0 when they don't line up. */
    fun of(test: AiTest, a: List<ByteArray>, b: List<ByteArray>): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        return compared(test, a.size).minOf { correlation(a[it], b[it]) }
    }

    /**
     * A scored run's answer is wrong below this: it has nothing in common with the CPU's (the broken ones seen sat at
     * 0.00–0.06). Between this and AiTuner.MIN_AGREEMENT an answer is off but recognisably the same — a GPU runs these
     * 8-bit models as a floating-point approximation, and object detection drifts to about 0.7 that way — and keeps
     * its points, with the agreement shown in the report: the line can be drawn tighter once phones have reported theirs.
     */
    const val WRONG_BELOW = 0.5

    fun isWrong(agreement: Double): Boolean = agreement < WRONG_BELOW

    /**
     * Why a GPU's or an NPU's run scores 0 although it ran, as the result card and the report say it: its answer
     * isn't the CPU's ([isWrong]). Speed with a wrong answer is worth nothing.
     */
    fun wrongAnswer(ips: Double, agreement: Double): String =
        "wrong answer: its result doesn't match the CPU's (agreement %.2f, a match is 1.00), so it scores 0 · it ran at %.2f/s".format(agreement, ips)
}

// ── choosing ─────────────────────────────────────────────────────────────────

object AiTuner {
    /** A setting has to beat the default by this much (geometric mean over the probe models): smaller gaps are noise. */
    const val MIN_GAIN = 1.05
    /** …and may not be slower than this fraction of the default on any probe model. */
    const val MAX_LOSS = 0.92
    /** Outputs at least this correlated with the CPU's. */
    const val MIN_AGREEMENT = 0.9
    /** A setting this much slower than the default on the first model isn't tried on the second. */
    const val HOPELESS = 0.8

    /** A setting's outputs may be this far below the default's agreement with the reference (and always count above [MIN_AGREEMENT]). */
    private const val AGREEMENT_SLACK = 0.02

    private fun rate(row: AiTuningRow?, test: AiTest): Double = row?.results?.get(test)?.takeIf { it.error == null }?.ips ?: 0.0

    /** Rate of `row` over the default's on `test`; 0 where either didn't run. */
    fun ratio(row: AiTuningRow, base: AiTuningRow, test: AiTest): Double {
        val d = rate(base, test)
        return if (d > 0) rate(row, test) / d else 0.0
    }

    /** Why `row` can't win, or null when it can. */
    fun disqualified(row: AiTuningRow, base: AiTuningRow, models: List<AiTest>): String? {
        row.skipped?.let { return it }
        for (t in models) {
            val r = row.results[t] ?: return "not run on ${t.name.lowercase()}"
            r.error?.let { return "failed: $it" }
            val ref = base.results[t]?.agreement ?: 1.0
            if (r.agreement < min(MIN_AGREEMENT, ref - AGREEMENT_SLACK)) return "outputs differ from the reference (%.2f)".format(r.agreement)
            if (ratio(row, base, t) < MAX_LOSS) return "slower on ${t.name.lowercase()}"
        }
        return null
    }

    /** Geometric mean of the rate ratios over the probe models. */
    fun gain(row: AiTuningRow, base: AiTuningRow, models: List<AiTest>): Double {
        val ratios = models.map { ratio(row, base, it) }
        return if (ratios.any { it <= 0 }) 0.0 else exp(ratios.sumOf { ln(it) } / ratios.size)
    }

    /** True when `row` ran every model and each answer agreed with the reference's at least `floor`. */
    private fun answers(row: AiTuningRow, models: List<AiTest>, floor: Double): Boolean =
        row.skipped == null && models.all { t -> row.results[t]?.let { it.error == null && it.ips > 0 && it.agreement >= floor } == true }

    /** True when `row` ran every model and each answer was the reference's. */
    fun answersRight(row: AiTuningRow, models: List<AiTest>): Boolean = answers(row, models, MIN_AGREEMENT)

    /** True when `row` ran every model and no answer was wrong (AiAgreement.isWrong): right, or off but the same answer. */
    fun answersUsable(row: AiTuningRow, models: List<AiTest>): Boolean = answers(row, models, AiAgreement.WRONG_BELOW)

    /**
     * True when the default ran but one of its answers is wrong (AiAgreement.isWrong). A run that answers wrongly
     * scores 0, so such a default is no yardstick for speed: what is looked for then is a setting that answers right.
     * (Compared with the default's own outputs — no CPU answer to go by — a default always agrees with itself.)
     */
    fun defaultWrong(base: AiTuningRow?, models: List<AiTest>): Boolean =
        base != null && models.any { t -> base.results[t]?.let { it.error == null && AiAgreement.isWrong(it.agreement) } == true }

    /**
     * True when the default failed on a probe model: it delivered no answer at all — LiteRT's OpenCL path on Adreno
     * GPUs never writes one (AiBench's output marker), and a model may not compile. Like a wrong default it is no
     * yardstick: the fastest setting that answers right is used instead, and the default only when there is none.
     */
    fun defaultFailed(base: AiTuningRow?, models: List<AiTest>): Boolean =
        base != null && base.skipped == null && models.any { t -> base.results[t]?.error != null }

    /** The default can't be measured against: wrong ([defaultWrong]) or no answer ([defaultFailed]). */
    fun noYardstick(base: AiTuningRow?, models: List<AiTest>): Boolean = defaultWrong(base, models) || defaultFailed(base, models)

    /** Geometric mean of a row's own rates over the probe models: how settings are ranked when the default can't be. */
    private fun speed(row: AiTuningRow, models: List<AiTest>): Double {
        val rates = models.map { rate(row, it) }
        return if (rates.any { it <= 0 }) 0.0 else exp(rates.sumOf { ln(it) } / rates.size)
    }

    /**
     * The fastest setting that qualifies and gains at least [MIN_GAIN]; the default otherwise. Where the default's
     * own answers are wrong: the fastest setting whose answers are right, however its speed compares — and the default
     * when there is none (its runs then score 0, and say why).
     */
    fun choose(rows: List<AiTuningRow>, models: List<AiTest>): AiSetting {
        val base = rows.firstOrNull { it.setting.isDefault } ?: return AiSettings.DEFAULT
        if (noYardstick(base, models)) {
            val others = rows.filter { !it.setting.isDefault }
            // the fastest whose answers are right; failing that the fastest whose answers at least aren't wrong
            return (others.filter { answersRight(it, models) }.maxByOrNull { speed(it, models) }
                ?: others.filter { answersUsable(it, models) }.maxByOrNull { speed(it, models) })?.setting ?: base.setting
        }
        if (models.any { rate(base, it) <= 0 }) return base.setting      // no baseline to beat
        val best = rows.filter { !it.setting.isDefault && disqualified(it, base, models) == null }
            .maxByOrNull { gain(it, base, models) }
        return if (best != null && gain(best, base, models) >= MIN_GAIN) best.setting else base.setting
    }

    /**
     * Runs every setting on every model, default first, and chooses. `measure` does one model on one setting;
     * `reference` gives the outputs to compare with (the CPU's), or null to compare with the default's;
     * `skip` names the reason a setting mustn't be tried at all. Stops trying settings after `deadlineNs`.
     */
    fun tune(
        accel: AiAccel, device: String, settings: List<AiSetting>, models: List<AiTest>,
        reference: (AiTest) -> List<ByteArray>?, skip: (AiSetting) -> String?, isActive: () -> Boolean,
        deadlineNs: Long, nanoTime: () -> Long = System::nanoTime, measure: (AiTest, AiSetting) -> AiSample
    ): AiTuning {
        val started = nanoTime()
        val rows = ArrayList<AiTuningRow>()
        var base: AiTuningRow? = null
        val baseOutputs = HashMap<AiTest, List<ByteArray>>()
        var usedCpuReference = true
        for (setting in settings) {
            val results = LinkedHashMap<AiTest, AiProbeResult>()
            var skipped = skip(setting)
            for ((i, test) in models.withIndex()) {
                if (!isActive()) break
                if (skipped != null) break
                if (!setting.isDefault && nanoTime() > deadlineNs) { skipped = "out of time"; break }
                // (a default that answers wrongly is no yardstick: every setting gets its full turn then)
                if (!setting.isDefault && i > 0 && base != null && !noYardstick(base, models) &&
                    ratio(AiTuningRow(setting, results), base, models.first()) < HOPELESS) { skipped = "clearly slower on ${models.first().name.lowercase()}"; break }
                val s = try { measure(test, setting) } catch (e: Exception) { AiSample(0.0, error = e.message ?: e.javaClass.simpleName) }
                val ref = reference(test)
                if (ref == null) usedCpuReference = false
                if (setting.isDefault && s.error == null) baseOutputs[test] = s.outputs
                val against = ref ?: baseOutputs[test]
                val agreement = if (s.error == null && against != null) AiAgreement.of(test, s.outputs, against) else 1.0
                results[test] = AiProbeResult(s.ips, agreement, s.writeMs, s.readMs, s.error, s.note)
                if (s.error != null) break
            }
            val row = AiTuningRow(setting, results, skipped)
            if (setting.isDefault) base = row
            rows += row
        }
        val chosen = if (isActive()) choose(rows, models) else AiSettings.DEFAULT
        return AiTuning(accel, device, models, rows, chosen, if (usedCpuReference) "CPU" else "default", (nanoTime() - started) / 1e9)
    }

    /** Report lines for a tuning: one for the whole, one per setting with its rate on each model and against the default. */
    fun lines(t: AiTuning): List<String> {
        val models = t.models
        val base = t.rows.firstOrNull { it.setting.isDefault }
        val head = "AI tuning · %s · %s · scored with %s · compared with the %s's outputs · %.1f s%s".format(
            t.accel.label, t.device, t.chosen.label, t.reference, t.seconds, t.backend?.let { " · default ran on $it" } ?: "")
        return listOf(head) + t.rows.map { row ->
            val parts = models.mapNotNull { m ->
                val r = row.results[m] ?: return@mapNotNull null
                if (r.error != null) return@mapNotNull "${m.name.lowercase()} failed (${r.error.take(120)})"
                val rel = if (row.setting.isDefault || base == null) "" else ratio(row, base, m).takeIf { it > 0 }?.let { " ×%.2f".format(it) } ?: ""
                val copies = if (r.writeMs >= 0) ", ${"%.2f".format(1000.0 / r.ips)} ms/call, copy in %.2f out %.2f ms".format(r.writeMs, r.readMs) else ""
                "%s %.2f/s%s (agreement %.2f%s)".format(m.name.lowercase(), r.ips, rel, r.agreement, copies)
            }
            val failedDefault = defaultFailed(base, models)
            val wrongDefault = defaultWrong(base, models) || failedDefault
            val verdict = when {
                row.setting == t.chosen -> if (wrongDefault && row.setting.isDefault) "used: no setting's answers were the CPU's (wrong answers score 0)" else "used"
                row.skipped != null -> "not tried: ${row.skipped}"
                failedDefault && row.setting.isDefault -> "it delivered no answer: not used"
                wrongDefault && row.setting.isDefault -> "its answers differ from the CPU's: not used"
                wrongDefault -> row.results.values.firstNotNullOfOrNull { it.error }?.let { "rejected: failed: $it" }
                    ?: when {
                        answersRight(row, models) -> "answers right, but another right setting was faster"
                        answersUsable(row, models) -> "answers off but not wrong: another setting was used"
                        else -> "rejected: its answers differ from the CPU's too"
                    }
                base != null && !row.setting.isDefault -> disqualified(row, base, models)?.let { "rejected: $it" }
                    ?: if (gain(row, base, models) >= MIN_GAIN) "a faster setting was used" else "gain under ${((MIN_GAIN - 1) * 100).toInt()}%, default kept"
                else -> ""
            }
            val note = row.results.values.firstNotNullOfOrNull { it.note }?.let { " · $it" } ?: ""
            "  ${row.setting.label} · ${parts.joinToString(" · ").ifEmpty { "no result" }}${if (verdict.isEmpty()) "" else " · $verdict"}$note"
        }
    }

    /** Report lines for the NPU's delegation: ops on the NPU, partitions, and the ops that stayed on the CPU. */
    fun delegationLines(d: List<AiDelegation>): List<String> = d.map {
        if (!it.logged) {
            "AI %d %s · NPU delegation unknown: LiteRT's log had no \"Partitioned subgraph\" line for this model%s".format(it.test.number, it.test.title,
                if (it.notOnNpu.isEmpty()) "" else " · ops its plugin couldn't legalize: ${it.notOnNpu.joinToString(", ")}")
        } else
        "AI %d %s · NPU took %d of %d ops in %d partition%s%s".format(it.test.number, it.test.title, it.selected, it.total, it.partitions,
            if (it.partitions == 1) "" else "s",
            if (it.notOnNpu.isNotEmpty()) " · not on the NPU: ${it.notOnNpu.joinToString(", ")}"
            else if (it.selected < it.total) " · ${it.total - it.selected} ops stayed on the CPU (LiteRT named none)" else "")
    }
}

// ── LiteRT's log ─────────────────────────────────────────────────────────────

/** What LiteRT says about a model's compile, read from the app's own logcat lines (the Kotlin API reports none of it). */
object LiteRtLog {
    private val PARTITIONED = Regex("""Partitioned subgraph<\d+>, selected (\d+) ops, from a total of (\d+) ops\. resulted in (\d+) partitions""")
    /** The MediaTek plugin says "Unlegalized op: N" for an op it can't turn into a Neuron op and "Unsupported op: N" for one it won't take at all. */
    private val UNLEGALIZED = Regex("""(?:Unlegalized|Unsupported) op: (\d+)""")

    /**
     * LiteRT's op codes are TFLite's builtin operator numbers (schema.fbs BuiltinOperator, which LiteRtOpCode mirrors):
     * the whole table, so whichever op a plugin names comes out by name.
     */
    private val OPS: Map<Int, String> = listOf(
        "ADD", "AVERAGE_POOL_2D", "CONCATENATION", "CONV_2D", "DEPTHWISE_CONV_2D", "DEPTH_TO_SPACE", "DEQUANTIZE", "EMBEDDING_LOOKUP",
        "FLOOR", "FULLY_CONNECTED", "HASHTABLE_LOOKUP", "L2_NORMALIZATION", "L2_POOL_2D", "LOCAL_RESPONSE_NORMALIZATION", "LOGISTIC",
        "LSH_PROJECTION", "LSTM", "MAX_POOL_2D", "MUL", "RELU", "RELU_N1_TO_1", "RELU6", "RESHAPE", "RESIZE_BILINEAR", "RNN", "SOFTMAX",
        "SPACE_TO_DEPTH", "SVDF", "TANH", "CONCAT_EMBEDDINGS", "SKIP_GRAM", "CALL", "CUSTOM", "EMBEDDING_LOOKUP_SPARSE", "PAD",
        "UNIDIRECTIONAL_SEQUENCE_RNN", "GATHER", "BATCH_TO_SPACE_ND", "SPACE_TO_BATCH_ND", "TRANSPOSE", "MEAN", "SUB", "DIV", "SQUEEZE",
        "UNIDIRECTIONAL_SEQUENCE_LSTM", "STRIDED_SLICE", "BIDIRECTIONAL_SEQUENCE_RNN", "EXP", "TOPK_V2", "SPLIT", "LOG_SOFTMAX", "DELEGATE",
        "BIDIRECTIONAL_SEQUENCE_LSTM", "CAST", "PRELU", "MAXIMUM", "ARG_MAX", "MINIMUM", "LESS", "NEG", "PADV2", "GREATER", "GREATER_EQUAL",
        "LESS_EQUAL", "SELECT", "SLICE", "SIN", "TRANSPOSE_CONV", "SPARSE_TO_DENSE", "TILE", "EXPAND_DIMS", "EQUAL", "NOT_EQUAL", "LOG",
        "SUM", "SQRT", "RSQRT", "SHAPE", "POW", "ARG_MIN", "FAKE_QUANT", "REDUCE_PROD", "REDUCE_MAX", "PACK", "LOGICAL_OR", "ONE_HOT",
        "LOGICAL_AND", "LOGICAL_NOT", "UNPACK", "REDUCE_MIN", "FLOOR_DIV", "REDUCE_ANY", "SQUARE", "ZEROS_LIKE", "FILL", "FLOOR_MOD", "RANGE",
        "RESIZE_NEAREST_NEIGHBOR", "LEAKY_RELU", "SQUARED_DIFFERENCE", "MIRROR_PAD", "ABS", "SPLIT_V", "UNIQUE", "CEIL", "REVERSE_V2",
        "ADD_N", "GATHER_ND", "COS", "WHERE", "RANK", "ELU", "REVERSE_SEQUENCE", "MATRIX_DIAG", "QUANTIZE", "MATRIX_SET_DIAG", "ROUND",
        "HARD_SWISH", "IF", "WHILE", "NON_MAX_SUPPRESSION_V4", "NON_MAX_SUPPRESSION_V5", "SCATTER_ND", "SELECT_V2", "DENSIFY", "SEGMENT_SUM",
        "BATCH_MATMUL", "PLACEHOLDER_FOR_GREATER_OP_CODES", "CUMSUM", "CALL_ONCE", "BROADCAST_TO", "RFFT2D", "CONV_3D", "IMAG", "REAL",
        "COMPLEX_ABS", "HASHTABLE", "HASHTABLE_FIND", "HASHTABLE_IMPORT", "HASHTABLE_SIZE", "REDUCE_ALL", "CONV_3D_TRANSPOSE", "VAR_HANDLE",
        "READ_VARIABLE", "ASSIGN_VARIABLE", "BROADCAST_ARGS", "RANDOM_STANDARD_NORMAL", "BUCKETIZE", "RANDOM_UNIFORM", "MULTINOMIAL", "GELU",
        "DYNAMIC_UPDATE_SLICE", "RELU_0_TO_1", "UNSORTED_SEGMENT_PROD", "UNSORTED_SEGMENT_MAX", "UNSORTED_SEGMENT_SUM", "ATAN2",
        "UNSORTED_SEGMENT_MIN", "SIGN", "BITCAST", "BITWISE_XOR", "RIGHT_SHIFT").withIndex().associate { it.index to it.value }

    fun opName(code: Int): String = OPS[code] ?: "op $code"

    /**
     * The last "Partitioned subgraph" line (what the final compile kept) and every op the plugin couldn't legalize or
     * wouldn't take. A model whose log has ops but no partition line still reports them, flagged as not fully logged;
     * one with neither gives null (the caller says LiteRT logged nothing).
     */
    fun delegation(test: AiTest, lines: List<String>): AiDelegation? {
        val off = lines.mapNotNull { UNLEGALIZED.find(it)?.groupValues?.get(1)?.toIntOrNull() }.distinct().map { opName(it) }
        val m = lines.mapNotNull { PARTITIONED.find(it) }.lastOrNull() ?: return if (off.isEmpty()) null else AiDelegation(test, 0, 0, 0, off, logged = false)
        return AiDelegation(test, m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), off)
    }

    /** LiteRT's GPU delegate says so when OpenCL isn't usable and it falls back to OpenGL ES; silence means OpenCL. */
    fun gpuBackend(lines: List<String>): String =
        if (lines.any { it.contains("OpenCL not supported on this platform") }) "OpenGL ES (LiteRT found no usable OpenCL)" else "OpenCL"
}
