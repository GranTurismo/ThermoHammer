using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>
/// "DID THE UPDATE SLOW MY PC?" for the benchmark — the stress card's rules and look (UpdateImpactCard). PROMPT: Windows changed and
/// there's no benchmark run since — run it again. RESULT: the median totals before and after, called slower or faster only beyond
/// 3 %, and each section's change. Wording is temporal ("after the update"), never causal.
/// </summary>
public sealed class BenchmarkUpdateCard : Grid
{
    public BenchmarkUpdateCard(BenchmarkUpdateImpact.Insight insight, Action onRunBenchmark, Action onDismiss)
    {
        Children.Add(insight switch
        {
            BenchmarkUpdateImpact.Prompt p => PromptCard(p, onRunBenchmark, onDismiss),
            BenchmarkUpdateImpact.Comparison c => ComparisonCard(c, onDismiss),
            _ => new Grid(),
        });
    }

    private static FrameworkElement Shell(Color accent, params UIElement[] rows)
    {
        var g = new Grid();
        g.Children.Add(Ui.Box(Ui.VStack(10, rows), Forge.Surface, 12, 14, accent.WithAlpha(0.25)));
        g.Children.Add(new CornerTicks { Color = accent.WithAlpha(0.5) });
        return g;
    }

    private static FrameworkElement PromptCard(BenchmarkUpdateImpact.Prompt p, Action run, Action dismiss)
    {
        var accent = Forge.PhasePreFlight;
        var head = Ui.HStack(8, new StatusLed(accent).Align(HorizontalAlignment.Left, VerticalAlignment.Center), Ui.Engraved("UPDATE DETECTED", accent, 10),
            Ui.Engraved("· BENCHMARK", size: 8).Align(HorizontalAlignment.Left, VerticalAlignment.Center),
            Ui.Spacer(), Ui.Button(Ui.Engraved("NOT NOW", size: 7.5), dismiss, Colors.Transparent, 6, 6, 4));
        return Shell(accent, head,
            Ui.Text(p.Text, 9.5, Forge.Ink0, wrap: true),
            Ui.Text($"Your last benchmark: {p.Last.Score.N0()} points · {Date(p.Last.Timestamp)}", 8, Forge.Ink1, wrap: true),
            Ui.Button("RUN BENCHMARK", run, accent.WithAlpha(0.10), accent, 9, Forge.W.Black, 10, 12, stroke: accent.WithAlpha(0.4)));
    }

    private static FrameworkElement ComparisonCard(BenchmarkUpdateImpact.Comparison c, Action dismiss)
    {
        var tint = c.Verdict switch
        {
            BenchmarkUpdateImpact.Verdict.Slower => Ramp.At(0.68), BenchmarkUpdateImpact.Verdict.Faster => Forge.PhaseMeasured, _ => Forge.Ink1,
        };
        var head = Ui.HStack(0, Ui.Engraved("BENCHMARK · UPDATE IMPACT", Forge.Ink1, 10), Ui.Spacer(),
            Ui.Button(Ui.Text("✕", 11, Forge.Ink2), dismiss, Colors.Transparent, 6, 8, 2));
        var sides = Ui.HStack(6, Side("BEFORE", c.Before, false), Ui.Spacer(),
            Ui.Text("→", 16, Forge.Ink2).Align(HorizontalAlignment.Center, VerticalAlignment.Center), Ui.Spacer(), Side("AFTER", c.After, true));
        var verdict = Ui.HStack(10, Ui.Text(Signed(c.DeltaPct) + "%", 30, tint, display: true),
            Ui.Engraved(c.Title, tint, 8.5).Align(HorizontalAlignment.Left, VerticalAlignment.Bottom));
        verdict.Stretch = false;
        var rows = new List<UIElement> { head, sides, verdict, Ui.Text(c.Text, 8.5, Forge.Ink0, wrap: true) };
        if (c.Sections.Count > 0)
        {
            var grid = new UniformGrid3();
            foreach (var s in c.Sections) grid.Children.Add(SectionDelta(s));
            rows.Add(grid);
        }
        rows.Add(Ui.Text("Same PC, verified runs only, the median of up to 3 runs a side. This shows what changed after the update — not what caused it.",
            7, Forge.Ink2, wrap: true));
        return Shell(tint, [.. rows]);
    }

    private static FrameworkElement Side(string tag, BenchmarkUpdateImpact.Side side, bool right)
    {
        var a = right ? TextAlignment.Right : TextAlignment.Left;
        return Ui.VStack(2, Ui.Engraved(tag, size: 7).Align(right ? HorizontalAlignment.Right : HorizontalAlignment.Left),
            Ui.Text(Math.Round(side.MedianTotal).N0(), 20, Forge.Ink0, Forge.W.Bold, align: a),
            Ui.Text($"Windows {side.Os.Label}", 8, Forge.Ink0, Forge.W.Bold, align: a),
            Ui.Text(side.Runs.Count == 1 ? "1 run" : $"median of {side.Runs.Count} runs", 6.5, Forge.Ink2, align: a));
    }

    private static FrameworkElement SectionDelta(BenchmarkUpdateImpact.SectionDelta s)
    {
        double d = s.DeltaPct;
        var c = Math.Abs(d) < BenchmarkUpdateImpact.NoiseFloorPct ? Forge.Ink1 : d < 0 ? Ramp.At(0.68) : Forge.PhaseMeasured;
        var row = Ui.HStack(6, Ui.Text(s.Label, 9, RunArt.SectionColor(s.Key), Forge.W.Black), Ui.Spacer(), Ui.Text(Signed(d) + "%", 9, c, Forge.W.Bold));
        row.Margin = new Thickness(0, 0, 12, 4);
        return row;
    }

    /// <summary>Proper minus sign; one decimal while it's small; an exact zero stays unsigned.</summary>
    private static string Signed(double v)
    {
        string n = BenchmarkUpdateImpact.Pct(Math.Abs(v));
        return v > 0.05 ? $"+{n}" : v < -0.05 ? $"−{n}" : "0.0";
    }

    private static string Date(DateTime t) => t.ToString("d MMM", CultureInfo.InvariantCulture).ToUpperInvariant();

    /// <summary>Three columns of equal width (CPU MEM GPU / UX AI LLM).</summary>
    private sealed class UniformGrid3 : System.Windows.Controls.Primitives.UniformGrid
    {
        public UniformGrid3() { Columns = 3; }
    }
}
