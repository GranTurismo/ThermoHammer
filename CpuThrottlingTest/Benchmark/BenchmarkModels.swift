import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK — the same six sections and the same scoring as the Android app
// (engine/Benchmark.kt) and the server (BenchmarkScoring.cs): CPU + MEM + GPU
// + UX + AI + LLM, every test 6000 points on the Snapdragon 8 Elite reference phone.
// Change a number here only together with those two.
// ─────────────────────────────────────────────────────────────────────────────

enum BenchmarkScore {
    /// Bump when a scene, the resolution or the scoring changes — scores compare only within a version.
    static let version = 4
    /// Frames that start before this (s) are driver/shader warm-up and not scored.
    static let warmupS: Float = 2
    static let windowS: Float = 5

    static func points(_ test: GpuTest, fps: Double) -> Int { Int((fps * test.weight).rounded()) }

    /// fps are kept to 2 decimals — what is hashed, submitted and re-scored by the server.
    static func round2(_ v: Double) -> Double { (v * 100).rounded() / 100 }

    /// Raster (geometric mean of Tests 1–2) counts two thirds, ray tracing (Test 3; 0 where unsupported) one third.
    static func overall(_ t1: Int, _ t2: Int, _ t3: Int) -> Int {
        guard t1 > 0, t2 > 0 else { return 0 }
        let raster = exp((log(Double(t1)) + log(Double(t2))) / 2)
        return Int(((2 * raster + Double(max(t3, 0))) / 3).rounded())
    }

    static func overall(_ results: [GpuTestResult]) -> Int {
        func pts(_ t: GpuTest) -> Int { results.first { $0.test == t }?.points ?? 0 }
        return overall(pts(.lakeValley), pts(.neonCity), pts(.neonCityRT))
    }

    /// Summarises one GPU test from its frame log: `starts[i]` = timeline time the frame began, `durNs[i]` its
    /// render time. Nil when too few frames were scored.
    static func summarize(_ test: GpuTest, starts: [Float], durNs: [Int64], startTempC: Float?, endTempC: Float?) -> GpuTestResult? {
        let idx = starts.indices.filter { starts[$0] >= warmupS }
        guard idx.count >= 10 else { return nil }
        let totalNs = idx.reduce(Int64(0)) { $0 + durNs[$1] }
        let avg = round2(Double(idx.count) * 1e9 / Double(totalNs))
        let sorted = idx.map { durNs[$0] }.sorted()
        let p99 = sorted[Int((Double(sorted.count - 1) * 0.99).rounded())]
        let low = round2(1e9 / Double(p99))
        var windows: [Int: (Int, Int64)] = [:]
        for i in idx {
            let w = Int((starts[i] - warmupS) / windowS)
            let (n, ns) = windows[w] ?? (0, 0)
            windows[w] = (n + 1, ns + durNs[i])
        }
        let lastFull = Int((Float(test.seconds) - warmupS) / windowS) - 1
        let rates = windows.filter { $0.key <= lastFull && $0.value.0 > 0 }.map { Double($0.value.0) * 1e9 / Double($0.value.1) }
        let stability = rates.count >= 2 ? Float(rates.min()! / rates.max()! * 100) : 100
        return GpuTestResult(test: test, supported: true, avgFps: avg, lowFps: low, frames: idx.count, stabilityPct: stability,
                             startTempC: startTempC, endTempC: endTempC, points: points(test, fps: avg))
    }
}

/// The run's validity bits (the same values as Android's ValidityFlag; 0 = clean).
enum BenchFlag {
    static let warmStarted = 1 << 0
    static let interference = 1 << 1
    static let powerEvent = 1 << 2
    static let envCapped = 1 << 5
    static let cooldownTimeout = 1 << 7
}

// MARK: - GPU

/// One GPU test: its scene, timeline length and the weight that turns fps into points (129.5 / 26.3 / 13.4 fps → 6000).
enum GpuTest: Int, CaseIterable {
    case lakeValley = 1, neonCity = 2, neonCityRT = 3

