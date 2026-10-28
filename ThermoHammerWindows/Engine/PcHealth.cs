namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// PC HEALTH — "is my PC as fast as it should be?" A run against the verified
// runs of the same model (GET /benchmark/model) and against this PC's own
// earlier runs; the parts that fall short are grouped by what usually causes
// them. The phones' Phone Health (PhoneHealth.kt / .swift), the same rules.
// ─────────────────────────────────────────────────────────────────────────────

public static class PcHealth
{
    public const int MinRuns = 3;           // verified runs of a model needed before a run is compared with them
    private const double Short = 0.85;      // a part this far below the model's median is a finding
    private const double OwnDrop = 0.92;

    public enum Verdict { Faster, OnPar, Slower, MuchSlower, Unknown }

    /// <summary>severity: 0 note, 1 worth fixing, 2 the main reason.</summary>
    public sealed record Finding(string Text, int Severity);

    public sealed record Report(Verdict Verdict, string Headline, int? Percent, int Runs, int Total, int? Median, int? P25, int? P75,
        int? Best, List<Finding> Findings);

    /// <summary>The run's parts, each 6000 on the reference phone — named as the server names them.</summary>
    public static Dictionary<string, int> Parts(BenchmarkResult r)
    {
        var p = new Dictionary<string, int>();
        if (r.Cpu is { } c)
        {
            p["cpu.math"] = c.Points(CpuTest.Math); p["cpu.data"] = c.Points(CpuTest.Data);
            p["cpu.crypto"] = c.Points(CpuTest.Crypto); p["cpu.multi"] = c.Points(CpuTest.Multi);
        }
        if (r.Mem is { } m) { p["mem.ram"] = m.RamPoints; p["mem.storage"] = m.StoragePoints; p["mem.db"] = m.DbPoints; }
        if (r.Gpu.Count > 0)
        {
            int G(GpuTest t) => r.Gpu.FirstOrDefault(x => x.Test == t)?.Points ?? 0;
            p["gpu.lake"] = G(GpuTest.LakeValley); p["gpu.neon"] = G(GpuTest.NeonCity); p["gpu.rt"] = G(GpuTest.NeonCityRT);
        }
        if (r.Ux is { } u)
        {
            p["ux.web"] = u.WebPoints; p["ux.scroll"] = u.ScrollPoints; p["ux.gallery"] = u.GalleryPoints;
            p["ux.photo"] = u.PhotoPoints; p["ux.video"] = u.VideoPoints; p["ux.export"] = u.ExportPoints; p["ux.qr"] = u.QrPoints;
        }
        if (r.Ai is { } a) { p["ai.cpu"] = a.AccelScore(AiAccel.Cpu); p["ai.gpu"] = a.AccelScore(AiAccel.Gpu); p["ai.npu"] = a.AccelScore(AiAccel.Npu); }
        if (r.Llm is { } l) { p["llm.prefill"] = l.PrefillPoints; p["llm.decode"] = l.DecodePoints; }
        return p;
    }

    private enum Cause { Heat, Busy, Storage, Web, Video, Export, Npu, Llm }

    private static string Text(Cause c, int pct) => c switch
    {
        Cause.Heat => $"The GPU tests ran {pct}% below other units — it throttled. Check the fans and the vents, and that it's plugged in.",
        Cause.Busy => $"The CPU tests ran {pct}% below other units — something else was using the processor, or a power-saving mode was on.",
        Cause.Storage => $"Storage ran {pct}% below other units — drives slow down as they fill. Freeing some space usually brings it back.",
        Cause.Web => $"The browser tests ran {pct}% below other units — Edge's WebView2 engine updates itself; check that it's current.",
        Cause.Video => $"Video decoding ran {pct}% below other units — a codec ran in software (the graphics driver or a codec extension is missing).",
        Cause.Export => $"Video export ran {pct}% below other units — the encoder was slower or its output worse. Heat or a driver update can do that.",
        Cause.Npu => $"The NPU ran {pct}% below other units — it didn't take the models the way theirs did.",
        _ => $"The language model ran {pct}% below other units — usually less free memory or apps in the background.",
    };

    private static Cause CauseOf(string part) =>
        part.StartsWith("gpu.") || part == "ux.photo" || part == "ai.gpu" ? Cause.Heat
        : part is "mem.storage" or "mem.db" ? Cause.Storage
        : part is "ux.web" or "ux.scroll" or "ux.gallery" ? Cause.Web
        : part == "ux.video" ? Cause.Video
        : part == "ux.export" ? Cause.Export
        : part == "ai.npu" ? Cause.Npu
        : part.StartsWith("llm.") ? Cause.Llm
        : Cause.Busy;

