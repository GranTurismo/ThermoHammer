import XCTest
@testable import CpuThrottlingTest

/** Scoring version 5: the references every platform repeats, and the LLM's rule B (Android's BenchmarkScoreTest). */
final class BenchmarkScoreTests: XCTestCase {

    /**
     * The reference phone (Honor VKP-NX9, Snapdragon 8 Elite, Performance mode): the median of every measurement over its
     * 11 runs of 2026-11-23 scores 6000 on each test — the same numbers pin Benchmark.kt, BenchmarkScoring.cs and
     * BenchmarkModels.cs, so the four copies of the references can't drift apart.
     */
    func testTheReferencePhonesMediansScore6000OnEveryTest() {
        func near(_ points: Int, _ what: String) { XCTAssertTrue((5970...6030).contains(points), "\(what) scored \(points)") }
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
        })), 3000)
        near(AiScore.accel(.cpu, [543.63, 28.67, 7.73, 4.59, 64.54, 3967.59]), "AI CPU")
        near(AiScore.accel(.gpu, [280.7, 36.7, 16.6, 38.8, 74.2, 582.0]), "AI GPU")
        near(AiScore.accel(.npu, [1725.64, 485.03, 214.56, 689.25, 986.01, 2154.38]), "AI NPU")
        near(LlmScore.engine(.cpu, 160.0, 55.0), "LLM CPU")
        near(LlmScore.engine(.gpu, 890.0, 32.0), "LLM GPU")
        near(LlmScore.npu(5450.0, 52.0), "LLM NPU")
        XCTAssertEqual(BenchmarkScore.version, 5)
    }

    func testLlmVersion5CpuAndGpuHalfEachTheNpuNotScored() {
        // the reference phone on both engines: 6000 each, 6000 in all; a report-only NPU run never counts
        let reference = LlmResult(runs: [LlmRun(accel: .cpu, prefill: LlmScore.refCpuPrefill, decode: LlmScore.refCpuDecode, threads: 6),
                                         LlmRun(accel: .gpu, prefill: LlmScore.refGpuPrefill, decode: LlmScore.refGpuDecode)],
                                  gpuName: "Apple A19 Pro GPU", freeGb: 9, largestModel: 8,
                                  npu: LlmNpuRun(prefill: LlmScore.refNpuPrefill, decode: LlmScore.refNpuDecode, right: true, model: "gemma-3-1b"))
        XCTAssertEqual(reference.points(.cpu), 6000)
        XCTAssertEqual(reference.points(.gpu), 6000)
        XCTAssertEqual(reference.npuPoints, 6000)                                  // what it would score: shown, not counted
        XCTAssertEqual(reference.score, 6000)
        XCTAssertEqual(reference.prefill, LlmScore.refGpuPrefill)                  // the fastest scored engine at each phase (shown)
        XCTAssertEqual(reference.prefillPoints, 6000)
        // an engine is the geometric mean of its two phases: twice the prompt rate and half the reply rate is still 6000
        XCTAssertEqual(LlmScore.engine(.cpu, 2 * LlmScore.refCpuPrefill, LlmScore.refCpuDecode / 2), 6000)
        // no usable GPU: the CPU's half only
        let cpuOnly = LlmResult(runs: [LlmRun(accel: .cpu, prefill: LlmScore.refCpuPrefill / 2, decode: LlmScore.refCpuDecode / 2),
                                       LlmRun(accel: .gpu, prefill: 0, decode: 0, error: "llama.cpp found no Metal GPU it can run on")],
                                gpuName: nil, freeGb: 5, largestModel: 4,
                                npu: LlmNpuRun(prefill: 0, decode: 0, right: false, error: "no Neural Engine LLM engine in this build yet"))
        XCTAssertEqual(cpuOnly.points(.cpu), 3000)
        XCTAssertEqual(cpuOnly.npuPoints, 0)
        XCTAssertNil(cpuOnly.npuCounted)
        XCTAssertEqual(cpuOnly.score, 1500)                                        // (3000 + 0) / 2
        // the NPU never moves the score: a wrong answer, a fast run or none at all
        var wrong = reference
        wrong.npu = LlmNpuRun(prefill: 2 * LlmScore.refNpuPrefill, decode: 2 * LlmScore.refNpuDecode, right: false)
        XCTAssertEqual(wrong.npuPoints, 0)
        XCTAssertEqual(wrong.score, 6000)
        var fast = reference
        fast.npu = LlmNpuRun(prefill: 4 * LlmScore.refNpuPrefill, decode: 4 * LlmScore.refNpuDecode, right: true)
        XCTAssertEqual(fast.score, 6000)
        var none = reference
        none.npu = nil
        XCTAssertEqual(none.score, 6000)
        XCTAssertEqual(LlmScore.engine(.gpu, 120, 0), 0)                           // no reply: 0
        var total = BenchmarkResult()
        total.llm = cpuOnly
        XCTAssertEqual(total.total, 1500)
    }

    func testPhoneHealthNamesEachLlmEngine() {
        var r = BenchmarkResult()
        r.llm = LlmResult(runs: [LlmRun(accel: .cpu, prefill: LlmScore.refCpuPrefill, decode: LlmScore.refCpuDecode)],
                          gpuName: nil, freeGb: 5, largestModel: 4)
        let parts = PhoneHealth.parts(r)
        XCTAssertEqual(parts["llm.cpu"], 6000)
        XCTAssertEqual(parts["llm.gpu"], 0)
        XCTAssertNil(parts["llm.npu"])                                             // not scored, not a part
        XCTAssertNil(parts["llm.prefill"])
    }
}
