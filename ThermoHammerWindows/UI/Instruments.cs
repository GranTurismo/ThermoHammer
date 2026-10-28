using System.Windows;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// FORGE INSTRUMENTS — the phones' main-screen instruments, drawn in OnRender:
// PhaseRail · ThermalReactor · ThroughputRibbon · CoreConstellation · ThermalHorizon
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>A custom-drawn instrument that can animate (redrawn every frame while <see cref="Animating"/>).</summary>
public abstract class Instrument : FrameworkElement
{
    private bool hooked;
    protected double Time => Environment.TickCount64 / 1000.0;

    protected virtual bool Animating => false;

    protected Instrument()
    {
        Loaded += (_, _) => Hook();
        Unloaded += (_, _) => Unhook();
        IsVisibleChanged += (_, _) => { if (IsVisible) Hook(); else Unhook(); };
    }

    private void Hook() { if (!hooked) { CompositionTarget.Rendering += Tick; hooked = true; } }
    private void Unhook() { if (hooked) { CompositionTarget.Rendering -= Tick; hooked = false; } }
    private void Tick(object? s, EventArgs e) { if (Animating) InvalidateVisual(); }

    protected static StreamGeometry Arc(Point c, double r, double startDeg, double sweepDeg)
    {
        var g = new StreamGeometry();
        if (Math.Abs(sweepDeg) < 0.01) { g.Freeze(); return g; }
        sweepDeg = Math.Min(sweepDeg, 359.99);
        double a0 = startDeg * Math.PI / 180, a1 = (startDeg + sweepDeg) * Math.PI / 180;
        using (var ctx = g.Open())
        {
            ctx.BeginFigure(new Point(c.X + r * Math.Cos(a0), c.Y + r * Math.Sin(a0)), false, false);
            ctx.ArcTo(new Point(c.X + r * Math.Cos(a1), c.Y + r * Math.Sin(a1)), new Size(r, r), 0, sweepDeg > 180, SweepDirection.Clockwise, true, false);
        }
        g.Freeze();
        return g;
    }
}

/// <summary>PRE-FLIGHT ▸ COOLDOWN ▸ WARM-UP ▸ CALIBRATION ▸ MEASURED ▸ VERDICT, and the current phase's progress.</summary>
public sealed class PhaseRail : Instrument
{
    private static readonly string[] Phases = ["PRE-FLIGHT", "COOLDOWN", "WARM-UP", "CALIBRATE", "MEASURED", "VERDICT"];
    private RunPhase phase;
    private bool preflight, completed;
    private double progress, shown;
    private string label = "";

    public PhaseRail() { Height = 74; }

    public void Update(RunPhase p, bool preflightActive, bool done, double prog, string progressLabel)
    {
        phase = p; preflight = preflightActive; completed = done; progress = prog; label = progressLabel;
        InvalidateVisual();
    }

    protected override bool Animating => Math.Abs(shown - progress) > 0.001;

    private static Color Seg(int i) => i switch
    {
        0 => Forge.PhasePreFlight, 1 => Forge.PhaseCooldown, 2 => Forge.PhaseWarmup, 3 => Forge.PhaseCalibration, 4 => Forge.PhaseMeasured,
        _ => Ramp.At(0.05),
    };

