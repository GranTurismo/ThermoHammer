package com.example.thermohammer.engine

import com.example.thermohammer.engine.cpu.CpuTest
import com.example.thermohammer.engine.cpu.CpuTestRun
import com.example.thermohammer.engine.ux.QrResult
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

// ── GPU benchmark — Blender-authored scenes, one score ─────────────────────────
// The default run: GPU Test 1 (Lake Valley) and GPU Test 2 (Neon City) on
// OpenGL ES, then GPU Test 3 (Neon City RT: Vulkan ray queries) on phones that
// support it — elsewhere it scores 0. Each renders offscreen at 1920×1080 for a
// fixed timeline. The sustained stress test (StressMode / TestDuration) remains
// the second, longer mode.

enum class RunKind { BENCHMARK, STRESS }

/** One GPU test: its scene, timeline length and the weight that turns fps into points — the Snapdragon 8 Elite (Honor VKP-NX9, release build, 2026-10-25) = 6000 (BenchmarkScoring.cs repeats them). */
enum class BenchmarkTest(val number: Int, val scene: GpuSceneId, val title: String, val seconds: Int,
                         val weight: Double, val rayTracing: Boolean = false) {
    LAKE_VALLEY(1, GpuSceneId.LAKE_VALLEY, "LAKE VALLEY", 50, 46.3),                       // 129.5 fps
    NEON_CITY(2, GpuSceneId.NEON_CITY, "NEON CITY", 60, 228.0),                            // 26.3 fps
    NEON_CITY_RT(3, GpuSceneId.NEON_CITY, "NEON CITY RT", 60, 448.0, rayTracing = true);  // 13.4 fps
}

data class BenchmarkTestResult(
    val test: BenchmarkTest,
    val supported: Boolean = true,    // false: the phone can't run this test (no ray tracing) — 0 points
    val avgFps: Double,           // frames ÷ their summed render time (presentation excluded)
    val lowFps: Double,           // 1 % low — fps at the 99th-percentile frame time
    val frames: Int,
    val stabilityPct: Float,      // slowest 5 s window ÷ fastest 5 s window
    val startTempC: Float?,
    val endTempC: Float?,
    val points: Int
) {
    companion object {
        fun unsupported(test: BenchmarkTest) = BenchmarkTestResult(test = test, supported = false, avgFps = 0.0,
            lowFps = 0.0, frames = 0, stabilityPct = 0f, startTempC = null, endTempC = null, points = 0)
    }
}

data class BenchmarkResult(
    val tests: List<BenchmarkTestResult>,
    val gpuScore: Int,
    val gpuName: String,
    val validityFlags: Int,
    val rayTracingProblem: String? = null,   // why Test 3 didn't run, null when it did
    val ux: UxResult? = null,
    val cpu: CpuResult? = null,
    val ai: AiResult? = null,
    val mem: MemResult? = null,
    val startCpuShare: Float = 1f,           // share of the CPU the app got when the tests began
    val quietWaitSec: Int = 0                // how long it waited for background work to finish first
) {
    val isVerified: Boolean get() = validityFlags == 0
    val uxScore: Int get() = ux?.score ?: 0
    val cpuScore: Int get() = cpu?.score ?: 0
    val aiScore: Int get() = ai?.score ?: 0
    val memScore: Int get() = mem?.score ?: 0
    /** CPU + GPU + MEM + UX + AI, like AnTuTu's sections. */
    val total: Int get() = cpuScore + gpuScore + memScore + uxScore + aiScore
}

// ── MEM tests: RAM, storage and an app-style database (after the CPU tests) ───

/** MEM tests in run order; `labels` are their measurements, in order. */
enum class MemTest(val number: Int, val title: String, val seconds: Int, val labels: List<String>) {
    RAM(1, "RAM", 10, listOf("READ", "WRITE", "COPY", "LATENCY")),
    STORAGE(2, "STORAGE", 12, listOf("SEQ WRITE", "SEQ READ", "RANDOM READ", "RANDOM WRITE")),
    DATABASE(3, "DATABASE", 8, listOf("INSERT", "UPDATE", "QUERY"))
}

