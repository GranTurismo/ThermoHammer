using System.Globalization;
using System.Text.Json.Serialization;

namespace ThermoHammer.Api.Models;

/// <summary>
/// One benchmark run. CPU: math, JSON & XML, compression & crypto, multi-core. GPU: Test 1 (Lake Valley),
/// Test 2 (Neon City) and Test 3 (Neon City RT, Vulkan ray tracing — 0 on phones without it), each rendered
/// offscreen at 1920×1080. UX: web browsing, web scrolling, photo gallery, photo editing, 4K video playback and
/// QR scanning. AI: six 8-bit models on the CPU, the GPU and the NPU (0 without one). MEM: RAM, storage and an
/// app-style SQLite database. Total = CPU + MEM + GPU + UX + AI. Every score is computed here from the submitted raw results (fps, scene
/// complexities, megapixels and scans per second) — the client's own scores are never trusted.
/// </summary>
public class BenchmarkRun
{
    public const int MaxText = 64;

    public int Id { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    [JsonIgnore]
    public ThermoSession? Session { get; set; }
    public int SessionId { get; set; }
    public required string Hash { get; set; }

    public int BenchmarkVersion { get; set; }
    public required string DeviceManufacturer { get; set; }
    public required string DeviceModel { get; set; }
    public OsPlatform Os { get; set; }
    public required string OsVersion { get; set; }
    public string? OsBuild { get; set; }
    public string? SocModel { get; set; }
    public string? GpuName { get; set; }
    public string? AppVersion { get; set; }

    public double Test1Fps { get; set; }
    public double Test1LowFps { get; set; }
    public double Test2Fps { get; set; }
    public double Test2LowFps { get; set; }
    public double Test3Fps { get; set; }
    public double Test3LowFps { get; set; }
    public bool RayTracing { get; set; }     // Test 3 ran (the phone has Vulkan ray queries)
    public int Test1Score { get; set; }
    public int Test2Score { get; set; }
    public int Test3Score { get; set; }
    public int Score { get; set; }           // GPU score
    public double WebLayout { get; set; }    // WebView scenes: complexity held at ≥ 28 fps
    public double WebCanvas { get; set; }
    public double WebCss { get; set; }
    public double WebSvg { get; set; }
    public double WebJs { get; set; }        // JavaScript suite, geometric mean ops/s
    public int WebScore { get; set; }
    public double ScrollSpeed { get; set; }  // feed px/s held with ≤ 10 % dropped frames
    public double ScrollDropped { get; set; }// % frames dropped over the scroll test (reported)
    public int ScrollScore { get; set; }
    public double GalleryJpeg { get; set; }  // photo gallery: MP decoded and shown per second, per format
    public double GalleryWebp { get; set; }
    public double GalleryAvif { get; set; }
    public int GalleryScore { get; set; }
    public double PhotoEdit { get; set; }    // MP/s through the GPU editing chain
    public int PhotoScore { get; set; }
    public double VideoAvc { get; set; }     // 4K60 decode fps per codec, 0 = unsupported
    public double VideoHevc { get; set; }
    public double VideoVp9 { get; set; }
    public double VideoAv1 { get; set; }
    public int VideoHardware { get; set; }   // bit per codec (AVC, HEVC, VP9, AV1) decoded in hardware (reported)
    public int VideoScore { get; set; }
    public double QrRate { get; set; }       // ZXing QR decodes/s (scored)
    public double? MlKitQrRate { get; set; } // ML Kit scans/s (reported only)
    public int QrScore { get; set; }
    public int UxScore { get; set; }
    // AI: inferences/s of each model (classify, detect, segment, upscale, depth, sound) on each accelerator
    public double AiClassifyCpu { get; set; }
    public double AiClassifyGpu { get; set; }
    public double AiClassifyNpu { get; set; }
    public double AiDetectCpu { get; set; }
    public double AiDetectGpu { get; set; }
    public double AiDetectNpu { get; set; }
    public double AiSegmentCpu { get; set; }
    public double AiSegmentGpu { get; set; }
    public double AiSegmentNpu { get; set; }
    public double AiUpscaleCpu { get; set; }
    public double AiUpscaleGpu { get; set; }
    public double AiUpscaleNpu { get; set; }
    public double AiDepthCpu { get; set; }
    public double AiDepthGpu { get; set; }
    public double AiDepthNpu { get; set; }
    public double AiSoundCpu { get; set; }
    public double AiSoundGpu { get; set; }
    public double AiSoundNpu { get; set; }
    public string? AiNpuRuntime { get; set; }// e.g. "Qualcomm QNN"; null when the NPU didn't run
    public int AiCpuScore { get; set; }
    public int AiGpuScore { get; set; }
    public int AiNpuScore { get; set; }
    public int AiScore { get; set; }
    // MEM: RAM GB/s (read, write, copy — every core) and ns per random access; storage MB/s (4 MB sequential) and
    // 4 KB IOPS (random, 4 threads); SQLite messages saved / marked read (synced transactions) and chats opened per second
    public double MemRead { get; set; }
    public double MemWrite { get; set; }
    public double MemCopy { get; set; }
    public double MemLatency { get; set; }
    public double StorageSeqRead { get; set; }
    public double StorageSeqWrite { get; set; }
    public double StorageRandomRead { get; set; }
    public double StorageRandomWrite { get; set; }
    public bool StorageDirect { get; set; }  // reads bypassed the page cache (reported)
    public double DbInsert { get; set; }
    public double DbUpdate { get; set; }
    public double DbQuery { get; set; }
    public int RamScore { get; set; }
    public int StorageScore { get; set; }
    public int DbScore { get; set; }
    public int MemScore { get; set; }
    public double CpuMath { get; set; }      // CPU tests: geometric mean of kernel ops/s
    public double CpuData { get; set; }
    public double CpuCrypto { get; set; }
    public double CpuMulti { get; set; }
    public int CpuScore { get; set; }
    public int TotalScore { get; set; }
    public int ValidityFlags { get; set; }   // ValidityFlag bits from the app; 0 = clean run
}

public class BenchmarkRequest
{
    public int SessionId { get; set; }
    public required string Hash { get; set; }
    public int BenchmarkVersion { get; set; }
    public required string DeviceManufacturer { get; set; }
    public required string DeviceModel { get; set; }
    public OsPlatform Os { get; set; }
    public required string OsVersion { get; set; }
    public string? OsBuild { get; set; }
    public string? SocModel { get; set; }
    public required string GpuName { get; set; }
    public double Test1Fps { get; set; }
    public double Test1LowFps { get; set; }
    public double Test2Fps { get; set; }
    public double Test2LowFps { get; set; }
    public double Test3Fps { get; set; }
    public double Test3LowFps { get; set; }
    public bool RayTracing { get; set; }
    public double WebLayout { get; set; }
    public double WebCanvas { get; set; }
    public double WebCss { get; set; }
    public double WebSvg { get; set; }
    public double WebJs { get; set; }
    public double QrRate { get; set; }
    public double? MlKitQrRate { get; set; }
    public double CpuMath { get; set; }
    public double CpuData { get; set; }
    public double CpuCrypto { get; set; }
    public double CpuMulti { get; set; }
    public double ScrollSpeed { get; set; }
    public double ScrollDropped { get; set; }
    public double GalleryJpeg { get; set; }
    public double GalleryWebp { get; set; }
    public double GalleryAvif { get; set; }
    public double PhotoEdit { get; set; }
    public double VideoAvc { get; set; }
    public double VideoHevc { get; set; }
    public double VideoVp9 { get; set; }
    public double VideoAv1 { get; set; }
    public int VideoHardware { get; set; }
    public double AiClassifyCpu { get; set; }
    public double AiClassifyGpu { get; set; }
    public double AiClassifyNpu { get; set; }
    public double AiDetectCpu { get; set; }
    public double AiDetectGpu { get; set; }
    public double AiDetectNpu { get; set; }
    public double AiSegmentCpu { get; set; }
    public double AiSegmentGpu { get; set; }
    public double AiSegmentNpu { get; set; }
    public double AiUpscaleCpu { get; set; }
    public double AiUpscaleGpu { get; set; }
    public double AiUpscaleNpu { get; set; }
    public double AiDepthCpu { get; set; }
    public double AiDepthGpu { get; set; }
    public double AiDepthNpu { get; set; }
    public double AiSoundCpu { get; set; }
    public double AiSoundGpu { get; set; }
    public double AiSoundNpu { get; set; }
    public string? AiNpuRuntime { get; set; }
    public double MemRead { get; set; }
    public double MemWrite { get; set; }
    public double MemCopy { get; set; }
    public double MemLatency { get; set; }
    public double StorageSeqRead { get; set; }
    public double StorageSeqWrite { get; set; }
    public double StorageRandomRead { get; set; }
    public double StorageRandomWrite { get; set; }
    public bool StorageDirect { get; set; }  // reads bypassed the page cache (reported)
    public double DbInsert { get; set; }
    public double DbUpdate { get; set; }
    public double DbQuery { get; set; }
    public int ValidityFlags { get; set; }
    public string? AppVersion { get; set; }
}

public class BenchmarkDto
{
    public int Id { get; set; }
    public required string DeviceManufacturer { get; set; }
    public required string DeviceModel { get; set; }
    public string? SocModel { get; set; }
    public string? GpuName { get; set; }
    public OsPlatform Os { get; set; }
    public required string OsVersion { get; set; }
    public int Score { get; set; }           // GPU
    public int UxScore { get; set; }
    public int CpuScore { get; set; }
    public int AiScore { get; set; }
    public int MemScore { get; set; }
    public int TotalScore { get; set; }
    public int WebScore { get; set; }
    public int ScrollScore { get; set; }
    public int GalleryScore { get; set; }
    public int PhotoScore { get; set; }
    public int VideoScore { get; set; }
    public int QrScore { get; set; }
    public int Test1Score { get; set; }
    public int Test2Score { get; set; }
    public int Test3Score { get; set; }
    public double Test1Fps { get; set; }
    public double Test2Fps { get; set; }
    public double Test3Fps { get; set; }
    public bool RayTracing { get; set; }
    public int Runs { get; set; }            // runs submitted for this device model
}

public static class BenchmarkScoring
{
    /// <summary>Scene/scoring version the server accepts — matches BenchmarkScore.VERSION in the app.</summary>
    public const int CurrentVersion = 2;
    // Every reference below: the Snapdragon 8 Elite (Honor VKP-NX9, 2026-10-25) scores 6000 on each test —
    // they must match engine/Benchmark.kt in the app.
    public const double Test1Weight = 46.3;  // Lake Valley:  129.5 fps → 6000 points
    public const double Test2Weight = 228;   // Neon City:     26.3 fps → 6000 points
    public const double Test3Weight = 448;   // Neon City RT:  13.4 fps → 6000 points

