using System.Globalization;
using System.Net.Http.Json;
using System.Text;
using ThermoHammer.Engine.Ai;
using ThermoHammer.Engine.Ux;

namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK SUBMISSION — the same payload and signed canonical string as the
// phone apps (Android's BenchmarkPayload, iOS's BenchmarkUpload.swift); the
// server re-scores every run from these raw results.
// ─────────────────────────────────────────────────────────────────────────────

public sealed record LlmRecord(double PrefillCpu = 0, double DecodeCpu = 0, double PrefillGpu = 0, double DecodeGpu = 0, string? Gpu = null, double FreeGb = 0);

public sealed record MediaRecord(double Video8kHevc = 0, double Video8kAv1 = 0, double ExportAvc = 0, double ExportHevc = 0, double ExportAv1 = 0,
    double Export8kHevc = 0, double ExportAvcPsnr = 0, double ExportHevcPsnr = 0, double ExportAv1Psnr = 0, double Export8kHevcPsnr = 0,
    int ExportHardware = 0);

/// <summary>One finished run as kept on the PC and submitted: raw results only (2 decimals — what is hashed).</summary>
public sealed record BenchmarkRecord
{
    public Guid Id = Guid.NewGuid();
    public DateTime Timestamp = DateTime.Now;
    public int Version = BenchmarkScore.Version;
    public int Score;
    public string GpuName = "";
    public double Test1Fps, Test1LowFps, Test2Fps, Test2LowFps, Test3Fps, Test3LowFps;
    public bool RayTracing;
    public double WebLayout, WebCanvas, WebCss, WebSvg, WebJs;
    public double QrRate;
    public double? ReferenceQrRate;
    public double CpuMath, CpuData, CpuCrypto, CpuMulti;
    public double ScrollSpeed, ScrollDropped;
    public double GalleryJpeg, GalleryWebp, GalleryAvif;
    public double PhotoEdit;
    public double VideoAvc, VideoHevc, VideoVp9, VideoAv1;
    public int VideoHardware;
    public double[] Ai = new double[18];   // AiTest × AiAccel, test-major
    public string? AiNpuRuntime;
    public double MemRead, MemWrite, MemCopy, MemLatency;
    public double StorageSeqRead, StorageSeqWrite, StorageRandomRead, StorageRandomWrite;
    public bool StorageDirect;
    public double DbInsert, DbUpdate, DbQuery;
    public LlmRecord? Llm;
    public MediaRecord? Media;
    public string? OsVersion;
    public int ValidityFlags;
    public bool Complete;
    public bool Submitted;

