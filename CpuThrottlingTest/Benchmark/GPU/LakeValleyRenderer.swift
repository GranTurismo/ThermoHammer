import Metal
import simd

// ─────────────────────────────────────────────────────────────────────────────
// LAKE VALLEY — GPU Test 1, the Metal port of the Android renderer
// (engine/gpu/LakeValleyRenderer.kt): the same asset, passes and workload.
//
//   Per frame:  sun shadow map 2048² → planar water reflection (½ res) →
//               4× MSAA main pass (terrain, ~14k instanced trees/rocks/reeds,
//               props, water, sky + clouds, birds, smoke, pollen) → resolve →
//               3-level bloom → composite.
//
//   Everything is drawn every frame — no culling, no LOD switching — and the
//   camera circles the lake looking outward, so the workload stays flat.
// ─────────────────────────────────────────────────────────────────────────────

struct LVFrame {
    var viewProj = Mat4.identity
    var invViewProj = Mat4.identity
    var shadowMat = Mat4.identity
    var sunDir = SIMD4<Float>()
    var sunColor = SIMD4<Float>()
    var camPos = SIMD4<Float>()
    var wind = SIMD4<Float>()
    var lake = SIMD4<Float>()
    var camRight = SIMD4<Float>()
    var camUp = SIMD4<Float>()
    var origin = SIMD4<Float>()
    var invViewport = SIMD2<Float>()
    var time: Float = 0
    var exposure: Float = 1.05
}

struct LVDraw { var meshHeight: Float; var sway: Float; var flutter: Float; var tint: Float; var seed: Float }
struct LVPost { var texel = SIMD2<Float>(); var step = SIMD2<Float>(); var time: Float = 0 }

final class LakeValleyRenderer: GpuScene {
    static let loopS: Float = 50              // camera loop — one lap of the lake
    private static let shadowSize = 2048
    private static let shadowExtent: Float = 620
    private static let birds = 80, smoke = 48, pollen = 600
    private static let waterGridX = 160, waterGridY = 100

    private struct GpuMesh { let vertices: MTLBuffer; let indices: MTLBuffer; let indexCount: Int; let indexType: MTLIndexType; let height: Float }
    private struct Batch {
        let mesh: GpuMesh; let instances: MTLBuffer; let count: Int
        var sway: Float = 0, flutter: Float = 0, tint: Float = 0, seed: Float = 0, cull = false
        var isTerrain = false
    }

    private let device: MTLDevice
    private let asset: LakeValleyAsset
    private let width: Int, height: Int

    // pipelines
    private var objMain, objMirror, terrainMain, terrainMirror, depthOnly: MTLRenderPipelineState!
    private var water, sky, skyMirror, birds, smoke, pollen: MTLRenderPipelineState!
    private var bright, down, blur, composite: MTLRenderPipelineState!
    private var depthLess, depthLessEqual, depthTestOnly, depthOff: MTLDepthStencilState!

    // geometry
    private var mainBatches: [Batch] = [], shadowBatches: [Batch] = [], mirrorBatches: [Batch] = []
    private var boatInstances: MTLBuffer!
    private var waterXY: MTLBuffer!, waterIndices: MTLBuffer!
    private var waterIndexCount = 0
    private var birdVerts: MTLBuffer!

    // targets
    private var shadowTex: MTLTexture!
    private var reflTex: MTLTexture!, reflDepth: MTLTexture!
    private var msaaColor: MTLTexture?, msaaDepth: MTLTexture!
    private var sceneTex: MTLTexture!
    private var bloomTex: [MTLTexture] = [], tmpTex: [MTLTexture] = []
    private(set) var output: MTLTexture!
    private(set) var trianglesPerFrame = 0

    private var frame = LVFrame()
    private var mirrorFrame = LVFrame()
    private var lightFrame = LVFrame()
    private let chimney: SIMD3<Float>
    private let boatHome: SIMD3<Float>

    init(device: MTLDevice, library: MTLLibrary, asset: LakeValleyAsset, width: Int, height: Int) throws {
        self.device = device
        self.asset = asset
        self.width = width
        self.height = height
        chimney = asset.anchors["chimney"] ?? SIMD3(asset.lakeCenterX, asset.lakeCenterY + 80, 8)
        boatHome = asset.anchors["boat"] ?? SIMD3(asset.lakeCenterX, asset.lakeCenterY, asset.waterZ)
        try buildTargets()
        try buildPipelines(library)
        buildGeometry()
    }