    // UX references, 6000 points each
    public const double RefLayout = 1320, RefCanvas = 2730, RefCss = 1980, RefSvg = 3130, RefJs = 2160;
    public const double RefScroll = 66600;   // feed px/s held
    public const double RefJpeg = 122, RefWebp = 99, RefAvif = 103;   // gallery MP/s per format
    public const double RefPhoto = 397;      // MP/s through the editing chain
    public const double RefVideo = 298;      // 4K frames/s, mean of the codecs
    public const double QrWeight = 13.6;     // zxing-cpp decodes/s → points (440/s → 6000)

    // CPU references (geometric-mean kernel ops/s of the shared C++ kernels), 6000 points each — provisional
    public const double RefCpuMath = 1172, RefCpuData = 632, RefCpuCrypto = 595, RefCpuMulti = 6091;
    public const double MaxFps = 5000;

    // AI references: inferences/s worth 6000 points per model (classify, detect, segment, upscale, depth, sound)
    // and accelerator (NPU through Qualcomm QNN).
    public static readonly double[] RefAiCpu = [330, 18.2, 4.71, 2.65, 42, 2890];
    public static readonly double[] RefAiGpu = [658, 85.5, 33.1, 92.8, 182, 1470];
    public static readonly double[] RefAiNpu = [1690, 473, 229, 774, 993, 2100];

