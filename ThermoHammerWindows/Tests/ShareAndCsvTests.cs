using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;
using ThermoHammer.Engine;

namespace ThermoHammer.Tests;

public class ShareLinksTests
{
    [Fact]
    public void Links_go_to_the_run_page_or_the_front_page()
    {
        Assert.Equal("https://thermohammer.gtgroup.dev/run/123/", ShareLinks.Run(123));
        Assert.Equal("https://thermohammer.gtgroup.dev/stress/45/", ShareLinks.Stress(45));
        Assert.Equal("https://thermohammer.gtgroup.dev/cert/7/", ShareLinks.Cert(7));
        Assert.Equal("https://thermohammer.gtgroup.dev/", ShareLinks.Run(null));
        Assert.Equal("https://thermohammer.gtgroup.dev/", ShareLinks.Stress(null));
        Assert.Equal("https://thermohammer.gtgroup.dev/", ShareLinks.Run(0));
    }

    [Fact]
    public void Display_link_has_no_scheme_and_no_trailing_slash()
    {
        Assert.Equal("thermohammer.gtgroup.dev/run/123", ShareLinks.Display(ShareLinks.Run(123)));
        Assert.Equal("thermohammer.gtgroup.dev", ShareLinks.Display(ShareLinks.Home));
    }

    [Fact]
    public void Texts_say_PC_and_carry_the_link()
    {
        string url = ShareLinks.Run(9);
        Assert.Equal("My Lenovo ThinkPad X1 scored 12,345 on the ThermoHammer benchmark. Can your PC beat it? " + url,
            ShareLinks.BenchmarkText("Lenovo ThinkPad X1", 12345, url, mine: true));
        Assert.Equal("HONOR 600 Pro scored 31,002 on ThermoHammer. Can your PC beat it? " + url,
            ShareLinks.BenchmarkText("HONOR 600 Pro", 31002, url, mine: false));
        string s = ShareLinks.Stress(4);
        Assert.Equal("My Dell XPS 13 kept 87% of its speed through a 15-minute ThermoHammer stress test. Can your PC do better? " + s,
            ShareLinks.StressText("Dell XPS 13", 86.5, 15, s, mine: true));
        Assert.Equal("Pixel 9 kept 64% of its speed through a 5-minute ThermoHammer stress test. " + s,
            ShareLinks.StressText("Pixel 9", 63.6, 5, s, mine: false));
        Assert.Equal("Health certificate for my HP ProBook: https://thermohammer.gtgroup.dev/cert/1/",
            ShareLinks.CertText("HP ProBook", ShareLinks.Cert(1)));
        Assert.Equal("CAN YOUR PC BEAT THIS?", ShareLinks.Challenge);
    }

    [Fact]
    public void Numbers_use_the_apps_separators_whatever_the_region()
    {
        var keep = CultureInfo.CurrentCulture;
        try
        {
            CultureInfo.CurrentCulture = new CultureInfo("de-DE");
            Assert.Contains("12,345", ShareLinks.BenchmarkText("X", 12345, "u", true));
            Assert.Equal("thermohammer-benchmark-x-20261112-0905.csv", ShareLinks.CsvFileName(false, "X", new DateTime(2026, 11, 12, 9, 5, 0)));
        }
        finally { CultureInfo.CurrentCulture = keep; }
    }

    [Fact]
    public void File_names()
    {
        Assert.Equal("thermohammer-run-77.png", ShareLinks.CardFileName(false, 77, DateTimeOffset.FromUnixTimeSeconds(1)));
        Assert.Equal("thermohammer-stress-1731420000.png", ShareLinks.CardFileName(true, null, DateTimeOffset.FromUnixTimeSeconds(1731420000)));
        Assert.Equal("thermohammer-stress-lenovo-thinkpad-x1-gen-12-20261112-1430.csv",
            ShareLinks.CsvFileName(true, "Lenovo ThinkPad X1 (Gen 12)", new DateTime(2026, 11, 12, 14, 30, 59)));
        Assert.Equal("pc-ryzen-7-7800x3d", ShareLinks.Slug("PC (Ryzen 7 7800X3D)"));
        Assert.Equal("device", ShareLinks.Slug("—"));
    }
}

