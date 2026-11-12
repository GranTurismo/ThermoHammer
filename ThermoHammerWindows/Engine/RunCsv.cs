using System.Globalization;
using System.Text;

namespace ThermoHammer.Engine;

/// <summary>One second of an own stress run, as the CSV export writes it (from a StampV2).</summary>
public sealed record StressCsvRow(double ElapsedS, long Throughput, double PercentOfBaseline, int ThermalState, double CpuMhz,
    double ScheduledPct, double SystemLoadPct, int Attribution);

/// <summary>
/// EXPORT CSV — a run's numbers for a spreadsheet: UTF-8, comma-separated, "." decimals, the header row first, no comment lines. The
/// columns are the phones' (Windows adds what it records per stamp after the shared ones).
/// </summary>
public static class RunCsv
{
    public const string StressHeader = "elapsed_s,throughput,percent_of_baseline,thermal_state,cpu_mhz,workers_scheduled_pct,system_load_pct,attribution";
    public const string BoardStressHeader = "elapsed_s,score,thermal_state";
    public const string BenchmarkHeader = "section,metric,value,unit";

    /// <summary>Own stress run, one row per stamp: throughput in iterations/s, thermal state 0 nominal … 3 critical, then the clock, the share of
    /// the time the workers were scheduled, the load of the whole PC, and what the engine blamed a drop on (none, thermal, contention, unknown).</summary>
    public static string Stress(IEnumerable<StressCsvRow> rows)
    {
        var s = new StringBuilder(StressHeader).Append('\n');
        foreach (var r in rows)
            Line(s, Num(r.ElapsedS, 3), Int(r.Throughput), Num(r.PercentOfBaseline, 2), Int(r.ThermalState), Num(r.CpuMhz, 0),
                Num(r.ScheduledPct, 1), Num(r.SystemLoadPct, 1), AttributionName(r.Attribution));
        return s.ToString();
    }

    /// <summary>A leaderboard stress run (GET /stamps/{id}): what the server keeps per stamp.</summary>
    public static string BoardStress(IEnumerable<(int ElapsedMs, long Score, int ThermalState)> stamps)
    {
        var s = new StringBuilder(BoardStressHeader).Append('\n');
        foreach (var x in stamps) Line(s, Num(x.ElapsedMs / 1000.0, 3), Int(x.Score), Int(x.ThermalState));
        return s.ToString();
    }

    private static string AttributionName(int a) => a switch { 1 => "thermal", 2 => "contention", 3 => "unknown", _ => "none" };

