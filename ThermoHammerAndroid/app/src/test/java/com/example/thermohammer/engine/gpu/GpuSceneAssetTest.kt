package com.example.thermohammer.engine.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import kotlin.math.hypot

/**
 * The shipped benchmark scene (assets/lake_valley.bin, exported from Blender) must parse,
 * carry everything the renderer draws, and keep the workload it was tuned for — a careless
 * re-export with the Blender-quality meshes would silently change every GPU score.
 */
class GpuSceneAssetTest {

    private val asset by lazy {
        GpuSceneAsset(File("src/main/assets/${GpuSceneAsset.ASSET_NAME}").readBytes())
    }

    @Test
    fun `carries every mesh, instance group and anchor the renderer uses`() {
        for (name in listOf("terrain", "pine_a", "pine_b", "broadleaf", "pine_far",
                "rock_0", "rock_1", "rock_2", "reeds", "boat", "cabin", "dock")) {
            assertTrue("mesh $name", asset.meshIndex(name) >= 0)
        }
        for (name in listOf("pine_a", "pine_b", "broadleaf", "pine_far", "reeds")) {
            assertTrue("group $name", (asset.group(name)?.count ?: 0) > 0)
        }
        assertNotNull(asset.anchors["chimney"])
        assertNotNull(asset.anchors["boat"])
    }

    @Test
    fun `buffers match their declared sizes and indices stay in range`() {
        for (m in asset.meshes) {
            assertEquals(m.name, m.vertexCount * GpuSceneAsset.VERTEX_BYTES, m.vertices.remaining())
            if (m.isPolar) {
                assertNull(m.indices)
                assertEquals(m.name, 1 + m.polarSegments * m.polarRings, m.vertexCount)
            } else {
                val idx = m.indices!!.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                assertEquals(m.name, m.indexCount, idx.remaining())
                assertEquals(m.name, 0, m.indexCount % 3)
                var max = 0
                while (idx.hasRemaining()) max = maxOf(max, idx.get().toInt() and 0xFFFF)
                assertTrue("${m.name} index $max ≥ ${m.vertexCount}", max < m.vertexCount)
            }
        }
    }

    @Test
    fun `instances stand inside the valley at sane scales`() {
        for (g in asset.groups) {
            assertEquals(g.name, g.count * 9, g.transforms.size)
            for (i in 0 until g.count) {
                val o = i * 9
                val r = hypot(g.transforms[o] - asset.lakeCenterX, g.transforms[o + 1] - asset.lakeCenterY)
                assertTrue("${g.name}[$i] at $r m", r < 3500f)
                for (s in 6..8) assertTrue("${g.name}[$i] scale", g.transforms[o + s] in 0.2f..5f)
            }
        }
    }

    @Test
    fun `main pass stays within the tuned phone budget`() {
        val tris = asset.mainPassTriangles()
        assertTrue("main pass $tris triangles", tris in 1_500_000L..3_000_000L)
    }
}
