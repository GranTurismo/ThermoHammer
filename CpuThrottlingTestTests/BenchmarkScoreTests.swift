import XCTest
@testable import CpuThrottlingTest

/** Scoring version 5: the references every platform repeats, and the five sections (Android's BenchmarkScoreTest). */
final class BenchmarkScoreTests: XCTestCase {

    /**
     * The reference phone (Honor VKP-NX9, Snapdragon 8 Elite, Performance mode): the median of every measurement over its
     * 11 runs of 2026-11-23 scores 9000 (refPoints) on each test — the same numbers pin Benchmark.kt, BenchmarkScoring.cs and
     * BenchmarkModels.cs, so the four copies of the references can't drift apart.
     */
    func testTheReferencePhonesMediansScore9000OnEveryTest() {
        func near(_ points: Int, _ what: String) { XCTAssertTrue((8955...9045).contains(points), "\(what) scored \(points)") }
        let cpu: [(CpuTest, Double)] = [(.math, 3039.31), (.data, 1533.32), (.crypto, 1184.12), (.multi, 8884.08)]
        for (t, v) in cpu { near(CpuScore.points(t, v), t.title) }
        near(MemScore.ram(63.51, 28.26, 19.71, 133.94), "RAM")
        near(MemScore.storage(1780.0, 1309.0, 24722.0, 13375.0), "storage")
        near(MemScore.db(15700.0, 389000.0, 43800.0), "database")
        near(BenchmarkScore.points(.lakeValley, fps: 129.85), "Lake Valley")
        near(BenchmarkScore.points(.neonCity, fps: 28.98), "Neon City")
        near(BenchmarkScore.points(.neonCityRT, fps: 14.12), "Neon City RT")
        near(UxScore.webPoints(WebResult(layout: 3061.0, canvas: 4818.0, css: 3061.0, svg: 4898.0, js: 4001.7)), "web")
        near(UxScore.scrollPoints(66558.0), "scroll")
        near(UxScore.galleryPoints(GalleryResult(jpeg: 229.26, webp: 167.78, avif: 173.55)), "gallery")
        near(UxScore.photoPoints(349.95), "photo")
        let clips: [(VideoClip, Double)] = [(.avc4k, 269.0), (.hevc4k, 331.4), (.vp94k, 309.6), (.av14k, 274.2), (.hevc8k, 91.13), (.av18k, 75.02)]
        near(UxScore.videoPoints(VideoResult(codecs: clips.map { VideoCodecResult(clip: $0.0, fps: $0.1, decoder: nil, hardware: true) })), "video")
        near(UxScore.qrPoints(1171.51), "QR")
        // export: the reference phone has two of the three 4K encoders and no 8K one — two thirds of the 4K part
        let jobs: [(ExportJob, Double)] = [(.avc4k, 136.3), (.hevc4k, 136.3), (.av14k, 0), (.hevc8k, 0)]
        XCTAssertEqual(UxScore.exportPoints(ExportResult(jobs: jobs.map {
            ExportJobResult(job: $0.0, fps: $0.1, psnr: $0.1 > 0 ? 40 : 0, encoder: nil, hardware: $0.1 > 0)
        })), 4500)
        near(AiScore.accel(.cpu, [543.63, 28.67, 7.73, 4.59, 64.54, 3967.59]), "AI CPU")
        near(AiScore.accel(.gpu, [280.7, 36.7, 16.6, 38.8, 74.2, 582.0]), "AI GPU")
        near(AiScore.accel(.npu, [1725.64, 485.03, 214.56, 689.25, 986.01, 2154.38]), "AI NPU")
        XCTAssertEqual(BenchmarkScore.version, 5)
        XCTAssertEqual(BenchmarkScore.refPoints, 9000)            // 1.5× the 6,000-point scale: phones total about what they did in version 4
    }

    func testTheTotalIsTheFiveSectionsWithNoLlm() {
        var r = BenchmarkResult()
        r.ai = AiResult(runs: AiTest.allCases.flatMap { t in [AiAccel.cpu, .gpu].map { AiRun(test: t, accel: $0, ips: AiScore.ref(t, $0)) } },
                        npuRuntime: nil)
        XCTAssertEqual(r.aiScore, 9000)
        XCTAssertEqual(r.total, 9000)                                                 // CPU + MEM + GPU + UX + AI: nothing else adds
        XCTAssertFalse(PhoneHealth.parts(r).keys.contains { $0.hasPrefix("llm.") })   // nothing for Phone Health to compare
        XCTAssertFalse(r.isComplete)                                                  // CPU, MEM, GPU and UX didn't run
    }

    func testAiCountsTheCpuAndTheGpuHalfEachTheNpuNever() {
        func result(npu: Bool, gpu: Bool = true) -> AiResult {
            AiResult(runs: AiTest.allCases.flatMap { t in AiAccel.allCases.compactMap { a -> AiRun? in
                if a == .npu && !npu { return nil }
                return AiRun(test: t, accel: a, ips: a == .gpu && !gpu ? 0 : AiScore.ref(t, a))
            } }, npuRuntime: npu ? "Core ML · Neural Engine" : nil)
        }
        XCTAssertEqual(result(npu: true).score, 9000)
        XCTAssertEqual(result(npu: false).score, 9000)                 // no NPU: nothing lost
        XCTAssertEqual(result(npu: true, gpu: false).score, 4500)      // no GPU: its half
        XCTAssertEqual(result(npu: false).shown, [.cpu, .gpu])         // a Store build's run shows no NPU column
        XCTAssertEqual(result(npu: true).shown, [.cpu, .gpu, .npu])    // a -npuReport run does, report only
        var r = BenchmarkResult()
        r.ai = result(npu: true)
        XCTAssertNil(PhoneHealth.parts(r)["ai.npu"])
    }
}
