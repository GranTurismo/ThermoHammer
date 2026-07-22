import SwiftUI
import CoreTelephony

struct SummaryDetails {
    let duration: TimeInterval
    let minStability: Double
    let finalStability: Double
    let worstThermalState: ProcessInfo.ThermalState
}

struct ContentView: View {
    @StateObject private var engine = StressEngine.shared
    @State private var selectedDuration: TestDuration = .minutes5
    @State private var selectedThreadingType: StressThreadingType = .multi
    
    // Summary popup states
    @State private var showSummary = false
    @State private var summaryDetails: SummaryDetails? = nil
    
    // Alerts/Warnings
    @State private var showStartWarning = false
    @State private var showBackgroundCancelledWarning = false
    @State private var showManualCancelledWarning = false
    @State private var showConnectionRequest = false
    
    // Network & Leaderboards
    @StateObject private var networkMonitor = NetworkMonitor.shared
    @State private var isInitializingServer = false
    @State private var showInitError = false
    @State private var initErrorMessage = ""
    @State private var isSubmittingScore = false
    @State private var submitSuccessMessage: String? = nil
    @State private var submitErrorMessage: String? = nil
    
    @State private var selectedTab = 0
    @State private var currentPendingResultId: UUID? = nil
    @ObservedObject private var pendingStore = PendingResultStore.shared
    
