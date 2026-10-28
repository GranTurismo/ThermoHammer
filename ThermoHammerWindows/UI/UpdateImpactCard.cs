using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>
/// UPDATE IMPACT CARD — the PC compared with ITSELF across a Windows update. PROMPT: Windows changed and there's no
/// usable test since — offer to repeat the old one. RESULT: before → after, medians of like-for-like clean runs, with a
/// verdict only when the change is bigger than normal run-to-run noise. Wording is temporal ("after update"), never causal.
/// </summary>
public sealed class UpdateImpactCard : Grid
{
    public UpdateImpactCard(UpdateImpact.Insight insight, Action<RunRecord> onRetest, Action onDismiss)
    {
        Margin = new Thickness(0, 0, 0, 0);
        Children.Add(insight switch
        {
            UpdateImpact.Prompt p => PromptCard(p, onRetest, onDismiss),
            UpdateImpact.Comparison c => ComparisonCard(c, onRetest, onDismiss),
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

    private static FrameworkElement PromptCard(UpdateImpact.Prompt p, Action<RunRecord> retest, Action dismiss)
    {
        var accent = Forge.PhasePreFlight;
        var head = Ui.HStack(8, new StatusLed(accent).Align(HorizontalAlignment.Left, VerticalAlignment.Center), Ui.Engraved("UPDATE DETECTED", accent, 10),
            Ui.Spacer(), Ui.Button(Ui.Engraved("NOT NOW", size: 7.5), dismiss, Colors.Transparent, 6, 6, 4));
        var oses = Ui.HStack(6, OsColumn("WAS", p.Last.OsVersion, p.Last.OsBuild, false), Ui.Spacer(),
            Ui.Text("→", 16, Forge.Ink2).Align(HorizontalAlignment.Center, VerticalAlignment.Center), Ui.Spacer(),
            OsColumn("NOW", p.NowOsVersion, p.NowOsBuild, true));
        return Shell(accent, head, oses,
            Ui.Text($"Your last test: {p.Last.Capacity:0.0}% · {Settings(p.Last)} · {Date(p.Last.Timestamp)}", 9.5, Forge.Ink0, wrap: true),
            Ui.Text("Run the same test again to see whether this update changed how your PC holds up under load.", 8, Forge.Ink1, wrap: true),
            Action("RUN SAME TEST", accent, () => retest(p.Last)));
    }

    private static FrameworkElement ComparisonCard(UpdateImpact.Comparison c, Action<RunRecord> retest, Action dismiss)
    {
        var tint = c.Verdict switch { UpdateImpact.Verdict.Slower => Ramp.At(0.68), UpdateImpact.Verdict.Faster => Forge.PhaseMeasured, _ => Forge.Ink1 };
        string verdict = c.Verdict switch
        {
            UpdateImpact.Verdict.Slower => "LOWER AFTER UPDATE", UpdateImpact.Verdict.Faster => "HIGHER AFTER UPDATE", _ => "NO CLEAR CHANGE",
        };
        var head = Ui.HStack(0, Ui.Engraved("UPDATE IMPACT", Forge.Ink1, 10), Ui.Spacer(), Ui.Button(Ui.Text("✕", 11, Forge.Ink2), dismiss, Colors.Transparent, 6, 8, 2));
        var sides = Ui.HStack(6, Side("BEFORE", c.Before, false), Ui.Spacer(), Ui.Text("→", 16, Forge.Ink2).Align(HorizontalAlignment.Center, VerticalAlignment.Center),
            Ui.Spacer(), Side("AFTER", c.After, true));
        var delta = Ui.HStack(10, Ui.Text(Signed(c.DeltaPts) + " pts", 30, tint, display: true), Ui.Engraved(verdict, tint, 8.5).Align(HorizontalAlignment.Left, VerticalAlignment.Bottom));
        delta.Stretch = false;
        var rows = new List<UIElement>
        {
            head, sides, delta,
            Ui.Text(c.Verdict == UpdateImpact.Verdict.NoClearChange
                ? $"Within normal run-to-run variation (±{c.ThresholdPts:0} pts). Nothing here shows the update changed your PC."
                : $"Bigger than normal run-to-run variation (±{c.ThresholdPts:0} pts) for this PC and test.", 8, Forge.Ink1, wrap: true),
            Ui.Text("Same PC, same settings, clean runs only. This shows what changed after the update — not what caused it. " +
                    "Room temperature, dust in the fans and the power plan still matter.", 7, Forge.Ink2, wrap: true),
        };
        if (c.Weak)
        {
            rows.Add(Ui.Text($"Based on {Runs(c.Before.Runs.Count)} before and {Runs(c.After.Runs.Count)} after — run again to confirm.", 8, Ramp.At(0.5), wrap: true));
            rows.Add(Action("RUN AGAIN TO CONFIRM", tint, () => retest(c.Before.Latest)));
        }
        return Shell(tint, [.. rows]);
    }

    private static FrameworkElement OsColumn(string tag, string version, string? build, bool right)
    {
        var a = right ? TextAlignment.Right : TextAlignment.Left;
        var col = Ui.VStack(2, Ui.Engraved(tag, size: 7).Align(right ? HorizontalAlignment.Right : HorizontalAlignment.Left),
            Ui.Text($"Windows {version}", 12, Forge.Ink0, Forge.W.Bold, align: a));
        if (!string.IsNullOrEmpty(build)) col.Children.Add(Ui.Text($"BUILD {build}", 7, Forge.Ink1, align: a));
        return col;
    }

    private static FrameworkElement Side(string tag, UpdateImpact.Side side, bool right)
    {
        var a = right ? TextAlignment.Right : TextAlignment.Left;
        var col = Ui.VStack(2, Ui.Engraved(tag, size: 7).Align(right ? HorizontalAlignment.Right : HorizontalAlignment.Left),
            Ui.Text($"{side.Median:0.0}%", 20, Ramp.ForCapacity(side.Median), Forge.W.Bold, align: a),
            Ui.Text($"Windows {side.Latest.OsVersion}", 9, Forge.Ink0, Forge.W.Bold, align: a));
        if (!string.IsNullOrEmpty(side.Latest.OsBuild)) col.Children.Add(Ui.Text($"BUILD {side.Latest.OsBuild}", 6.5, Forge.Ink1, align: a));
        col.Children.Add(Ui.Text(side.Runs.Count == 1 ? "1 run" : $"median of {side.Runs.Count} runs", 6.5, Forge.Ink2, align: a));
        return col;
    }

    private static Button Action(string text, Color accent, Action action) =>
        Ui.Button(text, action, accent.WithAlpha(0.10), accent, 9, Forge.W.Black, 10, 12, stroke: accent.WithAlpha(0.4));

    /// <summary>Proper minus sign; an exact zero stays unsigned.</summary>
    private static string Signed(double v) => v > 0.05 ? $"+{v:0.0}" : v < -0.05 ? $"−{-v:0.0}" : "0.0";
    private static string Runs(int n) => n == 1 ? "1 run" : $"{n} runs";
    private static string Settings(RunRecord r) =>
        $"{(r.DurationType switch { 0 => "5 MIN", 1 => "15 MIN", 2 => "30 MIN", _ => "?" })} · {(r.ThreadingType == 0 ? "1 THREAD" : "MULTI THREAD")}";
    private static string Date(double ts) =>
        DateTimeOffset.FromUnixTimeMilliseconds((long)(ts * 1000)).LocalDateTime.ToString("d MMM", CultureInfo.InvariantCulture).ToUpperInvariant();
}
