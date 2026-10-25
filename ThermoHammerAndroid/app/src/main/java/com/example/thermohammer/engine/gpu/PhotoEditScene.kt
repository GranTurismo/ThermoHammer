package com.example.thermohammer.engine.gpu

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// PHOTO EDITING — UX Test 4: a photo editor's filter chain on the GPU.
//
//   The 12 MP Lake Valley photo (assets/media/photo_lake.webp, 4000×3000) goes
//   through the whole chain every frame, at full resolution:
//     LENS     fisheye (barrel) distortion with lateral colour fringing,
//              Catmull-Rom bicubic resampling
//     BLUR     separable Gaussian, 129-texel kernel (2 × 65 bilinear taps)
//     BOKEH    portrait-mode background blur: a 48-tap disc over the blurred
//              copy that blooms highlights, faded in outside a moving focus band
//     FINISH   unsharp mask, 33³ 3D-LUT colour grade, vignette, film grain
//   Each stage's result is shown in its own panel of a 2×2 preview (the stage
//   labels them), strong enough to read at a fifth of the size. The settings
//   drift with time, so no frame can reuse the last one. One frame = one full
//   edit; the score is megapixels through the chain per second.
//   Textures stay in image orientation (v = 0 is the top row); only the preview
//   flips into the framebuffer's bottom-up rows.
// ─────────────────────────────────────────────────────────────────────────────

internal class PhotoEditScene(private val assets: AssetManager, private val outW: Int, private val outH: Int) : GpuScene {

    companion object {
        const val PHOTO = "media/photo_lake.webp"
        private const val LUT = 33
        private const val BLUR_RADIUS = 64         // texels each side
        private const val BLUR_TAPS = BLUR_RADIUS / 2 + 1   // centre + bilinear pairs
        private const val BOKEH_RADIUS = 60f       // px at full strength
        /** The preview panels in grid order: top-left, top-right, bottom-left, bottom-right. */
        val PANELS = listOf("FISHEYE LENS", "GAUSSIAN BLUR", "PORTRAIT BOKEH", "SHARPEN + COLOUR GRADE")
        const val PANEL_W = 704                    // each panel: the 4:3 photo inside a 960×540 quadrant
        const val PANEL_H = 528
    }

    /** Size of the photo actually edited (4000×3000 unless the GPU's texture limit is smaller). */
    @Volatile var photoW = 0; private set
    @Volatile var photoH = 0; private set
    val megapixels: Double get() = photoW.toDouble() * photoH / 1e6

    override var outputFbo = 0; private set
    override val trianglesPerFrame = 6L

    private var src = 0
    private val tex = IntArray(3)       // A, B, C
    private val fbo = IntArray(3)
    private var lut = 0
    private var vao = 0
    private lateinit var lens: Program
    private lateinit var blur: Program
    private lateinit var bokeh: Program
    private lateinit var finish: Program
    private lateinit var show: Program
    private val blurOff = FloatArray(BLUR_TAPS)
    private val blurW = FloatArray(BLUR_TAPS)

    override fun init() {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        var bmp = assets.open(PHOTO).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw GlException("can't decode $PHOTO")
        val maxTex = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        if (bmp.width > maxTex || bmp.height > maxTex) {
            val s = min(maxTex.toFloat() / bmp.width, maxTex.toFloat() / bmp.height)
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            bmp.recycle(); bmp = scaled
        }
        photoW = bmp.width; photoH = bmp.height
        src = Gl.texture(photoW, photoH)
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bmp)
        bmp.recycle()
        for (i in 0 until 3) {
            tex[i] = Gl.texture(photoW, photoH)
            fbo[i] = Gl.framebuffer(colorTex = tex[i])
        }
        val out = Gl.texture(outW, outH)
        outputFbo = Gl.framebuffer(colorTex = out)
        lut = gradeLut()
        vao = Gl.vertexArray()
        gaussian()

