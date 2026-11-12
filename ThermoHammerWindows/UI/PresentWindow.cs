using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>
/// PRESENT — a run full screen, for filming and screenshots: the device, a sub-line (OS · date · test settings) and the numbers at
/// presentation size on the forge background, nothing else. A borderless window over the whole monitor the app is on, laid out
/// again whenever its size changes; the ✕ fades after 3 seconds and comes back when the mouse moves or a button is pressed; Esc
/// (or the mouse's back button) closes it. The display stays awake while it's open.
/// </summary>
public sealed class PresentWindow : Window
{
    private readonly Button close;
    private readonly DispatcherTimer idle = new() { Interval = TimeSpan.FromSeconds(3) };
    private Point? lastMouse;
    private bool closeShown = true;

    public static void Open(ShowcaseRun run)
    {
        try { new PresentWindow(run).Show(); }
        catch (Exception e) { Sheets.Message("COULDN'T PRESENT THE RUN", e.Message); }
    }

    private PresentWindow(ShowcaseRun run)
    {
        Title = $"ThermoHammer · {run.Device}";
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        ShowInTaskbar = false;
        Background = Forge.Bg.Brush();
        if (Application.Current.MainWindow is { IsLoaded: true } owner && owner != this)
        {
            Owner = owner;
            // maximized on the monitor the app is on: start inside the app's window
            WindowStartupLocation = WindowStartupLocation.Manual;
            var at = owner.WindowState == WindowState.Maximized ? owner.RestoreBounds : new Rect(owner.Left, owner.Top, owner.ActualWidth, owner.ActualHeight);
            Left = at.Left + 40; Top = at.Top + 40; Width = 640; Height = 400;
        }
        WindowState = WindowState.Maximized;

        close = Ui.Button(Ui.Text("✕", 16, Forge.Ink1, Forge.W.Bold), Close, Forge.Raised.WithAlpha(0.8), 18, 12, 6, stroke: Forge.Hairline);
        close.Align(HorizontalAlignment.Right, VerticalAlignment.Top).Pad(0, 18, 18, 0);
        close.ToolTip = "Close (Esc)";
        var root = new Grid();
        root.Children.Add(new PresentView(run));
        root.Children.Add(close);
        Content = root;

        idle.Tick += (_, _) => HideClose();
        PreviewKeyDown += (_, e) => { if (e.Key is Key.Escape or Key.BrowserBack) { e.Handled = true; Close(); } else ShowClose(); };
        PreviewMouseDown += (_, e) => { if (e.ChangedButton == MouseButton.XButton1) { e.Handled = true; Close(); } else ShowClose(); };
        PreviewMouseMove += (_, e) =>
        {
            var p = e.GetPosition(this);
            if (lastMouse is { } q && Math.Abs(p.X - q.X) + Math.Abs(p.Y - q.Y) < 4) return;   // a resting mouse doesn't count
            lastMouse = p;
            ShowClose();
        };
        Loaded += (_, _) =>
        {
            Win32.SetThreadExecutionState(Win32.EsContinuous | Win32.EsDisplayRequired);
            idle.Start();
            Activate();
        };
        Closed += (_, _) =>
        {
            idle.Stop();
            // back to what a running test asked for, or to nothing
            Win32.SetThreadExecutionState(StressEngine.Shared.IsRunning || BenchmarkEngine.Shared.IsRunning
                ? Win32.EsContinuous | Win32.EsSystemRequired | Win32.EsDisplayRequired : Win32.EsContinuous);
            Owner?.Activate();
        };
    }

