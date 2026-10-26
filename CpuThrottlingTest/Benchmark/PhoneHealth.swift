import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// PHONE HEALTH — "is my phone as fast as it should be?" A run against the
// verified runs of the same model (GET /benchmark/model) and against this
// phone's own earlier runs; the parts that fall short are grouped by what
// usually causes them. Android's engine/PhoneHealth.kt, the same rules.
// ─────────────────────────────────────────────────────────────────────────────

/** GET /benchmark/model: one model's verified runs of the current scoring version. */
struct ModelStats: Decodable {
    struct Spread: Decodable { let median, p25, p75, best: Int }
    struct OsVersion: Decodable { let osVersion: String; let runs: Int; let medianTotal: Int }
    let deviceModel: String
    let runs: Int
    let total: Spread?
    let sections: [String: Int]?
    let parts: [String: Int]?
    let byOsVersion: [OsVersion]?
}

extension BenchmarkUpload {
    static func fetchModelStats(model: String, os: Int) async throws -> ModelStats {
        var c = URLComponents(url: baseURL.appendingPathComponent("benchmark/model"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "model", value: model), URLQueryItem(name: "os", value: "\(os)")]
        let (data, response) = try await URLSession.shared.data(from: c.url!)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return try JSONDecoder().decode(ModelStats.self, from: data)
    }
}

enum PhoneHealth {
    static let minRuns = 3          // verified runs of a model needed before a run is compared with them
    private static let short = 0.85 // a part this far below the model's median is a finding
    private static let ownDrop = 0.92

    enum Verdict { case faster, onPar, slower, muchSlower, unknown }

    /** severity: 0 note, 1 worth fixing, 2 the main reason. */
    struct Finding: Hashable { let text: String; let severity: Int }

    struct Report {
        let verdict: Verdict
        let headline: String
        let percent: Int?
        let runs: Int
        let total: Int
        let median, p25, p75, best: Int?
        let findings: [Finding]
    }

    /** The run's parts, each 6000 on the reference phone — named as the server names them. */
    static func parts(_ r: BenchmarkResult) -> [String: Int] {
        var p: [String: Int] = [:]
        if let c = r.cpu {
            p["cpu.math"] = c.points(.math); p["cpu.data"] = c.points(.data); p["cpu.crypto"] = c.points(.crypto); p["cpu.multi"] = c.points(.multi)
        }
        if let m = r.mem { p["mem.ram"] = m.ramPoints; p["mem.storage"] = m.storagePoints; p["mem.db"] = m.dbPoints }
        func gpu(_ t: GpuTest) -> Int { r.gpu.first { $0.test == t }?.points ?? 0 }
        p["gpu.lake"] = gpu(.lakeValley); p["gpu.neon"] = gpu(.neonCity); p["gpu.rt"] = gpu(.neonCityRT)
        if let u = r.ux {
            p["ux.web"] = u.webPoints; p["ux.scroll"] = u.scrollPoints; p["ux.gallery"] = u.galleryPoints
            p["ux.photo"] = u.photoPoints; p["ux.video"] = u.videoPoints; p["ux.export"] = u.exportPoints; p["ux.qr"] = u.qrPoints
        }
        if let a = r.ai { p["ai.cpu"] = a.accelScore(.cpu); p["ai.gpu"] = a.accelScore(.gpu); p["ai.npu"] = a.accelScore(.npu) }
        if let l = r.llm { p["llm.prefill"] = l.prefillPoints; p["llm.decode"] = l.decodePoints }
        return p
    }

    private enum Cause: Int {
        case heat, busy, storage, web, video, export, npu, llm
        func text(_ pct: Int) -> String {
            let os = LeaderboardService.isMac ? "macOS" : "iOS"
            switch self {
            case .heat: return "The GPU tests ran \(pct)% below other units — it throttled. Let it cool and take the case off before the next run."
            case .busy: return "The CPU tests ran \(pct)% below other units — something else was using the processor, or Low Power Mode was on."
            case .storage: return "Storage ran \(pct)% below other units — flash slows down as it fills. Freeing some space usually brings it back."
            case .web: return "The browser tests ran \(pct)% below other units — Safari's engine comes with \(os): update it."
            case .video: return "Video decoding ran \(pct)% below other units — a codec ran in software."
            case .export: return "Video export ran \(pct)% below other units — the encoder was slower or its output worse. Heat or a system update can do that."
            case .npu: return "The Neural Engine ran \(pct)% below other units — it didn't take the models the way theirs did."
            case .llm: return "The language model ran \(pct)% below other units — usually less free memory or apps in the background."
            }
        }
    }

    private static func cause(_ part: String) -> Cause {
        if part.hasPrefix("gpu.") || part == "ux.photo" || part == "ai.gpu" { return .heat }
        if part == "mem.storage" || part == "mem.db" { return .storage }
        if ["ux.web", "ux.scroll", "ux.gallery"].contains(part) { return .web }
        if part == "ux.video" { return .video }
        if part == "ux.export" { return .export }
        if part == "ai.npu" { return .npu }
        if part.hasPrefix("llm.") { return .llm }
        return .busy
    }

