package com.example.thermohammer.engine.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiTest
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.NpuCompatibilityChecker
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────
// AI BENCHMARK — six open models through LiteRT on the CPU, the GPU and the NPU.
//
//   Every model is Qualcomm AI Hub's 8-bit (w8a8) build, the same file on all
//   three accelerators: the CPU runs it in INT8 (XNNPACK), the GPU in FP16, the
//   NPU in INT8. A run compiles the model (not timed), warms up, then counts
//   inferences for a fixed time. The NPU is used only where LiteRT finds a
//   vendor runtime: Qualcomm (QNN — bundled in bench builds only), MediaTek or
//   Google Tensor; elsewhere it scores 0.
//   Inputs are the benchmark's own renders (assets/media) and a synthesized
//   piano phrase (assets/ai/yamnet_input.bin); the first run's outputs become
//   the picture on the stage.
// ─────────────────────────────────────────────────────────────────────────────

/** A detection: box in the shown picture's pixels. */
data class AiBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val label: String, val score: Float)

/** What the stage shows for a model: what it saw and what it made of it. */
data class AiVisual(
    val test: AiTest,
    val picture: Bitmap,                         // the input as a picture (spectrogram for sound)
    val result: Bitmap? = null,                  // segmentation overlay, depth map or upscaled crop
    val boxes: List<AiBox> = emptyList(),
    val labels: List<Pair<String, Float>> = emptyList()   // top classes, 0..1
)

class AiBench(private val context: Context) {

    companion object {
        private const val WARMUP_RUNS = 3
        private const val CPU_THREADS = 4
    }

    /** LiteRT's environment, pointed at the bundled vendor libraries (extracted only in bench builds). */
    private val env: Environment? by lazy {
        val dir = context.applicationInfo.nativeLibraryDir
        runCatching {
            Environment.create(mapOf(Environment.Option.CompilerPluginLibraryDir to dir,
                Environment.Option.DispatchLibraryDir to dir))
        }.getOrNull()
    }

    /** Accelerators LiteRT can use on this phone. */
    fun available(): Set<AiAccel> {
        val got = runCatching { env?.getAvailableAccelerators() }.getOrNull().orEmpty()
        return buildSet {
            add(AiAccel.CPU)
            if (Accelerator.GPU in got) add(AiAccel.GPU)
            if (Accelerator.NPU in got) add(AiAccel.NPU)
        }
    }

    /** The NPU runtime LiteRT would use on this phone's chip, or null when it knows none. */
    fun npuRuntime(): String? = when {
        NpuCompatibilityChecker.Qualcomm.isDeviceSupported() -> "Qualcomm QNN"
        NpuCompatibilityChecker.Mediatek.isDeviceSupported() -> "MediaTek NeuroPilot"
        NpuCompatibilityChecker.GoogleTensor.isDeviceSupported() -> "Google Tensor"
        else -> null
    }

    /**
     * Why the NPU can't run here, or null when it can. LiteRT quietly runs an "NPU" model on the CPU when
     * no vendor dispatch library loads, so the libraries have to be on disk: the dispatch library for the
     * chip's vendor and, for Qualcomm, the QNN runtime (both extracted only in bench builds).
     */
    fun npuProblem(): String? {
        val runtime = npuRuntime() ?: return "no NPU runtime for this chip"
        val dir = java.io.File(context.applicationInfo.nativeLibraryDir)
        val vendor = when (runtime) { "Qualcomm QNN" -> "Qualcomm"; "MediaTek NeuroPilot" -> "MediaTek"; else -> "GoogleTensor" }
        if (!java.io.File(dir, "libLiteRtDispatch_$vendor.so").exists()) return "$runtime dispatch library not in this build"
        if (vendor == "Qualcomm" && !java.io.File(dir, "libQnnHtp.so").exists()) return "Qualcomm's QNN runtime is only in the test build"
        if (AiAccel.NPU !in available()) return "LiteRT found no NPU accelerator"
        return null
    }

    /** Inferences per second of `test` on `accel`, and the outputs of its last inference. */
    class Measured(val ips: Double, val outputs: List<ByteArray>)

