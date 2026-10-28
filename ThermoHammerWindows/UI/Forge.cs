using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// THERMOHAMMER FORGE — design tokens (Windows port of the phones' Forge system)
// "The forge is dark. The metal glows. The instrument tells the truth beautifully."
// ─────────────────────────────────────────────────────────────────────────────

public static class Forge
{
    public static Color Hex(uint rgb, double alpha = 1) =>
        Color.FromArgb((byte)Math.Round(alpha * 255), (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);

    public static readonly Color Bg = Hex(0x06090B);        // deepest layer — inside of a cold forge
    public static readonly Color Surface = Hex(0x0B1117);   // card surface
    public static readonly Color Raised = Hex(0x111A21);    // raised panels / overlay cards
    public static readonly Color Interact = Hex(0x1A2630);  // interactive elements
    public static readonly Color Hairline = Color.FromArgb(20, 255, 255, 255);
    public static readonly Color Ink0 = Hex(0xEAF4F4);      // primary data text
    public static readonly Color Ink1 = Hex(0x9FB4BC);      // secondary text
    public static readonly Color Ink2 = Hex(0x5A6B73);      // engraved labels / annotations

    public static readonly Color PhasePreFlight = Hex(0x4A9EFF);   // signal blue
    public static readonly Color PhaseCooldown = Hex(0x5BC8FF);    // ice blue — shedding heat
    public static readonly Color PhaseWarmup = Hex(0x5A6B73);      // dormant steel
    public static readonly Color PhaseCalibration = Hex(0x9D7BFF); // arc violet
    public static readonly Color PhaseMeasured = Hex(0x3DE8C2);    // forge teal

    public static Color WithAlpha(this Color c, double a) => Color.FromArgb((byte)Math.Round(Math.Clamp(a, 0, 1) * c.A), c.R, c.G, c.B);

    private static readonly Dictionary<Color, SolidColorBrush> brushes = [];
    /// <summary>A frozen brush of the colour (cached).</summary>
    public static SolidColorBrush Brush(this Color c)
    {
        if (brushes.TryGetValue(c, out var b)) return b;
        b = new SolidColorBrush(c);
        b.Freeze();
        if (brushes.Count < 4096) brushes[c] = b;
        return b;
    }

    public static Pen Pen(this Color c, double width, DashStyle? dash = null, PenLineCap cap = PenLineCap.Flat)
    {
        var p = new Pen(c.Brush(), width) { StartLineCap = cap, EndLineCap = cap, DashCap = cap };
        if (dash != null) p.DashStyle = dash;
        p.Freeze();
        return p;
    }

    public static Color PhaseColor(RunPhase p) => p switch
    {
        RunPhase.Cooldown => PhaseCooldown, RunPhase.Warmup => PhaseWarmup, RunPhase.Calibration => PhaseCalibration,
        RunPhase.Measured => PhaseMeasured, _ => Ink2,
    };

    public static Color ThermalColor(ThermalLevel t) => t switch
    {
        ThermalLevel.Fair => Ramp.At(0.30), ThermalLevel.Serious => Ramp.At(0.62), ThermalLevel.Critical => Ramp.At(0.85), _ => Ramp.At(0.05),
    };

    public static Color AttributionColor(Attribution a) => a switch
    {
        Attribution.Thermal => Ramp.At(0.62), Attribution.Contention => PhasePreFlight, Attribution.Unknown => Ink2, _ => Ramp.At(0.05),
    };

    public static string AttributionLabel(Attribution a) => a switch
    {
        Attribution.Thermal => "THERMAL / POWER LIMIT", Attribution.Contention => "EXTERNAL LOAD", Attribution.Unknown => "TELEMETRY LIMITED",
        _ => "CLEAN RUN",
    };

    // ── typography — the phones' bundled JetBrains Mono + Space Grotesk ──

    private static readonly Uri FontBase = new("pack://application:,,,/");
    // The fonts are instances of variable fonts: their weights are 400 / 436 / 558 / 800 (mono) and 500 / 700 (display),
    // and Medium and ExtraBold carry their own legacy family name — both ways of naming them are listed.
    private static readonly FontFamily MonoFamily = new(FontBase, "./Fonts/#JetBrains Mono");
    private static readonly FontFamily MonoMediumFamily = new(FontBase, "./Fonts/#JetBrains Mono Medium, ./Fonts/#JetBrains Mono");
    private static readonly FontFamily MonoBlackFamily = new(FontBase, "./Fonts/#JetBrains Mono ExtraBold, ./Fonts/#JetBrains Mono");
    private static readonly FontFamily DisplayMediumFamily = new(FontBase, "./Fonts/#Space Grotesk Light Medium, ./Fonts/#Space Grotesk Light");
    private static readonly FontFamily DisplayBoldFamily = new(FontBase, "./Fonts/#Space Grotesk Light");

    public enum W { Regular, Medium, Bold, Black }

    public static (FontFamily, FontWeight) Mono(W w = W.Regular) => w switch
    {
        W.Medium => (MonoMediumFamily, FontWeight.FromOpenTypeWeight(436)),
        W.Bold => (MonoFamily, FontWeight.FromOpenTypeWeight(558)),
        W.Black => (MonoBlackFamily, FontWeight.FromOpenTypeWeight(800)),
        _ => (MonoFamily, FontWeights.Normal),
    };

    public static (FontFamily, FontWeight) Display(bool bold = false) =>
        bold ? (DisplayBoldFamily, FontWeights.Bold) : (DisplayMediumFamily, FontWeight.FromOpenTypeWeight(500));

    /// <summary>The app's design unit (the phones' points) in WPF device-independent pixels.</summary>
    public const double Scale = 1.0;

    // ── text for OnRender ──

    /// <summary>The window's pixels per DIP (set by the main window as it appears and when it changes monitors).</summary>
    public static double PixelsPerDip { get; set; } = 1;

    public static FormattedText Text(string s, double size, Color color, W w = W.Regular, bool display = false)
    {
        var (family, weight) = display ? Display(w >= W.Bold) : Mono(w);
        return new FormattedText(s, CultureInfo.InvariantCulture, FlowDirection.LeftToRight,
            new Typeface(family, FontStyles.Normal, weight, FontStretches.Normal), size, color.Brush(), PixelsPerDip);
    }

    /// <summary>Draws text with its anchor at (x, y): ax/ay 0 = left/top, 0.5 = centre, 1 = right/bottom.</summary>
    public static void DrawText(this DrawingContext dc, string s, double size, Color color, Point at, double ax = 0, double ay = 0,
        W w = W.Regular, bool display = false, double tracking = 0)
    {
        var t = Text(s, size, color, w, display);
        if (tracking != 0)
        {
            // letter spacing: draw glyph by glyph (labels are short)
            double total = 0;
            var parts = s.Select(ch => Text(ch.ToString(), size, color, w, display)).ToList();
            foreach (var p in parts) total += p.WidthIncludingTrailingWhitespace + tracking;
            total -= tracking;
            double x = at.X - total * ax, y = at.Y - t.Height * ay;
            foreach (var p in parts) { dc.DrawText(p, new Point(x, y)); x += p.WidthIncludingTrailingWhitespace + tracking; }
            return;
        }
        dc.DrawText(t, new Point(at.X - t.WidthIncludingTrailingWhitespace * ax, at.Y - t.Height * ay));
    }

    // ── number formatting (the phones' "1,234") ──

    public static string N0(this int v) => v.ToString("N0", CultureInfo.InvariantCulture);
    public static string N0(this double v) => v.ToString("N0", CultureInfo.InvariantCulture);
    public static string F(this double v, int d = 1) => v.ToString("F" + d, CultureInfo.InvariantCulture);
}

/// <summary>The Thermal Ramp — continuous position 0..1. t=0 cool teal → t=1 molten.</summary>
public static class Ramp
{
    private static readonly (double p, Color c)[] stops =
    [
        (0.00, Forge.Hex(0x3DE8C2)), (0.25, Forge.Hex(0x9EE86A)), (0.50, Forge.Hex(0xFFB545)),
        (0.68, Forge.Hex(0xFF6A3D)), (0.85, Forge.Hex(0xFF3D5E)), (1.00, Forge.Hex(0xFFE9D6)),
    ];

