package com.example.thermohammer.engine.gpu

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.example.thermohammer.engine.ExportJob
import com.example.thermohammer.engine.ExportJobResult
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// VIDEO EXPORT — UX Test 6: what an editor's "export" does, encoder by encoder.
//
//   The source clip loops through its decoder into a SurfaceTexture; every
//   frame gets an edit pass on the GPU (colour grade, vignette, a lower-third
//   title, a dip to black where the clip loops) drawn straight into the
//   encoder's input surface, and the encoder writes a real MP4 (cacheDir,
//   deleted after). A job is a fixed timeline, exported start to finish —
//   ExportJob.frames (8 s of 4K60, 4 s of 8K30) — so the stage's progress bar
//   is the export's own; the rate is the frames after the first WARMUP_FRAMES
//   over the time until the file is finished. A chip that can't finish within
//   CAP_S is measured on what it did. eglSwapBuffers blocks while the encoder's
//   queue is full, so the rate is the slowest link, as in a real export.
//
//   Quality check: at SAMPLES the edit's lower third — an opaque title with a
//   timecode that changes every frame — is also drawn at full resolution and
//   read back; after the job the first encoded frames (kept in memory) are
//   decoded again and the same window compared: luma PSNR after a linear fit,
//   so a different but legitimate colour range doesn't count against the
//   encoder. Crisp graphics, not the rainy picture: rain is lost by every
//   encoder alike and drowns the difference between a good export and a
//   broken one. Honest encodes land near 30–34 dB there, a starved or broken
//   one near 24; below VideoGrid.MIN_PSNR the job's speed doesn't count.
//
//   An encoder the phone lacks — or only has in software, for AV1 — scores 0,
//   shown for SKIP_S and passed over.
// ─────────────────────────────────────────────────────────────────────────────

