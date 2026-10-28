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
    val llm: LlmResult? = null,
    val startCpuShare: Float = 1f,           // share of the CPU the app got when the tests began
    val quietWaitSec: Int = 0                // how long it waited for background work to finish first
) {
    val isVerified: Boolean get() = validityFlags == 0
    val uxScore: Int get() = ux?.score ?: 0
    val cpuScore: Int get() = cpu?.score ?: 0
    val aiScore: Int get() = ai?.score ?: 0
    val memScore: Int get() = mem?.score ?: 0
    val llmScore: Int get() = llm?.score ?: 0
    /** CPU + GPU + MEM + UX + AI + LLM, like AnTuTu's sections. */
    val total: Int get() = cpuScore + gpuScore + memScore + uxScore + aiScore + llmScore
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
    // Reference values (geometric mean of the test's kernel ops/s, the shared C++ kernels of BenchCore), 6000 points
    // each: the Snapdragon 8 Elite (Honor VKP-NX9, bench build, 2026-10-26). Repeated in BenchmarkScoring.cs.
    const val REF_MATH = 1469.0
    const val REF_DATA = 647.0
    const val REF_CRYPTO = 580.0
    const val REF_MULTI = 5003.0

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
    VIDEO(5, "VIDEO PLAYBACK", 28),                      // 4K60 H.264, HEVC 10-bit, VP9, AV1 and 8K30 HEVC, AV1 decoded flat out
    EXPORT(6, "VIDEO EXPORT", 26),                       // a 4K edit graded on the GPU, encoded H.264, HEVC, AV1 (+ 8K HEVC)
    QR(7, "QR SCANNING", 24)                             // 32 camera-like frames: ZXing timed (scored), ML Kit (reported)
}

/** Raw WebView results: sustainable complexity of each scene at ≥ 28 fps, and JavaScript ops/s. */
data class WebResult(val layout: Double, val canvas: Double, val css: Double, val svg: Double, val js: Double)

/** Web scrolling: the fastest feed speed held with ≤ 10 % dropped frames (CSS px/s); dropped frames over the run (%). */
data class ScrollResult(val speed: Double, val droppedPct: Double)

/** Photo gallery: decode-and-show throughput of each format, megapixels per second. */
data class GalleryResult(val jpeg: Double, val webp: Double, val avif: Double)

enum class VideoCodec(val label: String, val short: String, val mime: String) {
    AVC("H.264", "H.264", "video/avc"),
    HEVC("HEVC 10-BIT", "HEVC", "video/hevc"),
    VP9("VP9", "VP9", "video/x-vnd.on2.vp9"),
    AV1("AV1 10-BIT", "AV1", "video/av01")
}

/**
 * The clips of UX Test 5 (assets/media, BenchmarkScene/build_ux_media.sh): Neon City, a different three seconds in
 * each, so the test plays one continuous film — 4K60 in every codec, 8K30 in the two that 8K is actually shipped in
 * (cameras record HEVC, streaming serves AV1). 8K is 30 fps so a decoder rated for 8K30 only still takes it.
 */
enum class VideoClip(val codec: VideoCodec, val k8: Boolean, val asset: String, val width: Int, val height: Int,
                     val fps: Int, val mbps: Int) {
    AVC_4K(VideoCodec.AVC, false, "media/clip_avc.mp4", 3840, 2160, 60, 30),
    HEVC_4K(VideoCodec.HEVC, false, "media/clip_hevc10.mp4", 3840, 2160, 60, 18),
    VP9_4K(VideoCodec.VP9, false, "media/clip_vp9.webm", 3840, 2160, 60, 16),
    AV1_4K(VideoCodec.AV1, false, "media/clip_av1.mp4", 3840, 2160, 60, 10),
    HEVC_8K(VideoCodec.HEVC, true, "media/clip8k_hevc10.mp4", 7680, 4320, 30, 40),
    AV1_8K(VideoCodec.AV1, true, "media/clip8k_av1.mp4", 7680, 4320, 30, 24);

    val res: String get() = if (k8) "8K" else "4K"
    val label: String get() = "${codec.label} $res"
}