    public static BenchmarkRecord From(BenchmarkResult r)
    {
        static double Q(double v) => BenchmarkScore.Round2(v);
        GpuTestResult? G(GpuTest t) => r.Gpu.FirstOrDefault(x => x.Test == t);
        var ux = r.Ux;
        var v = ux?.Video;
        var e = ux?.Export;
        var cpu = r.Cpu;
        var m = r.Mem;
        var l = r.Llm;
        return new BenchmarkRecord
        {
            Score = r.Total,
            GpuName = r.GpuName,
            Test1Fps = Q(G(GpuTest.LakeValley)?.AvgFps ?? 0), Test1LowFps = Q(G(GpuTest.LakeValley)?.LowFps ?? 0),
            Test2Fps = Q(G(GpuTest.NeonCity)?.AvgFps ?? 0), Test2LowFps = Q(G(GpuTest.NeonCity)?.LowFps ?? 0),
            Test3Fps = Q(G(GpuTest.NeonCityRT)?.AvgFps ?? 0), Test3LowFps = Q(G(GpuTest.NeonCityRT)?.LowFps ?? 0),
            RayTracing = G(GpuTest.NeonCityRT)?.Supported ?? false,
            WebLayout = Q(ux?.Web?.Layout ?? 0), WebCanvas = Q(ux?.Web?.Canvas ?? 0), WebCss = Q(ux?.Web?.Css ?? 0),
            WebSvg = Q(ux?.Web?.Svg ?? 0), WebJs = Q(ux?.Web?.Js ?? 0),
            QrRate = Q(ux?.Qr?.Rate ?? 0), ReferenceQrRate = ux?.Qr?.ReferenceRate is { } rr ? Q(rr) : null,
            ScrollSpeed = Q(ux?.Scroll?.Speed ?? 0), ScrollDropped = Q(ux?.Scroll?.DroppedPct ?? 0),
            GalleryJpeg = Q(ux?.Gallery?.Jpeg ?? 0), GalleryWebp = Q(ux?.Gallery?.Webp ?? 0), GalleryAvif = Q(ux?.Gallery?.Avif ?? 0),
            PhotoEdit = Q(ux?.PhotoMps ?? 0),
            VideoAvc = Q(v?.Fps(VideoClip.Avc4k) ?? 0), VideoHevc = Q(v?.Fps(VideoClip.Hevc4k) ?? 0),
            VideoVp9 = Q(v?.Fps(VideoClip.Vp94k) ?? 0), VideoAv1 = Q(v?.Fps(VideoClip.Av14k) ?? 0),
            VideoHardware = v?.HardwareMask ?? 0,
            Media = ux == null ? null : new MediaRecord(Q(v?.Fps(VideoClip.Hevc8k) ?? 0), Q(v?.Fps(VideoClip.Av18k) ?? 0),
                Q(e?.Fps(ExportJob.Avc4k) ?? 0), Q(e?.Fps(ExportJob.Hevc4k) ?? 0), Q(e?.Fps(ExportJob.Av14k) ?? 0), Q(e?.Fps(ExportJob.Hevc8k) ?? 0),
                Q(e?.Psnr(ExportJob.Avc4k) ?? 0), Q(e?.Psnr(ExportJob.Hevc4k) ?? 0), Q(e?.Psnr(ExportJob.Av14k) ?? 0), Q(e?.Psnr(ExportJob.Hevc8k) ?? 0),
                e?.HardwareMask ?? 0),
            CpuMath = Q(cpu?.Value(CpuTest.Math) ?? 0), CpuData = Q(cpu?.Value(CpuTest.Data) ?? 0),
            CpuCrypto = Q(cpu?.Value(CpuTest.Crypto) ?? 0), CpuMulti = Q(cpu?.Value(CpuTest.Multi) ?? 0),
            Ai = r.Ai is { } a ? AiTests.All.SelectMany(t => AiTests.Accels.Select(x => Q(a.Ips(t, x)))).ToArray() : new double[18],
            AiNpuRuntime = r.Ai?.NpuRuntime,
            MemRead = Q(m?.Ram.Read ?? 0), MemWrite = Q(m?.Ram.Write ?? 0), MemCopy = Q(m?.Ram.Copy ?? 0), MemLatency = Q(m?.Ram.LatencyNs ?? 0),
            StorageSeqRead = Q(m?.Storage.SeqRead ?? 0), StorageSeqWrite = Q(m?.Storage.SeqWrite ?? 0),
            StorageRandomRead = Q(m?.Storage.RandomRead ?? 0), StorageRandomWrite = Q(m?.Storage.RandomWrite ?? 0),
            StorageDirect = m?.Storage.Direct ?? false,
            DbInsert = Q(m?.Db.Insert ?? 0), DbUpdate = Q(m?.Db.Update ?? 0), DbQuery = Q(m?.Db.Query ?? 0),
            Llm = l == null ? null : new LlmRecord(Q(l.Run(LlmAccel.Cpu)?.Prefill ?? 0), Q(l.Run(LlmAccel.Cpu)?.Decode ?? 0),
                Q(l.Run(LlmAccel.Gpu)?.Prefill ?? 0), Q(l.Run(LlmAccel.Gpu)?.Decode ?? 0), l.GpuName, Q(l.FreeGb)),
            OsVersion = DeviceInfo.OsVersion,
            ValidityFlags = r.ValidityFlags,
            Complete = r.IsComplete,
        };
    }
}

/// <summary>The request body of POST /benchmark — the field names the server (and the phone apps) use.</summary>
public sealed class BenchmarkPayload
{
    public int SessionId;
    public string Hash = "";
    public int BenchmarkVersion;
    public string DeviceManufacturer = "";
    public string DeviceModel = "";
    public int Os = DeviceInfo.OsPlatform;
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
    public double LlmPrefillCpu, LlmDecodeCpu, LlmPrefillGpu, LlmDecodeGpu;
    public string? LlmGpu;
    public double LlmFreeGb;
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
            ValidityFlags.ToString(CultureInfo.InvariantCulture),
        ];
        return string.Join("|", parts);
    }
}

public sealed record BenchmarkSubmitResponse(int Id, int Score, int Rank);

