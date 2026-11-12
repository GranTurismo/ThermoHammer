import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// SHARE CARD — "Can your phone beat this?" A run drawn as a 1080 × 1350 image
// (not a screenshot: a SwiftUI view rendered at scale 1) and handed to the
// share sheet with a line of text and the run's link. The same data feeds the
// full-screen presentation (PresentView).
// ─────────────────────────────────────────────────────────────────────────────

/** One benchmark section on a card: "cpu"… "llm", its label and points. */
struct SectionScore: Identifiable, Equatable {
    let key: String
    let label: String
    let points: Int
    var id: String { key }

    /// The app's colour for each section (the result screen's).
    var color: Color {
        switch key {
        case "cpu": return BenchColor.cpu
        case "mem": return BenchColor.mem
        case "gpu": return Forge.phaseCalibration
        case "ai": return BenchColor.ai
        case "llm": return BenchColor.llm
        default: return Forge.phaseMeasured
        }
    }

    /// CPU, MEM, GPU, UX, AI, LLM — the sections this run has.
    static func of(_ r: BenchmarkResult) -> [SectionScore] {
        [("cpu", "CPU", r.cpuScore, r.cpu != nil), ("mem", "MEM", r.memScore, r.mem != nil), ("gpu", "GPU", r.gpuScore, !r.gpu.isEmpty),
         ("ux", "UX", r.uxScore, r.ux != nil), ("ai", "AI", r.aiScore, r.ai != nil), ("llm", "LLM", r.llmScore, r.llm != nil)]
            .filter { $0.3 }.map { SectionScore(key: $0.0, label: $0.1, points: $0.2) }
    }

    /// A leaderboard run's sections; one at 0 is one it didn't have (a run from before that section existed).
    static func of(_ e: BenchmarkDto) -> [SectionScore] {
        [("cpu", "CPU", e.cpuScore), ("mem", "MEM", e.memScore), ("gpu", "GPU", e.score), ("ux", "UX", e.uxScore),
         ("ai", "AI", e.aiScore), ("llm", "LLM", e.llmScore)]
            .filter { $0.2 > 0 }.map { SectionScore(key: $0.0, label: $0.1, points: $0.2) }
    }
}

/** A benchmark run as the share card and the presentation show it. */
struct BenchmarkCard {
    let device: String
    let os: String              // "iOS 26.4"
    let date: Date?
    let chip: String?
    let total: Int
    let sections: [SectionScore]
    let verified: Bool
    let serverId: Int?
    let mine: Bool              // this device's own run (the text says "My …")

    var url: String { ShareLinks.run(serverId) }
    var subline: String { [os, date.map(ShareLinks.cardDate), chip].compactMap { $0 }.joined(separator: " · ") }
    /// The presentation's line: OS · date · what ran.
    var settings: String { [os, date.map(ShareLinks.cardDate), chip, "BENCHMARK · \(sections.count) SECTIONS"].compactMap { $0 }.joined(separator: " · ") }
}

/** A stress run as the share card and the presentation show it. */
struct StressCard {
    let device: String
    let os: String
    let date: Date?
    let chip: String?
    let capacity: Double        // % of its speed it kept
    let minutes: Int            // the test's length
    let load: String            // CPU · GPU · CPU+GPU — always CPU on iOS
    let threads: String?        // "MULTI THREAD", when known
    let curve: [Double]         // % of baseline, evenly spaced over the run (StressCurve)
    let durationS: Double       // what the curve spans
    let onsetSec: Int?          // nil = never throttled
    let verified: Bool
    let serverId: Int?
    let mine: Bool

    var url: String { ShareLinks.stress(serverId) }
    var subline: String { [os, date.map(ShareLinks.cardDate), chip].compactMap { $0 }.joined(separator: " · ") }
    var loadLabel: String { "SPEED KEPT · \(minutes)-MIN \(load) LOAD" }
    /// The presentation's line: OS · date · the test's settings.
    var settings: String {
        [os, date.map(ShareLinks.cardDate), "\(minutes) MIN STRESS", threads, "\(load) LOAD"].compactMap { $0 }.joined(separator: " · ")
    }
    var onsetFraction: Double? { onsetSec.map { durationS > 0 ? min(1, max(0, Double($0) / durationS)) : 0 } }
}

enum ShareCard {
    case benchmark(BenchmarkCard)
    case stress(StressCard)

    var device: String { switch self { case .benchmark(let b): return b.device; case .stress(let s): return s.device } }
    var verified: Bool { switch self { case .benchmark(let b): return b.verified; case .stress(let s): return s.verified } }
    var subline: String { switch self { case .benchmark(let b): return b.subline; case .stress(let s): return s.subline } }
    var url: String { switch self { case .benchmark(let b): return b.url; case .stress(let s): return s.url } }
    var settings: String { switch self { case .benchmark(let b): return b.settings; case .stress(let s): return s.settings } }