/** RAM over a buffer far bigger than the caches: GB/s with every core streaming at once, and one random access in ns. */
data class RamResult(val read: Double, val write: Double, val copy: Double, val latencyNs: Double)

/**
 * The flash: 4 MB sequential transfers in MB/s, random 4 KB transfers from 4 threads in IOPS, on a test file of
 * `fileMb`. `direct` = the page cache was bypassed (otherwise it was dropped before every read).
 */
data class StorageResult(val seqRead: Double, val seqWrite: Double, val randomRead: Double, val randomWrite: Double,
                         val direct: Boolean, val fileMb: Int)

/** SQLite the way a messaging app uses it: messages saved and marked read (a durable transaction each), chats opened. Per second. */
data class DbResult(val insert: Double, val update: Double, val query: Double)

data class MemResult(val ram: RamResult, val storage: StorageResult, val db: DbResult) {
    val ramPoints: Int get() = MemScore.ram(ram.read, ram.write, ram.copy, ram.latencyNs)
    val storagePoints: Int get() = MemScore.storage(storage.seqRead, storage.seqWrite, storage.randomRead, storage.randomWrite)
    val dbPoints: Int get() = MemScore.db(db.insert, db.update, db.query)
    val score: Int get() = MemScore.overall(listOf(ramPoints, storagePoints, dbPoints))
}

object MemScore {
    // Reference values, 6000 points each: the Snapdragon 8 Elite (Honor VKP-NX9, release build, 2026-10-25) scores 6000 on every test.
    // Never calibrate on the emulator — its RAM and storage are the Mac's. Repeated in BenchmarkScoring.cs.
    const val REF_READ = 57.8              // GB/s, every core
    const val REF_WRITE = 28.4
    const val REF_COPY = 18.7
    const val REF_LATENCY = 245.0          // ns per random access — lower is better
    const val REF_SEQ_READ = 1750.0        // MB/s
    const val REF_SEQ_WRITE = 1300.0
    const val REF_RANDOM_READ = 29_000.0   // 4 KB IOPS
    const val REF_RANDOM_WRITE = 13_400.0
    const val REF_INSERT = 9700.0          // transactions/s
    const val REF_UPDATE = 36_000.0
    const val REF_QUERY = 20_300.0         // chats opened/s

    /** 6000 × the geometric mean of value ÷ reference; 0 if any part is 0. */
    private fun geo(vararg ratios: Double): Int =
        if (ratios.any { !(it > 0) }) 0 else (6000 * exp(ratios.sumOf { ln(it) } / ratios.size)).roundToInt()

    fun ram(read: Double, write: Double, copy: Double, latencyNs: Double): Int =
        if (!(latencyNs > 0)) 0 else geo(read / REF_READ, write / REF_WRITE, copy / REF_COPY, REF_LATENCY / latencyNs)

    fun storage(seqRead: Double, seqWrite: Double, randomRead: Double, randomWrite: Double): Int =
        geo(seqRead / REF_SEQ_READ, seqWrite / REF_SEQ_WRITE, randomRead / REF_RANDOM_READ, randomWrite / REF_RANDOM_WRITE)

    fun db(insert: Double, update: Double, query: Double): Int = geo(insert / REF_INSERT, update / REF_UPDATE, query / REF_QUERY)

    /** Geometric mean of the three tests. */
    fun overall(points: List<Int>): Int {
        if (points.isEmpty() || points.any { it <= 0 }) return 0
        return exp(points.sumOf { ln(it.toDouble()) } / points.size).roundToInt()
    }
}

// ── CPU tests: math, JSON & XML, compression & crypto, multi-core ─────────────

