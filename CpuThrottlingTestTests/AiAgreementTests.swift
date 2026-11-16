import XCTest
@testable import CpuThrottlingTest

/** An accelerator's answer against the CPU's: what counts as the same answer, and what a wrong one is told — Android's rule. */
final class AiAgreementTests: XCTestCase {
    private let ramp = (0..<256).map { UInt8($0) }
    /// An output unrelated to `ramp` (correlation about 0.01).
    private let scrambled = (0..<256).map { UInt8(($0 * 37) % 251) }

    func testIdenticalScaledAndUnrelatedOutputs() {
        XCTAssertEqual(AiAgreement.correlation(ramp, ramp), 1, accuracy: 1e-9)
        XCTAssertEqual(AiAgreement.correlation(ramp, ramp.map { $0 / 2 }), 1, accuracy: 0.01)      // the same picture, dimmer
        XCTAssertLessThan(abs(AiAgreement.correlation(ramp, scrambled)), 0.2)
        XCTAssertEqual(AiAgreement.correlation(ramp, Array(ramp.dropLast())), 0)                   // sizes that don't line up
        XCTAssertEqual(AiAgreement.correlation([], []), 0)
    }

    func testConstantOutputsAgreeOnlyWhenIdentical() {
        XCTAssertEqual(AiAgreement.correlation([7, 7, 7], [7, 7, 7]), 1)
        XCTAssertEqual(AiAgreement.correlation([7, 7, 7], [9, 9, 9]), 0)
        XCTAssertEqual(AiAgreement.correlation([7, 7, 7], [1, 2, 3]), 0)
    }

    func testTheWorstOutputDecides() {
        XCTAssertEqual(AiAgreement.of(.segment, [ramp, ramp], [ramp, ramp]), 1, accuracy: 1e-9)
        XCTAssertLessThan(AiAgreement.of(.segment, [ramp, ramp], [ramp, scrambled]), AiAgreement.wrongBelow)
        XCTAssertEqual(AiAgreement.of(.segment, [ramp], [ramp, ramp]), 0)
        XCTAssertEqual(AiAgreement.of(.segment, [], []), 0)
    }

    func testDetectionComparesBoxesAndScoresButNotTheClassIds() {
        XCTAssertEqual(AiAgreement.of(.detect, [ramp, ramp, ramp], [ramp, ramp, scrambled]), 1, accuracy: 1e-9)
        XCTAssertLessThan(AiAgreement.of(.detect, [ramp, ramp, ramp], [ramp, scrambled, ramp]), AiAgreement.wrongBelow)
    }

    func testAnAnswerIsWrongOnlyWhenItHasNothingInCommonWithTheCPUs() {
        XCTAssertTrue(AiAgreement.isWrong(AiAgreement.correlation(ramp, scrambled)))                  // the broken GPUs and NPUs: about 0
        let drifted = (0..<256).map { $0 % 4 != 0 ? ramp[$0] : scrambled[$0] }                       // three values in four the same
        let off = AiAgreement.correlation(ramp, drifted)
        XCTAssertGreaterThan(off, AiAgreement.wrongBelow); XCTAssertLessThan(off, AiAgreement.minimum)  // a GPU's approximation: about 0.7
        XCTAssertFalse(AiAgreement.isWrong(off))
        XCTAssertFalse(AiAgreement.isWrong(1))
        XCTAssertTrue(AiAgreement.isWrong(0.49))
        XCTAssertFalse(AiAgreement.isWrong(0.5))
    }

    func testAWrongAnswersExplanation() {
        XCTAssertEqual(AiAgreement.wrongAnswer(ips: 25.97, agreement: 0.02),
                       "wrong answer: its result doesn't match the CPU's (agreement 0.02, a match is 1.00), so it scores 0 · it ran at 25.97/s")
    }
}
