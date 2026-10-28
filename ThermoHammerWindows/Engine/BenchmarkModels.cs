namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK — the same six sections and the same scoring as the Android app
// (engine/Benchmark.kt), the iPhone app (BenchmarkModels.swift) and the server
// (BenchmarkScoring.cs): CPU + MEM + GPU + UX + AI + LLM, every test 6000 points
// on the Snapdragon 8 Elite reference phone. Change a number here only together
// with those three.
// ─────────────────────────────────────────────────────────────────────────────

public static class BenchmarkScore
{
    /// <summary>Bump when a scene, the resolution or the scoring changes — scores compare only within a version.</summary>
    public const int Version = 4;
    public const float WarmupS = 2, WindowS = 5;

    public static int Points(GpuTest t, double fps) => (int)Math.Round(fps * t.Weight(), MidpointRounding.AwayFromZero);

    /// <summary>Kept to 2 decimals — what is hashed, submitted and re-scored by the server.</summary>
    public static double Round2(double v) => Math.Round(v * 100, MidpointRounding.AwayFromZero) / 100;

    /// <summary>Raster (geometric mean of Tests 1–2) two thirds, ray tracing (Test 3; 0 where unsupported) one third.</summary>
    public static int Overall(int t1, int t2, int t3)
    {
        if (t1 <= 0 || t2 <= 0) return 0;
        double raster = Math.Exp((Math.Log(t1) + Math.Log(t2)) / 2);
        return (int)Math.Round((2 * raster + Math.Max(t3, 0)) / 3, MidpointRounding.AwayFromZero);
    }

    public static int Overall(IReadOnlyList<GpuTestResult> results)
    {
        int Pts(GpuTest t) => results.FirstOrDefault(r => r.Test == t)?.Points ?? 0;
        return Overall(Pts(GpuTest.LakeValley), Pts(GpuTest.NeonCity), Pts(GpuTest.NeonCityRT));
    }

    /// <summary>Geometric mean; 0 if any part failed.</summary>
    public static int Geomean(IReadOnlyCollection<int> points)
    {
        if (points.Count == 0 || points.Any(p => p <= 0)) return 0;
        return (int)Math.Round(Math.Exp(points.Sum(p => Math.Log(p)) / points.Count), MidpointRounding.AwayFromZero);
    }

    public static int Round(double v) => (int)Math.Round(v, MidpointRounding.AwayFromZero);

    /// <summary>
    /// Summarises one GPU test from its frame log: <c>starts[i]</c> = timeline time the frame began, <c>durNs[i]</c> its render
    /// time. Null when too few frames were scored.
    /// </summary>
    public static GpuTestResult? Summarize(GpuTest test, float[] starts, long[] durNs)
    {
        var idx = Enumerable.Range(0, starts.Length).Where(i => starts[i] >= WarmupS).ToList();
        if (idx.Count < 10) return null;
        long totalNs = idx.Sum(i => durNs[i]);
        double avg = Round2(idx.Count * 1e9 / totalNs);
        var sorted = idx.Select(i => durNs[i]).OrderBy(x => x).ToList();
        long p99 = sorted[(int)Math.Round((sorted.Count - 1) * 0.99, MidpointRounding.AwayFromZero)];
        double low = Round2(1e9 / p99);
        var windows = new Dictionary<int, (int n, long ns)>();
        foreach (int i in idx)
        {
            int w = (int)((starts[i] - WarmupS) / WindowS);
            var (n, ns) = windows.GetValueOrDefault(w);
            windows[w] = (n + 1, ns + durNs[i]);
        }
        int lastFull = (int)((test.Seconds() - WarmupS) / WindowS) - 1;
        var rates = windows.Where(kv => kv.Key <= lastFull && kv.Value.n > 0).Select(kv => kv.Value.n * 1e9 / kv.Value.ns).ToList();
        float stability = rates.Count >= 2 ? (float)(rates.Min() / rates.Max() * 100) : 100;
        return new GpuTestResult(test, true, avg, low, idx.Count, stability, Points(test, avg));
    }
}

