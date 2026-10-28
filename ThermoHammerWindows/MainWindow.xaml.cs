using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using ThermoHammer.Engine;
using ThermoHammer.UI;

namespace ThermoHammer;

// ─────────────────────────────────────────────────────────────────────────────
// THERMOHAMMER FORGE — the main window: the phones' desktop layout (the Mac
// app's): a header, a sidebar (where to go, what to run) and the instrument
// laid out in two columns; the overlays and the benchmark stage above it.
// ─────────────────────────────────────────────────────────────────────────────

public partial class MainWindow : Window
{
    private readonly StressEngine engine = StressEngine.Shared;
    private readonly BenchmarkEngine bench = BenchmarkEngine.Shared;

    private bool runBenchmark = true;          // the run kind picked: benchmark or stress test
    private TestDuration duration = TestDuration.Minutes5;
    private StressThreadingType threading = StressThreadingType.Multi;
    private int tab;                           // 0 diagnostics · 1 leaderboard

    // the instrument
    private readonly PhaseRail rail = new();
    private readonly ThermalReactor reactor = new();
    private readonly ThroughputRibbon ribbon = new();
    private readonly CoreConstellation constellation = new();
    private readonly ThermalHorizon horizon = new();
    private readonly ThermalXRay xray = new();
    private readonly Flex liveChips = Ui.VStack(6);
    private readonly ContentControl updateCard = new();

    // chrome
    private readonly TextBlock onlineText = Ui.Text("", 8, Forge.Ink1, Forge.W.Bold);
    private readonly StatusLed onlineLed = new(Forge.Ink1, 5);
    private readonly TextBlock thermalText = Ui.Text("", 11, Forge.Ink1, Forge.W.Bold);
    private readonly EngravedText subtitle = new() { Label = "PC BENCHMARK · THERMAL STRESS", Size = 9 };
    private readonly ContentControl sidebarNav = new(), runControls = new(), content = new();
    private readonly Grid overlays = new(), stageLayer = new(), welcomeLayer = new();
    private FrameworkElement? diagnostics;
    private LeaderboardTab? leaderboard;

    // overlays
    private bool showPreflight;
    private Scorecard? retainedScorecard;
    private List<StampV2> retainedStamps = [];
    private Guid? currentPendingId;

    public MainWindow()
    {
        InitializeComponent();
        Root.Children.Add(Body());
        Root.Children.Add(overlays);
        Root.Children.Add(stageLayer);
        Root.Children.Add(welcomeLayer);

        engine.Changed += Refresh;
        engine.RunEnded += HandleRunEnd;
        bench.Changed += RefreshBench;
        NetworkMonitor.Shared.Changed += Refresh;
        Activated += (_, _) => bench.WindowActive(true);
        Deactivated += (_, _) => bench.WindowActive(false);
        SourceInitialized += (_, _) => { DarkTitleBar(); Forge.PixelsPerDip = VisualTreeHelper.GetDpi(this).PixelsPerDip; };
        DpiChanged += (_, e) => { Forge.PixelsPerDip = e.NewDpi.PixelsPerDip; InvalidateVisualTree(Root); };

        // telemetry: the header and the idle instruments follow the machine twice a second
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        timer.Tick += (_, _) => { if (!engine.IsRunning) Refresh(); };
        timer.Start();

        BuildSidebar();
        ShowTab(0);
        Refresh();
        RefreshBench();
        if (!Settings.Bool("welcome.seen") && !App.AutoBenchmark && !App.AutoStress || App.Arg("--welcome") != null) ShowWelcome();
        Loaded += (_, _) =>
        {
            if (App.AutoBenchmark)
            {
                runBenchmark = true;
                // `--only gpu,ux`: just those sections (checks)
                if (App.Arg("--only") is { } only) bench.Only = only.Split(',').ToHashSet();
                bench.Start();
            }
            if (App.AutoStress) { runBenchmark = false; engine.StartTest(TestDuration.Minutes5, StressThreadingType.Multi); }
            if (App.Arg("--tab") == "leaderboard") ShowTab(1);
            if (App.Arg("--screenshot") is { } shot) _ = Screenshot(shot);
        };
    }

