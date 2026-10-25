import Metal
import simd

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY — GPU Test 2, the Metal port of the Android renderer
// (engine/gpu/NeonCityRenderer.kt): the same asset, simulation, light grid,
// passes and knobs.
//
//   Per frame:  light grid (≈870 static + ≈300 moving lights) → searchlight
//               shadow map → mirrored street reflection (½ res, HDR, mips) →
//               4× MSAA HDR main pass (≈2.7M triangles: buildings with
//               ray-traced rooms behind the windows, signs, traffic, people,
//               trains; light halos, steam, rain, splashes) → resolve →
//               volumetric fog march (½ res) → combine → 6-level bloom +
//               anamorphic streaks → motion blur, tonemap, grade.
//
//   Neon City RT (GPU Test 3, `rayTracing`, the port of the Android app's
//   Vulkan renderer): the same scene with rays instead of the mirror render
//   and the searchlight's shadow map — reflections on the wet street, glass,
//   paint and chrome, shadows of the searchlight and of each point's two
//   strongest lights, and searchlight shafts in the fog. One acceleration
//   structure per mesh; the city-wide one (static instances + the moving
//   cars, people and trains) is rebuilt every frame.
// ─────────────────────────────────────────────────────────────────────────────

struct NCFrame {
    var viewProj = Mat4.identity
    var invViewProj = Mat4.identity
    var prevViewProj = Mat4.identity
    var searchMat = Mat4.identity
    var camPos = SIMD4<Float>()
    var camRight = SIMD4<Float>()
    var camUp = SIMD4<Float>()
    var camFwd = SIMD4<Float>()
    var origin = SIMD4<Float>()
    var groups: (Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float,
                 Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float) =
        (1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
    var invViewport = SIMD2<Float>()
    var time: Float = 0
    var frame: Float = 0
    var maxMoving: Int32 = 48
    var maxStatic: Int32 = 48
    var reflTaps: Int32 = 8
    var fogSteps: Int32 = 24
    var fogLights: Int32 = 6
    var blurSamples: Int32 = 8
    var exposure: Float = 1
}

struct NCDraw { var actor: Int32; var instanced: Int32; var seed: Float }
struct NCPost { var texel = SIMD2<Float>(); var step = SIMD2<Float>(); var weight: Float = 1 }

final class NeonCityRenderer: GpuScene {
    private static let mirrorMoving: Int32 = 4, mirrorStatic: Int32 = 8
    private static let rain = 36000, splashes = 3000, steamPuffs = 24
    private static let searchShadow = 1024
    private static let bloomLevels = 6
    private static let maxDynamicLights = 400
    private static let searchCos: Float = 0.982      // searchlight cone, cos of the half-angle

    private struct GpuMesh { let vertices: MTLBuffer; let indices: MTLBuffer; let indexCount: Int; let indexType: MTLIndexType }
    private final class Batch {
        let mesh: GpuMesh; let meshIndex: Int; let instanceBuffer: MTLBuffer; var instances: Int
        let actor: Int32; let instanced: Bool; let seed: Float; let shadow: Bool
        let sim: NeonCitySim.ActorBatch?
        let rows: [Float]                  // static instances' records (for the ray-tracing instances)
        init(mesh: GpuMesh, meshIndex: Int, instanceBuffer: MTLBuffer, instances: Int, actor: Int32, instanced: Bool, seed: Float, shadow: Bool,
             sim: NeonCitySim.ActorBatch? = nil, rows: [Float] = []) {
            self.mesh = mesh; self.meshIndex = meshIndex; self.instanceBuffer = instanceBuffer; self.instances = instances; self.actor = actor
            self.instanced = instanced; self.seed = seed; self.shadow = shadow; self.sim = sim; self.rows = rows
        }
    }

    /** One ray-tracing instance as the shaders read it (NCRTInstance in NeonCity.metal). */
    private struct RTInstance {
        var row0 = SIMD4<Float>(), row1 = SIMD4<Float>(), row2 = SIMD4<Float>(), extra = SIMD4<Float>()
        var mesh: Int32 = 0, actor: Int32 = 0, instanced: Int32 = 0, seed: Float = 0
    }

    private let device: MTLDevice
    private let width: Int, height: Int
    let rayTracing: Bool
    private let sim: NeonCitySim
    private let grid: NeonLightGrid

    private var objMain, objMirror, depthOnly, sky, skyMirror, rain, splash, glow, steam: MTLRenderPipelineState!
    private var fog, combine, downFirst, down, up, streak, final: MTLRenderPipelineState!
    private var objMainRT, fogRT: MTLRenderPipelineState!

