import CryptoKit
import Foundation
import Metal
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK SUBMISSION — the same payload and signed canonical string as the
// Android app (network/ApiService.kt BenchmarkPayload); the server re-scores
// every run from these raw results.
// ─────────────────────────────────────────────────────────────────────────────

/** One finished run as kept on the phone and submitted: raw results only (2 decimals — what is hashed). */
struct BenchmarkRecord: Codable, Identifiable {
    var id = UUID()
    var timestamp = Date()
    var version = BenchmarkScore.version
    var score = 0
    var gpuName = ""
    var test1Fps = 0.0, test1LowFps = 0.0, test2Fps = 0.0, test2LowFps = 0.0, test3Fps = 0.0, test3LowFps = 0.0
    var rayTracing = false
    var webLayout = 0.0, webCanvas = 0.0, webCss = 0.0, webSvg = 0.0, webJs = 0.0
    var qrRate = 0.0
    var referenceQrRate: Double?
    var cpuMath = 0.0, cpuData = 0.0, cpuCrypto = 0.0, cpuMulti = 0.0
    var scrollSpeed = 0.0, scrollDropped = 0.0
    var galleryJpeg = 0.0, galleryWebp = 0.0, galleryAvif = 0.0
    var photoEdit = 0.0
    var videoAvc = 0.0, videoHevc = 0.0, videoVp9 = 0.0, videoAv1 = 0.0
    var videoHardware = 0
    var ai = [Double](repeating: 0, count: 18)   // AiTest × AiAccel, test-major
    var aiNpuRuntime: String?
    var memRead = 0.0, memWrite = 0.0, memCopy = 0.0, memLatency = 0.0
    var storageSeqRead = 0.0, storageSeqWrite = 0.0, storageRandomRead = 0.0, storageRandomWrite = 0.0
    var storageDirect = false
    var dbInsert = 0.0, dbUpdate = 0.0, dbQuery = 0.0
    var validityFlags = 0
    var complete = false
    var submitted = false

    init(_ r: BenchmarkResult) {
        let q = BenchmarkScore.round2
        score = r.total
        gpuName = r.gpuName
        func gpu(_ t: GpuTest) -> GpuTestResult? { r.gpu.first { $0.test == t } }
        test1Fps = q(gpu(.lakeValley)?.avgFps ?? 0); test1LowFps = q(gpu(.lakeValley)?.lowFps ?? 0)
        test2Fps = q(gpu(.neonCity)?.avgFps ?? 0); test2LowFps = q(gpu(.neonCity)?.lowFps ?? 0)
        test3Fps = q(gpu(.neonCityRT)?.avgFps ?? 0); test3LowFps = q(gpu(.neonCityRT)?.lowFps ?? 0)
        rayTracing = gpu(.neonCityRT)?.supported ?? false
        if let ux = r.ux {
            webLayout = q(ux.web?.layout ?? 0); webCanvas = q(ux.web?.canvas ?? 0); webCss = q(ux.web?.css ?? 0)
            webSvg = q(ux.web?.svg ?? 0); webJs = q(ux.web?.js ?? 0)
            qrRate = q(ux.qr?.rate ?? 0); referenceQrRate = ux.qr?.referenceRate.map(q)
            scrollSpeed = q(ux.scroll?.speed ?? 0); scrollDropped = q(ux.scroll?.droppedPct ?? 0)
            galleryJpeg = q(ux.gallery?.jpeg ?? 0); galleryWebp = q(ux.gallery?.webp ?? 0); galleryAvif = q(ux.gallery?.avif ?? 0)
            photoEdit = q(ux.photoMps)
            videoAvc = q(ux.video?.fps(.avc) ?? 0); videoHevc = q(ux.video?.fps(.hevc) ?? 0)
            videoVp9 = q(ux.video?.fps(.vp9) ?? 0); videoAv1 = q(ux.video?.fps(.av1) ?? 0)
            videoHardware = ux.video?.hardwareMask ?? 0
        }
        if let cpu = r.cpu {
            cpuMath = q(cpu.value(.math)); cpuData = q(cpu.value(.data)); cpuCrypto = q(cpu.value(.crypto)); cpuMulti = q(cpu.value(.multi))
        }
        if let a = r.ai {
            ai = AiTest.allCases.flatMap { t in AiAccel.allCases.map { q(a.ips(t, $0)) } }
            aiNpuRuntime = a.npuRuntime
        }
        if let m = r.mem {
            memRead = q(m.ram.read); memWrite = q(m.ram.write); memCopy = q(m.ram.copy); memLatency = q(m.ram.latencyNs)
            storageSeqRead = q(m.storage.seqRead); storageSeqWrite = q(m.storage.seqWrite)
            storageRandomRead = q(m.storage.randomRead); storageRandomWrite = q(m.storage.randomWrite)
            storageDirect = m.storage.direct
            dbInsert = q(m.db.insert); dbUpdate = q(m.db.update); dbQuery = q(m.db.query)
        }
        validityFlags = r.validityFlags
        complete = r.isComplete
    }
}

