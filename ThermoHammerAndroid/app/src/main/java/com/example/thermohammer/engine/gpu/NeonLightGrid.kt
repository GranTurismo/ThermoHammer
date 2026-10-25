package com.example.thermohammer.engine.gpu

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY LIGHT GRID — world-space light binning for GPU Test 2.
//
//   The street is lit by ~870 static lights (signs, lamps, shop windows) plus
//   a few hundred moving ones (car head/tail lamps, trains, the searchlight).
//   Lights are binned into 4 m × 4 m ground cells. A cell lists its moving
//   lights first, then its static ones, each run ranked by what the light adds
//   in that cell. Surfaces shade the whole list (it never exceeds their
//   budget); fog marches only the leading moving lights (pinned searchlight
//   and strobe, then the strongest cars); the GLES street reflection takes the
//   top few of each run. No budget may let traffic push static lights out of
//   a list — that made shop signs blink as cars passed. Static lights
//   are binned once; moving ones are re-binned and merged in each frame.
//
//   GPU layout (rebuilt into the arrays below, uploaded by the renderer):
//     lights  RGBA32F, LIGHT_TEXELS per light:
//               (x, y, z, radius) (r, g, b, type) (dir xyz, cos outer) (group, 0, 0, 0)
//             type 0 point · 1 spot · 2 shadowed spot (the searchlight)
//     cells   R32UI, offset << 12 | moving << 6 | count, CELLS_X × CELLS_Y (counts ≤ 48)
//     indices R16UI, INDEX_W wide
//   Pure JVM — unit-tested.
// ─────────────────────────────────────────────────────────────────────────────

internal class NeonLightGrid(staticLights: NeonCityAsset.Table, private val maxDynamic: Int) {

    companion object {
        const val X0 = -256f
        const val Y0 = -320f
        const val CELL = 4f
        const val CELLS_X = 128
        const val CELLS_Y = 336
        const val MAX_PER_CELL = 48
        const val LIGHT_TEXELS = 4
        const val LIGHTS_PER_ROW = 256
        const val INDEX_W = 2048
    }

    val staticCount = staticLights.rows
    val capacity = staticCount + maxDynamic

    /** Light texture rows: LIGHTS_PER_ROW lights × LIGHT_TEXELS texels × 4 floats. */
    val lightRows = (capacity + LIGHTS_PER_ROW - 1) / LIGHTS_PER_ROW
    val lights = FloatArray(lightRows * LIGHTS_PER_ROW * LIGHT_TEXELS * 4)

    val cells = IntArray(CELLS_X * CELLS_Y)
    private val staticCells = IntArray(CELLS_X * CELLS_Y)          // offset << 8 | count into staticIdx
    private val staticIdx: ShortArray
    private val staticImp: FloatArray                              // importance(staticIdx[k], its cell)
    private val dynCount = IntArray(CELLS_X * CELLS_Y)
    private val dynStart = IntArray(CELLS_X * CELLS_Y + 1)
    private var dynIdx = ShortArray(maxDynamic * 64)
    private var dynImp = FloatArray(maxDynamic * 64)
    private val dynPinned = BooleanArray(maxDynamic)
    private val dynFill = IntArray(CELLS_X * CELLS_Y)
    private var dynamicCount = 0

    var indexRows = 0; private set
    var indices = ShortArray(INDEX_W * 64); private set
    var indexCount = 0; private set

    init {
        for (i in 0 until staticCount) {
            val o = i * LIGHT_TEXELS * 4
            for (c in 0 until 13) lights[o + c] = staticLights[i, c]      // same column order as the table
        }
        // static binning: two passes (count, fill), then each cell ranked; build() caps the merged lists
        val count = IntArray(CELLS_X * CELLS_Y)
        for (i in 0 until staticCount) forCells(i) { cell -> count[cell]++ }
        var total = 0
        for (c in count.indices) {
            staticCells[c] = (total shl 8) or min(count[c], 255)
            total += min(count[c], 255)
        }
        staticIdx = ShortArray(total)
        staticImp = FloatArray(total)
        val fill = IntArray(CELLS_X * CELLS_Y)
        for (i in 0 until staticCount) forCells(i) { cell ->
            if (fill[cell] < (staticCells[cell] and 255)) {
                val at = (staticCells[cell] ushr 8) + fill[cell]++
                staticIdx[at] = i.toShort()
                staticImp[at] = importance(i, cell)
            }
        }
        for (c in staticCells.indices) {
            val from = staticCells[c] ushr 8
            sortByImportance(staticIdx, staticImp, from, from + (staticCells[c] and 255))
        }
    }

    /** Static entries — informational (lights per covered cell, before the per-frame lights). */
    val staticEntries get() = staticIdx.size

