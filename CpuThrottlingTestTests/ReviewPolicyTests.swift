import XCTest
@testable import CpuThrottlingTest

/** The store-rating ask: only after a verified, complete run, with a gap between asks and a cap — Android's and Windows' rule. */
final class ReviewPolicyTests: XCTestCase {
    private let day: TimeInterval = 24 * 60 * 60
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func ask(last: TimeInterval? = nil, asks: Int = 0, verified: Bool = true, complete: Bool = true) -> Bool {
        ReviewPolicy.shouldAsk(now: now, lastAskedAt: last.map { now.addingTimeInterval(-$0) }, asks: asks,
                               verified: verified, complete: complete)
    }

    func testAsksAfterTheFirstGoodRun() { XCTAssertTrue(ask()) }

    func testNeverAfterAnUnverifiedRun() { XCTAssertFalse(ask(verified: false)) }

    func testNeverAfterARunWithAPartMissing() { XCTAssertFalse(ask(complete: false)) }

    func testWaitsOutTheGap() {
        XCTAssertFalse(ask(last: 1 * day, asks: 1))
        XCTAssertFalse(ask(last: 119 * day, asks: 1))
        XCTAssertFalse(ask(last: ReviewPolicy.minGap - 1, asks: 1))
    }

    func testAsksAgainOnceTheGapIsOver() {
        XCTAssertTrue(ask(last: ReviewPolicy.minGap, asks: 1))
        XCTAssertTrue(ask(last: 200 * day, asks: 2))
    }

    func testStopsAfterThreeAsks() {
        XCTAssertFalse(ask(last: 400 * day, asks: ReviewPolicy.maxAsks))
        XCTAssertFalse(ask(last: 400 * day, asks: ReviewPolicy.maxAsks + 1))
    }

    func testAClockSetBackCountsAsTooSoon() { XCTAssertFalse(ask(last: -5 * day, asks: 1)) }

    func testTheGapIsFourMonths() { XCTAssertEqual(ReviewPolicy.minGap, 120 * day) }

    /// The prompt itself: asks once, counts it, and stays quiet until the gap is over.
    func testThePromptCountsItsAsks() throws {
        let name = "review-tests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        var requests = 0

        XCTAssertFalse(ReviewPrompt.onResultClosed(verified: false, complete: true, now: now, defaults: defaults) { requests += 1 })
        XCTAssertEqual(requests, 0)
        XCTAssertNil(ReviewPrompt.lastAskedAt(defaults))

        XCTAssertTrue(ReviewPrompt.onResultClosed(verified: true, complete: true, now: now, defaults: defaults) { requests += 1 })
        XCTAssertEqual(requests, 1)
        XCTAssertEqual(ReviewPrompt.asks(defaults), 1)
        XCTAssertEqual(ReviewPrompt.lastAskedAt(defaults), now)

        let nextDay = now.addingTimeInterval(day)
        XCTAssertFalse(ReviewPrompt.onResultClosed(verified: true, complete: true, now: nextDay, defaults: defaults) { requests += 1 })
        XCTAssertEqual(requests, 1)

        let later = now.addingTimeInterval(ReviewPolicy.minGap)
        XCTAssertTrue(ReviewPrompt.onResultClosed(verified: true, complete: true, now: later, defaults: defaults) { requests += 1 })
        XCTAssertEqual(requests, 2)
        XCTAssertEqual(ReviewPrompt.asks(defaults), 2)
    }
}