    public static Color At(double t)
    {
        double c = Math.Clamp(t, 0, 1);
        for (int i = 0; i < stops.Length - 1; i++)
        {
            var (p0, c0) = stops[i];
            var (p1, c1) = stops[i + 1];
            if (c <= p1)
            {
                double f = p1 > p0 ? (c - p0) / (p1 - p0) : 0;
                return Lerp(c0, c1, f);
            }
        }
        return stops[^1].c;
    }

    /// <summary>Hue 0..1, saturation and brightness 0..1 → colour.</summary>
    public static Color FromHsv(double h, double s, double v)
    {
        double hh = h % 1 * 6, f = hh - Math.Floor(hh), p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        var (r, g, b) = (int)hh switch { 0 => (v, t, p), 1 => (q, v, p), 2 => (p, v, t), 3 => (p, q, v), 4 => (t, p, v), _ => (v, p, q) };
        return Color.FromRgb((byte)(r * 255), (byte)(g * 255), (byte)(b * 255));
    }

    /// <summary>Delivered-capacity % (100 perfect) → ramp position.</summary>
    public static Color ForCapacity(double pct) => At(Math.Clamp((100 - pct) / 55, 0, 1));

    public static Color Lerp(Color a, Color b, double f) => Color.FromArgb(
        (byte)(a.A + (b.A - a.A) * f), (byte)(a.R + (b.R - a.R) * f), (byte)(a.G + (b.G - a.G) * f), (byte)(a.B + (b.B - a.B) * f));
}

/// <summary>
/// A row or a column with spacing, where children marked <see cref="GrowProperty"/> share the room left over — the
/// SwiftUI stacks and Spacer() the phone screens are written with.
/// </summary>
public sealed class Flex : Panel
{
    public static readonly DependencyProperty GrowProperty = DependencyProperty.RegisterAttached("Grow", typeof(double), typeof(Flex),
        new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsParentMeasure));
    public static double GetGrow(UIElement e) => (double)e.GetValue(GrowProperty);
    public static void SetGrow(UIElement e, double v) => e.SetValue(GrowProperty, v);