/// <summary>One machine's best benchmark run, as GET /benchmark/leaderboard returns it.</summary>
public sealed record BenchmarkDto(int Id, string DeviceManufacturer, string DeviceModel, string? SocModel, string? GpuName, int Os,
    string OsVersion, int Score, int UxScore, int CpuScore, int AiScore, int MemScore, int LlmScore, int TotalScore, double Test3Fps,
    bool RayTracing, int Runs)
{
    public string Name => DeviceModel.StartsWith(DeviceManufacturer, StringComparison.OrdinalIgnoreCase) ? DeviceModel : $"{DeviceManufacturer} {DeviceModel}";
}

/// <summary>GET /benchmark/{id}: one run's numbers section by section, already written as text by the server — a new measurement needs no app update.</summary>
public sealed record BenchmarkDetail(int Id, DateTime CreatedAt, int BenchmarkVersion, string DeviceManufacturer, string DeviceModel,
    string? SocModel, string? GpuName, int Os, string OsVersion, string? OsBuild, string? AppVersion, int TotalScore, List<BenchmarkDetail.Section> Sections)
{
    public sealed record Section(string Key, string Title, int Score, List<Group> Groups);
    public sealed record Group(string Title, int? Points, List<Row> Rows);
    public sealed record Row(string Label, string Value);
}

/// <summary>GET /benchmark/model: one model's verified runs of the current scoring version.</summary>
public sealed record ModelStats(string DeviceModel, int Runs, ModelStats.Spread? Total, Dictionary<string, int>? Sections,
    Dictionary<string, int>? Parts, List<ModelStats.OsVersionStat>? ByOsVersion)
{
    public sealed record Spread(int Median, int P25, int P75, int Best);
    public sealed record OsVersionStat(string OsVersion, int Runs, int MedianTotal);
}

public static class BenchmarkUpload
{
    public static string AppVersion =>
        typeof(BenchmarkUpload).Assembly.GetName().Version is { } v ? $"{v.Major}.{v.Minor}" : "?";

    public static async Task<BenchmarkSubmitResponse> Submit(BenchmarkRecord r)
    {
        var session = await Api.CreateSession();
        var a = r.Ai;
        var l = r.Llm ?? new LlmRecord();
        var m = r.Media ?? new MediaRecord();
        var p = new BenchmarkPayload
        {
            SessionId = session.Id, BenchmarkVersion = r.Version, DeviceManufacturer = DeviceInfo.Manufacturer, DeviceModel = DeviceInfo.Model,
            OsVersion = DeviceInfo.OsVersion, OsBuild = DeviceInfo.OsBuild, SocModel = DeviceInfo.ShortCpu(DeviceInfo.CpuName), GpuName = r.GpuName,
            Test1Fps = r.Test1Fps, Test1LowFps = r.Test1LowFps, Test2Fps = r.Test2Fps, Test2LowFps = r.Test2LowFps,
            Test3Fps = r.Test3Fps, Test3LowFps = r.Test3LowFps, RayTracing = r.RayTracing,
            WebLayout = r.WebLayout, WebCanvas = r.WebCanvas, WebCss = r.WebCss, WebSvg = r.WebSvg, WebJs = r.WebJs,
            QrRate = r.QrRate, MlkitQrRate = r.ReferenceQrRate,
            CpuMath = r.CpuMath, CpuData = r.CpuData, CpuCrypto = r.CpuCrypto, CpuMulti = r.CpuMulti,
            ScrollSpeed = r.ScrollSpeed, ScrollDropped = r.ScrollDropped,
            GalleryJpeg = r.GalleryJpeg, GalleryWebp = r.GalleryWebp, GalleryAvif = r.GalleryAvif, PhotoEdit = r.PhotoEdit,
            VideoAvc = r.VideoAvc, VideoHevc = r.VideoHevc, VideoVp9 = r.VideoVp9, VideoAv1 = r.VideoAv1,
            Video8kHevc = m.Video8kHevc, Video8kAv1 = m.Video8kAv1, VideoHardware = r.VideoHardware,
            ExportAvc = m.ExportAvc, ExportHevc = m.ExportHevc, ExportAv1 = m.ExportAv1, Export8kHevc = m.Export8kHevc,
            ExportAvcPsnr = m.ExportAvcPsnr, ExportHevcPsnr = m.ExportHevcPsnr, ExportAv1Psnr = m.ExportAv1Psnr,
            Export8kHevcPsnr = m.Export8kHevcPsnr, ExportHardware = m.ExportHardware,
            AiClassifyCpu = a[0], AiClassifyGpu = a[1], AiClassifyNpu = a[2], AiDetectCpu = a[3], AiDetectGpu = a[4], AiDetectNpu = a[5],
            AiSegmentCpu = a[6], AiSegmentGpu = a[7], AiSegmentNpu = a[8], AiUpscaleCpu = a[9], AiUpscaleGpu = a[10], AiUpscaleNpu = a[11],
            AiDepthCpu = a[12], AiDepthGpu = a[13], AiDepthNpu = a[14], AiSoundCpu = a[15], AiSoundGpu = a[16], AiSoundNpu = a[17],
            AiNpuRuntime = r.AiNpuRuntime,
            MemRead = r.MemRead, MemWrite = r.MemWrite, MemCopy = r.MemCopy, MemLatency = r.MemLatency,
            StorageSeqRead = r.StorageSeqRead, StorageSeqWrite = r.StorageSeqWrite,
            StorageRandomRead = r.StorageRandomRead, StorageRandomWrite = r.StorageRandomWrite, StorageDirect = r.StorageDirect,
            DbInsert = r.DbInsert, DbUpdate = r.DbUpdate, DbQuery = r.DbQuery,
            LlmPrefillCpu = l.PrefillCpu, LlmDecodeCpu = l.DecodeCpu, LlmPrefillGpu = l.PrefillGpu, LlmDecodeGpu = l.DecodeGpu,
            LlmGpu = l.Gpu, LlmFreeGb = l.FreeGb,
            ValidityFlags = r.ValidityFlags,
            AppVersion = AppVersion,
        };
        p.Hash = ThermoHasher.Hmac(session.EncryptionKey, p.Canonical());
        using var resp = await Api.Http.PostAsJsonAsync("benchmark", p, Api.Json);
        if (!resp.IsSuccessStatusCode)
            throw new BenchException($"Server rejected the run {await resp.Content.ReadAsStringAsync()}");
        return (await resp.Content.ReadFromJsonAsync<BenchmarkSubmitResponse>(Api.Json))!;
    }

