import AVFoundation
import CoreImage
import UIKit
import VideoToolbox

// ─────────────────────────────────────────────────────────────────────────────
// VIDEO EXPORT — UX Test 6, as on Android (VideoExportScene.kt): what an
// editor's "export" does, encoder by encoder. The source clip loops through
// AVAssetReader's decoder; every frame gets the edit in Core Image (grade,
// vignette, a dip to black where the clip loops, an opaque lower third with a
// timecode that changes every frame) rendered into a VideoToolbox encoder's
// buffer (4:2:0, rendered on the GPU while the previous frame encodes) — not
// real time, so it runs as fast as the encoder takes frames —
// and the encoder writes a real MP4 (temporary, deleted after). A job is a
// fixed timeline (ExportJob.frames: 8 s of 4K60, 4 s of 8K30) exported start
// to finish, so the stage's progress bar is the export's own; the rate is the
// frames after the first `warmupFrames` over the time until the file is
// finished. One that can't finish within `capS` is measured on what it did.
//
// Quality check: at `samples` the lower third is also rendered at full
// resolution and kept; after the job the first encoded frames are decoded
// again and the same window compared — luma PSNR after a linear fit, so a
// different colour range doesn't count against the encoder. Below
// VideoGrid.minPsnr the job's speed doesn't count. Apple has no AV1 encoder:
// that job scores 0, like a phone without one.
// ─────────────────────────────────────────────────────────────────────────────

final class ExportBench {
    static let warmupFrames = 30, capS = 15.0
    /// Exported frames compared with what was sent in (those reached).
    static let samples: Set<Int> = [6, 18, 30, 42]
    /// The checked window, a fraction of the frame from the bottom left (Core Image's origin, GL's too).
    static let window = CGRect(x: 0.0677, y: 0.1523, width: 0.375, height: 0.1037)
    private static let keep = 80                           // encoded frames kept for the check

    struct Progress {
        let fraction: Double; let fps: Double; let framesDone: Int; let elapsedS: Double; let stage: String; let image: UIImage?
        let encoder: String?
        var error: String? = nil
    }

    private let ci: CIContext = {
        let opts: [CIContextOption: Any] = [.workingColorSpace: NSNull(), .outputColorSpace: NSNull(), .cacheIntermediates: false]
        if let d = MTLCreateSystemDefaultDevice() { return CIContext(mtlDevice: d, options: opts) }
        return CIContext(options: opts)
    }()

    /** Every job in turn; `onProgress` gets the job, its index, how it's going and the jobs finished before it. */
    func run(isActive: () -> Bool, onProgress: (ExportJob, Int, Progress, [ExportJobResult]) -> Void) -> ExportResult {
        var results: [ExportJobResult] = []
        for (i, job) in ExportJob.allCases.enumerated() {
            guard isActive() else { break }
            do {
                let done = results
                results.append(try export(job, isActive: isActive) { onProgress(job, i, $0, done) })
            } catch {
                results.append(ExportJobResult(job: job, fps: 0, psnr: 0, encoder: nil, hardware: false, error: error.localizedDescription))
                onProgress(job, i, Progress(fraction: 1, fps: 0, framesDone: 0, elapsedS: 0, stage: "", image: nil, encoder: nil,
                                            error: error.localizedDescription), results)
                Thread.sleep(forTimeInterval: 1)   // the stage says why for a moment
            }
        }
        return ExportResult(jobs: results)
    }

    // MARK: the source

    /** The source clip decoded to BGRA, looping. */
    private final class Source {
        let asset: AVURLAsset, track: AVAssetTrack
        var reader: AVAssetReader, output: AVAssetReaderTrackOutput
        let frames: Int                                  // per pass of the clip

