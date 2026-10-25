import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK STAGE — full screen while the benchmark runs: which test, its live
// rate, a view of the work (CPU threads, the MEM block map, later the GPU
// scene), and one progress bar across all five sections.
// ─────────────────────────────────────────────────────────────────────────────

enum BenchColor {
    static let cpu = Color(hex: 0xE8C547)       // gold
    static let mem = Color(hex: 0xF2A65A)       // copper
    static let memWrite = Color(hex: 0xFF7A6B)  // coral
    static let ai = Color(hex: 0xC792EA)        // lavender
    static func gpu(_ t: GpuTest) -> Color {
        switch t { case .lakeValley: return Forge.phaseCalibration; case .neonCity: return Ramp.at(0.85); case .neonCityRT: return Forge.phaseCooldown }
    }
    static func ux(_ t: UxTest) -> Color {
        switch t {
        case .web: return Forge.phaseMeasured
        case .scroll: return Color(hex: 0x6FE3A0)
        case .gallery: return Color(hex: 0x7FD6E8)
        case .photo: return Color(hex: 0xFF8FB1)
        case .video: return Color(hex: 0x6FA8FF)
        case .qr: return Ramp.at(0.55)
        }
    }
}

struct BenchmarkStageView: View {
    @ObservedObject var engine: BenchmarkEngine

    private var accent: Color {
        engine.cpuTest != nil ? BenchColor.cpu : engine.memTest != nil ? BenchColor.mem : engine.aiTest != nil ? BenchColor.ai
            : engine.gpuTest.map(BenchColor.gpu) ?? engine.uxTest.map(BenchColor.ux) ?? Forge.phaseCooldown
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                    .padding(.horizontal, 20).padding(.top, 8)
                Spacer(minLength: 12)
                Group {
                    if let test = engine.cpuTest {
                        CpuStageView(test: test, progress: engine.cpuProgress, accent: BenchColor.cpu)
                    } else if let test = engine.memTest {
                        MemStageView(test: test, progress: engine.memProgress)
                    } else if let page = engine.webPage {
                        WebBenchView(page: page, engine: engine)
                            .id(page)
                            .aspectRatio(16 / 9, contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    } else if engine.uxTest == .video {
                        ZStack {
                            Color.black
                            if let img = engine.videoFrame { Image(uiImage: img).resizable().scaledToFit() }
                        }
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    } else if engine.aiTest != nil {
                        AiStageView(visual: engine.aiVisual, accent: BenchColor.ai)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    } else if engine.uxTest == .qr {
                        QrStageView(frame: engine.qrFrame, found: engine.qrFound, accent: BenchColor.ux(.qr))
                            .aspectRatio(16 / 9, contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    } else if engine.gpuTest != nil || engine.uxTest == .photo, let bench = GpuBench.shared {
                        GpuFrameView(bench: bench)
                            .aspectRatio(16 / 9, contentMode: .fit)
                            .overlay { if engine.uxTest == .photo { PhotoPanelLabels() } }
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    } else {
                        PreparingView(busy: engine.preparingBusy, elapsed: engine.preparingElapsed)
                    }
                }
                .padding(.horizontal, 20)
                Spacer(minLength: 12)
                HStack(alignment: .bottom, spacing: 14) {
                    BenchProgressBar(engine: engine)
                    HoldToAbort { engine.stop() }
                }
                .padding(.horizontal, 20).padding(.bottom, 14)
            }
        }
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text(engine.cpuTest != nil ? "CPU BENCHMARK" : engine.memTest != nil ? "MEM BENCHMARK" : engine.gpuTest != nil ? "GPU BENCHMARK"
                     : engine.uxTest != nil ? "UX BENCHMARK" : engine.aiTest != nil ? "AI BENCHMARK" : "BENCHMARK")
                    .font(ThermoFont.mono(9, weight: .bold)).tracking(2).foregroundColor(accent)
                if let t = engine.cpuTest {
                    Text("CPU TEST \(t.number)/\(CpuTest.allCases.count) · \(t.title)").font(ThermoFont.mono(13, weight: .black)).foregroundColor(Forge.ink0)
                    Text(engine.cpuProgress?.kernel ?? "").font(ThermoFont.mono(10)).foregroundColor(Forge.ink1)
                } else if let t = engine.memTest {
                    Text("MEM TEST \(t.number)/\(MemTest.allCases.count) · \(t.title)").font(ThermoFont.mono(13, weight: .black)).foregroundColor(Forge.ink0)
                    Text(engine.memProgress?.measure ?? t.labels[0]).font(ThermoFont.mono(10)).foregroundColor(Forge.ink1)
                } else if let t = engine.gpuTest {
                    Text("GPU TEST \(t.number)/\(GpuTest.allCases.count) · \(t.title)").font(ThermoFont.mono(13, weight: .black)).foregroundColor(Forge.ink0)
                    Text("\(GpuBench.shared?.name ?? "GPU") · 1920×1080 offscreen").font(ThermoFont.mono(10)).foregroundColor(Forge.ink1)
                } else if let t = engine.uxTest {
                    Text("UX TEST \(t.number)/\(UxTest.allCases.count) · \(t.title)").font(ThermoFont.mono(13, weight: .black)).foregroundColor(Forge.ink0)
                    Text(engine.uxDetail).font(ThermoFont.mono(10)).foregroundColor(Forge.ink1).lineLimit(1)
                } else if let t = engine.aiTest {
                    Text("AI TEST \(t.number)/\(AiTest.allCases.count) · \(t.title)").font(ThermoFont.mono(13, weight: .black)).foregroundColor(Forge.ink0)
                    Text(engine.aiDetail).font(ThermoFont.mono(10)).foregroundColor(Forge.ink1).lineLimit(1).minimumScaleFactor(0.7)
                } else {
                    Text("GETTING READY").font(ThermoFont.mono(13, weight: .black)).foregroundColor(Forge.ink0)
                }
            }
            Spacer()
            if let (value, unit) = liveRate {
                HStack(alignment: .lastTextBaseline, spacing: 6) {
                    Text(value < 100 ? String(format: "%.1f", value) : value.formatted(.number.precision(.fractionLength(0))))
                        .font(ThermoFont.mono(30, weight: .black)).foregroundColor(Forge.ink0)
                        .contentTransition(.numericText())
                    Text(unit).font(ThermoFont.mono(10, weight: .bold)).foregroundColor(Forge.ink2)
                }
            }
        }
    }

    private var liveRate: (Double, String)? {
        if engine.cpuTest != nil, let p = engine.cpuProgress, p.rate >= 0 { return (p.rate, "OPS/S") }
        if engine.memTest != nil, let p = engine.memProgress, p.rate >= 0 { return (p.rate, p.unit) }
        if engine.gpuTest != nil, engine.gpuFps >= 0 { return (engine.gpuFps, "FPS") }
        if engine.uxTest != nil, engine.uxValue >= 0 { return (engine.uxValue, engine.uxUnit) }
        if engine.aiTest != nil, engine.aiRate >= 0 { return (engine.aiRate, "INF/S") }
        return nil
    }
}

