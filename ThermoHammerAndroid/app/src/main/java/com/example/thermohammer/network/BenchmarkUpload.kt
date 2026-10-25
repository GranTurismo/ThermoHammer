package com.example.thermohammer.network

import android.content.Context
import android.os.Build
import com.example.thermohammer.data.BenchmarkRecord

/** Submits a stored benchmark run: fresh session, payload hashed with its key, server-scored. */
object BenchmarkUpload {

    suspend fun submit(context: Context, record: BenchmarkRecord, socModel: String?): BenchmarkSubmitResponse {
        val session = ApiClient.api.createSession()
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { null }
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() }
        // same identity the stress leaderboard uses (StressEngine.getDeviceModel)
        val model = if (Build.MODEL.startsWith(manufacturer, ignoreCase = true)) Build.MODEL else "$manufacturer ${Build.MODEL}"
        val unsigned = BenchmarkPayload(
            sessionId = session.id,
            hash = "",
            benchmarkVersion = record.version,
            deviceManufacturer = manufacturer,
            deviceModel = model,
            osVersion = Build.VERSION.RELEASE,
            osBuild = Build.ID.ifBlank { null },
            socModel = socModel?.ifBlank { null },
            gpuName = record.gpuName,
            test1Fps = BenchmarkPayload.round2(record.test1Fps),
            test1LowFps = BenchmarkPayload.round2(record.test1LowFps),
            test2Fps = BenchmarkPayload.round2(record.test2Fps),
            test2LowFps = BenchmarkPayload.round2(record.test2LowFps),
            test3Fps = BenchmarkPayload.round2(record.test3Fps),
            test3LowFps = BenchmarkPayload.round2(record.test3LowFps),
            rayTracing = record.rayTracing,
            webLayout = BenchmarkPayload.round2(record.webLayout),
            webCanvas = BenchmarkPayload.round2(record.webCanvas),
            webCss = BenchmarkPayload.round2(record.webCss),
            webSvg = BenchmarkPayload.round2(record.webSvg),
            webJs = BenchmarkPayload.round2(record.webJs),
            qrRate = BenchmarkPayload.round2(record.qrRate),
            mlkitQrRate = record.mlkitRate?.let { BenchmarkPayload.round2(it) },
            cpuMath = BenchmarkPayload.round2(record.cpuMath),
            cpuData = BenchmarkPayload.round2(record.cpuData),
            cpuCrypto = BenchmarkPayload.round2(record.cpuCrypto),
            cpuMulti = BenchmarkPayload.round2(record.cpuMulti),
            scrollSpeed = BenchmarkPayload.round2(record.scrollSpeed),
            scrollDropped = BenchmarkPayload.round2(record.scrollDropped),
            galleryJpeg = BenchmarkPayload.round2(record.galleryJpeg),
            galleryWebp = BenchmarkPayload.round2(record.galleryWebp),
            galleryAvif = BenchmarkPayload.round2(record.galleryAvif),
            photoEdit = BenchmarkPayload.round2(record.photoEdit),
            videoAvc = BenchmarkPayload.round2(record.videoAvc),
            videoHevc = BenchmarkPayload.round2(record.videoHevc),
            videoVp9 = BenchmarkPayload.round2(record.videoVp9),
            videoAv1 = BenchmarkPayload.round2(record.videoAv1),
            videoHardware = record.videoHardware,
            aiClassifyCpu = BenchmarkPayload.round2(record.aiClassifyCpu),
            aiClassifyGpu = BenchmarkPayload.round2(record.aiClassifyGpu),
            aiClassifyNpu = BenchmarkPayload.round2(record.aiClassifyNpu),
            aiDetectCpu = BenchmarkPayload.round2(record.aiDetectCpu),
            aiDetectGpu = BenchmarkPayload.round2(record.aiDetectGpu),
            aiDetectNpu = BenchmarkPayload.round2(record.aiDetectNpu),
            aiSegmentCpu = BenchmarkPayload.round2(record.aiSegmentCpu),
            aiSegmentGpu = BenchmarkPayload.round2(record.aiSegmentGpu),
            aiSegmentNpu = BenchmarkPayload.round2(record.aiSegmentNpu),
            aiUpscaleCpu = BenchmarkPayload.round2(record.aiUpscaleCpu),
            aiUpscaleGpu = BenchmarkPayload.round2(record.aiUpscaleGpu),
            aiUpscaleNpu = BenchmarkPayload.round2(record.aiUpscaleNpu),
            aiDepthCpu = BenchmarkPayload.round2(record.aiDepthCpu),
            aiDepthGpu = BenchmarkPayload.round2(record.aiDepthGpu),
            aiDepthNpu = BenchmarkPayload.round2(record.aiDepthNpu),
            aiSoundCpu = BenchmarkPayload.round2(record.aiSoundCpu),
            aiSoundGpu = BenchmarkPayload.round2(record.aiSoundGpu),
            aiSoundNpu = BenchmarkPayload.round2(record.aiSoundNpu),
            aiNpuRuntime = record.aiNpuRuntime,
            memRead = BenchmarkPayload.round2(record.memRead),
            memWrite = BenchmarkPayload.round2(record.memWrite),
            memCopy = BenchmarkPayload.round2(record.memCopy),
            memLatency = BenchmarkPayload.round2(record.memLatency),
            storageSeqRead = BenchmarkPayload.round2(record.storageSeqRead),
            storageSeqWrite = BenchmarkPayload.round2(record.storageSeqWrite),
            storageRandomRead = BenchmarkPayload.round2(record.storageRandomRead),
            storageRandomWrite = BenchmarkPayload.round2(record.storageRandomWrite),
            storageDirect = record.storageDirect,
            dbInsert = BenchmarkPayload.round2(record.dbInsert),
            dbUpdate = BenchmarkPayload.round2(record.dbUpdate),
            dbQuery = BenchmarkPayload.round2(record.dbQuery),
            validityFlags = record.validityFlags,
            appVersion = appVersion
        )
        val payload = unsigned.copy(hash = ThermoHasher.computeBenchmarkHash(session.encryptionKey, unsigned.canonical()))
        return ApiClient.api.submitBenchmark(payload)
    }
}