    public bool Horizontal { get; set; }
    public double Spacing { get; set; }
    /// <summary>Cross-axis: children stretch (true) or keep their size and follow their own alignment.</summary>
    public bool Stretch { get; set; } = true;

    private double Main(Size s) => Horizontal ? s.Width : s.Height;
    private double Cross(Size s) => Horizontal ? s.Height : s.Width;
    private Size Make(double main, double cross) => Horizontal ? new Size(main, cross) : new Size(cross, main);

    protected override Size MeasureOverride(Size available)
    {
        double avMain = Main(available), avCross = Cross(available);
        var kids = InternalChildren.Cast<UIElement>().Where(c => c.Visibility != Visibility.Collapsed).ToList();
        double used = Math.Max(0, kids.Count - 1) * Spacing, cross = 0, grow = 0;
        foreach (var c in kids)
        {
            double g = GetGrow(c);
            if (g > 0) { grow += g; continue; }
            c.Measure(Make(double.PositiveInfinity, avCross));
            used += Main(c.DesiredSize);
            cross = Math.Max(cross, Cross(c.DesiredSize));
        }
        double left = double.IsInfinity(avMain) ? 0 : Math.Max(0, avMain - used);
        foreach (var c in kids)
        {
            double g = GetGrow(c);
            if (g <= 0) continue;
            c.Measure(Make(double.IsInfinity(avMain) ? double.PositiveInfinity : left * g / grow, avCross));
            if (double.IsInfinity(avMain)) used += Main(c.DesiredSize);
            cross = Math.Max(cross, Cross(c.DesiredSize));
        }
        double main = grow > 0 && !double.IsInfinity(avMain) ? avMain : used;
        return Make(main, cross);
    }

