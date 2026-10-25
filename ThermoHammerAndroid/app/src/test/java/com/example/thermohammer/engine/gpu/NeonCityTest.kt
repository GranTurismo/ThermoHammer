package com.example.thermohammer.engine.gpu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.hypot

/**
 * GPU Test 2's scene (assets/neon_city.bin, exported from Blender) must parse, carry what the
 * renderer and simulation read, keep its tuned workload, and animate identically on every device.
 */
class NeonCityTest {

    private val asset by lazy { NeonCityAsset(File("src/main/assets/${NeonCityAsset.ASSET_NAME}").readBytes()) }

    @Test
    fun `carries the meshes, groups and tables the renderer and simulation read`() {
        for (k in NeonCityAsset.CAR_KINDS) assertTrue("car_$k", asset.meshIndex("car_$k") >= 0)
        for (name in listOf("ped_0", "ped_1", "ped_2", "ped_3", "train_head", "train_car", "drone", "drone_police",
                "street", "viaduct")) assertTrue("mesh $name", asset.meshIndex(name) >= 0)
        for (name in listOf("win_res", "win_curtain", "balcony", "lamp", "lantern", "tower_0")) {
            assertTrue("group $name", asset.groups.any { it.name == name && it.count > 0 })
        }
        val cols = mapOf("lights" to 13, "lanes" to 8, "cars" to 7, "peds" to 11, "camera" to 8, "trains" to 5,
            "drones" to 7, "steam" to 3)
        for ((name, n) in cols) {
            val t = asset.table(name)
            assertTrue("table $name rows", t.rows > 0)
            assertEquals("table $name cols", n, t.cols)
        }
        assertTrue(asset.chunkMeshes().map { asset.meshes[it].name }.containsAll(listOf("street", "viaduct", "wires")))
        assertTrue("actors are never drawn as static chunks",
            asset.chunkMeshes().none { NeonCityAsset.isActor(asset.meshes[it].name) })
    }

    @Test
    fun `buffers match their declared sizes and indices stay in range`() {
        for (m in asset.meshes) {
            assertEquals(m.name, m.vertexCount * NeonCityAsset.VERTEX_BYTES, m.vertices.remaining())
            assertEquals(m.name, 0, m.indexCount % 3)
            val b = m.indices.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var max = 0L
            if (m.wideIndices) {
                val idx = b.asIntBuffer()
                assertEquals(m.name, m.indexCount, idx.remaining())
                while (idx.hasRemaining()) max = maxOf(max, idx.get().toLong() and 0xFFFFFFFFL)
            } else {
                val idx = b.asShortBuffer()
                assertEquals(m.name, m.indexCount, idx.remaining())
                while (idx.hasRemaining()) max = maxOf(max, (idx.get().toInt() and 0xFFFF).toLong())
            }
            assertTrue("${m.name} index $max ≥ ${m.vertexCount}", max < m.vertexCount)
            val classes = HashSet<Int>()
            for (v in 0 until m.vertexCount) classes += m.vertices.get(v * NeonCityAsset.VERTEX_BYTES + 19).toInt() and 0xFF
            assertTrue("${m.name} classes $classes", classes.all { it < NeonCityAsset.CLASSES.size })
        }
    }

    @Test
    fun `main pass stays within the tuned workload`() {
        val tris = asset.staticTriangles()
        assertTrue("static main pass $tris triangles", tris in 2_300_000L..3_200_000L)
        assertTrue("lights ${asset.table("lights").rows}", asset.table("lights").rows in 700..1100)
    }

    @Test
    fun `signal cycle matches the generator`() {
        // avenue: green 14 s, yellow 3 s, red 13 s; cross street: red 18 s, green 9 s, yellow 2.5 s
        val expect = listOf(0.0 to (0 to 2), 9.9 to (0 to 2), 10.5 to (1 to 2), 13.5 to (2 to 2),
            14.5 to (2 to 0), 23.0 to (2 to 1), 25.6 to (2 to 2), 26.5 to (0 to 2), -36.0 to (2 to 1))
        for ((t, s) in expect) {
            assertEquals("avenue at $t", s.first, NeonCitySim.signalState(0, t))
            assertEquals("cross at $t", s.second, NeonCitySim.signalState(1, t))
        }
    }

    @Test
    fun `simulation is deterministic and the camera follows its keys`() {
        val a = NeonCitySim(asset)
        val b = NeonCitySim(asset)
        for (t in listOf(0.0, 7.3, 31.0, 59.9)) { a.update(t); b.update(t) }
        assertArrayEquals(a.carLamps, b.carLamps, 0f)
        val cam = asset.table("camera")
        val s = NeonCitySim(asset)
        s.update(0.0)
        assertArrayEquals(floatArrayOf(cam[0, 1], cam[0, 2], cam[0, 3]), s.eye, 1e-4f)
        s.update(cam[cam.rows - 1, 0] - 1e-6)                        // the loop restarts at DURATION
        assertArrayEquals(floatArrayOf(cam[cam.rows - 1, 4], cam[cam.rows - 1, 5], cam[cam.rows - 1, 6]), s.target, 1e-2f)
        assertEquals(cam[cam.rows - 1, 7], s.fovDeg, 1e-3f)
    }

