package com.example.thermohammer.engine

import com.example.thermohammer.network.BenchmarkPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkScoreTest {

    /** A steady run at `fps` across the test's whole timeline. */
    private fun steady(test: BenchmarkTest, fps: Double): Triple<FloatArray, LongArray, Int> {
        val n = (test.seconds * fps).toInt()
        val dur = (1e9 / fps).toLong()
        return Triple(FloatArray(n) { (it / fps).toFloat() }, LongArray(n) { dur }, n)
    }

    @Test
    fun `points scale linearly with fps and the tests land on the same scale`() {
        // the Snapdragon 8 Elite reference: 129.5, 26.3 and 13.4 fps
        assertEquals(5996, BenchmarkScore.points(BenchmarkTest.LAKE_VALLEY, 129.5))
        assertEquals(5996, BenchmarkScore.points(BenchmarkTest.NEON_CITY, 26.3))
        assertEquals(6003, BenchmarkScore.points(BenchmarkTest.NEON_CITY_RT, 13.4))
        assertEquals(12000, BenchmarkScore.points(BenchmarkTest.NEON_CITY, 6000 * 2 / BenchmarkTest.NEON_CITY.weight))
        assertEquals(6000, BenchmarkScore.overall(6000, 6000, 6000))
    }

    @Test
    fun `raster is a geometric mean worth two thirds, ray tracing one third`() {
        assertEquals(6000, BenchmarkScore.overall(8000, 2000, 10000))      // (2·4000 + 10000) / 3
        assertEquals(4000, BenchmarkScore.overall(6000, 6000, 0))          // no ray tracing: Test 3 scores 0
        assertEquals(0, BenchmarkScore.overall(8000, 0, 9000))             // a raster test failed
    }

    @Test
    fun `a phone without ray tracing keeps its raster score at two thirds`() {
        val results = listOf(
            BenchmarkTestResult(BenchmarkTest.LAKE_VALLEY, avgFps = 120.0, lowFps = 100.0, frames = 1, stabilityPct = 100f,
                startTempC = null, endTempC = null, points = 6000),
            BenchmarkTestResult(BenchmarkTest.NEON_CITY, avgFps = 30.0, lowFps = 25.0, frames = 1, stabilityPct = 100f,
                startTempC = null, endTempC = null, points = 6000),
            BenchmarkTestResult.unsupported(BenchmarkTest.NEON_CITY_RT))
        assertEquals(4000, BenchmarkScore.overall(results))
        assertEquals(0, results[2].points)
    }

    @Test
    fun `summary skips warm-up frames and reports fps, lows and stability`() {
        val (start, dur, n) = steady(BenchmarkTest.NEON_CITY, 30.0)
        dur[0] = 2_000_000_000L                               // a slow first frame (shader compile) is ignored
        val r = BenchmarkScore.summarize(BenchmarkTest.NEON_CITY, start, dur, n, 35f, 41f)!!
        assertEquals(30.0, r.avgFps, 0.01)
        assertEquals(30.0, r.lowFps, 0.01)
        assertEquals(100f, r.stabilityPct, 0.01f)
        assertEquals(BenchmarkScore.points(BenchmarkTest.NEON_CITY, 30.0), r.points)
        assertEquals(n - (30 * BenchmarkScore.WARMUP_S).toInt(), r.frames)
    }

    @Test
    fun `a throttled second half shows in the stability and the 1 percent low`() {
        val test = BenchmarkTest.LAKE_VALLEY
        val starts = ArrayList<Float>(); val durs = ArrayList<Long>()
        var t = 0.0
        while (t < test.seconds) {
            val fps = if (t < 25) 120.0 else 60.0
            starts += t.toFloat(); durs += (1e9 / fps).toLong(); t += 1 / fps
        }
        val r = BenchmarkScore.summarize(test, starts.toFloatArray(), durs.toLongArray(), starts.size, null, null)!!
        assertTrue("stability ${r.stabilityPct}", r.stabilityPct in 49f..51f)
        assertEquals(60.0, r.lowFps, 0.5)
        assertEquals(4260 / 48.0, r.avgFps, 0.3)              // frames ÷ time: 23 s at 120 + 25 s at 60
    }

    private fun ux(web: Int, scroll: Int, gallery: Int, photo: Int, video: Int, qr: Int) = UxResult(
        web = null, scroll = null, gallery = null, photoMps = 0.0, video = null, qr = null,
        webPoints = web, scrollPoints = scroll, galleryPoints = gallery, photoPoints = photo, videoPoints = video, qrPoints = qr)

    @Test
    fun `each UX test scores 6000 at its reference`() {
        val ref = WebResult(UxScore.REF_LAYOUT, UxScore.REF_CANVAS, UxScore.REF_CSS, UxScore.REF_SVG, UxScore.REF_JS)
        assertEquals(6000, UxScore.webPoints(ref))
        assertEquals(12000, UxScore.webPoints(ref.copy(layout = ref.layout * 32)))   // 32^(1/5) = 2
        assertEquals(0, UxScore.webPoints(ref.copy(canvas = 0.0)))
        assertEquals(6000, UxScore.scrollPoints(UxScore.REF_SCROLL))
        assertEquals(6000, UxScore.galleryPoints(GalleryResult(UxScore.REF_JPEG, UxScore.REF_WEBP, UxScore.REF_AVIF)))
        assertEquals(4000, UxScore.galleryPoints(GalleryResult(UxScore.REF_JPEG, UxScore.REF_WEBP, 0.0)))  // no AVIF: a third off
        assertEquals(6000, UxScore.photoPoints(UxScore.REF_PHOTO))
        assertEquals(6000, UxScore.qrPoints(6000 / UxScore.QR_WEIGHT))
    }

    @Test
    fun `video averages its codecs, so a missing one costs a quarter`() {
        fun video(vararg fps: Double) = VideoResult(VideoCodec.entries.mapIndexed { i, c -> VideoCodecResult(c, fps[i], "dec", true) })
        val ref = UxScore.REF_VIDEO
        assertEquals(6000, UxScore.videoPoints(video(ref, ref, ref, ref)))
        assertEquals(4500, UxScore.videoPoints(video(ref, ref, ref, 0.0)))
        assertEquals(3000, UxScore.videoPoints(video(ref / 2, ref / 2, ref / 2, ref / 2)))   // real time
        val mixed = VideoResult(listOf(VideoCodecResult(VideoCodec.AVC, 200.0, "a", true),
            VideoCodecResult(VideoCodec.AV1, 30.0, "b", false), VideoCodecResult(VideoCodec.VP9, 0.0, null, false, "no decoder")))
        assertEquals(1 shl VideoCodec.AVC.ordinal, mixed.hardwareMask)
        assertEquals(0.0, mixed.fps(VideoCodec.HEVC), 0.0)
    }

    @Test
    fun `AI counts CPU, GPU and NPU a third each`() {
        fun result(npu: Boolean, gpu: Boolean = true) = AiResult(AiTest.entries.flatMap { t ->
            AiAccel.entries.map { a ->
                val on = (a != AiAccel.NPU || npu) && (a != AiAccel.GPU || gpu)
                AiRun(t, a, if (on) AiScore.ref(t, a) else 0.0, if (on) null else "not here")
            }
        }, npuRuntime = if (npu) "Qualcomm QNN" else null)
        assertEquals(6000, result(npu = true).accelScore(AiAccel.NPU))
        assertEquals(6000, result(npu = true).score)
        assertEquals(4000, result(npu = false).score)                  // no NPU: two thirds
        assertEquals(4000, result(npu = true, gpu = false).score)      // no GPU either: that third
        val oneModelShort = AiResult(result(npu = true).runs.map {
            if (it.test == AiTest.SOUND && it.accel == AiAccel.CPU) it.copy(ips = 0.0) else it }, "Qualcomm QNN")
        assertEquals(5000, oneModelShort.accelScore(AiAccel.CPU))      // a model short costs a sixth
        val total = BenchmarkResult(emptyList(), gpuScore = 5000, gpuName = "", validityFlags = 0, ai = result(npu = false))
        assertEquals(5000 + 4000, total.total)
    }

    @Test
    fun `UX is the geometric mean of its six tests and joins the total`() {
        assertEquals(6000, UxScore.overall(listOf(6000, 6000, 6000, 6000, 6000, 6000)))
        assertEquals(4000, UxScore.overall(listOf(8000, 2000, 8000, 2000, 8000, 2000)))
        assertEquals(0, UxScore.overall(listOf(6000, 6000, 6000, 0, 6000, 6000)))
        assertEquals(4000, ux(8000, 2000, 8000, 2000, 8000, 2000).score)
        val result = BenchmarkResult(emptyList(), gpuScore = 5000, gpuName = "", validityFlags = 0,
            ux = ux(8000, 2000, 8000, 2000, 8000, 2000))
        assertEquals(9000, result.total)
    }

    @Test
    fun `CPU is the geometric mean of its four tests and joins the total`() {
        val ref = mapOf(com.example.thermohammer.engine.cpu.CpuTest.MATH to CpuScore.REF_MATH,
            com.example.thermohammer.engine.cpu.CpuTest.DATA to CpuScore.REF_DATA,
            com.example.thermohammer.engine.cpu.CpuTest.CRYPTO to CpuScore.REF_CRYPTO,
            com.example.thermohammer.engine.cpu.CpuTest.MULTI to CpuScore.REF_MULTI)
        for ((t, r) in ref) assertEquals(6000, CpuScore.points(t, r))
        assertEquals(4000, CpuScore.overall(listOf(8000, 2000, 8000, 2000)))
        assertEquals(0, CpuScore.overall(listOf(8000, 0, 8000, 2000)))
        val cpu = CpuResult(ref.map { (t, r) -> com.example.thermohammer.engine.cpu.CpuTestRun(t, mapOf("K" to r)) })
        assertEquals(6000, cpu.score)
        val result = BenchmarkResult(emptyList(), gpuScore = 5000, gpuName = "", validityFlags = 0,
            ux = ux(8000, 2000, 8000, 2000, 8000, 2000), cpu = cpu)
        assertEquals(6000 + 5000 + 4000, result.total)
    }

    @Test
    fun `MEM is the geometric mean of RAM, storage and database at their references`() {
        val ram = RamResult(MemScore.REF_READ, MemScore.REF_WRITE, MemScore.REF_COPY, MemScore.REF_LATENCY)
        val storage = StorageResult(MemScore.REF_SEQ_READ, MemScore.REF_SEQ_WRITE, MemScore.REF_RANDOM_READ,
            MemScore.REF_RANDOM_WRITE, direct = true, fileMb = 512)
        val db = DbResult(MemScore.REF_INSERT, MemScore.REF_UPDATE, MemScore.REF_QUERY)
        val mem = MemResult(ram, storage, db)
        assertEquals(6000, mem.ramPoints)
        assertEquals(6000, mem.storagePoints)
        assertEquals(6000, mem.dbPoints)
        assertEquals(6000, mem.score)
        // latency: half the time is twice as good for that part — 6000 × 2^(1/4)
        assertEquals(7135, MemScore.ram(MemScore.REF_READ, MemScore.REF_WRITE, MemScore.REF_COPY, MemScore.REF_LATENCY / 2))
        assertEquals(0, MemScore.ram(MemScore.REF_READ, MemScore.REF_WRITE, MemScore.REF_COPY, 0.0))
        assertEquals(0, MemScore.storage(0.0, 1.0, 1.0, 1.0))
        assertEquals(4000, MemScore.overall(listOf(8000, 2000, 4000)))
        val result = BenchmarkResult(emptyList(), gpuScore = 5000, gpuName = "", validityFlags = 0, mem = mem)
        assertEquals(6000, result.memScore)
        assertEquals(11000, result.total)
    }

    @Test
    fun `too few frames cannot be scored`() {
        assertNull(BenchmarkScore.summarize(BenchmarkTest.NEON_CITY, FloatArray(5) { 3f + it }, LongArray(5) { 1L }, 5, null, null))
    }

    @Test
    fun `canonical string is what the server re-derives`() {
        val p = BenchmarkPayload(
            sessionId = 7, hash = "", benchmarkVersion = 2, deviceManufacturer = "Honor", deviceModel = "Honor 600 Pro",
            osVersion = "16", gpuName = "Adreno (TM) 830", test1Fps = BenchmarkPayload.round2(123.456),
            test1LowFps = 98.0, test2Fps = BenchmarkPayload.round2(29.994), test2LowFps = 24.5,
            test3Fps = 21.5, test3LowFps = 17.25, rayTracing = true,
            webLayout = 180.0, webCanvas = 1400.5, webCss = 700.0, webSvg = 900.0, webJs = 912.34,
            qrRate = 75.2, mlkitQrRate = null, cpuMath = 61.5, cpuData = 40.25, cpuCrypto = 58.0, cpuMulti = 301.75,
            scrollSpeed = 9100.5, scrollDropped = 3.4, galleryJpeg = 61.2, galleryWebp = 38.75, galleryAvif = 0.0,
            photoEdit = 118.3, videoAvc = 241.5, videoHevc = 180.25, videoVp9 = 0.0, videoAv1 = 33.1, videoHardware = 3,
            aiClassifyCpu = 301.5, aiClassifyGpu = 640.25, aiClassifyNpu = 4100.0, aiDetectCpu = 0.0, aiDetectGpu = 0.0, aiDetectNpu = 0.0, aiSegmentCpu = 0.0, aiSegmentGpu = 0.0, aiSegmentNpu = 0.0, aiUpscaleCpu = 0.0, aiUpscaleGpu = 0.0, aiUpscaleNpu = 0.0, aiDepthCpu = 0.0, aiDepthGpu = 0.0, aiDepthNpu = 0.0, aiSoundCpu = 0.0, aiSoundGpu = 0.0, aiSoundNpu = 0.0, aiNpuRuntime = "Qualcomm QNN",
            memRead = 52.31, memWrite = 41.0, memCopy = 26.5, memLatency = 142.75, storageSeqRead = 3120.4,
            storageSeqWrite = 2050.0, storageRandomRead = 61234.5, storageRandomWrite = 48000.0, storageDirect = true,
            dbInsert = 812.25, dbUpdate = 790.0, dbQuery = 5120.5,
            validityFlags = 0
        )
        assertEquals("bench|2|Honor|Honor 600 Pro|16|Adreno (TM) 830|123.46|98.00|29.99|24.50|21.50|17.25|1|" +
            "180.00|1400.50|700.00|900.00|912.34|75.20|0.00|61.50|40.25|58.00|301.75|" +
            "9100.50|3.40|61.20|38.75|0.00|118.30|241.50|180.25|0.00|33.10|3|" +
            "301.50|640.25|4100.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|Qualcomm QNN|" +
            "52.31|41.00|26.50|142.75|3120.40|2050.00|61234.50|48000.00|1|812.25|790.00|5120.50|0", p.canonical())
    }
}
