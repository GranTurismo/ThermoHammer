using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// THERMAL X-RAY (Windows)
//
//   The phones' instrument, for a PC: a schematic mainboard seen from above. It
//   lights only what Windows lets an app read, each labelled with its own
//   quantity:
//
//     · rim      — the thermal level (from the firmware's limits; not a °C)
//     · CPU      — per-core worker throughput, the clock now, the performance cap
//     · board    — the hottest ACPI thermal zone, °C (most laptops; desktops rarely)
//     · memory   — RAM in use (a gauge, deliberately NOT on the heat ramp)
//     · power    — mains or battery, and Windows' power mode
//     · battery  — charge level (laptops)
//
//   Everything else is hatched and says NO SENSOR. Part positions are schematic.
// ─────────────────────────────────────────────────────────────────────────────

public sealed class ThermalXRay : Instrument
{
    private enum Region { Cpu, Gpu, Mem, Storage, Board, Battery, Power, Skin }

    private sealed record Lit(Color Color, double Intensity, string Value, string Caption, string? Label = null,
        double? FillLevel = null, double[]? Cores = null, int ActiveCores = 0, bool Dormant = false);

    private sealed record Node(Region Region, string Label, bool Left, double Slot, Func<Rect, Point> Anchor);

    private const double BodyW = 200, Aspect = 0.80, Pad = 16, HeaderH = 34, FooterH = 70, CalloutH = 38;
    private static double BodyH => BodyW / Aspect;

    private static Point At(Rect b, double ux, double uy) => new(b.X + ux * b.Width, b.Y + uy * b.Height);
    private static Rect R(Rect b, double x0, double y0, double x1, double y1) => new(At(b, x0, y0), At(b, x1, y1));
    private static Point Mid(Rect r) => new(r.X + r.Width / 2, r.Y + r.Height / 2);

    private static Rect Socket(Rect b) => R(b, 0.22, 0.09, 0.54, 0.35);
    private static Rect Board(Rect b) => R(b, 0.05, 0.07, 0.15, 0.42);
    private static Rect Ram(Rect b) => R(b, 0.62, 0.07, 0.80, 0.42);
    private static Rect Power(Rect b) => R(b, 0.86, 0.10, 0.95, 0.30);
    private static Rect Gpu(Rect b) => R(b, 0.07, 0.51, 0.93, 0.65);
    private static Rect Storage(Rect b) => R(b, 0.12, 0.73, 0.52, 0.80);
    private static Rect Battery(Rect b) => R(b, 0.60, 0.72, 0.92, 0.93);

    private static readonly Node[] Nodes =
    [
        new(Region.Board, "BOARD · ACPI ZONE", true, 0.12, b => Mid(Board(b))),
        new(Region.Cpu, "CPU", true, 0.32, b => At(b, 0.30, 0.17)),
        new(Region.Gpu, "GPU", true, 0.58, b => At(b, 0.18, 0.58)),
        new(Region.Storage, "STORAGE", true, 0.80, b => Mid(Storage(b))),
        new(Region.Power, "POWER", false, 0.12, b => Mid(Power(b))),
        new(Region.Mem, "MEMORY", false, 0.32, b => Mid(Ram(b))),
        new(Region.Skin, "THERMAL STATE", false, 0.55, b => At(b, 1.0, 0.47)),
        new(Region.Battery, "BATTERY", false, 0.82, b => Mid(Battery(b))),
    ];

    private StressState state = new();
    private ClusterTopology topology = ClusterTopology.Flat(1);
    private int activeWorkers;
    private TelemetrySample tel = new();
    private bool showPeak;
    private Rect liveChip, peakChip;

    public ThermalXRay()
    {
        Height = HeaderH + Pad * 2 + BodyH + FooterH + 24;
        Cursor = Cursors.Arrow;
    }

    public void Update(StressState s, ClusterTopology t, int active, TelemetrySample telemetry)
    {
        state = s; topology = t; activeWorkers = active; tel = telemetry;
        InvalidateVisual();
    }