    public static async Task<List<BenchmarkDto>> FetchLeaderboard()
    {
        using var r = await Api.Http.GetAsync("benchmark/leaderboard");
        if (!r.IsSuccessStatusCode) throw new BenchException($"Leaderboard unavailable (HTTP {(int)r.StatusCode})");
        return (await r.Content.ReadFromJsonAsync<List<BenchmarkDto>>(Api.Json)) ?? [];
    }

    /// <summary>Null when the run is no longer on the board (the server answers 404).</summary>
    public static async Task<BenchmarkDetail?> FetchDetail(int id)
    {
        using var r = await Api.Http.GetAsync($"benchmark/{id}");
        if (r.StatusCode == System.Net.HttpStatusCode.NotFound) return null;
        if (!r.IsSuccessStatusCode) throw new BenchException($"Run details unavailable (HTTP {(int)r.StatusCode})");
        return await r.Content.ReadFromJsonAsync<BenchmarkDetail>(Api.Json);
    }

    public static async Task<ModelStats?> FetchModelStats(string model)
    {
        using var r = await Api.Http.GetAsync($"benchmark/model?model={Uri.EscapeDataString(model)}&os={DeviceInfo.OsPlatform}");
        if (!r.IsSuccessStatusCode) return null;
        return await r.Content.ReadFromJsonAsync<ModelStats>(Api.Json);
    }
}

/// <summary>The PC's benchmark runs — the latest 30.</summary>
public sealed class BenchmarkHistoryStore
{
    public static readonly BenchmarkHistoryStore Shared = new();
    private const string File = "benchmark_history.json";
    public List<BenchmarkRecord> Records { get; private set; } = AppData.Load<List<BenchmarkRecord>>(File) ?? [];

    public BenchmarkRecord? Best => Records.Where(r => r.Version == BenchmarkScore.Version && r.Complete).MaxBy(r => r.Score);

    public void Add(BenchmarkRecord r) { Records = Records.Append(r).TakeLast(30).ToList(); AppData.Save(File, Records); }

    public void MarkSubmitted(Guid id)
    {
        foreach (var r in Records.Where(r => r.Id == id)) r.Submitted = true;
        AppData.Save(File, Records);
    }
}