        lens = Program("photo lens", Gl.glsl(emptyList(), VS), Gl.glsl(emptyList(), LENS_FS))
        blur = Program("photo blur", Gl.glsl(listOf("TAPS $BLUR_TAPS"), VS), Gl.glsl(listOf("TAPS $BLUR_TAPS"), BLUR_FS))
        bokeh = Program("photo bokeh", Gl.glsl(emptyList(), VS), Gl.glsl(emptyList(), BOKEH_FS))
        finish = Program("photo finish", Gl.glsl(emptyList(), VS), Gl.glsl(emptyList(), FINISH_FS))
        show = Program("photo panel", Gl.glsl(emptyList(), VS), Gl.glsl(emptyList(), SHOW_FS))
        Gl.check("photo init")
    }

    override fun render(t: Float) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vao)
        val (A, B, C) = Triple(0, 1, 2)
        val texel = floatArrayOf(1f / photoW, 1f / photoH)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glClearColor(0.043f, 0.055f, 0.071f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // LENS: src → A
        lens.use(); bind(0, src); lens.i("uSrc", 0)
        lens.f2("uSize", photoW.toFloat(), photoH.toFloat())
        lens.f("uK", 0.75f + 0.2f * sin(t * 0.7f))
        pass(fbo[A], photoW, photoH)
        panel(0, tex[A])

        // BLUR: A → B (horizontal) → C (vertical)
        blur.use(); blur.i("uSrc", 0); blur.fv("uOff", blurOff); blur.fv("uW", blurW)
        bind(0, tex[A]); blur.f2("uDir", texel[0], 0f); pass(fbo[B], photoW, photoH)
        bind(0, tex[B]); blur.f2("uDir", 0f, texel[1]); pass(fbo[C], photoW, photoH)
        panel(1, tex[C])

        // BOKEH: A (+ its blur C) → B
        bokeh.use(); bind(0, tex[A]); bind(1, tex[C]); bokeh.i("uSharp", 0); bokeh.i("uSoft", 1)
        bokeh.f2("uTexel", texel[0], texel[1])
        bokeh.f("uRadius", BOKEH_RADIUS * photoW / 4000f)
        bokeh.f("uFocusY", 0.62f + 0.14f * sin(t * 0.45f))
        pass(fbo[B], photoW, photoH)
        panel(2, tex[B])

        // FINISH: B + C + LUT → A
        finish.use(); bind(0, tex[B]); bind(1, tex[C])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut)
        finish.i("uImg", 0); finish.i("uSoft", 1); finish.i("uLut", 2)
        finish.f("uAmount", 0.55f + 0.25f * sin(t * 1.1f))
        finish.f("uGrade", 0.8f + 0.2f * sin(t * 0.5f))
        finish.f("uTime", t)
        finish.f2("uSize", photoW.toFloat(), photoH.toFloat())
        pass(fbo[A], photoW, photoH)
        panel(3, tex[A])
        for (u in 0..2) bind(u, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /** Draws a stage's result, scaled down, into its quadrant of the 16:9 output (row 0 at the top). */
    private fun panel(index: Int, texture: Int) {
        val qw = outW / 2; val qh = outH / 2
        val x = (index % 2) * qw + (qw - PANEL_W * outW / 1920) / 2
        val y = (1 - index / 2) * qh + (qh - PANEL_H * outH / 1080) / 2
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glViewport(x, y, PANEL_W * outW / 1920, PANEL_H * outH / 1080)
        show.use(); bind(0, texture); show.i("uTex", 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun pass(target: Int, w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun bind(unit: Int, t: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
    }

    /** σ ≈ 21 texels over ±64, folded into 32 bilinear pairs a side (+ the centre). */
    private fun gaussian() {
        val sigma = BLUR_RADIUS / 3.0
        val g = DoubleArray(BLUR_RADIUS + 1) { exp(-it * it / (2 * sigma * sigma)) }
        val norm = g[0] + 2 * (1..BLUR_RADIUS).sumOf { g[it] }
        blurOff[0] = 0f; blurW[0] = (g[0] / norm).toFloat()
        for (k in 1 until BLUR_TAPS) {
            val a = 2 * k - 1; val b = 2 * k
            val w = g[a] + g[b]
            blurW[k] = (w / norm).toFloat()
            blurOff[k] = ((a * g[a] + b * g[b]) / w).toFloat()
        }
    }

    /** A filmic grade: gentle S-curve, teal shadows, warm highlights, a little more saturation. */
    private fun gradeLut(): Int {
        val data = Gl.direct(LUT * LUT * LUT * 4)
        for (b in 0 until LUT) for (g in 0 until LUT) for (r in 0 until LUT) {
            var cr = r / (LUT - 1.0); var cg = g / (LUT - 1.0); var cb = b / (LUT - 1.0)
            fun curve(x: Double) = x + 0.35 * (x * x * (3 - 2 * x) - x)
            cr = curve(cr); cg = curve(cg); cb = curve(cb)
            val l = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb
            val sh = (1 - l) * (1 - l); val hi = l * l
            cr += -0.03 * sh + 0.07 * hi; cg += 0.03 * sh + 0.025 * hi; cb += 0.06 * sh - 0.05 * hi
            val l2 = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb
            cr = l2 + (cr - l2) * 1.15; cg = l2 + (cg - l2) * 1.15; cb = l2 + (cb - l2) * 1.15
            for (v in doubleArrayOf(cr, cg, cb)) data.put((max(0.0, min(1.0, v)) * 255 + 0.5).toInt().toByte())
            data.put(255.toByte())
        }
        data.position(0)
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, id[0])
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, LUT, LUT, LUT, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, data)
        for (p in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_LINEAR)
        for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        return id[0]
    }
}

private const val VS = """
out vec2 vUV;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUV = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

/** Fisheye: magnifies the centre, squeezes the edges; red and blue land a little apart (lateral CA). */
private const val LENS_FS = """
uniform sampler2D uSrc;
uniform vec2 uSize;
uniform float uK;
in vec2 vUV;
out vec4 oColor;

vec3 bicubic(vec2 uv) {
    vec2 p = uv * uSize - 0.5;
    vec2 f = fract(p);
    ivec2 i = ivec2(floor(p));
    vec4 wx = vec4(f.x * (-0.5 + f.x * (1.0 - 0.5 * f.x)), 1.0 + f.x * f.x * (-2.5 + 1.5 * f.x),
                   f.x * (0.5 + f.x * (2.0 - 1.5 * f.x)), f.x * f.x * (-0.5 + 0.5 * f.x));
    vec4 wy = vec4(f.y * (-0.5 + f.y * (1.0 - 0.5 * f.y)), 1.0 + f.y * f.y * (-2.5 + 1.5 * f.y),
                   f.y * (0.5 + f.y * (2.0 - 1.5 * f.y)), f.y * f.y * (-0.5 + 0.5 * f.y));
    ivec2 hi = ivec2(uSize) - 1;
    vec3 c = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        vec3 row = vec3(0.0);
        for (int x = 0; x < 4; x++)
            row += texelFetch(uSrc, clamp(i + ivec2(x - 1, y - 1), ivec2(0), hi), 0).rgb * wx[x];
        c += row * wy[y];
    }
    return c;
}

