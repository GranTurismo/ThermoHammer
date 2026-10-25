package com.example.thermohammer.engine.gpu

/** A benchmark scene rendered offscreen by GpuBench; all calls run on its GL thread. */
internal interface GpuScene {
    /** Uploads geometry, compiles shaders, creates render targets. */
    fun init()

    /** Renders one frame at timeline time `t` seconds into [outputFbo] (RGBA8, the bench's size). */
    fun render(t: Float)

    val outputFbo: Int

    /** Triangles submitted per frame across all passes (informational). */
    val trianglesPerFrame: Long

    /** True once a scene that keeps its own timeline (the UX tests) has finished it. */
    val complete: Boolean get() = false

    /** Frees what tearing down the GL context doesn't (codecs, threads). Called on the GL thread. */
    fun release() {}
}
