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

// MARK: - Colour tokens
private let cyan   = Color(red: 0.0, green: 0.9, blue: 1.0)
private let amber  = Color(red: 1.0, green: 0.67, blue: 0.0)
private let surfaceLow  = Color(white: 0.08)
private let surfaceMid  = Color(white: 0.11)

// MARK: - ComparisonView
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
        (preselectedRunA?.testThreadingType ?? preselectedRunB?.testThreadingType) ?? 1
    }

    var allComparableRuns: [PendingTestResult] {
        let tt = targetThreadingType
        var list = (store.results + onlineResults).filter { ($0.testThreadingType ?? 1) == tt }
        if let preA = preselectedRunA, !list.contains(where: { (preA.sessionId > 0 && $0.sessionId == preA.sessionId) || $0.id == preA.id }) {
            list.insert(preA, at: 0)
        }
        if let preB = preselectedRunB, !list.contains(where: { (preB.sessionId > 0 && $0.sessionId == preB.sessionId) || $0.id == preB.id }) {
            list.insert(preB, at: 0)
        }
        return list
    }

    private func updateIndices() {
        let runs = allComparableRuns
        if let preA = preselectedRunA,
           let idxA = runs.firstIndex(where: { (preA.sessionId > 0 && $0.sessionId == preA.sessionId) || $0.id == preA.id }) {
            indexA = idxA
        }
        if let preB = preselectedRunB,
           let idxB = runs.firstIndex(where: { (preB.sessionId > 0 && $0.sessionId == preB.sessionId) || $0.id == preB.id }) {
            indexB = idxB
        }
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if allComparableRuns.isEmpty {
                emptyState
            } else {
                mainContent
            }
        }
        .onAppear {
            updateIndices()
        }
        .onChange(of: onlineResults.count) { _ in
            updateIndices()
        }
        .task {
            do {
                let items = try await LeaderboardService.shared.fetchLeaderboard()
                self.onlineResults = items.map { $0.toPendingTestResult() }
                updateIndices()
            } catch {}
        }
    }

    // MARK: - Empty state
    private var emptyState: some View {
        VStack(spacing: 20) {
            Text("⚖️")
                .font(.system(size: 44))
            Text("UNIVERSAL RUN COMPARISON")
                .font(.system(size: 13, weight: .black, design: .monospaced))
                .foregroundColor(.white)
            Text("No matching test runs available.\nSingle Thread tests can only be compared with Single Thread,\nand Multi Thread with Multi Thread.")
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Button(action: { dismiss() }) {
                Text("CLOSE")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
                    .padding(.horizontal, 28)
                    .padding(.vertical, 10)
                    .background(Color.white.opacity(0.1))
                    .cornerRadius(14)
            }
        }
    }

    // MARK: - Main content
    private var mainContent: some View {
        let runs   = allComparableRuns
        let rawA   = runs[min(indexA, runs.count - 1)]
        let rawB   = runs[min(indexB, runs.count - 1)]
        let stA    = stampsMap[rawA.sessionId] ?? rawA.stamps
        let stB    = stampsMap[rawB.sessionId] ?? rawB.stamps
        let runA   = rawA.withStamps(stA)
        let runB   = rawB.withStamps(stB)
        let analysis = ComparisonEngine.analyze(runA: runA, runB: runB)

        let pointsA = buildPoints(stamps: stA, fallback: runA)
        let pointsB = buildPoints(stamps: stB, fallback: runB)

        return ScrollView {
            VStack(spacing: 20) {
                titleBar
                selectorRow(runs: runs, runA: runA, runB: runB, stA: stA, stB: stB, rawA: rawA, rawB: rawB)
                DualStabilityChart(
                    pointsA: pointsA,
                    labelA: "Run A (\(runA.deviceModel))",
                    pointsB: pointsB,
                    labelB: "Run B (\(runB.deviceModel))"
                )
                metricsSection(analysis: analysis, runA: runA, runB: runB)
                insightsCard(analysis: analysis)
            }
            .padding(16)
            .task(id: rawA.sessionId) {
                if stA.isEmpty && rawA.sessionId > 0 {
                    if let fetched = try? await LeaderboardService.shared.fetchStamps(for: rawA.sessionId) {
                        await MainActor.run { stampsMap[rawA.sessionId] = fetched }
                    }
                }
            }
            .task(id: rawB.sessionId) {
                if stB.isEmpty && rawB.sessionId > 0 {
                    if let fetched = try? await LeaderboardService.shared.fetchStamps(for: rawB.sessionId) {
                        await MainActor.run { stampsMap[rawB.sessionId] = fetched }
                    }
                }
            }
        }
    }

    // MARK: - Title bar
    private var titleBar: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text("⚖️ COMPARISON ENGINE")
                        .font(.system(size: 14, weight: .black, design: .monospaced))
                        .foregroundColor(.white)
                    Text(targetThreadingType == 0 ? "1 THREAD" : "MULTI")
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .foregroundColor(cyan)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(cyan.opacity(0.15))
                        .cornerRadius(6)
                }
                Text(targetThreadingType == 0 ? "Single Thread Benchmark" : "Multi Thread Benchmark")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(.secondary)
            }
            Spacer()
            Button(action: { dismiss() }) {
                Text("✕ CLOSE")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Color.white.opacity(0.1))
                    .cornerRadius(16)
            }
        }
    }

    // MARK: - Selector row
    private func selectorRow(
        runs: [PendingTestResult],
        runA: PendingTestResult, runB: PendingTestResult,
        stA: [DeviceHammerStamp], stB: [DeviceHammerStamp],
        rawA: PendingTestResult, rawB: PendingTestResult
    ) -> some View {
        HStack(spacing: 12) {
            runSelectorCard(
                label: "RUN A", accentColor: cyan,
                runs: runs, selectedIndex: $indexA,
                isLoading: stA.isEmpty && rawA.sessionId > 0
            )
            runSelectorCard(
                label: "RUN B", accentColor: amber,
                runs: runs, selectedIndex: $indexB,
                isLoading: stB.isEmpty && rawB.sessionId > 0
            )
        }
    }

    private func runSelectorCard(
        label: String,
        accentColor: Color,
        runs: [PendingTestResult],
        selectedIndex: Binding<Int>,
        isLoading: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // Label row
            HStack(spacing: 6) {
                Text(label)
                    .font(.system(size: 9, weight: .black, design: .monospaced))
                    .foregroundColor(accentColor)
                if isLoading {
                    ProgressView()
                        .scaleEffect(0.55)
                }
            }

            // Picker (device selector) — full width, no duplicate text
            Picker(label, selection: selectedIndex) {
                ForEach(0..<runs.count, id: \.self) { idx in
                    let r = runs[idx]
                    Text("\(r.deviceManufacturer) \(r.deviceModel)")
                        .font(.system(size: 11, design: .monospaced))
                        .tag(idx)
                }
            }
            .pickerStyle(.menu)
            .tint(accentColor)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(accentColor.opacity(0.07))
        .cornerRadius(18)
        .overlay(
            RoundedRectangle(cornerRadius: 18)
                .stroke(accentColor.opacity(0.3), lineWidth: 1)
        )
    }

    // MARK: - Metrics section
    private func metricsSection(analysis: ComparisonAnalysis, runA: PendingTestResult, runB: PendingTestResult) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("⚡ COMPARATIVE DIAGNOSTICS")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundColor(.secondary)

            VStack(spacing: 10) {
                HStack(spacing: 10) {
                    metricCard(
                        title: "FINAL STABILITY",
                        valA: String(format: "%.1f%%", runA.finalStability),
                        valB: String(format: "%.1f%%", runB.finalStability),
                        deltaText: nil
                    )
                    metricCard(
                        title: "PEAK SCORE (IPS)",
                        valA: String(format: "%.0f", analysis.peakScoreA),
                        valB: String(format: "%.0f", analysis.peakScoreB),
                        deltaText: String(format: "%+.1f%%", analysis.peakScoreDeltaPct)
                    )
                }
                HStack(spacing: 10) {
                    metricCard(
                        title: "THROTTLE ONSET",
                        valA: analysis.throttleOnsetTimeA.map { "\($0)s" } ?? "None",
                        valB: analysis.throttleOnsetTimeB.map { "\($0)s" } ?? "None",
                        deltaText: nil
                    )
                    metricCard(
                        title: "AVG SCORE (IPS)",
                        valA: String(format: "%.0f", analysis.avgScoreA),
                        valB: String(format: "%.0f", analysis.avgScoreB),
                        deltaText: String(format: "%+.1f%%", analysis.avgScoreDeltaPct)
                    )
                }
            }
        }
        .padding(16)
        .background(surfaceLow.opacity(0.7))
        .cornerRadius(22)
    }

    @ViewBuilder
    private func metricCard(title: String, valA: String, valB: String, deltaText: String?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // Title
            Text(title)
                .font(.system(size: 8, weight: .bold, design: .monospaced))
                .foregroundColor(.secondary)
                .lineLimit(1)

            // Run A
            VStack(alignment: .leading, spacing: 2) {
                Text("RUN A")
                    .font(.system(size: 7, weight: .semibold, design: .monospaced))
                    .foregroundColor(cyan)
                Text(valA)
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }

            Divider()
                .background(Color.white.opacity(0.07))

            // Run B
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text("RUN B")
                        .font(.system(size: 7, weight: .semibold, design: .monospaced))
                        .foregroundColor(amber)
                    if let delta = deltaText {
                        let positive = delta.hasPrefix("+")
                        Text(delta)
                            .font(.system(size: 7, weight: .bold, design: .monospaced))
                            .foregroundColor(positive ? Color(red: 0.2, green: 0.9, blue: 0.4) : Color(red: 1.0, green: 0.35, blue: 0.35))
                            .padding(.horizontal, 5)
                            .padding(.vertical, 2)
                            .background((positive ? Color.green : Color.red).opacity(0.15))
                            .cornerRadius(5)
                    }
                }
                Text(valB)
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white.opacity(0.04))
        .cornerRadius(16)
    }

    // MARK: - Insights card
    private func insightsCard(analysis: ComparisonAnalysis) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Text("🤖")
                    .font(.system(size: 14))
                Text("ANALYTICAL INSIGHTS")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(cyan)
            }
            Text(analysis.summaryText)
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(.white.opacity(0.85))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(surfaceMid)
        .cornerRadius(22)
        .overlay(
            RoundedRectangle(cornerRadius: 22)
                .stroke(cyan.opacity(0.2), lineWidth: 1)
        )
    }

    // MARK: - Helpers
    private func buildPoints(stamps: [DeviceHammerStamp], fallback: PendingTestResult) -> [StabilityPoint] {
        if !stamps.isEmpty {
            let maxVal = Double(stamps.map { $0.score }.max() ?? 1)
            return stamps.map { StabilityPoint(time: Double($0.elapsedMs / 1000), score: (Double($0.score) / maxVal) * 100.0) }
        } else {
            return [
                StabilityPoint(time: 0, score: fallback.finalStability),
                StabilityPoint(time: Double(fallback.durationSeconds), score: fallback.finalStability)
            ]
        }
    }
}

// MARK: - PendingTestResult helpers
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
