using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ThermoHammer.Engine;
using ThermoHammer.Engine.Ai;
using ThermoHammer.Engine.Gpu;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK STAGE — the whole window while the benchmark runs: which test, its
// live rate, a view of the work (the CPU's threads, the MEM block map), and one
// progress bar across the sections that run.
// ─────────────────────────────────────────────────────────────────────────────

public static class BenchColor
{
    public static readonly Color Cpu = Forge.Hex(0xE8C547);       // gold
    public static readonly Color Mem = Forge.Hex(0xF2A65A);       // copper
    public static readonly Color MemWrite = Forge.Hex(0xFF7A6B);  // coral
    public static readonly Color Ai = Forge.Hex(0xC792EA);        // lavender
    public static readonly Color Llm = Forge.Hex(0xB5E853);       // lime
    public static Color Gpu(GpuTest t) => t switch { GpuTest.LakeValley => Forge.PhaseCalibration, GpuTest.NeonCity => Ramp.At(0.85), _ => Forge.PhaseCooldown };
    public static Color Ux(UxTest t) => t switch
    {
        UxTest.Web => Forge.PhaseMeasured, UxTest.Scroll => Forge.Hex(0x6FE3A0), UxTest.Gallery => Forge.Hex(0x7FD6E8),
        UxTest.Photo => Forge.Hex(0xFF8FB1), UxTest.Video => Forge.Hex(0x6FA8FF), UxTest.Export => Forge.Hex(0xFFB077), _ => Ramp.At(0.55),
    };
}

public sealed class BenchmarkStage : Grid
{
    private readonly BenchmarkEngine engine;
    private readonly TextBlock section = Ui.Text("", 9, BenchColor.Cpu, Forge.W.Bold);
    private readonly TextBlock title = Ui.Text("", 14, Forge.Ink0, Forge.W.Black);
    private readonly TextBlock detail = Ui.Text("", 10, Forge.Ink1);
    private readonly TextBlock rate = Ui.Text("", 30, Forge.Ink0, Forge.W.Black);
    private readonly TextBlock unit = Ui.Text("", 10, Forge.Ink2, Forge.W.Bold);
    private readonly ContentControl center = new();
    private readonly TextBlock background = Ui.Text("KEEP THERMOHAMMER IN FRONT — A COVERED WINDOW RUNS SLOWER, SO THIS RUN IS NOW UNVERIFIED",
        9, Ramp.At(0.5), Forge.W.Bold, wrap: true, align: TextAlignment.Center);
    private readonly BenchProgressBar bar;
    private readonly CpuLanes lanes = new();
    private readonly BlockMap blocks = new();
    private readonly StageChips chips = new();
    private readonly TextBlock cpuCaption = Ui.Text("", 9, Forge.Ink2, wrap: true, align: TextAlignment.Center);
    private readonly TextBlock memCaption = Ui.Text("", 9, Forge.Ink2, wrap: true, align: TextAlignment.Center);
    private readonly StageChips memChips = new();
    private readonly GpuFrameView gpuView = new();
    private readonly WebStage webStage;
    private readonly QrStageView qrView = new();
    private readonly AiStageView aiView = new();
    private readonly VideoStageView videoView = new();
    private readonly ExportStageView exportView = new();
    private readonly LlmStageView llmView = new();
    private readonly FrameworkElement cpuView, memView;
    private string centerKey = "";