    var number: Int { rawValue }
    var title: String { ["", "LAKE VALLEY", "NEON CITY", "NEON CITY RT"][rawValue] }
    var seconds: Int { [0, 50, 60, 60][rawValue] }
    var weight: Double { [0, 46.3, 228.0, 448.0][rawValue] }
    var rayTracing: Bool { self == .neonCityRT }
}

struct GpuTestResult {
    let test: GpuTest
    var supported = true          // false: the phone can't run this test (no ray tracing) — 0 points
    let avgFps: Double
    let lowFps: Double
    let frames: Int
    let stabilityPct: Float
    let startTempC: Float?
    let endTempC: Float?
    let points: Int

    static func unsupported(_ test: GpuTest) -> GpuTestResult {
        GpuTestResult(test: test, supported: false, avgFps: 0, lowFps: 0, frames: 0, stabilityPct: 0, startTempC: nil, endTempC: nil, points: 0)
    }
}

// MARK: - CPU

enum CpuTest: Int, CaseIterable {
    case math = 1, data, crypto, multi

    var number: Int { rawValue }
    var title: String { ["", "MATH", "JSON & XML", "COMPRESSION & CRYPTO", "MULTI-CORE"][rawValue] }
    var seconds: Int { [0, 10, 9, 9, 10][rawValue] }
    var multiCore: Bool { self == .multi }
    /// BenchCore kernel ids (bench_core.h), in order.
    var kernelIds: [Int32] {
        switch self {
        case .math: return [Int32(BC_MATMUL), Int32(BC_FFT), Int32(BC_MANDELBROT), Int32(BC_SIEVE), Int32(BC_RSA), Int32(BC_NBODY)]
        case .data: return [Int32(BC_JSON), Int32(BC_XML)]
        case .crypto: return [Int32(BC_ZLIB), Int32(BC_AES_GCM), Int32(BC_SHA256)]
        case .multi: return [Int32(BC_MATMUL), Int32(BC_FFT), Int32(BC_MANDELBROT), Int32(BC_JSON), Int32(BC_ZLIB), Int32(BC_SHA256)]
        }
    }
    var labels: [String] { kernelIds.map { String(cString: bc_kernel_name($0)) } }
    /// Timed seconds per kernel; tests take ~8 s each.
    var secondsPerKernel: Double { 8.0 / Double(kernelIds.count) }
}

/// Kernel throughputs of one test (operations per second, all threads together for MULTI) and their geometric mean.
struct CpuTestRun {
    let test: CpuTest
    let rates: [(String, Double)]
    var value: Double {
        guard !rates.isEmpty, rates.allSatisfy({ $0.1 > 0 }) else { return 0 }
        return exp(rates.map { log($0.1) }.reduce(0, +) / Double(rates.count))
    }
}

struct CpuResult {
    let runs: [CpuTestRun]
    func value(_ t: CpuTest) -> Double { runs.first { $0.test == t }?.value ?? 0 }
    func points(_ t: CpuTest) -> Int { CpuScore.points(t, value(t)) }
    var score: Int { CpuScore.overall(CpuTest.allCases.map { points($0) }) }
}

enum CpuScore {
    // geometric-mean kernel ops/s worth 6000 points (the Honor, 2026-10-26 — see Benchmark.kt)
    static let refMath = 1469.0, refData = 647.0, refCrypto = 580.0, refMulti = 5003.0

    static func ref(_ t: CpuTest) -> Double {
        switch t { case .math: return refMath; case .data: return refData; case .crypto: return refCrypto; case .multi: return refMulti }
    }
    static func points(_ t: CpuTest, _ value: Double) -> Int { Int((6000 * max(value, 0) / ref(t)).rounded()) }
    static func overall(_ points: [Int]) -> Int { geomean(points) }
}

// MARK: - MEM

enum MemTest: Int, CaseIterable {
    case ram = 1, storage, database

