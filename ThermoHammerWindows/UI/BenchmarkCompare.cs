using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>
/// Two leaderboard runs head to head — the phones' BenchmarkCompareOverlay: who leads overall and by how much, the shape of
/// both across the six sections, every section as mirrored bars that open into each test's numbers (the better one lit in
/// its side's colour), and the facts of both machines. The board opens it with both runs loaded.
/// </summary>
public static class BenchmarkCompareView
{
    public static Color ColorA => DualStabilityChart.ColorA;
    public static Color ColorB => DualStabilityChart.ColorB;

    public static Color Of(Lead lead) => lead == Lead.A ? ColorA : lead == Lead.B ? ColorB : Forge.Ink1;

    private static Color SectionColor(string key) => key switch
    {
        "cpu" => BenchColor.Cpu,
        "mem" => BenchColor.Mem,
        "gpu" => Forge.PhaseCalibration,
        "ai" => BenchColor.Ai,
        "llm" => BenchColor.Llm,
        _ => Forge.PhaseMeasured,
    };

    private static bool Lit(Duel d, Lead side) => d.Winner == side || d.Winner == Lead.Even;
    private static bool Beaten(Duel d, Lead side) => d.Winner is Lead.A or Lead.B && d.Winner != side;

    /// <summary>Which colour is which, kept in view above the scrolling body.</summary>
    public static FrameworkElement Legend(BenchmarkDto a, BenchmarkDto b)
    {
        var row = Ui.HStack(10,
            Ui.HStack(6, Dot(ColorA), Ui.Text(a.Name, 11, ColorA, Forge.W.Bold).Grow()).Grow(),
            Ui.Text("VS", 9, Forge.Ink2, Forge.W.Black),
            Ui.HStack(6, Ui.Text(b.Name, 11, ColorB, Forge.W.Bold, align: TextAlignment.Right).Grow(), Dot(ColorB)).Grow());
        row.Stretch = false;
        foreach (FrameworkElement c in row.Children) c.VerticalAlignment = VerticalAlignment.Center;
        return row;
    }

    private static Ellipse Dot(Color c) => new() { Width = 8, Height = 8, Fill = c.Brush(), VerticalAlignment = VerticalAlignment.Center };

    public static FrameworkElement Body(BenchmarkComparison c, BenchmarkDto ea, int rankA, BenchmarkDto eb, int rankB)
    {
        var col = Ui.VStack(12);
        col.Children.Add(Ui.HStack(10, DeviceCard(ea, rankA, c.A, "A", ColorA).Grow(),
            Ui.Text("VS", 10, Forge.Ink2, Forge.W.Black).Align(HorizontalAlignment.Center, VerticalAlignment.Center),
            DeviceCard(eb, rankB, c.B, "B", ColorB).Grow()));
        col.Children.Add(Verdict(c, ea.Name, eb.Name));
        col.Children.Add(Panel(Ui.VStack(4, Ui.Text("PROFILE", 10, Forge.Ink1, Forge.W.Black),
            Ui.Text("Each axis: the better of the two reaches the edge", 9, Forge.Ink2), new CompareRadar(c.Sections))));

        // every section as mirrored bars, each opening into its groups and rows
        var details = new List<FrameworkElement>();
        var openAll = Ui.Text("OPEN ALL", 9, Forge.PhaseMeasured, Forge.W.Black);
        void Refresh() => openAll.Text = details.All(d => d.Visibility == Visibility.Visible) ? "CLOSE ALL" : "OPEN ALL";
        var openAllButton = Ui.Button(openAll, () =>
        {
            bool all = details.All(d => d.Visibility == Visibility.Visible);
            foreach (var d in details) d.Visibility = all ? Visibility.Collapsed : Visibility.Visible;
            Refresh();
        }, Colors.Transparent, 6, 6, 4);
        col.Children.Add(Ui.HStack(8, Ui.Text("SECTION BY SECTION", 10, Forge.Ink1, Forge.W.Black).Align(HorizontalAlignment.Left, VerticalAlignment.Center),
            Ui.Spacer(), openAllButton).Pad(2, 4, 2, 0));
        foreach (var s in c.Sections)
        {
            var (view, detail) = SectionDuel(s, Refresh);
            details.Add(detail);
            col.Children.Add(view);
        }
        col.Children.Add(Facts(c, ea, eb));
        return col;
    }

