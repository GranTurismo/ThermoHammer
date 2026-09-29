import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// THERMOHAMMER FORGE — main diagnostics screen (iOS)
// ─────────────────────────────────────────────────────────────────────────────

struct ContentView: View {
    @StateObject private var engine = StressEngine.shared
    @StateObject private var networkMonitor = NetworkMonitor.shared
    @StateObject private var pendingStore = PendingResultStore.shared

    @State private var selectedDuration: TestDuration = .minutes5
    @State private var selectedThreadingType: StressThreadingType = .multi
    @State private var selectedTab = 0

    // overlays
    @State private var showPreflight = false
    @State private var showVerdict = false
    @State private var showAbort = false
    @State private var showConnectionRequest = false
    @State private var retainedScorecard: Scorecard? = nil   // kept mounted through exit animation
    @State private var retainedStamps: [StampV2] = []

    // submission
    @State private var isSubmittingScore = false
    @State private var submitSuccessMessage: String? = nil
    @State private var submitErrorMessage: String? = nil
    @State private var currentPendingResultId: UUID? = nil

    private var capacity: Double {
        guard engine.state.baselineIps > 0 else { return 100 }
        return min(110, engine.state.liveIps / engine.state.baselineIps * 100)
    }

    private var phaseProgress: Double {
        switch engine.state.phase {
        case .cooldown:    return min(1, engine.state.cooldownElapsed / 120)
        case .warmup:      return min(1, Double(engine.state.preRunIps.count) / (12.0 * 4))
        case .calibration: return min(1, max(0, Double(engine.state.preRunIps.count) - 48) / (20.0 * 4))
        case .measured:    return engine.state.duration > 0 ? min(1, engine.state.elapsed / engine.state.duration) : 0
        case .idle:        return 0
        }
    }

    private var phaseProgressLabel: String {
        switch engine.state.phase {
        case .cooldown:    return "\(engine.state.thermalState.name) → NOMINAL · \(Int(engine.state.cooldownElapsed))s"
        case .warmup:      return "settling"
        case .calibration: return "locking baseline"
        case .measured:
            let r = max(0, Int(engine.state.duration - engine.state.elapsed))
            return String(format: "T-%02d:%02d", r / 60, r % 60)
        case .idle:        return ""
        }
    }

    var body: some View {
        ZStack {
            Forge.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                headerSection

                if selectedTab == 0 {
                    diagnosticsTab
                        .transition(.opacity.combined(with: .scale(scale: 0.98)))
                } else {
                    LeaderboardView()
                        .transition(.opacity.combined(with: .scale(scale: 0.98)))
                }
            }
            .animation(.easeInOut(duration: 0.28), value: selectedTab)

            // ── overlays (animated entrances) ──
            if showPreflight {
                PreflightOverlay(
                    engine: engine,
                    onProceed: {
                        withAnimation(.easeOut(duration: 0.2)) { showPreflight = false }
                        engine.startTest(duration: selectedDuration, threadingType: selectedThreadingType)
                    },
                    onCancel: { withAnimation(.easeOut(duration: 0.2)) { showPreflight = false } }
                )
                .transition(.opacity.combined(with: .scale(scale: 0.96)))
                .zIndex(10)
            }

            if retainedScorecard != nil {
                VerdictOverlay(
                    scorecard: retainedScorecard!,
                    stamps: retainedStamps,
                    submitting: isSubmittingScore,
                    submitMsg: submitSuccessMessage,
                    submitError: submitErrorMessage,
                    canSubmit: networkMonitor.isConnected && submitSuccessMessage == nil,
                    onSubmit: submitScore,
                    onDismiss: {
                        withAnimation(.easeOut(duration: 0.2)) { showVerdict = false }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) {
                            retainedScorecard = nil; retainedStamps = []
                        }
                    }
                )
                .transition(.opacity.combined(with: .scale(scale: 0.94)))
                .zIndex(11)
            }

            if showAbort {
                AbortOverlay(elapsed: engine.state.elapsed) {
                    withAnimation(.easeOut(duration: 0.2)) { showAbort = false }
                }
                .transition(.opacity.combined(with: .scale(scale: 0.94)))
                .zIndex(12)
            }

