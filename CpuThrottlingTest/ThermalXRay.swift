import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// THERMAL X-RAY (iOS)
//
//   A schematic phone, seen from the back. Same instrument as the Android build,
//   with one hard difference: iOS gives apps NO thermal sensors — no zones, no °C,
//   not even battery temperature. So this view never shows a temperature. It lights
//   only the three signals an app can legitimately see, each labelled with its own
//   quantity:
//
//     · rim      — ProcessInfo.thermalState (system-wide pressure, 4 levels)
//     · CPU die  — per-core worker throughput + delivered capacity (our own test)
//     · battery  — charge level (a gauge, deliberately NOT on the heat ramp)
//
//   Everything else is hatched and says NO SENSOR. Outline is schematic: part
//   positions are typical, the readings beside them are not.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Regions

enum XRayRegion: CaseIterable, Hashable {
    case cpu, gpu, npu, mem, camera, modem, board, battery, charger, skin

    var label: String {
        switch self {
        case .cpu:     return "CPU / SOC"
        case .gpu:     return "GPU"
        case .npu:     return "NPU / DSP"
        case .mem:     return "MEMORY"
        case .camera:  return "CAMERA / ISP"
        case .modem:   return "MODEM / RF"
        case .board:   return "LOGIC BOARD"
        case .battery: return "BATTERY"
        case .charger: return "CHARGE / USB"
        case .skin:    return "SKIN"
        }
    }
}

/// What the view needs to draw and caption one lit region.
private struct XRayLit {
    var color: Color
    var intensity: Double            // 0…1 — glow strength
    var value: String
    var caption: String
    var label: String? = nil         // overrides the region's default label
    var fillLevel: Double? = nil     // battery: gauge fill instead of a heat glow
    var cores: [Double]? = nil       // CPU: one cell per core (worker throughput ratio)
    var activeCores: Int = 0
    var dormant: Bool = false        // known part, nothing live to show right now
}

// MARK: - Schematic geometry (functions of the body rect — mirrors the Android build)

private enum XRayGeo {
    static let bodyW: CGFloat = 160
    static let aspect: CGFloat = 0.47
    static let pad: CGFloat = 16
    static var bodyH: CGFloat { bodyW / aspect }
    static var canvasH: CGFloat { bodyH + pad * 2 }

    static let dieCY: CGFloat = 0.305
    static let dieHalf: CGFloat = 0.17          // of body width

    static func at(_ b: CGRect, _ ux: CGFloat, _ uy: CGFloat) -> CGPoint {
        CGPoint(x: b.minX + ux * b.width, y: b.minY + uy * b.height)
    }
    static func rect(_ b: CGRect, _ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat) -> CGRect {
        let p = at(b, x0, y0), q = at(b, x1, y1)
        return CGRect(x: p.x, y: p.y, width: q.x - p.x, height: q.y - p.y)
    }
    static func dieQuad(_ b: CGRect, col: Int, row: Int) -> CGRect {
        let half = dieHalf * b.width
        let gap = 0.016 * b.width
        let c = at(b, 0.5, dieCY)
        let x0 = col == 0 ? c.x - half : c.x + gap / 2
        let x1 = col == 0 ? c.x - gap / 2 : c.x + half
        let y0 = row == 0 ? c.y - half : c.y + gap / 2
        let y1 = row == 0 ? c.y - gap / 2 : c.y + half
        return CGRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }
    static func board(_ b: CGRect) -> CGRect { rect(b, 0.07, 0.185, 0.93, 0.47) }
    static func battery(_ b: CGRect) -> CGRect { rect(b, 0.10, 0.51, 0.90, 0.885) }
    static func camera(_ b: CGRect) -> CGRect { rect(b, 0.08, 0.035, 0.46, 0.15) }
    static func modem(_ b: CGRect) -> CGRect { rect(b, 0.62, 0.045, 0.92, 0.12) }
    static func charger(_ b: CGRect) -> CGRect { rect(b, 0.36, 0.925, 0.64, 0.955) }
}

private struct XRayNode {
    let region: XRayRegion
    let left: Bool                         // which gutter holds the callout
    let slot: CGFloat                      // callout centre, fraction of body height
    let anchor: (CGRect) -> CGPoint        // where the leader line lands on the body
}

