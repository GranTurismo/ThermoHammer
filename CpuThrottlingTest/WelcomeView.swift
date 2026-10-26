import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// WELCOME — what ThermoHammer is and why its numbers can be trusted, on six
// pages: the instrument, its six sections, the same work on every platform,
// scoring that can't be gamed, Phone Health and the heat test. The pictures
// are drawn live (a TimelineView per picture, only for the pages in view);
// each page plays its entrance as it slides in, and the pictures lag the
// swipe for depth. Shown on first launch; the "?" in the header opens it again.
// Android's ui/screens/WelcomeScreen.kt, the same pages.
// ─────────────────────────────────────────────────────────────────────────────

private struct WelcomePage {
    let kicker, title, body: String
    let accent: Color
    let art: Int
}

struct WelcomeView: View {
    let onDone: () -> Void

    @State private var page = 0
    @State private var drag: CGFloat = 0
    @State private var starts: [Int: Date] = [:]  // when each page in view began its entrance (it starts as the page slides in)
    @State private var moves = 0                  // page changes so far: only the latest one tidies up

    private let pages: [WelcomePage] = [
        WelcomePage(kicker: "THERMOHAMMER", title: "The benchmark that tells the truth",
                    body: "Six sections, scored on our server, the same test on every phone — and the only one that also tells you when your phone gets too hot to keep up.",
                    accent: Forge.phaseMeasured, art: 0),
        WelcomePage(kicker: "01 · SIX SECTIONS", title: "One honest score",
                    body: "Processor, memory and storage, 3D graphics with ray tracing, everyday apps, AI on the Neural Engine, and a language model running right on your phone. Each test is 6,000 points on a Snapdragon 8 Elite.",
                    accent: Ramp.at(0.25), art: 1),
        WelcomePage(kicker: "02 · SAME WORK EVERYWHERE", title: "Byte-identical on every platform",
                    body: "iPhone, Android and Mac run the very same C\u{2060}+\u{2060}+, the same 3D scenes, the same AI models and the same seeded language model. A score means the same thing on every device.",
                    accent: Forge.phaseCooldown, art: 2),
        WelcomePage(kicker: "03 · CAN'T BE GAMED", title: "Scored where no one can tune for it",
                    body: "Phone makers have been caught boosting known benchmarks. Your raw results are signed and scored on our server, and only clean runs — cool, unplugged, nothing else running — reach the leaderboard.",
                    accent: Forge.phaseCalibration, art: 3),
        WelcomePage(kicker: "04 · PHONE HEALTH", title: "Is your phone as fast as it should be?",
                    body: "Every run is compared with other units of your model and with your own past runs. If it's slower, you find out why: heat, background apps, full storage or an update.",
                    accent: Ramp.at(0.5), art: 4),
        WelcomePage(kicker: "05 · THE HEAT TEST", title: "See when it starts to throttle",
                    body: "The stress test hammers the chip for 5 to 30 minutes and charts every second: when it slows down, how far, and what caused it.",
                    accent: Ramp.at(0.68), art: 5),
    ]

    private var last: Bool { page == pages.count - 1 }

