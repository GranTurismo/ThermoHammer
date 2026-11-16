import Foundation

/**
 * How well an accelerator's answer agrees with the CPU's for the same model — Android's AiAgreement.
 *
 * A GPU or a Neural Engine that is fast and wrong is worth nothing: a run whose answer isn't the CPU's scores 0, and
 * says why (a Mali GPU and a MediaTek NPU were found returning results that had nothing in common with the CPU's and
 * being paid points for them). All three accelerators hand back TFLite's 8-bit tensors, so their bytes compare directly.
 */
enum AiAgreement {
    /** Outputs at least this correlated with the CPU's are the same answer. */
    static let minimum = 0.9

    /**
     * A scored run's answer is wrong below this: it has nothing in common with the CPU's (the broken ones seen sat at
     * 0.00–0.06). Between this and `minimum` an answer is off but recognisably the same — the GPU runs these 8-bit
     * models as a floating-point approximation, and object detection drifts to about 0.7 that way — and keeps its
     * points, with the agreement shown in the report: the line can be drawn tighter once devices have reported theirs.
     */
    static let wrongBelow = 0.5

    static func isWrong(_ agreement: Double) -> Bool { agreement < wrongBelow }

    /** Pearson correlation of two outputs' bytes; two identical constant outputs agree fully. */
    static func correlation(_ a: [UInt8], _ b: [UInt8]) -> Double {
        guard a.count == b.count, !a.isEmpty else { return 0 }
        var ma = 0.0, mb = 0.0
        for i in a.indices { ma += Double(a[i]); mb += Double(b[i]) }
        ma /= Double(a.count); mb /= Double(a.count)
        var ab = 0.0, aa = 0.0, bb = 0.0
        for i in a.indices {
            let x = Double(a[i]) - ma, y = Double(b[i]) - mb
            ab += x * y; aa += x * x; bb += y * y
        }
        if aa == 0, bb == 0 { return a == b ? 1 : 0 }
        return aa > 0 && bb > 0 ? ab / (aa * bb).squareRoot() : 0
    }

    /** The worst correlation among the outputs that mean something; 0 when they don't line up. */
    static func of(_ test: AiTest, _ a: [[UInt8]], _ b: [[UInt8]]) -> Double {
        guard !a.isEmpty, a.count == b.count else { return 0 }
        // YOLOX's third output is a class index per candidate box: where nothing was found the argmax is noise
        let compared = test == .detect ? min(2, a.count) : a.count
        return (0..<compared).map { correlation(a[$0], b[$0]) }.min() ?? 0
    }

    /** Why a GPU's or an NPU's run scores 0 although it ran, as the result and the report say it. */
    static func wrongAnswer(ips: Double, agreement: Double) -> String {
        String(format: "wrong answer: its result doesn't match the CPU's (agreement %.2f, a match is 1.00), so it scores 0 · it ran at %.2f/s", agreement, ips)
    }
}