    public BenchmarkStage(BenchmarkEngine engine)
    {
        this.engine = engine;
        webStage = new WebStage(engine);
        Background = Brushes.Black;
        // each section's picture is built once; the stage only switches between them
        cpuView = Ui.VStack(26, chips, lanes, cpuCaption);
        memView = Ui.VStack(20, memChips, blocks, memCaption);
        bar = new BenchProgressBar(engine);
        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        RowDefinitions.Add(new RowDefinition());
        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var rateRow = Ui.HStack(6, rate, unit);
        rateRow.Stretch = false;
        rate.VerticalAlignment = VerticalAlignment.Bottom;
        unit.VerticalAlignment = VerticalAlignment.Bottom;
        unit.Margin = new Thickness(0, 0, 0, 6);
        var header = Ui.HStack(12, Ui.VStack(4, section, title, detail).Grow(), rateRow).Pad(28, 18, 28, 0);
        Children.Add(header);
        center.HorizontalAlignment = HorizontalAlignment.Center;
        center.VerticalAlignment = VerticalAlignment.Center;
        center.MaxWidth = 900;
        center.Margin = new Thickness(28, 12, 28, 12);
        SetRow(center, 1);
        Children.Add(center);
        var bottom = Ui.VStack(10, background, Ui.HStack(14, bar.Grow(), new HoldToAbort(engine.Stop).Align(HorizontalAlignment.Right, VerticalAlignment.Bottom)))
            .Pad(28, 0, 28, 18);
        SetRow(bottom, 2);
        Children.Add(bottom);
        Refresh();
    }

    private Color Accent => engine.CpuTest != null ? BenchColor.Cpu : engine.MemTest != null ? BenchColor.Mem
        : engine.GpuTest is { } g ? BenchColor.Gpu(g) : engine.UxTest is { } u ? BenchColor.Ux(u)
        : engine.AiTest != null ? BenchColor.Ai : engine.LlmAccel != null ? BenchColor.Llm : Forge.PhaseCooldown;