    // ray tracing: per-mesh structures, the city-wide one and what the shaders read about each instance
    private var gpuMeshes: [GpuMesh] = []
    private var blas: [MTLAccelerationStructure] = []
    private var tlas: MTLAccelerationStructure!
    private var tlasDescriptor: MTLInstanceAccelerationStructureDescriptor!
    private var tlasScratch: MTLBuffer!
    private var instanceDescriptors: MTLBuffer!
    private var rtInstances: MTLBuffer!
    private var meshRefs: MTLBuffer!
    private var staticInstances = 0
    private var rtResources: [MTLResource] = []
    private var depthLess, depthLessEqual, depthTestOnly: MTLDepthStencilState!

    private var batches: [Batch] = []
    private var steamOrigins: [SIMD4<Float>] = []
    private var lightsBuf: MTLBuffer!, cellsBuf: MTLBuffer!, indexBuf: MTLBuffer!

    private var searchTex, reflTex, reflDepth, msaaColor, msaaDepth, sceneTex, depthTex, fogTex, hdrTex: MTLTexture!
    private var bloomTex: [MTLTexture] = [], streakTex: [MTLTexture] = []
    private(set) var output: MTLTexture!
    private(set) var trianglesPerFrame = 0

    private var frame = NCFrame()
    private var mirrorFrame = NCFrame()
    private var searchFrame = NCFrame()
    private var prevViewProj: Mat4?
    private var frameIndex = 0

    init(device: MTLDevice, library: MTLLibrary, asset: NeonCityAsset, width: Int, height: Int, rayTracing: Bool = false) throws {
        self.device = device
        self.width = width
        self.height = height
        self.rayTracing = rayTracing
        sim = NeonCitySim(asset)
        grid = NeonLightGrid(asset.table("lights"), maxDynamic: Self.maxDynamicLights)
        try buildTargets()
        try buildPipelines(library)
        buildGeometry(asset)
        if rayTracing { try buildAccelerationStructures() }
        lightsBuf = device.makeBuffer(length: grid.lights.count * 4, options: .storageModeShared)
        cellsBuf = device.makeBuffer(length: grid.cells.count * 4, options: .storageModeShared)
        indexBuf = device.makeBuffer(length: grid.indices.count * 2, options: .storageModeShared)
    }

    // MARK: setup

    private static func vertexDescriptor() -> MTLVertexDescriptor {
        let d = MTLVertexDescriptor()
        d.attributes[0].format = .float3; d.attributes[0].offset = 0; d.attributes[0].bufferIndex = 0
        d.attributes[1].format = .char4Normalized; d.attributes[1].offset = 12; d.attributes[1].bufferIndex = 0
        d.attributes[2].format = .uchar4Normalized; d.attributes[2].offset = 16; d.attributes[2].bufferIndex = 0
        for r in 0..<5 {
            d.attributes[3 + r].format = .float4; d.attributes[3 + r].offset = r * 16; d.attributes[3 + r].bufferIndex = 1
        }
        d.layouts[0].stride = NeonCityAsset.vertexBytes
        d.layouts[1].stride = NeonCitySim.instanceFloats * 4
        d.layouts[1].stepFunction = .perInstance
        return d
    }

