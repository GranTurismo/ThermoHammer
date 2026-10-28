package com.example.thermohammer.network

import com.example.thermohammer.engine.StampV2
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ── Data Models ────────────────────────────────────────────────────────────────

data class DeviceHammerStamp(
    @SerializedName("elapsedMs") val elapsedMs: Int,
    @SerializedName("score") val score: Long,
    @SerializedName("thermalState") val thermalState: Int // 0=Nominal 1=Fair 2=Serious 3=Critical
)

data class HammerPayload(
    @SerializedName("stamps") val stamps: List<DeviceHammerStamp>,
    @SerializedName("type") val type: Int, // 0=5min 1=15min 2=30min
    @SerializedName("deviceManufacturer") val deviceManufacturer: String,
    @SerializedName("deviceModel") val deviceModel: String,
    @SerializedName("os") val os: Int, // 2 = Android
    @SerializedName("testThreadingType") val testThreadingType: Int = 1, // 0 = Single, 1 = Multi
    @SerializedName("osVersion") val osVersion: String,
    @SerializedName("sessionId") val sessionId: Int,
    @SerializedName("hash") val hash: String,
    // ── Schema v2 — additive optional fields; absent on legacy clients ──
    @SerializedName("schemaVersion") val schemaVersion: Int? = null,
    @SerializedName("baselineScore") val baselineScore: Long? = null,      // calibration p95 IPS
    @SerializedName("deliveredCapacity") val deliveredCapacity: Double? = null, // AUC % — leaderboard metric
    @SerializedName("sustainedRatio") val sustainedRatio: Double? = null,
    @SerializedName("throttleOnsetSec") val throttleOnsetSec: Int? = null,
    @SerializedName("confidence") val confidence: Int? = null,
    @SerializedName("validityFlags") val validityFlags: Int? = null,       // 0 = verified run
    @SerializedName("socModel") val socModel: String? = null,
    @SerializedName("clusterTopology") val clusterTopology: String? = null,
    @SerializedName("governor") val governor: String? = null,
    @SerializedName("hashV2") val hashV2: String? = null,                  // hash over stamps + metadata
    // ── GPU channel ──
    @SerializedName("stressMode") val stressMode: Int? = null,             // 0=CPU 1=GPU 2=COMBINED
    @SerializedName("gpuBaselineFps") val gpuBaselineFps: Double? = null,
    @SerializedName("gpuDeliveredCapacity") val gpuDeliveredCapacity: Double? = null,
    @SerializedName("gpuSustainedRatio") val gpuSustainedRatio: Double? = null,
    @SerializedName("gpuName") val gpuName: String? = null,
    // ── OS build identity — lets the server compare OS updates fairly ──
    @SerializedName("osBuild") val osBuild: String? = null,
    @SerializedName("securityPatch") val securityPatch: String? = null
)

/** Convert a StampV2 (recorded truth) to the wire stamp the server hashes/stores. */
fun StampV2.toWire(): DeviceHammerStamp =
    DeviceHammerStamp(elapsedMs = elapsedMs, score = score, thermalState = thermalState)

fun List<StampV2>.toWire(): List<DeviceHammerStamp> = map { it.toWire() }

/** Convert a fetched wire stamp back into a minimal StampV2 for analysis/comparison. */
fun DeviceHammerStamp.toStampV2(): StampV2 =
    StampV2(tNs = elapsedMs * 1_000_000L, ipsTotal = score, thermalStatus = thermalState)

data class SessionResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("encryptionKey") val encryptionKey: String
)

data class HammerDto(
    @SerializedName("id") val id: Int,
    @SerializedName("stamps") val stamps: List<DeviceHammerStamp>?,
    @SerializedName("type") val type: Int,
    @SerializedName("deviceManufacturer") val deviceManufacturer: String,
    @SerializedName("deviceModel") val deviceModel: String,
    @SerializedName("os") val os: Int,
    @SerializedName("osVersion") val osVersion: String,
    @SerializedName("stabilityPercentage") val stabilityPercentage: Double,
    @SerializedName("testThreadingType") val testThreadingType: Int? = 1
)

