import SwiftUI

/** The two sides of a comparison: cyan and amber, the pair the Windows app's run comparison uses too. */
enum CompareColor {
    static let a = Color(red: 0, green: 230 / 255, blue: 1)
    static let b = Color(red: 1, green: 171 / 255, blue: 0)
    static func of(_ lead: Lead) -> Color { lead == .a ? a : lead == .b ? b : Forge.ink1 }
}

private func sectionColor(_ key: String) -> Color {
    switch key {
    case "cpu": return BenchColor.cpu
    case "mem": return BenchColor.mem
    case "gpu": return Forge.phaseCalibration
    case "ai": return BenchColor.ai
    case "llm": return BenchColor.llm
    default: return Forge.phaseMeasured
    }
}

private func lit(_ duel: Duel, _ side: Lead) -> Bool { duel.winner == side || duel.winner == .even }
private func beaten(_ duel: Duel, _ side: Lead) -> Bool { (duel.winner == .a || duel.winner == .b) && duel.winner != side }

/**
 Two leaderboard runs head to head: who leads overall and by how much, the shape of both across the six sections, every
 section as mirrored bars that open into each test's numbers (the better one lit in its side's colour), and the facts of
 both devices. `a` and `b` are board entries; their full runs are loaded here. Android: BenchmarkCompareOverlay.kt.
 */
struct BenchmarkCompareOverlay: View {
    let a: BenchmarkDto
    let rankA: Int
    let b: BenchmarkDto
    let rankB: Int
    let onClose: () -> Void

    @State private var details: (BenchmarkDetail, BenchmarkDetail)?
    @State private var errorMessage: String?
    @State private var swapped = false
    @State private var open: Set<String> = []
    @State private var appeared = false

    private var entryA: BenchmarkDto { swapped ? b : a }
    private var entryB: BenchmarkDto { swapped ? a : b }
    private var shownRankA: Int { swapped ? rankB : rankA }
    private var shownRankB: Int { swapped ? rankA : rankB }
    private var comparison: BenchmarkComparison? {
        guard let d = details else { return nil }
        return swapped ? .make(d.1, d.0) : .make(d.0, d.1)
    }

