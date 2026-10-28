using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Threading;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// FORGE OVERLAYS — preflight checklist · verdict seal · abort · connection
// ─────────────────────────────────────────────────────────────────────────────

public static class Overlays
{
    public static void FadeIn(UIElement e, double scaleFrom = 0.96)
    {
        e.Opacity = 0;
        e.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(200)) { EasingFunction = new QuadraticEase() });
        if (e is FrameworkElement fe)
        {
            var st = new ScaleTransform(scaleFrom, scaleFrom);
            fe.RenderTransformOrigin = new Point(0.5, 0.5);
            fe.RenderTransform = st;
            var anim = new DoubleAnimation(scaleFrom, 1, TimeSpan.FromMilliseconds(260)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
            st.BeginAnimation(ScaleTransform.ScaleXProperty, anim);
            st.BeginAnimation(ScaleTransform.ScaleYProperty, anim);
        }
    }

    /// <summary>The raised glass card of every overlay.</summary>
    public static FrameworkElement Card(UIElement content, double width)
    {
        var g = new Grid { Width = width };
        g.Children.Add(new Border
        {
            Background = Forge.Raised.Brush(), CornerRadius = new CornerRadius(16), Padding = new Thickness(20), Child = content,
            Effect = new DropShadowEffect { Color = Colors.Black, BlurRadius = 48, ShadowDepth = 0, Opacity = 0.6 },
        });
        g.Children.Add(new CornerTicks { Color = Forge.Ink2.WithAlpha(0.8) });
        return g;
    }

    /// <summary>The dark scrim the card sits on (blocks the window under it).</summary>
    public static Grid Scrim(UIElement card)
    {
        var g = new Grid { Background = Color.FromArgb(184, 0, 0, 0).Brush() };
        if (card is FrameworkElement fe) { fe.HorizontalAlignment = HorizontalAlignment.Center; fe.VerticalAlignment = VerticalAlignment.Center; }
        g.Children.Add(card);
        return g;
    }

    public static Grid Message(string title, string text, Action onDismiss) => Scrim(Card(Ui.VStack(12,
        Ui.Text(title, 12, Ramp.At(0.85), Forge.W.Black),
        Ui.Text(text, 10, Forge.Ink1, wrap: true),
        Ui.Button("DISMISS", onDismiss, Forge.Interact, Forge.Ink1, 11)), 380));
}

/// <summary>Pre-flight — the launch check, re-verifying its gates every 1.5 s while open.</summary>
public sealed class PreflightOverlay : Grid, IDisposable
{
    private readonly Flex gates = Ui.VStack(10);
    private readonly Button proceed;
    private readonly EngravedText arming = new() { Label = "ARMING…", Color = Forge.PhasePreFlight, Size = 8 };
    private readonly CancellationTokenSource cancel = new();

    public PreflightOverlay(Action onProceed, Action onCancel)
    {
        for (int i = 0; i < 5; i++) gates.Children.Add(new Border { Height = 26, CornerRadius = new CornerRadius(4), Background = Forge.Ink2.WithAlpha(0.15).Brush() });
        proceed = Ui.Button("PROCEED", onProceed, Forge.Ink2.WithAlpha(0.4), Forge.Bg, 11, Forge.W.Black, 10, 11, enabled: false);
        var head = Ui.HStack(0, Ui.Engraved("LAUNCH CHECK", Forge.PhasePreFlight, 10), Ui.Spacer(), arming);
        var buttons = Ui.HStack(10, Ui.Button("ABORT", onCancel, Forge.Interact, Forge.Ink1, 11, radius: 10, padV: 11).Grow(), proceed.Grow());
        Background = Color.FromArgb(184, 0, 0, 0).Brush();
        var card = Overlays.Card(Ui.VStack(14, head, gates, buttons), 360);
        card.HorizontalAlignment = HorizontalAlignment.Center;
        card.VerticalAlignment = VerticalAlignment.Center;
        Children.Add(card);
        _ = Poll();
    }

    private async Task Poll()
    {
        while (!cancel.IsCancellationRequested)
        {
            var r = await Task.Run(StressEngine.RunPreflight);
            if (cancel.IsCancellationRequested) return;
            Show(r);
            try { await Task.Delay(1500, cancel.Token); } catch (TaskCanceledException) { return; }
        }
    }

