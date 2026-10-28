using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

public sealed record StabilityPoint(double Time, double Score);

/// <summary>One run's curve: score relative to its peak over time, thermal-state changes marked, the pointer reading it out.</summary>
public sealed class StabilityChart : FrameworkElement
{
    private readonly List<StabilityPoint> points;
    private readonly List<(double time, ThermalLevel state)> events;
    private double? pointerX;

    public StabilityChart(List<StabilityPoint> points, List<(double, ThermalLevel)> events)
    {
        this.points = points;
        this.events = events;
        Height = 250;
        Cursor = Cursors.Cross;
    }

    protected override void OnMouseMove(MouseEventArgs e) { pointerX = e.GetPosition(this).X; InvalidateVisual(); }
    protected override void OnMouseLeave(MouseEventArgs e) { pointerX = null; InvalidateVisual(); }

    public static Color ScoreColor(double s) => s >= 90 ? Color.FromRgb(51, 204, 102) : s >= 75 ? Color.FromRgb(242, 178, 26) : Color.FromRgb(230, 89, 26);

    private static LinearGradientBrush LineGradient(double top, double h)
    {
        var g = new LinearGradientBrush { StartPoint = new Point(0, top), EndPoint = new Point(0, top + h), MappingMode = BrushMappingMode.Absolute };
        g.GradientStops.Add(new GradientStop(Color.FromRgb(51, 204, 102), 0));
        g.GradientStops.Add(new GradientStop(Color.FromRgb(242, 178, 26), 0.25));
        g.GradientStops.Add(new GradientStop(Color.FromRgb(230, 89, 26), 0.5));
        g.GradientStops.Add(new GradientStop(Color.FromRgb(217, 51, 51), 1));
        return g;
    }

    private static string Fmt(double t) => $"{(int)t / 60}m:{(int)t % 60:00}s";

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        dc.DrawRoundedRectangle(Color.FromArgb(150, 20, 20, 20).Brush(), Color.FromArgb(15, 255, 255, 255).Pen(1), new Rect(0, 0, w, h), 20, 20);
        dc.DrawText("UNIFIED STABILITY CHART", 11, Forge.Ink1, new Point(16, 16), w: Forge.W.Bold);
        var plot = new Rect(16, 44, w - 32, h - 80);
        foreach (var f in new[] { 0.0, 0.25, 0.5, 0.75, 1.0 })
        {
            double y = plot.Y + plot.Height * f;
            if (f > 0 && f < 1) dc.DrawLine(Color.FromArgb(10, 255, 255, 255).Pen(1), new Point(plot.X, y), new Point(plot.Right, y));
            dc.DrawText($"{(int)(100 - f * 100)}%", 8, Forge.Ink2, new Point(plot.X + 2, y - 6), 0, 0.5);
        }
        if (points.Count == 0) return;
        double t0 = points[0].Time, range = Math.Max(1, points[^1].Time) - t0;
        Point P(StabilityPoint p) => new(plot.X + (range > 0 ? (p.Time - t0) / range : 0) * plot.Width,
            plot.Bottom - Math.Clamp(p.Score / 100, 0, 1) * plot.Height);
        var line = new StreamGeometry();
        var area = new StreamGeometry();
        using (var c = line.Open())
        using (var a = area.Open())
        {
            var first = P(points[0]);
            c.BeginFigure(first, false, false);
            a.BeginFigure(new Point(plot.X, plot.Bottom), true, true);
            a.LineTo(first, false, false);
            for (int i = 0; i < points.Count - 1; i++)
            {
                Point p0 = P(points[i]), p1 = P(points[i + 1]);
                var c1 = new Point(p0.X + (p1.X - p0.X) / 2, p0.Y);
                var c2 = new Point(p0.X + (p1.X - p0.X) / 2, p1.Y);
                c.BezierTo(c1, c2, p1, true, true);
                a.BezierTo(c1, c2, p1, false, false);
            }
            if (points.Count == 1) { c.LineTo(new Point(plot.Right, first.Y), true, true); a.LineTo(new Point(plot.Right, first.Y), false, false); }
            a.LineTo(new Point(points.Count == 1 ? plot.Right : P(points[^1]).X, plot.Bottom), false, false);
        }
        line.Freeze(); area.Freeze();
        var fill = LineGradient(plot.Y, plot.Height);
        dc.PushOpacity(0.15);
        dc.DrawGeometry(fill, null, area);
        dc.Pop();
        dc.DrawGeometry(null, new Pen(LineGradient(plot.Y, plot.Height), 3.5) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, LineJoin = PenLineJoin.Round }, line);
        foreach (var (time, state) in events)
        {
            double x = plot.X + (range > 0 ? (time - t0) / range : 0) * plot.Width;
            dc.DrawLine(Forge.ThermalColor(state).WithAlpha(0.5).Pen(1, new DashStyle([4, 4], 0)), new Point(x, plot.Y), new Point(x, plot.Bottom));
        }
        dc.DrawText("0m:00s", 9, Forge.Ink2, new Point(plot.X, h - 18), w: Forge.W.Bold);
        dc.DrawText(Fmt(points[^1].Time), 9, Forge.Ink2, new Point(plot.Right, h - 18), 1, 0, Forge.W.Bold);
        if (pointerX is { } px && px >= plot.X && px <= plot.Right)
        {
            double target = t0 + (px - plot.X) / plot.Width * range;
            var near = points.MinBy(p => Math.Abs(p.Time - target))!;
            var np = P(near);
            var cyan = Color.FromRgb(0, 230, 255);
            dc.DrawLine(cyan.WithAlpha(0.7).Pen(1.5, new DashStyle([6, 4], 0)), new Point(np.X, plot.Y), new Point(np.X, plot.Bottom));
            dc.DrawEllipse(cyan.WithAlpha(0.25).Brush(), null, np, 10, 10);
            dc.DrawEllipse(cyan.Brush(), null, np, 4, 4);
            dc.DrawText($"{Fmt(near.Time)}  SCORE {near.Score:0.0}%", 10, ScoreColor(near.Score), new Point(w - 16, 16), 1, 0, Forge.W.Black);
        }
    }
}