    var number: Int { rawValue }
    var title: String { ["", "RAM", "STORAGE", "DATABASE"][rawValue] }
    var seconds: Int { [0, 10, 12, 8][rawValue] }
    var labels: [String] {
        switch self {
        case .ram: return ["READ", "WRITE", "COPY", "LATENCY"]
        case .storage: return ["SEQ WRITE", "SEQ READ", "RANDOM READ", "RANDOM WRITE"]
        case .database: return ["INSERT", "UPDATE", "QUERY"]
        }
    }
}

struct RamResult { let read, write, copy, latencyNs: Double }
struct StorageResult { let seqRead, seqWrite, randomRead, randomWrite: Double; let direct: Bool; let fileMb: Int }
struct DbResult { let insert, update, query: Double }

struct MemResult {
    let ram: RamResult
    let storage: StorageResult
    let db: DbResult
    var ramPoints: Int { MemScore.ram(ram.read, ram.write, ram.copy, ram.latencyNs) }
    var storagePoints: Int { MemScore.storage(storage.seqRead, storage.seqWrite, storage.randomRead, storage.randomWrite) }
    var dbPoints: Int { MemScore.db(db.insert, db.update, db.query) }
    var score: Int { MemScore.overall([ramPoints, storagePoints, dbPoints]) }
}

enum MemScore {
    static let refRead = 57.8, refWrite = 28.4, refCopy = 18.7, refLatency = 245.0           // GB/s, ns
    static let refSeqRead = 1750.0, refSeqWrite = 1300.0, refRandomRead = 29_000.0, refRandomWrite = 13_400.0
    static let refInsert = 9700.0, refUpdate = 36_000.0, refQuery = 20_300.0

    /// 6000 × the geometric mean of value ÷ reference; 0 if any part is 0.
    private static func geo(_ ratios: [Double]) -> Int {
        guard ratios.allSatisfy({ $0 > 0 }) else { return 0 }
        return Int((6000 * exp(ratios.map(log).reduce(0, +) / Double(ratios.count))).rounded())
    }
    static func ram(_ read: Double, _ write: Double, _ copy: Double, _ latencyNs: Double) -> Int {
        latencyNs > 0 ? geo([read / refRead, write / refWrite, copy / refCopy, refLatency / latencyNs]) : 0
    }
    static func storage(_ sr: Double, _ sw: Double, _ rr: Double, _ rw: Double) -> Int {
        geo([sr / refSeqRead, sw / refSeqWrite, rr / refRandomRead, rw / refRandomWrite])
    }
    static func db(_ insert: Double, _ update: Double, _ query: Double) -> Int {
        geo([insert / refInsert, update / refUpdate, query / refQuery])
    }
    static func overall(_ points: [Int]) -> Int { geomean(points) }
}

// MARK: - UX

enum UxTest: Int, CaseIterable {
    case web = 1, scroll, gallery, photo, video, export, qr

    var number: Int { rawValue }
    var title: String { ["", "WEB BROWSING", "WEB SCROLLING", "PHOTO GALLERY", "PHOTO EDITING", "VIDEO PLAYBACK", "VIDEO EXPORT", "QR SCANNING"][rawValue] }
    var seconds: Int { [0, 36, 20, 16, 15, 28, 26, 24][rawValue] }
    /// The test's web page (bundled `web/` folder), for the browser tests.
    var page: String? { [nil, "web/bench.html", "web/scroll.html", "web/gallery.html", nil, nil, nil, nil][rawValue] }
}

struct WebResult { let layout, canvas, css, svg, js: Double }
struct ScrollResult { let speed, droppedPct: Double }
struct GalleryResult { let jpeg, webp, avif: Double }

enum VideoCodec: Int, CaseIterable {
    case avc, hevc, vp9, av1
    var label: String { ["H.264", "HEVC 10-BIT", "VP9", "AV1 10-BIT"][rawValue] }
    var short: String { ["H.264", "HEVC", "VP9", "AV1"][rawValue] }
}