    var body: some View {
        ZStack {
            Color.black.opacity(0.88).ignoresSafeArea().onTapGesture(perform: onClose)
            VStack(spacing: 10) {
                topBar
                legend
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(spacing: 10) {
                        if let c = comparison {
                            content(c)
                        } else if let errorMessage {
                            VStack(spacing: 10) {
                                Text("⚠ \(errorMessage)").font(ThermoFont.mono(12, weight: .bold)).foregroundColor(Ramp.at(0.85))
                                    .multilineTextAlignment(.center)
                                Button { Task { await load() } } label: {
                                    Text("RETRY").font(ThermoFont.mono(10)).foregroundColor(Forge.ink0)
                                        .padding(.horizontal, 14).padding(.vertical, 6).background(Forge.ink0.opacity(0.1)).cornerRadius(8)
                                }
                            }
                            .padding(30)
                        } else {
                            VStack(spacing: 12) {
                                ProgressView().tint(Forge.phasePreFlight)
                                Text("LOADING BOTH RUNS…").font(ThermoFont.mono(10)).foregroundColor(Forge.ink0.opacity(0.4))
                            }
                            .padding(50)
                        }
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: Forge.columnWidth, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 24).fill(Forge.raised)
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Forge.hairline, lineWidth: 1)))
            .padding(12)
        }
        .task { await load() }
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Text("COMPARE").font(ThermoFont.mono(12, weight: .black)).tracking(2).foregroundColor(Forge.ink0)
            Spacer()
            roundButton("arrow.left.arrow.right") { withAnimation(.easeInOut(duration: 0.25)) { swapped.toggle() } }
            roundButton("xmark", action: onClose)
        }
    }

    /// Which colour is which, always in view.
    private var legend: some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Circle().fill(CompareColor.a).frame(width: 8, height: 8)
                Text(entryA.name).font(ThermoFont.mono(10, weight: .bold)).foregroundColor(CompareColor.a).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text("VS").font(ThermoFont.mono(8, weight: .black)).foregroundColor(Forge.ink2)
            HStack(spacing: 6) {
                Text(entryB.name).font(ThermoFont.mono(10, weight: .bold)).foregroundColor(CompareColor.b).lineLimit(1)
                Circle().fill(CompareColor.b).frame(width: 8, height: 8)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }

    private func roundButton(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 12, weight: .bold)).foregroundColor(Forge.ink0)
                .frame(width: 32, height: 32).background(Forge.ink0.opacity(0.08)).clipShape(Circle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func content(_ c: BenchmarkComparison) -> some View {
        HStack(spacing: 6) {
            deviceCard(entryA, rank: shownRankA, d: c.a, letter: "A", color: CompareColor.a)
            Text("VS").font(ThermoFont.mono(9, weight: .black)).foregroundColor(Forge.ink2)
            deviceCard(entryB, rank: shownRankB, d: c.b, letter: "B", color: CompareColor.b)
        }
        .fixedSize(horizontal: false, vertical: true)
        .onAppear { withAnimation(.easeOut(duration: 0.9)) { appeared = true } }
        verdict(c)
        card {
            Text("PROFILE").font(ThermoFont.mono(10, weight: .black)).tracking(1).foregroundColor(Forge.ink1)
            Text("Each axis: the better of the two reaches the edge").font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
            RadarView(sections: c.sections, progress: appeared ? 1 : 0).frame(height: 250)
        }
        HStack {
            Text("SECTION BY SECTION").font(ThermoFont.mono(10, weight: .black)).tracking(1).foregroundColor(Forge.ink1)
            Spacer()
            let all = Set(c.sections.map(\.key))
            Button {
                withAnimation(.easeOut(duration: 0.2)) { open = open.isSuperset(of: all) ? [] : all }
            } label: {
                Text(open.isSuperset(of: all) ? "CLOSE ALL" : "OPEN ALL").font(ThermoFont.mono(9, weight: .black)).foregroundColor(Forge.phaseMeasured)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 2).padding(.top, 4)
        ForEach(c.sections) { s in
            SectionDuelView(s: s, open: open.contains(s.key), appeared: appeared) {
                withAnimation(.easeOut(duration: 0.2)) { if open.contains(s.key) { open.remove(s.key) } else { open.insert(s.key) } }
            }
        }
        facts(c)
    }

    private func deviceCard(_ e: BenchmarkDto, rank: Int, d: BenchmarkDetail, letter: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(letter).font(ThermoFont.mono(11, weight: .black)).foregroundColor(Forge.bg)
                    .frame(width: 22, height: 22).background(color).clipShape(Circle())
                Spacer()
                if rank > 0 { Text("#\(rank)").font(ThermoFont.mono(12, weight: .black)).foregroundColor(Forge.ink1) }
            }
            Text(e.name).font(ThermoFont.mono(12, weight: .bold)).foregroundColor(Forge.ink0).lineLimit(2).padding(.top, 5)
            Text([d.socModel ?? e.socModel, d.gpuName ?? e.gpuName].compactMap { $0 }.joined(separator: " · "))
                .font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.45)).lineLimit(2)
            Text(LeaderboardService.osLabel(d.os, d.osVersion)).font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.45))
            Spacer(minLength: 8)
            Text(d.totalScore.formatted()).font(ThermoFont.mono(20, weight: .black)).foregroundColor(color)
            Text("TOTAL POINTS").font(ThermoFont.mono(7, weight: .bold)).foregroundColor(Forge.ink2)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(color.opacity(0.07))
        .cornerRadius(16)
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(color.opacity(0.4), lineWidth: 1))
    }

    private func verdict(_ c: BenchmarkComparison) -> some View {
        let color: Color = c.total.winner == .a || c.total.winner == .b ? CompareColor.of(c.total.winner) : Forge.ink0
        return card {
            Text(c.headline(nameA: entryA.name, nameB: entryB.name)).font(ThermoFont.mono(13, weight: .black)).foregroundColor(color)
                .fixedSize(horizontal: false, vertical: true)
            Text(c.sectionsLine()).font(ThermoFont.mono(9)).foregroundColor(Forge.ink1)
            SplitBar(shareA: appeared ? c.totalShareA : 0.5).frame(height: 12).padding(.top, 8)
            HStack {
                Text(c.a.totalScore.formatted()).foregroundColor(CompareColor.a)
                Spacer()
                Text("SHARE OF BOTH TOTALS").font(ThermoFont.mono(7, weight: .bold)).foregroundColor(Forge.ink2)
                Spacer()
                Text(c.b.totalScore.formatted()).foregroundColor(CompareColor.b)
            }
            .font(ThermoFont.mono(10, weight: .black))
            .padding(.top, 2)
        }
    }

    private func facts(_ c: BenchmarkComparison) -> some View {
        let rows: [(String, String?, String?)] = [
            ("CHIP", c.a.socModel, c.b.socModel),
            ("GPU", c.a.gpuName, c.b.gpuName),
            ("SYSTEM", LeaderboardService.osLabel(c.a.os, c.a.osVersion), LeaderboardService.osLabel(c.b.os, c.b.osVersion)),
            ("BUILD", c.a.osBuild, c.b.osBuild),
            ("APP", c.a.appVersion, c.b.appVersion),
            ("RUN DATE", c.a.date, c.b.date),
            ("RUNS ON THE BOARD", "\(entryA.runs)", "\(entryB.runs)"),
        ]
        return card {
            Text("DEVICES").font(ThermoFont.mono(10, weight: .black)).tracking(1).foregroundColor(Forge.ink1)
            ForEach(rows, id: \.0) { label, x, y in
                Text(label).font(ThermoFont.mono(7, weight: .bold)).tracking(0.5).foregroundColor(Forge.ink2)
                    .frame(maxWidth: .infinity).padding(.top, 6)
                HStack(alignment: .top, spacing: 10) {
                    Text(x ?? "—").foregroundColor(CompareColor.a.opacity(0.9)).frame(maxWidth: .infinity, alignment: .leading)
                    Text(y ?? "—").foregroundColor(CompareColor.b.opacity(0.9)).multilineTextAlignment(.trailing)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                .font(ThermoFont.mono(9))
            }
        }
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 4, content: content)
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Forge.surface)
            .cornerRadius(16)
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Forge.hairline, lineWidth: 1))
    }

    private func load() async {
        errorMessage = nil
        guard details == nil else { return }
        do {
            async let da = BenchmarkUpload.fetchDetail(id: a.id)
            async let db = BenchmarkUpload.fetchDetail(id: b.id)
            let (x, y) = try await (da, db)
            guard let x, let y else { errorMessage = "One of these runs is no longer on the board"; return }
            details = (x, y)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/** A hexagon of the sections: each axis scaled so the better of the two touches the edge, A drawn over B. */
private struct RadarView: View, Animatable {
    let sections: [ComparedSection]
    var progress: Double
    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        Canvas { ctx, size in
            let n = sections.count
            guard n >= 3 else { return }
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let r = min(size.width, size.height) / 2 * 0.72
            func at(_ i: Int, _ f: Double) -> CGPoint {
                let angle = -Double.pi / 2 + 2 * Double.pi * Double(i) / Double(n)
                return CGPoint(x: c.x + CGFloat(cos(angle) * f) * r, y: c.y + CGFloat(sin(angle) * f) * r)
            }
            func polygon(_ fractions: [Double]) -> Path {
                var p = Path()
                for (i, f) in fractions.enumerated() {
                    if i == 0 { p.move(to: at(i, f)) } else { p.addLine(to: at(i, f)) }
                }
                p.closeSubpath()
                return p
            }
            for level in 1...4 {
                ctx.stroke(polygon(Array(repeating: Double(level) / 4, count: n)),
                           with: .color(Forge.ink0.opacity(level == 4 ? 0.16 : 0.06)), lineWidth: 1)
            }
            for i in 0..<n {
                var spoke = Path()
                spoke.move(to: c)
                spoke.addLine(to: at(i, 1))
                ctx.stroke(spoke, with: .color(Forge.ink0.opacity(0.07)), lineWidth: 1)
            }
            func shape(_ shares: [Double], _ color: Color) {
                let p = polygon(shares.map { $0 * progress })
                ctx.fill(p, with: .color(color.opacity(0.16)))
                ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 2, lineJoin: .round))
                for (i, s) in shares.enumerated() {
                    let q = at(i, s * progress)
                    ctx.fill(Path(ellipseIn: CGRect(x: q.x - 3, y: q.y - 3, width: 6, height: 6)), with: .color(color))
                }
            }
            shape(sections.map(\.shareB), CompareColor.b)
            shape(sections.map(\.shareA), CompareColor.a)
            for (i, s) in sections.enumerated() {
                ctx.draw(Text(s.short).font(ThermoFont.mono(10, weight: .black)).foregroundColor(CompareColor.of(s.duel.winner)), at: at(i, 1.2))
            }
        }
    }
}

