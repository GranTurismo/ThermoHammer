import SwiftUI

extension HammerDto {
    func toPendingTestResult(stampsList: [DeviceHammerStamp] = []) -> PendingTestResult {
        let durSec = type == 0 ? 300 : (type == 1 ? 900 : 1800)
        return PendingTestResult(
            id: UUID(),
            timestamp: Date().timeIntervalSince1970,
            durationSeconds: durSec,
            testDurationType: type,
            testThreadingType: testThreadingType,
            minStability: Double(stabilityPercentage),
            finalStability: Double(stabilityPercentage),
            worstThermalState: 0,
            stamps: stampsList.isEmpty ? (stamps ?? []) : stampsList,
            deviceModel: deviceModel,
            deviceManufacturer: deviceManufacturer,
            osVersion: osVersion,
            sessionId: id,
            encryptionKey: ""
        )
    }
}

struct ComparisonView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store = PendingResultStore.shared
    
    var preselectedRunA: PendingTestResult? = nil
    var preselectedRunB: PendingTestResult? = nil
    
    @State private var onlineResults: [PendingTestResult] = []
    @State private var indexA: Int = 0
    @State private var indexB: Int = 1
    
    @State private var stampsMap: [Int: [DeviceHammerStamp]] = [:]
    
    var targetThreadingType: Int {
        return (preselectedRunA?.testThreadingType ?? preselectedRunB?.testThreadingType) ?? 1
    }
    
    var allComparableRuns: [PendingTestResult] {
        let tt = targetThreadingType
        var list = (store.results + onlineResults).filter { ($0.testThreadingType ?? 1) == tt }
        if let preA = preselectedRunA {
            if !list.contains(where: { $0.sessionId == preA.sessionId && $0.deviceModel == preA.deviceModel }) {
                list.insert(preA, at: 0)
            }
        }
        if let preB = preselectedRunB {
            if !list.contains(where: { $0.sessionId == preB.sessionId && $0.deviceModel == preB.deviceModel }) {
                list.insert(preB, at: 0)
            }
        }
        return list
    }
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            if allComparableRuns.isEmpty {
                VStack(spacing: 16) {
                    Text("⚖️")
                        .font(.system(size: 40))
                    Text("UNIVERSAL RUN COMPARISON")
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                    Text("No matching test runs available to compare. Single Thread tests can only be compared with Single Thread tests, and Multi Thread with Multi Thread.")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    
                    Button(action: { dismiss() }) {
                        Text("CLOSE")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                            .padding(.horizontal, 24)
                            .padding(.vertical, 10)
                            .background(Color.white.opacity(0.1))
                            .cornerRadius(12)
                    }
                }
            } else {
                let runs = allComparableRuns
                let rawRunA = runs[min(indexA, runs.count - 1)]
                let rawRunB = runs[min(indexB, runs.count - 1)]
                
                let stampsA = stampsMap[rawRunA.sessionId] ?? rawRunA.stamps
                let stampsB = stampsMap[rawRunB.sessionId] ?? rawRunB.stamps
                
                let runA = rawRunA.withStamps(stampsA)
                let runB = rawRunB.withStamps(stampsB)
                
                let analysis = ComparisonEngine.analyze(runA: runA, runB: runB)
                
                let pointsA: [StabilityPoint] = {
                    if !stampsA.isEmpty {
                        let maxVal = Double(stampsA.map { $0.score }.max() ?? 1)
                        return stampsA.map { StabilityPoint(time: Double($0.elapsedMs / 1000), score: (Double($0.score) / maxVal) * 100.0) }
                    } else {
                        return [StabilityPoint(time: 0, score: runA.finalStability), StabilityPoint(time: Double(runA.durationSeconds), score: runA.finalStability)]
                    }
                }()
                
                let pointsB: [StabilityPoint] = {
                    if !stampsB.isEmpty {
                        let maxVal = Double(stampsB.map { $0.score }.max() ?? 1)
                        return stampsB.map { StabilityPoint(time: Double($0.elapsedMs / 1000), score: (Double($0.score) / maxVal) * 100.0) }
                    } else {
                        return [StabilityPoint(time: 0, score: runB.finalStability), StabilityPoint(time: Double(runB.durationSeconds), score: runB.finalStability)]
                    }
                }()
                
                ScrollView {
                    VStack(spacing: 16) {
                        // Title bar
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text("⚖️ UNIVERSAL COMPARISON ENGINE")
                                        .font(.system(size: 13, weight: .black, design: .monospaced))
                                        .foregroundColor(.white)
                                    Text(targetThreadingType == 0 ? "1 THREAD" : "MULTI")
                                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                                        .foregroundColor(Color(red: 0.0, green: 0.9, blue: 1.0))
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(Color(red: 0.0, green: 0.9, blue: 1.0).opacity(0.15))
                                        .cornerRadius(6)
                                }
                                Text("Comparing \(targetThreadingType == 0 ? "Single Thread" : "Multi Thread") Benchmark Runs")
                                    .font(.system(size: 9, design: .monospaced))
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Button(action: { dismiss() }) {
                                Text("✕ CLOSE")
                                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(Color.white.opacity(0.1))
                                    .cornerRadius(16)
                            }
                        }
                        
                        // Selectors (Run A vs Run B)
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text("RUN A (CYAN)")
                                        .font(.system(size: 9, weight: .black, design: .monospaced))
                                        .foregroundColor(Color(red: 0.0, green: 0.9, blue: 1.0))
                                    if stampsA.isEmpty && rawRunA.sessionId > 0 {
                                        ProgressView()
                                            .scaleEffect(0.6)
                                    }
                                }
                                Text("\(runA.deviceManufacturer) \(runA.deviceModel)")
                                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                                    .foregroundColor(.white)
                                
                                Picker("Run A", selection: $indexA) {
                                    ForEach(0..<runs.count, id: \.self) { idx in
                                        Text("\(runs[idx].deviceManufacturer) \(runs[idx].deviceModel)").tag(idx)
                                    }
                                }
                                .pickerStyle(.menu)
                            }
                            .padding(12)
                            .background(Color(red: 0.0, green: 0.9, blue: 1.0).opacity(0.08))
                            .cornerRadius(18)
                            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(red: 0.0, green: 0.9, blue: 1.0).opacity(0.3), lineWidth: 1))
                            
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text("RUN B (AMBER)")
                                        .font(.system(size: 9, weight: .black, design: .monospaced))
                                        .foregroundColor(Color(red: 1.0, green: 0.67, blue: 0.0))
                                    if stampsB.isEmpty && rawRunB.sessionId > 0 {
                                        ProgressView()
                                            .scaleEffect(0.6)
                                    }
                                }
                                Text("\(runB.deviceManufacturer) \(runB.deviceModel)")
                                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                                    .foregroundColor(.white)
                                
                                Picker("Run B", selection: $indexB) {
                                    ForEach(0..<runs.count, id: \.self) { idx in
                                        Text("\(runs[idx].deviceManufacturer) \(runs[idx].deviceModel)").tag(idx)
                                    }
                                }
                                .pickerStyle(.menu)
                            }
                            .padding(12)
                            .background(Color(red: 1.0, green: 0.67, blue: 0.0).opacity(0.08))
                            .cornerRadius(18)
                            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(red: 1.0, green: 0.67, blue: 0.0).opacity(0.3), lineWidth: 1))
                        }
                        
                        // Dual Stability Chart (Real API Time-Series Curves!)
                        DualStabilityChart(
                            pointsA: pointsA,
                            labelA: "Run A (\(runA.deviceModel))",
                            pointsB: pointsB,
                            labelB: "Run B (\(runB.deviceModel))"
                        )
                        
                        // Analytical Metric Grid
                        VStack(alignment: .leading, spacing: 12) {
                            Text("⚡ COMPARATIVE DIAGNOSTICS METRICS")
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .foregroundColor(.secondary)
                            
                            HStack(spacing: 12) {
                                metricCard(
                                    title: "FINAL STABILITY",
                                    valA: String(format: "%.1f%%", runA.finalStability),
                                    valB: String(format: "%.1f%%", runB.finalStability)
                                )
                                metricCard(
                                    title: "PEAK SCORE (IPS)",
                                    valA: String(format: "%.0f", analysis.peakScoreA),
                                    valB: String(format: "%.0f (%+.1f%%)", analysis.peakScoreB, analysis.peakScoreDeltaPct)
                                )
                            }
                            
                            HStack(spacing: 12) {
                                metricCard(
                                    title: "THROTTLE ONSET",
                                    valA: analysis.throttleOnsetTimeA.map { "\($0)s" } ?? "No Throttle",
                                    valB: analysis.throttleOnsetTimeB.map { "\($0)s" } ?? "No Throttle"
                                )
                                metricCard(
                                    title: "AVG SCORE (IPS)",
                                    valA: String(format: "%.0f", analysis.avgScoreA),
                                    valB: String(format: "%.0f (%+.1f%%)", analysis.avgScoreB, analysis.avgScoreDeltaPct)
                                )
                            }
                        }
                        .padding(16)
                        .background(Color(white: 0.08).opacity(0.6))
                        .cornerRadius(24)
                        
                        // Automated Analytical Summary Card
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 6) {
                                Text("🤖")
                                Text("ANALYTICAL ENGINE INSIGHTS")
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundColor(Color(red: 0.0, green: 0.9, blue: 1.0))
                            }
                            Text(analysis.summaryText)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundColor(.white.opacity(0.9))
                        }
                        .padding(18)
                        .background(Color(white: 0.1))
                        .cornerRadius(24)
                        .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color(red: 0.0, green: 0.9, blue: 1.0).opacity(0.2), lineWidth: 1))
                    }
                    .padding(16)
                    .task(id: rawRunA.sessionId) {
                        if stampsA.isEmpty && rawRunA.sessionId > 0 {
                            do {
                                let fetched = try await LeaderboardService.shared.fetchStamps(for: rawRunA.sessionId)
                                await MainActor.run {
                                    self.stampsMap[rawRunA.sessionId] = fetched
                                }
                            } catch {}
                        }
                    }
                    .task(id: rawRunB.sessionId) {
                        if stampsB.isEmpty && rawRunB.sessionId > 0 {
                            do {
                                let fetched = try await LeaderboardService.shared.fetchStamps(for: rawRunB.sessionId)
                                await MainActor.run {
                                    self.stampsMap[rawRunB.sessionId] = fetched
                                }
                            } catch {}
                        }
                    }
                }
            }
        }
        .task {
            do {
                let items = try await LeaderboardService.shared.fetchLeaderboard()
                self.onlineResults = items.map { $0.toPendingTestResult() }
            } catch {
                // Ignore offline error
            }
        }
    }
    
    @ViewBuilder
    private func metricCard(title: String, valA: String, valB: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 8, weight: .bold, design: .monospaced))
                .foregroundColor(.secondary)
            HStack {
                VStack(alignment: .leading) {
                    Text("RUN A")
                        .font(.system(size: 8, design: .monospaced))
                        .foregroundColor(Color(red: 0.0, green: 0.9, blue: 1.0))
                    Text(valA)
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                }
                Spacer()
                VStack(alignment: .trailing) {
                    Text("RUN B")
                        .font(.system(size: 8, design: .monospaced))
                        .foregroundColor(Color(red: 1.0, green: 0.67, blue: 0.0))
                    Text(valB)
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(Color.white.opacity(0.03))
        .cornerRadius(16)
    }
}

extension PendingTestResult {
    func withStamps(_ newStamps: [DeviceHammerStamp]) -> PendingTestResult {
        return PendingTestResult(
            id: id,
            timestamp: timestamp,
            durationSeconds: durationSeconds,
            testDurationType: testDurationType,
            testThreadingType: testThreadingType,
            minStability: minStability,
            finalStability: finalStability,
            worstThermalState: worstThermalState,
            stamps: newStamps.isEmpty ? stamps : newStamps,
            deviceModel: deviceModel,
            deviceManufacturer: deviceManufacturer,
            osVersion: osVersion,
            sessionId: sessionId,
            encryptionKey: encryptionKey
        )
    }
}
