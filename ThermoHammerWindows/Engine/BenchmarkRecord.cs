namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK RECORD — one finished run as the PC keeps it (benchmark_history.json)
// and submits it: raw results only, and what can be worked out from them again
// (the section scores, for comparing runs across a Windows update). No Windows
// calls here, so the unit tests compile it as it is.
// ─────────────────────────────────────────────────────────────────────────────

public sealed record LlmRecord(double PrefillCpu = 0, double DecodeCpu = 0, double PrefillGpu = 0, double DecodeGpu = 0, string? Gpu = null, double FreeGb = 0);

public sealed record MediaRecord(double Video8kHevc = 0, double Video8kAv1 = 0, double ExportAvc = 0, double ExportHevc = 0, double ExportAv1 = 0,
    double Export8kHevc = 0, double ExportAvcPsnr = 0, double ExportHevcPsnr = 0, double ExportAv1Psnr = 0, double Export8kHevcPsnr = 0,
    int ExportHardware = 0);

/// <summary>The six section scores of a run, in the order every screen lists them.</summary>
public sealed record SectionScores(int Cpu, int Mem, int Gpu, int Ux, int Ai, int Llm)
{
    /// <summary>The server's section keys (BenchmarkDetail.Section.Key), in order.</summary>
    public static readonly string[] Keys = ["cpu", "mem", "gpu", "ux", "ai", "llm"];

    public static string Label(string key) => key.ToUpperInvariant();

    public int this[string key] => key switch
    {
        "cpu" => Cpu, "mem" => Mem, "gpu" => Gpu, "ux" => Ux, "ai" => Ai, "llm" => Llm, _ => 0,
    };

    public int Total => Cpu + Mem + Gpu + Ux + Ai + Llm;

    public static SectionScores Of(BenchmarkResult r) => new(r.CpuScore, r.MemScore, r.GpuScore, r.UxScore, r.AiScore, r.LlmScore);
}

/// <summary>One finished run as kept on the PC and submitted: raw results only (2 decimals — what is hashed).</summary>
public sealed partial record BenchmarkRecord
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
    /// <summary>The run's id on the server once it's on the leaderboard (POST /benchmark answers it): its page is SITE/run/{id}/.
    /// Null for a run not submitted, or submitted before 2.1.</summary>
    public int? ServerId;
    /// <summary>Windows' build when the run was made ("26100.6584"): with <see cref="OsVersion"/>, the update the run belongs to. Null before 2.1.</summary>
    public string? OsBuild;
    /// <summary>The PC's model when the run was made, as the leaderboard names it. Null before 2.1 (those runs count as this PC's).</summary>
    public string? DeviceModel;

    public static BenchmarkRecord From(BenchmarkResult r, string? osVersion, string? osBuild, string? deviceModel)
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
            OsVersion = osVersion,
            OsBuild = osBuild,
            DeviceModel = deviceModel,
            ValidityFlags = r.ValidityFlags,
            Complete = r.IsComplete,
        };
    }

    /// <summary>
    /// The section scores, worked out again from the raw results with the app's (and the server's) scoring — what a saved run scored
    /// section by section, old runs included. Equal to the run's own section scores up to the 2-decimal rounding of what is kept.
    /// </summary>
    public SectionScores Sections()
    {
        int cpu = CpuScore.Overall(CpuTests.All.Select(t => CpuScore.Points(t, t switch
        {
            CpuTest.Math => CpuMath, CpuTest.Data => CpuData, CpuTest.Crypto => CpuCrypto, _ => CpuMulti,
        })).ToList());
        int mem = MemScore.Overall([MemScore.Ram(MemRead, MemWrite, MemCopy, MemLatency),
            MemScore.Storage(StorageSeqRead, StorageSeqWrite, StorageRandomRead, StorageRandomWrite), MemScore.Db(DbInsert, DbUpdate, DbQuery)]);
        int gpu = BenchmarkScore.Overall(BenchmarkScore.Points(GpuTest.LakeValley, Test1Fps), BenchmarkScore.Points(GpuTest.NeonCity, Test2Fps),
            RayTracing ? BenchmarkScore.Points(GpuTest.NeonCityRT, Test3Fps) : 0);
        var media = Media ?? new MediaRecord();
        var video = new VideoResult(
        [
            new(VideoClip.Avc4k, VideoAvc, null, false), new(VideoClip.Hevc4k, VideoHevc, null, false), new(VideoClip.Vp94k, VideoVp9, null, false),
            new(VideoClip.Av14k, VideoAv1, null, false), new(VideoClip.Hevc8k, media.Video8kHevc, null, false), new(VideoClip.Av18k, media.Video8kAv1, null, false),
        ]);
        var export = new ExportResult(
        [
            new(ExportJob.Avc4k, media.ExportAvc, media.ExportAvcPsnr, null, false), new(ExportJob.Hevc4k, media.ExportHevc, media.ExportHevcPsnr, null, false),
            new(ExportJob.Av14k, media.ExportAv1, media.ExportAv1Psnr, null, false), new(ExportJob.Hevc8k, media.Export8kHevc, media.Export8kHevcPsnr, null, false),
        ]);
        int ux = UxScore.Overall(
        [
            UxScore.WebPoints(new WebResult(WebLayout, WebCanvas, WebCss, WebSvg, WebJs)), UxScore.ScrollPoints(ScrollSpeed),
            UxScore.GalleryPoints(new GalleryResult(GalleryJpeg, GalleryWebp, GalleryAvif)), UxScore.PhotoPoints(PhotoEdit),
            UxScore.VideoPoints(video), UxScore.QrPoints(QrRate),
        ], UxScore.ExportPoints(export));
        double Ips(int test, int accel) => Ai.Length == 18 ? Ai[test * 3 + accel] : 0;
        int AccelScore(AiAccel a) => AiScore.Accel(a, AiTests.All.Select((_, i) => Ips(i, (int)a)).ToArray());
        int ai = AiScore.Overall(AccelScore(AiAccel.Cpu), AccelScore(AiAccel.Gpu), AccelScore(AiAccel.Npu));
        var l = Llm ?? new LlmRecord();
        int llm = LlmScore.Overall(Math.Max(l.PrefillCpu, l.PrefillGpu), Math.Max(l.DecodeCpu, l.DecodeGpu));
        return new SectionScores(cpu, mem, gpu, ux, ai, llm);
    }
}
