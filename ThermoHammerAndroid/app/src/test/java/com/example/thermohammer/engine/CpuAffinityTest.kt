package com.example.thermohammer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuAffinityTest {

    private fun status(list: String) = "Name:\tthread\nState:\tS (sleeping)\nCpus_allowed:\tf0\nCpus_allowed_list:\t$list\nMems_allowed:\t1\n"

    @Test
    fun `a task's mask comes from its status file`() {
        assertEquals(listOf(4, 5, 6, 7), ThreadTasks.parseMask(status("4-7")))
        assertEquals(listOf(7), ThreadTasks.parseMask(status("7")))
        assertEquals(listOf(0, 1, 2, 3, 6), ThreadTasks.parseMask(status("0-3,6")))
        assertEquals(emptyList<Int>(), ThreadTasks.parseMask("Name:\tx\n"))
        assertEquals(emptyList<Int>(), ThreadTasks.parseMask(null))
    }

    @Test
    fun `the threads a model added are what is new after it was built`() {
        assertEquals(setOf(103, 104), ThreadTasks.added(setOf(100, 101, 102), setOf(100, 101, 102, 103, 104)))
        assertEquals(emptySet<Int>(), ThreadTasks.added(setOf(1, 2), setOf(1)))
    }

    @Test
    fun `tasks pinned to exactly one core are picked out`() {
        val masks = mapOf(10 to listOf(4), 11 to listOf(0, 1, 2, 3), 12 to listOf(7), 13 to emptyList())
        assertEquals(mapOf(10 to 4, 12 to 7), ThreadTasks.singleCore(masks))
    }

    @Test
    fun `masks are read per task and a task that ended in between is left out`() {
        val files = mapOf("/proc/self/task/10/status" to status("4-7"), "/proc/self/task/11/status" to status("0"))
        val got = ThreadTasks.masks(listOf(10, 11, 12)) { files[it] }
        assertEquals(mapOf(10 to listOf(4, 5, 6, 7), 11 to listOf(0)), got)
    }

    @Test
    fun `the description says whether the pool inherited the mask`() {
        val asked = listOf(4, 5, 6, 7)
        val inherited = ThreadTasks.describe(mapOf(21 to asked, 22 to asked, 23 to asked), asked)
        assertEquals("LiteRT started 3 threads: 3 of 3 inside the asked cores cpu4-7 (masks cpu4-7 ×3)", inherited)
        val missed = ThreadTasks.describe(mapOf(21 to (0..7).toList(), 22 to (0..7).toList()), asked)
        assertTrue(missed, missed.startsWith("LiteRT started 2 threads: 0 of 2 inside the asked cores cpu4-7 (masks cpu0-7 ×2)"))
        val mixed = ThreadTasks.describe(mapOf(21 to asked, 22 to (0..7).toList()), asked)
        assertTrue(mixed, mixed.contains("1 of 2 inside"))
        assertTrue(ThreadTasks.describe(emptyMap(), asked).contains("no new thread"))
        assertEquals("LiteRT started 1 thread: 1 of 1 inside the asked cores cpu7 (masks cpu7 ×1)", ThreadTasks.describe(mapOf(5 to listOf(7)), listOf(7)))
    }
}
