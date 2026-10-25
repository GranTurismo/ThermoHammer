package com.example.thermohammer.engine.gpu

import android.view.Surface
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

/** JNI bindings of libneonrt.so (src/main/cpp) — the Vulkan renderer of GPU Test 3. */
internal object NeonRtNative {

    /** False on ABIs the library wasn't built for, or if it failed to load. */
    val loaded: Boolean = try {
        System.loadLibrary("neonrt")
        true
    } catch (_: Throwable) {
        false
    }

    /** Why ray queries are unavailable on this phone, or null when GPU Test 3 can run. */
    fun rayTracingProblem(): String? = if (!loaded) "Vulkan renderer not available for this CPU" else probe()

    @JvmStatic external fun probe(): String?
    @JvmStatic external fun create(asset: ByteBuffer, size: Int, actorMeshes: Array<String>, actorCapacity: IntArray,
                                   width: Int, height: Int, rayTracing: Boolean): Long
    @JvmStatic external fun lastError(): String
    @JvmStatic external fun deviceName(handle: Long): String
    @JvmStatic external fun triangles(handle: Long): Long
    @JvmStatic external fun render(handle: Long, frame: FloatBuffer, lights: FloatBuffer, lightCount: Int,
                                   cells: IntBuffer, indices: ShortBuffer, indexCount: Int,
                                   actors: FloatBuffer, actorCounts: IntArray, readback: Boolean): Boolean
    @JvmStatic external fun setWindow(handle: Long, surface: Surface?)
    @JvmStatic external fun present(handle: Long)
    @JvmStatic external fun readPixels(handle: Long, out: ByteBuffer)
    @JvmStatic external fun destroy(handle: Long)
}
