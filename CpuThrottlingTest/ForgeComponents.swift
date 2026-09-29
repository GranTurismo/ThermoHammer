import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// FORGE COMPONENTS — iOS port
// PhaseRail · ThermalReactor · ThroughputRibbon · CoreConstellation · ThermalHorizon
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Phase Rail
// PRE-FLIGHT ▸ COOLDOWN ▸ WARM-UP ▸ CALIBRATION ▸ MEASURED ▸ VERDICT

private let railPhases = ["PRE-FLIGHT", "COOLDOWN", "WARM-UP", "CALIBRATE", "MEASURED", "VERDICT"]

private func railSegmentColor(_ i: Int) -> Color {
    switch i {
    case 0: return Forge.phasePreFlight
    case 1: return Forge.phaseCooldown
    case 2: return Forge.phaseWarmup
    case 3: return Forge.phaseCalibration
    case 4: return Forge.phaseMeasured
    default: return Ramp.at(0.05)
    }
}

struct PhaseRail: View {
    let phase: RunPhase
    var preflightActive: Bool = false
    var completed: Bool = false
    var progress: Double = 0       // 0..1 within the CURRENT phase
    var progressLabel: String = ""

    private var activeIdx: Int {
        if completed { return 5 }
        switch phase {
        case .measured: return 4
        case .calibration: return 3
        case .warmup: return 2
        case .cooldown: return 1
        default: return preflightActive ? 0 : -1
        }
    }

    private var fillColor: Color {
        if completed { return Ramp.at(0.05) }
        if preflightActive { return Forge.phasePreFlight }
        return phaseColor(phase)
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(0..<railPhases.count, id: \.self) { i in
                    let isActive = i == activeIdx
                    let segColor: Color = i < activeIdx ? railSegmentColor(i).opacity(0.55)
                        : i == activeIdx ? railSegmentColor(i)
                        : Forge.ink2.opacity(0.30)
                    let lineColor: Color = i <= activeIdx ? railSegmentColor(i).opacity(0.5)
                        : Forge.ink2.opacity(0.18)
                    VStack(spacing: 5) {
                        HStack(spacing: 0) {
                            if i > 0 { lineColor.frame(height: 1) }
                            Circle()
                                .fill(segColor)
                                .frame(width: isActive ? 7 : 5, height: isActive ? 7 : 5)
                                .opacity(isActive ? 1 : 1)
                                .shadow(color: isActive ? segColor.opacity(0.8) : .clear, radius: isActive ? 4 : 0)
                            if i < railPhases.count - 1 { lineColor.frame(height: 1) }
                        }
                        Text(railPhases[i])
                            .font(ThermoFont.mono(6.5, weight: isActive ? .black : .bold))
                            .tracking(0.4)
                            .foregroundColor(segColor)
                    }
                    .frame(maxWidth: .infinity)
                    .animation(.easeInOut(duration: 0.28), value: activeIdx)
                }
            }

            if activeIdx >= 0 {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Forge.ink2.opacity(0.18))
                        // quarter ticks
                        HStack(spacing: 0) {
                            ForEach(1..<4, id: \.self) { q in
                                Color.clear.frame(width: geo.size.width / 4)
                                    .overlay(alignment: .leading) {
                                        Forge.bg.frame(width: 1.5, height: 4)
                                    }
                            }
                            Color.clear.frame(maxWidth: .infinity)
                        }
                        Capsule()
                            .fill(fillColor)
                            .frame(width: max(0, geo.size.width * min(1, progress)))
                            .shadow(color: fillColor.opacity(0.7), radius: 3)
                    }
                }
                .frame(height: 4)
                .padding(.top, 8)
                .animation(.easeOut(duration: 0.28), value: progress)

                if !progressLabel.isEmpty {
                    HStack {
                        Text(railPhases[max(0, activeIdx)])
                            .font(ThermoFont.mono(7, weight: .bold))
                            .tracking(1)
                            .foregroundColor(fillColor.opacity(0.8))
                        Spacer()
                        Text(progressLabel)
                            .font(ThermoFont.mono(7, weight: .bold))
                            .foregroundColor(Forge.ink1)
                    }
                    .padding(.top, 4)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Forge.surface)
        .cornerRadius(12)
    }
}

// MARK: - Thermal Reactor — hero instrument
//   outer ring = one arc per CPU cluster (worker throughput ratio — iOS has no cpufreq)
//   middle ring = thermal coolant (thermalState mapped to headroom 1.0 → 0.05)
//   center      = delivered-capacity numeral on the ramp + onset marker + countdown

