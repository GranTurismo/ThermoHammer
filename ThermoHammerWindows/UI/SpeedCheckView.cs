using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>
/// FIND MY LOST SPEED — one card for the whole check: what it does, 30 seconds of measuring, what's slowing the PC down (and the
/// setting to change), then the same check again and how the two compare. The last check is kept for an hour, so the card opens on
/// its diagnosis after a trip to Settings — even after the app was closed.
/// </summary>
public sealed class SpeedCheckView : Grid, IDisposable
{
    private const double CardWidth = 460;
    private readonly Action close;
    private readonly ContentControl body = new();
    private CancellationTokenSource? cancel;
    /// <summary>The check the next one is compared with.</summary>
    private SpeedResult? first;

    /// <summary>Opens the check over everything (not while a benchmark or stress test runs).</summary>
    public static void Open()
    {
        Action close = () => { };
        var view = new SpeedCheckView(() => close());
        close = Sheets.Show(view);
    }

    private SpeedCheckView(Action close)
    {
        this.close = close;
        Children.Add(Overlays.Card(body, CardWidth));
        first = SpeedCheckStore.Fresh();
        if (first != null) ShowDiagnosis(first, saved: true); else ShowIntro();
    }

    public void Dispose() => cancel?.Cancel();

    private static bool TestRunning => StressEngine.Shared.IsRunning || BenchmarkEngine.Shared.IsRunning;

    private static FrameworkElement Title(string sub) => Ui.VStack(3,
        Ui.Engraved("FIND MY LOST SPEED", Forge.PhaseMeasured, 11),
        sub.Length > 0 ? Ui.Text(sub, 8, Forge.Ink2, Forge.W.Bold) : null);

    private static Button Primary(string label, Action act, bool enabled = true) =>
        Ui.Button(label, act, Forge.PhaseMeasured, Forge.Bg, 10, Forge.W.Black, 10, 11, enabled);

    private static Button Secondary(string label, Action act, bool enabled = true) =>
        Ui.Button(label, act, Forge.Interact, Forge.Ink0, 10, Forge.W.Black, 10, 11, enabled);

    private Button Done() => Ui.Button("DONE", close, Colors.Transparent, Forge.Ink1, 10, Forge.W.Bold, 10, 9);

    // ── what it is ──

    private void ShowIntro() => body.Content = Ui.VStack(14,
        Title(""),
        Ui.Text($"A 30-second check of how fast your {ShareLinks.Device} really runs, then what's slowing it down.", 12, Forge.Ink0, Forge.W.Bold, wrap: true),
        Ui.Text("It runs the benchmark's multi-core CPU work on every core and watches whether the speed holds once the first burst is over. " +
                "Close other apps first.", 9, Forge.Ink1, wrap: true),
        TestRunning ? Ui.Text("A test is running — the check can start when it has finished.", 9, Ramp.At(0.5), wrap: true) : null,
        Primary("START 30-SECOND CHECK", () => _ = Measure(), !TestRunning),
        Done());

    // ── measuring ──

    private async Task Measure()
    {
        if (TestRunning) { ShowIntro(); return; }
        bool again = first != null;
        var bar = new FillBar(Forge.PhaseMeasured);
        var spark = new Sparkline(Forge.PhaseMeasured) { Height = 70 };
        var rate = Ui.Text("—", 30, Forge.Ink0, Forge.W.Black);
        var second = Ui.Text($"0 / {SpeedMath.Seconds} s", 9, Forge.Ink1, Forge.W.Bold);
        cancel = new CancellationTokenSource();
        var token = cancel.Token;
        body.Content = Ui.VStack(14,
            Title(again ? "CHECKING AGAIN" : "MEASURING"),
            Ui.HStack(8, Ui.VStack(2, rate, Ui.Text("OPS/S · ALL CORES", 8, Forge.Ink2, Forge.W.Bold)).Grow(), second.Align(HorizontalAlignment.Right, VerticalAlignment.Bottom)),
            spark, bar,
            Ui.Text("Keep this window in front and leave the PC alone until it's done.", 9, Forge.Ink1, wrap: true),
            Secondary("CANCEL", () => { cancel?.Cancel(); if (first != null) ShowDiagnosis(first, saved: true); else ShowIntro(); }));
        SpeedResult result;
        try
        {
            result = await new SpeedCheck().Run(p => Dispatcher.BeginInvoke(() =>
            {
                if (token.IsCancellationRequested) return;
                rate.Text = p.Rate.N0();
                second.Text = $"{p.Second} / {SpeedMath.Seconds} s";
                bar.Fraction = (double)p.Second / SpeedMath.Seconds;
                spark.Values = p.Rates;
            }), token);
        }
        catch (OperationCanceledException) { return; }
        catch (Exception e)
        {
            if (token.IsCancellationRequested) return;
            body.Content = Ui.VStack(14, Title("THE CHECK STOPPED"), Ui.Text(e.Message, 10, Ramp.At(0.85), wrap: true), Done());
            return;
        }
        if (token.IsCancellationRequested) return;
        SpeedCheckStore.Save(result);
        var before = first;
        first = result;
        if (before != null) ShowComparison(before, result); else ShowDiagnosis(result, saved: false);
    }