/// <summary>The run's validity bits (the same values as the phones' ValidityFlag; 0 = clean).</summary>
public static class BenchFlag
{
    public const int WarmStarted = 1 << 0, Interference = 1 << 1, PowerEvent = 1 << 2, EnvCapped = 1 << 5, CooldownTimeout = 1 << 7;
}

// ── GPU ──

public enum GpuTest { LakeValley = 1, NeonCity = 2, NeonCityRT = 3 }

public static class GpuTests
{
    public static readonly GpuTest[] All = [GpuTest.LakeValley, GpuTest.NeonCity, GpuTest.NeonCityRT];
    public static int Number(this GpuTest t) => (int)t;
    public static string Title(this GpuTest t) => t switch { GpuTest.LakeValley => "LAKE VALLEY", GpuTest.NeonCity => "NEON CITY", _ => "NEON CITY RT" };
    public static int Seconds(this GpuTest t) => t switch { GpuTest.LakeValley => 50, _ => 60 };
    public static bool RayTracing(this GpuTest t) => t == GpuTest.NeonCityRT;
    public static double Weight(this GpuTest t) => t switch { GpuTest.LakeValley => 46.3, GpuTest.NeonCity => 228.0, _ => 448.0 };
}

public sealed record GpuTestResult(GpuTest Test, bool Supported, double AvgFps, double LowFps, int Frames, float StabilityPct, int Points)
{
    public static GpuTestResult Unsupported(GpuTest t) => new(t, false, 0, 0, 0, 0, 0);
}

// ── CPU ──

public enum CpuTest { Math = 1, Data, Crypto, Multi }

public static class CpuTests
{
    public static readonly CpuTest[] All = [CpuTest.Math, CpuTest.Data, CpuTest.Crypto, CpuTest.Multi];
    public static int Number(this CpuTest t) => (int)t;
    public static string Title(this CpuTest t) => t switch
    {
        CpuTest.Math => "MATH", CpuTest.Data => "JSON & XML", CpuTest.Crypto => "COMPRESSION & CRYPTO", _ => "MULTI-CORE",
    };
    public static int Seconds(this CpuTest t) => t switch { CpuTest.Math => 10, CpuTest.Data => 9, CpuTest.Crypto => 9, _ => 10 };
    public static bool MultiCore(this CpuTest t) => t == CpuTest.Multi;
    /// <summary>BenchCore kernel ids (bench_core.h), in order.</summary>
    public static int[] KernelIds(this CpuTest t) => t switch
    {
        CpuTest.Math => [BenchCore.Matmul, BenchCore.Fft, BenchCore.Mandelbrot, BenchCore.Sieve, BenchCore.Rsa, BenchCore.NBody],
        CpuTest.Data => [BenchCore.Json, BenchCore.Xml],
        CpuTest.Crypto => [BenchCore.Zlib, BenchCore.AesGcm, BenchCore.Sha256],
        _ => [BenchCore.Matmul, BenchCore.Fft, BenchCore.Mandelbrot, BenchCore.Json, BenchCore.Zlib, BenchCore.Sha256],
    };
    public static string[] Labels(this CpuTest t) => t.KernelIds().Select(BenchCore.KernelName).ToArray();
    /// <summary>Timed seconds per kernel; tests take ~8 s each.</summary>
    public static double SecondsPerKernel(this CpuTest t) => 8.0 / t.KernelIds().Length;
}

/// <summary>Kernel throughputs of one test (operations per second, all threads together for MULTI) and their geometric mean.</summary>
public sealed record CpuTestRun(CpuTest Test, List<(string name, double rate)> Rates)
{
    public double Value => Rates.Count == 0 || Rates.Any(r => r.rate <= 0) ? 0 : Math.Exp(Rates.Sum(r => Math.Log(r.rate)) / Rates.Count);
}

public sealed record CpuResult(List<CpuTestRun> Runs)
{
    public double Value(CpuTest t) => Runs.FirstOrDefault(r => r.Test == t)?.Value ?? 0;
    public int Points(CpuTest t) => CpuScore.Points(t, Value(t));
    public int Score => CpuScore.Overall(CpuTests.All.Select(Points).ToList());
}