    // MEM references, 6000 points each
    public const double RefMemRead = 57.8, RefMemWrite = 28.4, RefMemCopy = 18.7, RefMemLatency = 245;  // GB/s, ns
    public const double RefSeqRead = 1750, RefSeqWrite = 1300, RefRandomRead = 29000, RefRandomWrite = 13400; // MB/s, IOPS
    public const double RefDbInsert = 9700, RefDbUpdate = 36000, RefDbQuery = 20300;                  // per second

    /// <summary>The app's ValidityFlag.POWER_EVENT — a charger during the run lifts power limits.</summary>
    public const int PowerEventFlag = 1 << 2;

    public static int Points(double fps, double weight) =>
        (int)Math.Round(fps * weight, MidpointRounding.AwayFromZero);

    /// <summary>
    /// Raster (geometric mean of Tests 1–2) counts two thirds, ray tracing (Test 3, 0 without it) one third —
    /// the same formula as BenchmarkScore.overall in the app.
    /// </summary>
    public static int Overall(int p1, int p2, int p3)
    {
        if (p1 <= 0 || p2 <= 0) return 0;
        double raster = Math.Exp((Math.Log(p1) + Math.Log(p2)) / 2);
        return (int)Math.Round((2 * raster + Math.Max(p3, 0)) / 3, MidpointRounding.AwayFromZero);
    }

