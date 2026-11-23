package com.example.thermohammer.engine

import android.content.res.AssetManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import com.example.thermohammer.engine.gpu.GlException
import com.example.thermohammer.engine.gpu.GpuScene
import com.example.thermohammer.engine.gpu.GpuSceneAsset
import com.example.thermohammer.engine.gpu.LakeValleyRenderer
import com.example.thermohammer.engine.gpu.NeonCityAsset
import com.example.thermohammer.engine.gpu.NeonCityRenderer
import com.example.thermohammer.engine.gpu.PhotoEditScene
import com.example.thermohammer.engine.gpu.VideoExportScene
import com.example.thermohammer.engine.gpu.VideoPlaybackScene
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────────────
// GPU BENCHMARK — headless OpenGL ES 3.x sustained-throughput renderer
//
//   Renders a Blender-authored scene (engine/gpu) — "Lake Valley" (GPU Test 1,
//   also the stress load) or the far heavier "Neon City" (GPU Test 2) — or one
//   of the GPU-side UX tests (photo editing, video playback) into an
//   offscreen 1920×1080 target so results are comparable across display
//   resolutions. No vsync — each frame ends with glFinish() for true GPU-bound
//   timing; the display only ever receives a blit of a finished frame.
//
//   Exposes per-frame timestamps + a fractional frame counter so the engine's
//   250 ms sampler can derive live FPS exactly like CPU IPS — even when a heavy
//   frame takes longer than the sampling window.
// ─────────────────────────────────────────────────────────────────────────────

/** Scenes the GPU channel can render. */
enum class GpuSceneId(val displayName: String) {
    LAKE_VALLEY("Lake Valley"),
    NEON_CITY("Neon City"),
    PHOTO_EDIT("Photo editing"),      // UX Test 4
    VIDEO("Video playback"),          // UX Test 5 — ends itself once every clip has run
    EXPORT("Video export")            // UX Test 6 — ends itself once every encoder has run
}

class GpuBench(private val assets: AssetManager, private val cacheDir: java.io.File) : GpuRunner {

    companion object {
        private const val TAG = "GpuBench"
        const val RENDER_W = 1920
        const val RENDER_H = 1080
        private const val FRAME_HIST = 4096  // ring buffer of frame times
    }

    // ── published counters (read by the engine sampler thread) ──────────────
    val framesRendered = AtomicLong(0)        // measured FBO frames — uncapped
    val presentedFrames = AtomicLong(0)       // frames actually blitted to the display
    val frameNsTotal = AtomicLong(0)          // sum of frame times — for fps math
    private val frameTimes = LongArray(FRAME_HIST)
    private val frameIdx = AtomicLong(0)

    /** Last completed frame — published as one object so readers see a consistent triple. */
    private class FrameTick(val frames: Long, val doneNs: Long, val durNs: Long)
    @Volatile private var tick = FrameTick(0, 0, 0)

    @Volatile override var running = false; private set
    @Volatile override var glError: String? = null; private set
    @Volatile override var gpuName: String = ""; private set
    @Volatile var glVersion: String = ""; private set
    @Volatile var displayAttached = false; private set
    @Volatile var trianglesPerFrame = 0L; private set
    @Volatile var scene = GpuSceneId.LAKE_VALLEY; private set
    /** The scene of the current (or last) run — the UX scenes publish their progress and results on it. */
    @Volatile internal var activeScene: GpuScene? = null; private set

    // ── fixed-length runs (benchmark tests): every frame is logged ──────────
    @Volatile var runForS = 0f; private set         // 0 = until stop()
    @Volatile override var finished = false; private set     // timeline completed
    @Volatile override var sceneSeconds = 0f; private set    // timeline time of the last frame
    private var logStart = FloatArray(0)
    private var logDur = LongArray(0)
    private var logCount = 0

    private var thread: Thread? = null
    private val stopFlag = AtomicBoolean(false)

    // Display surface requested by the UI (SurfaceView). Consumed on the GL
    // thread — attach/detach mid-run is safe; measurement keeps running headless.
    @Volatile private var wantedSurface: Surface? = null