    protected override Size ArrangeOverride(Size final)
    {
        var kids = InternalChildren.Cast<UIElement>().Where(c => c.Visibility != Visibility.Collapsed).ToList();
        double fixedMain = Math.Max(0, kids.Count - 1) * Spacing, grow = 0;
        foreach (var c in kids)
        {
            double g = GetGrow(c);
            if (g > 0) grow += g; else fixedMain += Main(c.DesiredSize);
        }
        double left = Math.Max(0, Main(final) - fixedMain), at = 0, cross = Cross(final);
        foreach (var c in kids)
        {
            double g = GetGrow(c);
            double m = g > 0 ? left * g / grow : Main(c.DesiredSize);
            double cs = Stretch ? cross : Math.Min(cross, Cross(c.DesiredSize)), co = 0;
            if (!Stretch && c is FrameworkElement fe)
            {
                var align = Horizontal ? (fe.VerticalAlignment == VerticalAlignment.Center ? 0.5 : fe.VerticalAlignment == VerticalAlignment.Bottom ? 1 : 0)
                                       : (fe.HorizontalAlignment == HorizontalAlignment.Center ? 0.5 : fe.HorizontalAlignment == HorizontalAlignment.Right ? 1 : 0);
                co = (cross - cs) * align;
            }
            c.Arrange(Horizontal ? new Rect(at, co, m, cs) : new Rect(co, at, cs, m));
            at += m + Spacing;
        }
        return final;
    }
}

/// <summary>Small builders, so screens read like the phones' SwiftUI.</summary>
public static class Ui
{
    public static TextBlock Text(string s, double size, Color color, Forge.W w = Forge.W.Regular, bool display = false,
        bool wrap = false, TextAlignment align = TextAlignment.Left)
    {
        var (family, weight) = display ? Forge.Display(w >= Forge.W.Bold) : Forge.Mono(w);
        var t = new TextBlock
        {
            Text = s, FontFamily = family, FontWeight = weight, FontSize = size, Foreground = color.Brush(),
            TextWrapping = wrap ? TextWrapping.Wrap : TextWrapping.NoWrap, TextAlignment = align,
            TextTrimming = wrap ? TextTrimming.None : TextTrimming.CharacterEllipsis,
        };
        return t;
    }

    /// <summary>Engraved instrument label — uppercase spaced mono.</summary>
    public static FrameworkElement Engraved(string s, Color? color = null, double size = 9) =>
        new EngravedText { Label = s.ToUpperInvariant(), Color = color ?? Forge.Ink2, Size = size };

    public static Flex VStack(double spacing, params UIElement?[] children) => Stack(false, spacing, children);
    public static Flex HStack(double spacing, params UIElement?[] children) => Stack(true, spacing, children);

    private static Flex Stack(bool horizontal, double spacing, UIElement?[] children)
    {
        var f = new Flex { Horizontal = horizontal, Spacing = spacing };
        foreach (var c in children) if (c != null) f.Children.Add(c);
        return f;
    }

    public static T Grow<T>(this T e, double weight = 1) where T : UIElement { Flex.SetGrow(e, weight); return e; }

    public static FrameworkElement Spacer() => new FrameworkElement().Grow();

    public static T Pad<T>(this T e, double all) where T : FrameworkElement { e.Margin = new Thickness(all); return e; }
    public static T Pad<T>(this T e, double h, double v) where T : FrameworkElement { e.Margin = new Thickness(h, v, h, v); return e; }
    public static T Pad<T>(this T e, double l, double t, double r, double b) where T : FrameworkElement { e.Margin = new Thickness(l, t, r, b); return e; }

    public static T Align<T>(this T e, HorizontalAlignment h, VerticalAlignment v = VerticalAlignment.Stretch) where T : FrameworkElement
    {
        e.HorizontalAlignment = h; e.VerticalAlignment = v; return e;
    }

    public static T Size<T>(this T e, double w = double.NaN, double h = double.NaN) where T : FrameworkElement
    {
        if (!double.IsNaN(w)) e.Width = w;
        if (!double.IsNaN(h)) e.Height = h;
        return e;
    }