// ── GPU benchmark ──────────────────────────────────────────────────────────────

/** A benchmark run as submitted. The server recomputes points and score from the fps. */
data class BenchmarkPayload(
    @SerializedName("sessionId") val sessionId: Int,
    @SerializedName("hash") val hash: String,                 // HMAC over canonical() with the session key
    @SerializedName("benchmarkVersion") val benchmarkVersion: Int,
    @SerializedName("deviceManufacturer") val deviceManufacturer: String,
    @SerializedName("deviceModel") val deviceModel: String,
    @SerializedName("os") val os: Int = 2,
    @SerializedName("osVersion") val osVersion: String,
    @SerializedName("osBuild") val osBuild: String? = null,
    @SerializedName("socModel") val socModel: String? = null,
    @SerializedName("gpuName") val gpuName: String,
    @SerializedName("test1Fps") val test1Fps: Double,
    @SerializedName("test1LowFps") val test1LowFps: Double,
    @SerializedName("test2Fps") val test2Fps: Double,
    @SerializedName("test2LowFps") val test2LowFps: Double,
    @SerializedName("test3Fps") val test3Fps: Double,             // 0 when the phone has no ray tracing
    @SerializedName("test3LowFps") val test3LowFps: Double,
    @SerializedName("rayTracing") val rayTracing: Boolean,         // Test 3 ran
    // UX: WebView scene complexities at ≥ 28 fps, JavaScript ops/s, QR scans/s
    @SerializedName("webLayout") val webLayout: Double,
    @SerializedName("webCanvas") val webCanvas: Double,
    @SerializedName("webCss") val webCss: Double,
    @SerializedName("webSvg") val webSvg: Double,
    @SerializedName("webJs") val webJs: Double,
    @SerializedName("qrRate") val qrRate: Double,                  // ZXing (scored)
    @SerializedName("mlkitQrRate") val mlkitQrRate: Double?,       // ML Kit (reported)
    // CPU: geometric-mean kernel ops/s of each test
    @SerializedName("cpuMath") val cpuMath: Double,
    @SerializedName("cpuData") val cpuData: Double,
    @SerializedName("cpuCrypto") val cpuCrypto: Double,
    @SerializedName("cpuMulti") val cpuMulti: Double,
    // UX 2–5: scroll px/s held (+ % dropped), gallery MP/s per format, editing MP/s, 4K decode fps per codec
    @SerializedName("scrollSpeed") val scrollSpeed: Double,
    @SerializedName("scrollDropped") val scrollDropped: Double,
    @SerializedName("galleryJpeg") val galleryJpeg: Double,
    @SerializedName("galleryWebp") val galleryWebp: Double,
    @SerializedName("galleryAvif") val galleryAvif: Double,
    @SerializedName("photoEdit") val photoEdit: Double,
    @SerializedName("videoAvc") val videoAvc: Double,
    @SerializedName("videoHevc") val videoHevc: Double,
    @SerializedName("videoVp9") val videoVp9: Double,
    @SerializedName("videoAv1") val videoAv1: Double,
    @SerializedName("video8kHevc") val video8kHevc: Double,          // 8K30 decode fps
    @SerializedName("video8kAv1") val video8kAv1: Double,
    @SerializedName("videoHardware") val videoHardware: Int,        // bit per clip decoded in hardware
    // UX 6: export fps per encoder (4K60 H.264, HEVC, AV1; 8K30 HEVC) and each export's quality, dB
    @SerializedName("exportAvc") val exportAvc: Double,
    @SerializedName("exportHevc") val exportHevc: Double,
    @SerializedName("exportAv1") val exportAv1: Double,
    @SerializedName("export8kHevc") val export8kHevc: Double,
    @SerializedName("exportAvcPsnr") val exportAvcPsnr: Double,
    @SerializedName("exportHevcPsnr") val exportHevcPsnr: Double,
    @SerializedName("exportAv1Psnr") val exportAv1Psnr: Double,
    @SerializedName("export8kHevcPsnr") val export8kHevcPsnr: Double,
    @SerializedName("exportHardware") val exportHardware: Int,      // bit per encoder in hardware
    // AI: inferences/s per model (classify, detect, segment, upscale, depth, sound) × accelerator (CPU, GPU, NPU)
    @SerializedName("aiClassifyCpu") val aiClassifyCpu: Double,
    @SerializedName("aiClassifyGpu") val aiClassifyGpu: Double,
    @SerializedName("aiClassifyNpu") val aiClassifyNpu: Double,
    @SerializedName("aiDetectCpu") val aiDetectCpu: Double,
    @SerializedName("aiDetectGpu") val aiDetectGpu: Double,
    @SerializedName("aiDetectNpu") val aiDetectNpu: Double,
    @SerializedName("aiSegmentCpu") val aiSegmentCpu: Double,
    @SerializedName("aiSegmentGpu") val aiSegmentGpu: Double,
    @SerializedName("aiSegmentNpu") val aiSegmentNpu: Double,
    @SerializedName("aiUpscaleCpu") val aiUpscaleCpu: Double,
    @SerializedName("aiUpscaleGpu") val aiUpscaleGpu: Double,
    @SerializedName("aiUpscaleNpu") val aiUpscaleNpu: Double,
    @SerializedName("aiDepthCpu") val aiDepthCpu: Double,
    @SerializedName("aiDepthGpu") val aiDepthGpu: Double,
    @SerializedName("aiDepthNpu") val aiDepthNpu: Double,
    @SerializedName("aiSoundCpu") val aiSoundCpu: Double,
    @SerializedName("aiSoundGpu") val aiSoundGpu: Double,
    @SerializedName("aiSoundNpu") val aiSoundNpu: Double,
    @SerializedName("aiNpuRuntime") val aiNpuRuntime: String?,
    // MEM: RAM GB/s (read, write, copy) and latency ns; storage MB/s (sequential) and 4 KB IOPS (random); SQLite per second
    @SerializedName("memRead") val memRead: Double,
    @SerializedName("memWrite") val memWrite: Double,
    @SerializedName("memCopy") val memCopy: Double,
    @SerializedName("memLatency") val memLatency: Double,
    @SerializedName("storageSeqRead") val storageSeqRead: Double,
    @SerializedName("storageSeqWrite") val storageSeqWrite: Double,
    @SerializedName("storageRandomRead") val storageRandomRead: Double,
    @SerializedName("storageRandomWrite") val storageRandomWrite: Double,
    @SerializedName("storageDirect") val storageDirect: Boolean,  // reported
    @SerializedName("dbInsert") val dbInsert: Double,
    @SerializedName("dbUpdate") val dbUpdate: Double,
    @SerializedName("dbQuery") val dbQuery: Double,
    // LLM: tokens/s reading the 512-token prompt and writing the reply, on the CPU and the GPU (0 = didn't run)
    @SerializedName("llmPrefillCpu") val llmPrefillCpu: Double,
    @SerializedName("llmDecodeCpu") val llmDecodeCpu: Double,
    @SerializedName("llmPrefillGpu") val llmPrefillGpu: Double,
    @SerializedName("llmDecodeGpu") val llmDecodeGpu: Double,
    @SerializedName("llmGpu") val llmGpu: String?,                 // the GPU it ran on (reported)
    @SerializedName("llmFreeGb") val llmFreeGb: Double,            // memory the app could take (reported)
    @SerializedName("validityFlags") val validityFlags: Int,
    @SerializedName("appVersion") val appVersion: String? = null
) {
    /** Exactly what the server re-derives and verifies: fps at 2 decimals, invariant format. */
    fun canonical(): String = listOf("bench", benchmarkVersion, deviceManufacturer, deviceModel, osVersion, gpuName,
        f2(test1Fps), f2(test1LowFps), f2(test2Fps), f2(test2LowFps), f2(test3Fps), f2(test3LowFps),
        if (rayTracing) 1 else 0, f2(webLayout), f2(webCanvas), f2(webCss), f2(webSvg), f2(webJs), f2(qrRate),
        f2(mlkitQrRate ?: 0.0), f2(cpuMath), f2(cpuData), f2(cpuCrypto), f2(cpuMulti), f2(scrollSpeed), f2(scrollDropped),
        f2(galleryJpeg), f2(galleryWebp), f2(galleryAvif), f2(photoEdit), f2(videoAvc), f2(videoHevc), f2(videoVp9),
        f2(videoAv1), f2(video8kHevc), f2(video8kAv1), videoHardware, f2(exportAvc), f2(exportHevc), f2(exportAv1),
        f2(export8kHevc), f2(exportAvcPsnr), f2(exportHevcPsnr), f2(exportAv1Psnr), f2(export8kHevcPsnr), exportHardware,
        f2(aiClassifyCpu), f2(aiClassifyGpu), f2(aiClassifyNpu), f2(aiDetectCpu), f2(aiDetectGpu), f2(aiDetectNpu), f2(aiSegmentCpu), f2(aiSegmentGpu), f2(aiSegmentNpu), f2(aiUpscaleCpu), f2(aiUpscaleGpu), f2(aiUpscaleNpu), f2(aiDepthCpu), f2(aiDepthGpu), f2(aiDepthNpu), f2(aiSoundCpu), f2(aiSoundGpu), f2(aiSoundNpu), aiNpuRuntime ?: "",
        f2(memRead), f2(memWrite), f2(memCopy), f2(memLatency), f2(storageSeqRead), f2(storageSeqWrite),
        f2(storageRandomRead), f2(storageRandomWrite), if (storageDirect) 1 else 0, f2(dbInsert), f2(dbUpdate), f2(dbQuery),
        f2(llmPrefillCpu), f2(llmDecodeCpu), f2(llmPrefillGpu), f2(llmDecodeGpu), llmGpu ?: "", f2(llmFreeGb),
        validityFlags).joinToString("|")

    companion object {
        fun f2(v: Double): String = "%.2f".format(java.util.Locale.US, v)
        /** Rounds to the 2 decimals that are hashed, so the JSON number and the hash agree. */
        fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}

/** GET /benchmark/model: one phone model's verified runs — what Phone Health compares a run with. */
data class ModelStatsDto(
    @SerializedName("deviceModel") val deviceModel: String,
    @SerializedName("runs") val runs: Int,
    @SerializedName("total") val total: ScoreSpreadDto?,
    @SerializedName("sections") val sections: Map<String, Int>?,
    @SerializedName("parts") val parts: Map<String, Int>?,
    @SerializedName("byOsVersion") val byOsVersion: List<OsVersionStatsDto>?
)

data class ScoreSpreadDto(
    @SerializedName("median") val median: Int,
    @SerializedName("p25") val p25: Int,
    @SerializedName("p75") val p75: Int,
    @SerializedName("best") val best: Int
)

data class OsVersionStatsDto(
    @SerializedName("osVersion") val osVersion: String,
    @SerializedName("runs") val runs: Int,
    @SerializedName("medianTotal") val medianTotal: Int
)

data class BenchmarkSubmitResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("score") val score: Int,
    @SerializedName("rank") val rank: Int
)

