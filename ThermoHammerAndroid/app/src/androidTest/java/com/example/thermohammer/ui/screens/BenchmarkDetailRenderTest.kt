package com.example.thermohammer.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.ThermoHammerTheme
import com.example.thermohammer.network.BenchmarkDetailDto
import com.example.thermohammer.network.BenchmarkDto
import com.example.thermohammer.network.DetailGroupDto
import com.example.thermohammer.network.DetailRowDto
import com.example.thermohammer.network.DetailSectionDto
import com.example.thermohammer.ui.theme.Forge
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** A leaderboard run opened from its row: the card with three sections open, drawn at a phone's size (benchmark_detail.png in the app's external files dir). */
@RunWith(AndroidJUnit4::class)
class BenchmarkDetailRenderTest {

    @get:Rule val rule = createComposeRule()

    private val entry = BenchmarkDto(id = 7, deviceManufacturer = "Microsoft", deviceModel = "Surface Laptop 7", socModel = "Snapdragon X Elite",
        gpuName = "Qualcomm Adreno X1-85", os = 4, osVersion = "10.0.26200", score = 5200, uxScore = 6100, cpuScore = 6900, aiScore = 3100,
        memScore = 5800, llmScore = 4300, totalScore = 31400, test1Score = 6000, test2Score = 5100, test3Score = 0, test1Fps = 210.5,
        test2Fps = 41.2, test3Fps = 0.0, rayTracing = false, runs = 3)

    private fun rows(vararg p: Pair<String, String>) = p.map { DetailRowDto(it.first, it.second) }

    private val detail = BenchmarkDetailDto(id = 7, createdAt = "2026-10-27T18:20:11Z", deviceManufacturer = "Microsoft", deviceModel = "Surface Laptop 7",
        socModel = "Snapdragon X Elite", gpuName = "Qualcomm Adreno X1-85", os = 4, osVersion = "10.0.26200", osBuild = "26200.5000", appVersion = "2.0.0",
        totalScore = 31400, sections = listOf(
            DetailSectionDto("cpu", "CPU", 6900, listOf(
                DetailGroupDto("Math", 6200, rows("Throughput" to "1,540 ops/s")),
                DetailGroupDto("Multi-core", 7400, rows("Throughput" to "5,210 ops/s")))),
            DetailSectionDto("mem", "MEMORY", 5800, listOf(
                DetailGroupDto("RAM", 6100, rows("Read" to "57.8 GB/s", "Write" to "28.4 GB/s", "Latency" to "245 ns")),
                DetailGroupDto("Storage", 5200, rows("Sequential read" to "1,750 MB/s", "Reads" to "direct I/O")))),
            DetailSectionDto("gpu", "GPU", 5200, listOf(
                DetailGroupDto("Lake Valley", 6000, rows("Average" to "210 fps", "1% low" to "180 fps")),
                DetailGroupDto("Neon City RT", 0, rows("Ray tracing" to "not supported by this GPU")))),
            DetailSectionDto("ux", "EVERYDAY UX", 6100, listOf(
                DetailGroupDto("Video playback", 5900, rows("4K60 H.264" to "298 fps · hardware", "4K60 AV1" to "not supported")))),
            DetailSectionDto("ai", "AI", 3100, emptyList()),
            DetailSectionDto("llm", "LLM", 4300, emptyList())))

    @Test
    fun detailCard() {
        rule.setContent {
            ThermoHammerTheme {
                Box(Modifier.size(412.dp, 860.dp).background(Forge.bg)) {
                    BenchmarkDetailOverlay(entry, 2, detail, false, null, {}, {})
                }
            }
        }
        rule.onNodeWithText("CPU").performClick()
        rule.onNodeWithText("MEMORY").performClick()
        rule.onNodeWithText("GPU").performClick()
        val bmp: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        FileOutputStream(File(app.getExternalFilesDir(null), "benchmark_detail.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
