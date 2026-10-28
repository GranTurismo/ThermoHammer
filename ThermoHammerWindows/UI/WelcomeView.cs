using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// WELCOME — what ThermoHammer is and why its numbers can be trusted, on six
// pages (the phones' WelcomeScreen / WelcomeView, worded for a PC): the
// instrument, its six sections, the same work on every platform, scoring that
// can't be gamed, PC Health and the heat test. The pictures are drawn live and
// play their entrance as their page comes in. Shown on first launch; the "?" in
// the header opens it again. Arrow keys, the wheel or NEXT turn the pages.
// ─────────────────────────────────────────────────────────────────────────────

public sealed class WelcomeView : Grid
{
    private sealed record Page(string Kicker, string Title, string Body, Color Accent);

    private static readonly Page[] Pages =
    [
        new("THERMOHAMMER", "The benchmark that tells the truth",
            "Six sections, scored on our server, the same test on every device — and the only one that also tells you when your PC gets too hot to keep up.",
            Forge.PhaseMeasured),
        new("01 · SIX SECTIONS", "One honest score",
            "Processor, memory and storage, 3D graphics with ray tracing, everyday apps, AI on the NPU, and a language model running right on your PC. Each test is 6,000 points on a Snapdragon 8 Elite phone.",
            Ramp.At(0.25)),
        new("02 · SAME WORK EVERYWHERE", "Byte-identical on every platform",
            "Windows, Android, iPhone and Mac run the very same C++, the same 3D scenes, the same AI models and the same seeded language model. A score means the same thing on every device.",
            Forge.PhaseCooldown),
        new("03 · CAN'T BE GAMED", "Scored where no one can tune for it",
            "Makers have been caught boosting known benchmarks. Your raw results are signed and scored on our server, and only clean runs — cool, nothing else running, no power saving — reach the leaderboard.",
            Forge.PhaseCalibration),
        new("04 · PC HEALTH", "Is your PC as fast as it should be?",
            "Every run is compared with other units of your model and with your own past runs. If it's slower, you find out why: heat, background apps, a full drive or an update.",
            Ramp.At(0.5)),
        new("05 · THE HEAT TEST", "See when it starts to throttle",
            "The stress test hammers every core for 5 to 30 minutes and charts every second: when it slows down, how far, and what caused it — with the clocks and the limits Windows reports.",
            Ramp.At(0.68)),
    ];

    private readonly Action onDone;
    private int page;
    private readonly WelcomeArt art = new();
    private readonly TextBlock kicker = Ui.Text("", 10, Forge.PhaseMeasured, Forge.W.Bold);
    private readonly TextBlock title = Ui.Text("", 28, Forge.Ink0, display: true, wrap: true);
    private readonly TextBlock body = Ui.Text("", 13, Forge.Ink1, wrap: true);
    private readonly Flex dots = Ui.HStack(6);
    private readonly Button next;
    private readonly Button skip;
    private readonly Flex column;
    private readonly RadialGradientBrush glow = new() { Center = new Point(0.5, 0.32), GradientOrigin = new Point(0.5, 0.32), RadiusX = 0.7, RadiusY = 0.7 };