        init(_ clip: VideoClip) throws {
            guard let url = Bundle.main.url(forResource: clip.asset, withExtension: nil) else { throw GpuError(message: "\(clip.asset) missing") }
            asset = AVURLAsset(url: url)
            guard let t = asset.tracks(withMediaType: .video).first else { throw GpuError(message: "can't open the \(clip.res) source") }
            track = t
            frames = max(1, Int((CMTimeGetSeconds(asset.duration) * Double(clip.fps)).rounded()))
            (reader, output) = try Source.open(asset, t, clip)
        }

        static func open(_ a: AVAsset, _ t: AVAssetTrack, _ clip: VideoClip) throws -> (AVAssetReader, AVAssetReaderTrackOutput) {
            let r = try AVAssetReader(asset: a)
            let o = AVAssetReaderTrackOutput(track: t, outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any]()])
            o.alwaysCopiesSampleData = false
            r.add(o)
            guard r.startReading() else { throw GpuError(message: "can't decode the \(clip.res) \(clip.codec.short) source") }
            return (r, o)
        }

        func next(_ clip: VideoClip) throws -> CVPixelBuffer {
            for _ in 0..<2 {
                if let s = output.copyNextSampleBuffer(), let px = CMSampleBufferGetImageBuffer(s) { return px }
                if reader.status == .failed { throw GpuError(message: "can't decode the \(clip.res) \(clip.codec.short) source") }
                reader.cancelReading()
                (reader, output) = try Source.open(asset, track, clip)            // loop
            }
            throw GpuError(message: "the source has no frames")
        }
    }

    // MARK: the encoder's output

    private final class Sink {
        let lock = NSLock()
        var encoded = 0, inFlight = 0
        var failed: OSStatus = noErr
        var kept: [CMSampleBuffer] = []
        var writer: AVAssetWriter?, input: AVAssetWriterInput?
        let url: URL
        init(url: URL) { self.url = url }

        /** On the encoder's thread: count it, keep the first frames for the check, write it. */
        func take(_ s: CMSampleBuffer) {
            encoded += 1
            if kept.count < ExportBench.keep { kept.append(s) }
            if writer == nil, let fmt = CMSampleBufferGetFormatDescription(s), let w = try? AVAssetWriter(outputURL: url, fileType: .mp4) {
                let i = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: fmt)
                i.expectsMediaDataInRealTime = true                               // never hold the encoder up
                if w.canAdd(i) { w.add(i); w.startWriting(); w.startSession(atSourceTime: CMSampleBufferGetPresentationTimeStamp(s)) }
                writer = w; input = i
            }
            if let i = input, i.isReadyForMoreMediaData { i.append(s) }
        }
    }

    // MARK: one export

    private func export(_ job: ExportJob, isActive: () -> Bool, onProgress: (Progress) -> Void) throws -> ExportJobResult {
        let w = job.width, h = job.height
        let source = try Source(job.source)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("export_\(job.rawValue).mp4")
        try? FileManager.default.removeItem(at: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let sink = Sink(url: url)

        let codec: CMVideoCodecType = job.codec == .avc ? kCMVideoCodecType_H264 : job.codec == .hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_AV1
        // iOS picks the hardware encoder itself; the key that asks for it only exists from 17.4
        var spec: CFDictionary?
        if #available(iOS 17.4, *) { spec = [kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: true] as CFDictionary }
        // 4:2:0 in, as the encoder takes it: no colour conversion of its own in the way
        let attrs = [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, kCVPixelBufferWidthKey: w,
                     kCVPixelBufferHeightKey: h, kCVPixelBufferIOSurfacePropertiesKey: [String: Any]()] as CFDictionary
        let callback: VTCompressionOutputCallback = { refcon, _, status, _, sample in
            let s = Unmanaged<Sink>.fromOpaque(refcon!).takeUnretainedValue()
            s.lock.lock(); defer { s.lock.unlock() }
            s.inFlight -= 1
            if status != noErr { s.failed = status; return }
            if let sample { s.take(sample) }
        }
        var made: VTCompressionSession?
        let st = VTCompressionSessionCreate(allocator: nil, width: Int32(w), height: Int32(h), codecType: codec, encoderSpecification: spec,
                                            imageBufferAttributes: attrs, compressedDataAllocator: nil, outputCallback: callback,
                                            refcon: Unmanaged.passUnretained(sink).toOpaque(), compressionSessionOut: &made)
        guard st == noErr, let session = made else { throw GpuError(message: "no \(job.label) encoder for \(w)×\(h)") }
        defer { VTCompressionSessionInvalidate(session) }
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanFalse)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: (job.mbps * 1_000_000) as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: job.fps as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: job.fps as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel,
                             value: job.codec == .avc ? kVTProfileLevel_H264_High_AutoLevel : kVTProfileLevel_HEVC_Main_AutoLevel)
        VTCompressionSessionPrepareToEncodeFrames(session)
        guard let pool = VTCompressionSessionGetPixelBufferPool(session) else { throw GpuError(message: "the \(job.label) encoder gave no buffers") }
        #if targetEnvironment(simulator)
        let hardware = false
        #else
        let hardware = true
        #endif
        let encoder = "VideoToolbox" + (hardware ? "" : " (software)")

        let look = Look(width: w, height: h)
        let window = CGRect(x: (Self.window.minX * Double(w)).rounded(), y: (Self.window.minY * Double(h)).rounded(),
                            width: (Self.window.width * Double(w)).rounded(), height: (Self.window.height * Double(h)).rounded())
        var refs: [Int: [UInt8]] = [:]
        let start = DispatchTime.now().uptimeNanoseconds
        var measuredFrom: UInt64 = 0, framesAtMeasure = 0, fps = 0.0
        var lastPreview: UInt64 = 0, lastReport: UInt64 = 0
        var image: UIImage?
        var k = 0
        var pending: (CIRenderTask, CVPixelBuffer, Int)?
        /**
         * Waits for a frame's render, then hands it to the encoder — a few frames inside it at most, so it sets the pace.
         * An encoder that looks ahead holds frames back until it has enough: after a quarter of a second it gets more.
         */
        func encode(_ f: (CIRenderTask, CVPixelBuffer, Int)) throws {
            _ = try f.0.waitUntilCompleted()
            let waitFrom = DispatchTime.now().uptimeNanoseconds
            while sink.lock.withLock({ sink.inFlight }) >= 6 && sink.lock.withLock({ sink.failed }) == noErr
                    && DispatchTime.now().uptimeNanoseconds - waitFrom < 250_000_000 {
                guard isActive() else { throw GpuError(message: "stopped") }
                usleep(200)
            }
            sink.lock.withLock { sink.inFlight += 1 }
            let status = VTCompressionSessionEncodeFrame(session, imageBuffer: f.1, presentationTimeStamp: CMTime(value: Int64(f.2), timescale: Int32(job.fps)),
                                                         duration: CMTime(value: 1, timescale: Int32(job.fps)), frameProperties: nil,
                                                         sourceFrameRefcon: nil, infoFlagsOut: nil)
            if status != noErr { throw GpuError(message: "the \(job.label) encoder refused a frame (\(status))") }
        }
        /** Frames per second after the warm-up frames (all of them, for a job that never got past those). */
        func rate(_ now: UInt64, _ encoded: Int) -> Double {
            if measuredFrom > 0 && now > measuredFrom + 50_000_000 { return Double(encoded - framesAtMeasure) * 1e9 / Double(now - measuredFrom) }
            let el = Double(now - start) / 1e9
            return el > 0.05 ? Double(encoded) / el : 0
        }
        var capped = false
        while isActive() && k < job.frames {
            let now = DispatchTime.now().uptimeNanoseconds
            let el = Double(now - start) / 1e9
            let (encoded, failed) = sink.lock.withLock { (sink.encoded, sink.failed) }
            if failed != noErr { throw GpuError(message: "the \(job.label) encoder failed (\(failed))") }
            if measuredFrom == 0 && encoded >= Self.warmupFrames { measuredFrom = now; framesAtMeasure = encoded }
            fps = rate(now, encoded)
            if el >= Self.capS { capped = true; break }                    // too slow to finish: measured on what it did

            let srcPx = try source.next(job.source)
            let edited = look.edit(CIImage(cvPixelBuffer: srcPx), frame: k, loop: source.frames, fps: job.fps)
            var out: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &out)
            guard let out else { throw GpuError(message: "the \(job.label) encoder ran out of buffers") }
            // rendered on the GPU while the previous frame goes to the encoder
            let task = try ci.startTask(toRender: edited, to: CIRenderDestination(pixelBuffer: out))
            if Self.samples.contains(k) { refs[k] = bitmap(edited, window) }
            if let p = pending { try encode(p) }
            pending = (task, out, k)
            k += 1

            if now - lastPreview > 300_000_000 {   // a small preview every 0.3 s (a few ms, inside the timed span)
                lastPreview = now
                let small = edited.transformed(by: CGAffineTransform(scaleX: 1280 / Double(w), y: 1280 / Double(w)))
                if let cg = ci.createCGImage(small, from: small.extent, format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)) {
                    image = UIImage(cgImage: cg)
                }
            }
            if now - lastReport > 100_000_000 {
                lastReport = now
                onProgress(Progress(fraction: 0.88 * Double(encoded) / Double(job.frames), fps: fps, framesDone: encoded, elapsedS: el,
                                    stage: "ENCODING", image: image, encoder: encoder))
            }
        }
        if let p = pending { try encode(p); pending = nil }
        guard isActive() else { throw GpuError(message: "stopped") }
        onProgress(Progress(fraction: 0.88, fps: fps, framesDone: min(job.frames, sink.lock.withLock { sink.encoded }),
                            elapsedS: Double(DispatchTime.now().uptimeNanoseconds - start) / 1e9,
                            stage: "FINISHING THE FILE", image: image, encoder: encoder))
        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        // the file is done: the export's whole time (unless it was cut off, then the rate at the cut stands)
        let doneAt = DispatchTime.now().uptimeNanoseconds
        let encodedAll = sink.lock.withLock { sink.encoded }
        if !capped { fps = rate(doneAt, encodedAll) }
        let (writer, input, kept, failed) = sink.lock.withLock { (sink.writer, sink.input, sink.kept, sink.failed) }
        if failed != noErr && kept.isEmpty { throw GpuError(message: "the \(job.label) encoder failed (\(failed))") }
        if let writer, let input, writer.status == .writing {
            input.markAsFinished()
            let done = DispatchSemaphore(value: 0)
            writer.finishWriting { done.signal() }
            _ = done.wait(timeout: .now() + 3)
        }
        onProgress(Progress(fraction: 0.92, fps: fps, framesDone: min(job.frames, encodedAll), elapsedS: Double(doneAt - start) / 1e9,
                            stage: "CHECKING QUALITY", image: image, encoder: encoder))
        let psnr = try verify(kept, refs: refs, window: window, fps: job.fps)
        return ExportJobResult(job: job, fps: BenchmarkScore.round2(fps), psnr: BenchmarkScore.round2(psnr), encoder: encoder, hardware: hardware)
    }

    // MARK: the quality check

    private final class Frames {
        let lock = NSLock()
        var got: [Int: CVPixelBuffer] = [:]
        var wanted: Set<Int> = []
        var fps = 60
    }

    /** Decodes the kept frames again; luma PSNR of the window against what was sent in. */
    private func verify(_ kept: [CMSampleBuffer], refs: [Int: [UInt8]], window: CGRect, fps: Int) throws -> Double {
        guard !refs.isEmpty, let first = kept.first, let format = CMSampleBufferGetFormatDescription(first) else {
            throw GpuError(message: "the encoder produced nothing")
        }
        let frames = Frames()
        frames.wanted = Set(refs.keys); frames.fps = fps
        var callback = VTDecompressionOutputCallbackRecord(decompressionOutputCallback: { ref, _, status, _, image, pts, _ in
            let f = Unmanaged<Frames>.fromOpaque(ref!).takeUnretainedValue()
            guard status == noErr, let image else { return }
            let k = Int((CMTimeGetSeconds(pts) * Double(f.fps)).rounded())
            f.lock.withLock { if f.wanted.contains(k) { f.got[k] = image } }
        }, decompressionOutputRefCon: Unmanaged.passUnretained(frames).toOpaque())
        let attrs = [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA, kCVPixelBufferIOSurfacePropertiesKey: [String: Any]()] as CFDictionary
        var made: VTDecompressionSession?
        guard VTDecompressionSessionCreate(allocator: nil, formatDescription: format, decoderSpecification: nil, imageBufferAttributes: attrs,
                                           outputCallback: &callback, decompressionSessionOut: &made) == noErr, let session = made else {
            throw GpuError(message: "can't decode the export to check it")
        }
        defer { VTDecompressionSessionInvalidate(session) }
        for s in kept { VTDecompressionSessionDecodeFrame(session, sampleBuffer: s, flags: [], frameRefcon: nil, infoFlagsOut: nil) }
        VTDecompressionSessionWaitForAsynchronousFrames(session)
        let got = frames.lock.withLock { frames.got }
        let pairs = refs.compactMap { k, ref in got[k].map { (ref, bitmap(CIImage(cvPixelBuffer: $0), window)) } }
        guard !pairs.isEmpty else { throw GpuError(message: "the export didn't decode") }
        return Self.psnr(pairs)
    }

    private func bitmap(_ image: CIImage, _ rect: CGRect) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: Int(rect.width) * Int(rect.height) * 4)
        ci.render(image, toBitmap: &out, rowBytes: Int(rect.width) * 4, bounds: rect, format: .RGBA8, colorSpace: nil)
        return out
    }

    /** Luma PSNR over (reference, exported) after fitting exported ≈ a·reference + b — Android's VideoExportScene.psnr. */
    static func psnr(_ pairs: [([UInt8], [UInt8])]) -> Double {
        func luma(_ b: [UInt8], _ i: Int) -> Double { Double(77 * Int(b[i]) + 150 * Int(b[i + 1]) + 29 * Int(b[i + 2])) / 256 }
        var n = 0.0, sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0
        for (r, e) in pairs {
            for i in stride(from: 0, to: min(r.count, e.count), by: 4) {
                let x = luma(r, i), y = luma(e, i); n += 1; sx += x; sy += y; sxx += x * x; sxy += x * y
            }
        }
        let varX = sxx - sx * sx / n
        let a = varX > 1e-6 ? (sxy - sx * sy / n) / varX : 1
        let b = (sy - a * sx) / n
        var se = 0.0
        for (r, e) in pairs {
            for i in stride(from: 0, to: min(r.count, e.count), by: 4) { let d = luma(e, i) - (a * luma(r, i) + b); se += d * d }
        }
        let mse = se / n
        return mse < 1e-6 ? 99 : (10 * log10(255 * 255 / mse) * 100).rounded() / 100
    }

    // MARK: the edit

    /**
     * Grade, vignette, the dip where the clip loops, and the lower third — a 1600×240 card (Android's titleTexture)
     * scaled into x 6–48 %, 74–85 % down the frame, with the timecode's eleven glyphs drawn per frame.
     */
    private struct Look {
        let width: Int, height: Int
        let title: CIImage
        let glyphs: [CIImage]
        let box: CGRect

        init(width: Int, height: Int) {
            self.width = width; self.height = height
            let w = Double(width), h = Double(height)
            box = CGRect(x: 0.06 * w, y: (1 - 0.852) * h, width: 0.42 * w, height: 0.112 * h)
            let card = UIGraphicsImageRenderer(size: CGSize(width: 1600, height: 240), format: Look.format).image { ctx in
                UIColor(red: 8 / 255, green: 12 / 255, blue: 16 / 255, alpha: 1).setFill()
                UIBezierPath(roundedRect: CGRect(x: 0, y: 0, width: 1600, height: 240), cornerRadius: 24).fill()
                Look.teal.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 14, height: 240))
                Look.draw("NEON CITY · NIGHT RUN", UIFont.monospacedSystemFont(ofSize: 92, weight: .bold), UIColor(white: 0.95, alpha: 1), x: 56, baseline: 112)
                Look.draw("TC", UIFont.monospacedSystemFont(ofSize: 64, weight: .regular), Look.teal, x: 58, baseline: 206)
            }
            title = CIImage(cgImage: card.cgImage!)
                .transformed(by: CGAffineTransform(scaleX: box.width / 1600, y: box.height / 240))
                .transformed(by: CGAffineTransform(translationX: box.minX, y: box.minY))
            let cw = 0.04 * box.width / 64, ch = 0.40 * box.height / 96
            glyphs = "0123456789:".map { g in
                let cell = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 96), format: Look.format).image { _ in
                    let f = UIFont.monospacedSystemFont(ofSize: 80, weight: .bold)
                    let s = NSAttributedString(string: String(g), attributes: [.font: f, .foregroundColor: Look.teal])
                    s.draw(at: CGPoint(x: (64 - s.size().width) / 2, y: 96 * 0.78 - f.ascender))
                }
                return CIImage(cgImage: cell.cgImage!).transformed(by: CGAffineTransform(scaleX: cw, y: ch))
            }
        }

        static let teal = UIColor(red: 0x3D / 255, green: 0xE8 / 255, blue: 0xC2 / 255, alpha: 1)
        static let format: UIGraphicsImageRendererFormat = { let f = UIGraphicsImageRendererFormat(); f.scale = 1; f.opaque = false; return f }()

        static func draw(_ text: String, _ font: UIFont, _ color: UIColor, x: CGFloat, baseline: CGFloat) {
            NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: color]).draw(at: CGPoint(x: x, y: baseline - font.ascender))
        }

        func edit(_ src: CIImage, frame k: Int, loop: Int, fps: Int) -> CIImage {
            let w = Double(width), h = Double(height)
            var img = src.applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 1.18, kCIInputContrastKey: 1.08])
                .applyingFilter("CIVignetteEffect", parameters: [kCIInputCenterKey: CIVector(x: w / 2, y: h / 2),
                                                                 kCIInputRadiusKey: 0.62 * w, kCIInputIntensityKey: 0.55, "inputFalloff": 0.6])
            let pos = k % loop
            let fade = min(1, max(0.25, Double(min(pos + 1, loop - pos)) / 8))        // a dip where the clip loops
            if fade < 1 {
                img = img.applyingFilter("CIColorMatrix", parameters: ["inputRVector": CIVector(x: fade, y: 0, z: 0, w: 0),
                                                                      "inputGVector": CIVector(x: 0, y: fade, z: 0, w: 0),
                                                                      "inputBVector": CIVector(x: 0, y: 0, z: fade, w: 0)])
            }
            img = title.composited(over: img)
            let sec = k / fps, ff = k % fps
            let digits = [0, 0, 10, sec / 60 / 10, sec / 60 % 10, 10, sec % 60 / 10, sec % 60 % 10, 10, ff / 10, ff % 10]
            for (i, d) in digits.enumerated() {
                let x = box.minX + (0.13 + 0.04 * Double(i)) * box.width, y = box.minY + 0.05 * box.height
                img = glyphs[d].transformed(by: CGAffineTransform(translationX: x, y: y)).composited(over: img)
            }
            return img.cropped(to: CGRect(x: 0, y: 0, width: w, height: h))
        }
    }
}

