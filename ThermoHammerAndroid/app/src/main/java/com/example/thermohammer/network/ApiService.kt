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
    @SerializedName("gpuName") val gpuName: String? = null
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