    private void Show(PreflightReport r)
    {
        arming.Visibility = Visibility.Collapsed;
        gates.Children.Clear();
        foreach (var g in r.Gates)
        {
            var c = g.Passed ? Ramp.At(0.05) : g.Blocking ? Ramp.At(0.85) : Ramp.At(0.5);
            var text = Ui.VStack(1, Ui.Text(g.Title, 10, g.Passed ? Forge.Ink0 : Ramp.At(0.5), Forge.W.Bold), Ui.Text(g.Detail, 8, Forge.Ink2, wrap: true));
            var row = Ui.HStack(10, new StatusLed(c), text.Grow(), Ui.Text(g.Passed ? "PASS" : g.Blocking ? "BLOCK" : "WARN", 8, c, Forge.W.Bold));
            row.Stretch = false;
            foreach (FrameworkElement e in row.Children) e.VerticalAlignment = VerticalAlignment.Center;
            gates.Children.Add(row);
        }
        gates.Children.Add(Ui.Engraved("◇ live — gates re-verify continuously while open", size: 7).Pad(0, 2, 0, 0));
        proceed.IsEnabled = r.CanRun;
        proceed.Background = (r.CanRun ? Forge.PhaseMeasured : Forge.Ink2.WithAlpha(0.4)).Brush();
    }

    public void Dispose() => cancel.Cancel();
}

/// <summary>Hold to abort: a ring fills over 0.6 s while the button is held; let go early and nothing happens.</summary>
public sealed class HoldToAbort : Instrument
{
    private readonly Action onAbort;
    private DateTime? since;

    public HoldToAbort(Action onAbort)
    {
        this.onAbort = onAbort;
        Width = Height = 46;
        Cursor = Cursors.Hand;
        ToolTip = "Hold to abort";
    }

    protected override bool Animating => since != null;

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e) { since = DateTime.UtcNow; CaptureMouse(); InvalidateVisual(); }
    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e) { since = null; ReleaseMouseCapture(); InvalidateVisual(); }

    protected override void OnRender(DrawingContext dc)
    {
        var c = new Point(ActualWidth / 2, ActualHeight / 2);
        double p = since is { } t ? Math.Min(1, (DateTime.UtcNow - t).TotalSeconds / 0.6) : 0;
        if (p >= 1) { since = null; ReleaseMouseCapture(); onAbort(); p = 0; }
        dc.DrawEllipse(Brushes.Transparent, null, c, 23, 23);
        if (p > 0) dc.DrawGeometry(null, Ramp.At(0.68).Pen(3, cap: PenLineCap.Round), Arc(c, 21, -90, 360 * p));
        bool holding = since != null;
        dc.DrawEllipse((holding ? Ramp.At(0.68).WithAlpha(0.25) : Forge.Interact).Brush(), null, c, 18, 18);
        dc.DrawRoundedRectangle((holding ? Ramp.At(0.68) : Forge.Ink1).Brush(), null, new Rect(c.X - 5, c.Y - 5, 10, 10), 2, 2);
    }
}

/// <summary>Verdict — the scorecard seal after a stress run, and its submission.</summary>
public sealed class VerdictOverlay : Grid
{
    private readonly ContentControl status = new();
    private readonly Func<Task> onSubmit;
    private readonly bool canSubmit;

    public VerdictOverlay(Scorecard sc, bool canSubmit, Func<Task> onSubmit, Action onDismiss)
    {
        this.onSubmit = onSubmit;
        this.canSubmit = canSubmit;
        verified = sc.IsVerified;
        Background = Color.FromArgb(184, 0, 0, 0).Brush();
        var seal = sc.IsVerified ? Ramp.At(0.05) : Ramp.At(0.5);
        var sealView = new Seal(seal, sc.IsVerified).Align(HorizontalAlignment.Center);
        var capacity = Ui.Text($"{sc.DeliveredCapacity.F(1)}%", 44, Ramp.ForCapacity(sc.DeliveredCapacity), Forge.W.Bold, display: true).Align(HorizontalAlignment.Center);
        var rows = Ui.VStack(5,
            Row("BASELINE", $"{(int)(sc.BaselineIps / 1000)}K it/s"),
            Row("SUSTAINED", $"{sc.SustainedRatio.F(1)}%"),
            Row("MIN SUSTAINED", sc.MinSustained is { } m ? $"{m.F(1)}%" : "—"),
            Row("ONSET", sc.ThrottleOnsetSec is { } o ? $"{o / 60}:{o % 60:00}" : "none"),
            Row("IN THROTTLE", $"{sc.TimeInThrottlePct:0}%"),
            Row("CONFIDENCE", $"{sc.Confidence}/100"));
        var flags = Ui.VStack(3);
        foreach (var f in ValidityFlag.Names(sc.ValidityFlags))
        {
            var b = Ui.Box(Ui.Text(f, 8, Ramp.At(0.5), Forge.W.Bold), null, 4, 0, Ramp.At(0.5).WithAlpha(0.5));
            b.Padding = new Thickness(8, 2, 8, 2);
            b.HorizontalAlignment = HorizontalAlignment.Center;
            flags.Children.Add(b);
        }
        ShowSubmit();
        var card = Overlays.Card(Ui.VStack(14, sealView, capacity, Ui.Engraved("DELIVERED CAPACITY", size: 8).Align(HorizontalAlignment.Center),
            rows, flags, status, Ui.Button("DISMISS", onDismiss, Forge.Interact, Forge.Ink1, 10, radius: 10, padV: 10)), 340);
        card.HorizontalAlignment = HorizontalAlignment.Center;
        card.VerticalAlignment = VerticalAlignment.Center;
        Children.Add(card);
    }