public static class CpuScore
{
    // geometric-mean kernel ops/s worth 6000 points (the Honor, 2026-10-26 — see Benchmark.kt)
    public const double RefMath = 1469, RefData = 647, RefCrypto = 580, RefMulti = 5003;
    public static double Ref(CpuTest t) => t switch { CpuTest.Math => RefMath, CpuTest.Data => RefData, CpuTest.Crypto => RefCrypto, _ => RefMulti };
    public static int Points(CpuTest t, double value) => BenchmarkScore.Round(6000 * Math.Max(value, 0) / Ref(t));
    public static int Overall(IReadOnlyCollection<int> points) => BenchmarkScore.Geomean(points);
}

// ── MEM ──

public enum MemTest { Ram = 1, Storage, Database }

public static class MemTests
{
    public static readonly MemTest[] All = [MemTest.Ram, MemTest.Storage, MemTest.Database];
    public static int Number(this MemTest t) => (int)t;
    public static string Title(this MemTest t) => t switch { MemTest.Ram => "RAM", MemTest.Storage => "STORAGE", _ => "DATABASE" };
    public static int Seconds(this MemTest t) => t switch { MemTest.Ram => 10, MemTest.Storage => 12, _ => 8 };
    public static string[] Labels(this MemTest t) => t switch
    {
        MemTest.Ram => ["READ", "WRITE", "COPY", "LATENCY"],
        MemTest.Storage => ["SEQ WRITE", "SEQ READ", "RANDOM READ", "RANDOM WRITE"],
        _ => ["INSERT", "UPDATE", "QUERY"],
    };
}

public sealed record RamResult(double Read, double Write, double Copy, double LatencyNs);
public sealed record StorageResult(double SeqRead, double SeqWrite, double RandomRead, double RandomWrite, bool Direct, int FileMb);
public sealed record DbResult(double Insert, double Update, double Query);

public sealed record MemResult(RamResult Ram, StorageResult Storage, DbResult Db)
{
    public int RamPoints => MemScore.Ram(Ram.Read, Ram.Write, Ram.Copy, Ram.LatencyNs);
    public int StoragePoints => MemScore.Storage(Storage.SeqRead, Storage.SeqWrite, Storage.RandomRead, Storage.RandomWrite);
    public int DbPoints => MemScore.Db(Db.Insert, Db.Update, Db.Query);
    public int Score => MemScore.Overall([RamPoints, StoragePoints, DbPoints]);
}

public static class MemScore
{
    public const double RefRead = 57.8, RefWrite = 28.4, RefCopy = 18.7, RefLatency = 245.0;           // GB/s, ns
    public const double RefSeqRead = 1750, RefSeqWrite = 1300, RefRandomRead = 29_000, RefRandomWrite = 13_400;
    public const double RefInsert = 9700, RefUpdate = 36_000, RefQuery = 20_300;

    /// <summary>6000 × the geometric mean of value ÷ reference; 0 if any part is 0.</summary>
    private static int Geo(double[] ratios) =>
        ratios.Any(r => r <= 0) ? 0 : BenchmarkScore.Round(6000 * Math.Exp(ratios.Sum(Math.Log) / ratios.Length));
    public static int Ram(double read, double write, double copy, double latencyNs) =>
        latencyNs > 0 ? Geo([read / RefRead, write / RefWrite, copy / RefCopy, RefLatency / latencyNs]) : 0;
    public static int Storage(double sr, double sw, double rr, double rw) =>
        Geo([sr / RefSeqRead, sw / RefSeqWrite, rr / RefRandomRead, rw / RefRandomWrite]);
    public static int Db(double insert, double update, double query) => Geo([insert / RefInsert, update / RefUpdate, query / RefQuery]);
    public static int Overall(IReadOnlyCollection<int> points) => BenchmarkScore.Geomean(points);
}

// ── UX ──

public enum UxTest { Web = 1, Scroll, Gallery, Photo, Video, Export, Qr }

