package com.example.thermohammer.engine

import com.example.thermohammer.engine.ai.AiDelegation
import com.example.thermohammer.engine.ai.AiProbeResult
import com.example.thermohammer.engine.ai.AiSettings
import com.example.thermohammer.engine.ai.AiTuning
import com.example.thermohammer.engine.ai.AiTuningRow
import com.example.thermohammer.engine.cpu.CpuTest
import com.example.thermohammer.engine.llm.LlmProbeResult
import com.example.thermohammer.engine.llm.LlmProbeRun
import com.example.thermohammer.engine.llm.LlmTrial
import com.example.thermohammer.engine.llm.LlmTuneReport
import com.example.thermohammer.engine.cpu.CpuTestRun
import com.example.thermohammer.engine.ux.QrResult
import org.junit.Assert.assertEquals
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

    @Test
    fun `report carries the LLM probe, the CPU's features and the GPU drivers' answers, and the score is the rates alone`() {
        val probe = LlmProbeResult(
            runs = listOf(LlmProbeRun("dotprod", "libbenchllm.so", 45.1, 21.0, 3.2), LlmProbeRun("i8mm", "libbenchllm_i8mm.so", 80.2, 21.5, 3.1, 0.9998)),
            promptTokens = 112, replyTokens = 16, threads = 6, batchThreads = 8, seconds = 17.4,
            cpuFeatures = "CPU features · asimddp yes · i8mm yes", vulkan = listOf("Vulkan · loader 1.3.0 · 1 device"), opencl = listOf("OpenCL · 1 platform"),
            chosen = "i8mm", why = "×1.33 over the dot-product build (prompt ×1.78, reply ×1.02)")
        val llm = LlmResult(listOf(LlmRun(LlmAccel.CPU, 44.9, 21.0, threads = 6, batchThreads = 8, kernels = "i8mm"), LlmRun(LlmAccel.GPU, 23.1, 21.2)),
            gpuName = "Mali-G1-Ultra MC12", freeGb = 9.1, largestModel = 8)
        val withProbe = llm.copy(probe = probe)
        assertEquals("the probe's text is not part of the score", llm.score, withProbe.score)
        assertEquals(llm.prefill, withProbe.prefill, 0.0)
        val text = BenchmarkReport.text(BenchmarkResult(emptyList(), 0, "Mali-G1-Ultra MC12", 0, llm = withProbe), "vivo X300 Pro", "16", "MT6993", "2.1", "2026-11-12 10:00")
        for (needle in listOf("LLM probe · 17.4 s · CPU kernels, a 112-token prompt on 8 threads and a 16-token reply on 6, unpinned · the CPU test ran in the i8mm build: ×1.33 over the dot-product build",
                "LLM CPU · prefill 44.90 tok/s · decode 21.00 tok/s · 6 threads (prompt 8) · i8mm kernels",
                "CPU i8mm · libbenchllm_i8mm.so · prefill 80.20 tok/s (×1.78)", "answers agree 0.9998", "USED FOR THE SCORE",
                "CPU features · asimddp yes · i8mm yes", "Vulkan · loader 1.3.0 · 1 device", "OpenCL · 1 platform")) {
            assertTrue("report lacks '$needle':\n$text", text.contains(needle))
        }
    }

    @Test
    fun `report lists the CPU's thread search with every trial and what pinning did`() {
        val tune = LlmTuneReport(listOf(7, 4, 5, 6), 4, decodePinned = true, promptPinned = false, decodeSkipped = false, promptSkipped = false,
            decodeThreads = 4, promptThreads = 8, mainCore = 7, seconds = 15.0,
            trials = listOf(LlmTrial(true, false, 8, 6, 0.6), LlmTrial(true, true, 4, 6, 0.4), LlmTrial(false, false, 8, 64, 1.0), LlmTrial(false, true, 4, 64, 1.1)),
            singleCoreThreads = mapOf(11 to 4, 12 to 5, 13 to 6))
        val llm = LlmResult(listOf(LlmRun(LlmAccel.CPU, 44.9, 21.0, threads = 4, batchThreads = 8, tune = tune)), gpuName = null, freeGb = 9.1, largestModel = 8)
        val text = BenchmarkReport.text(BenchmarkResult(emptyList(), 0, "?", 0, llm = llm), "d", "16", "s", "2.1", "t")
        for (needle in listOf("LLM CPU threads · tuned in 15.0 s · pinning tried on cores 7,4,5,6 (4 usable, fastest first)",
                "  decode tok/s · unpinned: 8 thr 10.00 · pinned: 4 thr 15.00 · used: pinned, 4 threads (×1.50 of the best unpinned)",
                "  prompt tok/s · unpinned: 8 thr 64.00 · pinned: 4 thr 58.18 · used: unpinned, 8 threads",
                "  pinning check · the calling thread sat alone on cpu7 · 3 threads of the process (the pools' workers and the caller) were each limited to one core (4,5,6)")) {
            assertTrue("report lacks '$needle':\n$text", text.contains(needle))
        }
    }

    @Test
    fun `report says which CPU settings the AI test tried and where LiteRT's threads went`() {
        val models = AiSettings.CPU_MODELS
        val settings = AiSettings.cpu(listOf(7, 4, 5, 6), 8, adpf = true)
        val rows = settings.mapIndexed { i, s ->
            AiTuningRow(s, models.associateWith { AiProbeResult(100.0 + 10 * i, 1.0, note = if (s.cpuPin.isNotEmpty()) "LiteRT started 3 threads: 3 of 3 inside the asked cores cpu4-7 (masks cpu4-7 ×3)" else null) })
        }
        val ai = AiResult(listOf(AiRun(AiTest.CLASSIFY, AiAccel.CPU, 110.0)), npuRuntime = null,
            tuning = listOf(AiTuning(AiAccel.CPU, "CPU · fast cores 7,4,5,6 first", models, rows, rows[1].setting, "default", 9.1)),
            delegation = listOf(AiDelegation(AiTest.DETECT, 0, 0, 0, emptyList(), logged = false)))
        val text = BenchmarkReport.text(BenchmarkResult(emptyList(), 0, "?", 0, ai = ai), "d", "16", "s", "2.1", "t")
        for (needle in listOf("AI tuning · CPU · CPU · fast cores 7,4,5,6 first · scored with threads on the 4 fastest cores (7,4,5,6)",
                "  performance hint session · classify 120.00/s ×1.20", "LiteRT started 3 threads: 3 of 3 inside the asked cores cpu4-7",
                "AI 2 OBJECT DETECTION · NPU delegation unknown")) {
            assertTrue("report lacks '$needle':\n$text", text.contains(needle))
        }
    }

    @Test
    fun `report lists what the AI tuning tried and what the NPU left to the CPU`() {
        val models = AiSettings.GPU_MODELS
        val rows = AiSettings.gpu().take(2).mapIndexed { i, setting ->
            AiTuningRow(setting, models.associateWith { AiProbeResult(100.0 + 30 * i, 0.99, 0.05, 0.02) })
        }
        val ai = AiResult(listOf(AiRun(AiTest.CLASSIFY, AiAccel.GPU, 130.0)), npuRuntime = "MediaTek NeuroPilot",
            tuning = listOf(AiTuning(AiAccel.GPU, "Mali-G1-Ultra MC12", models, rows, rows[1].setting, "CPU", 31.4, "OpenCL")),
            delegation = listOf(AiDelegation(AiTest.DETECT, 309, 317, 3, listOf("ARG_MAX", "EXP"))))
        val text = BenchmarkReport.text(BenchmarkResult(emptyList(), 0, "Mali-G1-Ultra MC12", 0, ai = ai), "vivo X300 Pro", "16", "MT6993", "2.1", "2026-11-12 10:00")
        for (needle in listOf("AI tuning · GPU · Mali-G1-Ultra MC12 · scored with OpenCL backend · compared with the CPU's outputs · 31.4 s · default ran on OpenCL",
                "  default · classify 100.00/s", "  OpenCL backend · classify 130.00/s ×1.30", "· used",
                "AI 2 OBJECT DETECTION · NPU took 309 of 317 ops in 3 partitions · not on the NPU: ARG_MAX, EXP")) {
            assertTrue("report lacks '$needle':\n$text", text.contains(needle))
        }
    }
}
