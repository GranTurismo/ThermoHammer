import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY — the asset (neon_city.bin, the Android app's file) and everything
// that moves in GPU Test 2, on the CPU: a port of engine/gpu/NeonCityAsset.kt
// and NeonCitySim.kt with the same arithmetic (Kotlin's floored `mod`, the same
// Float/Double mix), so every phone sees the same streets at the same t.
// ─────────────────────────────────────────────────────────────────────────────

struct NeonCityAsset {
    struct Mesh {
        let name: String
        let vertexCount: Int, indexCount: Int
        let wideIndices: Bool        // u32 indices (building chunks), else u16
        let chunk: Bool              // static, drawn once in world space
        let vertices: Data, indices: Data
    }
    /** Instances of one mesh: count × {x, y, z, euler x, y, z, scale x, y, z}. */
    struct Group { let name: String; let mesh: Int; let count: Int; let transforms: [Float] }
    struct Table {
        let name: String; let rows: Int; let cols: Int; let data: [Float]
        subscript(_ r: Int, _ c: Int) -> Float { data[r * cols + c] }
        func row(_ r: Int) -> [Float] { Array(data[r * cols..<(r + 1) * cols]) }
    }

    static let vertexBytes = 20     // f32 xyz · i8 normal + channel · u8 sRGB rgb + class
    static let carKinds = ["sedan", "hatch", "suv", "taxi", "van", "bus"]

    let meshes: [Mesh]
    let groups: [Group]
    let tables: [String: Table]

    init(_ data: Data) throws {
        var r = ByteReader(data)
        guard r.ascii(4) == "NCSC" else { throw SceneError("not a Neon City scene") }
        guard r.i32() == 1 else { throw SceneError("Neon City asset version") }
        var meshes: [Mesh] = []
        for _ in 0..<r.i32() {
            let name = r.name16()
            let vc = Int(r.i32()), ic = Int(r.i32()), flags = Int(r.i32())
            _ = r.f32()   // max z
            let wide = flags & 1 != 0
            let v = r.bytes(vc * Self.vertexBytes)
            let idx = r.bytes(ic * (wide ? 4 : 2))
            if !wide && ic % 2 == 1 { r.skip(2) }
            meshes.append(Mesh(name: name, vertexCount: vc, indexCount: ic, wideIndices: wide, chunk: flags & 2 != 0, vertices: v, indices: idx))
        }
        self.meshes = meshes
        var groups: [Group] = []
        for _ in 0..<r.i32() {
            let name = r.name16()
            let mesh = Int(r.i32()), count = Int(r.i32())
            groups.append(Group(name: name, mesh: mesh, count: count, transforms: (0..<count * 9).map { _ in r.f32() }))
        }
        self.groups = groups
        var tables: [String: Table] = [:]
        for _ in 0..<r.i32() {
            let name = r.name16()
            let rows = Int(r.i32()), cols = Int(r.i32())
            tables[name] = Table(name: name, rows: rows, cols: cols, data: (0..<rows * cols).map { _ in r.f32() })
        }
        self.tables = tables
        guard r.atEnd else { throw SceneError("Neon City asset has trailing bytes") }
    }

    func meshIndex(_ name: String) -> Int { meshes.firstIndex { $0.name == name } ?? -1 }
    func table(_ name: String) -> Table { tables[name] ?? Table(name: name, rows: 0, cols: 0, data: []) }
    var chunkMeshes: [Int] { meshes.indices.filter { meshes[$0].chunk } }
}

/** Kotlin's Double.mod: the remainder with the divisor's sign. */
@inline(__always) func kmod(_ a: Double, _ b: Double) -> Double {
    let r = a.truncatingRemainder(dividingBy: b)
    return r != 0 && (r < 0) != (b < 0) ? r + b : r
}

final class NeonCitySim {
    static let duration = 60.0              // camera timeline, seconds
    static let dt = 1.0 / 30.0
    static let warmup = 40.0
    static let signalPeriod = 30.0
    static let signalOffset = 4.0
    static let railZ: Float = 11
    static let trainCarL: Float = 19.5
    static let trainGap: Float = 0.8
    static let stride: Float = 1.5          // metres per walk cycle
    static let instanceFloats = 20          // 3 transform rows + 2 extra vec4
    private static let idmA = 1.6, idmB = 3.0, idmT = 1.3, idmS0 = 2.4
    static let carLength = [4.65, 4.10, 4.75, 4.70, 5.10, 11.5]

    /** 0 green, 1 yellow, 2 red for signal 0 (avenue) or 1 (cross street). */
    static func signalState(_ group: Int, _ t: Double) -> Int {
        let u = kmod(t + signalOffset, signalPeriod)
        if group == 0 { return u < 14 ? 0 : u < 17 ? 1 : 2 }
        return u < 18 ? 2 : u < 27 ? 0 : u < 29.5 ? 1 : 2
    }

    /** Instances of one actor mesh, refreshed every frame (rows + paint/brake or walk data). */
    final class ActorBatch {
        let mesh: String
        var data: [Float]
        var count = 0
        init(_ mesh: String, _ capacity: Int) { self.mesh = mesh; data = [Float](repeating: 0, count: capacity * NeonCitySim.instanceFloats) }
    }

    private let camera, lanes, cars, peds, trains, drones: NeonCityAsset.Table
    private var carS: [Double], prevS: [Double], carV: [Double], carA: [Double], acc: [Double]
    private let byLane: [[Int]]
    private var simTime = -NeonCitySim.warmup

    let carBatches: [ActorBatch]
    let pedBatches: [ActorBatch]
    let trainHeads: ActorBatch, trainCarsBatch: ActorBatch, droneBatch: ActorBatch, policeBatch: ActorBatch

    var eye = [Float](repeating: 0, count: 3)
    var target = [Float](repeating: 0, count: 3)
    private(set) var fovDeg: Float = 52
    var groups = [Float](repeating: 1, count: 32)       // animation-group intensity multipliers
    /** Searchlight of the police drone: position, aim point. */
    var searchPos = [Float](repeating: 0, count: 3)
    var searchAim = [Float](repeating: 0, count: 3)
    /** Car lamps for dynamic lights: x y z, fwd x y, length, brake (0/1) per car. */
    var carLamps: [Float]
    var carCount: Int { cars.rows }
    /** Train car centres (x, y, z) currently on the viaduct, for interior light spill. */
    var trainLamps: [Float]
    private(set) var trainLampCount = 0

    init(_ asset: NeonCityAsset) {
        camera = asset.table("camera"); lanes = asset.table("lanes"); cars = asset.table("cars")
        peds = asset.table("peds"); trains = asset.table("trains"); drones = asset.table("drones")
        let n = cars.rows
        carS = [Double](repeating: 0, count: n); prevS = carS; carV = carS; carA = carS; acc = carS
        let cars = self.cars
        byLane = (0..<lanes.rows).map { li in (0..<n).filter { Int(cars[$0, 0]) == li } }
        carBatches = NeonCityAsset.carKinds.enumerated().map { k, kind in
            ActorBatch("car_\(kind)", (0..<n).filter { Int(cars[$0, 2]) == k }.count)
        }
        let peds = self.peds
        pedBatches = (0..<4).map { v in ActorBatch("ped_\(v)", (0..<peds.rows).filter { Int(peds[$0, 6]) == v }.count) }
        let trains = self.trains
        let trainCars = (0..<trains.rows).reduce(0) { $0 + Int(trains[$1, 4]) }
        trainHeads = ActorBatch("train_head", trains.rows)
        trainCarsBatch = ActorBatch("train_car", trainCars)
        droneBatch = ActorBatch("drone", drones.rows)
        policeBatch = ActorBatch("drone_police", 1)
        carLamps = [Float](repeating: 0, count: n * 7)
        trainLamps = [Float](repeating: 0, count: trainCars * 3)
        for i in 0..<n {
            carS[i] = Double(cars[i, 1])
            carV[i] = Double(lanes[Int(cars[i, 0]), 5] * cars[i, 6]) * 0.9
        }
        stepTraffic(0)                       // warm up so traffic is already flowing at t = 0
    }

    /** Advances everything to timeline time `t` (seconds, ≥ 0; the camera loops every duration). */
    func update(_ t: Double) {
        stepTraffic(t)
        cameraAt(kmod(t, Self.duration))
        animateGroups(t)
        poseCars(min(1, max(0, (t - (simTime - Self.dt)) / Self.dt)))
        posePeople(t)
        poseTrains(t)
        poseDrones(t)
    }

    private func stepTraffic(_ until: Double) {
        while simTime < until - 1e-9 {
            for li in byLane.indices {
                let idx = byLane[li]
                if idx.isEmpty { continue }
                let length = Double(lanes[li, 4]), v0l = Double(lanes[li, 5]), stopS = Double(lanes[li, 6])
                let sig = Self.signalState(Int(lanes[li, 7]), simTime)
                let order = idx.sorted { carS[$0] < carS[$1] }
                for n in order.indices {
                    let i = order[n]
                    let s = carS[i], v = carV[i]
                    let v0 = v0l * Double(cars[i, 6])
                    let lead = order[(n + 1) % order.count]
                    let half = Self.carLength[Int(cars[i, 2])] / 2
                    var gap = kmod(carS[lead] - s, length) - Self.carLength[Int(cars[lead, 2])] / 2 - half
                    var dv = v - carV[lead]
                    if sig != 0 {                                    // stop line ahead?
                        let toLine = stopS - (s + half)
                        if toLine > 0 && toLine < 90 && (sig == 2 || toLine > v * v / (2 * Self.idmB)) {
                            if toLine < gap { gap = toLine; dv = v }
                        }
                    }
                    gap = max(gap, 0.1)
                    let sStar = Self.idmS0 + max(0, v * Self.idmT + v * dv / (2 * (Self.idmA * Self.idmB).squareRoot()))
                    let a = Self.idmA * (1 - pow(v / v0, 4) - pow(sStar / gap, 2))
                    acc[i] = max(a, -9)
                }
            }
            prevS = carS
            for i in carS.indices {
                let length = Double(lanes[Int(cars[i, 0]), 4])
                let v = max(0, carV[i] + acc[i] * Self.dt)
                carS[i] = kmod(carS[i] + v * Self.dt, length)
                carV[i] = v
                carA[i] = acc[i]
            }
            simTime += Self.dt
        }
    }

    /** Uniform Catmull-Rom through the camera keys (eye and target), linear FOV. */
    private func cameraAt(_ tIn: Double) {
        let n = camera.rows
        let t = min(max(tIn, Double(camera[0, 0])), Double(camera[n - 1, 0]))
        var i = 0
        while i < n - 2 && Double(camera[i + 1, 0]) < t { i += 1 }
        let t0 = camera[i, 0], t1 = camera[i + 1, 0]
        let u = Float((t - Double(t0)) / Double(t1 - t0))
        let r0 = max(i - 1, 0), r3 = min(i + 2, n - 1)
        for (col, isEye) in [(1, true), (4, false)] {
            for c in 0..<3 {
                let p0 = camera[r0, col + c], p1 = camera[i, col + c], p2 = camera[i + 1, col + c], p3 = camera[r3, col + c]
                let a = 2 * p1 + (-p0 + p2) * u
                let b = (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u
                let d = (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u
                let v = 0.5 * (a + b + d)
                if isEye { eye[c] = v } else { target[c] = v }
            }
        }
        var k = 0
        while k < n - 1 && Double(camera[k + 1, 0]) <= t + 1e-9 { k += 1 }
        let j = min(k + 1, n - 1)
        let sp = camera[j, 0] - camera[k, 0]
        let span: Float = sp > 0 ? sp : 1
        fovDeg = camera[k, 7] + (camera[j, 7] - camera[k, 7]) * Float((t - Double(camera[k, 0])) / Double(span))
    }

    private func animateGroups(_ t: Double) {
        let tf = Float(t)
        for g in groups.indices { groups[g] = 1 }
        // groups 1-8 ("faulty tubes" in the scene file) stay lit: blinking signs read as a rendering glitch
        for g in 13...16 { groups[g] = 0.62 + 0.38 * sin(tf * (0.9 + 0.35 * Float(g - 13)) + Float(g)) }
        for sigGroup in 0...1 {
            let lit: Int
            switch Self.signalState(sigGroup, t) { case 0: lit = 2; case 1: lit = 1; default: lit = 0 }
            for lamp in 0...2 { groups[24 + lamp + 3 * sigGroup] = lamp == lit ? 1 : 0.02 }
        }
        groups[30] = kmod(t / 1.6, 1) < 0.14 ? 1 : 0.04                         // aviation beacons
        let phase = kmod(t * 2.2, 1)
        groups[31] = kmod(t * 11, 1) < 0.5 && phase < 0.6 ? 1 : 0               // police strobe
    }

    private func writeRows(_ b: ActorBatch, _ x: Float, _ y: Float, _ z: Float, _ yaw: Float,
                           _ e0: Float = 0, _ e1: Float = 0, _ e2: Float = 0, _ e3: Float = 0, _ f0: Float = 0, _ f1: Float = 0) {
        let o = b.count * Self.instanceFloats
        let c = cos(yaw), s = sin(yaw)
        b.data.withUnsafeMutableBufferPointer { d in
            d[o] = c; d[o + 1] = -s; d[o + 2] = 0; d[o + 3] = x
            d[o + 4] = s; d[o + 5] = c; d[o + 6] = 0; d[o + 7] = y
            d[o + 8] = 0; d[o + 9] = 0; d[o + 10] = 1; d[o + 11] = z
            d[o + 12] = e0; d[o + 13] = e1; d[o + 14] = e2; d[o + 15] = e3
            d[o + 16] = f0; d[o + 17] = f1; d[o + 18] = 0; d[o + 19] = 0
        }
        b.count += 1
    }

    /** `alpha` 0…1: how far `t` lies between the previous traffic step and the current one. */
    private func poseCars(_ alpha: Double) {
        for b in carBatches { b.count = 0 }
        for i in 0..<cars.rows {
            let li = Int(cars[i, 0])
            let dx = lanes[li, 2], dy = lanes[li, 3]
            let length = Double(lanes[li, 4])
            let s = Float(kmod(prevS[i] + kmod(carS[i] - prevS[i], length) * alpha, length))   // cars only move forward
            let x = lanes[li, 0] + dx * s
            let y = lanes[li, 1] + dy * s
            let kind = Int(cars[i, 2])
            let brake: Float = carA[i] < -0.6 || carV[i] < 0.3 ? 1 : 0
            writeRows(carBatches[kind], x, y, 0, atan2(-dx, dy), cars[i, 3], cars[i, 4], cars[i, 5], brake)
            let o = i * 7
            carLamps[o] = x; carLamps[o + 1] = y; carLamps[o + 2] = 0
            carLamps[o + 3] = dx; carLamps[o + 4] = dy
            carLamps[o + 5] = Float(Self.carLength[kind]); carLamps[o + 6] = brake
        }
    }

    private func posePeople(_ t: Double) {
        for b in pedBatches { b.count = 0 }
        for i in 0..<peds.rows {
            let dir = peds[i, 2]
            let seg = Double(peds[i, 3]), speed = Double(peds[i, 4])
            let walked = Double(peds[i, 5]) + Double(dir) * speed * t
            let y = peds[i, 1] + Float(kmod(walked, seg))
            let cycle = Float(speed * t / Double(Self.stride) * 2 * .pi + Double(i) * 1.7)
            writeRows(pedBatches[Int(peds[i, 6])], peds[i, 0], y, 0.15, dir > 0 ? 0 : .pi,
                      peds[i, 7], peds[i, 8], peds[i, 9], peds[i, 10], cycle, speed > 0 ? 0.42 : 0)
        }
    }

    private func poseTrains(_ t: Double) {
        trainHeads.count = 0; trainCarsBatch.count = 0; trainLampCount = 0
        for r in 0..<trains.rows {
            let ty = trains[r, 0], d = trains[r, 1], tCross = trains[r, 2], speed = trains[r, 3]
            let yaw: Float = d > 0 ? -.pi / 2 : .pi / 2
            for ci in 0..<Int(trains[r, 4]) {
                let off = -(Float(ci) * (Self.trainCarL + Self.trainGap) + Self.trainCarL / 2)
                let x = d * (speed * (Float(t) - tCross) + off)
                writeRows(ci == 0 ? trainHeads : trainCarsBatch, x, ty, Self.railZ, yaw)
                if abs(x) < 520 {
                    let o = trainLampCount * 3
                    trainLampCount += 1
                    trainLamps[o] = x; trainLamps[o + 1] = ty; trainLamps[o + 2] = Self.railZ + 2.6
                }
            }
        }
    }

    private func poseDrones(_ t: Double) {
        droneBatch.count = 0; policeBatch.count = 0
        let tf = Float(t)
        for r in 0..<drones.rows {
            if drones[r, 6] > 0.5 {
                // police drone: holds station ahead of the camera, sweeping its searchlight over the street
                let x = 5 * sin(tf * 0.13)
                let y = eye[1] + 48 + 8 * sin(tf * 0.21)
                let z = 24 + 1.5 * sin(tf * 0.5)
                writeRows(policeBatch, x, y, z, 0.25 * sin(tf * 0.3))
                searchPos = [x, y + 0.3, z - 0.6]
                searchAim = [7 * sin(tf * 0.7), y - 14 + 9 * sin(tf * 0.33), 0]
            } else {
                let a = drones[r, 4] * tf + drones[r, 5]
                let rad = drones[r, 3]
                let x = drones[r, 0] + rad * cos(a)
                let y = drones[r, 1] + rad * sin(a)
                let z = drones[r, 2] + 0.6 * sin(tf * 1.3 + drones[r, 5])
                let sign: Float = drones[r, 4] >= 0 ? 1 : -1
                writeRows(droneBatch, x, y, z, atan2(sin(a) * sign, cos(a) * sign))
            }
        }
    }
}