    public static int WebPoints(double layout, double canvas, double css, double svg, double js)
    {
        double[] parts = [layout / RefLayout, canvas / RefCanvas, css / RefCss, svg / RefSvg, js / RefJs];
        if (parts.Any(p => !(p > 0))) return 0;
        return (int)Math.Round(6000 * Math.Exp(parts.Sum(Math.Log) / parts.Length), MidpointRounding.AwayFromZero);
    }

    public static int QrPoints(double rate) => (int)Math.Round(Math.Max(rate, 0) * QrWeight, MidpointRounding.AwayFromZero);

    public static int ScrollPoints(double speed) => (int)Math.Round(6000 * Math.Max(speed, 0) / RefScroll, MidpointRounding.AwayFromZero);

    /// <summary>Mean over the three formats — a WebView without AVIF loses a third.</summary>
    public static int GalleryPoints(double jpeg, double webp, double avif) => (int)Math.Round(
        6000 * (Math.Max(jpeg, 0) / RefJpeg + Math.Max(webp, 0) / RefWebp + Math.Max(avif, 0) / RefAvif) / 3,
        MidpointRounding.AwayFromZero);

    public static int PhotoPoints(double mps) => (int)Math.Round(6000 * Math.Max(mps, 0) / RefPhoto, MidpointRounding.AwayFromZero);

    /// <summary>Mean over the four codecs — a codec the phone lacks costs a quarter.</summary>
    public static int VideoPoints(double avc, double hevc, double vp9, double av1) => (int)Math.Round(
        6000 * (Math.Max(avc, 0) + Math.Max(hevc, 0) + Math.Max(vp9, 0) + Math.Max(av1, 0)) / (RefVideo * 4),
        MidpointRounding.AwayFromZero);

    public static int CpuPoints(double value, double reference) =>
        (int)Math.Round(6000 * Math.Max(value, 0) / reference, MidpointRounding.AwayFromZero);

    /// <summary>Geometric mean of the four CPU tests (0 if any is 0).</summary>
    public static int Cpu(double math, double data, double crypto, double multi)
    {
        int[] p = [CpuPoints(math, RefCpuMath), CpuPoints(data, RefCpuData), CpuPoints(crypto, RefCpuCrypto), CpuPoints(multi, RefCpuMulti)];
        if (p.Any(x => x <= 0)) return 0;
        return (int)Math.Round(Math.Exp(p.Sum(x => Math.Log(x)) / p.Length), MidpointRounding.AwayFromZero);
    }

    /// <summary>6000 × the geometric mean of value ÷ reference; 0 if any part is 0 — MemScore.geo in the app.</summary>
    private static int Geo(params double[] ratios) => ratios.Any(r => !(r > 0)) ? 0
        : (int)Math.Round(6000 * Math.Exp(ratios.Sum(Math.Log) / ratios.Length), MidpointRounding.AwayFromZero);

    /// <summary>Latency counts inversely: half the nanoseconds, twice as good.</summary>
    public static int RamPoints(double read, double write, double copy, double latencyNs) =>
        latencyNs > 0 ? Geo(read / RefMemRead, write / RefMemWrite, copy / RefMemCopy, RefMemLatency / latencyNs) : 0;

    public static int StoragePoints(double seqRead, double seqWrite, double randomRead, double randomWrite) =>
        Geo(seqRead / RefSeqRead, seqWrite / RefSeqWrite, randomRead / RefRandomRead, randomWrite / RefRandomWrite);