    public WelcomeView(Action onDone, int startPage = 0)
    {
        this.onDone = onDone;
        Background = Forge.Bg.Brush();
        Focusable = true;
        body.LineHeight = 20;
        var glowLayer = new Border { Background = glow, IsHitTestVisible = false };
        Children.Add(glowLayer);
        skip = Ui.Button("SKIP", onDone, Colors.Transparent, Forge.Ink1, 10, padV: 8);
        next = Ui.Button("NEXT  →", Next, Forge.PhaseMeasured, Forge.Bg, 12, Forge.W.Black, 14, 14);
        next.MinWidth = 140;
        var top = Ui.HStack(0, Ui.Text("⌖ THERMOHAMMER", 10, Forge.Ink1, Forge.W.Bold).Align(HorizontalAlignment.Left, VerticalAlignment.Center), Ui.Spacer(), skip);
        var text = Ui.VStack(10, kicker, title, body).Pad(0, 0, 0, 12);
        var bottom = Ui.HStack(0, dots.Align(HorizontalAlignment.Left, VerticalAlignment.Center), Ui.Spacer(), next);
        column = Ui.VStack(14, top, art.Grow(), text, bottom).Pad(24, 10, 24, 18);
        column.MaxWidth = 620;
        Children.Add(column);
        page = Math.Clamp(startPage, 0, Pages.Length - 1);
        Show(false);
        Loaded += (_, _) => Focus();
        if (App.Flag("--welcomeTour"))
        {
            var t = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(2.5) };
            t.Tick += (_, _) => { if (page == Pages.Length - 1) t.Stop(); else Next(); };
            t.Start();
        }
    }

    private void Next() { if (page == Pages.Length - 1) onDone(); else Go(page + 1); }

    private void Go(int to)
    {
        to = Math.Clamp(to, 0, Pages.Length - 1);
        if (to == page) return;
        int dir = Math.Sign(to - page);
        page = to;
        Show(true, dir);
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.Key is Key.Right or Key.PageDown or Key.Space) Next();
        else if (e.Key is Key.Left or Key.PageUp) Go(page - 1);
        else if (e.Key == Key.Escape) onDone();
        else if (e.Key == Key.Enter && page == Pages.Length - 1) onDone();
        e.Handled = true;
    }

    private DateTime lastWheel;
    protected override void OnMouseWheel(MouseWheelEventArgs e)
    {
        if ((DateTime.UtcNow - lastWheel).TotalMilliseconds < 450) return;
        lastWheel = DateTime.UtcNow;
        Go(page + (e.Delta < 0 ? 1 : -1));
    }

    private void Show(bool animate, int dir = 1)
    {
        var p = Pages[page];
        bool last = page == Pages.Length - 1;
        kicker.Text = p.Kicker;
        kicker.Foreground = p.Accent.Brush();
        title.Text = p.Title;
        body.Text = p.Body;
        art.Start(page);
        glow.GradientStops = new GradientStopCollection { new(p.Accent.WithAlpha(0.16), 0), new(Colors.Transparent, 1) };
        skip.Visibility = last ? Visibility.Hidden : Visibility.Visible;
        next.Content = Ui.Text(last ? "GET STARTED" : "NEXT  →", 12, Forge.Bg, Forge.W.Black).Align(HorizontalAlignment.Center);
        next.Background = new LinearGradientBrush(p.Accent, p.Accent.WithAlpha(0.75), 0);
        next.MinWidth = last ? 180 : 140;
        dots.Children.Clear();
        for (int i = 0; i < Pages.Length; i++)
            dots.Children.Add(new Border
            {
                Width = i == page ? 24 : 6, Height = 6, CornerRadius = new CornerRadius(3),
                Background = (i == page ? Pages[i].Accent : Forge.Ink2.WithAlpha(0.5)).Brush(),
            });
        if (!animate) return;
        // the text slides in from the side it came from; the picture plays its own entrance
        foreach (var el in new FrameworkElement[] { kicker, title, body })
        {
            var tt = new TranslateTransform(40 * dir, 0);
            el.RenderTransform = tt;
            tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(40 * dir, 0, TimeSpan.FromMilliseconds(420)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
            el.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(380)));
        }
    }
}

/// <summary>The welcome's pictures, drawn live; each plays its entrance from <see cref="Start"/>.</summary>
public sealed class WelcomeArt : Instrument
{
    private int kind;
    private double start;
    protected override bool Animating => true;

    public void Start(int k) { kind = k; start = Time; InvalidateVisual(); }

    private static double Ease(double x) { double c = Math.Clamp(x, 0, 1); return 1 - Math.Pow(1 - c, 3); }
    /// <summary>Progress of a staggered piece: starts at `from` of the entrance, takes `span`.</summary>
    private static double Stage(double a, double from, double span = 0.35) => Ease((a - from) / span);

    protected override void OnRender(DrawingContext dc)
    {
        // a square-ish canvas centred in the space
        double side = Math.Min(ActualWidth, ActualHeight * 1.1);
        double w = side, h = side / 1.1;
        dc.PushTransform(new TranslateTransform((ActualWidth - w) / 2, (ActualHeight - h) / 2));
        double t = Time, a = Ease((t - start) / 1.6);
        switch (kind)
        {
            case 0: Reactor(dc, w, h, t, a); break;
            case 1: Sections(dc, w, h, t, a); break;
            case 2: SameWork(dc, w, h, t, a); break;
            case 3: Server(dc, w, h, t, a); break;
            case 4: Health(dc, w, h, t, a); break;
            default: Heat(dc, w, h, t, a); break;
        }
        dc.Pop();
    }

