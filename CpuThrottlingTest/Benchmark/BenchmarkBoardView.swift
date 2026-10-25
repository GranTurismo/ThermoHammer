import SwiftUI

/** One phone's best benchmark run, as GET /benchmark/leaderboard returns it. */
struct BenchmarkDto: Decodable, Identifiable {
    let id: Int
    let deviceManufacturer: String
    let deviceModel: String
    let socModel: String?
    let gpuName: String?
    let os: Int                 // 1 iOS · 2 Android
    let osVersion: String
    let score: Int              // GPU
    let uxScore: Int
    let cpuScore: Int
    var aiScore: Int = 0
    var memScore: Int = 0
    let totalScore: Int
    let test3Fps: Double
    let rayTracing: Bool
    let runs: Int

    private enum CodingKeys: String, CodingKey {
        case id, deviceManufacturer, deviceModel, socModel, gpuName, os, osVersion, score, uxScore, cpuScore, aiScore, memScore
        case totalScore, test3Fps, rayTracing, runs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        deviceManufacturer = try c.decode(String.self, forKey: .deviceManufacturer)
        deviceModel = try c.decode(String.self, forKey: .deviceModel)
        socModel = try c.decodeIfPresent(String.self, forKey: .socModel)
        gpuName = try c.decodeIfPresent(String.self, forKey: .gpuName)
        os = try c.decode(Int.self, forKey: .os)
        osVersion = try c.decode(String.self, forKey: .osVersion)
        score = try c.decode(Int.self, forKey: .score)
        uxScore = try c.decode(Int.self, forKey: .uxScore)
        cpuScore = try c.decode(Int.self, forKey: .cpuScore)
        aiScore = try c.decodeIfPresent(Int.self, forKey: .aiScore) ?? 0
        memScore = try c.decodeIfPresent(Int.self, forKey: .memScore) ?? 0
        totalScore = try c.decode(Int.self, forKey: .totalScore)
        test3Fps = try c.decode(Double.self, forKey: .test3Fps)
        rayTracing = try c.decode(Bool.self, forKey: .rayTracing)
        runs = try c.decode(Int.self, forKey: .runs)
    }

    var name: String {
        deviceModel.lowercased().hasPrefix(deviceManufacturer.lowercased()) ? deviceModel : "\(deviceManufacturer) \(deviceModel)"
    }
}

extension BenchmarkUpload {
    static func fetchLeaderboard() async throws -> [BenchmarkDto] {
        let (data, response) = try await URLSession.shared.data(from: baseURL.appendingPathComponent("benchmark/leaderboard"))
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw NSError(domain: "BenchmarkUpload", code: (response as? HTTPURLResponse)?.statusCode ?? 0,
                          userInfo: [NSLocalizedDescriptionKey: "Leaderboard unavailable (HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0))"])
        }
        return try JSONDecoder().decode([BenchmarkDto].self, from: data)
    }
}

/** Leaderboard tab: the benchmark board (default) or the sustained stress board — Android's LeaderboardScreen. */
struct LeaderboardTab: View {
    @State private var board = 0          // 0 benchmark · 1 stress

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                ForEach(Array(["BENCHMARK", "STRESS TEST"].enumerated()), id: \.offset) { i, label in
                    Button { board = i } label: {
                        Text(label).font(ThermoFont.mono(10, weight: .black)).tracking(1)
                            .foregroundColor(board == i ? Forge.bg : Forge.ink1)
                            .frame(maxWidth: .infinity).padding(.vertical, 8)
                            .background(board == i ? Forge.phaseMeasured : Color.clear)
                            .cornerRadius(9)
                    }
                }
            }
            .padding(4)
            .background(Forge.surface)
            .cornerRadius(12)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Forge.hairline, lineWidth: 1))
            .padding(.horizontal, 16).padding(.top, 14)
            if board == 0 { BenchmarkBoardView() } else { LeaderboardView() }
        }
        .background(Color.black.ignoresSafeArea())
    }
}

/** Best benchmark score per phone model, highest first. */
struct BenchmarkBoardView: View {
    @State private var entries: [BenchmarkDto] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var searchText = ""
    @StateObject private var networkMonitor = NetworkMonitor.shared

    private var shown: [(Int, BenchmarkDto)] {
        let q = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        return entries.enumerated().map { ($0.offset + 1, $0.element) }.filter { _, e in
            q.isEmpty || e.deviceModel.lowercased().contains(q) || e.deviceManufacturer.lowercased().contains(q)
                || (e.gpuName?.lowercased().contains(q) ?? false)
        }
    }