    fun run(test: AiTest, accel: AiAccel, input: ByteArray, seconds: Double,
            isActive: () -> Boolean, onProgress: (fraction: Float, ips: Double) -> Unit): Measured {
        val options = CompiledModel.Options(when (accel) {
            AiAccel.CPU -> Accelerator.CPU; AiAccel.GPU -> Accelerator.GPU; AiAccel.NPU -> Accelerator.NPU
        })
        when (accel) {
            AiAccel.CPU -> options.cpuOptions = CompiledModel.CpuOptions(CPU_THREADS, null, null)
            AiAccel.NPU -> options.qualcommOptions = CompiledModel.QualcommOptions(
                htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST)
            AiAccel.GPU -> {}
        }
        val e = env
        val compiled = if (e != null) CompiledModel.create(context.assets, test.asset, options, e)
            else CompiledModel.create(context.assets, test.asset, options)
        compiled.use { model ->
            val inputs = model.createInputBuffers()
            val outputs = model.createOutputBuffers()
            try {
                inputs[0].writeInt8(input)
                repeat(WARMUP_RUNS) { model.run(inputs, outputs) }
                var n = 0
                val t0 = System.nanoTime()
                val limit = (seconds * 1e9).toLong()
                var lastReport = t0
                while (System.nanoTime() - t0 < limit && isActive()) {
                    model.run(inputs, outputs)
                    n++
                    val now = System.nanoTime()
                    if (now - lastReport > 150_000_000L) {
                        lastReport = now
                        onProgress(((now - t0).toFloat() / limit).coerceAtMost(1f), n * 1e9 / (now - t0))
                    }
                }
                val ips = n * 1e9 / (System.nanoTime() - t0)
                return Measured(ips, outputs.map { it.readInt8() })
            } finally {
                inputs.forEach { it.close() }
                outputs.forEach { it.close() }
            }
        }
    }
}

/** The models' inputs, prepared once, and their outputs turned into pictures. */
class AiInputs(private val context: Context) {

    private val lake: Bitmap = photo("media/photo_lake.webp", 2)
    private val city: Bitmap = photo("media/photo_city.webp", 2)            // 2000×1500

