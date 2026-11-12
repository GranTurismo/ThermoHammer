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
    /// <summary>The measured number behind Value, in its unit, so two runs can be compared; null for a text-only row
    /// and for a test that didn't run on this device.</summary>
    public double? Number { get; set; }
    /// <summary>"higher" or "lower": which way Number is better. Null when the number is context, not performance.</summary>
    public string? Better { get; set; }
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
            Group("Math", parts["cpu.math"], Row("Throughput", Rate(r.CpuMath, "ops/s"), Pos(r.CpuMath))),
            Group("JSON & XML", parts["cpu.data"], Row("Throughput", Rate(r.CpuData, "ops/s"), Pos(r.CpuData))),
            Group("Compression & crypto", parts["cpu.crypto"], Row("Throughput", Rate(r.CpuCrypto, "ops/s"), Pos(r.CpuCrypto))),
            Group("Multi-core", parts["cpu.multi"], Row("Throughput", Rate(r.CpuMulti, "ops/s"), Pos(r.CpuMulti))),
        }
    };

    private static DetailSection Mem(BenchmarkRun r) => new()
    {
        Key = "mem", Title = "MEMORY", Score = r.MemScore,
        Groups =
        {
            Group("RAM", r.RamScore,
                Row("Read", Rate(r.MemRead, "GB/s"), Pos(r.MemRead)), Row("Write", Rate(r.MemWrite, "GB/s"), Pos(r.MemWrite)),
                Row("Copy", Rate(r.MemCopy, "GB/s"), Pos(r.MemCopy)), Row("Latency", Rate(r.MemLatency, "ns"), Pos(r.MemLatency), Lower)),
            Group("Storage", r.StorageScore,
                Row("Sequential read", Rate(r.StorageSeqRead, "MB/s"), Pos(r.StorageSeqRead)),
                Row("Sequential write", Rate(r.StorageSeqWrite, "MB/s"), Pos(r.StorageSeqWrite)),
                Row("Random read (4 KB)", Rate(r.StorageRandomRead, "IOPS"), Pos(r.StorageRandomRead)),
                Row("Random write (4 KB)", Rate(r.StorageRandomWrite, "IOPS"), Pos(r.StorageRandomWrite)),
                Row("Reads", r.StorageDirect ? "direct I/O" : "through the page cache")),
            Group("Database", r.DbScore,
                Row("Insert", Rate(r.DbInsert, "tx/s"), Pos(r.DbInsert)), Row("Update", Rate(r.DbUpdate, "tx/s"), Pos(r.DbUpdate)),
                Row("Query", Rate(r.DbQuery, "/s"), Pos(r.DbQuery))),
        }
    };

    private static DetailSection Gpu(BenchmarkRun r) => new()
    {
        Key = "gpu", Title = "GPU", Score = r.Score,
        Groups =
        {
            Group("Lake Valley", r.Test1Score, Row("Average", Fps(r.Test1Fps), Pos(r.Test1Fps)), Row("1% low", Fps(r.Test1LowFps), Pos(r.Test1LowFps))),
            Group("Neon City", r.Test2Score, Row("Average", Fps(r.Test2Fps), Pos(r.Test2Fps)), Row("1% low", Fps(r.Test2LowFps), Pos(r.Test2LowFps))),
            r.RayTracing
                ? Group("Neon City RT", r.Test3Score, Row("Average", Fps(r.Test3Fps), Pos(r.Test3Fps)), Row("1% low", Fps(r.Test3LowFps), Pos(r.Test3LowFps)))
                : Group("Neon City RT", 0, Row("Ray tracing", "not supported by this GPU")),
        }
    };

    private static DetailSection Ux(BenchmarkRun r) => new()
    {
        Key = "ux", Title = "EVERYDAY UX", Score = r.UxScore,
        Groups =
        {
            Group("Web browsing", r.WebScore,
                Row("Layout", N(r.WebLayout, 0), Pos(r.WebLayout)), Row("Canvas", N(r.WebCanvas, 0), Pos(r.WebCanvas)),
                Row("CSS", N(r.WebCss, 0), Pos(r.WebCss)), Row("SVG", N(r.WebSvg, 0), Pos(r.WebSvg)),
                Row("JavaScript", Rate(r.WebJs, "ops/s"), Pos(r.WebJs))),
            Group("Web scrolling", r.ScrollScore,
                Row("Speed held", Rate(r.ScrollSpeed, "px/s"), Pos(r.ScrollSpeed)),
                // 0 % dropped is the best result there is: it counts whenever the test ran
                Row("Frames dropped", N(r.ScrollDropped, 1) + " %", r.ScrollSpeed > 0 ? r.ScrollDropped : null, Lower)),
            Group("Photo gallery", r.GalleryScore,
                Row("JPEG", Mps(r.GalleryJpeg), Pos(r.GalleryJpeg)), Row("WebP", Mps(r.GalleryWebp), Pos(r.GalleryWebp)),
                Row("AVIF", Mps(r.GalleryAvif), Pos(r.GalleryAvif))),
            Group("Photo editing", r.PhotoScore, Row("Editing chain", Mps(r.PhotoEdit), Pos(r.PhotoEdit))),
            Group("Video playback", r.VideoScore,
                Row("4K60 H.264", Play(r.VideoAvc, r.VideoHardware, 0), Pos(r.VideoAvc)), Row("4K60 HEVC", Play(r.VideoHevc, r.VideoHardware, 1), Pos(r.VideoHevc)),
                Row("4K60 VP9", Play(r.VideoVp9, r.VideoHardware, 2), Pos(r.VideoVp9)), Row("4K60 AV1", Play(r.VideoAv1, r.VideoHardware, 3), Pos(r.VideoAv1)),
                Row("8K30 HEVC", Play(r.Video8kHevc, r.VideoHardware, 4), Pos(r.Video8kHevc)), Row("8K30 AV1", Play(r.Video8kAv1, r.VideoHardware, 5), Pos(r.Video8kAv1))),
            Group("Video export", r.ExportScore,
                // an export whose quality is too low doesn't count, so it has no number to compare either
                Row("4K60 H.264", Export(r.ExportAvc, r.ExportAvcPsnr, r.ExportHardware, 0), Pos(BenchmarkScoring.Counted(r.ExportAvc, r.ExportAvcPsnr))),
                Row("4K60 HEVC", Export(r.ExportHevc, r.ExportHevcPsnr, r.ExportHardware, 1), Pos(BenchmarkScoring.Counted(r.ExportHevc, r.ExportHevcPsnr))),
                Row("4K60 AV1", Export(r.ExportAv1, r.ExportAv1Psnr, r.ExportHardware, 2), Pos(BenchmarkScoring.Counted(r.ExportAv1, r.ExportAv1Psnr))),
                Row("8K30 HEVC", Export(r.Export8kHevc, r.Export8kHevcPsnr, r.ExportHardware, 3), Pos(BenchmarkScoring.Counted(r.Export8kHevc, r.Export8kHevcPsnr)))),
            Group("QR scanning", r.QrScore,
                Row("ZXing", Rate(r.QrRate, "scans/s"), Pos(r.QrRate)),
                Row("ML Kit", r.MlKitQrRate is > 0 ? Rate(r.MlKitQrRate.Value, "scans/s") : "n/a", Pos(r.MlKitQrRate ?? 0))),
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

        static IEnumerable<DetailRow> Models(double[] ips) => ips.Select((v, i) => Row(AiModels[i], Rate(v, "inferences/s"), Pos(v)));
    }

    private static DetailSection Llm(BenchmarkRun r) => new()
    {
        Key = "llm", Title = "LLM", Score = r.LlmScore,
        Groups =
        {
            Group("Prompt (prefill)", r.LlmPrefillScore, Row("CPU", Tok(r.LlmPrefillCpu), Pos(r.LlmPrefillCpu)), Row("GPU", Tok(r.LlmPrefillGpu), Pos(r.LlmPrefillGpu))),
            Group("Reply (decode)", r.LlmDecodeScore, Row("CPU", Tok(r.LlmDecodeCpu), Pos(r.LlmDecodeCpu)), Row("GPU", Tok(r.LlmDecodeGpu), Pos(r.LlmDecodeGpu))),
            Group("Run", null, Row("GPU used", string.IsNullOrEmpty(r.LlmGpu) ? "none" : r.LlmGpu), Row("Free memory at start", N(r.LlmFreeGb, 1) + " GB", Pos(r.LlmFreeGb), better: null)),   // context, not performance
        }
    };

    private static DetailGroup Group(string title, int? points, params DetailRow[] rows) => new() { Title = title, Points = points, Rows = rows.ToList() };
    private const string Higher = "higher", Lower = "lower";
    private static DetailRow Row(string label, string value) => new() { Label = label, Value = value };
    private static DetailRow Row(string label, string value, double? number, string? better = Higher) =>
        new() { Label = label, Value = value, Number = number, Better = better };
    /// <summary>A measurement, or null when the test didn't run (0): a missing result must not "lose" by 100 %.</summary>
    private static double? Pos(double v) => v > 0 ? v : null;

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
