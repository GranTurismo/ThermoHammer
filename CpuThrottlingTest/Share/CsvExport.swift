import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// EXPORT CSV — a run's numbers for a spreadsheet: UTF-8, commas, "." decimals,
// a header row first, no comments. The same columns as the Android and Windows
// apps; a stress run's extra columns are what iOS records per stamp.
// ─────────────────────────────────────────────────────────────────────────────

enum CsvExport {

    /// A field as CSV needs it: quoted when it holds a comma, a quote or a line break.
    static func field(_ s: String) -> String {
        guard s.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) else { return s }
        return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    static func line(_ fields: [String]) -> String { fields.map(field).joined(separator: ",") }

    /// "." decimals and no grouping, whatever the phone's language.
    static func num(_ v: Double, _ decimals: Int = 2) -> String {
        guard v.isFinite else { return "" }
        return String(format: "%.\(decimals)f", locale: Locale(identifier: "en_US_POSIX"), v)
    }

    /// "MATRIX MULTIPLY" → "matrix_multiply".
    static func key(_ s: String) -> String {
        var out = "", sep = false
        for ch in s.lowercased() {
            if ch.isASCII && (ch.isLetter || ch.isNumber) { out.append(ch); sep = false }
            else if !sep && !out.isEmpty { out.append("_"); sep = true }
        }
        while out.hasSuffix("_") { out.removeLast() }
        return out
    }

    // MARK: stress

    private static func attributionName(_ raw: Int) -> String {
        switch Attribution(rawValue: raw) ?? .unknown {
        case .none: return "none"
        case .thermal: return "thermal"
        case .contention: return "contention"
        case .unknown: return "unknown"
        }
    }

    /// This device's stress run from its stamps: the shared four columns, then what iOS records per stamp — the
    /// window, the workers' scheduled share, the whole device's CPU load, the attribution, charger and Low Power Mode,
    /// and every worker's own throughput. thermal_state: 0 nominal · 1 fair · 2 serious · 3 critical.
    static func stress(_ stamps: [StampV2], baseline: Double) -> String {
        let workers = stamps.map(\.ipsPerWorker.count).max() ?? 0
        var out = line(["elapsed_s", "throughput", "percent_of_baseline", "thermal_state",
                        "window_s", "scheduled_pct", "system_load_pct", "attribution", "charging", "low_power_mode"]
                       + (0..<workers).map { "worker_\($0 + 1)_throughput" }) + "\n"
        for s in stamps {
            var f = [num(Double(s.tNs) / 1e9, 3), "\(s.ipsTotal)", baseline > 0 ? num(Double(s.ipsTotal) / baseline * 100) : "",
                     "\(s.thermalState)", num(Double(s.windowNs) / 1e9, 3), num(s.schedFracAvg * 100, 1), num(s.systemLoad * 100, 1),
                     attributionName(s.attribution), s.envFlags & EnvFlag.charging != 0 ? "1" : "0",
                     s.envFlags & EnvFlag.lowPowerMode != 0 ? "1" : "0"]
            f += (0..<workers).map { $0 < s.ipsPerWorker.count ? "\(s.ipsPerWorker[$0])" : "" }
            out += line(f) + "\n"
        }
        return out
    }

    /// A leaderboard stress run from `/stamps/{id}`.
    static func leaderboardStress(_ stamps: [DeviceHammerStamp]) -> String {
        var out = line(["elapsed_s", "score", "thermal_state"]) + "\n"
        for s in stamps { out += line([num(Double(s.elapsedMs) / 1000, 3), "\(s.score)", "\(s.thermalState)"]) + "\n" }
        return out
    }

    // MARK: benchmark