    private static void Label(DrawingContext dc, string s, Point at, Color c, double size, bool bold = false, double ax = 0, double ay = 0) =>
        dc.DrawText(s, size, c, at, ax, ay, bold ? Forge.W.Black : Forge.W.Bold, tracking: size * 0.06);

    /// <summary>The instrument: rings of the thermal ramp turning around a glowing core, a strike every few seconds.</summary>
    private static void Reactor(DrawingContext dc, double w, double h, double t, double a)
    {
        var c = new Point(w / 2, h / 2);
        double r = Math.Min(w, h) * 0.36, breath = 0.85 + 0.15 * Math.Sin(t * 1.6), gr = r * 0.95 * breath;
        var g = new RadialGradientBrush();
        g.GradientStops.Add(new GradientStop(Ramp.At(0.55).WithAlpha(0.55 * a), 0));
        g.GradientStops.Add(new GradientStop(Ramp.At(0.7).WithAlpha(0.18 * a), 0.5));
        g.GradientStops.Add(new GradientStop(Colors.Transparent, 1));
        dc.DrawEllipse(g, null, c, gr, gr);
        dc.DrawEllipse(Ramp.At(0.9).WithAlpha(0.9 * a).Brush(), null, c, r * 0.11 * breath, r * 0.11 * breath);
        for (int k = 0; k < 3; k++)
        {
            double rr = r * (0.48 + 0.26 * k) * (0.7 + 0.3 * a), dir = k % 2 == 0 ? 1 : -1, rot = dir * t * (24 + 14 * k) + k * 40;
            // the arc in ramp colours: short segments, each its own colour
            int segs = 40;
            for (int s = 0; s < segs; s++)
            {
                double f0 = (double)s / segs, sweep = 300 * a / segs;
                dc.DrawGeometry(null, Ramp.At(f0 < 0.75 ? f0 / 0.75 * 0.85 : (1 - f0) / 0.25 * 0.85).Pen(5 - k, cap: PenLineCap.Round),
                    ArcAt(c, rr, rot + 300 * a * f0, sweep * 1.05));
            }
            for (int j = 0; j < 12; j++)
            {
                double ang = (j * 30 + rot) * Math.PI / 180;
                dc.DrawLine(Forge.Ink2.WithAlpha(0.5 * a).Pen(1), new Point(c.X + Math.Cos(ang) * (rr + 6), c.Y + Math.Sin(ang) * (rr + 6)),
                    new Point(c.X + Math.Cos(ang) * (rr + 11), c.Y + Math.Sin(ang) * (rr + 11)));
            }
        }
        double wv = t % 2.6 / 2.6, sr = r * (0.2 + 1.1 * wv);
        dc.DrawEllipse(null, Ramp.At(0.6).WithAlpha((1 - wv) * 0.45 * a).Pen(3 * (1 - wv) + 0.5), c, sr, sr);
        for (int i = 0; i < 26; i++)
        {
            double seed = i * 37.17, life = (t * (0.22 + i % 5 * 0.05) + seed) % 1;
            double x = c.X + Math.Sin(seed) * r * 1.1 + Math.Sin(t + seed) * 8, y = c.Y + r - life * r * 2.2, pr = (1.4 + i % 3) * (1 - life);
            dc.DrawEllipse(Ramp.At(0.4 + 0.5 * life).WithAlpha((1 - life) * 0.8 * a).Brush(), null, new Point(x, y), pr, pr);
        }
    }

    private static Geometry ArcAt(Point c, double r, double startDeg, double sweepDeg)
    {
        double a0 = startDeg * Math.PI / 180, a1 = (startDeg + sweepDeg) * Math.PI / 180;
        var g = new StreamGeometry();
        using (var ctx = g.Open())
        {
            ctx.BeginFigure(new Point(c.X + r * Math.Cos(a0), c.Y + r * Math.Sin(a0)), false, false);
            ctx.ArcTo(new Point(c.X + r * Math.Cos(a1), c.Y + r * Math.Sin(a1)), new Size(r, r), 0, false, SweepDirection.Clockwise, true, false);
        }
        g.Freeze();
        return g;
    }

