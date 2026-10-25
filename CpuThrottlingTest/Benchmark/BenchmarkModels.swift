import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK — the same five sections and the same scoring as the Android app
// (engine/Benchmark.kt) and the server (BenchmarkScoring.cs): CPU + MEM + GPU
// + UX + AI, every test 6000 points on the Snapdragon 8 Elite reference phone.
// Change a number here only together with those two.
// ─────────────────────────────────────────────────────────────────────────────

enum BenchmarkScore {
    /// Bump when a scene, the resolution or the scoring changes — scores compare only within a version.
    static let version = 2
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
    // geometric-mean kernel ops/s worth 6000 points (provisional — see Benchmark.kt)
    static let refMath = 1172.0, refData = 632.0, refCrypto = 595.0, refMulti = 6091.0

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
    case web = 1, scroll, gallery, photo, video, qr

    var number: Int { rawValue }
    var title: String { ["", "WEB BROWSING", "WEB SCROLLING", "PHOTO GALLERY", "PHOTO EDITING", "VIDEO PLAYBACK", "QR SCANNING"][rawValue] }
    var seconds: Int { [0, 36, 20, 16, 15, 20, 24][rawValue] }
    /// The test's web page (bundled `web/` folder), for the browser tests.
    var page: String? { [nil, "web/bench.html", "web/scroll.html", "web/gallery.html", nil, nil, nil][rawValue] }
}

struct WebResult { let layout, canvas, css, svg, js: Double }
struct ScrollResult { let speed, droppedPct: Double }
struct GalleryResult { let jpeg, webp, avif: Double }

/// The clips of UX Test 5: the same second of Neon City, 3840×2160 at 60 fps.
enum VideoCodec: Int, CaseIterable {
    case avc, hevc, vp9, av1
    var label: String { ["H.264", "HEVC 10-BIT", "VP9", "AV1 10-BIT"][rawValue] }
    var short: String { ["H.264", "HEVC", "VP9", "AV1"][rawValue] }
    var asset: String { ["media/clip_avc.mp4", "media/clip_hevc10.mp4", "media/clip_vp9.webm", "media/clip_av1.mp4"][rawValue] }
}

struct VideoCodecResult { let codec: VideoCodec; let fps: Double; let decoder: String?; let hardware: Bool; var error: String? = nil }

struct VideoResult {
    let codecs: [VideoCodecResult]
    func fps(_ c: VideoCodec) -> Double { codecs.first { $0.codec == c }?.fps ?? 0 }
    /// Bit per codec decoded in hardware — reported, not scored.
    var hardwareMask: Int { codecs.filter { $0.hardware && $0.fps > 0 }.reduce(0) { $0 | (1 << $1.codec.rawValue) } }
}

/// ZXing's (zxing-cpp's) rate is scored; the platform's own scanner (Vision here, ML Kit on Android) is reported.
struct QrResult { let rate: Double; let decoded, missed: Int; let referenceRate: Double?; let referenceName: String; var referenceError: String? = nil }

struct UxResult {
    var web: WebResult?
    var scroll: ScrollResult?
    var gallery: GalleryResult?
    var photoMps: Double
    var video: VideoResult?
    var qr: QrResult?
    var error: String? = nil

    var webPoints: Int { web.map(UxScore.webPoints) ?? 0 }
    var scrollPoints: Int { UxScore.scrollPoints(scroll?.speed ?? 0) }
    var galleryPoints: Int { gallery.map(UxScore.galleryPoints) ?? 0 }
    var photoPoints: Int { UxScore.photoPoints(photoMps) }
    var videoPoints: Int { video.map(UxScore.videoPoints) ?? 0 }
    var qrPoints: Int { UxScore.qrPoints(qr?.rate ?? 0) }
    var score: Int { UxScore.overall([webPoints, scrollPoints, galleryPoints, photoPoints, videoPoints, qrPoints]) }
}

enum UxScore {
    static let refLayout = 1320.0, refCanvas = 2730.0, refCss = 1980.0, refSvg = 3130.0, refJs = 2160.0
    static let refScroll = 66_600.0
    static let refJpeg = 122.0, refWebp = 99.0, refAvif = 103.0
    static let refPhoto = 397.0
    static let refVideo = 298.0
    static let qrWeight = 13.6          // zxing-cpp decodes/s → points (440/s → 6000, as on Android)

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
    /// Mean over the four codecs, so a codec the phone lacks costs a quarter instead of everything.
    static func videoPoints(_ r: VideoResult) -> Int {
        Int((6000 * VideoCodec.allCases.map { max(r.fps($0), 0) }.reduce(0, +) / (refVideo * Double(VideoCodec.allCases.count))).rounded())
    }
    static func qrPoints(_ rate: Double) -> Int { Int((max(rate, 0) * qrWeight).rounded()) }
    static func overall(_ points: [Int]) -> Int { geomean(points) }
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

// MARK: - The whole run

struct BenchmarkResult {
    var cpu: CpuResult?
    var mem: MemResult?
    var gpu: [GpuTestResult] = []
    var gpuName = ""
    var rayTracingProblem: String? = nil
    var ux: UxResult?
    var ai: AiResult?
    var validityFlags = 0
    var startCpuShare: Float = 1
    var quietWaitSec = 0

    var cpuScore: Int { cpu?.score ?? 0 }
    var memScore: Int { mem?.score ?? 0 }
    var gpuScore: Int { BenchmarkScore.overall(gpu) }
    var uxScore: Int { ux?.score ?? 0 }
    var aiScore: Int { ai?.score ?? 0 }
    var total: Int { cpuScore + memScore + gpuScore + uxScore + aiScore }
    var isVerified: Bool { validityFlags == 0 }
    /// Every section ran — what the server needs to accept the run.
    var isComplete: Bool { cpu != nil && mem != nil && gpu.count == GpuTest.allCases.count && ux != nil && ai != nil }
}

/// Geometric mean; 0 if any part failed.
func geomean(_ points: [Int]) -> Int {
    guard !points.isEmpty, points.allSatisfy({ $0 > 0 }) else { return 0 }
    return Int(exp(points.map { log(Double($0)) }.reduce(0, +) / Double(points.count)).rounded())
}
