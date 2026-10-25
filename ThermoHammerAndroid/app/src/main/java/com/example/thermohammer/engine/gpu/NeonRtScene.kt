package com.example.thermohammer.engine.gpu

import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY RT — GPU Test 3: the Neon City scene through the Vulkan renderer
// (src/main/cpp), with ray-queried reflections and shadows.
//
//   Everything that moves is still simulated here (NeonCitySim, NeonLightGrid —
//   the same timelines as GPU Test 2); each frame is packed into direct buffers
//   the native side reads in place. Call from one thread only.
// ─────────────────────────────────────────────────────────────────────────────

internal class NeonRtScene(
    private val asset: NeonCityAsset,
    private val assetBytes: ByteArray,
    private val width: Int,
    private val height: Int,
    private val rayTracing: Boolean
) {
    companion object {
        private const val MAX_LIGHTS = NeonLightGrid.MAX_PER_CELL   // a surface shades its cell's whole list
        private const val FOG_STEPS = 24
        private const val SEARCH_COS = 0.982f
        private const val FRAME_FLOATS = 88        // the Frame block in shaders/common.glsl
        private const val MAX_DYNAMIC_LIGHTS = 400
    }

    private val sim = NeonCitySim(asset)
    private val grid = NeonLightGrid(asset.table("lights"), MAX_DYNAMIC_LIGHTS)
    private val actors = sim.carBatches + sim.pedBatches +
        listOf(sim.trainHeads, sim.trainCarsBatch, sim.droneBatch, sim.policeBatch)
    private val actorCapacity = IntArray(actors.size) { actors[it].data.size / NeonCitySim.INSTANCE_FLOATS }
    private val actorCounts = IntArray(actors.size)

    private var handle = 0L
    private var frame = 0
    private val frameData = FloatArray(FRAME_FLOATS)
    private val frameBuf = direct(FRAME_FLOATS * 4).asFloatBuffer()
    private val lightsBuf = direct(grid.lights.size * 4).asFloatBuffer()
    private val cellsBuf = direct(grid.cells.size * 4).asIntBuffer()
    private var indexBuf: ShortBuffer = direct(2).asShortBuffer()
    private val actorBuf = direct(actorCapacity.sum().coerceAtLeast(1) * NeonCitySim.INSTANCE_FLOATS * 4).asFloatBuffer()

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val glToVk = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0.5f, 1f)
    private val tmp = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val invViewProj = FloatArray(16)

    var deviceName = ""; private set
    var triangles = 0L; private set

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    fun init() {
        val bytes = direct(assetBytes.size).put(assetBytes).also { it.position(0) }
        handle = NeonRtNative.create(bytes, assetBytes.size, actors.map { it.mesh }.toTypedArray(), actorCapacity,
            width, height, rayTracing)
        if (handle == 0L) throw IllegalStateException(NeonRtNative.lastError())
        deviceName = NeonRtNative.deviceName(handle)
        triangles = NeonRtNative.triangles(handle)
    }

    /** Renders timeline time `t` and waits for the GPU; `readback` keeps the pixels for [readPixels]. */
    fun render(t: Float, readback: Boolean = false) {
        sim.update(t.toDouble())
        grid.setMovingLights(sim, t, SEARCH_COS)
        packFrame(t)
        lightsBuf.position(0); lightsBuf.put(grid.lights); lightsBuf.position(0)
        cellsBuf.position(0); cellsBuf.put(grid.cells); cellsBuf.position(0)
        if (indexBuf.capacity() < grid.indexCount) indexBuf = direct(grid.indexCount * 4).asShortBuffer()
        indexBuf.position(0); indexBuf.put(grid.indices, 0, grid.indexCount); indexBuf.position(0)
        actorBuf.position(0)
        for ((i, b) in actors.withIndex()) {
            actorCounts[i] = b.count
            actorBuf.put(b.data, 0, b.count * NeonCitySim.INSTANCE_FLOATS)
        }
        actorBuf.position(0)
        if (!NeonRtNative.render(handle, frameBuf, lightsBuf, grid.lightCount, cellsBuf, indexBuf, grid.indexCount,
                actorBuf, actorCounts, readback)) throw IllegalStateException(NeonRtNative.lastError())
        frame++
    }

    private fun packFrame(t: Float) {
        val e = sim.eye; val g = sim.target
        Matrix.setLookAtM(view, 0, e[0], e[1], e[2], g[0], g[1], g[2], 0f, 0f, 1f)
        Matrix.perspectiveM(proj, 0, sim.fovDeg, width.toFloat() / height, 0.25f, 4000f)
        Matrix.multiplyMM(tmp, 0, glToVk, 0, proj, 0)          // Vulkan clip space: y down, depth 0..1
        Matrix.multiplyMM(viewProj, 0, tmp, 0, view, 0)
        Matrix.invertM(invViewProj, 0, viewProj, 0)
        val f = frameData
        System.arraycopy(viewProj, 0, f, 0, 16)
        System.arraycopy(invViewProj, 0, f, 16, 16)
        f[32] = e[0]; f[33] = e[1]; f[34] = e[2]; f[35] = t
        f[36] = view[0]; f[37] = view[4]; f[38] = view[8]; f[39] = 0f            // camera right
        f[40] = view[1]; f[41] = view[5]; f[42] = view[9]; f[43] = 0f            // camera up
        f[44] = -view[2]; f[45] = -view[6]; f[46] = -view[10]; f[47] = 0f        // camera forward
        f[48] = grid.lightCount.toFloat(); f[49] = MAX_LIGHTS.toFloat(); f[50] = FOG_STEPS.toFloat(); f[51] = (frame % 64).toFloat()
        System.arraycopy(sim.groups, 0, f, 52, 32)
        f[84] = sim.searchPos[0]; f[85] = sim.searchPos[1]; f[86] = sim.searchPos[2]; f[87] = SEARCH_COS
        frameBuf.position(0); frameBuf.put(f); frameBuf.position(0)
    }

    fun setWindow(surface: Surface?) { if (handle != 0L) NeonRtNative.setWindow(handle, surface) }
    fun present() { if (handle != 0L) NeonRtNative.present(handle) }

    /** RGBA8, top row first, of the last frame rendered with `readback`. */
    fun readPixels(out: ByteBuffer) = NeonRtNative.readPixels(handle, out)

    fun destroy() {
        if (handle != 0L) NeonRtNative.destroy(handle)
        handle = 0L
    }
}