    private func buildPipelines(_ library: MTLLibrary) throws {
        func fn(_ name: String, mirror: Bool = false, depthOnly: Bool = false, threshold: Bool = false) throws -> MTLFunction {
            let c = MTLFunctionConstantValues()
            var flags = [mirror, depthOnly, threshold]
            for i in 0..<3 { c.setConstantValue(&flags[i], type: .bool, index: i) }
            return try library.makeFunction(name: name, constantValues: c)
        }
        enum Blend { case none, premultiplied, additive }
        func pipe(_ vs: MTLFunction, _ fs: MTLFunction?, color: MTLPixelFormat?, depth: Bool, samples: Int = 1,
                  vertex: MTLVertexDescriptor? = nil, blend: Blend = .none) throws -> MTLRenderPipelineState {
            let d = MTLRenderPipelineDescriptor()
            d.vertexFunction = vs
            d.fragmentFunction = fs
            d.vertexDescriptor = vertex
            d.rasterSampleCount = samples
            if let color {
                let a = d.colorAttachments[0]!
                a.pixelFormat = color
                if blend != .none {
                    a.isBlendingEnabled = true
                    a.sourceRGBBlendFactor = .one; a.sourceAlphaBlendFactor = .one
                    a.destinationRGBBlendFactor = blend == .additive ? .one : .oneMinusSourceAlpha
                    a.destinationAlphaBlendFactor = blend == .additive ? .one : .oneMinusSourceAlpha
                }
            }
            if depth { d.depthAttachmentPixelFormat = .depth32Float }
            return try device.makeRenderPipelineState(descriptor: d)
        }
        let vd = Self.vertexDescriptor()
        let hdr = MTLPixelFormat.rgba16Float
        let s = msaaColor.sampleCount
        objMain = try pipe(fn("ncObjectVS"), fn("ncObjectFS"), color: hdr, depth: true, samples: s, vertex: vd)
        objMirror = try pipe(fn("ncObjectVS", mirror: true), fn("ncObjectFS", mirror: true), color: hdr, depth: true, vertex: vd)
        depthOnly = try pipe(fn("ncDepthVS", depthOnly: true), nil, color: nil, depth: true, vertex: vd)
        sky = try pipe(fn("ncSkyVS"), fn("ncSkyFS"), color: hdr, depth: true, samples: s)
        skyMirror = try pipe(fn("ncSkyVS"), fn("ncSkyFS", mirror: true), color: hdr, depth: true)
        rain = try pipe(fn("ncRainVS"), fn("ncRainFS"), color: hdr, depth: true, samples: s, blend: .premultiplied)
        splash = try pipe(fn("ncSplashVS"), fn("ncSplashFS"), color: hdr, depth: true, samples: s, blend: .premultiplied)
        glow = try pipe(fn("ncGlowVS"), fn("ncGlowFS"), color: hdr, depth: true, samples: s, blend: .premultiplied)
        steam = try pipe(fn("ncSteamVS"), fn("ncSteamFS"), color: hdr, depth: true, samples: s, blend: .premultiplied)
        fog = try pipe(fn("ncPostVS"), fn("ncFogFS"), color: hdr, depth: false)
        combine = try pipe(fn("ncPostVS"), fn("ncCombineFS"), color: hdr, depth: false)
        downFirst = try pipe(fn("ncPostVS"), fn("ncDownFS", threshold: true), color: hdr, depth: false)
        down = try pipe(fn("ncPostVS"), fn("ncDownFS"), color: hdr, depth: false)
        up = try pipe(fn("ncPostVS"), fn("ncUpFS"), color: hdr, depth: false, blend: .additive)
        streak = try pipe(fn("ncPostVS"), fn("ncStreakFS"), color: hdr, depth: false)
        final = try pipe(fn("ncPostVS"), fn("ncFinalFS"), color: .rgba8Unorm, depth: false)
        if rayTracing {
            objMainRT = try pipe(fn("ncObjectVS"), fn("ncObjectRTFS"), color: hdr, depth: true, samples: s, vertex: vd)
            fogRT = try pipe(fn("ncPostVS"), fn("ncFogRTFS"), color: hdr, depth: false)
        }
        func depthState(_ compare: MTLCompareFunction, write: Bool) -> MTLDepthStencilState {
            let d = MTLDepthStencilDescriptor()
            d.depthCompareFunction = compare
            d.isDepthWriteEnabled = write
            return device.makeDepthStencilState(descriptor: d)!
        }
        depthLess = depthState(.less, write: true)
        depthLessEqual = depthState(.lessEqual, write: true)
        depthTestOnly = depthState(.less, write: false)
    }

    private func buffer(_ data: Data) -> MTLBuffer {
        data.withUnsafeBytes { device.makeBuffer(bytes: $0.baseAddress!, length: max($0.count, 16), options: .storageModeShared)! }
    }

    private func buffer(_ floats: [Float]) -> MTLBuffer {
        floats.withUnsafeBytes { device.makeBuffer(bytes: $0.baseAddress!, length: max($0.count, 16), options: .storageModeShared)! }
    }

    /** {pos, euler XYZ, scale} → instance records (Blender XYZ euler: R = Rz·Ry·Rx), extras zero. */
    private static func instanceRows(_ t: [Float], _ count: Int) -> [Float] {
        let stride = NeonCitySim.instanceFloats
        var out = [Float](repeating: 0, count: count * stride)
        for i in 0..<count {
            let o = i * 9
            let cx = cos(t[o + 3]), sx = sin(t[o + 3]), cy = cos(t[o + 4]), sy = sin(t[o + 4]), cz = cos(t[o + 5]), sz = sin(t[o + 5])
            let kx = t[o + 6], ky = t[o + 7], kz = t[o + 8]
            let b = i * stride
            out[b] = cz * cy * kx; out[b + 1] = (cz * sy * sx - sz * cx) * ky; out[b + 2] = (cz * sy * cx + sz * sx) * kz; out[b + 3] = t[o]
            out[b + 4] = sz * cy * kx; out[b + 5] = (sz * sy * sx + cz * cx) * ky; out[b + 6] = (sz * sy * cx - cz * sx) * kz; out[b + 7] = t[o + 1]
            out[b + 8] = -sy * kx; out[b + 9] = cy * sx * ky; out[b + 10] = cy * cx * kz; out[b + 11] = t[o + 2]
        }
        return out
    }