    public static int DbPoints(double insert, double update, double query) =>
        Geo(insert / RefDbInsert, update / RefDbUpdate, query / RefDbQuery);

    /// <summary>One accelerator: mean of its six model points — a model it can't run costs a sixth.</summary>
    public static int AiAccel(double[] ips, double[] refs) => (int)Math.Round(
        ips.Select((v, i) => 6000 * Math.Max(v, 0) / refs[i]).Sum() / refs.Length, MidpointRounding.AwayFromZero);

    /// <summary>Each accelerator a third; a missing one scores 0 there — AiScore.overall in the app.</summary>
    public static int Ai(int cpu, int gpu, int npu) =>
        (int)Math.Round((Math.Max(cpu, 0) + Math.Max(gpu, 0) + Math.Max(npu, 0)) / 3.0, MidpointRounding.AwayFromZero);

    public static double[] AiIps(BenchmarkRequest r, int accel) => accel switch
    {
        0 => [r.AiClassifyCpu, r.AiDetectCpu, r.AiSegmentCpu, r.AiUpscaleCpu, r.AiDepthCpu, r.AiSoundCpu],
        1 => [r.AiClassifyGpu, r.AiDetectGpu, r.AiSegmentGpu, r.AiUpscaleGpu, r.AiDepthGpu, r.AiSoundGpu],
        _ => [r.AiClassifyNpu, r.AiDetectNpu, r.AiSegmentNpu, r.AiUpscaleNpu, r.AiDepthNpu, r.AiSoundNpu],
    };

    /// <summary>Geometric mean of the six UX tests (0 if any is 0) — UxScore.overall in the app.</summary>
    public static int Ux(params int[] points)
    {
        if (points.Length == 0 || points.Any(p => p <= 0)) return 0;
        return (int)Math.Round(Math.Exp(points.Sum(p => Math.Log(p)) / points.Length), MidpointRounding.AwayFromZero);
    }

    /// <summary>Geometric mean of RAM, storage and database (0 if any is 0) — MemScore.overall in the app.</summary>
    public static int Mem(int ram, int storage, int db) => Ux(ram, storage, db);

    /// <summary>The string the app HMACs with the session key (BenchmarkPayload.canonical()).</summary>
    public static string Canonical(BenchmarkRequest r) => string.Join("|",
        "bench", r.BenchmarkVersion.ToString(CultureInfo.InvariantCulture), r.DeviceManufacturer, r.DeviceModel,
        r.OsVersion, r.GpuName, F2(r.Test1Fps), F2(r.Test1LowFps), F2(r.Test2Fps), F2(r.Test2LowFps),
        F2(r.Test3Fps), F2(r.Test3LowFps), r.RayTracing ? "1" : "0", F2(r.WebLayout), F2(r.WebCanvas), F2(r.WebCss),
        F2(r.WebSvg), F2(r.WebJs), F2(r.QrRate), F2(r.MlKitQrRate ?? 0), F2(r.CpuMath), F2(r.CpuData), F2(r.CpuCrypto),
        F2(r.CpuMulti), F2(r.ScrollSpeed), F2(r.ScrollDropped), F2(r.GalleryJpeg), F2(r.GalleryWebp), F2(r.GalleryAvif),
        F2(r.PhotoEdit), F2(r.VideoAvc), F2(r.VideoHevc), F2(r.VideoVp9), F2(r.VideoAv1),
        r.VideoHardware.ToString(CultureInfo.InvariantCulture), F2(r.AiClassifyCpu), F2(r.AiClassifyGpu), F2(r.AiClassifyNpu), F2(r.AiDetectCpu), F2(r.AiDetectGpu), F2(r.AiDetectNpu), F2(r.AiSegmentCpu), F2(r.AiSegmentGpu), F2(r.AiSegmentNpu), F2(r.AiUpscaleCpu), F2(r.AiUpscaleGpu), F2(r.AiUpscaleNpu), F2(r.AiDepthCpu), F2(r.AiDepthGpu), F2(r.AiDepthNpu), F2(r.AiSoundCpu), F2(r.AiSoundGpu), F2(r.AiSoundNpu),
        r.AiNpuRuntime ?? "", F2(r.MemRead), F2(r.MemWrite), F2(r.MemCopy), F2(r.MemLatency), F2(r.StorageSeqRead),
        F2(r.StorageSeqWrite), F2(r.StorageRandomRead), F2(r.StorageRandomWrite), r.StorageDirect ? "1" : "0",
        F2(r.DbInsert), F2(r.DbUpdate), F2(r.DbQuery), r.ValidityFlags.ToString(CultureInfo.InvariantCulture));

