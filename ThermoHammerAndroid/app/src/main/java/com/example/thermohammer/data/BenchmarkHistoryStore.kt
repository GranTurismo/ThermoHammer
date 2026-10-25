package com.example.thermohammer.data

import android.content.Context
import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiTest
import com.example.thermohammer.engine.BenchmarkResult
import com.example.thermohammer.engine.BenchmarkScore
import com.example.thermohammer.engine.BenchmarkTest
import com.example.thermohammer.engine.VideoCodec
import com.example.thermohammer.engine.cpu.CpuTest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** One finished benchmark (CPU + MEM + GPU + UX + AI) as kept on the device (and later submitted from); `score` = total. */
data class BenchmarkRecord(
    val id: String,
    val timestamp: Long,
    val version: Int,
    val score: Int,
    val test1Fps: Double,
    val test1LowFps: Double,
    val test1Points: Int,
    val test2Fps: Double,
    val test2LowFps: Double,
    val test2Points: Int,
    val test3Fps: Double = 0.0,           // 0 where the phone has no ray tracing
    val test3LowFps: Double = 0.0,
    val test3Points: Int = 0,
    val rayTracing: Boolean = false,      // Test 3 ran
    val gpuScore: Int = 0,
    val uxScore: Int = 0,
    val cpuScore: Int = 0,
    val cpuMath: Double = 0.0,            // CPU tests: geometric-mean kernel ops/s
    val cpuData: Double = 0.0,
    val cpuCrypto: Double = 0.0,
    val cpuMulti: Double = 0.0,
    val webLayout: Double = 0.0,
    val webCanvas: Double = 0.0,
    val webCss: Double = 0.0,
    val webSvg: Double = 0.0,
    val webJs: Double = 0.0,
    val scrollSpeed: Double = 0.0,        // feed px/s held with ≤ 10 % dropped frames
    val scrollDropped: Double = 0.0,      // % of frames dropped over the scroll test (reported)
    val galleryJpeg: Double = 0.0,        // photo gallery MP/s per format
    val galleryWebp: Double = 0.0,
    val galleryAvif: Double = 0.0,
    val photoEdit: Double = 0.0,          // MP/s through the editing chain
    val videoAvc: Double = 0.0,           // 4K60 decode fps per codec, 0 = unsupported
    val videoHevc: Double = 0.0,
    val videoVp9: Double = 0.0,
    val videoAv1: Double = 0.0,
    val videoHardware: Int = 0,           // bit per VideoCodec decoded in hardware (reported)
    val aiScore: Int = 0,
    // AI: inferences/s of each model on each accelerator (0 = it didn't run there)
    val aiClassifyCpu: Double = 0.0,
    val aiClassifyGpu: Double = 0.0,
    val aiClassifyNpu: Double = 0.0,
    val aiDetectCpu: Double = 0.0,
    val aiDetectGpu: Double = 0.0,
    val aiDetectNpu: Double = 0.0,
    val aiSegmentCpu: Double = 0.0,
    val aiSegmentGpu: Double = 0.0,
    val aiSegmentNpu: Double = 0.0,
    val aiUpscaleCpu: Double = 0.0,
    val aiUpscaleGpu: Double = 0.0,
    val aiUpscaleNpu: Double = 0.0,
    val aiDepthCpu: Double = 0.0,
    val aiDepthGpu: Double = 0.0,
    val aiDepthNpu: Double = 0.0,
    val aiSoundCpu: Double = 0.0,
    val aiSoundGpu: Double = 0.0,
    val aiSoundNpu: Double = 0.0,
    val aiNpuRuntime: String? = null,
    val memScore: Int = 0,
    // MEM: RAM GB/s and ns, storage MB/s and IOPS, database operations/s
    val memRead: Double = 0.0,
    val memWrite: Double = 0.0,
    val memCopy: Double = 0.0,
    val memLatency: Double = 0.0,
    val storageSeqRead: Double = 0.0,
    val storageSeqWrite: Double = 0.0,
    val storageRandomRead: Double = 0.0,
    val storageRandomWrite: Double = 0.0,
    val storageDirect: Boolean = false,   // reads bypassed the page cache (reported)
    val dbInsert: Double = 0.0,
    val dbUpdate: Double = 0.0,
    val dbQuery: Double = 0.0,
    val qrRate: Double = 0.0,             // ZXing decodes/s
    val mlkitRate: Double? = null,        // ML Kit scans/s (reported, not scored)
    val gpuName: String,
    val validityFlags: Int,
    val submitted: Boolean = false
) {
    companion object {
        fun of(result: BenchmarkResult, id: String, timestamp: Long): BenchmarkRecord {
            fun of(t: BenchmarkTest) = result.tests.first { it.test == t }
            val t1 = of(BenchmarkTest.LAKE_VALLEY)
            val t2 = of(BenchmarkTest.NEON_CITY)
            val t3 = of(BenchmarkTest.NEON_CITY_RT)
            val ux = result.ux
            val web = ux?.web
            val qr = ux?.qr
            val video = ux?.video
            val ai = result.ai
            val mem = result.mem
            return BenchmarkRecord(id = id, timestamp = timestamp, version = BenchmarkScore.VERSION, score = result.total,
                gpuScore = result.gpuScore, uxScore = result.uxScore, cpuScore = result.cpuScore,
                cpuMath = result.cpu?.value(CpuTest.MATH) ?: 0.0, cpuData = result.cpu?.value(CpuTest.DATA) ?: 0.0,
                cpuCrypto = result.cpu?.value(CpuTest.CRYPTO) ?: 0.0, cpuMulti = result.cpu?.value(CpuTest.MULTI) ?: 0.0,
                webLayout = web?.layout ?: 0.0, webCanvas = web?.canvas ?: 0.0, webCss = web?.css ?: 0.0,
                webSvg = web?.svg ?: 0.0, webJs = web?.js ?: 0.0, qrRate = qr?.zxingRate ?: 0.0, mlkitRate = qr?.mlkitRate,
                scrollSpeed = ux?.scroll?.speed ?: 0.0, scrollDropped = ux?.scroll?.droppedPct ?: 0.0,
                galleryJpeg = ux?.gallery?.jpeg ?: 0.0, galleryWebp = ux?.gallery?.webp ?: 0.0,
                galleryAvif = ux?.gallery?.avif ?: 0.0, photoEdit = ux?.photoMps ?: 0.0,
                videoAvc = video?.fps(VideoCodec.AVC) ?: 0.0, videoHevc = video?.fps(VideoCodec.HEVC) ?: 0.0,
                videoVp9 = video?.fps(VideoCodec.VP9) ?: 0.0, videoAv1 = video?.fps(VideoCodec.AV1) ?: 0.0,
                videoHardware = video?.hardwareMask ?: 0,
                aiScore = result.aiScore, aiNpuRuntime = ai?.npuRuntime,
                aiClassifyCpu = ai?.ips(AiTest.CLASSIFY, AiAccel.CPU) ?: 0.0, aiClassifyGpu = ai?.ips(AiTest.CLASSIFY, AiAccel.GPU) ?: 0.0, aiClassifyNpu = ai?.ips(AiTest.CLASSIFY, AiAccel.NPU) ?: 0.0, aiDetectCpu = ai?.ips(AiTest.DETECT, AiAccel.CPU) ?: 0.0, aiDetectGpu = ai?.ips(AiTest.DETECT, AiAccel.GPU) ?: 0.0, aiDetectNpu = ai?.ips(AiTest.DETECT, AiAccel.NPU) ?: 0.0, aiSegmentCpu = ai?.ips(AiTest.SEGMENT, AiAccel.CPU) ?: 0.0, aiSegmentGpu = ai?.ips(AiTest.SEGMENT, AiAccel.GPU) ?: 0.0, aiSegmentNpu = ai?.ips(AiTest.SEGMENT, AiAccel.NPU) ?: 0.0, aiUpscaleCpu = ai?.ips(AiTest.UPSCALE, AiAccel.CPU) ?: 0.0, aiUpscaleGpu = ai?.ips(AiTest.UPSCALE, AiAccel.GPU) ?: 0.0, aiUpscaleNpu = ai?.ips(AiTest.UPSCALE, AiAccel.NPU) ?: 0.0, aiDepthCpu = ai?.ips(AiTest.DEPTH, AiAccel.CPU) ?: 0.0, aiDepthGpu = ai?.ips(AiTest.DEPTH, AiAccel.GPU) ?: 0.0, aiDepthNpu = ai?.ips(AiTest.DEPTH, AiAccel.NPU) ?: 0.0, aiSoundCpu = ai?.ips(AiTest.SOUND, AiAccel.CPU) ?: 0.0, aiSoundGpu = ai?.ips(AiTest.SOUND, AiAccel.GPU) ?: 0.0, aiSoundNpu = ai?.ips(AiTest.SOUND, AiAccel.NPU) ?: 0.0,
                memScore = result.memScore, memRead = mem?.ram?.read ?: 0.0, memWrite = mem?.ram?.write ?: 0.0,
                memCopy = mem?.ram?.copy ?: 0.0, memLatency = mem?.ram?.latencyNs ?: 0.0,
                storageSeqRead = mem?.storage?.seqRead ?: 0.0, storageSeqWrite = mem?.storage?.seqWrite ?: 0.0,
                storageRandomRead = mem?.storage?.randomRead ?: 0.0, storageRandomWrite = mem?.storage?.randomWrite ?: 0.0,
                storageDirect = mem?.storage?.direct ?: false,
                dbInsert = mem?.db?.insert ?: 0.0, dbUpdate = mem?.db?.update ?: 0.0, dbQuery = mem?.db?.query ?: 0.0,
                test1Fps = t1.avgFps, test1LowFps = t1.lowFps, test1Points = t1.points,
                test2Fps = t2.avgFps, test2LowFps = t2.lowFps, test2Points = t2.points,
                test3Fps = t3.avgFps, test3LowFps = t3.lowFps, test3Points = t3.points, rayTracing = t3.supported,
                gpuName = result.gpuName, validityFlags = result.validityFlags)
        }
    }
}