public class RunCsvTests
{
    private static string[] Lines(string csv) => csv.TrimEnd('\n').Split('\n');

    [Fact]
    public void Own_stress_run_has_the_shared_columns_first_then_the_pcs()
    {
        var csv = RunCsv.Stress([new StressCsvRow(1.0004, 1_234_567, 99.456, 0, 3210.4, 99.95, 12.34, 0),
            new StressCsvRow(2.5, 1_000_000, 80.5, 2, 2800, 100, 5, 1)]);
        var lines = Lines(csv);
        Assert.Equal("elapsed_s,throughput,percent_of_baseline,thermal_state,cpu_mhz,workers_scheduled_pct,system_load_pct,attribution", lines[0]);
        Assert.Equal("1,1234567,99.46,0,3210,100,12.3,none", lines[1]);
        Assert.Equal("2.5,1000000,80.5,2,2800,100,5,thermal", lines[2]);
        Assert.DoesNotContain(lines, l => l.StartsWith('#'));
    }

    [Fact]
    public void Leaderboard_stress_run()
    {
        var lines = Lines(RunCsv.BoardStress([(1000, 500, 0), (2500, 480, 1)]));
        Assert.Equal(["elapsed_s,score,thermal_state", "1,500,0", "2.5,480,1"], lines);
    }

    [Fact]
    public void Benchmark_lists_raw_numbers_then_section_scores_and_the_total()
    {
        var keep = CultureInfo.CurrentCulture;
        try
        {
            CultureInfo.CurrentCulture = new CultureInfo("fr-FR");   // a decimal comma must not leak in
            var r = Fixtures.Result();
            var lines = Lines(RunCsv.Benchmark(r));
            Assert.Equal("section,metric,value,unit", lines[0]);
            Assert.All(lines, l => Assert.Equal(3, l.Count(c => c == ',')));
            Assert.Contains("cpu,math_matrix_multiply,1600.25,ops/s", lines);
            Assert.Contains("mem,ram_latency,230.5,ns", lines);
            Assert.Contains("gpu,lake_valley_avg_fps,140.25,fps", lines);
            Assert.Contains("ux,video_av1_8k,0,fps", lines);
            Assert.Contains("ux,export_hevc_8k_psnr,29.75,dB", lines);
            Assert.Contains("ai,classify_npu,1753.12,inferences/s", lines);   // 412.5 × 4.25, kept at 2 decimals by the fixture
            Assert.Contains("llm,gpu_decode,40.5,tokens/s", lines);
            var scores = lines.Where(l => l.StartsWith("score,")).ToList();
            Assert.Equal(["cpu", "mem", "gpu", "ux", "ai", "llm", "total"], scores.Select(l => l.Split(',')[1]));
            Assert.Equal($"score,cpu,{r.CpuScore},points", scores[0]);
            Assert.Equal($"score,total,{r.Total},points", scores[^1]);
            Assert.Equal(lines.Length - 7, Array.IndexOf(lines, scores[0]));   // the scores close the file
        }
        finally { CultureInfo.CurrentCulture = keep; }
    }

    [Fact]
    public void Kernel_names_become_metric_names() =>
        Assert.Equal(["matrix_multiply", "aes_256_gcm", "rsa_1024", "n_body"],
            new[] { "MATRIX MULTIPLY", "AES-256-GCM", "RSA 1024", "N-BODY" }.Select(RunCsv.Key));
}

public class StressCurveTests
{
    [Fact]
    public void Short_runs_keep_every_point_long_runs_get_120_evenly_spaced()
    {
        var shortRun = Enumerable.Range(1, 90).Select(i => new CurvePoint(i, 100)).ToList();
        Assert.Equal(90, StressCurve.Downsample(shortRun).Count);
        var longRun = Enumerable.Range(1, 1800).Select(i => new CurvePoint(i, i <= 900 ? 100 : 60)).ToList();
        var d = StressCurve.Downsample(longRun);
        Assert.Equal(120, d.Count);
        var gaps = d.Zip(d.Skip(1), (a, b) => b.Seconds - a.Seconds).ToList();
        Assert.All(gaps, g => Assert.Equal(15, g, 6));
        Assert.Equal(100, d[0].Percent);
        Assert.Equal(60, d[^1].Percent);
    }

