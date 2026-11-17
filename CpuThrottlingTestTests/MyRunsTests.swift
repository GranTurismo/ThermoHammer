import XCTest
@testable import CpuThrottlingTest

/** MY RUNS: the same rules as Android's MyRunsTest (and Windows), plus iOS's "complete runs only" for a submit. */
final class MyRunsTests: XCTestCase {

    private let phone = "iPhone 17 Pro"
    private var clock = 0.0

    private func run(_ total: Int, model: String? = "iPhone 17 Pro", flags: Int = 0, submitted: Bool = false, version: Int = 4,
                     serverId: Int? = nil, complete: Bool = true) -> BenchmarkRecord {
        clock += 1
        var r = BenchmarkRecord(BenchmarkResult())
        r.timestamp = Date(timeIntervalSince1970: clock * 1000)
        r.version = version; r.score = total; r.deviceModel = model; r.validityFlags = flags
        r.submitted = submitted; r.serverId = serverId; r.complete = complete
        return r
    }

    private func build(_ all: [BenchmarkRecord]) -> MyRuns.History { MyRuns.build(all, deviceModel: phone, version: 4) }

    private func ids(_ rows: [MyRuns.Row]) -> [UUID] { rows.map(\.record.id) }

    func testNoRuns() {
        let h = build([])
        XCTAssertTrue(h.rows.isEmpty)
        XCTAssertNil(h.best)
        XCTAssertNil(h.latest)
        XCTAssertEqual(MyRuns.footnotes(h), ["Your runs are kept only on this phone: the last 100."])
    }

    func testNewestFirstWithTheChangeFromTheRunBefore() {
        let a = run(30000), b = run(33000), c = run(31350)
        let h = build([a, b, c])
        XCTAssertEqual(ids(h.rows), [c.id, b.id, a.id])
        XCTAssertEqual(h.rows[0].changePct!, -5.0, accuracy: 1e-9)
        XCTAssertEqual(h.rows[1].changePct!, 10.0, accuracy: 1e-9)
        XCTAssertNil(h.rows[2].changePct)
        XCTAssertEqual(ids(h.chronological), [a.id, b.id, c.id])
        XCTAssertEqual(h.latest?.id, c.id)
    }

    func testStoredOutOfOrderStillListsByTime() {
        let a = run(30000), b = run(31000)
        XCTAssertEqual(ids(build([b, a]).rows), [b.id, a.id])
    }

    func testBestIsTheHighestAndTheEarlierOnATie() {
        let a = run(30000), b = run(32000), c = run(32000)
        let h = build([a, b, c])
        XCTAssertEqual(h.best?.id, b.id)
        XCTAssertEqual(h.chronological.map(\.isBest), [false, true, false])
    }

    func testAnUnverifiedRunCanBeTheBestButIsCountedApart() {
        let a = run(30000), b = run(34000, flags: 4)
        let h = build([a, b])
        XCTAssertEqual(h.best?.id, b.id)
        XCTAssertEqual(h.verified, 1)
    }

    func testStatusAndWhatCanBeSubmitted() {
        let sent = run(30000, submitted: true, serverId: 12)
        let unsent = run(30000)
        let unverified = run(30000, flags: 1)
        let unverifiedButSent = run(30000, flags: 1, submitted: true)   // an older app's submit: what the server took
        let h = build([sent, unsent, unverified, unverifiedButSent]).chronological
        XCTAssertEqual(h.map(\.status), [.submitted, .notSubmitted, .unverified, .submitted])
        XCTAssertEqual(h.map(\.canSubmit), [false, true, false, false])
    }

    func testAnIncompleteRunIsListedButNotSubmitted() {
        // iOS sends complete runs only (the result screen and the board do the same)
        let partial = run(5000, complete: false)
        let h = build([partial])
        XCTAssertEqual(h.rows.count, 1)
        XCTAssertEqual(h.rows[0].status, .notSubmitted)
        XCTAssertFalse(h.rows[0].canSubmit)
    }

    func testOlderVersionsAndOtherPhonesAreLeftOutAndCounted() {
        let old = run(9000, version: 3)
        let other = run(40000, model: "iPhone 15")
        let unnamed = run(30000, model: nil)              // saved before 2.1: made on this phone
        let mine = run(31000)
        let h = build([old, other, unnamed, mine])
        XCTAssertEqual(ids(h.rows), [mine.id, unnamed.id])
        XCTAssertEqual(h.olderVersion, 1)
        XCTAssertEqual(h.otherPhone, 1)
        XCTAssertEqual(h.best?.id, mine.id)               // the other phone's 40,000 is not this phone's best
        XCTAssertEqual(MyRuns.footnotes(h), [
            "1 earlier run used an older version of the benchmark, so it isn't shown: its score doesn't compare.",
            "1 run was made on another phone (restored from a backup), so it isn't shown.",
            "Your runs are kept only on this phone: the last 100."])
    }

    func testPluralFootnotesAndTheMacWording() {
        let h = build([run(1, version: 3), run(2, version: 2), run(3, model: "A"), run(4, model: "B")])
        XCTAssertEqual(MyRuns.footnotes(h, device: "Mac"), [
            "2 earlier runs used an older version of the benchmark, so they aren't shown: their scores don't compare.",
            "2 runs were made on another Mac (restored from a backup), so they aren't shown.",
            "Your runs are kept only on this Mac: the last 100."])
    }

    func testAZeroScoreBeforeHasNoChange() {
        XCTAssertNil(build([run(0), run(30000)]).rows[0].changePct)
    }

    func testChangeText() {
        XCTAssertEqual(MyRuns.changeText(2.06), "+2.1%")
        XCTAssertEqual(MyRuns.changeText(-3.96), "\u{2212}4.0%")
        XCTAssertEqual(MyRuns.changeText(0.04), "\u{00B1}0.0%")
        XCTAssertEqual(MyRuns.changeText(-0.04), "\u{00B1}0.0%")
    }

    func testTheStoreKeepsWhatTheScreenPromises() { XCTAssertEqual(MyRuns.kept, 100) }

    func testValidityNoteWithoutAKnownReason() {
        XCTAssertEqual(BenchmarkResultView.validityNote(0), "Unverified.")
        XCTAssertEqual(BenchmarkResultView.validityNote(BenchFlag.powerEvent), "Unverified: charger connected during the run.")
    }
}
