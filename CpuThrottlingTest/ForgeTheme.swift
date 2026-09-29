import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// THERMOHAMMER FORGE — design tokens (iOS port of the Android Forge system)
// "The forge is dark. The metal glows. The instrument tells the truth beautifully."
// ─────────────────────────────────────────────────────────────────────────────

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: alpha)
    }
}

enum Forge {
    static let bg       = Color(hex: 0x06090B)  // deepest layer — inside of a cold forge
    static let surface  = Color(hex: 0x0B1117)  // card surface
    static let raised   = Color(hex: 0x111A21)  // raised panels / overlay cards
    static let interact = Color(hex: 0x1A2630)  // interactive elements
    static let hairline = Color.white.opacity(0.08)
    static let ink0     = Color(hex: 0xEAF4F4)  // primary data text
    static let ink1     = Color(hex: 0x9FB4BC)  // secondary text
    static let ink2     = Color(hex: 0x5A6B73)  // engraved labels / annotations

    static let phasePreFlight   = Color(hex: 0x4A9EFF)  // signal blue
    static let phaseCooldown    = Color(hex: 0x5BC8FF)  // ice blue — shedding heat
    static let phaseWarmup      = Color(hex: 0x5A6B73)  // dormant steel
    static let phaseCalibration = Color(hex: 0x9D7BFF)  // arc violet
    static let phaseMeasured    = Color(hex: 0x3DE8C2)  // forge teal
}

/// The Thermal Ramp — continuous position 0..1. t=0 cool teal → t=1 molten.
enum Ramp {
    private static let stops: [(Double, Color)] = [
        (0.00, Color(hex: 0x3DE8C2)),
        (0.25, Color(hex: 0x9EE86A)),
        (0.50, Color(hex: 0xFFB545)),
        (0.68, Color(hex: 0xFF6A3D)),
        (0.85, Color(hex: 0xFF3D5E)),
        (1.00, Color(hex: 0xFFE9D6)),
    ]

    static func at(_ t: Double) -> Color {
        let c = min(1, max(0, t))
        for i in 0..<(stops.count - 1) {
            let (p0, c0) = stops[i]; let (p1, c1) = stops[i + 1]
            if c <= p1 {
                let f = p1 > p0 ? (c - p0) / (p1 - p0) : 0
                return lerpColor(c0, c1, f)
            }
        }
        return stops.last!.1
    }

    /// Delivered-capacity % (100 perfect) → ramp position.
    static func forCapacity(_ pct: Double) -> Color { at(min(1, max(0, (100 - pct) / 55))) }

    static func glow(_ t: Double, alpha: Double = 0.35) -> Color { at(t).opacity(alpha) }

    private static func lerpColor(_ a: Color, _ b: Color, _ f: Double) -> Color {
        let ac = UIColor(a).cgColor.components ?? [0, 0, 0, 1]
        let bc = UIColor(b).cgColor.components ?? [0, 0, 0, 1]
        return Color(.sRGB,
                     red: ac[0] + (bc[0] - ac[0]) * f,
                     green: ac[1] + (bc[1] - ac[1]) * f,
                     blue: ac[2] + (bc[2] - ac[2]) * f,
                     opacity: ac[3] + (bc[3] - ac[3]) * f)
    }
}

func phaseColor(_ phase: RunPhase) -> Color {
    switch phase {
    case .idle:        return Forge.ink2
    case .cooldown:    return Forge.phaseCooldown
    case .warmup:      return Forge.phaseWarmup
    case .calibration: return Forge.phaseCalibration
    case .measured:    return Forge.phaseMeasured
    }
}

func thermalStateColor(_ state: ProcessInfo.ThermalState) -> Color {
    switch state {
    case .nominal:  return Ramp.at(0.05)
    case .fair:     return Ramp.at(0.30)
    case .serious:  return Ramp.at(0.62)
    case .critical: return Ramp.at(0.85)
    @unknown default: return Forge.ink2
    }
}

// MARK: - Attribution colors & labels (iOS evidence-limited set)

func attributionColor(_ a: Attribution) -> Color {
    switch a {
    case .none:       return Ramp.at(0.05)
    case .thermal:    return Ramp.at(0.62)
    case .contention: return Forge.phasePreFlight
    case .unknown:    return Forge.ink2
    }
}

func attributionLabel(_ a: Attribution) -> String {
    switch a {
    case .none:       return "CLEAN RUN"
    case .thermal:    return "THERMAL DVFS"
    case .contention: return "EXTERNAL LOAD"
    case .unknown:    return "TELEMETRY LIMITED"
    }
}

func validityFlagNames(_ flags: Int) -> [String] {
    var out: [String] = []
    if flags & ValidityFlag.warmStarted      != 0 { out.append("WARM START") }
    if flags & ValidityFlag.interference     != 0 { out.append("INTERFERENCE") }
    if flags & ValidityFlag.powerEvent       != 0 { out.append("POWER EVENT") }
    if flags & ValidityFlag.freqMissing      != 0 { out.append("NO CLOCK DATA") }
    if flags & ValidityFlag.shortRun         != 0 { out.append("SHORT RUN") }
    if flags & ValidityFlag.envCapped        != 0 { out.append("LOW POWER MODE") }
    if flags & ValidityFlag.suspiciousBoost  != 0 { out.append("BOOST ANOMALY") }
    if flags & ValidityFlag.cooldownTimeout  != 0 { out.append("COOLDOWN TIMEOUT") }
    if flags & ValidityFlag.baselineExceeded != 0 { out.append("BASELINE EXCEEDED") }
    return out
}

