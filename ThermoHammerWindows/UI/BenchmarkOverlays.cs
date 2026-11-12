using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK OVERLAYS — what the benchmark says around its stage: the labelled
// hold-to-abort button (Android's StageHoldAbort) and the "TEST CANCELLED" card
// (Android's ManualCancelledOverlay).
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>
/// The stage's abort control: the stop ring with its label, in a pill the whole of which can be held. The ring (<see cref="HoldToAbort"/>)
/// still does the holding — a press anywhere on the pill is passed on to it — and the label reads HOLDING… while it fills.
/// </summary>
public sealed class HoldToAbortButton : Border
{
    private readonly HoldToAbort ring;
    private readonly EngravedText label = new() { Label = "HOLD TO ABORT", Color = Ramp.At(0.85), Size = 11, Weight = Forge.W.Black };

    public HoldToAbortButton(Action onAbort)
    {
        ring = new HoldToAbort(onAbort) { ToolTip = null };
        var row = Ui.HStack(10, ring, label);
        row.Stretch = false;
        foreach (FrameworkElement e in row.Children) e.VerticalAlignment = VerticalAlignment.Center;
        Child = row;
        Width = 190;
        Padding = new Thickness(8, 4, 8, 4);
        CornerRadius = new CornerRadius(14);
        Background = Forge.Raised.WithAlpha(0.8).Brush();
        BorderBrush = Ramp.At(0.85).WithAlpha(0.35).Brush();
        BorderThickness = new Thickness(1);
        Cursor = Cursors.Hand;
        // the ring takes the capture when it is pressed; a press on the rest of the pill starts the same hold
        PreviewMouseLeftButtonDown += (_, e) =>
        {
            if (ring.IsMouseOver) return;
            e.Handled = true;
            ring.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice, e.Timestamp, MouseButton.Left) { RoutedEvent = MouseLeftButtonDownEvent, Source = ring });
        };
        ring.GotMouseCapture += (_, _) => label.Label = "HOLDING…";
        ring.LostMouseCapture += (_, _) => label.Label = "HOLD TO ABORT";
    }
}

/// <summary>The benchmark was stopped by hand: nothing was scored and nothing is kept (Android's "TEST CANCELLED").</summary>
public sealed class ManualCancelled : Grid
{
    public ManualCancelled(Action onDismiss)
    {
        Background = Color.FromArgb(184, 0, 0, 0).Brush();
        var amber = Ramp.At(0.5);
        var card = Overlays.Card(Ui.VStack(12,
            new Badge(amber).Align(HorizontalAlignment.Center),
            Ui.Engraved("TEST CANCELLED", Forge.Ink0, 12).Align(HorizontalAlignment.Center),
            Ui.Text("The run was stopped manually. Results are discarded — they would not be valid.", 10, Forge.Ink1, wrap: true, align: TextAlignment.Center),
            Ui.Button("DISMISS", onDismiss, Forge.Interact, Forge.Ink1, 10, radius: 10, padV: 10)), 320);
        card.HorizontalAlignment = HorizontalAlignment.Center;
        card.VerticalAlignment = VerticalAlignment.Center;
        Children.Add(card);
    }

    /// <summary>A ring with a stop square in it.</summary>
    private sealed class Badge(Color color) : FrameworkElement
    {
        protected override Size MeasureOverride(Size s) => new(56, 56);
        protected override void OnRender(DrawingContext dc)
        {
            var c = new Point(28, 28);
            dc.DrawEllipse(color.WithAlpha(0.12).Brush(), color.Pen(2), c, 27, 27);
            dc.DrawRoundedRectangle(color.Brush(), null, new Rect(c.X - 7, c.Y - 7, 14, 14), 3, 3);
        }
    }
}
