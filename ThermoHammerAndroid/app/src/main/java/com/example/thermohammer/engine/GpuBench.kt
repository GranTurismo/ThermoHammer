package com.example.thermohammer.engine

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────────────
// GPU BENCHMARK — headless OpenGL ES 3.x sustained-throughput renderer
//
//   Renders a fixed instanced 3D scene into an offscreen 1920×1080 FBO so
//   results are comparable across display resolutions. No vsync — eglSwapBuffers
//   never runs; each frame ends with glFinish() for true GPU-bound timing.
//
//   Exposes per-frame timestamps + a SafeCounter-style frame counter so the
//   engine's 250 ms sampler can derive live FPS exactly like CPU IPS.
// ─────────────────────────────────────────────────────────────────────────────

class GpuBench {

    companion object {
        private const val TAG = "GpuBench"
        const val RENDER_W = 1920
        const val RENDER_H = 1080
        const val INSTANCES = 512            // cubes per frame — vertex/fragment load
        const val FRAG_ITERS = 24            // fragment shader workload loop
        private const val FRAME_HIST = 4096  // ring buffer of frame times
    }

    // ── published counters (read by the engine sampler thread) ──────────────
    val framesRendered = AtomicLong(0)        // measured FBO frames — uncapped
    val presentedFrames = AtomicLong(0)       // frames actually blitted to the display
    val frameNsTotal = AtomicLong(0)          // sum of frame times — for fps math
    private val frameTimes = LongArray(FRAME_HIST)
    private val frameIdx = AtomicLong(0)

    @Volatile var running = false; private set
    @Volatile var glError: String? = null; private set
    @Volatile var gpuName: String = ""; private set
    @Volatile var glVersion: String = ""; private set
    @Volatile var displayAttached = false; private set

    private var thread: Thread? = null
    private val stopFlag = AtomicBoolean(false)

    // Display surface requested by the UI (SurfaceView). Consumed on the GL
    // thread — attach/detach mid-run is safe; measurement keeps running headless.
    @Volatile private var wantedSurface: Surface? = null

    /** Attach a live display surface — the scene becomes visible. */
    fun attachDisplay(s: Surface) { wantedSurface = s }
    fun detachDisplay() { wantedSurface = null }