struct ThermalReactor: View {
    let state: StressState
    let topology: ClusterTopology
    var completed: Bool = false

    private var running: Bool { state.phase != .idle }
    private var idle: Bool { state.phase == .idle && !completed }

    private var capacity: Double {
        guard state.baselineIps > 0 else { return 100 }
        return min(110, state.liveIps / state.baselineIps * 100)
    }

    private var headroom: Double {
        switch state.thermalState {
        case .nominal: return 1.0
        case .fair: return 0.66
        case .serious: return 0.33
        case .critical: return 0.05
        @unknown default: return 1.0
        }
    }

    private var clusterRatios: [Double] {
        topology.clusters.map { cl in
            let rs = cl.compactMap { state.workerRatios.indices.contains($0) ? state.workerRatios[$0] : nil }
            return rs.isEmpty ? 0 : rs.reduce(0, +) / Double(rs.count)
        }
    }

    private var onsetSec: Int? {
        let b = state.baselineIps
        guard b > 0, state.stamps.count >= 3 else { return nil }
        let s = state.stamps
        for i in 0..<(s.count - 2) where Double(s[i].ipsTotal) < b * 0.9
            && Double(s[i+1].ipsTotal) < b * 0.9 && Double(s[i+2].ipsTotal) < b * 0.9 {
            return s[i].elapsedMs / 1000
        }
        return nil
    }