    // ── what it found ──

    private void ShowDiagnosis(SpeedResult r, bool saved)
    {
        var d = SpeedMath.Diagnose(r, SpeedCheckStore.LastHealth);
        int minutes = (int)((DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000.0 - r.Timestamp) / 60);
        var col = Ui.VStack(12, Title(saved ? $"YOUR CHECK {(minutes < 1 ? "JUST NOW" : $"{minutes} MIN AGO")}" : "RESULT"));
        col.Children.Add(Numbers(r));
        col.Children.Add(new Sparkline(Forge.PhaseMeasured) { Height = 46, Values = r.Rates });
        foreach (var f in d.Findings) col.Children.Add(Finding(f));
        if (d.Note != null) col.Children.Add(Ui.Text(d.Note, 9, Forge.Ink1, wrap: true));
        if (d.Findings.All(f => f.Cause == SpeedCause.NoLostSpeed))
        {
            // nothing is holding it back: no setting to change — DONE leads, and checking again starts afresh (no comparison)
            col.Children.Add(Primary("DONE", close));
            col.Children.Add(Secondary("CHECK AGAIN", () => { first = null; _ = Measure(); }, !TestRunning));
            body.Content = col;
            return;
        }
        if (d.PowerSettingsHelp)
            col.Children.Add(Secondary("OPEN POWER SETTINGS", () =>
            {
                try { Process.Start(new ProcessStartInfo(SpeedMath.PowerSettingsUri) { UseShellExecute = true }); }
                catch (Exception e) { Sheets.Message("COULDN'T OPEN SETTINGS", e.Message); }
            }));
        col.Children.Add(Primary("I CHANGED IT — CHECK AGAIN", () => _ = Measure(), !TestRunning));
        // a check kept from earlier: start afresh instead of comparing with it
        if (saved) col.Children.Add(Ui.Button("START A NEW CHECK", () => { first = null; _ = Measure(); }, Colors.Transparent, Forge.Ink1, 9,
            Forge.W.Bold, 10, 7, !TestRunning));
        col.Children.Add(Done());
        body.Content = col;
    }

    private static FrameworkElement Numbers(SpeedResult r) => Ui.HStack(8,
        Number(r.Burst.N0(), "BURST OPS/S", Forge.Ink0),
        Number(r.Sustained.N0(), "SUSTAINED OPS/S", Forge.Ink0),
        Number($"{SpeedMath.Kept(r)}%", "SPEED KEPT", Ramp.ForCapacity(SpeedMath.Kept(r))));

    private static FrameworkElement Number(string value, string label, Color c) =>
        Ui.VStack(2, Ui.Text(value, 15, c, Forge.W.Black), Ui.Text(label, 7, Forge.Ink2, Forge.W.Bold)).Grow();

    private static FrameworkElement Finding(SpeedFinding f)
    {
        var c = f.Cause switch
        {
            SpeedCause.NoLostSpeed => Forge.PhaseMeasured, SpeedCause.Heat => Ramp.At(0.68), SpeedCause.HeldBackWhileCool => Ramp.At(0.55),
            _ => Ramp.At(0.5),
        };
        var b = Ui.Box(Ui.VStack(4, Ui.Text(f.Title, 10, c, Forge.W.Black, wrap: true), Ui.Text(f.Text, 9, Forge.Ink0, wrap: true)),
            c.WithAlpha(0.07), 10, 0, c.WithAlpha(0.35));
        b.Padding = new Thickness(12, 9, 12, 9);
        return b;
    }

