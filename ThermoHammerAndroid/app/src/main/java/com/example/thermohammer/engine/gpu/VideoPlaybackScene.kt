package com.example.thermohammer.engine.gpu

import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.example.thermohammer.engine.VideoClip
import com.example.thermohammer.engine.VideoCodecResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// ─────────────────────────────────────────────────────────────────────────────
// VIDEO PLAYBACK — UX Test 5: decode throughput, clip by clip.
//
//   Each clip in assets/media (VideoClip: three seconds of Neon City each, 4K60
//   H.264, HEVC Main 10, VP9, AV1 10-bit, then 8K30 HEVC and AV1) loops through
//   the phone's own decoder — MediaCodec, hardware where it has one — as fast
//   as it will go. Frames decode into a SurfaceTexture that this GL thread
//   latches and draws, so the stage shows the clip fast-forwarding and the
//   decoder never waits on the display. Per clip: WARMUP_S, then MEASURE_S
//   counted; the result is frames decoded per second. A clip the phone can't
//   decode scores 0 — shown for SKIP_S and passed over.
// ─────────────────────────────────────────────────────────────────────────────

internal class VideoPlaybackScene(private val assets: AssetManager, private val outW: Int, private val outH: Int) : GpuScene {

    companion object {
        const val WARMUP_S = 0.6f
        const val MEASURE_S = 3.6f
        const val SKIP_S = 1.0f
    }

    // published for the engine's sampler (another thread)
    @Volatile var current: VideoClip? = null; private set
    @Volatile var positionS = 0.0; private set          // where in the clip the newest frame is (the stage's timecode)
    @Volatile var durationS = 0.0; private set
    @Volatile var currentFps = 0.0; private set
    @Volatile var currentDecoder: String? = null; private set
    @Volatile var currentHardware = false; private set
    @Volatile var progress = 0f; private set
    val results = CopyOnWriteArrayList<VideoCodecResult>()

    override var outputFbo = 0; private set
    override val trianglesPerFrame = 2L
    @Volatile private var done = false
    override val complete: Boolean get() = done

    private var oesTex = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var surface: Surface
    private lateinit var callbacks: HandlerThread
    private val pending = AtomicInteger(0)
    private var haveFrame = false
    private val stMatrix = FloatArray(16)
    private lateinit var draw: Program
    private var vao = 0

    private var next = 0                  // index into VideoClip.entries
    private var session: Session? = null
    private var phaseStart = 0f
    private var skipping = false