public static class UxTests
{
    public static readonly UxTest[] All = [UxTest.Web, UxTest.Scroll, UxTest.Gallery, UxTest.Photo, UxTest.Video, UxTest.Export, UxTest.Qr];
    public static int Number(this UxTest t) => (int)t;
    public static string Title(this UxTest t) => t switch
    {
        UxTest.Web => "WEB BROWSING", UxTest.Scroll => "WEB SCROLLING", UxTest.Gallery => "PHOTO GALLERY", UxTest.Photo => "PHOTO EDITING",
        UxTest.Video => "VIDEO PLAYBACK", UxTest.Export => "VIDEO EXPORT", _ => "QR SCANNING",
    };
    /// <summary>The test's page (assets\web), for the browser tests.</summary>
    public static string? Page(this UxTest t) => t switch { UxTest.Web => "bench.html", UxTest.Scroll => "scroll.html", UxTest.Gallery => "gallery.html", _ => null };
    public static int Seconds(this UxTest t) => t switch
    {
        UxTest.Web => 36, UxTest.Scroll => 20, UxTest.Gallery => 16, UxTest.Photo => 15, UxTest.Video => 28, UxTest.Export => 26, _ => 24,
    };
}

public sealed record WebResult(double Layout, double Canvas, double Css, double Svg, double Js);
public sealed record ScrollResult(double Speed, double DroppedPct);
public sealed record GalleryResult(double Jpeg, double Webp, double Avif);

public enum VideoClip { Avc4k, Hevc4k, Vp94k, Av14k, Hevc8k, Av18k }
public enum ExportJob { Avc4k, Hevc4k, Av14k, Hevc8k }

public sealed record VideoCodecResult(VideoClip Clip, double Fps, string? Decoder, bool Hardware, string? Error = null);

public sealed record VideoResult(List<VideoCodecResult> Codecs)
{
    public double Fps(VideoClip c) => Codecs.FirstOrDefault(x => x.Clip == c)?.Fps ?? 0;
    public int HardwareMask => Codecs.Where(c => c.Hardware && c.Fps > 0).Aggregate(0, (m, c) => m | (1 << (int)c.Clip));
}

public sealed record ExportJobResult(ExportJob Job, double Fps, double Psnr, string? Encoder, bool Hardware, string? Error = null)
{
    public double Counted => Psnr >= UxScore.MinPsnr ? Fps : 0;
}

public sealed record ExportResult(List<ExportJobResult> Jobs)
{
    public ExportJobResult? Job(ExportJob j) => Jobs.FirstOrDefault(x => x.Job == j);
    public double Fps(ExportJob j) => Job(j)?.Fps ?? 0;
    public double Psnr(ExportJob j) => Job(j)?.Psnr ?? 0;
    public double Counted(ExportJob j) => Job(j)?.Counted ?? 0;
    public int HardwareMask => Jobs.Where(j => j.Hardware && j.Fps > 0).Aggregate(0, (m, j) => m | (1 << (int)j.Job));
}

/// <summary>ZXing's (zxing-cpp's) rate is scored; the platform's own scanner is reported.</summary>
public sealed record QrResult(double Rate, int Decoded, int Missed, double? ReferenceRate, string ReferenceName, string? ReferenceError = null);

public sealed record UxResult(WebResult? Web, ScrollResult? Scroll, GalleryResult? Gallery, double PhotoMps, VideoResult? Video,
    ExportResult? Export, QrResult? Qr, string? Error = null)
{
    public int WebPoints => Web is { } w ? UxScore.WebPoints(w) : 0;
    public int ScrollPoints => UxScore.ScrollPoints(Scroll?.Speed ?? 0);
    public int GalleryPoints => Gallery is { } g ? UxScore.GalleryPoints(g) : 0;
    public int PhotoPoints => UxScore.PhotoPoints(PhotoMps);
    public int VideoPoints => Video is { } v ? UxScore.VideoPoints(v) : 0;
    public int ExportPoints => Export is { } e ? UxScore.ExportPoints(e) : 0;
    public int QrPoints => UxScore.QrPoints(Qr?.Rate ?? 0);
    public int Score => UxScore.Overall([WebPoints, ScrollPoints, GalleryPoints, PhotoPoints, VideoPoints, QrPoints], ExportPoints);
}

