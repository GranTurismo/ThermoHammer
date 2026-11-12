using System.Globalization;
using System.Windows;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// RUN SHOWCASE — one run as the share card and the presentation show it (own
// or from the leaderboard, benchmark or stress), and the drawing both use: the
// six section bars and the stress curve.
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>What the share card, the presentation and the share text say about a run.</summary>
public sealed class ShowcaseRun
{
    public bool Stress { get; init; }
    /// <summary>This PC's own run ("My … scored") rather than one from the leaderboard.</summary>
    public bool Mine { get; init; }
    public string Device { get; init; } = "";
    /// <summary>"Windows 11 24H2".</summary>
    public string Os { get; init; } = "";
    public DateTime? Date { get; init; }
    public string? Chip { get; init; }
    public bool Verified { get; init; }
    /// <summary>The run's id on the server (its page); null before it's submitted — the link is then the site's front page.</summary>
    public int? ServerId { get; init; }

    // benchmark
    public int Total { get; init; }
    /// <summary>The sections the run has, in the order CPU, MEM, GPU, UX, AI, LLM.</summary>
    public List<(string Key, int Score)> Sections { get; init; } = [];

    // stress
    public double Capacity { get; init; }
    public int Minutes { get; init; } = 5;
    public string Load { get; init; } = "CPU";
    /// <summary>"MULTI THREAD" / "1 THREAD" when known.</summary>
    public string? Threading { get; init; }
    public List<CurvePoint> Curve { get; init; } = [];
    public int? OnsetSec { get; init; }

    public string Url => Stress ? ShareLinks.Stress(ServerId) : ShareLinks.Run(ServerId);
    public string Text => Stress ? ShareLinks.StressText(Device, Capacity, Minutes, Url, Mine) : ShareLinks.BenchmarkText(Device, Total, Url, Mine);
    public string DateText => Date?.ToString("d MMM yyyy", CultureInfo.InvariantCulture) ?? "";
    /// <summary>"Windows 11 24H2 · 12 Nov 2026 · Snapdragon X Elite X1E78100".</summary>
    public string SubLine => string.Join(" · ", new[] { Os, DateText, Chip }.Where(s => !string.IsNullOrWhiteSpace(s)));
    /// <summary>"SPEED KEPT · 5-MIN CPU LOAD".</summary>
    public string StressLabel => $"SPEED KEPT · {Minutes}-MIN {Load} LOAD";
    /// <summary>The run's seconds on the curve's time axis.</summary>
    public double CurveSeconds => Math.Max(1, Curve.Count > 0 ? Curve[^1].Seconds : Minutes * 60);

    private static List<(string, int)> Ordered(IEnumerable<(string Key, int Score)> sections)
    {
        var have = sections.ToList();
        return SectionScores.Keys.Where(k => have.Any(s => s.Key == k)).Select(k => (k, have.First(s => s.Key == k).Score)).ToList();
    }

    /// <summary>This PC's benchmark result, just finished.</summary>
    public static ShowcaseRun ForResult(BenchmarkResult r, BenchmarkRecord? rec)
    {
        var sections = new List<(string, int)>();
        if (r.Cpu != null) sections.Add(("cpu", r.CpuScore));
        if (r.Mem != null) sections.Add(("mem", r.MemScore));
        if (r.Gpu.Count > 0) sections.Add(("gpu", r.GpuScore));
        if (r.Ux != null) sections.Add(("ux", r.UxScore));
        if (r.Ai != null) sections.Add(("ai", r.AiScore));
        if (r.Llm != null) sections.Add(("llm", r.LlmScore));
        return new ShowcaseRun
        {
            Mine = true, Device = PcHealth.Name(), Os = DeviceInfo.OsLabel(DeviceInfo.OsPlatform, DeviceInfo.OsVersion),
            Date = rec?.Timestamp ?? DateTime.Now, Chip = DeviceInfo.ShortCpu(DeviceInfo.CpuName), Verified = r.IsVerified && r.IsComplete,
            ServerId = rec?.ServerId, Total = r.Total, Sections = sections,
        };
    }

