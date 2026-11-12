import Foundation

/** Which of the two compared runs leads on one number. */
enum Lead { case a, b, even, none }

/**
 Who leads on one number of two runs, and by how much: `advantage` is how many percent the better result beats the other
 (nil when only one run has a result, or the other one is zero). Results closer than `evenWithin` are even.
 The same rules as Android's BenchmarkComparison.kt and the Windows app's BenchmarkCompare.cs.
 */
struct Duel: Equatable {
    let winner: Lead
    var advantage: Double? = nil

    static let evenWithin = 0.01
    static let none = Duel(winner: .none)

    /// `better` is the server's "higher" or "lower"; anything else is text or context, not a contest. A missing number
    /// means the test didn't run on that device: the other one leads, but not by "100 %".
    static func of(_ a: Double?, _ b: Double?, better: String?) -> Duel {
        guard better == "higher" || better == "lower" else { return .none }
        let lower = better == "lower"
        func has(_ v: Double?) -> Bool { guard let v, !v.isNaN else { return false }; return lower ? v >= 0 : v > 0 }
        switch (has(a), has(b)) {
        case (false, false): return .none
        case (true, false): return Duel(winner: .a)
        case (false, true): return Duel(winner: .b)
        default: break
        }
        let x = a!, y = b!
        if x == y { return Duel(winner: .even, advantage: 0) }
        let aLeads = lower ? x < y : x > y
        let hi = max(x, y), lo = min(x, y)
        if lo <= 0 { return Duel(winner: aLeads ? .a : .b) }       // 0 % dropped against 2 %: ahead, no ratio
        let pct = (hi / lo - 1) * 100
        return hi / lo - 1 < evenWithin ? Duel(winner: .even, advantage: pct) : Duel(winner: aLeads ? .a : .b, advantage: pct)
    }

    /// Points and scores: more is better, 0 means nothing was scored.
    static func scores(_ a: Int?, _ b: Int?) -> Duel { of(a.map(Double.init), b.map(Double.init), better: "higher") }
}

private func share(_ x: Int?, _ y: Int?) -> Double {
    let top = max(x ?? 0, y ?? 0)
    return top <= 0 ? 0 : Double(max(x ?? 0, 0)) / Double(top)
}

struct ComparedRow: Identifiable {
    let label: String
    let a: String?
    let b: String?
    let duel: Duel
    var id: String { label }
}

struct ComparedGroup: Identifiable {
    let title: String
    let pointsA: Int?
    let pointsB: Int?
    let duel: Duel
    let rows: [ComparedRow]
    var id: String { title }
    var shareA: Double { share(pointsA, pointsB) }
    var shareB: Double { share(pointsB, pointsA) }
}

struct ComparedSection: Identifiable {
    let key: String
    let title: String
    let scoreA: Int?
    let scoreB: Int?
    let duel: Duel
    let groups: [ComparedGroup]
    var id: String { key }
    /// Each side's score as a share of the larger one: what the bars and the radar draw.
    var shareA: Double { share(scoreA, scoreB) }
    var shareB: Double { share(scoreB, scoreA) }
    var short: String {
        switch key { case "cpu": return "CPU"; case "mem": return "MEM"; case "gpu": return "GPU"; case "ux": return "UX"
        case "ai": return "AI"; case "llm": return "LLM"; default: return title }
    }
}

/**
 Two leaderboard runs side by side: every section, group and row of both, matched by the server's keys, titles and labels
 (so a measurement the server adds later is compared too), each with who leads and by how much.
 */
struct BenchmarkComparison {
    let a: BenchmarkDetail
    let b: BenchmarkDetail
    let total: Duel
    let sections: [ComparedSection]

    var winsA: Int { sections.filter { $0.duel.winner == .a }.count }
    var winsB: Int { sections.filter { $0.duel.winner == .b }.count }
    var even: Int { sections.filter { $0.duel.winner == .even }.count }

    /// Each run's total as a share of both together: the split bar.
    var totalShareA: Double {
        let sum = max(a.totalScore, 0) + max(b.totalScore, 0)
        return sum == 0 ? 0.5 : Double(max(a.totalScore, 0)) / Double(sum)
    }

