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
    /** The GPU llama.cpp found, "" if none. */
    @JvmStatic external fun gpuName(): String
    /** How llama.cpp runs on it: "OpenCL" (Adreno) or "Vulkan" (other GPUs), "" if there's none. */
    @JvmStatic external fun gpuApi(): String
    /** Builds the model (weights from [seed]) on the CPU or the GPU; 0 with the reason in error[0]. */
    @JvmStatic external fun create(gpu: Boolean, threads: Int, seed: Long, error: Array<String?>): Long
    /**
     * Told on the tuning thread just before each trial run: [phase] 0 decode / 1 prefill, the [threads] about to be
     * timed, the [trial] number in that phase (0: the warm-up run before the first), and the [cores] there are.
     */
    fun interface TuneListener { fun onTrial(phase: Int, threads: Int, trial: Int, cores: Int) }
    /** [decode threads, prefill threads] that ran fastest; null if a trial failed. */
    @JvmStatic external fun tuneThreads(handle: Long, listener: TuneListener): IntArray?
    @JvmStatic external fun setThreads(handle: Long, threads: Int, batchThreads: Int)
    /**
     * The cores the CPU threads may use, fastest first, and which of the two pools (reply, prompt) have one thread
     * pinned to each of the first n of them; returns the cores usable. Without a call nothing is pinned.
     */
    @JvmStatic external fun setCores(handle: Long, cores: IntArray, pinDecode: Boolean, pinPrompt: Boolean): Int
    /** [tuneThreads] over [cores], each phase unpinned and pinned, the pinned result used only if clearly faster: LlmTuneReport decodes it. */
    @JvmStatic external fun tuneThreadsPinned(handle: Long, cores: IntArray, listener: LlmPinTuneListener): DoubleArray?
    /** The CPU's logits sample for a prompt of [tokens] from another library's run, so the GPU's answer is checked against it. */
    @JvmStatic external fun setReference(seed: Long, tokens: Int, sample: FloatArray)
    /** What the GPU backends said at debug level about the device they chose (Vulkan's and OpenCL's device lines). */
    @JvmStatic external fun backendLog(): String
    /** Runs the GPU on this backend ("Vulkan") instead of the usual choice; "" restores it. */
    @JvmStatic external fun setGpuApi(api: String)
    /** One prompt of [tokens] from an empty context: seconds, < 0 on failure. */
    @JvmStatic external fun prefill(handle: Long, tokens: Int): Double
    /** Empties the context for a new reply. */
    @JvmStatic external fun reset(handle: Long)
    /** One generated token: seconds (its id in token[0]), < 0 on failure. */
    @JvmStatic external fun next(handle: Long, token: IntArray?): Double
    /** Makes a running [prefill] or [next] fail early (any thread). */
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun error(handle: Long): String
    /** How closely the GPU's last prompt matched the CPU's (1 = the same, or nothing to compare). */
    @JvmStatic external fun agreement(handle: Long): Double
    /** The logits sample of the last [prefill]: what the two CPU libraries' answers are compared by (LlmProbe). */
    @JvmStatic external fun lastSample(handle: Long): FloatArray
    @JvmStatic external fun destroy(handle: Long)
    /** The few-millisecond kernel self-test (a tiny seeded model: a prompt, then one token): its [LlmProbe.SELFTEST_LOGITS] logits, or null with the reason in error[0]. */
    @JvmStatic external fun kernelSelftest(threads: Int, error: Array<String?>): FloatArray?
    /**
     * The same tiny model with every layer on the GPU llama.cpp chose (driver set-up, buffers, shaders, a prompt and
     * one token); false with the reason in error[0]. Run in the driver check's own process (DriverCheck): a GPU
     * driver that crashes here must not be this one's.
     */
    @JvmStatic external fun gpuSelftest(error: Array<String?>): Boolean
    /** The largest common 4-bit model (billions of parameters) that fits in [bytes]; 0 if none. */
    @JvmStatic external fun largestModel(bytes: Double): Int

    // What the phone offers, for the report (probe_jni.cpp; nothing here is scored).
    /** [AT_HWCAP, AT_HWCAP2]: the kernel's words for what every core can do (CpuFeatures decodes them). */
    @JvmStatic external fun hwcaps(): LongArray
    /** The Vulkan devices and what they offer llama.cpp's shaders, as text lines; [llamaGpu] marks the one it runs on. */
    @JvmStatic external fun vulkanReport(llamaGpu: String): String
    /** What the phone's OpenCL driver says about itself (questions only: no kernel is built or run), as text lines. */
    @JvmStatic external fun openClReport(): String
    /** Which of llama.cpp's Vulkan shader families this library was built with (glslc decides that at build time). */
    @JvmStatic external fun vulkanBuildFeatures(): String
}

/** Told before each trial of the pinned thread search: [phase] 0 decode / 1 prompt, whether it [pinned], the [threads] about to be timed, the [trial] in the pass, the [cores] it starts from. */
fun interface LlmPinTuneListener { fun onTrial(phase: Int, pinned: Int, threads: Int, trial: Int, cores: Int) }
