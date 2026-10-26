import Foundation

/** Plain-text report of a benchmark run — every raw number, for sharing or pasting into a bug report (Android's format). */
enum BenchmarkReport {

    static func text(_ r: BenchmarkResult, device: String, os: String, soc: String, appVersion: String, time: String) -> String {
        var s = ""
        func line(_ t: String) { s += t + "\n" }
        func f(_ v: Double, _ d: Int = 2) -> String { String(format: "%.\(d)f", v) }
        func pts(_ v: Int) -> String { v.formatted(.number.grouping(.automatic)) }
        line("ThermoHammer benchmark report · app \(appVersion) · \(time)")
        line("\(device) · \(LeaderboardService.osLabel(LeaderboardService.osPlatform, os)) · SoC \(soc) · GPU \(r.gpuName.isEmpty ? "?" : r.gpuName)")
        line("TOTAL \(pts(r.total)) = CPU \(pts(r.cpuScore)) + MEM \(pts(r.memScore)) + GPU \(pts(r.gpuScore)) + UX \(pts(r.uxScore)) + AI \(pts(r.aiScore)) + LLM \(pts(r.llmScore)) · validity flags \(r.validityFlags)")
        line("Start · CPU share \(Int((r.startCpuShare * 100).rounded()))% · waited \(r.quietWaitSec) s for background work")
        if let cpu = r.cpu {
            for t in CpuTest.allCases {
                let run = cpu.runs.first { $0.test == t }
                line("CPU \(t.number) \(t.title) · \(pts(cpu.points(t))) pts · \(f(cpu.value(t))) · " +
                     (run.map { $0.rates.map { "\($0.0) \(f($0.1, 1))" }.joined(separator: ", ") } ?? "not run"))
            }
        }
        if let m = r.mem {
            line("MEM 1 RAM · \(pts(m.ramPoints)) pts · read \(f(m.ram.read)), write \(f(m.ram.write)), copy \(f(m.ram.copy)) GB/s · latency \(f(m.ram.latencyNs)) ns")
            line("MEM 2 STORAGE · \(pts(m.storagePoints)) pts · seq read \(f(m.storage.seqRead, 0)), seq write \(f(m.storage.seqWrite, 0)) MB/s · " +
                 "random read \(f(m.storage.randomRead, 0)), random write \(f(m.storage.randomWrite, 0)) IOPS · \(m.storage.fileMb) MB file, " +
                 (m.storage.direct ? "direct I/O" : "cached I/O"))
            line("MEM 3 DATABASE · \(pts(m.dbPoints)) pts · insert \(f(m.db.insert, 0)), update \(f(m.db.update, 0)) tx/s · query \(f(m.db.query, 0))/s")
        }
        for t in GpuTest.allCases {
            guard let g = r.gpu.first(where: { $0.test == t }) else { line("GPU \(t.number) \(t.title) · not run"); continue }
            if !g.supported { line("GPU \(t.number) \(t.title) · 0 pts · not run: \(r.rayTracingProblem ?? "unsupported")"); continue }
            line("GPU \(t.number) \(t.title) · \(pts(g.points)) pts · avg \(f(g.avgFps)) fps · 1% low \(f(g.lowFps)) · stability \(f(Double(g.stabilityPct), 0))% · \(g.frames) frames")
        }
        if let ux = r.ux {
            line("UX 1 WEB BROWSING · \(pts(ux.webPoints)) pts · " + (ux.web.map { "layout \(f($0.layout, 0)), canvas \(f($0.canvas, 0)), css \(f($0.css, 0)), svg \(f($0.svg, 0)), js \(f($0.js, 1))" } ?? "did not finish"))
            line("UX 2 WEB SCROLLING · \(pts(ux.scrollPoints)) pts · " + (ux.scroll.map { "held \(f($0.speed, 0)) px/s, \(f($0.droppedPct, 1))% frames dropped" } ?? "did not finish"))
            line("UX 3 PHOTO GALLERY · \(pts(ux.galleryPoints)) pts · " + (ux.gallery.map { "JPEG \(f($0.jpeg)), WebP \(f($0.webp)), AVIF \(f($0.avif)) MP/s" } ?? "did not finish"))
            line("UX 4 PHOTO EDITING · \(pts(ux.photoPoints)) pts · \(f(ux.photoMps)) MP/s")
            line("UX 5 VIDEO PLAYBACK · \(pts(ux.videoPoints)) pts · " + (ux.video.map { v in v.codecs.map { c in
                c.fps > 0 ? "\(c.clip.label) \(f(c.fps)) fps (\(c.hardware ? "HW" : "SW") \(c.decoder ?? "?"))" : "\(c.clip.label) 0 (\(c.error ?? "unsupported"))"
            }.joined(separator: ", ") } ?? "did not run"))
            if let v = ux.video {
                for c in VideoCodec.allCases { line("  plays " + c.short.padding(toLength: 5, withPad: " ", startingAt: 0) + " " + VideoGrid.text(VideoGrid.playback(v, c))) }
            }
            line("UX 6 VIDEO EXPORT · \(pts(ux.exportPoints)) pts · " + (ux.export.map { e in e.jobs.map { j in
                j.fps > 0 ? "\(j.job.label) \(j.job.res) \(f(j.fps)) fps \(f(j.psnr, 1)) dB\(j.psnr < VideoGrid.minPsnr ? " LOW QUALITY" : "") (\(j.hardware ? "HW" : "SW") \(j.encoder ?? "?"))"
                    : "\(j.job.label) \(j.job.res) 0 (\(j.error ?? "unsupported"))"
            }.joined(separator: ", ") } ?? "did not run"))
            if let e = ux.export {
                for c in [VideoCodec.avc, .hevc, .av1] { line("  exports " + c.short.padding(toLength: 5, withPad: " ", startingAt: 0) + " " + VideoGrid.text(VideoGrid.export(e, c))) }
            }
            line("UX 7 QR SCANNING · \(pts(ux.qrPoints)) pts · " + (ux.qr.map { q in
                "ZXing \(f(q.rate))/s (\(q.missed) missed), \(q.referenceName) " +
                    (q.referenceRate.map { "\(f($0))/s" } ?? "n/a" + (q.referenceError.map { " (\($0))" } ?? ""))
            } ?? "did not run"))
            if let e = ux.error { line("UX error: \(e)") }
        }
        if let ai = r.ai {
            line("AI · \(pts(ai.score)) pts = CPU \(pts(ai.accelScore(.cpu))) · GPU \(pts(ai.accelScore(.gpu))) · NPU \(pts(ai.accelScore(.npu))) · NPU " +
                 (ai.npuRuntime ?? "not run: \(ai.npuProblem ?? "?")"))
            for t in AiTest.allCases {
                var parts: [String] = []
                for a in AiAccel.allCases {
                    let run = ai.runs.first { $0.test == t && $0.accel == a }
                    let ips = run?.ips ?? 0
                    parts.append(ips > 0 ? "\(a.label) \(f(ips))/s" : "\(a.label) 0 (\(run?.error ?? "not run"))")
                }
                line("AI \(t.number) \(t.title) · " + parts.joined(separator: ", "))
            }
        }
        if let llm = r.llm {
            line("LLM · \(pts(llm.score)) pts = prefill \(pts(llm.prefillPoints)) (\(f(llm.prefill)) tok/s) · decode \(pts(llm.decodePoints)) (\(f(llm.decode)) tok/s) · " +
                 "\(f(llm.freeGb)) GB free, largest model \(llm.largestModel)B" + (llm.error.map { " · not run: \($0)" } ?? ""))
            for a in LlmAccel.allCases {
                if let run = llm.run(a), run.decode > 0 {
                    line("LLM \(a.label) · prefill \(f(run.prefill)) tok/s · decode \(f(run.decode)) tok/s · " +
                         (a == .gpu ? (llm.gpuName ?? "?") : "\(run.threads) threads (prompt \(run.batchThreads))"))
                } else {
                    line("LLM \(a.label) · 0 (\(llm.run(a)?.error ?? "not run"))")
                }
            }
        }
        return s
    }
}
