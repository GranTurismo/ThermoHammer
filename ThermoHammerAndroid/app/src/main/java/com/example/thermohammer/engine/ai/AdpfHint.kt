package com.example.thermohammer.engine.ai

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager

/**
 * An ADPF hint session (Android Dynamic Performance Framework, Android 12+): the app tells the system which threads
 * do latency-critical work and how long each round of it took, and the vendor's governor raises those threads' CPU
 * clocks (and may move them to faster cores) when the rounds run longer than the target. It is vendor-neutral — the
 * same call on Qualcomm, MediaTek, Samsung and Tensor phones — and its effect isn't documented per chip, so the AI
 * tuning only uses it where a probe measured it faster (AiSettings.cpu).
 *
 * The target is set far below any inference's real time on purpose: a session that is always behind its target is
 * the one the governor gives the most to, which is what a benchmark wants.
 *
 * The class is only ever made through [start], which refuses before Android 12 (the NewApi lint can't follow that).
 */
@SuppressLint("NewApi")
class AdpfHint private constructor(private val session: PerformanceHintManager.Session) : AutoCloseable {

    /** Tells the system one round took `nanos` (an inference). A refusal is not worth failing a run for. */
    fun report(nanos: Long) {
        try { session.reportActualWorkDuration(nanos) } catch (_: Exception) {}
    }

    override fun close() {
        try { session.close() } catch (_: Exception) {}
    }

    companion object {
        /** The session's target round time: 1 ms, below what any model here takes, so the system always sees it running late. */
        const val TARGET_NS = 1_000_000L

        /** Whether this Android has the API at all (12+). */
        fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /**
         * A session over the threads `tids` (kernel thread ids of this process). Throws with the reason when the
         * phone has no hint manager, refuses the session, or a thread has ended — the caller reports the setting as failed
         * rather than pretending it ran.
         */
        fun start(context: Context, tids: IntArray): AdpfHint {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw IllegalStateException("ADPF needs Android 12")
            val manager = context.getSystemService(PerformanceHintManager::class.java)
                ?: throw IllegalStateException("this phone has no PerformanceHintManager")
            val session = try { manager.createHintSession(tids, TARGET_NS) } catch (e: Exception) {
                throw IllegalStateException("the hint session was refused: ${e.message ?: e.javaClass.simpleName}")
            } ?: throw IllegalStateException("the system gave no hint session (the vendor's governor doesn't support ADPF)")
            // Android 15 can be told not to favour power efficiency for these threads: what a benchmark wants
            if (Build.VERSION.SDK_INT >= 35) try { session.setPreferPowerEfficiency(false) } catch (_: Exception) {}
            return AdpfHint(session)
        }
    }
}
