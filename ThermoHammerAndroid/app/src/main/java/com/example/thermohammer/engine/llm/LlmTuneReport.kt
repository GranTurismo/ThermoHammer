package com.example.thermohammer.engine.llm

import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// LLM THREAD TUNING REPORT — what the CPU test's thread search tried, pinned and not, and what it kept.
//
//   BenchCore/llm searches the thread counts twice per phase: as it always did (the most threads first, wherever the
//   system runs them) and pinned (one thread on each of the fastest cores — CpuTopology says which). The pinned
//   result is used only where it beats the unpinned best by at least 5%, because pinning can also hurt when the system
//   wants one of those cores for itself. This decodes the numbers the native search hands back and writes every
//   trial's rate into the report, so the choice can be read after a run.
// ─────────────────────────────────────────────────────────────────────────────

/** One timed trial: a phase, whether its threads were pinned, how many, and the time for `tokens` tokens. */
data class LlmTrial(val decode: Boolean, val pinned: Boolean, val threads: Int, val tokens: Int, val seconds: Double) {
    val rate: Double get() = if (seconds > 0) tokens / seconds else 0.0
}

data class LlmTuneReport(
    val cores: List<Int>,                 // the cores offered for pinning, fastest first
    val pinnable: Int,                    // how many of them the native side could use
    val decodePinned: Boolean,            // the pinned result won and is used
    val promptPinned: Boolean,
    val decodeSkipped: Boolean,           // no pinned pass: the unpinned one had already taken too long
    val promptSkipped: Boolean,
    val decodeThreads: Int,
    val promptThreads: Int,
    val mainCore: Int,                    // the core the calling thread sat on alone after tuning, -1 if none
    val seconds: Double,
    val trials: List<LlmTrial>,
    /** Cores the process's threads were found pinned to one each, checked from /proc after tuning (null: not checked). */
    val singleCoreThreads: Map<Int, Int>? = null
) {
    /** Whether the CPU test's threads were pinned for either phase. */
    val pinned: Boolean get() = decodePinned || promptPinned

    private fun best(decode: Boolean, pinned: Boolean): LlmTrial? =
        trials.filter { it.decode == decode && it.pinned == pinned }.maxByOrNull { it.rate }

    /** The pinned best's rate over the unpinned best's in a phase (null when either wasn't run). */
    fun gain(decode: Boolean): Double? {
        val u = best(decode, false)?.rate ?: return null
        val p = best(decode, true)?.rate ?: return null
        return if (u > 0) p / u else null
    }

    private fun phaseLine(decode: Boolean): String {
        val name = if (decode) "decode" else "prompt"
        fun pass(pinned: Boolean): String {
            val t = trials.filter { it.decode == decode && it.pinned == pinned }
            return if (t.isEmpty()) "-" else t.joinToString(", ") { "%d thr %.2f".format(Locale.US, it.threads, it.rate) }
        }
        val used = if (decode) decodePinned else promptPinned
        val threads = if (decode) decodeThreads else promptThreads
        val skipped = if (decode) decodeSkipped else promptSkipped
        val g = gain(decode)
        val verdict = when {
            used -> "used: pinned, $threads threads${g?.let { " (×%.2f of the best unpinned)".format(Locale.US, it) } ?: ""}"
            skipped -> "used: unpinned, $threads threads (no pinned pass: the unpinned one took too long)"
            g != null -> "used: unpinned, $threads threads (pinned ×%.2f of it, under ×%.2f)".format(Locale.US, g, MIN_GAIN)
            pinnable == 0 -> "used: unpinned, $threads threads (no core could be used for pinning)"
            else -> "used: unpinned, $threads threads"
        }
        return "  $name tok/s · unpinned: ${pass(false)} · pinned: ${pass(true)} · $verdict"
    }

    /** Report lines: the whole tuning, a line per phase with every trial's rate, and what /proc says was pinned. */
    fun lines(): List<String> {
        val out = ArrayList<String>()
        out += "LLM CPU threads · tuned in %.1f s · pinning tried on cores %s (%d usable, fastest first)".format(Locale.US, seconds,
            cores.joinToString(","), pinnable)
        out += phaseLine(true)
        out += phaseLine(false)
        if (pinned) {
            val single = singleCoreThreads
            out += "  pinning check · the calling thread sat " + (if (mainCore >= 0) "alone on cpu$mainCore" else "on no single core") +
                (single?.let { " · ${it.size} threads of the process (the pools' workers and the caller) were each limited to one core (${it.values.sorted().joinToString(",")})" } ?: "")
        }
        return out
    }

    companion object {
        /** A pinned result is used only when it is this much faster than the best unpinned count (the native constant). */
        const val MIN_GAIN = 1.05
        private const val HEADER = 10
        private const val PER_TRIAL = 5

        /**
         * Decodes what LlmNative.tuneThreadsPinned returns: [pinnable, decodePinned, promptPinned, decodeSkipped,
         * promptSkipped, decodeThreads, promptThreads, mainCore, seconds, trials, then per trial: phase, pinned,
         * threads, tokens, seconds]. Null when the array doesn't have that shape.
         */
        fun parse(raw: DoubleArray?, cores: List<Int>): LlmTuneReport? {
            if (raw == null || raw.size < HEADER) return null
            val n = raw[9].toInt()
            if (n < 0 || raw.size != HEADER + n * PER_TRIAL) return null
            val trials = (0 until n).map { i ->
                val o = HEADER + i * PER_TRIAL
                LlmTrial(raw[o].toInt() == 0, raw[o + 1].toInt() != 0, raw[o + 2].toInt(), raw[o + 3].toInt(), raw[o + 4])
            }
            return LlmTuneReport(cores, raw[0].toInt(), raw[1] != 0.0, raw[2] != 0.0, raw[3] != 0.0, raw[4] != 0.0,
                raw[5].toInt(), raw[6].toInt(), raw[7].toInt(), raw[8], trials)
        }
    }
}