    var text: String {
        switch self {
        case .benchmark(let b):
            return ShareLinks.benchmarkText(device: b.device, total: b.total.formatted(), url: b.url, mine: b.mine, noun: ShareService.noun)
        case .stress(let s):
            return ShareLinks.stressText(device: s.device, capacity: s.capacity, minutes: s.minutes, url: s.url, mine: s.mine, noun: ShareService.noun)
        }
    }

    func fileName(now: Date = Date()) -> String {
        switch self {
        case .benchmark(let b): return ShareLinks.imageFileName(stress: false, serverId: b.serverId, date: b.date ?? now)
        case .stress(let s): return ShareLinks.imageFileName(stress: true, serverId: s.serverId, date: s.date ?? now)
        }
    }
}

// MARK: - The card

struct ShareCardView: View {
    let card: ShareCard
    static let size = CGSize(width: 1080, height: 1350)

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center) {
                Text("THERMOHAMMER").font(ThermoFont.mono(28, weight: .black)).tracking(8).foregroundColor(Forge.phaseMeasured)
                Spacer()
                if card.verified {
                    Text("✓ VERIFIED").font(ThermoFont.mono(24, weight: .black)).tracking(2).foregroundColor(Forge.phaseMeasured)
                        .padding(.horizontal, 22).padding(.vertical, 10)
                        .background(Forge.phaseMeasured.opacity(0.14)).clipShape(Capsule())
                        .overlay(Capsule().stroke(Forge.phaseMeasured.opacity(0.5), lineWidth: 2))
                }
            }
            .frame(height: 52)
            Text(card.device).font(ThermoFont.display(64, weight: .bold)).foregroundColor(Forge.ink0)
                .lineLimit(2).truncationMode(.tail).minimumScaleFactor(0.85)
                .padding(.top, 40)
            Text(card.subline).font(ThermoFont.mono(30)).foregroundColor(Forge.ink2).lineLimit(1).minimumScaleFactor(0.6)
                .padding(.top, 10)
            switch card {
            case .benchmark(let b): benchmark(b)
            case .stress(let s): stress(s)
            }
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 12) {
                Rectangle().fill(Forge.ink0.opacity(0.10)).frame(height: 2).padding(.bottom, 16)
                Text(ShareLinks.challenge(noun: ShareService.noun)).font(ThermoFont.display(46, weight: .bold)).foregroundColor(Forge.ink0)
                    .lineLimit(1).minimumScaleFactor(0.7)
                Text(ShareLinks.display(card.url)).font(ThermoFont.mono(32, weight: .bold)).foregroundColor(Forge.phaseMeasured)
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
        }
        .padding(64)
        .frame(width: Self.size.width, height: Self.size.height, alignment: .topLeading)
        .background(
            ZStack {
                Forge.bg
                RadialGradient(colors: [glow.opacity(0.16), .clear], center: .topTrailing, startRadius: 0, endRadius: 900)
            }
        )
    }

    private var glow: Color {
        if case .stress(let s) = card { return Ramp.forCapacity(s.capacity) }
        return Forge.phaseMeasured
    }

    private func benchmark(_ b: BenchmarkCard) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(b.total.formatted()).font(ThermoFont.mono(190, weight: .black)).foregroundColor(Forge.ink0)
                .lineLimit(1).minimumScaleFactor(0.5)
                .padding(.top, 56)
            Text("BENCHMARK SCORE").font(ThermoFont.mono(30, weight: .bold)).tracking(6).foregroundColor(Forge.ink2)
            SectionBars(sections: b.sections, nameSize: 32, valueSize: 36, barHeight: 22, spacing: 30, nameWidth: 96, valueWidth: 190)
                .padding(.top, 64)
        }
    }

    private func stress(_ s: StressCard) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("\(Int(s.capacity.rounded()))%").font(ThermoFont.mono(190, weight: .black)).foregroundColor(Ramp.forCapacity(s.capacity))
                .lineLimit(1).minimumScaleFactor(0.5)
                .padding(.top, 30)
            Text(s.loadLabel).font(ThermoFont.mono(30, weight: .bold)).tracking(4).foregroundColor(Forge.ink2)
                .lineLimit(1).minimumScaleFactor(0.7)
            StressCurveChart(curve: s.curve, durationS: s.durationS, onsetFraction: s.onsetFraction, labelSize: 22, lineWidth: 6)
                .padding(28)
                .frame(height: 440)
                .background(Forge.surface)
                .cornerRadius(28)
                .overlay(RoundedRectangle(cornerRadius: 28).stroke(Forge.ink0.opacity(0.06), lineWidth: 2))
                .padding(.top, 36)
            Text(StressCurve.onsetText(s.onsetSec)).font(ThermoFont.mono(32, weight: .bold))
                .foregroundColor(s.onsetSec == nil ? Forge.phaseMeasured : Ramp.at(0.68))
                .padding(.top, 22)
        }
    }
}