            if showConnectionRequest {
                ConnectionOverlay(
                    onRetry: {
                        withAnimation(.easeOut(duration: 0.2)) { showConnectionRequest = false }
                        submitScore()
                    },
                    onDismiss: { withAnimation(.easeOut(duration: 0.2)) { showConnectionRequest = false } }
                )
                .transition(.opacity.combined(with: .scale(scale: 0.94)))
                .zIndex(13)
            }
        }
        .forgeGrain()
        .preferredColorScheme(.dark)
        .onChange(of: engine.isRunning) { running in
            if !running { handleRunEnd() }
        }
    }

    // MARK: header

    private var headerSection: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text("THERMOHAMMER")
                        .font(ThermoFont.display(22, weight: .bold))
                        .tracking(2)
                        .foregroundColor(Forge.ink0)
                    HStack(spacing: 4) {
                        Circle().fill(networkMonitor.isConnected ? Ramp.at(0.05) : Ramp.at(0.5))
                            .frame(width: 5, height: 5)
                        Text(networkMonitor.isConnected ? "ONLINE" : "OFFLINE")
                            .font(ThermoFont.mono(8, weight: .bold))
                            .foregroundColor(networkMonitor.isConnected ? Ramp.at(0.05) : Ramp.at(0.5))
                    }
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(Color.white.opacity(0.04)).cornerRadius(6)
                    .overlay(RoundedRectangle(cornerRadius: 6)
                        .stroke(Forge.hairline, lineWidth: 1))
                }
                Text("iOS CPU THERMAL DIAGNOSTIC")
                    .font(ThermoFont.mono(9, weight: .bold))
                    .tracking(1)
                    .foregroundColor(engine.isRunning ? Ramp.at(0.5) : Forge.ink2)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(engine.state.thermalState.name)
                    .font(ThermoFont.mono(11, weight: .bold))
                    .foregroundColor(thermalStateColor(engine.state.thermalState))
                Engraved(text: "THERMAL", size: 7)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Forge.surface.opacity(0.9))
        .overlay(Rectangle().fill(Forge.hairline).frame(height: 1), alignment: .bottom)
    }

    // MARK: diagnostics tab

    private var diagnosticsTab: some View {
        VStack(spacing: 0) {
            ScrollView(showsIndicators: false) {
                VStack(spacing: 12) {
                    PhaseRail(phase: engine.state.phase,
                              preflightActive: showPreflight,
                              completed: engine.wasCompleted,
                              progress: phaseProgress,
                              progressLabel: phaseProgressLabel)
                        .padding(.top, 10)

                    ThermalReactor(state: engine.state, topology: engine.topology,
                                   completed: engine.wasCompleted)
                        .padding(.top, 4)

                    // live chips under reactor
                    VStack(spacing: 6) {
                        if engine.state.phase == .measured && engine.state.attribution != .none {
                            attributionChip
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                        if engine.state.phase == .cooldown {
                            Text("❄ COOLING \(engine.state.thermalState.name) → NOMINAL · \(Int(engine.state.cooldownElapsed))s")
                                .font(ThermoFont.mono(9, weight: .bold))
                                .foregroundColor(Forge.phaseCooldown)
                                .padding(.horizontal, 10).padding(.vertical, 5)
                                .background(Forge.phaseCooldown.opacity(0.08))
                                .cornerRadius(8)
                                .overlay(RoundedRectangle(cornerRadius: 8)
                                    .stroke(Forge.phaseCooldown.opacity(0.3), lineWidth: 1))
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                    }
                    .animation(.easeInOut(duration: 0.3), value: engine.state.phase)
                    .animation(.easeInOut(duration: 0.3), value: engine.state.attribution)

                    ThroughputRibbon(stamps: engine.state.stamps,
                                     preRun: engine.state.preRunIps,
                                     baselineIps: engine.state.baselineIps,
                                     duration: engine.state.duration)
                        .padding(.horizontal, 14)
                        .background(Forge.surface.opacity(0.5))
                        .cornerRadius(12)
                        .cornerTicks()
                        .padding(.horizontal, 4)

                    CoreConstellation(topology: engine.topology,
                                      workerRatios: engine.state.workerRatios,
                                      activeWorkers: selectedThreadingType == .single ? 1 : engine.coreCount)
                        .padding(.horizontal, 4)

                    ThermalHorizon(thermalState: engine.state.thermalState,
                                   systemLoad: engine.state.systemLoad,
                                   history: engine.state.stamps.map { $0.thermalState })
                        .padding(.horizontal, 4)

                    Spacer(minLength: 90)
                }
            }

            // bottom control area
            controlArea
        }
    }

    private var attributionChip: some View {
        Text("◈ \(attributionLabel(engine.state.attribution))")
            .font(ThermoFont.mono(9, weight: .bold))
            .foregroundColor(attributionColor(engine.state.attribution))
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(attributionColor(engine.state.attribution).opacity(0.10))
            .cornerRadius(8)
            .overlay(RoundedRectangle(cornerRadius: 8)
                .stroke(attributionColor(engine.state.attribution).opacity(0.35), lineWidth: 1))
    }

    // MARK: controls

    private var controlArea: some View {
        VStack(spacing: 10) {
            if !engine.isRunning {
                // pickers — animated away during run
                HStack(spacing: 10) {
                    ForEach([TestDuration.minutes5, .minutes15, .minutes30], id: \.self) { d in
                        Button {
                            selectedDuration = d
                            ThermoHaptics.tap()
                        } label: {
                            Text(d.displayName.uppercased())
                                .font(ThermoFont.mono(10, weight: .bold))
                                .foregroundColor(selectedDuration == d ? Forge.bg : Forge.ink1)
                                .frame(maxWidth: .infinity).padding(.vertical, 9)
                                .background(selectedDuration == d ? Forge.phaseMeasured : Forge.interact)
                                .cornerRadius(8)
                        }
                    }
                }
                HStack(spacing: 10) {
                    ForEach(StressThreadingType.allCases, id: \.self) { t in
                        Button {
                            selectedThreadingType = t
                            ThermoHaptics.tap()
                        } label: {
                            Text(t.displayName.uppercased())
                                .font(ThermoFont.mono(10, weight: .bold))
                                .foregroundColor(selectedThreadingType == t ? Forge.bg : Forge.ink1)
                                .frame(maxWidth: .infinity).padding(.vertical, 9)
                                .background(selectedThreadingType == t ? Forge.phaseCalibration : Forge.interact)
                                .cornerRadius(8)
                        }
                    }
                }

                Button {
                    showPreflight = true
                    ThermoHaptics.tap()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "bolt.fill")
                        Text("INITIATE STRESS")
                            .font(ThermoFont.mono(13, weight: .black)).tracking(1.5)
                    }
                    .foregroundColor(Forge.bg)
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(LinearGradient(colors: [Forge.phaseMeasured, Ramp.at(0.25)],
                                               startPoint: .leading, endPoint: .trailing))
                    .cornerRadius(12)
                    .shadow(color: Forge.phaseMeasured.opacity(0.35), radius: 12)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Engraved(text: engine.state.phase.rawValue, color: phaseColor(engine.state.phase), size: 9)
                        Text("HOLD ◉ TO ABORT")
                            .font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
                    }
                    Spacer()
                    HoldToAbort {
                        engine.abort()
                    }
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            tabBar
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 4)
        .background(Forge.surface.opacity(0.92))
        .animation(.easeInOut(duration: 0.3), value: engine.isRunning)
    }

    private var tabBar: some View {
        HStack(spacing: 0) {
            ForEach(0..<2, id: \.self) { i in
                let titles = ["DIAGNOSTICS", "LEADERBOARD"]
                Button {
                    selectedTab = i
                    ThermoHaptics.tap()
                } label: {
                    VStack(spacing: 4) {
                        Text(titles[i])
                            .font(ThermoFont.mono(9, weight: .bold)).tracking(1)
                            .foregroundColor(selectedTab == i ? Forge.ink0 : Forge.ink2)
                        Capsule()
                            .fill(selectedTab == i ? Forge.phaseMeasured : .clear)
                            .frame(width: 24, height: 2)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 8)
                }
            }
        }
        .animation(.easeInOut(duration: 0.25), value: selectedTab)
    }

    // MARK: run end → persist + verdict

    private func handleRunEnd() {
        guard engine.state.stamps.count > 0 else {
            if engine.wasCancelledDueToBackground || engine.abortedByUser {
                withAnimation(.easeOut(duration: 0.2)) { showAbort = true }
            }
            return
        }
        let sc = engine.state.scorecard
        let wireStamps = engine.state.stamps.map {
            DeviceHammerStamp(elapsedMs: $0.elapsedMs, score: Int($0.ipsTotal), thermalState: $0.thermalState)
        }
        let minStability = engine.state.stamps.map { Double($0.ipsTotal) / max(1, engine.state.baselineIps) * 100 }.min() ?? 100
        let worst = engine.state.stamps.map { $0.thermalState }.max() ?? 0

        let pending = PendingTestResult(
            id: UUID(),
            timestamp: Date().timeIntervalSince1970,
            durationSeconds: Int(engine.state.elapsed),
            testDurationType: { switch engine.testDuration { case .minutes5: return 0; case .minutes15: return 1; case .minutes30: return 2 } }(),
            testThreadingType: engine.testThreadingType.rawValue,
            minStability: minStability,
            finalStability: capacity,
            worstThermalState: worst,
            stamps: wireStamps,
            deviceModel: LeaderboardService.shared.getDeviceModelName(),
            deviceManufacturer: "Apple",
            osVersion: UIDevice.current.systemVersion,
            sessionId: 0,
            encryptionKey: "",
            baselineScore: Int(sc?.baselineIps ?? 0),
            deliveredCapacity: sc?.deliveredCapacity,
            sustainedRatio: sc?.sustainedRatio,
            throttleOnsetSec: sc?.throttleOnsetSec,
            confidence: sc?.confidence,
            validityFlags: sc?.validityFlags,
            socModel: StressEngine.socModel(),
            clusterTopology: engine.topology.describe()
        )
        PendingResultStore.shared.saveResult(pending)
        currentPendingResultId = pending.id

        retainedScorecard = sc
        retainedStamps = engine.state.stamps
        if sc != nil {
            if sc!.isVerified { ThermoHaptics.confirmed() } else { ThermoHaptics.rejected() }
            withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { showVerdict = true }
        } else if engine.wasCancelledDueToBackground || engine.abortedByUser {
            withAnimation(.easeOut(duration: 0.2)) { showAbort = true }
        }
        if !networkMonitor.isConnected {
            withAnimation(.easeOut(duration: 0.2)) { showConnectionRequest = true }
        }
    }

    private func submitScore() {
        isSubmittingScore = true
        submitSuccessMessage = nil
        submitErrorMessage = nil
        let wireStamps = retainedStamps.map {
            DeviceHammerStamp(elapsedMs: $0.elapsedMs, score: Int($0.ipsTotal), thermalState: $0.thermalState)
        }
        let sc = retainedScorecard
        Task {
            do {
                let session = try await LeaderboardService.shared.createSession()
                let hash = ThermoHasher.computeHash(encryptionKey: session.encryptionKey, stamps: wireStamps)

                let type: Int = { switch engine.testDuration { case .minutes5: return 0; case .minutes15: return 1; case .minutes30: return 2 } }()
                let model = LeaderboardService.shared.getDeviceModelName()
                let osv = UIDevice.current.systemVersion
                let metaCanonical = "v2|\(type)|\(engine.testThreadingType.rawValue)|Apple|\(model)|\(osv)|\(Int(sc?.baselineIps ?? 0))|\(String(format: "%.4f", sc?.deliveredCapacity ?? 0))|\(sc?.validityFlags ?? 0)"
                let hashV2 = ThermoHasher.computeHashV2(encryptionKey: session.encryptionKey,
                                                        metaCanonical: metaCanonical,
                                                        stamps: wireStamps)
                let payload = HammerPayload(
                    stamps: wireStamps, type: type,
                    testThreadingType: engine.testThreadingType.rawValue,
                    deviceManufacturer: "Apple", deviceModel: model,
                    os: 1, osVersion: osv,
                    sessionId: session.id, hash: hash,
                    schemaVersion: 2,
                    baselineScore: Int(sc?.baselineIps ?? 0),
                    deliveredCapacity: sc?.deliveredCapacity,
                    sustainedRatio: sc?.sustainedRatio,
                    throttleOnsetSec: sc?.throttleOnsetSec,
                    confidence: sc?.confidence,
                    validityFlags: sc?.validityFlags,
                    socModel: StressEngine.socModel(),
                    clusterTopology: engine.topology.describe(),
                    governor: nil,
                    hashV2: hashV2
                )
                try await LeaderboardService.shared.submitScore(payload: payload)
                await MainActor.run {
                    if let pid = currentPendingResultId {
                        PendingResultStore.shared.deleteResult(id: pid)
                    }
                    isSubmittingScore = false
                    submitSuccessMessage = "SUBMITTED TO LEADERBOARD"
                }
            } catch {
                await MainActor.run {
                    isSubmittingScore = false
                    submitErrorMessage = error.localizedDescription
                }
            }
        }
    }
}