data class BenchmarkDto(
    @SerializedName("id") val id: Int,
    @SerializedName("deviceManufacturer") val deviceManufacturer: String,
    @SerializedName("deviceModel") val deviceModel: String,
    @SerializedName("socModel") val socModel: String?,
    @SerializedName("gpuName") val gpuName: String?,
    @SerializedName("os") val os: Int,
    @SerializedName("osVersion") val osVersion: String,
    @SerializedName("score") val score: Int,                      // GPU
    @SerializedName("uxScore") val uxScore: Int,
    @SerializedName("cpuScore") val cpuScore: Int,
    @SerializedName("aiScore") val aiScore: Int = 0,
    @SerializedName("memScore") val memScore: Int = 0,
    @SerializedName("llmScore") val llmScore: Int = 0,
    @SerializedName("totalScore") val totalScore: Int,
    @SerializedName("test1Score") val test1Score: Int,
    @SerializedName("test2Score") val test2Score: Int,
    @SerializedName("test3Score") val test3Score: Int,
    @SerializedName("test1Fps") val test1Fps: Double,
    @SerializedName("test2Fps") val test2Fps: Double,
    @SerializedName("test3Fps") val test3Fps: Double,
    @SerializedName("rayTracing") val rayTracing: Boolean,
    @SerializedName("runs") val runs: Int
)