    /** Attach a live display surface — the scene becomes visible. */
    override fun attachDisplay(s: Surface) { wantedSurface = s }
    override fun detachDisplay() { wantedSurface = null }

    // ── public API ──────────────────────────────────────────────────────────

    /** Starts rendering `scene`; with `runForS` > 0 it renders that much timeline, logging every frame. */
    fun start(scene: GpuSceneId = GpuSceneId.LAKE_VALLEY, runForS: Float = 0f) {
        if (running) return
        this.scene = scene
        this.runForS = runForS
        finished = false
        sceneSeconds = 0f
        logCount = 0
        if (runForS > 0f && logStart.isEmpty()) { logStart = FloatArray(8192); logDur = LongArray(8192) }
        glError = null
        stopFlag.set(false)
        framesRendered.set(0)
        presentedFrames.set(0)
        frameNsTotal.set(0)
        frameIdx.set(0)
        tick = FrameTick(0, 0, 0)
        activeScene = null
        thread = Thread { renderLoop() }.also {
            it.name = "th-gpu"
            it.priority = Thread.NORM_PRIORITY
            it.start()
        }
    }

    override fun stop() {
        stopFlag.set(true)
        thread?.join(1500)
        thread = null
        running = false
    }

    /** Frame log of the last fixed-length run as (timeline start s, render ns, count) — read after stop(). */
    override fun frameLog(): Triple<FloatArray, LongArray, Int> = Triple(logStart, logDur, logCount)

    /**
     * Frames completed plus the elapsed fraction of the frame in flight, estimated
     * from the previous frame's duration. Whole-frame counts quantise badly once a
     * frame approaches the 250 ms sampling window; this keeps short-window FPS
     * smooth on slow GPUs and never runs ahead of the next completed frame.
     */
    override fun frameProgress(): Double {
        val t = tick
        if (t.durNs <= 0) return t.frames.toDouble()
        val frac = (System.nanoTime() - t.doneNs).toDouble() / t.durNs
        return t.frames + frac.coerceIn(0.0, 0.95)
    }

    /** p95 frame time (ms) over the most recent ~1 s of frames. */
    fun frameP95Ms(framesInWindow: Int): Float {
        val total = frameIdx.get().toInt().coerceAtMost(FRAME_HIST)
        if (total == 0) return -1f
        val n = framesInWindow.coerceIn(1, total)
        val vals = ArrayList<Long>(n)
        val head = (frameIdx.get() % FRAME_HIST).toInt()
        for (k in 0 until n) {
            val i = ((head - 1 - k) % FRAME_HIST + FRAME_HIST) % FRAME_HIST
            vals += frameTimes[i]
        }
        vals.sort()
        return vals[(vals.size * 95 / 100).coerceAtMost(vals.size - 1)] / 1e6f
    }

    /** GPU clock ratio via devfreq (Qualcomm kgsl / Mali / generic). -1 = unreadable. */
    fun gpuFreqRatio(): Float {
        try {
            val cur = File("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq").readText().trim().toFloat()
            val max = File("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq").readText().trim().toFloat()
            if (max > 0) return (cur / max).coerceIn(0f, 1.1f)
        } catch (_: Exception) {}
        // generic devfreq scan — pick the GPU node
        try {
            File("/sys/class/devfreq").listFiles()?.forEach { d ->
                if (d.name.contains("gpu", true) || d.name.contains("kgsl", true)
                    || d.name.contains("mali", true) || d.name.contains("g3d", true)) {
                    val cur = File(d, "cur_freq").readText().trim().toFloat()
                    val max = File(d, "max_freq").readText().trim().toFloat()
                    if (max > 0) return (cur / max).coerceIn(0f, 1.1f)
                }
            }
        } catch (_: Exception) {}
        return -1f
    }

    // ── GL renderer ─────────────────────────────────────────────────────────

