using System.Globalization;

namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK PAYLOAD — what POST /benchmark takes and the canonical string its
// hash signs: field for field Android's BenchmarkPayload and the server's
// BenchmarkScoring.Canonical. No Windows calls here, so the unit tests compile
// it as it is and pin the string (BenchmarkScoreTests).
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>The request body of POST /benchmark — the field names the server (and the phone apps) use.</summary>
public sealed class BenchmarkPayload
{
    public int SessionId;
    public string Hash = "";
    public int BenchmarkVersion;
    public string DeviceManufacturer = "";
    public string DeviceModel = "";
    public int Os;                       // DeviceInfo.OsPlatform: 4 = Windows
    public string OsVersion = "";
    public string? OsBuild;
    public string? SocModel;
    public string GpuName = "";
    public double Test1Fps, Test1LowFps, Test2Fps, Test2LowFps, Test3Fps, Test3LowFps;
    public bool RayTracing;
    public double WebLayout, WebCanvas, WebCss, WebSvg, WebJs;
    public double QrRate;
    public double? MlkitQrRate;
    public double CpuMath, CpuData, CpuCrypto, CpuMulti;
    public double ScrollSpeed, ScrollDropped;
    public double GalleryJpeg, GalleryWebp, GalleryAvif;
    public double PhotoEdit;
    public double VideoAvc, VideoHevc, VideoVp9, VideoAv1;
    public double Video8kHevc, Video8kAv1;
    public int VideoHardware;
    public double ExportAvc, ExportHevc, ExportAv1, Export8kHevc;
    public double ExportAvcPsnr, ExportHevcPsnr, ExportAv1Psnr, Export8kHevcPsnr;
    public int ExportHardware;
    public double AiClassifyCpu, AiClassifyGpu, AiClassifyNpu, AiDetectCpu, AiDetectGpu, AiDetectNpu;
    public double AiSegmentCpu, AiSegmentGpu, AiSegmentNpu, AiUpscaleCpu, AiUpscaleGpu, AiUpscaleNpu;
    public double AiDepthCpu, AiDepthGpu, AiDepthNpu, AiSoundCpu, AiSoundGpu, AiSoundNpu;
    public string? AiNpuRuntime;
    public double MemRead, MemWrite, MemCopy, MemLatency;
    public double StorageSeqRead, StorageSeqWrite, StorageRandomRead, StorageRandomWrite;
    public bool StorageDirect;
    public double DbInsert, DbUpdate, DbQuery;
    // the LLM test's fields: version 5 has no LLM test, so the app always sends 0 and none. They stay in the payload and in the
    // signed text, in the same places, because the server's layout (BenchmarkScoring.Canonical) still has them
    public double LlmPrefillCpu, LlmDecodeCpu, LlmPrefillGpu, LlmDecodeGpu;
    public string? LlmGpu;
    public double LlmFreeGb;
    public double LlmPrefillNpu, LlmDecodeNpu;
    public string? LlmNpu;
    public int ValidityFlags;
    public string? AppVersion;

    /// <summary>Exactly what the server re-derives and verifies (BenchmarkScoring.Canonical).</summary>
    public string Canonical()
    {
        static string F2(double v) => v.ToString("F2", CultureInfo.InvariantCulture);
        string[] parts =
        [
            "bench", BenchmarkVersion.ToString(CultureInfo.InvariantCulture), DeviceManufacturer, DeviceModel, OsVersion, GpuName,
            F2(Test1Fps), F2(Test1LowFps), F2(Test2Fps), F2(Test2LowFps), F2(Test3Fps), F2(Test3LowFps), RayTracing ? "1" : "0",
            F2(WebLayout), F2(WebCanvas), F2(WebCss), F2(WebSvg), F2(WebJs), F2(QrRate), F2(MlkitQrRate ?? 0),
            F2(CpuMath), F2(CpuData), F2(CpuCrypto), F2(CpuMulti), F2(ScrollSpeed), F2(ScrollDropped),
            F2(GalleryJpeg), F2(GalleryWebp), F2(GalleryAvif), F2(PhotoEdit), F2(VideoAvc), F2(VideoHevc), F2(VideoVp9), F2(VideoAv1),
            F2(Video8kHevc), F2(Video8kAv1), VideoHardware.ToString(CultureInfo.InvariantCulture), F2(ExportAvc), F2(ExportHevc), F2(ExportAv1), F2(Export8kHevc),
            F2(ExportAvcPsnr), F2(ExportHevcPsnr), F2(ExportAv1Psnr), F2(Export8kHevcPsnr), ExportHardware.ToString(CultureInfo.InvariantCulture),
            F2(AiClassifyCpu), F2(AiClassifyGpu), F2(AiClassifyNpu), F2(AiDetectCpu), F2(AiDetectGpu), F2(AiDetectNpu),
            F2(AiSegmentCpu), F2(AiSegmentGpu), F2(AiSegmentNpu), F2(AiUpscaleCpu), F2(AiUpscaleGpu), F2(AiUpscaleNpu),
            F2(AiDepthCpu), F2(AiDepthGpu), F2(AiDepthNpu), F2(AiSoundCpu), F2(AiSoundGpu), F2(AiSoundNpu), AiNpuRuntime ?? "",
            F2(MemRead), F2(MemWrite), F2(MemCopy), F2(MemLatency), F2(StorageSeqRead), F2(StorageSeqWrite),
            F2(StorageRandomRead), F2(StorageRandomWrite), StorageDirect ? "1" : "0", F2(DbInsert), F2(DbUpdate), F2(DbQuery),
            F2(LlmPrefillCpu), F2(LlmDecodeCpu), F2(LlmPrefillGpu), F2(LlmDecodeGpu), LlmGpu ?? "", F2(LlmFreeGb),
            F2(LlmPrefillNpu), F2(LlmDecodeNpu), LlmNpu ?? "",
            ValidityFlags.ToString(CultureInfo.InvariantCulture),
        ];
        return string.Join("|", parts);
    }
}
