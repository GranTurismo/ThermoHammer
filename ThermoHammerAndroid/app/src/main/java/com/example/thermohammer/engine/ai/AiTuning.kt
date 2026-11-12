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
//   its compiler can't take (YOLOX's ArgMax and Exp) run on one CPU thread. Nothing says which is best, so a
//   short probe before the scored runs tries each setting on two models, and the scored runs use the winner.
//   A setting only wins by being clearly faster (MIN_GAIN) without losing on either probe model (MAX_LOSS) and
//   with outputs that still agree with the CPU's (MIN_AGREEMENT) — a fast wrong answer doesn't count, as with the
//   LLM's GPU. The same model files run in every case: only how LiteRT executes them differs.
//
//   Everything here is plain Kotlin (no LiteRT types), so the rules are tested on the JVM; AiBench turns an
//   AiSetting into LiteRT options, StressEngine runs the probe, BenchmarkReport prints the rows.
// ─────────────────────────────────────────────────────────────────────────────

/** The GPU API LiteRT's delegate runs on: automatic is OpenCL where the phone offers it, else OpenGL ES. */
enum class GpuBackend(val label: String) { AUTOMATIC("automatic"), OPENCL("OpenCL"), OPENGL("OpenGL") }

enum class GpuStorage(val label: String) { DEFAULT("default"), BUFFER("buffers"), TEXTURE_2D("2D textures") }

/** One way to run the models on an accelerator. The default is what the app has always done (and what Qualcomm phones still get). */
data class AiSetting(
    val id: String,
    val label: String,
    val gpuBackend: GpuBackend = GpuBackend.AUTOMATIC,
    val gpuStorage: GpuStorage = GpuStorage.DEFAULT,
    /** GPU: fully-connected and convolution inputs quantized to 8 bits so the GPU's integer dot product runs them (LiteRT: needs constant-tensor sharing). */
    val srcQuantized: Boolean = false,
    /** NPU: threads for the ops the NPU's compiler doesn't take — LiteRT's own default is one. */
    val cpuThreads: Int? = null,
    /** NPU: the ops it doesn't take go to the GPU delegate first and only then to the CPU. */
    val gpuTakesRest: Boolean = false
) {
    val isDefault: Boolean get() = id == DEFAULT_ID

    companion object { const val DEFAULT_ID = "default" }
}

object AiSettings {
    val DEFAULT = AiSetting(AiSetting.DEFAULT_ID, "default")

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
        AiSetting("int8-input", "8-bit input quantization", srcQuantized = true))

    /** Tried on MediaTek NeuroPilot: LiteRT 2.3.0's Kotlin API has no MediaTek options, so only what runs beside the NPU can change. */
    fun mediatekNpu(): List<AiSetting> = listOf(
        DEFAULT,
        AiSetting("cpu-threads", "$CPU_THREADS CPU threads for the rest", cpuThreads = CPU_THREADS),
        AiSetting("gpu-rest", "GPU takes the rest", cpuThreads = CPU_THREADS, gpuTakesRest = true))

    /** True for a GPU the probe applies to: a known renderer that isn't Qualcomm's Adreno (an unknown one keeps the defaults). */
    fun gpuApplies(renderer: String): Boolean = renderer.isNotBlank() && !renderer.contains("adreno", ignoreCase = true)
}

// ── what a probe measured ────────────────────────────────────────────────────

/** One model on one setting: rate, how its outputs compare with the reference, and the time the copies in and out take. */
data class AiProbeResult(
    val ips: Double,
    val agreement: Double = 1.0,
    val writeMs: Double = -1.0,           // copying the input in (the timed loop doesn't: it's written once)
    val readMs: Double = -1.0,            // copying the outputs back (read once, after the loop)
    val error: String? = null
)

/** What a probe run hands back for one model: its rate, its outputs (to compare), the copy times, or why it failed. */
class AiSample(val ips: Double, val outputs: List<ByteArray> = emptyList(), val writeMs: Double = -1.0,
               val readMs: Double = -1.0, val error: String? = null)

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
    val notOnNpu: List<String>
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
}

// ── choosing ─────────────────────────────────────────────────────────────────

object AiTuner {
    /** A setting has to beat the default by this much (geometric mean over the probe models): smaller gaps are noise. */
    const val MIN_GAIN = 1.05
    /** …and may not be slower than this fraction of the default on any probe model. */
    const val MAX_LOSS = 0.92
    /** Outputs at least this correlated with the CPU's — the LLM's GPU has to reach the same with its CPU. */
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