    /// <summary>The title bar in the app's dark (Windows 10 2004+; Windows 11 also takes the caption colour).</summary>
    private void DarkTitleBar()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        int on = 1;
        DwmSetWindowAttribute(hwnd, 20, ref on, sizeof(int));
        int caption = 0x00170B0B;   // COLORREF 0x00BBGGRR — Forge.Surface #0B1117
        DwmSetWindowAttribute(hwnd, 35, ref caption, sizeof(int));
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    private static void InvalidateVisualTree(DependencyObject d)
    {
        if (d is UIElement e) e.InvalidateVisual();
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(d); i++) InvalidateVisualTree(VisualTreeHelper.GetChild(d, i));
    }

    // ── layout ──

    private UIElement Body()
    {
        var grid = new Grid();
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition());
        var header = Header();
        grid.Children.Add(header);
        var body = new Grid();
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(290) });
        body.ColumnDefinitions.Add(new ColumnDefinition());
        var side = new Border
        {
            Background = Forge.Surface.WithAlpha(0.55).Brush(), BorderBrush = Forge.Hairline.Brush(), BorderThickness = new Thickness(0, 0, 1, 0),
            Child = Ui.VStack(18, sidebarNav, Ui.Line(Forge.Hairline), runControls).Pad(16),
        };
        body.Children.Add(side);
        Grid.SetColumn(content, 1);
        body.Children.Add(content);
        Grid.SetRow(body, 1);
        grid.Children.Add(body);
        return grid;
    }

    private UIElement Header()
    {
        var title = Ui.Text("THERMOHAMMER", 22, Forge.Ink0, Forge.W.Bold, display: true);
        var chip = Ui.Box(Ui.HStack(4, onlineLed.Align(HorizontalAlignment.Left, VerticalAlignment.Center), onlineText), Color.FromArgb(10, 255, 255, 255), 6, 0, Forge.Hairline);
        chip.Padding = new Thickness(6, 3, 6, 3);
        ((Flex)chip.Child).Stretch = false;
        var help = Ui.Button(Ui.Text("?", 10, Forge.Ink1, Forge.W.Bold).Align(HorizontalAlignment.Center, VerticalAlignment.Center), ShowWelcome,
            Colors.Transparent, 10, 0, 0, stroke: Forge.Ink2.WithAlpha(0.6)).Size(20, 20);
        var top = Ui.HStack(8, title, chip.Align(HorizontalAlignment.Left, VerticalAlignment.Center), help.Align(HorizontalAlignment.Left, VerticalAlignment.Center));
        top.Stretch = false;
        var left = Ui.VStack(3, top, subtitle);
        var right = Ui.VStack(2, thermalText.Align(HorizontalAlignment.Right), Ui.Engraved("THERMAL", size: 7).Align(HorizontalAlignment.Right));
        var row = Ui.HStack(10, left, Ui.Spacer(), right);
        return new Border
        {
            Background = Forge.Surface.WithAlpha(0.9).Brush(), BorderBrush = Forge.Hairline.Brush(), BorderThickness = new Thickness(0, 0, 0, 1),
            Padding = new Thickness(16, 10, 16, 10), Child = row,
        };
    }

    private void BuildSidebar()
    {
        var nav = Ui.VStack(4);
        string[] items = ["DIAGNOSTICS", "LEADERBOARD"];
        string[] icons = ["◉", "♛"];
        for (int i = 0; i < 2; i++)
        {
            int index = i;
            bool selected = tab == i;
            var label = Ui.HStack(10, Ui.Text(icons[i], 13, selected ? Forge.Ink0 : Forge.Ink2).Size(18), Ui.Engraved(items[i], selected ? Forge.Ink0 : Forge.Ink2, 10));
            label.Stretch = false;
            foreach (FrameworkElement c in label.Children) c.VerticalAlignment = VerticalAlignment.Center;
            var b = Ui.Button(label, () => ShowTab(index), selected ? Forge.Interact : Colors.Transparent, 8, 12, 10);
            b.HorizontalContentAlignment = HorizontalAlignment.Left;
            nav.Children.Add(b);
        }
        sidebarNav.Content = nav;
    }

    private void ShowTab(int i)
    {
        tab = i;
        BuildSidebar();
        if (i == 0)
        {
            diagnostics ??= Diagnostics();
            content.Content = diagnostics;
        }
        else
        {
            leaderboard ??= new LeaderboardTab();
            content.Content = leaderboard;
            leaderboard.Appeared();
        }
    }

    /// <summary>The instrument, in two columns: reactor, cores and horizon; the ribbon and the X-ray.</summary>
    private FrameworkElement Diagnostics()
    {
        var reactorCard = Ui.Box(Ui.VStack(8, reactor.Align(HorizontalAlignment.Center), liveChips.Align(HorizontalAlignment.Center)).Pad(0, 12),
            Forge.Surface.WithAlpha(0.5), 12);
        var ribbonCard = Ui.Card(ribbon.Pad(14, 12), 0, Forge.Surface.WithAlpha(0.5));
        var left = Ui.VStack(16, Ticked(reactorCard), constellation, horizon).Grow();
        var right = Ui.VStack(16, ribbonCard, xray).Grow();
        var columns = Ui.HStack(16, left, right);
        columns.Stretch = false;
        foreach (FrameworkElement c in columns.Children) c.VerticalAlignment = VerticalAlignment.Top;
        var page = Ui.VStack(16, updateCard, rail, columns).Pad(20);
        page.MaxWidth = 1080;
        return Ui.Scroll(page);
    }

    private static FrameworkElement Ticked(UIElement e)
    {
        var g = new Grid();
        g.Children.Add(e);
        g.Children.Add(new CornerTicks());
        return g;
    }

    // ── refresh ──

    private double Capacity => engine.State.BaselineIps > 0 ? Math.Min(110, engine.State.LiveIps / engine.State.BaselineIps * 100) : 100;

    private double PhaseProgress => engine.State.Phase switch
    {
        RunPhase.Cooldown => Math.Min(1, engine.State.CooldownElapsed / 30),
        RunPhase.Warmup => Math.Min(1, engine.State.PreRunIps.Count / (12.0 * 4)),
        RunPhase.Calibration => Math.Clamp((engine.State.PreRunIps.Count - 48) / (20.0 * 4), 0, 1),
        RunPhase.Measured => engine.State.Duration > 0 ? Math.Min(1, engine.State.Elapsed / engine.State.Duration) : 0,
        _ => 0,
    };

    private string PhaseProgressLabel
    {
        get
        {
            var s = engine.State;
            switch (s.Phase)
            {
                case RunPhase.Cooldown: return $"{s.Thermal.Name()} → NOMINAL · {(int)s.CooldownElapsed}s";
                case RunPhase.Warmup: return "settling";
                case RunPhase.Calibration: return "locking baseline";
                case RunPhase.Measured:
                    int r = Math.Max(0, (int)(s.Duration - s.Elapsed));
                    return $"T-{r / 60:00}:{r % 60:00}";
                default: return "";
            }
        }
    }

    private int ActiveWorkers => threading == StressThreadingType.Single ? 1 : engine.CoreCount;

    private void Refresh()
    {
        var s = engine.State;
        var tel = Telemetry.Shared.Current;
        var level = engine.IsRunning ? s.Thermal : tel.Thermal;
        if (!engine.IsRunning) s.Thermal = tel.Thermal;
        bool online = NetworkMonitor.Shared.IsConnected;
        onlineText.Text = online ? "ONLINE" : "OFFLINE";
        onlineText.Foreground = (online ? Ramp.At(0.05) : Ramp.At(0.5)).Brush();
        onlineLed.Color = online ? Ramp.At(0.05) : Ramp.At(0.5);
        onlineLed.InvalidateVisual();
        thermalText.Text = level.Name();
        thermalText.Foreground = Forge.ThermalColor(level).Brush();
        subtitle.Color = engine.IsRunning ? Ramp.At(0.5) : Forge.Ink2;

        rail.Update(s.Phase, showPreflight, engine.WasCompleted && !engine.IsRunning && retainedScorecard != null, PhaseProgress, PhaseProgressLabel);
        reactor.Update(s, engine.Topology, engine.WasCompleted && !engine.IsRunning);
        ribbon.Update(s);
        constellation.Update(engine.Topology, s.WorkerRatios, engine.IsRunning ? ActiveWorkers : 0, tel);
        horizon.Update(level, engine.IsRunning ? s.SystemLoad : 0, s.Stamps.Select(x => x.ThermalState).ToList(), tel);
        xray.Update(s, engine.Topology, ActiveWorkers, tel);
        RefreshChips();
        RefreshUpdateCard();
        BuildRunControls();
    }

    private bool chipsMeasured, chipsCooldown;
    private Attribution chipsAttribution = (Attribution)(-1);

    private void RefreshChips()
    {
        var s = engine.State;
        bool measured = s.Phase == RunPhase.Measured && s.Attribution != Attribution.None, cooling = s.Phase == RunPhase.Cooldown;
        if (measured == chipsMeasured && cooling == chipsCooldown && s.Attribution == chipsAttribution && !cooling) return;
        chipsMeasured = measured; chipsCooldown = cooling; chipsAttribution = s.Attribution;
        liveChips.Children.Clear();
        if (measured) liveChips.Children.Add(Chip($"◈ {Forge.AttributionLabel(s.Attribution)}", Forge.AttributionColor(s.Attribution)));
        if (cooling) liveChips.Children.Add(Chip($"❄ COOLING {s.Thermal.Name()} → NOMINAL · {(int)s.CooldownElapsed}s", Forge.PhaseCooldown));
    }

    private static FrameworkElement Chip(string text, Color c)
    {
        var b = Ui.Box(Ui.Text(text, 9, c, Forge.W.Bold), c.WithAlpha(0.10), 8, 0, c.WithAlpha(0.35));
        b.Padding = new Thickness(10, 5, 10, 5);
        return b;
    }

    private UpdateImpact.Insight? shownInsight;

    private void RefreshUpdateCard()
    {
        var insight = engine.IsRunning ? null : RunHistoryStore.Shared.Insight(DeviceInfo.Model, DeviceInfo.OsVersion, DeviceInfo.OsBuild);
        if (insight?.DismissKey == shownInsight?.DismissKey && (insight == null) == (shownInsight == null)) return;
        shownInsight = insight;
        updateCard.Content = insight == null ? null : new UpdateImpactCard(insight,
            rec =>
            {
                duration = (TestDuration)rec.DurationType;
                threading = (StressThreadingType)rec.ThreadingType;
                runBenchmark = false;
                OpenPreflight();
            },
            () => { RunHistoryStore.Shared.Dismiss(insight); RefreshUpdateCard(); });
    }

    // ── run controls ──

    private string controlsKey = "";

    /// <summary>Benchmark or stress test, its options, and the button — or, while a test runs, hold to abort.</summary>
    private void BuildRunControls()
    {
        string key = $"{engine.IsRunning}|{runBenchmark}|{duration}|{threading}|{engine.State.Phase}";
        if (key == controlsKey) return;
        controlsKey = key;
        var col = Ui.VStack(10, Ui.Engraved(engine.IsRunning ? "RUNNING" : "RUN", size: 8));
        if (!engine.IsRunning)
        {
            var kinds = Ui.HStack(10);
            foreach (bool b in new[] { true, false })
            {
                bool isB = b;
                kinds.Children.Add(Ui.Button(b ? "BENCHMARK" : "STRESS TEST", () => { runBenchmark = isB; BuildRunControls(); },
                    runBenchmark == b ? Forge.PhasePreFlight : Forge.Interact, runBenchmark == b ? Forge.Bg : Forge.Ink1).Grow());
            }
            col.Children.Add(kinds);
            if (runBenchmark)
            {
                col.Children.Add(Ui.Text("WHOLE PC · SIX SECTIONS · SAME TESTS AS THE PHONES", 8, Forge.Ink2, wrap: true));
            }
            else
            {
                var durs = Ui.HStack(10);
                foreach (var d in new[] { TestDuration.Minutes5, TestDuration.Minutes15, TestDuration.Minutes30 })
                {
                    var dd = d;
                    durs.Children.Add(Ui.Button(d.DisplayName().ToUpperInvariant(), () => { duration = dd; BuildRunControls(); },
                        duration == d ? Forge.PhaseMeasured : Forge.Interact, duration == d ? Forge.Bg : Forge.Ink1).Grow());
                }
                col.Children.Add(durs);
                var threads = Ui.HStack(10);
                foreach (var t in new[] { StressThreadingType.Single, StressThreadingType.Multi })
                {
                    var tt = t;
                    threads.Children.Add(Ui.Button(t == StressThreadingType.Single ? "1 THREAD" : "MULTI THREAD", () => { threading = tt; BuildRunControls(); },
                        threading == t ? Forge.PhaseCalibration : Forge.Interact, threading == t ? Forge.Bg : Forge.Ink1).Grow());
                }
                col.Children.Add(threads);
            }
            var go = Ui.HStack(8, Ui.Text("⚡", 13, Forge.Bg, Forge.W.Black), Ui.Text(runBenchmark ? "RUN BENCHMARK" : "INITIATE STRESS", 13, Forge.Bg, Forge.W.Black));
            go.Stretch = false;
            go.HorizontalAlignment = HorizontalAlignment.Center;
            var start = Ui.Button(go, OpenPreflight, Forge.PhaseMeasured, 12, 12, 14);
            start.Background = new LinearGradientBrush(Forge.PhaseMeasured, Ramp.At(0.25), 0);
            start.Effect = new System.Windows.Media.Effects.DropShadowEffect { Color = Forge.PhaseMeasured, BlurRadius = 18, ShadowDepth = 0, Opacity = 0.35 };
            col.Children.Add(start);
        }
        else
        {
            var info = Ui.VStack(2, Ui.Engraved(engine.State.Phase.Label(), Forge.PhaseColor(engine.State.Phase), 9),
                Ui.Text("HOLD ◉ TO ABORT", 8, Forge.Ink2));
            var row = Ui.HStack(12, info, Ui.Spacer(), new HoldToAbort(engine.Abort));
            row.Stretch = false;
            foreach (FrameworkElement c in row.Children) c.VerticalAlignment = VerticalAlignment.Center;
            col.Children.Add(row);
        }
        runControls.Content = col;
    }

    // ── pre-flight, run end, verdict ──

    private void OpenPreflight()
    {
        if (engine.IsRunning || bench.IsRunning || showPreflight) return;
        showPreflight = true;
        Refresh();
        var p = new PreflightOverlay(
            onProceed: () =>
            {
                CloseOverlay();
                showPreflight = false;
                if (runBenchmark) bench.Start(); else engine.StartTest(duration, threading);
                Refresh();
            },
            onCancel: () => { CloseOverlay(); showPreflight = false; Refresh(); });
        ShowOverlay(p);
    }

    private void ShowOverlay(UIElement e)
    {
        overlays.Children.Clear();
        overlays.Children.Add(e);
        Overlays.FadeIn(e);
    }

    private void CloseOverlay()
    {
        foreach (var c in overlays.Children.OfType<IDisposable>()) c.Dispose();
        overlays.Children.Clear();
    }

    private void HandleRunEnd()
    {
        var s = engine.State;
        if (s.Stamps.Count == 0)
        {
            if (engine.AbortedByUser) ShowOverlay(new AbortOverlay(s.Elapsed, CloseOverlay));
            return;
        }
        var sc = s.Scorecard;
        var wire = s.Stamps.Select(x => new DeviceHammerStamp(x.ElapsedMs, x.IpsTotal, x.ThermalState)).ToList();
        double baseline = Math.Max(1, s.BaselineIps);
        var pending = new PendingTestResult
        {
            Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000.0,
            DurationSeconds = (int)s.Elapsed,
            TestDurationType = engine.Duration.Wire(),
            TestThreadingType = (int)engine.ThreadingType,
            MinStability = s.Stamps.Min(x => x.IpsTotal / baseline * 100),
            FinalStability = Capacity,
            WorstThermalState = s.Stamps.Max(x => x.ThermalState),
            Stamps = wire,
            DeviceModel = DeviceInfo.Model,
            DeviceManufacturer = DeviceInfo.Manufacturer,
            OsVersion = DeviceInfo.OsVersion,
            BaselineScore = (long)(sc?.BaselineIps ?? 0),
            DeliveredCapacity = sc?.DeliveredCapacity,
            SustainedRatio = sc?.SustainedRatio,
            ThrottleOnsetSec = sc?.ThrottleOnsetSec,
            Confidence = sc?.Confidence,
            ValidityFlags = sc?.ValidityFlags,
            SocModel = DeviceInfo.ShortCpu(DeviceInfo.CpuName),
            ClusterTopology = engine.Topology.Describe(),
            OsBuild = DeviceInfo.OsBuild,
        };
        PendingResultStore.Shared.Save(pending);
        currentPendingId = pending.Id;
        // local history for the before/after-update comparison (never uploaded)
        if (engine.WasCompleted && sc != null)
            RunHistoryStore.Shared.Add(new RunRecord(pending.Id, pending.Timestamp, pending.DeviceModel, pending.OsVersion, pending.OsBuild,
                pending.TestDurationType, (int)engine.ThreadingType, sc.DeliveredCapacity, sc.ThrottleOnsetSec, sc.ValidityFlags, sc.Confidence));
        retainedScorecard = sc;
        retainedStamps = s.Stamps.ToList();
        if (sc != null) ShowVerdict();
        else if (engine.AbortedByUser) ShowOverlay(new AbortOverlay(s.Elapsed, CloseOverlay));
        Refresh();
    }

    private void ShowVerdict()
    {
        if (retainedScorecard is not { } sc) return;
        VerdictOverlay? v = null;
        v = new VerdictOverlay(sc, NetworkMonitor.Shared.IsConnected,
            onSubmit: async () =>
            {
                v!.SetSubmitting();
                try
                {
                    var p = PendingResultStore.Shared.Results.FirstOrDefault(r => r.Id == currentPendingId);
                    if (p == null) throw new BenchException("result not found");
                    await Api.SubmitStress(p);
                    PendingResultStore.Shared.Delete(p.Id);
                    v.SetSubmitted("SUBMITTED TO LEADERBOARD");
                }
                catch (Exception e)
                {
                    if (!NetworkMonitor.Shared.IsConnected) { ShowOverlay(new ConnectionOverlay(() => { ShowVerdict(); }, CloseOverlay)); return; }
                    v.SetError(e.Message);
                }
            },
            onDismiss: () => { CloseOverlay(); retainedScorecard = null; Refresh(); });
        ShowOverlay(v);
    }

    // ── the benchmark: the stage while it runs, then the result ──

    private BenchmarkStage? stage;
    private BenchmarkEngine.Phase shownPhase = BenchmarkEngine.Phase.Idle;

    private void RefreshBench()
    {
        if (bench.Current != shownPhase)
        {
            shownPhase = bench.Current;
            stageLayer.Children.Clear();
            stage = null;
            if (bench.IsRunning)
            {
                stage = new BenchmarkStage(bench);
                stageLayer.Children.Add(stage);
            }
            else if (bench.Current == BenchmarkEngine.Phase.Done && bench.Result is { } r)
            {
                var view = new BenchmarkResultView(r, bench.Record, bench.DismissResult);
                stageLayer.Children.Add(view);
                Overlays.FadeIn(view);
            }
            else if (bench.Current == BenchmarkEngine.Phase.Done && bench.Error is { } e)
            {
                stageLayer.Children.Add(Overlays.Message("BENCHMARK STOPPED", e, bench.DismissResult));
            }
        }
        stage?.Refresh();
    }

    // ── welcome ──

    private void ShowWelcome()
    {
        if (engine.IsRunning || bench.IsRunning) return;
        welcomeLayer.Children.Clear();
        var w = new WelcomeView(() =>
        {
            Settings.Set("welcome.seen", true);
            welcomeLayer.Children.Clear();
        }, int.TryParse(App.Arg("--welcome"), out var page) ? page : 0);
        welcomeLayer.Children.Add(w);
        Overlays.FadeIn(w);
    }

    // ── checks: `--screenshot file.png [--after s]` saves the window and quits ──

    /// <summary>`--after 45,160` takes one capture per time (seconds from start), as file-1.png, file-2.png….</summary>
    private async Task Screenshot(string path)
    {
        if (App.Arg("--size") is { } size && size.Split('x') is [var sw, var sh] && double.TryParse(sw, out var w) && double.TryParse(sh, out var h))
        {
            Width = w; Height = h;
        }
        var times = (App.Arg("--after") ?? "2").Split(',').Select(t => double.TryParse(t, out var v) ? v : 2).ToList();
        var start = DateTime.UtcNow;
        for (int i = 0; i < times.Count; i++)
        {
            var wait = start.AddSeconds(times[i]) - DateTime.UtcNow;
            if (wait > TimeSpan.Zero) await Task.Delay(wait);
            var dpi = VisualTreeHelper.GetDpi(this);
            var bmp = new RenderTargetBitmap((int)(Root.ActualWidth * dpi.DpiScaleX), (int)(Root.ActualHeight * dpi.DpiScaleY),
                dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32);
            bmp.Render(Root);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(bmp));
            string file = times.Count == 1 ? path : Path.ChangeExtension(path, null) + $"-{i + 1}.png";
            using (var f = File.Create(file)) enc.Save(f);
        }
        if (!App.Flag("--stay")) Application.Current.Shutdown();
    }
}
