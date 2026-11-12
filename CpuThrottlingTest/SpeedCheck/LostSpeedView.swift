import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// FIND MY LOST SPEED (iOS) — intro → a 30-second check → what's holding the
// phone back → "I changed it — check again" → before and after, side by side.
// iOS has no settings deep link for any of it: the steps are text.
// ─────────────────────────────────────────────────────────────────────────────

struct LostSpeedView: View {
    let onClose: () -> Void
    @ObservedObject private var runner = SpeedCheckRunner.shared
    @ObservedObject private var stress = StressEngine.shared
    @ObservedObject private var bench = BenchmarkEngine.shared

    private var noun: String { ShareService.noun }
    /// Never alongside a benchmark or a stress run: they'd measure each other.
    private var busy: Bool { stress.isRunning || bench.isRunning }

    /// The last benchmark's Phone Health, when it found this model slower than its median.
    private var healthNote: SpeedCheck.HealthNote? {
        guard let memo = PhoneHealthMemo.last, memo.slower, memo.model == LeaderboardService.shared.getDeviceModelName() else { return nil }
        return SpeedCheck.HealthNote(percentBelow: max(0, 100 - memo.percent), model: memo.model)
    }

    var body: some View {
        ForgeOverlayScrim {
            ForgeCard {
                ViewThatFits(in: .vertical) {
                    content
                    ScrollView(showsIndicators: false) { content }
                }
            }
            .frame(maxWidth: 400)
            .padding(.horizontal, 14).padding(.vertical, 24)
        }
        .onAppear { runner.dropStale() }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                StatusLED(color: Forge.phaseMeasured, active: runner.running)
                Engraved(text: "FIND MY LOST SPEED", color: Forge.phaseMeasured, size: 11)
                Spacer()
                Button {
                    if runner.running { runner.cancel("The check was stopped.") }
                    onClose()
                } label: {
                    Text("✕").font(ThermoFont.mono(12)).foregroundColor(Forge.ink2).padding(.horizontal, 6)
                }
                .keyboardShortcut(.cancelAction)
            }
            if runner.running {
                running
            } else if let a = runner.first, let b = runner.second {
                comparison(a, b)
            } else if let a = runner.first {
                diagnosis(a)
            } else {
                intro
            }
        }
    }

    // MARK: states

    private var intro: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("A 30-second check of how fast your \(noun) really runs, then what's slowing it down.")
                .font(ThermoFont.mono(10)).foregroundColor(Forge.ink0)
            stoppedNote
            if busy { busyNote }
            primary("START 30-SECOND CHECK") { runner.start(again: false) }.disabled(busy)
        }
    }

    private var running: some View {
        VStack(alignment: .leading, spacing: 10) {
            Engraved(text: runner.again ? "CHECK 2 · AFTER YOUR CHANGE" : "CHECK 1 · ALL CORES · 30 S", size: 8)
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                Text(rate(runner.live)).font(ThermoFont.mono(34, weight: .black)).foregroundColor(Forge.ink0)
                    .monospacedDigit()
                Engraved(text: "WORK/S", size: 8)
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Forge.ink0.opacity(0.07))
                    Capsule().fill(Forge.phaseMeasured)
                        .frame(width: g.size.width * CGFloat(min(1, runner.elapsed / Double(SpeedCheck.seconds))))
                }
            }
            .frame(height: 6)
            Text("\(max(0, SpeedCheck.seconds - Int(runner.elapsed))) s left · every core busy with the benchmark's CPU 4 work")
                .font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
            Sparkline(values: runner.perSecond, slots: SpeedCheck.seconds)
                .stroke(Forge.phaseMeasured, style: StrokeStyle(lineWidth: 1.5, lineJoin: .round))
                .frame(height: 56)
                .padding(8)
                .background(Forge.surface).cornerRadius(10)
            secondary("STOP") { runner.cancel("The check was stopped.") }
        }
    }

    private func diagnosis(_ m: SpeedCheck.Measurement) -> some View {
        let d = SpeedCheck.diagnose(m, mac: LeaderboardService.isMac, noun: noun, health: healthNote)
        return VStack(alignment: .leading, spacing: 12) {
            numbers(m)
            ForEach(Array(d.findings.enumerated()), id: \.offset) { _, f in
                let color = f.cause == .nothingFound ? Forge.phaseMeasured : f.cause == .heat ? Ramp.at(0.68) : Ramp.at(0.5)
                VStack(alignment: .leading, spacing: 4) {
                    Text(f.title).font(ThermoFont.mono(11, weight: .black)).foregroundColor(color)
                    Text(f.text).font(ThermoFont.mono(9)).foregroundColor(Forge.ink0)
                    if let s = f.steps { Text(s).font(ThermoFont.mono(9, weight: .bold)).foregroundColor(Forge.ink1) }
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(color.opacity(0.07)).cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(color.opacity(0.3), lineWidth: 1))
            }
            if let note = d.note { Text(note).font(ThermoFont.mono(9)).foregroundColor(Forge.ink1) }
            if d.foundCause {
                Text("Change the setting, come back, and check again — this check is kept for an hour.")
                    .font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
            }
            stoppedNote
            if busy { busyNote }
            if d.foundCause {
                primary("I CHANGED IT — CHECK AGAIN") { runner.start(again: true) }.disabled(busy)
                HStack(spacing: 10) {
                    secondary("START OVER") { runner.start(again: false) }.disabled(busy)
                    secondary("DONE", action: onClose)
                }
            } else {
                // nothing to change: no before/after, just done — or a fresh check
                primary("DONE", dims: false, action: onClose)
                secondary("CHECK AGAIN") { runner.start(again: false) }.disabled(busy)
            }
        }
    }

    private func comparison(_ a: SpeedCheck.Measurement, _ b: SpeedCheck.Measurement) -> some View {
        let c = SpeedCheck.compare(before: a, after: b, noun: noun)
        let color = c.verdict == .faster ? Forge.phaseMeasured : c.verdict == .slower ? Ramp.at(0.68) : Forge.ink1
        let top = max(1, [a.burst, a.sustained, b.burst, b.sustained].max() ?? 1)
        return VStack(alignment: .leading, spacing: 12) {
            Text(c.title).font(ThermoFont.display(30, weight: .bold)).foregroundColor(color)
            Text(c.text).font(ThermoFont.mono(10)).foregroundColor(Forge.ink0)
            HStack(alignment: .top, spacing: 14) {
                runColumn("BEFORE", a, top: top)
                runColumn("AFTER", b, top: top)
            }
            primary("DONE") {
                runner.reset()
                onClose()
            }
        }
    }

    // MARK: pieces

    private func numbers(_ m: SpeedCheck.Measurement) -> some View {
        HStack {
            stat("BURST", rate(m.burst)); Spacer()
            stat("SUSTAINED", rate(m.sustained)); Spacer()
            stat("DROP", "\(Int((max(0, m.drop) * 100).rounded()))%", m.drop >= SpeedCheck.dropThreshold ? Ramp.at(0.68) : Forge.phaseMeasured)
        }
    }

    private func stat(_ label: String, _ value: String, _ color: Color = Forge.ink0) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(ThermoFont.mono(16, weight: .black)).foregroundColor(color)
            Engraved(text: label, size: 7)
        }
    }

    private func runColumn(_ tag: String, _ m: SpeedCheck.Measurement, top: Double) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Engraved(text: tag, size: 8)
            bar("BURST", m.burst, top: top, color: Forge.ink1)
            bar("SUSTAINED", m.sustained, top: top, color: Forge.phaseMeasured)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func bar(_ label: String, _ v: Double, top: Double, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(label).font(ThermoFont.mono(7, weight: .bold)).foregroundColor(Forge.ink2)
                Spacer()
                Text(rate(v)).font(ThermoFont.mono(9, weight: .bold)).foregroundColor(Forge.ink0)
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Forge.ink0.opacity(0.07))
                    Capsule().fill(color).frame(width: max(6, g.size.width * CGFloat(v / top)))
                }
            }
            .frame(height: 8)
        }
    }

    @ViewBuilder private var stoppedNote: some View {
        if let s = runner.stopped { Text(s).font(ThermoFont.mono(9)).foregroundColor(Ramp.at(0.5)) }
    }

    private var busyNote: some View {
        Text("A test is running — the check can start when it's done.").font(ThermoFont.mono(9)).foregroundColor(Ramp.at(0.5))
    }

    private func rate(_ v: Double) -> String { v > 0 ? (v < 100 ? String(format: "%.1f", v) : String(format: "%.0f", v)) : "—" }

    /// `dims`: greyed out while a test runs (a button that starts a check); DONE never is.
    private func primary(_ text: String, dims: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(ThermoFont.mono(11, weight: .black)).foregroundColor(Forge.bg)
                .lineLimit(1).minimumScaleFactor(0.7)
                .frame(maxWidth: .infinity).padding(.vertical, 12)
                .background(dims && busy ? Forge.ink2.opacity(0.4) : Forge.phaseMeasured).cornerRadius(10)
        }
    }

    private func secondary(_ text: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(ThermoFont.mono(10, weight: .bold)).foregroundColor(Forge.ink1)
                .frame(maxWidth: .infinity).padding(.vertical, 10)
                .background(Forge.interact).cornerRadius(10)
        }
    }
}

/** The check's seconds so far, across the 30 it will have; scaled to its own peak. */
struct Sparkline: Shape {
    let values: [Double]
    let slots: Int

    func path(in rect: CGRect) -> Path {
        var p = Path()
        guard values.count >= 2, slots > 1, let top = values.max(), top > 0 else { return p }
        for (i, v) in values.enumerated() {
            let pt = CGPoint(x: rect.minX + rect.width * CGFloat(i) / CGFloat(slots - 1),
                             y: rect.maxY - rect.height * CGFloat(v / (top * 1.1)))
            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        return p
    }
}
