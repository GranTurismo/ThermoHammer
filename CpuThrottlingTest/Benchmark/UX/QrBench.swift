import CoreVideo
import UIKit
import Vision

// ─────────────────────────────────────────────────────────────────────────────
// QR SCANNING — UX Test 6: the Android app's frames and decoder, the same C++
// (BenchCore/src/qr.cpp, zxing-cpp): 32 camera-like 1280×720 frames of QR
// codes, scanned on one thread for 14 s (scored), then by Apple's Vision for
// 8 s — reported in the place Android shows Google's ML Kit, not scored.
// ─────────────────────────────────────────────────────────────────────────────

final class QrBench {
    static let zxingS = 14.0, visionS = 8.0
    static let width = Int(BC_QR_W), height = Int(BC_QR_H)

    private let qr: OpaquePointer
    let count: Int
    /// Half-resolution greys of every frame, for the stage.
    private(set) var previews: [UIImage] = []

    struct Progress { let phase: String; let fraction: Double; let rate: Double; let frame: Int; let found: [CGPoint]? }

    init() throws {
        guard let qr = bc_qr_create(Int32(BC_QR_FRAMES), Int32(BC_QR_SEED)) else { throw GpuError(message: "couldn't prepare the QR frames") }
        self.qr = qr
        count = Int(bc_qr_count(qr))
        previews = (0..<count).map { preview($0) }
    }

    deinit { bc_qr_destroy(qr) }

    private func text(_ i: Int) -> String { String(cString: bc_qr_text(qr, Int32(i))) }

    func run(isActive: () -> Bool, onProgress: (Progress) -> Void) -> QrResult {
        // ── ZXing (zxing-cpp) ──
        var decoded = 0, missed = 0, i = 0
        var found = [Float](repeating: 0, count: 8)
        let t0 = DispatchTime.now().uptimeNanoseconds
        var lastReport: UInt64 = 0
        let limit = UInt64(Self.zxingS * 1e9)
        while DispatchTime.now().uptimeNanoseconds - t0 < limit && isActive() {
            let ok = bc_qr_scan(qr, Int32(i % count), &found) != 0
            if ok { decoded += 1 } else { missed += 1 }
            let now = DispatchTime.now().uptimeNanoseconds
            if now - lastReport > 120_000_000 {
                lastReport = now
                onProgress(Progress(phase: "ZXING", fraction: min(1, Double(now - t0) / Double(limit)), rate: Double(decoded) * 1e9 / Double(now - t0),
                                    frame: i % count, found: ok ? (0..<4).map { CGPoint(x: CGFloat(found[$0 * 2]), y: CGFloat(found[$0 * 2 + 1])) } : nil))
            }
            i += 1
        }
        let rate = Double(decoded) * 1e9 / Double(DispatchTime.now().uptimeNanoseconds - t0)

        // ── Vision (Apple's scanner) ──
        var visionRate: Double?
        var visionError: String?
        if isActive() {
            do { visionRate = try vision(isActive: isActive, onProgress: onProgress) } catch { visionError = error.localizedDescription }
        }
        return QrResult(rate: rate, decoded: decoded, missed: missed, referenceRate: visionRate, referenceName: "Vision", referenceError: visionError)
    }

    /** Vision's barcode detector on the frames as a camera hands them over (NV12, the luminance and neutral chroma). */
    private func vision(isActive: () -> Bool, onProgress: (Progress) -> Void) throws -> Double {
        let buffers = try (0..<count).map { try nv12($0) }
        let texts = (0..<count).map { text($0) }
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        func scan(_ j: Int) throws -> VNBarcodeObservation? {
            try VNImageRequestHandler(cvPixelBuffer: buffers[j], options: [:]).perform([request])
            return request.results?.first { $0.payloadStringValue == texts[j] }
        }
        _ = try scan(0)                                   // loads the detector
        var ok = 0, j = 0
        let t0 = DispatchTime.now().uptimeNanoseconds
        var lastReport: UInt64 = 0
        let limit = UInt64(Self.visionS * 1e9)
        while DispatchTime.now().uptimeNanoseconds - t0 < limit && isActive() {
            let hit = try scan(j % count)
            if hit != nil { ok += 1 }
            let now = DispatchTime.now().uptimeNanoseconds
            if now - lastReport > 120_000_000 {
                lastReport = now
                // normalised, origin bottom-left → frame pixels
                let pts = hit.map { h in [h.topLeft, h.topRight, h.bottomRight, h.bottomLeft].map {
                    CGPoint(x: $0.x * CGFloat(Self.width), y: (1 - $0.y) * CGFloat(Self.height)) } }
                onProgress(Progress(phase: "VISION", fraction: min(1, Double(now - t0) / Double(limit)), rate: Double(ok) * 1e9 / Double(now - t0),
                                    frame: j % count, found: pts))
            }
            j += 1
        }
        return Double(ok) * 1e9 / Double(DispatchTime.now().uptimeNanoseconds - t0)
    }

    private func nv12(_ i: Int) throws -> CVPixelBuffer {
        var pb: CVPixelBuffer?
        let attrs = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        guard CVPixelBufferCreate(nil, Self.width, Self.height, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, attrs, &pb) == kCVReturnSuccess,
              let pb else { throw GpuError(message: "can't make a camera frame") }
        CVPixelBufferLockBaseAddress(pb, [])
        defer { CVPixelBufferUnlockBaseAddress(pb, []) }
        let luma = bc_qr_luma(qr, Int32(i))!
        let y = CVPixelBufferGetBaseAddressOfPlane(pb, 0)!.assumingMemoryBound(to: UInt8.self)
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
        for row in 0..<Self.height { memcpy(y + row * yStride, luma + row * Self.width, Self.width) }
        let uv = CVPixelBufferGetBaseAddressOfPlane(pb, 1)!
        memset(uv, 128, CVPixelBufferGetBytesPerRowOfPlane(pb, 1) * CVPixelBufferGetHeightOfPlane(pb, 1))
        return pb
    }

    private func preview(_ i: Int) -> UIImage {
        let w = Self.width / 2, h = Self.height / 2
        let luma = bc_qr_luma(qr, Int32(i))!
        var px = [UInt8](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w { px[y * w + x] = luma[(y * 2) * Self.width + x * 2] } }
        let provider = CGDataProvider(data: Data(px) as CFData)!
        let cg = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: w, space: CGColorSpaceCreateDeviceGray(),
                         bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue), provider: provider, decode: nil,
                         shouldInterpolate: true, intent: .defaultIntent)!
        return UIImage(cgImage: cg)
    }
}