    private func buildGeometry(_ asset: NeonCityAsset) {
        let meshes = asset.meshes.map { m in
            GpuMesh(vertices: buffer(m.vertices), indices: buffer(m.indices), indexCount: m.indexCount,
                    indexType: m.wideIndices ? .uint32 : .uint16)
        }
        gpuMeshes = meshes
        var identity = [Float](repeating: 0, count: NeonCitySim.instanceFloats)
        identity[0] = 1; identity[5] = 1; identity[10] = 1
        let identityBuf = buffer(identity)
        for i in asset.chunkMeshes {
            batches.append(Batch(mesh: meshes[i], meshIndex: i, instanceBuffer: identityBuf, instances: 1, actor: 0, instanced: false, seed: 0,
                                 shadow: true, rows: identity))
        }
        // small street props would only speckle the searchlight's shadow map
        let noShadow: Set<String> = ["bollard", "bin", "hydrant", "bench", "lantern", "ac_unit"]
        for (gi, g) in asset.groups.enumerated() {
            let far = g.name.hasPrefix("tower_")
            let rows = Self.instanceRows(g.transforms, g.count)
            batches.append(Batch(mesh: meshes[g.mesh], meshIndex: g.mesh, instanceBuffer: buffer(rows), instances: g.count,
                                 actor: 0, instanced: true, seed: Float(gi) * 7.31, shadow: !far && !noShadow.contains(g.name), rows: rows))
        }
        let actors: [(NeonCitySim.ActorBatch, Int32)] = sim.carBatches.map { ($0, 1) } + sim.pedBatches.map { ($0, 2) } +
            [(sim.trainHeads, 3), (sim.trainCarsBatch, 3), (sim.droneBatch, 3), (sim.policeBatch, 3)]
        for (ab, kind) in actors {
            let capacity = ab.data.count / NeonCitySim.instanceFloats
            let mi = asset.meshIndex(ab.mesh)
            if capacity == 0 || mi < 0 { continue }
            let buf = device.makeBuffer(length: ab.data.count * 4, options: .storageModeShared)!
            batches.append(Batch(mesh: meshes[mi], meshIndex: mi, instanceBuffer: buf, instances: 0, actor: kind, instanced: true, seed: 0,
                                 shadow: kind != 2, sim: ab))
        }
        let steam = asset.table("steam")
        for r in 0..<steam.rows { let row = steam.row(r); steamOrigins.append(SIMD4(row[0], row[1], row[2], 1)) }
        let main = batches.reduce(0) { $0 + $1.mesh.indexCount / 3 * max($1.instances, ($1.sim?.data.count ?? 0) / NeonCitySim.instanceFloats) }
        let shadow = batches.filter(\.shadow).reduce(0) { $0 + $1.mesh.indexCount / 3 * max($1.instances, 1) }
        // with rays there is no mirror render and no shadow map: the main pass only
        trianglesPerFrame = (rayTracing ? main : main * 2 + shadow) + 2 * (Self.rain + Self.splashes)
    }