    @Test
    fun `cars glide between traffic steps at any frame rate`() {
        // 45 fps against the 30 Hz traffic step: drawn without blending, every third frame froze the cars
        val sim = NeonCitySim(asset)
        val dt = 1.0 / 45.0
        sim.update(10.0)
        var prev = sim.carLamps.copyOf()
        var checked = 0
        var t = 10.0 + dt
        while (t < 16.0) {
            val speed = DoubleArray(sim.carCount) { sim.carSpeed(it) }
            sim.update(t)
            for (i in 0 until sim.carCount) {
                val o = i * 7
                val moved = hypot(sim.carLamps[o] - prev[o], sim.carLamps[o + 1] - prev[o + 1]).toDouble()
                if (moved > 50.0) continue                                   // wrapped to the lane's start
                if (speed[i] > 2.0 && sim.carSpeed(i) > 2.0) {
                    val expected = (speed[i] + sim.carSpeed(i)) / 2 * dt
                    assertTrue("car $i moved ${"%.3f".format(moved)} m, expected ≈${"%.3f".format(expected)} at t=$t",
                        moved in expected * 0.6..expected * 1.4)
                    checked++
                }
            }
            prev = sim.carLamps.copyOf()
            t += dt
        }
        assertTrue("no moving cars checked", checked > 1000)
    }

    @Test
    fun `signs never blink`() {
        // steady (0-12) or a slow pulse (13-16) — nothing that steps from frame to frame
        val sim = NeonCitySim(asset)
        var prev: FloatArray? = null
        var t = 0.0
        while (t < 60.0) {
            sim.update(t)
            for (g in 0..12) assertEquals("sign group $g (t=$t)", 1f, sim.groups[g], 0f)
            prev?.let { p -> for (g in 13..16) assertEquals("pulse group $g (t=$t)", p[g], sim.groups[g], 0.03f) }
            prev = sim.groups.copyOf()
            t += 1.0 / 60.0
        }
    }

    @Test
    fun `cars never overlap and queue at a red light`() {
        val sim = NeonCitySim(asset)
        val lanes = asset.table("lanes")
        val cars = asset.table("cars")
        var t = 0.0
        var stoppedAtRed = 0
        while (t <= 60.0) {
            sim.update(t)
            for (li in 0 until lanes.rows) {
                val ids = (0 until sim.carCount).filter { sim.carLane(it) == li }.sortedBy { sim.carProgress(it) }
                val length = lanes[li, 4].toDouble()
                for (n in ids.indices) {
                    val i = ids[n]; val j = ids[(n + 1) % ids.size]
                    val gap = (sim.carProgress(j) - sim.carProgress(i)).mod(length) -
                        NeonCitySim.CAR_LENGTH[cars[j, 2].toInt()] / 2 - NeonCitySim.CAR_LENGTH[cars[i, 2].toInt()] / 2
                    assertTrue("lane $li cars $i/$j overlap ${"%.2f".format(gap)} m at t=$t", gap > -0.3)
                }
                if (lanes[li, 7] == 0f && NeonCitySim.signalState(0, t) == 2 && (t + 4.0).mod(30.0) > 22.0) {
                    for (i in ids) {
                        val toLine = lanes[li, 6] - sim.carProgress(i) - NeonCitySim.CAR_LENGTH[cars[i, 2].toInt()] / 2
                        if (toLine in 0.0..12.0) {
                            assertTrue("car $i rolling ${sim.carSpeed(i)} m/s at a red light", sim.carSpeed(i) < 0.6)
                            stoppedAtRed++
                        }
                    }
                }
            }
            t += 0.5
        }
        assertTrue("no car ever waited at a red light", stoppedAtRed > 0)
    }

    @Test
    fun `light grid lists every light where it shines, moving then static, each ranked`() {
        val lights = asset.table("lights")
        val grid = NeonLightGrid(lights, 64)
        grid.beginDynamic()
        grid.add(0f, 100f, 0.75f, 30f, 7f, 6.5f, 5.7f, 1, 0f, 0.985f, -0.17f, 0.85f)
        grid.build()
        fun cellOf(x: Float, y: Float): Int {
            val cx = floor((x - NeonLightGrid.X0) / NeonLightGrid.CELL).toInt()
            val cy = floor((y - NeonLightGrid.Y0) / NeonLightGrid.CELL).toInt()
            return if (cx in 0 until NeonLightGrid.CELLS_X && cy in 0 until NeonLightGrid.CELLS_Y)
                cy * NeonLightGrid.CELLS_X + cx else -1
        }
        fun listed(cell: Int): List<Int> {
            val v = grid.cells[cell]
            return (0 until (v and 63)).map { grid.indices[(v ushr 12) + it].toInt() }
        }
        var checked = 0
        for (i in 0 until lights.rows) {
            val cell = cellOf(lights[i, 0], lights[i, 1])
            if (cell < 0 || lights[i, 7] > 0.5f) continue
            val l = listed(cell)
            if (l.size < NeonLightGrid.MAX_PER_CELL) { assertTrue("light $i missing from its cell", i in l); checked++ }
        }
        assertTrue(checked > 500)
        assertTrue("headlight missing from the cell it lights", lights.rows in listed(cellOf(0f, 110f)))
        assertTrue(grid.cells.all { (it and 63) <= NeonLightGrid.MAX_PER_CELL })
        // fog reads only the leading moving lights; each run must go from strongest to faintest there
        for (cell in grid.cells.indices) {
            val l = listed(cell)
            val moving = (grid.cells[cell] ushr 6) and 63
            assertTrue("cell $cell: moving count", l.take(moving).all { it >= grid.staticCount } &&
                l.drop(moving).all { it < grid.staticCount })
            for (run in listOf(l.take(moving), l.drop(moving))) {
                val imp = run.map { grid.importance(it, cell) }
                assertTrue("cell $cell not ranked: $imp", imp.zipWithNext().all { (a, b) -> a >= b })
            }
        }
    }
}