private func mid(_ r: CGRect) -> CGPoint { CGPoint(x: r.midX, y: r.midY) }

private let xrayNodes: [XRayNode] = [
    XRayNode(region: .camera,  left: true,  slot: 0.07) { mid(XRayGeo.camera($0)) },
    XRayNode(region: .cpu,     left: true,  slot: 0.25) { mid(XRayGeo.dieQuad($0, col: 0, row: 0)) },
    XRayNode(region: .npu,     left: true,  slot: 0.43) { mid(XRayGeo.dieQuad($0, col: 0, row: 1)) },
    XRayNode(region: .board,   left: true,  slot: 0.61) { XRayGeo.at($0, 0.12, 0.42) },
    XRayNode(region: .battery, left: true,  slot: 0.79) { XRayGeo.at($0, 0.26, 0.70) },
    XRayNode(region: .modem,   left: false, slot: 0.07) { mid(XRayGeo.modem($0)) },
    XRayNode(region: .gpu,     left: false, slot: 0.25) { mid(XRayGeo.dieQuad($0, col: 1, row: 0)) },
    XRayNode(region: .mem,     left: false, slot: 0.43) { mid(XRayGeo.dieQuad($0, col: 1, row: 1)) },
    XRayNode(region: .skin,    left: false, slot: 0.61) { XRayGeo.at($0, 1.0, 0.56) },
    XRayNode(region: .charger, left: false, slot: 0.90) { mid(XRayGeo.charger($0)) },
]

// MARK: - Card

struct ThermalXRay: View {
    let state: StressState
    let topology: ClusterTopology
    let activeWorkers: Int

    @State private var showPeak = false
    @State private var batteryLevel: Float = UIDevice.current.batteryLevel
    @State private var batteryState: UIDevice.BatteryState = UIDevice.current.batteryState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Render budget: no ambient animation under reduced motion or thermal pressure.
    private var calm: Bool {
        reduceMotion || state.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue
    }

    // PEAK = worst of the run so far (nothing recorded yet → falls back to live).
    private var hasRunData: Bool { !state.stamps.isEmpty && state.baselineIps > 0 }
    private var peakMode: Bool { showPeak && hasRunData }

