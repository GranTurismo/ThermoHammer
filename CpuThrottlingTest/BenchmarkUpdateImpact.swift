import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK UPDATE IMPACT — "did the update slow my phone?", from the benchmark
// side: the stress version's rules (UpdateImpact) on this phone's benchmark
// history. Pure logic, Foundation only; nothing here is uploaded.
//
//   · verified, complete runs of this model and of the current scoring only
//   · OS = version + build; a run saved without a build (before 2.1) counts as
//     the same OS when the version matches — missing data never prompts
//   · medians of up to 3 newest runs per side; under 3 % is no clear change
// The Android and Windows apps follow the same rules.
// ─────────────────────────────────────────────────────────────────────────────

extension BenchmarkRecord {
    /// The six section scores, re-derived from the run's raw numbers with the app's scoring (as the server re-scores
    /// a submitted run); a section the run doesn't have is missing.
    var sectionScores: [String: Int] {
        var s: [String: Int] = [:]
        func put(_ key: String, _ v: Int) { if v > 0 { s[key] = v } }
        put("cpu", CpuScore.overall([CpuScore.points(.math, cpuMath), CpuScore.points(.data, cpuData),
                                     CpuScore.points(.crypto, cpuCrypto), CpuScore.points(.multi, cpuMulti)]))
        put("mem", MemScore.overall([MemScore.ram(memRead, memWrite, memCopy, memLatency),
                                     MemScore.storage(storageSeqRead, storageSeqWrite, storageRandomRead, storageRandomWrite),
                                     MemScore.db(dbInsert, dbUpdate, dbQuery)]))
        put("gpu", BenchmarkScore.overall(BenchmarkScore.points(.lakeValley, fps: test1Fps), BenchmarkScore.points(.neonCity, fps: test2Fps),
                                          rayTracing ? BenchmarkScore.points(.neonCityRT, fps: test3Fps) : 0))
        let m = media ?? MediaRecord()
        let clips: [(VideoClip, Double)] = [(.avc4k, videoAvc), (.hevc4k, videoHevc), (.vp94k, videoVp9), (.av14k, videoAv1),
                                            (.hevc8k, m.video8kHevc), (.av18k, m.video8kAv1)]
        let jobs: [(ExportJob, Double, Double)] = [(.avc4k, m.exportAvc, m.exportAvcPsnr), (.hevc4k, m.exportHevc, m.exportHevcPsnr),
                                                   (.av14k, m.exportAv1, m.exportAv1Psnr), (.hevc8k, m.export8kHevc, m.export8kHevcPsnr)]
        let ux = UxResult(web: WebResult(layout: webLayout, canvas: webCanvas, css: webCss, svg: webSvg, js: webJs),
                          scroll: ScrollResult(speed: scrollSpeed, droppedPct: scrollDropped),
                          gallery: GalleryResult(jpeg: galleryJpeg, webp: galleryWebp, avif: galleryAvif),
                          photoMps: photoEdit,
                          video: VideoResult(codecs: clips.map { VideoCodecResult(clip: $0.0, fps: $0.1, decoder: nil, hardware: false) }),
                          export: ExportResult(jobs: jobs.map { ExportJobResult(job: $0.0, fps: $0.1, psnr: $0.2, encoder: nil, hardware: false) }),
                          qr: QrResult(rate: qrRate, decoded: 0, missed: 0, referenceRate: nil, referenceName: ""))
        put("ux", ux.score)
        func ips(_ a: AiAccel) -> [Double] {
            AiTest.allCases.map { t in let i = (t.rawValue - 1) * AiAccel.allCases.count + a.rawValue; return i < ai.count ? ai[i] : 0 }
        }
        put("ai", AiScore.overall(AiScore.accel(.cpu, ips(.cpu)), AiScore.accel(.gpu, ips(.gpu)), AiScore.accel(.npu, ips(.npu))))
        if let l = llm { put("llm", LlmScore.overall(max(l.prefillCpu, l.prefillGpu), max(l.decodeCpu, l.decodeGpu))) }
        return s
    }
}

enum BenchmarkUpdateImpact {

    /// A change smaller than this (3 %) is within run-to-run noise.
    static let noiseFloor = 0.03
    static let maxRunsPerSide = 3
    static let sections: [(key: String, label: String)] = [("cpu", "CPU"), ("mem", "MEM"), ("gpu", "GPU"), ("ux", "UX"), ("ai", "AI"), ("llm", "LLM")]

    enum Verdict { case slower, faster, noClearChange }