public static class UxScore
{
    public const double RefLayout = 1320, RefCanvas = 2730, RefCss = 1980, RefSvg = 3130, RefJs = 2160;
    public const double RefScroll = 66_600;
    public const double RefJpeg = 122, RefWebp = 99, RefAvif = 103;
    public const double RefPhoto = 397;
    public const double RefVideo = 298;
    // PROVISIONAL until the Honor's version-4 report (as on Android, iOS and the server)
    public const double RefVideo8k = 74.5, RefExport = 100, RefExport8k = 30;
    public const double QrWeight = 10.67;
    /// <summary>dB on the lower third: honest encodes 29.7–33.9, a starved one 24.2 — below this the export is broken.</summary>
    public const double MinPsnr = 26;

    public static int WebPoints(WebResult r)
    {
        double[] parts = [r.Layout / RefLayout, r.Canvas / RefCanvas, r.Css / RefCss, r.Svg / RefSvg, r.Js / RefJs];
        return parts.Any(p => p <= 0) ? 0 : BenchmarkScore.Round(6000 * Math.Exp(parts.Sum(Math.Log) / parts.Length));
    }
    public static int ScrollPoints(double speed) => BenchmarkScore.Round(6000 * Math.Max(speed, 0) / RefScroll);
    public static int GalleryPoints(GalleryResult r) =>
        BenchmarkScore.Round(6000 * (Math.Max(r.Jpeg, 0) / RefJpeg + Math.Max(r.Webp, 0) / RefWebp + Math.Max(r.Avif, 0) / RefAvif) / 3);
    public static int PhotoPoints(double mps) => BenchmarkScore.Round(6000 * Math.Max(mps, 0) / RefPhoto);
    public static int VideoPoints(VideoResult r)
    {
        VideoClip[] k4 = [VideoClip.Avc4k, VideoClip.Hevc4k, VideoClip.Vp94k, VideoClip.Av14k], k8 = [VideoClip.Hevc8k, VideoClip.Av18k];
        return BenchmarkScore.Round(6000 * (0.75 * k4.Sum(c => Math.Max(r.Fps(c), 0)) / (RefVideo * k4.Length) +
                                            0.25 * k8.Sum(c => Math.Max(r.Fps(c), 0)) / (RefVideo8k * k8.Length)));
    }
    public static int ExportPoints(ExportResult r)
    {
        ExportJob[] k4 = [ExportJob.Avc4k, ExportJob.Hevc4k, ExportJob.Av14k];
        return BenchmarkScore.Round(6000 * (0.75 * k4.Sum(j => Math.Max(r.Counted(j), 0)) / (RefExport * k4.Length) +
                                            0.25 * Math.Max(r.Counted(ExportJob.Hevc8k), 0) / RefExport8k));
    }
    public static int QrPoints(double rate) => BenchmarkScore.Round(Math.Max(rate, 0) * QrWeight);
    /// <summary>The six everyday tests' geometric mean six sevenths, the export one seventh.</summary>
    public static int Overall(int[] everyday, int export)
    {
        int g = BenchmarkScore.Geomean(everyday);
        return g <= 0 ? 0 : BenchmarkScore.Round((6.0 * g + Math.Max(export, 0)) / 7);
    }
}

// ── AI ──

public enum AiAccel { Cpu, Gpu, Npu }
public enum AiTest { Classify = 1, Detect, Segment, Upscale, Depth, Sound }

public static class AiTests
{
    public static readonly AiTest[] All = [AiTest.Classify, AiTest.Detect, AiTest.Segment, AiTest.Upscale, AiTest.Depth, AiTest.Sound];
    public static readonly AiAccel[] Accels = [AiAccel.Cpu, AiAccel.Gpu, AiAccel.Npu];
    public static string Label(this AiAccel a) => a switch { AiAccel.Cpu => "CPU", AiAccel.Gpu => "GPU", _ => "NPU" };
}

public sealed record AiRun(AiTest Test, AiAccel Accel, double Ips, string? Error = null);

public sealed record AiResult(List<AiRun> Runs, string? NpuRuntime, string? NpuProblem = null)
{
    public double Ips(AiTest t, AiAccel a) => Runs.FirstOrDefault(r => r.Test == t && r.Accel == a)?.Ips ?? 0;
    public int AccelScore(AiAccel a) => AiScore.Accel(a, AiTests.All.Select(t => Ips(t, a)).ToArray());
    public int Score => AiScore.Overall(AccelScore(AiAccel.Cpu), AccelScore(AiAccel.Gpu), AccelScore(AiAccel.Npu));
}

