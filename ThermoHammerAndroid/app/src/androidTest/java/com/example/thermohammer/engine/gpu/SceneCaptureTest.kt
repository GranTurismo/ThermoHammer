package com.example.thermohammer.engine.gpu

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a GPU benchmark scene at fixed time steps and saves the frames — a smooth demo/store
 * video regardless of how fast the device renders — or just times them. Opt-in, skipped otherwise:
 *
 *   adb shell am instrument -w -e frames 1500 -e fps 30 -e scene neon \
 *     -e class com.example.thermohammer.engine.gpu.SceneCaptureTest \
 *     com.granturismo.thermohammer.test/androidx.test.runner.AndroidJUnitRunner
 *
 *   scene  lake (default) | neon | neonrt (Vulkan, GPU Test 3)    start  first frame's time (s)
 *   every  save every Nth frame (1)     time   true = only time frames, save nothing
 *   rt     neonrt only: false renders without ray queries (GPUs that lack them)
 *   w, h   GL scenes only: output size (1920 × 1080) — e.g. the 12 MP photo and 4K clips of UX Tests 3–5
 *   png    true = lossless frames (f_0000.png …) for re-encoding
 *
 * Frames land in the app's external files dir (capture_<scene>/f_0000.jpg …), bottom-up as GL
 * reads them: encode with `ffmpeg -framerate 30 -i f_%04d.jpg -vf vflip …`. The mean GPU frame
 * time is reported in the instrumentation status ("frame_ms").
 */
@RunWith(AndroidJUnit4::class)
class SceneCaptureTest {

    @Test
    fun captureFrames() {
        val args = InstrumentationRegistry.getArguments()
        val frames = args.getString("frames")?.toIntOrNull()
        assumeTrue("pass -e frames N to capture", frames != null && frames > 0)
        val fps = args.getString("fps")?.toFloatOrNull() ?: 30f
        val start = args.getString("start")?.toFloatOrNull() ?: 0f
        val every = args.getString("every")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val timeOnly = args.getString("time") == "true"
        val sceneArg = args.getString("scene") ?: "lake"
        val neon = sceneArg == "neon"
        val png = args.getString("png") == "true"
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "capture_$sceneArg").apply { deleteRecursively(); mkdirs() }
        if (sceneArg == "neonrt") {
            captureVulkan(ctx, out, frames!!, fps, start, every, timeOnly, args.getString("rt") != "false")
            return
        }

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1))
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE
        ), 0, cfgs, 0, 1, n, 0)
        check(n[0] > 0)
        val egl = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(display, cfgs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surf, surf, egl))
        try {
            val w = args.getString("w")?.toIntOrNull() ?: 1920
            val h = args.getString("h")?.toIntOrNull() ?: 1080
            fun read(name: String) = ctx.assets.open(name).use { it.readBytes() }
            val scene: GpuScene = if (neon) NeonCityRenderer(NeonCityAsset(read(NeonCityAsset.ASSET_NAME)), w, h)
                else LakeValleyRenderer(GpuSceneAsset(read(GpuSceneAsset.ASSET_NAME)), w, h)
            scene.init()
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            var timedNs = 0L
            var timed = 0
            for (i in 0 until frames!!) {
                val t0 = System.nanoTime()
                scene.render(start + i / fps)
                GLES30.glFinish()
                if (i >= 3) { timedNs += System.nanoTime() - t0; timed++ }     // skip shader warm-up
                if (timeOnly || i % every != 0) continue
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, scene.outputFbo)
                pixels.position(0)
                GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                pixels.position(0)
                bmp.copyPixelsFromBuffer(pixels)
                FileOutputStream(File(out, "f_%04d.%s".format(i / every, if (png) "png" else "jpg"))).use {
                    if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, it) else bmp.compress(Bitmap.CompressFormat.JPEG, 93, it)
                }
            }
            val error = GLES30.glGetError()
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("frame_ms", if (timed > 0) "%.2f".format(timedNs / 1e6 / timed) else "n/a")
                putString("triangles", scene.trianglesPerFrame.toString())
                putString("gl_error", "0x" + Integer.toHexString(error))
                putString("renderer", GLES30.glGetString(GLES30.GL_RENDERER))
            })
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surf)
            EGL14.eglDestroyContext(display, egl)
            EGL14.eglTerminate(display)
        }
    }

    /** GPU Test 3 through the Vulkan renderer; frames saved bottom-up like the GL captures. */
    private fun captureVulkan(ctx: android.content.Context, out: File, frames: Int, fps: Float, start: Float,
                              every: Int, timeOnly: Boolean, rayTracing: Boolean) {
        val w = 1920
        val h = 1080
        val bytes = ctx.assets.open(NeonCityAsset.ASSET_NAME).use { it.readBytes() }
        val scene = NeonRtScene(NeonCityAsset(bytes), bytes, w, h, rayTracing)
        scene.init()
        try {
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            val flipped = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            val row = ByteArray(w * 4)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            var timedNs = 0L
            var timed = 0
            for (i in 0 until frames) {
                val save = !timeOnly && i % every == 0
                val t0 = System.nanoTime()
                scene.render(start + i / fps, readback = save)
                if (i >= 3) { timedNs += System.nanoTime() - t0; timed++ }
                if (!save) continue
                scene.readPixels(pixels)
                for (y in 0 until h) {
                    pixels.position((h - 1 - y) * w * 4); pixels.get(row)
                    flipped.position(y * w * 4); flipped.put(row)
                }
                flipped.position(0)
                bmp.copyPixelsFromBuffer(flipped)
                FileOutputStream(File(out, "f_%04d.jpg".format(i / every))).use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 93, it)
                }
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("frame_ms", if (timed > 0) "%.2f".format(timedNs / 1e6 / timed) else "n/a")
                putString("triangles", scene.triangles.toString())
                putString("renderer", "Vulkan " + scene.deviceName + if (rayTracing) " (ray queries)" else " (no RT)")
            })
        } finally {
            scene.destroy()
        }
    }
}
