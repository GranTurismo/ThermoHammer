import XCTest
@testable import CpuThrottlingTest

/** Find my lost speed: burst / sustained / drop, the diagnosis, the before-and-after — Android's and Windows' rules. */
final class SpeedCheckTests: XCTestCase {

    /// 30 seconds: `burst` for the first `burstSecs`, then `sustained`.
    private func seconds(_ burst: Double, _ sustained: Double, burstSecs: Int = 8) -> [Double] {
        (0..<30).map { $0 < burstSecs ? burst : sustained }
    }

    private func m(_ w: [Double], saver: Bool = false, thermal: Int = 0) -> SpeedCheck.Measurement {
        SpeedCheck.measurement(w, powerSaver: saver, charging: false, thermalEnd: thermal, timestamp: 1_000)
    }

    func testBurstSustainedDrop() {
        var w = seconds(100, 60)
        w[0] = 90                                                   // burst: mean of seconds 1–5
        w[12] = 10; w[13] = 200                                     // sustained: median of seconds 11–30 shrugs these off
        let r = m(w)
        XCTAssertEqual(r.burst, 98, accuracy: 1e-9)
        XCTAssertEqual(r.sustained, 60, accuracy: 1e-9)
        XCTAssertEqual(r.drop, 1 - 60.0 / 98, accuracy: 1e-9)
        XCTAssertEqual(SpeedCheck.drop(burst: 0, sustained: 5), 0)
    }

    func testSustainedIsTheMedianOfSeconds11To30() {
        let w = (1...30).map(Double.init)                           // second n did n units
        XCTAssertEqual(SpeedCheck.sustained(w), 20.5, accuracy: 1e-9)   // 11…30
        XCTAssertEqual(SpeedCheck.burst(w), 3, accuracy: 1e-9)          // 1…5
    }

    func testNothingFound() {
        let d = SpeedCheck.diagnose(m(seconds(100, 92)), mac: false, noun: "phone")
        XCTAssertEqual(d.findings.map(\.cause), [.nothingFound])
        XCTAssertEqual(d.findings[0].title, "NO LOST SPEED FOUND")
        XCTAssertEqual(d.findings[0].text, "Your phone kept 92% of its burst speed. Nothing is holding it back.")
        XCTAssertNil(d.note)
        XCTAssertFalse(d.foundCause)                                // nothing to change: DONE, not "I changed it"
    }

    func testAnyCauseIsSomethingToChange() {
        XCTAssertTrue(SpeedCheck.diagnose(m(seconds(100, 92), saver: true), mac: false, noun: "phone").foundCause)
        XCTAssertTrue(SpeedCheck.diagnose(m(seconds(100, 60)), mac: false, noun: "phone").foundCause)
        XCTAssertTrue(SpeedCheck.diagnose(m(seconds(100, 60), thermal: 3), mac: true, noun: "Mac").foundCause)
        // a slow Phone Health note alone is no cause
        XCTAssertFalse(SpeedCheck.diagnose(m(seconds(100, 92)), mac: false, noun: "phone",
                                           health: SpeedCheck.HealthNote(percentBelow: 20, model: "iPhone 15")).foundCause)
    }

    func testHeldBackWhileCoolAtA25PercentDrop() {
        let d = SpeedCheck.diagnose(m(seconds(100, 75), thermal: 1), mac: false, noun: "phone")     // fair = still cool
        XCTAssertEqual(d.findings.map(\.cause), [.heldBackWhileCool])
        XCTAssertEqual(d.findings[0].steps, "Settings › Battery › Low Power Mode — off. Then let the phone cool and check again.")
        // just under the threshold: nothing
        XCTAssertEqual(SpeedCheck.diagnose(m(seconds(100, 75.5)), mac: false, noun: "phone").findings.map(\.cause), [.nothingFound])
    }