    /// The same two runs the other way round.
    func swapped() -> BenchmarkComparison { .make(b, a) }

    /// One line on the overall result, e.g. "HONOR 600 Pro scores 23% higher overall".
    func headline(nameA: String, nameB: String) -> String {
        switch total.winner {
        case .even: return "Dead heat: within 1% of each other overall"
        case .a: return total.advantage.map { "\(nameA) scores \(percentText($0)) higher overall" } ?? "\(nameA) wins: the other has no total"
        case .b: return total.advantage.map { "\(nameB) scores \(percentText($0)) higher overall" } ?? "\(nameB) wins: the other has no total"
        case .none: return "Neither run has a total score"
        }
    }

    /// How the sections went, from the side of whoever leads, e.g. "Ahead in 4 of 6 sections · 1 even".
    func sectionsLine() -> String {
        let counted = sections.filter { $0.duel.winner != .none }.count
        let base = winsA == winsB ? "Sections split \(winsA)–\(winsB)" : "Ahead in \(max(winsA, winsB)) of \(counted) sections"
        return even > 0 ? "\(base) · \(even) even" : base
    }

    static func make(_ a: BenchmarkDetail, _ b: BenchmarkDetail) -> BenchmarkComparison {
        let pairs: [(BenchmarkDetail.Section?, BenchmarkDetail.Section?)] = union(a.sections, b.sections, key: \.key)
        let sections: [ComparedSection] = pairs.map { pair in section(pair.0, pair.1) }
        return BenchmarkComparison(a: a, b: b, total: .scores(a.totalScore, b.totalScore), sections: sections)
    }

    private static func section(_ sa: BenchmarkDetail.Section?, _ sb: BenchmarkDetail.Section?) -> ComparedSection {
        let pairs: [(BenchmarkDetail.Group?, BenchmarkDetail.Group?)] = union(sa?.groups ?? [], sb?.groups ?? [], key: \.title)
        let groups: [ComparedGroup] = pairs.map { pair in group(pair.0, pair.1) }
        let s: BenchmarkDetail.Section = (sa ?? sb)!
        return ComparedSection(key: s.key, title: s.title, scoreA: sa?.score, scoreB: sb?.score,
                               duel: .scores(sa?.score, sb?.score), groups: groups)
    }

    private static func group(_ ga: BenchmarkDetail.Group?, _ gb: BenchmarkDetail.Group?) -> ComparedGroup {
        let pairs: [(BenchmarkDetail.Row?, BenchmarkDetail.Row?)] = union(ga?.rows ?? [], gb?.rows ?? [], key: \.label)
        let rows: [ComparedRow] = pairs.map { pair in row(pair.0, pair.1) }
        let g: BenchmarkDetail.Group = (ga ?? gb)!
        return ComparedGroup(title: g.title, pointsA: ga?.points, pointsB: gb?.points, duel: .scores(ga?.points, gb?.points), rows: rows)
    }

    private static func row(_ ra: BenchmarkDetail.Row?, _ rb: BenchmarkDetail.Row?) -> ComparedRow {
        let better: String? = ra?.better ?? rb?.better
        let label: String = (ra ?? rb)!.label
        return ComparedRow(label: label, a: ra?.value, b: rb?.value, duel: Duel.of(ra?.number, rb?.number, better: better))
    }

    /// Both lists matched by `key`: A's order first, then whatever only B has.
    private static func union<T, K: Hashable>(_ a: [T], _ b: [T], key: KeyPath<T, K>) -> [(T?, T?)] {
        var byB: [K: T] = [:]
        for y in b { byB[y[keyPath: key]] = y }
        var seen = Set<K>()
        var out: [(T?, T?)] = []
        for x in a where seen.insert(x[keyPath: key]).inserted { out.append((x, byB[x[keyPath: key]])) }
        for y in b where seen.insert(y[keyPath: key]).inserted { out.append((nil, y)) }
        return out
    }
}

/// "23%" or "4.2%": one decimal while it's small.
func percentText(_ pct: Double) -> String { pct >= 9.95 ? String(format: "%.0f%%", pct) : String(format: "%.1f%%", pct) }

/// "+23%": the badge next to the better result.
func advantageText(_ pct: Double) -> String { "+" + percentText(pct) }