    var body: some View {
        ScrollView(showsIndicators: false) {
            LazyVStack(spacing: 8) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("BENCHMARK").font(ThermoFont.mono(18, weight: .black)).tracking(1.5).foregroundColor(Forge.ink0)
                        Text("CPU + MEM + GPU + UX + AI · best run per phone").font(ThermoFont.mono(9)).foregroundColor(Forge.ink0.opacity(0.4))
                    }
                    Spacer()
                    Button { Task { await load() } } label: {
                        Text("↻").font(.system(size: 18, weight: .bold)).foregroundColor(Forge.ink0)
                    }
                    .disabled(isLoading)
                }
                HStack {
                    Text("🔍").font(.system(size: 12))
                    TextField("", text: $searchText, prompt: Text("Search phone or GPU…").foregroundColor(Forge.ink0.opacity(0.3)))
                        .font(ThermoFont.mono(11)).foregroundColor(Forge.ink0)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                }
                .padding(.horizontal, 12).padding(.vertical, 10)
                .background(Forge.ink0.opacity(0.04)).cornerRadius(12)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Forge.ink0.opacity(0.08), lineWidth: 1))
                if isLoading {
                    ProgressView().tint(Forge.phasePreFlight).padding(40)
                } else if let errorMessage {
                    VStack(spacing: 8) {
                        Text("⚠ \(errorMessage)").font(ThermoFont.mono(12, weight: .bold)).foregroundColor(Ramp.at(0.85))
                        Button { Task { await load() } } label: {
                            Text("RETRY").font(ThermoFont.mono(10)).foregroundColor(Forge.ink0)
                                .padding(.horizontal, 14).padding(.vertical, 6).background(Forge.ink0.opacity(0.1)).cornerRadius(8)
                        }
                    }
                    .padding(30)
                } else if shown.isEmpty {
                    Text(entries.isEmpty ? "No benchmark runs yet — be the first." : "No phones match.")
                        .font(ThermoFont.mono(11)).foregroundColor(Forge.ink0.opacity(0.4)).padding(30)
                } else {
                    let top = max(entries.first?.totalScore ?? 1, 1)
                    let mine = LeaderboardService.shared.getDeviceModelName()
                    ForEach(shown, id: \.1.id) { rank, e in
                        row(rank, e, fraction: Double(e.totalScore) / Double(top), mine: e.deviceModel.caseInsensitiveCompare(mine) == .orderedSame)
                    }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
        }
        .task { await load() }
    }

    private func load() async {
        guard networkMonitor.isConnected else { errorMessage = "Offline"; return }
        isLoading = true
        errorMessage = nil
        do {
            entries = try await BenchmarkUpload.fetchLeaderboard().sorted { $0.totalScore > $1.totalScore }
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    private func row(_ rank: Int, _ e: BenchmarkDto, fraction: Double, mine: Bool) -> some View {
        VStack(spacing: 8) {
            HStack(alignment: .center) {
                Text("#\(rank)").font(ThermoFont.mono(11, weight: .black))
                    .foregroundColor(rank <= 3 ? Ramp.at(1 - Double(rank - 1) * 0.2) : Forge.ink2)
                    .frame(width: 38, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    Text(e.name).font(ThermoFont.mono(12, weight: .bold)).foregroundColor(Forge.ink0).lineLimit(1)
                    Text([e.gpuName, e.os == 1 ? "iOS \(e.osVersion)" : "Android \(e.osVersion)"].compactMap { $0 }.joined(separator: " · "))
                        .font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.4)).lineLimit(1)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(e.totalScore.formatted()).font(ThermoFont.mono(15, weight: .black)).foregroundColor(Forge.ink0)
                    Text("CPU \(e.cpuScore.formatted()) · MEM \(e.memScore.formatted()) · GPU \(e.score.formatted()) · UX \(e.uxScore.formatted()) · AI \(e.aiScore.formatted()) · "
                         + (e.rayTracing ? String(format: "RT %.0f FPS", e.test3Fps) : "NO RT"))
                        .font(ThermoFont.mono(7)).foregroundColor(Forge.ink0.opacity(0.4)).lineLimit(1).minimumScaleFactor(0.7)
                }
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Forge.ink0.opacity(0.06))
                    Capsule().fill(LinearGradient(colors: [Forge.phaseCalibration, Ramp.at(0.85)], startPoint: .leading, endPoint: .trailing))
                        .frame(width: g.size.width * CGFloat(max(0.02, min(1, fraction))))
                }
            }
            .frame(height: 4)
        }
        .padding(12)
        .background(Forge.surface)
        .cornerRadius(14)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(mine ? Forge.phaseMeasured.opacity(0.6) : Forge.ink0.opacity(0.06), lineWidth: 1))
    }
}