    /** A structure per mesh (built once), and the city-wide one sized for every static instance plus a full set of actors. */
    private func buildAccelerationStructures() throws {
        guard let queue = device.makeCommandQueue(), let cb = queue.makeCommandBuffer() else { throw GpuError(message: "no command queue") }
        var scratchSize = 0
        var descriptors: [MTLPrimitiveAccelerationStructureDescriptor] = []
        for m in gpuMeshes {
            let geo = MTLAccelerationStructureTriangleGeometryDescriptor()
            geo.vertexBuffer = m.vertices
            geo.vertexStride = NeonCityAsset.vertexBytes
            geo.vertexFormat = .float3
            geo.indexBuffer = m.indices
            geo.indexType = m.indexType
            geo.triangleCount = m.indexCount / 3
            geo.opaque = true
            let d = MTLPrimitiveAccelerationStructureDescriptor()
            d.geometryDescriptors = [geo]
            let sizes = device.accelerationStructureSizes(descriptor: d)
            guard let a = device.makeAccelerationStructure(size: sizes.accelerationStructureSize) else { throw GpuError(message: "out of memory for ray tracing") }
            blas.append(a)
            descriptors.append(d)
            scratchSize = max(scratchSize, sizes.buildScratchBufferSize)
        }
        guard let scratch = device.makeBuffer(length: max(scratchSize, 16), options: .storageModePrivate) else { throw GpuError(message: "out of memory for ray tracing") }
        // one encoder per build: they share the scratch buffer, so they must not overlap
        for (a, d) in zip(blas, descriptors) {
            let enc = cb.makeAccelerationStructureCommandEncoder()!
            enc.build(accelerationStructure: a, descriptor: d, scratchBuffer: scratch, scratchBufferOffset: 0)
            enc.endEncoding()
        }
        // compacted copies: about half the memory
        let sizesBuf = device.makeBuffer(length: 4 * blas.count, options: .storageModeShared)!
        let sizeEnc = cb.makeAccelerationStructureCommandEncoder()!
        for (i, a) in blas.enumerated() { sizeEnc.writeCompactedSize(accelerationStructure: a, buffer: sizesBuf, offset: 4 * i) }
        sizeEnc.endEncoding()
        cb.commit()
        cb.waitUntilCompleted()
        if let e = cb.error { throw GpuError(message: "building the ray-tracing structures failed: \(e.localizedDescription)") }
        let compacted = sizesBuf.contents().bindMemory(to: UInt32.self, capacity: blas.count)
        if let ccb = queue.makeCommandBuffer() {
            let enc = ccb.makeAccelerationStructureCommandEncoder()!
            blas = blas.enumerated().map { i, a in
                guard let c = device.makeAccelerationStructure(size: Int(compacted[i])) else { return a }
                enc.copyAndCompact(sourceAccelerationStructure: a, destinationAccelerationStructure: c)
                return c
            }
            enc.endEncoding()
            ccb.commit()
            ccb.waitUntilCompleted()
        }

        // what the shaders read about each mesh: where its vertices and indices are
        let refs = device.makeBuffer(length: 24 * gpuMeshes.count, options: .storageModeShared)!
        let p = refs.contents()
        for (i, m) in gpuMeshes.enumerated() {
            p.storeBytes(of: m.vertices.gpuAddress, toByteOffset: i * 24, as: UInt64.self)
            p.storeBytes(of: m.indices.gpuAddress, toByteOffset: i * 24 + 8, as: UInt64.self)
            p.storeBytes(of: UInt32(m.indexType == .uint32 ? 1 : 0), toByteOffset: i * 24 + 16, as: UInt32.self)
        }
        meshRefs = refs
        rtResources = blas + gpuMeshes.flatMap { [$0.vertices, $0.indices] as [MTLResource] }

        // instances: the static city first (written once), then room for every actor
        let capacity = batches.reduce(0) { $0 + ($1.sim.map { $0.data.count / NeonCitySim.instanceFloats } ?? $1.instances) }
        instanceDescriptors = device.makeBuffer(length: MemoryLayout<MTLAccelerationStructureInstanceDescriptor>.stride * capacity, options: .storageModeShared)
        rtInstances = device.makeBuffer(length: MemoryLayout<RTInstance>.stride * capacity, options: .storageModeShared)
        for b in batches where b.sim == nil {
            for k in 0..<b.instances { writeInstance(staticInstances, b, b.rows, k * NeonCitySim.instanceFloats); staticInstances += 1 }
        }
        let d = MTLInstanceAccelerationStructureDescriptor()
        d.instancedAccelerationStructures = blas
        d.instanceCount = capacity
        d.instanceDescriptorBuffer = instanceDescriptors
        d.instanceDescriptorType = .default
        let sizes = device.accelerationStructureSizes(descriptor: d)
        guard let t = device.makeAccelerationStructure(size: sizes.accelerationStructureSize),
              let ts = device.makeBuffer(length: max(sizes.buildScratchBufferSize, 16), options: .storageModePrivate) else {
            throw GpuError(message: "out of memory for ray tracing")
        }
        tlas = t
        tlasScratch = ts
        tlasDescriptor = d
        rtResources.append(t)
    }

    /** Instance `slot` of the city-wide structure: batch `b`'s record at `o` in `rows` (3 transform rows, then extras). */
    private func writeInstance(_ slot: Int, _ b: Batch, _ rows: UnsafePointer<Float>, _ o: Int) {
        func r(_ i: Int) -> Float { rows[o + i] }
        var d = MTLAccelerationStructureInstanceDescriptor()
        d.transformationMatrix = MTLPackedFloat4x3(columns: (MTLPackedFloat3Make(r(0), r(4), r(8)), MTLPackedFloat3Make(r(1), r(5), r(9)),
                                                             MTLPackedFloat3Make(r(2), r(6), r(10)), MTLPackedFloat3Make(r(3), r(7), r(11))))
        d.options = .opaque
        d.mask = 0xFF
        d.intersectionFunctionTableOffset = 0
        d.accelerationStructureIndex = UInt32(b.meshIndex)
        instanceDescriptors.contents().storeBytes(of: d, toByteOffset: slot * MemoryLayout<MTLAccelerationStructureInstanceDescriptor>.stride,
                                                  as: MTLAccelerationStructureInstanceDescriptor.self)
        var i = RTInstance()
        i.row0 = SIMD4(r(0), r(1), r(2), r(3)); i.row1 = SIMD4(r(4), r(5), r(6), r(7)); i.row2 = SIMD4(r(8), r(9), r(10), r(11))
        if b.sim != nil { i.extra = SIMD4(r(12), r(13), r(14), r(15)) }
        i.mesh = Int32(b.meshIndex); i.actor = b.actor; i.instanced = b.instanced ? 1 : 0; i.seed = b.seed
        rtInstances.contents().storeBytes(of: i, toByteOffset: slot * MemoryLayout<RTInstance>.stride, as: RTInstance.self)
    }

    private func writeInstance(_ slot: Int, _ b: Batch, _ rows: [Float], _ o: Int) {
        rows.withUnsafeBufferPointer { writeInstance(slot, b, $0.baseAddress!, o) }
    }