    private static List<string> FlagReasons(int flags)
    {
        var r = new List<string>();
        if ((flags & BenchFlag.PowerEvent) != 0) r.Add("the charger was plugged or unplugged");
        if ((flags & BenchFlag.EnvCapped) != 0) r.Add("a power-saving mode was on");
        if ((flags & (BenchFlag.CooldownTimeout | BenchFlag.WarmStarted)) != 0) r.Add("it started warm");
        if ((flags & BenchFlag.Interference) != 0) r.Add("another app was in front or busy");
        return r;
    }

    public static Report Assess(BenchmarkResult result, string model, ModelStats? stats, List<BenchmarkRecord> history,
        BenchmarkRecord? current, string osVersion)
    {
        var findings = new List<Finding>();
        int total = result.Total;
        var reasons = FlagReasons(result.ValidityFlags);
        if (reasons.Count > 0)
            findings.Add(new($"Not a verified run: {string.Join(", ", reasons)} — it doesn't reach the leaderboard, and it may explain a lower score.", 1));
        var verdict = Verdict.Unknown;
        int? percent = null;
        string headline;
        if (stats is { Total: { } spread } && stats.Runs >= MinRuns && spread.Median > 0)
        {
            double ratio = (double)total / spread.Median;
            percent = (int)Math.Round(ratio * 100);
            verdict = ratio >= 1.05 ? Verdict.Faster : ratio >= 0.95 ? Verdict.OnPar : ratio >= Short ? Verdict.Slower : Verdict.MuchSlower;
            headline = verdict switch
            {
                Verdict.Faster => $"Faster than most {model} units",
                Verdict.OnPar => $"As fast as a {model} should be",
                Verdict.Slower => $"A little slower than other {model} units",
                _ => $"Slower than a {model} should be",
            };
            var medians = stats.Parts ?? [];
            var shortParts = Parts(result).Where(kv => medians.TryGetValue(kv.Key, out var m) && m > 0 && (double)kv.Value / m < Short)
                .Select(kv => (cause: CauseOf(kv.Key), r: (double)kv.Value / medians[kv.Key])).ToList();
            var worst = shortParts.GroupBy(x => x.cause).Select(g => (g.Key, r: g.Min(x => x.r))).OrderBy(x => x.r).Take(3).ToList();
            for (int i = 0; i < worst.Count; i++)
                findings.Add(new(Text(worst[i].Key, (int)Math.Round((1 - worst[i].r) * 100)), i == 0 ? 2 : 1));
            if (verdict is Verdict.OnPar or Verdict.Faster && !findings.Any(f => f.Severity >= 1))
                findings.Add(new("Every section is where other units are: nothing is holding it back.", 0));
            var byOs = stats.ByOsVersion ?? [];
            int @base = byOs.FirstOrDefault(o => o.OsVersion == osVersion && o.Runs >= 2)?.MedianTotal ?? spread.Median;
            var change = byOs.Where(o => o.OsVersion != osVersion && o.Runs >= 2 && @base > 0)
                .Select(o => (o, d: (double)o.MedianTotal / @base - 1)).Where(x => Math.Abs(x.d) >= 0.05)
                .OrderByDescending(x => Math.Abs(x.d)).FirstOrDefault();
            if (change.o != null)
                findings.Add(new($"On Windows {change.o.OsVersion} this model scores {(int)Math.Round(Math.Abs(change.d) * 100)}% " +
                    $"{(change.d > 0 ? "higher" : "lower")} ({change.o.Runs} runs) than on Windows {osVersion}.", 0));
        }
        else
        {
            headline = stats == null ? $"Connect to compare with other {model} units"
                : $"Not enough verified runs of {model} yet ({stats.Runs} of {MinRuns})";
        }
        var earlier = history.Where(h => h.Id != current?.Id && h.ValidityFlags == 0 && h.Score > 0 && h.Complete).ToList();
        if (earlier.MaxBy(h => h.Score) is { } best)
        {
            string date = best.Timestamp.ToString("d MMM", System.Globalization.CultureInfo.InvariantCulture);
            if (total < best.Score * OwnDrop)
            {
                string update = best.OsVersion is { } o && o != osVersion ? $" — before the update to Windows {osVersion}" : "";
                findings.Add(new($"{(int)Math.Round((1 - (double)total / best.Score) * 100)}% below this PC's best run ({date}, {best.Score:N0}){update}.",
                    percent == null ? 2 : 1));
            }
            else if (total >= best.Score) findings.Add(new("This PC's best run so far.", 0));
        }
        return new Report(verdict, headline, percent, stats?.Runs ?? 0, total, stats?.Total?.Median, stats?.Total?.P25, stats?.Total?.P75,
            stats?.Total?.Best, findings);
    }
}