    private static func flagReasons(_ flags: Int) -> [String] {
        var r: [String] = []
        if flags & BenchFlag.powerEvent != 0 { r.append("a charger was connected") }
        if flags & BenchFlag.envCapped != 0 { r.append("Low Power Mode was on") }
        if flags & (BenchFlag.cooldownTimeout | BenchFlag.warmStarted) != 0 { r.append("it started warm") }
        if flags & BenchFlag.interference != 0 {
            r.append(LeaderboardService.isMac ? "another app was in front or busy" : "other work was running")
        }
        return r
    }

    static func assess(_ result: BenchmarkResult, model: String, stats: ModelStats?, history: [BenchmarkRecord],
                       current: BenchmarkRecord?, osVersion: String) -> Report {
        var findings: [Finding] = []
        let total = result.total
        let reasons = flagReasons(result.validityFlags)
        if !reasons.isEmpty {
            findings.append(Finding(text: "Not a verified run: \(reasons.joined(separator: ", ")) — it doesn't reach the leaderboard, and it may explain a lower score.", severity: 1))
        }
        let os = LeaderboardService.isMac ? "macOS" : "iOS"
        var verdict = Verdict.unknown
        var percent: Int?
        let headline: String
        if let stats, let spread = stats.total, stats.runs >= minRuns, spread.median > 0 {
            let ratio = Double(total) / Double(spread.median)
            percent = Int((ratio * 100).rounded())
            verdict = ratio >= 1.05 ? .faster : ratio >= 0.95 ? .onPar : ratio >= short ? .slower : .muchSlower
            switch verdict {
            case .faster: headline = "Faster than most \(model) units"
            case .onPar: headline = "As fast as a \(model) should be"
            case .slower: headline = "A little slower than other \(model) units"
            default: headline = "Slower than a \(model) should be"
            }
            let medians = stats.parts ?? [:]
            let shortParts = parts(result).compactMap { k, v -> (Cause, Double)? in
                guard let m = medians[k], m > 0 else { return nil }
                let r = Double(v) / Double(m)
                return r < short ? (cause(k), r) : nil
            }
            let worst = Dictionary(grouping: shortParts, by: { $0.0 }).map { ($0.key, $0.value.map(\.1).min()!) }
                .sorted { $0.1 < $1.1 }.prefix(3)
            for (i, (c, r)) in worst.enumerated() {
                findings.append(Finding(text: c.text(Int(((1 - r) * 100).rounded())), severity: i == 0 ? 2 : 1))
            }
            if (verdict == .onPar || verdict == .faster) && !findings.contains(where: { $0.severity >= 1 }) {
                findings.append(Finding(text: "Every section is where other units are: nothing is holding it back.", severity: 0))
            }
            let byOs = stats.byOsVersion ?? []
            let base = byOs.first { $0.osVersion == osVersion && $0.runs >= 2 }?.medianTotal ?? spread.median
            if let (o, d) = byOs.filter({ $0.osVersion != osVersion && $0.runs >= 2 && base > 0 })
                .map({ ($0, Double($0.medianTotal) / Double(base) - 1) })
                .filter({ abs($0.1) >= 0.05 }).max(by: { abs($0.1) < abs($1.1) }) {
                findings.append(Finding(text: "On \(os) \(o.osVersion) this model scores \(Int((abs(d) * 100).rounded()))% \(d > 0 ? "higher" : "lower") (\(o.runs) runs) than on \(os) \(osVersion).", severity: 0))
            }
        } else {
            headline = stats == nil ? "Connect to compare with other \(model) units"
                : "Not enough verified runs of \(model) yet (\(stats!.runs) of \(minRuns))"
        }
        let earlier = history.filter { $0.id != current?.id && $0.validityFlags == 0 && $0.score > 0 && $0.complete }
        if let best = earlier.max(by: { $0.score < $1.score }) {
            let date = best.timestamp.formatted(.dateTime.day().month(.abbreviated))
            if Double(total) < Double(best.score) * ownDrop {
                let update = best.osVersion.flatMap { $0 != osVersion ? " — before the update to \(os) \(osVersion)" : nil } ?? ""
                findings.append(Finding(text: "\(Int(((1 - Double(total) / Double(best.score)) * 100).rounded()))% below this phone's best run (\(date), \(best.score.formatted()))\(update).",
                                        severity: percent == nil ? 2 : 1))
            } else if total >= best.score {
                findings.append(Finding(text: "This phone's best run so far.", severity: 0))
            }
        }
        return Report(verdict: verdict, headline: headline, percent: percent, runs: stats?.runs ?? 0, total: total,
                      median: stats?.total?.median, p25: stats?.total?.p25, p75: stats?.total?.p75, best: stats?.total?.best,
                      findings: findings)
    }
}

/** The run against its model and the phone's own history; the model's statistics load in the background. */
struct HealthCard: View {
    let result: BenchmarkResult
    let record: BenchmarkRecord?
    @State private var stats: ModelStats?
    @State private var loaded = false
    @ObservedObject private var history = BenchmarkHistoryStore.shared
    @StateObject private var network = NetworkMonitor.shared