    private static Border Panel(UIElement child) => Ui.Box(child, Forge.Surface, 16, 16, Forge.Hairline);

    private static FrameworkElement DeviceCard(BenchmarkDto e, int rank, BenchmarkDetail d, string letter, Color color)
    {
        var badge = Ui.Box(Ui.Text(letter, 12, Forge.Bg, Forge.W.Black, align: TextAlignment.Center)
            .Align(HorizontalAlignment.Center, VerticalAlignment.Center), color, 12).Size(24, 24);
        var top = Ui.HStack(8, badge, Ui.Spacer(), rank > 0 ? Ui.Text($"#{rank}", 13, Forge.Ink1, Forge.W.Black) : null);
        top.Stretch = false;
        var chips = string.Join(" · ", new[] { d.SocModel ?? e.SocModel, d.GpuName ?? e.GpuName }.Where(s => !string.IsNullOrEmpty(s)));
        var col = Ui.VStack(3, top,
            Ui.Text(e.Name, 14, Forge.Ink0, Forge.W.Bold, wrap: true).Pad(0, 6, 0, 0),
            Ui.Text(chips, 9, Forge.Ink0.WithAlpha(0.45), wrap: true),
            Ui.Text(DeviceInfo.OsLabel(d.Os, d.OsVersion), 9, Forge.Ink0.WithAlpha(0.45)),
            Ui.Spacer(),
            Ui.Text(d.TotalScore.N0(), 24, color, Forge.W.Black).Pad(0, 8, 0, 0),
            Ui.Text("TOTAL POINTS", 8, Forge.Ink2, Forge.W.Bold));
        return Ui.Box(col, color.WithAlpha(0.07), 16, 14, color.WithAlpha(0.4));
    }

    private static FrameworkElement Verdict(BenchmarkComparison c, string nameA, string nameB)
    {
        var color = c.Total.Winner is Lead.A or Lead.B ? Of(c.Total.Winner) : Forge.Ink0;
        var totals = Ui.HStack(8, Ui.Text(c.A.TotalScore.N0(), 12, ColorA, Forge.W.Black), Ui.Spacer(),
            Ui.Text("SHARE OF BOTH TOTALS", 8, Forge.Ink2, Forge.W.Bold).Align(HorizontalAlignment.Center, VerticalAlignment.Center), Ui.Spacer(),
            Ui.Text(c.B.TotalScore.N0(), 12, ColorB, Forge.W.Black));
        return Panel(Ui.VStack(6,
            Ui.Text(c.Headline(nameA, nameB), 16, color, Forge.W.Black, wrap: true),
            Ui.Text(c.SectionsLine(), 10, Forge.Ink1),
            new CompareSplitBar(c.TotalShareA).Pad(0, 8, 0, 0),
            totals));
    }