    private int Active => completed ? 5 : phase switch
    {
        RunPhase.Measured => 4, RunPhase.Calibration => 3, RunPhase.Warmup => 2, RunPhase.Cooldown => 1, _ => preflight ? 0 : -1,
    };

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        dc.DrawRoundedRectangle(Forge.Surface.Brush(), null, new Rect(0, 0, w, h), 12, 12);
        double x0 = 14, inner = w - 28, colW = inner / Phases.Length;
        int active = Active;
        shown += (progress - shown) * 0.2;
        for (int i = 0; i < Phases.Length; i++)
        {
            bool on = i == active;
            Color seg = i < active ? Seg(i).WithAlpha(0.55) : on ? Seg(i) : Forge.Ink2.WithAlpha(0.30);
            Color line = i <= active ? Seg(i).WithAlpha(0.5) : Forge.Ink2.WithAlpha(0.18);
            double cx = x0 + colW * (i + 0.5), cy = 16;
            if (i > 0) dc.DrawLine(line.Pen(1), new Point(x0 + colW * i, cy), new Point(cx - 4, cy));
            if (i < Phases.Length - 1) dc.DrawLine(line.Pen(1), new Point(cx + 4, cy), new Point(x0 + colW * (i + 1), cy));
            double r = on ? 3.5 : 2.5;
            if (on) dc.DrawEllipse(seg.WithAlpha(0.25).Brush(), null, new Point(cx, cy), 7, 7);
            dc.DrawEllipse(seg.Brush(), null, new Point(cx, cy), r, r);
            dc.DrawText(Phases[i], 7.5, seg, new Point(cx, cy + 9), 0.5, 0, on ? Forge.W.Black : Forge.W.Bold, tracking: 0.4);
        }
        if (active < 0) return;
        Color fill = completed ? Ramp.At(0.05) : preflight ? Forge.PhasePreFlight : Forge.PhaseColor(phase);
        double by = 44;
        dc.DrawRoundedRectangle(Forge.Ink2.WithAlpha(0.18).Brush(), null, new Rect(x0, by, inner, 4), 2, 2);
        for (int q = 1; q < 4; q++) dc.DrawRectangle(Forge.Bg.Brush(), null, new Rect(x0 + inner * q / 4, by, 1.5, 4));
        double fw = inner * Math.Clamp(shown, 0, 1);
        if (fw > 0)
        {
            dc.DrawRoundedRectangle(fill.WithAlpha(0.25).Brush(), null, new Rect(x0, by - 1.5, fw, 7), 3.5, 3.5);
            dc.DrawRoundedRectangle(fill.Brush(), null, new Rect(x0, by, fw, 4), 2, 2);
        }
        if (label.Length > 0)
        {
            dc.DrawText(Phases[Math.Max(0, active)], 8, fill.WithAlpha(0.8), new Point(x0, by + 10), w: Forge.W.Bold, tracking: 1);
            dc.DrawText(label, 8, Forge.Ink1, new Point(x0 + inner, by + 10), 1, 0, Forge.W.Bold);
        }
    }
}

/// <summary>
/// Thermal Reactor — hero instrument. Outer ring: one arc per core type (worker throughput ratio) with a tick per
/// worker; middle ring: thermal coolant (the firmware's level mapped to headroom); centre: delivered capacity.
/// </summary>
public sealed class ThermalReactor : Instrument
{
    private StressState state = new();
    private ClusterTopology topology = ClusterTopology.Flat(1);
    private bool completed;
    private double shownCapacity = 100;

    public ThermalReactor() { Width = 250; Height = 250; }

    public void Update(StressState s, ClusterTopology t, bool done) { state = s; topology = t; completed = done; InvalidateVisual(); }

    private bool Running => state.Phase != RunPhase.Idle;
    private bool Idle => state.Phase == RunPhase.Idle && !completed;
    private bool Arming => state.Phase is RunPhase.Cooldown or RunPhase.Warmup or RunPhase.Calibration;
    protected override bool Animating => Arming || Running;

    private double Capacity => state.BaselineIps > 0 ? Math.Min(110, state.LiveIps / state.BaselineIps * 100) : 100;

    private double Headroom => state.Thermal switch
    {
        ThermalLevel.Fair => 0.66, ThermalLevel.Serious => 0.33, ThermalLevel.Critical => 0.05, _ => 1.0,
    };

    private int? OnsetSec()
    {
        double b = state.BaselineIps;
        var s = state.Stamps;
        if (b <= 0 || s.Count < 3) return null;
        for (int i = 0; i < s.Count - 2; i++)
            if (s[i].IpsTotal < b * 0.9 && s[i + 1].IpsTotal < b * 0.9 && s[i + 2].IpsTotal < b * 0.9) return s[i].ElapsedMs / 1000;
        return null;
    }

