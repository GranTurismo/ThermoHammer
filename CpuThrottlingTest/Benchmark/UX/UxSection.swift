import Foundation

/** The UX tests in order: web browsing, web scrolling, photo gallery, photo editing, video playback, video export, QR scanning. */
struct UxSection: BenchmarkSection {
    func run(_ engine: BenchmarkEngine, _ r: inout BenchmarkResult) throws {
        var ux = UxResult(photoMps: 0)
        var errors: [String] = []
        let q = BenchmarkScore.round2
        func number(_ o: [String: Any], _ key: String) throws -> Double {
            guard let v = o[key] as? Double ?? (o[key] as? NSNumber)?.doubleValue else { throw GpuError(message: "no \(key) in the result") }
            return q(v)
        }
        func noted<T>(_ test: UxTest, _ body: () throws -> T?) -> T? {
            do { return try body() } catch {
                errors.append("\(test.title.capitalized): \(error.localizedDescription)")
                return nil
            }
        }
        let first: UxTest = engine.startFrom == "photo" ? .photo : engine.startFrom == "video" ? .video
            : engine.startFrom == "export" ? .export : engine.startFrom == "qr" ? .qr : .web   // simulator checks
        for test in [UxTest.web, .scroll, .gallery] where test.rawValue >= first.rawValue {
            guard let outcome = engine.runWebPage(test) else { return }
            switch test {
            case .web: ux.web = noted(test) { let o = try outcome.get()
                return WebResult(layout: try number(o, "layout"), canvas: try number(o, "canvas"), css: try number(o, "css"),
                                 svg: try number(o, "svg"), js: try number(o, "js")) }
            case .scroll: ux.scroll = noted(test) { let o = try outcome.get()
                return ScrollResult(speed: try number(o, "speed"), droppedPct: try number(o, "dropped")) }
            default: ux.gallery = noted(test) { let o = try outcome.get()
                return GalleryResult(jpeg: try number(o, "jpeg"), webp: try number(o, "webp"), avif: try number(o, "avif")) }
            }
        }
        // UX 4: the editing chain on the GPU — megapixels per second through all of it
        guard engine.isActive else { return }
        if first.rawValue <= UxTest.photo.rawValue {
            ux.photoMps = noted(.photo) { () -> Double in
                guard let bench = GpuBench.shared else { throw GpuError(message: "Metal is unavailable") }
                let scene = try PhotoEditScene(device: bench.device, library: bench.library, width: GpuBench.width, height: GpuBench.height)
                let seconds = Float(UxTest.photo.seconds)
                let detail = "LENS · BLUR · BOKEH · SHARPEN · GRADE · \(Int(scene.megapixels.rounded())) MP"
                engine.publishUx(.photo, detail: detail, unit: "MP/S")
                let (starts, durs) = try bench.run(scene, seconds: seconds, isActive: { engine.isActive }) { t, fps in
                    engine.publishUx(.photo, progress: Double(t / seconds), detail: detail, value: fps * scene.megapixels, unit: "MP/S")
                }
                let idx = starts.indices.filter { starts[$0] >= BenchmarkScore.warmupS }
                guard idx.count >= 3 else { throw GpuError(message: "too few frames") }
                return q(Double(idx.count) * 1e9 / Double(idx.reduce(Int64(0)) { $0 + durs[$1] }) * scene.megapixels)
            } ?? 0
        }
        // UX 5: each clip's decode rate — 4K60 in four codecs, 8K30 in two
        guard engine.isActive else { return }
        if first.rawValue <= UxTest.video.rawValue {
            engine.publishUx(.video, detail: "OPENING DECODERS", unit: "FPS")
            ux.video = VideoBench().run(isActive: { engine.isActive }) { f, fps, clip, image, position in
                engine.publishUx(.video, progress: f / Double(VideoClip.allCases.count),
                                 detail: position == nil ? "\(clip.label) · NOT SUPPORTED · 0 POINTS" : "\(clip.label) · VIDEOTOOLBOX",
                                 value: fps > 0 ? fps : -1, unit: "FPS")
                engine.publishVideoFrame(image)
                engine.publishVideoOsd(position.map { VideoOsd(format: "\(clip.codec.label) · \(clip.width)×\(clip.height) · \(clip.fps) FPS",
                                                               mbps: clip.mbps, positionS: $0, durationS: 3, speed: fps / Double(clip.fps)) })
            }
            engine.publishVideoOsd(nil)
        }
        // UX 6: a graded edit exported by each encoder, its quality checked
        guard engine.isActive else { return }
        if first.rawValue <= UxTest.export.rawValue {
            engine.publishUx(.export, detail: "OPENING ENCODERS", unit: "FPS")
            ux.export = ExportBench().run(isActive: { engine.isActive }) { job, i, p, done in
                let skipped = p.encoder == nil
                let step = skipped ? (p.error ?? "not supported").uppercased() : p.stage
                engine.publishUx(.export, progress: (Double(i) + p.fraction) / Double(ExportJob.allCases.count),
                                 detail: skipped ? "\(job.label) \(job.res) · \(step) · 0 POINTS"
                                     : "\(job.label) \(job.res) · \(job.mbps) MBPS · \(p.encoder ?? "")",
                                 value: p.fps > 0 ? p.fps : -1, unit: "FPS")
                engine.publishVideoFrame(p.image)
                let queue = ExportJob.allCases.map { e -> ExportStage.Item in
                    if let r = done.first(where: { $0.job == e }) {
                        return r.fps > 0 ? ExportStage.Item(job: e, state: .done, fps: r.fps, psnr: r.psnr) : ExportStage.Item(job: e, state: .skipped)
                    }
                    return ExportStage.Item(job: e, state: e == job ? (skipped ? .skipped : .active) : .pending)
                }
                engine.publishExportStage(ExportStage(job: job, encoder: p.encoder, hardware: p.encoder.map { !$0.contains("software") } ?? false,
                                                      framesDone: p.framesDone, elapsedS: p.elapsedS, fps: p.fps, step: step, queue: queue))
            }
            engine.publishExportStage(nil)
        }
        // UX 7: the shared QR frames, zxing-cpp on one thread (scored), then Vision (reported)
        guard engine.isActive else { return }
        engine.publishUx(.qr, detail: "PREPARING FRAMES", unit: "SCANS/S")
        ux.qr = noted(.qr) { () -> QrResult in
            let bench = try QrBench()
            let span = QrBench.zxingS + QrBench.visionS
            return bench.run(isActive: { engine.isActive }) { p in
                let overall = p.phase == "ZXING" ? p.fraction * QrBench.zxingS / span : (QrBench.zxingS + p.fraction * QrBench.visionS) / span
                engine.publishUx(.qr, progress: overall, detail: p.phase + (p.phase == "VISION" ? " · NOT SCORED" : ""), value: p.rate, unit: "SCANS/S")
                engine.publishQr(bench.previews[p.frame], found: p.found)
            }
        }
        guard engine.isActive else { return }
        ux.error = errors.isEmpty ? nil : errors.joined(separator: "; ")
        engine.publishUx(nil)
        r.ux = ux
    }
}