    [Fact]
    public void Leaderboard_curve_is_against_the_median_of_the_first_five_seconds()
    {
        var c = StressCurve.OfFirstSeconds([(1000, 90), (2000, 110), (3000, 100), (4000, 105), (5000, 95), (6000, 50)]);
        Assert.Equal(100, c[2].Percent, 6);    // median of 90, 110, 100, 105, 95
        Assert.Equal(50, c[^1].Percent, 6);
        Assert.Equal(6, c[^1].Seconds, 6);
    }

    [Fact]
    public void Onset_is_the_engines_rule()
    {
        var c = new[] { 100, 95, 89, 92, 88, 87, 85, 80 }.Select((p, i) => new CurvePoint(i + 1, p)).ToList();
        Assert.Equal(5, StressCurve.Onset(c));   // 88, 87, 85 at 5–7 s: dated at 7 − 2
        Assert.Null(StressCurve.Onset([new(1, 100), new(2, 89), new(3, 95)]));
        Assert.Equal("Throttled after 02:05", StressCurve.OnsetLine(125));
        Assert.Equal("Never throttled", StressCurve.OnsetLine(null));
    }

    [Fact]
    public void Kept_curve_round_trips_at_one_decimal()
    {
        var v = StressCurve.Compact([new(0, 99.96), new(150, 80.04), new(300, 70.25)]);
        Assert.Equal([100.0, 80.0, 70.3], v);
        var back = StressCurve.Expand(v, 300);
        Assert.Equal([0.0, 150, 300], back.Select(p => p.Seconds));
        Assert.Equal(110, StressCurve.Top(back));
        Assert.Equal(125, StressCurve.Top([new(0, 125)]));
    }

    [Fact]
    public void Settings_names()
    {
        Assert.Equal([5, 15, 30], new[] { 0, 1, 2 }.Select(StressCurve.Minutes));
        Assert.Equal(["CPU", "GPU", "CPU+GPU", "CPU"], new int?[] { 0, 1, 2, null }.Select(StressCurve.Load));
    }
}

public class SavedDataTests
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull, IncludeFields = true,   // the app's Api.Json
    };

    [Fact]
    public void A_record_saved_before_2_1_still_loads()
    {
        string old = """[{"id":"6f7f2a8e-55c6-4c1e-9d0b-4f6c2b8a9d10","timestamp":"2026-11-01T10:00:00","version":4,"score":12345,"osVersion":"11 24H2","complete":true,"submitted":true}]""";
        var list = JsonSerializer.Deserialize<List<BenchmarkRecord>>(old, Json)!;
        Assert.Equal(12345, list[0].Score);
        Assert.Null(list[0].ServerId);
        Assert.Null(list[0].OsBuild);
        Assert.Null(list[0].DeviceModel);
    }

    [Fact]
    public void New_fields_are_kept()
    {
        var r = Fixtures.Record("11 24H2", "26100.1", DateTime.Now);
        r.ServerId = 42;
        var back = JsonSerializer.Deserialize<BenchmarkRecord>(JsonSerializer.Serialize(r, Json), Json)!;
        Assert.Equal(42, back.ServerId);
        Assert.Equal("26100.1", back.OsBuild);
        Assert.Equal("ThinkPad X1", back.DeviceModel);
    }

    /// <summary>The shape of RunRecord: a positional record whose 2.1 parameters have defaults.</summary>
    private sealed record Positional(Guid Id, double Capacity, int? ServerId = null, List<double>? Curve = null, double? CurveSeconds = null);

    [Fact]
    public void A_run_record_without_the_new_parameters_still_loads()
    {
        var p = JsonSerializer.Deserialize<Positional>("""{"id":"6f7f2a8e-55c6-4c1e-9d0b-4f6c2b8a9d10","capacity":91.5}""", Json)!;
        Assert.Equal(91.5, p.Capacity);
        Assert.Null(p.ServerId);
        Assert.Null(p.Curve);
    }
}
