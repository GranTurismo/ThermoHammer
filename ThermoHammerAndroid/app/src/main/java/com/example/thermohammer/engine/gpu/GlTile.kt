package com.example.thermohammer.engine.gpu

import android.opengl.GLES30

/**
 * Anti-aliasing kept on the GPU's tiles (GL_EXT_multisampled_render_to_texture), the way mobile GPUs are meant to
 * draw it and the way the iOS scenes already do it (memoryless MSAA, resolved as each tile is written out).
 *
 * The classic way — 4-sample renderbuffers resolved by glBlitFramebuffer — writes the whole 4-sample image and its
 * depth to memory and reads them back for the resolve: a desktop GPU's habit, and expensive on tile-based GPUs
 * (Arm's Mali guide warns against exactly this). The picture is the same either way; only the traffic differs.
 *
 * All calls on the GL thread, with a context current.
 */
internal object GlTile {

    private val libLoaded: Boolean = runCatching { System.loadLibrary("thgltile") }.isSuccess

    /** Whether this context can draw on-tile MSAA: the extension is listed and its functions were found. */
    fun available(): Boolean {
        if (!libLoaded) return false
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: return false
        return ext.split(' ').any { it == "GL_EXT_multisampled_render_to_texture" } && GlTileNative.load()
    }

    /**
     * A framebuffer that draws `samples`-sample anti-aliasing on the tiles and writes the resolved picture straight
     * into `colorTex`. Depth is a `samples`-sample renderbuffer that never leaves the chip, so only a pass whose depth
     * nothing reads later can use it (an on-tile depth texture didn't give the classic resolve's depth on an Adreno).
     * Returns 0 — after deleting what it made — when the driver won't take this combination (incomplete framebuffer).
     */
    fun framebuffer(colorTex: Int, samples: Int, width: Int, height: Int): Int {
        Gl.check("before on-tile MSAA")                        // an earlier error is the scene's, not this attempt's
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GlTileNative.framebufferTexture2DMultisample(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, colorTex, 0, samples)
        GLES30.glGenRenderbuffers(1, ids, 0)
        val depthRb = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb)
        GlTileNative.renderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples,
            GLES30.GL_DEPTH_COMPONENT24, width, height)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER, depthRb)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        val error = GLES30.glGetError()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (complete && error == GLES30.GL_NO_ERROR) return fbo
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* leave no error behind for the scene's own checks */ }
        return 0
    }

    /** Tells the driver the bound draw framebuffer's `attachments` needn't be kept (after a pass) or loaded (before one). */
    fun discard(vararg attachments: Int) =
        GLES30.glInvalidateFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, attachments.size, attachments, 0)

    fun discardColor() = discard(GLES30.GL_COLOR_ATTACHMENT0)
    fun discardDepth() = discard(GLES30.GL_DEPTH_ATTACHMENT)
}

/** The extension's two functions (cpp/gl_tile_jni.cpp, libthgltile.so). */
internal object GlTileNative {
    @JvmStatic external fun load(): Boolean
    @JvmStatic external fun framebufferTexture2DMultisample(target: Int, attachment: Int, texTarget: Int, texture: Int,
                                                            level: Int, samples: Int)
    @JvmStatic external fun renderbufferStorageMultisample(target: Int, samples: Int, format: Int, width: Int, height: Int)
}
