using System.Globalization;

namespace ThermoHammer.Engine;

/// <summary>
/// "DID THE UPDATE SLOW MY PC?" — the benchmark side of UpdateImpact: this PC's verified, complete runs (this model, this scoring
/// version) before and after a Windows update. The OS is the version and the build; a run saved without a build (before 2.1) counts
/// as the same OS when the version matches, so missing data never prompts. Nothing here is uploaded.
/// </summary>
public static class BenchmarkUpdateImpact
{
    public const double NoiseFloorPct = 3.0;
    public const int MaxRunsPerSide = 3;

    public enum Verdict { Slower, Faster, NoClearChange }

    /// <summary>A Windows version and build ("11 24H2", "26100.6584"); either may be unknown.</summary>
    public sealed record Os(string? Version, string? Build)
    {
        public string Key => $"{Version ?? ""}|{Build ?? ""}";
        /// <summary>"11 24H2 (26100.6584)".</summary>
        public string Label => Build is { Length: > 0 } b ? $"{Version} ({b})" : Version ?? "?";

        /// <summary>The same OS, an unknown part matching anything.</summary>
        public bool Same(Os o) => (Version == null || o.Version == null || Version == o.Version) && (Build == null || o.Build == null || Build == o.Build);

        public static Os Of(BenchmarkRecord r) => new(r.OsVersion, r.OsBuild);
    }

    public sealed record Side(List<BenchmarkRecord> Runs, Os Os)
    {
        public BenchmarkRecord Latest => Runs[0];
        public double MedianTotal => StressCurve.Median(Runs.Select(r => (double)r.Score).ToList());
        public double MedianSection(string key) => StressCurve.Median(Runs.Select(r => (double)r.Sections()[key]).ToList());
    }

    public sealed record SectionDelta(string Key, double Before, double After)
    {
        public string Label => SectionScores.Label(Key);
        public double DeltaPct => Before > 0 ? (After / Before - 1) * 100 : 0;
    }

    public abstract record Insight(string Key)
    {
        /// <summary>Prompt and result are dismissed separately, each for its own update.</summary>
        public abstract string DismissKey { get; }
        /// <summary>Changes when what the card shows changes (another run on either side).</summary>
        public abstract string Signature { get; }
    }

    /// <summary>The PC updated and has no benchmark run since: offer to run it again.</summary>
    public sealed record Prompt(string Key, BenchmarkRecord Last, Os Was, Os Now) : Insight(Key)
    {
        public override string DismissKey => $"prompt:{Key}";
        public override string Signature => $"{DismissKey}|{Last.Id}";
        /// <summary>"Windows 11 24H2 (26100.6584) → 11 24H2 (26100.7171). Run the benchmark again to see if the update changed your PC."</summary>
        public string Text => $"Windows {Was.Label} → {Now.Label}. Run the benchmark again to see if the update changed your {ShareLinks.Device}.";
    }

    public sealed record Comparison(string Key, Side Before, Side After, double DeltaPct, Verdict Verdict, List<SectionDelta> Sections) : Insight(Key)
    {
        public override string DismissKey => $"result:{Key}";
        public override string Signature => $"{DismissKey}|{string.Join(",", Before.Runs.Concat(After.Runs).Select(r => r.Id))}";

        public string Title => Verdict switch { Verdict.Slower => "SLOWER", Verdict.Faster => "FASTER", _ => "NO CLEAR CHANGE" };

        /// <summary>"7% slower than before the update", or why it isn't called either way.</summary>
        public string Text => Verdict == Verdict.NoClearChange
            ? $"Within {NoiseFloorPct:0}% of before the update — normal run-to-run variation. Nothing here shows the update changed your {ShareLinks.Device}."
            : $"{Pct(Math.Abs(DeltaPct))}% {(Verdict == Verdict.Slower ? "slower" : "faster")} than before the update";
    }

    /// <summary>"7" or "4.2": one decimal while it's small.</summary>
    public static string Pct(double v) =>
        v >= 9.95 ? v.ToString("0", CultureInfo.InvariantCulture) : v.ToString("0.0", CultureInfo.InvariantCulture);

    /// <summary>The runs that count: this scoring version, verified, complete, made on this model (or saved before the model was kept).</summary>
    public static bool Counts(BenchmarkRecord r, string model) =>
        r.Version == BenchmarkScore.Version && r.Complete && r.ValidityFlags == 0 && r.Score > 0 && (r.DeviceModel == null || r.DeviceModel == model);

    public static Insight? For(IEnumerable<BenchmarkRecord> history, string model, string? osVersion, string? osBuild)
    {
        var now = new Os(osVersion, osBuild);
        var mine = history.Where(r => Counts(r, model)).OrderByDescending(r => r.Timestamp).ToList();
        var current = mine.Where(r => Os.Of(r).Same(now)).ToList();
        // the update: the OS of the newest run made on another one
        var baseline = mine.FirstOrDefault(r => !Os.Of(r).Same(now));
        if (baseline == null) return null;
        var was = Os.Of(baseline);
        string key = $"{was.Key}->{now.Key}";
        if (current.Count == 0) return new Prompt(key, baseline, was, now);
        var before = mine.Where(r => !Os.Of(r).Same(now) && Os.Of(r).Same(was)).Take(MaxRunsPerSide).ToList();
        var after = current.Take(MaxRunsPerSide).ToList();
        Side b = new(before, was), a = new(after, now);
        double delta = b.MedianTotal > 0 ? (a.MedianTotal / b.MedianTotal - 1) * 100 : 0;
        var verdict = Math.Abs(delta) < NoiseFloorPct ? Verdict.NoClearChange : delta < 0 ? Verdict.Slower : Verdict.Faster;
        var sections = SectionScores.Keys.Select(k => new SectionDelta(k, b.MedianSection(k), a.MedianSection(k)))
            .Where(s => s.Before > 0 && s.After > 0).ToList();
        return new Comparison(key, b, a, delta, verdict, sections);
    }
}
