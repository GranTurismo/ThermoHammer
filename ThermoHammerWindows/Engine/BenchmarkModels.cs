namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK — the same six sections and the same scoring as the Android app
// (engine/Benchmark.kt), the iPhone app (BenchmarkModels.swift) and the server
// (BenchmarkScoring.cs): CPU + MEM + GPU + UX + AI + LLM, every test 6000 points
// on the Snapdragon 8 Elite reference phone (Honor VKP-NX9) in Performance mode —
// the median of its runs of 2026-11-23, scoring version 5. Change a number here
// only together with those three (BenchmarkScoreTests pins the same medians).
// ─────────────────────────────────────────────────────────────────────────────

public static class BenchmarkScore
{
    /// <summary>Bump when a scene, the resolution or the scoring changes — scores compare only within a version.</summary>
    public const int Version = 5;
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
    /// <summary>fps → points: the reference phone's 129.85, 28.98 and 14.12 fps = 6000.</summary>
    public static double Weight(this GpuTest t) => t switch { GpuTest.LakeValley => 46.21, GpuTest.NeonCity => 207.0, _ => 424.9 };
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
    // geometric-mean kernel ops/s worth 6000 points (the Honor in Performance mode, 2026-11-23 — see Benchmark.kt)
    public const double RefMath = 3039, RefData = 1533, RefCrypto = 1184, RefMulti = 8884;
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
    public static int Seconds(this MemTest t) => t switch { MemTest.Ram => 10, MemTest.Storage => 12, _ => 14 };   // the database's steps each warm up first (MemBench.WarmUp)
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
    public const double RefRead = 63.5, RefWrite = 28.3, RefCopy = 19.7, RefLatency = 134.0;           // GB/s, ns
    public const double RefSeqRead = 1780, RefSeqWrite = 1309, RefRandomRead = 24_700, RefRandomWrite = 13_380;
    // version 5: the database steps are timed after their warm-up (MemBench.WarmUp) — the old references timed the climb
    public const double RefInsert = 15_700, RefUpdate = 389_000, RefQuery = 43_800;

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
    public const double RefLayout = 3061, RefCanvas = 4818, RefCss = 3061, RefSvg = 4898, RefJs = 4002;
    public const double RefScroll = 66_560;
    public const double RefJpeg = 229.3, RefWebp = 167.8, RefAvif = 173.6;
    public const double RefPhoto = 350;
    public const double RefVideo = 296;
    // 8K decode: the mean of the Honor's HEVC (91.1) and AV1 (75.0) rates. Export: one of its 4K hardware encoders' rate (it has no
    // AV1 encoder, so it scores two thirds of the 4K part); 8K export = a quarter of that (the same pixel rate), as it encodes no 8K
    public const double RefVideo8k = 83.1, RefExport = 136.3, RefExport8k = 34.1;
    public const double QrWeight = 5.122;   // zxing-cpp decodes/s → points: the Honor's 1,171.5/s = 6000
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
    /// <summary>The payload's and the record's layout: each model's rate on the CPU, the GPU and the NPU (always 0 here).</summary>
    public static readonly AiAccel[] Accels = [AiAccel.Cpu, AiAccel.Gpu, AiAccel.Npu];
    /// <summary>What runs and counts in version 5: the CPU and the GPU. The NPU isn't scored on any platform (no NPU runtime reaches every device).</summary>
    public static readonly AiAccel[] Scored = [AiAccel.Cpu, AiAccel.Gpu];
    public static string Label(this AiAccel a) => a switch { AiAccel.Cpu => "CPU", AiAccel.Gpu => "GPU", _ => "NPU" };
    /// <summary>What the models run in on this accelerator (AiSection's labels): the phones run INT8 everywhere, the PC's CPU and NPU the
    /// INT8 (QDQ) model, its GPU (DirectML) the same model as float16 with the weights dequantized.</summary>
    public static string Precision(this AiAccel a) => a == AiAccel.Gpu ? "FP16" : "INT8";
}

/// <summary>Agreement: how well a GPU's or an NPU's answer agreed with the CPU's for this model (AiAgreement; 1 is a match); null where there was none to compare.</summary>
public sealed record AiRun(AiTest Test, AiAccel Accel, double Ips, string? Error = null, double? Agreement = null);

public sealed record AiResult(List<AiRun> Runs, string? NpuRuntime, string? NpuProblem = null)
{
    public double Ips(AiTest t, AiAccel a) => Runs.FirstOrDefault(r => r.Test == t && r.Accel == a)?.Ips ?? 0;
    public int AccelScore(AiAccel a) => AiScore.Accel(a, AiTests.All.Select(t => Ips(t, a)).ToArray());
    /// <summary>Version 5: the CPU and the GPU, half each.</summary>
    public int Score => AiScore.Overall(AccelScore(AiAccel.Cpu), AccelScore(AiAccel.Gpu));
}