    var body: some View {
        let rampColor = Ramp.forCapacity(capacity)
        let phaseCol = phaseColor(state.phase)
        let arming = state.phase == .cooldown || state.phase == .warmup || state.phase == .calibration

        ZStack {
            Canvas { ctx, size in
                let c = CGPoint(x: size.width / 2, y: size.height / 2)
                let R = min(size.width, size.height) / 2

                // bloom when hot/active
                if running {
                    let strength = (min(45, max(0, 100 - capacity)) / 100 * 0.5) + 0.06
                    var bloomCtx = ctx
                    bloomCtx.addFilter(.blur(radius: 18))
                    bloomCtx.fill(Path(ellipseIn: CGRect(x: c.x - R * 0.9, y: c.y - R * 0.9,
                                                         width: R * 1.8, height: R * 1.8)),
                                  with: .color(rampColor.opacity(strength)))
                }

                // OUTER — cluster tick ring
                let outerR = R * 0.92
                let gapDeg = 6.0
                let nCl = max(1, topology.clusters.count)
                let segSweep = 360.0 / Double(nCl)

                // dormant base ring
                ctx.stroke(Path(ellipseIn: CGRect(x: c.x - outerR, y: c.y - outerR,
                                                  width: outerR * 2, height: outerR * 2)),
                           with: .color(Forge.hairline.opacity(idle ? 0.5 : 0.35)), lineWidth: 2)

                for (ci, cores) in topology.clusters.enumerated() {
                    let startA = -90 + ci * Int(segSweep) + Int(gapDeg / 2)
                    let sweep = segSweep - gapDeg
                    let ratio = min(1, max(0, ci < clusterRatios.count ? clusterRatios[ci] : 0))

                    let rect = CGRect(x: c.x - outerR, y: c.y - outerR, width: outerR * 2, height: outerR * 2)
                    if idle {
                        ctx.stroke(arcPath(rect: rect, start: Double(startA), sweep: sweep),
                                   with: .color(Forge.ink2.opacity(0.45)), lineWidth: 2)
                    } else {
                        ctx.stroke(arcPath(rect: rect, start: Double(startA), sweep: sweep),
                                   with: .color(Forge.hairline), lineWidth: 7)
                        ctx.stroke(arcPath(rect: rect, start: Double(startA), sweep: sweep * ratio),
                                   with: .color(Ramp.at(1 - ratio).opacity(arming ? 0.55 : 1)), lineWidth: 7)
                        // per-worker tick marks
                        for (k, core) in cores.enumerated() {
                            let cr = min(1, max(0, core < state.workerRatios.count ? state.workerRatios[core] : 0))
                            let a = (Double(startA) + (Double(k) + 0.5) * (sweep / Double(max(1, cores.count)))) * .pi / 180
                            let inner = outerR - 14
                            let outer = outerR - 14 - 8 * cr - 2
                            var p = Path()
                            p.move(to: CGPoint(x: c.x + inner * cos(a), y: c.y + inner * sin(a)))
                            p.addLine(to: CGPoint(x: c.x + outer * cos(a), y: c.y + outer * sin(a)))
                            ctx.stroke(p, with: .color(Ramp.at(1 - cr).opacity(0.9)),
                                       style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                        }
                    }
                }

                // MIDDLE — thermal coolant arc
                let midR = R * 0.70
                ctx.stroke(Path(ellipseIn: CGRect(x: c.x - midR, y: c.y - midR, width: midR * 2, height: midR * 2)),
                           with: .color(Forge.hairline), lineWidth: 3)
                if !idle {
                    ctx.stroke(arcPath(rect: CGRect(x: c.x - midR, y: c.y - midR, width: midR * 2, height: midR * 2),
                                       start: -90, sweep: 360 * headroom),
                               with: .color(Ramp.at(1 - headroom).opacity(0.9)),
                               style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
                }

                // idle dormant wireframe
                if idle {
                    ctx.stroke(Path(ellipseIn: CGRect(x: c.x - R * 0.55, y: c.y - R * 0.55, width: R * 1.1, height: R * 1.1)),
                               with: .color(Forge.ink2.opacity(0.15)), lineWidth: 1.5)
                }
            }

            // arming radar sweep
            if arming {
                ArmingSweep(color: phaseCol)
            }

            // center readout
            VStack(spacing: 3) {
                if idle {
                    Text("STANDBY")
                        .font(ThermoFont.mono(15, weight: .bold)).tracking(4)
                        .foregroundColor(Forge.ink2.opacity(0.9))
                    Engraved(text: "THERMAL REACTOR", size: 8)
                } else if state.phase != .measured {
                    Text(state.phase.rawValue)
                        .font(ThermoFont.mono(14, weight: .black)).tracking(2)
                        .foregroundColor(phaseCol)
                    Engraved(text: {
                        switch state.phase {
                        case .cooldown: return "\(state.thermalState.name) → NOMINAL · \(Int(state.cooldownElapsed))s"
                        case .warmup: return "settling scheduler + jit"
                        default: return "locking baseline"
                        }
                    }(), size: 8)
                } else {
                    TickerText(value: capacity, format: { String(format: "%.1f%%", $0) },
                               color: rampColor, size: 42, font: ThermoFont.display(42))
                        .animation(.easeOut(duration: 0.28), value: capacity)
                    Engraved(text: "DELIVERED CAPACITY", size: 7.5)
                    let remaining = max(0, Int(state.duration - state.elapsed))
                    Text(String(format: "T-%02d:%02d", remaining / 60, remaining % 60))
                        .font(ThermoFont.mono(10))
                        .foregroundColor(Forge.ink1)
                    if let onset = onsetSec {
                        Text(String(format: "◇ ONSET %d:%02d", onset / 60, onset % 60))
                            .font(ThermoFont.mono(9, weight: .bold))
                            .foregroundColor(Ramp.at(0.68))
                    }
                }
            }
            .animation(.easeInOut(duration: 0.35), value: state.phase)
        }
        .frame(width: 240, height: 240)
    }

    private func arcPath(rect: CGRect, start: Double, sweep: Double) -> Path {
        var p = Path()
        p.addArc(center: CGPoint(x: rect.midX, y: rect.midY),
                 radius: rect.width / 2,
                 startAngle: .degrees(start), endAngle: .degrees(start + sweep), clockwise: false)
        return p
    }
}

/// Radar sweep line used during arming phases.
private struct ArmingSweep: View {
    let color: Color
    @State private var angle: Double = 0
    var body: some View {
        Canvas { ctx, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let r = min(size.width, size.height) / 2 * 0.80
            var p = Path()
            p.addArc(center: c, radius: r, startAngle: .degrees(angle - 60), endAngle: .degrees(angle), clockwise: false)
            ctx.stroke(p, with: .color(color.opacity(0.8)), lineWidth: 2.5)
        }
        .onAppear {
            withAnimation(.linear(duration: 2.2).repeatForever(autoreverses: false)) { angle = 360 }
        }
    }
}

// MARK: - Throughput Ribbon
// Ghost prefix (warm-up steel + calibration violet) then the measured ribbon —
// cluster-spread band = per-worker min..max throughput share.

struct ThroughputRibbon: View {
    let stamps: [StampV2]
    let preRun: [(phase: RunPhase, ips: Double)]
    let baselineIps: Double
    let duration: TimeInterval
    var onInspect: ((StampV2?) -> Void)? = nil

    private static let warmupS: Double = 12
    private static let calS: Double = 20

    private func xFor(_ t: Double, preSpan: Double, measSpan: Double, w: CGFloat) -> CGFloat {
        // pre-run occupies left [-32s .. 0], measured right [0 .. duration]
        let preW = w * 0.22
        if t < 0 { return preW * CGFloat(1 + t / preSpan) }
        return preW + (w - preW) * CGFloat(t / measSpan)
    }

    var body: some View {
        Canvas { ctx, size in
            let w = size.width, h = size.height
            let base = max(1, baselineIps)
            func y(_ pct: Double) -> CGFloat { h - CGFloat(min(1.05, max(0, pct)) / 1.05) * h }

            // ── regions & grid ──
            ctx.fill(Path(CGRect(x: 0, y: 0, width: w * 0.22 * (Self.warmupS / 32), height: h)),
                     with: .color(Forge.phaseWarmup.opacity(0.06)))
            ctx.fill(Path(CGRect(x: w * 0.22 * (Self.warmupS / 32), y: 0,
                                 width: w * 0.22 * (Self.calS / 32), height: h)),
                     with: .color(Forge.phaseCalibration.opacity(0.08)))

            // 100% potential line
            var pot = Path()
            pot.move(to: CGPoint(x: 0, y: y(1.0))); pot.addLine(to: CGPoint(x: w, y: y(1.0)))
            ctx.stroke(pot, with: .color(Forge.ink2.opacity(0.5)),
                       style: StrokeStyle(lineWidth: 1, dash: [8, 6]))

            // t = 0 divider
            let x0 = w * 0.22
            var div = Path()
            div.move(to: CGPoint(x: x0, y: 0)); div.addLine(to: CGPoint(x: x0, y: h))
            ctx.stroke(div, with: .color(Forge.ink2.opacity(0.7)),
                       style: StrokeStyle(lineWidth: 1, dash: [4, 4]))

            // ── ghost prefix — warm-up + calibration collapse, honest and visible ──
            if !preRun.isEmpty {
                var ghostPts: [(x: CGFloat, y: CGFloat, ph: RunPhase)] = []
                let n = preRun.count
                for (i, s) in preRun.enumerated() {
                    // map index → time in [-32, 0]
                    let t = -32 + 32 * Double(i + 1) / Double(n)
                    let pct = s.ips / base
                    ghostPts.append((xFor(t, preSpan: 32, measSpan: 1, w: w), y(pct), s.phase))
                }
                var gp = Path()
                gp.move(to: ghostPts[0].0 == ghostPts[0].0 ? CGPoint(x: ghostPts[0].x, y: ghostPts[0].y) : .zero)
                for p in ghostPts.dropFirst() { gp.addLine(to: CGPoint(x: p.x, y: p.y)) }
                ctx.stroke(gp, with: .color(Forge.ink1.opacity(0.55)), lineWidth: 1.5)
            }

            // ── measured ribbon ──
            if stamps.count >= 2 {
                let top = Path()
                var tp = Path(); var bp = Path()
                var pts: [(x: CGFloat, y: CGFloat, a: Int)] = []
                for s in stamps {
                    let t = Double(s.elapsedMs) / 1000
                    let pct = Double(s.ipsTotal) / base
                    pts.append((xFor(t, preSpan: 32, measSpan: duration, w: w), y(pct), s.attribution))
                }
                tp.move(to: CGPoint(x: pts[0].x, y: pts[0].y))
                for p in pts.dropFirst() { tp.addLine(to: CGPoint(x: p.x, y: p.y)) }
                _ = top
                // attribution-tinted edge — color each segment by its stamp's attribution
                for i in 1..<pts.count {
                    var seg = Path()
                    seg.move(to: CGPoint(x: pts[i-1].x, y: pts[i-1].y))
                    seg.addLine(to: CGPoint(x: pts[i].x, y: pts[i].y))
                    let c = attributionColor(Attribution(rawValue: pts[i].a) ?? .none)
                    ctx.stroke(seg, with: .color(c), lineWidth: 2.5)
                }
                // filled area under the edge
                bp = tp
                bp.addLine(to: CGPoint(x: pts.last!.x, y: h))
                bp.addLine(to: CGPoint(x: pts[0].x, y: h))
                bp.closeSubpath()
                ctx.fill(bp, with: .color(Forge.phaseMeasured.opacity(0.10)))

                // head pip
                if let last = pts.last {
                    ctx.fill(Path(ellipseIn: CGRect(x: last.x - 5, y: last.y - 5, width: 10, height: 10)),
                             with: .color(attributionColor(Attribution(rawValue: last.a) ?? .none).opacity(0.3)))
                    ctx.fill(Path(ellipseIn: CGRect(x: last.x - 2.5, y: last.y - 2.5, width: 5, height: 5)),
                             with: .color(attributionColor(Attribution(rawValue: last.a) ?? .none)))
                }
            }

            // axis labels
            let lblFont = Font.custom(ThermoFont.monoRegular, size: 7)
            ctx.draw(Text("-32s PRE-RUN").font(lblFont).foregroundColor(Forge.ink2),
                     at: CGPoint(x: w * 0.11, y: h - 7), anchor: .center)
            ctx.draw(Text("▸ MEASURED").font(lblFont).foregroundColor(Forge.ink2),
                     at: CGPoint(x: x0 + 24, y: h - 7), anchor: .center)
        }
        .frame(height: 130)
    }
}

// MARK: - Core Constellation
// Cluster cards: PERF/EFF groups, worker throughput gauges.
// Honest tag: iOS does not expose per-core frequency.

struct CoreConstellation: View {
    let topology: ClusterTopology
    let workerRatios: [Double]
    let activeWorkers: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Engraved(text: "CORE CONSTELLATION")
                Spacer()
                Engraved(text: topology.describe(), color: Forge.ink1, size: 8)
            }
            ForEach(0..<topology.clusters.count, id: \.self) { ci in
                let cores = topology.clusters[ci]
                let name = topology.names.indices.contains(ci) ? topology.names[ci] : "CPU"
                HStack(spacing: 8) {
                    Engraved(text: name, color: ci == topology.slowestClusterIdx ? Forge.phaseCalibration : Forge.phaseMeasured, size: 8)
                        .frame(width: 34, alignment: .leading)
                    ForEach(cores, id: \.self) { core in
                        let active = core < activeWorkers
                        let r = core < workerRatios.count ? workerRatios[core] : 0
                        WorkerGauge(ratio: active ? r : 0, active: active)
                    }
                }
            }
            Engraved(text: "frequency telemetry unavailable on iOS — gauges show worker throughput share", size: 7)
                .opacity(0.7)
        }
        .padding(12)
        .background(Forge.surface)
        .cornerRadius(12)
        .cornerTicks()
    }
}