/// <summary>Plain-text report of a benchmark run — every raw number, for sharing or pasting into a bug report (the phones' format).</summary>
public static class BenchmarkReport
{
    public static string Text(BenchmarkResult r, string time)
    {
        var s = new StringBuilder();
        void Line(string t) => s.Append(t).Append('\n');
        static string F(double v, int d = 2) => v.ToString("F" + d, CultureInfo.InvariantCulture);
        static string Pts(int v) => v.ToString("N0", CultureInfo.InvariantCulture);
        Line($"ThermoHammer benchmark report · app {BenchmarkUpload.AppVersion} (Windows {DeviceInfo.Architecture}) · {time}");
        Line($"{DeviceInfo.Manufacturer} {DeviceInfo.Model} · {DeviceInfo.OsLabel(DeviceInfo.OsPlatform, DeviceInfo.OsVersion)} ({DeviceInfo.OsBuild}) · CPU {DeviceInfo.CpuName} · GPU {(r.GpuName.Length == 0 ? "?" : r.GpuName)} · {F(DeviceInfo.TotalRamGb, 1)} GB RAM");
        Line($"TOTAL {Pts(r.Total)} = CPU {Pts(r.CpuScore)} + MEM {Pts(r.MemScore)} + GPU {Pts(r.GpuScore)} + UX {Pts(r.UxScore)} + AI {Pts(r.AiScore)} + LLM {Pts(r.LlmScore)} · validity flags {r.ValidityFlags}");
        Line($"Start · CPU share {Math.Round(r.StartCpuShare * 100)}% · waited {r.QuietWaitSec} s for background work · crypto {BenchCore.CryptoPath}");
        if (r.Cpu is { } cpu)
            foreach (var t in CpuTests.All)
            {
                var run = cpu.Runs.FirstOrDefault(x => x.Test == t);
                Line($"CPU {t.Number()} {t.Title()} · {Pts(cpu.Points(t))} pts · {F(cpu.Value(t))} · " +
                     (run == null ? "not run" : string.Join(", ", run.Rates.Select(x => $"{x.name} {F(x.rate, 1)}"))));
            }
        if (r.Mem is { } m)
        {
            Line($"MEM 1 RAM · {Pts(m.RamPoints)} pts · read {F(m.Ram.Read)}, write {F(m.Ram.Write)}, copy {F(m.Ram.Copy)} GB/s · latency {F(m.Ram.LatencyNs)} ns");
            Line($"MEM 2 STORAGE · {Pts(m.StoragePoints)} pts · seq read {F(m.Storage.SeqRead, 0)}, seq write {F(m.Storage.SeqWrite, 0)} MB/s · " +
                 $"random read {F(m.Storage.RandomRead, 0)}, random write {F(m.Storage.RandomWrite, 0)} IOPS · {m.Storage.FileMb} MB file, {(m.Storage.Direct ? "direct I/O" : "cached I/O")}");
            Line($"MEM 3 DATABASE · {Pts(m.DbPoints)} pts · insert {F(m.Db.Insert, 0)}, update {F(m.Db.Update, 0)} tx/s · query {F(m.Db.Query, 0)}/s");
        }
        foreach (var t in GpuTests.All)
        {
            var g = r.Gpu.FirstOrDefault(x => x.Test == t);
            if (g == null) { Line($"GPU {t.Number()} {t.Title()} · not run"); continue; }
            if (!g.Supported) { Line($"GPU {t.Number()} {t.Title()} · 0 pts · not run: {r.RayTracingProblem ?? "unsupported"}"); continue; }
            Line($"GPU {t.Number()} {t.Title()} · {Pts(g.Points)} pts · avg {F(g.AvgFps)} fps · 1% low {F(g.LowFps)} · stability {F(g.StabilityPct, 0)}% · {g.Frames} frames");
        }
        if (r.Ux is { } ux)
        {
            Line($"UX 1 WEB BROWSING · {Pts(ux.WebPoints)} pts · " + (ux.Web is { } w ? $"layout {F(w.Layout, 0)}, canvas {F(w.Canvas, 0)}, css {F(w.Css, 0)}, svg {F(w.Svg, 0)}, js {F(w.Js, 1)}" : "did not finish"));
            Line($"UX 2 WEB SCROLLING · {Pts(ux.ScrollPoints)} pts · " + (ux.Scroll is { } sc ? $"held {F(sc.Speed, 0)} px/s, {F(sc.DroppedPct, 1)}% frames dropped" : "did not finish"));
            Line($"UX 3 PHOTO GALLERY · {Pts(ux.GalleryPoints)} pts · " + (ux.Gallery is { } ga ? $"JPEG {F(ga.Jpeg)}, WebP {F(ga.Webp)}, AVIF {F(ga.Avif)} MP/s" : "did not finish"));
            Line($"UX 4 PHOTO EDITING · {Pts(ux.PhotoPoints)} pts · {F(ux.PhotoMps)} MP/s");
            Line($"UX 5 VIDEO PLAYBACK · {Pts(ux.VideoPoints)} pts · " + (ux.Video is { } v ? string.Join(", ", v.Codecs.Select(c =>
                c.Fps > 0 ? $"{c.Clip.Label()} {F(c.Fps)} fps ({(c.Hardware ? "HW" : "SW")} {c.Decoder ?? "?"})" : $"{c.Clip.Label()} 0 ({c.Error ?? "unsupported"})")) : "did not run"));
            if (ux.Video is { } vg)
                foreach (var c in Enum.GetValues<Ux.VideoCodec>())
                    Line("  plays " + c.Short().PadRight(5) + " " + string.Join(" ", Ux.VideoGrid.Tiers.Zip(Ux.VideoGrid.Playback(vg, c), (t, cell) => t.Label + cell.Mark())));
            Line($"UX 6 VIDEO EXPORT · {Pts(ux.ExportPoints)} pts · " + (ux.Export is { } e ? string.Join(", ", e.Jobs.Select(j =>
                j.Fps > 0 ? $"{j.Job.Label()} {j.Job.Res()} {F(j.Fps)} fps {F(j.Psnr, 1)} dB{(j.Psnr < UxScore.MinPsnr ? " LOW QUALITY" : "")} ({(j.Hardware ? "HW" : "SW")} {j.Encoder ?? "?"})"
                    : $"{j.Job.Label()} {j.Job.Res()} 0 ({j.Error ?? "unsupported"})")) : "did not run"));
            if (ux.Export is { } eg)
                foreach (var c in new[] { Ux.VideoCodec.Avc, Ux.VideoCodec.Hevc, Ux.VideoCodec.Av1 })
                    Line("  exports " + c.Short().PadRight(5) + " " + string.Join(" ", Ux.VideoGrid.Tiers.Zip(Ux.VideoGrid.Export(eg, c), (t, cell) => t.Label + cell.Mark())));
            Line($"UX 7 QR SCANNING · {Pts(ux.QrPoints)} pts · " + (ux.Qr is { } q
                ? $"ZXing {F(q.Rate)}/s ({q.Missed} missed), {q.ReferenceName} " + (q.ReferenceRate is { } rr ? $"{F(rr)}/s" : "n/a" + (q.ReferenceError is { } re ? $" ({re})" : "")) : "did not run"));
            if (ux.Error is { } ue) Line($"UX error: {ue}");
        }
        if (r.Ai is { } ai)
        {
            Line($"AI · {Pts(ai.Score)} pts = CPU {Pts(ai.AccelScore(AiAccel.Cpu))} · GPU {Pts(ai.AccelScore(AiAccel.Gpu))} · NPU {Pts(ai.AccelScore(AiAccel.Npu))} · NPU " + (ai.NpuRuntime ?? $"not run: {ai.NpuProblem ?? "?"}"));
            foreach (var t in AiTests.All)
            {
                var parts = AiTests.Accels.Select(a =>
                {
                    var run = ai.Runs.FirstOrDefault(x => x.Test == t && x.Accel == a);
                    double ips = run?.Ips ?? 0;
                    return ips > 0 ? $"{a.Label()} {F(ips)}/s" : $"{a.Label()} 0 ({run?.Error ?? "not run"})";
                });
                Line($"AI {t.Number()} {t.Title()} · " + string.Join(", ", parts));
            }
        }
        if (r.Llm is { } llm)
        {
            Line($"LLM · {Pts(llm.Score)} pts = prefill {Pts(llm.PrefillPoints)} ({F(llm.Prefill)} tok/s) · decode {Pts(llm.DecodePoints)} ({F(llm.Decode)} tok/s) · " +
                 $"{F(llm.FreeGb)} GB free, largest model {llm.LargestModel}B" + (llm.Error is { } le ? $" · not run: {le}" : ""));
            foreach (var a in LlmAccels.All)
            {
                if (llm.Run(a) is { Decode: > 0 } run)
                    Line($"LLM {a.Label()} · prefill {F(run.Prefill)} tok/s · decode {F(run.Decode)} tok/s · " + (a == LlmAccel.Gpu ? (llm.GpuName ?? "?") : $"{run.Threads} threads (prompt {run.BatchThreads})"));
                else Line($"LLM {a.Label()} · 0 ({llm.Run(a)?.Error ?? "not run"})");
            }
        }
        foreach (var why in r.NotYet) Line($"Not run: {why}");
        return s.ToString();
    }
}