    var body: some View {
        ZStack {
            // Dark futuristic background gradient
            LinearGradient(
                colors: [Color(red: 0.05, green: 0.06, blue: 0.08), Color(red: 0.1, green: 0.12, blue: 0.16)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            
            VStack(spacing: 0) {
                if selectedTab == 0 {
                    ScrollView(.vertical, showsIndicators: false) {
                        VStack(spacing: 20) {
                            
                            // --- App Header ---
                            headerSection
                            
                            // --- Unsubmitted Runs Warning Banner ---
                            if !pendingStore.results.isEmpty && !engine.isRunning {
                                pendingRunsBanner
                            }
                            
                            // --- Top Stats Dashboard ---
                            statsPanelSection
                            
                            // --- Test Duration Options (Only when idle) ---
                            if !engine.isRunning {
                                optionsSection
                            }
                            
                            // --- Start/Stop Pulsing Button ---
                            controlButtonSection
                            
                            // --- Main Stability Graph ---
                            StabilityChart(points: engine.chartPoints, events: engine.thermalEvents)
                                .padding(.horizontal, 4)
                            
                            // --- Core Status Meters ---
                            CoreStatusView(coreImpacts: engine.coreImpacts)
                                .padding(.horizontal, 4)
                            
                            Spacer(minLength: 30)
                        }
                        .padding()
                    }
                } else {
                    LeaderboardView()
                }
                
                // Floating bottom tab bar
                if !engine.isRunning {
                    customTabBar
                }
            }
            
            // --- Summary Overlay ---
            if showSummary, let details = summaryDetails {
                summaryOverlay(details)
            }
            
            // --- Start Warning Overlay ---
            if showStartWarning {
                startWarningOverlay
            }
            
            // --- Background Cancelled Warning Overlay ---
            if showBackgroundCancelledWarning {
                backgroundCancelledOverlay
            }
            
            // --- Manual Cancelled Warning Overlay ---
            if showManualCancelledWarning {
                manualCancelledOverlay
            }
            
            // --- Connection Request Overlay ---
            if showConnectionRequest {
                connectionRequestOverlay
            }
            
            // --- Server Session Init Overlay ---
            if isInitializingServer {
                serverInitOverlay
            }
            
            // --- Server Session Error Overlay ---
            if showInitError {
                serverInitErrorOverlay
            }
        }
        .preferredColorScheme(.dark)
        .onChange(of: engine.isRunning) { newValue in
            // When the test stops, calculate summary
            if !newValue && engine.elapsedTime > 0 {
                // Reset submit outcomes for a clean state
                submitSuccessMessage = nil
                submitErrorMessage = nil
                isSubmittingScore = false
                
                if engine.wasCancelledDueToBackground {
                    withAnimation(.spring()) {
                        showBackgroundCancelledWarning = true
                    }
                    return
                }
                
                if !engine.wasCompleted {
                    withAnimation(.spring()) {
                        showManualCancelledWarning = true
                    }
                    return
                }
                
                let minStability = engine.chartPoints.map { $0.score }.min() ?? engine.overallStability
                let worstState = engine.thermalEvents.map { $0.state }.max(by: { $0.rawValue < $1.rawValue }) ?? .nominal
                
                // Automatically save to local history / pending results immediately!
                let generatedId = UUID()
                let pendingResult = PendingTestResult(
                    id: generatedId,
                    timestamp: Date().timeIntervalSince1970,
                    durationSeconds: Int(engine.elapsedTime),
                    testDurationType: {
                        switch engine.testDuration {
                        case .minutes5: return 0
                        case .minutes15: return 1
                        case .minutes30: return 2
                        }
                    }(),
                    testThreadingType: engine.testThreadingType.rawValue,
                    minStability: minStability,
                    finalStability: engine.overallStability,
                    worstThermalState: {
                        switch worstState {
                        case .nominal: return 0
                        case .fair: return 1
                        case .serious: return 2
                        case .critical: return 3
                        @unknown default: return 0
                        }
                    }(),
                    stamps: engine.recordedStamps,
                    deviceModel: LeaderboardService.shared.getDeviceModelName(),
                    deviceManufacturer: "Apple",
                    osVersion: UIDevice.current.systemVersion,
                    sessionId: engine.sessionId ?? 0,
                    encryptionKey: engine.encryptionKey ?? ""
                )
                PendingResultStore.shared.saveResult(pendingResult)
                currentPendingResultId = generatedId
                submitSuccessMessage = "SAVED TO PENDING RESULTS!"
                
                summaryDetails = SummaryDetails(
                    duration: engine.elapsedTime,
                    minStability: minStability,
                    finalStability: engine.overallStability,
                    worstThermalState: worstState
                )
                if networkMonitor.isConnected {
                    withAnimation(.spring()) {
                        showSummary = true
                    }
                } else {
                    withAnimation(.spring()) {
                        showConnectionRequest = true
                    }
                }
            }
        }
    }
    
    // Header View
    private var headerSection: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text("THERMOHAMMER")
                        .font(.system(size: 24, weight: .black, design: .monospaced))
                        .tracking(2)
                        .foregroundStyle(
                            LinearGradient(
                                colors: [.white, .secondary],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                    
                    // Connection Status Capsule
                    HStack(spacing: 4) {
                        Circle()
                            .fill(networkMonitor.isConnected ? Color.green : Color.yellow)
                            .frame(width: 5, height: 5)
                        Text(networkMonitor.isConnected ? "ONLINE" : "OFFLINE")
                            .font(.system(size: 8, weight: .black, design: .monospaced))
                            .foregroundColor(networkMonitor.isConnected ? .green : .yellow)
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(Color.white.opacity(0.04))
                    .cornerRadius(6)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(networkMonitor.isConnected ? Color.green.opacity(0.15) : Color.yellow.opacity(0.15), lineWidth: 1)
                    )
                }
                
                Text("iOS CPU Stress & Throttling Diagnostic")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(engine.isRunning ? .orange : .secondary)
            }
            Spacer()
            
            // Active Pulsing Radar Icon
            if engine.isRunning {
                Circle()
                    .fill(Color.red)
                    .frame(width: 8, height: 8)
                    .overlay(
                        Circle()
                            .stroke(Color.red, lineWidth: 2)
                            .scaleEffect(engine.isRunning ? 2.5 : 1.0)
                            .opacity(engine.isRunning ? 0.0 : 1.0)
                            .animation(.easeOut(duration: 1.2).repeatForever(autoreverses: false), value: engine.isRunning)
                    )
            }
        }
        .padding(.vertical, 8)
    }
    