/** The request body of POST /benchmark — the field names the server (and the Android app) use. */
struct BenchmarkPayload: Encodable {
    let sessionId: Int
    var hash: String
    let benchmarkVersion: Int
    let deviceManufacturer: String
    let deviceModel: String
    let os = 1   // OsPlatform.iOS
    let osVersion: String
    let osBuild: String?
    let socModel: String?
    let gpuName: String
    let test1Fps, test1LowFps, test2Fps, test2LowFps, test3Fps, test3LowFps: Double
    let rayTracing: Bool
    let webLayout, webCanvas, webCss, webSvg, webJs: Double
    let qrRate: Double
    let mlkitQrRate: Double?        // the reference scanner's rate (reported, not scored)
    let cpuMath, cpuData, cpuCrypto, cpuMulti: Double
    let scrollSpeed, scrollDropped: Double
    let galleryJpeg, galleryWebp, galleryAvif: Double
    let photoEdit: Double
    let videoAvc, videoHevc, videoVp9, videoAv1: Double
    let videoHardware: Int
    let aiClassifyCpu, aiClassifyGpu, aiClassifyNpu, aiDetectCpu, aiDetectGpu, aiDetectNpu: Double
    let aiSegmentCpu, aiSegmentGpu, aiSegmentNpu, aiUpscaleCpu, aiUpscaleGpu, aiUpscaleNpu: Double
    let aiDepthCpu, aiDepthGpu, aiDepthNpu, aiSoundCpu, aiSoundGpu, aiSoundNpu: Double
    let aiNpuRuntime: String?
    let memRead, memWrite, memCopy, memLatency: Double
    let storageSeqRead, storageSeqWrite, storageRandomRead, storageRandomWrite: Double
    let storageDirect: Bool
    let dbInsert, dbUpdate, dbQuery: Double
    let validityFlags: Int
    let appVersion: String?

    /** Exactly what the server re-derives and verifies (BenchmarkScoring.Canonical). */
    func canonical() -> String {
        func f2(_ v: Double) -> String { String(format: "%.2f", v) }
        let parts: [String] = ["bench", "\(benchmarkVersion)", deviceManufacturer, deviceModel, osVersion, gpuName,
            f2(test1Fps), f2(test1LowFps), f2(test2Fps), f2(test2LowFps), f2(test3Fps), f2(test3LowFps), rayTracing ? "1" : "0",
            f2(webLayout), f2(webCanvas), f2(webCss), f2(webSvg), f2(webJs), f2(qrRate), f2(mlkitQrRate ?? 0),
            f2(cpuMath), f2(cpuData), f2(cpuCrypto), f2(cpuMulti), f2(scrollSpeed), f2(scrollDropped),
            f2(galleryJpeg), f2(galleryWebp), f2(galleryAvif), f2(photoEdit), f2(videoAvc), f2(videoHevc), f2(videoVp9), f2(videoAv1),
            "\(videoHardware)",
            f2(aiClassifyCpu), f2(aiClassifyGpu), f2(aiClassifyNpu), f2(aiDetectCpu), f2(aiDetectGpu), f2(aiDetectNpu),
            f2(aiSegmentCpu), f2(aiSegmentGpu), f2(aiSegmentNpu), f2(aiUpscaleCpu), f2(aiUpscaleGpu), f2(aiUpscaleNpu),
            f2(aiDepthCpu), f2(aiDepthGpu), f2(aiDepthNpu), f2(aiSoundCpu), f2(aiSoundGpu), f2(aiSoundNpu), aiNpuRuntime ?? "",
            f2(memRead), f2(memWrite), f2(memCopy), f2(memLatency), f2(storageSeqRead), f2(storageSeqWrite),
            f2(storageRandomRead), f2(storageRandomWrite), storageDirect ? "1" : "0", f2(dbInsert), f2(dbUpdate), f2(dbQuery),
            "\(validityFlags)"]
        return parts.joined(separator: "|")
    }
}

struct BenchmarkSubmitResponse: Decodable { let id: Int; let score: Int; let rank: Int }

enum BenchmarkUpload {
    static let baseURL = URL(string: "https://thapi.gtgroup.dev")!

