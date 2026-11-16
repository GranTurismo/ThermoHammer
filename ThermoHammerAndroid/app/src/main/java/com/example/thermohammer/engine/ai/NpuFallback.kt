package com.example.thermohammer.engine.ai

import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiRun
import com.example.thermohammer.engine.AiTest
import kotlin.math.exp
import kotlin.math.ln

/**
 * Which NPU runs count as the NPU's.
 *
 * LiteRT runs an "NPU" model on the CPU without a word when no vendor library takes it, so an NPU that is no faster
 * than the CPU across the models is taken for that fallback and scores 0. That rule alone threw away a working NPU:
 * on a Dimensity 1200 MediaTek's compiler took every model and the answers were the CPU's, but a tiny model costs more
 * to hand over to the NPU than to run (sound recognition: 6 ms a call against the CPU's 0.6), and that one result
 * pulled the average under the line — all six runs scored 0.
 *
 * So a run is the NPU's own where there is evidence of it (`proven`: LiteRT's log says the vendor's compiler took ops
 * of the model, and its outputs agree with the CPU's): it keeps its rate, whatever that is — a slow NPU scores few
 * points, not none. Runs without such evidence (Qualcomm's and Google's runtimes log nothing of the kind) are judged
 * exactly as before.
 */
object NpuFallback {
    /** The NPU has to be this much faster than the CPU (geometric mean over the models) not to be taken for a fallback. */
    const val MIN_SPEEDUP = 1.2

    /** NPU rate over CPU rate, geometric mean over the models both ran; null when there is none to compare. */
    fun speedup(runs: List<AiRun>): Double? {
        fun ips(t: AiTest, a: AiAccel) = runs.firstOrNull { it.test == t && it.accel == a }?.ips ?: 0.0
        val ratios = AiTest.entries.mapNotNull { t -> ips(t, AiAccel.CPU).takeIf { it > 0 }?.let { c -> ips(t, AiAccel.NPU) / c }?.takeIf { it > 0 } }
        return if (ratios.isEmpty()) null else exp(ratios.sumOf { ln(it) } / ratios.size)
    }

    /**
     * `runs` with the NPU runs that pass for a CPU fallback set to 0 (and why); `proven` are the models whose NPU run
     * is known to be the NPU's. (A run whose answer isn't the CPU's has scored 0 before this, on any accelerator:
     * AiAgreement.wrongAnswer.)
     */
    fun check(runs: List<AiRun>, proven: Set<AiTest> = emptySet()): List<AiRun> {
        val speedup = speedup(runs) ?: return runs
        if (speedup >= MIN_SPEEDUP) return runs
        return runs.map {
            if (it.accel != AiAccel.NPU || it.ips <= 0 || it.test in proven) it
            else it.copy(ips = 0.0, error = "no faster than the CPU (%.2f×): LiteRT fell back to it, so it scores 0 · it ran at %.2f/s".format(speedup, it.ips))
        }
    }
}
