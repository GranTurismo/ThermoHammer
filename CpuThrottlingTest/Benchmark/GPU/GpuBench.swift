import Metal
import QuartzCore
import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// GPU BENCHMARK — renders a scene offscreen at 1920×1080 (comparable across
// screen sizes) as fast as it goes, every frame timed from encode to GPU
// completion (Android: render + glFinish). The display only gets a copy of a
// finished frame now and then, outside the timing.
// ─────────────────────────────────────────────────────────────────────────────

protocol GpuScene: AnyObject {
    var output: MTLTexture! { get }
    var trianglesPerFrame: Int { get }
    /** Encodes one frame at timeline time `t` (s). */
    func encode(_ cb: MTLCommandBuffer, t: Float)
}

struct GpuError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

final class GpuBench {
    static let width = 1920, height = 1080
    static let shared = try? GpuBench()

    let device: MTLDevice
    let queue: MTLCommandQueue
    let library: MTLLibrary
    private let present: MTLRenderPipelineState
    private let lock = NSLock()
    private var layer: CAMetalLayer?

    init() throws {
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue(),
              let library = device.makeDefaultLibrary() else { throw GpuError(message: "Metal unavailable") }
        self.device = device
        self.queue = queue
        self.library = library
        let d = MTLRenderPipelineDescriptor()
        d.vertexFunction = library.makeFunction(name: "presentVS")
        d.fragmentFunction = library.makeFunction(name: "presentFS")
        d.colorAttachments[0].pixelFormat = .bgra8Unorm
        present = try device.makeRenderPipelineState(descriptor: d)
    }

    var name: String { device.name }

    /** The stage's view of the scene (nil = headless). */
    func attach(_ layer: CAMetalLayer?) {
        layer?.device = device
        layer?.pixelFormat = .bgra8Unorm
        layer?.framebufferOnly = true
        lock.withLock { self.layer = layer }
    }

    /**
     * Renders `seconds` of the scene's timeline (wall time, like Android) and returns every frame's timeline start
     * and render time. `onFrame` gets the timeline position and the fps of the last ~¼ s.
     */
    func run(_ scene: GpuScene, seconds: Float, isActive: () -> Bool, onFrame: (Float, Double) -> Void) throws -> ([Float], [Int64]) {
        var starts: [Float] = [], durs: [Int64] = []
        starts.reserveCapacity(8192); durs.reserveCapacity(8192)
        let start = DispatchTime.now().uptimeNanoseconds
        var lastPresent: UInt64 = 0, windowStart = start, windowFrames = 0
        var fps = 0.0
        while isActive() {
            let t0 = DispatchTime.now().uptimeNanoseconds
            let sceneT = Float(Double(t0 - start) / 1e9)
            if sceneT >= seconds { break }
            try autoreleasepool {
                guard let cb = queue.makeCommandBuffer() else { throw GpuError(message: "no command buffer") }
                scene.encode(cb, t: sceneT)
                cb.commit()
                cb.waitUntilCompleted()   // true frame completion — not just API enqueue
                if cb.status == .error { throw GpuError(message: cb.error?.localizedDescription ?? "GPU error") }
            }
            let done = DispatchTime.now().uptimeNanoseconds
            starts.append(sceneT)
            durs.append(Int64(done - t0))
            windowFrames += 1
            if done - windowStart > 250_000_000 {
                fps = Double(windowFrames) * 1e9 / Double(done - windowStart)
                windowStart = done; windowFrames = 0
            }
            if done - lastPresent > 33_000_000 {   // ~30 fps on screen, never timed
                lastPresent = done
                show(scene.output)
                onFrame(sceneT, fps)
            }
        }
        return (starts, durs)
    }

    private func show(_ texture: MTLTexture) {
        guard let layer = lock.withLock({ layer }), let drawable = layer.nextDrawable(),
              let cb = queue.makeCommandBuffer() else { return }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = drawable.texture
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        if let enc = cb.makeRenderCommandEncoder(descriptor: pass) {
            enc.setRenderPipelineState(present)
            enc.setFragmentTexture(texture, index: 0)
            enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            enc.endEncoding()
        }
        cb.present(drawable)
        cb.commit()
    }
}

/** A CAMetalLayer-backed view the GPU bench presents finished frames into. */
struct GpuFrameView: UIViewRepresentable {
    let bench: GpuBench

    final class LayerView: UIView {
        override class var layerClass: AnyClass { CAMetalLayer.self }
        var metalLayer: CAMetalLayer { layer as! CAMetalLayer }
        override func layoutSubviews() {
            super.layoutSubviews()
            metalLayer.drawableSize = CGSize(width: bounds.width * contentScaleFactor, height: bounds.height * contentScaleFactor)
        }
    }

    func makeUIView(context: Context) -> LayerView {
        let v = LayerView()
        v.contentScaleFactor = UIScreen.main.scale
        bench.attach(v.metalLayer)
        return v
    }

    func updateUIView(_ uiView: LayerView, context: Context) {}

    static func dismantleUIView(_ uiView: LayerView, coordinator: ()) {
        GpuBench.shared?.attach(nil)
    }
}