/// The clips of UX Test 5 (media/, BenchmarkScene/build_ux_media.sh): Neon City, a different three seconds in each —
/// 4K60 in every codec, 8K30 in HEVC and AV1. Android's VideoClip, in the same order.
enum VideoClip: Int, CaseIterable {
    case avc4k, hevc4k, vp94k, av14k, hevc8k, av18k
    var codec: VideoCodec { [.avc, .hevc, .vp9, .av1, .hevc, .av1][rawValue] }
    var k8: Bool { rawValue >= 4 }
    var asset: String { ["media/clip_avc.mp4", "media/clip_hevc10.mp4", "media/clip_vp9.webm", "media/clip_av1.mp4",
                         "media/clip8k_hevc10.mp4", "media/clip8k_av1.mp4"][rawValue] }
    var width: Int { k8 ? 7680 : 3840 }
    var height: Int { k8 ? 4320 : 2160 }
    var fps: Int { k8 ? 30 : 60 }
    var mbps: Int { [30, 18, 16, 10, 40, 24][rawValue] }
    var res: String { k8 ? "8K" : "4K" }
    var label: String { "\(codec.label) \(res)" }
}

struct VideoCodecResult {
    let clip: VideoClip; let fps: Double; let decoder: String?; let hardware: Bool; var error: String? = nil
    var codec: VideoCodec { clip.codec }
}

struct VideoResult {
    let codecs: [VideoCodecResult]
    func fps(_ c: VideoClip) -> Double { codecs.first { $0.clip == c }?.fps ?? 0 }
    /// Bit per clip decoded in hardware — reported, not scored.
    var hardwareMask: Int { codecs.filter { $0.hardware && $0.fps > 0 }.reduce(0) { $0 | (1 << $1.clip.rawValue) } }
}

/// The jobs of UX Test 6: the H.264 4K clip graded and encoded at a fixed bitrate — 4K60 H.264, HEVC, AV1 and 8K30 HEVC
/// (from the 8K HEVC clip). Android's ExportJob, in the same order.
enum ExportJob: Int, CaseIterable {
    case avc4k, hevc4k, av14k, hevc8k
    var codec: VideoCodec { [.avc, .hevc, .av1, .hevc][rawValue] }
    var k8: Bool { self == .hevc8k }
    var label: String { ["H.264", "HEVC", "AV1", "HEVC"][rawValue] }
    var source: VideoClip { k8 ? .hevc8k : .avc4k }
    var width: Int { k8 ? 7680 : 3840 }
    var height: Int { k8 ? 4320 : 2160 }
    var fps: Int { k8 ? 30 : 60 }
    var mbps: Int { [40, 30, 20, 80][rawValue] }
    var res: String { k8 ? "8K" : "4K" }
    /// The timeline exported start to finish: 8 s of 4K60, 4 s of 8K30 (Android's ExportJob.seconds).
    var seconds: Int { k8 ? 4 : 8 }
    var frames: Int { seconds * fps }
    var fileName: String { "Neon City · Night Run · \(res) \(label).mp4" }
}

/// UX Test 6 on the stage: an editor's export dialog — the job in hand, its progress, the render queue (Android's ExportStage).
struct ExportStage: Equatable {
    enum State { case done, active, pending, skipped }
    struct Item: Equatable { let job: ExportJob; let state: State; var fps = 0.0; var psnr = 0.0 }
    let job: ExportJob
    let encoder: String?
    let hardware: Bool
    let framesDone: Int
    let elapsedS: Double
    let fps: Double
    let step: String            // ENCODING · FINISHING THE FILE · CHECKING QUALITY · or why it can't run
    let queue: [Item]
    var progress: Double { min(1, max(0, Double(framesDone) / Double(job.frames))) }
    var leftS: Double? { fps > 0 ? Double(max(0, job.frames - framesDone)) / fps : nil }
    /// "×1.2 REAL TIME" — two decimals below 1, so a slow export doesn't read ×0.0.
    var speedText: String {
        guard fps > 0 else { return "×— REAL TIME" }
        let x = fps / Double(job.fps)
        return String(format: x < 1 ? "×%.2f REAL TIME" : "×%.1f REAL TIME", x)
    }
    /// "~2.4 S LEFT · " while encoding — or, when it won't finish in time, where it'll be cut off.
    var timeLeftText: String {
        guard step == "ENCODING", let left = leftS else { return "" }
        return elapsedS + left > ExportBench.capS ? String(format: "STOPS AT %.0f S · ", ExportBench.capS) : String(format: "~%.1f S LEFT · ", left)
    }
}