    // MARK: setup

    private static func objectVertexDescriptor() -> MTLVertexDescriptor {
        let d = MTLVertexDescriptor()
        d.attributes[0].format = .float3; d.attributes[0].offset = 0; d.attributes[0].bufferIndex = 0
        d.attributes[1].format = .char4Normalized; d.attributes[1].offset = 12; d.attributes[1].bufferIndex = 0
        d.attributes[2].format = .uchar4Normalized; d.attributes[2].offset = 16; d.attributes[2].bufferIndex = 0
        for r in 0..<3 {
            d.attributes[3 + r].format = .float4; d.attributes[3 + r].offset = r * 16; d.attributes[3 + r].bufferIndex = 1
        }
        d.layouts[0].stride = LakeValleyAsset.vertexBytes
        d.layouts[1].stride = 48
        d.layouts[1].stepFunction = .perInstance
        return d
    }

    private func buildPipelines(_ library: MTLLibrary) throws {
        let samples = msaaColor?.sampleCount ?? 1
        func fn(_ name: String, mirror: Bool = false, terrain: Bool = false, smoke: Bool = false, threshold: Bool = false) throws -> MTLFunction {
            let c = MTLFunctionConstantValues()
            var flags = [mirror, terrain, smoke, threshold]
            for i in 0..<4 { c.setConstantValue(&flags[i], type: .bool, index: i) }
            return try library.makeFunction(name: name, constantValues: c)
        }
        func pipe(_ vs: MTLFunction, _ fs: MTLFunction?, color: MTLPixelFormat?, depth: Bool, samples: Int = 1,
                  vertex: MTLVertexDescriptor? = nil, premultiplied: Bool = false) throws -> MTLRenderPipelineState {
            let d = MTLRenderPipelineDescriptor()
            d.vertexFunction = vs
            d.fragmentFunction = fs
            d.vertexDescriptor = vertex
            d.rasterSampleCount = samples
            if let color {
                d.colorAttachments[0].pixelFormat = color
                if premultiplied {
                    d.colorAttachments[0].isBlendingEnabled = true
                    d.colorAttachments[0].sourceRGBBlendFactor = .one
                    d.colorAttachments[0].sourceAlphaBlendFactor = .one
                    d.colorAttachments[0].destinationRGBBlendFactor = .oneMinusSourceAlpha
                    d.colorAttachments[0].destinationAlphaBlendFactor = .oneMinusSourceAlpha
                }
            }
            if depth { d.depthAttachmentPixelFormat = .depth32Float }
            return try device.makeRenderPipelineState(descriptor: d)
        }
        let vd = Self.objectVertexDescriptor()
        let rgba = MTLPixelFormat.rgba8Unorm
        objMain = try pipe(fn("lvObjectVS"), fn("lvObjectFS"), color: rgba, depth: true, samples: samples, vertex: vd)
        terrainMain = try pipe(fn("lvObjectVS"), fn("lvObjectFS", terrain: true), color: rgba, depth: true, samples: samples, vertex: vd)
        objMirror = try pipe(fn("lvObjectVS"), fn("lvObjectFS", mirror: true), color: rgba, depth: true, vertex: vd)
        terrainMirror = try pipe(fn("lvObjectVS"), fn("lvObjectFS", mirror: true, terrain: true), color: rgba, depth: true, vertex: vd)
        depthOnly = try pipe(fn("lvDepthVS"), nil, color: nil, depth: true, vertex: vd)
        water = try pipe(fn("lvWaterVS"), fn("lvWaterFS"), color: rgba, depth: true, samples: samples)
        sky = try pipe(fn("lvSkyVS"), fn("lvSkyFS"), color: rgba, depth: true, samples: samples)
        skyMirror = try pipe(fn("lvSkyVS"), fn("lvSkyFS", mirror: true), color: rgba, depth: true)
        birds = try pipe(fn("lvBirdVS"), fn("lvBirdFS"), color: rgba, depth: true, samples: samples)
        smoke = try pipe(fn("lvParticleVS", smoke: true), fn("lvParticleFS", smoke: true), color: rgba, depth: true, samples: samples, premultiplied: true)
        pollen = try pipe(fn("lvParticleVS"), fn("lvParticleFS"), color: rgba, depth: true, samples: samples, premultiplied: true)
        bright = try pipe(fn("lvPostVS"), fn("lvDownFS", threshold: true), color: rgba, depth: false)
        down = try pipe(fn("lvPostVS"), fn("lvDownFS"), color: rgba, depth: false)
        blur = try pipe(fn("lvPostVS"), fn("lvBlurFS"), color: rgba, depth: false)
        composite = try pipe(fn("lvPostVS"), fn("lvCompositeFS"), color: rgba, depth: false)

        func depthState(_ compare: MTLCompareFunction, write: Bool) -> MTLDepthStencilState {
            let d = MTLDepthStencilDescriptor()
            d.depthCompareFunction = compare
            d.isDepthWriteEnabled = write
            return device.makeDepthStencilState(descriptor: d)!
        }
        depthLess = depthState(.less, write: true)
        depthLessEqual = depthState(.lessEqual, write: true)
        depthTestOnly = depthState(.less, write: false)
        depthOff = depthState(.always, write: false)
    }