    private func go(_ to: Int) {
        let target = max(0, min(pages.count - 1, to))
        if starts[target] == nil { starts[target] = Date() }
        withAnimation(.spring(response: 0.48, dampingFraction: 0.86)) { page = target; drag = 0 }
        // once it has come to rest, the pages out of view forget theirs: the next visit replays the entrance
        moves += 1
        let move = moves
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
            if move == moves && drag == 0 { starts = starts.filter { $0.key == page } }
        }
    }

    var body: some View {
        GeometryReader { geo in
            let w = min(geo.size.width, Forge.columnWidth)     // a phone-width column on a Mac or an iPad
            let pos = Double(page) - Double(drag / max(w, 1))
            ZStack {
                Forge.bg.ignoresSafeArea()
                RadialGradient(colors: [pages[page].accent.opacity(0.16), .clear], center: UnitPoint(x: 0.5, y: 0.32),
                               startRadius: 0, endRadius: w * 0.95)
                    .ignoresSafeArea()
                    .animation(.easeInOut(duration: 0.5), value: page)
                VStack(spacing: 0) {
                    HStack {
                        Text("⌖ THERMOHAMMER").font(ThermoFont.mono(10, weight: .bold)).tracking(1.2).foregroundColor(Forge.ink1)
                        Spacer()
                        if !last {
                            Button(action: onDone) {
                                Text("SKIP").font(ThermoFont.mono(10, weight: .bold)).tracking(1.2).foregroundColor(Forge.ink1).padding(8)
                            }
                        }
                    }
                    .padding(.horizontal, 20).padding(.top, 6)
                    HStack(spacing: 0) {
                        ForEach(pages.indices, id: \.self) { i in
                            pageView(i, rel: CGFloat(Double(i) - pos))
                                .frame(width: w)
                        }
                    }
                    .frame(width: w, alignment: .leading)
                    .offset(x: -CGFloat(page) * w + drag)
                    // in a window wider than the column (a Mac, an iPad) the pages dissolve at its edges instead of being cut
                    .mask(LinearGradient(stops: geo.size.width > w + 1
                                            ? [.init(color: .clear, location: 0), .init(color: .black, location: 20 / w),
                                               .init(color: .black, location: 1 - 20 / w), .init(color: .clear, location: 1)]
                                            : [.init(color: .black, location: 0), .init(color: .black, location: 1)],
                                         startPoint: .leading, endPoint: .trailing))
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 8)
                            .onChanged { v in
                                let edge = (page == 0 && v.translation.width > 0) || (last && v.translation.width < 0)
                                drag = edge ? v.translation.width * 0.3 : v.translation.width    // rubber band at the ends
                                let next = page + (v.translation.width < 0 ? 1 : -1)              // comes alive as it slides in
                                if pages.indices.contains(next), starts[next] == nil { starts[next] = Date() }
                            }
                            .onEnded { v in
                                let projected = v.predictedEndTranslation.width
                                if projected < -w * 0.35 || v.translation.width < -w * 0.5 { go(page + 1) }
                                else if projected > w * 0.35 || v.translation.width > w * 0.5 { go(page - 1) }
                                else { go(page) }
                            }
                    )
                    HStack {
                        dots(pos: pos)
                        Spacer()
                        Button { if last { onDone() } else { go(page + 1) } } label: {
                            Text(last ? "GET STARTED" : "NEXT  →").font(ThermoFont.mono(12, weight: .black)).tracking(1)
                                .foregroundColor(Forge.bg)
                                .frame(width: last ? 168 : 116, height: 48)
                                .background(LinearGradient(colors: [pages[page].accent, pages[page].accent.opacity(0.75)],
                                                           startPoint: .leading, endPoint: .trailing))
                                .cornerRadius(14)
                        }
                        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: last)
                    }
                    .padding(.horizontal, 24).padding(.vertical, 18)
                }
                .frame(width: w)
                .clipped()
            }
        }
        .onAppear {
            // simulator checks: `simctl launch … -welcomePage N` opens on page N
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "-welcomePage"), i + 1 < args.count, let n = Int(args[i + 1]) {
                page = max(0, min(pages.count - 1, n))
            }
            starts = [page: Date()]
            // checks: `-welcomeTour` steps through the pages on its own, to film the transitions
            if args.contains("-welcomeTour") {
                Timer.scheduledTimer(withTimeInterval: 2.5, repeats: true) { t in if last { t.invalidate() } else { go(page + 1) } }
            }
        }
    }

    @ViewBuilder
    private func pageView(_ i: Int, rel: CGFloat) -> some View {
        let p = pages[i]
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                if abs(rel) < 1.2 {                                   // only the pages in view draw
                    WelcomeArt(kind: p.art, start: starts[i] ?? .distantFuture)
                        .aspectRatio(1.1, contentMode: .fit)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // a little behind the swipe (less than the page's margin), gone by the time it would reach the next page
            .offset(x: -rel * 40)
            .scaleEffect(1 - min(1, abs(rel)) * 0.1)
            .opacity(1 - min(1, abs(rel)))
            VStack(alignment: .leading, spacing: 10) {
                Text(p.kicker).font(ThermoFont.mono(10, weight: .bold)).tracking(1.2).foregroundColor(p.accent)
                Text(p.title).font(ThermoFont.display(27, weight: .medium)).foregroundColor(Forge.ink0)
                    .fixedSize(horizontal: false, vertical: true)
                Text(p.body).font(ThermoFont.mono(13)).lineSpacing(5).foregroundColor(Forge.ink1)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.bottom, 12)
            .offset(x: -rel * 24)
            .opacity(1 - min(1, abs(rel)))
        }
        .padding(.horizontal, 24)
    }

    private func dots(pos: Double) -> some View {
        HStack(spacing: 6) {
            ForEach(pages.indices, id: \.self) { i in
                let near = max(0, 1 - abs(pos - Double(i)))
                Capsule()
                    .fill(near > 0.5 ? pages[i].accent : Forge.ink2.opacity(0.5))
                    .frame(width: 6 + 18 * near, height: 6)
            }
        }
    }
}