data class CpuResult(val runs: List<CpuTestRun>) {
    fun value(t: CpuTest): Double = runs.firstOrNull { it.test == t }?.value ?: 0.0
    fun points(t: CpuTest): Int = CpuScore.points(t, value(t))
    val score: Int get() = CpuScore.overall(CpuTest.entries.map { points(it) })
}

object CpuScore {
    // Reference values (geometric mean of the test's kernel ops/s), 6000 points each — PROVISIONAL: the
    // kernels moved to shared C++ (BenchCore), so the Snapdragon 8 Elite anchor of the Kotlin kernels is
    // scaled by the C++/Kotlin speed ratio measured on the emulator, until a phone run calibrates them.
    // Repeated in BenchmarkScoring.cs.
    const val REF_MATH = 1172.0
    const val REF_DATA = 632.0
    const val REF_CRYPTO = 595.0
    const val REF_MULTI = 6091.0

    fun ref(t: CpuTest) = when (t) {
        CpuTest.MATH -> REF_MATH
        CpuTest.DATA -> REF_DATA
        CpuTest.CRYPTO -> REF_CRYPTO
        CpuTest.MULTI -> REF_MULTI
    }

    fun points(t: CpuTest, value: Double): Int = (6000 * value.coerceAtLeast(0.0) / ref(t)).roundToInt()

    /** Geometric mean of the four tests. */
    fun overall(points: List<Int>): Int {
        if (points.isEmpty() || points.any { it <= 0 }) return 0
        return exp(points.sumOf { ln(it.toDouble()) } / points.size).roundToInt()
    }
}

// ── UX tests: everyday work — the browser engine, photos, video and the camera pipeline ─

/** UX tests in run order; `page` is the WebView page of the browser tests. */
enum class UxTest(val number: Int, val title: String, val seconds: Int, val page: String? = null) {
    WEB(1, "WEB BROWSING", 36, "web/bench.html"),        // layout, Canvas 2D, CSS, SVG ramps + a JavaScript suite
    SCROLL(2, "WEB SCROLLING", 20, "web/scroll.html"),   // an endless social feed built while it scrolls ever faster
    GALLERY(3, "PHOTO GALLERY", 16, "web/gallery.html"), // 12 MP JPEG, WebP and AVIF photos decoded and shown
    PHOTO(4, "PHOTO EDITING", 15),                       // GPU filters on a 12 MP photo: lens, blur, bokeh, sharpen, grade
    VIDEO(5, "VIDEO PLAYBACK", 20),                      // 4K60 H.264, HEVC 10-bit, VP9, AV1 decoded flat out
    QR(6, "QR SCANNING", 24)                             // 32 camera-like frames: ZXing timed (scored), ML Kit (reported)
}

/** Raw WebView results: sustainable complexity of each scene at ≥ 28 fps, and JavaScript ops/s. */
data class WebResult(val layout: Double, val canvas: Double, val css: Double, val svg: Double, val js: Double)

/** Web scrolling: the fastest feed speed held with ≤ 10 % dropped frames (CSS px/s); dropped frames over the run (%). */
data class ScrollResult(val speed: Double, val droppedPct: Double)

/** Photo gallery: decode-and-show throughput of each format, megapixels per second. */
data class GalleryResult(val jpeg: Double, val webp: Double, val avif: Double)

/** The clips of UX Test 5 (assets/media): the same second of Neon City, 3840×2160 at 60 fps. */
enum class VideoCodec(val label: String, val short: String, val mime: String, val asset: String) {
    AVC("H.264", "H.264", "video/avc", "media/clip_avc.mp4"),
    HEVC("HEVC 10-BIT", "HEVC", "video/hevc", "media/clip_hevc10.mp4"),
    VP9("VP9", "VP9", "video/x-vnd.on2.vp9", "media/clip_vp9.webm"),
    AV1("AV1 10-BIT", "AV1", "video/av01", "media/clip_av1.mp4")
}

