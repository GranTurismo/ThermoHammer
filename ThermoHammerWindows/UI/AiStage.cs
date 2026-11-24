using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ThermoHammer.Engine;
using ThermoHammer.Engine.Ai;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// AI STAGE — what each model saw and what it made of it, while it is timed on
// the CPU and the GPU: classes with confidence bars, detection boxes,
// the street's class map, the ×4 upscale beside its pixelated input, the depth
// map beside the photo, and what YAMNet heard in the spectrogram.
// ─────────────────────────────────────────────────────────────────────────────

public sealed class AiStageView : Instrument
{
    private AiVisual? visual;
    private static readonly ConditionalWeakTable<Img, BitmapSource> Bitmaps = new();
    private static readonly Color Accent = BenchColor.Ai;

    public AiStageView() { MinHeight = 320; }

    public void Update(AiVisual? v)
    {
        if (ReferenceEquals(v, visual)) return;
        visual = v;
        InvalidateVisual();
    }

    protected override bool Animating => false;

    private static BitmapSource Bitmap(Img img) => Bitmaps.GetValue(img, i =>
    {
        var b = BitmapSource.Create(i.W, i.H, 96, 96, PixelFormats.Pbgra32, null, i.Pbgra(), i.W * 4);
        b.Freeze();
        return b;
    });

    protected override Size MeasureOverride(Size s) => new(double.IsInfinity(s.Width) ? 900 : s.Width, double.IsInfinity(s.Height) ? 480 : Math.Min(s.Height, 560));

    protected override void OnRender(DrawingContext dc)
    {
        var all = new Rect(0, 0, ActualWidth, ActualHeight);
        dc.DrawRectangle(Brushes.Black, null, all);
        if (visual is not { } v) return;
        switch (v.Test)
        {
            case AiTest.Upscale: SideBySide(dc, v, "INPUT · 128×128", "AI ×4 · 512×512", pixelated: true); break;
            case AiTest.Depth: SideBySide(dc, v, "PHOTO", "DEPTH · NEAR = BRIGHT", pixelated: false); break;
            default: Overlaid(dc, v); break;
        }
    }

    /// <summary>The picture fitted into the stage, with boxes, a class map or labels drawn over it.</summary>
    private void Overlaid(DrawingContext dc, AiVisual v)
    {
        double s = Math.Min(ActualWidth / v.Picture.W, ActualHeight / v.Picture.H);
        var r = new Rect((ActualWidth - v.Picture.W * s) / 2, (ActualHeight - v.Picture.H * s) / 2, v.Picture.W * s, v.Picture.H * s);
        dc.PushClip(new RectangleGeometry(r, 10, 10));
        dc.DrawImage(Bitmap(v.Picture), r);
        if (v.Result != null) dc.DrawImage(Bitmap(v.Result), r);
        if (v.Boxes is { Count: > 0 })
        {
            foreach (var b in v.Boxes)
                dc.DrawRectangle(null, Accent.Pen(2), new Rect(r.X + b.Left * s, r.Y + b.Top * s, Math.Max(1, (b.Right - b.Left) * s), Math.Max(1, (b.Bottom - b.Top) * s)));
            foreach (var b in v.Boxes.Take(12))
            {
                string text = $"{b.Label} {Math.Round(b.Score * 100)}%";
                var ft = Forge.Text(text, 8, Colors.Black, Forge.W.Bold);
                var at = new Point(r.X + b.Left * s, Math.Max(r.Y, r.Y + b.Top * s - 13));
                dc.DrawRectangle(Accent.WithAlpha(0.85).Brush(), null, new Rect(at.X, at.Y, ft.WidthIncludingTrailingWhitespace + 6, ft.Height + 1));
                dc.DrawText(ft, new Point(at.X + 3, at.Y));
            }
        }
        if (v.Labels is { Count: > 0 }) Labels(dc, v.Labels, r);
        dc.Pop();
    }

    private void SideBySide(DrawingContext dc, AiVisual v, string left, string right, bool pixelated)
    {
        double gap = 12, side = Math.Min((ActualWidth - gap) / 2, ActualHeight - 24);
        double x0 = (ActualWidth - 2 * side - gap) / 2, y0 = (ActualHeight - side - 20) / 2;
        Panel(dc, v.Picture, left, new Rect(x0, y0, side, side), pixelated);
        Panel(dc, v.Result, right, new Rect(x0 + side + gap, y0, side, side), false);
    }

    private void Panel(DrawingContext dc, Img? img, string label, Rect r, bool crisp)
    {
        dc.PushClip(new RectangleGeometry(r, 10, 10));
        dc.DrawRectangle(Forge.Surface.Brush(), null, r);
        if (img != null)
        {
            var bmp = Bitmap(img);
            RenderOptions.SetBitmapScalingMode(this, crisp ? BitmapScalingMode.NearestNeighbor : BitmapScalingMode.Linear);
            dc.DrawImage(bmp, r);
        }
        dc.Pop();
        dc.DrawText(label, 9, Accent, new Point(r.X + r.Width / 2, r.Bottom + 6), 0.5, 0, Forge.W.Black, tracking: 1);
    }

    private static void Labels(DrawingContext dc, List<(string name, float p)> labels, Rect area)
    {
        double w = 0;
        var rows = labels.Select(l => (ft: Forge.Text($"{l.name.ToUpperInvariant()} · {Math.Round(l.p * 100)}%", 10, Forge.Ink0, Forge.W.Bold), l.p)).ToList();
        w = rows.Max(r => r.ft.WidthIncludingTrailingWhitespace) + 100 + 28;
        double h = rows.Count * 17 + 12;
        var box = new Rect(area.X + 10, area.Bottom - h - 10, w, h);
        dc.DrawRoundedRectangle(Color.FromArgb(200, 6, 9, 11).Brush(), null, box, 8, 8);
        for (int i = 0; i < rows.Count; i++)
        {
            double y = box.Y + 8 + i * 17;
            dc.DrawRoundedRectangle(Accent.WithAlpha(0.2).Brush(), null, new Rect(box.X + 10, y + 5, 90, 6), 3, 3);
            dc.DrawRoundedRectangle(Accent.Brush(), null, new Rect(box.X + 10, y + 5, 90 * Math.Clamp(rows[i].p, 0.02, 1), 6), 3, 3);
            dc.DrawText(rows[i].ft, new Point(box.X + 108, y));
        }
    }
}
