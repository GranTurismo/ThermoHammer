import XCTest
@testable import CpuThrottlingTest

/** What a run sends and keeps: the signed canonical text the server re-derives, and records saved by earlier versions. */
final class BenchmarkPayloadTests: XCTestCase {

    /// Android's BenchmarkScoreTest `canonical string is what the server re-derives`, field for field.
    private func payload() -> BenchmarkPayload {
        BenchmarkPayload(
            sessionId: 7, hash: "", benchmarkVersion: 5, deviceManufacturer: "Honor", deviceModel: "Honor 600 Pro",
            osVersion: "16", osBuild: nil, socModel: nil, gpuName: "Adreno (TM) 830",
            test1Fps: BenchmarkScore.round2(123.456), test1LowFps: 98.0, test2Fps: BenchmarkScore.round2(29.994), test2LowFps: 24.5,
            test3Fps: 21.5, test3LowFps: 17.25, rayTracing: true,
            webLayout: 180.0, webCanvas: 1400.5, webCss: 700.0, webSvg: 900.0, webJs: 912.34,
            qrRate: 75.2, mlkitQrRate: nil, cpuMath: 61.5, cpuData: 40.25, cpuCrypto: 58.0, cpuMulti: 301.75,
            scrollSpeed: 9100.5, scrollDropped: 3.4, galleryJpeg: 61.2, galleryWebp: 38.75, galleryAvif: 0.0,
            photoEdit: 118.3, videoAvc: 241.5, videoHevc: 180.25, videoVp9: 0.0, videoAv1: 33.1,
            video8kHevc: 64.5, video8kAv1: 0.0, videoHardware: 19,
            exportAvc: 150.25, exportHevc: 140.0, exportAv1: 0.0, export8kHevc: 31.5,
            exportAvcPsnr: 39.87, exportHevcPsnr: 41.2, exportAv1Psnr: 0.0, export8kHevcPsnr: 40.05, exportHardware: 11,
            aiClassifyCpu: 301.5, aiClassifyGpu: 640.25, aiClassifyNpu: 4100.0, aiDetectCpu: 0, aiDetectGpu: 0, aiDetectNpu: 0,
            aiSegmentCpu: 0, aiSegmentGpu: 0, aiSegmentNpu: 0, aiUpscaleCpu: 0, aiUpscaleGpu: 0, aiUpscaleNpu: 0,
            aiDepthCpu: 0, aiDepthGpu: 0, aiDepthNpu: 0, aiSoundCpu: 0, aiSoundGpu: 0, aiSoundNpu: 0, aiNpuRuntime: "Qualcomm QNN",
            memRead: 52.31, memWrite: 41.0, memCopy: 26.5, memLatency: 142.75, storageSeqRead: 3120.4,
            storageSeqWrite: 2050.0, storageRandomRead: 61234.5, storageRandomWrite: 48000.0, storageDirect: true,
            dbInsert: 812.25, dbUpdate: 790.0, dbQuery: 5120.5,
            validityFlags: 0, appVersion: "2.2.0")
    }

    /// Version 5 has no LLM test: its nine fields keep their place in the server's layout (after the database's, before the
    /// validity flags), always 0 or empty — four rates, the GPU, free memory, the NPU's two rates and its engine.
    func testCanonicalStringIsWhatTheServerReDerives() {
        XCTAssertEqual(payload().canonical(), "bench|5|Honor|Honor 600 Pro|16|Adreno (TM) 830|123.46|98.00|29.99|24.50|21.50|17.25|1|" +
            "180.00|1400.50|700.00|900.00|912.34|75.20|0.00|61.50|40.25|58.00|301.75|" +
            "9100.50|3.40|61.20|38.75|0.00|118.30|241.50|180.25|0.00|33.10|64.50|0.00|19|" +
            "150.25|140.00|0.00|31.50|39.87|41.20|0.00|40.05|11|" +
            "301.50|640.25|4100.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|Qualcomm QNN|" +
            "52.31|41.00|26.50|142.75|3120.40|2050.00|61234.50|48000.00|1|812.25|790.00|5120.50|" +
            "0.00|0.00|0.00|0.00||0.00|0.00|0.00||0")
        // the JSON carries them by the server's names
        let json = try! JSONSerialization.jsonObject(with: JSONEncoder().encode(payload())) as! [String: Any]
        for key in ["llmPrefillCpu", "llmDecodeCpu", "llmPrefillGpu", "llmDecodeGpu", "llmFreeGb", "llmPrefillNpu", "llmDecodeNpu"] {
            XCTAssertEqual(json[key] as? Double, 0, key)
        }
        XCTAssertEqual(json["llmGpu"] as? String, "")
        XCTAssertEqual(json["llmNpu"] as? String, "")
    }

    /// History saved by 2.1.x and the version 5 drafts has an "llm" entry: it still loads, and no LLM score comes of it.
    func testARecordWithAnOldLlmEntryStillLoads() throws {
        var dict = try JSONSerialization.jsonObject(with: JSONEncoder().encode(BenchmarkRecord(BenchmarkResult()))) as! [String: Any]
        dict["llm"] = ["prefillCpu": 118.4, "decodeCpu": 21.35, "prefillGpu": 402, "decodeGpu": 19.1, "gpu": "Apple A18 Pro GPU",
                       "freeGb": 5.5, "prefillNpu": 0, "decodeNpu": 0]
        let old = try JSONDecoder().decode(BenchmarkRecord.self, from: JSONSerialization.data(withJSONObject: dict))
        XCTAssertNil(old.sectionScores["llm"])
    }
}
