package com.example.thermohammer.engine.gpu

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ─────────────────────────────────────────────────────────────────────────────
// GL KIT — the small amount of GLES 3.0 plumbing the benchmark scene needs.
//   All calls must run on the GL thread that owns the current context.
// ─────────────────────────────────────────────────────────────────────────────

class GlException(msg: String) : Exception(msg)

internal object Gl {

    private const val HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\n"

    /** GLSL ES 3.00 source: header, `#define`s, then the parts in order. */
    fun glsl(defines: List<String>, vararg parts: String): String =
        HEADER + defines.joinToString("") { "#define $it\n" } + parts.joinToString("\n")

    fun direct(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    fun direct(src: ByteBuffer): ByteBuffer =
        direct(src.remaining()).put(src.duplicate()).also { it.position(0) }

    fun direct(f: FloatArray): ByteBuffer =
        direct(f.size * 4).also { it.asFloatBuffer().put(f); it.position(0) }

    fun direct(i: IntArray): ByteBuffer =
        direct(i.size * 4).also { it.asIntBuffer().put(i); it.position(0) }

    fun direct(s: ShortArray): ByteBuffer =
        direct(s.size * 2).also { it.asShortBuffer().put(s); it.position(0) }

    fun buffer(target: Int, data: ByteBuffer, usage: Int = GLES30.GL_STATIC_DRAW): Int {
        val id = IntArray(1)
        GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(target, id[0])
        GLES30.glBufferData(target, data.capacity(), data, usage)
        return id[0]
    }

    fun vertexArray(): Int {
        val id = IntArray(1)
        GLES30.glGenVertexArrays(1, id, 0)
        return id[0]
    }

    fun texture(w: Int, h: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    /** Depth texture sampled with hardware comparison (sampler2DShadow, bilinear PCF). */
    fun shadowTexture(size: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0,
            GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE,
            GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        return id[0]
    }

    fun renderbuffer(samples: Int, format: Int, w: Int, h: Int): Int {
        val id = IntArray(1)
        GLES30.glGenRenderbuffers(1, id, 0)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, id[0])
        if (samples > 1) GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, format, w, h)
        else GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, format, w, h)
        return id[0]
    }

    /**
     * Framebuffer from any of: colour texture, colour renderbuffer, depth renderbuffer,
     * depth texture. Depth-only targets get no draw/read buffer.
     */
    fun framebuffer(colorTex: Int = 0, colorRb: Int = 0, depthRb: Int = 0, depthTex: Int = 0): Int {
        val id = IntArray(1)
        GLES30.glGenFramebuffers(1, id, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id[0])
        if (colorTex != 0) GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, colorTex, 0)
        if (colorRb != 0) GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_RENDERBUFFER, colorRb)
        if (depthRb != 0) GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER, depthRb)
        if (depthTex != 0) GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_TEXTURE_2D, depthTex, 0)
        if (colorTex == 0 && colorRb == 0) {
            GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
            GLES30.glReadBuffer(GLES30.GL_NONE)
        }
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw GlException("framebuffer incomplete 0x${Integer.toHexString(status)}")
        return id[0]
    }

    fun check(stage: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) throw GlException("$stage: GL error 0x${Integer.toHexString(e)}")
    }
}

/** Linked program with a uniform-location cache (missing uniforms resolve to −1, a GL no-op). */
internal class Program(name: String, vs: String, fs: String) {

    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(s)
                throw GlException("$name ${if (type == GLES30.GL_VERTEX_SHADER) "VS" else "FS"}: $log")
            }
            return s
        }
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, v)
        GLES30.glAttachShader(id, f)
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw GlException("$name link: ${GLES30.glGetProgramInfoLog(id)}")
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
    }

    fun use() = GLES30.glUseProgram(id)

    private fun loc(n: String) = locations.getOrPut(n) { GLES30.glGetUniformLocation(id, n) }

    fun f(n: String, x: Float) = GLES30.glUniform1f(loc(n), x)
    fun f2(n: String, x: Float, y: Float) = GLES30.glUniform2f(loc(n), x, y)
    fun f3(n: String, v: FloatArray) = GLES30.glUniform3f(loc(n), v[0], v[1], v[2])
    fun f3(n: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc(n), x, y, z)
    fun f4(n: String, v: FloatArray) = GLES30.glUniform4f(loc(n), v[0], v[1], v[2], v[3])
    fun fv(n: String, v: FloatArray) = GLES30.glUniform1fv(loc(n), v.size, v, 0)
    fun i(n: String, v: Int) = GLES30.glUniform1i(loc(n), v)
    fun m4(n: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(n), 1, false, m, 0)
}
