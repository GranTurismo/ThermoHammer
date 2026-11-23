package com.example.thermohammer.engine.gpu

import android.opengl.GLES30
import android.opengl.Matrix
import com.example.thermohammer.engine.gpu.NeonCityShaders as S
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY — GPU Test 2: a rainy downtown avenue at night (BenchmarkScene/).
//
//   Per frame:  light grid (≈870 static + ≈300 moving lights) → searchlight
//               shadow map → mirrored street reflection (½ res, HDR, mips) →
//               4× MSAA HDR main pass (≈2.7M triangles: buildings with
//               instanced windows whose rooms are ray-traced, signs, traffic,
//               people, trains; light halos, steam, rain, splashes) → resolve →
//               volumetric fog march (½ res) → combine → 6-level bloom +
//               anamorphic streaks → motion blur, tonemap, grade.
//
//   Like Lake Valley, everything is drawn every frame (no culling, no LOD) and
//   all motion is on a fixed timeline, so every device renders the same work.
//   The knobs below set the load: tuned to ≈4× Lake Valley's frame cost.
// ─────────────────────────────────────────────────────────────────────────────

internal class NeonCityRenderer(
    private val asset: NeonCityAsset,
    private val width: Int,
    private val height: Int
) : GpuScene {

    companion object {
        private const val MSAA = 4
        private const val MAX_LIGHTS = NeonLightGrid.MAX_PER_CELL   // a surface shades its cell's whole list
        private const val MIRROR_MOVING = 4        // … in the reflection: the strongest moving lights
        private const val MIRROR_STATIC = 8        //   and, separately, the strongest static ones
        private const val REFL_TAPS = 8            // vertical smear of street reflections
        private const val FOG_STEPS = 24
        private const val FOG_LIGHTS = 6
        private const val RAIN = 36000
        private const val SPLASHES = 3000
        private const val STEAM_PUFFS = 24
        private const val SEARCH_SHADOW = 1024
        private const val BLOOM_LEVELS = 6
        private const val MOTION_SAMPLES = 8
        private const val MAX_DYNAMIC_LIGHTS = 400
        private const val EXPOSURE = 1.0f
        private const val SEARCH_COS = 0.982f      // searchlight cone, cos of the half-angle
        private const val INSTANCE_BYTES = NeonCitySim.INSTANCE_FLOATS * 4

        private const val UNIT_LIGHTS = 3
        private const val UNIT_CELLS = 4
        private const val UNIT_INDICES = 5
        private const val UNIT_SEARCH = 6
        private const val UNIT_REFLECTION = 7
        private const val UNIT_DEPTH = 8
    }

    private class GpuMesh(val vbo: Int, val ibo: Int, val indexCount: Int, val indexType: Int)

    private class Batch(
        val vao: Int, val mesh: GpuMesh, var instances: Int,
        val actor: Int, val instanced: Boolean, val seed: Float, val shadow: Boolean,
        val sim: NeonCitySim.ActorBatch? = null, val vbo: Int = 0, val upload: FloatBuffer? = null
    )

    private val sim = NeonCitySim(asset)
    private val grid = NeonLightGrid(asset.table("lights"), MAX_DYNAMIC_LIGHTS)

    // programs
    private lateinit var objMain: Program
    private lateinit var objMirror: Program
    private lateinit var depthOnly: Program
    private lateinit var sky: Program
    private lateinit var skyMirror: Program
    private lateinit var rain: Program
    private lateinit var splash: Program
    private lateinit var glow: Program
    private lateinit var steam: Program
    private lateinit var fog: Program
    private lateinit var combine: Program
    private lateinit var downFirst: Program
    private lateinit var down: Program
    private lateinit var up: Program
    private lateinit var streak: Program
    private lateinit var final: Program
    private val scenePrograms = ArrayList<Program>()

    // geometry
    private val batches = ArrayList<Batch>()
    private var emptyVao = 0
    private val steamOrigins = ArrayList<FloatArray>()

    // light grid textures
    private var lightsTex = 0; private var cellsTex = 0; private var indexTex = 0; private var indexTexRows = 0
    private lateinit var lightsBuf: FloatBuffer
    private lateinit var cellsBuf: IntBuffer
    private var indexBuf: ShortBuffer = Gl.direct(2).asShortBuffer()

    // render targets
    private var hdrFloat = false
    private var hdrInternal = 0; private var hdrType = 0
    private var hdrWrite = 1f; private var hdrRead = 1f
    private var searchTex = 0; private var searchFbo = 0
    private var reflTex = 0; private var reflFbo = 0
    private var mainFbo = 0; private var msaa = false
    private var sceneTex = 0; private var depthTex = 0; private var sceneFbo = 0
    private var fogTex = 0; private var fogFbo = 0
    private var hdrTex = 0; private var hdrFbo = 0
    private val bloomTex = IntArray(BLOOM_LEVELS); private val bloomFbo = IntArray(BLOOM_LEVELS)
    private val streakTex = IntArray(2); private val streakFbo = IntArray(2)
    private var finalTex = 0
    override var outputFbo = 0; private set
    override var trianglesPerFrame = 0L; private set

    // per-frame state
    private var time = 0f
    private var frame = 0
    private var boundVao = -1
    private val camPos = FloatArray(3)
    private val mirrorCamPos = FloatArray(3)
    private val camFwd = FloatArray(3)
    private val camRight = FloatArray(3)
    private val camUp = FloatArray(3)
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val prevViewProj = FloatArray(16)
    private val invViewProj = FloatArray(16)
    private val mirrorViewProj = FloatArray(16)
    private val searchViewProj = FloatArray(16)
    private val searchMat = FloatArray(16)
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)

    // ── setup ───────────────────────────────────────────────────────────────

    override fun init() {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        hdrFloat = ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")
        if (hdrFloat) {
            hdrInternal = GLES30.GL_RGBA16F; hdrType = GLES30.GL_HALF_FLOAT
        } else {
            // no half-float render targets: 10-bit fixed point holding light / 16
            hdrInternal = GLES30.GL_RGB10_A2; hdrType = GLES30.GL_UNSIGNED_INT_2_10_10_10_REV
            hdrWrite = 1f / 16f; hdrRead = 16f
        }
        buildPrograms()
        buildGeometry()
        buildLightTextures()
        buildTargets()
        Gl.check("neon city init")
    }

    private fun buildPrograms() {
        fun vsfs(name: String, vs: String, fs: String, vsLights: Boolean, fsLights: Boolean, vararg defs: String): Program {
            val d = defs.toList()
            val v = if (vsLights) Gl.glsl(d, S.COMMON, S.LIGHTS, vs) else Gl.glsl(d, S.COMMON, vs)
            val f = if (fsLights) Gl.glsl(d, S.COMMON, S.LIGHTS, fs) else Gl.glsl(d, S.COMMON, fs)
            return Program(name, v, f).also { scenePrograms += it }
        }
        objMain = vsfs("nc object", S.OBJECT_VS, S.OBJECT_FS, false, true)
        objMirror = vsfs("nc object/mirror", S.OBJECT_VS, S.OBJECT_FS, false, true, "MIRROR")
        depthOnly = vsfs("nc depth", S.OBJECT_VS, S.DEPTH_FS, false, false, "DEPTH")
        sky = vsfs("nc sky", S.FULLSCREEN_VS, S.SKY_FS, false, false)
        skyMirror = vsfs("nc sky/mirror", S.FULLSCREEN_VS, S.SKY_FS, false, false, "MIRROR")
        rain = vsfs("nc rain", S.RAIN_VS, S.RAIN_FS, true, false)
        splash = vsfs("nc splash", S.SPLASH_VS, S.SPLASH_FS, true, false)
        glow = vsfs("nc glow", S.GLOW_VS, S.GLOW_FS, false, false)
        steam = vsfs("nc steam", S.STEAM_VS, S.STEAM_FS, true, false)
        fog = vsfs("nc fog", S.POST_VS, S.FOG_FS, false, true)
        combine = vsfs("nc combine", S.POST_VS, S.COMBINE_FS, false, false)
        downFirst = vsfs("nc bloom/threshold", S.POST_VS, S.DOWN_FS, false, false, "THRESHOLD")
        down = vsfs("nc bloom/down", S.POST_VS, S.DOWN_FS, false, false)
        up = vsfs("nc bloom/up", S.POST_VS, S.UP_FS, false, false)
        streak = vsfs("nc streak", S.POST_VS, S.STREAK_FS, false, false)
        final = vsfs("nc final", S.POST_VS, S.FINAL_FS, false, false)
        for (p in scenePrograms) {
            p.use()
            p.i("uLights", UNIT_LIGHTS); p.i("uCells", UNIT_CELLS); p.i("uIndices", UNIT_INDICES)
            p.i("uSearchShadow", UNIT_SEARCH); p.i("uReflection", UNIT_REFLECTION); p.i("uDepth", UNIT_DEPTH)
            p.f("uHdrWrite", hdrWrite); p.f("uHdrRead", hdrRead)
        }
    }

    private fun upload(m: NeonCityAsset.Mesh): GpuMesh {
        val vbo = Gl.buffer(GLES30.GL_ARRAY_BUFFER, Gl.direct(m.vertices))
        val ibo = Gl.buffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, Gl.direct(m.indices))
        return GpuMesh(vbo, ibo, m.indexCount, if (m.wideIndices) GLES30.GL_UNSIGNED_INT else GLES30.GL_UNSIGNED_SHORT)
    }

    private fun vao(mesh: GpuMesh, instanceVbo: Int): Int {
        val id = Gl.vertexArray()
        GLES30.glBindVertexArray(id)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, NeonCityAsset.VERTEX_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_BYTE, true, NeonCityAsset.VERTEX_BYTES, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, NeonCityAsset.VERTEX_BYTES, 16)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        for (r in 0 until 5) {
            GLES30.glEnableVertexAttribArray(3 + r)
            GLES30.glVertexAttribPointer(3 + r, 4, GLES30.GL_FLOAT, false, INSTANCE_BYTES, r * 16)
            GLES30.glVertexAttribDivisor(3 + r, 1)
        }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo)
        GLES30.glBindVertexArray(0)
        return id
    }

    /** {pos, euler XYZ, scale} → instance records (Blender XYZ euler: R = Rz·Ry·Rx), extras zero. */
    private fun instanceRows(t: FloatArray, count: Int): FloatArray {
        val stride = NeonCitySim.INSTANCE_FLOATS
        val out = FloatArray(count * stride)
        for (i in 0 until count) {
            val o = i * 9
            val cx = cos(t[o + 3]); val sx = sin(t[o + 3])
            val cy = cos(t[o + 4]); val sy = sin(t[o + 4])
            val cz = cos(t[o + 5]); val sz = sin(t[o + 5])
            val kx = t[o + 6]; val ky = t[o + 7]; val kz = t[o + 8]
            val b = i * stride
            out[b] = cz * cy * kx; out[b + 1] = (cz * sy * sx - sz * cx) * ky; out[b + 2] = (cz * sy * cx + sz * sx) * kz; out[b + 3] = t[o]
            out[b + 4] = sz * cy * kx; out[b + 5] = (sz * sy * sx + cz * cx) * ky; out[b + 6] = (sz * sy * cx - cz * sx) * kz; out[b + 7] = t[o + 1]
            out[b + 8] = -sy * kx; out[b + 9] = cy * sx * ky; out[b + 10] = cy * cx * kz; out[b + 11] = t[o + 2]
        }
        return out
    }

    private fun buildGeometry() {
        val meshes = asset.meshes.map { upload(it) }
        val identity = FloatArray(NeonCitySim.INSTANCE_FLOATS).also { it[0] = 1f; it[5] = 1f; it[10] = 1f }
        val identityVbo = Gl.buffer(GLES30.GL_ARRAY_BUFFER, Gl.direct(identity))

        for (i in asset.chunkMeshes()) batches += Batch(vao(meshes[i], identityVbo), meshes[i], 1, 0, false, 0f, true)
        // small street props would only speckle the searchlight's shadow map
        val noShadow = setOf("bollard", "bin", "hydrant", "bench", "lantern", "ac_unit")
        for ((gi, g) in asset.groups.withIndex()) {
            val vbo = Gl.buffer(GLES30.GL_ARRAY_BUFFER, Gl.direct(instanceRows(g.transforms, g.count)))
            val far = g.name.startsWith("tower_")
            batches += Batch(vao(meshes[g.mesh], vbo), meshes[g.mesh], g.count, 0, true, gi * 7.31f,
                !far && g.name !in noShadow)
        }
        val actors = sim.carBatches.map { it to 1 } + sim.pedBatches.map { it to 2 } +
            listOf(sim.trainHeads to 3, sim.trainCarsBatch to 3, sim.droneBatch to 3, sim.policeBatch to 3)
        for ((ab, kind) in actors) {
            val capacity = ab.data.size / NeonCitySim.INSTANCE_FLOATS
            if (capacity == 0) continue
            val buf = Gl.direct(ab.data.size * 4).asFloatBuffer()
            val vbo = Gl.buffer(GLES30.GL_ARRAY_BUFFER, Gl.direct(ab.data.size * 4), GLES30.GL_DYNAMIC_DRAW)
            val mi = asset.meshIndex(ab.mesh)
            batches += Batch(vao(meshes[mi], vbo), meshes[mi], 0, kind, true, 0f, kind != 2, ab, vbo, buf)
        }
        emptyVao = Gl.vertexArray()
        val steamTable = asset.table("steam")
        for (r in 0 until steamTable.rows) steamOrigins += steamTable.row(r)

        val main = batches.sumOf { it.mesh.indexCount / 3L * max(it.instances, it.sim?.data?.size?.div(NeonCitySim.INSTANCE_FLOATS) ?: 0) }
        val shadow = batches.filter { it.shadow }.sumOf { it.mesh.indexCount / 3L * max(it.instances, 1) }
        trianglesPerFrame = main * 2 + shadow + 2L * (RAIN + SPLASHES)
    }

    private fun intTexture(internal: Int, format: Int, type: Int, w: Int, h: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun buildLightTextures() {
        lightsTex = intTexture(GLES30.GL_RGBA32F, GLES30.GL_RGBA, GLES30.GL_FLOAT,
            NeonLightGrid.LIGHTS_PER_ROW * NeonLightGrid.LIGHT_TEXELS, grid.lightRows)
        lightsBuf = Gl.direct(grid.lights.size * 4).asFloatBuffer()
        cellsTex = intTexture(GLES30.GL_R32UI, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_INT,
            NeonLightGrid.CELLS_X, NeonLightGrid.CELLS_Y)
        cellsBuf = Gl.direct(grid.cells.size * 4).asIntBuffer()
    }

    private fun hdrTexture(w: Int, h: Int, mipmaps: Boolean = false): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        val levels = if (mipmaps) 1 + (31 - Integer.numberOfLeadingZeros(max(w, h))) else 1
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, hdrInternal, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            if (mipmaps) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun depthTexture(w: Int, h: Int): Int =
        intTexture(GLES30.GL_DEPTH_COMPONENT24, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, w, h)

    private fun buildTargets() {
        searchTex = Gl.shadowTexture(SEARCH_SHADOW)
        searchFbo = Gl.framebuffer(depthTex = searchTex)

        val rw = width / 2; val rh = height / 2
        reflTex = hdrTexture(rw, rh, mipmaps = true)
        reflFbo = Gl.framebuffer(colorTex = reflTex,
            depthRb = Gl.renderbuffer(0, GLES30.GL_DEPTH_COMPONENT24, rw, rh))

        sceneTex = hdrTexture(width, height)
        depthTex = depthTexture(width, height)
        sceneFbo = Gl.framebuffer(colorTex = sceneTex, depthTex = depthTex)
        val samples = supportedSamples(hdrInternal)
        msaa = samples > 1
        mainFbo = if (msaa) Gl.framebuffer(
            colorRb = Gl.renderbuffer(samples, hdrInternal, width, height),
            depthRb = Gl.renderbuffer(samples, GLES30.GL_DEPTH_COMPONENT24, width, height)) else sceneFbo

        // fog keeps its transmittance in alpha, which 2-bit RGB10_A2 alpha can't hold
        fogTex = if (hdrFloat) hdrTexture(width / 2, height / 2) else Gl.texture(width / 2, height / 2)
        fogFbo = Gl.framebuffer(colorTex = fogTex)
        hdrTex = hdrTexture(width, height)
        hdrFbo = Gl.framebuffer(colorTex = hdrTex)
        for (l in 0 until BLOOM_LEVELS) {
            bloomTex[l] = hdrTexture(max(1, width shr (l + 1)), max(1, height shr (l + 1)))
            bloomFbo[l] = Gl.framebuffer(colorTex = bloomTex[l])
        }
        for (i in 0 until 2) {
            streakTex[i] = hdrTexture(width / 4, height / 4)
            streakFbo[i] = Gl.framebuffer(colorTex = streakTex[i])
        }
        finalTex = Gl.texture(width, height)
        outputFbo = Gl.framebuffer(colorTex = finalTex)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun supportedSamples(format: Int): Int {
        val n = IntArray(1)
        GLES30.glGetInternalformativ(GLES30.GL_RENDERBUFFER, format, GLES30.GL_NUM_SAMPLE_COUNTS, 1, n, 0)
        if (n[0] <= 0) return 0
        val counts = IntArray(n[0])
        GLES30.glGetInternalformativ(GLES30.GL_RENDERBUFFER, format, GLES30.GL_SAMPLES, n[0], counts, 0)
        return counts.filter { it <= MSAA }.maxOrNull() ?: 0
    }

    // ── per frame ───────────────────────────────────────────────────────────

    override fun render(t: Float) {
        time = t
        boundVao = -1
        sim.update(t.toDouble())
        camera()
        lights()
        uploadActors()
        bindLightTextures()
        searchShadowPass()
        reflectionPass()
        mainPass()
        fogPass()
        postPass()
        System.arraycopy(viewProj, 0, prevViewProj, 0, 16)
        frame++
    }

    private fun camera() {
        val e = sim.eye; val g = sim.target
        camPos[0] = e[0]; camPos[1] = e[1]; camPos[2] = e[2]
        Matrix.setLookAtM(view, 0, e[0], e[1], e[2], g[0], g[1], g[2], 0f, 0f, 1f)
        Matrix.perspectiveM(proj, 0, sim.fovDeg, width.toFloat() / height, 0.25f, 4000f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        Matrix.invertM(invViewProj, 0, viewProj, 0)
        if (frame == 0) System.arraycopy(viewProj, 0, prevViewProj, 0, 16)
        camRight[0] = view[0]; camRight[1] = view[4]; camRight[2] = view[8]
        camUp[0] = view[1]; camUp[1] = view[5]; camUp[2] = view[9]
        camFwd[0] = -view[2]; camFwd[1] = -view[6]; camFwd[2] = -view[10]

        // the street's mirror image: z' = −z
        Matrix.setIdentityM(tmpA, 0)
        tmpA[10] = -1f
        Matrix.multiplyMM(tmpB, 0, view, 0, tmpA, 0)
        Matrix.multiplyMM(mirrorViewProj, 0, proj, 0, tmpB, 0)
        mirrorCamPos[0] = e[0]; mirrorCamPos[1] = e[1]; mirrorCamPos[2] = -e[2]

        // searchlight frustum (the cone plus a margin), biased into [0, 1] for shadow lookups
        val p = sim.searchPos; val a = sim.searchAim
        Matrix.setLookAtM(tmpA, 0, p[0], p[1], p[2], a[0], a[1], a[2], 0f, 1f, 0f)
        Matrix.perspectiveM(tmpB, 0, Math.toDegrees(2.0 * acos(SEARCH_COS.toDouble())).toFloat() + 6f, 1f, 2f, 140f)
        Matrix.multiplyMM(searchViewProj, 0, tmpB, 0, tmpA, 0)
        Matrix.setIdentityM(tmpA, 0)
        tmpA[0] = 0.5f; tmpA[5] = 0.5f; tmpA[10] = 0.5f; tmpA[12] = 0.5f; tmpA[13] = 0.5f; tmpA[14] = 0.5f
        Matrix.multiplyMM(searchMat, 0, tmpA, 0, searchViewProj, 0)
    }

    /** Moving lights for this frame, then the merged grid uploaded to its textures. */
    private fun lights() {
        grid.setMovingLights(sim, time, SEARCH_COS)

        lightsBuf.position(0); lightsBuf.put(grid.lights); lightsBuf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lightsTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, NeonLightGrid.LIGHTS_PER_ROW * NeonLightGrid.LIGHT_TEXELS,
            grid.lightRows, GLES30.GL_RGBA, GLES30.GL_FLOAT, lightsBuf)
        cellsBuf.position(0); cellsBuf.put(grid.cells); cellsBuf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cellsTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, NeonLightGrid.CELLS_X, NeonLightGrid.CELLS_Y,
            GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_INT, cellsBuf)
        if (grid.indexRows > indexTexRows) {
            if (indexTex != 0) GLES30.glDeleteTextures(1, intArrayOf(indexTex), 0)
            indexTexRows = grid.indexRows + 4
            indexTex = intTexture(GLES30.GL_R16UI, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT,
                NeonLightGrid.INDEX_W, indexTexRows)
            indexBuf = Gl.direct(indexTexRows * NeonLightGrid.INDEX_W * 2).asShortBuffer()
        }
        indexBuf.position(0); indexBuf.put(grid.indices, 0, grid.indexRows * NeonLightGrid.INDEX_W); indexBuf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, indexTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, NeonLightGrid.INDEX_W, grid.indexRows,
            GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, indexBuf)
    }

    private fun uploadActors() {
        for (b in batches) {
            val ab = b.sim ?: continue
            b.instances = ab.count
            if (ab.count == 0) continue
            val buf = b.upload!!
            buf.position(0); buf.put(ab.data, 0, ab.count * NeonCitySim.INSTANCE_FLOATS); buf.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, ab.count * INSTANCE_BYTES, buf)
        }
    }

    private fun bind(unit: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    }

    private fun bindLightTextures() {
        bind(UNIT_LIGHTS, lightsTex); bind(UNIT_CELLS, cellsTex); bind(UNIT_INDICES, indexTex)
    }

    private fun common(p: Program, cam: FloatArray = camPos) {
        p.use()
        p.f3("uCamPos", cam); p.f("uTime", time); p.fv("uGroups", sim.groups)
        p.m4("uSearchMat", searchMat)
    }

    private fun bindVao(id: Int) {
        if (id != boundVao) { GLES30.glBindVertexArray(id); boundVao = id }
    }

    private fun drawBatches(p: Program, vp: FloatArray, shadowOnly: Boolean) {
        p.m4("uViewProj", vp)
        for (b in batches) {
            if (b.instances == 0 || (shadowOnly && !b.shadow)) continue
            p.i("uActor", b.actor); p.i("uInstanced", if (b.instanced) 1 else 0); p.f("uSeed", b.seed)
            bindVao(b.vao)
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, b.mesh.indexCount, b.mesh.indexType, 0, b.instances)
        }
    }

    private fun searchShadowPass() {
        bind(UNIT_SEARCH, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, searchFbo)
        GLES30.glViewport(0, 0, SEARCH_SHADOW, SEARCH_SHADOW)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(2f, 4f)
        common(depthOnly)
        drawBatches(depthOnly, searchViewProj, true)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        bind(UNIT_SEARCH, searchTex)
    }

    private fun drawSky(p: Program, cam: FloatArray) {
        common(p, cam)
        p.m4("uInvViewProj", invViewProj)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        bindVao(emptyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthFunc(GLES30.GL_LESS)
    }

    private fun reflectionPass() {
        bind(UNIT_REFLECTION, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, reflFbo)
        GLES30.glViewport(0, 0, width / 2, height / 2)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        common(objMirror, mirrorCamPos)
        objMirror.i("uMaxMoving", MIRROR_MOVING); objMirror.i("uMaxStatic", MIRROR_STATIC)
        drawBatches(objMirror, mirrorViewProj, false)
        drawSky(skyMirror, mirrorCamPos)
        bind(UNIT_REFLECTION, reflTex)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    private fun mainPass() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mainFbo)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        common(objMain)
        objMain.i("uMaxMoving", MAX_LIGHTS); objMain.i("uMaxStatic", MAX_LIGHTS); objMain.i("uReflTaps", REFL_TAPS)
        objMain.f2("uInvViewport", 1f / width, 1f / height)
        drawBatches(objMain, viewProj, false)
        drawSky(sky, camPos)

        // translucent: premultiplied alpha (alpha 0 = additive), depth-tested, not written
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        bindVao(emptyVao)
        common(glow)
        glow.m4("uViewProj", viewProj); glow.f3("uCamRight", camRight); glow.f3("uCamUp", camUp)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, grid.lightCount)
        common(steam)
        steam.m4("uViewProj", viewProj); steam.f3("uCamRight", camRight); steam.f3("uCamUp", camUp)
        for (o in steamOrigins) {
            steam.f3("uOrigin", o)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, STEAM_PUFFS)
        }
        common(rain)
        rain.m4("uViewProj", viewProj); rain.f3("uCamFwd", camFwd)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, RAIN)
        common(splash)
        splash.m4("uViewProj", viewProj); splash.f3("uCamFwd", camFwd); splash.f3("uCamRight", camRight)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, SPLASHES)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)

        if (msaa) {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mainFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, sceneFbo)
            GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT, GLES30.GL_NEAREST)
        }
    }

    private fun fullscreen(fbo: Int, w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun fogPass() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        bindVao(emptyVao)
        bind(UNIT_DEPTH, depthTex)
        common(fog)
        fog.m4("uInvViewProj", invViewProj)
        fog.i("uFogSteps", FOG_STEPS); fog.i("uFogLights", FOG_LIGHTS); fog.f("uFrame", (frame % 64).toFloat())
        fullscreen(fogFbo, width / 2, height / 2)
    }

    private fun postPass() {
        combine.use()
        bind(0, sceneTex); bind(1, fogTex)
        combine.i("uScene", 0); combine.i("uFog", 1)
        fullscreen(hdrFbo, width, height)

        // bloom: thresholded 13-tap chain down, streaks off the ¼-res level, tent chain back up
        var src = hdrTex; var sw = width; var sh = height
        for (l in 0 until BLOOM_LEVELS) {
            val w = max(1, width shr (l + 1)); val h = max(1, height shr (l + 1))
            val p = if (l == 0) downFirst else down
            p.use(); p.i("uSrc", 0); p.f2("uTexel", 1f / sw, 1f / sh)
            bind(0, src)
            fullscreen(bloomFbo[l], w, h)
            src = bloomTex[l]; sw = w; sh = h
        }
        streak.use(); streak.i("uSrc", 0)
        var sSrc = bloomTex[1]
        for ((i, stride) in listOf(1f, 3f, 8f).withIndex()) {
            streak.f2("uStep", stride * 4f / width, 0f)
            bind(0, sSrc)
            fullscreen(streakFbo[i % 2], width / 4, height / 4)
            sSrc = streakTex[i % 2]
        }
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        up.use(); up.i("uSrc", 0); up.f("uWeight", 1f)
        for (l in BLOOM_LEVELS - 1 downTo 1) {
            val w = max(1, width shr l); val h = max(1, height shr l)
            up.f2("uTexel", 1f / max(1, width shr (l + 1)), 1f / max(1, height shr (l + 1)))
            bind(0, bloomTex[l])
            fullscreen(bloomFbo[l - 1], w, h)
        }
        GLES30.glDisable(GLES30.GL_BLEND)

        common(final)
        bind(0, hdrTex); bind(1, bloomTex[0]); bind(2, sSrc)
        final.i("uHdr", 0); final.i("uBloom", 1); final.i("uStreak", 2)
        final.m4("uInvViewProj", invViewProj); final.m4("uPrevViewProj", prevViewProj)
        final.i("uBlurSamples", MOTION_SAMPLES); final.f("uExposure", EXPOSURE)
        fullscreen(outputFbo, width, height)
        for (u in intArrayOf(0, 1, 2, UNIT_DEPTH, UNIT_REFLECTION)) bind(u, 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }
}
