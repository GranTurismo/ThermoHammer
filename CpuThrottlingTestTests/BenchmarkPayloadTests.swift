import XCTest
@testable import CpuThrottlingTest

/** What a run sends and keeps: the signed canonical text the server re-derives, and the LLM's NPU result in a record. */
final class BenchmarkPayloadTests: XCTestCase {

    /// Android's BenchmarkScoreTest `canonical string is what the server re-derives`, field for field.
    private func payload(prefillCpu: Double = 0, decodeCpu: Double = 0, prefillGpu: Double = 0, decodeGpu: Double = 0, llmGpu: String? = nil,
                         freeGb: Double = 0, prefillNpu: Double = 0, decodeNpu: Double = 0, llmNpu: String? = nil) -> BenchmarkPayload {
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
            llmPrefillCpu: prefillCpu, llmDecodeCpu: decodeCpu, llmPrefillGpu: prefillGpu, llmDecodeGpu: decodeGpu, llmGpu: llmGpu,
            llmFreeGb: freeGb, llmPrefillNpu: prefillNpu, llmDecodeNpu: decodeNpu, llmNpu: llmNpu,
            validityFlags: 0, appVersion: "2.2.0")
    }

    func testCanonicalStringIsWhatTheServerReDerives() {
        XCTAssertEqual(payload().canonical(), "bench|5|Honor|Honor 600 Pro|16|Adreno (TM) 830|123.46|98.00|29.99|24.50|21.50|17.25|1|" +
            "180.00|1400.50|700.00|900.00|912.34|75.20|0.00|61.50|40.25|58.00|301.75|" +
            "9100.50|3.40|61.20|38.75|0.00|118.30|241.50|180.25|0.00|33.10|64.50|0.00|19|" +
            "150.25|140.00|0.00|31.50|39.87|41.20|0.00|40.05|11|" +
            "301.50|640.25|4100.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|Qualcomm QNN|" +
            "52.31|41.00|26.50|142.75|3120.40|2050.00|61234.50|48000.00|1|812.25|790.00|5120.50|" +
            "0.00|0.00|0.00|0.00||0.00|0.00|0.00||0")
        // the NPU's three fields after llmFreeGb, before the validity flags
        let llm = payload(prefillCpu: 118.4, decodeCpu: 21.35, prefillGpu: 402.0, decodeGpu: 19.1, llmGpu: "QUALCOMM Adreno(TM) 830",
                          freeGb: 9.42, prefillNpu: 5723.514, decodeNpu: 52.86, llmNpu: "LiteRT-LM 0.19.1 · Gemma3-1B-IT_q4_ekv1280_sm8750")
        XCTAssertTrue(llm.canonical().hasSuffix("|5120.50|118.40|21.35|402.00|19.10|QUALCOMM Adreno(TM) 830|9.42|" +
            "5723.51|52.86|LiteRT-LM 0.19.1 · Gemma3-1B-IT_q4_ekv1280_sm8750|0"), llm.canonical())
        // the JSON carries the same three fields by the server's names
        let json = try! JSONSerialization.jsonObject(with: JSONEncoder().encode(llm)) as! [String: Any]
        XCTAssertEqual(json["llmPrefillNpu"] as? Double, 5723.514)
        XCTAssertEqual(json["llmDecodeNpu"] as? Double, 52.86)
        XCTAssertEqual(json["llmNpu"] as? String, "LiteRT-LM 0.19.1 · Gemma3-1B-IT_q4_ekv1280_sm8750")
    }

    private func llm(npu: LlmNpuRun?) -> BenchmarkResult {
        var r = BenchmarkResult()
        r.llm = LlmResult(runs: [LlmRun(accel: .cpu, prefill: LlmScore.refCpuPrefill, decode: LlmScore.refCpuDecode)],
                          gpuName: nil, freeGb: 5.123, largestModel: 4, npu: npu)
        return r
    }

    func testTheRecordKeepsTheNpuResultAsItCounts() throws {
        let right = BenchmarkRecord(llm(npu: LlmNpuRun(prefill: LlmScore.refNpuPrefill, decode: LlmScore.refNpuDecode, right: true, model: "gemma-3-1b-lut4")))
        XCTAssertEqual(right.llm?.prefillNpu, LlmScore.refNpuPrefill)
        XCTAssertEqual(right.llm?.decodeNpu, LlmScore.refNpuDecode)
        XCTAssertEqual(right.llm?.npu, "\(LlmNpuEngine.engineName) · gemma-3-1b-lut4")
        XCTAssertEqual(right.sectionScores["llm"], 4000)                           // (6000 + 0 + 6000) / 3, re-derived from the record
        // a wrong answer: the model ran (it's named), its rates don't count
        let wrong = BenchmarkRecord(llm(npu: LlmNpuRun(prefill: 9000, decode: 90, right: false, model: "gemma-3-1b-lut4")))
        XCTAssertEqual(wrong.llm?.prefillNpu, 0)
        XCTAssertEqual(wrong.llm?.decodeNpu, 0)
        XCTAssertEqual(wrong.llm?.npu, "\(LlmNpuEngine.engineName) · gemma-3-1b-lut4")
        XCTAssertEqual(wrong.sectionScores["llm"], 2000)
        // no engine in this build: nothing ran, nothing named
        let none = BenchmarkRecord(llm(npu: LlmNpuRun(prefill: 0, decode: 0, right: false, error: "no Neural Engine LLM engine in this build yet")))
        XCTAssertEqual(none.llm?.prefillNpu, 0)
        XCTAssertNil(none.llm?.npu)
        let again = try JSONDecoder().decode(BenchmarkRecord.self, from: JSONEncoder().encode(right))
        XCTAssertEqual(again.llm?.npu, right.llm?.npu)
        XCTAssertEqual(again.llm?.prefillNpu, LlmScore.refNpuPrefill)
    }

    /// History saved by 2.1.x (version 4) has no NPU fields in its LLM record.
    func testAVersion4LlmRecordStillLoads() throws {
        let json = #"{"prefillCpu":118.4,"decodeCpu":21.35,"prefillGpu":402,"decodeGpu":19.1,"gpu":"Apple A18 Pro GPU","freeGb":5.5}"#
        let l = try JSONDecoder().decode(LlmRecord.self, from: Data(json.utf8))
        XCTAssertEqual(l.prefillGpu, 402)
        XCTAssertNil(l.prefillNpu)
        XCTAssertNil(l.decodeNpu)
        XCTAssertNil(l.npu)
    }
}