    /// <summary>Six tiles light up one after another, each running its own little workload, the total counting up.</summary>
    private static void Sections(DrawingContext dc, double w, double h, double t, double a)
    {
        string[] names = ["CPU", "MEM", "GPU", "UX", "AI", "LLM"];
        Color[] colors = [BenchColor.Cpu, BenchColor.Mem, Forge.PhaseCalibration, Forge.PhaseMeasured, BenchColor.Ai, BenchColor.Llm];
        double gap = 10, tileW = (w - gap * 2) / 3, tileH = (h * 0.72 - gap) / 2;
        for (int i = 0; i < 6; i++)
        {
            double p = Stage(a, i * 0.09, 0.4), x = i % 3 * (tileW + gap), y = i / 3 * (tileH + gap) + (1 - p) * 30;
            var rect = new Rect(x, y, tileW, tileH);
            dc.DrawRoundedRectangle(Forge.Surface.WithAlpha(p).Brush(), colors[i].WithAlpha(0.55 * p).Pen(1), rect, 14, 14);
            Label(dc, names[i], new Point(x + 12, y + 10), colors[i].WithAlpha(p), 11);
            int bars = 7;
            double bw = (tileW - 24) / (bars * 1.6);
            for (int b = 0; b < bars; b++)
            {
                double bh = (0.25 + 0.75 * (0.5 + 0.5 * Math.Sin(t * (2.2 + i * 0.3) + b * 0.9 + i))) * tileH * 0.42 * p;
                dc.DrawRoundedRectangle(colors[i].WithAlpha(0.8 * p).Brush(), null, new Rect(x + 12 + b * bw * 1.6, y + tileH - 12 - bh, bw, bh), bw / 2, bw / 2);
            }
        }
        int total = (int)(36026 * Stage(a, 0.45, 0.55));
        double ty = h * 0.80;
        Label(dc, "TOTAL", new Point(0, ty), Forge.Ink2, 10);
        Label(dc, total.N0(), new Point(0, ty + 14), Forge.Ink0, 30, true);
        Label(dc, "CPU + MEM + GPU + UX + AI + LLM", new Point(w * 0.42, ty + 26), Forge.Ink2, 9);
    }

    /// <summary>The same packets, in the same order, flowing from one seed into a PC, an Android phone, an iPhone and a Mac.</summary>
    private static void SameWork(DrawingContext dc, double w, double h, double t, double a)
    {
        Color[] packet = [BenchColor.Cpu, BenchColor.Mem, Forge.PhaseCalibration, Forge.PhaseMeasured, BenchColor.Ai, BenchColor.Llm];
        var src = new Point(w / 2, h * 0.12);
        dc.DrawRoundedRectangle(Forge.Surface.Brush(), Forge.PhaseCooldown.WithAlpha(0.6).Pen(1), new Rect(src.X - 62, src.Y - 16, 124, 32), 10, 10);
        Label(dc, "ONE TEST · ONE SEED", src, Forge.PhaseCooldown, 9, ax: 0.5, ay: 0.5);
        double devY = h * 0.62;
        double[] xs = [w * 0.13, w * 0.38, w * 0.62, w * 0.87];
        string[] names = ["WINDOWS", "ANDROID", "iPHONE", "MAC"];
        int[] kinds = [3, 0, 1, 2];
        for (int k = 0; k < 4; k++)
        {
            double p = Stage(a, 0.1 + k * 0.1, 0.45), x = xs[k];
            Point p0 = new(src.X, src.Y + 16), p3 = new(x, devY - 58), c1 = new(src.X, src.Y + 70), c2 = new(x, p3.Y - 70);
            var path = new StreamGeometry();
            using (var ctx = path.Open()) { ctx.BeginFigure(p0, false, false); ctx.BezierTo(c1, c2, p3, true, false); }
            path.Freeze();
            dc.DrawGeometry(null, Forge.Ink2.WithAlpha(0.35 * p).Pen(1.5), path);
            for (int j = 0; j < 6; j++)
            {
                double u = (t * 0.32 + j / 6.0) % 1, m = 1 - u;
                double px = m * m * m * p0.X + 3 * m * m * u * c1.X + 3 * m * u * u * c2.X + u * u * u * p3.X;
                double py = m * m * m * p0.Y + 3 * m * m * u * c1.Y + 3 * m * u * u * c2.Y + u * u * u * p3.Y;
                dc.DrawRoundedRectangle(packet[j].WithAlpha(p * (0.4 + 0.6 * Math.Sin(u * Math.PI))).Brush(), null, new Rect(px - 4, py - 4, 8, 8), 2, 2);
            }
            Device(dc, kinds[k], new Point(x, devY), p);
            Label(dc, names[k], new Point(x, devY + 70), Forge.Ink1.WithAlpha(p), 9, ax: 0.5, ay: 0.5);
            string shown = "26f8354e"[..(int)(8 * Stage(a, 0.55 + k * 0.05, 0.35))];
            Label(dc, shown, new Point(x, devY), Forge.PhaseCooldown.WithAlpha(p), 9, ax: 0.5, ay: 0.5);
        }
        Label(dc, "same code · same scenes · same models · same result", new Point(w / 2, h * 0.97), Forge.Ink2.WithAlpha(a), 9, ax: 0.5, ay: 0.5);
    }