/** One codec: decoded frames per second (0 = the phone can't decode it), the decoder and whether it is hardware. */
data class VideoCodecResult(val codec: VideoCodec, val fps: Double, val decoder: String?, val hardware: Boolean,
                            val error: String? = null)

data class VideoResult(val codecs: List<VideoCodecResult>) {
    fun fps(c: VideoCodec): Double = codecs.firstOrNull { it.codec == c }?.fps ?: 0.0
    /** Bit `ordinal` set for each codec decoded in hardware — reported, not scored. */
    val hardwareMask: Int get() = codecs.filter { it.hardware && it.fps > 0 }.fold(0) { m, r -> m or (1 shl r.codec.ordinal) }
}

data class UxResult(
    val web: WebResult?,
    val scroll: ScrollResult?,
    val gallery: GalleryResult?,
    val photoMps: Double,            // photo editing: megapixels per second through the whole filter chain
    val video: VideoResult?,
    val qr: QrResult?,
    val webPoints: Int,
    val scrollPoints: Int,
    val galleryPoints: Int,
    val photoPoints: Int,
    val videoPoints: Int,
    val qrPoints: Int,
    val error: String? = null
) {
    val score: Int get() = UxScore.overall(listOf(webPoints, scrollPoints, galleryPoints, photoPoints, videoPoints, qrPoints))
}

object UxScore {
    // Reference results, scored at 6000 points each: the Snapdragon 8 Elite (Honor VKP-NX9, release build, 2026-10-25) scores 6000 on every test.
    // The server repeats these numbers (BenchmarkScoring.cs).
    const val REF_LAYOUT = 1320.0     // cards reflowed every frame
    const val REF_CANVAS = 2730.0     // gradient shapes
    const val REF_CSS = 1980.0        // composited tiles
    const val REF_SVG = 3130.0        // animated paths
    const val REF_JS = 2160.0         // geometric mean of suite ops/s
    const val REF_SCROLL = 66_600.0   // feed px/s held
    const val REF_JPEG = 122.0        // gallery MP/s per format
    const val REF_WEBP = 99.0
    const val REF_AVIF = 103.0
    const val REF_PHOTO = 397.0       // MP/s through the editing chain
    const val REF_VIDEO = 298.0       // decoded 4K frames/s, mean of the codecs
    // zxing-cpp decodes/s → points (440/s → 6000). Provisional: the Honor's 125/s with Java ZXing × the 3.5×
    // zxing-cpp speed-up measured on the arm64 emulator — re-anchor on the Honor.
    const val QR_WEIGHT = 13.6

    fun webPoints(r: WebResult): Int {
        val parts = doubleArrayOf(r.layout / REF_LAYOUT, r.canvas / REF_CANVAS, r.css / REF_CSS, r.svg / REF_SVG, r.js / REF_JS)
        if (parts.any { !(it > 0.0) }) return 0
        return (6000 * exp(parts.sumOf { ln(it) } / parts.size)).roundToInt()
    }

    fun scrollPoints(speed: Double): Int = (6000 * speed.coerceAtLeast(0.0) / REF_SCROLL).roundToInt()

    /** Mean over the three formats, so a WebView without AVIF loses a third instead of everything. */
    fun galleryPoints(r: GalleryResult): Int =
        (6000 * (r.jpeg.coerceAtLeast(0.0) / REF_JPEG + r.webp.coerceAtLeast(0.0) / REF_WEBP +
            r.avif.coerceAtLeast(0.0) / REF_AVIF) / 3).roundToInt()

    fun photoPoints(mps: Double): Int = (6000 * mps.coerceAtLeast(0.0) / REF_PHOTO).roundToInt()

    /** Mean over the four codecs, so a codec the phone lacks costs a quarter instead of everything. */
    fun videoPoints(fps: List<Double>): Int =
        (6000 * fps.sumOf { it.coerceAtLeast(0.0) } / (REF_VIDEO * VideoCodec.entries.size)).roundToInt()

    fun videoPoints(r: VideoResult): Int = videoPoints(VideoCodec.entries.map { r.fps(it) })

