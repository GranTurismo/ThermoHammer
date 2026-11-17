import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// MY RUNS — this device's own benchmark history, as the screen lists it. Pure
// logic, Foundation only; the same rules as Android and Windows:
//  · only runs of the current benchmark version (older versions scored
//    different work, so they don't compare)
//  · only runs made on this model; a run saved without a model (before 2.1)
//    counts as this device's, like BenchmarkUpdateImpact. A backup restored onto
//    another phone must not mix two phones into one history.
//  · newest first, each with its change from the run before it
//  · best = the highest total (the earlier run on a tie)
// ─────────────────────────────────────────────────────────────────────────────

enum MyRuns {
    /// The kept history's length (BenchmarkHistoryStore).
    static let kept = 100

    enum Status { case submitted, notSubmitted, unverified }

    struct Row: Identifiable {
        let record: BenchmarkRecord
        let changePct: Double?       // against the run before it; nil for the first run
        let isBest: Bool
        let status: Status
        var id: UUID { record.id }
        /// A verified run not yet sent — and, as everywhere on iOS, only a complete one (every section ran).
        var canSubmit: Bool { status == .notSubmitted && record.complete }
    }

    struct History {
        let rows: [Row]              // newest first
        let olderVersion: Int        // runs not shown: an older benchmark version
        let otherPhone: Int          // runs not shown: made on another model

        var best: BenchmarkRecord? { rows.first { $0.isBest }?.record }
        var latest: BenchmarkRecord? { rows.first?.record }
        var verified: Int { rows.filter { $0.record.validityFlags == 0 }.count }
        /// Oldest first: what the chart draws, left to right.
        var chronological: [Row] { rows.reversed() }
    }

    static func build(_ all: [BenchmarkRecord], deviceModel: String, version: Int = BenchmarkScore.version) -> History {
        let current = all.filter { $0.version == version }
        // stable: runs with the same time keep the order they were saved in
        let mine = current.enumerated().filter { $0.element.deviceModel == nil || $0.element.deviceModel == deviceModel }
            .sorted { ($0.element.timestamp, $0.offset) < ($1.element.timestamp, $1.offset) }.map(\.element)
        var best: BenchmarkRecord?
        for r in mine where best == nil || r.score > best!.score { best = r }
        var rows: [Row] = []
        for (i, r) in mine.enumerated() {
            let before = i > 0 ? mine[i - 1] : nil
            let change = before.flatMap { b in b.score > 0 ? Double(r.score - b.score) * 100 / Double(b.score) : nil }
            let status: Status = r.submitted ? .submitted : r.validityFlags != 0 ? .unverified : .notSubmitted
            rows.append(Row(record: r, changePct: change, isBest: r.id == best?.id, status: status))
        }
        return History(rows: rows.reversed(), olderVersion: all.count - current.count, otherPhone: current.count - mine.count)
    }

    /// "+2.1%", "−4.0%", "±0.0%" — the change from the run before, one decimal.
    static func changeText(_ pct: Double) -> String {
        let rounded = (pct * 10).rounded() / 10
        if rounded > 0 { return String(format: "+%.1f%%", rounded) }
        if rounded < 0 { return String(format: "\u{2212}%.1f%%", -rounded) }
        return "\u{00B1}0.0%"
    }

    /// The notes under the list: what is not shown, and where the history lives. `device`: ShareService.noun.
    static func footnotes(_ h: History, device: String = "phone") -> [String] {
        var out: [String] = []
        if h.olderVersion > 0 {
            out.append(h.olderVersion == 1 ? "1 earlier run used an older version of the benchmark, so it isn't shown: its score doesn't compare."
                       : "\(h.olderVersion) earlier runs used an older version of the benchmark, so they aren't shown: their scores don't compare.")
        }
        if h.otherPhone > 0 {
            out.append(h.otherPhone == 1 ? "1 run was made on another \(device) (restored from a backup), so it isn't shown."
                       : "\(h.otherPhone) runs were made on another \(device) (restored from a backup), so they aren't shown.")
        }
        out.append("Your runs are kept only on this \(device): the last \(kept).")
        return out
    }
}
