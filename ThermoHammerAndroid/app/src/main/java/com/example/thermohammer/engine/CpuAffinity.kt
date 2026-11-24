package com.example.thermohammer.engine

import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// CPU AFFINITY — which cores a thread may run on, for the tests that try placing threads on the fast cores.
//
//   For what the app can't hand a mask to: LiteRT's XNNPACK pool. A thread's mask is inherited by the threads it
//   starts, so pinning the calling thread just before LiteRT builds its pool places the pool's workers, and the caller
//   puts its own mask back afterwards. Whether LiteRT really builds its pool on the calling thread is checked from /proc/self/task, never
//   assumed. A refusal (an OEM kernel, a core that is off, no native library) is a null, never an exception.
// ─────────────────────────────────────────────────────────────────────────────

object CpuAffinityNative {
    val loaded: Boolean = runCatching { System.loadLibrary("thaffinity") }.isSuccess

    /** The calling thread's kernel id, the number in /proc/self/task/<tid>. */
    @JvmStatic external fun tid(): Int
    /** The cores thread `tid` (0: the caller) may run on; empty when the kernel won't say. */
    @JvmStatic external fun get(tid: Int): IntArray
    /** Restricts thread `tid` (0: the caller) to `cores`. */
    @JvmStatic external fun set(tid: Int, cores: IntArray): Boolean
}

object CpuAffinity {
    /** A thread's mask as it was, to put back. */
    class Saved(val tid: Int, val cores: List<Int>)

    private fun native(): Boolean = CpuAffinityNative.loaded

    fun tid(): Int = if (native()) runCatching { CpuAffinityNative.tid() }.getOrDefault(0) else 0

    /** The cores thread `tid` (0: the caller) may run on; empty when unknown. */
    fun mask(tid: Int = 0): List<Int> = if (native()) runCatching { CpuAffinityNative.get(tid).toList() }.getOrDefault(emptyList()) else emptyList()

    /**
     * Restricts the calling thread to `cores` and returns what to put back; null when there is nothing to pin to or the
     * kernel refused (the thread is then as it was).
     */
    fun pin(cores: List<Int>): Saved? {
        if (!native() || cores.isEmpty()) return null
        val before = mask()
        if (before.isEmpty()) return null
        val ok = runCatching { CpuAffinityNative.set(0, cores.toIntArray()) }.getOrDefault(false)
        return if (ok) Saved(tid(), before) else null
    }

    /** Puts `saved`'s mask back on the thread it came from (any thread may ask). */
    fun restore(saved: Saved?): Boolean =
        saved != null && native() && runCatching { CpuAffinityNative.set(saved.tid, saved.cores.toIntArray()) }.getOrDefault(false)
}

/** A look at the process's threads from /proc/self/task: which exist, and which cores each may run on. */
object ThreadTasks {
    /** The cores of a task's status file ("Cpus_allowed_list:\t4-7"); empty when the line isn't there. */
    fun parseMask(status: String?): List<Int> = CpuTopologyParser.parseList(CpuTopologyParser.allowedFromStatus(status))

    /** Threads that exist in `after` and not in `before`: what starting a model added. */
    fun added(before: Set<Int>, after: Set<Int>): Set<Int> = after - before

    /**
     * What the threads a model start-up added ended up allowed to run on, against the cores they were asked to stay on:
     * whether the pool inherited the mask of the thread that built the model. "LiteRT started 3 threads: 3 inside the
     * asked cores 4-7" or "...: 0 of 3 inside the asked cores 4-7 (masks cpu0-7 ×3)".
     */
    fun describe(added: Map<Int, List<Int>>, asked: List<Int>): String {
        if (added.isEmpty()) return "no new thread was found after the model was built (its pool starts none, or on first use)"
        val inside = added.values.count { m -> m.isNotEmpty() && asked.containsAll(m) }
        val masks = added.values.groupingBy { CpuTopology.ranges(it) }.eachCount().entries.joinToString(", ") { "${it.key} ×${it.value}" }
        return "LiteRT started ${added.size} thread${if (added.size == 1) "" else "s"}: $inside of ${added.size} inside the asked cores " +
            "${CpuTopology.ranges(asked)} (masks $masks)"
    }

    /** Task → the one core it is pinned to, for the tasks whose mask is a single core. */
    fun singleCore(masks: Map<Int, List<Int>>): Map<Int, Int> = masks.filterValues { it.size == 1 }.mapValues { it.value.first() }

    private fun text(path: String): String? = try { File(path).readText() } catch (_: Exception) { null }

    fun ids(): Set<Int> = try { File("/proc/self/task").list().orEmpty().mapNotNull { it.toIntOrNull() }.toSet() } catch (_: Exception) { emptySet() }

    /** Each given task's allowed cores (a task that ended in between is left out). */
    fun masks(ids: Collection<Int>, read: (String) -> String? = ::text): Map<Int, List<Int>> =
        ids.mapNotNull { id -> parseMask(read("/proc/self/task/$id/status")).takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
}