/** One clip: decoded frames per second (0 = the phone can't decode it), the decoder and whether it is hardware. */
data class VideoCodecResult(val clip: VideoClip, val fps: Double, val decoder: String?, val hardware: Boolean,
                            val error: String? = null) {
    val codec: VideoCodec get() = clip.codec
}

data class VideoResult(val codecs: List<VideoCodecResult>) {
    fun fps(c: VideoClip): Double = codecs.firstOrNull { it.clip == c }?.fps ?: 0.0
    /** Bit `ordinal` set for each clip decoded in hardware — reported, not scored. */
    val hardwareMask: Int get() = codecs.filter { it.hardware && it.fps > 0 }.fold(0) { m, r -> m or (1 shl r.clip.ordinal) }
}

/**
 * The jobs of UX Test 6: the H.264 4K clip (every phone decodes it) graded on the GPU and encoded at a fixed bitrate,
 * 4K60 in three codecs and 8K30 in HEVC (from the 8K HEVC clip) — what an editor's export does. Each is a fixed
 * timeline (`seconds` of video), exported start to finish.
 */
enum class ExportJob(val codec: VideoCodec, val k8: Boolean, val label: String, val mime: String, val source: VideoClip,
                     val width: Int, val height: Int, val fps: Int, val mbps: Int, val seconds: Int) {
    AVC_4K(VideoCodec.AVC, false, "H.264", "video/avc", VideoClip.AVC_4K, 3840, 2160, 60, 40, 8),
    HEVC_4K(VideoCodec.HEVC, false, "HEVC", "video/hevc", VideoClip.AVC_4K, 3840, 2160, 60, 30, 8),
    AV1_4K(VideoCodec.AV1, false, "AV1", "video/av01", VideoClip.AVC_4K, 3840, 2160, 60, 20, 8),
    HEVC_8K(VideoCodec.HEVC, true, "HEVC", "video/hevc", VideoClip.HEVC_8K, 7680, 4320, 30, 80, 4);

    val res: String get() = if (k8) "8K" else "4K"
    val frames: Int get() = seconds * fps
    /** What the export dialog calls the file. */
    val fileName: String get() = "Neon City · Night Run · $res $label.mp4"
}

/** UX Test 6 on the stage: an editor's export dialog — the job in hand, its progress, and the render queue. */
data class ExportStage(
    val job: ExportJob,
    val encoder: String?,
    val hardware: Boolean,
    val framesDone: Int,
    val elapsedS: Double,
    val fps: Double,
    val step: String,                    // ENCODING · FINISHING THE FILE · CHECKING QUALITY · or why it can't run
    val queue: List<Item>
) {
    enum class State { DONE, ACTIVE, PENDING, SKIPPED }
    data class Item(val job: ExportJob, val state: State, val fps: Double = 0.0, val psnr: Double = 0.0, val note: String? = null)
    val progress: Float get() = (framesDone.toFloat() / job.frames).coerceIn(0f, 1f)
    /** Seconds left at the rate so far (null before there is one). */
    val leftS: Double? get() = if (fps > 0) (job.frames - framesDone).coerceAtLeast(0) / fps else null
    /** "×1.2 REAL TIME" — two decimals below 1, so a slow export doesn't read ×0.0. */
    val speedText: String get() = if (fps <= 0) "×— REAL TIME" else (fps / job.fps).let { (if (it < 1) "×%.2f REAL TIME" else "×%.1f REAL TIME").format(it) }
    /** "~2.4 S LEFT · " while encoding — or, when it won't finish in time, where it'll be cut off. */
    val timeLeftText: String get() {
        val left = leftS ?: return ""
        if (step != "ENCODING") return ""
        return if (elapsedS + left > CAP_S) "STOPS AT %.0f S · ".format(CAP_S) else "~%.1f S LEFT · ".format(left)
    }

    companion object {
        /** VideoExportScene.CAP_S: an export that can't finish by then is measured on what it did. */
        const val CAP_S = 15.0
    }
}