    private static string F2(double v) => v.ToString("F2", CultureInfo.InvariantCulture);

    /// <summary>Why a request can't be stored, or null when it is plausible.</summary>
    public static string? Problem(BenchmarkRequest r)
    {
        if (r.BenchmarkVersion != CurrentVersion) return $"Unsupported benchmark version {r.BenchmarkVersion}";
        if (string.IsNullOrWhiteSpace(r.DeviceModel) || string.IsNullOrWhiteSpace(r.GpuName)) return "Missing device";
        foreach (var (fps, low) in new[] { (r.Test1Fps, r.Test1LowFps), (r.Test2Fps, r.Test2LowFps) })
        {
            if (!(fps > 0 && fps <= MaxFps)) return "Implausible fps";
            if (!(low > 0 && low <= fps * 1.05)) return "Implausible 1% low";
        }
        if (r.RayTracing)
        {
            if (!(r.Test3Fps > 0 && r.Test3Fps <= MaxFps)) return "Implausible ray tracing fps";
            if (!(r.Test3LowFps > 0 && r.Test3LowFps <= r.Test3Fps * 1.05)) return "Implausible ray tracing 1% low";
        }
        else if (r.Test3Fps != 0 || r.Test3LowFps != 0) return "Ray tracing fps without ray tracing";
        // UX: a WebView test that didn't finish submits zeros; anything else must be positive and sane
        double[] web = [r.WebLayout, r.WebCanvas, r.WebCss, r.WebSvg, r.WebJs];
        if (web.Any(v => !(v >= 0 && v < 1e7))) return "Implausible web result";
        if (!(r.QrRate >= 0 && r.QrRate <= 5000)) return "Implausible QR rate";
        if (r.MlKitQrRate is { } ml && !(ml >= 0 && ml <= 5000)) return "Implausible ML Kit rate";
        double[] cpu = [r.CpuMath, r.CpuData, r.CpuCrypto, r.CpuMulti];
        if (cpu.Any(v => !(v >= 0 && v < 1e7))) return "Implausible CPU result";
        if (!(r.ScrollSpeed >= 0 && r.ScrollSpeed <= 1e6) || !(r.ScrollDropped >= 0 && r.ScrollDropped <= 100))
            return "Implausible scroll result";
        double[] media = [r.GalleryJpeg, r.GalleryWebp, r.GalleryAvif, r.PhotoEdit];
        if (media.Any(v => !(v >= 0 && v < 1e5))) return "Implausible photo result";
        double[] video = [r.VideoAvc, r.VideoHevc, r.VideoVp9, r.VideoAv1];
        if (video.Any(v => !(v >= 0 && v <= 5000))) return "Implausible video result";
        if (r.VideoHardware is < 0 or > 15) return "Invalid video hardware flags";
        double[] ai = [r.AiClassifyCpu, r.AiClassifyGpu, r.AiClassifyNpu, r.AiDetectCpu, r.AiDetectGpu, r.AiDetectNpu, r.AiSegmentCpu, r.AiSegmentGpu, r.AiSegmentNpu, r.AiUpscaleCpu, r.AiUpscaleGpu, r.AiUpscaleNpu, r.AiDepthCpu, r.AiDepthGpu, r.AiDepthNpu, r.AiSoundCpu, r.AiSoundGpu, r.AiSoundNpu];
        if (ai.Any(v => !(v >= 0 && v < 1e6))) return "Implausible AI result";
        double[] ram = [r.MemRead, r.MemWrite, r.MemCopy];
        if (ram.Any(v => !(v >= 0 && v < 2000)) || !(r.MemLatency >= 0 && r.MemLatency < 1e5)) return "Implausible RAM result";
        double[] storage = [r.StorageSeqRead, r.StorageSeqWrite, r.StorageRandomRead, r.StorageRandomWrite];
        if (storage.Any(v => !(v >= 0 && v < 1e7))) return "Implausible storage result";
        double[] db = [r.DbInsert, r.DbUpdate, r.DbQuery];
        if (db.Any(v => !(v >= 0 && v < 1e7))) return "Implausible database result";
        return r.ValidityFlags < 0 ? "Invalid flags" : null;
    }

