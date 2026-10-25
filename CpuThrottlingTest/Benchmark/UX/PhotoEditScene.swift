import ImageIO
import Metal
import MetalKit

// ─────────────────────────────────────────────────────────────────────────────
// PHOTO EDITING — UX Test 4, the Metal port of engine/gpu/PhotoEditScene.kt:
// a photo editor's filter chain over the 12 MP Lake Valley photo (the bundled
// media/photo_lake.webp, 4000×3000) every frame at full resolution — fisheye
// lens with bicubic resampling and colour fringing, a 129-texel Gaussian blur,
// portrait bokeh, then unsharp mask + 33³ LUT grade + vignette + grain. Each
// stage's result is shown in its own panel of a 2×2 preview. One frame = one
// full edit; the score is megapixels through the chain per second.
// ─────────────────────────────────────────────────────────────────────────────

struct PhotoParams {
    var size = SIMD2<Float>(), dir = SIMD2<Float>(), texel = SIMD2<Float>()
    var k: Float = 0, radius: Float = 0, focusY: Float = 0, amount: Float = 0, grade: Float = 0, time: Float = 0
}

final class PhotoEditScene: GpuScene {
    static let panels = ["FISHEYE LENS", "GAUSSIAN BLUR", "PORTRAIT BOKEH", "SHARPEN + COLOUR GRADE"]
    private static let lutSize = 33
    private static let blurRadius = 64                  // texels each side
    private static let blurTaps = blurRadius / 2 + 1    // centre + bilinear pairs
    private static let bokehRadius: Float = 60          // px at full strength
    static let panelW = 704, panelH = 528               // each panel: the 4:3 photo inside a 960×540 quadrant

    private let outW: Int, outH: Int
    private let src: MTLTexture
    private var tex: [MTLTexture] = []                  // A, B, C
    private let lut: MTLTexture
    private(set) var output: MTLTexture!
    let trianglesPerFrame = 6
    let photoW: Int, photoH: Int
    var megapixels: Double { Double(photoW) * Double(photoH) / 1e6 }

    private let lens, blur, bokeh, finish, show: MTLRenderPipelineState
    private var blurOff = [Float](repeating: 0, count: PhotoEditScene.blurTaps)
    private var blurW = [Float](repeating: 0, count: PhotoEditScene.blurTaps)

