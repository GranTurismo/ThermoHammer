import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// UPDATE IMPACT CARD (iOS)
//
//   The phone compared with ITSELF across an OS update. Two states:
//     PROMPT — the OS changed and there is no usable test since: offer to repeat the old one
//     RESULT — before → after, medians of like-for-like clean runs, with a verdict that
//              only appears when the change is bigger than normal run-to-run noise
//   Wording is deliberately temporal ("after update"), never causal.
// ─────────────────────────────────────────────────────────────────────────────

struct UpdateImpactCard: View {
    let insight: UpdateImpact.Insight
    let onRetest: (RunRecord) -> Void
    let onDismiss: () -> Void

    var body: some View {
        switch insight {
        case .prompt(let p):     promptCard(p)
        case .comparison(let c): comparisonCard(c)
        }
    }

    // MARK: prompt

    private func promptCard(_ p: UpdateImpact.Prompt) -> some View {
        let accent = Forge.phasePreFlight
        return shell(accent) {
            HStack(spacing: 8) {
                StatusLED(color: accent)
                Engraved(text: "UPDATE DETECTED", color: accent, size: 10)
                Spacer()
                Button(action: onDismiss) { Engraved(text: "NOT NOW", size: 7.5) }
                    .padding(.horizontal, 6).padding(.vertical, 4)
            }
            HStack(alignment: .center) {
                osColumn("WAS", name: osName(p.last.osVersion), detail: osDetail(p.last.osBuild), alignment: .leading)
                Spacer(minLength: 6)
                Text("→").font(ThermoFont.mono(16)).foregroundColor(Forge.ink2)
                Spacer(minLength: 6)
                osColumn("NOW", name: osName(p.nowOsVersion), detail: osDetail(p.nowOsBuild), alignment: .trailing)
            }
            Text(String(format: "Your last test: %.1f%% · %@ · %@", p.last.capacity,
                        settingsLabel(p.last), dateLabel(p.last.timestamp)))
                .font(ThermoFont.mono(9.5)).foregroundColor(Forge.ink0)
            Text("Run the same test again to see whether this update changed how your phone holds up under load.")
                .font(ThermoFont.mono(8)).foregroundColor(Forge.ink1)
            actionButton("RUN SAME TEST", accent) { onRetest(p.last) }
        }
    }

    // MARK: result

    private func comparisonCard(_ c: UpdateImpact.Comparison) -> some View {
        let tint = verdictColor(c.verdict)
        return shell(tint) {
            HStack {
                Engraved(text: "UPDATE IMPACT", color: Forge.ink1, size: 10)
                Spacer()
                Button(action: onDismiss) {
                    Text("✕").font(ThermoFont.mono(11)).foregroundColor(Forge.ink2)
                }
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
                Text(signed(c.deltaPts) + " pts")
                    .font(ThermoFont.display(30)).foregroundColor(tint)
                Engraved(text: verdictText(c.verdict), color: tint, size: 8.5)
            }
            Text(c.verdict == .noClearChange
                 ? String(format: "Within normal run-to-run variation (±%.0f pts). Nothing here shows the update changed your phone.", c.thresholdPts)
                 : String(format: "Bigger than normal run-to-run variation (±%.0f pts) for this phone and test.", c.thresholdPts))
                .font(ThermoFont.mono(8)).foregroundColor(Forge.ink1)
            Text("Same phone, same settings, clean runs only. This shows what changed after the update — " +
                 "not what caused it. Room temperature, case and battery level still matter.")
                .font(ThermoFont.mono(7)).foregroundColor(Forge.ink2)
            if c.weak {
                Text("Based on \(runs(c.before.runs.count)) before and \(runs(c.after.runs.count)) after — run again to confirm.")
                    .font(ThermoFont.mono(8)).foregroundColor(Ramp.at(0.5))
                actionButton("RUN AGAIN TO CONFIRM", tint) { onRetest(c.before.latest) }
            }
        }
    }

    // MARK: pieces

    private func shell<Content: View>(_ accent: Color, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10, content: content)
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Forge.surface)
            .cornerRadius(12)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(accent.opacity(0.25), lineWidth: 1))
            .cornerTicks(color: accent.opacity(0.5))
    }

    private func osColumn(_ tag: String, name: String, detail: String, alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 2) {
            Engraved(text: tag, size: 7)
            Text(name).font(ThermoFont.mono(12, weight: .bold)).foregroundColor(Forge.ink0)
            if !detail.isEmpty {
                Text(detail).font(ThermoFont.mono(7)).foregroundColor(Forge.ink1)
            }
        }
    }

    private func sideColumn(_ tag: String, _ side: UpdateImpact.Side, alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 2) {
            Engraved(text: tag, size: 7)
            Text(String(format: "%.1f%%", side.median))
                .font(ThermoFont.mono(20, weight: .bold)).foregroundColor(Ramp.forCapacity(side.median))
            Text(osName(side.latest.osVersion)).font(ThermoFont.mono(9, weight: .bold)).foregroundColor(Forge.ink0)
            let d = osDetail(side.latest.osBuild)
            if !d.isEmpty { Text(d).font(ThermoFont.mono(6.5)).foregroundColor(Forge.ink1) }
            Text(side.runs.count == 1 ? "1 run" : "median of \(side.runs.count) runs")
                .font(ThermoFont.mono(6.5)).foregroundColor(Forge.ink2)
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

    // MARK: formatting

    private func verdictColor(_ v: UpdateImpact.Verdict) -> Color {
        switch v {
        case .slower:        return Ramp.at(0.68)
        case .faster:        return Forge.phaseMeasured
        case .noClearChange: return Forge.ink1
        }
    }

    private func verdictText(_ v: UpdateImpact.Verdict) -> String {
        switch v {
        case .slower:        return "LOWER AFTER UPDATE"
        case .faster:        return "HIGHER AFTER UPDATE"
        case .noClearChange: return "NO CLEAR CHANGE"
        }
    }

    /// Proper minus sign; an exact zero stays unsigned.
    private func signed(_ v: Double) -> String {
        if v > 0.05 { return String(format: "+%.1f", v) }
        if v < -0.05 { return String(format: "−%.1f", -v) }
        return "0.0"
    }

    private func runs(_ n: Int) -> String { n == 1 ? "1 run" : "\(n) runs" }
    private func osName(_ version: String) -> String { LeaderboardService.osLabel(LeaderboardService.osPlatform, version) }
    private func osDetail(_ build: String?) -> String {
        guard let b = build, !b.isEmpty else { return "" }
        return "BUILD \(b)"
    }

    private func settingsLabel(_ r: RunRecord) -> String {
        let dur: String = { switch r.durationType { case 0: return "5 MIN"; case 1: return "15 MIN"; case 2: return "30 MIN"; default: return "?" } }()
        return "\(dur) · \(r.threadingType == 0 ? "1 THREAD" : "MULTI THREAD")"
    }

    private func dateLabel(_ ts: Double) -> String {
        let f = DateFormatter()
        f.dateFormat = "d MMM"
        return f.string(from: Date(timeIntervalSince1970: ts)).uppercased()
    }
}