/**
 * One export: frames encoded per second, and the exported file's quality — PSNR (dB) of the luma of sampled frames'
 * lower third (title + running timecode) against what was sent in, at full resolution. Below [VideoGrid.MIN_PSNR] the
 * speed doesn't count.
 */
data class ExportJobResult(val job: ExportJob, val fps: Double, val psnr: Double, val encoder: String?, val hardware: Boolean,
                           val error: String? = null) {
    val counted: Double get() = if (psnr >= VideoGrid.MIN_PSNR) fps else 0.0
}

data class ExportResult(val jobs: List<ExportJobResult>) {
    fun job(j: ExportJob): ExportJobResult? = jobs.firstOrNull { it.job == j }
    fun fps(j: ExportJob): Double = job(j)?.fps ?: 0.0
    fun psnr(j: ExportJob): Double = job(j)?.psnr ?: 0.0
    fun counted(j: ExportJob): Double = job(j)?.counted ?: 0.0
    /** Bit `ordinal` set for each job encoded in hardware — reported, not scored. */
    val hardwareMask: Int get() = jobs.filter { it.hardware && it.fps > 0 }.fold(0) { m, r -> m or (1 shl r.job.ordinal) }
}

/** The stage's player overlay during UX Test 5. */
data class VideoOsd(
    val format: String,          // e.g. "HEVC 10-BIT · 3840×2160 · 60 FPS"
    val mbps: Int,
    val positionS: Double,       // where in the clip
    val durationS: Double,       // the clip's length
    val speed: Double            // × real time: frames per second ÷ the format's frame rate
)

/**
 * What a speed means in playback terms: a codec plays (or exports) a format in real time when it decodes (encodes) that
 * resolution at least 10 % faster than the format's frame rate. Derived from the measured speed, so a faster future
 * chip fills in the higher tiers without a new test. 8K240 needs more than any HEVC or VP9 level allows — it shows "–"
 * on everything today.
 */
object VideoGrid {
    data class Tier(val k8: Boolean, val fps: Int) { val label: String get() = (if (k8) "8K" else "4K") + fps }
    val TIERS = listOf(Tier(false, 30), Tier(false, 60), Tier(false, 120), Tier(false, 240),
        Tier(true, 30), Tier(true, 60), Tier(true, 120), Tier(true, 240))
    const val MARGIN = 1.1
    /**
     * dB on the lower third: honest encodes measured 29.7–33.9 (x264/x265 at half to full bitrate), a starved one 24.2 —
     * below this the export is broken and its speed doesn't count. (A quiet downscale scores 27–30 and isn't caught:
     * PSNR can't tell it from an honest encoder at a lower bitrate.)
     */
    const val MIN_PSNR = 26.0

    enum class Cell(val symbol: String) { YES("✓"), NO("–"), UNSUPPORTED("✗"), NOT_TESTED("·") }

    /** `fps4k` / `fps8k`: measured speed at that resolution; null = not tested there, 0 = the phone can't. */
    fun cell(tier: Tier, fps4k: Double?, fps8k: Double?): Cell {
        val f = (if (tier.k8) fps8k else fps4k) ?: return Cell.NOT_TESTED
        return when { f <= 0 -> Cell.UNSUPPORTED; f >= tier.fps * MARGIN -> Cell.YES; else -> Cell.NO }
    }

    fun row(fps4k: Double?, fps8k: Double?): List<Cell> = TIERS.map { cell(it, fps4k, fps8k) }

    fun playback(v: VideoResult, c: VideoCodec): List<Cell> =
        row(VideoClip.entries.firstOrNull { it.codec == c && !it.k8 }?.let { v.fps(it) },
            VideoClip.entries.firstOrNull { it.codec == c && it.k8 }?.let { v.fps(it) })