struct WorkerGauge: View {
    let ratio: Double
    let active: Bool
    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: 2).fill(Forge.hairline)
                RoundedRectangle(cornerRadius: 2)
                    .fill(active ? Ramp.at(1 - min(1, ratio)) : Forge.ink2.opacity(0.3))
                    .frame(height: geo.size.height * CGFloat(min(1, max(0, ratio))))
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 34)
        .animation(.easeOut(duration: 0.35), value: ratio)
    }
}

// MARK: - Thermal Horizon
// Thermal-state dial + history + power context chips (battery level, ambient load).

struct ThermalHorizon: View {
    let thermalState: ProcessInfo.ThermalState
    let systemLoad: Double
    let history: [Int]      // thermalState raw values over time (per stamp)

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Engraved(text: "THERMAL HORIZON")
                Spacer()
                let lvl = UIDevice.current.batteryLevel
                Engraved(text: lvl >= 0 ? "BATT \(Int(lvl * 100))%" : "BATT --",
                         color: Forge.ink1, size: 8)
            }
            HStack(spacing: 14) {
                // state dial
                VStack(spacing: 2) {
                    Text(thermalState.name)
                        .font(ThermoFont.display(18, weight: .bold))
                        .foregroundColor(thermalStateColor(thermalState))
                        .shadow(color: thermalStateColor(thermalState).opacity(0.5), radius: 8)
                    Engraved(text: "SYSTEM STATE", size: 7)
                }
                .frame(width: 100)

                // sparkline of thermal-state history
                Canvas { ctx, size in
                    guard history.count >= 2 else { return }
                    var p = Path()
                    for (i, v) in history.enumerated() {
                        let x = size.width * CGFloat(i) / CGFloat(history.count - 1)
                        let yv = size.height * (1 - CGFloat(v) / 3.0)
                        if i == 0 { p.move(to: CGPoint(x: x, y: yv)) }
                        else { p.addLine(to: CGPoint(x: x, y: yv)) }
                    }
                    ctx.stroke(p, with: .color(thermalStateColor(thermalState).opacity(0.8)), lineWidth: 1.5)
                }
                .frame(height: 34)
            }
            HStack(spacing: 8) {
                Engraved(text: "AMBIENT LOAD \(Int(systemLoad * 100))%", size: 7.5)
                if ProcessInfo.processInfo.isLowPowerModeEnabled {
                    Engraved(text: "LOW POWER MODE", color: Ramp.at(0.5), size: 7.5)
                }
                if UIDevice.current.batteryState == .charging {
                    Engraved(text: "CHARGING", color: Ramp.at(0.62), size: 7.5)
                }
            }
        }
        .padding(12)
        .background(Forge.surface)
        .cornerRadius(12)
        .cornerTicks()
    }
}
