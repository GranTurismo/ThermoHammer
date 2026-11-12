import XCTest
@testable import CpuThrottlingTest

/** "Did the update slow my phone?" for benchmark runs: the stress card's rules on the benchmark history. */
final class BenchmarkUpdateImpactTests: XCTestCase {

    private let model = "iPhone 17 Pro"

    /// A verified, complete run; `cpu` scales the CPU tests (1 = the reference phone, 6000 points).
    private func run(_ day: Double, _ os: String, _ build: String?, score: Int, cpu: Double = 1, model: String? = "iPhone 17 Pro",
                     flags: Int = 0, complete: Bool = true) -> BenchmarkRecord {
        var r = BenchmarkRecord(BenchmarkResult())
        r.timestamp = Date(timeIntervalSince1970: day * 86_400)
        r.osVersion = os; r.osBuild = build; r.deviceModel = model
        r.score = score; r.complete = complete; r.validityFlags = flags
        r.cpuMath = CpuScore.refMath * cpu; r.cpuData = CpuScore.refData * cpu
        r.cpuCrypto = CpuScore.refCrypto * cpu; r.cpuMulti = CpuScore.refMulti * cpu
        return r
    }

    private func insight(_ h: [BenchmarkRecord], _ os: String = "26.4", _ build: String? = "23E7") -> BenchmarkUpdateImpact.Insight? {
        BenchmarkUpdateImpact.insight(history: h, deviceModel: model, osVersion: os, osBuild: build)
    }

    func testNothingWithoutHistoryOrWithoutAnUpdate() {
        XCTAssertNil(insight([]))
        XCTAssertNil(insight([run(1, "26.4", "23E7", score: 40_000)]))
    }

    func testPromptAfterAnUpdate() {
        guard case .prompt(let p)? = insight([run(1, "26.3", "23D5", score: 40_000), run(2, "26.3", "23D5", score: 41_000)]) else {
            return XCTFail("expected UPDATE DETECTED")
        }
        XCTAssertEqual(p.last.score, 41_000)
        XCTAssertEqual(p.key, "26.3|23D5->26.4|23E7")
        XCTAssertEqual(BenchmarkUpdateImpact.transition(os: "iOS", from: ("26.3", "23D5"), to: ("26.4", "23E7")), "iOS 26.3 → 26.4")
        XCTAssertEqual(BenchmarkUpdateImpact.transition(os: "iOS", from: ("26.4", "23E5"), to: ("26.4", "23E7")), "iOS 26.4 (23E5) → 26.4 (23E7)")
    }

    func testMissingBuildCountsAsTheSameOs() {
        // a run saved before 2.1 has no build: on the same version it is no update
        XCTAssertNil(insight([run(1, "26.4", nil, score: 40_000)]))
        // a different build of the same version is one
        guard case .prompt? = insight([run(1, "26.4", "23E5", score: 40_000)]) else { return XCTFail("a new build is an update") }
    }

    func testOnlyVerifiedCompleteRunsOfThisModel() {
        let h = [run(1, "26.3", "23D5", score: 40_000, flags: BenchFlag.powerEvent),
                 run(2, "26.3", "23D5", score: 40_000, complete: false),
                 run(3, "26.3", "23D5", score: 40_000, model: "iPhone 15")]
        XCTAssertNil(insight(h))
        // no model recorded (before 2.1): this phone's
        guard case .prompt? = insight([run(1, "26.3", "23D5", score: 40_000, model: nil)]) else { return XCTFail("expected a prompt") }
    }

    func testComparisonMediansAndVerdict() {
        let h = [run(0, "26.3", "23D5", score: 10_000),                           // the oldest of four: only the newest 3 count
                 run(1, "26.3", "23D5", score: 39_000), run(2, "26.3", "23D5", score: 40_000), run(3, "26.3", "23D5", score: 41_000),
                 run(5, "26.4", "23E7", score: 37_000, cpu: 0.9), run(6, "26.4", "23E7", score: 36_000, cpu: 0.9)]
        guard case .comparison(let c)? = insight(h) else { return XCTFail("expected a comparison") }
        XCTAssertEqual(c.before.runs.count, 3)
        XCTAssertEqual(c.before.median, 40_000)
        XCTAssertEqual(c.after.median, 36_500)
        XCTAssertEqual(c.delta, 36_500.0 / 40_000 - 1, accuracy: 1e-9)
        XCTAssertEqual(c.verdict, .slower)
        XCTAssertEqual(BenchmarkUpdateImpact.deltaText(c.delta), "9% slower than before the update")
        XCTAssertEqual(c.sections.first { $0.key == "cpu" }?.delta ?? 0, -0.1, accuracy: 0.001)
        XCTAssertEqual(c.sections.map(\.key), ["cpu"])                           // the only section these runs have
    }

    func testUnder3PercentIsNoClearChange() {
        let h = [run(1, "26.3", "23D5", score: 40_000), run(2, "26.4", "23E7", score: 41_000)]
        guard case .comparison(let c)? = insight(h) else { return XCTFail("expected a comparison") }
        XCTAssertEqual(c.verdict, .noClearChange)
        let faster = [run(1, "26.3", "23D5", score: 40_000), run(2, "26.4", "23E7", score: 41_300)]
        guard case .comparison(let f)? = insight(faster) else { return XCTFail("expected a comparison") }
        XCTAssertEqual(f.verdict, .faster)
    }

    func testANewerRunMakesANewerComparison() {
        let h = [run(1, "26.3", "23D5", score: 40_000), run(2, "26.4", "23E7", score: 36_000)]
        let first = insight(h)!.dismissKey
        let second = insight(h + [run(3, "26.4", "23E7", score: 36_500)])!.dismissKey
        XCTAssertNotEqual(first, second)
        XCTAssertTrue(first.hasPrefix("result:26.3|23D5->26.4|23E7:"))
    }

    func testSectionScoresFromRawNumbers() {
        let s = run(1, "26.4", nil, score: 0).sectionScores
        XCTAssertEqual(s["cpu"], 6000)                                           // the reference phone's numbers
        XCTAssertNil(s["gpu"])                                                   // not run: no section
        XCTAssertNil(s["llm"])
    }
}