    private static (FrameworkElement view, FrameworkElement detail) SectionDuel(ComparedSection s, Action changed)
    {
        var accent = SectionColor(s.Key);
        var chevron = Ui.Text("▸", 10, Forge.Ink2, align: TextAlignment.Center);
        var head = Ui.HStack(8,
            ScoreCell(s.ScoreA, s.Duel, Lead.A).Size(w: 96),
            new CompareHalfBar(s.ShareA, ColorA, Lit(s.Duel, Lead.A), towardLeft: true, height: 8).Grow(),
            Ui.VStack(1, Ui.Text(s.Short, 12, accent, Forge.W.Black, align: TextAlignment.Center), chevron).Size(w: 70),
            new CompareHalfBar(s.ShareB, ColorB, Lit(s.Duel, Lead.B), towardLeft: false, height: 8).Grow(),
            ScoreCell(s.ScoreB, s.Duel, Lead.B).Size(w: 96));
        head.Stretch = false;
        head.Background = Brushes.Transparent;   // the whole line takes the click, not just its text
        head.Cursor = Cursors.Hand;
        foreach (FrameworkElement c in head.Children) c.VerticalAlignment = VerticalAlignment.Center;

        var detail = Ui.VStack(4);
        detail.Visibility = Visibility.Collapsed;
        foreach (var g in s.Groups) detail.Children.Add(GroupDuel(g, accent));

        var box = Ui.Box(Ui.VStack(6, head, detail), Forge.Surface, 14, 12, accent.WithAlpha(0.18));
        detail.IsVisibleChanged += (_, _) =>
        {
            chevron.Text = detail.Visibility == Visibility.Visible ? "▾" : "▸";
            box.BorderBrush = accent.WithAlpha(detail.Visibility == Visibility.Visible ? 0.45 : 0.18).Brush();
        };
        head.MouseLeftButtonUp += (_, _) =>
        {
            detail.Visibility = detail.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
            changed();
        };
        return (box, detail);
    }

    private static FrameworkElement ScoreCell(int? score, Duel d, Lead side)
    {
        bool won = d.Winner == side;
        var align = side == Lead.B ? TextAlignment.Right : TextAlignment.Left;
        var color = won ? Of(side) : Beaten(d, side) ? Forge.Ink0.WithAlpha(0.45) : Forge.Ink0;
        string note = won ? (d.Advantage is { } p ? BenchmarkComparison.AdvantageText(p) : " ") : d.Winner == Lead.Even ? "EVEN" : " ";
        return Ui.VStack(1, Ui.Text(score is { } v ? v.N0() : "—", 15, color, Forge.W.Black, align: align),
            Ui.Text(note, 9, won ? Of(side) : Forge.Ink2, Forge.W.Black, align: align));
    }

    private static FrameworkElement GroupDuel(ComparedGroup g, Color accent)
    {
        var col = Ui.VStack(5);
        col.Margin = new Thickness(0, 10, 0, 0);
        col.Children.Add(Mirrored(
            Ui.Text(g.PointsA is { } a ? $"{a.N0()} PTS" : "", 10, PointsColor(g.Duel, Lead.A), Forge.W.Black, align: TextAlignment.Right),
            Ui.Text(g.Title.ToUpperInvariant(), 9, accent, Forge.W.Black, wrap: true, align: TextAlignment.Center),
            Ui.Text(g.PointsB is { } b ? $"{b.N0()} PTS" : "", 10, PointsColor(g.Duel, Lead.B), Forge.W.Black)));
        if (g.PointsA != null || g.PointsB != null)
            col.Children.Add(Mirrored(new CompareHalfBar(g.ShareA, ColorA, Lit(g.Duel, Lead.A), true, 4), null,
                new CompareHalfBar(g.ShareB, ColorB, Lit(g.Duel, Lead.B), false, 4)));
        foreach (var r in g.Rows)
            col.Children.Add(Mirrored(Value(r.A, r.Duel, Lead.A), Ui.Text(r.Label, 10, Forge.Ink2, wrap: true, align: TextAlignment.Center),
                Value(r.B, r.Duel, Lead.B)));
        return col;
    }

    private static Color PointsColor(Duel d, Lead side) => d.Winner == side ? Of(side) : Beaten(d, side) ? Forge.Ink0.WithAlpha(0.45) : Forge.Ink1;