    // ── before and after ──

    private void ShowComparison(SpeedResult a, SpeedResult b)
    {
        var cmp = SpeedMath.Compare(a, b);
        var c = cmp.Change switch { SpeedChange.Faster => Forge.PhaseMeasured, SpeedChange.Slower => Ramp.At(0.68), _ => Forge.Ink1 };
        double top = Math.Max(1, new[] { a.Burst, a.Sustained, b.Burst, b.Sustained }.Max());
        var runs = Ui.HStack(14, Run("BEFORE", a, top, Forge.Ink1).Grow(), Run("AFTER", b, top, c).Grow());
        body.Content = Ui.VStack(14,
            Title("COMPARED"),
            Ui.Text(cmp.Title, 26, c, Forge.W.Black),
            Ui.Text(cmp.Text, 11, Forge.Ink0, Forge.W.Bold, wrap: true),
            runs,
            Ui.Text($"Sustained speed {b.Sustained.N0()} against {a.Sustained.N0()} ops/s before.", 8, Forge.Ink2, wrap: true),
            Done());
    }

    private static FrameworkElement Run(string tag, SpeedResult r, double top, Color c) => Ui.VStack(6,
        Ui.Engraved(tag, size: 8),
        Pair("BURST", r.Burst, top, c.WithAlpha(0.6)),
        Pair("SUSTAINED", r.Sustained, top, c));

    private static FrameworkElement Pair(string label, double value, double top, Color c) => Ui.VStack(3,
        Ui.HStack(4, Ui.Text(label, 7, Forge.Ink2, Forge.W.Bold), Ui.Spacer(), Ui.Text(value.N0(), 9, Forge.Ink0, Forge.W.Bold)),
        new FillBar(c) { Fraction = value / top });

    /// <summary>A thin bar filled to [Fraction].</summary>
    private sealed class FillBar(Color color) : FrameworkElement
    {
        private double fraction;
        public double Fraction { get => fraction; set { fraction = Math.Clamp(value, 0, 1); InvalidateVisual(); } }
        protected override Size MeasureOverride(Size s) => new(0, 6);
        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth;
            dc.DrawRoundedRectangle(Forge.Ink0.WithAlpha(0.08).Brush(), null, new Rect(0, 0, w, 6), 3, 3);
            if (fraction > 0) dc.DrawRoundedRectangle(color.Brush(), null, new Rect(0, 0, Math.Max(6, w * fraction), 6), 3, 3);
        }
    }

    /// <summary>The 30 one-second rates as a line, on a scale from 0 to the fastest second.</summary>
    private sealed class Sparkline(Color color) : FrameworkElement
    {
        private IReadOnlyList<double> values = [];
        public IReadOnlyList<double> Values { get => values; set { values = value; InvalidateVisual(); } }
        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight;
            dc.DrawRoundedRectangle(Forge.Surface.Brush(), null, new Rect(0, 0, w, h), 8, 8);
            if (values.Count == 0) return;
            double top = Math.Max(1, values.Max()) * 1.08;
            Point P(int i) => new(6 + (w - 12) * (i + 0.5) / SpeedMath.Seconds, h - 6 - (h - 12) * values[i] / top);
            var g = new StreamGeometry();
            using (var c = g.Open())
            {
                c.BeginFigure(P(0), false, false);
                for (int i = 1; i < values.Count; i++) c.LineTo(P(i), true, true);
            }
            g.Freeze();
            dc.DrawGeometry(null, color.Pen(2, cap: PenLineCap.Round), g);
            // the burst (seconds 1–5) and the sustained window (11–30)
            double x5 = 6 + (w - 12) * 5 / SpeedMath.Seconds, x10 = 6 + (w - 12) * 10 / SpeedMath.Seconds;
            dc.DrawLine(Forge.Ink2.WithAlpha(0.5).Pen(1, new DashStyle([3, 3], 0)), new Point(x5, 4), new Point(x5, h - 4));
            dc.DrawLine(Forge.Ink2.WithAlpha(0.5).Pen(1, new DashStyle([3, 3], 0)), new Point(x10, 4), new Point(x10, h - 4));
            dc.DrawEllipse(color.Brush(), null, P(values.Count - 1), 3, 3);
        }
    }
}