private struct PreparingView: View {
    let busy: Bool
    let elapsed: Int
    var body: some View {
        VStack(spacing: 14) {
            ProgressView().tint(Forge.phaseCooldown).scaleEffect(1.3)
            Text("❄ GETTING READY · \(elapsed)s").font(ThermoFont.mono(11, weight: .bold)).tracking(1).foregroundColor(Forge.phaseCooldown)
            Text(busy ? "Waiting for the phone to finish background work" : "Letting the phone cool to a fair start")
                .font(ThermoFont.mono(9)).foregroundColor(Forge.ink2).multilineTextAlignment(.center)
        }
    }
}

/** The test's measurements as chips: done, running, waiting. */
struct StageChips: View {
    let labels: [String]
    let current: Int
    let accent: Color
    var body: some View {
        let cols = [GridItem(.adaptive(minimum: 104), spacing: 8)]
        LazyVGrid(columns: cols, spacing: 8) {
            ForEach(Array(labels.enumerated()), id: \.offset) { i, label in
                let done = i < current, now = i == current
                Text((done ? "✓ " : "") + label)
                    .font(ThermoFont.mono(10, weight: now ? .black : .bold))
                    .foregroundColor(now ? accent : done ? Forge.ink1 : Forge.ink2)
                    .lineLimit(1).minimumScaleFactor(0.7)
                    .padding(.horizontal, 10).padding(.vertical, 7)
                    .frame(maxWidth: .infinity)
                    .background(now ? accent.opacity(0.18) : Forge.surface)
                    .cornerRadius(10)
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(now ? accent : done ? accent.opacity(0.4) : Forge.hairline, lineWidth: 1))
            }
        }
    }
}