// MARK: - Pieces shared with the presentation

/** The six section bars: each bar's length is its points against max(12000, the largest section). */
struct SectionBars: View {
    let sections: [SectionScore]
    var nameSize: CGFloat = 14
    var valueSize: CGFloat = 16
    var barHeight: CGFloat = 8
    var spacing: CGFloat = 10
    var nameWidth: CGFloat = 44
    var valueWidth: CGFloat = 80

    var body: some View {
        let top = Double(max(12000, sections.map(\.points).max() ?? 0))
        VStack(spacing: spacing) {
            ForEach(sections) { s in
                HStack(spacing: nameSize * 0.75) {
                    Text(s.label).font(ThermoFont.mono(nameSize, weight: .bold)).foregroundColor(s.color)
                        .frame(width: nameWidth, alignment: .leading)
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Forge.ink0.opacity(0.07))
                            Capsule().fill(s.color)
                                .frame(width: max(barHeight, g.size.width * CGFloat(min(1, Double(s.points) / top))))
                        }
                    }
                    .frame(height: barHeight)
                    Text(s.points.formatted()).font(ThermoFont.mono(valueSize, weight: .black)).foregroundColor(Forge.ink0)
                        .lineLimit(1).minimumScaleFactor(0.6)
                        .frame(width: valueWidth, alignment: .trailing)
                }
            }
        }
    }
}

/** % of baseline over the run: y from 0 to max(110, peak), a dashed 100 % line, the area under the curve tinted by the
 *  Ramp (cool teal where it held, molten where it fell), the throttle onset marked. Fills whatever size it is given. */
struct StressCurveChart: View {
    let curve: [Double]
    let durationS: Double
    var onsetFraction: Double? = nil
    var labelSize: CGFloat = 9
    var lineWidth: CGFloat = 2

    private var yMax: Double { max(110, curve.max() ?? 0) }

    /// The curve's colour at each point, left to right.
    private func ramp(_ opacity: Double) -> LinearGradient {
        let n = curve.count
        let stops = curve.enumerated().map { Gradient.Stop(color: Ramp.forCapacity($0.element).opacity(opacity),
                                                           location: n > 1 ? CGFloat($0.offset) / CGFloat(n - 1) : 0) }
        return LinearGradient(stops: stops.isEmpty ? [.init(color: .clear, location: 0)] : stops, startPoint: .leading, endPoint: .trailing)
    }

    var body: some View {
        VStack(spacing: labelSize * 0.5) {
            GeometryReader { g in
                let h = g.size.height, w = g.size.width
                let y100 = h * CGFloat(1 - 100 / yMax)
                ZStack(alignment: .topLeading) {
                    // quarter lines
                    ForEach([25.0, 50.0, 75.0], id: \.self) { v in
                        Path { p in
                            let y = h * CGFloat(1 - v / yMax)
                            p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: w, y: y))
                        }
                        .stroke(Forge.ink0.opacity(0.05), lineWidth: max(1, lineWidth * 0.25))
                    }
                    if curve.count >= 2 {
                        CurveShape(curve: curve, yMax: yMax, closed: true)
                            .fill(ramp(0.32))
                            .mask(LinearGradient(colors: [.white, .white.opacity(0.12)], startPoint: .top, endPoint: .bottom))
                        CurveShape(curve: curve, yMax: yMax, closed: false)
                            .stroke(ramp(1), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
                    }
                    Path { p in p.move(to: CGPoint(x: 0, y: y100)); p.addLine(to: CGPoint(x: w, y: y100)) }
                        .stroke(Forge.ink1.opacity(0.7), style: StrokeStyle(lineWidth: max(1, lineWidth * 0.4), dash: [lineWidth * 2.5, lineWidth * 2]))
                    Text("100%").font(ThermoFont.mono(labelSize, weight: .bold)).foregroundColor(Forge.ink1)
                        .frame(width: w, alignment: .trailing)
                        .offset(y: max(0, y100 - labelSize * 1.6))
                    if let f = onsetFraction {
                        Path { p in p.move(to: CGPoint(x: w * CGFloat(f), y: 0)); p.addLine(to: CGPoint(x: w * CGFloat(f), y: h)) }
                            .stroke(Ramp.at(0.68).opacity(0.75), style: StrokeStyle(lineWidth: max(1, lineWidth * 0.4), dash: [lineWidth * 1.5, lineWidth * 1.5]))
                    }
                    if curve.count < 2 {
                        Text("NO CURVE").font(ThermoFont.mono(labelSize, weight: .bold)).foregroundColor(Forge.ink2)
                            .frame(width: w, height: h)
                    }
                }
            }
            HStack {
                Text("0:00")
                Spacer()
                Text(StressCurve.mmss(Int(durationS.rounded())))
            }
            .font(ThermoFont.mono(labelSize)).foregroundColor(Forge.ink2)
        }
    }
}

