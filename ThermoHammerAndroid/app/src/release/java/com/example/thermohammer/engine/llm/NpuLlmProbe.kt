package com.example.thermohammer.engine.llm

import android.content.Context
import com.example.thermohammer.engine.LlmNpuRun

/**
 * The Play build until version 5 ships LiteRT-LM there (with the model files as Play AI packs): no NPU engine yet —
 * the test builds' version is in src/nonRelease.
 */
object NpuLlmProbe {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun runIsolated(context: Context, onStep: (String, Float) -> Unit): LlmNpuRun =
        LlmNpuRun(0.0, 0.0, false, error = "no NPU engine in this build")
}