    /// <summary>This PC's stress run, just finished: the curve from its stamps against its calibration baseline.</summary>
    public static ShowcaseRun ForStress(Scorecard sc, IReadOnlyList<StampV2> stamps, PendingTestResult p, int? serverId) => new()
    {
        Stress = true, Mine = true, Device = DeviceInfo.FullName, Os = DeviceInfo.OsLabel(DeviceInfo.OsPlatform, p.OsVersion),
        Date = DateTimeOffset.FromUnixTimeMilliseconds((long)(p.Timestamp * 1000)).LocalDateTime, Chip = p.SocModel, Verified = sc.IsVerified,
        ServerId = serverId, Capacity = sc.DeliveredCapacity, Minutes = StressCurve.Minutes(p.TestDurationType),
        Threading = (p.TestThreadingType ?? 1) == 0 ? "1 THREAD" : "MULTI THREAD",
        Curve = StressCurve.Downsample(StressCurve.OfBaseline(stamps.Select(s => (s.ElapsedMs, (double)s.IpsTotal)), sc.BaselineIps)),
        OnsetSec = sc.ThrottleOnsetSec,
    };

    /// <summary>A leaderboard benchmark run (GET /benchmark/{id}); the board keeps verified runs only.</summary>
    public static ShowcaseRun ForBoard(BenchmarkDetail d, BenchmarkDto e) => new()
    {
        Device = e.Name, Os = DeviceInfo.OsLabel(d.Os, d.OsVersion),
        Date = d.CreatedAt.Kind == DateTimeKind.Local ? d.CreatedAt.ToUniversalTime() : d.CreatedAt,
        Chip = d.SocModel, Verified = true, ServerId = d.Id, Total = d.TotalScore,
        Sections = Ordered(d.Sections.Select(s => (s.Key.ToLowerInvariant(), s.Score))),
    };

    /// <summary>A leaderboard stress run: its curve from GET /stamps/{id} against the median of its first 5 seconds.</summary>
    public static ShowcaseRun ForBoard(HammerDto e, IReadOnlyList<DeviceHammerStamp> stamps)
    {
        var all = StressCurve.OfFirstSeconds(stamps.Select(s => (s.ElapsedMs, s.Score)).ToList());
        return new ShowcaseRun
        {
            Stress = true, Device = e.Name, Os = DeviceInfo.OsLabel(e.Os, e.OsVersion), Date = e.CreatedAt, Chip = e.SocModel, Verified = true,
            ServerId = e.Id, Capacity = e.StabilityPercentage, Minutes = StressCurve.Minutes(e.Type), Load = StressCurve.Load(e.StressMode),
            Threading = (e.TestThreadingType ?? 1) == 0 ? "1 THREAD" : "MULTI THREAD",
            Curve = StressCurve.Downsample(all), OnsetSec = StressCurve.Onset(all),
        };
    }
}

/// <summary>The drawing the share card (1 px = 1 unit, at 96 dpi) and the presentation (on screen) share.</summary>
public static class RunArt
{
    /// <summary>Each section's colour, as the result screen shows them.</summary>
    public static Color SectionColor(string key) => key switch
    {
        "cpu" => BenchColor.Cpu, "mem" => BenchColor.Mem, "gpu" => Forge.PhaseCalibration, "ux" => Forge.PhaseMeasured, "ai" => BenchColor.Ai,
        _ => BenchColor.Llm,
    };

    public static FormattedText Text(string s, double size, Color color, Forge.W w, bool display, double ppd, double maxWidth = 0, int maxLines = 0)
    {
        var (family, weight) = display ? Forge.Display(w >= Forge.W.Bold) : Forge.Mono(w);
        var t = new FormattedText(s, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, new Typeface(family, FontStyles.Normal, weight, FontStretches.Normal),
            size, color.Brush(), ppd);
        if (maxWidth > 0) { t.MaxTextWidth = maxWidth; t.Trimming = TextTrimming.CharacterEllipsis; }
        if (maxLines > 0) t.MaxLineCount = maxLines;
        return t;
    }

    /// <summary>Text with its first line's baseline at [baseline]; [ax] 0 left, 0.5 centre, 1 right of [x]. Returns its width.</summary>
    public static double Draw(DrawingContext dc, FormattedText t, double x, double baseline, double ax = 0)
    {
        dc.DrawText(t, new Point(x - t.WidthIncludingTrailingWhitespace * ax, baseline - t.Baseline));
        return t.WidthIncludingTrailingWhitespace;
    }

