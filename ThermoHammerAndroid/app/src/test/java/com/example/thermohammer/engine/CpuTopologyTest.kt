package com.example.thermohammer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuTopologyTest {

    /** A phone's cpufreq files: core number → max frequency in kHz. */
    private fun phone(freqs: Map<Int, Long>, online: String = "0-${freqs.size - 1}", allowed: String? = null,
                      capacity: Map<Int, Long> = emptyMap()) =
        CpuTopologyParser.parse(online, allowed, { c -> freqs[c]?.let { "$it\n" } }, { c -> capacity[c]?.let { "$it\n" } })

    // Dimensity 9500 (Vivo X300 Pro): 4 C1-Pro, 3 C1-Premium, 1 C1-Ultra — the Ultra is the highest-numbered core
    private val dimensity9500 = (0..3).associateWith { 2_700_000L } + (4..6).associateWith { 3_500_000L } + (7 to 4_210_000L)

    // Snapdragon 8 Elite (HONOR 600 Pro): 6 performance cores then 2 prime, no efficiency cluster
    private val snapdragon8Elite = (0..5).associateWith { 3_532_800L } + (6..7).associateWith { 4_320_000L }

    @Test
    fun `a Dimensity 9500 keeps the Ultra and the Premium cores and leaves the Pro cluster out`() {
        val t = phone(dimensity9500)
        assertEquals(TopologyBasis.FREQUENCY, t.basis)
        assertEquals(listOf(listOf(7), listOf(4, 5, 6), listOf(0, 1, 2, 3)), t.clusters.map { it.cores })
        assertEquals("fastest first, and in a cluster by number", listOf(7, 4, 5, 6), t.big)
        assertEquals(listOf(0, 1, 2, 3), t.excluded)
        assertTrue(t.canPin)
        assertEquals(listOf(7, 4), t.fastest(2))
    }

    @Test
    fun `a Snapdragon 8 Elite keeps all eight cores because its second cluster is an equal-class one`() {
        val t = phone(snapdragon8Elite)
        assertEquals(listOf(listOf(6, 7), listOf(0, 1, 2, 3, 4, 5)), t.clusters.map { it.cores })
        assertEquals(listOf(6, 7, 0, 1, 2, 3, 4, 5), t.big)
        assertTrue(t.excluded.isEmpty())
        assertTrue("3.53 of 4.32 GHz is ${3_532_800.0 / 4_320_000}", 3_532_800.0 / 4_320_000 > CpuTopologyParser.EXCLUDE_BELOW)
    }

    @Test
    fun `a 1 plus 5 plus 2 phone leaves the two small cores out`() {
        val t = phone((0..1).associateWith { 2_000_000L } + (2..6).associateWith { 2_900_000L } + (7 to 3_300_000L))
        assertEquals(listOf(7, 2, 3, 4, 5, 6), t.big)
        assertEquals(listOf(0, 1), t.excluded)
    }

    @Test
    fun `a four core phone with one cluster keeps everything and can still be pinned`() {
        val t = phone((0..3).associateWith { 1_800_000L })
        assertEquals(1, t.clusters.size)
        assertEquals(listOf(0, 1, 2, 3), t.big)
        assertTrue(t.canPin)
    }

    @Test
    fun `the rule never leaves fewer than four cores, however slow the rest are`() {
        // 1 fast core and 7 slow ones: leaving the 7 out would leave one thread, so they stay in
        val t = phone((0..6).associateWith { 1_000_000L } + (7 to 3_000_000L))
        assertEquals(8, t.big.size)
        assertEquals(7, t.big.first())
        // 2 fast and 6 slow: the two alone are fewer than four, so the next cluster comes in
        val u = phone((0..5).associateWith { 1_500_000L } + (6..7).associateWith { 3_000_000L })
        assertEquals(8, u.big.size)
        // 4 fast and 4 slow: exactly four are kept
        val v = phone((0..3).associateWith { 1_500_000L } + (4..7).associateWith { 3_000_000L })
        assertEquals(listOf(4, 5, 6, 7), v.big)
    }

    @Test
    fun `cores within three percent of a cluster's fastest are one cluster`() {
        val t = phone(mapOf(0 to 3_000_000L, 1 to 2_950_000L, 2 to 2_920_000L, 3 to 2_000_000L, 4 to 1_980_000L, 5 to 1_950_000L))
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), t.clusters.map { it.cores })
        // 2.9 GHz is below 97% of 3.0: its own cluster
        assertEquals(3, phone(mapOf(0 to 3_000_000L, 1 to 2_900_000L, 2 to 2_000_000L, 3 to 1_950_000L)).clusters.size)
    }

    @Test
    fun `cpu_capacity ranks the cores when no frequency is readable`() {
        val cap = (0..3).associateWith { 400L } + (4..6).associateWith { 800L } + (7 to 1024L)
        val t = phone(emptyMap(), online = "0-7", capacity = cap)
        assertEquals(TopologyBasis.CAPACITY, t.basis)
        assertEquals(listOf(7, 4, 5, 6), t.big)
        assertEquals(listOf(0, 1, 2, 3), t.excluded)
    }

    @Test
    fun `frequency is preferred over capacity when both are there`() {
        val t = phone(dimensity9500, capacity = (0..7).associateWith { 512L })
        assertEquals(TopologyBasis.FREQUENCY, t.basis)
        assertEquals(listOf(7, 4, 5, 6), t.big)
    }

    @Test
    fun `missing or unreadable files mean an unknown topology and no pinning, never a crash`() {
        val none = CpuTopologyParser.parse("0-7", null, { null }, { null })
        assertFalse(none.known); assertFalse(none.canPin); assertTrue(none.big.isEmpty())
        assertTrue(none.line(), none.line().contains("unknown") && none.line().contains("no thread is pinned"))
        assertFalse("no online list", CpuTopologyParser.parse(null, null, { "3000000" }, { null }).known)
        assertFalse("garbage online list", CpuTopologyParser.parse("x-y", null, { "3000000" }, { null }).known)
        // a frequency file with text in it, an empty one, a zero
        for (bad in listOf("n/a", "", "0", "-5", "3.5GHz")) assertFalse(bad, CpuTopologyParser.parse("0-7", null, { bad }, { null }).known)
        assertNotNull(none.note)
    }

    @Test
    fun `some cores reporting and some not is unknown rather than a guess`() {
        val t = phone(dimensity9500 - 3)
        assertFalse(t.known)
        assertTrue(t.note!!, t.note!!.contains("some cores"))
    }

    @Test
    fun `a single core or a process allowed on one core has nothing to pin`() {
        assertFalse(phone(mapOf(0 to 2_000_000L), online = "0").known)
        assertFalse("restricted to one core by its cpuset", phone(dimensity9500, allowed = "7").known)
    }

    @Test
    fun `cores the process may not use are left out of the ranking`() {
        // a background cpuset without the fast cores
        val t = phone(dimensity9500, allowed = "0-6")
        assertEquals(listOf(4, 5, 6, 0, 1, 2, 3), t.big)
        assertFalse(7 in t.clusters.flatMap { it.cores })
    }

    @Test
    fun `an offline core is not ranked`() {
        val t = phone(dimensity9500, online = "0-5,7")
        assertEquals(listOf(7, 4, 5), t.clusters.take(2).flatMap { it.cores })
        assertFalse(6 in t.clusters.flatMap { it.cores })
    }

    @Test
    fun `core lists parse as the kernel writes them`() {
        assertEquals(listOf(0, 1, 2, 3, 6, 7), CpuTopologyParser.parseList("0-3,6,7\n"))
        assertEquals(listOf(5), CpuTopologyParser.parseList("5"))
        assertEquals(emptyList<Int>(), CpuTopologyParser.parseList(""))
        assertEquals(emptyList<Int>(), CpuTopologyParser.parseList(null))
        assertEquals(emptyList<Int>(), CpuTopologyParser.parseList("3-1"))
        assertEquals(emptyList<Int>(), CpuTopologyParser.parseList("a,b"))
        assertEquals(emptyList<Int>(), CpuTopologyParser.parseList("1-2-3"))
        assertEquals(listOf(0, 1, 2), CpuTopologyParser.parseList(" 0 - 2 "))
        assertEquals("0-7", CpuTopologyParser.allowedFromStatus("Name:\tapp\nCpus_allowed:\tff\nCpus_allowed_list:\t0-7\nMems_allowed:\t1\n"))
        assertEquals(null, CpuTopologyParser.allowedFromStatus("Name:\tapp\n"))
    }

    @Test
    fun `the report line names the clusters, what is left out and the pinning order`() {
        val line = phone(dimensity9500).line()
        assertEquals("CPU topology · by cpuinfo_max_freq · 4.21 GHz: cpu7 · 3.50 GHz: cpu4-6 · " +
            "2.70 GHz: cpu0-3 (left out: 64% of the fastest) · pinning order 7,4,5,6", line)
        assertTrue(phone(snapdragon8Elite).line(), phone(snapdragon8Elite).line().endsWith("pinning order 6,7,0,1,2,3,4,5"))
        assertEquals("cpu0-3 cpu7", CpuTopology.ranges(listOf(7, 0, 1, 2, 3)))
        assertEquals("cpu0 cpu1 cpu5", CpuTopology.ranges(listOf(0, 1, 5)))
    }

    @Test
    fun `the reader asks for the kernel's files and survives every one of them missing`() {
        val files = mapOf("/sys/devices/system/cpu/online" to "0-3\n", "/proc/self/status" to "Cpus_allowed_list:\t0-3\n") +
            (0..3).associate { "/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq" to (if (it < 2) "2400000\n" else "1800000\n") }
        val t = CpuTopologyReader.read { files[it] }
        assertEquals(listOf(0, 1, 2, 3), t.big)
        assertEquals(2, t.clusters.size)
        assertFalse(CpuTopologyReader.read { null }.known)
        assertFalse("a reader that throws", CpuTopologyReader.read { throw SecurityException("denied") }.known)
        // 'present' stands in when 'online' can't be read
        val viaPresent = CpuTopologyReader.read { if (it.endsWith("/online")) null else if (it.endsWith("/present")) "0-1" else if (it.contains("cpuinfo_max_freq")) "2000000" else null }
        assertEquals(listOf(0, 1), viaPresent.big)
    }
}
