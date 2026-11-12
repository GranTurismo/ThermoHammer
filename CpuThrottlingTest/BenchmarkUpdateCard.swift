import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK UPDATE CARD (iOS) — the stress card's twin (UpdateImpactCard), for
// benchmark runs: UPDATE DETECTED with RUN BENCHMARK, then before → after with
// each section's change. Shown in the same place; both cards can show.
// ─────────────────────────────────────────────────────────────────────────────

struct BenchmarkUpdateCard: View {
    let insight: BenchmarkUpdateImpact.Insight
    let onRun: () -> Void
    let onDismiss: () -> Void

    private var os: String { LeaderboardService.isMac ? "macOS" : "iOS" }

    var body: some View {
        switch insight {
        case .prompt(let p): promptCard(p)
        case .comparison(let c): comparisonCard(c)
        }
    }

    private func promptCard(_ p: BenchmarkUpdateImpact.Prompt) -> some View {
        let accent = Forge.phasePreFlight
        return shell(accent) {
            HStack(spacing: 8) {
                StatusLED(color: accent)
                Engraved(text: "UPDATE DETECTED", color: accent, size: 10)
                Spacer()
                Button(action: onDismiss) { Engraved(text: "NOT NOW", size: 7.5) }
                    .padding(.horizontal, 6).padding(.vertical, 4)
            }
            Text(BenchmarkUpdateImpact.transition(os: os, from: (p.last.osVersion ?? "?", p.last.osBuild), to: (p.nowOsVersion, p.nowOsBuild))
                 + ". Run the benchmark again to see if the update changed your \(ShareService.noun).")
                .font(ThermoFont.mono(9.5)).foregroundColor(Forge.ink0)
            Text("Your last benchmark: \(p.last.score.formatted()) points · \(dateLabel(p.last.timestamp))")
                .font(ThermoFont.mono(8)).foregroundColor(Forge.ink1)
            actionButton("RUN BENCHMARK", accent, action: onRun)
        }
    }

    private func comparisonCard(_ c: BenchmarkUpdateImpact.Comparison) -> some View {
        let tint: Color = c.verdict == .slower ? Ramp.at(0.68) : c.verdict == .faster ? Forge.phaseMeasured : Forge.ink1
        return shell(tint) {
            HStack {
                Engraved(text: "BENCHMARK · UPDATE IMPACT", color: Forge.ink1, size: 10)
                Spacer()
                Button(action: onDismiss) { Text("✕").font(ThermoFont.mono(11)).foregroundColor(Forge.ink2) }
                    .padding(.horizontal, 8)
            }
            HStack(alignment: .center) {
                sideColumn("BEFORE", c.before, alignment: .leading)
                Spacer(minLength: 6)
                Text("→").font(ThermoFont.mono(16)).foregroundColor(Forge.ink2)
                Spacer(minLength: 6)
                sideColumn("AFTER", c.after, alignment: .trailing)
            }
            HStack(alignment: .lastTextBaseline, spacing: 10) {
                Text(percent(c.delta)).font(ThermoFont.display(30)).foregroundColor(tint)
                Engraved(text: c.verdict == .slower ? "SLOWER" : c.verdict == .faster ? "FASTER" : "NO CLEAR CHANGE", color: tint, size: 8.5)
            }
            Text(c.verdict == .noClearChange
                 ? "Within 3 % of before the update — normal run-to-run variation."
                 : BenchmarkUpdateImpact.deltaText(c.delta) + ".")
                .font(ThermoFont.mono(8)).foregroundColor(Forge.ink1)
            if !c.sections.isEmpty {
                VStack(spacing: 4) {
                    ForEach(c.sections, id: \.key) { s in
                        HStack {
                            Text(s.label).font(ThermoFont.mono(8, weight: .bold)).foregroundColor(Forge.ink2).frame(width: 34, alignment: .leading)
                            Text("\(Int(s.before.rounded()).formatted()) → \(Int(s.after.rounded()).formatted())")
                                .font(ThermoFont.mono(8)).foregroundColor(Forge.ink1)
                            Spacer()
                            Text(percent(s.delta)).font(ThermoFont.mono(9, weight: .bold))
                                .foregroundColor(abs(s.delta) < BenchmarkUpdateImpact.noiseFloor ? Forge.ink1 : s.delta < 0 ? Ramp.at(0.68) : Forge.phaseMeasured)
                        }
                    }
                }
            }
            Text("Same \(ShareService.noun), verified complete runs only, medians of up to \(BenchmarkUpdateImpact.maxRunsPerSide) per side. " +
                 "This shows what changed after the update — not what caused it.")
                .font(ThermoFont.mono(7)).foregroundColor(Forge.ink2)
        }
    }

    // MARK: pieces (UpdateImpactCard's look)

    private func shell<Content: View>(_ accent: Color, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10, content: content)
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Forge.surface)
            .cornerRadius(12)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(accent.opacity(0.25), lineWidth: 1))
            .cornerTicks(color: accent.opacity(0.5))
    }

    private func sideColumn(_ tag: String, _ side: BenchmarkUpdateImpact.Side, alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 2) {
            Engraved(text: tag, size: 7)
            Text(Int(side.median.rounded()).formatted()).font(ThermoFont.mono(20, weight: .bold)).foregroundColor(Forge.ink0)
            Text("\(os) \(side.latest.osVersion ?? "?")").font(ThermoFont.mono(9, weight: .bold)).foregroundColor(Forge.ink0)
            if let b = side.latest.osBuild { Text("BUILD \(b)").font(ThermoFont.mono(6.5)).foregroundColor(Forge.ink1) }
            Text(side.runs.count == 1 ? "1 run" : "median of \(side.runs.count) runs").font(ThermoFont.mono(6.5)).foregroundColor(Forge.ink2)
        }
    }

    private func actionButton(_ text: String, _ accent: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text)
                .font(ThermoFont.mono(9, weight: .black)).tracking(1.2)
                .foregroundColor(accent)
                .frame(maxWidth: .infinity).padding(.vertical, 12)
                .background(accent.opacity(0.10))
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(accent.opacity(0.4), lineWidth: 1))
        }
    }

    /// "−7%", "+4%", "0%" — a proper minus sign.
    private func percent(_ d: Double) -> String {
        let p = Int((d * 100).rounded())
        return p > 0 ? "+\(p)%" : p < 0 ? "−\(-p)%" : "0%"
    }

    private func dateLabel(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "d MMM"
        return f.string(from: d).uppercased()
    }
}

/** Which benchmark update cards were dismissed (per OS transition, per result), on this device only. */
final class BenchmarkUpdateDismissals: ObservableObject {
    static let shared = BenchmarkUpdateDismissals()
    @Published private(set) var keys: Set<String>
    private static let key = "benchmarkUpdateImpact.dismissed"

    private init() { keys = Set(UserDefaults.standard.stringArray(forKey: Self.key) ?? []) }

    func dismiss(_ insight: BenchmarkUpdateImpact.Insight) {
        keys.insert(insight.dismissKey)
        UserDefaults.standard.set(Array(keys.suffix(50)), forKey: Self.key)
    }
}