    public void Refresh()
    {
        var accent = Accent;
        section.Text = engine.CpuTest != null ? "CPU BENCHMARK" : engine.MemTest != null ? "MEM BENCHMARK" : engine.GpuTest != null ? "GPU BENCHMARK"
            : engine.UxTest != null ? "UX BENCHMARK" : engine.AiTest != null ? "AI BENCHMARK" : engine.LlmAccel != null ? "LLM BENCHMARK" : "BENCHMARK";
        section.Foreground = accent.Brush();
        if (engine.CpuTest is { } ct)
        {
            title.Text = $"CPU TEST {ct.Number()}/{CpuTests.All.Length} · {ct.Title()}";
            detail.Text = engine.CpuProgress?.Kernel ?? "";
        }
        else if (engine.MemTest is { } mt)
        {
            title.Text = $"MEM TEST {mt.Number()}/{MemTests.All.Length} · {mt.Title()}";
            detail.Text = engine.MemProgress?.Measure ?? mt.Labels()[0];
        }
        else if (engine.GpuTest is { } gt)
        {
            title.Text = $"GPU TEST {gt.Number()}/{GpuTests.All.Length} · {gt.Title()}";
            detail.Text = engine.GpuDetail;
        }
        else if (engine.UxTest is { } ut)
        {
            title.Text = $"UX TEST {ut.Number()}/{UxTests.All.Length} · {ut.Title()}";
            detail.Text = engine.UxDetail;
        }
        else if (engine.AiTest is { } at)
        {
            title.Text = $"AI TEST {at.Number()}/{AiTests.All.Length} · {at.Title()}";
            detail.Text = engine.AiDetail;
        }
        else if (engine.LlmAccel is { } la)
        {
            title.Text = $"LLM TEST {(int)la + 1}/{LlmAccels.All.Length} · {la.Label()}";
            detail.Text = engine.LlmDetail;
        }
        else
        {
            title.Text = "GETTING READY";
            detail.Text = "";
        }
        (double v, string u)? live = engine.CpuTest != null && engine.CpuProgress is { Rate: >= 0 } cp ? (cp.Rate, "OPS/S")
            : engine.MemTest != null && engine.MemProgress is { Rate: >= 0 } mp ? (mp.Rate, mp.Unit)
            : engine.GpuTest != null && engine.GpuFps >= 0 ? (engine.GpuFps, "FPS")
            : engine.UxTest != null && engine.UxValue >= 0 ? (engine.UxValue, engine.UxUnit)
            : engine.AiTest != null && engine.AiRate >= 0 ? (engine.AiRate, "INF/S")
            : engine.LlmAccel != null && engine.LlmRate >= 0 ? (engine.LlmRate, "TOK/S") : null;
        rate.Text = live is { } l ? (l.v < 100 ? l.v.F(1) : l.v.N0()) : "";
        unit.Text = live?.u ?? "";
        background.Visibility = engine.InBackground ? Visibility.Visible : Visibility.Collapsed;

        string key = engine.CpuTest != null ? $"cpu{engine.CpuTest}" : engine.MemTest != null ? $"mem{engine.MemTest}" : engine.GpuTest != null ? $"gpu{engine.GpuTest}"
            : engine.UxTest != null ? $"ux{engine.UxTest}" : engine.AiTest != null ? "ai" : engine.LlmAccel != null ? "llm" : "prep";
        if (key != centerKey)
        {
            centerKey = key;
            center.Content = engine.CpuTest != null ? cpuView : engine.MemTest != null ? memView : engine.GpuTest != null ? gpuView
                : engine.UxTest is { } k ? UxView(k) : engine.AiTest != null ? aiView : engine.LlmAccel != null ? llmView : null;
            bool wide = engine.UxTest is UxTest.Web or UxTest.Scroll or UxTest.Gallery;
            center.MaxWidth = wide ? double.PositiveInfinity : 900;
            center.HorizontalAlignment = wide ? HorizontalAlignment.Stretch : HorizontalAlignment.Center;
            center.VerticalAlignment = wide ? VerticalAlignment.Stretch : VerticalAlignment.Center;
        }
        if (engine.CpuTest is { } c)
        {
            var labels = c.Labels();
            chips.Update(labels, engine.CpuProgress is { } p ? Math.Max(0, Array.IndexOf(labels, p.Kernel)) : 0, BenchColor.Cpu);
            int threads = engine.CpuProgress?.Threads ?? 1;
            lanes.Threads = threads;
            cpuCaption.Text = threads > 1 ? $"{threads} THREADS · EVERY CORE" : "1 THREAD · ONE CORE";
        }
        else if (engine.MemTest is { } m)
        {
            var labels = m.Labels();
            memChips.Update(labels, engine.MemProgress is { } p ? Math.Max(0, Array.IndexOf(labels, p.Measure)) : 0, BenchColor.Mem);
            blocks.Access = engine.MemProgress?.Access;
            blocks.Lanes = m == MemTest.Ram;
            memCaption.Text = engine.MemProgress?.Detail ?? "";
        }
        else if (engine.GpuTest != null)
        {
            gpuView.Update(engine, engine.GpuTest, labels: false);
        }
        else if (engine.AiTest != null)
        {
            aiView.Update(engine.AiVisual);
        }
        else if (engine.LlmAccel != null)
        {
            llmView.Update(engine);
        }
        else if (engine.UxTest is { } u)
        {
            switch (u)
            {
                case UxTest.Photo: gpuView.Update(engine, u, labels: true); break;
                case UxTest.Qr: qrView.Update(engine); break;
                case UxTest.Video: videoView.Update(engine); break;
                case UxTest.Export: exportView.Update(engine); break;
            }
        }
        else
        {
            center.Content = Preparing();
        }
        webStage.Show(engine.WebPage, keep: engine.UxTest is UxTest.Web or UxTest.Scroll or UxTest.Gallery);
        bar.InvalidateVisual();
    }

    private FrameworkElement UxView(UxTest t) => t switch
    {
        UxTest.Web or UxTest.Scroll or UxTest.Gallery => webStage,
        UxTest.Photo => gpuView,
        UxTest.Qr => qrView,
        UxTest.Video => videoView,
        UxTest.Export => exportView,
        _ => Ui.Text(t.Title(), 11, Forge.Ink1, align: TextAlignment.Center),
    };