    private func buffer<T>(_ values: [T]) -> MTLBuffer {
        values.withUnsafeBytes { device.makeBuffer(bytes: $0.baseAddress!, length: max($0.count, 16), options: .storageModeShared)! }
    }

    private func buffer(_ data: Data) -> MTLBuffer {
        data.withUnsafeBytes { device.makeBuffer(bytes: $0.baseAddress!, length: $0.count, options: .storageModeShared)! }
    }

    private func upload(_ m: LakeValleyAsset.Mesh) -> GpuMesh {
        let v = buffer(m.vertices)
        if m.isPolar {
            let idx = Self.polarIndices(m.polarSegments, m.polarRings)
            return GpuMesh(vertices: v, indices: buffer(idx), indexCount: idx.count, indexType: .uint32, height: m.height)
        }
        return GpuMesh(vertices: v, indices: buffer(m.indices!), indexCount: m.indexCount, indexType: .uint16, height: m.height)
    }

    /** Centre vertex + rings × segments, matching the exporter's vertex order. */
    private static func polarIndices(_ n: Int, _ rings: Int) -> [UInt32] {
        var out: [UInt32] = []
        out.reserveCapacity(3 * n + 6 * n * (rings - 1))
        for j in 0..<n { out += [0, UInt32(1 + j), UInt32(1 + (j + 1) % n)] }
        for r in 0..<(rings - 1) {
            let a = 1 + r * n, b = a + n
            for j in 0..<n {
                let j1 = (j + 1) % n
                out += [UInt32(a + j), UInt32(b + j), UInt32(b + j1), UInt32(a + j), UInt32(b + j1), UInt32(a + j1)]
            }
        }
        return out
    }

    /** {pos, euler XYZ, scale} → transform rows (Blender XYZ euler: R = Rz·Ry·Rx). */
    private static func instanceRows(_ t: [Float], _ count: Int) -> [Float] {
        var out = [Float](repeating: 0, count: count * 12)
        for i in 0..<count {
            let o = i * 9
            let cx = cos(t[o + 3]), sx = sin(t[o + 3]), cy = cos(t[o + 4]), sy = sin(t[o + 4]), cz = cos(t[o + 5]), sz = sin(t[o + 5])
            let kx = t[o + 6], ky = t[o + 7], kz = t[o + 8]
            let b = i * 12
            out[b] = cz * cy * kx; out[b + 1] = (cz * sy * sx - sz * cx) * ky; out[b + 2] = (cz * sy * cx + sz * sx) * kz; out[b + 3] = t[o]
            out[b + 4] = sz * cy * kx; out[b + 5] = (sz * sy * sx + cz * cx) * ky; out[b + 6] = (sz * sy * cx - cz * sx) * kz; out[b + 7] = t[o + 1]
            out[b + 8] = -sy * kx; out[b + 9] = cy * sx * ky; out[b + 10] = cy * cx * kz; out[b + 11] = t[o + 2]
        }
        return out
    }

