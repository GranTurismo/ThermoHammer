import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY LIGHT GRID — world-space light binning for GPU Test 2, a port of
// engine/gpu/NeonLightGrid.kt.
//
//   ~870 static lights (signs, lamps, shop windows) plus a few hundred moving
//   ones (car lamps, trains, the searchlight), binned into 4 m × 4 m ground
//   cells. A cell lists its moving lights first, then its static ones, each run
//   ranked by what the light adds in that cell. Static lights are binned once;
//   moving ones are re-binned and merged in each frame.
//
//   GPU layout (uploaded by the renderer into buffers):
//     lights  4 float4 per light: (x y z radius) (r g b type) (dir xyz, cos outer) (group, 0, 0, 0)
//             type 0 point · 1 spot · 2 shadowed spot (the searchlight)
//     cells   offset << 12 | moving << 6 | count, cellsX × cellsY (counts ≤ 48)
//     indices u16
// ─────────────────────────────────────────────────────────────────────────────

final class NeonLightGrid {
    static let x0: Float = -256, y0: Float = -320, cell: Float = 4
    static let cellsX = 128, cellsY = 336
    static let maxPerCell = 48
    static let lightFloats = 16

    let staticCount: Int
    let capacity: Int
    var lights: [Float]
    var cells: [UInt32]
    private var staticCells: [Int]          // offset << 8 | count into staticIdx
    private var staticIdx: [UInt16] = []
    private var staticImp: [Float] = []
    private var dynCount: [Int], dynStart: [Int], dynFill: [Int]
    private var dynIdx: [UInt16], dynImp: [Float]
    private var dynPinned: [Bool]
    private var dynamicCount = 0
    private let maxDynamic: Int
    private(set) var indices: [UInt16]
    private(set) var indexCount = 0

    var lightCount: Int { staticCount + dynamicCount }

    init(_ staticLights: NeonCityAsset.Table, maxDynamic: Int) {
        self.maxDynamic = maxDynamic
        staticCount = staticLights.rows
        capacity = staticCount + maxDynamic
        lights = [Float](repeating: 0, count: capacity * Self.lightFloats)
        let nCells = Self.cellsX * Self.cellsY
        cells = [UInt32](repeating: 0, count: nCells)
        staticCells = [Int](repeating: 0, count: nCells)
        dynCount = [Int](repeating: 0, count: nCells)
        dynStart = [Int](repeating: 0, count: nCells + 1)
        dynFill = [Int](repeating: 0, count: nCells)
        dynIdx = [UInt16](repeating: 0, count: maxDynamic * 64)
        dynImp = [Float](repeating: 0, count: maxDynamic * 64)
        dynPinned = [Bool](repeating: false, count: maxDynamic)
        indices = [UInt16](repeating: 0, count: 2048 * 64)
        for i in 0..<staticCount {
            for c in 0..<13 { lights[i * Self.lightFloats + c] = staticLights[i, c] }   // same column order as the table
        }
        // static binning: two passes (count, fill), then each cell ranked; build() caps the merged lists
        var count = [Int](repeating: 0, count: nCells)
        for i in 0..<staticCount { forCells(i) { count[$0] += 1 } }
        var total = 0
        for c in 0..<nCells {
            staticCells[c] = (total << 8) | min(count[c], 255)
            total += min(count[c], 255)
        }
        staticIdx = [UInt16](repeating: 0, count: total)
        staticImp = [Float](repeating: 0, count: total)
        var fill = [Int](repeating: 0, count: nCells)
        for i in 0..<staticCount {
            forCells(i) { cell in
                if fill[cell] < (staticCells[cell] & 255) {
                    let at = (staticCells[cell] >> 8) + fill[cell]
                    fill[cell] += 1
                    staticIdx[at] = UInt16(i)
                    staticImp[at] = importance(i, cell)
                }
            }
        }
        for c in 0..<nCells {
            let from = staticCells[c] >> 8
            Self.sortByImportance(&staticIdx, &staticImp, from, from + (staticCells[c] & 255))
        }
    }

    // MARK: per frame — moving lights

