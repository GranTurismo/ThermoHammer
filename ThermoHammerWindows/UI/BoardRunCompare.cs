using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>
/// Two stress runs side by side — the phones' ComparisonOverlay / ComparisonView: Run A and Run B are picked from drop-downs
/// over this PC's runs and the online board that have the same threading type as the runs it was opened with (a single
/// thread run is only compared with single thread runs), then the dual curve, the diagnostics and the insights.
/// With no run to pick from it shows "UNIVERSAL RUN COMPARISON".
/// </summary>
public sealed class RunComparison : Grid
{
    private readonly int threading;
    private readonly PendingTestResult? preA, preB;
    private readonly List<PendingTestResult> local;
    private List<PendingTestResult> online;
    private List<PendingTestResult> runs = [];
    private PendingTestResult? a, b;
    private readonly Dictionary<int, List<DeviceHammerStamp>> stamps = [];
    private readonly HashSet<int> asked = [], inFlight = [], failed = [];
    private readonly Action close;
    private readonly ContentControl body = new();

    /// <param name="known">The online board as the page already has it (fetched again only when it is empty).</param>
    public RunComparison(PendingTestResult? runA, PendingTestResult? runB, IEnumerable<HammerDto> known, Action close)
    {
        preA = a = runA;
        preB = b = runB;
        this.close = close;
        threading = (runA?.TestThreadingType ?? runB?.TestThreadingType) ?? 1;
        local = PendingResultStore.Shared.Results.ToList();
        online = known.Select(e => StressBoard.ToPending(e)).ToList();
        Children.Add(body);
        Show();
        if (online.Count == 0 && NetworkMonitor.Shared.IsConnected) _ = LoadOnline();
    }

    private async Task LoadOnline()
    {
        try { online = (await Api.FetchLeaderboard()).Select(e => StressBoard.ToPending(e)).ToList(); }
        catch (Exception) { return; }   // offline: this PC's runs and the ones it was opened with are still there
        Show();
    }

    /// <summary>A run with its curve: an online run's stamps come from the server, once, the first time it is picked.</summary>
    private PendingTestResult Resolved(PendingTestResult r)
    {
        if (r.Stamps.Count > 0 || r.SessionId <= 0) return r;
        if (stamps.TryGetValue(r.SessionId, out var s)) return r with { Stamps = s };
        if (asked.Add(r.SessionId)) { inFlight.Add(r.SessionId); _ = Fetch(r.SessionId); }
        return r;
    }

    private async Task Fetch(int id)
    {
        try { stamps[id] = await Api.FetchStamps(id); failed.Remove(id); } catch (Exception) { failed.Add(id); }
        inFlight.Remove(id);
        Show();
    }

    /// <summary>An online run whose curve hasn't arrived: nothing can be said about it yet (no numbers are made up for it).</summary>
    private bool Waiting(PendingTestResult r) => r.SessionId > 0 && r.Stamps.Count == 0 && !stamps.ContainsKey(r.SessionId);

