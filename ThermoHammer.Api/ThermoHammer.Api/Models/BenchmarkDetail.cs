using System.Globalization;

namespace ThermoHammer.Api.Models;

/// <summary>
/// One run opened from the leaderboard: the device, and every section as ready-to-show text — each client draws the
/// same sections → groups → rows, so a new measurement needs no app update.
/// </summary>
public class BenchmarkDetailDto
{
    public int Id { get; set; }
    public DateTime CreatedAt { get; set; }
    public int BenchmarkVersion { get; set; }
    public required string DeviceManufacturer { get; set; }
    public required string DeviceModel { get; set; }
    public string? SocModel { get; set; }
    public string? GpuName { get; set; }
    public OsPlatform Os { get; set; }
    public required string OsVersion { get; set; }
    public string? OsBuild { get; set; }
    public string? AppVersion { get; set; }
    public int TotalScore { get; set; }
    public List<DetailSection> Sections { get; set; } = new();
}

public class DetailSection
{
    public required string Key { get; set; }     // cpu, mem, gpu, ux, ai, llm
    public required string Title { get; set; }
    public int Score { get; set; }
    public List<DetailGroup> Groups { get; set; } = new();
}

public class DetailGroup
{
    public required string Title { get; set; }
    public int? Points { get; set; }             // null when the group has no points of its own
    public List<DetailRow> Rows { get; set; } = new();
}

public class DetailRow
{
    public required string Label { get; set; }
    public required string Value { get; set; }
}

public static class BenchmarkDetail
{
    private static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

    public static BenchmarkDetailDto From(BenchmarkRun r)
    {
        var parts = BenchmarkScoring.Parts(r);
        return new BenchmarkDetailDto
        {
            Id = r.Id,
            CreatedAt = DateTime.SpecifyKind(r.CreatedAt, DateTimeKind.Utc),
            BenchmarkVersion = r.BenchmarkVersion,
            DeviceManufacturer = r.DeviceManufacturer,
            DeviceModel = r.DeviceModel,
            SocModel = r.SocModel,
            GpuName = r.GpuName,
            Os = r.Os,
            OsVersion = r.OsVersion,
            OsBuild = r.OsBuild,
            AppVersion = r.AppVersion,
            TotalScore = r.TotalScore,
            Sections =
            {
                Cpu(r, parts), Mem(r), Gpu(r), Ux(r), Ai(r), Llm(r)
            }
        };
    }

    private static DetailSection Cpu(BenchmarkRun r, Dictionary<string, int> parts) => new()
    {
        Key = "cpu", Title = "CPU", Score = r.CpuScore,
        Groups =
        {
            Group("Math", parts["cpu.math"], Row("Throughput", Rate(r.CpuMath, "ops/s"))),
            Group("JSON & XML", parts["cpu.data"], Row("Throughput", Rate(r.CpuData, "ops/s"))),
            Group("Compression & crypto", parts["cpu.crypto"], Row("Throughput", Rate(r.CpuCrypto, "ops/s"))),
            Group("Multi-core", parts["cpu.multi"], Row("Throughput", Rate(r.CpuMulti, "ops/s"))),
        }
    };

    private static DetailSection Mem(BenchmarkRun r) => new()
    {
        Key = "mem", Title = "MEMORY", Score = r.MemScore,
        Groups =
        {
            Group("RAM", r.RamScore,
                Row("Read", Rate(r.MemRead, "GB/s")), Row("Write", Rate(r.MemWrite, "GB/s")),
                Row("Copy", Rate(r.MemCopy, "GB/s")), Row("Latency", Rate(r.MemLatency, "ns"))),
            Group("Storage", r.StorageScore,
                Row("Sequential read", Rate(r.StorageSeqRead, "MB/s")), Row("Sequential write", Rate(r.StorageSeqWrite, "MB/s")),
                Row("Random read (4 KB)", Rate(r.StorageRandomRead, "IOPS")), Row("Random write (4 KB)", Rate(r.StorageRandomWrite, "IOPS")),
                Row("Reads", r.StorageDirect ? "direct I/O" : "through the page cache")),
            Group("Database", r.DbScore,
                Row("Insert", Rate(r.DbInsert, "tx/s")), Row("Update", Rate(r.DbUpdate, "tx/s")), Row("Query", Rate(r.DbQuery, "/s"))),
        }
    };

    private static DetailSection Gpu(BenchmarkRun r) => new()
    {
        Key = "gpu", Title = "GPU", Score = r.Score,
        Groups =
        {
            Group("Lake Valley", r.Test1Score, Row("Average", Fps(r.Test1Fps)), Row("1% low", Fps(r.Test1LowFps))),
            Group("Neon City", r.Test2Score, Row("Average", Fps(r.Test2Fps)), Row("1% low", Fps(r.Test2LowFps))),
            r.RayTracing
                ? Group("Neon City RT", r.Test3Score, Row("Average", Fps(r.Test3Fps)), Row("1% low", Fps(r.Test3LowFps)))
                : Group("Neon City RT", 0, Row("Ray tracing", "not supported by this GPU")),
        }
    };

