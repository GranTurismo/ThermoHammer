namespace ThermoHammer.Engine;

/// <summary>One point of a stress run's curve: seconds into the measured run, throughput as % of the run's own baseline.</summary>
public readonly record struct CurvePoint(double Seconds, double Percent);

/// <summary>
/// A stress run as a curve — what the share card, the presentation and the run record draw: throughput as % of the baseline over
/// time, at most 120 points, and where it throttled. Own runs are measured against their calibration baseline; a leaderboard run
/// (GET /stamps/{id}, scores only) against the median of its first 5 seconds.
/// </summary>
public static class StressCurve
{
    public const int MaxPoints = 120;
    /// <summary>A leaderboard run's baseline: the median score of its first seconds.</summary>
    public const int BaselineMs = 5000;

    /// <summary>Own run: each stamp's throughput as % of the calibration baseline.</summary>
    public static List<CurvePoint> OfBaseline(IEnumerable<(int ElapsedMs, double Throughput)> stamps, double baseline) =>
        stamps.Select(s => new CurvePoint(s.ElapsedMs / 1000.0, baseline > 0 ? s.Throughput / baseline * 100 : 0)).ToList();

    /// <summary>Leaderboard run: each stamp's score as % of the median of the run's first 5 seconds (of its first stamp when it has none there).</summary>
    public static List<CurvePoint> OfFirstSeconds(IReadOnlyList<(int ElapsedMs, long Score)> stamps)
    {
        if (stamps.Count == 0) return [];
        var first = stamps.Where(s => s.ElapsedMs <= BaselineMs).Select(s => (double)s.Score).ToList();
        double baseline = first.Count > 0 ? Median(first) : stamps[0].Score;
        return OfBaseline(stamps.Select(s => (s.ElapsedMs, (double)s.Score)), baseline);
    }

    /// <summary>
    /// At most [max] evenly spaced points: a run with more stamps is cut into [max] equal runs of stamps and each becomes its mean
    /// (time and value), which also smooths the second-to-second noise a full-screen curve would show.
    /// </summary>
    public static List<CurvePoint> Downsample(IReadOnlyList<CurvePoint> points, int max = MaxPoints)
    {
        if (points.Count <= max) return [.. points];
        var o = new List<CurvePoint>(max);
        for (int i = 0; i < max; i++)
        {
            int a = (int)((long)i * points.Count / max), b = (int)((long)(i + 1) * points.Count / max);
            double t = 0, v = 0;
            for (int k = a; k < b; k++) { t += points[k].Seconds; v += points[k].Percent; }
            o.Add(new CurvePoint(t / (b - a), v / (b - a)));
        }
        return o;
    }

    /// <summary>The curve as the run record keeps it: values to 1 decimal (the times follow from the run's length).</summary>
    public static List<double> Compact(IReadOnlyList<CurvePoint> points) =>
        points.Select(p => Math.Round(p.Percent, 1, MidpointRounding.AwayFromZero)).ToList();

    /// <summary>A kept curve back as points, evenly spaced from 0 to [seconds].</summary>
    public static List<CurvePoint> Expand(IReadOnlyList<double> values, double seconds) =>
        values.Select((v, i) => new CurvePoint(values.Count > 1 ? seconds * i / (values.Count - 1) : 0, v)).ToList();

    /// <summary>
    /// When it throttled: the stress engine's rule — the first time throughput stays below 90 % of the baseline for 3 stamps in a row,
    /// dated two stamps before the third (null: it never did). For a run that only has its stamps (a leaderboard run).
    /// </summary>
    public static int? Onset(IReadOnlyList<CurvePoint> stamps)
    {
        int streak = 0;
        foreach (var s in stamps)
        {
            streak = s.Percent < 90 ? streak + 1 : 0;
            if (streak >= 3) return Math.Max(0, (int)s.Seconds - 2);
        }
        return null;
    }

    /// <summary>The y axis: 0 to 110 %, higher when the run went above that.</summary>
    public static double Top(IReadOnlyList<CurvePoint> points) => Math.Max(110, points.Count > 0 ? points.Max(p => p.Percent) : 0);

    /// <summary>"02:15".</summary>
    public static string Clock(int seconds) => $"{Math.Max(0, seconds) / 60:00}:{Math.Max(0, seconds) % 60:00}";

    /// <summary>The line under the curve: "Throttled after 02:15" or "Never throttled".</summary>
    public static string OnsetLine(int? onsetSec) => onsetSec is { } o ? $"Throttled after {Clock(o)}" : "Never throttled";

    /// <summary>The minutes the run was set to (the server's HammerType: 0 = 5, 1 = 15, 2 = 30).</summary>
    public static int Minutes(int durationType) => durationType switch { 1 => 15, 2 => 30, _ => 5 };

    /// <summary>What the run loaded: the server's StressMode (0 CPU, 1 GPU, 2 both; null — a run from before modes — the CPU).</summary>
    public static string Load(int? stressMode) => stressMode switch { 1 => "GPU", 2 => "CPU+GPU", _ => "CPU" };

    public static double Median(IReadOnlyList<double> values)
    {
        if (values.Count == 0) return 0;
        var v = values.OrderBy(x => x).ToList();
        int m = v.Count / 2;
        return v.Count % 2 == 1 ? v[m] : (v[m - 1] + v[m]) / 2;
    }
}
