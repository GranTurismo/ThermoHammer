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
                    verifiedPill
                    HStack(spacing: 4) {
                        section("CPU", result.cpuScore, BenchColor.cpu, ran: result.cpu != nil)
                        section("MEM", result.memScore, BenchColor.mem, ran: result.mem != nil)
                        section("GPU", result.gpuScore, Forge.phaseCalibration, ran: !result.gpu.isEmpty)
                        section("UX", result.uxScore, Forge.phaseMeasured, ran: result.ux != nil)
                        section("AI", result.aiScore, BenchColor.ai, ran: result.ai != nil)
                        section("LLM", result.llmScore, BenchColor.llm, ran: result.llm != nil)
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
                    HealthCard(result: result, record: record)
                    if let cpu = result.cpu {
                        ForEach(CpuTest.allCases, id: \.self) { t in cpuCard(t, cpu) }
                    }
                    if let mem = result.mem { memCards(mem) }
                    ForEach(result.gpu, id: \.test) { gpuCard($0) }
                    if let ux = result.ux { uxCards(ux) }
                    if let ai = result.ai { aiCards(ai) }
                    if let llm = result.llm { llmCard(llm) }
                    buttons
                }
                .padding(18)
                .background(Forge.raised)
                .cornerRadius(18)
                .padding(.horizontal, 14).padding(.vertical, 30)
            }
        }
    }

    /** Verified: none of the validity flags — scored on the server, eligible for the leaderboard. */
    private var verifiedPill: some View {
        let ok = result.isVerified && result.isComplete
        let color = ok ? Forge.phaseMeasured : Ramp.at(0.5)
        return HStack(spacing: 0) {
            Text(ok ? "✓ VERIFIED RUN" : "UNVERIFIED RUN").font(ThermoFont.mono(9, weight: .bold))
            Text(ok ? " · SCORED ON OUR SERVER" : " · NOT FOR THE LEADERBOARD").font(ThermoFont.mono(8)).opacity(0.75)
        }
        .foregroundColor(color)
        .padding(.horizontal, 12).padding(.vertical, 5)
        .background(color.opacity(0.14))
        .clipShape(Capsule())
        .overlay(Capsule().stroke(color.opacity(0.5), lineWidth: 1))
    }

    private func section(_ label: String, _ value: Int, _ color: Color, ran: Bool) -> some View {
        VStack(spacing: 2) {
            Text(ran ? value.formatted() : "—").font(ThermoFont.mono(13, weight: .black)).foregroundColor(ran ? color : Forge.ink2)
                .lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(ThermoFont.mono(8)).foregroundColor(Forge.ink2)
        }
        .frame(maxWidth: .infinity)
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

    /** One clip or export: its rate, what it is, how it ran — a column of equal width, so four never run together. */
    private func codecMetric(_ value: String, _ name: String, _ how: String, _ ok: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(ThermoFont.mono(12, weight: .bold)).foregroundColor(ok ? Forge.ink0 : Ramp.at(0.85))
            Text(name).font(ThermoFont.mono(7)).foregroundColor(Forge.ink2).lineLimit(1)
            Text(how).font(ThermoFont.mono(7)).foregroundColor(ok ? Forge.ink1 : Ramp.at(0.85)).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /** Codec rows × 4K30 … 8K240: ✓ real time with 10 % to spare, – too slow, ✗ can't, · not tested. */
    private func gridTable(_ rows: [(String, [VideoGrid.Cell])], _ accent: Color) -> some View {
        VStack(spacing: 3) {
            HStack(spacing: 0) {
                Spacer().frame(width: 40)
                ForEach(VideoGrid.tiers.indices, id: \.self) { i in
                    Text(VideoGrid.tiers[i].label).font(ThermoFont.mono(7)).foregroundColor(Forge.ink2).lineLimit(1).minimumScaleFactor(0.7).frame(maxWidth: .infinity)
                }
            }
            ForEach(rows.indices, id: \.self) { r in
                HStack(spacing: 0) {
                    Text(rows[r].0).font(ThermoFont.mono(9, weight: .bold)).foregroundColor(Forge.ink1).frame(width: 40, alignment: .leading)
                    ForEach(rows[r].1.indices, id: \.self) { i in
                        let c = rows[r].1[i]
                        Text(c.rawValue).font(ThermoFont.mono(11, weight: .black)).frame(maxWidth: .infinity)
                            .foregroundColor(c == .yes ? accent : c == .no ? Forge.ink2 : c == .unsupported ? Ramp.at(0.85) : Forge.ink2.opacity(0.4))
                    }
                }
            }
        }
        .padding(.top, 4)
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
                ForEach([false, true], id: \.self) { k8 in
                    HStack(alignment: .top, spacing: 4) {
                        ForEach(v.codecs.filter { $0.clip.k8 == k8 }, id: \.clip) { c in
                            codecMetric(c.fps > 0 ? String(format: "%.0f", c.fps) : "—", "\(c.clip.res) \(c.codec.short)",
                                        c.fps > 0 ? (c.hardware ? "HW" : "SW") : "N/A", c.fps > 0)
                        }
                        if k8 { Spacer().frame(maxWidth: .infinity); Spacer().frame(maxWidth: .infinity) }   // 8K: two clips under four
                    }
                }
                gridTable(VideoCodec.allCases.map { ($0.short, VideoGrid.playback(v, $0)) }, BenchColor.ux(.video))
            }
            note("Frames per second decoding each clip flat out (an unsupported one 0); 4K counts three quarters, 8K one. The grid: what plays in real time with 10 % to spare.")
        }
        uxCard(.export, ux.exportPoints) {
            if let e = ux.export {
                HStack(alignment: .top, spacing: 4) {
                    ForEach(e.jobs, id: \.job) { j in
                        let ok = j.fps > 0 && j.psnr >= VideoGrid.minPsnr
                        codecMetric(j.fps > 0 ? String(format: "%.0f", j.fps) : "—", "\(j.job.res) \(j.job.label)",
                                    j.fps <= 0 ? "N/A" : (j.hardware ? "HW · " : "SW · ") + String(format: ok ? "%.1f dB" : "LOW %.1f dB", j.psnr), ok)
                    }
                }
                gridTable([VideoCodec.avc, .hevc, .av1].map { ($0.short, VideoGrid.export(e, $0)) }, BenchColor.ux(.export))
            }
            note("Frames per second exporting a graded edit; each export is decoded again and compared with what went in — below \(Int(VideoGrid.minPsnr)) dB it doesn't count.")
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

    private func tokensPerS(_ v: Double) -> String {
        v > 0 ? (v < 100 ? String(format: "%.1f", v) : v.formatted(.number.precision(.fractionLength(0)))) : "—"
    }

    @ViewBuilder private func llmCard(_ llm: LlmResult) -> some View {
        let missing = Ramp.at(0.85)
        card("LLM · LLAMA 3.2 3B · 4-BIT", "PROMPT · REPLY", llm.score, BenchColor.llm) {
            ForEach(LlmAccel.allCases, id: \.self) { a in
                let run = llm.run(a)
                HStack {
                    metric("\(a.label) PREFILL TOK/S", tokensPerS(run?.prefill ?? 0), (run?.prefill ?? 0) > 0 ? Forge.ink0 : missing); Spacer()
                    metric("\(a.label) DECODE TOK/S", tokensPerS(run?.decode ?? 0), (run?.decode ?? 0) > 0 ? Forge.ink0 : missing); Spacer()
                    metric(a == .cpu ? "THREADS" : "GPU", a == .cpu ? (run.map { $0.threads > 0 ? "\($0.threads)" : "—" } ?? "—")
                           : String((llm.gpuName ?? "—").prefix(14)), Forge.ink1)
                }
                if llm.error == nil, let e = run?.error { note("\(a.label): \(e)", missing) }
            }
            if let e = llm.error { note("Not run: \(e).", missing) }
            note(llm.largestModel > 0
                 ? String(format: "Largest common model that fits in the memory iOS lets the app take now: %dB (4-bit, %.1f GB).", llm.largestModel, llm.freeGb)
                 : String(format: "Memory the app can take now: %.1f GB — too little for even a 1B model.", llm.freeGb), Forge.ink1)
            note(String(format: "Prefill reads a 512-token prompt; decode writes the reply token by token. The faster of the CPU and the GPU counts for each: %.0f and %.1f tokens/s = 6000 points. The weights come from a seed — the words are random, the work is real.",
                        LlmScore.refPrefill, LlmScore.refDecode))
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
        if flags & BenchFlag.interference != 0 {
            notes.append(LeaderboardService.isMac ? "another app was in front during the run, or the Mac was busy"
                         : "the phone was busy with background work")
        }
        return "Unverified: " + notes.joined(separator: ", ") + "."
    }
}