    /// <summary>One mirrored line: A toward the middle from the left, the label in the middle, B toward the middle from the right.</summary>
    private static Grid Mirrored(UIElement? left, UIElement? middle, UIElement? right)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(200) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        void Put(UIElement? e, int column)
        {
            if (e == null) return;
            if (e is FrameworkElement fe && column != 1) fe.Margin = new Thickness(column == 0 ? 0 : 10, 0, column == 0 ? 10 : 0, 0);
            Grid.SetColumn(e, column);
            grid.Children.Add(e);
        }
        Put(left, 0);
        Put(middle, 1);
        Put(right, 2);
        return grid;
    }

    /// <summary>One run's value; the better one lit in its side's colour, with how far ahead it is on the outer side.</summary>
    private static TextBlock Value(string? text, Duel d, Lead side)
    {
        bool won = d.Winner == side;
        var color = won ? Of(side) : Beaten(d, side) ? Forge.Ink0.WithAlpha(0.42) : Forge.Ink0.WithAlpha(0.85);
        var t = Ui.Text("", 11, color, won ? Forge.W.Bold : Forge.W.Regular, wrap: true, align: side == Lead.A ? TextAlignment.Right : TextAlignment.Left);
        var (family, weight) = Forge.Mono(Forge.W.Black);
        Run Badge(double p) => new(BenchmarkComparison.AdvantageText(p)) { FontSize = 9, FontFamily = family, FontWeight = weight };
        t.Inlines.Clear();
        if (won && side == Lead.A && d.Advantage is { } pa) { t.Inlines.Add(Badge(pa)); t.Inlines.Add(new Run("  ")); }
        t.Inlines.Add(new Run(text ?? "—"));
        if (won && side == Lead.B && d.Advantage is { } pb) { t.Inlines.Add(new Run("  ")); t.Inlines.Add(Badge(pb)); }
        return t;
    }

    private static FrameworkElement Facts(BenchmarkComparison c, BenchmarkDto ea, BenchmarkDto eb)
    {
        var rows = new (string label, string? a, string? b)[]
        {
            ("CHIP", c.A.SocModel, c.B.SocModel),
            ("GPU", c.A.GpuName, c.B.GpuName),
            ("SYSTEM", DeviceInfo.OsLabel(c.A.Os, c.A.OsVersion), DeviceInfo.OsLabel(c.B.Os, c.B.OsVersion)),
            ("BUILD", c.A.OsBuild, c.B.OsBuild),
            ("APP", c.A.AppVersion, c.B.AppVersion),
            ("RUN DATE", c.A.Date, c.B.Date),   // UTC, like the phones
            ("RUNS ON THE BOARD", ea.Runs.ToString(), eb.Runs.ToString()),
        };
        var col = Ui.VStack(6, Ui.Text("DEVICES", 10, Forge.Ink1, Forge.W.Black));
        foreach (var (label, a, b) in rows)
            col.Children.Add(Mirrored(Ui.Text(a ?? "—", 10, ColorA.WithAlpha(0.9), wrap: true, align: TextAlignment.Right),
                Ui.Text(label, 8, Forge.Ink2, Forge.W.Bold, align: TextAlignment.Center),
                Ui.Text(b ?? "—", 10, ColorB.WithAlpha(0.9), wrap: true)));
        return Panel(col);
    }
}

/// <summary>Grows from 0 to 1 once the element is on screen: the bars and the radar draw themselves in.</summary>
public abstract class RevealElement : FrameworkElement
{
    public static readonly DependencyProperty ProgressProperty = DependencyProperty.Register(nameof(Progress), typeof(double), typeof(RevealElement),
        new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsRender));

    public double Progress { get => (double)GetValue(ProgressProperty); set => SetValue(ProgressProperty, value); }

    protected RevealElement(int milliseconds)
    {
        Loaded += (_, _) => BeginAnimation(ProgressProperty,
            new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(milliseconds)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
    }
}

/// <summary>A hexagon of the sections: each axis scaled so the better of the two touches the edge, A drawn over B.</summary>
public sealed class CompareRadar : RevealElement
{
    private readonly List<ComparedSection> sections;

    public CompareRadar(List<ComparedSection> sections) : base(900)
    {
        this.sections = sections;
        Height = 300;
    }