    func testHeatWhenHot() {
        let d = SpeedCheck.diagnose(m(seconds(100, 50), thermal: 2), mac: false, noun: "phone")
        XCTAssertEqual(d.findings.map(\.cause), [.heat])
        XCTAssertEqual(d.findings[0].text, "It slowed down because it got hot. Take it out of its case, unplug it, let it cool, and check again.")
    }

    func testPowerSavingComesFirstAndEveryCauseShows() {
        let d = SpeedCheck.diagnose(m(seconds(100, 60), saver: true), mac: false, noun: "phone",
                                    health: SpeedCheck.HealthNote(percentBelow: 18, model: "iPhone 15"))
        XCTAssertEqual(d.findings.map(\.cause), [.powerSaving, .heldBackWhileCool])
        XCTAssertEqual(d.findings[0].text, "Low Power Mode caps the CPU. Settings › Battery › Low Power Mode — turn it off.")
        XCTAssertEqual(d.note, "Your last benchmark was 18% below the median iPhone 15.")
    }

    func testMacTexts() {
        let d = SpeedCheck.diagnose(m(seconds(100, 60), saver: true), mac: true, noun: "Mac")
        XCTAssertEqual(d.findings[0].text, "System Settings › Battery › Low Power Mode — set it to Never.")
        XCTAssertEqual(d.findings[1].text, "Your Mac slows down after a short burst even though it isn't hot — a power setting is holding it back, not heat.")
        XCTAssertEqual(d.findings[1].steps, "System Settings › Battery › Low Power Mode — set it to Never. On Macs with Energy Mode, choose High Power.")
    }

    func testComparison() {
        let before = m(seconds(100, 50))
        let faster = SpeedCheck.compare(before: before, after: m(seconds(100, 96)), noun: "phone")
        XCTAssertEqual(faster.verdict, .faster)
        XCTAssertEqual(faster.title, "1.92× FASTER")
        XCTAssertEqual(faster.text, "You got back 92% speed.")
        XCTAssertEqual(SpeedCheck.compare(before: before, after: m(seconds(100, 55)), noun: "phone").verdict, .faster)   // 1.10
        XCTAssertEqual(SpeedCheck.compare(before: before, after: m(seconds(100, 45)), noun: "phone").title, "SLOWER")    // 0.90
        let same = SpeedCheck.compare(before: before, after: m(seconds(100, 52)), noun: "phone")
        XCTAssertEqual(same.verdict, .noChange)
        XCTAssertEqual(same.text, "That setting wasn't holding your phone back.")
    }

    func testSlowedWhileCool() {
        let stamps = (0..<300).map { (elapsedMs: $0 * 1000 + 500, thermalState: $0 < 200 ? 0 : 2) }
        XCTAssertTrue(SpeedCheck.slowedWhileCool(capacity: 70, onsetSec: 60, stamps: stamps))
        XCTAssertFalse(SpeedCheck.slowedWhileCool(capacity: 70, onsetSec: 250, stamps: stamps))   // hot by then
        XCTAssertFalse(SpeedCheck.slowedWhileCool(capacity: 85, onsetSec: 60, stamps: stamps))    // kept enough
        XCTAssertFalse(SpeedCheck.slowedWhileCool(capacity: 70, onsetSec: nil, stamps: stamps))   // never throttled
    }

    func testFirstCheckExpiresAfterAnHour() {
        let first = m(seconds(100, 60))                             // timestamp 1000
        XCTAssertNotNil(SpeedCheck.fresh(first, now: 1_000 + 3_599))
        XCTAssertNil(SpeedCheck.fresh(first, now: 1_000 + 3_600))
        XCTAssertNil(SpeedCheck.fresh(nil, now: 0))
    }

    func testMeasurementSurvivesJson() throws {
        let a = m(seconds(100, 60), saver: true, thermal: 1)
        XCTAssertEqual(try JSONDecoder().decode(SpeedCheck.Measurement.self, from: JSONEncoder().encode(a)), a)
    }
}