    /// <summary>
    /// A benchmark run: one row per raw number of the text report (BenchmarkReport.Text) as section, metric, value, unit — each test's
    /// points among them — then each section's score ("score,cpu,…,points") and the total ("score,total,…,points").
    /// </summary>
    public static string Benchmark(BenchmarkResult r)
    {
        var s = new StringBuilder(BenchmarkHeader).Append('\n');
        void Row(string section, string metric, double value, string unit, int decimals = 2) => Line(s, section, metric, Num(value, decimals), unit);
        void Pts(string section, string metric, int points) => Line(s, section, metric, Int(points), "points");

        Row("run", "validity_flags", r.ValidityFlags, "flags", 0);
        Row("run", "start_cpu_share", Math.Round(r.StartCpuShare * 100), "%", 0);
        Row("run", "quiet_wait", r.QuietWaitSec, "s", 0);

        if (r.Cpu is { } cpu)
            foreach (var t in CpuTests.All)
            {
                string test = t switch { CpuTest.Math => "math", CpuTest.Data => "data", CpuTest.Crypto => "crypto", _ => "multi" };
                Pts("cpu", $"{test}_points", cpu.Points(t));
                Row("cpu", test, cpu.Value(t), "ops/s");
                foreach (var (name, rate) in cpu.Runs.FirstOrDefault(x => x.Test == t)?.Rates ?? [])
                    Row("cpu", $"{test}_{Key(name)}", rate, "ops/s");
            }
        if (r.Mem is { } m)
        {
            Pts("mem", "ram_points", m.RamPoints);
            Row("mem", "ram_read", m.Ram.Read, "GB/s"); Row("mem", "ram_write", m.Ram.Write, "GB/s");
            Row("mem", "ram_copy", m.Ram.Copy, "GB/s"); Row("mem", "ram_latency", m.Ram.LatencyNs, "ns");
            Pts("mem", "storage_points", m.StoragePoints);
            Row("mem", "storage_seq_read", m.Storage.SeqRead, "MB/s"); Row("mem", "storage_seq_write", m.Storage.SeqWrite, "MB/s");
            Row("mem", "storage_random_read", m.Storage.RandomRead, "IOPS"); Row("mem", "storage_random_write", m.Storage.RandomWrite, "IOPS");
            Row("mem", "storage_file", m.Storage.FileMb, "MB", 0); Row("mem", "storage_direct_io", m.Storage.Direct ? 1 : 0, "flag", 0);
            Pts("mem", "database_points", m.DbPoints);
            Row("mem", "database_insert", m.Db.Insert, "tx/s"); Row("mem", "database_update", m.Db.Update, "tx/s");
            Row("mem", "database_query", m.Db.Query, "queries/s");
        }
        foreach (var t in GpuTests.All)
        {
            if (r.Gpu.FirstOrDefault(x => x.Test == t) is not { } g) continue;
            string test = t switch { GpuTest.LakeValley => "lake_valley", GpuTest.NeonCity => "neon_city", _ => "neon_city_rt" };
            Pts("gpu", $"{test}_points", g.Points);
            Row("gpu", $"{test}_supported", g.Supported ? 1 : 0, "flag", 0);
            if (!g.Supported) continue;
            Row("gpu", $"{test}_avg_fps", g.AvgFps, "fps"); Row("gpu", $"{test}_low_fps", g.LowFps, "fps");
            Row("gpu", $"{test}_stability", g.StabilityPct, "%", 1); Row("gpu", $"{test}_frames", g.Frames, "frames", 0);
        }
        if (r.Ux is { } ux)
        {
            Pts("ux", "web_points", ux.WebPoints);
            if (ux.Web is { } w)
            {
                Row("ux", "web_layout", w.Layout, "score"); Row("ux", "web_canvas", w.Canvas, "score"); Row("ux", "web_css", w.Css, "score");
                Row("ux", "web_svg", w.Svg, "score"); Row("ux", "web_js", w.Js, "ops/s");
            }
            Pts("ux", "scroll_points", ux.ScrollPoints);
            if (ux.Scroll is { } sc) { Row("ux", "scroll_speed", sc.Speed, "px/s"); Row("ux", "scroll_dropped", sc.DroppedPct, "%"); }
            Pts("ux", "gallery_points", ux.GalleryPoints);
            if (ux.Gallery is { } ga)
            {
                Row("ux", "gallery_jpeg", ga.Jpeg, "MP/s"); Row("ux", "gallery_webp", ga.Webp, "MP/s"); Row("ux", "gallery_avif", ga.Avif, "MP/s");
            }
            Pts("ux", "photo_points", ux.PhotoPoints);
            Row("ux", "photo_edit", ux.PhotoMps, "MP/s");
            Pts("ux", "video_points", ux.VideoPoints);
            foreach (var c in ux.Video?.Codecs ?? [])
            {
                Row("ux", $"video_{Clip(c.Clip)}", c.Fps, "fps");
                Row("ux", $"video_{Clip(c.Clip)}_hardware", c.Hardware && c.Fps > 0 ? 1 : 0, "flag", 0);
            }
            Pts("ux", "export_points", ux.ExportPoints);
            foreach (var j in ux.Export?.Jobs ?? [])
            {
                string job = Job(j.Job);
                Row("ux", $"export_{job}", j.Fps, "fps"); Row("ux", $"export_{job}_psnr", j.Psnr, "dB");
                Row("ux", $"export_{job}_hardware", j.Hardware && j.Fps > 0 ? 1 : 0, "flag", 0);
            }
            Pts("ux", "qr_points", ux.QrPoints);
            if (ux.Qr is { } q)
            {
                Row("ux", "qr_rate", q.Rate, "scans/s"); Row("ux", "qr_missed", q.Missed, "frames", 0);
                if (q.ReferenceRate is { } rr) Row("ux", "qr_reference_rate", rr, "scans/s");
            }
        }
        if (r.Ai is { } ai)
        {
            foreach (var a in AiTests.Accels) Pts("ai", $"{Key(a.ToString())}_points", ai.AccelScore(a));
            foreach (var t in AiTests.All)
                foreach (var a in AiTests.Accels)
                    Row("ai", $"{Key(t.ToString())}_{Key(a.ToString())}", ai.Ips(t, a), "inferences/s");
        }
        if (r.Llm is { } llm)
        {
            Pts("llm", "prefill_points", llm.PrefillPoints);
            Pts("llm", "decode_points", llm.DecodePoints);
            foreach (var a in LlmAccels.All)
            {
                var run = llm.Run(a);
                Row("llm", $"{Key(a.ToString())}_prefill", run?.Prefill ?? 0, "tokens/s");
                Row("llm", $"{Key(a.ToString())}_decode", run?.Decode ?? 0, "tokens/s");
            }
            Row("llm", "free_memory", llm.FreeGb, "GB");
            Row("llm", "largest_model", llm.LargestModel, "B params", 0);
        }

        var scores = SectionScores.Of(r);
        foreach (var key in SectionScores.Keys) Pts("score", key, scores[key]);
        Pts("score", "total", r.Total);
        return s.ToString();
    }

    /// <summary>A name as a metric: "MATRIX MULTIPLY" → "matrix_multiply", "AES-256-GCM" → "aes_256_gcm".</summary>
    public static string Key(string name)
    {
        var b = new StringBuilder();
        foreach (char c in name)
        {
            if (char.IsLetterOrDigit(c) && c < 128) b.Append(char.ToLowerInvariant(c));
            else if (b.Length > 0 && b[^1] != '_') b.Append('_');
        }
        return b.ToString().Trim('_');
    }

    private static string Clip(VideoClip c) => c switch
    {
        VideoClip.Avc4k => "h264_4k", VideoClip.Hevc4k => "hevc_4k", VideoClip.Vp94k => "vp9_4k", VideoClip.Av14k => "av1_4k",
        VideoClip.Hevc8k => "hevc_8k", _ => "av1_8k",
    };

    private static string Job(ExportJob j) => j switch
    {
        ExportJob.Avc4k => "h264_4k", ExportJob.Hevc4k => "hevc_4k", ExportJob.Av14k => "av1_4k", _ => "hevc_8k",
    };

    private static string Num(double v, int decimals) =>
        double.IsFinite(v) ? Math.Round(v, decimals, MidpointRounding.AwayFromZero).ToString("0." + new string('#', Math.Max(decimals, 0)), CultureInfo.InvariantCulture).TrimEnd('.') : "";

    private static string Int(long v) => v.ToString(CultureInfo.InvariantCulture);

    /// <summary>A field quoted only when it has to be (a comma, a quote or a line break in it).</summary>
    private static string Field(string v) =>
        v.IndexOfAny([',', '"', '\n', '\r']) >= 0 ? "\"" + v.Replace("\"", "\"\"") + "\"" : v;

    private static void Line(StringBuilder s, params string[] fields) => s.Append(string.Join(",", fields.Select(Field))).Append('\n');
}
