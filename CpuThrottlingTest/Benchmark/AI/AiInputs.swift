import CoreGraphics
import ImageIO
import UIKit

/** A detection: box in the shown picture's pixels. */
struct AiBox { let left, top, right, bottom: CGFloat; let label: String; let score: Float }

/** What the stage shows for a model: what it saw and what it made of it. */
struct AiVisual {
    let test: AiTest
    let picture: UIImage                       // the input as a picture (spectrogram for sound)
    var result: UIImage? = nil                 // segmentation overlay, depth map or upscaled crop
    var boxes: [AiBox] = []
    var labels: [(String, Float)] = []         // top classes, 0..1
}

/**
 * The models' inputs, prepared once, and their outputs turned into pictures — the Android app's AiInputs:
 * the benchmark's own renders (media/) cut and scaled the same way, and the synthesized piano phrase.
 */
final class AiInputs {
    private let lake: CGImage
    private let city: CGImage                  // 2000×1500

    init() throws {
        lake = try Self.photo("media/photo_lake.webp")
        city = try Self.photo("media/photo_city.webp")
    }

    /** The photo at half size (Android decodes with inSampleSize 2). */
    private static func photo(_ path: String) throws -> CGImage {
        guard let url = Bundle.main.url(forResource: path, withExtension: nil),
              let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let full = CGImageSourceCreateImageAtIndex(src, 0, nil) else { throw AiError(message: "can't decode \(path)") }
        return scale(full, full.width / 2, full.height / 2)
    }