// MARK: - Typography — bundled JetBrains Mono + Space Grotesk

enum ThermoFont {
    static let monoRegular  = "JetBrainsMono-Regular"
    static let monoMedium   = "JetBrainsMono-Medium"
    static let monoBold     = "JetBrainsMono-Bold"
    static let monoBlack    = "JetBrainsMono-ExtraBold"
    static let displayMed   = "SpaceGrotesk-Medium"
    static let displayBold  = "SpaceGrotesk-Bold"

    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        let name: String
        switch weight {
        case .bold:           name = monoBold
        case .black, .heavy:  name = monoBlack
        case .medium:         name = monoMedium
        default:              name = monoRegular
        }
        return .custom(name, size: size)
    }
    static func display(_ size: CGFloat, weight: Font.Weight = .medium) -> Font {
        .custom(weight == .bold ? displayBold : displayMed, size: size)
    }
}

// MARK: - Modifiers & drawing helpers

/// Corner ticks — instrument-bezel L marks instead of a full border.
struct CornerTicks: ViewModifier {
    var color: Color = Forge.ink2.opacity(0.6)
    var tickLen: CGFloat = 10
    var stroke: CGFloat = 1
    var inset: CGFloat = 0
    func body(content: Content) -> some View {
        content.overlay(
            Canvas { ctx, size in
                let l = tickLen, s = stroke, i = inset
                let w = size.width - i, h = size.height - i
                func line(_ a: CGPoint, _ b: CGPoint) {
                    var p = Path(); p.move(to: a); p.addLine(to: b)
                    ctx.stroke(p, with: .color(color), lineWidth: s)
                }
                line(CGPoint(x: i, y: i), CGPoint(x: i + l, y: i))
                line(CGPoint(x: i, y: i), CGPoint(x: i, y: i + l))
                line(CGPoint(x: w, y: i), CGPoint(x: w - l, y: i))
                line(CGPoint(x: w, y: i), CGPoint(x: w, y: i + l))
                line(CGPoint(x: i, y: h), CGPoint(x: i + l, y: h))
                line(CGPoint(x: i, y: h), CGPoint(x: i, y: h - l))
                line(CGPoint(x: w, y: h), CGPoint(x: w - l, y: h))
                line(CGPoint(x: w, y: h), CGPoint(x: w, y: h - l))
            }
        )
    }
}

extension View {
    func cornerTicks(color: Color = Forge.ink2.opacity(0.6), tickLen: CGFloat = 10, stroke: CGFloat = 1, inset: CGFloat = 0) -> some View {
        modifier(CornerTicks(color: color, tickLen: tickLen, stroke: stroke, inset: inset))
    }
}

/// Filmic grain — pre-rendered monochrome noise, tiled once.
struct ForgeGrain: ViewModifier {
    private static let noiseImage: UIImage = {
        let size = 128
        var rng = SystemRandomNumberGenerator()
        var px = [UInt8](repeating: 0, count: size * size * 4)
        for i in 0..<(size * size) {
            let v = UInt8.random(in: 0...255, using: &rng)
            px[i * 4] = v; px[i * 4 + 1] = v; px[i * 4 + 2] = v; px[i * 4 + 3] = 28
        }
        let data = Data(px)
        let provider = CGDataProvider(data: data as CFData)!
        let img = CGImage(width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 32,
                          bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(),
                          bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                          provider: provider, decode: nil, shouldInterpolate: false,
                          intent: .defaultIntent)!
        return UIImage(cgImage: img).resizableImage(withCapInsets: .zero, resizingMode: .tile)
    }()

    func body(content: Content) -> some View {
        content.overlay(
            Image(uiImage: ForgeGrain.noiseImage)
                .resizable(resizingMode: .tile)
                .allowsHitTesting(false)
                .opacity(0.22)
        )
    }
}

extension View {
    func forgeGrain() -> some View { modifier(ForgeGrain()) }
}

// MARK: - Micro components

/// Engraved instrument label — uppercase spaced mono.
struct Engraved: View {
    let text: String
    var color: Color = Forge.ink2
    var size: CGFloat = 9
    var body: some View {
        Text(text.uppercased())
            .font(ThermoFont.mono(size, weight: .bold))
            .tracking(size * 0.12)
            .foregroundColor(color)
    }
}

/// Status LED — hollow (pending) vs solid (result).
struct StatusLED: View {
    let color: Color
    var active: Bool = true
    var size: CGFloat = 8
    var body: some View {
        Circle()
            .strokeBorder(color.opacity(0.5), lineWidth: 1.5)
            .background(Circle().fill(active ? color : .clear))
            .frame(width: size, height: size)
    }
}

/// Lerped number ticker — value animates to target instead of snapping.
struct TickerText: View, Animatable {
    var value: Double
    var format: (Double) -> String
    var color: Color = Forge.ink0
    var size: CGFloat = 22
    var font: Font? = nil
    var animatableData: Double {
        get { value }
        set { value = newValue }
    }
    var body: some View {
        Text(format(value))
            .font(font ?? ThermoFont.mono(size, weight: .bold))
            .foregroundColor(color)
    }
}

// MARK: - Haptics

enum ThermoHaptics {
    static func onset() {
        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
    }
    static func confirmed() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
    static func rejected() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }
    static func tap() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }
}
