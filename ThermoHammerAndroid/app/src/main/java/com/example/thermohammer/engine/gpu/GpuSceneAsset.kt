package com.example.thermohammer.engine.gpu

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ─────────────────────────────────────────────────────────────────────────────
// LAKE VALLEY SCENE ASSET — parsed from assets/lake_valley.bin
//
//   Written by BenchmarkScene/build_lake_valley.py (export_app_scene), which
//   documents the byte layout. Pure JVM — no GL — so it is unit-testable; the
//   renderer copies the vertex/index slices into direct buffers for upload.
// ─────────────────────────────────────────────────────────────────────────────

class GpuSceneAsset(bytes: ByteArray) {

    class Mesh(
        val name: String,
        val vertexCount: Int,
        val indexCount: Int,          // 0 for polar meshes — their indices are built on the phone
        val polarSegments: Int,
        val polarRings: Int,
        val height: Float,            // max Z — normalises wind sway
        val vertices: ByteBuffer,     // vertexCount × VERTEX_BYTES
        val indices: ByteBuffer?      // u16, indexCount entries
    ) {
        val isPolar get() = polarSegments > 0
        val triangleCount get() =
            if (isPolar) 2 * polarSegments * polarRings - polarSegments else indexCount / 3
    }

    /** Instances of one mesh: count × {x, y, z, euler x, y, z, scale x, y, z}. */
    class Group(val name: String, val mesh: Int, val count: Int, val transforms: FloatArray)

    val lakeCenterX: Float
    val lakeCenterY: Float
    val lakeRadiusX: Float
    val lakeRadiusY: Float
    val waterZ: Float
    val anchors: Map<String, FloatArray>
    val meshes: List<Mesh>
    val groups: List<Group>

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "LVSC") { "not a Lake Valley scene" }
        val version = b.int
        require(version == VERSION) { "scene asset v$version, renderer expects v$VERSION" }
        lakeCenterX = b.float; lakeCenterY = b.float
        lakeRadiusX = b.float; lakeRadiusY = b.float
        waterZ = b.float

        anchors = (0 until b.int).associate { name16(b) to floatArrayOf(b.float, b.float, b.float) }

        meshes = (0 until b.int).map {
            val name = name16(b)
            val vertexCount = b.int
            val indexCount = b.int
            val segments = b.int
            val rings = b.int
            val height = b.float
            val vertices = slice(b, vertexCount * VERTEX_BYTES)
            val indices = if (indexCount > 0) slice(b, indexCount * 2) else null
            if (indexCount % 2 == 1) b.position(b.position() + 2)      // u16 run padded to 4 bytes
            Mesh(name, vertexCount, indexCount, segments, rings, height, vertices, indices)
        }

        groups = (0 until b.int).map {
            val name = name16(b)
            val mesh = b.int
            val count = b.int
            val t = FloatArray(count * 9)
            b.asFloatBuffer().get(t)
            b.position(b.position() + t.size * 4)
            Group(name, mesh, count, t)
        }
        require(!b.hasRemaining()) { "scene asset has ${b.remaining()} trailing bytes" }
    }

    fun mesh(name: String): Mesh = meshes.first { it.name == name }
    fun meshIndex(name: String): Int = meshes.indexOfFirst { it.name == name }
    fun group(name: String): Group? = groups.firstOrNull { it.name == name }

    /** Triangles submitted by one full main-pass draw of every mesh and instance. */
    fun mainPassTriangles(): Long {
        val instanced = groups.map { it.mesh }.toSet()
        return groups.sumOf { it.count.toLong() * meshes[it.mesh].triangleCount } +
            meshes.withIndex().filter { it.index !in instanced }.sumOf { it.value.triangleCount.toLong() }
    }

    companion object {
        const val ASSET_NAME = "lake_valley.bin"
        const val VERSION = 1
        const val VERTEX_BYTES = 20           // f32 xyz · i8 normal ×4 · u8 sRGB rgb + class

        // vertex class (colour alpha byte)
        const val CLASS_SOLID = 0
        const val CLASS_GLOW = 128
        const val CLASS_FOLIAGE = 255

        private fun name16(b: ByteBuffer): String {
            val a = ByteArray(16).also { b.get(it) }
            return String(a, Charsets.US_ASCII).trimEnd('\u0000')
        }

        private fun slice(b: ByteBuffer, size: Int): ByteBuffer {
            val s = b.slice().order(ByteOrder.LITTLE_ENDIAN)
            s.limit(size)
            b.position(b.position() + size)
            return s
        }
    }
}
