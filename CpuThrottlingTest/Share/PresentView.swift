import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// PRESENT — reviewer mode: one run full screen, for filming and screenshots.
// Nothing on screen but the device, a line of what ran, the numbers at
// presentation size and a small THERMOHAMMER mark. The ✕ fades after 3 s and
// comes back on a tap; Esc closes. The screen stays awake while it's open.
// Lays itself out for the window it gets: portrait, landscape, a Mac window
// being resized (the iPhone app itself stays portrait-only).
// ─────────────────────────────────────────────────────────────────────────────

struct PresentView: View {
    let card: ShareCard
    let onClose: () -> Void
    @State private var closeShown = true
    @State private var hideTask: Task<Void, Never>?
    @State private var idleWasDisabled = false

    var body: some View {
        GeometryReader { g in
            let wide = g.size.width > g.size.height * 1.15
            let unit = min(g.size.width, g.size.height)
            ZStack(alignment: .topTrailing) {
                Group {
                    switch card {
                    case .benchmark(let b): benchmark(b, size: g.size, wide: wide, unit: unit)
                    case .stress(let s): stress(s, size: g.size, wide: wide, unit: unit)
                    }
                }
                .padding(.horizontal, max(20, unit * 0.06)).padding(.vertical, max(20, unit * 0.05))
                .frame(width: g.size.width, height: g.size.height, alignment: .topLeading)

                Text("THERMOHAMMER").font(ThermoFont.mono(max(9, unit * 0.018), weight: .black)).tracking(max(2, unit * 0.005))
                    .foregroundColor(Forge.phaseMeasured.opacity(0.8))
                    .padding(max(14, unit * 0.03))
                    .frame(width: g.size.width, height: g.size.height, alignment: .bottomTrailing)
                    .allowsHitTesting(false)

                Button(action: onClose) {
                    Text("✕").font(ThermoFont.mono(16, weight: .bold)).foregroundColor(Forge.ink1)
                        .frame(width: 40, height: 40).background(Forge.ink0.opacity(0.08)).clipShape(Circle())
                }
                .padding(14)
                .opacity(closeShown ? 1 : 0)
                .allowsHitTesting(closeShown)
            }
        }
        .background(Forge.bg.ignoresSafeArea())
        .contentShape(Rectangle())
        .onTapGesture { showClose() }
        .background(Button("", action: onClose).keyboardShortcut(.cancelAction).opacity(0))   // Esc, even while the ✕ is hidden
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear {
            idleWasDisabled = UIApplication.shared.isIdleTimerDisabled
            UIApplication.shared.isIdleTimerDisabled = true
            showClose()
        }
        .onDisappear {
            hideTask?.cancel()
            UIApplication.shared.isIdleTimerDisabled = idleWasDisabled
        }
    }

    private func showClose() {
        withAnimation(.easeOut(duration: 0.2)) { closeShown = true }
        hideTask?.cancel()
        hideTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.6)) { closeShown = false }
        }
    }

    private func title(_ device: String, _ line: String, unit: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: unit * 0.012) {
            Text(device).font(ThermoFont.display(unit * 0.075, weight: .bold)).foregroundColor(Forge.ink0)
                .lineLimit(2).minimumScaleFactor(0.6)
            Text(line).font(ThermoFont.mono(max(10, unit * 0.026))).foregroundColor(Forge.ink2)
                .lineLimit(2).minimumScaleFactor(0.7)
        }
    }

    // MARK: stress — the capacity and the curve, filling most of the screen

    @ViewBuilder private func stress(_ s: StressCard, size: CGSize, wide: Bool, unit: CGFloat) -> some View {
        let big = Text("\(Int(s.capacity.rounded()))%").font(ThermoFont.mono(unit * (wide ? 0.2 : 0.22), weight: .black))
            .foregroundColor(Ramp.forCapacity(s.capacity)).lineLimit(1).minimumScaleFactor(0.5)
        let label = Text(s.loadLabel).font(ThermoFont.mono(max(10, unit * 0.026), weight: .bold)).tracking(unit * 0.004)
            .foregroundColor(Forge.ink2).lineLimit(1).minimumScaleFactor(0.6)
        let onset = Text(StressCurve.onsetText(s.onsetSec)).font(ThermoFont.mono(max(11, unit * 0.032), weight: .bold))
            .foregroundColor(s.onsetSec == nil ? Forge.phaseMeasured : Ramp.at(0.68))
        let chart = StressCurveChart(curve: s.curve, durationS: s.durationS, onsetFraction: s.onsetFraction,
                                     labelSize: max(9, unit * 0.022), lineWidth: max(2, unit * 0.006))
            .padding(unit * 0.03)
            .background(Forge.surface.opacity(0.7)).cornerRadius(unit * 0.03)
        if wide {
            HStack(alignment: .center, spacing: unit * 0.05) {
                VStack(alignment: .leading, spacing: unit * 0.02) {
                    title(s.device, s.settings, unit: unit)
                    Spacer(minLength: 0)
                    big
                    label
                    onset.padding(.top, unit * 0.01)
                    Spacer(minLength: unit * 0.04)
                }
                .frame(width: size.width * 0.32, alignment: .leading)
                chart
            }
        } else {
            VStack(alignment: .leading, spacing: unit * 0.02) {
                title(s.device, s.settings, unit: unit)
                big.padding(.top, unit * 0.02)
                label
                chart.frame(maxHeight: .infinity)
                onset
                Spacer(minLength: unit * 0.04)
            }
        }
    }

    // MARK: benchmark — the total and the six section bars, large

    @ViewBuilder private func benchmark(_ b: BenchmarkCard, size: CGSize, wide: Bool, unit: CGFloat) -> some View {
        let total = VStack(alignment: .leading, spacing: unit * 0.005) {
            Text(b.total.formatted()).font(ThermoFont.mono(unit * (wide ? 0.19 : 0.2), weight: .black)).foregroundColor(Forge.ink0)
                .lineLimit(1).minimumScaleFactor(0.4)
            Text("BENCHMARK SCORE").font(ThermoFont.mono(max(10, unit * 0.026), weight: .bold)).tracking(unit * 0.005)
                .foregroundColor(Forge.ink2)
        }
        let bars = SectionBars(sections: b.sections, nameSize: max(12, unit * 0.04), valueSize: max(13, unit * 0.045),
                               barHeight: max(8, unit * 0.022), spacing: unit * 0.035, nameWidth: unit * 0.12, valueWidth: unit * 0.22)
        if wide {
            HStack(alignment: .center, spacing: unit * 0.08) {
                VStack(alignment: .leading, spacing: unit * 0.04) {
                    title(b.device, b.settings, unit: unit)
                    Spacer(minLength: 0)
                    total
                    Spacer(minLength: unit * 0.05)
                }
                .frame(width: size.width * 0.4, alignment: .leading)
                bars.frame(maxHeight: .infinity)
            }
        } else {
            VStack(alignment: .leading, spacing: unit * 0.05) {
                title(b.device, b.settings, unit: unit)
                total
                Spacer(minLength: 0)
                bars
                Spacer(minLength: unit * 0.08)
            }
        }
    }
}