    init(device: MTLDevice, library: MTLLibrary, width: Int, height: Int) throws {
        outW = width; outH = height
        guard let url = Bundle.main.url(forResource: "media/photo_lake.webp", withExtension: nil),
              let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw GpuError(message: "can't decode photo_lake.webp") }
        src = try MTKTextureLoader(device: device).newTexture(cgImage: image, options: [
            .SRGB: false, .textureStorageMode: MTLStorageMode.private.rawValue, .textureUsage: MTLTextureUsage.shaderRead.rawValue])
        photoW = src.width; photoH = src.height
        for _ in 0..<3 {
            let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: photoW, height: photoH, mipmapped: false)
            d.usage = [.renderTarget, .shaderRead]
            d.storageMode = .private
            tex.append(device.makeTexture(descriptor: d)!)
        }
        let od = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: width, height: height, mipmapped: false)
        od.usage = [.renderTarget, .shaderRead]
        od.storageMode = .private
        output = device.makeTexture(descriptor: od)!
        lut = Self.gradeLut(device)
        func pipe(_ fs: String) throws -> MTLRenderPipelineState {
            let d = MTLRenderPipelineDescriptor()
            d.vertexFunction = library.makeFunction(name: "photoVS")
            d.fragmentFunction = library.makeFunction(name: fs)
            d.colorAttachments[0].pixelFormat = .rgba8Unorm
            return try device.makeRenderPipelineState(descriptor: d)
        }
        lens = try pipe("photoLensFS"); blur = try pipe("photoBlurFS"); bokeh = try pipe("photoBokehFS")
        finish = try pipe("photoFinishFS"); show = try pipe("photoShowFS")
        gaussian()
    }

    func encode(_ cb: MTLCommandBuffer, t: Float) {
        let (A, B, C) = (0, 1, 2)
        var p = PhotoParams()
        p.size = SIMD2(Float(photoW), Float(photoH))
        p.texel = SIMD2(1 / Float(photoW), 1 / Float(photoH))
        // LENS: src → A
        p.k = 0.75 + 0.2 * sin(t * 0.7)
        pass(cb, lens, into: tex[A], [src], &p)
        panel(cb, 0, tex[A], clear: true)
        // BLUR: A → B (horizontal) → C (vertical)
        p.dir = SIMD2(p.texel.x, 0); pass(cb, blur, into: tex[B], [tex[A]], &p, blurTables: true)
        p.dir = SIMD2(0, p.texel.y); pass(cb, blur, into: tex[C], [tex[B]], &p, blurTables: true)
        panel(cb, 1, tex[C])
        // BOKEH: A (+ its blur C) → B
        p.radius = Self.bokehRadius * Float(photoW) / 4000
        p.focusY = 0.62 + 0.14 * sin(t * 0.45)
        pass(cb, bokeh, into: tex[B], [tex[A], tex[C]], &p)
        panel(cb, 2, tex[B])
        // FINISH: B + C + LUT → A
        p.amount = 0.55 + 0.25 * sin(t * 1.1)
        p.grade = 0.8 + 0.2 * sin(t * 0.5)
        p.time = t
        pass(cb, finish, into: tex[A], [tex[B], tex[C], lut], &p)
        panel(cb, 3, tex[A])
    }

    private func pass(_ cb: MTLCommandBuffer, _ pipe: MTLRenderPipelineState, into target: MTLTexture, _ sources: [MTLTexture],
                      _ p: inout PhotoParams, blurTables: Bool = false) {
        let rp = MTLRenderPassDescriptor()
        rp.colorAttachments[0].texture = target
        rp.colorAttachments[0].loadAction = .dontCare
        rp.colorAttachments[0].storeAction = .store
        let enc = cb.makeRenderCommandEncoder(descriptor: rp)!
        enc.setRenderPipelineState(pipe)
        enc.setFragmentBytes(&p, length: MemoryLayout<PhotoParams>.stride, index: 0)
        if blurTables {
            enc.setFragmentBytes(&blurOff, length: blurOff.count * 4, index: 1)
            enc.setFragmentBytes(&blurW, length: blurW.count * 4, index: 2)
        }
        for (i, s) in sources.enumerated() { enc.setFragmentTexture(s, index: i) }
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        enc.endEncoding()
    }

    /** Draws a stage's result, scaled down, into its quadrant of the 16:9 output. */
    private func panel(_ cb: MTLCommandBuffer, _ index: Int, _ texture: MTLTexture, clear: Bool = false) {
        let rp = MTLRenderPassDescriptor()
        rp.colorAttachments[0].texture = output
        rp.colorAttachments[0].loadAction = clear ? .clear : .load
        rp.colorAttachments[0].clearColor = MTLClearColor(red: 0.043, green: 0.055, blue: 0.071, alpha: 1)
        rp.colorAttachments[0].storeAction = .store
        let enc = cb.makeRenderCommandEncoder(descriptor: rp)!
        let qw = outW / 2, qh = outH / 2
        let pw = Self.panelW * outW / 1920, ph = Self.panelH * outH / 1080
        enc.setViewport(MTLViewport(originX: Double((index % 2) * qw + (qw - pw) / 2), originY: Double((index / 2) * qh + (qh - ph) / 2),
                                    width: Double(pw), height: Double(ph), znear: 0, zfar: 1))
        enc.setRenderPipelineState(show)
        enc.setFragmentTexture(texture, index: 0)
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        enc.endEncoding()
    }

    /** σ ≈ 21 texels over ±64, folded into 32 bilinear pairs a side (+ the centre). */
    private func gaussian() {
        let sigma = Double(Self.blurRadius) / 3
        let g = (0...Self.blurRadius).map { exp(-Double($0 * $0) / (2 * sigma * sigma)) }
        let norm = g[0] + 2 * g[1...].reduce(0, +)
        blurOff[0] = 0; blurW[0] = Float(g[0] / norm)
        for k in 1..<Self.blurTaps {
            let a = 2 * k - 1, b = 2 * k
            let w = g[a] + g[b]
            blurW[k] = Float(w / norm)
            blurOff[k] = Float((Double(a) * g[a] + Double(b) * g[b]) / w)
        }
    }

    /** A filmic grade: gentle S-curve, teal shadows, warm highlights, a little more saturation. */
    private static func gradeLut(_ device: MTLDevice) -> MTLTexture {
        let n = lutSize
        var data = [UInt8](repeating: 255, count: n * n * n * 4)
        func curve(_ x: Double) -> Double { x + 0.35 * (x * x * (3 - 2 * x) - x) }
        var o = 0
        for b in 0..<n {
            for g in 0..<n {
                for r in 0..<n {
                    var cr = curve(Double(r) / Double(n - 1)), cg = curve(Double(g) / Double(n - 1)), cb = curve(Double(b) / Double(n - 1))
                    let l = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb
                    let sh = (1 - l) * (1 - l), hi = l * l
                    cr += -0.03 * sh + 0.07 * hi; cg += 0.03 * sh + 0.025 * hi; cb += 0.06 * sh - 0.05 * hi
                    let l2 = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb
                    cr = l2 + (cr - l2) * 1.15; cg = l2 + (cg - l2) * 1.15; cb = l2 + (cb - l2) * 1.15
                    for v in [cr, cg, cb] { data[o] = UInt8(Int(max(0, min(1, v)) * 255 + 0.5)); o += 1 }
                    o += 1
                }
            }
        }
        let d = MTLTextureDescriptor()
        d.textureType = .type3D
        d.pixelFormat = .rgba8Unorm
        d.width = n; d.height = n; d.depth = n
        d.usage = .shaderRead
        let t = device.makeTexture(descriptor: d)!
        t.replace(region: MTLRegionMake3D(0, 0, 0, n, n, n), mipmapLevel: 0, slice: 0, withBytes: data, bytesPerRow: n * 4, bytesPerImage: n * n * 4)
        return t
    }
}