    /// <summary>Letter-spaced capitals (the engraved labels), baseline at [baseline]. Returns the width.</summary>
    public static double Tracked(DrawingContext dc, string s, double size, Color color, Forge.W w, double x, double baseline, double tracking, double ppd, double ax = 0)
    {
        var glyphs = s.Select(ch => Text(ch.ToString(), size, color, w, false, ppd)).ToList();
        double width = glyphs.Sum(g => g.WidthIncludingTrailingWhitespace) + tracking * Math.Max(0, glyphs.Count - 1);
        double at = x - width * ax;
        foreach (var g in glyphs) { dc.DrawText(g, new Point(at, baseline - g.Baseline)); at += g.WidthIncludingTrailingWhitespace + tracking; }
        return width;
    }

    /// <summary>
    /// The six section bars, one row each in [area]: the name, a bar as long as score ÷ max(12000, the largest score), the score.
    /// [size] is the text size; rows share the height.
    /// </summary>
    public static void Bars(DrawingContext dc, Rect area, IReadOnlyList<(string Key, int Score)> sections, double size, double ppd)
    {
        if (sections.Count == 0) return;
        double top = Math.Max(12000, sections.Max(s => s.Score));
        double row = area.Height / sections.Count, bar = Math.Max(4, size * 0.42);
        double nameW = Text("LLM", size, Forge.Ink0, Forge.W.Black, false, ppd).Width + size * 0.9;
        double valueW = Text("888,888", size, Forge.Ink0, Forge.W.Black, false, ppd).Width + size * 0.9;
        double trackX = area.X + nameW, trackW = Math.Max(10, area.Width - nameW - valueW);
        for (int i = 0; i < sections.Count; i++)
        {
            var (key, score) = sections[i];
            var c = SectionColor(key);
            double mid = area.Y + row * (i + 0.5);
            double baseline = mid + size * 0.36;
            Draw(dc, Text(SectionScores.Label(key), size, c, Forge.W.Black, false, ppd), area.X, baseline);
            dc.DrawRoundedRectangle(Forge.Ink0.WithAlpha(0.07).Brush(), null, new Rect(trackX, mid - bar / 2, trackW, bar), bar / 2, bar / 2);
            double len = Math.Clamp(score / top, 0, 1) * trackW;
            if (len > 0)
                dc.DrawRoundedRectangle(new LinearGradientBrush(c.WithAlpha(0.55), c, 0), null, new Rect(trackX, mid - bar / 2, Math.Max(bar, len), bar), bar / 2, bar / 2);
            Draw(dc, Text(ShareLinks.Points(score), size, Forge.Ink0, Forge.W.Black, false, ppd), area.Right, baseline, 1);
        }
    }

    /// <summary>The colour a point of the curve takes: the thermal ramp by how much of the baseline is left.</summary>
    private static LinearGradientBrush RampBrush(Rect plot, double yTop, double alpha)
    {
        var g = new LinearGradientBrush { StartPoint = new Point(0, plot.Top), EndPoint = new Point(0, plot.Bottom), MappingMode = BrushMappingMode.Absolute };
        foreach (double v in new[] { yTop, 100, 90, 80, 70, 60, 50, 40, 0 }.Where(v => v <= yTop).Distinct())
            g.GradientStops.Add(new GradientStop(Ramp.ForCapacity(v).WithAlpha(alpha), 1 - v / yTop));
        g.Freeze();
        return g;
    }