    private static void Device(DrawingContext dc, int kind, Point c, double p)
    {
        var col = Forge.Ink1.WithAlpha(0.8 * p);
        if (kind < 2)
        {
            var r = new Rect(c.X - 27, c.Y - 56, 54, 112);
            dc.DrawRoundedRectangle(Forge.Surface.WithAlpha(p).Brush(), col.Pen(1.5), r, 12, 12);
            if (kind == 0) dc.DrawEllipse(col.Brush(), null, new Point(c.X, r.Y + 8), 2.5, 2.5);
            else dc.DrawRoundedRectangle(col.Brush(), null, new Rect(c.X - 9, r.Y + 6, 18, 5), 3, 3);
        }
        else if (kind == 2)
        {
            var r = new Rect(c.X - 46, c.Y - 36, 92, 60);
            dc.DrawRoundedRectangle(Forge.Surface.WithAlpha(p).Brush(), col.Pen(1.5), r, 6, 6);
            dc.DrawRoundedRectangle(col.Brush(), null, new Rect(r.X - 10, r.Bottom + 2, r.Width + 20, 5), 3, 3);
        }
        else
        {
            // a monitor on a stand, and the Windows logo's four panes
            var r = new Rect(c.X - 46, c.Y - 40, 92, 62);
            dc.DrawRoundedRectangle(Forge.Surface.WithAlpha(p).Brush(), col.Pen(1.5), r, 4, 4);
            dc.DrawLine(col.Pen(2), new Point(c.X, r.Bottom), new Point(c.X, r.Bottom + 10));
            dc.DrawRoundedRectangle(col.Brush(), null, new Rect(c.X - 18, r.Bottom + 10, 36, 4), 2, 2);
            for (int q = 0; q < 4; q++)
                dc.DrawRectangle(Forge.PhasePreFlight.WithAlpha(0.35 * p).Brush(), null, new Rect(c.X - 11 + q % 2 * 12, r.Y + 8 + q / 2 * 12, 10, 10));
        }
    }