    protected override void OnRender(DrawingContext dc)
    {
        int n = sections.Count;
        if (n < 3) return;
        var c = new Point(ActualWidth / 2, ActualHeight / 2);
        double r = Math.Min(ActualWidth, ActualHeight) / 2 * 0.72;
        Point At(int i, double f)
        {
            double angle = -Math.PI / 2 + 2 * Math.PI * i / n;
            return new Point(c.X + Math.Cos(angle) * r * f, c.Y + Math.Sin(angle) * r * f);
        }
        StreamGeometry Polygon(IReadOnlyList<double> fractions)
        {
            var g = new StreamGeometry();
            using (var ctx = g.Open())
            {
                ctx.BeginFigure(At(0, fractions[0]), true, true);
                for (int i = 1; i < n; i++) ctx.LineTo(At(i, fractions[i]), true, true);
            }
            g.Freeze();
            return g;
        }
        for (int level = 1; level <= 4; level++)
            dc.DrawGeometry(null, new Pen(Forge.Ink0.WithAlpha(level == 4 ? 0.16 : 0.06).Brush(), 1), Polygon(Enumerable.Repeat(level / 4.0, n).ToList()));
        var spoke = new Pen(Forge.Ink0.WithAlpha(0.07).Brush(), 1);
        for (int i = 0; i < n; i++) dc.DrawLine(spoke, c, At(i, 1));

        void Shape(IEnumerable<double> shares, Color color)
        {
            var f = shares.Select(s => s * Progress).ToList();
            dc.DrawGeometry(color.WithAlpha(0.16).Brush(), new Pen(color.Brush(), 2) { LineJoin = PenLineJoin.Round }, Polygon(f));
            for (int i = 0; i < n; i++) dc.DrawEllipse(color.Brush(), null, At(i, f[i]), 3, 3);
        }
        Shape(sections.Select(s => s.ShareB), BenchmarkCompareView.ColorB);
        Shape(sections.Select(s => s.ShareA), BenchmarkCompareView.ColorA);
        for (int i = 0; i < n; i++)
            dc.DrawText(sections[i].Short, 11, BenchmarkCompareView.Of(sections[i].Duel.Winner), At(i, 1.2), 0.5, 0.5, Forge.W.Black);
    }
}

/// <summary>One side's half of a mirrored bar: A grows from the middle to the left, B to the right.</summary>
public sealed class CompareHalfBar : RevealElement
{
    private readonly double share;
    private readonly Color color;
    private readonly bool strong, towardLeft;

    public CompareHalfBar(double share, Color color, bool strong, bool towardLeft, double height) : base(700)
    {
        this.share = share;
        this.color = color;
        this.strong = strong;
        this.towardLeft = towardLeft;
        Height = height;
        VerticalAlignment = VerticalAlignment.Center;
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        dc.DrawRoundedRectangle(Forge.Ink0.WithAlpha(0.05).Brush(), null, new Rect(0, 0, w, h), h / 2, h / 2);
        double fw = w * Math.Clamp(share, 0, 1) * Progress;
        if (fw <= 0) return;
        dc.DrawRoundedRectangle(color.WithAlpha(strong ? 1 : 0.4).Brush(), null, new Rect(towardLeft ? w - fw : 0, 0, fw, h), h / 2, h / 2);
    }
}

/// <summary>The totals as one bar: A's share from the left, B's from the right.</summary>
public sealed class CompareSplitBar : RevealElement
{
    private readonly double shareA;

    public CompareSplitBar(double shareA) : base(800)
    {
        this.shareA = shareA;
        Height = 12;
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight, gap = 2;
        double f = Math.Clamp(0.5 + (shareA - 0.5) * Progress, 0.02, 0.98);
        double wa = (w - gap) * f;
        var a = BenchmarkCompareView.ColorA;
        var b = BenchmarkCompareView.ColorB;
        dc.DrawRoundedRectangle(new LinearGradientBrush(a.WithAlpha(0.55), a, 0), null, new Rect(0, 0, wa, h), h / 2, h / 2);
        dc.DrawRoundedRectangle(new LinearGradientBrush(b, b.WithAlpha(0.55), 0), null, new Rect(wa + gap, 0, Math.Max(0, w - wa - gap), h), h / 2, h / 2);
    }
}
