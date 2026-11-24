package com.example.thermohammer.engine.llm

import android.content.Context
import com.example.thermohammer.engine.LlmNpuRun
import java.io.File

/**
 * The Play build: no NPU LLM engine. Scoring version 5 doesn't score the NPU (no way to run it reaches every chip yet,
 * BENCHMARK_V5_PLAN.md), so the Play build leaves out LiteRT-LM (~23 MB); test builds' report-only engine is in
 * src/nonRelease.
 */
object NpuLlmProbe {
    const val AVAILABLE = false
    const val ENGINE = "LiteRT-LM 0.19.1"

    @Suppress("UNUSED_PARAMETER")
    suspend fun runIsolated(context: Context, model: File? = null, stepTimeoutMs: Long = 0,
                            onStep: (String, Float) -> Unit): LlmNpuRun =
        LlmNpuRun(0.0, 0.0, false, error = "no NPU engine in this build")
}
