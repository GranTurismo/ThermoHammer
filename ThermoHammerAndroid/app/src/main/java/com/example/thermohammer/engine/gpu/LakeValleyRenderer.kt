package com.example.thermohammer.engine.gpu

import android.opengl.GLES30
import android.opengl.Matrix
import com.example.thermohammer.engine.gpu.LakeValleyShaders as S
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// ─────────────────────────────────────────────────────────────────────────────
// LAKE VALLEY — the GPU benchmark scene (Blender-authored, see BenchmarkScene/).
//
//   Per frame:  sun shadow map 2048² → planar water reflection (½ res) →
//               4× MSAA main pass (terrain, ~14k instanced trees/rocks/reeds,
//               props, water, sky + clouds, birds, smoke, pollen) → resolve →
//               3-level bloom → composite.
//
//   Everything is drawn every frame — no culling, no LOD switching — and the
//   camera circles the lake looking outward, so each frame shows the same mix
//   of water, forest, mountains and sky. The workload therefore stays flat
//   while the scene moves, which the throttling measurement depends on.
// ─────────────────────────────────────────────────────────────────────────────

internal class LakeValleyRenderer(
    private val asset: GpuSceneAsset,
    private val width: Int,
    private val height: Int
) : GpuScene {
    companion object {
        const val LOOP_S = 50f                 // camera loop — one lap of the lake
        private const val SHADOW_SIZE = 2048
        private const val SHADOW_EXTENT = 620f // half-width of the sun's ortho box (m)
        private const val EXPOSURE = 1.05f
        private const val BIRDS = 80
        private const val SMOKE = 48
        private const val POLLEN = 600
        private const val WATER_GRID_X = 160
        private const val WATER_GRID_Y = 100
    }

    private class GpuMesh(val vbo: Int, val ibo: Int, val indexCount: Int, val indexType: Int, val height: Float)

    private class Batch(
        val vao: Int, val mesh: GpuMesh, val instances: Int,
        val sway: Float = 0f, val flutter: Float = 0f, val tint: Float = 0f,
        val seed: Float = 0f, val cull: Boolean = false
    )

    // programs
    private lateinit var objMain: Program
    private lateinit var objMirror: Program
    private lateinit var terrainMain: Program
    private lateinit var terrainMirror: Program
    private lateinit var depthOnly: Program
    private lateinit var water: Program
    private lateinit var sky: Program
    private lateinit var skyMirror: Program
    private lateinit var birds: Program
    private lateinit var smoke: Program
    private lateinit var pollen: Program
    private lateinit var bright: Program
    private lateinit var down: Program
    private lateinit var blur: Program
    private lateinit var composite: Program

    // draw lists
    private lateinit var terrain: Batch
    private val mainBatches = ArrayList<Batch>()
    private val shadowBatches = ArrayList<Batch>()
    private val mirrorBatches = ArrayList<Batch>()
    private var boatInstanceVbo = 0
    private var waterVao = 0
    private var waterIndexCount = 0
    private var birdVao = 0
    private var emptyVao = 0

    // render targets
    private var shadowTex = 0; private var shadowFbo = 0
    private var reflTex = 0; private var reflFbo = 0
    private var mainFbo = 0; private var msaa = false
    private var sceneTex = 0; private var sceneFbo = 0
    private val bloomTex = IntArray(3); private val bloomFbo = IntArray(3)
    private val tmpTex = IntArray(3); private val tmpFbo = IntArray(3)
    private var finalTex = 0
    override var outputFbo = 0; private set

    // per-frame state
    private var time = 0f
    private val camPos = FloatArray(3)
    private val sunDir = FloatArray(3)
    private val sunColor = floatArrayOf(1.0f * 3.1f, 0.83f * 3.1f, 0.64f * 3.1f)
    private var boundVao = -1
    private val wind = FloatArray(4)
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val invViewProj = FloatArray(16)
    private val mirrorViewProj = FloatArray(16)
    private val lightViewProj = FloatArray(16)
    private val shadowMat = FloatArray(16)
    private val camRight = FloatArray(3)
    private val camUp = FloatArray(3)
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)
    private val boatRows = FloatArray(12)
    private val boatBuf = Gl.direct(12 * 4)
    private val chimney = asset.anchors["chimney"] ?: floatArrayOf(asset.lakeCenterX, asset.lakeCenterY + 80f, 8f)
    private val boatHome = asset.anchors["boat"] ?: floatArrayOf(asset.lakeCenterX, asset.lakeCenterY, asset.waterZ)

    override var trianglesPerFrame = 0L; private set

    // ── setup ───────────────────────────────────────────────────────────────

    override fun init() {
        buildPrograms()
        buildGeometry()
        buildTargets()
        Gl.check("scene init")
    }

    private fun buildPrograms() {
        fun scene(name: String, vs: String, fs: String, vararg defs: String) =
            Program(name, Gl.glsl(defs.toList(), vs), Gl.glsl(defs.toList(), S.COMMON, S.SHADOW, fs))

        objMain = scene("object", S.OBJECT_VS, S.OBJECT_FS)
        objMirror = scene("object/mirror", S.OBJECT_VS, S.OBJECT_FS, "MIRROR")
        terrainMain = scene("terrain", S.OBJECT_VS, S.OBJECT_FS, "TERRAIN")
        terrainMirror = scene("terrain/mirror", S.OBJECT_VS, S.OBJECT_FS, "TERRAIN", "MIRROR")
        depthOnly = Program("depth", Gl.glsl(emptyList(), S.OBJECT_VS), Gl.glsl(emptyList(), S.DEPTH_FS))
        water = scene("water", S.WATER_VS, S.WATER_FS)
        sky = scene("sky", S.FULLSCREEN_VS, S.SKY_FS)
        skyMirror = scene("sky/mirror", S.FULLSCREEN_VS, S.SKY_FS, "MIRROR")
        birds = scene("birds", S.BIRD_VS, S.BIRD_FS)
        smoke = scene("smoke", S.PARTICLE_VS, S.PARTICLE_FS, "SMOKE")
        pollen = scene("pollen", S.PARTICLE_VS, S.PARTICLE_FS)
        bright = Program("bright", Gl.glsl(emptyList(), S.POST_VS), Gl.glsl(listOf("THRESHOLD"), S.DOWN_FS))
        down = Program("down", Gl.glsl(emptyList(), S.POST_VS), Gl.glsl(emptyList(), S.DOWN_FS))
        blur = Program("blur", Gl.glsl(emptyList(), S.POST_VS), Gl.glsl(emptyList(), S.BLUR_FS))
        composite = Program("composite", Gl.glsl(emptyList(), S.POST_VS), Gl.glsl(emptyList(), S.COMPOSITE_FS))
    }

    private fun upload(m: GpuSceneAsset.Mesh): GpuMesh {
        val vbo = Gl.buffer(GLES30.GL_ARRAY_BUFFER, Gl.direct(m.vertices))
        return if (m.isPolar) {
            val idx = polarIndices(m.polarSegments, m.polarRings)
            GpuMesh(vbo, Gl.buffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, Gl.direct(idx)), idx.size,
                GLES30.GL_UNSIGNED_INT, m.height)
        } else {
            GpuMesh(vbo, Gl.buffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, Gl.direct(m.indices!!)), m.indexCount,
                GLES30.GL_UNSIGNED_SHORT, m.height)
        }
    }

    /** Centre vertex + rings × segments, matching the exporter's vertex order. */
    private fun polarIndices(n: Int, rings: Int): IntArray {
        val out = IntArray(3 * n + 6 * n * (rings - 1))
        var k = 0
        for (j in 0 until n) { out[k++] = 0; out[k++] = 1 + j; out[k++] = 1 + (j + 1) % n }
        for (r in 0 until rings - 1) {
            val a = 1 + r * n
            val b = a + n
            for (j in 0 until n) {
                val j1 = (j + 1) % n
                out[k++] = a + j; out[k++] = b + j; out[k++] = b + j1
                out[k++] = a + j; out[k++] = b + j1; out[k++] = a + j1
            }
        }
        return out
    }

    private fun vao(mesh: GpuMesh, instanceVbo: Int): Int {
        val id = Gl.vertexArray()
        GLES30.glBindVertexArray(id)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, GpuSceneAsset.VERTEX_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_BYTE, true, GpuSceneAsset.VERTEX_BYTES, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, GpuSceneAsset.VERTEX_BYTES, 16)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        for (r in 0 until 3) {
            GLES30.glEnableVertexAttribArray(3 + r)
            GLES30.glVertexAttribPointer(3 + r, 4, GLES30.GL_FLOAT, false, 48, r * 16)
            GLES30.glVertexAttribDivisor(3 + r, 1)
        }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo)
        GLES30.glBindVertexArray(0)
        return id
    }

    /** {pos, euler XYZ, scale} → transform rows (Blender XYZ euler: R = Rz·Ry·Rx). */
    private fun instanceRows(t: FloatArray, count: Int): FloatArray {
        val out = FloatArray(count * 12)
        for (i in 0 until count) {
            val o = i * 9
            val cx = cos(t[o + 3]); val sx = sin(t[o + 3])
            val cy = cos(t[o + 4]); val sy = sin(t[o + 4])
            val cz = cos(t[o + 5]); val sz = sin(t[o + 5])
            val kx = t[o + 6]; val ky = t[o + 7]; val kz = t[o + 8]
            val b = i * 12
            out[b] = cz * cy * kx; out[b + 1] = (cz * sy * sx - sz * cx) * ky; out[b + 2] = (cz * sy * cx + sz * sx) * kz; out[b + 3] = t[o]
            out[b + 4] = sz * cy * kx; out[b + 5] = (sz * sy * sx + cz * cx) * ky; out[b + 6] = (sz * sy * cx - cz * sx) * kz; out[b + 7] = t[o + 1]
            out[b + 8] = -sy * kx; out[b + 9] = cy * sx * ky; out[b + 10] = cy * cx * kz; out[b + 11] = t[o + 2]
        }
        return out
    }

    private fun buildGeometry() {
        val meshes = asset.meshes.map { upload(it) }
        fun mesh(name: String) = meshes[asset.meshIndex(name)]
        val identity = Gl.buffer(GLES30.GL_ARRAY_BUFFER,
            Gl.direct(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)))

        terrain = Batch(vao(mesh("terrain"), identity), mesh("terrain"), 1, cull = true)

        // instanced groups: sway (m at the top) · flutter · tint · casts shadow · culls back faces
        data class Look(val sway: Float, val flutter: Float, val tint: Float, val casts: Boolean, val cull: Boolean)
        val looks = mapOf(
            "pine_a" to Look(0.45f, 0.06f, 0.18f, true, false),
            "pine_b" to Look(0.45f, 0.06f, 0.18f, true, false),
            "broadleaf" to Look(0.55f, 0.10f, 0.25f, true, false),
            "pine_far" to Look(0.30f, 0.0f, 0.15f, false, false),
            "reeds" to Look(0.25f, 0.03f, 0.20f, false, false)
        )
        val farMesh = mesh("pine_far")
        val trees = setOf("pine_a", "pine_b", "broadleaf", "pine_far")
        mainBatches += terrain; shadowBatches += terrain; mirrorBatches += terrain
        for ((gi, g) in asset.groups.withIndex()) {
            val look = looks[g.name] ?: Look(0f, 0f, 0.12f, true, true)            // rocks
            val inst = Gl.buffer(GLES30.GL_ARRAY_BUFFER, Gl.direct(instanceRows(g.transforms, g.count)))
            val m = meshes[g.mesh]
            val batch = Batch(vao(m, inst), m, g.count, look.sway, look.flutter, look.tint, gi * 17.3f, look.cull)
            mainBatches += batch
            if (look.casts) shadowBatches += batch
            // the water only needs silhouettes: every tree is reflected with the distant-pine mesh
            if (g.name in trees) mirrorBatches += Batch(vao(farMesh, inst), farMesh, g.count,
                look.sway, 0f, look.tint, gi * 17.3f)
        }
        for (name in listOf("cabin", "dock")) {
            val b = Batch(vao(mesh(name), identity), mesh(name), 1)
            mainBatches += b; shadowBatches += b; mirrorBatches += b
        }
        boatInstanceVbo = Gl.buffer(GLES30.GL_ARRAY_BUFFER, Gl.direct(12 * 4), GLES30.GL_DYNAMIC_DRAW)
        val boat = Batch(vao(mesh("boat"), boatInstanceVbo), mesh("boat"), 1)
        mainBatches += boat; shadowBatches += boat; mirrorBatches += boat

        buildWater()
        buildBirds()
        emptyVao = Gl.vertexArray()

        fun tris(list: List<Batch>) = list.sumOf { it.mesh.indexCount / 3L * it.instances }
        trianglesPerFrame = tris(mainBatches) + tris(shadowBatches) + tris(mirrorBatches) +
            2L * WATER_GRID_X * WATER_GRID_Y + BIRDS * 3L
    }

    private fun buildWater() {
        val x0 = asset.lakeCenterX - asset.lakeRadiusX * 1.3f
        val y0 = asset.lakeCenterY - asset.lakeRadiusY * 1.35f
        val dx = asset.lakeRadiusX * 2.6f / WATER_GRID_X
        val dy = asset.lakeRadiusY * 2.7f / WATER_GRID_Y
        val v = FloatArray((WATER_GRID_X + 1) * (WATER_GRID_Y + 1) * 2)
        var k = 0
        for (j in 0..WATER_GRID_Y) for (i in 0..WATER_GRID_X) { v[k++] = x0 + i * dx; v[k++] = y0 + j * dy }
        val idx = ShortArray(WATER_GRID_X * WATER_GRID_Y * 6)
        k = 0
        val row = WATER_GRID_X + 1
        for (j in 0 until WATER_GRID_Y) for (i in 0 until WATER_GRID_X) {
            val a = j * row + i
            idx[k++] = a.toShort(); idx[k++] = (a + 1).toShort(); idx[k++] = (a + row + 1).toShort()
            idx[k++] = a.toShort(); idx[k++] = (a + row + 1).toShort(); idx[k++] = (a + row).toShort()
        }
        waterIndexCount = idx.size
        waterVao = Gl.vertexArray()
        GLES30.glBindVertexArray(waterVao)
        Gl.buffer(GLES30.GL_ARRAY_BUFFER, Gl.direct(v))
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        Gl.buffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, Gl.direct(idx))
        GLES30.glBindVertexArray(0)
    }

    private fun buildBirds() {
        // body + two wings; w = how much the vertex flaps
        val v = floatArrayOf(
            0f, 0.45f, 0f, 0f, -0.07f, -0.35f, 0f, 0f, 0.07f, -0.35f, 0f, 0f,
            -0.04f, 0.15f, 0f, 0f, -0.04f, -0.12f, 0f, 0f, -0.95f, -0.05f, 0f, 1f,
            0.04f, 0.15f, 0f, 0f, 0.95f, -0.05f, 0f, 1f, 0.04f, -0.12f, 0f, 0f
        )
        birdVao = Gl.vertexArray()
        GLES30.glBindVertexArray(birdVao)
        Gl.buffer(GLES30.GL_ARRAY_BUFFER, Gl.direct(v))
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun buildTargets() {
        shadowTex = Gl.shadowTexture(SHADOW_SIZE)
        shadowFbo = Gl.framebuffer(depthTex = shadowTex)

        reflTex = Gl.texture(width / 2, height / 2)
        reflFbo = Gl.framebuffer(colorTex = reflTex,
            depthRb = Gl.renderbuffer(0, GLES30.GL_DEPTH_COMPONENT24, width / 2, height / 2))

        sceneTex = Gl.texture(width, height)
        val maxSamples = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, it, 0) }[0]
        val samples = min(4, maxSamples)
        msaa = samples > 1
        if (msaa) {
            mainFbo = Gl.framebuffer(
                colorRb = Gl.renderbuffer(samples, GLES30.GL_RGBA8, width, height),
                depthRb = Gl.renderbuffer(samples, GLES30.GL_DEPTH_COMPONENT24, width, height))
            sceneFbo = Gl.framebuffer(colorTex = sceneTex)
        } else {
            sceneFbo = Gl.framebuffer(colorTex = sceneTex,
                depthRb = Gl.renderbuffer(0, GLES30.GL_DEPTH_COMPONENT24, width, height))
            mainFbo = sceneFbo
        }
        for (l in 0 until 3) {
            val w = width shr (l + 1); val h = height shr (l + 1)
            bloomTex[l] = Gl.texture(w, h); bloomFbo[l] = Gl.framebuffer(colorTex = bloomTex[l])
            tmpTex[l] = Gl.texture(w, h); tmpFbo[l] = Gl.framebuffer(colorTex = tmpTex[l])
        }
        finalTex = Gl.texture(width, height)
        outputFbo = Gl.framebuffer(colorTex = finalTex)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    // ── per frame ───────────────────────────────────────────────────────────

    override fun render(t: Float) {
        time = t
        boundVao = -1
        animate(t)
        shadowPass()
        reflectionPass()
        mainPass()
        postPass()
    }

    private fun animate(t: Float) {
        val th = (t % LOOP_S) / LOOP_S * 2f * PI.toFloat()
        val cx = asset.lakeCenterX; val cy = asset.lakeCenterY; val wz = asset.waterZ

        // camera: a lap of the lake, skimming 1–12 m above the water, looking out at the shore
        val rx = asset.lakeRadiusX * 0.58f; val ry = asset.lakeRadiusY * 0.53f
        camPos[0] = cx + rx * cos(th); camPos[1] = cy + ry * sin(th)
        camPos[2] = wz + 6.5f + 5.0f * sin(2f * th + 0.7f) + 1.0f * sin(5f * th)
        var tx = -rx * sin(th); var ty = ry * cos(th)                      // tangent
        var nx = ry * cos(th); var ny = rx * sin(th)                       // outward normal
        val tl = sqrt(tx * tx + ty * ty); tx /= tl; ty /= tl
        val nl = sqrt(nx * nx + ny * ny); nx /= nl; ny /= nl
        val phi = Math.toRadians(38.0 + 18.0 * sin(th.toDouble())).toFloat()
        val dx = tx * cos(phi) + nx * sin(phi); val dy = ty * cos(phi) + ny * sin(phi)
        val pitch = Math.toRadians(3.0 + 3.0 * sin(3.0 * th)).toFloat()
        val roll = Math.toRadians(4.0 * sin(2.0 * th)).toFloat()
        // up = Z tilted toward the camera's right by the bank angle
        val ux = -dy * sin(roll); val uy = dx * sin(roll); val uz = cos(roll)
        Matrix.setLookAtM(view, 0, camPos[0], camPos[1], camPos[2],
            camPos[0] + dx * 300f, camPos[1] + dy * 300f, camPos[2] + 300f * tan(pitch), ux, uy, uz)
        Matrix.perspectiveM(proj, 0, 50f, width.toFloat() / height, 0.5f, 6000f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        Matrix.invertM(invViewProj, 0, viewProj, 0)
        camRight[0] = view[0]; camRight[1] = view[4]; camRight[2] = view[8]
        camUp[0] = view[1]; camUp[1] = view[5]; camUp[2] = view[9]

        // mirror the world in the water plane: z' = 2·waterZ − z
        Matrix.setIdentityM(tmpA, 0)
        tmpA[10] = -1f; tmpA[14] = 2f * wz
        Matrix.multiplyMM(tmpB, 0, view, 0, tmpA, 0)
        Matrix.multiplyMM(mirrorViewProj, 0, proj, 0, tmpB, 0)

        // sun: late afternoon, drifting a little over the lap so shadows move
        val el = Math.toRadians(24.0 + 5.0 * sin(th + 1.0)).toFloat()
        val az = Math.toRadians(-105.0 + 20.0 * sin(th.toDouble())).toFloat()
        sunDir[0] = sin(az) * cos(el); sunDir[1] = cos(az) * cos(el); sunDir[2] = sin(el)

        // wind: veering, gusty
        val wa = 0.6f + 0.3f * sin(th)
        wind[0] = cos(wa); wind[1] = sin(wa); wind[2] = 0.55f + 0.3f * sin(3f * th + 0.4f)

        // sun shadow: ortho box over the lake, refit as the sun moves
        val fz = wz + 30f
        Matrix.setLookAtM(tmpA, 0, cx + sunDir[0] * 1200f, cy + sunDir[1] * 1200f, fz + sunDir[2] * 1200f,
            cx, cy, fz, 0f, 0f, 1f)
        Matrix.orthoM(tmpB, 0, -SHADOW_EXTENT, SHADOW_EXTENT, -SHADOW_EXTENT, SHADOW_EXTENT, 200f, 2400f)
        Matrix.multiplyMM(lightViewProj, 0, tmpB, 0, tmpA, 0)
        Matrix.setIdentityM(tmpA, 0)
        tmpA[0] = 0.5f; tmpA[5] = 0.5f; tmpA[10] = 0.5f; tmpA[12] = 0.5f; tmpA[13] = 0.5f; tmpA[14] = 0.5f
        Matrix.multiplyMM(shadowMat, 0, tmpA, 0, lightViewProj, 0)

        // rowboat: drifts a slow circle off its mooring, bobbing and rolling on the waves
        val ba = th + 1.3f
        val bx = boatHome[0] + 16f * cos(ba); val by = boatHome[1] + 10f * sin(ba)
        val heading = kotlin.math.atan2(10f * cos(ba), -16f * sin(ba)) - PI.toFloat() / 2f
        val bob = 0.06f * sin(t * 1.3f)
        val rollB = 0.05f * sin(t * 0.9f); val pitchB = 0.035f * sin(t * 1.1f + 1f)
        val ch = cos(heading); val sh = sin(heading)
        val cr = cos(rollB); val sr = sin(rollB); val cp = cos(pitchB); val sp = sin(pitchB)
        // R = Rz(heading) · Rx(pitch) · Ry(roll)
        boatRows[0] = ch * cr - sh * sp * sr; boatRows[1] = -sh * cp; boatRows[2] = ch * sr + sh * sp * cr; boatRows[3] = bx
        boatRows[4] = sh * cr + ch * sp * sr; boatRows[5] = ch * cp; boatRows[6] = sh * sr - ch * sp * cr; boatRows[7] = by
        boatRows[8] = -cp * sr; boatRows[9] = sp; boatRows[10] = cp * cr; boatRows[11] = wz - 0.06f + bob
        boatBuf.asFloatBuffer().put(boatRows)
        boatBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, boatInstanceVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 48, boatBuf)
    }

    private fun common(p: Program) {
        p.f3("uSunDir", sunDir); p.f3("uSunColor", sunColor); p.f3("uCamPos", camPos)
        p.f("uTime", time); p.f4("uWind", wind); p.f("uExposure", EXPOSURE); p.f("uWaterZ", asset.waterZ)
    }

    private fun bindShadow(p: Program) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        p.i("uShadowMap", 1)
        p.m4("uShadowMat", shadowMat)
    }

    private fun draw(p: Program, b: Batch) {
        p.f("uMeshHeight", max(b.mesh.height, 0.1f)); p.f("uSway", b.sway); p.f("uFlutter", b.flutter)
        p.f("uTint", b.tint); p.f("uSeed", b.seed)
        if (b.cull) GLES30.glEnable(GLES30.GL_CULL_FACE) else GLES30.glDisable(GLES30.GL_CULL_FACE)
        bindVao(b.vao)
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, b.mesh.indexCount, b.mesh.indexType, 0, b.instances)
    }

    private fun bindVao(id: Int) {
        if (id != boundVao) { GLES30.glBindVertexArray(id); boundVao = id }
    }

    private fun drawScene(terrainProg: Program, objProg: Program, vp: FloatArray, list: List<Batch>) {
        for (p in arrayOf(terrainProg, objProg)) {
            p.use(); common(p); bindShadow(p); p.m4("uViewProj", vp)
            for (b in list) if ((b === terrain) == (p === terrainProg)) draw(p, b)
        }
    }

    private fun drawSky(p: Program) {
        p.use(); common(p); p.m4("uInvViewProj", invViewProj)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        bindVao(emptyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthFunc(GLES30.GL_LESS)
    }

    private fun shadowPass() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(2f, 4f)
        depthOnly.use()
        depthOnly.m4("uViewProj", lightViewProj)
        depthOnly.f("uTime", time); depthOnly.f4("uWind", wind)
        for (b in shadowBatches) draw(depthOnly, b)          // swaying trees cast swaying shadows
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
    }

    private fun reflectionPass() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, reflFbo)
        GLES30.glViewport(0, 0, width / 2, height / 2)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glFrontFace(GLES30.GL_CW)                     // mirroring flips the winding
        drawScene(terrainMirror, objMirror, mirrorViewProj, mirrorBatches)
        drawSky(skyMirror)
        GLES30.glFrontFace(GLES30.GL_CCW)
    }

    private fun mainPass() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mainFbo)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawScene(terrainMain, objMain, viewProj, mainBatches)

        water.use(); common(water); bindShadow(water)
        water.m4("uViewProj", viewProj)
        water.f2("uInvViewport", 1f / width, 1f / height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, reflTex)
        water.i("uReflection", 2)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        bindVao(waterVao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, waterIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)

        drawSky(sky)

        birds.use(); common(birds)
        birds.m4("uViewProj", viewProj)
        birds.f3("uLake", asset.lakeCenterX, asset.lakeCenterY, asset.waterZ)
        bindVao(birdVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 9, BIRDS)

        // translucent last: premultiplied alpha, depth-tested but not written
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        bindVao(emptyVao)
        for ((p, origin, count) in listOf(Triple(smoke, chimney, SMOKE), Triple(pollen, camPos, POLLEN))) {
            p.use(); common(p)
            p.m4("uViewProj", viewProj); p.f3("uOrigin", origin)
            p.f3("uCamRight", camRight); p.f3("uCamUp", camUp)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, count)
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        unbindTextures(3)

        if (msaa) {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mainFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, sceneFbo)
            GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        }
    }

    private fun fullscreen(p: Program, fbo: Int, w: Int, h: Int, src: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        p.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src)
        p.i("uSrc", 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun postPass() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        bindVao(emptyVao)
        var src = sceneTex; var sw = width; var shh = height
        for (l in 0 until 3) {
            val w = width shr (l + 1); val h = height shr (l + 1)
            val p = if (l == 0) bright else down
            p.use(); p.f2("uTexel", 0.5f / sw, 0.5f / shh)
            fullscreen(p, bloomFbo[l], w, h, src)
            blur.use(); blur.f2("uStep", 1f / w, 0f)
            fullscreen(blur, tmpFbo[l], w, h, bloomTex[l])
            blur.use(); blur.f2("uStep", 0f, 1f / h)
            fullscreen(blur, bloomFbo[l], w, h, tmpTex[l])
            src = bloomTex[l]; sw = w; shh = h
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glViewport(0, 0, width, height)
        composite.use()
        composite.f("uTime", time)
        val units = intArrayOf(sceneTex, bloomTex[0], bloomTex[1], bloomTex[2])
        val names = arrayOf("uScene", "uBloomA", "uBloomB", "uBloomC")
        for (u in units.indices) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, units[u])
            composite.i(names[u], u)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        unbindTextures(4)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** Leave no render target bound for sampling while the next pass draws into it. */
    private fun unbindTextures(units: Int) {
        for (u in units - 1 downTo 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
    }
}