    private fun renderLoop() {
        var display = EGL14.EGL_NO_DISPLAY
        var ctx = EGL14.EGL_NO_CONTEXT
        var surf = EGL14.EGL_NO_SURFACE
        var renderer: GpuScene? = null
        try {
            fun read(name: String) = assets.open(name).use { it.readBytes() }
            val sceneId = scene
            val lakeAsset = if (sceneId == GpuSceneId.LAKE_VALLEY) GpuSceneAsset(read(GpuSceneAsset.ASSET_NAME)) else null
            val cityAsset = if (sceneId == GpuSceneId.NEON_CITY) NeonCityAsset(read(NeonCityAsset.ASSET_NAME)) else null

            // ── EGL init: surfaceless context preferred, pbuffer fallback ──
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw GlException("eglInitialize")
            val exts = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""

            val cfgAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(8)
            val n = IntArray(1)
            // the export draws into a video encoder's surface, which some drivers only take from a recordable config
            if (sceneId == GpuSceneId.EXPORT) {
                val recordable = cfgAttrs.copyOf(cfgAttrs.size - 1) + intArrayOf(0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE)
                EGL14.eglChooseConfig(display, recordable, 0, cfgs, 0, 8, n, 0)
            }
            if (n[0] <= 0) EGL14.eglChooseConfig(display, cfgAttrs, 0, cfgs, 0, 8, n, 0)
            if (n[0] <= 0) throw GlException("eglChooseConfig")
            val cfg = cfgs[0]!!

            ctx = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) throw GlException("eglCreateContext ES3")

            // Headless "current" surfaces — used whenever no display is attached.
            // Kept so we can restore them after the window surface goes away.
            val headlessSurf: EGLSurface
            if (exts.contains("EGL_KHR_surfaceless_context")) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, ctx)
                headlessSurf = EGL14.EGL_NO_SURFACE
            } else {
                surf = EGL14.eglCreatePbufferSurface(display, cfg,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                if (surf == EGL14.EGL_NO_SURFACE) throw GlException("eglCreatePbufferSurface")
                EGL14.eglMakeCurrent(display, surf, surf, ctx)
                headlessSurf = surf
            }

            gpuName = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
            glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: ""

            // ── scene: geometry upload, shader compile, render targets ──
            val scene: GpuScene = when (sceneId) {
                GpuSceneId.LAKE_VALLEY -> LakeValleyRenderer(lakeAsset!!, RENDER_W, RENDER_H)
                GpuSceneId.NEON_CITY -> NeonCityRenderer(cityAsset!!, RENDER_W, RENDER_H)
                GpuSceneId.PHOTO_EDIT -> PhotoEditScene(assets, RENDER_W, RENDER_H)
                GpuSceneId.VIDEO -> VideoPlaybackScene(assets, RENDER_W, RENDER_H)
                GpuSceneId.EXPORT -> VideoExportScene(assets, cacheDir, RENDER_W, RENDER_H)
            }
            renderer = scene
            activeScene = scene
            scene.init()
            trianglesPerFrame = scene.trianglesPerFrame
            running = true

            // Display surface state — created lazily when the UI attaches a Surface.
            var windowSurf: EGLSurface = EGL14.EGL_NO_SURFACE
            var windowAndroidSurface: Surface? = null
            var winW = 0; var winH = 0
            EGL14.eglSwapInterval(display, 0) // never let vsync cap the measured loop
            var presentStride = 1             // adaptive: present ~1 in N measured frames

            fun destroyWindowSurface() {
                if (windowSurf != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, windowSurf)
                    windowSurf = EGL14.EGL_NO_SURFACE
                }
                windowAndroidSurface = null
                displayAttached = false
                // restore headless surfaces as current
                EGL14.eglMakeCurrent(display, headlessSurf, headlessSurf, ctx)
            }

            // ── frame loop — uncapped render to FBO, glFinish times each frame;
            //    every Nth frame is blitted to the display for visibility ──
            val startNs = System.nanoTime()
            while (!stopFlag.get()) {
                // Sync display surface with what the UI wants
                val wanted = wantedSurface
                if (wanted !== windowAndroidSurface) {
                    destroyWindowSurface()
                    if (wanted != null) {
                        windowSurf = EGL14.eglCreateWindowSurface(
                            display, cfg, wanted, intArrayOf(EGL14.EGL_NONE), 0)
                        if (windowSurf != EGL14.EGL_NO_SURFACE) {
                            // Framebuffer 0 must be THIS surface's back buffer —
                            // make it current, otherwise blit+swap go nowhere.
                            EGL14.eglMakeCurrent(display, windowSurf, windowSurf, ctx)
                            EGL14.eglSwapInterval(display, 0) // per-surface — set after makeCurrent
                            windowAndroidSurface = wanted
                            val wh = IntArray(1)
                            EGL14.eglQuerySurface(display, windowSurf, EGL14.EGL_WIDTH, wh, 0); winW = wh[0]
                            EGL14.eglQuerySurface(display, windowSurf, EGL14.EGL_HEIGHT, wh, 0); winH = wh[0]
                            displayAttached = true
                        }
                    }
                }

                val t0 = System.nanoTime()
                val sceneT = (t0 - startNs) / 1e9f
                scene.render(sceneT)
                GLES30.glFinish() // true frame completion — not just API enqueue
                val done = System.nanoTime()
                val dt = done - t0
                sceneSeconds = sceneT
                if (runForS > 0f) {
                    if (logCount == logStart.size) {
                        logStart = logStart.copyOf(logCount * 2); logDur = logDur.copyOf(logCount * 2)
                    }
                    logStart[logCount] = sceneT; logDur[logCount] = dt; logCount++
                }
                val frames = framesRendered.incrementAndGet()
                tick = FrameTick(frames, done, dt)
                frameNsTotal.addAndGet(dt)
                val idx = frameIdx.getAndIncrement()
                frameTimes[(idx % FRAME_HIST).toInt()] = dt

                // Present to the display at an adaptive stride — keeps the
                // visible stream near refresh rate without slowing measurement.
                if (windowSurf != EGL14.EGL_NO_SURFACE && idx % presentStride == 0L) {
                    // re-read the size: the buffer may change under us (rotation, resize)
                    val wh = IntArray(1)
                    EGL14.eglQuerySurface(display, windowSurf, EGL14.EGL_WIDTH, wh, 0); winW = wh[0]
                    EGL14.eglQuerySurface(display, windowSurf, EGL14.EGL_HEIGHT, wh, 0); winH = wh[0]
                    // landscape: fill the screen (a wider panel crops a little top and bottom);
                    // portrait: fit, so the whole frame stays visible
                    val sa = RENDER_W.toFloat() / RENDER_H
                    val da = winW.toFloat() / winH
                    val dw: Int; val dh: Int
                    if ((da > sa) == (winW < winH)) { dh = winH; dw = (winH * sa).toInt() }
                    else { dw = winW; dh = (winW / sa).toInt() }
                    val dx = (winW - dw) / 2; val dy = (winH - dh) / 2
                    GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, scene.outputFbo)
                    GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
                    GLES30.glClearColor(0f, 0f, 0f, 1f)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    GLES30.glBlitFramebuffer(0, 0, RENDER_W, RENDER_H,
                        dx, dy, dx + dw, dy + dh,
                        GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
                    if (EGL14.eglSwapBuffers(display, windowSurf)) {
                        presentedFrames.incrementAndGet()
                        EGLExt.eglPresentationTimeANDROID(display, windowSurf, done)
                    } else if (EGL14.eglGetError() == EGL14.EGL_BAD_SURFACE) {
                        destroyWindowSurface() // surface died mid-run — keep measuring headless
                    }
                }
                // Re-tune present stride every 256 frames from measured fps
                if (idx % 256L == 255L) {
                    val elapsed = frameNsTotal.get()
                    if (elapsed > 0 && frames > 0) {
                        val fps = frames * 1e9 / elapsed
                        presentStride = (fps / 45.0).toInt().coerceIn(1, 8)
                    }
                }
                if ((runForS > 0f && sceneT >= runForS) || scene.complete) { finished = true; break }
            }

            destroyWindowSurface()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        } catch (e: Exception) {
            glError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "renderer failed", e)
        } finally {
            try { renderer?.release() } catch (e: Exception) { Log.w(TAG, "scene release", e) }
            running = false
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                EGL14.eglTerminate(display)
            }
        }
    }
}