    protected override void OnRender(DrawingContext dc)
    {
        var c = new Point(ActualWidth / 2, ActualHeight / 2);
        double R = Math.Min(ActualWidth, ActualHeight) / 2;
        shownCapacity += (Capacity - shownCapacity) * 0.15;
        var ramp = Ramp.ForCapacity(shownCapacity);
        var phaseCol = Forge.PhaseColor(state.Phase);

        if (Running)
        {
            double strength = Math.Min(45, Math.Max(0, 100 - shownCapacity)) / 100 * 0.5 + 0.06;
            var glow = new RadialGradientBrush(ramp.WithAlpha(strength), Colors.Transparent) { GradientOrigin = new Point(0.5, 0.5) };
            dc.DrawEllipse(glow, null, c, R * 1.05, R * 1.05);
        }

        // OUTER — the core-type tick ring
        double outerR = R * 0.92, gap = 6;
        int n = Math.Max(1, topology.Clusters.Count);
        double segSweep = 360.0 / n;
        dc.DrawEllipse(null, Forge.Hairline.WithAlpha(Idle ? 0.5 : 0.35).Pen(2), c, outerR, outerR);
        for (int ci = 0; ci < topology.Clusters.Count; ci++)
        {
            var cores = topology.Clusters[ci];
            double start = -90 + ci * segSweep + gap / 2, sweep = segSweep - gap;
            var rs = cores.Where(k => k < state.WorkerRatios.Length).Select(k => state.WorkerRatios[k]).ToList();
            double ratio = Math.Clamp(rs.Count == 0 ? 0 : rs.Average(), 0, 1);
            if (Idle)
            {
                dc.DrawGeometry(null, Forge.Ink2.WithAlpha(0.45).Pen(2), Arc(c, outerR, start, sweep));
                continue;
            }
            dc.DrawGeometry(null, Forge.Hairline.Pen(7), Arc(c, outerR, start, sweep));
            dc.DrawGeometry(null, Ramp.At(1 - ratio).WithAlpha(Arming ? 0.55 : 1).Pen(7), Arc(c, outerR, start, sweep * ratio));
            for (int k = 0; k < cores.Length; k++)
            {
                int core = cores[k];
                double cr = Math.Clamp(core < state.WorkerRatios.Length ? state.WorkerRatios[core] : 0, 0, 1);
                double a = (start + (k + 0.5) * (sweep / Math.Max(1, cores.Length))) * Math.PI / 180;
                double inner = outerR - 14, outer = outerR - 14 - 8 * cr - 2;
                dc.DrawLine(Ramp.At(1 - cr).WithAlpha(0.9).Pen(cores.Length > 24 ? 1.5 : 2.5, cap: PenLineCap.Round),
                    new Point(c.X + inner * Math.Cos(a), c.Y + inner * Math.Sin(a)), new Point(c.X + outer * Math.Cos(a), c.Y + outer * Math.Sin(a)));
            }
        }

        // MIDDLE — thermal coolant
        double midR = R * 0.70;
        dc.DrawEllipse(null, Forge.Hairline.Pen(3), c, midR, midR);
        if (!Idle) dc.DrawGeometry(null, Ramp.At(1 - Headroom).WithAlpha(0.9).Pen(3.5, cap: PenLineCap.Round), Arc(c, midR, -90, 360 * Headroom));
        if (Idle) dc.DrawEllipse(null, Forge.Ink2.WithAlpha(0.15).Pen(1.5), c, R * 0.55, R * 0.55);

        // arming radar sweep
        if (Arming)
        {
            double angle = Time % 2.2 / 2.2 * 360;
            dc.DrawGeometry(null, phaseCol.WithAlpha(0.8).Pen(2.5), Arc(c, R * 0.80, angle - 60, 60));
        }

        // centre readout
        if (Idle)
        {
            dc.DrawText("STANDBY", 16, Forge.Ink2.WithAlpha(0.9), new Point(c.X, c.Y - 10), 0.5, 0.5, Forge.W.Bold, tracking: 4);
            dc.DrawText("THERMAL REACTOR", 8, Forge.Ink2, new Point(c.X, c.Y + 10), 0.5, 0.5, Forge.W.Bold, tracking: 1);
        }
        else if (state.Phase != RunPhase.Measured)
        {
            string sub = state.Phase switch
            {
                RunPhase.Cooldown => $"{state.Thermal.Name()} → NOMINAL · {(int)state.CooldownElapsed}s",
                RunPhase.Warmup => "SETTLING SCHEDULER + JIT",
                _ => "LOCKING BASELINE",
            };
            dc.DrawText(state.Phase.Label(), 15, phaseCol, new Point(c.X, c.Y - 9), 0.5, 0.5, Forge.W.Black, tracking: 2);
            dc.DrawText(sub, 8, Forge.Ink2, new Point(c.X, c.Y + 11), 0.5, 0.5, Forge.W.Bold, tracking: 0.9);
        }
        else
        {
            dc.DrawText($"{shownCapacity.F(1)}%", 44, ramp, new Point(c.X, c.Y - 22), 0.5, 0.5, display: true);
            dc.DrawText("DELIVERED CAPACITY", 7.5, Forge.Ink2, new Point(c.X, c.Y + 8), 0.5, 0.5, Forge.W.Bold, tracking: 0.9);
            int remaining = Math.Max(0, (int)(state.Duration - state.Elapsed));
            dc.DrawText($"T-{remaining / 60:00}:{remaining % 60:00}", 10, Forge.Ink1, new Point(c.X, c.Y + 24), 0.5, 0.5);
            if (OnsetSec() is { } onset)
                dc.DrawText($"◇ ONSET {onset / 60}:{onset % 60:00}", 9, Ramp.At(0.68), new Point(c.X, c.Y + 40), 0.5, 0.5, Forge.W.Bold);
        }
    }
}