    fun qrPoints(zxingRate: Double): Int = (zxingRate.coerceAtLeast(0.0) * QR_WEIGHT).roundToInt()

    /** Geometric mean of the six UX tests; 0 if any failed. */
    fun overall(points: List<Int>): Int {
        if (points.isEmpty() || points.any { it <= 0 }) return 0
        return exp(points.sumOf { ln(it.toDouble()) } / points.size).roundToInt()
    }
}

// ── AI tests: open vision and audio models on the CPU, the GPU and the NPU ────

/** Where a model runs. The NPU runs only where LiteRT finds the phone's NPU runtime; elsewhere it scores 0. */
enum class AiAccel(val label: String) { CPU("CPU"), GPU("GPU"), NPU("NPU") }

/**
 * The AI workloads: Qualcomm AI Hub's 8-bit (w8a8) LiteRT builds of open models (assets/ai, Apache-2.0, MIT
 * and BSD), the same file on every accelerator. Each runs on the CPU, then the GPU, then the NPU.
 */
enum class AiTest(val number: Int, val title: String, val model: String, val asset: String, val seconds: Int) {
    CLASSIFY(1, "IMAGE CLASSIFICATION", "MobileNet-v2 · 224×224", "ai/mobilenet_v2.tflite", 7),
    DETECT(2, "OBJECT DETECTION", "YOLOX · 640×640", "ai/yolox.tflite", 8),
    SEGMENT(3, "STREET SEGMENTATION", "FFNet-40S · Cityscapes · 2048×1024", "ai/ffnet_40s.tflite", 8),
    UPSCALE(4, "SUPER RESOLUTION", "Real-ESRGAN ×4 · 128 → 512", "ai/real_esrgan_general_x4v3.tflite", 8),
    DEPTH(5, "DEPTH ESTIMATION", "MiDaS v2 · 256×256", "ai/midas.tflite", 8),
    SOUND(6, "SOUND RECOGNITION", "YAMNet · 0.96 s of audio", "ai/yamnet.tflite", 7)
}

/** One model on one accelerator: inferences per second (0 = it couldn't run there, `error` says why). */
data class AiRun(val test: AiTest, val accel: AiAccel, val ips: Double, val error: String? = null)

data class AiResult(
    val runs: List<AiRun>,
    val npuRuntime: String?,          // e.g. "Qualcomm QNN" — null when the NPU didn't run
    val npuProblem: String? = null    // why it didn't
) {
    fun ips(t: AiTest, a: AiAccel): Double = runs.firstOrNull { it.test == t && it.accel == a }?.ips ?: 0.0
    fun points(t: AiTest, a: AiAccel): Int = AiScore.points(t, a, ips(t, a))
    fun accelScore(a: AiAccel): Int = AiScore.accel(a, AiTest.entries.map { ips(it, a) })
    val score: Int get() = AiScore.overall(accelScore(AiAccel.CPU), accelScore(AiAccel.GPU), accelScore(AiAccel.NPU))
}

object AiScore {
    // Inferences per second worth 6000 points, per accelerator, in AiTest order: the Snapdragon 8 Elite (Honor VKP-NX9, release build, 2026-10-25) scores 6000 on every test
    // (NPU through Qualcomm QNN). Repeated in BenchmarkScoring.cs.
    val REF_CPU = doubleArrayOf(330.0, 18.2, 4.71, 2.65, 42.0, 2890.0)
    val REF_GPU = doubleArrayOf(658.0, 85.5, 33.1, 92.8, 182.0, 1470.0)
    val REF_NPU = doubleArrayOf(1690.0, 473.0, 229.0, 774.0, 993.0, 2100.0)

    fun ref(t: AiTest, a: AiAccel): Double = when (a) {
        AiAccel.CPU -> REF_CPU; AiAccel.GPU -> REF_GPU; AiAccel.NPU -> REF_NPU
    }[t.ordinal]

