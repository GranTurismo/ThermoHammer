import Foundation

enum ComparisonWinner {
    case runA
    case runB
    case equal
}

struct ComparisonAnalysis {
    let runA: PendingTestResult
    let runB: PendingTestResult
    let peakScoreA: Double
    let peakScoreB: Double
    let avgScoreA: Double
    let avgScoreB: Double
    let peakScoreDeltaPct: Double
    let avgScoreDeltaPct: Double
    let finalStabilityDeltaPct: Double
    let throttleOnsetTimeA: Int?
    let throttleOnsetTimeB: Int?
    let winner: ComparisonWinner
    let summaryText: String
}

struct ComparisonEngine {
    static func analyze(runA: PendingTestResult, runB: PendingTestResult) -> ComparisonAnalysis {
        let stampsA = runA.stamps
        let stampsB = runB.stamps

        let peakA = !stampsA.isEmpty ? Double(stampsA.map { $0.score }.max() ?? 1) : max(100.0, Double(runA.finalStability) * 2500.0)
        let peakB = !stampsB.isEmpty ? Double(stampsB.map { $0.score }.max() ?? 1) : max(100.0, Double(runB.finalStability) * 2500.0)
        let peakDelta = peakA > 0 ? (((peakB - peakA) / peakA) * 100.0) : 0.0

        let avgA = !stampsA.isEmpty ? Double(stampsA.map { $0.score }.reduce(0, +)) / Double(stampsA.count) : max(90.0, Double(runA.finalStability) * 2200.0)
        let avgB = !stampsB.isEmpty ? Double(stampsB.map { $0.score }.reduce(0, +)) / Double(stampsB.count) : max(90.0, Double(runB.finalStability) * 2200.0)
        let avgDelta = avgA > 0 ? (((avgB - avgA) / avgA) * 100.0) : 0.0

        let stabDelta = runB.finalStability - runA.finalStability

        let onsetA = stampsA.first(where: { Double($0.score) < peakA * 0.9 }).map { $0.elapsedMs / 1000 }
        let onsetB = stampsB.first(where: { Double($0.score) < peakB * 0.9 }).map { $0.elapsedMs / 1000 }

        var scoreA = 0
        var scoreB = 0
        if runA.finalStability > runB.finalStability { scoreA += 2 } else if runB.finalStability > runA.finalStability { scoreB += 2 }
        if avgA > avgB { scoreA += 2 } else if avgB > avgA { scoreB += 2 }

        let winner: ComparisonWinner
        if scoreA > scoreB {
            winner = .runA
        } else if scoreB > scoreA {
            winner = .runB
        } else {
            winner = .equal
        }

        let nameA = "\(runA.deviceManufacturer) \(runA.deviceModel)"
        let nameB = "\(runB.deviceManufacturer) \(runB.deviceModel)"

        var summary = ""
        if winner == .runA {
            summary += "\(nameA) demonstrated superior thermal stability (\(String(format: "%.1f", runA.finalStability))% vs \(String(format: "%.1f", runB.finalStability))%). "
        } else if winner == .runB {
            summary += "\(nameB) demonstrated superior thermal stability (\(String(format: "%.1f", runB.finalStability))% vs \(String(format: "%.1f", runA.finalStability))%). "
        } else {
            summary += "Both runs demonstrated equivalent thermal performance. "
        }

        if let oA = onsetA, let oB = onsetB {
            if oA > oB {
                summary += "Run A delayed thermal throttling by \(oA - oB)s longer than Run B. "
            } else if oB > oA {
                summary += "Run B delayed thermal throttling by \(oB - oA)s longer than Run A. "
            }
        } else if onsetA == nil && onsetB != nil {
            summary += "Run A maintained sustained performance without significant throttling, whereas Run B throttled at \(onsetB!)s. "
        } else if onsetB == nil && onsetA != nil {
            summary += "Run B maintained sustained performance without significant throttling, whereas Run A throttled at \(onsetA!)s. "
        }

        return ComparisonAnalysis(
            runA: runA,
            runB: runB,
            peakScoreA: peakA,
            peakScoreB: peakB,
            avgScoreA: avgA,
            avgScoreB: avgB,
            peakScoreDeltaPct: peakDelta,
            avgScoreDeltaPct: avgDelta,
            finalStabilityDeltaPct: stabDelta,
            throttleOnsetTimeA: onsetA,
            throttleOnsetTimeB: onsetB,
            winner: winner,
            summaryText: summary
        )
    }
}