/// <summary>
/// Throughput Ribbon — the ghost prefix (warm-up steel + calibration violet) then the measured ribbon, each segment
/// tinted by its stamp's attribution.
/// </summary>
public sealed class ThroughputRibbon : Instrument
{
    private List<StampV2> stamps = [];
    private List<(RunPhase phase, double ips)> preRun = [];
    private double baseline = 1, duration = 300;
    private const double WarmupS = 12, CalS = 20;

    public ThroughputRibbon() { Height = 130; }

    public void Update(StressState s)
    {
        stamps = s.Stamps; preRun = s.PreRunIps; baseline = Math.Max(1, s.BaselineIps); duration = Math.Max(1, s.Duration);
        InvalidateVisual();
    }

    private static double X(double t, double measSpan, double w)
    {
        double preW = w * 0.22;
        return t < 0 ? preW * (1 + t / 32) : preW + (w - preW) * (t / measSpan);
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        double Y(double pct) => h - Math.Clamp(pct, 0, 1.05) / 1.05 * h;
        dc.DrawRectangle(Forge.PhaseWarmup.WithAlpha(0.06).Brush(), null, new Rect(0, 0, w * 0.22 * (WarmupS / 32), h));
        dc.DrawRectangle(Forge.PhaseCalibration.WithAlpha(0.08).Brush(), null, new Rect(w * 0.22 * (WarmupS / 32), 0, w * 0.22 * (CalS / 32), h));
        dc.DrawLine(Forge.Ink2.WithAlpha(0.5).Pen(1, new DashStyle([8, 6], 0)), new Point(0, Y(1)), new Point(w, Y(1)));
        double x0 = w * 0.22;
        dc.DrawLine(Forge.Ink2.WithAlpha(0.7).Pen(1, new DashStyle([4, 4], 0)), new Point(x0, 0), new Point(x0, h));

        if (preRun.Count > 1)
        {
            var g = new StreamGeometry();
            using (var ctx = g.Open())
            {
                for (int i = 0; i < preRun.Count; i++)
                {
                    double t = -32 + 32.0 * (i + 1) / preRun.Count;
                    var p = new Point(X(t, 1, w), Y(preRun[i].ips / baseline));
                    if (i == 0) ctx.BeginFigure(p, false, false); else ctx.LineTo(p, true, true);
                }
            }
            g.Freeze();
            dc.DrawGeometry(null, Forge.Ink1.WithAlpha(0.55).Pen(1.5), g);
        }

        if (stamps.Count >= 2)
        {
            var pts = stamps.Select(s => (p: new Point(X(s.ElapsedMs / 1000.0, duration, w), Y(s.IpsTotal / baseline)), a: (Attribution)s.Attribution)).ToList();
            var area = new StreamGeometry();
            using (var ctx = area.Open())
            {
                ctx.BeginFigure(new Point(pts[0].p.X, h), true, true);
                foreach (var p in pts) ctx.LineTo(p.p, false, false);
                ctx.LineTo(new Point(pts[^1].p.X, h), false, false);
            }
            area.Freeze();
            dc.DrawGeometry(Forge.PhaseMeasured.WithAlpha(0.10).Brush(), null, area);
            for (int i = 1; i < pts.Count; i++) dc.DrawLine(Forge.AttributionColor(pts[i].a).Pen(2.5, cap: PenLineCap.Round), pts[i - 1].p, pts[i].p);
            var last = pts[^1];
            var lc = Forge.AttributionColor(last.a);
            dc.DrawEllipse(lc.WithAlpha(0.3).Brush(), null, last.p, 5, 5);
            dc.DrawEllipse(lc.Brush(), null, last.p, 2.5, 2.5);
        }
        dc.DrawText("-32s PRE-RUN", 7.5, Forge.Ink2, new Point(w * 0.11, h - 7), 0.5, 0.5);
        dc.DrawText("▸ MEASURED", 7.5, Forge.Ink2, new Point(x0 + 26, h - 7), 0.5, 0.5);
    }
}

