package com.example.thermohammer.data

import com.example.thermohammer.network.BenchmarkDetailDto
import com.example.thermohammer.network.DetailGroupDto
import com.example.thermohammer.network.DetailRowDto
import com.example.thermohammer.network.DetailSectionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkComparisonTest {

    private fun detail(total: Int, vararg sections: DetailSectionDto) = BenchmarkDetailDto(
        id = 1, createdAt = "2026-11-09T13:58:36", deviceManufacturer = "HONOR", deviceModel = "HONOR 600 Pro", socModel = "SM8750",
        gpuName = "Adreno (TM) 830", os = 2, osVersion = "17", osBuild = null, appVersion = "2.0.3", totalScore = total,
        sections = sections.toList()
    )

    private fun section(key: String, score: Int, vararg groups: DetailGroupDto) = DetailSectionDto(key, key.uppercase(), score, groups.toList())
    private fun group(title: String, points: Int?, vararg rows: DetailRowDto) = DetailGroupDto(title, points, rows.toList())
    private fun row(label: String, number: Double?, better: String? = "higher") =
        DetailRowDto(label, number?.let { "$it" } ?: "—", number, better)

    @Test
    fun `a duel follows which way is better`() {
        assertEquals(Duel(Lead.A, 20.0), Duel.of(120.0, 100.0, "higher").rounded())
        assertEquals(Duel(Lead.B, 20.0), Duel.of(120.0, 100.0, "lower").rounded())    // latency: less wins
        assertEquals(Lead.EVEN, Duel.of(100.0, 100.5, "higher").winner)                 // within 1 %
        assertEquals(Lead.EVEN, Duel.of(100.0, 100.0, "lower").winner)
        assertEquals(Lead.NONE, Duel.of(1.0, 2.0, null).winner)                         // context, e.g. free memory
        assertEquals(Lead.NONE, Duel.of(null, null, "higher").winner)
    }

    @Test
    fun `a test only one device ran is a lead without a percentage`() {
        assertEquals(Duel(Lead.A), Duel.of(50.0, null, "higher"))
        assertEquals(Duel(Lead.B), Duel.of(0.0, 30.0, "higher"))        // 0 means it didn't run
        assertEquals(Duel(Lead.A), Duel.of(0.0, 2.0, "lower"))          // 0 % dropped frames is the best there is
        assertEquals(Duel(Lead.B), Duel.scores(0, 4532))                // ray tracing on one GPU only
    }

    @Test
    fun `runs are matched by key, title and label, in A's order and then what only B has`() {
        val a = detail(30000,
            section("cpu", 6000, group("Math", 6000, row("Throughput", 61.5))),
            section("gpu", 4000,
                group("Neon City RT", 4532, row("Average", 15.0), row("1% low", 12.0))))
        val b = detail(25000,
            section("gpu", 3000,
                group("Neon City RT", 0, DetailRowDto("Ray tracing", "not supported by this GPU"))),
            section("cpu", 7000, group("Math", 7000, row("Throughput", 70.0))),
            section("ai", 2000, group("NPU", 2000, row("Classify", 4100.0))))

        val c = BenchmarkComparison.of(a, b)
        assertEquals(listOf("cpu", "gpu", "ai"), c.sections.map { it.key })

        val rt = c.sections[1].groups.single()
        assertEquals(listOf("Average", "1% low", "Ray tracing"), rt.rows.map { it.label })
        assertEquals(Lead.A, rt.rows[0].duel.winner)
        assertNull(rt.rows[0].duel.advantage)
        assertNull(rt.rows[0].b)                                         // B has no such row
        assertEquals(Lead.NONE, rt.rows[2].duel.winner)                  // text on one side only
        assertEquals(Lead.A, rt.duel.winner)                             // 4532 points against 0

        val ai = c.sections[2]
        assertNull(ai.scoreA)
        assertEquals(Lead.B, ai.duel.winner)
        assertEquals(0f, ai.shareA)
        assertEquals(1f, ai.shareB)
    }

    @Test
    fun `the totals and the sections give the verdict`() {
        val a = detail(36000, section("cpu", 6000), section("gpu", 6000), section("ux", 6000), section("ai", 3000))
        val b = detail(30000, section("cpu", 5000), section("gpu", 6030), section("ux", 7000), section("ai", 1000))
        val c = BenchmarkComparison.of(a, b)

        assertEquals(Lead.A, c.total.winner)
        assertEquals(2, c.winsA)                                         // cpu, ai
        assertEquals(1, c.winsB)                                         // ux
        assertEquals(1, c.even)                                          // gpu within 1 %
        assertEquals("Alpha scores 20% higher overall", c.headline("Alpha", "Beta"))
        assertEquals("Ahead in 2 of 4 sections · 1 even", c.sectionsLine())
        assertEquals(6000f / 7000f, c.sections[2].shareA, 1e-6f)
        assertEquals(36000f / 66000f, c.totalShareA, 1e-6f)
    }

    @Test
    fun `swapping mirrors every result`() {
        val a = detail(36000, section("cpu", 6000, group("RAM", 3000, row("Latency", 140.0, "lower"))))
        val b = detail(30000, section("cpu", 5000, group("RAM", 2500, row("Latency", 120.0, "lower"))))
        val c = BenchmarkComparison.of(a, b)
        val s = c.swapped()
        assertEquals(Lead.B, c.sections[0].groups[0].rows[0].duel.winner)
        assertEquals(Lead.A, s.sections[0].groups[0].rows[0].duel.winner)
        assertEquals(Lead.B, s.total.winner)
        assertEquals("Beta scores 20% higher overall", s.headline("Alpha", "Beta"))
    }

    @Test
    fun `percentages read naturally`() {
        assertEquals("4.2%", percentText(4.24))
        assertEquals("10%", percentText(9.96))
        assertEquals("+23%", advantageText(23.4))
    }

    private fun Duel.rounded() = copy(advantage = advantage?.let { Math.round(it * 1000) / 1000.0 })
}