    @discardableResult
    func add(_ x: Float, _ y: Float, _ z: Float, _ radius: Float, _ r: Float, _ g: Float, _ b: Float, type: Int = 0,
             dx: Float = 0, dy: Float = 0, dz: Float = -1, cosOuter: Float = 0, pinned: Bool = false) -> Bool {
        if dynamicCount >= maxDynamic { return false }
        dynPinned[dynamicCount] = pinned
        let o = (staticCount + dynamicCount) * Self.lightFloats
        lights.withUnsafeMutableBufferPointer { l in
            l[o] = x; l[o + 1] = y; l[o + 2] = z; l[o + 3] = radius
            l[o + 4] = r; l[o + 5] = g; l[o + 6] = b; l[o + 7] = Float(type)
            l[o + 8] = dx; l[o + 9] = dy; l[o + 10] = dz; l[o + 11] = cosOuter
            l[o + 12] = 0
        }
        dynamicCount += 1
        return true
    }

    /** Ranks this frame's moving lights per cell and puts them ahead of the ranked static lists. */
    func build() {
        let nCells = cells.count
        for c in 0..<nCells { dynCount[c] = 0; dynFill[c] = 0 }
        for k in 0..<dynamicCount { forCells(staticCount + k) { dynCount[$0] += 1 } }
        var acc = 0
        for c in 0..<nCells { dynStart[c] = acc; acc += dynCount[c] }
        dynStart[nCells] = acc
        if acc > dynIdx.count { dynIdx = [UInt16](repeating: 0, count: acc * 2); dynImp = [Float](repeating: 0, count: acc * 2) }
        for k in 0..<dynamicCount {
            forCells(staticCount + k) { cell in
                let at = dynStart[cell] + dynFill[cell]
                dynFill[cell] += 1
                dynIdx[at] = UInt16(staticCount + k)
                dynImp[at] = importance(staticCount + k, cell)
            }
        }
        let needed = staticIdx.count + acc
        if needed > indices.count { indices = [UInt16](repeating: 0, count: (needed / 2048 + 8) * 2048) }
        var w = 0
        for c in 0..<nCells {
            let start = w
            let d = dynStart[c]
            Self.sortByImportance(&dynIdx, &dynImp, d, d + dynFill[c])
            let nd = min(dynFill[c], Self.maxPerCell)
            for k in 0..<nd { indices[w + k] = dynIdx[d + k] }
            w += nd
            let ns = min(staticCells[c] & 255, Self.maxPerCell - nd)
            let s0 = staticCells[c] >> 8
            for k in 0..<ns { indices[w + k] = staticIdx[s0 + k] }
            w += ns
            cells[c] = UInt32((start << 12) | (nd << 6) | (w - start))
        }
        indexCount = w
    }

    /**
     * This frame's moving lights from the simulation — the police searchlight (shadowed spot) and its strobes,
     * every car's headlights and tail lights, lit train cars — then the merged grid.
     */
    func setMovingLights(_ sim: NeonCitySim, time: Float, searchCos: Float) {
        dynamicCount = 0
        let p = sim.searchPos, a = sim.searchAim
        var dx = a[0] - p[0], dy = a[1] - p[1], dz = a[2] - p[2]
        let dl = (dx * dx + dy * dy + dz * dz).squareRoot()
        dx /= dl; dy /= dl; dz /= dl
        add(p[0], p[1], p[2], 90, 0.85 * 38, 0.92 * 38, 1.0 * 38, type: 2, dx: dx, dy: dy, dz: dz, cosOuter: searchCos, pinned: true)
        let strobe = sim.groups[31]
        let red = (time * 2.2).truncatingRemainder(dividingBy: 1) < 0.3
        add(p[0], p[1], p[2] + 0.4, 18, red ? 6 * strobe : 0, 0, red ? 0 : 7 * strobe, pinned: true)
        let lamps = sim.carLamps
        for i in 0..<sim.carCount {
            let o = i * 7
            let x = lamps[o], y = lamps[o + 1], fx = lamps[o + 3], fy = lamps[o + 4]
            let half = lamps[o + 5] / 2
            let brake = lamps[o + 6] > 0.5
            add(x + fx * (half + 0.3), y + fy * (half + 0.3), 0.75, 30, 5, 4.6, 4.1, type: 1,
                dx: fx * 0.985, dy: fy * 0.985, dz: -0.17, cosOuter: 0.85)
            let tail: Float = brake ? 2.2 : 0.7
            add(x - fx * (half + 0.2), y - fy * (half + 0.2), 0.85, brake ? 8 : 5, tail, tail * 0.04, tail * 0.02)
        }
        let tl = sim.trainLamps
        for i in 0..<sim.trainLampCount { add(tl[i * 3], tl[i * 3 + 1], tl[i * 3 + 2], 14, 2.5, 2.25, 1.9) }
        build()
    }

