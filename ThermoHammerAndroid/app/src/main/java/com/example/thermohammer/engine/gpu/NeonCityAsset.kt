package com.example.thermohammer.engine.gpu

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY SCENE ASSET — parsed from assets/neon_city.bin (GPU Test 2)
//
//   Written by BenchmarkScene/build_neon_city.py (export_app_scene), which
//   documents the byte layout. Pure JVM — no GL — so it is unit-testable.
//   Static chunks (buildings, streets, viaduct) are drawn once in world space;
//   prototypes are drawn through their instance group or animated by the app
//   (cars, people, trains, drones). Tables carry everything that moves or glows.
// ─────────────────────────────────────────────────────────────────────────────

class NeonCityAsset(bytes: ByteArray) {

    class Mesh(
        val name: String,
        val vertexCount: Int,
        val indexCount: Int,
        val wideIndices: Boolean,     // u32 indices (building chunks), else u16
        val chunk: Boolean,           // static, drawn once in world space
        val maxZ: Float,
        val vertices: ByteBuffer,     // vertexCount × VERTEX_BYTES
        val indices: ByteBuffer
    ) {
        val triangleCount get() = indexCount / 3
    }

    /** Instances of one mesh: count × {x, y, z, euler x, y, z, scale x, y, z}. */
    class Group(val name: String, val mesh: Int, val count: Int, val transforms: FloatArray)

    class Table(val name: String, val rows: Int, val cols: Int, val data: FloatArray) {
        operator fun get(row: Int, col: Int): Float = data[row * cols + col]
        fun row(r: Int): FloatArray = data.copyOfRange(r * cols, (r + 1) * cols)
    }

    val meshes: List<Mesh>
    val groups: List<Group>
    val tables: Map<String, Table>

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "NCSC") { "not a Neon City scene" }
        val version = b.int
        require(version == VERSION) { "scene asset v$version, renderer expects v$VERSION" }

        meshes = (0 until b.int).map {
            val name = name16(b)
            val vertexCount = b.int
            val indexCount = b.int
            val flags = b.int
            val maxZ = b.float
            val wide = flags and 1 != 0
            val chunk = flags and 2 != 0
            val vertices = slice(b, vertexCount * VERTEX_BYTES)
            val indices = slice(b, indexCount * (if (wide) 4 else 2))
            if (!wide && indexCount % 2 == 1) b.position(b.position() + 2)
            Mesh(name, vertexCount, indexCount, wide, chunk, maxZ, vertices, indices)
        }

        groups = (0 until b.int).map {
            val name = name16(b)
            val mesh = b.int
            val count = b.int
            Group(name, mesh, count, floats(b, count * 9))
        }

        tables = (0 until b.int).associate {
            val name = name16(b)
            val rows = b.int
            val cols = b.int
            name to Table(name, rows, cols, floats(b, rows * cols))
        }
        require(!b.hasRemaining()) { "scene asset has ${b.remaining()} trailing bytes" }
    }

    fun meshIndex(name: String): Int = meshes.indexOfFirst { it.name == name }
    fun mesh(name: String): Mesh = meshes[meshIndex(name).also { require(it >= 0) { "mesh $name missing" } }]
    fun table(name: String): Table = tables[name] ?: Table(name, 0, 0, FloatArray(0))

    fun chunkMeshes(): List<Int> = meshes.indices.filter { meshes[it].chunk }

    /** Triangles of one full main-pass draw of all static geometry and instances. */
    fun staticTriangles(): Long =
        groups.sumOf { it.count.toLong() * meshes[it.mesh].triangleCount } +
            chunkMeshes().sumOf { meshes[it].triangleCount.toLong() }

    companion object {
        const val ASSET_NAME = "neon_city.bin"
        const val VERSION = 1
        const val VERTEX_BYTES = 20           // f32 xyz · i8 normal + channel · u8 sRGB rgb + class

        // Vertex classes — must match CLASSES in build_neon_city.py
        val CLASSES = listOf("concrete", "brick", "tile", "metal", "glass", "shopglass", "emissive",
            "asphalt", "paving", "paint", "carpaint", "carglass", "rubber", "chrome", "headlight",
            "taillight", "fabric", "skin", "foliage", "screen", "tower", "lantern", "trainwin", "wood")

        val CAR_KINDS = listOf("sedan", "hatch", "suv", "taxi", "van", "bus")

        fun isActor(name: String) = name.startsWith("car_") || name.startsWith("ped_") ||
            name.startsWith("train_") || name.startsWith("drone")

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

        private fun floats(b: ByteBuffer, n: Int): FloatArray {
            val f = FloatArray(n)
            b.asFloatBuffer().get(f)
            b.position(b.position() + n * 4)
            return f
        }
    }
}
