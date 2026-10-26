package com.example.thermohammer.engine.llm

/**
 * JNI surface of the LLM test in libbenchllm.so (BenchCore/llm/llm.cpp, llama.cpp; shared with iOS). Built for
 * 64-bit Arm only: elsewhere [loaded] is false and nothing else may be called.
 */
internal object LlmNative {
    val loaded: Boolean = runCatching { System.loadLibrary("benchllm") }.isSuccess

    const val PROMPT = 512           // BC_LLM_PROMPT
    const val SEED = 3L              // BC_LLM_SEED

    @JvmStatic external fun weightBytes(): Long
    /** ARMv8.2 dot product and FP16, which the kernels are built with. */
    @JvmStatic external fun cpuSupported(): Boolean
    /** Where the GPU's compiled kernels are kept between runs; before the first [create]. */
    @JvmStatic external fun setCacheDir(dir: String)
    /** The OpenCL GPU llama.cpp found, "" if none. */
    @JvmStatic external fun gpuName(): String
    /** Builds the model (weights from [seed]) on the CPU or the GPU; 0 with the reason in error[0]. */
    @JvmStatic external fun create(gpu: Boolean, threads: Int, seed: Long, error: Array<String?>): Long
    /** [decode threads, prefill threads] that ran fastest; null if a trial failed. */
    @JvmStatic external fun tuneThreads(handle: Long): IntArray?
    @JvmStatic external fun setThreads(handle: Long, threads: Int, batchThreads: Int)
    /** One prompt of [tokens] from an empty context: seconds, < 0 on failure. */
    @JvmStatic external fun prefill(handle: Long, tokens: Int): Double
    /** Empties the context for a new reply. */
    @JvmStatic external fun reset(handle: Long)
    /** One generated token: seconds (its id in token[0]), < 0 on failure. */
    @JvmStatic external fun next(handle: Long, token: IntArray?): Double
    /** Makes a running [prefill] or [next] fail early (any thread). */
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun error(handle: Long): String
    @JvmStatic external fun destroy(handle: Long)
    /** The largest common 4-bit model (billions of parameters) that fits in [bytes]; 0 if none. */
    @JvmStatic external fun largestModel(bytes: Double): Int
}
