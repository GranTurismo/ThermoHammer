package com.example.thermohammer.engine

import android.content.res.AssetManager
import android.util.Log
import android.view.Surface
import com.example.thermohammer.engine.gpu.NeonCityAsset
import com.example.thermohammer.engine.gpu.NeonRtNative
import com.example.thermohammer.engine.gpu.NeonRtScene
import java.util.concurrent.atomic.AtomicBoolean

/** What the engine needs from a GPU renderer thread (GpuBench for GLES, VkBench for Vulkan). */
interface GpuRunner {
    val running: Boolean
    val finished: Boolean
    val sceneSeconds: Float
    val glError: String?
    val gpuName: String
    fun stop()
    fun frameLog(): Triple<FloatArray, LongArray, Int>
    fun frameProgress(): Double
    fun attachDisplay(s: Surface)
    fun detachDisplay()
}

// ─────────────────────────────────────────────────────────────────────────────
// VULKAN BENCHMARK — GPU Test 3 (Neon City RT) on its own thread.
//
//   Same contract as GpuBench: offscreen 1920×1080, each frame timed from
//   simulation to GPU completion (the native side waits on a fence), every
//   frame of a fixed-length run logged, the display fed at an adaptive stride
//   through a Vulkan swapchain on the stage's Surface.
// ─────────────────────────────────────────────────────────────────────────────

class VkBench(private val assets: AssetManager) : GpuRunner {

    companion object {
        private const val TAG = "VkBench"

        /** Why GPU Test 3 can't run on this phone, or null when it can (Vulkan ray queries). Cached. */
        val rayTracingProblem: String? by lazy {
            try { NeonRtNative.rayTracingProblem() } catch (t: Throwable) { t.message ?: "Vulkan probe failed" }
        }
    }

    @Volatile override var running = false; private set
    @Volatile override var finished = false; private set
    @Volatile override var sceneSeconds = 0f; private set
    @Volatile override var glError: String? = null; private set
    @Volatile override var gpuName = ""; private set

    private class FrameTick(val frames: Long, val doneNs: Long, val durNs: Long)
    @Volatile private var tick = FrameTick(0, 0, 0)
    private var logStart = FloatArray(8192)
    private var logDur = LongArray(8192)
    private var logCount = 0
    private var thread: Thread? = null
    private val stopFlag = AtomicBoolean(false)
    @Volatile private var wantedSurface: Surface? = null
    private var runForS = 0f

    override fun attachDisplay(s: Surface) { wantedSurface = s }
    override fun detachDisplay() { wantedSurface = null }

    /** Renders Neon City RT for `runForS` seconds of timeline (ray queries on unless `rayTracing` is false). */
    fun start(runForS: Float, rayTracing: Boolean = true) {
        if (running) return
        this.runForS = runForS
        finished = false
        sceneSeconds = 0f
        logCount = 0
        glError = null
        tick = FrameTick(0, 0, 0)
        stopFlag.set(false)
        running = true
        thread = Thread { renderLoop(rayTracing) }.also {
            it.name = "th-vulkan"
            it.start()
        }
    }

    override fun stop() {
        stopFlag.set(true)
        thread?.join(3000)
        thread = null
        running = false
    }

    override fun frameLog(): Triple<FloatArray, LongArray, Int> = Triple(logStart, logDur, logCount)

    override fun frameProgress(): Double {
        val t = tick
        if (t.durNs <= 0) return t.frames.toDouble()
        val frac = (System.nanoTime() - t.doneNs).toDouble() / t.durNs
        return t.frames + frac.coerceIn(0.0, 0.95)
    }

    private fun renderLoop(rayTracing: Boolean) {
        var scene: NeonRtScene? = null
        try {
            val bytes = assets.open(NeonCityAsset.ASSET_NAME).use { it.readBytes() }
            scene = NeonRtScene(NeonCityAsset(bytes), bytes, GpuBench.RENDER_W, GpuBench.RENDER_H, rayTracing)
            scene.init()
            gpuName = scene.deviceName
            var shownSurface: Surface? = null
            var presentStride = 1
            var frames = 0L
            var totalNs = 0L
            val startNs = System.nanoTime()
            while (!stopFlag.get()) {
                val wanted = wantedSurface
                if (wanted !== shownSurface) {
                    scene.setWindow(wanted)
                    shownSurface = wanted
                }
                val t0 = System.nanoTime()
                val sceneT = (t0 - startNs) / 1e9f
                scene.render(sceneT)
                val done = System.nanoTime()
                val dt = done - t0
                frames++
                totalNs += dt
                tick = FrameTick(frames, done, dt)
                sceneSeconds = sceneT
                if (logCount == logStart.size) {
                    logStart = logStart.copyOf(logCount * 2); logDur = logDur.copyOf(logCount * 2)
                }
                logStart[logCount] = sceneT; logDur[logCount] = dt; logCount++
                if (shownSurface != null && frames % presentStride == 0L) scene.present()
                if (frames % 256L == 0L) presentStride = (frames * 1e9 / totalNs / 45.0).toInt().coerceIn(1, 8)
                if (runForS > 0f && sceneT >= runForS) { finished = true; break }
            }
        } catch (e: Throwable) {
            glError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "renderer failed", e)
        } finally {
            try { scene?.destroy() } catch (_: Throwable) {}
            running = false
        }
    }
}