    /** The moving actors into the city-wide structure, rebuilt for this frame. */
    private func rebuildTlas(_ cb: MTLCommandBuffer) {
        var slot = staticInstances
        for b in batches {
            guard let ab = b.sim, ab.count > 0 else { continue }
            ab.data.withUnsafeBufferPointer { p in
                for k in 0..<ab.count { writeInstance(slot, b, p.baseAddress!, k * NeonCitySim.instanceFloats); slot += 1 }
            }
        }
        tlasDescriptor.instanceCount = slot
        let enc = cb.makeAccelerationStructureCommandEncoder()!
        enc.build(accelerationStructure: tlas, descriptor: tlasDescriptor, scratchBuffer: tlasScratch, scratchBufferOffset: 0)
        enc.endEncoding()
    }

    private func bindRays(_ enc: MTLRenderCommandEncoder) {
        enc.setFragmentAccelerationStructure(tlas, bufferIndex: 7)
        enc.setFragmentBuffer(meshRefs, offset: 0, index: 8)
        enc.setFragmentBuffer(rtInstances, offset: 0, index: 9)
        enc.useResources(rtResources, usage: .read, stages: .fragment)
    }

    private func texture(_ format: MTLPixelFormat, _ w: Int, _ h: Int, samples: Int = 1, transient: Bool = false, mips: Bool = false) -> MTLTexture {
        let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: w, height: h, mipmapped: mips)
        d.usage = transient ? .renderTarget : [.renderTarget, .shaderRead]
        d.storageMode = .private
        if samples > 1 { d.textureType = .type2DMultisample; d.sampleCount = samples }
        if transient {
            d.storageMode = .memoryless
            if let t = device.makeTexture(descriptor: d) { return t }
            d.storageMode = .private
        }
        return device.makeTexture(descriptor: d)!
    }

    private func buildTargets() throws {
        searchTex = texture(.depth32Float, Self.searchShadow, Self.searchShadow)
        reflTex = texture(.rgba16Float, width / 2, height / 2, mips: true)
        reflDepth = texture(.depth32Float, width / 2, height / 2, transient: true)
        let samples = device.supportsTextureSampleCount(4) ? 4 : 1
        msaaColor = texture(.rgba16Float, width, height, samples: samples, transient: true)
        msaaDepth = texture(.depth32Float, width, height, samples: samples, transient: true)
        sceneTex = texture(.rgba16Float, width, height)
        depthTex = texture(.depth32Float, width, height)
        fogTex = texture(.rgba16Float, width / 2, height / 2)
        hdrTex = texture(.rgba16Float, width, height)
        for l in 0..<Self.bloomLevels { bloomTex.append(texture(.rgba16Float, max(1, width >> (l + 1)), max(1, height >> (l + 1)))) }
        for _ in 0..<2 { streakTex.append(texture(.rgba16Float, width / 4, height / 4)) }
        output = texture(.rgba8Unorm, width, height)
    }

    // MARK: per frame

    func encode(_ cb: MTLCommandBuffer, t: Float) {
        sim.update(Double(t))
        camera(t)
        lights(t)
        uploadActors()
        if rayTracing {
            rebuildTlas(cb)
        } else {
            searchShadowPass(cb)
            reflectionPass(cb)
        }
        mainPass(cb)
        fogPass(cb)
        postPass(cb)
        prevViewProj = frame.viewProj
        frameIndex += 1
    }

    private func camera(_ t: Float) {
        let e = SIMD3(sim.eye[0], sim.eye[1], sim.eye[2]), g = SIMD3(sim.target[0], sim.target[1], sim.target[2])
        let view = Mat4.lookAt(eye: e, center: g, up: SIMD3(0, 0, 1))
        let proj = Mat4.perspective(fovyDeg: sim.fovDeg, aspect: Float(width) / Float(height), near: 0.25, far: 4000)
        let viewProj = proj * view
        frame.viewProj = viewProj
        frame.invViewProj = viewProj.inverse
        frame.prevViewProj = prevViewProj ?? viewProj
        frame.camPos = SIMD4(e, 1)
        frame.camRight = SIMD4(view.row0, 0)
        frame.camUp = SIMD4(view.row1, 0)
        frame.camFwd = SIMD4(-SIMD3(view.columns.0.z, view.columns.1.z, view.columns.2.z), 0)
        frame.time = t
        frame.frame = Float(frameIndex % 64)
        frame.invViewport = SIMD2(1 / Float(width), 1 / Float(height))
        withUnsafeMutableBytes(of: &frame.groups) { g in sim.groups.withUnsafeBytes { g.copyMemory(from: $0) } }

        // the street's mirror image: z' = −z
        mirrorFrame = frame
        mirrorFrame.viewProj = proj * view * Mat4.mirrorZ(0)
        mirrorFrame.camPos = SIMD4(e.x, e.y, -e.z, 1)
        mirrorFrame.maxMoving = Self.mirrorMoving
        mirrorFrame.maxStatic = Self.mirrorStatic

        // searchlight frustum (the cone plus a margin), biased into texture space for shadow lookups
        let p = SIMD3(sim.searchPos[0], sim.searchPos[1], sim.searchPos[2]), a = SIMD3(sim.searchAim[0], sim.searchAim[1], sim.searchAim[2])
        let searchView = Mat4.lookAt(eye: p, center: a, up: SIMD3(0, 1, 0))
        let fov = Float(2 * acos(Double(Self.searchCos)) * 180 / .pi) + 6
        let searchViewProj = Mat4.perspective(fovyDeg: fov, aspect: 1, near: 2, far: 140) * searchView
        frame.searchMat = Mat4.shadowBias * searchViewProj
        mirrorFrame.searchMat = frame.searchMat
        searchFrame = frame
        searchFrame.viewProj = searchViewProj
    }

