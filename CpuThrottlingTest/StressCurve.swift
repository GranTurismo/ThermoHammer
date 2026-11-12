import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// STRESS CURVE — a stress run's throughput as % of its baseline: what the share
// card, the presentation screen and a saved RunRecord draw. Pure logic,
// Foundation only. Android's data/StressCurve.kt, the same rules.
// ─────────────────────────────────────────────────────────────────────────────

enum StressCurve {

    /// At most this many points are kept: plenty for a 1080-px card, small enough to store with every run.
    static let maxPoints = 120

    /// Below this share of the baseline for `onsetStamps` stamps in a row = throttled (as StressEngine scores it).
    private static let onsetRatio = 90.0
    private static let onsetStamps = 3

    /// Each 1-s stamp's throughput as % of `baseline`; empty without a baseline.
    static func percentOf(_ throughput: [Int64], baseline: Double) -> [Double] {
        baseline > 0 ? throughput.map { Double($0) / baseline * 100 } : []
    }

    /// `values` cut into at most `max` equal slices of time, each slice its mean, rounded to 1 decimal — evenly
    /// spaced points over the whole run. Shorter input is only rounded.
    static func downsample(_ values: [Double], max: Int = maxPoints) -> [Double] {
        guard values.count > max, max > 0 else { return values.map(round1) }
        return (0..<max).map { i in
            let from = i * values.count / max
            let to = Swift.max(from + 1, (i + 1) * values.count / max)
            let slice = values[from..<to]
            return round1(slice.reduce(0, +) / Double(slice.count))
        }
    }

    /// A leaderboard run's curve from its `/stamps/{id}` scores: % of the median of its first 5 seconds (the server
    /// keeps no baseline). Falls back to the first 5 stamps when none are that early.
    static func fromScores(elapsedMs: [Int], scores: [Int]) -> [Double] {
        guard !scores.isEmpty else { return [] }
        var early = scores.indices.filter { $0 < elapsedMs.count && elapsedMs[$0] < 5_000 }.map { Double(scores[$0]) }
        if early.isEmpty { early = scores.prefix(5).map(Double.init) }
        let base = median(early)
        return base > 0 ? scores.map { Double($0) / base * 100 } : []
    }

    /// First stamp where the run held below 90 % for 3 stamps in a row, in seconds; nil = never throttled.
    static func onsetSec(elapsedMs: [Int], percent: [Double]) -> Int? {
        guard percent.count >= onsetStamps else { return nil }
        for i in 0...(percent.count - onsetStamps) where (0..<onsetStamps).allSatisfy({ percent[i + $0] < onsetRatio }) {
            return (i < elapsedMs.count ? elapsedMs[i] : 0) / 1000
        }
        return nil
    }

    /// "1:23" — minutes and seconds.
    static func mmss(_ sec: Int) -> String { String(format: "%d:%02d", sec / 60, sec % 60) }

    /// "Throttled after 1:23" or "Never throttled" — the line under every curve.
    static func onsetText(_ sec: Int?) -> String { sec.map { "Throttled after \(mmss($0))" } ?? "Never throttled" }

    static func median(_ v: [Double]) -> Double {
        guard !v.isEmpty else { return 0 }
        let s = v.sorted(), m = s.count / 2
        return s.count % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2
    }

    private static func round1(_ v: Double) -> Double { (v * 10).rounded() / 10 }
}
