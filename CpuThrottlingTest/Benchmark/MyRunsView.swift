import SwiftUI

/**
 MY RUNS: every benchmark run kept on this device, newest first, with the score over time, each run's six sections, and
 a submit for a verified run that hasn't been sent. Opened through AppOverlays from the home screen and the leaderboard;
 it reads the history (BenchmarkHistoryStore) itself, so a submit here shows everywhere.
 */
struct MyRunsView: View {
    let onClose: () -> Void
    @ObservedObject private var store = BenchmarkHistoryStore.shared
    @ObservedObject private var network = NetworkMonitor.shared
    @Environment(\.openURL) private var openURL
    @State private var open: UUID?                  // the run whose sections are showing
    @State private var submittingId: UUID?
    @State private var note: (id: UUID, text: String)?   // what a run's submit answered

    private var history: MyRuns.History {
        MyRuns.build(store.records, deviceModel: LeaderboardService.shared.getDeviceModelName())
    }

    var body: some View {
        let h = history
        ZStack {
            Color.black.opacity(0.85).ignoresSafeArea().onTapGesture(perform: onClose)
            VStack(spacing: 14) {
                header
                Divider().background(Forge.hairline)
                if h.rows.isEmpty {
                    empty(h)
                } else {
                    ScrollView(.vertical, showsIndicators: false) {
                        LazyVStack(spacing: 8) {
                            summary(h)
                            if h.rows.count >= 2 { MyRunsChart(history: h) }
                            Text("TAP A RUN FOR ITS SECTIONS").font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.3))
                                .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 4).padding(.top, 4)
                            ForEach(h.rows) { row($0) }
                            VStack(alignment: .leading, spacing: 6) {
                                ForEach(MyRuns.footnotes(h, device: ShareService.noun), id: \.self) {
                                    Text($0).font(ThermoFont.mono(9)).foregroundColor(Forge.ink2).fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 4).padding(.top, 8)
                        }
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: Forge.columnWidth, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 24).fill(Forge.raised)
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Forge.hairline, lineWidth: 1)))
            .padding(16)
        }
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 3) {
                Text("MY RUNS").font(ThermoFont.mono(16, weight: .black)).tracking(1).foregroundColor(Forge.ink0)
                Text("Every benchmark on this \(ShareService.noun)").font(ThermoFont.mono(10)).foregroundColor(Forge.ink0.opacity(0.4))
            }
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundColor(Forge.ink0)
                    .frame(width: 32, height: 32).background(Forge.ink0.opacity(0.08)).clipShape(Circle())
            }
        }
    }

    private func empty(_ h: MyRuns.History) -> some View {
        VStack(spacing: 10) {
            Spacer()
            Text("No benchmark runs yet").font(ThermoFont.mono(13, weight: .bold)).foregroundColor(Forge.ink0)
            Text("Run the benchmark and every result is kept here, so you can see how your \(ShareService.noun) does over time.")
                .font(ThermoFont.mono(10)).foregroundColor(Forge.ink1).multilineTextAlignment(.center)
            ForEach(MyRuns.footnotes(h, device: ShareService.noun).dropLast(), id: \.self) {
                Text($0).font(ThermoFont.mono(9)).foregroundColor(Forge.ink2).multilineTextAlignment(.center)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: BEST · LATEST · RUNS

    private func summary(_ h: MyRuns.History) -> some View {
        HStack(alignment: .top, spacing: 8) {
            if let b = h.best { cell("BEST", b.score.formatted(), ShareLinks.cardDate(b.timestamp), Forge.phaseMeasured) }
            if let l = h.latest { cell("LATEST", l.score.formatted(), ShareLinks.cardDate(l.timestamp), Forge.ink0) }
            cell("RUNS", "\(h.rows.count)", "\(h.verified) verified", Forge.ink0)
        }
        .padding(14)
        .background(Forge.ink0.opacity(0.03)).cornerRadius(16)
    }

    private func cell(_ label: String, _ value: String, _ sub: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(ThermoFont.mono(8, weight: .black)).foregroundColor(Forge.ink0.opacity(0.4))
            Text(value).font(ThermoFont.mono(17, weight: .black)).foregroundColor(color).lineLimit(1).minimumScaleFactor(0.7)
            Text(sub).font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.4)).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: a run

    private static let dateTime: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "d MMM yyyy · HH:mm"
        return f
    }()

    private func os(_ r: BenchmarkRecord) -> String? {
        r.osVersion.map { LeaderboardService.osLabel(LeaderboardService.osPlatform, $0) }
    }

    private func row(_ row: MyRuns.Row) -> some View {
        let r = row.record
        let isOpen = open == r.id
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .lastTextBaseline, spacing: 6) {
                        Text(r.score.formatted()).font(ThermoFont.mono(15, weight: .black)).foregroundColor(Forge.ink0)
                        if row.isBest { Text("BEST").font(ThermoFont.mono(8, weight: .black)).foregroundColor(Forge.phaseMeasured) }
                    }
                    Text([Self.dateTime.string(from: r.timestamp), os(r)].compactMap { $0 }.joined(separator: " · "))
                        .font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.4)).lineLimit(1)
                }
                Spacer(minLength: 4)
                if let pct = row.changePct {
                    Text(MyRuns.changeText(pct)).font(ThermoFont.mono(10, weight: .bold))
                        .foregroundColor(pct >= 0.05 ? Forge.phaseMeasured : pct <= -0.05 ? Ramp.at(0.85) : Forge.ink2)
                }
                statusChip(row.status)
            }
            if isOpen { details(row).padding(.top, 10) }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Forge.surface).cornerRadius(14)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(row.isBest ? Forge.phaseMeasured.opacity(0.6) : Forge.ink0.opacity(0.06), lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(.easeOut(duration: 0.2)) { open = isOpen ? nil : r.id } }
    }

    private func statusChip(_ status: MyRuns.Status) -> some View {
        let (label, color): (String, Color) = switch status {
        case .submitted: ("SUBMITTED", Forge.phaseMeasured)
        case .notSubmitted: ("NOT SUBMITTED", Ramp.at(0.5))
        case .unverified: ("UNVERIFIED", Forge.ink2)
        }
        return Text(label).font(ThermoFont.mono(7, weight: .black)).foregroundColor(color)
            .padding(.horizontal, 6).padding(.vertical, 4)
            .background(color.opacity(0.12)).cornerRadius(6)
            .fixedSize()
    }

    @ViewBuilder private func details(_ row: MyRuns.Row) -> some View {
        let r = row.record
        VStack(alignment: .leading, spacing: 8) {
            sections(r)
            Text([os(r), r.osBuild, r.gpuName.isEmpty ? nil : r.gpuName].compactMap { $0 }.joined(separator: " · "))
                .font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.4))
            if r.validityFlags != 0 {
                Text(BenchmarkResultView.validityNote(r.validityFlags) + " Only verified runs can go to the leaderboard.")
                    .font(ThermoFont.mono(9)).foregroundColor(Ramp.at(0.5)).fixedSize(horizontal: false, vertical: true)
            }
            if row.canSubmit && submittingId == r.id {
                ProgressView().tint(Forge.phasePreFlight)
            } else if row.canSubmit && network.isConnected {
                actionPill("SUBMIT ↑", Forge.phasePreFlight) { submit(r) }
            } else if row.canSubmit {
                Text("Not on the leaderboard yet. Turn on the internet to submit it.")
                    .font(ThermoFont.mono(9)).foregroundColor(Ramp.at(0.5)).fixedSize(horizontal: false, vertical: true)
            } else if row.status == .notSubmitted {
                // iOS sends only complete runs (every section ran), as the result screen and the leaderboard do
                Text("Not every section finished, so this run can't go to the leaderboard.")
                    .font(ThermoFont.mono(9)).foregroundColor(Forge.ink1).fixedSize(horizontal: false, vertical: true)
            } else if r.submitted, let id = r.serverId, let url = URL(string: ShareLinks.run(id)) {
                actionPill("↗ OPEN ITS PAGE", Forge.phaseMeasured) { openURL(url) }
            }
            if let note, note.id == r.id {
                Text(note.text).font(ThermoFont.mono(9)).foregroundColor(Forge.ink1).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// The six section scores, two rows of three, each in its section's colour.
    private func sections(_ r: BenchmarkRecord) -> some View {
        let s = r.sectionScores
        let cells: [(String, Int, Color)] = [("CPU", s["cpu"] ?? 0, BenchColor.cpu), ("MEM", s["mem"] ?? 0, BenchColor.mem),
                                             ("GPU", s["gpu"] ?? 0, Forge.phaseCalibration), ("UX", s["ux"] ?? 0, Forge.phaseMeasured),
                                             ("AI", s["ai"] ?? 0, BenchColor.ai), ("LLM", s["llm"] ?? 0, BenchColor.llm)]
        return VStack(spacing: 6) {
            ForEach(0..<2, id: \.self) { line in
                HStack(spacing: 6) {
                    ForEach(0..<3, id: \.self) { i in
                        let (label, score, color) = cells[line * 3 + i]
                        VStack(alignment: .leading, spacing: 1) {
                            Text(label).font(ThermoFont.mono(8, weight: .black)).foregroundColor(color)
                            Text(score.formatted()).font(ThermoFont.mono(12, weight: .black)).foregroundColor(Forge.ink0)
                                .lineLimit(1).minimumScaleFactor(0.7)
                        }
                        .padding(.horizontal, 8).padding(.vertical, 6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(color.opacity(0.08)).cornerRadius(8)
                    }
                }
            }
        }
    }

    private func actionPill(_ label: String, _ color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(ThermoFont.mono(9, weight: .black)).tracking(0.5).foregroundColor(color)
                .frame(maxWidth: .infinity).padding(.vertical, 9)
                .background(color.opacity(0.1)).cornerRadius(8)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(color.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private func submit(_ r: BenchmarkRecord) {
        submittingId = r.id
        note = nil
        Task { @MainActor in
            do {
                let res = try await BenchmarkUpload.submitAndRemember(r)
                note = (r.id, "Submitted · rank #\(res.rank)")
            } catch {
                note = (r.id, "Submit failed: \(error.localizedDescription)")
            }
            submittingId = nil
        }
    }
}

/** Total score per run, oldest to newest, evenly spaced: filled dots are verified runs, hollow ones unverified, the ring the best. */
private struct MyRunsChart: View {
    let history: MyRuns.History

    var body: some View {
        let runs = history.chronological
        let scores = runs.map { $0.record.score }
        let lo = scores.min() ?? 0, hi = scores.max() ?? 0
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("SCORE OVER TIME").font(ThermoFont.mono(8, weight: .black)).foregroundColor(Forge.ink0.opacity(0.4))
                Spacer()
                Text("\(lo.formatted()) – \(hi.formatted())").font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.4))
            }
            Canvas { ctx, size in
                let pad: CGFloat = 8
                // a flat history sits mid-height; otherwise the lowest run near the bottom, the highest near the top
                let span = CGFloat(hi - lo)
                func y(_ s: Int) -> CGFloat { span <= 0 ? size.height / 2 : pad + (size.height - 2 * pad) * (1 - CGFloat(s - lo) / span) }
                func x(_ i: Int) -> CGFloat { pad + (size.width - 2 * pad) * CGFloat(i) / CGFloat(max(1, runs.count - 1)) }
                for g in 0...2 {
                    let gy = pad + (size.height - 2 * pad) * CGFloat(g) / 2
                    var grid = Path(); grid.move(to: CGPoint(x: 0, y: gy)); grid.addLine(to: CGPoint(x: size.width, y: gy))
                    ctx.stroke(grid, with: .color(.white.opacity(0.05)), lineWidth: 1)
                }
                var line = Path()
                for (i, r) in runs.enumerated() {
                    let p = CGPoint(x: x(i), y: y(r.record.score))
                    if i == 0 { line.move(to: p) } else { line.addLine(to: p) }
                }
                ctx.stroke(line, with: .color(Forge.phaseMeasured.opacity(0.6)), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                let dot: CGFloat = 3.5
                for (i, r) in runs.enumerated() {
                    let c = CGPoint(x: x(i), y: y(r.record.score))
                    func circle(_ radius: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - radius, y: c.y - radius, width: 2 * radius, height: 2 * radius)) }
                    if r.isBest { ctx.stroke(circle(dot * 2), with: .color(Forge.phaseMeasured), lineWidth: 1.5) }
                    if r.record.validityFlags == 0 {
                        ctx.fill(circle(dot), with: .color(Forge.phaseMeasured))
                    } else {
                        ctx.fill(circle(dot), with: .color(Forge.raised))
                        ctx.stroke(circle(dot), with: .color(Forge.ink2), lineWidth: 1.5)
                    }
                }
            }
            .frame(height: 110)
            HStack {
                Text(runs.first.map { ShareLinks.cardDate($0.record.timestamp) } ?? "")
                Spacer()
                Text(runs.last.map { ShareLinks.cardDate($0.record.timestamp) } ?? "")
            }
            .font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.4))
            Text("● verified   ○ unverified   ◎ best").font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.3))
        }
        .padding(14)
        .background(Forge.ink0.opacity(0.03)).cornerRadius(16)
    }
}