// MARK: - the pictures

private func ease(_ x: Double) -> Double { let c = max(0, min(1, x)); return 1 - pow(1 - c, 3) }
/** Progress of a staggered piece: starts at `from` of the entrance, takes `span`. */
private func stage(_ a: Double, _ from: Double, _ span: Double = 0.35) -> Double { ease((a - from) / span) }

private struct WelcomeArt: View {
    let kind: Int
    let start: Date

    var body: some View {
        TimelineView(.animation) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate
            let a = ease(tl.date.timeIntervalSince(start) / 1.6)
            Canvas { ctx, size in
                switch kind {
                case 0: reactor(&ctx, size, t, a)
                case 1: sections(&ctx, size, t, a)
                case 2: sameWork(&ctx, size, t, a)
                case 3: server(&ctx, size, t, a)
                case 4: health(&ctx, size, t, a)
                default: heat(&ctx, size, t, a)
                }
            }
        }
    }

    private func label(_ ctx: inout GraphicsContext, _ s: String, _ at: CGPoint, _ color: Color, _ size: CGFloat,
                       bold: Bool = false, anchor: UnitPoint = .topLeading) {
        ctx.draw(Text(s).font(ThermoFont.mono(size, weight: bold ? .black : .bold)).tracking(size * 0.06).foregroundColor(color),
                 at: at, anchor: anchor)
    }

    /** The instrument: rings of the thermal ramp turning around a glowing core, a strike every few seconds. */
    private func reactor(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ a: Double) {
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let r = min(size.width, size.height) * 0.36
        let breath = 0.85 + 0.15 * sin(t * 1.6)
        let gr = r * 0.95 * breath
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - gr, y: c.y - gr, width: gr * 2, height: gr * 2)),
                 with: .radialGradient(Gradient(colors: [Ramp.at(0.55).opacity(0.55 * a), Ramp.at(0.7).opacity(0.18 * a), .clear]),
                                       center: c, startRadius: 0, endRadius: gr))
        let cr = r * 0.11 * breath
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - cr, y: c.y - cr, width: cr * 2, height: cr * 2)), with: .color(Ramp.at(0.9).opacity(0.9 * a)))
        for k in 0..<3 {
            let rr = r * (0.48 + 0.26 * CGFloat(k)) * (0.7 + 0.3 * a)
            let dir: Double = k % 2 == 0 ? 1 : -1
            let rot = dir * t * (24 + 14 * Double(k)) + Double(k) * 40
            var arc = Path()
            arc.addArc(center: c, radius: rr, startAngle: .degrees(rot), endAngle: .degrees(rot + 300 * a), clockwise: false)
            ctx.stroke(arc, with: .conicGradient(Gradient(colors: [Ramp.at(0), Ramp.at(0.25), Ramp.at(0.5), Ramp.at(0.85), Ramp.at(0)]),
                                                 center: c, angle: .degrees(rot)),
                       style: StrokeStyle(lineWidth: CGFloat(5 - k), lineCap: .round))
            for j in 0..<12 {
                let ang = (Double(j) * 30 + rot) * .pi / 180
                var tick = Path()
                tick.move(to: CGPoint(x: c.x + cos(ang) * (rr + 6), y: c.y + sin(ang) * (rr + 6)))
                tick.addLine(to: CGPoint(x: c.x + cos(ang) * (rr + 11), y: c.y + sin(ang) * (rr + 11)))
                ctx.stroke(tick, with: .color(Forge.ink2.opacity(0.5 * a)), lineWidth: 1)
            }
        }
        let w = t.truncatingRemainder(dividingBy: 2.6) / 2.6
        let sr = r * (0.2 + 1.1 * w)
        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - sr, y: c.y - sr, width: sr * 2, height: sr * 2)),
                   with: .color(Ramp.at(0.6).opacity((1 - w) * 0.45 * a)), lineWidth: 3 * (1 - w) + 0.5)
        for i in 0..<26 {
            let seed = Double(i) * 37.17
            let life = (t * (0.22 + Double(i % 5) * 0.05) + seed).truncatingRemainder(dividingBy: 1)
            let x = c.x + sin(seed) * r * 1.1 + sin(t + seed) * 8
            let y = c.y + r - life * r * 2.2
            let pr = (1.4 + Double(i % 3)) * (1 - life)
            ctx.fill(Path(ellipseIn: CGRect(x: x - pr, y: y - pr, width: pr * 2, height: pr * 2)),
                     with: .color(Ramp.at(0.4 + 0.5 * life).opacity((1 - life) * 0.8 * a)))
        }
    }

    /** Six tiles light up one after another, each running its own little workload, the total counting up. */
    private func sections(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ a: Double) {
        let names = ["CPU", "MEM", "GPU", "UX", "AI", "LLM"]
        let colors = [BenchColor.cpu, BenchColor.mem, Forge.phaseCalibration, Forge.phaseMeasured, BenchColor.ai, BenchColor.llm]
        let gap: CGFloat = 10
        let tileW = (size.width - gap * 2) / 3, tileH = (size.height * 0.72 - gap) / 2
        for i in 0..<6 {
            let p = stage(a, Double(i) * 0.09, 0.4)
            let x = CGFloat(i % 3) * (tileW + gap), y = CGFloat(i / 3) * (tileH + gap) + (1 - p) * 30
            let rect = CGRect(x: x, y: y, width: tileW, height: tileH)
            ctx.fill(Path(roundedRect: rect, cornerRadius: 14), with: .color(Forge.surface.opacity(p)))
            ctx.stroke(Path(roundedRect: rect, cornerRadius: 14), with: .color(colors[i].opacity(0.55 * p)), lineWidth: 1)
            label(&ctx, names[i], CGPoint(x: x + 12, y: y + 10), colors[i].opacity(p), 11)
            let bars = 7
            let bw = (tileW - 24) / (CGFloat(bars) * 1.6)
            for b in 0..<bars {
                let h = (0.25 + 0.75 * (0.5 + 0.5 * sin(t * (2.2 + Double(i) * 0.3) + Double(b) * 0.9 + Double(i)))) * tileH * 0.42 * p
                ctx.fill(Path(roundedRect: CGRect(x: x + 12 + CGFloat(b) * bw * 1.6, y: y + tileH - 12 - h, width: bw, height: h), cornerRadius: bw / 2),
                         with: .color(colors[i].opacity(0.8 * p)))
            }
        }
        let total = Int(36026 * stage(a, 0.45, 0.55))
        let ty = size.height * 0.80
        label(&ctx, "TOTAL", CGPoint(x: 0, y: ty), Forge.ink2, 10)
        label(&ctx, total.formatted(), CGPoint(x: 0, y: ty + 14), Forge.ink0, 30, bold: true)
        label(&ctx, "CPU + MEM + GPU + UX + AI + LLM", CGPoint(x: size.width * 0.42, y: ty + 26), Forge.ink2, 9)
    }

    /** The same packets, in the same order, flowing from one seed into an Android phone, an iPhone and a Mac. */
    private func sameWork(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ a: Double) {
        let packet = [BenchColor.cpu, BenchColor.mem, Forge.phaseCalibration, Forge.phaseMeasured, BenchColor.ai, BenchColor.llm]
        let src = CGPoint(x: size.width / 2, y: size.height * 0.12)
        let chip = CGRect(x: src.x - 62, y: src.y - 16, width: 124, height: 32)
        ctx.fill(Path(roundedRect: chip, cornerRadius: 10), with: .color(Forge.surface))
        ctx.stroke(Path(roundedRect: chip, cornerRadius: 10), with: .color(Forge.phaseCooldown.opacity(0.6)), lineWidth: 1)
        label(&ctx, "ONE TEST · ONE SEED", src, Forge.phaseCooldown, 9, anchor: .center)
        let devY = size.height * 0.62
        let xs = [size.width * 0.17, size.width * 0.5, size.width * 0.83]
        let names = ["ANDROID", "iPHONE", "MAC"]
        for k in 0..<3 {
            let p = stage(a, 0.1 + Double(k) * 0.12, 0.45)
            let x = xs[k]
            let p0 = CGPoint(x: src.x, y: src.y + 16), p3 = CGPoint(x: x, y: devY - 58)
            let c1 = CGPoint(x: src.x, y: src.y + 70), c2 = CGPoint(x: x, y: p3.y - 70)
            var path = Path(); path.move(to: p0); path.addCurve(to: p3, control1: c1, control2: c2)
            ctx.stroke(path, with: .color(Forge.ink2.opacity(0.35 * p)), lineWidth: 1.5)
            for j in 0..<6 {
                let u = (t * 0.32 + Double(j) / 6).truncatingRemainder(dividingBy: 1)
                let m = 1 - u
                let px = m * m * m * p0.x + 3 * m * m * u * c1.x + 3 * m * u * u * c2.x + u * u * u * p3.x
                let py = m * m * m * p0.y + 3 * m * m * u * c1.y + 3 * m * u * u * c2.y + u * u * u * p3.y
                ctx.fill(Path(roundedRect: CGRect(x: px - 4, y: py - 4, width: 8, height: 8), cornerRadius: 2),
                         with: .color(packet[j].opacity(p * (0.4 + 0.6 * sin(u * .pi)))))
            }
            device(&ctx, k, CGPoint(x: x, y: devY), p)
            label(&ctx, names[k], CGPoint(x: x, y: devY + 70), Forge.ink1.opacity(p), 9, anchor: .center)
            let shown = String("26f8354e".prefix(Int(8 * stage(a, 0.55 + Double(k) * 0.05, 0.35))))     // all eight by the end
            label(&ctx, shown, CGPoint(x: x, y: devY), Forge.phaseCooldown.opacity(p), 9, anchor: .center)
        }
        label(&ctx, "same code · same scenes · same models · same result", CGPoint(x: size.width / 2, y: size.height * 0.97),
              Forge.ink2.opacity(a), 9, anchor: .center)
    }

    private func device(_ ctx: inout GraphicsContext, _ kind: Int, _ c: CGPoint, _ p: Double) {
        let col = Forge.ink1.opacity(0.8 * p)
        if kind < 2 {
            let r = CGRect(x: c.x - 27, y: c.y - 56, width: 54, height: 112)
            ctx.fill(Path(roundedRect: r, cornerRadius: 12), with: .color(Forge.surface.opacity(p)))
            ctx.stroke(Path(roundedRect: r, cornerRadius: 12), with: .color(col), lineWidth: 1.5)
            if kind == 0 { ctx.fill(Path(ellipseIn: CGRect(x: c.x - 2.5, y: r.minY + 5.5, width: 5, height: 5)), with: .color(col)) }
            else { ctx.fill(Path(roundedRect: CGRect(x: c.x - 9, y: r.minY + 6, width: 18, height: 5), cornerRadius: 3), with: .color(col)) }
        } else {
            let r = CGRect(x: c.x - 46, y: c.y - 36, width: 92, height: 60)
            ctx.fill(Path(roundedRect: r, cornerRadius: 6), with: .color(Forge.surface.opacity(p)))
            ctx.stroke(Path(roundedRect: r, cornerRadius: 6), with: .color(col), lineWidth: 1.5)
            ctx.fill(Path(roundedRect: CGRect(x: r.minX - 10, y: r.maxY + 2, width: r.width + 20, height: 5), cornerRadius: 3), with: .color(col))
        }
    }

    /** Raw results leave the phone signed, the server scores them, and only clean runs pass its checks. */
    private func server(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ a: Double) {
        let phone = CGPoint(x: size.width * 0.16, y: size.height * 0.34)
        let srv = CGPoint(x: size.width * 0.84, y: size.height * 0.34)
        device(&ctx, 0, phone, a)
        for u in 0..<3 {
            let r = CGRect(x: srv.x - 38, y: srv.y - 46 + CGFloat(u) * 32, width: 76, height: 26)
            ctx.fill(Path(roundedRect: r, cornerRadius: 6), with: .color(Forge.surface.opacity(a)))
            ctx.stroke(Path(roundedRect: r, cornerRadius: 6), with: .color(Forge.phaseCalibration.opacity(0.6 * a)), lineWidth: 1)
            for l in 0..<3 {
                let on = sin(t * 5 + Double(u) * 1.7 + Double(l) * 2.3) > 0.2
                ctx.fill(Path(ellipseIn: CGRect(x: r.minX + 9.5 + CGFloat(l) * 9, y: r.minY + 10.5, width: 5, height: 5)),
                         with: .color((on ? Forge.phaseMeasured : Forge.ink2).opacity(a)))
            }
        }
        let y = phone.y
        var line = Path(); line.move(to: CGPoint(x: phone.x + 36, y: y)); line.addLine(to: CGPoint(x: srv.x - 46, y: y))
        ctx.stroke(line, with: .color(Forge.ink2.opacity(0.4 * a)),
                   style: StrokeStyle(lineWidth: 1.5, dash: [4, 4], dashPhase: CGFloat(-t * 20)))
        for j in 0..<3 {
            let u = (t * 0.45 + Double(j) / 3).truncatingRemainder(dividingBy: 1)
            let x = phone.x + 36 + u * (srv.x - phone.x - 82)
            ctx.fill(Path(roundedRect: CGRect(x: x - 9, y: y - 6, width: 18, height: 12), cornerRadius: 3),
                     with: .color(Forge.phaseCalibration.opacity(a * sin(u * .pi))))
        }
        let lc = CGPoint(x: size.width / 2, y: y - 22)
        var shackle = Path()
        shackle.addArc(center: CGPoint(x: lc.x, y: lc.y - 4), radius: 7, startAngle: .degrees(180), endAngle: .degrees(360), clockwise: false)
        ctx.stroke(shackle, with: .color(Forge.phaseCalibration.opacity(a)), lineWidth: 2)
        ctx.fill(Path(roundedRect: CGRect(x: lc.x - 10, y: lc.y - 4, width: 20, height: 15), cornerRadius: 3), with: .color(Forge.phaseCalibration.opacity(a)))
        label(&ctx, "SIGNED · SCORED ON THE SERVER", CGPoint(x: size.width / 2, y: y + 22), Forge.ink2.opacity(a), 9, anchor: .center)
        let checks = ["UNPLUGGED", "STARTED COOL", "NOTHING ELSE RUNNING", "NO POWER SAVING"]
        for (i, text) in checks.enumerated() {
            let p = stage(a, 0.3 + Double(i) * 0.13, 0.3)
            let cy = size.height * 0.62 + CGFloat(i) * 26
            let x0 = size.width * 0.18
            ctx.fill(Path(roundedRect: CGRect(x: x0, y: cy - 10, width: size.width * 0.64, height: 21), cornerRadius: 7), with: .color(Forge.surface.opacity(p)))
            let ok = p >= 1
            ctx.fill(Path(ellipseIn: CGRect(x: x0 + 8, y: cy - 6, width: 12, height: 12)), with: .color((ok ? Forge.phaseMeasured : Forge.ink2).opacity(p)))
            if ok {
                var tick = Path()
                tick.move(to: CGPoint(x: x0 + 11, y: cy)); tick.addLine(to: CGPoint(x: x0 + 13.5, y: cy + 2.5)); tick.addLine(to: CGPoint(x: x0 + 17.5, y: cy - 2.5))
                ctx.stroke(tick, with: .color(Forge.bg), style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
            }
            label(&ctx, text, CGPoint(x: x0 + 28, y: cy), (ok ? Forge.ink0 : Forge.ink2).opacity(p), 10, anchor: .leading)
        }
    }

    /** Other units of the model pile up into a curve; this phone's marker slides in and the reason appears. */
    private func health(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ a: Double) {
        let base = size.height * 0.62, h = size.height * 0.42
        func curve(_ x: Double) -> Double { exp(-((x - 0.55) * (x - 0.55)) / (2 * 0.12 * 0.12)) }
        let grow = stage(a, 0, 0.5)
        for i in 0..<70 {
            let u = (Double(i) * 0.6180339).truncatingRemainder(dividingBy: 1)
            let col = Int(curve(u) * 9)
            let stack = (i * 7) % (col + 1)
            let fall = stage(a, 0.02 * Double(i % 25), 0.3)
            let dy = base - 6 - CGFloat(stack) * 9 * max(0.15, curve(u)) - (1 - fall) * 120
            ctx.fill(Path(ellipseIn: CGRect(x: u * size.width - 2.6, y: dy - 2.6, width: 5.2, height: 5.2)), with: .color(Forge.ink1.opacity(0.35 * fall)))
        }
        var area = Path(); area.move(to: CGPoint(x: 0, y: base))
        for k in 0...100 { let x = Double(k) / 100; area.addLine(to: CGPoint(x: x * size.width, y: base - curve(x) * h * grow)) }
        area.addLine(to: CGPoint(x: size.width, y: base)); area.closeSubpath()
        ctx.fill(area, with: .linearGradient(Gradient(colors: [Ramp.at(0.25).opacity(0.28), .clear]),
                                             startPoint: CGPoint(x: 0, y: base - h), endPoint: CGPoint(x: 0, y: base)))
        ctx.fill(Path(CGRect(x: 0.47 * size.width, y: base - h, width: 0.16 * size.width, height: h)), with: .color(Ramp.at(0.25).opacity(0.12 * grow)))
        var med = Path(); med.move(to: CGPoint(x: 0.55 * size.width, y: base - h * 1.05)); med.addLine(to: CGPoint(x: 0.55 * size.width, y: base))
        ctx.stroke(med, with: .color(Forge.ink0.opacity(0.6 * grow)), lineWidth: 1.5)
        label(&ctx, "TYPICAL", CGPoint(x: 0.55 * size.width, y: base - h * 1.12), Forge.ink1.opacity(grow), 9, anchor: .center)
        var axis = Path(); axis.move(to: CGPoint(x: 0, y: base)); axis.addLine(to: CGPoint(x: size.width, y: base))
        ctx.stroke(axis, with: .color(Forge.ink2), lineWidth: 1)
        let you = 0.08 + (0.36 - 0.08) * stage(a, 0.45, 0.4) + 0.004 * sin(t * 2)
        let ux = you * size.width
        let accent = Ramp.at(0.5)
        var stem = Path(); stem.move(to: CGPoint(x: ux, y: base - h * 0.6)); stem.addLine(to: CGPoint(x: ux, y: base))
        ctx.stroke(stem, with: .color(accent), lineWidth: 2)
        ctx.fill(Path(ellipseIn: CGRect(x: ux - 6, y: base - h * 0.6 - 6, width: 12, height: 12)), with: .color(accent))
        label(&ctx, "YOU · 86%", CGPoint(x: ux, y: base - h * 0.6 - 16), accent, 10, bold: true, anchor: .center)
        let r = stage(a, 0.8, 0.25)
        let box = CGRect(x: 0, y: base + 18 + (1 - r) * 12, width: size.width, height: 54)
        ctx.fill(Path(roundedRect: box, cornerRadius: 10), with: .color(Forge.surface.opacity(r)))
        ctx.stroke(Path(roundedRect: box, cornerRadius: 10), with: .color(accent.opacity(0.5 * r)), lineWidth: 1)
        label(&ctx, "▲ GPU 18% BELOW OTHER UNITS", CGPoint(x: 12, y: box.minY + 9), Ramp.at(0.85).opacity(r), 10, bold: true)
        label(&ctx, "It throttled. Let it cool and take the case off.", CGPoint(x: 12, y: box.minY + 28), Forge.ink1.opacity(r), 10)
    }

    /** Temperature climbs; performance holds, then sags at the onset — drawn as it happens, over and over. */
    private func heat(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ a: Double) {
        let top = size.height * 0.1, bottom = size.height * 0.78
        for g in 0...4 {
            var l = Path(); let y = top + (bottom - top) * CGFloat(g) / 4
            l.move(to: CGPoint(x: 0, y: y)); l.addLine(to: CGPoint(x: size.width, y: y))
            ctx.stroke(l, with: .color(Forge.hairline), lineWidth: 1)
        }
        let sweep = min(1, t.truncatingRemainder(dividingBy: 7) / 6)
        let onset = 0.42
        func perf(_ x: Double) -> Double { x < onset ? 0.92 - 0.02 * sin(x * 30) : 0.92 - 0.34 * (1 - exp(-(x - onset) * 6)) - 0.02 * sin(x * 30) }
        func temp(_ x: Double) -> Double { 0.15 + 0.7 * (1 - exp(-x * 3.2)) }
        let n = max(1, Int(120 * sweep * a))
        var tp = Path(), pp = Path()
        for k in 0...n {
            let x = Double(k) / 120
            let px = x * size.width
            let ty = bottom - temp(x) * (bottom - top), py = bottom - perf(x) * (bottom - top)
            if k == 0 { tp.move(to: CGPoint(x: px, y: ty)); pp.move(to: CGPoint(x: px, y: py)) }
            else { tp.addLine(to: CGPoint(x: px, y: ty)); pp.addLine(to: CGPoint(x: px, y: py)) }
        }
        ctx.stroke(tp, with: .linearGradient(Gradient(colors: [Ramp.at(0.2), Ramp.at(0.55), Ramp.at(0.85)]),
                                             startPoint: .zero, endPoint: CGPoint(x: size.width, y: 0)),
                   style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
        ctx.stroke(pp, with: .color(Forge.phaseMeasured), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
        if sweep > onset {
            let ox = onset * size.width
            var m = Path(); m.move(to: CGPoint(x: ox, y: top)); m.addLine(to: CGPoint(x: ox, y: bottom))
            ctx.stroke(m, with: .color(Ramp.at(0.85).opacity(0.8)), style: StrokeStyle(lineWidth: 1.5, dash: [5, 4]))
            label(&ctx, "THROTTLING STARTS · 4:12", CGPoint(x: ox + 6, y: top + 2), Ramp.at(0.85), 9, bold: true)
        }
        label(&ctx, "PERFORMANCE", CGPoint(x: 0, y: bottom + 12), Forge.phaseMeasured, 10, bold: true)
        label(&ctx, "TEMPERATURE", CGPoint(x: size.width * 0.5, y: bottom + 12), Ramp.at(0.6), 10, bold: true)
    }
}
