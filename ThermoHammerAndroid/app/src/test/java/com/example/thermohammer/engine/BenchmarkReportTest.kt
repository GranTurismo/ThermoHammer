package com.example.thermohammer.engine

import com.example.thermohammer.engine.cpu.CpuTest
import com.example.thermohammer.engine.cpu.CpuTestRun
import com.example.thermohammer.engine.ux.QrResult
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkReportTest {

    @Test
    fun `report carries every section's raw numbers and why Test 3 didn't run`() {
        val result = BenchmarkResult(
            tests = listOf(
                BenchmarkTestResult(BenchmarkTest.LAKE_VALLEY, avgFps = 121.37, lowFps = 98.2, frames = 5800,
                    stabilityPct = 91f, startTempC = 35f, endTempC = 42f, points = 6069),
                BenchmarkTestResult(BenchmarkTest.NEON_CITY, avgFps = 30.5, lowFps = 24.1, frames = 1750,
                    stabilityPct = 86f, startTempC = 42f, endTempC = 47f, points = 6100),
                BenchmarkTestResult.unsupported(BenchmarkTest.NEON_CITY_RT)),
            gpuScore = 4056, gpuName = "Adreno (TM) 830", validityFlags = 0,
            rayTracingProblem = "missing VK_KHR_ray_query",
            ux = UxResult(web = WebResult(3700.0, 2050.0, 1200.0, 1340.0, 3230.0), scroll = ScrollResult(9100.0, 3.4),
                gallery = GalleryResult(61.2, 38.75, 0.0), photoMps = 118.3,
                video = VideoResult(listOf(VideoCodecResult(VideoClip.AVC_4K, 241.5, "c2.qti.avc.decoder", true),
                    VideoCodecResult(VideoClip.AV1_4K, 0.0, null, false, "no AV1 10-BIT decoder for 3840×2160"),
                    VideoCodecResult(VideoClip.HEVC_8K, 71.25, "c2.qti.hevc.decoder", true))),
                export = ExportResult(listOf(ExportJobResult(ExportJob.HEVC_4K, 140.5, 41.2, "c2.qti.hevc.encoder", true),
                    ExportJobResult(ExportJob.AVC_4K, 150.0, 22.4, "c2.qti.avc.encoder", true),
                    ExportJobResult(ExportJob.AV1_4K, 0.0, 0.0, "c2.android.av1.encoder", false, "no hardware AV1 encoder"))),
                qr = QrResult(129.7, 600, 0, 210.4),
                webPoints = 6000, scrollPoints = 9100, galleryPoints = 4100, photoPoints = 5915, videoPoints = 3019,
                exportPoints = 3500, qrPoints = 5966),
            cpu = CpuResult(CpuTest.entries.map { CpuTestRun(it, mapOf("K" to CpuScore.ref(it))) }),
            mem = MemResult(RamResult(48.2, 39.5, 24.75, 151.3), StorageResult(2890.0, 1950.0, 58000.0, 47000.0, false, 512),
                DbResult(805.0, 790.0, 5100.0)),
            ai = AiResult(listOf(AiRun(AiTest.CLASSIFY, AiAccel.CPU, 845.04), AiRun(AiTest.CLASSIFY, AiAccel.GPU, 0.0, "Failed to compile model"),
                AiRun(AiTest.CLASSIFY, AiAccel.NPU, 0.0, "no NPU runtime for this chip")), npuRuntime = null,
                npuProblem = "no NPU runtime for this chip")
        )
        val text = BenchmarkReport.text(result, "Honor 600 Pro", "16 (BP4A)", "SM8750", "1.9-bench", "2026-10-25 14:00")
        for (needle in listOf("Honor 600 Pro", "Adreno (TM) 830", "TOTAL", "CPU 4 MULTI-CORE", "avg 121.37 fps",
                "not run: missing VK_KHR_ray_query", "layout 3700", "ZXing 129.70/s", "ML Kit 210.40/s",
                "UX 2 WEB SCROLLING · 9,100 pts · held 9100 px/s, 3.4% frames dropped", "AVIF 0.00 MP/s",
                "UX 4 PHOTO EDITING · 5,915 pts · 118.30 MP/s", "H.264 4K 241.50 fps (HW c2.qti.avc.decoder)",
                "AV1 10-BIT 4K 0 (no AV1 10-BIT decoder for 3840×2160)", "UX 7 QR SCANNING", "+ AI ",
                "plays H.264 4K30✓ 4K60✓ 4K120✓ 4K240– 8K30· 8K60· 8K120· 8K240·",
                "plays HEVC  4K30✗ 4K60✗ 4K120✗ 4K240✗ 8K30✓ 8K60✓ 8K120– 8K240–",
                "UX 6 VIDEO EXPORT · 3,500 pts · HEVC 4K 140.50 fps 41.2 dB (HW c2.qti.hevc.encoder)",
                "H.264 4K 150.00 fps 22.4 dB LOW QUALITY", "AV1 4K 0 (no hardware AV1 encoder)",
                "exports H.264 4K30✗ 4K60✗", "exports HEVC  4K30✓ 4K60✓ 4K120✓ 4K240– 8K30✗",
                "NPU not run: no NPU runtime for this chip", "+ MEM ",
                "MEM 1 RAM", "read 48.20, write 39.50, copy 24.75 GB/s · latency 151.30 ns",
                "seq read 2890, seq write 1950 MB/s · random read 58000, random write 47000 IOPS · 512 MB file, cached I/O",
                "MEM 3 DATABASE", "insert 805, update 790 tx/s · query 5100/s", "AI 1 IMAGE CLASSIFICATION · CPU 845.04/s, GPU 0 (Failed to compile model)")) {
            assertTrue("report lacks '$needle':\n$text", text.contains(needle))
        }
    }
}