    public static BenchmarkRun ToDao(this BenchmarkRequest r)
    {
        int t1 = Points(r.Test1Fps, Test1Weight);
        int t2 = Points(r.Test2Fps, Test2Weight);
        int t3 = r.RayTracing ? Points(r.Test3Fps, Test3Weight) : 0;
        int gpu = Overall(t1, t2, t3);
        int webScore = WebPoints(r.WebLayout, r.WebCanvas, r.WebCss, r.WebSvg, r.WebJs);
        int scrollScore = ScrollPoints(r.ScrollSpeed);
        int galleryScore = GalleryPoints(r.GalleryJpeg, r.GalleryWebp, r.GalleryAvif);
        int photoScore = PhotoPoints(r.PhotoEdit);
        int videoScore = VideoPoints(r.VideoAvc, r.VideoHevc, r.VideoVp9, r.VideoAv1);
        int qrScore = QrPoints(r.QrRate);
        int ux = Ux(webScore, scrollScore, galleryScore, photoScore, videoScore, qrScore);
        int cpu = Cpu(r.CpuMath, r.CpuData, r.CpuCrypto, r.CpuMulti);
        int aiCpu = AiAccel(AiIps(r, 0), RefAiCpu), aiGpu = AiAccel(AiIps(r, 1), RefAiGpu), aiNpu = AiAccel(AiIps(r, 2), RefAiNpu);
        int ai = Ai(aiCpu, aiGpu, aiNpu);
        int ramScore = RamPoints(r.MemRead, r.MemWrite, r.MemCopy, r.MemLatency);
        int storageScore = StoragePoints(r.StorageSeqRead, r.StorageSeqWrite, r.StorageRandomRead, r.StorageRandomWrite);
        int dbScore = DbPoints(r.DbInsert, r.DbUpdate, r.DbQuery);
        int mem = Mem(ramScore, storageScore, dbScore);
        return new BenchmarkRun
        {
            SessionId = r.SessionId,
            Hash = r.Hash,
            BenchmarkVersion = r.BenchmarkVersion,
            DeviceManufacturer = Clip(r.DeviceManufacturer) ?? "",
            DeviceModel = Clip(r.DeviceModel) ?? "",
            Os = r.Os,
            OsVersion = Clip(r.OsVersion) ?? "",
            OsBuild = Clip(r.OsBuild),
            SocModel = Clip(r.SocModel),
            GpuName = Clip(r.GpuName),
            AppVersion = Clip(r.AppVersion),
            Test1Fps = r.Test1Fps,
            Test1LowFps = r.Test1LowFps,
            Test2Fps = r.Test2Fps,
            Test2LowFps = r.Test2LowFps,
            Test3Fps = r.RayTracing ? r.Test3Fps : 0,
            Test3LowFps = r.RayTracing ? r.Test3LowFps : 0,
            RayTracing = r.RayTracing,
            Test1Score = t1,
            Test2Score = t2,
            Test3Score = t3,
            Score = gpu,
            WebLayout = r.WebLayout,
            WebCanvas = r.WebCanvas,
            WebCss = r.WebCss,
            WebSvg = r.WebSvg,
            WebJs = r.WebJs,
            WebScore = webScore,
            ScrollSpeed = r.ScrollSpeed,
            ScrollDropped = r.ScrollDropped,
            ScrollScore = scrollScore,
            GalleryJpeg = r.GalleryJpeg,
            GalleryWebp = r.GalleryWebp,
            GalleryAvif = r.GalleryAvif,
            GalleryScore = galleryScore,
            PhotoEdit = r.PhotoEdit,
            PhotoScore = photoScore,
            VideoAvc = r.VideoAvc,
            VideoHevc = r.VideoHevc,
            VideoVp9 = r.VideoVp9,
            VideoAv1 = r.VideoAv1,
            VideoHardware = r.VideoHardware,
            VideoScore = videoScore,
            QrRate = r.QrRate,
            MlKitQrRate = r.MlKitQrRate,
            QrScore = qrScore,
            UxScore = ux,
            CpuMath = r.CpuMath,
            CpuData = r.CpuData,
            CpuCrypto = r.CpuCrypto,
            CpuMulti = r.CpuMulti,
            CpuScore = cpu,
            AiClassifyCpu = r.AiClassifyCpu,
            AiClassifyGpu = r.AiClassifyGpu,
            AiClassifyNpu = r.AiClassifyNpu,
            AiDetectCpu = r.AiDetectCpu,
            AiDetectGpu = r.AiDetectGpu,
            AiDetectNpu = r.AiDetectNpu,
            AiSegmentCpu = r.AiSegmentCpu,
            AiSegmentGpu = r.AiSegmentGpu,
            AiSegmentNpu = r.AiSegmentNpu,
            AiUpscaleCpu = r.AiUpscaleCpu,
            AiUpscaleGpu = r.AiUpscaleGpu,
            AiUpscaleNpu = r.AiUpscaleNpu,
            AiDepthCpu = r.AiDepthCpu,
            AiDepthGpu = r.AiDepthGpu,
            AiDepthNpu = r.AiDepthNpu,
            AiSoundCpu = r.AiSoundCpu,
            AiSoundGpu = r.AiSoundGpu,
            AiSoundNpu = r.AiSoundNpu,
            AiNpuRuntime = Clip(r.AiNpuRuntime),
            AiCpuScore = aiCpu,
            AiGpuScore = aiGpu,
            AiNpuScore = aiNpu,
            AiScore = ai,
            MemRead = r.MemRead,
            MemWrite = r.MemWrite,
            MemCopy = r.MemCopy,
            MemLatency = r.MemLatency,
            StorageSeqRead = r.StorageSeqRead,
            StorageSeqWrite = r.StorageSeqWrite,
            StorageRandomRead = r.StorageRandomRead,
            StorageRandomWrite = r.StorageRandomWrite,
            StorageDirect = r.StorageDirect,
            DbInsert = r.DbInsert,
            DbUpdate = r.DbUpdate,
            DbQuery = r.DbQuery,
            RamScore = ramScore,
            StorageScore = storageScore,
            DbScore = dbScore,
            MemScore = mem,
            TotalScore = cpu + mem + gpu + ux + ai,
            ValidityFlags = r.ValidityFlags
        };
    }