    fun export(e: ExportResult, c: VideoCodec): List<Cell> =
        row(ExportJob.entries.firstOrNull { it.codec == c && !it.k8 }?.let { e.counted(it) },
            ExportJob.entries.firstOrNull { it.codec == c && it.k8 }?.let { e.counted(it) })

    /** "4K30✓ 4K60✓ … 8K240–" — the report's line. */
    fun text(cells: List<Cell>): String = TIERS.zip(cells).joinToString(" ") { (t, c) -> t.label + c.symbol }
}

data class UxResult(
    val web: WebResult?,
    val scroll: ScrollResult?,
    val gallery: GalleryResult?,
    val photoMps: Double,            // photo editing: megapixels per second through the whole filter chain
    val video: VideoResult?,
    val export: ExportResult?,
    val qr: QrResult?,
    val webPoints: Int,
    val scrollPoints: Int,
    val galleryPoints: Int,
    val photoPoints: Int,
    val videoPoints: Int,
    val exportPoints: Int,
    val qrPoints: Int,
    val error: String? = null
) {
    val score: Int get() = UxScore.overall(listOf(webPoints, scrollPoints, galleryPoints, photoPoints, videoPoints, qrPoints), exportPoints)
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
    // PROVISIONAL until the Honor's report (version 4): 8K = a quarter of the 4K rate (same pixel rate); export ≈ a
    // hardware encoder's 4K rate with AV1 missing
    const val REF_VIDEO_8K = 74.5     // decoded 8K frames/s, mean of HEVC and AV1
    const val REF_EXPORT = 100.0      // encoded 4K frames/s, mean of H.264, HEVC, AV1
    const val REF_EXPORT_8K = 30.0    // encoded 8K frames/s, HEVC
    // zxing-cpp decodes/s → points: the Honor's 562/s (2026-10-26) → 6000
    const val QR_WEIGHT = 10.67

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

    /**
     * 4K counts three quarters (mean over the four codecs, so a codec the phone lacks costs a quarter of that), 8K one
     * quarter (mean of HEVC and AV1) — a phone without 8K loses a little, not half.
     */
    fun videoPoints(fps4k: List<Double>, fps8k: List<Double>): Int =
        (6000 * (0.75 * fps4k.sumOf { it.coerceAtLeast(0.0) } / (REF_VIDEO * fps4k.size) +
            0.25 * fps8k.sumOf { it.coerceAtLeast(0.0) } / (REF_VIDEO_8K * fps8k.size))).roundToInt()

    fun videoPoints(r: VideoResult): Int = videoPoints(VideoClip.entries.filter { !it.k8 }.map { r.fps(it) },
        VideoClip.entries.filter { it.k8 }.map { r.fps(it) })

    /** Same split: 4K (mean of the three encoders) three quarters, 8K HEVC one quarter; a failed quality check = 0. */
    fun exportPoints(fps4k: List<Double>, fps8k: Double): Int =
        (6000 * (0.75 * fps4k.sumOf { it.coerceAtLeast(0.0) } / (REF_EXPORT * fps4k.size) +
            0.25 * fps8k.coerceAtLeast(0.0) / REF_EXPORT_8K)).roundToInt()

    fun exportPoints(r: ExportResult): Int = exportPoints(ExportJob.entries.filter { !it.k8 }.map { r.counted(it) },
        r.counted(ExportJob.HEVC_8K))

    fun qrPoints(zxingRate: Double): Int = (zxingRate.coerceAtLeast(0.0) * QR_WEIGHT).roundToInt()

    /**
     * The six everyday tests' geometric mean counts six sevenths, the export one seventh — like ray tracing in the GPU
     * section: a phone with no 4K encoder (an emulator, a budget chip) loses a seventh, not the whole section.
     */
    fun overall(everyday: List<Int>, export: Int): Int {
        val g = overall(everyday)
        if (g <= 0) return 0
        return ((6.0 * g + export.coerceAtLeast(0)) / 7).roundToInt()
    }

    /** Geometric mean of the six everyday UX tests; 0 if any failed. */
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
    val npuProblem: String? = null,   // why it didn't
    val npuFiles: String? = null,     // the Hexagon DSP images on the phone, e.g. "V79 skel" (reported)
    val npuLog: String? = null,       // QNN / LiteRT's own error lines when a model failed on the NPU (reported)
    val npuBurst: Boolean = true      // false: BURST failed, the NPU ran at QNN's default clocks (reported)
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

// ── LLM test: a 3B language model through llama.cpp on the CPU and the GPU ────

/**
 * Where the model runs: the CPU (ggml's Arm dot-product kernels), or every layer on the GPU — llama.cpp's OpenCL
 * backend on Adreno GPUs, its Vulkan backend on the others (where neither runs it scores 0, and the CPU's result counts).
 */
enum class LlmAccel(val label: String, val seconds: Int) { CPU("CPU", 20), GPU("GPU", 16) }

/** One accelerator: tokens per second reading the 512-token prompt and writing the reply (0 = it couldn't run, `error` says why). */
data class LlmRun(val accel: LlmAccel, val prefill: Double, val decode: Double, val threads: Int = 0, val error: String? = null,
                  val startTempC: Float? = null,    // CPU-adjacent temperature when it began (reported)
                  val batchThreads: Int = 0)        // CPU threads reading the prompt (`threads` = writing the reply)

data class LlmResult(
    val runs: List<LlmRun>,
    val gpuName: String?,             // the GPU llama.cpp ran on (null when it didn't)
    val freeGb: Double,               // memory the app could still take when the test began (reported)
    val largestModel: Int,            // the largest common 4-bit model that fits in it, billions of parameters (reported)
    val error: String? = null         // why the test couldn't run at all (too little RAM, a 32-bit build, …)
) {
    fun run(a: LlmAccel): LlmRun? = runs.firstOrNull { it.accel == a }
    /** The faster of the CPU and the GPU at each — what a chat app on this phone would get. */
    val prefill: Double get() = runs.maxOfOrNull { it.prefill } ?: 0.0
    val decode: Double get() = runs.maxOfOrNull { it.decode } ?: 0.0
    val prefillPoints: Int get() = LlmScore.prefillPoints(prefill)
    val decodePoints: Int get() = LlmScore.decodePoints(decode)
    val score: Int get() = LlmScore.overall(prefill, decode)
}

object LlmScore {
    // Tokens per second worth 6000 points: the Snapdragon 8 Elite's faster path (Honor VKP-NX9, bench build,
    // 2026-10-26 — its Adreno 830 for both: CPU 23.5 / 6.8, GPU 362.7 / 17.23). Repeated in BenchmarkScoring.cs.
    const val REF_PREFILL = 362.7
    const val REF_DECODE = 17.23

    fun prefillPoints(tokensPerS: Double): Int = (6000 * tokensPerS.coerceAtLeast(0.0) / REF_PREFILL).roundToInt()
    fun decodePoints(tokensPerS: Double): Int = (6000 * tokensPerS.coerceAtLeast(0.0) / REF_DECODE).roundToInt()

    /** Geometric mean of prefill and decode — each the faster of the CPU and the GPU; 0 if either is 0. */
    fun overall(prefill: Double, decode: Double): Int {
        val p = prefillPoints(prefill); val d = decodePoints(decode)
        if (p <= 0 || d <= 0) return 0
        return exp((ln(p.toDouble()) + ln(d.toDouble())) / 2).roundToInt()
    }
}

object BenchmarkScore {
    /** Bump when a scene, the resolution or the scoring changes — scores compare only within a version. */
    const val VERSION = 4
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