    private func buildGeometry() {
        let meshes = asset.meshes.map(upload)
        func mesh(_ name: String) -> GpuMesh { meshes[asset.meshIndex(name)] }
        let identity = buffer([Float(1), 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0])
        var terrain = Batch(mesh: mesh("terrain"), instances: identity, count: 1, cull: true)
        terrain.isTerrain = true
        // instanced groups: sway (m at the top) · flutter · tint · casts shadow · culls back faces
        let looks: [String: (Float, Float, Float, Bool, Bool)] = [
            "pine_a": (0.45, 0.06, 0.18, true, false), "pine_b": (0.45, 0.06, 0.18, true, false),
            "broadleaf": (0.55, 0.10, 0.25, true, false), "pine_far": (0.30, 0, 0.15, false, false),
            "reeds": (0.25, 0.03, 0.20, false, false)]
        let farMesh = mesh("pine_far")
        let trees: Set<String> = ["pine_a", "pine_b", "broadleaf", "pine_far"]
        mainBatches = [terrain]; shadowBatches = [terrain]; mirrorBatches = [terrain]
        for (gi, g) in asset.groups.enumerated() {
            let look = looks[g.name] ?? (0, 0, 0.12, true, true)           // rocks
            let inst = buffer(Self.instanceRows(g.transforms, g.count))
            let b = Batch(mesh: meshes[g.mesh], instances: inst, count: g.count, sway: look.0, flutter: look.1, tint: look.2,
                          seed: Float(gi) * 17.3, cull: look.4)
            mainBatches.append(b)
            if look.3 { shadowBatches.append(b) }
            // the water only needs silhouettes: every tree is reflected with the distant-pine mesh
            if trees.contains(g.name) {
                mirrorBatches.append(Batch(mesh: farMesh, instances: inst, count: g.count, sway: look.0, tint: look.2, seed: Float(gi) * 17.3))
            }
        }
        for name in ["cabin", "dock"] {
            let b = Batch(mesh: mesh(name), instances: identity, count: 1)
            mainBatches.append(b); shadowBatches.append(b); mirrorBatches.append(b)
        }
        boatInstances = device.makeBuffer(length: 48, options: .storageModeShared)!
        let boat = Batch(mesh: mesh("boat"), instances: boatInstances, count: 1)
        mainBatches.append(boat); shadowBatches.append(boat); mirrorBatches.append(boat)

        // water grid
        let x0 = asset.lakeCenterX - asset.lakeRadiusX * 1.3, y0 = asset.lakeCenterY - asset.lakeRadiusY * 1.35
        let dx = asset.lakeRadiusX * 2.6 / Float(Self.waterGridX), dy = asset.lakeRadiusY * 2.7 / Float(Self.waterGridY)
        var xy: [Float] = []
        for j in 0...Self.waterGridY { for i in 0...Self.waterGridX { xy += [x0 + Float(i) * dx, y0 + Float(j) * dy] } }
        var idx: [UInt16] = []
        let row = Self.waterGridX + 1
        for j in 0..<Self.waterGridY {
            for i in 0..<Self.waterGridX {
                let a = j * row + i
                idx += [UInt16(a), UInt16(a + 1), UInt16(a + row + 1), UInt16(a), UInt16(a + row + 1), UInt16(a + row)]
            }
        }
        waterXY = buffer(xy); waterIndices = buffer(idx); waterIndexCount = idx.count
        // birds: body + two wings; w = how much the vertex flaps
        birdVerts = buffer([Float(0), 0.45, 0, 0, -0.07, -0.35, 0, 0, 0.07, -0.35, 0, 0,
                            -0.04, 0.15, 0, 0, -0.04, -0.12, 0, 0, -0.95, -0.05, 0, 1,
                            0.04, 0.15, 0, 0, 0.95, -0.05, 0, 1, 0.04, -0.12, 0, 0])

        func tris(_ list: [Batch]) -> Int { list.reduce(0) { $0 + $1.mesh.indexCount / 3 * $1.count } }
        trianglesPerFrame = tris(mainBatches) + tris(shadowBatches) + tris(mirrorBatches) + 2 * Self.waterGridX * Self.waterGridY + Self.birds * 3
    }

