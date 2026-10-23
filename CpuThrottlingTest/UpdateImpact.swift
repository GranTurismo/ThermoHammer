import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// UPDATE IMPACT — "did the OS update change my phone?"
//
// Pure logic, Foundation only (so it can be tested off-device). The phone is compared with
// ITSELF; nothing here is uploaded.
//
// Fairness rules — each one exists to stop a misleading verdict:
//   · same phone model only — a restored backup on a new phone must not count
//   · same test settings only — 5-min vs 15-min runs are different tests
//   · clean runs only — a run flagged warm-start / charging / low-power is not evidence
//   · medians of up to `maxRunsPerSide` runs, and a change must exceed normal run-to-run
//     noise before it is called slower or faster
// Mirrors the Android implementation (data/UpdateImpact.kt) rule for rule.
// ─────────────────────────────────────────────────────────────────────────────

/// One finished, scored run, kept on the device. Summary only — no stamps.
struct RunRecord: Codable, Identifiable, Equatable {
    let id: UUID
    let timestamp: Double          // seconds since 1970
    let deviceModel: String
    let osVersion: String
    let osBuild: String?           // kern.osversion — differs between point releases and betas
    let durationType: Int          // 0=5min 1=15min 2=30min
    let threadingType: Int         // 0=single 1=multi
    let capacity: Double           // deliveredCapacity — the leaderboard metric
    let onsetSec: Int?             // nil = never throttled
    let validityFlags: Int
    let confidence: Int

    var osKey: String { UpdateImpact.osKey(osVersion: osVersion, osBuild: osBuild) }
}

enum UpdateImpact {

    /// Typical run-to-run spread of the metric on one phone, in capacity points.
    static let noiseFloorPts = 3.0
    static let maxRunsPerSide = 3
    static let maxHistory = 40

    enum Verdict { case slower, faster, noClearChange }

    /// A set of comparable runs measured on one OS build, newest first.
    struct Side {
        let runs: [RunRecord]
        var latest: RunRecord { runs[0] }
        var median: Double {
            let v = runs.map { $0.capacity }.sorted()
            let m = v.count / 2
            return v.count % 2 == 1 ? v[m] : (v[m - 1] + v[m]) / 2
        }
        /// max − min of the runs; 0 for a single run.
        var spread: Double {
            guard runs.count >= 2 else { return 0 }
            let v = runs.map { $0.capacity }
            return (v.max() ?? 0) - (v.min() ?? 0)
        }
    }

    /// The phone updated and has no usable test since — offer to repeat the old one.
    struct Prompt {
        let key: String
        let last: RunRecord
        let nowOsVersion: String
        let nowOsBuild: String?
    }

    struct Comparison {
        let key: String
        let before: Side
        let after: Side
        let deltaPts: Double
        let thresholdPts: Double
        let verdict: Verdict
        /// One run on a side is a weak comparison — the UI says so.
        var weak: Bool { before.runs.count < 2 || after.runs.count < 2 }
    }

    enum Insight {
        case prompt(Prompt)
        case comparison(Comparison)

        /// Identifies one OS transition ("before build → current build").
        var key: String {
            switch self { case .prompt(let p): return p.key; case .comparison(let c): return c.key }
        }
        /// Prompt and result are dismissed separately: hiding "update detected" must not
        /// hide the result that appears after the user actually re-tests.
        var dismissKey: String {
            switch self { case .prompt(let p): return "prompt:\(p.key)"; case .comparison(let c): return "result:\(c.key)" }
        }
    }

    static func osKey(osVersion: String, osBuild: String?) -> String { "\(osVersion)|\(osBuild ?? "")" }

    /// A run is usable as evidence when nothing but the platform's constant "no clock data"
    /// flag is set. (On iOS that flag is always on — it describes the platform, not the run.)
    static func isComparable(_ r: RunRecord) -> Bool {
        (r.validityFlags & ~ValidityFlag.freqMissing) == 0
    }

    private static func sameSettings(_ a: RunRecord, _ b: RunRecord) -> Bool {
        a.durationType == b.durationType && a.threadingType == b.threadingType
    }

    /// What, if anything, to tell the user about the current OS versus their earlier runs.
    /// Returns nil when there is nothing honest to say (no history, or no clean baseline).
    static func insight(history: [RunRecord], deviceModel: String,
                        osVersion: String, osBuild: String?) -> Insight? {
        let curKey = osKey(osVersion: osVersion, osBuild: osBuild)
        let mine = history.filter { $0.deviceModel == deviceModel }.sorted { $0.timestamp > $1.timestamp }

        // Most recent clean run measured on a different OS build — the baseline.
        guard let baseline = mine.first(where: { $0.osKey != curKey && isComparable($0) }) else { return nil }
        let key = "\(baseline.osKey)->\(curKey)"

        let after = Array(mine.filter { $0.osKey == curKey && isComparable($0) && sameSettings($0, baseline) }
            .prefix(maxRunsPerSide))
        if after.isEmpty {
            return .prompt(Prompt(key: key, last: baseline, nowOsVersion: osVersion, nowOsBuild: osBuild))
        }
        let before = Array(mine.filter { $0.osKey == baseline.osKey && isComparable($0) && sameSettings($0, baseline) }
            .prefix(maxRunsPerSide))

        let b = Side(runs: before), a = Side(runs: after)
        let delta = a.median - b.median
        let threshold = max(noiseFloorPts, max(b.spread, a.spread))
        let verdict: Verdict = delta <= -threshold ? .slower : (delta >= threshold ? .faster : .noClearChange)
        return .comparison(Comparison(key: key, before: b, after: a,
                                      deltaPts: delta, thresholdPts: threshold, verdict: verdict))
    }

    /// Append a run, drop duplicates by id, keep only the newest `max`.
    static func appendCapped(_ existing: [RunRecord], _ run: RunRecord, max cap: Int = maxHistory) -> [RunRecord] {
        let all = (existing.filter { $0.id != run.id } + [run]).sorted { $0.timestamp < $1.timestamp }
        return Array(all.suffix(cap))
    }
}