    // ── per frame: moving lights ────────────────────────────────────────────

    fun beginDynamic() { dynamicCount = 0 }

    /** Adds a moving light (`pinned`: always first in its cells); false once the dynamic capacity is used up. */
    fun add(x: Float, y: Float, z: Float, radius: Float, r: Float, g: Float, b: Float, type: Int = 0,
            dx: Float = 0f, dy: Float = 0f, dz: Float = -1f, cosOuter: Float = 0f, pinned: Boolean = false): Boolean {
        if (dynamicCount >= maxDynamic) return false
        dynPinned[dynamicCount] = pinned
        val o = (staticCount + dynamicCount) * LIGHT_TEXELS * 4
        lights[o] = x; lights[o + 1] = y; lights[o + 2] = z; lights[o + 3] = radius
        lights[o + 4] = r; lights[o + 5] = g; lights[o + 6] = b; lights[o + 7] = type.toFloat()
        lights[o + 8] = dx; lights[o + 9] = dy; lights[o + 10] = dz; lights[o + 11] = cosOuter
        lights[o + 12] = 0f
        dynamicCount++
        return true
    }

    /** Ranks this frame's moving lights per cell and puts them ahead of the ranked static lists. */
    fun build() {
        dynCount.fill(0)
        for (k in 0 until dynamicCount) forCells(staticCount + k) { cell -> dynCount[cell]++ }
        var acc = 0
        for (c in dynCount.indices) { dynStart[c] = acc; acc += dynCount[c] }
        dynStart[dynCount.size] = acc
        if (acc > dynIdx.size) { dynIdx = ShortArray(acc * 2); dynImp = FloatArray(acc * 2) }
        dynFill.fill(0)
        for (k in 0 until dynamicCount) forCells(staticCount + k) { cell ->
            val at = dynStart[cell] + dynFill[cell]++
            dynIdx[at] = (staticCount + k).toShort()
            dynImp[at] = importance(staticCount + k, cell)
        }
        val needed = staticIdx.size + acc
        if (needed > indices.size) indices = ShortArray(((needed / INDEX_W) + 8) * INDEX_W)
        var w = 0
        for (c in cells.indices) {
            val start = w
            val d = dynStart[c]
            sortByImportance(dynIdx, dynImp, d, d + dynFill[c])
            val nd = min(dynFill[c], MAX_PER_CELL)
            System.arraycopy(dynIdx, d, indices, w, nd); w += nd
            val ns = min(staticCells[c] and 255, MAX_PER_CELL - nd)
            System.arraycopy(staticIdx, staticCells[c] ushr 8, indices, w, ns); w += ns
            cells[c] = (start shl 12) or (nd shl 6) or (w - start)
        }
        indexCount = w
        indexRows = max(1, (w + INDEX_W - 1) / INDEX_W)
    }

    val lightCount get() = staticCount + dynamicCount

    /**
     * This frame's moving lights from the simulation — the police searchlight (shadowed spot) and its
     * strobes, every car's headlights and tail lights, lit train cars — then the merged grid.
     */
    fun setMovingLights(sim: NeonCitySim, time: Float, searchCos: Float) {
        beginDynamic()
        val p = sim.searchPos; val a = sim.searchAim
        var dx = a[0] - p[0]; var dy = a[1] - p[1]; var dz = a[2] - p[2]
        val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
        add(p[0], p[1], p[2], 90f, 0.85f * 38f, 0.92f * 38f, 1.0f * 38f, 2, dx, dy, dz, searchCos, pinned = true)
        val strobe = sim.groups[31]
        val red = (time * 2.2f) % 1f < 0.3f
        add(p[0], p[1], p[2] + 0.4f, 18f, if (red) 6f * strobe else 0f, 0f, if (red) 0f else 7f * strobe, pinned = true)
        val lamps = sim.carLamps
        for (i in 0 until sim.carCount) {
            val o = i * 7
            val x = lamps[o]; val y = lamps[o + 1]; val fx = lamps[o + 3]; val fy = lamps[o + 4]
            val half = lamps[o + 5] / 2f
            val brake = lamps[o + 6] > 0.5f
            add(x + fx * (half + 0.3f), y + fy * (half + 0.3f), 0.75f, 30f, 5f, 4.6f, 4.1f, 1,
                fx * 0.985f, fy * 0.985f, -0.17f, 0.85f)
            val tail = if (brake) 2.2f else 0.7f
            add(x - fx * (half + 0.2f), y - fy * (half + 0.2f), 0.85f, if (brake) 8f else 5f,
                tail, tail * 0.04f, tail * 0.02f)
        }
        val tl = sim.trainLamps
        for (i in 0 until sim.trainLampCount) add(tl[i * 3], tl[i * 3 + 1], tl[i * 3 + 2], 14f, 2.5f, 2.25f, 1.9f)
        build()
    }