/// One export: frames encoded per second and the export's quality — luma PSNR (dB) of sampled frames' lower third
/// against what went in. Below VideoGrid.minPsnr the speed doesn't count.
struct ExportJobResult {
    let job: ExportJob; let fps: Double; let psnr: Double; let encoder: String?; let hardware: Bool; var error: String? = nil
    var counted: Double { psnr >= VideoGrid.minPsnr ? fps : 0 }
}

struct ExportResult {
    let jobs: [ExportJobResult]
    func job(_ j: ExportJob) -> ExportJobResult? { jobs.first { $0.job == j } }
    func fps(_ j: ExportJob) -> Double { job(j)?.fps ?? 0 }
    func psnr(_ j: ExportJob) -> Double { job(j)?.psnr ?? 0 }
    func counted(_ j: ExportJob) -> Double { job(j)?.counted ?? 0 }
    /// Bit per job encoded in hardware — reported, not scored.
    var hardwareMask: Int { jobs.filter { $0.hardware && $0.fps > 0 }.reduce(0) { $0 | (1 << $1.job.rawValue) } }
}

/// The stage's player overlay during UX Test 5 (Android's VideoOsd).
struct VideoOsd: Equatable {
    let format: String          // e.g. "HEVC 10-BIT · 3840×2160 · 60 FPS"
    let mbps: Int
    let positionS: Double       // where in the clip
    let durationS: Double       // the clip's length
    let speed: Double           // × real time
}

/// What a speed means in playback terms (Android's VideoGrid): real time at a tier = at least 10 % faster than its
/// frame rate. Derived from the measured speed, so a faster future chip fills in the higher tiers by itself.
enum VideoGrid {
    struct Tier { let k8: Bool; let fps: Int; var label: String { (k8 ? "8K" : "4K") + "\(fps)" } }
    static let tiers = [Tier(k8: false, fps: 30), Tier(k8: false, fps: 60), Tier(k8: false, fps: 120), Tier(k8: false, fps: 240),
                        Tier(k8: true, fps: 30), Tier(k8: true, fps: 60), Tier(k8: true, fps: 120), Tier(k8: true, fps: 240)]
    static let margin = 1.1
    /// dB on the lower third: honest encodes 29.7–33.9, a starved one 24.2 — below this the export is broken.
    static let minPsnr = 26.0

    enum Cell: String { case yes = "✓", no = "–", unsupported = "✗", notTested = "·" }

    static func cell(_ t: Tier, _ fps4k: Double?, _ fps8k: Double?) -> Cell {
        guard let f = t.k8 ? fps8k : fps4k else { return .notTested }
        return f <= 0 ? .unsupported : f >= Double(t.fps) * margin ? .yes : .no
    }
    static func row(_ fps4k: Double?, _ fps8k: Double?) -> [Cell] { tiers.map { cell($0, fps4k, fps8k) } }
    static func playback(_ v: VideoResult, _ c: VideoCodec) -> [Cell] {
        row(VideoClip.allCases.first { $0.codec == c && !$0.k8 }.map { v.fps($0) },
            VideoClip.allCases.first { $0.codec == c && $0.k8 }.map { v.fps($0) })
    }
    static func export(_ e: ExportResult, _ c: VideoCodec) -> [Cell] {
        row(ExportJob.allCases.first { $0.codec == c && !$0.k8 }.map { e.counted($0) },
            ExportJob.allCases.first { $0.codec == c && $0.k8 }.map { e.counted($0) })
    }
    static func text(_ cells: [Cell]) -> String { zip(tiers, cells).map { $0.label + $1.rawValue }.joined(separator: " ") }
}

