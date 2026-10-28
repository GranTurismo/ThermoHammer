import SwiftUI

/** One leaderboard run, as GET /benchmark/{id} returns it: every section's numbers already formatted by the server. */
struct BenchmarkDetail: Decodable {
    struct Row: Decodable { let label: String; let value: String }
    struct Group: Decodable { let title: String; let points: Int?; let rows: [Row] }
    struct Section: Decodable, Identifiable {
        let key: String
        let title: String
        let score: Int
        let groups: [Group]
        var id: String { key }
    }

    let id: Int
    let createdAt: String
    let deviceManufacturer: String
    let deviceModel: String
    let socModel: String?
    let gpuName: String?
    let os: Int
    let osVersion: String
    let osBuild: String?
    let appVersion: String?
    let totalScore: Int
    let sections: [Section]

    /// UTC time of the run, "27 Oct 2026" — the server sends .NET's 7 fractional digits, which are dropped here.
    var date: String? {
        let f = ISO8601DateFormatter()
        guard let d = f.date(from: String(createdAt.prefix(19)) + "Z") else { return nil }
        return d.formatted(date: .abbreviated, time: .omitted)
    }
}

extension BenchmarkUpload {
    /// Nil when the run is gone from the board (404): unverified runs are never served.
    static func fetchDetail(id: Int) async throws -> BenchmarkDetail? {
        let (data, response) = try await URLSession.shared.data(from: baseURL.appendingPathComponent("benchmark/\(id)"))
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 404 { return nil }
        guard (200...299).contains(status) else {
            throw NSError(domain: "BenchmarkUpload", code: status, userInfo: [NSLocalizedDescriptionKey: "Run unavailable (HTTP \(status))"])
        }
        return try JSONDecoder().decode(BenchmarkDetail.self, from: data)
    }
}

/** A benchmark leaderboard row opened: the device and its six sections, each a card that unfolds into its groups and rows. */
struct BenchmarkDetailOverlay: View {
    let entry: BenchmarkDto
    let onClose: () -> Void
    @State private var detail: BenchmarkDetail?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var gone = false
    @State private var expanded: Set<String> = []

    private func accent(_ key: String) -> Color {
        switch key {
        case "cpu": return BenchColor.cpu
        case "mem": return BenchColor.mem
        case "gpu": return Forge.phaseCalibration
        case "ai": return BenchColor.ai
        case "llm": return BenchColor.llm
        default: return Forge.phaseMeasured
        }
    }

    var body: some View {
        ZStack {
            Color.black.opacity(0.85).ignoresSafeArea().onTapGesture(perform: onClose)
            VStack(spacing: 14) {
                header
                Divider().background(Forge.hairline)
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(spacing: 10) {
                        if isLoading {
                            ProgressView().tint(Forge.phasePreFlight).padding(40)
                        } else if gone {
                            Text("This run is no longer on the board.")
                                .font(ThermoFont.mono(11)).foregroundColor(Forge.ink0.opacity(0.4)).padding(30)
                        } else if let errorMessage {
                            VStack(spacing: 8) {
                                Text("⚠ \(errorMessage)").font(ThermoFont.mono(12, weight: .bold)).foregroundColor(Ramp.at(0.85))
                                    .multilineTextAlignment(.center)
                                Button { Task { await load() } } label: {
                                    Text("RETRY").font(ThermoFont.mono(10)).foregroundColor(Forge.ink0)
                                        .padding(.horizontal, 14).padding(.vertical, 6).background(Forge.ink0.opacity(0.1)).cornerRadius(8)
                                }
                            }
                            .padding(30)
                        } else if let detail {
                            ForEach(detail.sections) { sectionCard($0) }
                        }
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: Forge.columnWidth, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 24).fill(Forge.raised)
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Forge.hairline, lineWidth: 1)))
            .padding(16)
        }
        .task { await load() }
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.name).font(ThermoFont.mono(16, weight: .black)).foregroundColor(Forge.ink0)
                let d = detail
                let os = LeaderboardService.osLabel(d?.os ?? entry.os, d?.osVersion ?? entry.osVersion)
                Text([d?.socModel ?? entry.socModel, d?.gpuName ?? entry.gpuName, os, d?.appVersion.map { "app \($0)" }, d?.date]
                        .compactMap { $0 }.joined(separator: " · "))
                    .font(ThermoFont.mono(9)).foregroundColor(Forge.ink0.opacity(0.4))
                Text((d?.totalScore ?? entry.totalScore).formatted()).font(ThermoFont.mono(30, weight: .black)).foregroundColor(Forge.ink0)
                Text("TOTAL POINTS").font(ThermoFont.mono(7)).foregroundColor(Forge.ink2)
            }
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundColor(Forge.ink0)
                    .frame(width: 32, height: 32).background(Forge.ink0.opacity(0.08)).clipShape(Circle())
            }
        }
    }

    private func sectionCard(_ s: BenchmarkDetail.Section) -> some View {
        let color = accent(s.key)
        let open = expanded.contains(s.key)
        return VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation(.easeOut(duration: 0.2)) { if open { expanded.remove(s.key) } else { expanded.insert(s.key) } }
            } label: {
                HStack {
                    Text(s.title).font(ThermoFont.mono(13, weight: .black)).foregroundColor(color)
                    Spacer()
                    Text(s.score.formatted()).font(ThermoFont.mono(16, weight: .black)).foregroundColor(Forge.ink0)
                    Image(systemName: "chevron.down").font(.system(size: 10, weight: .bold)).foregroundColor(Forge.ink2)
                        .rotationEffect(.degrees(open ? 180 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open {
                ForEach(Array(s.groups.enumerated()), id: \.offset) { _, g in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(g.title.uppercased()).font(ThermoFont.mono(9, weight: .bold)).foregroundColor(Forge.ink1)
                            Spacer()
                            if let p = g.points { Text("\(p.formatted()) PTS").font(ThermoFont.mono(9, weight: .bold)).foregroundColor(color) }
                        }
                        ForEach(Array(g.rows.enumerated()), id: \.offset) { _, r in
                            HStack(alignment: .top) {
                                Text(r.label).font(ThermoFont.mono(10)).foregroundColor(Forge.ink2)
                                Spacer(minLength: 12)
                                Text(r.value).font(ThermoFont.mono(10, weight: .bold)).foregroundColor(Forge.ink0).multilineTextAlignment(.trailing)
                            }
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Forge.surface)
        .cornerRadius(14)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(color.opacity(0.3), lineWidth: 1))
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        gone = false
        do {
            if let d = try await BenchmarkUpload.fetchDetail(id: entry.id) { detail = d } else { gone = true }
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}