    var body: some View {
        let lit = litRegions()
        let reporting = lit.values.filter { !$0.dormant }.count

        VStack(alignment: .leading, spacing: 10) {
            header(reporting: reporting)
            XRayBody(lit: lit, topology: topology, calm: calm || peakMode)
                .frame(height: XRayGeo.canvasH)
            footer
        }
        .padding(12)
        .background(Forge.surface)
        .cornerRadius(12)
        .cornerTicks()
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.batteryLevelDidChangeNotification)) { _ in
            batteryLevel = UIDevice.current.batteryLevel
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.batteryStateDidChangeNotification)) { _ in
            batteryState = UIDevice.current.batteryState
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilitySummary(lit))
    }

    // MARK: header / footer

    private func header(reporting: Int) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 3) {
                Engraved(text: "THERMAL X-RAY")
                Engraved(text: "\(reporting) OF \(XRayRegion.allCases.count) REGIONS REPORTING",
                         color: reporting >= 3 ? Forge.phaseMeasured : Forge.ink1, size: 7)
            }
            Spacer()
            HStack(spacing: 6) {
                chip("LIVE", selected: !peakMode) { showPeak = false }
                chip("PEAK", selected: peakMode) { if hasRunData { showPeak = true } }
                    .opacity(hasRunData ? 1 : 0.4)
            }
        }
    }

    private func chip(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        let tint = selected ? Forge.phaseMeasured : Forge.ink2
        return Button(action: action) {
            Text(text)
                .font(ThermoFont.mono(7.5, weight: .bold))
                .tracking(0.9)
                .foregroundColor(tint)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(selected ? Forge.phaseMeasured.opacity(0.10) : Color.clear)
                .cornerRadius(6)
                .overlay(RoundedRectangle(cornerRadius: 6)
                    .stroke(tint.opacity(selected ? 0.4 : 0.25), lineWidth: 1))
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Engraved(text: "OK", size: 6.5)
                LinearGradient(colors: [0, 0.25, 0.5, 0.68, 0.85, 1].map { Ramp.at($0) },
                               startPoint: .leading, endPoint: .trailing)
                    .frame(height: 4).cornerRadius(2)
                Engraved(text: "DEGRADED", size: 6.5)
            }
            Text("\(LeaderboardService.isMac ? "macOS" : "iOS") gives apps no thermal sensors — no °C, no zones. Lit regions show the only " +
                 "signals an app can see: system thermal state, per-core worker throughput\(LeaderboardService.isMac ? "" : ", battery level"). " +
                 "Nothing is estimated.")
                .font(ThermoFont.mono(6.5)).foregroundColor(Forge.ink1)
            Text("Outline is schematic — part positions are typical, readings are measured. " +
                 "Hatched = no readable sensor.")
                .font(ThermoFont.mono(6.5)).foregroundColor(Forge.ink2)
            if peakMode {
                Button { showPeak = false } label: {
                    Engraved(text: "WORST OF RUN · TAP FOR LIVE", color: Forge.phaseMeasured, size: 7)
                }
            }
        }
    }

    // MARK: what iOS can honestly light

    private func litRegions() -> [XRayRegion: XRayLit] {
        var out: [XRayRegion: XRayLit] = [:]

        // SKIN rim ← system thermal state. Not a temperature; labelled as the state.
        let rawState: Int = {
            guard peakMode else { return state.thermalState.rawValue }
            return max(state.thermalState.rawValue, state.stamps.map { $0.thermalState }.max() ?? 0)
        }()
        let ts = ProcessInfo.ThermalState(rawValue: rawState) ?? .nominal
        out[.skin] = XRayLit(
            color: thermalStateColor(ts),
            intensity: [0.15, 0.4, 0.7, 1.0][min(3, max(0, rawState))],
            value: ts.name,
            caption: "SYSTEM-WIDE · NOT °C",
            label: peakMode ? "WORST STATE" : "THERMAL STATE")

        // CPU ← our own measurement of what the cores delivered.
        out[.cpu] = cpuLit()

        // BATTERY ← charge level only. A gauge, on purpose not on the heat ramp.
        if batteryLevel >= 0 && !LeaderboardService.isMac {        // a Mac's battery reading is made up: left dark
            let charging = batteryState == .charging || batteryState == .full
            out[.battery] = XRayLit(
                color: Forge.phasePreFlight,
                intensity: 0,
                value: "\(Int((batteryLevel * 100).rounded()))%",
                caption: charging ? "CHARGING · NO TEMP" : "NO TEMP SENSOR",
                label: "BATTERY · LEVEL",
                fillLevel: Double(batteryLevel))
        }
        return out
    }

    private func cpuLit() -> XRayLit {
        let phase = state.phase
        let n = topology.totalCores

        if peakMode {
            guard let m = state.stamps.map({ $0.ipsTotal }).min(), state.baselineIps > 0 else {
                return XRayLit(color: Forge.ink2, intensity: 0, value: "—", caption: "", dormant: true)
            }
            let worst = min(110, Double(m) / state.baselineIps * 100)
            return XRayLit(color: Ramp.forCapacity(worst), intensity: min(1, max(0, (100 - worst) / 55)),
                           value: String(format: "%.0f%%", worst),
                           caption: "LOWEST CAPACITY", label: "CPU · WORST")
        }
        switch phase {
        case .idle:
            return XRayLit(color: Forge.ink2, intensity: 0, value: "STANDBY", caption: "run a test to light",
                           label: "CPU / SOC", cores: Array(repeating: 0, count: n), activeCores: 0, dormant: true)
        case .cooldown:
            return XRayLit(color: Forge.phaseCooldown, intensity: 0, value: "COOLING", caption: "no load applied",
                           label: "CPU / SOC", cores: Array(repeating: 0, count: n), activeCores: 0, dormant: true)
        case .warmup, .calibration:
            return XRayLit(color: phaseColor(phase), intensity: 0.2,
                           value: phase == .warmup ? "WARM-UP" : "CALIBRATING",
                           caption: "building baseline", label: "CPU / SOC",
                           cores: state.workerRatios, activeCores: activeWorkers)
        case .measured:
            let cap = state.baselineIps > 0 ? min(110, state.liveIps / state.baselineIps * 100) : 100
            return XRayLit(color: Ramp.forCapacity(cap), intensity: min(1, max(0, (100 - cap) / 55)),
                           value: String(format: "%.0f%%", cap),
                           caption: "DELIVERED CAPACITY", label: "CPU · THROUGHPUT",
                           cores: state.workerRatios, activeCores: activeWorkers)
        }
    }

    private func accessibilitySummary(_ lit: [XRayRegion: XRayLit]) -> String {
        let parts = XRayRegion.allCases.compactMap { r -> String? in
            guard let l = lit[r], !l.dormant else { return nil }
            return "\(l.label ?? r.label) \(l.value)"
        }
        return "Thermal X-ray. " + (parts.isEmpty ? "No readable sensors" : parts.joined(separator: ", "))
    }
}