/** The curve as a line, or closed down to the baseline for the area under it. */
struct CurveShape: Shape {
    let curve: [Double]
    let yMax: Double
    let closed: Bool

    func path(in rect: CGRect) -> Path {
        var p = Path()
        guard curve.count >= 2, yMax > 0 else { return p }
        let n = curve.count
        for (i, v) in curve.enumerated() {
            let pt = CGPoint(x: rect.minX + rect.width * CGFloat(i) / CGFloat(n - 1),
                             y: rect.maxY - rect.height * CGFloat(max(0, min(v, yMax)) / yMax))
            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        if closed {
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            p.closeSubpath()
        }
        return p
    }
}

// MARK: - Sharing

enum ShareService {
    /// What the texts call this device: "phone" (Windows says "PC"; the app on a Mac, "Mac").
    static var noun: String { LeaderboardService.isMac ? "Mac" : "phone" }

    /// The card as a PNG, 1080 × 1350 px.
    @MainActor static func png(_ card: ShareCard) -> Data? {
        let renderer = ImageRenderer(content: ShareCardView(card: card))
        renderer.scale = 1
        renderer.proposedSize = ProposedViewSize(ShareCardView.size)
        return renderer.uiImage?.pngData()
    }

    /// `Caches/<folder>/<name>` — written over each time.
    static func cacheFile(_ folder: String, _ name: String) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent(folder, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(name)
    }

    /// The card image (also kept as Caches/share/thermohammer-….png) and its text, through the share sheet.
    @MainActor static func share(_ card: ShareCard, from anchor: UIView?) {
        guard let png = png(card), let image = UIImage(data: png) else { return }
        try? png.write(to: cacheFile("share", card.fileName()), options: .atomic)
        ShareSheet.present([image, card.text], from: anchor)
    }

    /// A CSV export (Caches/export/<name>) through the share sheet, as a file.
    @MainActor static func shareCSV(_ csv: String, name: String, from anchor: UIView?) {
        let url = cacheFile("export", name)
        guard (try? Data(csv.utf8).write(to: url, options: .atomic)) != nil else { return }
        ShareSheet.present([url], from: anchor)
    }
}

/** UIActivityViewController over whatever is on screen — as a popover from `anchor` on an iPad or a Mac, where one
 *  without a source crashes. */
enum ShareSheet {
    @MainActor static func present(_ items: [Any], from anchor: UIView?) {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        guard let root = (windows.first { $0.isKeyWindow } ?? windows.first)?.rootViewController else { return }
        var top = root
        while let shown = top.presentedViewController, !shown.isBeingDismissed { top = shown }
        let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let popover = sheet.popoverPresentationController {
            if let anchor, anchor.window != nil {
                popover.sourceView = anchor
                popover.sourceRect = anchor.bounds
            } else {
                popover.sourceView = top.view
                popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }
        }
        top.present(sheet, animated: true)
    }
}

/** Where a button is, as a UIView the share sheet can point at. */
final class ShareAnchor {
    weak var view: UIView?
}

private struct ShareAnchorView: UIViewRepresentable {
    let anchor: ShareAnchor
    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.isUserInteractionEnabled = false          // only marks the spot: the button takes the tap
        v.backgroundColor = .clear
        anchor.view = v
        return v
    }
    func updateUIView(_ v: UIView, context: Context) { anchor.view = v }
}

/** A button that hands its own place on screen to its action — for a share sheet that pops over it. */
struct AnchoredButton<Label: View>: View {
    let action: (UIView?) -> Void
    @ViewBuilder let label: () -> Label
    @State private var anchor = ShareAnchor()

    var body: some View {
        Button { action(anchor.view) } label: { label().background(ShareAnchorView(anchor: anchor)) }
    }
}

/** The small secondary buttons under a run (SHARE · PRESENT · EXPORT CSV): ink on the interactive fill. */
struct RunActionLabel: View {
    let text: String
    var size: CGFloat = 10
    var accent: Color = Forge.ink0

    var body: some View {
        Text(text).font(ThermoFont.mono(size, weight: .black)).tracking(0.5).foregroundColor(accent)
            .lineLimit(1).minimumScaleFactor(0.7)
            .frame(maxWidth: .infinity).padding(.vertical, 11)
            .background(Forge.interact).cornerRadius(10)
    }
}
