package com.example.thermohammer.engine.llm

/**
 * JNI surface of libbenchllm_i8mm.so: the LLM test's CPU kernels built for CPUs with i8mm (CMakeLists.txt) — the same
 * sources and C API as [LlmNative], CPU only. Its functions answer to this class's name, so it never meets the other
 * library's. Touch it only after [CpuFeatures.supportsI8mm] says the CPU has i8mm: a CPU without it would fault the
 * moment a kernel ran. In stage 1 it is only probed (LlmProbe) and never scored.
 */
internal object LlmNativeI8mm {
    /** Why the library didn't load (null: it did): a phone without it in the build, or a loader refusal. */
    val loadError: String? = try { System.loadLibrary("benchllm_i8mm"); null } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
    val loaded: Boolean get() = loadError == null

    @JvmStatic external fun create(gpu: Boolean, threads: Int, seed: Long, error: Array<String?>): Long
    @JvmStatic external fun setThreads(handle: Long, threads: Int, batchThreads: Int)
    @JvmStatic external fun prefill(handle: Long, tokens: Int): Double
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun next(handle: Long, token: IntArray?): Double
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun error(handle: Long): String
    @JvmStatic external fun lastSample(handle: Long): FloatArray
    @JvmStatic external fun destroy(handle: Long)
}