    /** The fastest setting that qualifies and gains at least [MIN_GAIN]; the default otherwise. */
    fun choose(rows: List<AiTuningRow>, models: List<AiTest>): AiSetting {
        val base = rows.firstOrNull { it.setting.isDefault } ?: return AiSettings.DEFAULT
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
                if (!setting.isDefault && i > 0 && base != null &&
                    ratio(AiTuningRow(setting, results), base, models.first()) < HOPELESS) { skipped = "clearly slower on ${models.first().name.lowercase()}"; break }
                val s = try { measure(test, setting) } catch (e: Exception) { AiSample(0.0, error = e.message ?: e.javaClass.simpleName) }
                val ref = reference(test)
                if (ref == null) usedCpuReference = false
                if (setting.isDefault && s.error == null) baseOutputs[test] = s.outputs
                val against = ref ?: baseOutputs[test]
                val agreement = if (s.error == null && against != null) AiAgreement.of(test, s.outputs, against) else 1.0
                results[test] = AiProbeResult(s.ips, agreement, s.writeMs, s.readMs, s.error)
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
            val verdict = when {
                row.setting == t.chosen -> "used"
                row.skipped != null -> "not tried: ${row.skipped}"
                base != null && !row.setting.isDefault -> disqualified(row, base, models)?.let { "rejected: $it" }
                    ?: if (gain(row, base, models) >= MIN_GAIN) "a faster setting was used" else "gain under ${((MIN_GAIN - 1) * 100).toInt()}%, default kept"
                else -> ""
            }
            "  ${row.setting.label} · ${parts.joinToString(" · ").ifEmpty { "no result" }}${if (verdict.isEmpty()) "" else " · $verdict"}"
        }
    }

    /** Report lines for the NPU's delegation: ops on the NPU, partitions, and the ops that stayed on the CPU. */
    fun delegationLines(d: List<AiDelegation>): List<String> = d.map {
        "AI %d %s · NPU took %d of %d ops in %d partition%s%s".format(it.test.number, it.test.title, it.selected, it.total, it.partitions,
            if (it.partitions == 1) "" else "s", if (it.notOnNpu.isEmpty()) "" else " · not on the NPU: ${it.notOnNpu.joinToString(", ")}")
    }

    // ── crash guard ──────────────────────────────────────────────────────────

    /**
     * The probe's crash marker: "install:ACCEL:setting" written before a non-default setting runs. A marker still
     * there at the next start means that setting took the app down: it joins the crashed set (same install only)
     * and is never tried again, while the accelerator itself still runs with its defaults.
     */
    fun crashedAfter(running: String?, crashed: Set<String>, install: Long): Set<String> {
        val parts = running?.split(':') ?: return crashed
        return if (parts.size == 3 && parts[0] == install.toString()) crashed + running else crashed
    }

    /** "ACCEL:setting" for every crashed entry of this install. */
    fun crashedKeys(crashed: Set<String>, install: Long): Set<String> =
        crashed.mapNotNull { e -> e.split(':').takeIf { it.size == 3 && it[0] == install.toString() }?.let { "${it[1]}:${it[2]}" } }.toSet()
}

// ── LiteRT's log ─────────────────────────────────────────────────────────────

/** What LiteRT says about a model's compile, read from the app's own logcat lines (the Kotlin API reports none of it). */
object LiteRtLog {
    private val PARTITIONED = Regex("""Partitioned subgraph<\d+>, selected (\d+) ops, from a total of (\d+) ops\. resulted in (\d+) partitions""")
    private val UNLEGALIZED = Regex("""Unlegalized op: (\d+)""")

    /** LiteRT's op codes are TFLite's builtin operator numbers; these are the ones the six models and MediaTek's list involve. */
    private val OPS = mapOf(0 to "ADD", 1 to "AVERAGE_POOL_2D", 2 to "CONCATENATION", 3 to "CONV_2D", 4 to "DEPTHWISE_CONV_2D",
        6 to "DEQUANTIZE", 9 to "FULLY_CONNECTED", 14 to "LOGISTIC", 17 to "MAX_POOL_2D", 18 to "MUL", 19 to "RELU", 22 to "RESHAPE",
        23 to "RESIZE_BILINEAR", 25 to "SOFTMAX", 34 to "PAD", 39 to "TRANSPOSE", 40 to "MEAN", 41 to "SUB", 42 to "DIV",
        45 to "STRIDED_SLICE", 47 to "EXP", 53 to "CAST", 54 to "PRELU", 55 to "MAXIMUM", 56 to "ARG_MAX", 57 to "MINIMUM",
        82 to "REDUCE_MAX", 97 to "RESIZE_NEAREST_NEIGHBOR", 114 to "QUANTIZE")

    fun opName(code: Int): String = OPS[code] ?: "op $code"

    /** The last "Partitioned subgraph" line (what the final compile kept) and every op the plugin couldn't legalize. */
    fun delegation(test: AiTest, lines: List<String>): AiDelegation? {
        val m = lines.mapNotNull { PARTITIONED.find(it) }.lastOrNull() ?: return null
        val off = lines.mapNotNull { UNLEGALIZED.find(it)?.groupValues?.get(1)?.toIntOrNull() }.distinct().map { opName(it) }
        return AiDelegation(test, m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), off)
    }

    /** LiteRT's GPU delegate says so when OpenCL isn't usable and it falls back to OpenGL ES; silence means OpenCL. */
    fun gpuBackend(lines: List<String>): String =
        if (lines.any { it.contains("OpenCL not supported on this platform") }) "OpenGL ES (LiteRT found no usable OpenCL)" else "OpenCL"
}