    /// Runs measured on one OS, newest first.
    struct Side {
        let runs: [BenchmarkRecord]
        var latest: BenchmarkRecord { runs[0] }
        var median: Double { StressCurve.median(runs.map { Double($0.score) }) }
        /// The median of a section over the runs that have it; nil when none does.
        func median(_ key: String) -> Double? {
            let v = runs.compactMap { $0.sectionScores[key] }.map(Double.init)
            return v.isEmpty ? nil : StressCurve.median(v)
        }
    }

    struct SectionDelta: Equatable {
        let key: String
        let label: String
        let before: Double
        let after: Double
        var delta: Double { before > 0 ? after / before - 1 : 0 }
    }

    /// The phone updated and has no benchmark run since: offer one.
    struct Prompt {
        let key: String
        let last: BenchmarkRecord
        let nowOsVersion: String
        let nowOsBuild: String?
    }

    struct Comparison {
        let key: String
        let before: Side
        let after: Side
        let delta: Double                   // after / before − 1, of the median totals
        let verdict: Verdict
        let sections: [SectionDelta]        // where both sides have the section
    }

    enum Insight {
        case prompt(Prompt)
        case comparison(Comparison)

        /// The prompt is dismissed per OS transition; a result until a newer run makes a newer comparison.
        var dismissKey: String {
            switch self {
            case .prompt(let p): return "prompt:\(p.key)"
            case .comparison(let c): return "result:\(c.key):\(c.after.latest.id.uuidString)"
            }
        }
    }

    static func osKey(_ version: String, _ build: String?) -> String { "\(version)|\(build ?? "")" }

    /// The same OS: the same version, and the same build unless one of them wasn't recorded.
    static func sameOs(_ v1: String, _ b1: String?, _ v2: String, _ b2: String?) -> Bool {
        v1 == v2 && (b1 == nil || b2 == nil || b1 == b2)
    }

    /// Evidence: a verified, complete run of this model, scored the way the app scores now. A run saved before 2.1
    /// has no model and counts as this phone's.
    static func usable(_ r: BenchmarkRecord, deviceModel: String) -> Bool {
        r.version == BenchmarkScore.version && r.complete && r.validityFlags == 0 && r.osVersion != nil
            && (r.deviceModel == nil || r.deviceModel == deviceModel)
    }

    /// What to say about the benchmark before and after an OS update, or nil when there's nothing honest to say.
    static func insight(history: [BenchmarkRecord], deviceModel: String, osVersion: String, osBuild: String?) -> Insight? {
        let mine = history.filter { usable($0, deviceModel: deviceModel) }.sorted { $0.timestamp > $1.timestamp }
        func onNow(_ r: BenchmarkRecord) -> Bool { sameOs(r.osVersion ?? "", r.osBuild, osVersion, osBuild) }
        // before the update: the newest run on another OS
        guard let previous = mine.first(where: { !onNow($0) }), let prevVersion = previous.osVersion else { return nil }
        let key = "\(osKey(prevVersion, previous.osBuild))->\(osKey(osVersion, osBuild))"
        let after = Array(mine.filter { onNow($0) && $0.timestamp > previous.timestamp }.prefix(maxRunsPerSide))
        if after.isEmpty {
            return .prompt(Prompt(key: key, last: previous, nowOsVersion: osVersion, nowOsBuild: osBuild))
        }
        let before = Array(mine.filter { !onNow($0) && sameOs($0.osVersion ?? "", $0.osBuild, prevVersion, previous.osBuild) }
            .prefix(maxRunsPerSide))
        let b = Side(runs: before), a = Side(runs: after)
        let delta = b.median > 0 ? a.median / b.median - 1 : 0
        let verdict: Verdict = abs(delta) < noiseFloor ? .noClearChange : delta < 0 ? .slower : .faster
        let deltas = sections.compactMap { s -> SectionDelta? in
            guard let x = b.median(s.key), let y = a.median(s.key) else { return nil }
            return SectionDelta(key: s.key, label: s.label, before: x, after: y)
        }
        return .comparison(Comparison(key: key, before: b, after: a, delta: delta, verdict: verdict, sections: deltas))
    }

    /// "iOS 26.3 → 26.4", or with the builds when only they differ: "iOS 26.4 (23E5) → 26.4 (23E7)".
    static func transition(os: String, from: (String, String?), to: (String, String?)) -> String {
        let builds = from.0 == to.0
        func label(_ v: (String, String?)) -> String { builds ? "\(v.0)" + (v.1.map { " (\($0))" } ?? "") : v.0 }
        return "\(os) \(label(from)) → \(label(to))"
    }

    /// "7% slower than before the update".
    static func deltaText(_ delta: Double) -> String {
        "\(Int((abs(delta) * 100).rounded()))% \(delta < 0 ? "slower" : "faster") than before the update"
    }
}