    // ── public API ──────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        stopFlag.set(false)
        framesRendered.set(0)
        presentedFrames.set(0)
        frameNsTotal.set(0)
        frameIdx.set(0)
        thread = Thread { renderLoop() }.also {
            it.name = "th-gpu"
            it.priority = Thread.NORM_PRIORITY
            it.start()
        }
    }

    fun stop() {
        stopFlag.set(true)
        thread?.join(1500)
        thread = null
        running = false
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
        try {
            // ── EGL init: surfaceless context preferred, pbuffer fallback ──
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw GLException("eglInitialize")
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
            EGL14.eglChooseConfig(display, cfgAttrs, 0, cfgs, 0, 8, n, 0)
            if (n[0] <= 0) throw GLException("eglChooseConfig")
            val cfg = cfgs[0]!!

            ctx = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) throw GLException("eglCreateContext ES3")

            // Headless "current" surfaces — used whenever no display is attached.
            // Kept so we can restore them after the window surface goes away.
            val headlessSurf: EGLSurface
            if (exts.contains("EGL_KHR_surfaceless_context")) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, ctx)
                headlessSurf = EGL14.EGL_NO_SURFACE
            } else {
                surf = EGL14.eglCreatePbufferSurface(display, cfg,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                if (surf == EGL14.EGL_NO_SURFACE) throw GLException("eglCreatePbufferSurface")
                EGL14.eglMakeCurrent(display, surf, surf, ctx)
                headlessSurf = surf
            }

            gpuName = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
            glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: ""

            // ── Offscreen FBO 1920×1080 ──
            val tex = IntArray(1); GLES30.glGenTextures(1, tex, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                RENDER_W, RENDER_H, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            val rb = IntArray(1); GLES30.glGenRenderbuffers(1, rb, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb[0])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, RENDER_W, RENDER_H)
            val fbo = IntArray(1); GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, tex[0], 0)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_RENDERBUFFER, rb[0])
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE)
                throw GLException("FBO incomplete")

            // ── program ──
            val prog = buildProgram(VERT_SRC, FRAG_SRC)
            GLES30.glUseProgram(prog)
            val uVP = GLES30.glGetUniformLocation(prog, "uVP")
            val uTime = GLES30.glGetUniformLocation(prog, "uTime")
            val vbo = buildCubeVbo()
            setupAttribs(prog)

            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glViewport(0, 0, RENDER_W, RENDER_H)

            running = true
            val proj = FloatArray(16); val view = FloatArray(16); val vp = FloatArray(16)
            Matrix.perspectiveM(proj, 0, 60f, RENDER_W.toFloat() / RENDER_H, 0.1f, 200f)

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
                val secs = t0 / 1e9f
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
                GLES30.glViewport(0, 0, RENDER_W, RENDER_H)
                GLES30.glClearColor(0.02f, 0.03f, 0.04f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
                Matrix.setLookAtM(view, 0,
                    26f * kotlin.math.cos(secs * 0.3f), 14f + 4f * kotlin.math.sin(secs * 0.5f),
                    26f * kotlin.math.sin(secs * 0.3f), 0f, 0f, 0f, 0f, 1f, 0f)
                Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
                GLES30.glUniformMatrix4fv(uVP, 1, false, vp, 0)
                GLES30.glUniform1f(uTime, secs)
                GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 36, INSTANCES)
                GLES30.glFinish() // true frame completion — not just API enqueue
                val dt = System.nanoTime() - t0
                framesRendered.incrementAndGet()
                frameNsTotal.addAndGet(dt)
                val idx = frameIdx.getAndIncrement()
                frameTimes[(idx % FRAME_HIST).toInt()] = dt

                // Present to the display at an adaptive stride — keeps the
                // visible stream near refresh rate without slowing measurement.
                if (windowSurf != EGL14.EGL_NO_SURFACE && idx % presentStride == 0L) {
                    // aspect-fit letterbox
                    val sa = RENDER_W.toFloat() / RENDER_H
                    val da = winW.toFloat() / winH
                    val dw: Int; val dh: Int
                    if (da > sa) { dh = winH; dw = (winH * sa).toInt() }
                    else { dw = winW; dh = (winW / sa).toInt() }
                    val dx = (winW - dw) / 2; val dy = (winH - dh) / 2
                    GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[0])
                    GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
                    GLES30.glBlitFramebuffer(0, 0, RENDER_W, RENDER_H,
                        dx, dy, dx + dw, dy + dh,
                        GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
                    if (EGL14.eglSwapBuffers(display, windowSurf)) {
                        presentedFrames.incrementAndGet()
                        EGLExt.eglPresentationTimeANDROID(display, windowSurf, t0 + dt)
                    } else if (EGL14.eglGetError() == EGL14.EGL_BAD_SURFACE) {
                        destroyWindowSurface() // surface died mid-run — keep measuring headless
                    }
                }
                // Re-tune present stride every 256 frames from measured fps
                if (idx % 256L == 255L) {
                    val elapsed = frameNsTotal.get()
                    val frames = framesRendered.get()
                    if (elapsed > 0 && frames > 0) {
                        val fps = frames * 1e9 / elapsed
                        presentStride = (fps / 45.0).toInt().coerceIn(1, 8)
                    }
                }
            }

            destroyWindowSurface()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        } catch (e: Exception) {
            glError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "renderer failed", e)
        } finally {
            running = false
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                EGL14.eglTerminate(display)
            }
        }
    }

    private class GLException(msg: String) : Exception(msg)

    // ── scene: 512 instanced cubes, per-instance motion in the vertex shader ──

    private val VERT_SRC = """
        #version 300 es
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        uniform mat4 uVP;
        uniform float uTime;
        out vec3 vN;
        out vec3 vP;
        out float vShade;
        mat4 rotY(float a){float c=cos(a),s=sin(a);return mat4(c,0,s,0, 0,1,0,0, -s,0,c,0, 0,0,0,1);}
        mat4 rotX(float a){float c=cos(a),s=sin(a);return mat4(1,0,0,0, 0,c,-s,0, 0,s,c,0, 0,0,0,1);}
        void main() {
            int id = gl_InstanceID;
            int gx = id % 16; int gz = (id / 16) % 16; int gy = id / 256;
            vec3 grid = vec3(float(gx - 8) * 2.4, float(gy - 1) * 2.4, float(gz - 8) * 2.4);
            float ph = float(id) * 0.37 + uTime * (0.6 + float(id % 7) * 0.11);
            mat4 m = mat4(1.0);
            m[3].xyz = grid;
            mat4 r = rotY(ph) * rotX(ph * 0.7);
            vec4 p = m * r * vec4(aPos * (0.7 + float(id % 5) * 0.12), 1.0);
            vN = normalize((r * vec4(aNormal, 0.0)).xyz);
            vP = p.xyz;
            vShade = float(id % 256) / 255.0;
            gl_Position = uVP * p;
        }
    """

    // Fragment shader: Blinn-Phong + procedural workload loop — the thermal load.
    private val FRAG_SRC = """
        #version 300 es
        precision highp float;
        in vec3 vN; in vec3 vP; in float vShade;
        uniform float uTime;
        out vec4 frag;
        void main() {
            vec3 n = normalize(vN);
            vec3 l = normalize(vec3(0.5, 0.8, 0.35));
            vec3 v = normalize(-vP);
            vec3 h = normalize(l + v);
            float dif = max(dot(n, l), 0.0);
            float spc = pow(max(dot(n, h), 0.0), 64.0);
            // deterministic workload — scales GPU cost without affecting output much
            float w = 0.0;
            for (int i = 0; i < $FRAG_ITERS; i++) {
                w += sin(vP.x * float(i) * 0.013 + uTime) * cos(vP.z * float(i) * 0.017);
            }
            vec3 col = mix(vec3(0.05, 0.55, 0.5), vec3(1.0, 0.45, 0.2), vShade);
            col *= 0.25 + dif * 0.85 + spc * 0.6 + w * 0.02;
            frag = vec4(col, 1.0);
        }
    """

    private fun buildProgram(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw GLException("shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw GLException("link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun buildCubeVbo(): Int {
        // 36 verts, pos(3)+normal(3), face-shaded cube
        val f = floatArrayOf(
            // front
            -1f,-1f, 1f, 0f,0f,1f,   1f,-1f, 1f, 0f,0f,1f,   1f, 1f, 1f, 0f,0f,1f,
            -1f,-1f, 1f, 0f,0f,1f,   1f, 1f, 1f, 0f,0f,1f,  -1f, 1f, 1f, 0f,0f,1f,
            // back
             1f,-1f,-1f, 0f,0f,-1f, -1f,-1f,-1f, 0f,0f,-1f, -1f, 1f,-1f, 0f,0f,-1f,
             1f,-1f,-1f, 0f,0f,-1f, -1f, 1f,-1f, 0f,0f,-1f,  1f, 1f,-1f, 0f,0f,-1f,
            // left
            -1f,-1f,-1f,-1f,0f,0f,  -1f,-1f, 1f,-1f,0f,0f,  -1f, 1f, 1f,-1f,0f,0f,
            -1f,-1f,-1f,-1f,0f,0f,  -1f, 1f, 1f,-1f,0f,0f,  -1f, 1f,-1f,-1f,0f,0f,
            // right
             1f,-1f, 1f, 1f,0f,0f,   1f,-1f,-1f, 1f,0f,0f,   1f, 1f,-1f, 1f,0f,0f,
             1f,-1f, 1f, 1f,0f,0f,   1f, 1f,-1f, 1f,0f,0f,   1f, 1f, 1f, 1f,0f,0f,
            // top
            -1f, 1f, 1f, 0f,1f,0f,   1f, 1f, 1f, 0f,1f,0f,   1f, 1f,-1f, 0f,1f,0f,
            -1f, 1f, 1f, 0f,1f,0f,   1f, 1f,-1f, 0f,1f,0f,  -1f, 1f,-1f, 0f,1f,0f,
            // bottom
            -1f,-1f,-1f, 0f,-1f,0f,  1f,-1f,-1f, 0f,-1f,0f,  1f,-1f, 1f, 0f,-1f,0f,
            -1f,-1f,-1f, 0f,-1f,0f,  1f,-1f, 1f, 0f,-1f,0f, -1f,-1f, 1f, 0f,-1f,0f
        )
        val buf: FloatBuffer = ByteBuffer.allocateDirect(f.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(f)
        buf.position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, f.size * 4, buf, GLES30.GL_STATIC_DRAW)
        return ids[0]
    }

    private fun setupAttribs(prog: Int) {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
    }
}