    /// <summary>
    /// The stress curve on its card: % of the baseline over the run, y from 0 to max(110, peak), a dashed 100 % line, the area under
    /// the curve tinted by the thermal ramp, where it throttled marked. [size] scales the text and lines (1 = share card size).
    /// </summary>
    public static void Curve(DrawingContext dc, Rect card, IReadOnlyList<CurvePoint> points, double seconds, int? onset, double size, double ppd)
    {
        double r = 24 * size;
        dc.DrawRoundedRectangle(Forge.Surface.Brush(), Forge.Hairline.Pen(Math.Max(1, 1.5 * size)), card, r, r);
        double label = 20 * size;
        var plot = new Rect(card.X + 30 * size + Text("100%", label, Forge.Ink2, Forge.W.Bold, false, ppd).Width, card.Y + 34 * size,
            0, 0);
        plot.Width = Math.Max(10, card.Right - 30 * size - plot.X);
        plot.Height = Math.Max(10, card.Bottom - 30 * size - label * 1.6 - plot.Y);
        double top = StressCurve.Top(points);
        double Y(double pct) => plot.Bottom - Math.Clamp(pct / top, 0, 1) * plot.Height;
        double X(double t) => plot.X + Math.Clamp(t / Math.Max(1, seconds), 0, 1) * plot.Width;

        foreach (double v in new[] { 0.0, 50, 100 })
        {
            bool hundred = v == 100;
            var pen = hundred ? Forge.Ink1.WithAlpha(0.55).Pen(Math.Max(1, 2 * size), new DashStyle([5, 4], 0))
                : Forge.Ink0.WithAlpha(0.07).Pen(Math.Max(1, size));
            dc.DrawLine(pen, new Point(plot.X, Y(v)), new Point(plot.Right, Y(v)));
            Draw(dc, Text($"{v:0}%", label, hundred ? Forge.Ink1 : Forge.Ink2, Forge.W.Bold, false, ppd), plot.X - 10 * size, Y(v) + label * 0.35, 1);
        }
        if (points.Count > 0)
        {
            var line = new StreamGeometry();
            var area = new StreamGeometry();
            using (var c = line.Open())
            using (var a = area.Open())
            {
                var first = new Point(X(points[0].Seconds), Y(points[0].Percent));
                c.BeginFigure(first, false, false);
                a.BeginFigure(new Point(first.X, plot.Bottom), true, true);
                a.LineTo(first, false, false);
                for (int i = 1; i < points.Count; i++)
                {
                    var p = new Point(X(points[i].Seconds), Y(points[i].Percent));
                    c.LineTo(p, true, true);
                    a.LineTo(p, false, false);
                }
                if (points.Count == 1) { c.LineTo(new Point(plot.Right, first.Y), true, true); a.LineTo(new Point(plot.Right, first.Y), false, false); }
                a.LineTo(new Point(points.Count == 1 ? plot.Right : X(points[^1].Seconds), plot.Bottom), false, false);
            }
            line.Freeze(); area.Freeze();
            dc.DrawGeometry(RampBrush(plot, top, 0.30), null, area);
            var stroke = new Pen(RampBrush(plot, top, 1), Math.Max(2, 5 * size)) { LineJoin = PenLineJoin.Round, StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round };
            stroke.Freeze();
            dc.DrawGeometry(null, stroke, line);
        }
        if (onset is { } o && o <= seconds)
        {
            double x = X(o);
            var mark = Ramp.At(0.62);
            dc.DrawLine(mark.WithAlpha(0.8).Pen(Math.Max(1, 2 * size), new DashStyle([3, 3], 0)), new Point(x, plot.Y), new Point(x, plot.Bottom));
            dc.DrawEllipse(mark.Brush(), null, new Point(x, plot.Y), 6 * size, 6 * size);
        }
        double timeBase = plot.Bottom + label * 1.45;
        Draw(dc, Text("00:00", label, Forge.Ink2, Forge.W.Bold, false, ppd), plot.X, timeBase);
        Draw(dc, Text(StressCurve.Clock((int)Math.Round(seconds)), label, Forge.Ink2, Forge.W.Bold, false, ppd), plot.Right, timeBase, 1);
    }

    /// <summary>The "✓ VERIFIED" pill, right edge at [right], vertically centred on [mid]. Returns its width.</summary>
    public static double VerifiedPill(DrawingContext dc, double right, double mid, double size, double ppd)
    {
        var c = Forge.PhaseMeasured;
        var t = Text("✓ VERIFIED", size, c, Forge.W.Black, false, ppd);
        double h = size * 2, w = t.Width + size * 1.6;
        var rect = new Rect(right - w, mid - h / 2, w, h);
        dc.DrawRoundedRectangle(c.WithAlpha(0.14).Brush(), c.WithAlpha(0.6).Pen(Math.Max(1, size / 11)), rect, h / 2, h / 2);
        Draw(dc, t, rect.X + rect.Width / 2, mid + size * 0.36, 0.5);
        return w;
    }
}