public static class AiScore
{
    public static readonly double[] RefCpu = [543.6, 28.67, 7.73, 4.59, 64.5, 3968];
    // version 5: the phone's GPU re-measured on the path that answers right (LiteRT's OpenGL backend on the Adreno 830)
    public static readonly double[] RefGpu = [280.7, 36.7, 16.6, 38.8, 74.2, 582];
    public static readonly double[] RefNpu = [1726, 485, 214.6, 689.3, 986, 2154];
    public static double Ref(AiTest t, AiAccel a) => (a switch { AiAccel.Cpu => RefCpu, AiAccel.Gpu => RefGpu, _ => RefNpu })[(int)t - 1];
    public static int Accel(AiAccel a, double[] ips) =>
        BenchmarkScore.Round(AiTests.All.Select((t, i) => 6000 * Math.Max(ips[i], 0) / Ref(t, a)).Sum() / AiTests.All.Length);
    /// <summary>The CPU and the GPU each half; one that can't run the models scores 0 there.</summary>
    public static int Overall(int cpu, int gpu) => BenchmarkScore.Round((Math.Max(cpu, 0) + Math.Max(gpu, 0)) / 2.0);
}

// ── LLM ──

/// <summary>The engines llama.cpp runs Gemma 3 1B on: version 5 scores the CPU and the GPU, half each (the NPU isn't scored on any platform).</summary>
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
    /// <summary>One engine's points: its prompt and reply rates against the same engine's reference (LlmScore).</summary>
    public int Points(LlmAccel a) => Run(a) is { } r ? LlmScore.Engine(a, r.Prefill, r.Decode) : 0;
    /// <summary>The fastest engine at each phase — what a chat app on this PC could get (shown, not scored).</summary>
    public double Prefill => Runs.Count == 0 ? 0 : Runs.Max(r => r.Prefill);
    public double Decode => Runs.Count == 0 ? 0 : Runs.Max(r => r.Decode);
    /// <summary>The best engine's points for each phase, against that engine's own reference (CSV, the report; shown, not scored).</summary>
    public int PrefillPoints => Math.Max(Run(LlmAccel.Cpu) is { } c ? LlmScore.Phase(c.Prefill, LlmScore.RefCpuPrefill) : 0,
        Run(LlmAccel.Gpu) is { } g ? LlmScore.Phase(g.Prefill, LlmScore.RefGpuPrefill) : 0);
    public int DecodePoints => Math.Max(Run(LlmAccel.Cpu) is { } c ? LlmScore.Phase(c.Decode, LlmScore.RefCpuDecode) : 0,
        Run(LlmAccel.Gpu) is { } g ? LlmScore.Phase(g.Decode, LlmScore.RefGpuDecode) : 0);
    /// <summary>Version 5: the CPU and the GPU each count half — an engine that can't run the task counts 0.</summary>
    public int Score => LlmScore.Overall(Points(LlmAccel.Cpu), Points(LlmAccel.Gpu));
}

public static class LlmScore
{
    // Version 5: Gemma 3 1B (4-bit) through llama.cpp on the CPU and the GPU (seeded weights, the model's exact shape), each
    // against the same engine's reference, then the two averaged (BENCHMARK_V5_PLAN.md). The NPU isn't scored on any platform:
    // no way to run it reaches every device yet. Tokens per second worth 6000 points per engine: the Honor VKP-NX9 in
    // Performance mode (Benchmark.kt).
    public const double RefCpuPrefill = 160, RefCpuDecode = 55;
    public const double RefGpuPrefill = 890, RefGpuDecode = 32;

    /// <summary>One phase's points against `reference` tokens/s.</summary>
    public static int Phase(double tokensPerS, double reference) => BenchmarkScore.Round(6000 * Math.Max(tokensPerS, 0) / reference);

    /// <summary>One engine: 6000 × the geometric mean of its prompt and reply rates over its references; 0 if either is 0.</summary>
    public static int Engine(double prefill, double decode, double refPrefill, double refDecode) =>
        !(prefill > 0) || !(decode > 0) ? 0 : BenchmarkScore.Round(6000 * Math.Sqrt(prefill / refPrefill * (decode / refDecode)));

    public static int Engine(LlmAccel a, double prefill, double decode) => a == LlmAccel.Cpu
        ? Engine(prefill, decode, RefCpuPrefill, RefCpuDecode)
        : Engine(prefill, decode, RefGpuPrefill, RefGpuDecode);

    /// <summary>The CPU and the GPU each count half; one the PC lacks or can't run the task on scores 0 there.</summary>
    public static int Overall(int cpu, int gpu) => BenchmarkScore.Round((Math.Max(cpu, 0) + Math.Max(gpu, 0)) / 2.0);
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
