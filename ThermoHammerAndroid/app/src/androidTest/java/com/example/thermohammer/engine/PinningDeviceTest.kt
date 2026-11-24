package com.example.thermohammer.engine

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Thread placement on a real device or the emulator, read back from /proc/self/task: whether LiteRT's XNNPACK workers
 * inherit the mask of the thread that builds the model (the AI CPU test's pinning), and the caller getting its own mask
 * back. What the emulator can tell: the mechanics (masks, restoring), not speed — its cores are one cluster of equal
 * cores, so no placement is faster than another there. Results go to the instrumentation status.
 */
@RunWith(AndroidJUnit4::class)
class PinningDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val cores = listOf(7, 6, 5, 4)

    private fun status(vararg kv: Pair<String, String>) = instrumentation.sendStatus(0, Bundle().apply { kv.forEach { putString(it.first, it.second) } })

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