    private static DetailSection Ux(BenchmarkRun r) => new()
    {
        Key = "ux", Title = "EVERYDAY UX", Score = r.UxScore,
        Groups =
        {
            Group("Web browsing", r.WebScore,
                Row("Layout", N(r.WebLayout, 0)), Row("Canvas", N(r.WebCanvas, 0)), Row("CSS", N(r.WebCss, 0)),
                Row("SVG", N(r.WebSvg, 0)), Row("JavaScript", Rate(r.WebJs, "ops/s"))),
            Group("Web scrolling", r.ScrollScore,
                Row("Speed held", Rate(r.ScrollSpeed, "px/s")), Row("Frames dropped", N(r.ScrollDropped, 1) + " %")),
            Group("Photo gallery", r.GalleryScore,
                Row("JPEG", Mps(r.GalleryJpeg)), Row("WebP", Mps(r.GalleryWebp)), Row("AVIF", Mps(r.GalleryAvif))),
            Group("Photo editing", r.PhotoScore, Row("Editing chain", Mps(r.PhotoEdit))),
            Group("Video playback", r.VideoScore,
                Row("4K60 H.264", Play(r.VideoAvc, r.VideoHardware, 0)), Row("4K60 HEVC", Play(r.VideoHevc, r.VideoHardware, 1)),
                Row("4K60 VP9", Play(r.VideoVp9, r.VideoHardware, 2)), Row("4K60 AV1", Play(r.VideoAv1, r.VideoHardware, 3)),
                Row("8K30 HEVC", Play(r.Video8kHevc, r.VideoHardware, 4)), Row("8K30 AV1", Play(r.Video8kAv1, r.VideoHardware, 5))),
            Group("Video export", r.ExportScore,
                Row("4K60 H.264", Export(r.ExportAvc, r.ExportAvcPsnr, r.ExportHardware, 0)),
                Row("4K60 HEVC", Export(r.ExportHevc, r.ExportHevcPsnr, r.ExportHardware, 1)),
                Row("4K60 AV1", Export(r.ExportAv1, r.ExportAv1Psnr, r.ExportHardware, 2)),
                Row("8K30 HEVC", Export(r.Export8kHevc, r.Export8kHevcPsnr, r.ExportHardware, 3))),
            Group("QR scanning", r.QrScore,
                Row("ZXing", Rate(r.QrRate, "scans/s")),
                Row("ML Kit", r.MlKitQrRate is > 0 ? Rate(r.MlKitQrRate.Value, "scans/s") : "n/a")),
        }
    };

    private static readonly string[] AiModels = ["Classify", "Detect", "Segment", "Upscale", "Depth", "Sound"];

    private static DetailSection Ai(BenchmarkRun r)
    {
        double[] cpu = [r.AiClassifyCpu, r.AiDetectCpu, r.AiSegmentCpu, r.AiUpscaleCpu, r.AiDepthCpu, r.AiSoundCpu];
        double[] gpu = [r.AiClassifyGpu, r.AiDetectGpu, r.AiSegmentGpu, r.AiUpscaleGpu, r.AiDepthGpu, r.AiSoundGpu];
        double[] npu = [r.AiClassifyNpu, r.AiDetectNpu, r.AiSegmentNpu, r.AiUpscaleNpu, r.AiDepthNpu, r.AiSoundNpu];
        var npuGroup = Group("NPU", r.AiNpuScore, Models(npu).ToArray());
        npuGroup.Rows.Insert(0, Row("Runtime", r.AiNpuRuntime ?? "none ran"));
        return new DetailSection
        {
            Key = "ai", Title = "AI", Score = r.AiScore,
            Groups = { Group("CPU", r.AiCpuScore, Models(cpu).ToArray()), Group("GPU", r.AiGpuScore, Models(gpu).ToArray()), npuGroup }
        };

        static IEnumerable<DetailRow> Models(double[] ips) => ips.Select((v, i) => Row(AiModels[i], Rate(v, "inferences/s")));
    }

    private static DetailSection Llm(BenchmarkRun r) => new()
    {
        Key = "llm", Title = "LLM", Score = r.LlmScore,
        Groups =
        {
            Group("Prompt (prefill)", r.LlmPrefillScore, Row("CPU", Tok(r.LlmPrefillCpu)), Row("GPU", Tok(r.LlmPrefillGpu))),
            Group("Reply (decode)", r.LlmDecodeScore, Row("CPU", Tok(r.LlmDecodeCpu)), Row("GPU", Tok(r.LlmDecodeGpu))),
            Group("Run", null, Row("GPU used", string.IsNullOrEmpty(r.LlmGpu) ? "none" : r.LlmGpu), Row("Free memory at start", N(r.LlmFreeGb, 1) + " GB")),
        }
    };

    private static DetailGroup Group(string title, int? points, params DetailRow[] rows) => new() { Title = title, Points = points, Rows = rows.ToList() };
    private static DetailRow Row(string label, string value) => new() { Label = label, Value = value };

    private static string N(double v, int decimals) => v.ToString("N" + decimals, Inv);
    private static string Rate(double v, string unit) => v > 0 ? $"{N(v, v >= 100 ? 0 : v >= 10 ? 1 : 2)} {unit}" : "—";
    private static string Fps(double v) => v > 0 ? $"{N(v, v >= 100 ? 0 : 1)} fps" : "—";
    private static string Mps(double v) => v > 0 ? $"{N(v, v >= 100 ? 0 : 1)} MP/s" : "not supported";
    private static string Tok(double v) => v > 0 ? $"{N(v, v >= 100 ? 0 : 1)} tok/s" : "—";

    private static string Play(double fps, int hardware, int bit) =>
        fps > 0 ? $"{N(fps, fps >= 100 ? 0 : 1)} fps · {((hardware >> bit & 1) == 1 ? "hardware" : "software")}" : "not supported";

    private static string Export(double fps, double psnr, int hardware, int bit)
    {
        if (fps <= 0) return "no encoder";
        string text = $"{N(fps, fps >= 100 ? 0 : 1)} fps · {N(psnr, 1)} dB · {((hardware >> bit & 1) == 1 ? "hardware" : "software")}";
        return psnr < BenchmarkScoring.MinExportPsnr ? text + " · quality too low, not counted" : text;
    }
}
