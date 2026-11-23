package com.example.thermohammer.engine

import android.opengl.EGL14
import android.opengl.GLES30

/**
 * The GPU's name (GL_RENDERER) from a throwaway 1×1 OpenGL ES 3 context — for a partial benchmark that skips the GPU
 * section, whose scenes otherwise read it (the AI section picks its GPU settings by it, e.g. OpenGL on Adreno).
 * Empty if no context could be made.
 */
object GlRenderer {
    fun name(): String = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return ""
        try {
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
            if (n[0] <= 0) return ""
            val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            val surf = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                EGL14.eglMakeCurrent(display, surf, surf, ctx)
                GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surf)
                EGL14.eglDestroyContext(display, ctx)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }.getOrDefault("")
}