    private FrameworkElement Preparing() => Ui.VStack(14,
        new Spinner(Forge.PhaseCooldown).Align(HorizontalAlignment.Center),
        Ui.Text($"❄ GETTING READY · {engine.PreparingElapsed}s", 11, Forge.PhaseCooldown, Forge.W.Bold, align: TextAlignment.Center),
        Ui.Text(engine.PreparingBusy ? "Waiting for the PC to finish background work" : "Letting the PC cool to a fair start",
            9, Forge.Ink2, align: TextAlignment.Center));
}

/// <summary>The test's measurements as chips: done, running, waiting.</summary>
public sealed class StageChips : WrapPanel
{
    private string key = "";

    public void Update(string[] labels, int current, Color accent)
    {
        string k = string.Join("|", labels) + current;
        if (k == key) return;
        key = k;
        Children.Clear();
        HorizontalAlignment = HorizontalAlignment.Center;
        for (int i = 0; i < labels.Length; i++)
        {
            bool done = i < current, now = i == current;
            var b = Ui.Box(Ui.Text((done ? "✓ " : "") + labels[i], 10, now ? accent : done ? Forge.Ink1 : Forge.Ink2, now ? Forge.W.Black : Forge.W.Bold,
                    align: TextAlignment.Center).Align(HorizontalAlignment.Center),
                now ? accent.WithAlpha(0.18) : Forge.Surface, 10, 0, now ? accent : done ? accent.WithAlpha(0.4) : Forge.Hairline);
            b.Padding = new Thickness(10, 7, 10, 7);
            b.Margin = new Thickness(4);
            b.MinWidth = 120;
            Children.Add(b);
        }
    }
}

/// <summary>One lane per logical processor; the busy ones pulse (Windows shows the clocks elsewhere).</summary>
public sealed class CpuLanes : Instrument
{
    public int Threads { get; set; } = 1;
    private readonly int lanes = Environment.ProcessorCount;
    protected override bool Animating => true;

    private const double LaneH = 180;

    public CpuLanes() { Height = LaneH + 28; }

    protected override Size MeasureOverride(Size s) => new(Math.Min(double.IsInfinity(s.Width) ? 900 : s.Width, lanes * 48), LaneH + 28);

    protected override void OnRender(DrawingContext dc)
    {
        double t = Time, w = ActualWidth;
        double laneW = Math.Min(36, (w - 8 * (lanes - 1)) / lanes), gap = lanes > 1 ? (w - laneW * lanes) / (lanes - 1) : 0;
        gap = Math.Min(gap, 12);
        double x0 = (w - (laneW * lanes + gap * (lanes - 1))) / 2;
        for (int i = 0; i < lanes; i++)
        {
            bool busy = i < Threads;
            double h = busy ? 0.55 + 0.45 * Math.Abs(Math.Sin(t * 3 + i * 0.9)) : 0.06;
            double x = x0 + i * (laneW + gap);
            dc.DrawRoundedRectangle(Forge.Surface.Brush(), null, new Rect(x, 0, laneW, LaneH), 6, 6);
            dc.DrawRoundedRectangle(BenchColor.Cpu.WithAlpha(busy ? 0.35 + 0.65 * h : 0.15).Brush(), null, new Rect(x, LaneH - LaneH * h, laneW, LaneH * h), 6, 6);
            if (lanes <= 32 || i % 2 == 0) dc.DrawText($"T{i}", 9, Forge.Ink2, new Point(x + laneW / 2, LaneH + 16), 0.5, 0.5);
        }
    }
}

/// <summary>The buffer or file as cols × rows blocks; each glows when touched and fades (reads copper, writes coral).</summary>
public sealed class BlockMap : Instrument
{
    private const int Cols = 24, Rows = 12;
    private readonly float[] heat = new float[Cols * Rows];
    private readonly bool[] written = new bool[Cols * Rows];
    private readonly List<int> trail = [];
    private double head, last, hopIn;
    private MemAccess? shown;
    private SplitMix rng = new(11);
    public MemAccess? Access { get; set; }
    public bool Lanes { get; set; }
    protected override bool Animating => true;