/** The way into MY RUNS from the leaderboard: how many runs this device has kept, and its best. */
struct MyRunsEntry: View {
    @ObservedObject private var store = BenchmarkHistoryStore.shared

    var body: some View {
        let h = MyRuns.build(store.records, deviceModel: LeaderboardService.shared.getDeviceModelName())
        if !h.rows.isEmpty {
            Button { withAnimation(.easeOut(duration: 0.25)) { AppOverlays.shared.myRuns = true } } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("MY RUNS").font(ThermoFont.mono(11, weight: .black)).tracking(1).foregroundColor(Forge.phaseMeasured)
                        let runs = h.rows.count == 1 ? "1 run on this \(ShareService.noun)" : "\(h.rows.count) runs on this \(ShareService.noun)"
                        Text([runs, h.best.map { "best \($0.score.formatted())" }].compactMap { $0 }.joined(separator: " · "))
                            .font(ThermoFont.mono(9)).foregroundColor(Forge.ink0.opacity(0.5))
                    }
                    Spacer()
                    Text("›").font(ThermoFont.mono(18)).foregroundColor(Forge.phaseMeasured)
                }
                .padding(.horizontal, 12).padding(.vertical, 10)
                .background(Forge.phaseMeasured.opacity(0.06)).cornerRadius(14)
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Forge.phaseMeasured.opacity(0.3), lineWidth: 1))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }
}