/** One run opened from the leaderboard: the server sends every section as ready-to-show text. */
data class BenchmarkDetailDto(
    @SerializedName("id") val id: Int,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("deviceManufacturer") val deviceManufacturer: String,
    @SerializedName("deviceModel") val deviceModel: String,
    @SerializedName("socModel") val socModel: String?,
    @SerializedName("gpuName") val gpuName: String?,
    @SerializedName("os") val os: Int,
    @SerializedName("osVersion") val osVersion: String,
    @SerializedName("osBuild") val osBuild: String?,
    @SerializedName("appVersion") val appVersion: String?,
    @SerializedName("totalScore") val totalScore: Int,
    @SerializedName("sections") val sections: List<DetailSectionDto>
)

data class DetailSectionDto(
    @SerializedName("key") val key: String,
    @SerializedName("title") val title: String,
    @SerializedName("score") val score: Int,
    @SerializedName("groups") val groups: List<DetailGroupDto>
)

data class DetailGroupDto(
    @SerializedName("title") val title: String,
    @SerializedName("points") val points: Int?,
    @SerializedName("rows") val rows: List<DetailRowDto>
)

data class DetailRowDto(
    @SerializedName("label") val label: String,
    @SerializedName("value") val value: String
)

// ── Retrofit Interface ─────────────────────────────────────────────────────────