    protected override Size MeasureOverride(Size s)
    {
        double w = Math.Min(double.IsInfinity(s.Width) ? 760 : s.Width, 760);
        return new Size(w, w * Rows / Cols / 0.9);
    }

    private void Touch(int i, bool write) { heat[i] = 1; written[i] = write; }

    private void Step(double now)
    {
        var a = Access;
        if (a != shown) { shown = a; Array.Clear(heat); trail.Clear(); head = 0; }
        double dt = last == 0 ? 0 : Math.Min(0.1, now - last);
        last = now;
        float fade = (float)Math.Exp(-dt * 5);
        for (int i = 0; i < heat.Length; i++) heat[i] *= fade;
        switch (a)
        {
            case MemAccess.Read or MemAccess.Write:
            {
                bool write = a == MemAccess.Write;
                int span = Lanes ? Cols : Cols * Rows, from = (int)head;
                head = (head + dt * (Lanes ? 22 : 60)) % span;
                for (int c = from; c != (int)head; c = (c + 1) % span)
                    if (Lanes) for (int r = 0; r < Rows; r++) Touch(r * Cols + (c + r * 5) % Cols, write);
                    else Touch(c, write);
                break;
            }
            case MemAccess.Copy:
            {
                int from = (int)head;
                head = (head + dt * 22) % Cols;
                for (int c = from; c != (int)head; c = (c + 1) % Cols)
                    for (int r = 0; r < Rows / 2; r++)
                    {
                        Touch(r * Cols + (c + r * 5) % Cols, false);
                        Touch((r + Rows / 2) * Cols + (c + r * 5) % Cols, true);
                    }
                break;
            }
            case MemAccess.RandomRead or MemAccess.RandomWrite:
                for (int k = 0; k < Math.Max(1, (int)(dt * 160)); k++) Touch(rng.Below(heat.Length), a == MemAccess.RandomWrite);
                break;
            case MemAccess.Chase:
                hopIn -= dt;
                if (hopIn <= 0)
                {
                    hopIn = 0.07;
                    int next = rng.Below(heat.Length);
                    Touch(next, false);
                    trail.Add(next);
                    if (trail.Count > 7) trail.RemoveAt(0);
                }
                break;
        }
    }

    protected override void OnRender(DrawingContext dc)
    {
        Step(Time);
        double gap = 3, cw = (ActualWidth - gap * (Cols - 1)) / Cols, ch = (ActualHeight - gap * (Rows - 1)) / Rows;
        Rect R(int i) => new(i % Cols * (cw + gap), i / Cols * (ch + gap), cw, ch);
        for (int i = 0; i < heat.Length; i++)
        {
            var r = R(i);
            dc.DrawRoundedRectangle(Forge.Surface.Brush(), null, r, 2, 2);
            if (heat[i] > 0.02)
                dc.DrawRoundedRectangle((written[i] ? BenchColor.MemWrite : BenchColor.Mem).WithAlpha(0.15 + 0.85 * heat[i]).Brush(), null, r, 2, 2);
        }
        if (shown == MemAccess.Chase && trail.Count > 1)
            for (int k = 1; k < trail.Count; k++)
            {
                Rect a = R(trail[k - 1]), b = R(trail[k]);
                dc.DrawLine(BenchColor.Mem.WithAlpha(0.2 + 0.8 * k / trail.Count).Pen(1.5),
                    new Point(a.X + a.Width / 2, a.Y + a.Height / 2), new Point(b.X + b.Width / 2, b.Y + b.Height / 2));
            }
    }
}

/// <summary>Progress across the benchmark's sections, each a segment in its colour.</summary>
public sealed class BenchProgressBar : FrameworkElement
{
    private readonly BenchmarkEngine engine;
    public BenchProgressBar(BenchmarkEngine engine) { this.engine = engine; Height = 46; }