/// <summary>Two runs' curves on one time axis.</summary>
public sealed class DualStabilityChart : FrameworkElement
{
    private readonly List<StabilityPoint> a, b;
    private readonly string labelA, labelB;
    public static readonly Color ColorA = Color.FromRgb(0, 230, 255), ColorB = Color.FromRgb(255, 171, 0);
    private double? pointerX;

    public DualStabilityChart(List<StabilityPoint> a, string labelA, List<StabilityPoint> b, string labelB)
    {
        this.a = a; this.b = b; this.labelA = labelA; this.labelB = labelB;
        Height = 260;
    }

    protected override void OnMouseMove(MouseEventArgs e) { pointerX = e.GetPosition(this).X; InvalidateVisual(); }
    protected override void OnMouseLeave(MouseEventArgs e) { pointerX = null; InvalidateVisual(); }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        dc.DrawRoundedRectangle(Color.FromArgb(150, 20, 20, 20).Brush(), Color.FromArgb(15, 255, 255, 255).Pen(1), new Rect(0, 0, w, h), 20, 20);
        dc.DrawText("DUAL STABILITY COMPARISON", 11, Forge.Ink1, new Point(16, 16), w: Forge.W.Bold);
        dc.DrawEllipse(ColorA.Brush(), null, new Point(16 + 4, 42), 4, 4);
        dc.DrawText(labelA, 9, ColorA, new Point(30, 42), 0, 0.5, Forge.W.Bold);
        double lx = 30 + Forge.Text(labelA, 9, ColorA, Forge.W.Bold).Width + 18;
        dc.DrawEllipse(ColorB.Brush(), null, new Point(lx, 42), 4, 4);
        dc.DrawText(labelB, 9, ColorB, new Point(lx + 10, 42), 0, 0.5, Forge.W.Bold);
        var plot = new Rect(16, 60, w - 32, h - 76);
        foreach (var f in new[] { 0.25, 0.5, 0.75 })
            dc.DrawLine(Color.FromArgb(10, 255, 255, 255).Pen(1), new Point(plot.X, plot.Y + plot.Height * f), new Point(plot.Right, plot.Y + plot.Height * f));
        double total = Math.Max(1, Math.Max(a.LastOrDefault()?.Time ?? 1, b.LastOrDefault()?.Time ?? 1));
        void Curve(List<StabilityPoint> pts, Color c)
        {
            if (pts.Count == 0) return;
            Point P(StabilityPoint p) => new(plot.X + Math.Clamp(p.Time / total, 0, 1) * plot.Width, plot.Bottom - Math.Clamp(p.Score / 100, 0, 1) * plot.Height);
            var g = new StreamGeometry();
            using (var ctx = g.Open())
            {
                ctx.BeginFigure(P(pts[0]), false, false);
                for (int i = 0; i < pts.Count - 1; i++)
                {
                    Point p0 = P(pts[i]), p1 = P(pts[i + 1]);
                    ctx.BezierTo(new Point(p0.X + (p1.X - p0.X) / 2, p0.Y), new Point(p0.X + (p1.X - p0.X) / 2, p1.Y), p1, true, true);
                }
            }
            g.Freeze();
            dc.DrawGeometry(null, c.Pen(3, cap: PenLineCap.Round), g);
        }
        Curve(a, ColorA);
        Curve(b, ColorB);
        if (pointerX is { } px && px >= plot.X && px <= plot.Right)
            dc.DrawLine(Colors.White.WithAlpha(0.5).Pen(1.5, new DashStyle([6, 4], 0)), new Point(px, plot.Y), new Point(px, plot.Bottom));
    }
}