    private var model: String { LeaderboardService.shared.getDeviceModelName() }

    var body: some View {
        let report = PhoneHealth.assess(result, model: model, stats: stats,
                                        history: history.records.filter { $0.version == BenchmarkScore.version },
                                        current: record, osVersion: UIDevice.current.systemVersion)
        let accent: Color = {
            switch report.verdict {
            case .faster, .onPar: return Forge.phaseMeasured
            case .slower: return Ramp.at(0.5)
            case .muchSlower: return Ramp.at(0.85)
            case .unknown: return Forge.phaseCooldown
            }
        }()
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("PHONE HEALTH").font(ThermoFont.mono(9, weight: .bold)).foregroundColor(accent)
                    Text(network.isConnected && !loaded ? "Comparing with other \(model) units…"
                         : network.isConnected && stats == nil ? "Couldn't reach our server to compare — your own runs below"
                         : report.headline)
                        .font(ThermoFont.mono(12, weight: .black)).foregroundColor(Forge.ink0)
                }
                Spacer()
                if let p = report.percent {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("\(p)%").font(ThermoFont.mono(18, weight: .black)).foregroundColor(accent)
                        Text("OF TYPICAL").font(ThermoFont.mono(7)).foregroundColor(Forge.ink2)
                    }
                }
            }
            if report.percent != nil, let median = report.median {
                SpreadBar(total: report.total, median: median, p25: report.p25 ?? median, p75: report.p75 ?? median,
                          best: report.best ?? median, accent: accent)
                    .frame(height: 22)
                Text("\(report.runs) verified runs of this model · middle half \((report.p25 ?? 0).formatted())–\((report.p75 ?? 0).formatted()) · best \((report.best ?? 0).formatted())")
                    .font(ThermoFont.mono(7)).foregroundColor(Forge.ink2)
            }
            ForEach(report.findings, id: \.self) { f in
                HStack(alignment: .top, spacing: 4) {
                    Text(f.severity == 2 ? "▲" : f.severity == 1 ? "•" : "·")
                        .foregroundColor(f.severity == 2 ? Ramp.at(0.85) : f.severity == 1 ? Ramp.at(0.5) : Forge.ink2)
                    Text(f.text).foregroundColor(f.severity > 0 ? Forge.ink0 : Forge.ink1)
                }
                .font(ThermoFont.mono(9))
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Forge.surface)
        .cornerRadius(14)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(accent.opacity(0.45), lineWidth: 1))
        .task(id: network.isConnected) {
            guard network.isConnected, stats == nil else { return }
            stats = try? await BenchmarkUpload.fetchModelStats(model: model, os: LeaderboardService.osPlatform)
            loaded = true
        }
    }
}

/** The model's middle half as a band, its median as a tick, this run as a marker — on one scale. */
private struct SpreadBar: View {
    let total, median, p25, p75, best: Int
    let accent: Color
    @State private var start = Date()

    var body: some View {
        // the marker glides in over 0.9 s (a Canvas doesn't animate on its own)
        TimelineView(.animation(paused: Date().timeIntervalSince(start) > 1)) { tl in
        Canvas { ctx, size in
            let lo = Double(min(p25, total)) * 0.85, hi = Double(max(best, total)) * 1.05
            let f = min(1, tl.date.timeIntervalSince(start) / 0.9), eased = 1 - pow(1 - f, 3)
            let shown = lo + (Double(total) - lo) * eased
            func x(_ v: Double) -> CGFloat { CGFloat(max(0, min(1, (v - lo) / (hi - lo)))) * size.width }
            let mid = size.height * 0.62
            ctx.fill(Path(roundedRect: CGRect(x: 0, y: mid - 3, width: size.width, height: 6), cornerRadius: 3), with: .color(Forge.ink0.opacity(0.07)))
            ctx.fill(Path(roundedRect: CGRect(x: x(Double(p25)), y: mid - 3, width: max(2, x(Double(p75)) - x(Double(p25))), height: 6),
                          cornerRadius: 3), with: .color(Forge.ink0.opacity(0.22)))
            var tick = Path(); tick.move(to: CGPoint(x: x(Double(median)), y: mid - 6)); tick.addLine(to: CGPoint(x: x(Double(median)), y: mid + 6))
            ctx.stroke(tick, with: .color(Forge.ink0.opacity(0.7)), lineWidth: 2)
            let y = x(shown)
            var tri = Path(); tri.move(to: CGPoint(x: y, y: mid - 4)); tri.addLine(to: CGPoint(x: y - 5, y: 0)); tri.addLine(to: CGPoint(x: y + 5, y: 0)); tri.closeSubpath()
            ctx.fill(tri, with: .color(accent))
            ctx.fill(Path(ellipseIn: CGRect(x: y - 4, y: mid - 4, width: 8, height: 8)), with: .color(accent))
        }
        }
        .onAppear { start = Date() }
    }
}
