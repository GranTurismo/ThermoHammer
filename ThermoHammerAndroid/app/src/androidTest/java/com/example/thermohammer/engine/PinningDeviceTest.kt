package com.example.thermohammer.engine

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.engine.llm.DotprodLib
import com.example.thermohammer.engine.llm.LlmNative
import com.example.thermohammer.engine.llm.LlmTuneReport
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Thread placement on a real device or the emulator, read back from /proc/self/task: the LLM test's thread pool pinned
 * one thread to a core by llama.cpp's own cpumask, the calling thread given its own mask back afterwards, the pinned
 * search leaving nothing pinned, and whether LiteRT's XNNPACK workers inherit the mask of the thread that builds the
 * model. What the emulator can tell: the mechanics (masks, restoring), not speed — its cores are one cluster of equal
 * cores, so no placement is faster than another there. Results go to the instrumentation status.
 */
@RunWith(AndroidJUnit4::class)
class PinningDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val cores = listOf(7, 6, 5, 4)

    private fun status(vararg kv: Pair<String, String>) = instrumentation.sendStatus(0, Bundle().apply { kv.forEach { putString(it.first, it.second) } })

    @Test
    fun theLlmPoolIsPinnedOneThreadPerCoreAndTheCallerGetsItsMaskBack() {
        assumeTrue(LlmNative.loaded && LlmNative.cpuSupported() && Runtime.getRuntime().availableProcessors() >= 8)
        val before = CpuAffinity.mask()
        assertTrue("the test thread's own mask is readable", before.isNotEmpty())
        val error = arrayOfNulls<String>(1)
        val h = DotprodLib.create(4, LlmNative.SEED, error)
        assertTrue(error[0] ?: "model built", h != 0L)
        try {
            assertEquals(cores.size, DotprodLib.setCores(h, cores.toIntArray(), pinDecode = true, pinPrompt = true))
            DotprodLib.setThreads(h, 4, 4)
            assertTrue("a prompt runs on the pinned pool", DotprodLib.prefill(h, 16) > 0)
            val singles = ThreadTasks.singleCore(ThreadTasks.masks(ThreadTasks.ids())).filterValues { it in cores }
            // three workers and the calling thread, each alone on its own core of the four
            assertEquals("threads limited to one of the four cores: $singles", 4, singles.size)
            assertEquals("each on a different core", cores.sorted(), singles.values.sorted())
            assertEquals("the calling thread sits on the highest-numbered core of the set", cores.max(), CpuAffinity.mask().singleOrNull())
            status("pinned_threads" to singles.toString())
        } finally {
            DotprodLib.destroy(h)
        }
        assertEquals("the calling thread has its own mask back", before, CpuAffinity.mask())
        val left = ThreadTasks.singleCore(ThreadTasks.masks(ThreadTasks.ids())).filterValues { it in cores }
        assertTrue("no thread is left pinned: $left", left.isEmpty())
    }

    @Test
    fun theThreadSearchTriesBothAndLeavesTheThreadAsItWasWhenNothingIsAdopted() {
        assumeTrue(LlmNative.loaded && LlmNative.cpuSupported() && Runtime.getRuntime().availableProcessors() >= 8)
        val before = CpuAffinity.mask()
        val error = arrayOfNulls<String>(1)
        val h = DotprodLib.create(4, LlmNative.SEED, error)
        assertTrue(error[0] ?: "model built", h != 0L)
        try {
            val offered = (7 downTo 0).toList()
            val trials = ArrayList<String>()
            val started = System.nanoTime()
            val raw = DotprodLib.tuneThreadsPinned(h, offered.toIntArray()) { phase, pinned, n, trial, total -> trials += "$phase/$pinned/$n/$trial/$total" }
            val seconds = (System.nanoTime() - started) / 1e9
            val report = LlmTuneReport.parse(raw, offered)
            assertNotNull("the search returned a report of the expected shape", report)
            report!!
            assertEquals(8, report.pinnable)
            assertTrue("both passes ran for both phases: ${report.trials}", listOf(true, false).all { d -> listOf(true, false).all { p -> report.trials.any { it.decode == d && it.pinned == p } } })
            assertTrue("the progress listener heard every pass", trials.any { it.startsWith("0/1/") } && trials.any { it.startsWith("1/1/") })
            assertTrue("the chosen counts are within the trials", report.decodeThreads in 1..8 && report.promptThreads in 1..8)
            if (report.decodePinned) assertTrue(report.decodeThreads <= 8)
            // what is adopted is what is pinned now: with nothing adopted the calling thread must have its own mask
            if (!report.pinned) assertEquals("nothing adopted, nothing pinned", before, CpuAffinity.mask())
            status("search_seconds" to "%.1f".format(seconds), "report" to report.lines().joinToString("\n"))
        } finally {
            DotprodLib.destroy(h)
        }
        assertEquals(before, CpuAffinity.mask())
    }

    /** What the added pinned pass costs: the search as it always was, then the new one, on the same model. */
    @Test
    fun theUnpinnedSearchAloneAgainstTheTwoPassSearchInSeconds() {
        assumeTrue(LlmNative.loaded && LlmNative.cpuSupported() && Runtime.getRuntime().availableProcessors() >= 8)
        val error = arrayOfNulls<String>(1)
        val h = DotprodLib.create(4, LlmNative.SEED, error)
        assertTrue(error[0] ?: "model built", h != 0L)
        try {
            val times = ArrayList<String>()
            repeat(2) {
                var t = System.nanoTime()
                assertNotNull(DotprodLib.tuneThreads(h) { _, _, _, _ -> })
                val old = (System.nanoTime() - t) / 1e9
                t = System.nanoTime()
                assertNotNull(DotprodLib.tuneThreadsPinned(h, (7 downTo 0).toList().toIntArray()) { _, _, _, _, _ -> })
                times += "unpinned only %.1f s, with the pinned pass %.1f s".format(old, (System.nanoTime() - t) / 1e9)
            }
            status("search_times" to times.joinToString(" | "))
        } finally {
            DotprodLib.destroy(h)
        }
    }

    @Test
    fun anUnpinnedPoolIsNotBornOnAPinnedThread() {
        // the workers of an unpinned pool inherit the mask of the thread that makes them: after a pinned pool the next
        // unpinned one has to start from the thread's own mask, or every worker would share one core
        assumeTrue(LlmNative.loaded && LlmNative.cpuSupported() && Runtime.getRuntime().availableProcessors() >= 8)
        val before = CpuAffinity.mask()
        val error = arrayOfNulls<String>(1)
        val h = DotprodLib.create(4, LlmNative.SEED, error)
        assertTrue(error[0] ?: "model built", h != 0L)
        try {
            DotprodLib.setCores(h, cores.toIntArray(), pinDecode = true, pinPrompt = true)
            DotprodLib.setThreads(h, 4, 4)
            DotprodLib.prefill(h, 16)
            DotprodLib.setCores(h, IntArray(0), pinDecode = false, pinPrompt = false)      // back to unpinned
            DotprodLib.setThreads(h, 4, 4)
            assertTrue(DotprodLib.prefill(h, 16) > 0)
            assertEquals("the calling thread is back on its own mask", before, CpuAffinity.mask())
            val narrow = ThreadTasks.masks(ThreadTasks.ids()).filterValues { it.size <= 4 && it.all { c -> c in cores } }
            assertTrue("no worker is stuck on the old cores: $narrow", narrow.isEmpty())
        } finally {
            DotprodLib.destroy(h)
        }
    }

    /**
     * Run alone: the Apple-silicon emulator advertises SME2 that XNNPACK trusts and the guest can't run, so building a
     * model there dies with SIGILL. A watcher thread logs every new thread and its mask under the tag "PinWatch" while
     * the model is built, so what the pool inherited is in logcat even if the process dies after.
     */
    @Test
    fun liteRtsXnnpackWorkersInheritTheMaskOfTheThreadThatBuildsTheModel() {
        assumeTrue(CpuAffinityNative.loaded && Runtime.getRuntime().availableProcessors() >= 8)
        val before = CpuAffinity.mask()
        val tasks = ThreadTasks.ids()
        val saved = CpuAffinity.pin(cores)
        assertNotNull("the kernel accepts a mask", saved)
        val building = java.util.concurrent.atomic.AtomicBoolean(true)
        val seen = java.util.Collections.synchronizedSet(HashSet<Int>())
        val watcher = Thread {
            while (building.get()) {
                for (tid in ThreadTasks.added(tasks, ThreadTasks.ids()) - seen) {
                    val mask = ThreadTasks.masks(listOf(tid))[tid]
                    if (mask != null) { seen += tid; android.util.Log.w("PinWatch", "new thread $tid mask ${mask.joinToString(",")} (asked ${cores.sorted().joinToString(",")})") }
                }
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }
        var description = ""
        try {
            val options = CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(4, null, null) }
            android.util.Log.w("PinWatch", "creating the model on tid ${CpuAffinity.tid()} with mask ${CpuAffinity.mask().joinToString(",")}")
            CompiledModel.create(app.assets, AiTest.CLASSIFY.asset, options).use {
                description = ThreadTasks.describe(ThreadTasks.masks(ThreadTasks.added(tasks, ThreadTasks.ids())), cores)
            }
        } finally {
            building.set(false)
            CpuAffinity.restore(saved)
        }
        assertEquals("the caller's mask is back", before, CpuAffinity.mask())
        status("xnnpack_inheritance" to description)
        assertFalse(description, description.isEmpty())
    }
}