/// <summary>Two stress runs side by side — the phones' ComparisonEngine.</summary>
public static class ComparisonEngine
{
    public enum Winner { A, B, Equal }

    public sealed record Analysis(double PeakA, double PeakB, double AvgA, double AvgB, double PeakDeltaPct, double AvgDeltaPct,
        int? OnsetA, int? OnsetB, Winner Winner, string Summary);

    public static Analysis Analyze(PendingTestResult a, PendingTestResult b)
    {
        var sa = a.Stamps; var sb = b.Stamps;
        double peakA = sa.Count > 0 ? sa.Max(s => s.Score) : Math.Max(100, a.FinalStability * 2500);
        double peakB = sb.Count > 0 ? sb.Max(s => s.Score) : Math.Max(100, b.FinalStability * 2500);
        double avgA = sa.Count > 0 ? sa.Average(s => s.Score) : Math.Max(90, a.FinalStability * 2200);
        double avgB = sb.Count > 0 ? sb.Average(s => s.Score) : Math.Max(90, b.FinalStability * 2200);
        int? onsetA = sa.FirstOrDefault(s => s.Score < peakA * 0.9)?.ElapsedMs / 1000;
        int? onsetB = sb.FirstOrDefault(s => s.Score < peakB * 0.9)?.ElapsedMs / 1000;
        int scoreA = 0, scoreB = 0;
        if (a.FinalStability > b.FinalStability) scoreA += 2; else if (b.FinalStability > a.FinalStability) scoreB += 2;
        if (avgA > avgB) scoreA += 2; else if (avgB > avgA) scoreB += 2;
        var winner = scoreA > scoreB ? Winner.A : scoreB > scoreA ? Winner.B : Winner.Equal;
        string nameA = $"{a.DeviceManufacturer} {a.DeviceModel}", nameB = $"{b.DeviceManufacturer} {b.DeviceModel}";
        string s = winner switch
        {
            Winner.A => $"{nameA} demonstrated superior thermal stability ({a.FinalStability:0.0}% vs {b.FinalStability:0.0}%). ",
            Winner.B => $"{nameB} demonstrated superior thermal stability ({b.FinalStability:0.0}% vs {a.FinalStability:0.0}%). ",
            _ => "Both runs demonstrated equivalent thermal performance. ",
        };
        if (onsetA is { } oa && onsetB is { } ob)
        {
            if (oa > ob) s += $"Run A delayed thermal throttling by {oa - ob}s longer than Run B. ";
            else if (ob > oa) s += $"Run B delayed thermal throttling by {ob - oa}s longer than Run A. ";
        }
        else if (onsetA == null && onsetB != null) s += $"Run A maintained sustained performance without significant throttling, whereas Run B throttled at {onsetB}s. ";
        else if (onsetB == null && onsetA != null) s += $"Run B maintained sustained performance without significant throttling, whereas Run A throttled at {onsetA}s. ";
        return new Analysis(peakA, peakB, avgA, avgB, peakA > 0 ? (peakB - peakA) / peakA * 100 : 0, avgA > 0 ? (avgB - avgA) / avgA * 100 : 0,
            onsetA, onsetB, winner, s);
    }

    public static List<StabilityPoint> Points(PendingTestResult r)
    {
        if (r.Stamps.Count > 0)
        {
            double max = Math.Max(1, r.Stamps.Max(s => s.Score));
            return r.Stamps.Select(s => new StabilityPoint(s.ElapsedMs / 1000, s.Score / max * 100)).ToList();
        }
        return [new(0, r.FinalStability), new(r.DurationSeconds, r.FinalStability)];
    }
}