    private static func labels(_ name: String) -> [String] {
        guard let url = Bundle.main.url(forResource: "ai/\(name)_labels.txt", withExtension: nil),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return text.split(separator: "\n", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
    }

    /** The model's input bytes (NHWC RGB, 8-bit) and the picture shown while it runs. */
    func input(_ test: AiTest) throws -> ([UInt8], UIImage) {
        switch test {
        case .classify:
            let sq = Self.square(lake)
            return (Self.rgb(Self.scale(sq, 224, 224)), UIImage(cgImage: sq))
        case .detect:
            // letterboxed into 640×640 (grey bars), as YOLOX was trained
            let shown = Self.scale(city, 640, 480)
            let canvas = Self.draw(640, 640) { ctx in
                ctx.setFillColor(CGColor(gray: CGFloat(0x72) / 255, alpha: 1))
                ctx.fill(CGRect(x: 0, y: 0, width: 640, height: 640))
                ctx.draw(shown, in: CGRect(x: 0, y: 640 - 80 - 480, width: 640, height: 480))   // 80 px from the top (CG's y is up)
            }
            return (Self.rgb(canvas), UIImage(cgImage: shown))
        case .segment:
            let wide = city.cropping(to: CGRect(x: 0, y: city.height / 6, width: city.width, height: city.width / 2))!
            return (Self.rgb(Self.scale(wide, 2048, 1024)), UIImage(cgImage: Self.scale(wide, 1024, 512)))
        case .upscale:
            let small = Self.scale(city, 1000, 750)
            let crop = small.cropping(to: CGRect(x: 720, y: 330, width: 128, height: 128))!   // the SPA sign and shop lettering
            return (Self.rgb(crop), UIImage(cgImage: crop))
        case .depth:
            let sq = Self.square(city)
            return (Self.rgb(Self.scale(sq, 256, 256)), UIImage(cgImage: Self.scale(sq, 512, 512)))
        case .sound:
            guard let url = Bundle.main.url(forResource: "ai/yamnet_input.bin", withExtension: nil),
                  let data = try? Data(contentsOf: url) else { throw AiError(message: "yamnet_input.bin missing") }
            let q = [UInt8](data)
            return (q, spectrogram(q))
        }
    }

    /** Decodes the outputs of `test`'s last inference into what the stage draws. */
    func visual(_ test: AiTest, picture: UIImage, outputs out: [[UInt8]]) -> AiVisual {
        switch test {
        case .classify:
            return AiVisual(test: test, picture: picture, labels: top(dequant(out[0], 0.13067922, 76), Self.labels("mobilenet_v2"), 3, softmax: true))
        case .detect:
            return AiVisual(test: test, picture: picture, boxes: detections(out[0], out[1], out[2], Self.labels("yolox")))
        case .segment:
            return AiVisual(test: test, picture: picture, result: segmentation(out[0]))
        case .upscale:
            return AiVisual(test: test, picture: picture, result: upscaled(out[0]))
        case .depth:
            return AiVisual(test: test, picture: picture, result: depth(out[0]))
        case .sound:
            return AiVisual(test: test, picture: picture, labels: top(dequant(out[0], 0.7587276, 190), Self.labels("yamnet"), 3, softmax: false))
        }
    }

    // MARK: preparing

    private static func draw(_ w: Int, _ h: Int, _ body: (CGContext) -> Void) -> CGImage {
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        ctx.interpolationQuality = .high
        body(ctx)
        return ctx.makeImage()!
    }

    private static func scale(_ img: CGImage, _ w: Int, _ h: Int) -> CGImage {
        draw(w, h) { $0.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h)) }
    }

    private static func square(_ img: CGImage) -> CGImage {
        let s = min(img.width, img.height)
        return img.cropping(to: CGRect(x: (img.width - s) / 2, y: (img.height - s) / 2, width: s, height: s))!
    }

    /** RGB bytes, row by row from the top. */
    private static func rgb(_ img: CGImage) -> [UInt8] {
        let w = img.width, h = img.height
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        rgba.withUnsafeMutableBytes { p in
            let ctx = CGContext(data: p.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        var out = [UInt8](repeating: 0, count: w * h * 3)
        for i in 0..<(w * h) { out[i * 3] = rgba[i * 4]; out[i * 3 + 1] = rgba[i * 4 + 1]; out[i * 3 + 2] = rgba[i * 4 + 2] }
        return out
    }

    private static func image(_ argb: [UInt32], _ w: Int, _ h: Int) -> UIImage {
        // 0xAARRGGBB words, as on Android → premultiplied RGBA bytes
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        for i in 0..<(w * h) {
            let p = argb[i], a = (p >> 24) & 0xFF
            func c(_ v: UInt32) -> UInt8 { UInt8((v & 0xFF) * a / 255) }
            bytes[i * 4] = c(p >> 16); bytes[i * 4 + 1] = c(p >> 8); bytes[i * 4 + 2] = c(p); bytes[i * 4 + 3] = UInt8(a)
        }
        let provider = CGDataProvider(data: Data(bytes) as CFData)!
        let cg = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                         bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue), provider: provider,
                         decode: nil, shouldInterpolate: true, intent: .defaultIntent)!
        return UIImage(cgImage: cg)
    }

    private static func scaled(_ img: UIImage, _ w: Int, _ h: Int) -> UIImage { UIImage(cgImage: scale(img.cgImage!, w, h)) }

    /** YAMNet's 96 × 64 log-mel patch as a picture: time across, pitch up. */
    private func spectrogram(_ q: [UInt8]) -> UIImage {
        let frames = 96, bands = 64
        var px = [UInt32](repeating: 0, count: frames * bands)
        for t in 0..<frames {
            for m in 0..<bands {
                let v = Float(max(0, min(180, Int(q[t * bands + m]) - 60))) / 180
                px[(bands - 1 - m) * frames + t] = magma(v)
            }
        }
        return Self.scaled(Self.image(px, frames, bands), 960, 512)
    }

    // MARK: decoding

    private func dequant(_ q: [UInt8], _ scale: Float, _ zero: Int) -> [Float] { q.map { Float(Int($0) - zero) * scale } }

    private func top(_ v: [Float], _ names: [String], _ k: Int, softmax: Bool) -> [(String, Float)] {
        let p: [Float]
        if softmax {
            let m = v.max() ?? 0
            let e = v.map { exp($0 - m) }
            let s = e.reduce(0, +)
            p = e.map { $0 / s }
        } else {
            p = v.map { 1 / (1 + exp(-$0)) }
        }
        return p.indices.sorted { p[$0] > p[$1] }.prefix(k).map { (names.indices.contains($0) ? names[$0] : "#\($0)", p[$0]) }
    }

    /** YOLOX: 8400 candidate boxes (x1 y1 x2 y2 in the 640×640 input), scores and classes → kept after NMS. */
    private func detections(_ boxesQ: [UInt8], _ scoresQ: [UInt8], _ classes: [UInt8], _ names: [String]) -> [AiBox] {
        let b = dequant(boxesQ, 4.416495, 51)
        let scores = dequant(scoresQ, 0.0038126425, 0)
        let candidates = scores.indices.filter { scores[$0] > 0.35 }.sorted { scores[$0] > scores[$1] }
        func iou(_ i: Int, _ j: Int) -> Float {
            let x1 = max(b[i * 4], b[j * 4]), y1 = max(b[i * 4 + 1], b[j * 4 + 1])
            let x2 = min(b[i * 4 + 2], b[j * 4 + 2]), y2 = min(b[i * 4 + 3], b[j * 4 + 3])
            let inter = max(0, x2 - x1) * max(0, y2 - y1)
            let a = (b[i * 4 + 2] - b[i * 4]) * (b[i * 4 + 3] - b[i * 4 + 1])
            let c = (b[j * 4 + 2] - b[j * 4]) * (b[j * 4 + 3] - b[j * 4 + 1])
            return inter / max(1e-6, a + c - inter)
        }
        var kept: [Int] = []
        for i in candidates where kept.count < 25 {
            if !kept.contains(where: { classes[$0] == classes[i] && iou(i, $0) > 0.45 }) { kept.append(i) }
        }
        return kept.map { i in
            let c = Int(classes[i])
            return AiBox(left: CGFloat(b[i * 4]), top: CGFloat(b[i * 4 + 1] - 80), right: CGFloat(b[i * 4 + 2]), bottom: CGFloat(b[i * 4 + 3] - 80),
                         label: names.indices.contains(c) ? names[c] : "object", score: scores[i])
        }
    }

    /** FFNet: 128 × 256 × 19 Cityscapes class scores → a coloured, half-transparent class map. */
    private func segmentation(_ q: [UInt8]) -> UIImage {
        let h = 128, w = 256, c = 19
        var px = [UInt32](repeating: 0, count: w * h)
        for i in 0..<(w * h) {
            var best = 0, bestV = -1
            for k in 0..<c { let v = Int(q[i * c + k]); if v > bestV { bestV = v; best = k } }
            px[i] = (0xA0 << 24) | cityscapes[best]
        }
        return Self.image(px, w, h)
    }

    private func upscaled(_ q: [UInt8]) -> UIImage {
        let n = 512
        var px = [UInt32](repeating: 0, count: n * n)
        for i in 0..<(n * n) {
            func ch(_ k: Int) -> UInt32 { UInt32(max(0, min(255, Int(Float(Int(q[i * 3 + k]) - 25) * 0.004972266 * 255)))) }
            px[i] = (0xFF << 24) | (ch(0) << 16) | (ch(1) << 8) | ch(2)
        }
        return Self.image(px, n, n)
    }

    /** MiDaS: relative inverse depth (bigger = nearer) → a heat map, near warm. */
    private func depth(_ q: [UInt8]) -> UIImage {
        let n = 256
        let lo = Int(q.min() ?? 0), hi = Int(q.max() ?? 255)
        let px = q.prefix(n * n).map { magma(Float(Int($0) - lo) / Float(max(1, hi - lo))) }
        return Self.scaled(Self.image(Array(px), n, n), 512, 512)
    }

    /** A magma-like ramp: black → purple → orange → pale yellow. */
    private func magma(_ t: Float) -> UInt32 {
        let x = max(0, min(1, t))
        let r = UInt32(255 * min(1, x * 1.6))
        let g = UInt32(min(255, 255 * max(0, x * x * 1.1 - 0.05)))
        let b = UInt32(max(0, min(255, 255 * (0.35 + 0.5 * sin(x * 3.1) - 0.2 * x))))
        return (0xFF << 24) | (r << 16) | (g << 8) | b
    }

    /** Cityscapes' standard class colours (road, sidewalk, building, … bicycle). */
    private let cityscapes: [UInt32] = [
        0x804080, 0xF423E8, 0x464646, 0x66669C, 0xBE9999, 0x999999, 0xFAAA1E, 0xDCDC00, 0x6B8E23, 0x98FB98,
        0x4682B4, 0xDC143C, 0xFF0000, 0x00008E, 0x000046, 0x003C64, 0x005064, 0x0000E6, 0x770B20,
    ]
}