    private func texture(_ format: MTLPixelFormat, _ w: Int, _ h: Int, samples: Int = 1, transient: Bool = false) -> MTLTexture {
        let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: w, height: h, mipmapped: false)
        d.usage = transient ? .renderTarget : [.renderTarget, .shaderRead]
        d.storageMode = .private
        if samples > 1 { d.textureType = .type2DMultisample; d.sampleCount = samples }
        if transient, device.supportsFamily(.apple1) {
            d.storageMode = .memoryless
            if let t = device.makeTexture(descriptor: d) { return t }
            d.storageMode = .private
        }
        return device.makeTexture(descriptor: d)!
    }

    private func buildTargets() throws {
        shadowTex = texture(.depth32Float, Self.shadowSize, Self.shadowSize)
        reflTex = texture(.rgba8Unorm, width / 2, height / 2)
        reflDepth = texture(.depth32Float, width / 2, height / 2, transient: true)
        sceneTex = texture(.rgba8Unorm, width, height)
        let samples = device.supportsTextureSampleCount(4) ? 4 : 1
        if samples > 1 { msaaColor = texture(.rgba8Unorm, width, height, samples: samples, transient: true) }
        msaaDepth = texture(.depth32Float, width, height, samples: samples, transient: true)
        for l in 0..<3 {
            let w = width >> (l + 1), h = height >> (l + 1)
            bloomTex.append(texture(.rgba8Unorm, w, h)); tmpTex.append(texture(.rgba8Unorm, w, h))
        }
        output = texture(.rgba8Unorm, width, height)
    }

    // MARK: per frame

    func encode(_ cb: MTLCommandBuffer, t: Float) {
        animate(t)
        shadowPass(cb)
        reflectionPass(cb)
        mainPass(cb)
        postPass(cb)
    }

    private func animate(_ t: Float) {
        let th = (t.truncatingRemainder(dividingBy: Self.loopS)) / Self.loopS * 2 * .pi
        let cx = asset.lakeCenterX, cy = asset.lakeCenterY, wz = asset.waterZ
        // camera: a lap of the lake, skimming 1–12 m above the water, looking out at the shore
        let rx = asset.lakeRadiusX * 0.58, ry = asset.lakeRadiusY * 0.53
        let cam = SIMD3<Float>(cx + rx * cos(th), cy + ry * sin(th), wz + 6.5 + 5.0 * sin(2 * th + 0.7) + 1.0 * sin(5 * th))
        var tang = SIMD2<Float>(-rx * sin(th), ry * cos(th)), out = SIMD2<Float>(ry * cos(th), rx * sin(th))
        tang = simd_normalize(tang); out = simd_normalize(out)
        let phi = Float((38.0 + 18.0 * sin(Double(th))) * .pi / 180)
        let d = tang * cos(phi) + out * sin(phi)
        let pitch = Float((3.0 + 3.0 * sin(3.0 * Double(th))) * .pi / 180)
        let roll = Float(4.0 * sin(2.0 * Double(th)) * .pi / 180)
        // up = Z tilted toward the camera's right by the bank angle
        let up = SIMD3<Float>(-d.y * sin(roll), d.x * sin(roll), cos(roll))
        let view = Mat4.lookAt(eye: cam, center: cam + SIMD3(d.x * 300, d.y * 300, 300 * tan(pitch)), up: up)
        let proj = Mat4.perspective(fovyDeg: 50, aspect: Float(width) / Float(height), near: 0.5, far: 6000)
        let viewProj = proj * view

        // sun: late afternoon, drifting a little over the lap so shadows move
        let el = Float((24.0 + 5.0 * sin(Double(th) + 1.0)) * .pi / 180)
        let az = Float((-105.0 + 20.0 * sin(Double(th))) * .pi / 180)
        let sun = SIMD3<Float>(sin(az) * cos(el), cos(az) * cos(el), sin(el))
        // wind: veering, gusty
        let wa = 0.6 + 0.3 * sin(th)
        let wind = SIMD4<Float>(cos(wa), sin(wa), 0.55 + 0.3 * sin(3 * th + 0.4), 0)
        // sun shadow: ortho box over the lake, refit as the sun moves
        let fz = wz + 30
        let lightView = Mat4.lookAt(eye: SIMD3(cx + sun.x * 1200, cy + sun.y * 1200, fz + sun.z * 1200), center: SIMD3(cx, cy, fz), up: SIMD3(0, 0, 1))
        let e = Self.shadowExtent
        let lightViewProj = Mat4.ortho(left: -e, right: e, bottom: -e, top: e, near: 200, far: 2400) * lightView

        frame.viewProj = viewProj
        frame.invViewProj = viewProj.inverse
        frame.shadowMat = Mat4.shadowBias * lightViewProj
        frame.sunDir = SIMD4(sun, 0)
        frame.sunColor = SIMD4(1.0 * 3.1, 0.83 * 3.1, 0.64 * 3.1, 0)
        frame.camPos = SIMD4(cam, 1)
        frame.wind = wind
        frame.lake = SIMD4(cx, cy, wz, 0)
        frame.camRight = SIMD4(view.row0, 0)
        frame.camUp = SIMD4(view.row1, 0)
        frame.invViewport = SIMD2(1 / Float(width), 1 / Float(height))
        frame.time = t
        mirrorFrame = frame
        mirrorFrame.viewProj = proj * view * Mat4.mirrorZ(wz)   // the world mirrored in the water plane
        mirrorFrame.invViewport = SIMD2(2 / Float(width), 2 / Float(height))
        lightFrame = frame
        lightFrame.viewProj = lightViewProj

        // rowboat: drifts a slow circle off its mooring, bobbing and rolling on the waves
        let ba = th + 1.3
        let bx = boatHome.x + 16 * cos(ba), by = boatHome.y + 10 * sin(ba)
        let heading = atan2(10 * cos(ba), -16 * sin(ba)) - .pi / 2
        let bob = 0.06 * sin(t * 1.3), rollB = 0.05 * sin(t * 0.9), pitchB = 0.035 * sin(t * 1.1 + 1)
        let ch = cos(heading), sh = sin(heading), cr = cos(rollB), sr = sin(rollB), cp = cos(pitchB), sp = sin(pitchB)
        // R = Rz(heading) · Rx(pitch) · Ry(roll)
        let rows: [Float] = [ch * cr - sh * sp * sr, -sh * cp, ch * sr + sh * sp * cr, bx,
                             sh * cr + ch * sp * sr, ch * cp, sh * sr - ch * sp * cr, by,
                             -cp * sr, sp, cp * cr, wz - 0.06 + bob]
        rows.withUnsafeBytes { boatInstances.contents().copyMemory(from: $0.baseAddress!, byteCount: 48) }
    }

    private func setFrame(_ enc: MTLRenderCommandEncoder, _ f: inout LVFrame) {
        enc.setVertexBytes(&f, length: MemoryLayout<LVFrame>.stride, index: 2)
        enc.setFragmentBytes(&f, length: MemoryLayout<LVFrame>.stride, index: 2)
    }

    private func draw(_ enc: MTLRenderCommandEncoder, _ b: Batch) {
        var d = LVDraw(meshHeight: max(b.mesh.height, 0.1), sway: b.sway, flutter: b.flutter, tint: b.tint, seed: b.seed)
        enc.setVertexBytes(&d, length: MemoryLayout<LVDraw>.stride, index: 3)
        enc.setCullMode(b.cull ? .back : .none)
        enc.setVertexBuffer(b.mesh.vertices, offset: 0, index: 0)
        enc.setVertexBuffer(b.instances, offset: 0, index: 1)
        enc.drawIndexedPrimitives(type: .triangle, indexCount: b.mesh.indexCount, indexType: b.mesh.indexType,
                                  indexBuffer: b.mesh.indices, indexBufferOffset: 0, instanceCount: b.count)
    }

    private func drawScene(_ enc: MTLRenderCommandEncoder, terrain: MTLRenderPipelineState, object: MTLRenderPipelineState,
                           _ f: inout LVFrame, _ list: [Batch]) {
        setFrame(enc, &f)
        enc.setFragmentTexture(shadowTex, index: 1)
        enc.setDepthStencilState(depthLess)
        enc.setRenderPipelineState(terrain)
        for b in list where b.isTerrain { draw(enc, b) }
        enc.setRenderPipelineState(object)
        for b in list where !b.isTerrain { draw(enc, b) }
    }

    private func drawSky(_ enc: MTLRenderCommandEncoder, _ p: MTLRenderPipelineState) {
        enc.setRenderPipelineState(p)
        enc.setCullMode(.none)
        enc.setDepthStencilState(depthLessEqual)
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    }

    private func shadowPass(_ cb: MTLCommandBuffer) {
        let pass = MTLRenderPassDescriptor()
        pass.depthAttachment.texture = shadowTex
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.clearDepth = 1
        pass.depthAttachment.storeAction = .store
        let enc = cb.makeRenderCommandEncoder(descriptor: pass)!
        enc.setFrontFacing(.counterClockwise)
        enc.setDepthBias(4, slopeScale: 2, clamp: 0)
        enc.setRenderPipelineState(depthOnly)
        enc.setDepthStencilState(depthLess)
        setFrame(enc, &lightFrame)
        for b in shadowBatches { draw(enc, b) }
        enc.endEncoding()
    }

    private func reflectionPass(_ cb: MTLCommandBuffer) {
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = reflTex
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        pass.colorAttachments[0].storeAction = .store
        pass.depthAttachment.texture = reflDepth
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.clearDepth = 1
        pass.depthAttachment.storeAction = .dontCare
        let enc = cb.makeRenderCommandEncoder(descriptor: pass)!
        enc.setFrontFacing(.clockwise)                      // mirroring flips the winding
        drawScene(enc, terrain: terrainMirror, object: objMirror, &mirrorFrame, mirrorBatches)
        drawSky(enc, skyMirror)
        enc.endEncoding()
    }

    private func mainPass(_ cb: MTLCommandBuffer) {
        let pass = MTLRenderPassDescriptor()
        if let msaaColor {
            pass.colorAttachments[0].texture = msaaColor
            pass.colorAttachments[0].resolveTexture = sceneTex
            pass.colorAttachments[0].storeAction = .multisampleResolve
        } else {
            pass.colorAttachments[0].texture = sceneTex
            pass.colorAttachments[0].storeAction = .store
        }
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        pass.depthAttachment.texture = msaaDepth
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.clearDepth = 1
        pass.depthAttachment.storeAction = .dontCare
        let enc = cb.makeRenderCommandEncoder(descriptor: pass)!
        enc.setFrontFacing(.counterClockwise)
        drawScene(enc, terrain: terrainMain, object: objMain, &frame, mainBatches)

        enc.setRenderPipelineState(water)
        enc.setCullMode(.none)
        enc.setVertexBuffer(waterXY, offset: 0, index: 0)
        enc.setFragmentTexture(reflTex, index: 2)
        enc.drawIndexedPrimitives(type: .triangle, indexCount: waterIndexCount, indexType: .uint16, indexBuffer: waterIndices, indexBufferOffset: 0)

        drawSky(enc, sky)

        enc.setRenderPipelineState(birds)
        enc.setDepthStencilState(depthLess)
        enc.setVertexBuffer(birdVerts, offset: 0, index: 0)
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 9, instanceCount: Self.birds)

        // translucent last: premultiplied alpha, depth-tested but not written
        enc.setDepthStencilState(depthTestOnly)
        for (p, origin, count) in [(smoke, chimney, Self.smoke), (pollen, SIMD3(frame.camPos.x, frame.camPos.y, frame.camPos.z), Self.pollen)] {
            var f = frame
            f.origin = SIMD4(origin, 1)
            setFrame(enc, &f)
            enc.setRenderPipelineState(p!)
            enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: count)
        }
        enc.endEncoding()
    }

    private func fullscreen(_ cb: MTLCommandBuffer, _ p: MTLRenderPipelineState, into target: MTLTexture, sources: [MTLTexture], _ post: LVPost) {
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = target
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        let enc = cb.makeRenderCommandEncoder(descriptor: pass)!
        enc.setRenderPipelineState(p)
        var post = post
        enc.setFragmentBytes(&post, length: MemoryLayout<LVPost>.stride, index: 0)
        for (i, s) in sources.enumerated() { enc.setFragmentTexture(s, index: i) }
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        enc.endEncoding()
    }

    private func postPass(_ cb: MTLCommandBuffer) {
        var src: MTLTexture = sceneTex
        for l in 0..<3 {
            let w = Float(bloomTex[l].width), h = Float(bloomTex[l].height)
            fullscreen(cb, l == 0 ? bright : down, into: bloomTex[l], sources: [src],
                       LVPost(texel: SIMD2(0.5 / Float(src.width), 0.5 / Float(src.height))))
            fullscreen(cb, blur, into: tmpTex[l], sources: [bloomTex[l]], LVPost(step: SIMD2(1 / w, 0)))
            fullscreen(cb, blur, into: bloomTex[l], sources: [tmpTex[l]], LVPost(step: SIMD2(0, 1 / h)))
            src = bloomTex[l]
        }
        fullscreen(cb, composite, into: output, sources: [sceneTex] + bloomTex, LVPost(time: frame.time))
    }
}
