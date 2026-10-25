import Foundation

/**
 * Lake Valley scene asset, parsed from the bundled lake_valley.bin (written by BenchmarkScene/build_lake_valley.py,
 * which documents the byte layout) — the same file the Android app renders.
 */
struct LakeValleyAsset {
    struct Mesh {
        let name: String
        let vertexCount: Int
        let indexCount: Int          // 0 for polar meshes — their indices are built on the phone
        let polarSegments: Int
        let polarRings: Int
        let height: Float            // max Z — normalises wind sway
        let vertices: Data           // vertexCount × vertexBytes
        let indices: Data?           // u16
        var isPolar: Bool { polarSegments > 0 }
    }

    /** Instances of one mesh: count × {x, y, z, euler x, y, z, scale x, y, z}. */
    struct Group { let name: String; let mesh: Int; let count: Int; let transforms: [Float] }

    static let vertexBytes = 20     // f32 xyz · i8 normal ×4 · u8 sRGB rgb + class

    let lakeCenterX, lakeCenterY, lakeRadiusX, lakeRadiusY, waterZ: Float
    let anchors: [String: SIMD3<Float>]
    let meshes: [Mesh]
    let groups: [Group]

    init(_ data: Data) throws {
        var r = ByteReader(data)
        guard r.ascii(4) == "LVSC" else { throw SceneError("not a Lake Valley scene") }
        guard r.i32() == 1 else { throw SceneError("Lake Valley asset version") }
        lakeCenterX = r.f32(); lakeCenterY = r.f32(); lakeRadiusX = r.f32(); lakeRadiusY = r.f32(); waterZ = r.f32()
        var anchors: [String: SIMD3<Float>] = [:]
        for _ in 0..<r.i32() { let n = r.name16(); anchors[n] = SIMD3(r.f32(), r.f32(), r.f32()) }
        self.anchors = anchors
        var meshes: [Mesh] = []
        for _ in 0..<r.i32() {
            let name = r.name16()
            let vc = Int(r.i32()), ic = Int(r.i32()), seg = Int(r.i32()), rings = Int(r.i32())
            let h = r.f32()
            let v = r.bytes(vc * Self.vertexBytes)
            let idx = ic > 0 ? r.bytes(ic * 2) : nil
            if ic % 2 == 1 { r.skip(2) }   // u16 run padded to 4 bytes
            meshes.append(Mesh(name: name, vertexCount: vc, indexCount: ic, polarSegments: seg, polarRings: rings, height: h, vertices: v, indices: idx))
        }
        self.meshes = meshes
        var groups: [Group] = []
        for _ in 0..<r.i32() {
            let name = r.name16()
            let mesh = Int(r.i32()), count = Int(r.i32())
            groups.append(Group(name: name, mesh: mesh, count: count, transforms: (0..<count * 9).map { _ in r.f32() }))
        }
        self.groups = groups
        guard r.atEnd else { throw SceneError("Lake Valley asset has trailing bytes") }
    }

    func meshIndex(_ name: String) -> Int { meshes.firstIndex { $0.name == name } ?? 0 }
}

struct SceneError: LocalizedError {
    let message: String
    init(_ m: String) { message = m }
    var errorDescription: String? { message }
}

/** Little-endian reader over a scene file. */
struct ByteReader {
    let data: Data
    var at = 0
    init(_ d: Data) { data = d }
    var atEnd: Bool { at == data.count }
    mutating func skip(_ n: Int) { at += n }
    mutating func i32() -> Int32 { defer { at += 4 }; return data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: at, as: Int32.self) } }
    mutating func f32() -> Float { defer { at += 4 }; return data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: at, as: Float.self) } }
    mutating func bytes(_ n: Int) -> Data { defer { at += n }; return data.subdata(in: at..<at + n) }
    mutating func ascii(_ n: Int) -> String { String(decoding: bytes(n), as: UTF8.self) }
    mutating func name16() -> String { String(decoding: bytes(16).prefix { $0 != 0 }, as: UTF8.self) }
}