void main() {
    float aspect = uSize.x / uSize.y;
    vec2 c = (vUV - 0.5) * vec2(aspect, 1.0);
    float r2 = dot(c, c) / (0.25 * (aspect * aspect + 1.0));     // 1 at the corners
    float zoom = 1.0 + uK * 0.8;                                 // the centre magnified, only the corners past the edge
    vec2 d = c * (1.0 + uK * r2) / zoom;
    vec2 uvG = d / vec2(aspect, 1.0) + 0.5;
    vec2 uvR = (d * 1.004) / vec2(aspect, 1.0) + 0.5;
    vec2 uvB = (d * 0.996) / vec2(aspect, 1.0) + 0.5;
    vec3 col = bicubic(uvG);
    col.r = texture(uSrc, uvR).r;
    col.b = texture(uSrc, uvB).b;
    float inside = step(0.0, uvG.x) * step(uvG.x, 1.0) * step(0.0, uvG.y) * step(uvG.y, 1.0);
    oColor = vec4(clamp(col, 0.0, 1.0) * inside, 1.0);
}
"""

private const val BLUR_FS = """
uniform sampler2D uSrc;
uniform vec2 uDir;
uniform float uOff[TAPS];
uniform float uW[TAPS];
in vec2 vUV;
out vec4 oColor;
void main() {
    vec3 s = texture(uSrc, vUV).rgb * uW[0];
    for (int i = 1; i < TAPS; i++) {
        vec2 d = uDir * uOff[i];
        s += (texture(uSrc, vUV + d).rgb + texture(uSrc, vUV - d).rgb) * uW[i];
    }
    oColor = vec4(s, 1.0);
}
"""

/** Portrait mode: sharp inside the focus band, a highlight-blooming disc blur (of the pre-blurred photo) outside it. */
private const val BOKEH_FS = """
uniform sampler2D uSharp;
uniform sampler2D uSoft;
uniform vec2 uTexel;
uniform float uRadius;
uniform float uFocusY;
in vec2 vUV;
out vec4 oColor;
void main() {
    vec3 sharp = texture(uSharp, vUV).rgb;
    float coc = smoothstep(0.05, 0.30, abs(vUV.y - uFocusY));
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 48; i++) {
        float r = sqrt((float(i) + 0.5) / 48.0);
        float a = float(i) * 2.39996323;
        vec3 c = texture(uSoft, vUV + vec2(cos(a), sin(a)) * (r * uRadius * coc) * uTexel).rgb;
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float w = 1.0 + 8.0 * l * l * l * l;
        acc += c * w;
        wsum += w;
    }
    oColor = vec4(mix(sharp, acc / wsum, coc), 1.0);
}
"""

private const val FINISH_FS = """
uniform sampler2D uImg;
uniform sampler2D uSoft;
uniform highp sampler3D uLut;
uniform float uAmount;
uniform float uGrade;
uniform float uTime;
uniform vec2 uSize;
in vec2 vUV;
out vec4 oColor;
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
void main() {
    vec3 c = texture(uImg, vUV).rgb;
    vec3 s = texture(uSoft, vUV).rgb;
    c = clamp(c + uAmount * (c - s), 0.0, 1.0);                       // unsharp mask
    vec3 graded = texture(uLut, c * (32.0 / 33.0) + 0.5 / 33.0).rgb;
    c = mix(c, graded, uGrade);
    vec2 v = vUV - 0.5;
    c *= 1.0 - dot(v, v) * 0.9;                                       // vignette
    c += (hash(vUV * uSize + uTime) - 0.5) * 0.03;                    // grain
    oColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

private const val SHOW_FS = """
uniform sampler2D uTex;
in vec2 vUV;
out vec4 oColor;
void main() { oColor = vec4(texture(uTex, vec2(vUV.x, 1.0 - vUV.y)).rgb, 1.0); }
"""