    /// <summary>Best run per device model by total (ties: earliest), highest first.</summary>
    public static List<BenchmarkDto> Leaderboard(IEnumerable<BenchmarkRun> runs) => runs
        .GroupBy(r => r.DeviceModel, StringComparer.OrdinalIgnoreCase)
        .Select(g =>
        {
            var best = g.OrderByDescending(r => r.TotalScore).ThenBy(r => r.Id).First();
            return new BenchmarkDto
            {
                Id = best.Id,
                DeviceManufacturer = best.DeviceManufacturer,
                DeviceModel = best.DeviceModel,
                SocModel = best.SocModel,
                GpuName = best.GpuName,
                Os = best.Os,
                OsVersion = best.OsVersion,
                Score = best.Score,
                UxScore = best.UxScore,
                CpuScore = best.CpuScore,
                AiScore = best.AiScore,
                MemScore = best.MemScore,
                TotalScore = best.TotalScore,
                WebScore = best.WebScore,
                ScrollScore = best.ScrollScore,
                GalleryScore = best.GalleryScore,
                PhotoScore = best.PhotoScore,
                VideoScore = best.VideoScore,
                QrScore = best.QrScore,
                Test1Score = best.Test1Score,
                Test2Score = best.Test2Score,
                Test3Score = best.Test3Score,
                Test1Fps = best.Test1Fps,
                Test2Fps = best.Test2Fps,
                Test3Fps = best.Test3Fps,
                RayTracing = best.RayTracing,
                Runs = g.Count()
            };
        })
        .OrderByDescending(d => d.TotalScore)
        .ToList();

    private static string? Clip(string? value)
    {
        var v = value?.Trim();
        if (string.IsNullOrEmpty(v)) return null;
        return v.Length <= BenchmarkRun.MaxText ? v : v[..BenchmarkRun.MaxText];
    }
}