    private void Show()
    {
        var all = local.Concat(online).Where(r => (r.TestThreadingType ?? 1) == threading).ToList();
        if (preA != null && !all.Any(r => StressBoard.Same(r, preA))) all.Insert(0, preA);
        if (preB != null && !all.Any(r => StressBoard.Same(r, preB))) all.Insert(0, preB);
        runs = all;
        if (runs.Count == 0) { body.Content = Empty(); return; }
        a ??= runs[0];
        b ??= runs.Count > 1 ? runs[1] : runs[0];

        var runA = Resolved(a);
        var runB = Resolved(b);
        var cyan = DualStabilityChart.ColorA;
        var amber = DualStabilityChart.ColorB;
        bool single = threading == 0;
        var modeChip = Ui.Box(Ui.Text(single ? "1 THREAD" : "MULTI", 9, Forge.PhaseMeasured, Forge.W.Bold), Forge.PhaseMeasured.WithAlpha(0.15), 6);
        modeChip.Padding = new Thickness(7, 3, 7, 3);
        var titleRow = Ui.HStack(8, Ui.Text("⚖ COMPARISON ENGINE", 14, Colors.White, Forge.W.Black), modeChip);
        titleRow.Stretch = false;
        var title = Ui.HStack(8, Ui.VStack(4, titleRow, Ui.Text(single ? "Single Thread Benchmark" : "Multi Thread Benchmark", 10, Forge.Ink1)).Grow(),
            Ui.Button("✕ CLOSE", close, Colors.White.WithAlpha(0.1), Colors.White, 10, radius: 16, padV: 8));
        var pickers = Ui.HStack(12, Selector("RUN A", cyan, a, r => { a = r; Show(); }).Grow(),
            Selector("RUN B", amber, b, r => { b = r; Show(); }).Grow());
        if (Waiting(runA) || Waiting(runB))
        {
            bool lost = (Waiting(runA) && failed.Contains(runA.SessionId)) || (Waiting(runB) && failed.Contains(runB.SessionId));
            FrameworkElement note = lost
                ? Ui.VStack(8, Ui.Text("Couldn't load that run's data.", 10, Ramp.At(0.85), align: TextAlignment.Center),
                    Ui.Button("RETRY", () => { failed.Clear(); asked.Clear(); Show(); }, Forge.Interact, Forge.Ink0, 9).Align(HorizontalAlignment.Center))
                : Ui.Text("LOADING BOTH RUNS…", 10, Forge.Ink1, Forge.W.Bold, align: TextAlignment.Center);
            var waitCol = Ui.VStack(20, title, pickers, note.Pad(0, 30, 0, 0)).Pad(16);
            waitCol.MaxWidth = 860;
            body.Content = waitCol;
            return;
        }
        var an = ComparisonEngine.Analyze(runA, runB);
        FrameworkElement Metric(string t, string va, string vb, string? delta)
        {
            var deltaChip = delta == null ? null : Ui.Text(delta, 7, delta.StartsWith('+') ? Color.FromRgb(51, 230, 102) : Color.FromRgb(255, 89, 89), Forge.W.Bold);
            return Ui.Box(Ui.VStack(8, Ui.Text(t, 8, Forge.Ink1, Forge.W.Bold),
                Ui.VStack(2, Ui.Text("RUN A", 7, cyan, Forge.W.Bold), Ui.Text(va, 13, Colors.White, Forge.W.Bold)),
                Ui.Line(Colors.White.WithAlpha(0.07)),
                Ui.VStack(2, Ui.HStack(4, Ui.Text("RUN B", 7, amber, Forge.W.Bold), deltaChip), Ui.Text(vb, 13, Colors.White, Forge.W.Bold))),
                Colors.White.WithAlpha(0.04), 16, 12).Grow();
        }
        string Onset(int? s) => s is { } v ? $"{v}s" : "None";
        var metrics = Ui.Box(Ui.VStack(14, Ui.Text("⚡ COMPARATIVE DIAGNOSTICS", 10, Forge.Ink1, Forge.W.Bold),
            Ui.HStack(10, Metric("FINAL STABILITY", $"{runA.FinalStability:0.0}%", $"{runB.FinalStability:0.0}%", null),
                Metric("PEAK SCORE (IPS)", an.PeakA.ToString("F0"), an.PeakB.ToString("F0"), $"{an.PeakDeltaPct:+0.0;-0.0}%")),
            Ui.HStack(10, Metric("THROTTLE ONSET", Onset(an.OnsetA), Onset(an.OnsetB), null),
                Metric("AVG SCORE (IPS)", an.AvgA.ToString("F0"), an.AvgB.ToString("F0"), $"{an.AvgDeltaPct:+0.0;-0.0}%"))),
            Color.FromArgb(178, 20, 20, 20), 22, 16);
        var insights = Ui.Box(Ui.VStack(10, Ui.Text("ANALYTICAL INSIGHTS", 11, cyan, Forge.W.Bold), Ui.Text(an.Summary, 11, Colors.White.WithAlpha(0.85), wrap: true)),
            Color.FromRgb(28, 28, 28), 22, 18, cyan.WithAlpha(0.2));
        var col = Ui.VStack(20, title, pickers,
            new DualStabilityChart(ComparisonEngine.Points(runA), $"Run A ({runA.DeviceModel})", ComparisonEngine.Points(runB), $"Run B ({runB.DeviceModel})"),
            metrics, insights).Pad(16);
        col.MaxWidth = 860;
        body.Content = col;
    }

