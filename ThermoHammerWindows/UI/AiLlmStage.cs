using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ThermoHammer.Engine;
using ThermoHammer.Engine.Ai;
using ThermoHammer.Engine.Llm;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// AI STAGE — what each model saw and what it made of it, while it is timed on
// the CPU, the GPU and the NPU: classes with confidence bars, detection boxes,
// the street's class map, the ×4 upscale beside its pixelated input, the depth
// map beside the photo, and what YAMNet heard in the spectrogram.
// LLM STAGE — the context the model works on: the 512-token prompt lighting up
// as each pass reads it in one batch, then the reply written token by token.
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

/// <summary>The LLM test: the prompt's 512 tokens, the reply's 32 as chips of their ids.</summary>
public sealed class LlmStageView : Instrument
{
    private const int PromptCols = 32, PromptRows = 16, ReplyCols = 8;
    private static readonly Color Accent = BenchColor.Llm;
    private LlmAccel accel = LlmAccel.Cpu;
    private LlmPhase phase;
    private int prompts;
    private int[] reply = [];
    private double sweepStart = double.NegativeInfinity;
    protected override bool Animating => true;

    public void Update(BenchmarkEngine engine)
    {
        accel = engine.LlmAccel ?? LlmAccel.Cpu;
        phase = engine.LlmPhase;
        if (engine.LlmPrompts != prompts) sweepStart = Time;
        prompts = engine.LlmPrompts;
        reply = engine.LlmReply;
    }

    protected override Size MeasureOverride(Size s) => new(Math.Min(double.IsInfinity(s.Width) ? 760 : s.Width, 760), double.IsInfinity(s.Height) ? 520 : Math.Min(s.Height, 600));

    private static Color TokenColor(int id)
    {
        double h = id * 0.618034 % 1;
        return Ramp.FromHsv(h, 0.5, 0.97);
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, y = 0;
        // the accelerators as chips
        double x = 0;
        foreach (var a in LlmAccels.All)
        {
            bool now = a == accel, done = a < accel;
            var ft = Forge.Text((done ? "✓ " : "") + a.Label(), 9, now ? Accent : Forge.Ink2, Forge.W.Bold);
            var r = new Rect(x, y, ft.WidthIncludingTrailingWhitespace + 20, 24);
            dc.DrawRoundedRectangle(now ? Accent.WithAlpha(0.18).Brush() : Brushes.Transparent, (now ? Accent : Forge.Ink0.WithAlpha(0.12)).Pen(1), r, 8, 8);
            dc.DrawText(ft, new Point(r.X + 10, r.Y + 5));
            x += r.Width + 10;
        }
        y += 36;
        dc.DrawText($"{LlmSection.Model} · 1.8 GB · 28 LAYERS", 9, Forge.Ink1, new Point(0, y), tracking: 1.5);
        y += 22;
        dc.DrawText("PROMPT · 512 TOKENS · ONE BATCH" + (prompts > 0 ? $" · ×{prompts}" : ""), 9, phase == LlmPhase.Prefill ? Accent : Forge.Ink2,
            new Point(0, y), w: Forge.W.Bold, tracking: 1.5);
        y += 18;
        double gridH = Math.Min(w * PromptRows / PromptCols, ActualHeight - y - 150 - 70);
        gridH = Math.Max(80, gridH);
        double gridW = gridH * PromptCols / PromptRows;
        PromptGrid(dc, new Rect(0, y, Math.Min(w, gridW), gridH));
        y += gridH + 16;
        dc.DrawText("REPLY · TOKEN BY TOKEN", 9, phase == LlmPhase.Decode ? Accent : Forge.Ink2, new Point(0, y), w: Forge.W.Bold, tracking: 1.5);
        y += 18;
        ReplyGrid(dc, new Rect(0, y, w, 120));
        y += 134;
        dc.DrawText("WEIGHTS FROM A SEED: THE WORDS ARE RANDOM, THE WORK IS THE REAL MODEL'S", 8, Forge.Ink2, new Point(0, y), tracking: 1.2);
    }

    /// <summary>The prompt's 512 tokens: dim until read; each prefill pass sweeps through them at once (one batch).</summary>
    private void PromptGrid(DrawingContext dc, Rect r)
    {
        double sweep = Math.Min(1, (Time - sweepStart) / 0.52), gap = 2;
        double cw = (r.Width - gap * (PromptCols - 1)) / PromptCols, ch = (r.Height - gap * (PromptRows - 1)) / PromptRows;
        bool read = prompts > 0 || phase == LlmPhase.Decode;
        double front = sweep * PromptCols * PromptRows;
        for (int i = 0; i < PromptCols * PromptRows; i++)
        {
            var cell = new Rect(r.X + i % PromptCols * (cw + gap), r.Y + i / PromptCols * (ch + gap), cw, ch);
            double alpha = !read ? 0.08 : phase == LlmPhase.Prefill && i < front && i > front - 64 ? 1 : 0.42;
            dc.DrawRoundedRectangle(Accent.WithAlpha(alpha).Brush(), null, cell, Math.Min(cw, ch) * 0.2, Math.Min(cw, ch) * 0.2);
        }
    }

    /// <summary>The reply so far: one chip per generated token, its id in its own colour.</summary>
    private void ReplyGrid(DrawingContext dc, Rect r)
    {
        int rows = (LlmSection.Reply + ReplyCols - 1) / ReplyCols;
        double gap = 4, cw = (r.Width - gap * (ReplyCols - 1)) / ReplyCols, ch = (r.Height - gap * (rows - 1)) / rows;
        for (int k = 0; k < LlmSection.Reply; k++)
        {
            var cell = new Rect(r.X + k % ReplyCols * (cw + gap), r.Y + k / ReplyCols * (ch + gap), cw, ch);
            if (k < reply.Length)
            {
                var c = TokenColor(reply[k]);
                dc.DrawRoundedRectangle(c.WithAlpha(0.16).Brush(), c.WithAlpha(0.6).Pen(1), cell, 5, 5);
                dc.DrawText(reply[k].ToString(), 9, c, new Point(cell.X + cell.Width / 2, cell.Y + cell.Height / 2), 0.5, 0.5, Forge.W.Bold);
            }
            else dc.DrawRoundedRectangle(Forge.Ink0.WithAlpha(0.04).Brush(), null, cell, 5, 5);
        }
    }
}