    /**
     * How much `light` can add anywhere in `cell`, street level to 6 m up: its brightness × the shaders' falloff
     * (and cone) at the cell's point nearest the light, or the cell's centre if that is brighter. Pinned lights
     * outrank everything.
     */
    private func importance(_ light: Int, _ cell: Int) -> Float {
        if light >= staticCount && dynPinned[light - staticCount] { return .greatestFiniteMagnitude }
        let o = light * Self.lightFloats
        let x0 = Self.x0 + Float(cell % Self.cellsX) * Self.cell
        let y0 = Self.y0 + Float(cell / Self.cellsX) * Self.cell
        let z = min(max(lights[o + 2], 0), 6)
        let near = falloff(o, min(max(lights[o], x0), x0 + Self.cell), min(max(lights[o + 1], y0), y0 + Self.cell), z)
        let mid = lights[o + 7] >= 0.5 ? falloff(o, x0 + Self.cell / 2, y0 + Self.cell / 2, z) : 0
        return (0.2126 * lights[o + 4] + 0.7152 * lights[o + 5] + 0.0722 * lights[o + 6]) * max(near, mid)
    }

    /** lightAtten() in the shaders, without groups or the searchlight's shadow. */
    private func falloff(_ o: Int, _ px: Float, _ py: Float, _ pz: Float) -> Float {
        let dx = px - lights[o], dy = py - lights[o + 1], dz = pz - lights[o + 2]
        let d2 = dx * dx + dy * dy + dz * dz
        let r2 = lights[o + 3] * lights[o + 3]
        if d2 >= r2 { return 0 }
        let f = d2 / r2
        var att = (1 - f) * (1 - f) / (d2 + 1)
        if lights[o + 7] >= 0.5 && d2 > 1e-6 {
            let outer = lights[o + 11]
            let c = (dx * lights[o + 8] + dy * lights[o + 9] + dz * lights[o + 10]) / d2.squareRoot()
            let u = min(max((c - outer) / ((1 - outer) * 0.45), 0), 1)
            att *= u * u * (3 - 2 * u)
        }
        return att
    }

    /** Insertion sort of one cell's entries, most important first — the lists are short. */
    private static func sortByImportance(_ idx: inout [UInt16], _ imp: inout [Float], _ from: Int, _ to: Int) {
        var i = from + 1
        while i < to {
            let v = imp[i], id = idx[i]
            var j = i - 1
            while j >= from && imp[j] < v { imp[j + 1] = imp[j]; idx[j + 1] = idx[j]; j -= 1 }
            imp[j + 1] = v; idx[j + 1] = id
            i += 1
        }
    }

    /** Calls `f` for every cell a light can reach (spots: the cone's footprint box). */
    private func forCells(_ light: Int, _ f: (Int) -> Void) {
        let o = light * Self.lightFloats
        let x = lights[o], y = lights[o + 1], z = lights[o + 2], r = lights[o + 3]
        var x0 = x - r, x1 = x + r, y0 = y - r, y1 = y + r
        if lights[o + 7] >= 0.5 {
            // spot: bound the cone to where it can light — reach r along dir, spread by the cone's tangent
            let dx = lights[o + 8], dy = lights[o + 9], dz = lights[o + 10]
            let c = min(max(lights[o + 11], 0.05), 0.9999)
            let spread = r * (1 - c * c).squareRoot() / c
            let reach = dz < -0.2 ? min(r, z / -dz) : r
            let ex = x + dx * reach, ey = y + dy * reach
            x0 = max(min(x, ex) - spread, x - r); x1 = min(max(x, ex) + spread, x + r)
            y0 = max(min(y, ey) - spread, y - r); y1 = min(max(y, ey) + spread, y + r)
        }
        let cx0 = max(0, Int(floor((x0 - Self.x0) / Self.cell))), cx1 = min(Self.cellsX - 1, Int(floor((x1 - Self.x0) / Self.cell)))
        let cy0 = max(0, Int(floor((y0 - Self.y0) / Self.cell))), cy1 = min(Self.cellsY - 1, Int(floor((y1 - Self.y0) / Self.cell)))
        guard cx0 <= cx1, cy0 <= cy1 else { return }
        for cy in cy0...cy1 {
            for cx in cx0...cx1 {
                // skip cells whose nearest point is outside the radius
                let nx = min(max(x, Self.x0 + Float(cx) * Self.cell), Self.x0 + Float(cx + 1) * Self.cell) - x
                let ny = min(max(y, Self.y0 + Float(cy) * Self.cell), Self.y0 + Float(cy + 1) * Self.cell) - y
                if nx * nx + ny * ny <= r * r { f(cy * Self.cellsX + cx) }
            }
        }
    }
}