    private void ShowClose()
    {
        idle.Stop();
        idle.Start();
        if (closeShown) return;
        closeShown = true;
        close.IsHitTestVisible = true;
        Cursor = null;
        close.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(180)));
    }

    private void HideClose()
    {
        idle.Stop();
        if (!closeShown) return;
        closeShown = false;
        close.IsHitTestVisible = false;
        Cursor = Cursors.None;   // nothing but the run on screen while it's filmed
        close.BeginAnimation(OpacityProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(600)));
    }

    /// <summary>The run at presentation size, drawn for whatever size the window is (landscape: numbers left, data right; portrait: stacked).</summary>
    private sealed class PresentView(ShowcaseRun run) : FrameworkElement
    {
        protected override void OnRenderSizeChanged(SizeChangedInfo info)
        {
            base.OnRenderSizeChanged(info);
            InvalidateVisual();
        }

        private static FormattedText T(string s, double size, Color c, Forge.W w = Forge.W.Regular, bool display = false, double maxWidth = 0, int maxLines = 0) =>
            RunArt.Text(s, size, c, w, display, Forge.PixelsPerDip, maxWidth, maxLines);

        /// <summary>[s] at [size], or smaller so it fits [width].</summary>
        private static FormattedText Fit(string s, double size, Color c, double width)
        {
            var t = T(s, size, c, Forge.W.Black);
            return t.WidthIncludingTrailingWhitespace <= width ? t : T(s, Math.Max(8, size * width / t.WidthIncludingTrailingWhitespace), c, Forge.W.Black);
        }

        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight;
            if (w < 50 || h < 50) return;
            double unit = Math.Min(w, h), pad = Math.Max(28, unit * 0.06);
            dc.DrawRectangle(Forge.Bg.Brush(), null, new Rect(0, 0, w, h));
            var glow = new RadialGradientBrush(Forge.PhaseMeasured.WithAlpha(0.07), Forge.PhaseMeasured.WithAlpha(0))
            {
                Center = new Point(0.25, 0.45), GradientOrigin = new Point(0.25, 0.45), RadiusX = 0.7, RadiusY = 0.7,
            };
            dc.DrawRectangle(glow, null, new Rect(0, 0, w, h));

            // the device and the sub-line (room on the right for the ✕)
            double nameSize = Math.Clamp(unit * 0.068, 26, 120);
            var name = T(run.Device, nameSize, Forge.Ink0, Forge.W.Bold, display: true, maxWidth: w - 2 * pad - 60, maxLines: 2);
            dc.DrawText(name, new Point(pad, pad * 0.8));
            double y = pad * 0.8 + name.Height + nameSize * 0.12;
            string settings = run.Stress ? $"{run.Minutes}-MIN {run.Load} LOAD" + (run.Threading is { } th ? $" · {th}" : "") : run.Chip ?? "";
            string subLine = string.Join(" · ", new[] { run.Os, run.DateText, settings }.Where(s => !string.IsNullOrWhiteSpace(s)));
            var sub = T(subLine, Math.Max(12, nameSize * 0.4), Forge.Ink2, Forge.W.Medium, maxWidth: w - 2 * pad, maxLines: 2);
            dc.DrawText(sub, new Point(pad, y));
            y += sub.Height;

            // the corner mark
            double markSize = Math.Max(10, unit * 0.02);
            RunArt.Tracked(dc, "THERMOHAMMER", markSize, Forge.PhaseMeasured.WithAlpha(0.8), Forge.W.Black, w - pad, h - pad * 0.6, markSize * 0.25,
                Forge.PixelsPerDip, 1);

            var area = new Rect(pad, y + pad * 0.7, w - 2 * pad, Math.Max(40, h - pad * 1.3 - markSize * 2 - (y + pad * 0.7)));
            if (run.Stress) Stress(dc, area, unit);
            else Benchmark(dc, area, unit);
        }

        private void Benchmark(DrawingContext dc, Rect area, double unit)
        {
            bool wide = area.Width >= area.Height * 1.25;
            var numberArea = wide ? new Rect(area.X, area.Y, area.Width * 0.42, area.Height) : new Rect(area.X, area.Y, area.Width, area.Height * 0.36);
            var barsArea = wide ? new Rect(area.X + area.Width * 0.48, area.Y, area.Width * 0.52, area.Height)
                : new Rect(area.X, area.Y + area.Height * 0.4, area.Width, area.Height * 0.6);
            double size = Math.Min(numberArea.Height * 0.55, unit * 0.3);
            var total = Fit(ShareLinks.Points(run.Total), size, Forge.Ink0, numberArea.Width);
            double labelSize = Math.Max(11, unit * 0.026);
            // the number and its label as a block, centred in its column
            double blockH = total.Extent + labelSize * 2.4;
            double baseline = numberArea.Y + (numberArea.Height - blockH) / 2 + total.Extent;
            RunArt.Draw(dc, total, numberArea.X, baseline);
            RunArt.Tracked(dc, "BENCHMARK SCORE", labelSize, Forge.Ink2, Forge.W.Bold, numberArea.X + 4, baseline + labelSize * 2.2, labelSize * 0.2, Forge.PixelsPerDip);
            int n = Math.Max(1, run.Sections.Count);
            double rowH = Math.Min(barsArea.Height / n, unit * 0.14);
            var rows = new Rect(barsArea.X, barsArea.Y + (barsArea.Height - rowH * n) / 2, barsArea.Width, rowH * n);
            RunArt.Bars(dc, rows, run.Sections, Math.Clamp(rowH * 0.36, 12, 64), Forge.PixelsPerDip);
        }

        private void Stress(DrawingContext dc, Rect area, double unit)
        {
            bool wide = area.Width >= area.Height * 1.25;
            var numberArea = wide ? new Rect(area.X, area.Y, area.Width * 0.3, area.Height) : new Rect(area.X, area.Y, area.Width, area.Height * 0.3);
            var curveArea = wide ? new Rect(area.X + area.Width * 0.33, area.Y, area.Width * 0.67, area.Height)
                : new Rect(area.X, area.Y + area.Height * 0.33, area.Width, area.Height * 0.67);
            double size = Math.Min(numberArea.Height * (wide ? 0.3 : 0.5), unit * 0.26);
            var capacity = Fit($"{ShareLinks.Percent(run.Capacity)}%", size, Ramp.ForCapacity(run.Capacity), numberArea.Width);
            double labelSize = Math.Max(11, unit * 0.024);
            var label = T(run.StressLabel, labelSize, Forge.Ink2, Forge.W.Bold, maxWidth: numberArea.Width, maxLines: 3);
            var onset = T(StressCurve.OnsetLine(run.OnsetSec), labelSize * 1.2, run.OnsetSec != null ? Ramp.At(0.62) : Forge.PhaseMeasured, Forge.W.Bold,
                maxWidth: numberArea.Width, maxLines: 2);
            double blockH = capacity.Extent + labelSize + label.Height + labelSize * 1.2 + onset.Height;
            double baseline = numberArea.Y + Math.Max(0, (numberArea.Height - blockH) / 2) + capacity.Extent;
            RunArt.Draw(dc, capacity, numberArea.X, baseline);
            double y = baseline + labelSize;
            dc.DrawText(label, new Point(numberArea.X + 4, y));
            y += label.Height + labelSize * 1.2;
            dc.DrawText(onset, new Point(numberArea.X + 4, y));
            RunArt.Curve(dc, curveArea, run.Curve, run.CurveSeconds, run.OnsetSec, Math.Clamp(unit / 900, 0.7, 2.2), Forge.PixelsPerDip);
        }
    }
}