    private static FrameworkElement Row(string label, string value) =>
        Ui.HStack(0, Ui.Text(label, 8, Forge.Ink2, Forge.W.Bold), Ui.Spacer(), Ui.Text(value, 10, Forge.Ink0, Forge.W.Bold));

    private readonly bool verified;

    private void ShowSubmit() => status.Content = !verified
        ? Ui.Text("Only verified runs go to the leaderboard. This one is kept on this PC for your own comparisons.", 8, Forge.Ink1,
            wrap: true, align: TextAlignment.Center)
        : canSubmit
        ? Ui.Button("SUBMIT TO LEADERBOARD", () => _ = onSubmit(), Forge.PhaseMeasured, Forge.Bg, 10, Forge.W.Black, 10, 11)
        : Ui.VStack(4, Ui.Text("NO INTERNET CONNECTION", 9, Ramp.At(0.5), Forge.W.Bold, align: TextAlignment.Center),
            Ui.Text("Turn on Wi-Fi or Ethernet to submit this result. Until then it's saved on this PC — submit it from the leaderboard when you're online.",
                8, Forge.Ink1, wrap: true, align: TextAlignment.Center));

    public void SetSubmitting() => status.Content = Ui.Text("SUBMITTING…", 9, Forge.Ink1, Forge.W.Bold, align: TextAlignment.Center);
    public void SetSubmitted(string msg) => status.Content = Ui.Text(msg, 9, Ramp.At(0.05), Forge.W.Bold, align: TextAlignment.Center);
    public void SetError(string err) => status.Content = Ui.VStack(6, Ui.Text(err, 8, Ramp.At(0.85), wrap: true, align: TextAlignment.Center),
        Ui.Button("RETRY", () => _ = onSubmit(), Forge.Interact, Forge.Ink0, 9));

    private sealed class Seal(Color color, bool verified) : FrameworkElement
    {
        protected override Size MeasureOverride(Size s) => new(84, 84);
        protected override void OnRender(DrawingContext dc)
        {
            var c = new Point(42, 42);
            dc.DrawEllipse(null, color.WithAlpha(0.5).Pen(1), c, 41.5, 41.5);
            dc.DrawEllipse(color.WithAlpha(0.12).Brush(), color.Pen(2), c, 35, 35);
            dc.DrawText(verified ? "✓" : "⚑", 20, color, new Point(42, 36), 0.5, 0.5, Forge.W.Bold);
            dc.DrawText(verified ? "VERIFIED" : "FLAGGED", 7, color, new Point(42, 54), 0.5, 0.5, Forge.W.Black);
        }
    }
}

public sealed class AbortOverlay : Grid
{
    public AbortOverlay(double elapsed, Action onDismiss)
    {
        Background = Color.FromArgb(184, 0, 0, 0).Brush();
        var card = Overlays.Card(Ui.VStack(12,
            Ui.Engraved("RUN ABORTED", Ramp.At(0.5), 11).Align(HorizontalAlignment.Center),
            Ui.Text($"{(int)elapsed / 60}:{(int)elapsed % 60:00} recorded", 22, Forge.Ink0, Forge.W.Bold, display: true).Align(HorizontalAlignment.Center),
            Ui.Engraved("partial data is kept but flagged SHORT RUN", size: 8).Align(HorizontalAlignment.Center),
            Ui.Button("DISMISS", onDismiss, Forge.Interact, Forge.Ink1, 10, radius: 10, padV: 10)), 300);
        card.HorizontalAlignment = HorizontalAlignment.Center;
        card.VerticalAlignment = VerticalAlignment.Center;
        Children.Add(card);
    }
}

public sealed class ConnectionOverlay : Grid
{
    public ConnectionOverlay(Action onRetry, Action onDismiss)
    {
        Background = Color.FromArgb(184, 0, 0, 0).Brush();
        var card = Overlays.Card(Ui.VStack(12,
            Ui.Engraved("OFFLINE", Ramp.At(0.5), 11).Align(HorizontalAlignment.Center),
            Ui.Text("result saved locally — submit from the leaderboard when online", 9, Forge.Ink1, wrap: true, align: TextAlignment.Center),
            Ui.HStack(10, Ui.Button("LATER", onDismiss, Forge.Interact, Forge.Ink1, 10, radius: 10, padV: 10).Grow(),
                Ui.Button("RETRY", onRetry, Forge.PhaseMeasured, Forge.Bg, 10, Forge.W.Black, 10, 10).Grow())), 320);
        card.HorizontalAlignment = HorizontalAlignment.Center;
        card.VerticalAlignment = VerticalAlignment.Center;
        Children.Add(card);
    }
}