    private FrameworkElement Empty()
    {
        var col = Ui.VStack(16, Ui.Text("⚖", 44, Forge.Ink0, align: TextAlignment.Center),
            Ui.Text("UNIVERSAL RUN COMPARISON", 13, Colors.White, Forge.W.Black, align: TextAlignment.Center),
            Ui.Text("No matching test runs available.\nSingle Thread tests can only be compared with Single Thread,\nand Multi Thread with Multi Thread.",
                11, Forge.Ink1, wrap: true, align: TextAlignment.Center),
            Ui.Button("CLOSE", close, Colors.White.WithAlpha(0.1), Colors.White, 11, radius: 14, padV: 10).Align(HorizontalAlignment.Center)).Pad(40, 80, 40, 0);
        col.MaxWidth = 560;
        return col;
    }

    /// <summary>One run's card: its label, and a drop-down over every run that can be compared (this PC's, then the online board).</summary>
    private Grid Selector(string label, Color accent, PendingTestResult current, Action<PendingTestResult> choose)
    {
        var head = Ui.HStack(6, Ui.Text(label, 9, accent, Forge.W.Black),
            inFlight.Contains(current.SessionId) ? new Spinner(accent) { LayoutTransform = new ScaleTransform(0.35, 0.35) } : null);
        head.Stretch = false;
        foreach (FrameworkElement c in head.Children) c.VerticalAlignment = VerticalAlignment.Center;

        var arrow = Ui.Text("▼", 10, accent, Forge.W.Bold);
        var line = Ui.HStack(4, Ui.VStack(1, Ui.Text(current.Name, 11, Forge.Ink0, Forge.W.Bold),
            Ui.Text(current.SessionId > 0 ? "Global Leaderboard" : "Local Run", 8, Forge.Ink0.WithAlpha(0.4))).Grow(), arrow);
        line.Stretch = false;
        foreach (FrameworkElement c in line.Children) c.VerticalAlignment = VerticalAlignment.Center;

        var items = Ui.VStack(0);
        var scroller = new ScrollViewer
        {
            Content = items, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            MaxHeight = 340, Focusable = false,
        };
        var menu = Ui.Box(scroller, Forge.Raised, 8, 4, accent.WithAlpha(0.4));
        var popup = new Popup
        {
            Child = menu, Placement = PlacementMode.Bottom, StaysOpen = false, AllowsTransparency = true, PopupAnimation = PopupAnimation.Fade,
        };
        var lazy = new LazyRows(scroller, items);
        long closedAt = 0;
        // a click on the button while the menu is open closes it (the popup takes that mouse-down): it must not open again on the click
        var trigger = Ui.Button(line, () => { if (Environment.TickCount64 - closedAt > 250) popup.IsOpen = true; },
            Forge.Ink0.WithAlpha(0.06), 10, 10, 8, stroke: Forge.Ink0.WithAlpha(0.1));
        trigger.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        popup.PlacementTarget = trigger;

        FrameworkElement Item(int i)
        {
            var r = runs[i];
            bool on = StressBoard.Same(r, current);
            var text = Ui.VStack(1, Ui.Text($"{(r.SessionId > 0 ? "☁" : "⌂")} {r.Name}", 11, on ? accent : Forge.Ink0, on ? Forge.W.Bold : Forge.W.Regular),
                Ui.Text($"Stability: {r.FinalStability.F(1)}%", 9, Forge.Ink0.WithAlpha(0.4)));
            var button = Ui.Button(text, () => { popup.IsOpen = false; choose(r); }, on ? accent.WithAlpha(0.12) : Colors.Transparent, 6, 10, 7);
            button.HorizontalContentAlignment = HorizontalAlignment.Stretch;
            return button;
        }
        popup.Opened += (_, _) =>
        {
            menu.Width = Math.Max(260, trigger.ActualWidth);
            items.Children.Clear();
            lazy.Fill(runs.Count, Item);
        };
        popup.Closed += (_, _) => closedAt = Environment.TickCount64;

        var g = new Grid();
        g.Children.Add(Ui.Box(Ui.VStack(6, head, trigger), accent.WithAlpha(0.07), 18, 14, accent.WithAlpha(0.3)));
        g.Children.Add(popup);
        return g;
    }
}