/// ZXing's (zxing-cpp's) rate is scored; the platform's own scanner (Vision here, ML Kit on Android) is reported.
struct QrResult { let rate: Double; let decoded, missed: Int; let referenceRate: Double?; let referenceName: String; var referenceError: String? = nil }

struct UxResult {
    var web: WebResult?
    var scroll: ScrollResult?
    var gallery: GalleryResult?
    var photoMps: Double
    var video: VideoResult?
    var export: ExportResult?
    var qr: QrResult?
    var error: String? = nil

    var webPoints: Int { web.map(UxScore.webPoints) ?? 0 }
    var scrollPoints: Int { UxScore.scrollPoints(scroll?.speed ?? 0) }
    var galleryPoints: Int { gallery.map(UxScore.galleryPoints) ?? 0 }
    var photoPoints: Int { UxScore.photoPoints(photoMps) }
    var videoPoints: Int { video.map(UxScore.videoPoints) ?? 0 }
    var exportPoints: Int { export.map(UxScore.exportPoints) ?? 0 }
    var qrPoints: Int { UxScore.qrPoints(qr?.rate ?? 0) }
    var score: Int { UxScore.overall([webPoints, scrollPoints, galleryPoints, photoPoints, videoPoints, qrPoints], export: exportPoints) }
}

enum UxScore {
    static let refLayout = 1320.0, refCanvas = 2730.0, refCss = 1980.0, refSvg = 3130.0, refJs = 2160.0
    static let refScroll = 66_600.0
    static let refJpeg = 122.0, refWebp = 99.0, refAvif = 103.0
    static let refPhoto = 397.0
    static let refVideo = 298.0
    // PROVISIONAL until the Honor's version-4 report (as on Android and the server)
    static let refVideo8k = 74.5        // 8K frames/s, mean of HEVC and AV1
    static let refExport = 100.0        // encoded 4K frames/s, mean of H.264, HEVC, AV1
    static let refExport8k = 30.0       // encoded 8K frames/s, HEVC
    static let qrWeight = 10.67         // zxing-cpp decodes/s → points (562/s → 6000, as on Android)

    static func webPoints(_ r: WebResult) -> Int {
        let parts = [r.layout / refLayout, r.canvas / refCanvas, r.css / refCss, r.svg / refSvg, r.js / refJs]
        guard parts.allSatisfy({ $0 > 0 }) else { return 0 }
        return Int((6000 * exp(parts.map(log).reduce(0, +) / Double(parts.count))).rounded())
    }
    static func scrollPoints(_ speed: Double) -> Int { Int((6000 * max(speed, 0) / refScroll).rounded()) }
    /// Mean over the three formats, so a browser without AVIF loses a third instead of everything.
    static func galleryPoints(_ r: GalleryResult) -> Int {
        Int((6000 * (max(r.jpeg, 0) / refJpeg + max(r.webp, 0) / refWebp + max(r.avif, 0) / refAvif) / 3).rounded())
    }
    static func photoPoints(_ mps: Double) -> Int { Int((6000 * max(mps, 0) / refPhoto).rounded()) }
    /// 4K three quarters (mean of the four codecs), 8K one quarter (mean of HEVC and AV1).
    static func videoPoints(_ r: VideoResult) -> Int {
        let k4 = VideoClip.allCases.filter { !$0.k8 }.map { max(r.fps($0), 0) }
        let k8 = VideoClip.allCases.filter { $0.k8 }.map { max(r.fps($0), 0) }
        return Int((6000 * (0.75 * k4.reduce(0, +) / (refVideo * Double(k4.count)) +
                            0.25 * k8.reduce(0, +) / (refVideo8k * Double(k8.count)))).rounded())
    }
    /// The same split: 4K (mean of H.264, HEVC, AV1) three quarters, 8K HEVC one quarter; a failed quality check = 0.
    static func exportPoints(_ r: ExportResult) -> Int {
        let k4 = ExportJob.allCases.filter { !$0.k8 }.map { max(r.counted($0), 0) }
        return Int((6000 * (0.75 * k4.reduce(0, +) / (refExport * Double(k4.count)) +
                            0.25 * max(r.counted(.hevc8k), 0) / refExport8k)).rounded())
    }
    static func qrPoints(_ rate: Double) -> Int { Int((max(rate, 0) * qrWeight).rounded()) }
    static func overall(_ points: [Int]) -> Int { geomean(points) }
    /// The six everyday tests' geometric mean six sevenths, the export one seventh: no 4K encoder costs a seventh, not the section.
    static func overall(_ everyday: [Int], export: Int) -> Int {
        let g = geomean(everyday)
        return g <= 0 ? 0 : Int(((6 * Double(g) + Double(max(export, 0))) / 7).rounded())
    }
}