/// <summary>Core Constellation — per core type: each worker's throughput gauge and the cores' clock from Windows.</summary>
public sealed class CoreConstellation : Instrument
{
    private ClusterTopology topology = ClusterTopology.Flat(1);
    private double[] ratios = [];
    private int active;
    private TelemetrySample tel = new();

    public void Update(ClusterTopology t, double[] workerRatios, int activeWorkers, TelemetrySample telemetry)
    {
        topology = t; ratios = workerRatios; active = activeWorkers; tel = telemetry;
        Height = 34 + topology.Clusters.Count * 44 + 16;
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        dc.DrawRoundedRectangle(Forge.Surface.Brush(), null, new Rect(0, 0, w, h), 12, 12);
        dc.DrawText("CORE CONSTELLATION", 9, Forge.Ink2, new Point(12, 12), w: Forge.W.Bold, tracking: 1.1);
        dc.DrawText(topology.Describe(), 8, Forge.Ink1, new Point(w - 12, 13), 1, 0, Forge.W.Bold, tracking: 1);
        double y = 34;
        for (int ci = 0; ci < topology.Clusters.Count; ci++)
        {
            var cores = topology.Clusters[ci];
            string name = ci < topology.Names.Count ? topology.Names[ci] : "CPU";
            var col = ci == topology.SlowestClusterIdx && topology.Clusters.Count > 1 ? Forge.PhaseCalibration : Forge.PhaseMeasured;
            dc.DrawText(name, 8, col, new Point(12, y + 2), w: Forge.W.Bold, tracking: 1);
            // the cores' clock now (Windows' per-processor counters)
            var mhz = cores.Where(k => k < tel.CoreMhz.Length).Select(k => tel.CoreMhz[k]).ToList();
            if (mhz.Count > 0) dc.DrawText($"{mhz.Average() / 1000:0.0} GHz", 8, Forge.Ink1, new Point(12, y + 16), w: Forge.W.Bold);
            double gx = 70, gw = w - gx - 12, gap = cores.Length > 16 ? 2 : 4;
            double cw = (gw - gap * (cores.Length - 1)) / Math.Max(1, cores.Length);
            for (int k = 0; k < cores.Length; k++)
            {
                int core = cores[k];
                bool on = core < active;
                double r = Math.Clamp(on && core < ratios.Length ? ratios[core] : 0, 0, 1);
                var rect = new Rect(gx + k * (cw + gap), y, Math.Max(1, cw), 34);
                dc.DrawRoundedRectangle(Forge.Hairline.Brush(), null, rect, 2, 2);
                double fh = rect.Height * r;
                dc.DrawRoundedRectangle((on ? Ramp.At(1 - r) : Forge.Ink2.WithAlpha(0.3)).Brush(), null, new Rect(rect.X, rect.Bottom - fh, rect.Width, fh), 2, 2);
            }
            y += 44;
        }
        dc.DrawText(tel.HasClocks ? "gauges: each worker's throughput share · clocks: Windows' processor counters"
                                  : "Windows reports no clocks here — gauges show worker throughput share",
            7, Forge.Ink2.WithAlpha(0.8), new Point(12, h - 14), w: Forge.W.Bold, tracking: 0.5);
    }
}