    fun points(t: AiTest, a: AiAccel, ips: Double): Int = (6000 * ips.coerceAtLeast(0.0) / ref(t, a)).roundToInt()

    /** Mean over the models, so one an accelerator can't run costs a sixth instead of everything. */
    fun accel(a: AiAccel, ips: List<Double>): Int =
        (AiTest.entries.sumOf { t -> 6000 * ips[t.ordinal].coerceAtLeast(0.0) / ref(t, a) } / AiTest.entries.size).roundToInt()

    /** Each accelerator counts a third; one the phone lacks (or can't run the models on) scores 0 there. */
    fun overall(cpu: Int, gpu: Int, npu: Int): Int =
        ((cpu.coerceAtLeast(0) + gpu.coerceAtLeast(0) + npu.coerceAtLeast(0)) / 3.0).roundToInt()
}

object BenchmarkScore {
    /** Bump when a scene, the resolution or the scoring changes — scores compare only within a version. */
    const val VERSION = 2
    /** Frames that start before this (s) are driver/shader warm-up and not scored. */
    const val WARMUP_S = 2f
    private const val WINDOW_S = 5f

    fun points(test: BenchmarkTest, fps: Double): Int = (fps * test.weight).roundToInt()

    /** fps are kept to 2 decimals — what is hashed, submitted and re-scored by the server. */
    fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0

    /**
     * Raster (geometric mean of Tests 1–2, so a weak test pulls it down proportionally) counts two
     * thirds, ray tracing (Test 3; 0 where unsupported) one third.
     */
    fun overall(test1: Int, test2: Int, test3: Int): Int {
        if (test1 <= 0 || test2 <= 0) return 0
        val raster = exp((ln(test1.toDouble()) + ln(test2.toDouble())) / 2)
        return ((2 * raster + test3.coerceAtLeast(0)) / 3).roundToInt()
    }

    fun overall(results: List<BenchmarkTestResult>): Int {
        fun pts(t: BenchmarkTest) = results.firstOrNull { it.test == t }?.points ?: 0
        return overall(pts(BenchmarkTest.LAKE_VALLEY), pts(BenchmarkTest.NEON_CITY), pts(BenchmarkTest.NEON_CITY_RT))
    }

    /**
     * Summarises one test from its frame log: `startS[i]` = timeline time the frame began,
     * `durNs[i]` = its render time. Returns null when too few frames were scored.
     */
    fun summarize(test: BenchmarkTest, startS: FloatArray, durNs: LongArray, count: Int,
                  startTempC: Float?, endTempC: Float?): BenchmarkTestResult? {
        val idx = (0 until count).filter { startS[it] >= WARMUP_S }
        if (idx.size < 10) return null
        val totalNs = idx.sumOf { durNs[it] }
        val avg = round2(idx.size * 1e9 / totalNs)
        val sorted = idx.map { durNs[it] }.sorted()
        val p99 = sorted[((sorted.size - 1) * 0.99).roundToInt()]
        val low = round2(1e9 / p99)
        // throughput per 5 s window of the timeline; partial tail windows are skipped
        val windows = HashMap<Int, Pair<Int, Long>>()
        for (i in idx) {
            val w = ((startS[i] - WARMUP_S) / WINDOW_S).toInt()
            val (n, ns) = windows[w] ?: (0 to 0L)
            windows[w] = (n + 1) to (ns + durNs[i])
        }
        val lastFull = ((test.seconds - WARMUP_S) / WINDOW_S).toInt() - 1
        val rates = windows.filterKeys { it <= lastFull }.values.filter { it.first > 0 }.map { it.first * 1e9 / it.second }
        val stability = if (rates.size >= 2) (rates.min() / rates.max() * 100).toFloat() else 100f
        return BenchmarkTestResult(test = test, avgFps = avg, lowFps = low, frames = idx.size, stabilityPct = stability,
            startTempC = startTempC, endTempC = endTempC, points = points(test, avg))
    }
}