// MARK: - AI

enum AiAccel: Int, CaseIterable {
    case cpu, gpu, npu
    var label: String { ["CPU", "GPU", "NPU"][rawValue] }
}

enum AiTest: Int, CaseIterable {
    case classify = 1, detect, segment, upscale, depth, sound

    var number: Int { rawValue }
    var title: String { ["", "IMAGE CLASSIFICATION", "OBJECT DETECTION", "STREET SEGMENTATION", "SUPER RESOLUTION", "DEPTH ESTIMATION", "SOUND RECOGNITION"][rawValue] }
    var model: String { ["", "MobileNet-v2 · 224×224", "YOLOX · 640×640", "FFNet-40S · Cityscapes · 2048×1024", "Real-ESRGAN ×4 · 128 → 512", "MiDaS v2 · 256×256", "YAMNet · 0.96 s of audio"][rawValue] }
    var seconds: Int { [0, 7, 8, 8, 8, 8, 7][rawValue] }
    /// The model's file: `ai/<asset>.tflite` (CPU, GPU) and `<asset>.mlmodelc` (Neural Engine).
    var asset: String { ["", "mobilenet_v2", "yolox", "ffnet_40s", "real_esrgan_general_x4v3", "midas", "yamnet"][rawValue] }
}

struct AiRun { let test: AiTest; let accel: AiAccel; let ips: Double; var error: String? = nil }

struct AiResult {
    let runs: [AiRun]
    let npuRuntime: String?
    var npuProblem: String? = nil
    func ips(_ t: AiTest, _ a: AiAccel) -> Double { runs.first { $0.test == t && $0.accel == a }?.ips ?? 0 }
    func points(_ t: AiTest, _ a: AiAccel) -> Int { AiScore.points(t, a, ips(t, a)) }
    func accelScore(_ a: AiAccel) -> Int { AiScore.accel(a, AiTest.allCases.map { ips($0, a) }) }
    var score: Int { AiScore.overall(accelScore(.cpu), accelScore(.gpu), accelScore(.npu)) }
}

enum AiScore {
    static let refCpu = [330.0, 18.2, 4.71, 2.65, 42.0, 2890.0]
    static let refGpu = [658.0, 85.5, 33.1, 92.8, 182.0, 1470.0]
    static let refNpu = [1690.0, 473.0, 229.0, 774.0, 993.0, 2100.0]

    static func ref(_ t: AiTest, _ a: AiAccel) -> Double {
        let i = t.rawValue - 1
        switch a { case .cpu: return refCpu[i]; case .gpu: return refGpu[i]; case .npu: return refNpu[i] }
    }
    static func points(_ t: AiTest, _ a: AiAccel, _ ips: Double) -> Int { Int((6000 * max(ips, 0) / ref(t, a)).rounded()) }
    /// One accelerator: mean of its six model points — a model it can't run costs a sixth.
    static func accel(_ a: AiAccel, _ ips: [Double]) -> Int {
        Int((AiTest.allCases.enumerated().map { 6000 * max(ips[$0.offset], 0) / ref($0.element, a) }.reduce(0, +) / Double(AiTest.allCases.count)).rounded())
    }
    /// Each accelerator a third; a missing one scores 0 there.
    static func overall(_ cpu: Int, _ gpu: Int, _ npu: Int) -> Int { Int((Double(max(cpu, 0) + max(gpu, 0) + max(npu, 0)) / 3).rounded()) }
}

