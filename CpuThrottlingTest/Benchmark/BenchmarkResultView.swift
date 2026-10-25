import SwiftUI
import UIKit

/** The finished benchmark: total, section scores, every test's raw numbers; copy the report or submit the run. */
struct BenchmarkResultView: View {
    let result: BenchmarkResult
    let record: BenchmarkRecord?
    let onDismiss: () -> Void

    @State private var submitting = false
    @State private var submitMessage: String?
    @State private var copied = false

    var body: some View {
        ForgeOverlayScrim {
            ScrollView(showsIndicators: false) {
                VStack(spacing: 12) {
                    Text("THERMOHAMMER BENCHMARK").font(ThermoFont.mono(9, weight: .bold)).tracking(2).foregroundColor(Forge.ink2)
                    Text(result.total.formatted()).font(ThermoFont.mono(46, weight: .black)).foregroundColor(Forge.ink0)
                    Text("TOTAL POINTS").font(ThermoFont.mono(9)).tracking(1.5).foregroundColor(Forge.ink2)
                    HStack(spacing: 14) {
                        section("CPU", result.cpuScore, BenchColor.cpu, ran: result.cpu != nil)
                        section("MEM", result.memScore, BenchColor.mem, ran: result.mem != nil)
                        section("GPU", result.gpuScore, Forge.phaseCalibration, ran: !result.gpu.isEmpty)
                        section("UX", result.uxScore, Forge.phaseMeasured, ran: result.ux != nil)
                        section("AI", result.aiScore, BenchColor.ai, ran: result.ai != nil)
                    }
                    Text("\(LeaderboardService.shared.getDeviceModelName()) · \(result.gpuName)")
                        .font(ThermoFont.mono(10, weight: .bold)).foregroundColor(Forge.ink1)
                    if !result.isComplete {
                        Text("Not every section ran — this run can't go to the leaderboard.")
                            .font(ThermoFont.mono(9)).foregroundColor(Ramp.at(0.5)).multilineTextAlignment(.center)
                    }
                    if !result.isVerified {
                        Text(validityNote(result.validityFlags)).font(ThermoFont.mono(9)).foregroundColor(Ramp.at(0.5)).multilineTextAlignment(.center)
                    }
                    if let cpu = result.cpu {
                        ForEach(CpuTest.allCases, id: \.self) { t in cpuCard(t, cpu) }
                    }
                    if let mem = result.mem { memCards(mem) }
                    ForEach(result.gpu, id: \.test) { gpuCard($0) }
                    if let ux = result.ux { uxCards(ux) }
                    if let ai = result.ai { aiCards(ai) }
                    buttons
                }
                .padding(18)
                .background(Forge.raised)
                .cornerRadius(18)
                .padding(.horizontal, 14).padding(.vertical, 30)
            }
        }
    }

    private func section(_ label: String, _ value: Int, _ color: Color, ran: Bool) -> some View {
        VStack(spacing: 2) {
            Text(ran ? value.formatted() : "—").font(ThermoFont.mono(14, weight: .black)).foregroundColor(ran ? color : Forge.ink2)
            Text(label).font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
        }
    }