    /// <summary>Raw results leave the device signed, the server scores them, and only clean runs pass its checks.</summary>
    private static void Server(DrawingContext dc, double w, double h, double t, double a)
    {
        var pc = new Point(w * 0.16, h * 0.34);
        var srv = new Point(w * 0.84, h * 0.34);
        Device(dc, 3, pc, a);
        for (int u = 0; u < 3; u++)
        {
            var r = new Rect(srv.X - 38, srv.Y - 46 + u * 32, 76, 26);
            dc.DrawRoundedRectangle(Forge.Surface.WithAlpha(a).Brush(), Forge.PhaseCalibration.WithAlpha(0.6 * a).Pen(1), r, 6, 6);
            for (int l = 0; l < 3; l++)
            {
                bool on = Math.Sin(t * 5 + u * 1.7 + l * 2.3) > 0.2;
                dc.DrawEllipse((on ? Forge.PhaseMeasured : Forge.Ink2).WithAlpha(a).Brush(), null, new Point(r.X + 12 + l * 9, r.Y + 13), 2.5, 2.5);
            }
        }
        double y = pc.Y;
        dc.DrawLine(new Pen(Forge.Ink2.WithAlpha(0.4 * a).Brush(), 1.5) { DashStyle = new DashStyle([4, 4], -t * 20 / 1.5) },
            new Point(pc.X + 50, y), new Point(srv.X - 46, y));
        for (int j = 0; j < 3; j++)
        {
            double u = (t * 0.45 + j / 3.0) % 1, x = pc.X + 50 + u * (srv.X - pc.X - 96);
            dc.DrawRoundedRectangle(Forge.PhaseCalibration.WithAlpha(a * Math.Sin(u * Math.PI)).Brush(), null, new Rect(x - 9, y - 6, 18, 12), 3, 3);
        }
        var lc = new Point(w / 2, y - 22);
        dc.DrawGeometry(null, Forge.PhaseCalibration.WithAlpha(a).Pen(2), ArcAt(new Point(lc.X, lc.Y - 4), 7, 180, 180));
        dc.DrawRoundedRectangle(Forge.PhaseCalibration.WithAlpha(a).Brush(), null, new Rect(lc.X - 10, lc.Y - 4, 20, 15), 3, 3);
        Label(dc, "SIGNED · SCORED ON THE SERVER", new Point(w / 2, y + 22), Forge.Ink2.WithAlpha(a), 9, ax: 0.5, ay: 0.5);
        string[] checks = ["STARTED COOL", "NOTHING ELSE RUNNING", "IN FRONT THE WHOLE RUN", "NO POWER SAVING"];
        for (int i = 0; i < checks.Length; i++)
        {
            double p = Stage(a, 0.3 + i * 0.13, 0.3), cy = h * 0.62 + i * 26, x0 = w * 0.18;
            dc.DrawRoundedRectangle(Forge.Surface.WithAlpha(p).Brush(), null, new Rect(x0, cy - 10, w * 0.64, 21), 7, 7);
            bool ok = p >= 1;
            dc.DrawEllipse((ok ? Forge.PhaseMeasured : Forge.Ink2).WithAlpha(p).Brush(), null, new Point(x0 + 14, cy), 6, 6);
            if (ok)
            {
                var tick = new StreamGeometry();
                using (var ctx = tick.Open())
                {
                    ctx.BeginFigure(new Point(x0 + 11, cy), false, false);
                    ctx.LineTo(new Point(x0 + 13.5, cy + 2.5), true, true);
                    ctx.LineTo(new Point(x0 + 17.5, cy - 2.5), true, true);
                }
                tick.Freeze();
                dc.DrawGeometry(null, Forge.Bg.Pen(1.6, cap: PenLineCap.Round), tick);
            }
            Label(dc, checks[i], new Point(x0 + 28, cy), (ok ? Forge.Ink0 : Forge.Ink2).WithAlpha(p), 10, ay: 0.5);
        }
    }

