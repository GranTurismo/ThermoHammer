package com.example.thermohammer.engine.gpu

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.GLES30
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.engine.ExportJob
import com.example.thermohammer.engine.VideoClip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * UX Tests 4–6 on a real GL context: the photo-editing chain renders without GL errors, every clip either
 * decodes or is reported unsupported, and every export either runs (with its quality checked) or is reported
 * unsupported. Rates, quality and codecs go to the instrumentation status; a preview frame of each lands in the
 * app's external files dir (ux_photo.png, ux_video.png, ux_export.png).
 */
@RunWith(AndroidJUnit4::class)
class UxScenesTest {

    private lateinit var display: EGLDisplay
    private lateinit var ctx: android.opengl.EGLContext
    private lateinit var surf: android.opengl.EGLSurface
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Before
    fun egl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1))
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        // window-capable and recordable, like GpuBench for the export: it draws into an encoder's surface
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, 0x3142, 1, EGL14.EGL_NONE
        ), 0, cfgs, 0, 1, n, 0)
        if (n[0] <= 0) EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE
        ), 0, cfgs, 0, 1, n, 0)
        ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surf = EGL14.eglCreatePbufferSurface(display, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surf, surf, ctx))
    }

    @After
    fun teardown() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surf)
        EGL14.eglDestroyContext(display, ctx)
        EGL14.eglTerminate(display)
    }

    @Test
    fun photoEditingChainRenders() {
        val scene = PhotoEditScene(app.assets, W, H)
        scene.init()
        var timedNs = 0L
        for (i in 0 until 10) {
            val t0 = System.nanoTime()
            scene.render(i * 0.4f)
            GLES30.glFinish()
            if (i >= 2) timedNs += System.nanoTime() - t0
        }
        assertEquals("GL error", GLES30.GL_NO_ERROR, GLES30.glGetError())
        assertEquals(12.0, scene.megapixels, 0.01)
        save(scene.outputFbo, "ux_photo.png")
        val fps = 8 * 1e9 / timedNs
        status("photo_mps" to "%.2f".format(fps * scene.megapixels), "photo_ms" to "%.1f".format(1000 / fps))
        scene.release()
    }

    @Test
    fun everyClipDecodesOrIsReportedUnsupported() {
        val scene = VideoPlaybackScene(app.assets, W, H)
        scene.init()
        val t0 = System.nanoTime()
        var saved = false
        while (!scene.complete) {
            val t = (System.nanoTime() - t0) / 1e9f
            assertTrue("video scene didn't finish", t < 90f)
            scene.render(t)
            GLES30.glFinish()
            if (!saved && scene.current == VideoClip.VP9_4K && scene.currentFps > 0) { save(scene.outputFbo, "ux_video.png"); saved = true }
        }
        assertEquals("GL error", GLES30.GL_NO_ERROR, GLES30.glGetError())
        val results = scene.results.toList()
        assertEquals(VideoClip.entries.toSet(), results.map { it.clip }.toSet())
        assertTrue("H.264 must decode everywhere: $results", results.first { it.clip == VideoClip.AVC_4K }.fps > 0)
        status(*results.map { r ->
            r.clip.name to "%.1f fps %s %s %s".format(r.fps, if (r.hardware) "HW" else "SW", r.decoder ?: "-", r.error ?: "")
        }.toTypedArray())
        scene.release()
    }

    @Test
    fun everyExportRunsOrIsReportedUnsupported() {
        // an emulator's software encoders stop near 2K: there the pipeline is checked at 1080p
        val emulator = android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish")
        val scene = VideoExportScene(app.assets, app.cacheDir, W, H, scale = if (emulator) 2 else 1)
        scene.init()
        val t0 = System.nanoTime()
        var saved = false
        while (!scene.complete) {
            val t = (System.nanoTime() - t0) / 1e9f
            assertTrue("export scene didn't finish", t < 120f)
            scene.render(t)
            GLES30.glFinish()
            if (!saved && scene.current == ExportJob.AVC_4K && scene.framesDone > 30) { save(scene.outputFbo, "ux_export.png"); saved = true }
        }
        assertEquals("GL error", GLES30.GL_NO_ERROR, GLES30.glGetError())
        val results = scene.results.toList()
        assertEquals(ExportJob.entries.toSet(), results.map { it.job }.toSet())
        status(*results.map { r ->
            r.job.name to "%.1f fps %.2f dB %s %s %s".format(r.fps, r.psnr, if (r.hardware) "HW" else "SW", r.encoder ?: "-", r.error ?: "")
        }.toTypedArray())
        assertTrue("H.264 must export everywhere: $results", results.first { it.job == ExportJob.AVC_4K }.fps > 0)
        scene.release()
    }

    private fun save(fbo: Int, name: String) {
        val px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo)
        GLES30.glReadPixels(0, 0, W, H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        px.position(0)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(px) }
        FileOutputStream(File(app.getExternalFilesDir(null), name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun status(vararg kv: Pair<String, String>) =
        instrumentation.sendStatus(0, Bundle().apply { for ((k, v) in kv) putString(k, v) })

    private companion object {
        const val W = 1920
        const val H = 1080
    }
}