    private func card<Content: View>(_ label: String, _ title: String, _ points: Int, _ accent: Color, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(ThermoFont.mono(9, weight: .bold)).foregroundColor(accent)
                    Text(title).font(ThermoFont.mono(13, weight: .black)).foregroundColor(Forge.ink0)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(points.formatted()).font(ThermoFont.mono(16, weight: .black)).foregroundColor(Forge.ink0)
                    Text("POINTS").font(ThermoFont.mono(7)).foregroundColor(Forge.ink2)
                }
            }
            content()
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Forge.surface)
        .cornerRadius(14)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(accent.opacity(0.3), lineWidth: 1))
    }

    private func cpuCard(_ t: CpuTest, _ cpu: CpuResult) -> some View {
        card("CPU TEST \(t.number)" + (t.multiCore ? " · ALL CORES" : ""), t.title, cpu.points(t), BenchColor.cpu) {
            if let run = cpu.runs.first(where: { $0.test == t }) {
                Text(run.rates.map { "\($0.0) " + ($0.1 < 100 ? String(format: "%.1f", $0.1) : $0.1.formatted(.number.precision(.fractionLength(0)))) }
                        .joined(separator: "  ·  ") + "  ops/s")
                    .font(ThermoFont.mono(8)).foregroundColor(Forge.ink1)
            }
        }
    }

    private func gpuCard(_ g: GpuTestResult) -> some View {
        card("GPU TEST \(g.test.number)", g.test.title, g.points, BenchColor.gpu(g.test)) {
            if g.supported {
                HStack {
                    metric("AVG FPS", String(format: "%.1f", g.avgFps)); Spacer()
                    metric("1% LOW", String(format: "%.1f", g.lowFps)); Spacer()
                    metric("STABILITY", String(format: "%.0f%%", g.stabilityPct)); Spacer()
                    metric("FRAMES", "\(g.frames)")
                }
            } else {
                Text("Not run: \(result.rayTracingProblem ?? "the GPU has no ray tracing") — 0 points.")
                    .font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
            }
        }
    }

    private func metric(_ label: String, _ value: String, _ color: Color = Forge.ink0) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(ThermoFont.mono(12, weight: .bold)).foregroundColor(color)
            Text(label).font(ThermoFont.mono(7)).foregroundColor(Forge.ink2)
        }
    }

    @ViewBuilder private func memCards(_ m: MemResult) -> some View {
        card("MEM TEST 1", "RAM", m.ramPoints, BenchColor.mem) {
            HStack {
                metric("READ GB/S", String(format: "%.1f", m.ram.read)); Spacer()
                metric("WRITE GB/S", String(format: "%.1f", m.ram.write)); Spacer()
                metric("COPY GB/S", String(format: "%.1f", m.ram.copy)); Spacer()
                metric("LATENCY NS", String(format: "%.0f", m.ram.latencyNs))
            }
        }
        card("MEM TEST 2", "STORAGE", m.storagePoints, BenchColor.mem) {
            HStack {
                metric("SEQ READ MB/S", m.storage.seqRead.formatted(.number.precision(.fractionLength(0)))); Spacer()
                metric("SEQ WRITE MB/S", m.storage.seqWrite.formatted(.number.precision(.fractionLength(0))))
            }
            HStack {
                metric("RANDOM READ IOPS", m.storage.randomRead.formatted(.number.precision(.fractionLength(0)))); Spacer()
                metric("RANDOM WRITE IOPS", m.storage.randomWrite.formatted(.number.precision(.fractionLength(0))))
            }
            Text("\(m.storage.fileMb) MB test file, deleted afterwards. 4 MB sequential blocks; random 4 KB blocks from 4 threads. " +
                 (m.storage.direct ? "Direct I/O." : "The file system cached the reads."))
                .font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
        }
        card("MEM TEST 3", "DATABASE", m.dbPoints, BenchColor.mem) {
            HStack {
                metric("MESSAGES SAVED/S", m.db.insert.formatted(.number.precision(.fractionLength(0)))); Spacer()
                metric("MARKED READ/S", m.db.update.formatted(.number.precision(.fractionLength(0)))); Spacer()
                metric("CHATS OPENED/S", m.db.query.formatted(.number.precision(.fractionLength(0))))
            }
        }
    }

    private func note(_ text: String, _ color: Color = Forge.ink2) -> some View {
        Text(text).font(ThermoFont.mono(8)).foregroundColor(color)
    }

    private func uxCard<Content: View>(_ t: UxTest, _ points: Int, @ViewBuilder content: () -> Content) -> some View {
        card("UX TEST \(t.number)", t.title, points, BenchColor.ux(t), content: content)
    }

    @ViewBuilder private func uxCards(_ ux: UxResult) -> some View {
        let missing = Ramp.at(0.85)
        uxCard(.web, ux.webPoints) {
            if let w = ux.web {
                HStack {
                    metric("LAYOUT", String(format: "%.0f", w.layout)); Spacer()
                    metric("CANVAS", String(format: "%.0f", w.canvas)); Spacer()
                    metric("CSS", String(format: "%.0f", w.css)); Spacer()
                    metric("SVG", String(format: "%.0f", w.svg)); Spacer()
                    metric("JS OPS/S", String(format: "%.0f", w.js), Forge.ink1)
                }
            } else { note("The WebKit test didn't finish on this phone.", Forge.ink1) }
        }
        uxCard(.scroll, ux.scrollPoints) {
            if let r = ux.scroll {
                HStack {
                    metric("SPEED HELD PX/S", r.speed.formatted(.number.precision(.fractionLength(0)))); Spacer()
                    metric("DROPPED FRAMES", String(format: "%.1f%%", r.droppedPct), Forge.ink1)
                }
            } else { note("The scrolling test didn't finish on this phone.", Forge.ink1) }
        }
        uxCard(.gallery, ux.galleryPoints) {
            if let r = ux.gallery {
                HStack {
                    metric("JPEG MP/S", String(format: "%.1f", r.jpeg)); Spacer()
                    metric("WEBP MP/S", String(format: "%.1f", r.webp)); Spacer()
                    metric("AVIF MP/S", r.avif > 0 ? String(format: "%.1f", r.avif) : "—", r.avif > 0 ? Forge.ink0 : missing)
                }
            } else { note("The gallery test didn't finish on this phone.", Forge.ink1) }
        }
        uxCard(.photo, ux.photoPoints) {
            metric("MEGAPIXELS EDITED PER SECOND", String(format: "%.1f", ux.photoMps))
            note("Fisheye lens, blur, portrait bokeh, sharpening and a colour grade on a 12 MP photo, on the GPU.")
        }
        uxCard(.video, ux.videoPoints) {
            if let v = ux.video {
                HStack {
                    ForEach(v.codecs, id: \.codec) { c in
                        metric(c.codec.short + (c.fps > 0 ? (c.hardware ? " · HW" : " · SW") : " · N/A"),
                               c.fps > 0 ? String(format: "%.0f", c.fps) : "—", c.fps > 0 ? Forge.ink0 : missing)
                        if c.codec != v.codecs.last?.codec { Spacer() }
                    }
                }
            }
            note("Frames per second decoding 4K60 clips flat out; \(Int(UxScore.refVideo)) fps per codec = 6000 points, an unsupported codec 0.")
        }
        uxCard(.qr, ux.qrPoints) {
            if let q = ux.qr {
                HStack {
                    metric("ZXING SCANS/S", String(format: "%.1f", q.rate)); Spacer()
                    metric("MISSED", "\(q.missed)", q.missed > 0 ? missing : Forge.ink1); Spacer()
                    metric("\(q.referenceName.uppercased()) SCANS/S", q.referenceRate.map { String(format: "%.1f", $0) } ?? "—", Forge.ink1)
                }
                note("\(q.referenceName) (Apple's scanner) is shown for reference and not scored.")
            }
        }
    }

    @ViewBuilder private func aiCards(_ ai: AiResult) -> some View {
        let missing = Ramp.at(0.85)
        card("AI · 6 MODELS · INT8", "CPU · GPU · NPU", ai.score, BenchColor.ai) {
            HStack {
                ForEach(AiAccel.allCases, id: \.self) { a in
                    let pts = ai.accelScore(a)
                    metric("\(a.label) POINTS", pts > 0 ? pts.formatted() : "—", pts > 0 ? Forge.ink0 : missing)
                    if a != .npu { Spacer() }
                }
            }
            note(ai.npuRuntime.map { "NPU: \($0). CPU, GPU and NPU count a third each." }
                 ?? "NPU: \(ai.npuProblem ?? "not available") — 0 points there.")
        }
        ForEach(AiTest.allCases, id: \.self) { t in
            VStack(alignment: .leading, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("AI TEST \(t.number)").font(ThermoFont.mono(9, weight: .bold)).foregroundColor(BenchColor.ai)
                    Text(t.title).font(ThermoFont.mono(13, weight: .black)).foregroundColor(Forge.ink0)
                    Text(t.model).font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
                }
                HStack {
                    ForEach(AiAccel.allCases, id: \.self) { a in
                        let ips = ai.ips(t, a)
                        metric("\(a.label) INF/S", ips > 0 ? (ips < 100 ? String(format: "%.1f", ips) : ips.formatted(.number.precision(.fractionLength(0)))) : "—",
                               ips > 0 ? Forge.ink0 : missing)
                        if a != .npu { Spacer() }
                    }
                }
                ForEach(ai.runs.filter { $0.test == t && $0.error != nil && $0.accel != .npu }, id: \.accel) { run in
                    note("\(run.accel.label): \(run.error ?? "")", missing)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Forge.surface)
            .cornerRadius(14)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(BenchColor.ai.opacity(0.3), lineWidth: 1))
        }
    }

    private var buttons: some View {
        VStack(spacing: 10) {
            if let submitMessage {
                Text(submitMessage).font(ThermoFont.mono(9)).foregroundColor(Forge.ink1).multilineTextAlignment(.center)
            }
            HStack(spacing: 10) {
                Button {
                    UIPasteboard.general.string = BenchmarkReport.text(result, device: LeaderboardService.shared.getDeviceModelName(),
                        os: UIDevice.current.systemVersion + (StressEngine.osBuild().map { " (\($0))" } ?? ""),
                        soc: BenchmarkUpload.chipName() ?? "?",
                        appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?",
                        time: Date().formatted(date: .numeric, time: .shortened))
                    copied = true
                    ThermoHaptics.tap()
                } label: {
                    Text(copied ? "COPIED" : "COPY REPORT").font(ThermoFont.mono(11, weight: .black)).foregroundColor(Forge.ink0)
                        .frame(maxWidth: .infinity).padding(.vertical, 12).background(Forge.interact).cornerRadius(10)
                }
                Button(action: onDismiss) {
                    Text("DONE").font(ThermoFont.mono(11, weight: .black)).foregroundColor(Forge.ink0)
                        .frame(maxWidth: .infinity).padding(.vertical, 12).background(Forge.interact).cornerRadius(10)
                }
            }
            if let record, record.complete {
                Button {
                    submitting = true
                    Task {
                        do {
                            let r = try await BenchmarkUpload.submit(record)
                            BenchmarkHistoryStore.shared.markSubmitted(record.id)
                            submitMessage = "Submitted · \(r.score.formatted()) points · rank #\(r.rank)"
                        } catch {
                            submitMessage = error.localizedDescription
                        }
                        submitting = false
                    }
                } label: {
                    Text(submitting ? "SUBMITTING…" : "SUBMIT TO LEADERBOARD").font(ThermoFont.mono(12, weight: .black)).foregroundColor(Forge.bg)
                        .frame(maxWidth: .infinity).padding(.vertical, 14).background(Forge.phaseMeasured).cornerRadius(12)
                }
                .disabled(submitting)
            }
        }
        .padding(.top, 6)
    }

    private func validityNote(_ flags: Int) -> String {
        var notes: [String] = []
        if flags & BenchFlag.powerEvent != 0 { notes.append("charger connected during the run") }
        if flags & BenchFlag.envCapped != 0 { notes.append("Low Power Mode was on") }
        if flags & (BenchFlag.cooldownTimeout | BenchFlag.warmStarted) != 0 { notes.append("started warm") }
        if flags & BenchFlag.interference != 0 { notes.append("the phone was busy with background work") }
        return "Unverified: " + notes.joined(separator: ", ") + "."
    }
}