// MARK: - CPU

/** The test's kernels, and one lane per thread at work (iOS shows apps no core clocks). */
struct CpuStageView: View {
    let test: CpuTest
    let progress: CpuProgress?
    let accent: Color

    var body: some View {
        let labels = test.labels
        let current = progress.map { labels.firstIndex(of: $0.kernel) ?? 0 } ?? 0
        let threads = progress?.threads ?? 1
        let lanes = ProcessInfo.processInfo.activeProcessorCount
        VStack(spacing: 26) {
            StageChips(labels: labels, current: current, accent: accent)
            TimelineView(.animation) { tl in
                let t = tl.date.timeIntervalSinceReferenceDate
                HStack(alignment: .bottom, spacing: 8) {
                    ForEach(0..<lanes, id: \.self) { i in
                        let busy = i < threads
                        let h = busy ? 0.55 + 0.45 * abs(sin(t * 3 + Double(i) * 0.9)) : 0.06
                        VStack(spacing: 4) {
                            ZStack(alignment: .bottom) {
                                RoundedRectangle(cornerRadius: 5).fill(Forge.surface).frame(width: 22, height: 96)
                                RoundedRectangle(cornerRadius: 5).fill(accent.opacity(busy ? 0.35 + 0.65 * h : 0.15)).frame(width: 22, height: 96 * h)
                            }
                            Text("T\(i)").font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
                        }
                    }
                }
            }
            .frame(height: 120)
            Text(threads > 1 ? "\(threads) THREADS · EVERY CORE" : "1 THREAD · ONE CORE")
                .font(ThermoFont.mono(9)).tracking(1.5).foregroundColor(Forge.ink2)
        }
    }
}

// MARK: - MEM

/** The test's measurements over a block map of the RAM buffer or the test file (reads copper, writes coral). */
struct MemStageView: View {
    let test: MemTest
    let progress: MemProgress?
    @StateObject private var map = BlockMap()

    var body: some View {
        let current = progress.map { test.labels.firstIndex(of: $0.measure) ?? 0 } ?? 0
        VStack(spacing: 20) {
            StageChips(labels: test.labels, current: current, accent: BenchColor.mem)
            TimelineView(.animation) { tl in
                Canvas { ctx, size in
                    map.step(now: tl.date.timeIntervalSinceReferenceDate, access: progress?.access, lanes: test == .ram)
                    map.draw(ctx, size, accent: BenchColor.mem)
                }
            }
            .aspectRatio(CGFloat(BlockMap.cols) / CGFloat(BlockMap.rows) * 0.9, contentMode: .fit)
            Text(progress?.detail ?? "").font(ThermoFont.mono(9)).tracking(1.5).foregroundColor(Forge.ink2)
                .multilineTextAlignment(.center)
        }
    }
}

/** The buffer or file as cols × rows blocks; each glows when touched and fades. */
final class BlockMap: ObservableObject {
    static let cols = 24, rows = 12
    private var heat = [Float](repeating: 0, count: cols * rows)
    private var written = [Bool](repeating: false, count: cols * rows)
    private var trail: [Int] = []
    private var head: Double = 0
    private var last: Double = 0
    private var hopIn: Double = 0
    private var access: MemAccess?
    private var rng = SplitMix(seed: 11)