    /** Moving lights for this frame, then the merged grid copied to its buffers. */
    private func lights(_ t: Float) {
        grid.setMovingLights(sim, time: t, searchCos: Self.searchCos)
        grid.lights.withUnsafeBytes { lightsBuf.contents().copyMemory(from: $0.baseAddress!, byteCount: $0.count) }
        grid.cells.withUnsafeBytes { cellsBuf.contents().copyMemory(from: $0.baseAddress!, byteCount: $0.count) }
        if grid.indices.count * 2 > indexBuf.length {
            indexBuf = device.makeBuffer(length: grid.indices.count * 2, options: .storageModeShared)
        }
        grid.indices.withUnsafeBytes { indexBuf.contents().copyMemory(from: $0.baseAddress!, byteCount: grid.indexCount * 2) }
    }

    private func uploadActors() {
        for b in batches {
            guard let ab = b.sim else { continue }
            b.instances = ab.count
            if ab.count == 0 { continue }
            ab.data.withUnsafeBytes { b.instanceBuffer.contents().copyMemory(from: $0.baseAddress!, byteCount: ab.count * NeonCitySim.instanceFloats * 4) }
        }
    }

    private func bindGrid(_ enc: MTLRenderCommandEncoder, vertex: Bool) {
        if vertex {
            enc.setVertexBuffer(lightsBuf, offset: 0, index: 4)
            enc.setVertexBuffer(cellsBuf, offset: 0, index: 5)
            enc.setVertexBuffer(indexBuf, offset: 0, index: 6)
            enc.setVertexTexture(searchTex, index: 0)
        }
        enc.setFragmentBuffer(lightsBuf, offset: 0, index: 4)
        enc.setFragmentBuffer(cellsBuf, offset: 0, index: 5)
        enc.setFragmentBuffer(indexBuf, offset: 0, index: 6)
        enc.setFragmentTexture(searchTex, index: 0)
    }

    private func setFrame(_ enc: MTLRenderCommandEncoder, _ f: inout NCFrame) {
        enc.setVertexBytes(&f, length: MemoryLayout<NCFrame>.stride, index: 2)
        enc.setFragmentBytes(&f, length: MemoryLayout<NCFrame>.stride, index: 2)
    }

    private func drawBatches(_ enc: MTLRenderCommandEncoder, shadowOnly: Bool) {
        for b in batches {
            if b.instances == 0 || (shadowOnly && !b.shadow) { continue }
            var d = NCDraw(actor: b.actor, instanced: b.instanced ? 1 : 0, seed: b.seed)
            enc.setVertexBytes(&d, length: MemoryLayout<NCDraw>.stride, index: 3)
            enc.setFragmentBytes(&d, length: MemoryLayout<NCDraw>.stride, index: 3)
            enc.setVertexBuffer(b.mesh.vertices, offset: 0, index: 0)
            enc.setVertexBuffer(b.instanceBuffer, offset: 0, index: 1)
            enc.drawIndexedPrimitives(type: .triangle, indexCount: b.mesh.indexCount, indexType: b.mesh.indexType,
                                      indexBuffer: b.mesh.indices, indexBufferOffset: 0, instanceCount: b.instances)
        }
    }

    private func searchShadowPass(_ cb: MTLCommandBuffer) {
        let pass = MTLRenderPassDescriptor()
        pass.depthAttachment.texture = searchTex
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.clearDepth = 1
        pass.depthAttachment.storeAction = .store
        let enc = cb.makeRenderCommandEncoder(descriptor: pass)!
        enc.setCullMode(.none)
        enc.setDepthBias(4, slopeScale: 2, clamp: 0)
        enc.setRenderPipelineState(depthOnly)
        enc.setDepthStencilState(depthLess)
        setFrame(enc, &searchFrame)
        drawBatches(enc, shadowOnly: true)
        enc.endEncoding()
    }