    public static Border Box(UIElement child, Color? bg = null, double radius = 12, double pad = 0, Color? stroke = null, double strokeWidth = 1)
    {
        return new Border
        {
            Child = child, Background = (bg ?? Colors.Transparent).Brush(), CornerRadius = new CornerRadius(radius),
            Padding = new Thickness(pad), BorderBrush = stroke?.Brush(), BorderThickness = new Thickness(stroke != null ? strokeWidth : 0),
        };
    }

    /// <summary>A surface card with the instrument-bezel corner ticks.</summary>
    public static FrameworkElement Card(UIElement child, double pad = 12, Color? bg = null, double radius = 12)
    {
        var g = new Grid();
        g.Children.Add(Box(child, bg ?? Forge.Surface, radius, pad));
        g.Children.Add(new CornerTicks());
        return g;
    }

    /// <summary>A flat button: label on a fill, a hand cursor, and a lighter fill under the pointer.</summary>
    public static Button Button(UIElement content, Action onClick, Color bg, double radius = 8, double padH = 12, double padV = 9,
        bool enabled = true, Color? stroke = null)
    {
        var b = new Button
        {
            Content = content, Background = bg.Brush(), Cursor = Cursors.Hand, IsEnabled = enabled, Focusable = false,
            Padding = new Thickness(padH, padV, padH, padV), Template = FlatTemplate(radius, stroke),
            HorizontalContentAlignment = HorizontalAlignment.Center, VerticalContentAlignment = VerticalAlignment.Center,
        };
        b.Click += (_, _) => onClick();
        return b;
    }

    public static Button Button(string label, Action onClick, Color bg, Color fg, double size = 10, Forge.W w = Forge.W.Bold,
        double radius = 8, double padV = 9, bool enabled = true, Color? stroke = null) =>
        Button(Text(label, size, fg, w, align: TextAlignment.Center).Align(HorizontalAlignment.Center), onClick, bg, radius, 12, padV, enabled, stroke);

    private static readonly Dictionary<(double, Color?), ControlTemplate> templates = [];

    private static ControlTemplate FlatTemplate(double radius, Color? stroke)
    {
        if (templates.TryGetValue((radius, stroke), out var t)) return t;
        var border = new FrameworkElementFactory(typeof(Border), "bd");
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(radius));
        border.SetBinding(Border.BackgroundProperty, new System.Windows.Data.Binding("Background") { RelativeSource = System.Windows.Data.RelativeSource.TemplatedParent });
        border.SetBinding(Border.PaddingProperty, new System.Windows.Data.Binding("Padding") { RelativeSource = System.Windows.Data.RelativeSource.TemplatedParent });
        if (stroke is { } s)
        {
            border.SetValue(Border.BorderBrushProperty, s.Brush());
            border.SetValue(Border.BorderThicknessProperty, new Thickness(1));
        }
        var content = new FrameworkElementFactory(typeof(ContentPresenter));
        content.SetBinding(HorizontalAlignmentProperty(), new System.Windows.Data.Binding("HorizontalContentAlignment") { RelativeSource = System.Windows.Data.RelativeSource.TemplatedParent });
        content.SetBinding(FrameworkElement.VerticalAlignmentProperty, new System.Windows.Data.Binding("VerticalContentAlignment") { RelativeSource = System.Windows.Data.RelativeSource.TemplatedParent });
        border.AppendChild(content);
        t = new ControlTemplate(typeof(Button)) { VisualTree = border };
        var hover = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
        hover.Setters.Add(new Setter(UIElement.OpacityProperty, 0.85));
        var pressed = new Trigger { Property = System.Windows.Controls.Primitives.ButtonBase.IsPressedProperty, Value = true };
        pressed.Setters.Add(new Setter(UIElement.OpacityProperty, 0.7));
        var disabled = new Trigger { Property = UIElement.IsEnabledProperty, Value = false };
        disabled.Setters.Add(new Setter(UIElement.OpacityProperty, 0.45));
        t.Triggers.Add(hover);
        t.Triggers.Add(pressed);
        t.Triggers.Add(disabled);
        t.Seal();
        templates[(radius, stroke)] = t;
        return t;
    }

    private static DependencyProperty HorizontalAlignmentProperty() => FrameworkElement.HorizontalAlignmentProperty;

    public static ScrollViewer Scroll(UIElement content, bool horizontal = false) => new()
    {
        Content = content,
        VerticalScrollBarVisibility = horizontal ? ScrollBarVisibility.Disabled : ScrollBarVisibility.Auto,
        HorizontalScrollBarVisibility = horizontal ? ScrollBarVisibility.Auto : ScrollBarVisibility.Disabled,
        Focusable = false,
        PanningMode = horizontal ? PanningMode.HorizontalOnly : PanningMode.VerticalOnly,
    };

    public static Rectangle Line(Color c, double thickness = 1, bool vertical = false)
    {
        var r = new Rectangle { Fill = c.Brush() };
        if (vertical) r.Width = thickness; else r.Height = thickness;
        return r;
    }
}