    func step(now: Double, access a: MemAccess?, lanes: Bool) {
        if a != access { access = a; heat = heat.map { _ in 0 }; trail.removeAll(); head = 0 }
        let dt = last == 0 ? 0 : min(0.1, now - last)
        last = now
        let fade = Float(exp(-dt * 5))
        for i in heat.indices { heat[i] *= fade }
        func touch(_ i: Int, _ write: Bool) { heat[i] = 1; written[i] = write }
        let cols = Self.cols, rows = Self.rows
        switch a {
        case .read?, .write?:
            let write = a == .write
            let span = lanes ? cols : cols * rows
            let from = Int(head)
            head = (head + dt * (lanes ? 22 : 60)).truncatingRemainder(dividingBy: Double(span))
            var c = from
            while c != Int(head) {
                if lanes { for r in 0..<rows { touch(r * cols + (c + r * 5) % cols, write) } } else { touch(c, write) }
                c = (c + 1) % span
            }
        case .copy?:
            let from = Int(head)
            head = (head + dt * 22).truncatingRemainder(dividingBy: Double(cols))
            var c = from
            while c != Int(head) {
                for r in 0..<(rows / 2) {
                    touch(r * cols + (c + r * 5) % cols, false)
                    touch((r + rows / 2) * cols + (c + r * 5) % cols, true)
                }
                c = (c + 1) % cols
            }
        case .randomRead?, .randomWrite?:
            for _ in 0..<max(1, Int(dt * 160)) { touch(rng.below(heat.count), a == .randomWrite) }
        case .chase?:
            hopIn -= dt
            if hopIn <= 0 {
                hopIn = 0.07
                let next = rng.below(heat.count)
                touch(next, false)
                trail.append(next)
                if trail.count > 7 { trail.removeFirst() }
            }
        case nil: break
        }
    }

    func draw(_ ctx: GraphicsContext, _ size: CGSize, accent: Color) {
        let gap: CGFloat = 3, cols = Self.cols, rows = Self.rows
        let cw = (size.width - gap * CGFloat(cols - 1)) / CGFloat(cols)
        let ch = (size.height - gap * CGFloat(rows - 1)) / CGFloat(rows)
        func rect(_ i: Int) -> CGRect { CGRect(x: CGFloat(i % cols) * (cw + gap), y: CGFloat(i / cols) * (ch + gap), width: cw, height: ch) }
        for i in heat.indices {
            let r = rect(i)
            ctx.fill(Path(roundedRect: r, cornerRadius: 2), with: .color(Forge.surface))
            if heat[i] > 0.02 {
                ctx.fill(Path(roundedRect: r, cornerRadius: 2),
                         with: .color((written[i] ? BenchColor.memWrite : accent).opacity(0.15 + 0.85 * Double(heat[i]))))
            }
        }
        if access == .chase, trail.count > 1 {
            for k in 1..<trail.count {
                var p = Path()
                p.move(to: CGPoint(x: rect(trail[k - 1]).midX, y: rect(trail[k - 1]).midY))
                p.addLine(to: CGPoint(x: rect(trail[k]).midX, y: rect(trail[k]).midY))
                ctx.stroke(p, with: .color(accent.opacity(0.2 + 0.8 * Double(k) / Double(trail.count))), lineWidth: 1.5)
            }
        }
    }
}

// MARK: - progress across the whole benchmark

struct BenchProgressBar: View {
    @ObservedObject var engine: BenchmarkEngine

    private var segments: [(Int, Color)] {
        CpuTest.allCases.map { ($0.seconds, BenchColor.cpu) } + MemTest.allCases.map { ($0.seconds, BenchColor.mem) } +
            GpuTest.allCases.map { ($0.seconds, BenchColor.gpu($0)) } + UxTest.allCases.map { ($0.seconds, BenchColor.ux($0)) } +
            AiTest.allCases.map { ($0.seconds, BenchColor.ai) }
    }

    private var elapsed: Double {
        let cpuTotal = Double(CpuTest.allCases.reduce(0) { $0 + $1.seconds })
        if let t = engine.cpuTest {
            return Double(CpuTest.allCases.filter { $0.number < t.number }.reduce(0) { $0 + $1.seconds }) + (engine.cpuProgress?.fraction ?? 0) * Double(t.seconds)
        }
        let memTotal = Double(MemTest.allCases.reduce(0) { $0 + $1.seconds })
        if let t = engine.memTest {
            return cpuTotal + Double(MemTest.allCases.filter { $0.number < t.number }.reduce(0) { $0 + $1.seconds }) + (engine.memProgress?.fraction ?? 0) * Double(t.seconds)
        }
        if let t = engine.gpuTest {
            return cpuTotal + memTotal + Double(GpuTest.allCases.filter { $0.number < t.number }.reduce(0) { $0 + $1.seconds }) + engine.gpuProgress * Double(t.seconds)
        }
        let gpuTotal = Double(GpuTest.allCases.reduce(0) { $0 + $1.seconds })
        if let t = engine.uxTest {
            return cpuTotal + memTotal + gpuTotal + Double(UxTest.allCases.filter { $0.number < t.number }.reduce(0) { $0 + $1.seconds }) + engine.uxProgress * Double(t.seconds)
        }
        let uxTotal = Double(UxTest.allCases.reduce(0) { $0 + $1.seconds })
        if let t = engine.aiTest {
            return cpuTotal + memTotal + gpuTotal + uxTotal + Double(AiTest.allCases.filter { $0.number < t.number }.reduce(0) { $0 + $1.seconds }) + engine.aiProgress * Double(t.seconds)
        }
        return 0
    }