/** The totals as one bar: A's share from the left, B's from the right. */
private struct SplitBar: View {
    let shareA: Double

    var body: some View {
        GeometryReader { g in
            let f = CGFloat(max(0.02, min(0.98, shareA)))
            HStack(spacing: 2) {
                LinearGradient(colors: [CompareColor.a.opacity(0.55), CompareColor.a], startPoint: .leading, endPoint: .trailing)
                    .frame(width: max(0, g.size.width - 2) * f)
                LinearGradient(colors: [CompareColor.b, CompareColor.b.opacity(0.55)], startPoint: .leading, endPoint: .trailing)
            }
            .clipShape(Capsule())
        }
        .animation(.easeOut(duration: 0.8), value: shareA)
    }
}

/** One side's half of a mirrored bar: A grows from the middle to the left, B to the right. */
private struct HalfBar: View {
    let share: Double
    let color: Color
    let strong: Bool
    let towardLeading: Bool
    let height: CGFloat

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: towardLeading ? .trailing : .leading) {
                Capsule().fill(Forge.ink0.opacity(0.05))
                Capsule().fill(color.opacity(strong ? 1 : 0.4)).frame(width: g.size.width * CGFloat(max(0, min(1, share))))
            }
        }
        .frame(height: height)
        .animation(.easeOut(duration: 0.7), value: share)
    }
}