interface ThermoApi {
    @POST("session")
    suspend fun createSession(): SessionResponse

    @POST("hammer")
    suspend fun submitScore(@Body payload: HammerPayload)

    @GET("leaderboard")
    suspend fun fetchLeaderboard(): List<HammerDto>

    @GET("stamps/{id}")
    suspend fun fetchStamps(@Path("id") hammerId: Int): List<DeviceHammerStamp>

    @POST("benchmark")
    suspend fun submitBenchmark(@Body payload: BenchmarkPayload): BenchmarkSubmitResponse

    @GET("benchmark/leaderboard")
    suspend fun fetchBenchmarkLeaderboard(): List<BenchmarkDto>

    /** Every number of one leaderboard run; 404 once it is no longer a verified run. */
    @GET("benchmark/{id}")
    suspend fun fetchBenchmarkDetail(@Path("id") id: Int): BenchmarkDetailDto

    /** [os]: 1 iOS, 2 Android, 3 macOS — the server resolves [model] the way it stores submissions. */
    @GET("benchmark/model")
    suspend fun fetchModelStats(@Query("model") model: String, @Query("os") os: Int): ModelStatsDto
}

// ── Singleton Client ───────────────────────────────────────────────────────────

object ApiClient {
    private const val BASE_URL = "https://thapi.gtgroup.dev/"

    val api: ThermoApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ThermoApi::class.java)
    }
}