    var body: some View {
        let segs = segments
        let total = Double(segs.reduce(0) { $0 + $1.0 })
        let done = elapsed
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(engine.cpuTest.map { "CPU TEST \($0.number) · \($0.title)" } ?? engine.memTest.map { "MEM TEST \($0.number) · \($0.title)" }
                     ?? engine.gpuTest.map { "GPU TEST \($0.number) · \($0.title)" } ?? engine.uxTest.map { "UX TEST \($0.number) · \($0.title)" }
                     ?? engine.aiTest.map { "AI TEST \($0.number) · \($0.title)" } ?? "PREPARING")
                    .font(ThermoFont.mono(9, weight: .bold)).tracking(1.5).foregroundColor(Forge.ink0)
                Spacer()
                Text("\(Int(done / total * 100))%").font(ThermoFont.mono(9)).foregroundColor(Forge.ink1)
            }
            Canvas { ctx, size in
                let gap: CGFloat = 3
                var x: CGFloat = 0, start = 0.0
                for (secs, color) in segs {
                    let w = (size.width - gap * CGFloat(segs.count - 1)) * CGFloat(Double(secs) / total)
                    ctx.fill(Path(roundedRect: CGRect(x: x, y: 0, width: w, height: size.height), cornerRadius: size.height / 2), with: .color(color.opacity(0.2)))
                    let fill = min(1, max(0, (done - start) / Double(secs)))
                    if fill > 0 {
                        ctx.fill(Path(roundedRect: CGRect(x: x, y: 0, width: w * CGFloat(fill), height: size.height), cornerRadius: size.height / 2), with: .color(color))
                    }
                    x += w + gap
                    start += Double(secs)
                }
            }
            .frame(height: 6)
            let remaining = max(0, Int((total - done).rounded(.up)))
            Text(String(format: "ENDS IN %02d:%02d", remaining / 60, remaining % 60)).font(ThermoFont.mono(9)).tracking(1.5).foregroundColor(Forge.ink2)
        }
    }
}

/** The QR frame under the scanner, with the code it found outlined. */
private struct QrStageView: View {
    let frame: UIImage?
    let found: [CGPoint]?
    let accent: Color

    var body: some View {
        ZStack {
            Color.black
            if let frame { Image(uiImage: frame).resizable().scaledToFit() }
            Canvas { ctx, size in
                guard let pts = found, pts.count >= 3 else { return }
                // frame pixels onto the fitted image
                let s = min(size.width / CGFloat(QrBench.width), size.height / CGFloat(QrBench.height))
                let ox = (size.width - CGFloat(QrBench.width) * s) / 2, oy = (size.height - CGFloat(QrBench.height) * s) / 2
                let mapped = pts.map { CGPoint(x: ox + $0.x * s, y: oy + $0.y * s) }
                var path = Path()
                path.addLines(mapped)
                path.closeSubpath()
                ctx.stroke(path, with: .color(accent.opacity(0.8)), lineWidth: 2)
                for p in mapped { ctx.stroke(Path(ellipseIn: CGRect(x: p.x - 5, y: p.y - 5, width: 10, height: 10)), with: .color(accent), lineWidth: 2) }
            }
        }
    }
}

/** Names the photo editor's four preview panels (top-left, top-right, bottom-left, bottom-right). */
private struct PhotoPanelLabels: View {
    var body: some View {
        GeometryReader { g in
            ForEach(Array(PhotoEditScene.panels.enumerated()), id: \.offset) { i, name in
                Text("\(i + 1) · \(name)")
                    .font(ThermoFont.mono(7, weight: .bold)).foregroundColor(.white)
                    .padding(.horizontal, 4).padding(.vertical, 2)
                    .background(Color.black.opacity(0.55)).cornerRadius(4)
                    .position(x: g.size.width * (i % 2 == 0 ? 0.25 : 0.75), y: g.size.height * (i < 2 ? 0.06 : 0.56))
            }
        }
    }
}