public static class AiScore
{
    public static readonly double[] RefCpu = [330, 18.2, 4.71, 2.65, 42, 2890];
    public static readonly double[] RefGpu = [658, 85.5, 33.1, 92.8, 182, 1470];
    public static readonly double[] RefNpu = [1690, 473, 229, 774, 993, 2100];
    public static double Ref(AiTest t, AiAccel a) => (a switch { AiAccel.Cpu => RefCpu, AiAccel.Gpu => RefGpu, _ => RefNpu })[(int)t - 1];
    public static int Accel(AiAccel a, double[] ips) =>
        BenchmarkScore.Round(AiTests.All.Select((t, i) => 6000 * Math.Max(ips[i], 0) / Ref(t, a)).Sum() / AiTests.All.Length);
    public static int Overall(int cpu, int gpu, int npu) => BenchmarkScore.Round((Math.Max(cpu, 0) + Math.Max(gpu, 0) + Math.Max(npu, 0)) / 3.0);
}

// ── LLM ──

public enum LlmAccel { Cpu, Gpu }
public enum LlmPhase { Build, Warmup, Prefill, Decode }

public static class LlmAccels
{
    public static readonly LlmAccel[] All = [LlmAccel.Cpu, LlmAccel.Gpu];
    public static string Label(this LlmAccel a) => a == LlmAccel.Cpu ? "CPU" : "GPU";
    public static int Seconds(this LlmAccel a) => a == LlmAccel.Cpu ? 20 : 16;
}

public sealed record LlmRun(LlmAccel Accel, double Prefill, double Decode, int Threads = 0, string? Error = null, int BatchThreads = 0);

public sealed record LlmResult(List<LlmRun> Runs, string? GpuName, double FreeGb, int LargestModel, string? Error = null)
{
    public LlmRun? Run(LlmAccel a) => Runs.FirstOrDefault(r => r.Accel == a);
    public double Prefill => Runs.Count == 0 ? 0 : Runs.Max(r => r.Prefill);
    public double Decode => Runs.Count == 0 ? 0 : Runs.Max(r => r.Decode);
    public int PrefillPoints => LlmScore.PrefillPoints(Prefill);
    public int DecodePoints => LlmScore.DecodePoints(Decode);
    public int Score => LlmScore.Overall(Prefill, Decode);
}

public static class LlmScore
{
    public const double RefPrefill = 362.7, RefDecode = 17.23;
    public static int PrefillPoints(double tps) => BenchmarkScore.Round(6000 * Math.Max(tps, 0) / RefPrefill);
    public static int DecodePoints(double tps) => BenchmarkScore.Round(6000 * Math.Max(tps, 0) / RefDecode);
    public static int Overall(double prefill, double decode) => BenchmarkScore.Geomean([PrefillPoints(prefill), DecodePoints(decode)]);
}

// ── the whole run ──

public sealed class BenchmarkResult
{
    public CpuResult? Cpu;
    public MemResult? Mem;
    public List<GpuTestResult> Gpu = [];
    public string GpuName = "";
    public string? RayTracingProblem;
    public UxResult? Ux;
    public AiResult? Ai;
    public LlmResult? Llm;
    public int ValidityFlags;
    public float StartCpuShare = 1;
    public int QuietWaitSec;
    /// <summary>Why a section the Windows app doesn't run yet is missing (shown on the result; such a run can't be submitted).</summary>
    public List<string> NotYet = [];

    public int CpuScore => Cpu?.Score ?? 0;
    public int MemScore => Mem?.Score ?? 0;
    public int GpuScore => BenchmarkScore.Overall(Gpu);
    public int UxScore => Ux?.Score ?? 0;
    public int AiScore => Ai?.Score ?? 0;
    public int LlmScore => Llm?.Score ?? 0;
    public int Total => CpuScore + MemScore + GpuScore + UxScore + AiScore + LlmScore;
    public bool IsVerified => ValidityFlags == 0;
    /// <summary>Every section ran — what the server needs to accept the run.</summary>
    public bool IsComplete => Cpu != null && Mem != null && Gpu.Count == GpuTests.All.Length && Ux != null && Ai != null && Llm != null;
}
