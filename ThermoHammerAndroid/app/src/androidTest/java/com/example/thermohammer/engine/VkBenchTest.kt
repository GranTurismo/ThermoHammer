package com.example.thermohammer.engine

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GPU Test 3's Vulkan runner end to end: thread, frame log, fixed-length finish and presentation
 * through a swapchain. Ray queries are switched off so it also runs on GPUs (and emulators) without them;
 * on a ray-tracing phone a second case runs the real thing.
 */
@RunWith(AndroidJUnit4::class)
class VkBenchTest {

    private fun run(rayTracing: Boolean) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(1920, 1080) }
        val surface = Surface(texture)
        val bench = VkBench(ctx.assets)
        try {
            bench.attachDisplay(surface)
            bench.start(runForS = 4f, rayTracing = rayTracing)
            val deadline = System.currentTimeMillis() + 60_000
            while (!bench.finished && bench.glError == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertNull("renderer error", bench.glError)
            assertTrue("timeline finished", bench.finished)
            bench.stop()
            val (starts, durs, count) = bench.frameLog()
            assertTrue("frames rendered: $count", count >= 5)
            assertTrue(starts[count - 1] >= 4f)
            assertTrue(durs.take(count).all { it > 0 })
            assertTrue(bench.gpuName.isNotBlank())
            assertEquals(false, bench.running)
        } finally {
            bench.stop()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun rendersAndPresentsWithoutRayQueries() = run(rayTracing = false)

    @Test
    fun rendersWithRayQueriesWhereSupported() {
        assumeTrue("no Vulkan ray tracing: ${VkBench.rayTracingProblem}", VkBench.rayTracingProblem == null)
        run(rayTracing = true)
    }
}
