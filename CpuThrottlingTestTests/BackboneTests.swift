import XCTest
@testable import CpuThrottlingTest

/** 2.1's backbone: the server's run ids, the stored stress curve, and history saved by earlier versions still loading. */
final class BackboneTests: XCTestCase {

    func testRunIdHeader() {
        XCTAssertEqual(LeaderboardService.runId(fromHeader: "123"), 123)
        XCTAssertEqual(LeaderboardService.runId(fromHeader: " 7 "), 7)
        XCTAssertNil(LeaderboardService.runId(fromHeader: nil))          // an older server sends none
        XCTAssertNil(LeaderboardService.runId(fromHeader: ""))
        XCTAssertNil(LeaderboardService.runId(fromHeader: "0"))
        XCTAssertNil(LeaderboardService.runId(fromHeader: "-4"))
        XCTAssertNil(LeaderboardService.runId(fromHeader: "abc"))
    }

    func testDownsampleKeepsShortCurvesAndRounds() {
        XCTAssertEqual(StressCurve.downsample([99.94, 100.06, 87.25]), [99.9, 100.1, 87.3])
        XCTAssertEqual(StressCurve.downsample([]), [])
    }

    func testDownsampleToAtMost120EvenSlices() {
        let values = (0..<300).map { Double($0) }            // a 5-minute run, one stamp a second
        let d = StressCurve.downsample(values)
        XCTAssertEqual(d.count, 120)
        XCTAssertEqual(d.first, 0.5)                           // slices of 2 or 3 stamps (300/120): 0, 1
        XCTAssertEqual(d.last!, 298, accuracy: 0.01)            // 297, 298, 299
        XCTAssertEqual(StressCurve.downsample(Array(repeating: 50, count: 1800)), Array(repeating: 50, count: 120))
    }

    func testPercentOfBaseline() {
        XCTAssertEqual(StressCurve.percentOf([1000, 800], baseline: 1000), [100, 80])
        XCTAssertEqual(StressCurve.percentOf([1000], baseline: 0), [])
    }

    func testLeaderboardCurveFromFirstFiveSecondsMedian() {
        let ms = [1000, 2000, 3000, 4000, 5000, 6000]
        let c = StressCurve.fromScores(elapsedMs: ms, scores: [900, 1000, 1100, 1000, 500, 800])
        XCTAssertEqual(c[0], 90, accuracy: 0.001)              // median of the first 4 stamps (< 5 s) = 1000
        XCTAssertEqual(c[4], 50, accuracy: 0.001)
        // nothing that early: the first five stamps
        let late = StressCurve.fromScores(elapsedMs: [6000, 7000], scores: [200, 100])
        XCTAssertEqual(late.count, 2)
        XCTAssertEqual(late[0], 133.333, accuracy: 0.001)       // median 150
        XCTAssertEqual(late[1], 66.667, accuracy: 0.001)
        XCTAssertEqual(StressCurve.fromScores(elapsedMs: [], scores: []), [])
    }

    func testOnsetNeedsThreeStampsBelow90() {
        let ms = [1000, 2000, 3000, 4000, 5000, 6000]
        XCTAssertEqual(StressCurve.onsetSec(elapsedMs: ms, percent: [100, 89, 95, 88, 87, 86]), 4)
        XCTAssertNil(StressCurve.onsetSec(elapsedMs: ms, percent: [100, 89, 88, 95, 89, 89]))
        XCTAssertEqual(StressCurve.onsetText(83), "Throttled after 1:23")
        XCTAssertEqual(StressCurve.onsetText(nil), "Never throttled")
    }

    /// History written by 2.0.x has none of the new fields.
    func testOldRunRecordStillLoads() throws {
        let json = """
        [{"id":"8C5E3B49-9A4E-4D0D-B3F7-3C1D0E2A9B11","timestamp":1762000000,"deviceModel":"iPhone 17 Pro",
          "osVersion":"26.4","osBuild":"23E5","durationType":0,"threadingType":1,"capacity":92.5,"onsetSec":120,
          "validityFlags":8,"confidence":90}]
        """
        let runs = try JSONDecoder().decode([RunRecord].self, from: Data(json.utf8))
        XCTAssertEqual(runs.count, 1)
        XCTAssertNil(runs[0].serverId)
        XCTAssertNil(runs[0].curve)
        var r = runs[0]
        r.serverId = 42
        r.curve = [100, 95.5]
        let again = try JSONDecoder().decode(RunRecord.self, from: JSONEncoder().encode(r))
        XCTAssertEqual(again.serverId, 42)
        XCTAssertEqual(again.curve, [100, 95.5])
    }

    func testOldBenchmarkRecordStillLoads() throws {
        var r = BenchmarkRecord(BenchmarkResult())
        r.osBuild = nil; r.deviceModel = nil; r.serverId = nil
        var json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(r)) as! [String: Any]
        for k in ["osBuild", "deviceModel", "serverId"] { json.removeValue(forKey: k) }
        let old = try JSONDecoder().decode(BenchmarkRecord.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertNil(old.serverId)
        XCTAssertNil(old.osBuild)
        XCTAssertNil(old.deviceModel)
    }
}