    static func submit(_ r: BenchmarkRecord) async throws -> BenchmarkSubmitResponse {
        let session = try await LeaderboardService.shared.createSession()
        let a = r.ai
        var payload = BenchmarkPayload(
            sessionId: session.id, hash: "", benchmarkVersion: r.version, deviceManufacturer: "Apple",
            deviceModel: LeaderboardService.shared.getDeviceModelName(), osVersion: UIDevice.current.systemVersion,
            osBuild: StressEngine.osBuild(), socModel: chipName(), gpuName: r.gpuName,
            test1Fps: r.test1Fps, test1LowFps: r.test1LowFps, test2Fps: r.test2Fps, test2LowFps: r.test2LowFps,
            test3Fps: r.test3Fps, test3LowFps: r.test3LowFps, rayTracing: r.rayTracing,
            webLayout: r.webLayout, webCanvas: r.webCanvas, webCss: r.webCss, webSvg: r.webSvg, webJs: r.webJs,
            qrRate: r.qrRate, mlkitQrRate: r.referenceQrRate,
            cpuMath: r.cpuMath, cpuData: r.cpuData, cpuCrypto: r.cpuCrypto, cpuMulti: r.cpuMulti,
            scrollSpeed: r.scrollSpeed, scrollDropped: r.scrollDropped,
            galleryJpeg: r.galleryJpeg, galleryWebp: r.galleryWebp, galleryAvif: r.galleryAvif, photoEdit: r.photoEdit,
            videoAvc: r.videoAvc, videoHevc: r.videoHevc, videoVp9: r.videoVp9, videoAv1: r.videoAv1, videoHardware: r.videoHardware,
            aiClassifyCpu: a[0], aiClassifyGpu: a[1], aiClassifyNpu: a[2], aiDetectCpu: a[3], aiDetectGpu: a[4], aiDetectNpu: a[5],
            aiSegmentCpu: a[6], aiSegmentGpu: a[7], aiSegmentNpu: a[8], aiUpscaleCpu: a[9], aiUpscaleGpu: a[10], aiUpscaleNpu: a[11],
            aiDepthCpu: a[12], aiDepthGpu: a[13], aiDepthNpu: a[14], aiSoundCpu: a[15], aiSoundGpu: a[16], aiSoundNpu: a[17],
            aiNpuRuntime: r.aiNpuRuntime,
            memRead: r.memRead, memWrite: r.memWrite, memCopy: r.memCopy, memLatency: r.memLatency,
            storageSeqRead: r.storageSeqRead, storageSeqWrite: r.storageSeqWrite,
            storageRandomRead: r.storageRandomRead, storageRandomWrite: r.storageRandomWrite, storageDirect: r.storageDirect,
            dbInsert: r.dbInsert, dbUpdate: r.dbUpdate, dbQuery: r.dbQuery,
            validityFlags: r.validityFlags,
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String)
        let mac = HMAC<SHA256>.authenticationCode(for: Data(payload.canonical().utf8), using: SymmetricKey(data: Data(session.encryptionKey.utf8)))
        payload.hash = Data(mac).base64EncodedString()
        var request = URLRequest(url: baseURL.appendingPathComponent("benchmark"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(payload)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let why = String(data: data, encoding: .utf8) ?? ""
            throw NSError(domain: "BenchmarkUpload", code: (response as? HTTPURLResponse)?.statusCode ?? 0,
                          userInfo: [NSLocalizedDescriptionKey: "Server rejected the run \(why)"])
        }
        return try JSONDecoder().decode(BenchmarkSubmitResponse.self, from: data)
    }

    /** The phone's chip, from its model identifier. */
    static func chipName() -> String? {
        var info = utsname()
        uname(&info)
        let id = withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        let chips: [(String, String)] = [
            ("iPhone12,", "A13 Bionic"), ("iPhone13,", "A14 Bionic"), ("iPhone14,2", "A15 Bionic"), ("iPhone14,3", "A15 Bionic"),
            ("iPhone14,4", "A15 Bionic"), ("iPhone14,5", "A15 Bionic"), ("iPhone14,6", "A15 Bionic"), ("iPhone14,7", "A15 Bionic"),
            ("iPhone14,8", "A15 Bionic"), ("iPhone15,2", "A16 Bionic"), ("iPhone15,3", "A16 Bionic"), ("iPhone15,4", "A16 Bionic"),
            ("iPhone15,5", "A16 Bionic"), ("iPhone16,", "A17 Pro"), ("iPhone17,1", "A18 Pro"), ("iPhone17,2", "A18 Pro"),
            ("iPhone17,3", "A18"), ("iPhone17,4", "A18"), ("iPhone17,5", "A18"), ("iPhone18,1", "A19 Pro"), ("iPhone18,2", "A19 Pro"),
            ("iPhone18,3", "A19"), ("iPhone18,4", "A19"), ("iPhone18,5", "A19"), ("iPhone18,6", "A19 Pro")]
        return chips.first { id.hasPrefix($0.0) }?.1
    }

    /** The GPU's name as Metal reports it, e.g. "Apple A18 Pro GPU". */
    static func gpuName() -> String { MTLCreateSystemDefaultDevice()?.name ?? "" }
}

/** The phone's benchmark runs (Documents/benchmark_history.json) — the latest 30. */
final class BenchmarkHistoryStore: ObservableObject {
    static let shared = BenchmarkHistoryStore()
    @Published private(set) var records: [BenchmarkRecord] = []
    private let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("benchmark_history.json")

    private init() {
        records = (try? JSONDecoder().decode([BenchmarkRecord].self, from: Data(contentsOf: url))) ?? []
    }

    var best: BenchmarkRecord? { records.filter { $0.version == BenchmarkScore.version && $0.complete }.max { $0.score < $1.score } }

    func add(_ r: BenchmarkRecord) { records = Array((records + [r]).suffix(30)); save() }

    func markSubmitted(_ id: UUID) {
        if let i = records.firstIndex(where: { $0.id == id }) { records[i].submitted = true; save() }
    }

    private func save() { try? JSONEncoder().encode(records).write(to: url, options: .atomic) }
}