    private static IEnumerable<(int secs, Color color)> Segments =>
        CpuTests.All.Select(t => (t.Seconds(), BenchColor.Cpu)).Concat(MemTests.All.Select(t => (t.Seconds(), BenchColor.Mem)))
            .Concat(GpuTests.All.Select(t => (t.Seconds(), BenchColor.Gpu(t)))).Concat(UxTests.All.Select(t => (t.Seconds(), BenchColor.Ux(t))))
            .Concat(AiTests.All.Select(t => (t.Seconds(), BenchColor.Ai))).Concat(LlmAccels.All.Select(a => (a.Seconds(), BenchColor.Llm)));

    private double Elapsed
    {
        get
        {
            int cpuTotal = CpuTests.All.Sum(t => t.Seconds()), memTotal = MemTests.All.Sum(t => t.Seconds());
            int gpuTotal = GpuTests.All.Sum(t => t.Seconds()), uxTotal = UxTests.All.Sum(t => t.Seconds()), aiTotal = AiTests.All.Sum(t => t.Seconds());
            if (engine.CpuTest is { } c)
                return CpuTests.All.Where(t => t.Number() < c.Number()).Sum(t => t.Seconds()) + (engine.CpuProgress?.Fraction ?? 0) * c.Seconds();
            if (engine.MemTest is { } m)
                return cpuTotal + MemTests.All.Where(t => t.Number() < m.Number()).Sum(t => t.Seconds()) + (engine.MemProgress?.Fraction ?? 0) * m.Seconds();
            int before = cpuTotal + memTotal;
            if (engine.GpuTest is { } g)
                return before + GpuTests.All.Where(t => t.Number() < g.Number()).Sum(t => t.Seconds()) + engine.GpuProgress * g.Seconds();
            before += gpuTotal;
            if (engine.UxTest is { } u)
                return before + UxTests.All.Where(t => t.Number() < u.Number()).Sum(t => t.Seconds()) + engine.UxProgress * u.Seconds();
            before += uxTotal;
            if (engine.AiTest is { } a)
                return before + AiTests.All.Where(t => t.Number() < a.Number()).Sum(t => t.Seconds()) + engine.AiProgress * a.Seconds();
            before += aiTotal;
            if (engine.LlmAccel is { } l)
                return before + LlmAccels.All.Where(x => x < l).Sum(x => x.Seconds()) + engine.LlmProgress * l.Seconds();
            return 0;
        }
    }

    protected override void OnRender(DrawingContext dc)
    {
        var segs = Segments.ToList();
        double total = segs.Sum(s => s.secs), done = Elapsed, w = ActualWidth;
        string label = engine.CpuTest is { } c ? $"CPU TEST {c.Number()} · {c.Title()}" : engine.MemTest is { } m ? $"MEM TEST {m.Number()} · {m.Title()}"
            : engine.GpuTest is { } g ? $"GPU TEST {g.Number()} · {g.Title()}" : engine.UxTest is { } ux ? $"UX TEST {ux.Number()} · {ux.Title()}"
            : engine.AiTest is { } ai ? $"AI TEST {ai.Number()} · {ai.Title()}" : engine.LlmAccel is { } ll ? $"LLM TEST {(int)ll + 1} · {ll.Label()}" : "PREPARING";
        dc.DrawText(label, 9, Forge.Ink0, new Point(0, 0), w: Forge.W.Bold, tracking: 1.5);
        dc.DrawText($"{(int)(done / total * 100)}%", 9, Forge.Ink1, new Point(w, 0), 1, 0);
        double gap = 3, x = 0, start = 0;
        foreach (var (secs, color) in segs)
        {
            double sw = (w - gap * (segs.Count - 1)) * secs / total;
            dc.DrawRoundedRectangle(color.WithAlpha(0.2).Brush(), null, new Rect(x, 18, sw, 6), 3, 3);
            double fill = Math.Clamp((done - start) / secs, 0, 1);
            if (fill > 0) dc.DrawRoundedRectangle(color.Brush(), null, new Rect(x, 18, sw * fill, 6), 3, 3);
            x += sw + gap;
            start += secs;
        }
        int remaining = Math.Max(0, (int)Math.Ceiling(total - done));
        dc.DrawText($"ENDS IN {remaining / 60:00}:{remaining % 60:00}", 9, Forge.Ink2, new Point(0, 32), tracking: 1.5);
    }
}

