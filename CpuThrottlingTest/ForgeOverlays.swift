import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// FORGE OVERLAYS — preflight checklist · verdict seal · abort · connection
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Shared glass card shell

struct ForgeCard<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        content()
            .padding(20)
            .background(Forge.raised)
            .cornerRadius(16)
            .cornerTicks(color: Forge.ink2.opacity(0.8))
            .shadow(color: .black.opacity(0.6), radius: 24)
    }
}

struct ForgeOverlayScrim<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        ZStack {
            Color.black.opacity(0.72).ignoresSafeArea()
            content()
        }
        .transition(.opacity)
    }
}

// MARK: - Pre-flight overlay — live re-verifying gate checklist

struct PreflightOverlay: View {
    let engine: StressEngine
    let onProceed: () -> Void
    let onCancel: () -> Void

    @State private var report: PreflightReport? = nil
    @State private var initialChecking = true
    @State private var pollTask: Task<Void, Never>? = nil

    var body: some View {
        ForgeOverlayScrim {
            ForgeCard {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Engraved(text: "LAUNCH CHECK", color: Forge.phasePreFlight, size: 10)
                        Spacer()
                        if initialChecking {
                            Engraved(text: "ARMING…", color: Forge.phasePreFlight, size: 8)
                        }
                    }

                    if initialChecking || report == nil {
                        ForEach(0..<5, id: \.self) { _ in
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Forge.ink2.opacity(0.15))
                                .frame(height: 26)
                        }
                    } else if let r = report {
                        ForEach(r.gates, id: \.id) { g in
                            HStack(spacing: 10) {
                                StatusLED(color: g.passed ? Ramp.at(0.05)
                                                          : (g.blocking ? Ramp.at(0.85) : Ramp.at(0.5)))
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(g.title)
                                        .font(ThermoFont.mono(10, weight: .bold))
                                        .foregroundColor(g.passed ? Forge.ink0 : Ramp.at(0.5))
                                    Text(g.detail)
                                        .font(ThermoFont.mono(8))
                                        .foregroundColor(Forge.ink2)
                                }
                                Spacer()
                                Text(g.passed ? "PASS" : (g.blocking ? "BLOCK" : "WARN"))
                                    .font(ThermoFont.mono(8, weight: .bold))
                                    .foregroundColor(g.passed ? Ramp.at(0.05)
                                                              : (g.blocking ? Ramp.at(0.85) : Ramp.at(0.5)))
                            }
                        }
                        Engraved(text: "◇ live — gates re-verify continuously while open", size: 7)
                            .padding(.top, 2)
                    }

                    HStack(spacing: 10) {
                        Button(action: onCancel) {
                            Text("ABORT")
                                .font(ThermoFont.mono(11, weight: .bold))
                                .foregroundColor(Forge.ink1)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 11)
                                .background(Forge.interact)
                                .cornerRadius(10)
                        }
                        Button(action: onProceed) {
                            Text("PROCEED")
                                .font(ThermoFont.mono(11, weight: .black))
                                .foregroundColor(Forge.bg)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 11)
                                .background((report?.canRun ?? false) ? Forge.phaseMeasured : Forge.ink2.opacity(0.4))
                                .cornerRadius(10)
                        }
                        .disabled(!(report?.canRun ?? false))
                    }
                }
            }
            .frame(width: 320)
        }
        .onAppear {
            pollTask = Task {
                while !Task.isCancelled {
                    let r = await StressEngine.shared.runPreflight()
                    self.report = r
                    self.initialChecking = false
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                }
            }
        }
        .onDisappear { pollTask?.cancel() }
    }
}

// MARK: - Hold-to-abort button

struct HoldToAbort: View {
    let onAbort: () -> Void
    @State private var holding = false
    @State private var progress: Double = 0

    var body: some View {
        ZStack {
            Circle().trim(from: 0, to: progress)
                .stroke(Ramp.at(0.68), style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .frame(width: 44, height: 44)
            Circle()
                .fill(holding ? Ramp.at(0.68).opacity(0.25) : Forge.interact)
                .frame(width: 36, height: 36)
            Image(systemName: "stop.fill")
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(holding ? Ramp.at(0.68) : Forge.ink1)
        }
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !holding {
                        holding = true
                        ThermoHaptics.tap()
                        withAnimation(.linear(duration: 0.6)) { progress = 1 }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                            if holding { onAbort(); holding = false; progress = 0 }
                        }
                    }
                }
                .onEnded { _ in
                    holding = false
                    withAnimation(.easeOut(duration: 0.15)) { progress = 0 }
                }
        )
    }
}

// MARK: - Verdict overlay — scorecard seal

struct VerdictOverlay: View {
    let scorecard: Scorecard
    let stamps: [StampV2]
    let submitting: Bool
    let submitMsg: String?
    let submitError: String?
    let canSubmit: Bool
    let onSubmit: () -> Void
    let onDismiss: () -> Void

    private var capacity: Double { scorecard.deliveredCapacity }
    private var sealColor: Color { scorecard.isVerified ? Ramp.at(0.05) : Ramp.at(0.5) }