// MARK: - LLM

/** Where the model runs: the CPU (ggml's Arm dot-product kernels) or every layer on the GPU (llama.cpp's Metal backend). */
enum LlmAccel: Int, CaseIterable {
    case cpu, gpu
    var label: String { ["CPU", "GPU"][rawValue] }
    var seconds: Int { [20, 16][rawValue] }
}

/** One accelerator: tokens/s reading the 512-token prompt and writing the reply (0 = it couldn't run, `error` says why). */
struct LlmRun { let accel: LlmAccel; let prefill: Double; let decode: Double; var threads = 0; var error: String? = nil; var batchThreads = 0 }

struct LlmResult {
    let runs: [LlmRun]
    let gpuName: String?              // the GPU llama.cpp ran on (nil when it didn't)
    let freeGb: Double                // memory iOS still let the app take when the test began (reported)
    let largestModel: Int             // the largest common 4-bit model that fits in it, billions of parameters (reported)
    var error: String? = nil          // why the test couldn't run at all
    func run(_ a: LlmAccel) -> LlmRun? { runs.first { $0.accel == a } }
    /// The faster of the CPU and the GPU at each — what a chat app on this phone would get.
    var prefill: Double { runs.map(\.prefill).max() ?? 0 }
    var decode: Double { runs.map(\.decode).max() ?? 0 }
    var prefillPoints: Int { LlmScore.prefillPoints(prefill) }
    var decodePoints: Int { LlmScore.decodePoints(decode) }
    var score: Int { LlmScore.overall(prefill, decode) }
}

enum LlmScore {
    // tokens/s worth 6000 points — the Snapdragon 8 Elite's faster path, its GPU (see Benchmark.kt)
    static let refPrefill = 362.7, refDecode = 17.23

    static func prefillPoints(_ tokensPerS: Double) -> Int { Int((6000 * max(tokensPerS, 0) / refPrefill).rounded()) }
    static func decodePoints(_ tokensPerS: Double) -> Int { Int((6000 * max(tokensPerS, 0) / refDecode).rounded()) }
    /// Geometric mean of prefill and decode — each the faster of the CPU and the GPU; 0 if either is 0.
    static func overall(_ prefill: Double, _ decode: Double) -> Int { geomean([prefillPoints(prefill), decodePoints(decode)]) }
}

// MARK: - The whole run

struct BenchmarkResult {
    var cpu: CpuResult?
    var mem: MemResult?
    var gpu: [GpuTestResult] = []
    var gpuName = ""
    var rayTracingProblem: String? = nil
    var ux: UxResult?
    var ai: AiResult?
    var llm: LlmResult?
    var validityFlags = 0
    var startCpuShare: Float = 1
    var quietWaitSec = 0

    var cpuScore: Int { cpu?.score ?? 0 }
    var memScore: Int { mem?.score ?? 0 }
    var gpuScore: Int { BenchmarkScore.overall(gpu) }
    var uxScore: Int { ux?.score ?? 0 }
    var aiScore: Int { ai?.score ?? 0 }
    var llmScore: Int { llm?.score ?? 0 }
    var total: Int { cpuScore + memScore + gpuScore + uxScore + aiScore + llmScore }
    var isVerified: Bool { validityFlags == 0 }
    /// Every section ran — what the server needs to accept the run.
    var isComplete: Bool { cpu != nil && mem != nil && gpu.count == GpuTest.allCases.count && ux != nil && ai != nil && llm != nil }
}

/// Geometric mean; 0 if any part failed.
func geomean(_ points: [Int]) -> Int {
    guard !points.isEmpty, points.allSatisfy({ $0 > 0 }) else { return 0 }
    return Int(exp(points.map { log(Double($0)) }.reduce(0, +) / Double(points.count)).rounded())
}
