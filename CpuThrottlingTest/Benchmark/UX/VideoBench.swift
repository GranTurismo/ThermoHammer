import AVFoundation
import CoreImage
import UIKit
import VideoToolbox

// ─────────────────────────────────────────────────────────────────────────────
// VIDEO PLAYBACK — UX Test 5: the same four 4K60 clips as Android (the bundled
// media/clip_*), decoded flat out by VideoToolbox — 0.6 s warm-up, then 3.6 s
// counted per codec. A clip's compressed frames are read once and fed to the
// decoder again and again (each pass starts on its key frame). A codec the
// phone can't decode — or a container iOS can't open (WebM) — scores 0.
// ─────────────────────────────────────────────────────────────────────────────

final class VideoBench {
    static let warmupS = 0.6, measureS = 3.6

    /** Decodes every codec in turn; `onProgress` gets (codec index + fraction, fps so far, preview frame). */
    func run(isActive: () -> Bool, onProgress: (Double, Double, VideoCodec, UIImage?) -> Void) -> VideoResult {
        var results: [VideoCodecResult] = []
        for (i, codec) in VideoCodec.allCases.enumerated() {
            guard isActive() else { break }
            do {
                results.append(try decode(codec, isActive: isActive) { f, fps, img in onProgress(Double(i) + f, fps, codec, img) })
            } catch {
                results.append(VideoCodecResult(codec: codec, fps: 0, decoder: nil, hardware: false, error: error.localizedDescription))
                onProgress(Double(i) + 1, 0, codec, nil)
                Thread.sleep(forTimeInterval: 1)   // the stage says why for a moment
            }
        }
        return VideoResult(codecs: results)
    }

    private func samples(_ codec: VideoCodec) throws -> ([CMSampleBuffer], CMVideoFormatDescription) {
        guard let url = Bundle.main.url(forResource: codec.asset, withExtension: nil) else { throw GpuError(message: "\(codec.asset) missing") }
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first else { throw GpuError(message: "iOS can't open this container") }
        let reader = try AVAssetReader(asset: asset)
        let out = AVAssetReaderTrackOutput(track: track, outputSettings: nil)   // compressed, as stored
        out.alwaysCopiesSampleData = false
        reader.add(out)
        guard reader.startReading() else { throw GpuError(message: reader.error?.localizedDescription ?? "can't read the clip") }
        var list: [CMSampleBuffer] = []
        while let s = out.copyNextSampleBuffer() { if CMSampleBufferGetNumSamples(s) > 0 { list.append(s) } }
        guard let first = list.first, let format = CMSampleBufferGetFormatDescription(first), !list.isEmpty else {
            throw GpuError(message: "the clip has no frames")
        }
        return (list, format)
    }

    private final class Counter {
        let lock = NSLock()
        var frames = 0
        var failed: OSStatus = 0
        var latest: CVPixelBuffer?
    }

    private func decode(_ codec: VideoCodec, isActive: () -> Bool, onProgress: (Double, Double, UIImage?) -> Void) throws -> VideoCodecResult {
        let (list, format) = try samples(codec)
        let type = CMFormatDescriptionGetMediaSubType(format)
        let hardware = VTIsHardwareDecodeSupported(type)
        let counter = Counter()
        var callback = VTDecompressionOutputCallbackRecord(decompressionOutputCallback: { ref, _, status, _, image, _, _ in
            let c = Unmanaged<Counter>.fromOpaque(ref!).takeUnretainedValue()
            c.lock.withLock {
                if status == noErr, let image { c.frames += 1; c.latest = image } else if status != noErr { c.failed = status }
            }
        }, decompressionOutputRefCon: Unmanaged.passUnretained(counter).toOpaque())
        let attrs = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        var session: VTDecompressionSession?
        let st = VTDecompressionSessionCreate(allocator: nil, formatDescription: format, decoderSpecification: nil,   // iOS picks the hardware decoder itself
                                              imageBufferAttributes: attrs, outputCallback: &callback, decompressionSessionOut: &session)
        guard st == noErr, let session else { throw GpuError(message: "no \(codec.label) decoder for 3840×2160") }
        defer { VTDecompressionSessionInvalidate(session) }
        let flags: VTDecodeFrameFlags = [._EnableAsynchronousDecompression]
        let ci = CIContext()
        let start = DispatchTime.now().uptimeNanoseconds
        var measuredFrom: UInt64 = 0, framesAtMeasure = 0
        var lastPreview: UInt64 = 0
        var image: UIImage?
        var done = false
        while !done && isActive() {
            for s in list {
                VTDecompressionSessionDecodeFrame(session, sampleBuffer: s, flags: flags, frameRefcon: nil, infoFlagsOut: nil)
            }
            VTDecompressionSessionWaitForAsynchronousFrames(session)
            let now = DispatchTime.now().uptimeNanoseconds
            let el = Double(now - start) / 1e9
            let (frames, failed, latest) = counter.lock.withLock { (counter.frames, counter.failed, counter.latest) }
            if failed != noErr && frames == 0 { throw GpuError(message: "\(codec.label) decode failed (\(failed))") }
            if measuredFrom == 0 && el >= Self.warmupS { measuredFrom = now; framesAtMeasure = frames }
            if now - lastPreview > 300_000_000, let latest {   // a small preview every 0.3 s (a few ms, inside the timed span)
                lastPreview = now
                let img = CIImage(cvPixelBuffer: latest).transformed(by: CGAffineTransform(scaleX: 0.25, y: 0.25))
                if let cg = ci.createCGImage(img, from: img.extent) { image = UIImage(cgImage: cg) }
            }
            let fps = measuredFrom > 0 && now > measuredFrom ? Double(frames - framesAtMeasure) * 1e9 / Double(now - measuredFrom) : 0
            onProgress(min(1, el / (Self.warmupS + Self.measureS)), fps, image)
            done = el >= Self.warmupS + Self.measureS
        }
        let end = DispatchTime.now().uptimeNanoseconds
        let frames = counter.lock.withLock { counter.frames }
        let fps = measuredFrom > 0 ? Double(frames - framesAtMeasure) * 1e9 / Double(end - measuredFrom) : 0
        return VideoCodecResult(codec: codec, fps: BenchmarkScore.round2(fps),
                                decoder: hardware ? "VideoToolbox" : "VideoToolbox (software)", hardware: hardware)
    }
}
