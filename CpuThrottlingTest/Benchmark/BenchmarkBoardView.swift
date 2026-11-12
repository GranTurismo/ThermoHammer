import SwiftUI

/** One phone's best benchmark run, as GET /benchmark/leaderboard returns it. */
struct BenchmarkDto: Decodable, Identifiable {
    let id: Int
    let deviceManufacturer: String
    let deviceModel: String
    let socModel: String?
    let gpuName: String?
    let os: Int                 // 1 iOS · 2 Android · 3 macOS · 4 Windows
    let osVersion: String
    let score: Int              // GPU
    let uxScore: Int
    let cpuScore: Int
    var aiScore: Int = 0
    var memScore: Int = 0
    var llmScore: Int = 0
    let totalScore: Int
    let test3Fps: Double
    let rayTracing: Bool
    let runs: Int

    private enum CodingKeys: String, CodingKey {
        case id, deviceManufacturer, deviceModel, socModel, gpuName, os, osVersion, score, uxScore, cpuScore, aiScore, memScore, llmScore
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
        llmScore = try c.decodeIfPresent(Int.self, forKey: .llmScore) ?? 0
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

/** The platform tabs under the board toggle; `rawValue` is the server's OsPlatform (0 = every platform). */
enum LeaderboardPlatform: Int, CaseIterable {
    case all = 0, windows = 4, macOS = 3, android = 2, iOS = 1

    var tab: String { self == .all ? "ALL" : name.uppercased() }
    var name: String {
        switch self {
        case .all: return "All"
        case .windows: return "Windows"
        case .macOS: return "macOS"
        case .android: return "Android"
        case .iOS: return "iOS"
        }
    }
    func includes(_ os: Int) -> Bool { self == .all || os == rawValue }
}

/** Leaderboard tab: the benchmark board (default) or the sustained stress board — Android's LeaderboardScreen. */
struct LeaderboardTab: View {
    @State private var board = 0          // 0 benchmark · 1 stress
    @State private var platform = LeaderboardPlatform.all

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
            platformTabs
            if board == 0 { BenchmarkBoardView(platform: platform) } else { LeaderboardView(platform: platform) }
        }
        .background(Color.black.ignoresSafeArea())
    }

    /// Text tabs with an underline, lighter than the pill toggle above; both boards filter on the same choice.
    private var platformTabs: some View {
        HStack(spacing: 0) {
            ForEach(LeaderboardPlatform.allCases, id: \.self) { p in
                Button { withAnimation(.easeOut(duration: 0.15)) { platform = p } } label: {
                    Text(p.tab).font(ThermoFont.mono(9, weight: platform == p ? .black : .regular)).tracking(0.5)
                        .lineLimit(1).minimumScaleFactor(0.8)
                        .foregroundColor(platform == p ? Forge.phaseMeasured : Forge.ink2)
                        .frame(maxWidth: .infinity).padding(.vertical, 9)
                        .overlay(alignment: .bottom) {
                            Rectangle().fill(platform == p ? Forge.phaseMeasured : Color.clear).frame(height: 2)
                        }
                }
                .accessibilityAddTraits(platform == p ? .isSelected : [])
            }
        }
        .overlay(alignment: .bottom) { Rectangle().fill(Forge.hairline).frame(height: 1) }
        .padding(.horizontal, 16).padding(.top, 6)
    }
}

/** Best benchmark score per phone model, highest first. */
struct BenchmarkBoardView: View {
    let platform: LeaderboardPlatform
    @State private var entries: [BenchmarkDto] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var searchText = ""
    @StateObject private var networkMonitor = NetworkMonitor.shared
    @ObservedObject private var history = BenchmarkHistoryStore.shared
    @State private var submittingId: UUID?
    @State private var submitNote: String?
    @State private var selected: BenchmarkDto?
    // comparing two runs: tap two rows (A, then B), or start from a run's details
    @State private var picking = false
    @State private var picked: [PickedRun] = []
    @State private var comparePair: (PickedRun, PickedRun)?

    private struct PickedRun { let entry: BenchmarkDto; let rank: Int }

    private func pick(_ e: BenchmarkDto, rank: Int) {
        if picked.contains(where: { $0.entry.id == e.id }) { picked.removeAll { $0.entry.id == e.id } } else { picked.append(PickedRun(entry: e, rank: rank)) }
        if picked.count == 2 { withAnimation(.spring()) { comparePair = (picked[0], picked[1]) } }
    }

    private func stopComparing() { comparePair = nil; picking = false; picked = [] }