    private bool HasRunData => state.Stamps.Count > 0 && state.BaselineIps > 0;
    private bool PeakMode => showPeak && HasRunData;
    private bool Calm => PeakMode || state.Thermal >= ThermalLevel.Serious;
    protected override bool Animating => !Calm;

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        var p = e.GetPosition(this);
        if (liveChip.Contains(p)) showPeak = false;
        else if (peakChip.Contains(p) && HasRunData) showPeak = true;
        InvalidateVisual();
    }

    // ── what Windows can honestly light ──

    private Dictionary<Region, Lit> LitRegions()
    {
        var o = new Dictionary<Region, Lit>();
        var level = PeakMode ? (ThermalLevel)Math.Max((int)state.Thermal, state.Stamps.Max(s => s.ThermalState)) : state.Thermal;
        o[Region.Skin] = new Lit(Forge.ThermalColor(level), new[] { 0.15, 0.4, 0.7, 1.0 }[(int)level], level.Name(),
            tel.PerfLimitPct is { } l ? $"CAP {l:0}% · NOT °C" : "FROM THE FIRMWARE · NOT °C", PeakMode ? "WORST STATE" : null);
        o[Region.Cpu] = CpuLit();
        if (tel.ZoneTempC is { } z)
        {
            var c = Ramp.At(Math.Clamp((z - 30) / (95 - 30), 0, 1));
            o[Region.Board] = new Lit(c, Math.Clamp((z - 30) / 65, 0, 1), $"{z:0}°C",
                tel.PassiveLimitPct is < 100 and { } p ? $"PASSIVE COOLING {p:0}%" : "HOTTEST ZONE");
        }
        var mem = Win32.Memory();
        o[Region.Mem] = new Lit(Forge.PhasePreFlight, 0, $"{mem.MemoryLoad}%", $"IN USE · {mem.TotalPhys / 1e9:0} GB", "MEMORY · LOAD",
            FillLevel: mem.MemoryLoad / 100.0);
        o[Region.Power] = new Lit(Forge.PhasePreFlight, 0.3, tel.OnAc ? "MAINS" : "BATTERY",
            tel.BatterySaver ? "BATTERY SAVER" : tel.PowerMode, "POWER · SOURCE");
        if (tel.HasBattery && tel.BatteryPct is { } b)
            o[Region.Battery] = new Lit(Forge.PhasePreFlight, 0, $"{b}%", tel.Charging ? "CHARGING · NO TEMP" : tel.OnAc ? "PLUGGED IN · NO TEMP" : "NO TEMP SENSOR",
                "BATTERY · LEVEL", FillLevel: b / 100.0);
        else
            o[Region.Battery] = new Lit(Forge.Ink2, 0, "NONE", "MAINS-POWERED PC", "BATTERY", Dormant: true);
        return o;
    }

    private Lit CpuLit()
    {
        int n = topology.TotalCores;
        string clock = tel.HasClocks ? $"{tel.CpuMhzAverage / 1000:0.0} GHz" : "";
        if (PeakMode)
        {
            double worst = Math.Min(110, state.Stamps.Min(s => s.IpsTotal) / state.BaselineIps * 100);
            return new Lit(Ramp.ForCapacity(worst), Math.Clamp((100 - worst) / 55, 0, 1), $"{worst:0}%", "LOWEST CAPACITY", "CPU · WORST");
        }
        switch (state.Phase)
        {
            case RunPhase.Idle:
                return tel.HasClocks
                    ? new Lit(Forge.PhaseMeasured, 0.15, clock, $"CLOCK NOW · BASE {tel.BaseMhz / 1000:0.0}", "CPU · CLOCK", Cores: new double[n])
                    : new Lit(Forge.Ink2, 0, "STANDBY", "run a test to light", "CPU", Cores: new double[n], Dormant: true);
            case RunPhase.Cooldown:
                return new Lit(Forge.PhaseCooldown, 0, "COOLING", "no load applied", "CPU", Cores: new double[n], Dormant: true);
            case RunPhase.Warmup:
            case RunPhase.Calibration:
                return new Lit(Forge.PhaseColor(state.Phase), 0.2, state.Phase == RunPhase.Warmup ? "WARM-UP" : "CALIBRATING",
                    clock.Length > 0 ? $"BUILDING BASELINE · {clock}" : "building baseline", "CPU", Cores: state.WorkerRatios, ActiveCores: activeWorkers);
            default:
                double cap = state.BaselineIps > 0 ? Math.Min(110, state.LiveIps / state.BaselineIps * 100) : 100;
                return new Lit(Ramp.ForCapacity(cap), Math.Clamp((100 - cap) / 55, 0, 1), $"{cap:0}%",
                    clock.Length > 0 ? $"CAPACITY · {clock}" : "DELIVERED CAPACITY", "CPU · THROUGHPUT", Cores: state.WorkerRatios, ActiveCores: activeWorkers);
        }
    }

    // ── drawing ──

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        dc.DrawRoundedRectangle(Forge.Surface.Brush(), null, new Rect(0, 0, w, h), 12, 12);
        var lit = LitRegions();
        int reporting = lit.Values.Count(l => !l.Dormant);

        // header
        dc.DrawText("THERMAL X-RAY", 9, Forge.Ink2, new Point(12, 12), w: Forge.W.Bold, tracking: 1.1);
        dc.DrawText($"{reporting} OF {Nodes.Length} REGIONS REPORTING", 7, reporting >= 3 ? Forge.PhaseMeasured : Forge.Ink1,
            new Point(12, 26), w: Forge.W.Bold, tracking: 0.8);
        peakChip = DrawChip(dc, "PEAK", PeakMode, w - 12, HasRunData);
        liveChip = DrawChip(dc, "LIVE", !PeakMode, peakChip.X - 6, true);

        var body = new Rect((w - BodyW) / 2, HeaderH + Pad, BodyW, BodyH);
        double gutter = (w - BodyW) / 2;
        DrawBody(dc, body, gutter, w, lit);

        // callouts
        foreach (var node in Nodes)
        {
            lit.TryGetValue(node.Region, out var l);
            double cy = body.Y + node.Slot * body.Height;
            double x = node.Left ? gutter - 10 : w - gutter + 10;
            double ax = node.Left ? 1 : 0;
            bool live = l != null && !l.Dormant;
            dc.DrawText(l?.Label ?? node.Label, 7, live ? Forge.Ink1 : Forge.Ink2, new Point(x, cy - 15), ax, 0, Forge.W.Bold, tracking: 0.7);
            if (l != null)
            {
                dc.DrawText(l.Value, l.Value.Length > 6 ? 11 : 14, l.Dormant ? Forge.Ink1 : l.Color, new Point(x, cy - 4), ax, 0, Forge.W.Bold);
                if (l.Caption.Length > 0) dc.DrawText(l.Caption.ToUpperInvariant(), 6.5, Forge.Ink2, new Point(x, cy + 12), ax, 0, tracking: 0.4);
            }
            else
            {
                dc.DrawText("NO SENSOR", 7.5, Forge.Ink2.WithAlpha(0.75), new Point(x, cy - 2), ax, 0, tracking: 0.7);
            }
        }

        // footer: the ramp, and what the lights are
        double fy = body.Bottom + Pad + 6;
        dc.DrawText("OK", 6.5, Forge.Ink2, new Point(12, fy), 0, 0.5, Forge.W.Bold);
        var ramp = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 0) };
        foreach (var t in new[] { 0, 0.25, 0.5, 0.68, 0.85, 1.0 }) ramp.GradientStops.Add(new GradientStop(Ramp.At(t), t));
        dc.DrawRoundedRectangle(ramp, null, new Rect(30, fy - 2, w - 100, 4), 2, 2);
        dc.DrawText("DEGRADED", 6.5, Forge.Ink2, new Point(w - 12, fy), 1, 0.5, Forge.W.Bold);
        var note = Forge.Text("Windows gives apps no CPU or GPU temperatures. Lit regions show what it does publish: the firmware's " +
            "limits (the thermal state), each core's clock and the work it delivers, ACPI zones where the PC has them, memory and power. " +
            "Nothing is estimated. Hatched = no readable sensor.", 7, Forge.Ink1);
        note.MaxTextWidth = w - 24;
        dc.DrawText(note, new Point(12, fy + 10));
        if (PeakMode) dc.DrawText("WORST OF RUN · CLICK LIVE TO RETURN", 7, Forge.PhaseMeasured, new Point(12, h - 14), w: Forge.W.Bold, tracking: 0.8);
    }

    private static Rect DrawChip(DrawingContext dc, string text, bool selected, double right, bool enabled)
    {
        var tint = (selected ? Forge.PhaseMeasured : Forge.Ink2).WithAlpha(enabled ? 1 : 0.4);
        var t = Forge.Text(text, 7.5, tint, Forge.W.Bold);
        var r = new Rect(right - t.Width - 16 - text.Length * 0.9, 10, t.Width + 16 + text.Length * 0.9, 18);
        dc.DrawRoundedRectangle(selected ? Forge.PhaseMeasured.WithAlpha(0.10).Brush() : null, tint.WithAlpha(selected ? 0.4 : 0.25).Pen(1), r, 6, 6);
        dc.DrawText(text, 7.5, tint, new Point(r.X + r.Width / 2, r.Y + r.Height / 2), 0.5, 0.5, Forge.W.Bold, tracking: 0.9);
        return r;
    }

    private void DrawBody(DrawingContext dc, Rect b, double gutter, double w, Dictionary<Region, Lit> lit)
    {
        var bodyGeo = new RectangleGeometry(b, b.Width * 0.06, b.Width * 0.06);
        bodyGeo.Freeze();
        var glass = new LinearGradientBrush(Forge.Raised.WithAlpha(0.75), Forge.Surface.WithAlpha(0.95), 90);
        dc.DrawGeometry(glass, null, bodyGeo);

        // traces under the parts
        var trace = Forge.Ink2.WithAlpha(0.18).Pen(0.8);
        for (int i = 0; i < 6; i++)
        {
            double y = b.Y + b.Height * (0.44 + i * 0.012);
            dc.DrawLine(trace, new Point(b.X + b.Width * 0.18, y), new Point(b.X + b.Width * 0.84, y));
        }

        RegionShape(dc, Board(b), 3, Region.Board, lit, 0.9);
        RegionShape(dc, Power(b), 2, Region.Power, lit, 1.2);
        // RAM: four sticks inside the memory region
        RegionShape(dc, Ram(b), 3, Region.Mem, lit, 0.8);
        var ram = Ram(b);
        for (int i = 1; i < 4; i++)
            dc.DrawLine(Forge.Ink2.WithAlpha(0.35).Pen(0.8), new Point(ram.X + ram.Width * i / 4, ram.Y + 3), new Point(ram.X + ram.Width * i / 4, ram.Bottom - 3));
        RegionShape(dc, Gpu(b), 4, Region.Gpu, lit, 0.7);
        var gpu = Gpu(b);
        foreach (var f in new[] { 0.3, 0.7 })   // the card's two fans
            dc.DrawEllipse(null, Forge.Ink1.WithAlpha(0.3).Pen(1), new Point(gpu.X + gpu.Width * f, gpu.Y + gpu.Height / 2), gpu.Height * 0.36, gpu.Height * 0.36);
        RegionShape(dc, Storage(b), 2, Region.Storage, lit, 1.2);
        if (tel.HasBattery) RegionShape(dc, Battery(b), 4, Region.Battery, lit, 0.8);
        else dc.DrawRoundedRectangle(null, Forge.Ink2.WithAlpha(0.25).Pen(1, new DashStyle([3, 4], 0)), Battery(b), 4, 4);

        // the CPU socket: package, die, and a cell per logical processor
        var socket = Socket(b);
        RegionShape(dc, socket, 4, Region.Cpu, lit, 0.9);
        if (lit.TryGetValue(Region.Cpu, out var cpu) && cpu.Cores is { } cores)
            CoreCells(dc, new Rect(socket.X + 6, socket.Y + 6, socket.Width - 12, socket.Height - 12), cores, cpu.ActiveCores, cpu.Dormant || state.Phase == RunPhase.Idle);

        // the rim: the thermal state
        if (lit.TryGetValue(Region.Skin, out var s))
        {
            double k = 0.6 + 0.8 * s.Intensity;
            dc.DrawGeometry(null, s.Color.WithAlpha(Math.Min(1, 0.05 * k)).Pen(16), bodyGeo);
            dc.DrawGeometry(null, s.Color.WithAlpha(Math.Min(1, 0.09 * k)).Pen(10), bodyGeo);
            dc.DrawGeometry(null, s.Color.WithAlpha(Math.Min(1, 0.18 * k)).Pen(5), bodyGeo);
            dc.DrawGeometry(null, s.Color.Pen(1.6), bodyGeo);
        }

        // leader lines + anchor dots
        foreach (var node in Nodes)
        {
            lit.TryGetValue(node.Region, out var l);
            bool live = l != null && !l.Dormant;
            var c = live ? l!.Color : Forge.Ink2;
            var a = node.Anchor(b);
            double y = b.Y + node.Slot * b.Height;
            double sx = node.Left ? gutter - 4 : w - gutter + 4;
            dc.DrawLine(c.WithAlpha(live ? 0.55 : 0.3).Pen(0.8), new Point(sx, y), a);
            if (live) dc.DrawEllipse(c.Brush(), null, a, 2.4, 2.4);
            else dc.DrawEllipse(null, c.WithAlpha(0.6).Pen(1), a, 2.4, 2.4);
        }

        // scan line — clipped to the body
        if (!Calm)
        {
            double sw = Time % 6 / 6, y = b.Y + sw * b.Height, band = b.Height * 0.14;
            dc.PushClip(bodyGeo);
            var g = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(0, 1) };
            g.GradientStops.Add(new GradientStop(Colors.Transparent, 0));
            g.GradientStops.Add(new GradientStop(Forge.PhaseMeasured.WithAlpha(0.10), 0.5));
            g.GradientStops.Add(new GradientStop(Forge.PhaseMeasured.WithAlpha(0.32), 1));
            dc.DrawRectangle(g, null, new Rect(b.X, y - band, b.Width, band));
            dc.DrawLine(Forge.PhaseMeasured.WithAlpha(0.7).Pen(1), new Point(b.X, y), new Point(b.Right, y));
            dc.Pop();
        }
    }

    /// <summary>One part: lit when Windows gives a signal for it, hatched and dashed when not.</summary>
    private static void RegionShape(DrawingContext dc, Rect r, double radius, Region which, Dictionary<Region, Lit> lit, double glow)
    {
        var dash = new DashStyle([5, 4], 0);
        if (!lit.TryGetValue(which, out var l))
        {
            dc.DrawRoundedRectangle(Forge.Ink2.WithAlpha(0.05).Brush(), null, r, radius, radius);
            dc.PushClip(new RectangleGeometry(r, radius, radius));
            for (double x = r.X - r.Height; x < r.Right; x += 6)
                dc.DrawLine(Forge.Ink2.WithAlpha(0.22).Pen(0.8), new Point(x, r.Bottom), new Point(x + r.Height, r.Y));
            dc.Pop();
            dc.DrawRoundedRectangle(null, Forge.Ink2.WithAlpha(0.45).Pen(1, dash), r, radius, radius);
            return;
        }
        if (l.Dormant)
        {
            dc.DrawRoundedRectangle(Forge.Ink2.WithAlpha(0.04).Brush(), l.Color.WithAlpha(0.5).Pen(1, dash), r, radius, radius);
            return;
        }
        if (l.FillLevel is { } level)
        {
            dc.DrawRoundedRectangle(l.Color.WithAlpha(0.08).Brush(), null, r, radius, radius);
            double fh = r.Height * Math.Clamp(level, 0, 1);
            dc.PushClip(new RectangleGeometry(r, radius, radius));
            dc.DrawRectangle(new LinearGradientBrush(l.Color.WithAlpha(0.18), l.Color.WithAlpha(0.42), 90), null, new Rect(r.X, r.Bottom - fh, r.Width, fh));
            dc.Pop();
            dc.DrawRoundedRectangle(null, l.Color.WithAlpha(0.85).Pen(1.1), r, radius, radius);
            return;
        }
        dc.DrawRoundedRectangle(l.Color.WithAlpha(0.14 + 0.16 * l.Intensity).Brush(), null, r, radius, radius);
        double rad = Math.Max(r.Width, r.Height) * glow;
        dc.PushClip(new RectangleGeometry(r, radius, radius));
        dc.DrawEllipse(new RadialGradientBrush(l.Color.WithAlpha(0.45 + 0.4 * l.Intensity), Colors.Transparent), null, Mid(r), rad, rad);
        dc.Pop();
        dc.DrawRoundedRectangle(null, l.Color.WithAlpha(0.85).Pen(1.1), r, radius, radius);
    }

    /// <summary>One cell per logical processor in the socket, coloured by that worker's throughput ratio.</summary>
    private static void CoreCells(DrawingContext dc, Rect q, double[] cores, int active, bool dormant)
    {
        int n = cores.Length;
        if (n == 0) return;
        int cols = (int)Math.Ceiling(Math.Sqrt(n * q.Width / q.Height));
        int rows = (n + cols - 1) / cols;
        double gap = n > 16 ? 1 : 1.5;
        double cw = (q.Width - gap * (cols - 1)) / cols, ch = (q.Height - gap * (rows - 1)) / rows;
        for (int i = 0; i < n; i++)
        {
            var cell = new Rect(q.X + i % cols * (cw + gap), q.Y + i / cols * (ch + gap), cw, ch);
            if (dormant || i >= active) dc.DrawRoundedRectangle(null, Forge.Ink2.WithAlpha(0.5).Pen(0.6), cell, 1, 1);
            else dc.DrawRoundedRectangle(Ramp.At(1 - Math.Clamp(cores[i], 0, 1)).WithAlpha(0.9).Brush(), null, cell, 1, 1);
        }
    }
}