    var body: some View {
        ForgeOverlayScrim {
            ForgeCard {
                VStack(spacing: 14) {
                    // seal
                    ZStack {
                        Circle()
                            .strokeBorder(sealColor.opacity(0.5), lineWidth: 1)
                            .frame(width: 84, height: 84)
                        Circle()
                            .strokeBorder(sealColor, lineWidth: 2)
                            .frame(width: 72, height: 72)
                            .shadow(color: sealColor.opacity(0.6), radius: 10)
                        VStack(spacing: 0) {
                            Image(systemName: scorecard.isVerified ? "checkmark.seal.fill" : "flag.fill")
                                .font(.system(size: 18))
                                .foregroundColor(sealColor)
                            Text(scorecard.isVerified ? "VERIFIED" : "FLAGGED")
                                .font(ThermoFont.mono(7, weight: .black))
                                .foregroundColor(sealColor)
                        }
                    }

                    TickerText(value: capacity, format: { String(format: "%.1f%%", $0) },
                               color: Ramp.forCapacity(capacity), size: 44,
                               font: ThermoFont.display(44, weight: .bold))
                    Engraved(text: "DELIVERED CAPACITY", size: 8)

                    VStack(spacing: 5) {
                        verdictRow("BASELINE", "\(Int(scorecard.baselineIps / 1000))K it/s")
                        verdictRow("SUSTAINED", String(format: "%.1f%%", scorecard.sustainedRatio))
                        verdictRow("MIN SUSTAINED", scorecard.minSustained.map { String(format: "%.1f%%", $0) } ?? "—")
                        verdictRow("ONSET", scorecard.throttleOnsetSec.map { String(format: "%d:%02d", $0 / 60, $0 % 60) } ?? "none")
                        verdictRow("IN THROTTLE", String(format: "%.0f%%", scorecard.timeInThrottlePct))
                        verdictRow("CONFIDENCE", "\(scorecard.confidence)/100")
                    }

                    let flags = validityFlagNames(scorecard.validityFlags)
                    if !flags.isEmpty {
                        VStack(spacing: 3) {
                            ForEach(flags, id: \.self) { f in
                                Text(f)
                                    .font(ThermoFont.mono(8, weight: .bold))
                                    .foregroundColor(Ramp.at(0.5))
                                    .padding(.horizontal, 8).padding(.vertical, 2)
                                    .overlay(RoundedRectangle(cornerRadius: 4).stroke(Ramp.at(0.5).opacity(0.5), lineWidth: 1))
                            }
                        }
                    }

                    if let msg = submitMsg {
                        Text(msg).font(ThermoFont.mono(9, weight: .bold)).foregroundColor(Ramp.at(0.05))
                    } else if let err = submitError {
                        Text(err).font(ThermoFont.mono(8)).foregroundColor(Ramp.at(0.85))
                    } else if submitting {
                        HStack(spacing: 8) {
                            ProgressView().tint(Forge.phaseMeasured).scaleEffect(0.8)
                            Text("SUBMITTING…").font(ThermoFont.mono(9, weight: .bold)).foregroundColor(Forge.ink1)
                        }
                    } else if canSubmit {
                        Button(action: onSubmit) {
                            Text("SUBMIT TO LEADERBOARD")
                                .font(ThermoFont.mono(10, weight: .black))
                                .foregroundColor(Forge.bg)
                                .frame(maxWidth: .infinity).padding(.vertical, 11)
                                .background(Forge.phaseMeasured)
                                .cornerRadius(10)
                        }
                    }

                    Button(action: onDismiss) {
                        Text("DISMISS")
                            .font(ThermoFont.mono(10, weight: .bold))
                            .foregroundColor(Forge.ink1)
                            .frame(maxWidth: .infinity).padding(.vertical, 10)
                            .background(Forge.interact)
                            .cornerRadius(10)
                    }
                }
            }
            .frame(width: 320)
        }
    }

    private func verdictRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(ThermoFont.mono(8, weight: .bold)).foregroundColor(Forge.ink2)
            Spacer()
            Text(value).font(ThermoFont.mono(10, weight: .bold)).foregroundColor(Forge.ink0)
        }
    }
}

// MARK: - Abort / offline / banner overlays

struct AbortOverlay: View {
    let elapsed: TimeInterval
    let onDismiss: () -> Void
    var body: some View {
        ForgeOverlayScrim {
            ForgeCard {
                VStack(spacing: 12) {
                    Engraved(text: "RUN ABORTED", color: Ramp.at(0.5), size: 11)
                    Text(String(format: "%d:%02d recorded", Int(elapsed) / 60, Int(elapsed) % 60))
                        .font(ThermoFont.display(22, weight: .bold))
                        .foregroundColor(Forge.ink0)
                    Engraved(text: "partial data is kept but flagged SHORT RUN", size: 8)
                    Button(action: onDismiss) {
                        Text("DISMISS").font(ThermoFont.mono(10, weight: .bold))
                            .foregroundColor(Forge.ink1)
                            .frame(maxWidth: .infinity).padding(.vertical, 10)
                            .background(Forge.interact).cornerRadius(10)
                    }
                }
            }
            .frame(width: 280)
        }
    }
}

struct ConnectionOverlay: View {
    let onRetry: () -> Void
    let onDismiss: () -> Void
    var body: some View {
        ForgeOverlayScrim {
            ForgeCard {
                VStack(spacing: 12) {
                    Engraved(text: "OFFLINE", color: Ramp.at(0.5), size: 11)
                    Engraved(text: "result saved locally — submit from leaderboard when online",
                             color: Forge.ink1, size: 8)
                        .multilineTextAlignment(.center)
                    HStack(spacing: 10) {
                        Button(action: onDismiss) {
                            Text("LATER").font(ThermoFont.mono(10, weight: .bold))
                                .foregroundColor(Forge.ink1)
                                .frame(maxWidth: .infinity).padding(.vertical, 10)
                                .background(Forge.interact).cornerRadius(10)
                        }
                        Button(action: onRetry) {
                            Text("RETRY").font(ThermoFont.mono(10, weight: .black))
                                .foregroundColor(Forge.bg)
                                .frame(maxWidth: .infinity).padding(.vertical, 10)
                                .background(Forge.phaseMeasured).cornerRadius(10)
                        }
                    }
                }
            }
            .frame(width: 300)
        }
    }
}