    override fun init() {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        oesTex = id[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        callbacks = HandlerThread("th-video").also { it.start() }
        surfaceTexture = SurfaceTexture(oesTex)
        surfaceTexture.setDefaultBufferSize(3840, 2160)
        surfaceTexture.setOnFrameAvailableListener({ pending.incrementAndGet() }, Handler(callbacks.looper))
        surface = Surface(surfaceTexture)
        outputFbo = Gl.framebuffer(colorTex = Gl.texture(outW, outH))
        vao = Gl.vertexArray()
        draw = Program("video draw", DRAW_VS, DRAW_FS)
        Gl.check("video init")
    }

    override fun render(t: Float) {
        if (!done) step(t)
        // latch every frame the decoder has queued (in order), then show the newest
        var n = pending.getAndSet(0)
        while (n-- > 0) { surfaceTexture.updateTexImage(); haveFrame = true }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glClearColor(0.02f, 0.025f, 0.03f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (haveFrame && !skipping) {
            surfaceTexture.getTransformMatrix(stMatrix)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glBindVertexArray(vao)
            draw.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            draw.i("uTex", 0)
            draw.m4("uSt", stMatrix)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }
    }

    /** Advances the codec schedule: start, warm up, measure, record, next. */
    private fun step(t: Float) {
        val s = session
        if (s == null && !skipping) {
            if (next >= VideoClip.entries.size) { finish(); return }
            begin(VideoClip.entries[next++], t)
            return
        }
        val el = t - phaseStart
        val index = next - 1
        if (skipping) {
            progress = (index + (el / SKIP_S).coerceIn(0f, 1f)) / VideoClip.entries.size
            if (el >= SKIP_S) { skipping = false; haveFrame = false }
            return
        }
        s!!
        try {
            s.pump()
        } catch (e: Exception) {
            end(s, e.message ?: e.javaClass.simpleName)
            return
        }
        val now = System.nanoTime()
        if (s.measureStartNs == 0L && el >= WARMUP_S) { s.measureStartNs = now; s.measureStartFrames = s.decoded }
        if (s.measureStartNs != 0L) {
            val secs = (now - s.measureStartNs) / 1e9
            if (secs > 0.05) currentFps = (s.decoded - s.measureStartFrames) / secs
        } else if (el > 0.05f) currentFps = s.decoded / el.toDouble()
        progress = (index + (el / (WARMUP_S + MEASURE_S)).coerceIn(0f, 1f)) / VideoClip.entries.size
        val frames = s.clipFrames
        positionS = (s.decoded % frames) / s.clip.fps.toDouble()
        if (el >= WARMUP_S + MEASURE_S) end(s, null)
    }

    private fun begin(clip: VideoClip, t: Float) {
        current = clip; currentFps = 0.0; currentDecoder = null; currentHardware = false; positionS = 0.0; durationS = 3.0
        phaseStart = t
        haveFrame = false
        val s = try { Session(clip) } catch (e: Exception) { null.also { unsupported(clip, e.message) } }
        if (s == null) return
        durationS = s.clipFrames / clip.fps.toDouble()
        if (s.decoderName == null) { s.release(); unsupported(clip, "no ${clip.codec.label} decoder for ${clip.width}×${clip.height}"); return }
        currentDecoder = s.decoderName; currentHardware = s.hardware
        try {
            s.start(surface)
        } catch (e: Exception) {
            s.release()
            unsupported(clip, e.message ?: e.javaClass.simpleName)
            return
        }
        surfaceTexture.setDefaultBufferSize(clip.width, clip.height)
        session = s
    }

    private fun unsupported(clip: VideoClip, why: String?) {
        results += VideoCodecResult(clip, 0.0, currentDecoder, false, why ?: "not supported")
        skipping = true
    }

    private fun end(s: Session, error: String?) {
        val secs = if (s.measureStartNs != 0L) (System.nanoTime() - s.measureStartNs) / 1e9 else 0.0
        val fps = if (error == null && secs > 0) (s.decoded - s.measureStartFrames) / secs else 0.0
        results += VideoCodecResult(s.clip, fps, s.decoderName, s.hardware, error)
        s.release()
        session = null
    }

    private fun finish() {
        done = true
        progress = 1f
        current = null
    }

    override fun release() {
        session?.release(); session = null
        if (::surface.isInitialized) surface.release()
        if (::surfaceTexture.isInitialized) surfaceTexture.release()
        if (::callbacks.isInitialized) callbacks.quitSafely()
    }

    /** One clip looping through one decoder. */
    private inner class Session(val clip: VideoClip) {
        private val afd = assets.openFd(clip.asset)
        private val extractor = MediaExtractor().apply { setDataSource(afd) }
        private val track: Int
        private val clipUs: Long
        val clipFrames: Int
        val decoderName: String?
        val hardware: Boolean
        private var decoder: MediaCodec? = null
        private var loopUs = 0L
        private val info = MediaCodec.BufferInfo()
        var decoded = 0L
        var measureStartNs = 0L
        var measureStartFrames = 0L

        init {
            track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            clipUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 3_000_000L
            clipFrames = maxOf(1, Math.round(clipUs / 1e6 * clip.fps).toInt())
            // speed is what's measured, so the decoder has to take the size and profile, not 60 fps playback
            val query = MediaFormat.createVideoFormat(clip.codec.mime, clip.width, clip.height).apply {
                for (k in arrayOf(MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL))
                    if (format.containsKey(k)) setInteger(k, format.getInteger(k))
            }
            decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(query)
            hardware = decoderName != null && isHardware(decoderName)
        }

        fun start(surface: Surface) {
            val name = decoderName!!
            val fast = extractor.getTrackFormat(track).apply {
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                setInteger(MediaFormat.KEY_PRIORITY, 1)                    // not real time: as fast as it can
            }
            val c = MediaCodec.createByCodecName(name)
            decoder = c
            try {
                c.configure(fast, surface, null, 0)
            } catch (_: Exception) {
                c.reset()                                                  // some decoders reject the hints
                c.configure(extractor.getTrackFormat(track), surface, null, 0)
            }
            c.start()
        }

        fun pump() {
            val c = decoder ?: return
            while (true) {
                val i = c.dequeueInputBuffer(0)
                if (i < 0) break
                val buf = c.getInputBuffer(i) ?: break
                var n = extractor.readSampleData(buf, 0)
                if (n < 0) {                                               // loop: same clip, later timestamps
                    loopUs += clipUs
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    n = extractor.readSampleData(buf, 0)
                }
                if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                c.queueInputBuffer(i, 0, n, loopUs + extractor.sampleTime, 0)
                extractor.advance()
            }
            while (true) {
                val o = c.dequeueOutputBuffer(info, 0)
                if (o >= 0) {
                    val frame = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0
                    c.releaseOutputBuffer(o, frame)
                    if (frame) decoded++
                } else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break
            }
        }

        fun release() {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            decoder = null
            extractor.release()
            afd.close()
        }
    }

    private fun isHardware(name: String): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }
                ?.let { return it.isHardwareAccelerated }
        }
        val n = name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.startsWith("c2.goldfish."))
    }
}

/** External-OES sampling needs its extension right after #version, so these skip Gl.glsl's header. */
private const val DRAW_VS = """#version 300 es
uniform mat4 uSt;
out vec2 vUV;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUV = (uSt * vec4(p, 0.0, 1.0)).xy;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

private const val DRAW_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
in vec2 vUV;
out vec4 oColor;
void main() { oColor = vec4(texture(uTex, vUV).rgb, 1.0); }
"""