/**
 * On-device benchmark history: the latest runs, so the home card can show this phone's best and
 * offer to submit a run made offline. Corrupt or unreadable data degrades to "no history".
 */
class BenchmarkHistoryStore(context: Context) {
    private val file = File(context.filesDir, "benchmark_history.json")
    private val gson = Gson()

    @Synchronized
    fun all(): List<BenchmarkRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<BenchmarkRecord>>() {}.type
            val loaded: List<BenchmarkRecord> = gson.fromJson(file.readText(), type) ?: emptyList()
            // Gson bypasses Kotlin null-safety: drop rows that decoded without their essentials.
            loaded.filter { (it.id as String?) != null && (it.gpuName as String?) != null }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Runs of the current benchmark version only — older versions scored different work. */
    fun current(): List<BenchmarkRecord> = all().filter { it.version == BenchmarkScore.VERSION }

    fun add(record: BenchmarkRecord) = write((all() + record).takeLast(MAX_RECORDS))

    fun markSubmitted(id: String) = write(all().map { if (it.id == id) it.copy(submitted = true) else it })

    @Synchronized
    private fun write(records: List<BenchmarkRecord>) {
        try {
            // Write-then-rename so a crash mid-write cannot leave half a file behind.
            val tmp = File(file.parentFile, "benchmark_history.json.tmp")
            tmp.writeText(gson.toJson(records))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) { /* history is best-effort; never break a finished run */ }
    }

    private companion object {
        const val MAX_RECORDS = 30
    }
}