    /// This device's verified runs that haven't reached the leaderboard yet (made offline, or not submitted).
    private var unsubmitted: [BenchmarkRecord] {
        history.records.filter { $0.version == BenchmarkScore.version && $0.complete && $0.validityFlags == 0 && !$0.submitted }.reversed()
    }

    /// The chosen platform's phones, highest first: ranks and the score bars are relative to these.
    private var onPlatform: [BenchmarkDto] { entries.filter { platform.includes($0.os) } }

    private var shown: [(Int, BenchmarkDto)] {
        let q = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        return onPlatform.enumerated().map { ($0.offset + 1, $0.element) }.filter { _, e in
            q.isEmpty || e.deviceModel.lowercased().contains(q) || e.deviceManufacturer.lowercased().contains(q)
                || (e.gpuName?.lowercased().contains(q) ?? false)
        }
    }

    var body: some View {
        ScrollView(showsIndicators: false) {
            LazyVStack(spacing: 8) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("PHONE BENCHMARK").font(ThermoFont.mono(18, weight: .black)).tracking(1.5).foregroundColor(Forge.ink0)
                        Text("All six sections · best verified run per phone").font(ThermoFont.mono(9)).foregroundColor(Forge.ink0.opacity(0.4))
                    }
                    Spacer()
                    Button {
                        withAnimation(.easeOut(duration: 0.2)) { if picking { stopComparing() } else { picking = true; picked = [] } }
                    } label: {
                        Text(picking ? "✕ CANCEL" : "⇄ COMPARE").font(ThermoFont.mono(9, weight: .black))
                            .foregroundColor(picking ? Forge.bg : Forge.phaseMeasured)
                            .padding(.horizontal, 10).padding(.vertical, 7)
                            .background(picking ? Forge.phaseMeasured : Forge.phaseMeasured.opacity(0.1)).cornerRadius(8)
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Forge.phaseMeasured.opacity(0.5), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    Button { Task { await load() } } label: {
                        Text("↻").font(.system(size: 18, weight: .bold)).foregroundColor(Forge.ink0)
                    }
                    .disabled(isLoading)
                }
                if picking {
                    HStack {
                        Text(picked.first.map { "Now tap the one to compare with \($0.entry.name)" } ?? "Tap two devices to compare them")
                            .font(ThermoFont.mono(10, weight: .bold)).foregroundColor(Forge.ink0)
                        Spacer()
                        Text("\(picked.count)/2").font(ThermoFont.mono(11, weight: .black)).foregroundColor(Forge.phaseMeasured)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 10)
                    .background(Forge.phaseMeasured.opacity(0.08)).cornerRadius(12)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Forge.phaseMeasured.opacity(0.3), lineWidth: 1))
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
                if !unsubmitted.isEmpty { unsubmittedSection }
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
                    Text(!onPlatform.isEmpty ? "No phones match."
                         : platform == .all ? "No benchmark runs yet — be the first." : "No \(platform.name) runs yet.")
                        .font(ThermoFont.mono(11)).foregroundColor(Forge.ink0.opacity(0.4)).padding(30)
                } else {
                    let top = max(onPlatform.first?.totalScore ?? 1, 1)
                    let mine = LeaderboardService.shared.getDeviceModelName()
                    ForEach(shown, id: \.1.id) { rank, e in
                        row(rank, e, fraction: Double(e.totalScore) / Double(top), mine: e.deviceModel.caseInsensitiveCompare(mine) == .orderedSame,
                            pick: picking ? (picked.firstIndex { $0.entry.id == e.id } ?? -1) : nil)
                            .contentShape(Rectangle())
                            .onTapGesture { if picking { pick(e, rank: rank) } else { withAnimation(.spring()) { selected = e } } }
                    }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
        }
        .task { await load() }
        .overlay {
            if let e = selected {
                BenchmarkDetailOverlay(entry: e, onCompare: {
                    let rank = (onPlatform.firstIndex { $0.id == e.id } ?? -1) + 1
                    withAnimation(.spring()) { selected = nil; picking = true; picked = [PickedRun(entry: e, rank: rank)] }
                }) { withAnimation(.spring()) { selected = nil } }.transition(.opacity)
            }
        }
        .overlay {
            if let pair = comparePair {
                BenchmarkCompareOverlay(a: pair.0.entry, rankA: pair.0.rank, b: pair.1.entry, rankB: pair.1.rank) {
                    withAnimation(.spring()) { stopComparing() }
                }
                .transition(.opacity)
            }
        }
    }

    private var unsubmittedSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("YOUR UNSUBMITTED RUNS (\(unsubmitted.count))").font(ThermoFont.mono(11, weight: .black)).foregroundColor(Ramp.at(0.5))
            ForEach(unsubmitted) { r in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(r.score.formatted()).font(ThermoFont.mono(15, weight: .black)).foregroundColor(Forge.ink0)
                        Text(r.timestamp.formatted(date: .abbreviated, time: .shortened)).font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
                    }
                    Spacer()
                    if submittingId == r.id {
                        ProgressView().tint(Forge.phasePreFlight)
                    } else if networkMonitor.isConnected {
                        Button {
                            submittingId = r.id
                            Task {
                                do {
                                    let res = try await BenchmarkUpload.submitAndRemember(r)
                                    submitNote = "Submitted · \(res.score.formatted()) points · rank #\(res.rank)"
                                    await load()
                                } catch { submitNote = error.localizedDescription }
                                submittingId = nil
                            }
                        } label: {
                            Text("SUBMIT").font(ThermoFont.mono(9, weight: .bold)).foregroundColor(Forge.bg)
                                .padding(.horizontal, 12).padding(.vertical, 6).background(Forge.phaseMeasured).cornerRadius(8)
                        }
                    } else {
                        Text("TURN ON INTERNET\nTO SUBMIT").font(ThermoFont.mono(8, weight: .bold)).foregroundColor(Ramp.at(0.5))
                            .multilineTextAlignment(.trailing)
                    }
                }
                .padding(12).background(Ramp.at(0.5).opacity(0.06)).cornerRadius(14)
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Ramp.at(0.5).opacity(0.25), lineWidth: 1))
            }
            if let submitNote { Text(submitNote).font(ThermoFont.mono(9)).foregroundColor(Forge.ink1) }
        }
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

    private func row(_ rank: Int, _ e: BenchmarkDto, fraction: Double, mine: Bool, pick: Int? = nil) -> some View {
        let pickColor: Color? = pick == 0 ? CompareColor.a : pick == 1 ? CompareColor.b : nil
        return VStack(spacing: 8) {
            // the phone and its score share the line; the long list of section scores sits below it, so it can never squeeze the name
            HStack(alignment: .center) {
                Text("#\(rank)").font(ThermoFont.mono(11, weight: .black))
                    .foregroundColor(rank <= 3 ? Ramp.at(1 - Double(rank - 1) * 0.2) : Forge.ink2)
                    .frame(width: 38, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    Text(e.name).font(ThermoFont.mono(12, weight: .bold)).foregroundColor(Forge.ink0).lineLimit(2)
                    Text([e.gpuName, LeaderboardService.osLabel(e.os, e.osVersion)].compactMap { $0 }.joined(separator: " · "))
                        .font(ThermoFont.mono(8)).foregroundColor(Forge.ink0.opacity(0.4)).lineLimit(1)
                }
                Spacer(minLength: 8)
                Text(e.totalScore.formatted()).font(ThermoFont.mono(15, weight: .black)).foregroundColor(Forge.ink0)
                if let pick { PickMark(slot: pick) }
            }
            // each label stays with its number when the line wraps (non-breaking spaces)
            Text("CPU\u{00A0}\(e.cpuScore.formatted()) · MEM\u{00A0}\(e.memScore.formatted()) · GPU\u{00A0}\(e.score.formatted()) · UX\u{00A0}\(e.uxScore.formatted()) · AI\u{00A0}\(e.aiScore.formatted()) · LLM\u{00A0}\(e.llmScore.formatted()) · "
                 + (e.rayTracing ? String(format: "RT\u{00A0}%.0f\u{00A0}FPS", e.test3Fps) : "NO\u{00A0}RT"))
                .font(ThermoFont.mono(7)).foregroundColor(Forge.ink0.opacity(0.4)).lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
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
        .overlay(RoundedRectangle(cornerRadius: 14)
            .stroke(pickColor ?? (mine ? Forge.phaseMeasured.opacity(0.6) : Forge.ink0.opacity(0.06)), lineWidth: pickColor != nil ? 1.5 : 1))
    }
}

/** While picking runs to compare: an empty ring, or the A / B the run was picked as. */
private struct PickMark: View {
    let slot: Int

    var body: some View {
        let color: Color? = slot == 0 ? CompareColor.a : slot == 1 ? CompareColor.b : nil
        ZStack {
            if let color {
                Circle().fill(color)
                Text(slot == 0 ? "A" : "B").font(ThermoFont.mono(10, weight: .black)).foregroundColor(Forge.bg)
            } else {
                Circle().stroke(Forge.ink0.opacity(0.3), lineWidth: 1.5)
            }
        }
        .frame(width: 22, height: 22)
        .padding(.leading, 6)
    }
}