internal class VideoExportScene(private val assets: AssetManager, private val cacheDir: File,
                                private val outW: Int, private val outH: Int,
                                /** Tests only: export at 1/scale the size (an emulator's software encoders stop near 2K). */
                                private val scale: Int = 1) : GpuScene {

    companion object {
        const val WARMUP_FRAMES = 30
        const val CAP_S = com.example.thermohammer.engine.ExportStage.CAP_S.toFloat()
        const val SKIP_S = 1.0f
        private const val DRAIN_S = 2f
        private const val VERIFY_S = 4f
        /** The checked window, a fraction of the frame (x, y from the bottom, width, height): inside the lower third. */
        val WINDOW = floatArrayOf(0.0677f, 0.1523f, 0.375f, 0.1037f)
        /** Exported frames compared with what was sent in (those reached; at least one). */
        val SAMPLES = intArrayOf(6, 18, 30, 42)
        private const val KEEP = 80                     // encoded frames kept for the check (covers reordering)
    }

    enum class Stage { ENCODING, FINISHING, CHECKING }

    // published for the engine's sampler (another thread)
    @Volatile var current: ExportJob? = null; private set
    @Volatile var currentFps = 0.0; private set
    @Volatile var currentEncoder: String? = null; private set
    @Volatile var currentHardware = false; private set
    @Volatile var stage = Stage.ENCODING; private set
    @Volatile var framesDone = 0; private set           // frames of the timeline encoded so far
    @Volatile var elapsedS = 0f; private set            // since the job began
    @Volatile var progress = 0f; private set
    val results = CopyOnWriteArrayList<ExportJobResult>()

    override var outputFbo = 0; private set
    override val trianglesPerFrame = 2L
    @Volatile private var done = false
    override val complete: Boolean get() = done

    private lateinit var callbacks: HandlerThread
    private lateinit var handler: Handler
    private lateinit var edit: Program
    private lateinit var plain: Program
    private var vao = 0
    private var titleTex = 0
    private var tcTex = 0
    private val stMatrix = FloatArray(16)

    private var next = 0
    private var job: Job? = null
    private var phaseStart = 0f
    private var jobStart = 0f
    private var skipping = false

    override fun init() {
        callbacks = HandlerThread("th-export").also { it.start() }
        handler = Handler(callbacks.looper)
        outputFbo = Gl.framebuffer(colorTex = Gl.texture(outW, outH))
        vao = Gl.vertexArray()
        edit = Program("export edit", VS, EDIT_FS)
        plain = Program("export check", VS, PLAIN_FS)
        titleTex = titleTexture()
        tcTex = timecodeAtlas()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glClearColor(0.02f, 0.025f, 0.03f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        Gl.check("export init")
    }

    override fun render(t: Float) {
        if (done) return
        val j = job
        if (j == null && !skipping) {
            if (next >= ExportJob.entries.size) { finish(); return }
            begin(ExportJob.entries[next++], t)
            return
        }
        val el = t - phaseStart
        val index = next - 1
        if (skipping) {
            progress = (index + (el / SKIP_S).coerceIn(0f, 1f)) / ExportJob.entries.size
            if (el >= SKIP_S) skipping = false
            return
        }
        j!!
        try {
            when (stage) {
                Stage.ENCODING -> {
                    j.exportFrame()
                    val now = System.nanoTime()
                    track(j, t, index, now)
                    if (j.exportIndex >= j.job.frames) {             // every frame sent: the encoder finishes the file
                        j.endInput()
                        stage = Stage.FINISHING
                        phaseStart = t
                    } else if (t - jobStart >= CAP_S) {              // too slow to finish: measured on what it did
                        j.fps = j.rate(now, t - jobStart)
                        j.capped = true
                        j.endInput()
                        stage = Stage.FINISHING
                        phaseStart = t
                    }
                }
                Stage.FINISHING -> {
                    val now = System.nanoTime()
                    track(j, t, index, now)
                    if (j.eos.get() || el >= DRAIN_S) {
                        if (!j.capped) j.fps = j.rate(now, t - jobStart)  // the file is done: the export's whole time
                        j.closeMuxer()
                        stage = Stage.CHECKING
                        progress = (index + 0.9f) / ExportJob.entries.size
                    }
                }
                Stage.CHECKING -> {
                    val psnr = j.verify()
                    end(j, j.fps, psnr, null)
                }
            }
        } catch (e: Exception) {
            end(j, 0.0, 0.0, e.message ?: e.javaClass.simpleName)
        }
    }

    /** The stage's numbers while encoding: frames done, live rate (after the warm-up frames), progress. */
    private fun track(j: Job, t: Float, index: Int, now: Long) {
        val encoded = j.encoded.get()
        if (j.measureStartNs == 0L && encoded >= WARMUP_FRAMES) { j.measureStartNs = now; j.measureStartFrames = encoded }
        val r = j.rate(now, t - jobStart)
        if (r > 0) currentFps = r
        framesDone = encoded.toInt().coerceAtMost(j.job.frames)
        elapsedS = t - jobStart
        progress = (index + 0.88f * framesDone / j.job.frames) / ExportJob.entries.size
    }

    private fun begin(e: ExportJob, t: Float) {
        current = e; currentFps = 0.0; currentEncoder = null; currentHardware = false; framesDone = 0; elapsedS = 0f
        stage = Stage.ENCODING
        phaseStart = t
        jobStart = t
        val name = encoderFor(e)
        if (name == null) { unsupported(e, null, false, "no ${e.label} encoder for ${e.width / scale}×${e.height / scale}"); return }
        val hw = isHardware(name)
        currentEncoder = name; currentHardware = hw
        // a software AV1 encoder does a frame every few seconds at 4K — not an export anyone waits for
        if (!hw && e.codec == com.example.thermohammer.engine.VideoCodec.AV1) { unsupported(e, name, false, "no hardware AV1 encoder"); return }
        job = try {
            Job(e, name, hw)
        } catch (x: Exception) {
            unsupported(e, name, hw, x.message ?: x.javaClass.simpleName); null
        }
    }

    private fun unsupported(e: ExportJob, encoder: String?, hw: Boolean, why: String) {
        results += ExportJobResult(e, 0.0, 0.0, encoder, hw, why)
        skipping = true
    }

    private fun end(j: Job, fps: Double, psnr: Double, error: String?) {
        results += ExportJobResult(j.job, if (error == null) fps else 0.0, if (error == null) psnr else 0.0,
            j.encoderName, j.hardware, error)
        j.release()
        job = null
    }

    private fun finish() {
        done = true
        progress = 1f
        current = null
    }

    override fun release() {
        job?.release(); job = null
        if (::callbacks.isInitialized) callbacks.quitSafely()
    }

    // ── one export ─────────────────────────────────────────────────────────

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private inner class Job(val job: ExportJob, val encoderName: String, val hardware: Boolean) {
        private val width = job.width / scale
        private val height = job.height / scale
        private val cropW = Math.round(WINDOW[2] * width)
        private val cropH = Math.round(WINDOW[3] * height)
        private val cropTex = Gl.texture(cropW, cropH)
        private val cropFbo = Gl.framebuffer(colorTex = cropTex)
        private val pixels = Gl.direct(cropW * cropH * 4)
        private val display = EGL14.eglGetCurrentDisplay()
        private val context = EGL14.eglGetCurrentContext()
        private val afd = assets.openFd(job.source.asset)
        private val extractor = MediaExtractor().apply { setDataSource(afd) }
        private val srcTrack: Int
        private val clipUs: Long
        private val loopFrames: Int                      // source frames per pass of the clip
        private var decoder: MediaCodec? = null
        private val srcTex: Int
        private val srcSt: SurfaceTexture
        private val srcSurface: Surface
        private val srcPending = AtomicInteger(0)
        private var loopUs = 0L
        private var inputDone = false
        private val info = MediaCodec.BufferInfo()

        private val encoder: MediaCodec
        private val encSurface: EGLSurface
        private val inputSurface: Surface
        private val file = File(cacheDir, "export_${job.name.lowercase()}.mp4")
        private var muxer: MediaMuxer? = null
        private var track = -1
        private val muxLock = Any()
        @Volatile private var outFormat: MediaFormat? = null
        private val kept = ArrayList<Sample>()
        private val refs = HashMap<Int, ByteArray>()

        val encoded = AtomicLong(0)
        val eos = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile private var encoderError: String? = null
        var exportIndex = 0
        var measureStartNs = 0L
        var measureStartFrames = 0L
        var fps = 0.0
        var capped = false

        /** Frames per second after the warm-up frames (all of them, for a job that never got past those). */
        fun rate(now: Long, elapsedS: Float): Double {
            val n = encoded.get()
            if (measureStartNs != 0L && now > measureStartNs + 50_000_000L) return (n - measureStartFrames) / ((now - measureStartNs) / 1e9)
            return if (elapsedS > 0.05f) n / elapsedS.toDouble() else 0.0
        }

        init {
            srcTrack = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(srcTrack)
            val srcFormat = extractor.getTrackFormat(srcTrack)
            clipUs = if (srcFormat.containsKey(MediaFormat.KEY_DURATION)) srcFormat.getLong(MediaFormat.KEY_DURATION) else 3_000_000L
            loopFrames = max(1, (clipUs / 1e6 * job.source.fps).roundToInt())

            // the source: its own decoder into its own SurfaceTexture
            srcTex = oesTexture()
            srcSt = SurfaceTexture(srcTex).apply {
                setDefaultBufferSize(job.source.width, job.source.height)
                setOnFrameAvailableListener({ srcPending.incrementAndGet() }, handler)
            }
            srcSurface = Surface(srcSt)
            val query = MediaFormat.createVideoFormat(job.source.codec.mime, job.source.width, job.source.height).apply {
                for (k in arrayOf(MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL))
                    if (srcFormat.containsKey(k)) setInteger(k, srcFormat.getInteger(k))
            }
            val decName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(query)
                ?: throw IllegalStateException("can't decode the ${job.source.res} ${job.source.codec.short} source")
            decoder = MediaCodec.createByCodecName(decName).also { d ->
                val fast = MediaFormat(srcFormat).apply {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                    setInteger(MediaFormat.KEY_PRIORITY, 1)
                }
                try { d.configure(fast, srcSurface, null, 0) } catch (_: Exception) { d.reset(); d.configure(srcFormat, srcSurface, null, 0) }
                d.start()
            }

            // the encoder, fed through a surface; its output goes to the muxer on the callback thread
            encoder = MediaCodec.createByCodecName(encoderName)
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, bi: MediaCodec.BufferInfo) {
                    try {
                        val buf = codec.getOutputBuffer(index)
                        if (buf != null && bi.size > 0) {
                            val config = bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (kept.size < KEEP) synchronized(kept) {
                                val b = ByteArray(bi.size); buf.position(bi.offset); buf.get(b)
                                kept += Sample(b, bi.presentationTimeUs, bi.flags)
                            }
                            if (!config) {
                                synchronized(muxLock) {
                                    muxer?.takeIf { track >= 0 }?.let { m ->
                                        buf.position(bi.offset); buf.limit(bi.offset + bi.size)
                                        try { m.writeSampleData(track, buf, bi) } catch (_: Exception) {}
                                    }
                                }
                                encoded.incrementAndGet()
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.set(true)
                    } catch (e: Exception) { encoderError = e.message ?: e.javaClass.simpleName; eos.set(true) }
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    encoderError = e.diagnosticInfo ?: e.message; eos.set(true)
                }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    outFormat = format
                    try {
                        // MediaMuxer can't take every codec on every release (AV1 before Android 14): the export still
                        // runs and is checked from memory, it just isn't written
                        val m = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        track = m.addTrack(format)
                        m.start()
                        synchronized(muxLock) { muxer = m }
                    } catch (_: Exception) { track = -1 }
                }
            }, handler)
            // the export's settings, then the same with fewer hints for an encoder that refuses one: no speed hints,
            // no profile, a frame rate it is rated for
            fun format(rate: Int, withProfile: Boolean, hints: Boolean) = MediaFormat.createVideoFormat(job.mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, job.mbps * 1_000_000 / (scale * scale))
                setInteger(MediaFormat.KEY_FRAME_RATE, rate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (withProfile) profile(encoderName, job)?.let { (p, l) -> setInteger(MediaFormat.KEY_PROFILE, p); setInteger(MediaFormat.KEY_LEVEL, l) }
                if (hints) {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                    setInteger(MediaFormat.KEY_PRIORITY, 1)                   // not real time: as fast as it can
                }
            }
            val tries = listOf(format(job.fps, true, true), format(job.fps, true, false), format(job.fps, false, false),
                format(30, false, false))
            var configured: Exception? = null
            for (f in tries) {
                try { encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); configured = null; break }
                catch (e: Exception) { configured = e; encoder.reset() }
            }
            configured?.let { throw IllegalStateException("the ${job.label} encoder refused ${width}×${height}: ${it.message}") }
            inputSurface = encoder.createInputSurface()
            encoder.start()
            encSurface = EGL14.eglCreateWindowSurface(display, contextConfig(), inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            if (encSurface == EGL14.EGL_NO_SURFACE)
                throw IllegalStateException("can't draw into the encoder (EGL 0x${Integer.toHexString(EGL14.eglGetError())})")
        }

        private fun contextConfig(): EGLConfig {
            val id = IntArray(1)
            EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
            return cfgs[0] ?: throw IllegalStateException("no EGL config")
        }

        /** One frame: the next source frame, edited into the encoder (and the stage's preview). */
        fun exportFrame() {
            encoderError?.let { throw IllegalStateException(it) }
            nextSourceFrame()
            srcSt.getTransformMatrix(stMatrix)
            val k = exportIndex
            val pos = k % loopFrames
            val fade = (minOf(pos + 1, loopFrames - pos) / 8f).coerceIn(0.25f, 1f)   // a dip where the clip loops

            // into the encoder
            val drawS = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val readS = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            EGL14.eglMakeCurrent(display, encSurface, encSurface, context)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            drawEdit(width, height, fade, 0f, 0f, 1f, 1f)
            EGLExt.eglPresentationTimeANDROID(display, encSurface, k * 1_000_000_000L / job.fps)
            EGL14.eglSwapBuffers(display, encSurface)                         // blocks while the encoder is full
            EGL14.eglMakeCurrent(display, drawS, readS, context)

            // the reference for the quality check: the same edit, a full-resolution window at the centre
            if (k in SAMPLES) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cropFbo)
                val c = WINDOW
                drawEdit(cropW, cropH, fade, c[0], c[1], c[2], c[3])
                refs[k] = readCrop()
            }
            // the stage's preview
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
            drawEdit(outW, outH, fade, 0f, 0f, 1f, 1f)
            exportIndex++
        }

        private fun drawEdit(w: Int, h: Int, fade: Float, cx: Float, cy: Float, cw: Float, ch: Float) {
            GLES30.glViewport(0, 0, w, h)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glBindVertexArray(vao)
            edit.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, srcTex)
            edit.i("uSrc", 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, titleTex)
            edit.i("uTitle", 1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tcTex)
            edit.i("uTc", 2)
            // the timecode of this frame, HH:MM:SS:FF, as glyph indices (10 = ':')
            val k = exportIndex
            val sec = k / job.fps; val ff = k % job.fps
            val digits = intArrayOf(0, 0, 10, sec / 60 / 10, sec / 60 % 10, 10, sec % 60 / 10, sec % 60 % 10, 10, ff / 10, ff % 10)
            GLES30.glUniform1iv(GLES30.glGetUniformLocation(edit.id, "uDigits"), 11, digits, 0)
            edit.m4("uSt", stMatrix)
            edit.f4("uCrop", floatArrayOf(cx, cy, cw, ch))
            edit.f("uFade", fade)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }

        private fun readCrop(): ByteArray {
            pixels.position(0)
            GLES30.glReadPixels(0, 0, cropW, cropH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            val b = ByteArray(cropW * cropH * 4); pixels.position(0); pixels.get(b)
            return b
        }

        /** Latches the decoder's next frame (looping the clip), waiting for it if it isn't out yet. */
        private fun nextSourceFrame() {
            val d = decoder ?: return
            val deadline = System.nanoTime() + 2_000_000_000L
            while (true) {
                feed(d)
                val o = d.dequeueOutputBuffer(info, 1000)
                if (o >= 0) {
                    val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    d.releaseOutputBuffer(o, !ended)
                    if (ended) throw IllegalStateException("the source ended")
                    break
                }
                if (System.nanoTime() > deadline) throw IllegalStateException("the source decoder stalled")
            }
            while (srcPending.get() == 0) {
                if (System.nanoTime() > deadline) throw IllegalStateException("the source frame never arrived")
                Thread.sleep(0, 200_000)
            }
            srcPending.decrementAndGet()
            srcSt.updateTexImage()
        }

        private fun feed(d: MediaCodec) {
            if (inputDone) return
            while (true) {
                val i = d.dequeueInputBuffer(0)
                if (i < 0) return
                val buf = d.getInputBuffer(i) ?: return
                var n = extractor.readSampleData(buf, 0)
                if (n < 0) {                                                   // loop: same clip, later timestamps
                    loopUs += clipUs
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    n = extractor.readSampleData(buf, 0)
                }
                if (n < 0) { d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; return }
                d.queueInputBuffer(i, 0, n, loopUs + extractor.sampleTime, 0)
                extractor.advance()
            }
        }

        fun endInput() {
            try { encoder.signalEndOfInputStream() } catch (_: Exception) { eos.set(true) }
        }

        fun closeMuxer() = synchronized(muxLock) {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
        }

        /** Decodes the kept frames again and compares the SAMPLES windows with what was sent in: luma PSNR, dB. */
        fun verify(): Double {
            val format = outFormat ?: throw IllegalStateException("the encoder produced nothing")
            val samples = synchronized(kept) { kept.toList() }
            val wanted = refs.keys.toSortedSet()
            if (wanted.isEmpty() || samples.isEmpty()) return 0.0
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(
                MediaFormat.createVideoFormat(job.mime, width, height)) ?: throw IllegalStateException("can't decode the export to check it")
            val tex = oesTexture()
            val pending = AtomicInteger(0)
            val st = SurfaceTexture(tex).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener({ pending.incrementAndGet() }, handler)
            }
            val surface = Surface(st)
            val dec = MediaCodec.createByCodecName(name)
            val got = HashMap<Int, ByteArray>()
            try {
                val fmt = MediaFormat(format).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (samples.maxOf { it.data.size } + 4096))
                }
                dec.configure(fmt, surface, null, 0)
                dec.start()
                val noCsd = !format.containsKey("csd-0")
                val queue = samples.filter { noCsd || it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 }
                var fed = 0
                var eosSent = false
                val bi = MediaCodec.BufferInfo()
                val deadline = System.nanoTime() + (VERIFY_S * 1e9).toLong()
                while (got.size < wanted.size && System.nanoTime() < deadline) {
                    if (!eosSent) {
                        val i = dec.dequeueInputBuffer(0)
                        if (i >= 0) {
                            if (fed < queue.size) {
                                val s = queue[fed++]
                                dec.getInputBuffer(i)!!.apply { clear(); put(s.data) }
                                dec.queueInputBuffer(i, 0, s.data.size, s.ptsUs,
                                    s.flags and (MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_CODEC_CONFIG))
                            } else {
                                dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosSent = true
                            }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(bi, 2000)
                    if (o >= 0) {
                        val k = (bi.presentationTimeUs * job.fps / 1e6).roundToInt()
                        val show = bi.size > 0 && k in wanted && k !in got
                        dec.releaseOutputBuffer(o, show)
                        if (show) {
                            val wait = System.nanoTime() + 500_000_000L
                            while (pending.get() == 0 && System.nanoTime() < wait) Thread.sleep(0, 200_000)
                            if (pending.getAndSet(0) > 0) {
                                st.updateTexImage()
                                st.getTransformMatrix(stMatrix)
                                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cropFbo)
                                GLES30.glViewport(0, 0, cropW, cropH)
                                GLES30.glBindVertexArray(vao)
                                plain.use()
                                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                                plain.i("uSrc", 0)
                                plain.m4("uSt", stMatrix)
                                plain.f4("uCrop", WINDOW)
                                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                                got[k] = readCrop()
                            }
                        }
                        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                try { dec.stop() } catch (_: Exception) {}
                dec.release(); surface.release(); st.release()
                GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            }
            if (got.isEmpty()) throw IllegalStateException("the export didn't decode")
            return psnr(got.keys.map { refs[it]!! to got[it]!! })
        }

        fun release() {
            closeMuxer()
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            decoder = null
            if (encSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encSurface)
            inputSurface.release()
            srcSurface.release(); srcSt.release()
            GLES30.glDeleteTextures(2, intArrayOf(srcTex, cropTex), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(cropFbo), 0)
            extractor.release(); afd.close()
            file.delete()
        }
    }

    /**
     * Luma PSNR over the pairs (reference, exported), after fitting exported ≈ a·reference + b — a full-range vs
     * TV-range or BT.601 vs BT.709 round trip is a colour choice, not lost detail.
     */
    internal fun psnr(pairs: List<Pair<ByteArray, ByteArray>>): Double {
        var n = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        fun luma(b: ByteArray, i: Int) = (77 * (b[i].toInt() and 255) + 150 * (b[i + 1].toInt() and 255) + 29 * (b[i + 2].toInt() and 255)) / 256.0
        for ((r, e) in pairs) {
            var i = 0
            while (i < r.size) { val x = luma(r, i); val y = luma(e, i); n++; sx += x; sy += y; sxx += x * x; sxy += x * y; i += 4 }
        }
        val varX = sxx - sx * sx / n
        val a = if (varX > 1e-6) (sxy - sx * sy / n) / varX else 1.0
        val b = (sy - a * sx) / n
        var se = 0.0
        for ((r, e) in pairs) {
            var i = 0
            while (i < r.size) { val d = luma(e, i) - (a * luma(r, i) + b); se += d * d; i += 4 }
        }
        val mse = se / n
        return if (mse < 1e-6) 99.0 else (10 * log10(255.0 * 255.0 / mse) * 100).roundToInt() / 100.0
    }

    private fun oesTexture(): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    /**
     * The lower third: an opaque dark box, an accent rule, the title and "TC" (straight alpha; row 0 = top). The
     * timecode's digits are drawn per frame from [timecodeAtlas] into the slot right of "TC" (EDIT_FS).
     */
    private fun titleTexture(): Int {
        val w = 1600; val h = 240
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF080C10.toInt(); c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 24f, 24f, p)
        p.color = 0xFF3DE8C2.toInt(); c.drawRect(RectF(0f, 0f, 14f, h.toFloat()), p)
        p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        p.color = 0xFFF2F4F5.toInt(); p.textSize = 92f; c.drawText("NEON CITY · NIGHT RUN", 56f, 112f, p)
        p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        p.color = 0xFF3DE8C2.toInt(); p.textSize = 64f; c.drawText("TC", 58f, 206f, p)
        return upload(bmp)
    }

    /** "0123456789:" in eleven 64×96 cells, white on transparent — the timecode's glyphs. */
    private fun timecodeAtlas(): Int {
        val cw = 64; val ch = 96
        val bmp = Bitmap.createBitmap(cw * 11, ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); color = 0xFFFFFFFF.toInt(); textSize = 80f
            textAlign = Paint.Align.CENTER
        }
        "0123456789:".forEachIndexed { i, g -> c.drawText(g.toString(), cw * i + cw / 2f, ch * 0.78f, p) }
        return upload(bmp)
    }

    private fun upload(bmp: Bitmap): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        bmp.recycle()
        return id[0]
    }

    /** By size alone: speed is what's measured, so an encoder rated for 4K30 still gets to show its 4K rate. */
    private fun encoderFor(e: ExportJob): String? = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .findEncoderForFormat(MediaFormat.createVideoFormat(e.mime, e.width / scale, e.height / scale))

    /** H.264 High / HEVC Main / AV1 Main 8-bit at the encoder's highest level, when it offers it. */
    private fun profile(name: String, e: ExportJob): Pair<Int, Int>? {
        val want = when (e.codec) {
            com.example.thermohammer.engine.VideoCodec.AVC -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            com.example.thermohammer.engine.VideoCodec.HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            com.example.thermohammer.engine.VideoCodec.AV1 -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8
            else -> return null
        }
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }
            ?.getCapabilitiesForType(e.mime) ?: return null
        return caps.profileLevels.filter { it.profile == want }.maxByOrNull { it.level }?.let { it.profile to it.level }
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
private const val VS = """#version 300 es
out vec2 vUV;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUV = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

/** The edit: grade (saturation, contrast, teal shadows / warm highlights), vignette, the lower third, the dip. */
private const val EDIT_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uSrc;
uniform sampler2D uTitle;
uniform sampler2D uTc;   // "0123456789:" atlas
uniform int uDigits[11]; // this frame's timecode as atlas cells
uniform mat4 uSt;
uniform vec4 uCrop;      // the window of the frame drawn: x, y, width, height (0..1, y up)
uniform float uFade;
in vec2 vUV;
out vec4 oColor;
void main() {
    vec2 f = uCrop.xy + vUV * uCrop.zw;
    vec3 c = texture(uSrc, (uSt * vec4(f, 0.0, 1.0)).xy).rgb;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, 1.18);
    c = (c - 0.5) * 1.08 + 0.5;
    c += mix(vec3(-0.02, 0.01, 0.04), vec3(0.05, 0.02, -0.03), smoothstep(0.2, 0.8, l));
    vec2 d = (f - 0.5) * vec2(1.0, 0.75);
    c *= 1.0 - 0.35 * smoothstep(0.25, 0.6, length(d));
    vec2 t = (vec2(f.x, 1.0 - f.y) - vec2(0.06, 0.74)) / vec2(0.42, 0.112);   // 1600×240 at 16:9
    vec3 shot = clamp(c, 0.0, 1.0) * uFade;          // the picture dips at the loop; the lower third stays
    if (t.x >= 0.0 && t.y >= 0.0 && t.x <= 1.0 && t.y <= 1.0) {
        vec4 s = texture(uTitle, t);
        shot = mix(shot, s.rgb, s.a);
        // the timecode: eleven 0.04-wide cells from x = 0.13 on the second line (texture rows 0.55–0.95)
        vec2 g = vec2((t.x - 0.13) / 0.04, (t.y - 0.55) / 0.40);
        if (g.x >= 0.0 && g.x < 11.0 && g.y >= 0.0 && g.y <= 1.0) {
            int cell = uDigits[int(g.x)];
            float a = texture(uTc, vec2((float(cell) + fract(g.x)) / 11.0, g.y)).a;
            shot = mix(shot, vec3(0.24, 0.91, 0.76), a);
        }
    }
    oColor = vec4(shot, 1.0);
}
"""

/** The exported frame as decoded, the same window — what the reference is compared with. */
private const val PLAIN_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uSrc;
uniform mat4 uSt;
uniform vec4 uCrop;
in vec2 vUV;
out vec4 oColor;
void main() {
    vec2 f = uCrop.xy + vUV * uCrop.zw;
    oColor = vec4(texture(uSrc, (uSt * vec4(f, 0.0, 1.0)).xy).rgb, 1.0);
}
"""