    /// <summary>Other units of the model pile up into a curve; this PC's marker slides in and the reason appears.</summary>
    private static void Health(DrawingContext dc, double w, double h, double t, double a)
    {
        double bas = h * 0.62, hh = h * 0.42;
        static double Curve(double x) => Math.Exp(-((x - 0.55) * (x - 0.55)) / (2 * 0.12 * 0.12));
        double grow = Stage(a, 0, 0.5);
        for (int i = 0; i < 70; i++)
        {
            double u = i * 0.6180339 % 1;
            int col = (int)(Curve(u) * 9), stack = i * 7 % (col + 1);
            double fall = Stage(a, 0.02 * (i % 25), 0.3);
            double dy = bas - 6 - stack * 9 * Math.Max(0.15, Curve(u)) - (1 - fall) * 120;
            dc.DrawEllipse(Forge.Ink1.WithAlpha(0.35 * fall).Brush(), null, new Point(u * w, dy), 2.6, 2.6);
        }
        var area = new StreamGeometry();
        using (var ctx = area.Open())
        {
            ctx.BeginFigure(new Point(0, bas), true, true);
            for (int k = 0; k <= 100; k++) { double x = k / 100.0; ctx.LineTo(new Point(x * w, bas - Curve(x) * hh * grow), false, false); }
            ctx.LineTo(new Point(w, bas), false, false);
        }
        area.Freeze();
        var fill = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(0, 1) };
        fill.GradientStops.Add(new GradientStop(Ramp.At(0.25).WithAlpha(0.28), 0));
        fill.GradientStops.Add(new GradientStop(Colors.Transparent, 1));
        dc.DrawGeometry(fill, null, area);
        dc.DrawRectangle(Ramp.At(0.25).WithAlpha(0.12 * grow).Brush(), null, new Rect(0.47 * w, bas - hh, 0.16 * w, hh));
        dc.DrawLine(Forge.Ink0.WithAlpha(0.6 * grow).Pen(1.5), new Point(0.55 * w, bas - hh * 1.05), new Point(0.55 * w, bas));
        Label(dc, "TYPICAL", new Point(0.55 * w, bas - hh * 1.12), Forge.Ink1.WithAlpha(grow), 9, ax: 0.5, ay: 0.5);
        dc.DrawLine(Forge.Ink2.Pen(1), new Point(0, bas), new Point(w, bas));
        double you = 0.08 + (0.36 - 0.08) * Stage(a, 0.45, 0.4) + 0.004 * Math.Sin(t * 2), ux = you * w;
        var accent = Ramp.At(0.5);
        dc.DrawLine(accent.Pen(2), new Point(ux, bas - hh * 0.6), new Point(ux, bas));
        dc.DrawEllipse(accent.Brush(), null, new Point(ux, bas - hh * 0.6), 6, 6);
        Label(dc, "YOU · 86%", new Point(ux, bas - hh * 0.6 - 16), accent, 10, true, 0.5, 0.5);
        double rr = Stage(a, 0.8, 0.25);
        var box = new Rect(0, bas + 18 + (1 - rr) * 12, w, 54);
        dc.DrawRoundedRectangle(Forge.Surface.WithAlpha(rr).Brush(), accent.WithAlpha(0.5 * rr).Pen(1), box, 10, 10);
        Label(dc, "▲ GPU 18% BELOW OTHER UNITS", new Point(12, box.Y + 9), Ramp.At(0.85).WithAlpha(rr), 10, true);
        Label(dc, "It throttled. Check the fans and the vents.", new Point(12, box.Y + 28), Forge.Ink1.WithAlpha(rr), 10);
    }

    /// <summary>Temperature climbs; performance holds, then sags at the onset — drawn as it happens, over and over.</summary>
    private static void Heat(DrawingContext dc, double w, double h, double t, double a)
    {
        double top = h * 0.1, bottom = h * 0.78;
        for (int g = 0; g <= 4; g++) { double y = top + (bottom - top) * g / 4; dc.DrawLine(Forge.Hairline.Pen(1), new Point(0, y), new Point(w, y)); }
        double sweep = Math.Min(1, t % 7 / 6), onset = 0.42;
        double Perf(double x) => x < onset ? 0.92 - 0.02 * Math.Sin(x * 30) : 0.92 - 0.34 * (1 - Math.Exp(-(x - onset) * 6)) - 0.02 * Math.Sin(x * 30);
        static double Temp(double x) => 0.15 + 0.7 * (1 - Math.Exp(-x * 3.2));
        int n = Math.Max(1, (int)(120 * sweep * a));
        var tp = new StreamGeometry();
        var pp = new StreamGeometry();
        using (var tc = tp.Open())
        using (var pc = pp.Open())
            for (int k = 0; k <= n; k++)
            {
                double x = k / 120.0, px = x * w;
                var tpt = new Point(px, bottom - Temp(x) * (bottom - top));
                var ppt = new Point(px, bottom - Perf(x) * (bottom - top));
                if (k == 0) { tc.BeginFigure(tpt, false, false); pc.BeginFigure(ppt, false, false); }
                else { tc.LineTo(tpt, true, true); pc.LineTo(ppt, true, true); }
            }
        tp.Freeze(); pp.Freeze();
        var tg = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(w, 0), MappingMode = BrushMappingMode.Absolute };
        tg.GradientStops.Add(new GradientStop(Ramp.At(0.2), 0));
        tg.GradientStops.Add(new GradientStop(Ramp.At(0.55), 0.5));
        tg.GradientStops.Add(new GradientStop(Ramp.At(0.85), 1));
        dc.DrawGeometry(null, new Pen(tg, 2.5) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round }, tp);
        dc.DrawGeometry(null, Forge.PhaseMeasured.Pen(2.5, cap: PenLineCap.Round), pp);
        if (sweep > onset)
        {
            double ox = onset * w;
            dc.DrawLine(Ramp.At(0.85).WithAlpha(0.8).Pen(1.5, new DashStyle([5, 4], 0)), new Point(ox, top), new Point(ox, bottom));
            Label(dc, "THROTTLING STARTS · 4:12", new Point(ox + 6, top + 2), Ramp.At(0.85), 9, true);
        }
        Label(dc, "PERFORMANCE", new Point(0, bottom + 12), Forge.PhaseMeasured, 10, true);
        Label(dc, "TEMPERATURE", new Point(w * 0.5, bottom + 12), Ramp.At(0.6), 10, true);
    }
}