// MARK: - The body: canvas + callouts

private struct XRayBody: View {
    let lit: [XRayRegion: XRayLit]
    let topology: ClusterTopology
    let calm: Bool

    private let calloutH: CGFloat = 36

    var body: some View {
        GeometryReader { geo in
            let W = geo.size.width
            let bodyRect = CGRect(x: (W - XRayGeo.bodyW) / 2, y: XRayGeo.pad,
                                  width: XRayGeo.bodyW, height: XRayGeo.bodyH)
            let gutter = (W - XRayGeo.bodyW) / 2

            ZStack(alignment: .topLeading) {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: calm)) { tl in
                    let sweep: Double? = calm ? nil
                        : tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 6) / 6
                    Canvas { ctx, size in
                        draw(ctx, size: size, body: bodyRect, gutter: gutter, sweep: sweep)
                    }
                }

                ForEach(0..<xrayNodes.count, id: \.self) { i in
                    let node = xrayNodes[i]
                    callout(node, lit[node.region])
                        .frame(width: gutter - 6, height: calloutH,
                               alignment: node.left ? .trailing : .leading)
                        .position(x: node.left ? (gutter - 6) / 2 : W - (gutter - 6) / 2,
                                  y: bodyRect.minY + node.slot * bodyRect.height)
                }
            }
        }
    }

    // MARK: callout

    private func callout(_ node: XRayNode, _ l: XRayLit?) -> some View {
        VStack(alignment: node.left ? .trailing : .leading, spacing: 1) {
            Engraved(text: l?.label ?? node.region.label,
                     color: l != nil && !(l!.dormant) ? Forge.ink1 : Forge.ink2, size: 6.5)
                .lineLimit(1).minimumScaleFactor(0.8)
            if let l = l {
                Text(l.value)
                    .font(ThermoFont.mono(l.value.count > 6 ? 10 : 13, weight: .bold))
                    .foregroundColor(l.dormant ? Forge.ink1 : l.color)
                    .lineLimit(1).minimumScaleFactor(0.7)
                if !l.caption.isEmpty {
                    Text(l.caption.uppercased())
                        .font(ThermoFont.mono(5.5)).tracking(0.4)
                        .foregroundColor(Forge.ink2)
                        .lineLimit(1).minimumScaleFactor(0.7)
                }
            } else {
                Text("NO SENSOR")
                    .font(ThermoFont.mono(7)).tracking(0.7)
                    .foregroundColor(Forge.ink2.opacity(0.75))
            }
        }
    }

    // MARK: drawing

    private func draw(_ ctx: GraphicsContext, size: CGSize, body b: CGRect, gutter: CGFloat, sweep: Double?) {
        let bodyPath = Path(roundedRect: b, cornerRadius: b.width * 0.16)

        // Body glass
        ctx.fill(bodyPath, with: .linearGradient(
            Gradient(colors: [Forge.raised.opacity(0.75), Forge.surface.opacity(0.95)]),
            startPoint: CGPoint(x: b.midX, y: b.minY), endPoint: CGPoint(x: b.midX, y: b.maxY)))

        // Interior, back to front
        region(ctx, XRayGeo.board(b), radius: b.width * 0.05, .board, glow: 0.7)
        region(ctx, XRayGeo.battery(b), radius: b.width * 0.06, .battery, glow: 0.8)
        batteryCells(ctx, XRayGeo.battery(b))
        region(ctx, XRayGeo.camera(b), radius: b.width * 0.06, .camera, glow: 1.0)
        cameraLenses(ctx, XRayGeo.camera(b))
        region(ctx, XRayGeo.modem(b), radius: b.width * 0.03, .modem, glow: 1.0)
        let port = XRayGeo.charger(b)
        region(ctx, port, radius: port.height / 2, .charger, glow: 1.6)

        // SoC die — four quadrants
        let qr = b.width * 0.014
        region(ctx, XRayGeo.dieQuad(b, col: 0, row: 0), radius: qr, .cpu, glow: 0.9)
        region(ctx, XRayGeo.dieQuad(b, col: 1, row: 0), radius: qr, .gpu, glow: 0.9)
        region(ctx, XRayGeo.dieQuad(b, col: 0, row: 1), radius: qr, .npu, glow: 0.9)
        region(ctx, XRayGeo.dieQuad(b, col: 1, row: 1), radius: qr, .mem, glow: 0.9)
        if let l = lit[.cpu], let cores = l.cores {
            coreCells(ctx, XRayGeo.dieQuad(b, col: 0, row: 0), cores: cores, active: l.activeCores, dormant: l.dormant)
        }

        // Skin = the rim. Lit by the system state (always available on iOS).
        if let s = lit[.skin] {
            let k = 0.6 + 0.8 * s.intensity
            ctx.stroke(bodyPath, with: .color(s.color.opacity(min(1, 0.05 * k))), lineWidth: 16)
            ctx.stroke(bodyPath, with: .color(s.color.opacity(min(1, 0.09 * k))), lineWidth: 10)
            ctx.stroke(bodyPath, with: .color(s.color.opacity(min(1, 0.18 * k))), lineWidth: 5)
            ctx.stroke(bodyPath, with: .color(s.color), lineWidth: 1.6)
        } else {
            ctx.stroke(bodyPath, with: .color(Forge.ink2.opacity(0.6)),
                       style: StrokeStyle(lineWidth: 1.2, dash: [6, 4]))
        }

        // Leader lines + anchor dots
        for node in xrayNodes {
            let l = lit[node.region]
            let live = l != nil && !(l!.dormant)
            let c = live ? l!.color : Forge.ink2
            let a = node.anchor(b)
            let y = b.minY + node.slot * b.height
            let startX = node.left ? gutter - 3 : size.width - gutter + 3
            var p = Path(); p.move(to: CGPoint(x: startX, y: y)); p.addLine(to: a)
            ctx.stroke(p, with: .color(c.opacity(live ? 0.55 : 0.3)), lineWidth: 0.8)
            let dot = Path(ellipseIn: CGRect(x: a.x - 2.4, y: a.y - 2.4, width: 4.8, height: 4.8))
            if live { ctx.fill(dot, with: .color(c)) }
            else { ctx.stroke(dot, with: .color(c.opacity(0.6)), lineWidth: 1) }
        }

        // Scan line — clipped to the body
        if let sw = sweep {
            var c = ctx
            c.clip(to: bodyPath)
            let y = b.minY + CGFloat(sw) * b.height
            let band = b.height * 0.14
            c.fill(Path(CGRect(x: b.minX, y: y - band, width: b.width, height: band)),
                   with: .linearGradient(
                    Gradient(colors: [.clear, Forge.phaseMeasured.opacity(0.10), Forge.phaseMeasured.opacity(0.32)]),
                    startPoint: CGPoint(x: b.midX, y: y - band), endPoint: CGPoint(x: b.midX, y: y)))
            var line = Path(); line.move(to: CGPoint(x: b.minX, y: y)); line.addLine(to: CGPoint(x: b.maxX, y: y))
            c.stroke(line, with: .color(Forge.phaseMeasured.opacity(0.7)), lineWidth: 1)
        }
    }

    /// One part of the phone: lit when iOS gives us a signal for it, hatched and dashed when not.
    private func region(_ ctx: GraphicsContext, _ r: CGRect, radius: CGFloat, _ which: XRayRegion, glow: CGFloat) {
        let path = Path(roundedRect: r, cornerRadius: radius)
        let dash = StrokeStyle(lineWidth: 1, dash: [5, 4])

        guard let l = lit[which] else {
            ctx.fill(path, with: .color(Forge.ink2.opacity(0.05)))
            var c = ctx; c.clip(to: path)
            var x = r.minX - r.height
            while x < r.maxX {
                var p = Path(); p.move(to: CGPoint(x: x, y: r.maxY)); p.addLine(to: CGPoint(x: x + r.height, y: r.minY))
                c.stroke(p, with: .color(Forge.ink2.opacity(0.22)), lineWidth: 0.8)
                x += 6
            }
            ctx.stroke(path, with: .color(Forge.ink2.opacity(0.45)), style: dash)
            return
        }

        if l.dormant {
            // A real part we know about, with nothing live to show: outline only, no hatch.
            ctx.fill(path, with: .color(Forge.ink2.opacity(0.04)))
            ctx.stroke(path, with: .color(l.color.opacity(0.5)), style: dash)
            return
        }
        if let level = l.fillLevel {
            ctx.fill(path, with: .color(l.color.opacity(0.08)))
            var c = ctx; c.clip(to: path)
            let h = r.height * CGFloat(min(1, max(0, level)))
            c.fill(Path(CGRect(x: r.minX, y: r.maxY - h, width: r.width, height: h)),
                   with: .linearGradient(
                    Gradient(colors: [l.color.opacity(0.18), l.color.opacity(0.42)]),
                    startPoint: CGPoint(x: r.midX, y: r.minY), endPoint: CGPoint(x: r.midX, y: r.maxY)))
            ctx.stroke(path, with: .color(l.color.opacity(0.85)), lineWidth: 1.1)
            return
        }
        ctx.fill(path, with: .color(l.color.opacity(0.14 + 0.16 * l.intensity)))
        var c = ctx; c.clip(to: path)
        let center = CGPoint(x: r.midX, y: r.midY)
        let rad = max(r.width, r.height) * glow
        c.fill(Path(ellipseIn: CGRect(x: center.x - rad, y: center.y - rad, width: rad * 2, height: rad * 2)),
               with: .radialGradient(
                Gradient(colors: [l.color.opacity(0.45 + 0.4 * l.intensity), .clear]),
                center: center, startRadius: 0, endRadius: rad))
        ctx.stroke(path, with: .color(l.color.opacity(0.85)), lineWidth: 1.1)
    }

    /// One cell per core inside the CPU quadrant, coloured by that worker's throughput ratio.
    private func coreCells(_ ctx: GraphicsContext, _ q: CGRect, cores: [Double], active: Int, dormant: Bool) {
        let n = cores.count
        guard n > 0 else { return }
        let cols = n <= 4 ? 2 : 3
        let rows = (n + cols - 1) / cols
        let inset: CGFloat = 3, gap: CGFloat = 1.5
        let cw = (q.width - inset * 2 - gap * CGFloat(cols - 1)) / CGFloat(cols)
        let ch = (q.height - inset * 2 - gap * CGFloat(rows - 1)) / CGFloat(rows)
        for i in 0..<n {
            let col = i % cols, row = i / cols
            let cell = CGRect(x: q.minX + inset + CGFloat(col) * (cw + gap),
                              y: q.minY + inset + CGFloat(row) * (ch + gap), width: cw, height: ch)
            let path = Path(roundedRect: cell, cornerRadius: 1)
            if dormant || i >= active {
                ctx.stroke(path, with: .color(Forge.ink2.opacity(0.5)), lineWidth: 0.6)
            } else {
                let ratio = min(1, max(0, cores[i]))
                ctx.fill(path, with: .color(Ramp.at(1 - ratio).opacity(0.9)))
            }
        }
    }

    private func batteryCells(_ ctx: GraphicsContext, _ r: CGRect) {
        for f in [CGFloat(1) / 3, CGFloat(2) / 3] {
            var p = Path()
            p.move(to: CGPoint(x: r.minX + r.width * f, y: r.minY + 6))
            p.addLine(to: CGPoint(x: r.minX + r.width * f, y: r.maxY - 6))
            ctx.stroke(p, with: .color(Forge.ink2.opacity(0.35)), lineWidth: 0.8)
        }
    }

    private func cameraLenses(_ ctx: GraphicsContext, _ r: CGRect) {
        let rad = r.height * 0.27
        for f in [CGFloat(0.24), 0.5, 0.76] {
            let c = CGPoint(x: r.minX + r.width * f, y: r.midY)
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - rad, y: c.y - rad, width: rad * 2, height: rad * 2)),
                       with: .color(Forge.ink1.opacity(0.35)), lineWidth: 1)
        }
    }
}