private struct SectionDuelView: View {
    let s: ComparedSection
    let open: Bool
    let appeared: Bool
    let toggle: () -> Void

    var body: some View {
        let accent = sectionColor(s.key)
        VStack(spacing: 6) {
            HStack(spacing: 6) {
                ScoreCell(score: s.scoreA, duel: s.duel, side: .a).frame(width: 58, alignment: .leading)
                HalfBar(share: appeared ? s.shareA : 0, color: CompareColor.a, strong: lit(s.duel, .a), towardLeading: true, height: 8)
                VStack(spacing: 2) {
                    Text(s.short).font(ThermoFont.mono(11, weight: .black)).tracking(1).foregroundColor(accent)
                    Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold)).foregroundColor(Forge.ink2)
                        .rotationEffect(.degrees(open ? 180 : 0))
                }
                .frame(width: 54)
                HalfBar(share: appeared ? s.shareB : 0, color: CompareColor.b, strong: lit(s.duel, .b), towardLeading: false, height: 8)
                ScoreCell(score: s.scoreB, duel: s.duel, side: .b).frame(width: 58, alignment: .trailing)
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: toggle)
            if open {
                ForEach(s.groups) { GroupDuelView(g: $0, accent: accent, appeared: appeared) }
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 12)
        .background(Forge.surface)
        .cornerRadius(14)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(accent.opacity(open ? 0.45 : 0.14), lineWidth: 1))
    }
}