    // Stats Panel Section
    private var statsPanelSection: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                // Time Card
                VStack(alignment: .leading, spacing: 6) {
                    Text("TEST DURATION")
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .foregroundColor(.secondary)
                    
                    if engine.isRunning, let limit = engine.testDuration.timeInterval {
                        let remaining = max(0.0, limit - engine.elapsedTime)
                        Text(formatTime(remaining))
                            .font(.system(size: 22, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                    } else {
                        Text(formatTime(engine.elapsedTime))
                            .font(.system(size: 22, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.white.opacity(0.03))
                .cornerRadius(18)
                .overlay(
                    RoundedRectangle(cornerRadius: 18)
                        .stroke(Color.white.opacity(0.06), lineWidth: 1)
                )
                
                // Stability Card
                VStack(alignment: .leading, spacing: 6) {
                    Text("STABILITY SCORE")
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .foregroundColor(.secondary)
                    
                    Text(String(format: "%.0f%%", engine.overallStability))
                        .font(.system(size: 22, weight: .bold, design: .monospaced))
                        .foregroundColor(stabilityColor(for: engine.overallStability))
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.white.opacity(0.03))
                .cornerRadius(18)
                .overlay(
                    RoundedRectangle(cornerRadius: 18)
                        .stroke(Color.white.opacity(0.06), lineWidth: 1)
                )
            }
            
            // Thermal Status Bar
            HStack {
                HStack(spacing: 8) {
                    Image(systemName: thermalIconName(for: engine.currentThermalState))
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(thermalColor(for: engine.currentThermalState))
                    
                    Text("THERMAL STATE: \(thermalStateName(for: engine.currentThermalState))")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                }
                Spacer()
                
                Text(engine.isRunning ? "STRESS ACTIVE" : "STRESS INACTIVE")
                    .font(.system(size: 9, weight: .black, design: .monospaced))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(engine.isRunning ? Color.orange.opacity(0.2) : Color.white.opacity(0.06))
                    .foregroundColor(engine.isRunning ? .orange : .secondary)
                    .cornerRadius(6)
            }
            .padding(14)
            .background(Color.white.opacity(0.03))
            .cornerRadius(18)
            .overlay(
                RoundedRectangle(cornerRadius: 18)
                    .stroke(Color.white.opacity(0.06), lineWidth: 1)
            )
        }
    }
    
    // Options Picker Section
    private var optionsSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 8) {
                Text("TARGET DURATION")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(.secondary)
                    .padding(.horizontal, 4)
                
                HStack(spacing: 8) {
                    ForEach([TestDuration.minutes5, .minutes15, .minutes30], id: \.self) { duration in
                        Button(action: {
                            selectedDuration = duration
                        }) {
                            Text(duration.displayName)
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundColor(selectedDuration == duration ? .black : .white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                .background(
                                    RoundedRectangle(cornerRadius: 12)
                                        .fill(selectedDuration == duration ? Color.white : Color.white.opacity(0.05))
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color.white.opacity(selectedDuration == duration ? 0.0 : 0.08), lineWidth: 1)
                                )
                        }
                    }
                }
            }
            
            VStack(alignment: .leading, spacing: 8) {
                Text("THREADING MODE")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(.secondary)
                    .padding(.horizontal, 4)
                
                HStack(spacing: 8) {
                    ForEach(StressThreadingType.allCases, id: \.self) { type in
                        Button(action: {
                            selectedThreadingType = type
                        }) {
                            Text(type.displayName)
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundColor(selectedThreadingType == type ? .black : .white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                .background(
                                    RoundedRectangle(cornerRadius: 12)
                                        .fill(selectedThreadingType == type ? Color.white : Color.white.opacity(0.05))
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color.white.opacity(selectedThreadingType == type ? 0.0 : 0.08), lineWidth: 1)
                                )
                        }
                    }
                }
            }
        }
        .padding(14)
        .background(Color.white.opacity(0.02))
        .cornerRadius(18)
        .overlay(
            RoundedRectangle(cornerRadius: 18)
                .stroke(Color.white.opacity(0.04), lineWidth: 1)
        )
    }
    
    // Start / Stop stress test button
    private var controlButtonSection: some View {
        Button(action: {
            if engine.isRunning {
                engine.stopTest()
            } else {
                showStartWarning = true
            }
        }) {
            HStack(spacing: 12) {
                Image(systemName: engine.isRunning ? "stop.fill" : "play.fill")
                    .font(.system(size: 16, weight: .black))
                
                Text(engine.isRunning ? "STOP STRESS TEST" : "INITIATE STRESS TEST")
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .tracking(1)
            }
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(
                RoundedRectangle(cornerRadius: 18)
                    .fill(
                        LinearGradient(
                            colors: engine.isRunning 
                                ? [Color(red: 0.85, green: 0.2, blue: 0.2), Color(red: 0.7, green: 0.1, blue: 0.1)]
                                : [Color(red: 0.05, green: 0.5, blue: 0.95), Color(red: 0.0, green: 0.35, blue: 0.8)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .shadow(
                        color: (engine.isRunning ? Color.red : Color.blue).opacity(0.4),
                        radius: 8,
                        x: 0,
                        y: 4
                    )
            )
        }
        .padding(.horizontal, 4)
    }
    
    // Summary Popup Modal
    private func summaryOverlay(_ details: SummaryDetails) -> some View {
        ZStack {
            Color.black.opacity(0.85)
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.spring()) {
                        showSummary = false
                    }
                }
            
            VStack(spacing: 20) {
                // Header
                VStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 40))
                        .foregroundColor(.green)
                    Text("DIAGNOSTIC COMPLETE")
                        .font(.system(size: 16, weight: .black, design: .monospaced))
                        .tracking(1)
                        .foregroundColor(.white)
                }
                .padding(.top, 10)
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                // Stats Grid
                VStack(spacing: 12) {
                    summaryRow(label: "TEST TIME", value: formatTime(details.duration))
                    summaryRow(label: "MIN STABILITY", value: String(format: "%.0f%%", details.minStability), valColor: stabilityColor(for: details.minStability))
                    summaryRow(label: "FINAL STABILITY", value: String(format: "%.0f%%", details.finalStability), valColor: stabilityColor(for: details.finalStability))
                    summaryRow(label: "WORST THERMAL", value: thermalStateName(for: details.worstThermalState), valColor: thermalColor(for: details.worstThermalState))
                }
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                // --- Leaderboard Section ---
                VStack(spacing: 8) {
                    if networkMonitor.isConnected {
                        if let successMsg = submitSuccessMessage {
                            HStack {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.green)
                                Text(successMsg)
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundColor(.green)
                            }
                            .padding(.vertical, 8)
                        } else if let errorMsg = submitErrorMessage {
                            VStack(alignment: .center, spacing: 4) {
                                HStack {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundColor(.red)
                                    Text("SUBMISSION FAILED")
                                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                                        .foregroundColor(.red)
                                }
                                Text(errorMsg)
                                    .font(.system(size: 9))
                                    .foregroundColor(.secondary)
                                    .multilineTextAlignment(.center)
                            }
                            .padding(.vertical, 8)
                        } else if isSubmittingScore {
                            HStack(spacing: 8) {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .blue))
                                Text("SUBMITTING SCORE...")
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundColor(.blue)
                            }
                            .padding(.vertical, 8)
                        } else {
                            Button(action: {
                                isSubmittingScore = true
                                submitSuccessMessage = nil
                                submitErrorMessage = nil
                                
                                Task {
                                    do {
                                        // Request a session on-the-fly on submit click
                                        let session = try await LeaderboardService.shared.createSession()
                                        
                                        let hmacHash = ThermoHasher.computeHash(
                                            encryptionKey: session.encryptionKey,
                                            stamps: engine.recordedStamps
                                        )
                                        
                                        let durationType: Int
                                        switch engine.testDuration {
                                        case .minutes5: durationType = 0
                                        case .minutes15: durationType = 1
                                        case .minutes30: durationType = 2
                                        }
                                        
                                        let payload = HammerPayload(
                                            stamps: engine.recordedStamps,
                                            type: durationType,
                                            testThreadingType: engine.testThreadingType.rawValue,
                                            deviceManufacturer: "Apple",
                                            deviceModel: LeaderboardService.shared.getDeviceModelName(),
                                            os: 1, // iOS
                                            osVersion: UIDevice.current.systemVersion,
                                            sessionId: session.id,
                                            hash: hmacHash
                                        )
                                        
                                        try await LeaderboardService.shared.submitScore(payload: payload)
                                        
                                        await MainActor.run {
                                            if let pendingId = currentPendingResultId {
                                                PendingResultStore.shared.deleteResult(id: pendingId)
                                            }
                                            isSubmittingScore = false
                                            submitSuccessMessage = "SUBMITTED TO LEADERBOARD!"
                                        }
                                    } catch {
                                        await MainActor.run {
                                            isSubmittingScore = false
                                            submitErrorMessage = error.localizedDescription
                                        }
                                    }
                                }
                            }) {
                                HStack {
                                    Image(systemName: "crown.fill")
                                    Text("SUBMIT SCORE TO LEADERBOARD")
                                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                                }
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(
                                    LinearGradient(
                                        colors: [Color.blue, Color.purple],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    )
                                )
                                .cornerRadius(10)
                            }
                        }
                    } else {
                        // Offline run from the start
                        VStack(alignment: .center, spacing: 6) {
                            HStack {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.green)
                                Text("OFFLINE RUN AUTO-SAVED")
                                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                                    .foregroundColor(.green)
                            }
                            Text("Your result has been saved locally. You can view and submit it from the Leaderboard tab once you're online.")
                                .font(.system(size: 8))
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 12)
                        }
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity)
                        .background(Color.white.opacity(0.02))
                        .cornerRadius(10)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(Color.white.opacity(0.04), lineWidth: 1)
                        )
                    }
                }
                .padding(.vertical, 8)
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                // Done Button
                Button(action: {
                    withAnimation(.spring()) {
                        showSummary = false
                    }
                }) {
                    Text("DISMISS REPORT")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundColor(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color.white)
                        .cornerRadius(12)
                }
                .padding(.bottom, 10)
            }
            .padding(24)
            .frame(width: 320)
            .background(
                RoundedRectangle(cornerRadius: 24)
                    .fill(Color(white: 0.1))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.1), lineWidth: 1)
                    )
            )
            .shadow(color: .black, radius: 15)
        }
    }
    
    // Row inside Summary Modal
    private func summaryRow(label: String, value: String, valColor: Color = .white) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.system(size: 13, weight: .black, design: .monospaced))
                .foregroundColor(valColor)
        }
        .padding(.vertical, 4)
    }
    
    // Helper color code based on stability score
    private func stabilityColor(for score: Double) -> Color {
        if score >= 90 {
            return Color(red: 0.2, green: 0.8, blue: 0.4) // Green
        } else if score >= 75 {
            return Color(red: 0.95, green: 0.7, blue: 0.1) // Amber
        } else {
            return Color(red: 0.9, green: 0.35, blue: 0.1) // Orange/Red
        }
    }
    
    // Thermal State Name mappings
    private func thermalStateName(for state: ProcessInfo.ThermalState) -> String {
        switch state {
        case .nominal: return "NOMINAL"
        case .fair: return "FAIR"
        case .serious: return "SERIOUS (THROTTLED)"
        case .critical: return "CRITICAL"
        @unknown default: return "UNKNOWN"
        }
    }
    
    // Thermal State Color mappings
    private func thermalColor(for state: ProcessInfo.ThermalState) -> Color {
        switch state {
        case .nominal: return .green
        case .fair: return .yellow
        case .serious: return .orange
        case .critical: return .red
        @unknown default: return .gray
        }
    }
    
    // Thermal State SF Symbols
    private func thermalIconName(for state: ProcessInfo.ThermalState) -> String {
        switch state {
        case .nominal: return "thermometer.snowflake"
        case .fair: return "thermometer.low"
        case .serious: return "thermometer.medium"
        case .critical: return "thermometer.high"
        @unknown default: return "thermometer"
        }
    }
    
    // Time Formatter
    private func formatTime(_ time: TimeInterval) -> String {
        let minutes = Int(time) / 60
        let seconds = Int(time) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }
    
    // Pre-check diagnostics helpers
    private var isLowPowerModeEnabled: Bool {
        ProcessInfo.processInfo.isLowPowerModeEnabled
    }
    
    private var isCharging: Bool {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let state = UIDevice.current.batteryState
        return state == .charging || state == .full
    }
    
    private var isCellularRadioActive: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        let networkInfo = CTTelephonyNetworkInfo()
        if let technologies = networkInfo.serviceCurrentRadioAccessTechnology, !technologies.isEmpty {
            return true
        }
        return false
        #endif
    }
    
    enum PreCheckStatus {
        case optimal, warning, critical, info
    }
    
    private func warningRow(icon: String, title: String, message: String, status: PreCheckStatus, statusColor: Color) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(statusColor)
                .frame(width: 24, height: 24)
                .background(statusColor.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 6))
            
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 10, weight: .black, design: .monospaced))
                    .foregroundColor(.white)
                
                Text(message)
                    .font(.system(size: 9, weight: .medium))
                    .foregroundColor(.secondary)
                    .lineLimit(nil)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            
            Image(systemName: status == .optimal ? "checkmark.circle.fill" : (status == .critical ? "xmark.octagon.fill" : (status == .info ? "info.circle.fill" : "exclamationmark.circle.fill")))
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(statusColor)
        }
        .padding(10)
        .background(Color.white.opacity(0.02))
        .cornerRadius(10)
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.white.opacity(0.04), lineWidth: 1)
        )
    }
    
    // --- Custom Warning Overlays ---
    
    private var startWarningOverlay: some View {
        ZStack {
            Color.black.opacity(0.8)
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.spring()) {
                        showStartWarning = false
                    }
                }
            
            VStack(spacing: 20) {
                VStack(spacing: 8) {
                    Image(systemName: "checklist")
                        .font(.system(size: 32))
                        .foregroundColor(.blue)
                        .shadow(color: .blue.opacity(0.3), radius: 8)
                    
                    Text("PRE-TEST DIAGNOSTICS")
                        .font(.system(size: 14, weight: .black, design: .monospaced))
                        .tracking(1.5)
                        .foregroundColor(.white)
                }
                .padding(.top, 5)
                
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 12) {
                        // 1. Phone Case Warning (Always shown)
                        warningRow(
                            icon: "iphone.smartcover",
                            title: "REMOVE PHONE CASE",
                            message: "Highly recommended to remove your device case. Trapped heat severely skews throttling scores.",
                            status: .warning,
                            statusColor: .orange
                        )
                        
                        // 2. Low Power Mode Check
                        if isLowPowerModeEnabled {
                            warningRow(
                                icon: "battery.50",
                                title: "LOW POWER MODE IS ON",
                                message: "Please turn off Low Power Mode in Settings to get accurate unthrottled CPU performance.",
                                status: .warning,
                                statusColor: .orange
                            )
                        } else {
                            warningRow(
                                icon: "battery.100",
                                title: "POWER STATE OPTIMAL",
                                message: "Low Power Mode is disabled.",
                                status: .optimal,
                                statusColor: .green
                            )
                        }
                        
                        // 3. Charger Check
                        if isCharging {
                            warningRow(
                                icon: "bolt.fill",
                                title: "CHARGER CONNECTED!",
                                message: "CRITICAL: Unplug your charger. Battery charging emits significant heat that forces thermal throttling.",
                                status: .critical,
                                statusColor: .red
                            )
                        } else {
                            warningRow(
                                icon: "bolt.slash.fill",
                                title: "DISCONNECTED FROM CHARGER",
                                message: "Device is running on battery.",
                                status: .optimal,
                                statusColor: .green
                            )
                        }
                        
                        // 4. Airplane Mode Check
                        if isCellularRadioActive {
                            warningRow(
                                icon: "antenna.radiowaves.left.and.right",
                                title: "CELLULAR NETWORK DETECTED",
                                message: "Recommend turning on Airplane Mode. Cellular search generates extra background heat.",
                                status: .warning,
                                statusColor: .orange
                            )
                        } else {
                            warningRow(
                                icon: "airplane",
                                title: "AIRPLANE MODE / OFFLINE",
                                message: "Cellular radio is offline.",
                                status: .optimal,
                                statusColor: .green
                            )
                        }
                        
                        // 5. Foreground Warning
                        warningRow(
                            icon: "app.badge.fill",
                            title: "KEEP APP IN FOREGROUND",
                            message: "Minimizing, locking, or switching apps cancels the stress test automatically.",
                            status: .info,
                            statusColor: .blue
                        )
                    }
                }
                .frame(maxHeight: 280)
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                HStack(spacing: 12) {
                    Button(action: {
                        withAnimation(.spring()) {
                            showStartWarning = false
                        }
                    }) {
                        Text("CANCEL")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.white.opacity(0.08))
                            .cornerRadius(12)
                    }
                    
                    Button(action: {
                        withAnimation(.spring()) {
                            showStartWarning = false
                        }
                        
                        // Start test immediately without pre-test server session delays
                        engine.sessionId = nil
                        engine.encryptionKey = nil
                        engine.startTest(duration: selectedDuration, threadingType: selectedThreadingType)
                    }) {
                        Text("PROCEED")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(.black)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.white)
                            .cornerRadius(12)
                    }
                }
                .padding(.bottom, 5)
            }
            .padding(24)
            .frame(width: min(450, UIScreen.main.bounds.width * 0.9))
            .background(
                RoundedRectangle(cornerRadius: 24)
                    .fill(Color(white: 0.1).opacity(0.95))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.08), lineWidth: 1)
                    )
            )
            .shadow(color: .black.opacity(0.5), radius: 15)
        }
    }
    
    private var backgroundCancelledOverlay: some View {
        ZStack {
            Color.black.opacity(0.8)
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.spring()) {
                        showBackgroundCancelledWarning = false
                    }
                }
            
            VStack(spacing: 20) {
                VStack(spacing: 8) {
                    Image(systemName: "xmark.octagon.fill")
                        .font(.system(size: 40))
                        .foregroundColor(.red)
                        .shadow(color: .red.opacity(0.3), radius: 8)
                    
                    Text("TEST ABORTED")
                        .font(.system(size: 14, weight: .black, design: .monospaced))
                        .tracking(1)
                        .foregroundColor(.white)
                }
                .padding(.top, 10)
                
                Text("The diagnostic test was aborted because the app was minimized or moved to the background. Sticking to the foreground is required for accurate thermal stress measurement.")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                Button(action: {
                    withAnimation(.spring()) {
                        showBackgroundCancelledWarning = false
                    }
                }) {
                    Text("DISMISS")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundColor(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color.white)
                        .cornerRadius(12)
                }
                .padding(.bottom, 5)
            }
            .padding(24)
            .frame(width: 320)
            .background(
                RoundedRectangle(cornerRadius: 24)
                    .fill(Color(white: 0.1).opacity(0.95))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.08), lineWidth: 1)
                    )
            )
            .shadow(color: .black.opacity(0.5), radius: 15)
        }
    }
    
    private var manualCancelledOverlay: some View {
        ZStack {
            Color.black.opacity(0.8)
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.spring()) {
                        showManualCancelledWarning = false
                    }
                }
            
            VStack(spacing: 20) {
                VStack(spacing: 8) {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 40))
                        .foregroundColor(.orange)
                        .shadow(color: .orange.opacity(0.3), radius: 8)
                    
                    Text("TEST CANCELLED")
                        .font(.system(size: 14, weight: .black, design: .monospaced))
                        .tracking(1)
                        .foregroundColor(.white)
                }
                .padding(.top, 10)
                
                Text("The stress test was stopped manually. The diagnostic run was not allowed to finish, and the results are invalid.")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                Button(action: {
                    withAnimation(.spring()) {
                        showManualCancelledWarning = false
                    }
                }) {
                    Text("DISMISS")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundColor(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color.white)
                        .cornerRadius(12)
                }
                .padding(.bottom, 5)
            }
            .padding(24)
            .frame(width: 320)
            .background(
                RoundedRectangle(cornerRadius: 24)
                    .fill(Color(white: 0.1).opacity(0.95))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.08), lineWidth: 1)
                    )
            )
            .shadow(color: .black.opacity(0.5), radius: 15)
        }
    }
    
    private var serverInitOverlay: some View {
        ZStack {
            Color.black.opacity(0.8)
                .ignoresSafeArea()
            
            VStack(spacing: 20) {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: .blue))
                    .scaleEffect(1.5)
                    .padding(.top, 10)
                
                Text("CONNECTING TO SERVER")
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
                
                Text("Initializing secure diagnostic session for leaderboard verification...")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 10)
            }
            .padding(24)
            .frame(width: 280)
            .background(
                RoundedRectangle(cornerRadius: 24)
                    .fill(Color(white: 0.1).opacity(0.95))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.08), lineWidth: 1)
                      )
            )
            .shadow(color: .black.opacity(0.5), radius: 15)
        }
    }
    
    private var serverInitErrorOverlay: some View {
        ZStack {
            Color.black.opacity(0.8)
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.spring()) {
                        showInitError = false
                    }
                }
            
            VStack(spacing: 20) {
                VStack(spacing: 8) {
                    Image(systemName: "wifi.exclamationmark")
                        .font(.system(size: 40))
                        .foregroundColor(.yellow)
                        .shadow(color: .yellow.opacity(0.3), radius: 8)
                    
                    Text("SESSION FAILED")
                        .font(.system(size: 14, weight: .black, design: .monospaced))
                        .tracking(1)
                        .foregroundColor(.white)
                }
                .padding(.top, 10)
                
                Text("Could not connect to the diagnostic server:\n\(initErrorMessage)\n\nWould you like to run in Offline mode instead? (Offline runs cannot be submitted to the leaderboard.)")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                HStack(spacing: 12) {
                    Button(action: {
                        withAnimation(.spring()) {
                            showInitError = false
                        }
                    }) {
                        Text("CANCEL")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.white.opacity(0.08))
                            .cornerRadius(12)
                    }
                    
                    Button(action: {
                        withAnimation(.spring()) {
                            showInitError = false
                        }
                        // Proceed in Offline Mode
                        engine.sessionId = nil
                        engine.encryptionKey = nil
                        engine.startTest(duration: selectedDuration)
                    }) {
                        Text("RUN OFFLINE")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(.black)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.white)
                            .cornerRadius(12)
                    }
                }
                .padding(.bottom, 5)
            }
            .padding(24)
            .frame(width: 320)
            .background(
                RoundedRectangle(cornerRadius: 24)
                    .fill(Color(white: 0.1).opacity(0.95))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.08), lineWidth: 1)
                    )
            )
            .shadow(color: .black.opacity(0.5), radius: 15)
        }
    }
    
    private var customTabBar: some View {
        HStack {
            Spacer()
            
            Button(action: {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                    selectedTab = 0
                }
            }) {
                VStack(spacing: 4) {
                    Image(systemName: "gauge.with.needle.fill")
                        .font(.system(size: 20))
                    Text("Diagnostics")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                }
                .foregroundColor(selectedTab == 0 ? .white : .secondary)
                .frame(maxWidth: .infinity)
            }
            
            Button(action: {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                    selectedTab = 1
                }
            }) {
                VStack(spacing: 4) {
                    Image(systemName: "crown.fill")
                        .font(.system(size: 20))
                    Text("Leaderboard")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                }
                .foregroundColor(selectedTab == 1 ? .white : .secondary)
                .frame(maxWidth: .infinity)
            }
            
            Spacer()
        }
        .padding(.vertical, 10)
        .background(
            Color(white: 0.08)
                .opacity(0.85)
                .background(Material.thinMaterial)
        )
        .overlay(
            Divider()
                .background(Color.white.opacity(0.1)),
            alignment: .top
        )
    }
    private var connectionRequestOverlay: some View {
        ZStack {
            Color.black.opacity(0.8)
                .ignoresSafeArea()
            
            VStack(spacing: 20) {
                VStack(spacing: 8) {
                    Image(systemName: "wifi")
                        .font(.system(size: 40))
                        .foregroundColor(.blue)
                        .shadow(color: .blue.opacity(0.3), radius: 8)
                    
                    Text("DIAGNOSTICS COMPLETE")
                        .font(.system(size: 14, weight: .black, design: .monospaced))
                        .tracking(1)
                        .foregroundColor(.white)
                }
                .padding(.top, 10)
                
                Text("Please enable Wi-Fi or cellular data now to submit your score to the global leaderboard.")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                VStack(spacing: 8) {
                    Button(action: {
                        withAnimation(.spring()) {
                            showConnectionRequest = false
                        }
                        submitSuccessMessage = nil
                        submitErrorMessage = nil
                        withAnimation(.spring()) {
                            showSummary = true
                        }
                    }) {
                        Text("I TURNED IT ON")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(.black)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.white)
                            .cornerRadius(12)
                    }
                    
                    Button(action: {
                        withAnimation(.spring()) {
                            showConnectionRequest = false
                        }
                        withAnimation(.spring()) {
                            showSummary = true
                        }
                    }) {
                        Text("SUBMIT LATER")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(.white.opacity(0.6))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.white.opacity(0.08))
                            .cornerRadius(12)
                    }
                }
                .padding(.bottom, 5)
            }
            .padding(24)
            .background(
                RoundedRectangle(cornerRadius: 24)
                    .fill(Color(white: 0.12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.08), lineWidth: 1)
                    )
            )
            .frame(width: 320)
            .shadow(color: .black.opacity(0.4), radius: 20)
        }
    }
    private var pendingRunsBanner: some View {
        Button(action: {
            withAnimation(.spring()) {
                selectedTab = 1
            }
        }) {
            HStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 16))
                    .foregroundColor(Color(red: 0.95, green: 0.7, blue: 0.1))
                
                VStack(alignment: .leading, spacing: 2) {
                    Text("UNSUBMITTED RESULTS DETECTED")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                    
                    Text("You have \(pendingStore.results.count) locally saved test run\(pendingStore.results.count > 1 ? "s" : ""). Tap here to submit to the leaderboard.")
                        .font(.system(size: 8))
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.leading)
                }
                
                Spacer()
                
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(.white.opacity(0.3))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(
                RoundedRectangle(cornerRadius: 16)
                    .fill(Color(white: 0.08).opacity(0.6))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(Color(red: 0.95, green: 0.7, blue: 0.1).opacity(0.3), lineWidth: 1)
                    )
            )
        }
        .transition(.opacity.combined(with: .move(edge: .top)))
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