    private fun photo(asset: String, sample: Int): Bitmap = context.assets.open(asset).use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })!!
    }

    private fun labels(name: String): List<String> =
        context.assets.open("ai/${name}_labels.txt").bufferedReader().readLines().map { it.trim() }

    /** The model's input bytes (NHWC RGB, 8-bit) and the picture shown while it runs. */
    fun input(test: AiTest): Pair<ByteArray, Bitmap> = when (test) {
        AiTest.CLASSIFY -> square(lake).let { rgb(scale(it, 224, 224)) to it }
        AiTest.DETECT -> {
            // letterboxed into 640×640 (grey bars), as YOLOX was trained
            val shown = scale(city, 640, 480)
            val canvas = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF727272.toInt()) }
            android.graphics.Canvas(canvas).drawBitmap(shown, 0f, 80f, null)
            rgb(canvas) to shown
        }
        AiTest.SEGMENT -> {
            val wide = Bitmap.createBitmap(city, 0, city.height / 6, city.width, city.width / 2)
            rgb(scale(wide, 2048, 1024)) to scale(wide, 1024, 512)
        }
        AiTest.UPSCALE -> {
            val small = scale(city, 1000, 750)
            val crop = Bitmap.createBitmap(small, 720, 330, 128, 128)        // the SPA sign and shop lettering
            rgb(crop) to crop
        }
        AiTest.DEPTH -> square(city).let { rgb(scale(it, 256, 256)) to scale(it, 512, 512) }
        AiTest.SOUND -> context.assets.open("ai/yamnet_input.bin").use { it.readBytes() }.let { it to spectrogram(it) }
    }

    /** Decodes the outputs of `test`'s last inference into what the stage draws. */
    fun visual(test: AiTest, picture: Bitmap, out: List<ByteArray>): AiVisual = when (test) {
        AiTest.CLASSIFY -> AiVisual(test, picture, labels = top(dequant(out[0], 0.13067922f, 76), labels("mobilenet_v2"), 3, softmax = true))
        AiTest.DETECT -> AiVisual(test, picture, boxes = detections(out[0], out[1], out[2], labels("yolox")))
        AiTest.SEGMENT -> AiVisual(test, picture, result = segmentation(out[0]))
        AiTest.UPSCALE -> AiVisual(test, picture, result = upscaled(out[0]))
        AiTest.DEPTH -> AiVisual(test, picture, result = depth(out[0]))
        AiTest.SOUND -> AiVisual(test, picture, labels = top(dequant(out[0], 0.7587276f, 190), labels("yamnet"), 3, softmax = false))
    }

    // ── preparing ──────────────────────────────────────────────────────────

    private fun square(b: Bitmap): Bitmap {
        val s = min(b.width, b.height)
        return Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
    }

    private fun scale(b: Bitmap, w: Int, h: Int): Bitmap = Bitmap.createScaledBitmap(b, w, h, true)

    private fun rgb(b: Bitmap): ByteArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val out = ByteArray(px.size * 3)
        for (i in px.indices) {
            val p = px[i]
            out[i * 3] = (p shr 16).toByte(); out[i * 3 + 1] = (p shr 8).toByte(); out[i * 3 + 2] = p.toByte()
        }
        return out
    }

    /** YAMNet's 96 × 64 log-mel patch as a picture: time across, pitch up. */
    private fun spectrogram(q: ByteArray): Bitmap {
        val frames = 96; val bands = 64
        val bmp = Bitmap.createBitmap(frames, bands, Bitmap.Config.ARGB_8888)
        for (t in 0 until frames) for (m in 0 until bands) {
            val v = ((q[t * bands + m].toInt() and 0xFF) - 60).coerceIn(0, 180) / 180f
            bmp.setPixel(t, bands - 1 - m, magma(v))
        }
        return scale(bmp, 960, 512)
    }

    // ── decoding ───────────────────────────────────────────────────────────

    private fun dequant(q: ByteArray, scale: Float, zero: Int) = FloatArray(q.size) { ((q[it].toInt() and 0xFF) - zero) * scale }

    private fun top(v: FloatArray, names: List<String>, k: Int, softmax: Boolean): List<Pair<String, Float>> {
        val p = if (softmax) {
            val m = v.max(); val e = FloatArray(v.size) { exp(v[it] - m) }; val s = e.sum(); FloatArray(v.size) { e[it] / s }
        } else FloatArray(v.size) { 1f / (1f + exp(-v[it])) }
        return p.indices.sortedByDescending { p[it] }.take(k).map { names.getOrElse(it) { "#$it" } to p[it] }
    }

    /** YOLOX: 8400 candidate boxes (x1 y1 x2 y2 in the 640×640 input), scores and classes → kept after NMS. */
    private fun detections(boxesQ: ByteArray, scoresQ: ByteArray, classes: ByteArray, names: List<String>): List<AiBox> {
        val boxes = dequant(boxesQ, 4.416495f, 51)
        val scores = dequant(scoresQ, 0.0038126425f, 0)
        val candidates = scores.indices.filter { scores[it] > 0.35f }.sortedByDescending { scores[it] }
        val kept = ArrayList<Int>()
        for (i in candidates) {
            if (kept.size == 25) break
            if (kept.none { j -> (classes[j] == classes[i]) && iou(boxes, i, j) > 0.45f }) kept += i
        }
        return kept.map { i ->
            AiBox(boxes[i * 4], boxes[i * 4 + 1] - 80f, boxes[i * 4 + 2], boxes[i * 4 + 3] - 80f,
                names.getOrElse(classes[i].toInt() and 0xFF) { "object" }, scores[i])
        }
    }

    private fun iou(b: FloatArray, i: Int, j: Int): Float {
        val x1 = max(b[i * 4], b[j * 4]); val y1 = max(b[i * 4 + 1], b[j * 4 + 1])
        val x2 = min(b[i * 4 + 2], b[j * 4 + 2]); val y2 = min(b[i * 4 + 3], b[j * 4 + 3])
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val a = (b[i * 4 + 2] - b[i * 4]) * (b[i * 4 + 3] - b[i * 4 + 1])
        val c = (b[j * 4 + 2] - b[j * 4]) * (b[j * 4 + 3] - b[j * 4 + 1])
        return inter / max(1e-6f, a + c - inter)
    }

    /** FFNet: 128 × 256 × 19 Cityscapes class scores → a coloured, half-transparent class map. */
    private fun segmentation(q: ByteArray): Bitmap {
        val h = 128; val w = 256; val c = 19
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            var best = 0; var bestV = -1
            for (k in 0 until c) { val v = q[i * c + k].toInt() and 0xFF; if (v > bestV) { bestV = v; best = k } }
            px[i] = (0xA0 shl 24) or CITYSCAPES[best]
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun upscaled(q: ByteArray): Bitmap {
        val n = 512
        val px = IntArray(n * n) { i ->
            fun ch(k: Int) = (((q[i * 3 + k].toInt() and 0xFF) - 25) * 0.004972266f * 255f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** MiDaS: relative inverse depth (bigger = nearer) → a heat map, near warm. */
    private fun depth(q: ByteArray): Bitmap {
        val n = 256
        var lo = 255; var hi = 0
        for (b in q) { val v = b.toInt() and 0xFF; lo = min(lo, v); hi = max(hi, v) }
        val px = IntArray(n * n) { i -> magma(((q[i].toInt() and 0xFF) - lo) / max(1f, (hi - lo).toFloat())) }
        return scale(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), 512, 512)
    }

    /** A magma-like ramp: black → purple → orange → pale yellow. */
    private fun magma(t: Float): Int {
        val x = t.coerceIn(0f, 1f)
        val r = (255 * min(1f, x * 1.6f)).toInt()
        val g = (255 * max(0f, x * x * 1.1f - 0.05f)).coerceAtMost(255f).toInt()
        val b = (255 * (0.35f + 0.5f * kotlin.math.sin(x * 3.1f) - 0.2f * x)).coerceIn(0f, 255f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** Cityscapes' standard class colours (road, sidewalk, building, … bicycle). */
private val CITYSCAPES = intArrayOf(
    0x804080, 0xF423E8, 0x464646, 0x66669C, 0xBE9999, 0x999999, 0xFAAA1E, 0xDCDC00, 0x6B8E23, 0x98FB98,
    0x4682B4, 0xDC143C, 0xFF0000, 0x00008E, 0x000046, 0x003C64, 0x005064, 0x0000E6, 0x770B20
)