private struct ScoreCell: View {
    let score: Int?
    let duel: Duel
    let side: Lead

    var body: some View {
        let won = duel.winner == side
        VStack(alignment: side == .b ? .trailing : .leading, spacing: 1) {
            Text(score.map { $0.formatted() } ?? "—").font(ThermoFont.mono(13, weight: .black))
                .foregroundColor(won ? CompareColor.of(side) : beaten(duel, side) ? Forge.ink0.opacity(0.45) : Forge.ink0)
            Text(won ? (duel.advantage.map(advantageText) ?? " ") : duel.winner == .even ? "EVEN" : " ")
                .font(ThermoFont.mono(8, weight: .black)).foregroundColor(won ? CompareColor.of(side) : Forge.ink2)
        }
    }
}

private struct GroupDuelView: View {
    let g: ComparedGroup
    let accent: Color
    let appeared: Bool

    var body: some View {
        VStack(spacing: 4) {
            HStack(alignment: .center) {
                Text(g.pointsA.map { $0.formatted() } ?? "").foregroundColor(pointsColor(.a)).frame(maxWidth: .infinity, alignment: .leading)
                Text(g.title.uppercased()).font(ThermoFont.mono(8, weight: .black)).tracking(0.5).foregroundColor(accent.opacity(0.9))
                    .multilineTextAlignment(.center).lineLimit(2).frame(maxWidth: .infinity).layoutPriority(1)
                Text(g.pointsB.map { $0.formatted() } ?? "").foregroundColor(pointsColor(.b)).frame(maxWidth: .infinity, alignment: .trailing)
            }
            .font(ThermoFont.mono(9, weight: .black))
            if g.pointsA != nil || g.pointsB != nil {
                HStack(spacing: 6) {
                    HalfBar(share: appeared ? g.shareA : 0, color: CompareColor.a, strong: lit(g.duel, .a), towardLeading: true, height: 4)
                    HalfBar(share: appeared ? g.shareB : 0, color: CompareColor.b, strong: lit(g.duel, .b), towardLeading: false, height: 4)
                }
            }
            ForEach(g.rows) { r in
                VStack(spacing: 1) {
                    Text(r.label).font(ThermoFont.mono(8)).foregroundColor(Forge.ink2).frame(maxWidth: .infinity)
                    HStack(alignment: .top, spacing: 10) {
                        ValueCell(text: r.a, duel: r.duel, side: .a)
                        ValueCell(text: r.b, duel: r.duel, side: .b)
                    }
                }
                .padding(.top, 3)
            }
        }
        .padding(.top, 10)
    }

    private func pointsColor(_ side: Lead) -> Color {
        g.duel.winner == side ? CompareColor.of(side) : beaten(g.duel, side) ? Forge.ink0.opacity(0.45) : Forge.ink1
    }
}

private struct ValueCell: View {
    let text: String?
    let duel: Duel
    let side: Lead

    var body: some View {
        let won = duel.winner == side
        let color: Color = won ? CompareColor.of(side) : beaten(duel, side) ? Forge.ink0.opacity(0.42) : Forge.ink0.opacity(0.85)
        let badge = won ? duel.advantage.map { "  " + advantageText($0) } : nil
        (Text(text ?? "—").font(ThermoFont.mono(10, weight: won ? .bold : .regular))
            + Text(badge ?? "").font(ThermoFont.mono(8, weight: .black)))
            .foregroundColor(color)
            .multilineTextAlignment(side == .b ? .trailing : .leading)
            .frame(maxWidth: .infinity, alignment: side == .b ? .trailing : .leading)
    }
}