/// <summary>An indeterminate spinner in the accent colour.</summary>
public sealed class Spinner : Instrument
{
    private readonly Color color;
    public Spinner(Color c) { color = c; Width = Height = 34; }
    protected override bool Animating => true;
    protected override void OnRender(DrawingContext dc)
    {
        var c = new Point(ActualWidth / 2, ActualHeight / 2);
        dc.DrawEllipse(null, color.WithAlpha(0.15).Pen(3), c, 14, 14);
        dc.DrawGeometry(null, color.Pen(3, cap: PenLineCap.Round), Arc(c, 14, Time * 360 % 360, 90));
    }
}

/// <summary>The GPU test's frame, a 16:9 picture the benchmark thread hands over a few times a second.</summary>
public sealed class GpuFrameView : FrameworkElement
{
    private readonly WriteableBitmap bitmap = new((int)GpuBench.ViewW, (int)GpuBench.ViewH, 96, 96, PixelFormats.Bgr32, null);
    private object? test;
    private int shown;
    private bool hasFrame;
    private bool labels;

    /// <summary>Takes the engine's newest frame; a frame left over from the previous test is not shown. `labels`: name the photo editor's panels.</summary>
    public void Update(BenchmarkEngine engine, object? key, bool labels)
    {
        this.labels = labels;
        if (!Equals(key, test))
        {
            test = key;
            shown = engine.GpuFrameNumber;
            hasFrame = false;
            InvalidateVisual();
        }
        if (engine.GpuFrame is { } f && engine.GpuFrameNumber != shown)
        {
            shown = engine.GpuFrameNumber;
            bitmap.WritePixels(new Int32Rect(0, 0, bitmap.PixelWidth, bitmap.PixelHeight), f, bitmap.PixelWidth * 4, 0);
            hasFrame = true;
            InvalidateVisual();
        }
    }

    protected override Size MeasureOverride(Size s)
    {
        double w = double.IsInfinity(s.Width) ? 900 : s.Width, h = double.IsInfinity(s.Height) ? w * 9 / 16 : s.Height;
        w = Math.Min(w, h * 16 / 9);
        return new Size(w, w * 9 / 16);
    }

    protected override void OnRender(DrawingContext dc)
    {
        var r = new Rect(0, 0, ActualWidth, ActualHeight);
        dc.PushClip(new RectangleGeometry(r, 10, 10));
        dc.DrawRectangle(Brushes.Black, null, r);
        if (hasFrame) dc.DrawImage(bitmap, r);
        if (labels)
            for (int i = 0; i < Engine.Ux.PhotoEditScene.Panels.Length; i++)
            {
                var at = new Point(r.Width * (i % 2 == 0 ? 0.25 : 0.75), r.Height * (i < 2 ? 0.06 : 0.56));
                string text = $"{i + 1} · {Engine.Ux.PhotoEditScene.Panels[i]}";
                var ft = Forge.Text(text, 8, Colors.White, Forge.W.Bold);
                dc.DrawRoundedRectangle(Color.FromArgb(140, 0, 0, 0).Brush(), null,
                    new Rect(at.X - ft.Width / 2 - 5, at.Y - ft.Height / 2 - 2, ft.Width + 10, ft.Height + 4), 4, 4);
                dc.DrawText(text, 8, Colors.White, at, 0.5, 0.5, Forge.W.Bold);
            }
        dc.Pop();
    }
}