/// <summary>Thermal Horizon — the level, its history, and the power context.</summary>
public sealed class ThermalHorizon : Instrument
{
    private ThermalLevel level;
    private double load;
    private List<int> history = [];
    private TelemetrySample tel = new();

    public ThermalHorizon() { Height = 112; }

    public void Update(ThermalLevel t, double systemLoad, List<int> h, TelemetrySample telemetry)
    {
        level = t; load = systemLoad; history = h; tel = telemetry;
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        dc.DrawRoundedRectangle(Forge.Surface.Brush(), null, new Rect(0, 0, w, h), 12, 12);
        dc.DrawText("THERMAL HORIZON", 9, Forge.Ink2, new Point(12, 12), w: Forge.W.Bold, tracking: 1.1);
        dc.DrawText(tel.HasBattery ? $"BATT {tel.BatteryPct?.ToString() ?? "--"}%" : "MAINS POWER", 8, Forge.Ink1, new Point(w - 12, 13), 1, 0, Forge.W.Bold, tracking: 1);
        var col = Forge.ThermalColor(level);
        dc.DrawText(level.Name(), 19, col, new Point(62, 52), 0.5, 0.5, display: true, w: Forge.W.Bold);
        dc.DrawText("SYSTEM STATE", 7, Forge.Ink2, new Point(62, 68), 0.5, 0.5, Forge.W.Bold, tracking: 0.8);
        if (history.Count >= 2)
        {
            double sx = 126, sw = w - sx - 12, sy = 36, sh = 34;
            var g = new StreamGeometry();
            using (var ctx = g.Open())
                for (int i = 0; i < history.Count; i++)
                {
                    var p = new Point(sx + sw * i / (history.Count - 1), sy + sh * (1 - history[i] / 3.0));
                    if (i == 0) ctx.BeginFigure(p, false, false); else ctx.LineTo(p, true, true);
                }
            g.Freeze();
            dc.DrawGeometry(null, col.WithAlpha(0.8).Pen(1.5), g);
        }
        double x = 12, cy = h - 18;
        void Chip(string s, Color c)
        {
            dc.DrawText(s, 7.5, c, new Point(x, cy), w: Forge.W.Bold, tracking: 0.9);
            x += Forge.Text(s, 7.5, c, Forge.W.Bold).Width + s.Length * 0.9 + 12;
        }
        Chip($"AMBIENT LOAD {(int)(load * 100)}%", Forge.Ink2);
        if (tel.PerfLimitPct is { } l) Chip($"PERF LIMIT {l:0}%", l < 95 ? Ramp.At(0.5) : Forge.Ink2);
        if (tel.ZoneTempC is { } z) Chip($"ZONE {z:0}°C", z >= 85 ? Ramp.At(0.62) : Forge.Ink2);
        if (tel.LowPower) Chip(tel.BatterySaver ? "BATTERY SAVER" : "POWER EFFICIENCY", Ramp.At(0.5));
        if (tel.HasBattery && !tel.OnAc) Chip("ON BATTERY", Ramp.At(0.5));
    }
}