    private func drawSky(_ enc: MTLRenderCommandEncoder, _ p: MTLRenderPipelineState) {
        enc.setRenderPipelineState(p)
        enc.setDepthStencilState(depthLessEqual)
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
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
        enc.setCullMode(.none)
        enc.setRenderPipelineState(objMirror)
        enc.setDepthStencilState(depthLess)
        setFrame(enc, &mirrorFrame)
        bindGrid(enc, vertex: false)
        drawBatches(enc, shadowOnly: false)
        drawSky(enc, skyMirror)
        enc.endEncoding()
        let blit = cb.makeBlitCommandEncoder()!
        blit.generateMipmaps(for: reflTex)
        blit.endEncoding()
    }

    private func mainPass(_ cb: MTLCommandBuffer) {
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = msaaColor
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        if msaaColor.sampleCount > 1 {
            pass.colorAttachments[0].resolveTexture = sceneTex
            pass.colorAttachments[0].storeAction = .multisampleResolve
            pass.depthAttachment.resolveTexture = depthTex
            pass.depthAttachment.storeAction = .multisampleResolve
        } else {
            pass.colorAttachments[0].texture = sceneTex
            pass.colorAttachments[0].storeAction = .store
        }
        pass.depthAttachment.texture = msaaColor.sampleCount > 1 ? msaaDepth : depthTex
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.clearDepth = 1
        if msaaColor.sampleCount <= 1 { pass.depthAttachment.storeAction = .store }
        let enc = cb.makeRenderCommandEncoder(descriptor: pass)!
        enc.setCullMode(.none)
        enc.setRenderPipelineState(rayTracing ? objMainRT : objMain)
        enc.setDepthStencilState(depthLess)
        setFrame(enc, &frame)
        bindGrid(enc, vertex: true)
        enc.setFragmentTexture(reflTex, index: 1)
        if rayTracing { bindRays(enc) }
        drawBatches(enc, shadowOnly: false)
        drawSky(enc, sky)

        // translucent: premultiplied alpha (alpha 0 = additive), depth-tested, not written
        enc.setDepthStencilState(depthTestOnly)
        enc.setRenderPipelineState(glow)
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: grid.lightCount)
        enc.setRenderPipelineState(steam)
        for o in steamOrigins {
            var f = frame
            f.origin = o
            setFrame(enc, &f)
            enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: Self.steamPuffs)
        }
        setFrame(enc, &frame)
        enc.setRenderPipelineState(rain)
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: Self.rain)
        enc.setRenderPipelineState(splash)
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: Self.splashes)
        enc.endEncoding()
    }

    private func fullscreen(_ cb: MTLCommandBuffer, _ p: MTLRenderPipelineState, into target: MTLTexture, load: Bool = false,
                            textures: [Int: MTLTexture], post: NCPost? = nil, frame f: Bool = false, grid g: Bool = false, rays: Bool = false) {
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = target
        pass.colorAttachments[0].loadAction = load ? .load : .dontCare
        pass.colorAttachments[0].storeAction = .store
        let enc = cb.makeRenderCommandEncoder(descriptor: pass)!
        enc.setRenderPipelineState(p)
        if var post { enc.setFragmentBytes(&post, length: MemoryLayout<NCPost>.stride, index: 0) }
        if f { setFrame(enc, &frame) }
        if g { bindGrid(enc, vertex: false) }
        if rays { bindRays(enc) }
        for (i, t) in textures { enc.setFragmentTexture(t, index: i) }
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        enc.endEncoding()
    }

    private func fogPass(_ cb: MTLCommandBuffer) {
        fullscreen(cb, rayTracing ? fogRT : fog, into: fogTex, textures: [2: depthTex], frame: true, grid: true, rays: rayTracing)
    }

    private func postPass(_ cb: MTLCommandBuffer) {
        fullscreen(cb, combine, into: hdrTex, textures: [0: sceneTex, 1: fogTex])
        // bloom: thresholded 13-tap chain down, streaks off the ¼-res level, tent chain back up
        var src: MTLTexture = hdrTex
        for l in 0..<Self.bloomLevels {
            fullscreen(cb, l == 0 ? downFirst : down, into: bloomTex[l], textures: [0: src],
                       post: NCPost(texel: SIMD2(1 / Float(src.width), 1 / Float(src.height))))
            src = bloomTex[l]
        }
        var sSrc = bloomTex[1]
        for (i, stride) in [Float(1), 3, 8].enumerated() {
            fullscreen(cb, streak, into: streakTex[i % 2], textures: [0: sSrc], post: NCPost(step: SIMD2(stride * 4 / Float(width), 0)))
            sSrc = streakTex[i % 2]
        }
        for l in stride(from: Self.bloomLevels - 1, through: 1, by: -1) {
            fullscreen(cb, up, into: bloomTex[l - 1], load: true, textures: [0: bloomTex[l]],
                       post: NCPost(texel: SIMD2(1 / Float(bloomTex[l].width), 1 / Float(bloomTex[l].height)), weight: 1))
        }
        fullscreen(cb, final, into: output, textures: [0: hdrTex, 1: bloomTex[0], 2: depthTex, 3: sSrc], frame: true)
    }
}