    /**
     * How much `light` can add anywhere in `cell`, street level to 6 m up: its brightness × the shaders'
     * falloff (and cone) at the cell's point nearest the light, or the cell's centre if that is brighter.
     * Group animation is left out — static lists are ranked once. Pinned lights outrank everything.
     */
    internal fun importance(light: Int, cell: Int): Float {
        if (light >= staticCount && dynPinned[light - staticCount]) return Float.MAX_VALUE
        val o = light * LIGHT_TEXELS * 4
        val x0 = X0 + (cell % CELLS_X) * CELL
        val y0 = Y0 + (cell / CELLS_X) * CELL
        val z = lights[o + 2].coerceIn(0f, 6f)
        val near = falloff(o, lights[o].coerceIn(x0, x0 + CELL), lights[o + 1].coerceIn(y0, y0 + CELL), z)
        val mid = if (lights[o + 7] >= 0.5f) falloff(o, x0 + CELL / 2, y0 + CELL / 2, z) else 0f
        return (0.2126f * lights[o + 4] + 0.7152f * lights[o + 5] + 0.0722f * lights[o + 6]) * max(near, mid)
    }

    /** lightAtten() in the shaders, without groups or the searchlight's shadow. */
    private fun falloff(o: Int, px: Float, py: Float, pz: Float): Float {
        val dx = px - lights[o]; val dy = py - lights[o + 1]; val dz = pz - lights[o + 2]
        val d2 = dx * dx + dy * dy + dz * dz
        val r2 = lights[o + 3] * lights[o + 3]
        if (d2 >= r2) return 0f
        val f = d2 / r2
        var att = (1f - f) * (1f - f) / (d2 + 1f)
        if (lights[o + 7] >= 0.5f && d2 > 1e-6f) {
            val outer = lights[o + 11]
            val c = (dx * lights[o + 8] + dy * lights[o + 9] + dz * lights[o + 10]) / sqrt(d2)
            val u = ((c - outer) / ((1f - outer) * 0.45f)).coerceIn(0f, 1f)
            att *= u * u * (3f - 2f * u)
        }
        return att
    }

    /** Insertion sort of one cell's entries, most important first — the lists are short. */
    private fun sortByImportance(idx: ShortArray, imp: FloatArray, from: Int, to: Int) {
        for (i in from + 1 until to) {
            val v = imp[i]; val id = idx[i]
            var j = i - 1
            while (j >= from && imp[j] < v) { imp[j + 1] = imp[j]; idx[j + 1] = idx[j]; j-- }
            imp[j + 1] = v; idx[j + 1] = id
        }
    }

    /** Calls `f` for every cell a light can reach (spots: the cone's footprint box). */
    private inline fun forCells(light: Int, f: (Int) -> Unit) {
        val o = light * LIGHT_TEXELS * 4
        val x = lights[o]; val y = lights[o + 1]; val z = lights[o + 2]; val r = lights[o + 3]
        var x0 = x - r; var x1 = x + r; var y0 = y - r; var y1 = y + r
        if (lights[o + 7] >= 0.5f) {
            // spot: bound the cone to where it can light — reach r along dir, spread by the cone's tangent
            val dx = lights[o + 8]; val dy = lights[o + 9]; val dz = lights[o + 10]
            val c = lights[o + 11].coerceIn(0.05f, 0.9999f)
            val spread = r * sqrt(1f - c * c) / c
            val reach = if (dz < -0.2f) min(r, z / -dz) else r
            val ex = x + dx * reach; val ey = y + dy * reach
            x0 = min(x, ex) - spread; x1 = max(x, ex) + spread
            y0 = min(y, ey) - spread; y1 = max(y, ey) + spread
            x0 = max(x0, x - r); x1 = min(x1, x + r); y0 = max(y0, y - r); y1 = min(y1, y + r)
        }
        val cx0 = max(0, floor((x0 - X0) / CELL).toInt()); val cx1 = min(CELLS_X - 1, floor((x1 - X0) / CELL).toInt())
        val cy0 = max(0, floor((y0 - Y0) / CELL).toInt()); val cy1 = min(CELLS_Y - 1, floor((y1 - Y0) / CELL).toInt())
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            // skip cells whose nearest point is outside the radius
            val nx = (x).coerceIn(X0 + cx * CELL, X0 + (cx + 1) * CELL) - x
            val ny = (y).coerceIn(Y0 + cy * CELL, Y0 + (cy + 1) * CELL) - y
            if (nx * nx + ny * ny <= r * r) f(cy * CELLS_X + cx)
        }
    }
}