/// <summary>Instrument-bezel L marks at the four corners, instead of a full border.</summary>
public sealed class CornerTicks : FrameworkElement
{
    public Color Color { get; set; } = Forge.Ink2.WithAlpha(0.6);
    public double TickLen { get; set; } = 10;

    public CornerTicks() { IsHitTestVisible = false; }

    protected override void OnRender(DrawingContext dc)
    {
        double l = TickLen, w = ActualWidth, h = ActualHeight;
        var pen = Color.Pen(1);
        void L(double x0, double y0, double x1, double y1) => dc.DrawLine(pen, new Point(x0, y0), new Point(x1, y1));
        L(0.5, 0.5, l, 0.5); L(0.5, 0.5, 0.5, l);
        L(w - 0.5, 0.5, w - l, 0.5); L(w - 0.5, 0.5, w - 0.5, l);
        L(0.5, h - 0.5, l, h - 0.5); L(0.5, h - 0.5, 0.5, h - l);
        L(w - 0.5, h - 0.5, w - l, h - 0.5); L(w - 0.5, h - 0.5, w - 0.5, h - l);
    }
}

/// <summary>Uppercase spaced mono — the instrument's engraved labels (letter spacing, which TextBlock can't do).</summary>
public sealed class EngravedText : FrameworkElement
{
    private string label = "";
    public string Label { get => label; set { label = value; InvalidateMeasure(); InvalidateVisual(); } }
    private Color color = Forge.Ink2;
    public Color Color { get => color; set { color = value; InvalidateVisual(); } }
    public double Size { get; set; } = 9;
    public Forge.W Weight { get; set; } = Forge.W.Bold;
    private double Tracking => Size * 0.12;

    private double TextWidth() =>
        Label.Sum(c => Forge.Text(c.ToString(), Size, Color, Weight).WidthIncludingTrailingWhitespace + Tracking) - (Label.Length > 0 ? Tracking : 0);

    protected override Size MeasureOverride(Size availableSize) =>
        new(Math.Min(TextWidth(), double.IsInfinity(availableSize.Width) ? double.MaxValue : availableSize.Width), Forge.Text("X", Size, Color, Weight).Height);

    protected override void OnRender(DrawingContext dc) =>
        dc.DrawText(Label, Size, Color, new Point(0, 0), w: Weight, tracking: Tracking);
}

/// <summary>Status LED — hollow (pending) vs solid (result).</summary>
public sealed class StatusLed : FrameworkElement
{
    public Color Color { get; set; }
    public bool Active { get; set; } = true;
    public StatusLed(Color c, double size = 8) { Color = c; Width = Height = size; }
    protected override void OnRender(DrawingContext dc)
    {
        double r = Math.Min(ActualWidth, ActualHeight) / 2;
        var c = new Point(ActualWidth / 2, ActualHeight / 2);
        dc.DrawEllipse(Active ? Color.Brush() : null, Color.WithAlpha(0.5).Pen(1.5), c, r - 0.75, r - 0.75);
    }
}