    /// One row per raw number the text report (BenchmarkReport) has, then each section's score and the total.
    static func benchmark(_ r: BenchmarkResult) -> String {
        var rows: [[String]] = []
        func add(_ section: String, _ metric: String, _ value: String, _ unit: String) { rows.append([section, metric, value, unit]) }
        func add(_ section: String, _ metric: String, _ value: Double, _ unit: String, _ d: Int = 2) { add(section, metric, num(value, d), unit) }
        func pts(_ section: String, _ metric: String, _ value: Int) { add(section, metric + "_points", "\(value)", "points") }
        func flag(_ b: Bool) -> String { b ? "1" : "0" }

        add("run", "validity_flags", "\(r.validityFlags)", "flags")
        add("run", "start_cpu_share", Double(r.startCpuShare) * 100, "%", 0)
        add("run", "quiet_wait", "\(r.quietWaitSec)", "s")

        if let cpu = r.cpu {
            for t in CpuTest.allCases {
                let test = key(t.title)
                pts("cpu", test, cpu.points(t))
                add("cpu", test + "_geomean", cpu.value(t), "ops/s")
                for (kernel, rate) in cpu.runs.first(where: { $0.test == t })?.rates ?? [] {
                    add("cpu", test + "_" + key(kernel), rate, "ops/s")
                }
            }
        }
        if let m = r.mem {
            pts("mem", "ram", m.ramPoints)
            add("mem", "ram_read", m.ram.read, "GB/s"); add("mem", "ram_write", m.ram.write, "GB/s")
            add("mem", "ram_copy", m.ram.copy, "GB/s"); add("mem", "ram_latency", m.ram.latencyNs, "ns")
            pts("mem", "storage", m.storagePoints)
            add("mem", "storage_seq_read", m.storage.seqRead, "MB/s"); add("mem", "storage_seq_write", m.storage.seqWrite, "MB/s")
            add("mem", "storage_random_read", m.storage.randomRead, "IOPS"); add("mem", "storage_random_write", m.storage.randomWrite, "IOPS")
            add("mem", "storage_file", "\(m.storage.fileMb)", "MB"); add("mem", "storage_direct_io", flag(m.storage.direct), "bool")
            pts("mem", "database", m.dbPoints)
            add("mem", "database_insert", m.db.insert, "tx/s"); add("mem", "database_update", m.db.update, "tx/s")
            add("mem", "database_query", m.db.query, "queries/s")
        }
        for t in GpuTest.allCases {
            let test = key(t.title)
            guard let g = r.gpu.first(where: { $0.test == t }) else { continue }
            add("gpu", test + "_supported", flag(g.supported), "bool")
            guard g.supported else { continue }
            pts("gpu", test, g.points)
            add("gpu", test + "_avg_fps", g.avgFps, "fps"); add("gpu", test + "_1pct_low_fps", g.lowFps, "fps")
            add("gpu", test + "_stability", Double(g.stabilityPct), "%", 0); add("gpu", test + "_frames", "\(g.frames)", "frames")
        }
        if let ux = r.ux {
            pts("ux", "web_browsing", ux.webPoints)
            if let w = ux.web {
                add("ux", "web_layout", w.layout, "score", 0); add("ux", "web_canvas", w.canvas, "score", 0)
                add("ux", "web_css", w.css, "score", 0); add("ux", "web_svg", w.svg, "score", 0); add("ux", "web_js", w.js, "ops/s", 1)
            }
            pts("ux", "web_scrolling", ux.scrollPoints)
            if let s = ux.scroll { add("ux", "scroll_speed", s.speed, "px/s", 0); add("ux", "scroll_dropped_frames", s.droppedPct, "%", 1) }
            pts("ux", "photo_gallery", ux.galleryPoints)
            if let g = ux.gallery {
                add("ux", "gallery_jpeg", g.jpeg, "MP/s"); add("ux", "gallery_webp", g.webp, "MP/s"); add("ux", "gallery_avif", g.avif, "MP/s")
            }
            pts("ux", "photo_editing", ux.photoPoints)
            add("ux", "photo_editing", ux.photoMps, "MP/s")
            pts("ux", "video_playback", ux.videoPoints)
            for c in ux.video?.codecs ?? [] {
                let clip = "video_" + key(c.clip.codec.short) + "_" + c.clip.res.lowercased()
                add("ux", clip, c.fps, "fps"); add("ux", clip + "_hardware", flag(c.hardware && c.fps > 0), "bool")
            }
            pts("ux", "video_export", ux.exportPoints)
            for j in ux.export?.jobs ?? [] {
                let job = "export_" + key(j.job.label) + "_" + j.job.res.lowercased()
                add("ux", job, j.fps, "fps"); add("ux", job + "_psnr", j.psnr, "dB")
                add("ux", job + "_hardware", flag(j.hardware && j.fps > 0), "bool")
            }
            pts("ux", "qr_scanning", ux.qrPoints)
            if let q = ux.qr {
                add("ux", "qr_zxing", q.rate, "scans/s"); add("ux", "qr_missed", "\(q.missed)", "codes")
                if let ref = q.referenceRate { add("ux", "qr_" + key(q.referenceName), ref, "scans/s") }
            }
        }
        if let ai = r.ai {
            for a in AiAccel.allCases { pts("ai", key(a.label), ai.accelScore(a)) }
            for t in AiTest.allCases {
                for a in AiAccel.allCases { add("ai", key(t.title) + "_" + key(a.label), ai.ips(t, a), "inferences/s") }
            }
        }
        if let llm = r.llm {
            pts("llm", "prefill", llm.prefillPoints); pts("llm", "decode", llm.decodePoints)
            add("llm", "prefill", llm.prefill, "tokens/s"); add("llm", "decode", llm.decode, "tokens/s")
            for a in LlmAccel.allCases {
                let run = llm.run(a)
                add("llm", key(a.label) + "_prefill", run?.prefill ?? 0, "tokens/s"); add("llm", key(a.label) + "_decode", run?.decode ?? 0, "tokens/s")
                if a == .cpu, let run { add("llm", "cpu_threads", "\(run.threads)", "threads") }
            }
            add("llm", "free_memory", llm.freeGb, "GB"); add("llm", "largest_model", "\(llm.largestModel)", "billion parameters")
        }
        for s in SectionScore.of(r) { add("score", s.key, "\(s.points)", "points") }
        add("score", "total", "\(r.total)", "points")

        return ([line(["section", "metric", "value", "unit"])] + rows.map(line)).joined(separator: "\n") + "\n"
    }
}
