using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>Leaderboard tab: the benchmark board (default) or the sustained stress board — the phones' LeaderboardScreen.</summary>
public sealed class LeaderboardTab : Grid
{
    private int board;
    private readonly ContentControl tabs = new(), body = new();
    private readonly BenchmarkBoard benchmark = new();
    private readonly StressBoard stress = new();

    public LeaderboardTab()
    {
        Background = Brushes.Black;
        MaxWidth = 860;
        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        RowDefinitions.Add(new RowDefinition());
        Children.Add(tabs);
        SetRow(body, 1);
        Children.Add(body);
        Show(0);
    }

    public void Appeared() { if (board == 0) benchmark.LoadIfEmpty(); else stress.LoadIfEmpty(); }

    private void Show(int i)
    {
        board = i;
        var row = Ui.HStack(4);
        string[] labels = ["BENCHMARK", "STRESS TEST"];
        for (int k = 0; k < 2; k++)
        {
            int kk = k;
            row.Children.Add(Ui.Button(labels[k], () => Show(kk), board == k ? Forge.PhaseMeasured : Colors.Transparent,
                board == k ? Forge.Bg : Forge.Ink1, 10, Forge.W.Black, 9, 8).Grow());
        }
        var seg = Ui.Box(row, Forge.Surface, 12, 4, Forge.Hairline);
        seg.Margin = new Thickness(16, 14, 16, 0);
        tabs.Content = seg;
        body.Content = board == 0 ? benchmark : stress;
        Appeared();
    }

    /// <summary>A search field with a hint, in the forge style.</summary>
    public static (FrameworkElement view, TextBox box) Search(string hint, Action<string> changed)
    {
        var box = new TextBox { FontFamily = Forge.Mono().Item1, FontSize = 11, Foreground = Forge.Ink0.Brush(), VerticalContentAlignment = VerticalAlignment.Center };
        var hintText = Ui.Text(hint, 11, Forge.Ink0.WithAlpha(0.3));
        hintText.IsHitTestVisible = false;
        box.TextChanged += (_, _) => { hintText.Visibility = box.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed; changed(box.Text); };
        var g = new Grid();
        g.Children.Add(hintText);
        g.Children.Add(box);
        var row = Ui.HStack(8, Ui.Text("⌕", 13, Forge.Ink1), g.Grow());
        var b = Ui.Box(row, Forge.Ink0.WithAlpha(0.04), 12, 0, Forge.Ink0.WithAlpha(0.08));
        b.Padding = new Thickness(12, 9, 12, 9);
        return (b, box);
    }
}

/// <summary>Best benchmark score per machine (phones, iPhones, Macs and PCs), highest first.</summary>
public sealed class BenchmarkBoard : Grid
{
    private List<BenchmarkDto> entries = [];
    private string query = "", error = "";
    private bool loading, loaded;
    private readonly Flex list = Ui.VStack(8);
    private readonly Grid popup = new();

    public BenchmarkBoard()
    {
        var refresh = Ui.Button(Ui.Text("↻", 18, Forge.Ink0, Forge.W.Bold), () => _ = Load(), Colors.Transparent, 8, 6, 2);
        var head = Ui.HStack(0, Ui.VStack(2, Ui.Text("BENCHMARK", 18, Forge.Ink0, Forge.W.Black),
            Ui.Text("All six sections · best verified run per device", 9, Forge.Ink0.WithAlpha(0.4))), Ui.Spacer(), refresh);
        var (search, _) = LeaderboardTab.Search("Search device, chip or GPU…", q => { query = q; Render(); });
        Children.Add(Ui.Scroll(Ui.VStack(8, head, search, list).Pad(16, 12)));
        Children.Add(popup);
    }

    public void LoadIfEmpty() { if (!loaded && !loading) _ = Load(); }

    private async Task Load()
    {
        if (!NetworkMonitor.Shared.IsConnected) { error = "Offline"; Render(); return; }
        loading = true; error = ""; Render();
        try { entries = (await BenchmarkUpload.FetchLeaderboard()).OrderByDescending(e => e.TotalScore).ToList(); loaded = true; }
        catch (Exception e) { error = e.Message; }
        loading = false;
        Render();
    }

    private Guid? submittingId;
    private string? submitNote;

    /// <summary>This PC's verified runs that haven't reached the leaderboard (made offline, or not submitted).</summary>
    private void Unsubmitted()
    {
        var runs = BenchmarkHistoryStore.Shared.Records
            .Where(r => r.Version == BenchmarkScore.Version && r.Complete && r.ValidityFlags == 0 && !r.Submitted).Reverse().ToList();
        if (runs.Count == 0) return;
        var amber = Ramp.At(0.5);
        list.Children.Add(Ui.Text($"YOUR UNSUBMITTED RUNS ({runs.Count})", 11, amber, Forge.W.Black));
        foreach (var r in runs)
        {
            FrameworkElement action = submittingId == r.Id ? new Spinner(Forge.PhasePreFlight)
                : NetworkMonitor.Shared.IsConnected ? Ui.Button("SUBMIT", () => _ = Submit(r), Forge.PhaseMeasured, Forge.Bg, 9, padV: 6)
                : Ui.Text("TURN ON INTERNET TO SUBMIT", 8, amber, Forge.W.Bold);
            var row = Ui.HStack(8, Ui.VStack(2, Ui.Text(r.Score.N0(), 15, Forge.Ink0, Forge.W.Black),
                Ui.Text(r.Timestamp.ToString("d MMM yyyy HH:mm"), 8, Forge.Ink2)).Grow(), action.Align(HorizontalAlignment.Right, VerticalAlignment.Center));
            list.Children.Add(Ui.Box(row, amber.WithAlpha(0.06), 14, 12, amber.WithAlpha(0.25)));
        }
        if (submitNote != null) list.Children.Add(Ui.Text(submitNote, 9, Forge.Ink1, wrap: true));
    }

    private async Task Submit(BenchmarkRecord r)
    {
        submittingId = r.Id; Render();
        try
        {
            var res = await BenchmarkUpload.Submit(r);
            BenchmarkHistoryStore.Shared.MarkSubmitted(r.Id);
            submitNote = $"Submitted · {res.Score:N0} points · rank #{res.Rank}";
        }
        catch (Exception e) { submitNote = e.Message; }
        submittingId = null;
        await Load();
    }

    private void Render()
    {
        list.Children.Clear();
        Unsubmitted();
        if (loading) { list.Children.Add(new Spinner(Forge.PhasePreFlight).Align(HorizontalAlignment.Center).Pad(40)); return; }
        if (error.Length > 0)
        {
            list.Children.Add(Ui.VStack(8, Ui.Text($"⚠ {error}", 12, Ramp.At(0.85), Forge.W.Bold, wrap: true, align: TextAlignment.Center),
                Ui.Button("RETRY", () => _ = Load(), Forge.Ink0.WithAlpha(0.1), Forge.Ink0).Align(HorizontalAlignment.Center)).Pad(30));
            return;
        }
        string q = query.Trim().ToLowerInvariant();
        var shown = entries.Select((e, i) => (rank: i + 1, e)).Where(x => q.Length == 0 || x.e.DeviceModel.ToLowerInvariant().Contains(q)
            || x.e.DeviceManufacturer.ToLowerInvariant().Contains(q) || (x.e.GpuName?.ToLowerInvariant().Contains(q) ?? false)
            || (x.e.SocModel?.ToLowerInvariant().Contains(q) ?? false)).ToList();
        if (shown.Count == 0)
        {
            list.Children.Add(Ui.Text(entries.Count == 0 ? "No benchmark runs yet — be the first." : "No devices match.", 11, Forge.Ink0.WithAlpha(0.4),
                align: TextAlignment.Center).Pad(30));
            return;
        }
        int top = Math.Max(entries[0].TotalScore, 1);
        foreach (var (rank, e) in shown.Take(200))
            list.Children.Add(Row(rank, e, (double)e.TotalScore / top, string.Equals(e.DeviceModel, DeviceInfo.Model, StringComparison.OrdinalIgnoreCase)));
    }

    private FrameworkElement Row(int rank, BenchmarkDto e, double fraction, bool mine)
    {
        var rankText = Ui.Text($"#{rank}", 11, rank <= 3 ? Ramp.At(1 - (rank - 1) * 0.2) : Forge.Ink2, Forge.W.Black).Size(40);
        var name = Ui.VStack(2, Ui.Text(e.Name, 12, Forge.Ink0, Forge.W.Bold),
            Ui.Text(string.Join(" · ", new[] { e.SocModel, e.GpuName, DeviceInfo.OsLabel(e.Os, e.OsVersion) }.Where(s => !string.IsNullOrEmpty(s))), 8, Forge.Ink0.WithAlpha(0.4)));
        var score = Ui.VStack(2, Ui.Text(e.TotalScore.N0(), 15, Forge.Ink0, Forge.W.Black, align: TextAlignment.Right),
            Ui.Text($"CPU {e.CpuScore:N0} · MEM {e.MemScore:N0} · GPU {e.Score:N0} · UX {e.UxScore:N0} · AI {e.AiScore:N0} · LLM {e.LlmScore:N0} · " +
                    (e.RayTracing ? $"RT {e.Test3Fps:0} FPS" : "NO RT"), 7, Forge.Ink0.WithAlpha(0.4), align: TextAlignment.Right));
        var top = Ui.HStack(8, rankText, name.Grow(), score);
        top.Stretch = false;
        foreach (FrameworkElement c in top.Children) c.VerticalAlignment = VerticalAlignment.Center;
        var bar = new ScoreBar(fraction);
        var b = Ui.Box(Ui.VStack(8, top, bar), Forge.Surface, 14, 12, mine ? Forge.PhaseMeasured.WithAlpha(0.6) : Forge.Ink0.WithAlpha(0.06));
        b.Cursor = Cursors.Hand;
        b.MouseLeftButtonUp += (_, _) => OpenDetail(e);
        return b;
    }

    // ── one run's numbers ──

    private void OpenDetail(BenchmarkDto e)
    {
        var window = Window.GetWindow(this);
        KeyEventHandler? esc = null;
        void Close() { popup.Children.Clear(); if (window != null && esc != null) window.PreviewKeyDown -= esc; }
        esc = (_, k) => { if (k.Key == Key.Escape) { Close(); k.Handled = true; } };
        if (window != null) window.PreviewKeyDown += esc;
        var content = new ContentControl();
        var close = Ui.Button(Ui.Text("✕", 12, Forge.Ink0, Forge.W.Bold), Close, Colors.White.WithAlpha(0.08), 16, 10, 6).Align(HorizontalAlignment.Right, VerticalAlignment.Top);
        var head = Ui.HStack(10, Ui.VStack(2, Ui.Text(e.Name, 16, Forge.Ink0, Forge.W.Black),
            Ui.Text(string.Join(" · ", new[] { e.SocModel, e.GpuName, DeviceInfo.OsLabel(e.Os, e.OsVersion) }.Where(s => !string.IsNullOrEmpty(s))), 10, Forge.Ink1, wrap: true)).Grow(),
            Ui.VStack(2, Ui.Text(e.TotalScore.N0(), 22, Forge.Ink0, Forge.W.Black, align: TextAlignment.Right),
                Ui.Text("TOTAL", 8, Forge.Ink1, Forge.W.Bold, align: TextAlignment.Right)), close);
        var card = Ui.Box(Ui.VStack(16, head, Ui.Line(Colors.White.WithAlpha(0.1)), content), Forge.Raised, 24, 24, Colors.White.WithAlpha(0.1));
        card.MaxWidth = 760;
        card.Margin = new Thickness(30);
        card.VerticalAlignment = VerticalAlignment.Center;
        var scrim = new Grid { Background = Color.FromArgb(217, 0, 0, 0).Brush() };
        scrim.MouseLeftButtonUp += (s, a) => { if (a.OriginalSource == scrim) Close(); };
        scrim.Children.Add(Ui.Scroll(card));
        popup.Children.Clear();
        popup.Children.Add(scrim);
        Overlays.FadeIn(card);
        _ = LoadDetail(e, content);
    }

    private async Task LoadDetail(BenchmarkDto e, ContentControl content)
    {
        FrameworkElement Message(string text, bool retry) => Ui.VStack(10, Ui.Text(retry ? $"⚠ {text}" : text, 12, retry ? Ramp.At(0.85) : Forge.Ink1, Forge.W.Bold, wrap: true,
            align: TextAlignment.Center), retry ? Ui.Button("RETRY", () => _ = LoadDetail(e, content), Forge.Ink0.WithAlpha(0.1), Forge.Ink0).Align(HorizontalAlignment.Center) : null).Pad(30);
        content.Content = new Spinner(Forge.PhasePreFlight).Align(HorizontalAlignment.Center).Pad(60);
        if (!NetworkMonitor.Shared.IsConnected) { content.Content = Message("Offline", true); return; }
        try
        {
            var d = await BenchmarkUpload.FetchDetail(e.Id);
            content.Content = d == null ? Message("This run is no longer on the board", false) : DetailBody(d);
        }
        catch (Exception ex) { content.Content = Message(ex.Message, true); }
    }

    private static FrameworkElement DetailBody(BenchmarkDetail d)
    {
        var meta = string.Join(" · ", new[] { d.AppVersion is { } v ? $"app {v}" : null, d.OsBuild, d.CreatedAt.ToLocalTime().ToString("d MMM yyyy HH:mm") }.Where(s => !string.IsNullOrEmpty(s)));
        var col = Ui.VStack(14, Ui.Text(meta, 9, Forge.Ink2, wrap: true));
        foreach (var s in d.Sections)
        {
            var box = Ui.VStack(10, Ui.HStack(8, Ui.Text(s.Title, 12, Forge.Ink0, Forge.W.Black), Ui.Spacer(), Ui.Text(s.Score.N0(), 14, Forge.PhaseMeasured, Forge.W.Black)));
            foreach (var g in s.Groups)
            {
                box.Children.Add(Ui.Line(Forge.Hairline));
                box.Children.Add(Ui.HStack(8, Ui.Text(g.Title.ToUpperInvariant(), 9, Forge.Ink1, Forge.W.Bold), Ui.Spacer(),
                    g.Points is { } p ? Ui.Text($"{p.N0()} PTS", 9, Forge.Ink1, Forge.W.Bold) : null));
                foreach (var r in g.Rows)
                    box.Children.Add(Ui.HStack(12, Ui.Text(r.Label, 10, Forge.Ink1), Ui.Text(r.Value, 10, Forge.Ink0, Forge.W.Bold, wrap: true, align: TextAlignment.Right).Grow()));
            }
            col.Children.Add(Ui.Box(box, Colors.White.WithAlpha(0.03), 16, 14, Forge.Hairline));
        }
        return col;
    }

    private sealed class ScoreBar(double fraction) : FrameworkElement
    {
        protected override Size MeasureOverride(Size s) => new(0, 4);
        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth;
            dc.DrawRoundedRectangle(Forge.Ink0.WithAlpha(0.06).Brush(), null, new Rect(0, 0, w, 4), 2, 2);
            dc.DrawRoundedRectangle(new LinearGradientBrush(Forge.PhaseCalibration, Ramp.At(0.85), 0), null,
                new Rect(0, 0, w * Math.Clamp(fraction, 0.02, 1), 4), 2, 2);
        }
    }
}

/// <summary>The sustained stress board: pending local runs, filters, search, the run's curve, and comparing two runs.</summary>
public sealed class StressBoard : Grid
{
    private List<HammerDto> entries = [];
    private string query = "", error = "";
    private bool loading, loaded, myDevice, myOs;
    private int? platform, durationFilter;
    private readonly Flex list = Ui.VStack(10);
    private readonly ContentControl filters = new(), compareBar = new();
    private readonly Grid popup = new();
    private readonly List<PendingTestResult> compare = [];
    private string? uploadError;
    private Guid? uploading;

    public StressBoard()
    {
        var refresh = Ui.Button(Ui.Text("↻", 18, Forge.Ink0, Forge.W.Bold), () => _ = Load(), Colors.Transparent, 8, 6, 2);
        var head = Ui.HStack(0, Ui.VStack(2, Ui.Text("GLOBAL LEADERBOARD", 18, Forge.Ink0, Forge.W.Black),
            Ui.Text("Select 2 tests to compare side by side", 10, Forge.Ink1)), Ui.Spacer(), refresh);
        var (search, _) = LeaderboardTab.Search("Search model, vendor, or OS…", q => { query = q; Render(); });
        Children.Add(Ui.Scroll(Ui.VStack(10, head, search, filters, list).Pad(16, 12, 16, 80)));
        compareBar.VerticalAlignment = VerticalAlignment.Bottom;
        compareBar.Margin = new Thickness(16);
        Children.Add(compareBar);
        Children.Add(popup);
        PendingResultStore.Shared.Changed += Render;
        BuildFilters();
    }

    public void LoadIfEmpty() { if (!loaded && !loading) _ = Load(); else Render(); }

    private async Task Load()
    {
        loading = true; error = ""; Render();
        try { entries = await Api.FetchLeaderboard(); loaded = true; }
        catch (Exception e) { error = e.Message; }
        loading = false;
        Render();
    }

    private static string PlatformName(int? p) => p switch { 1 => "iOS", 2 => "Android", 3 => "macOS", 4 => "Windows", _ => "All" };
    private static string DurationName(int t) => t switch { 1 => "15 MIN", 2 => "30 MIN", _ => "5 MIN" };

    private void BuildFilters()
    {
        var row = new WrapPanel();
        void Chip(bool selected, string label, Action toggle)
        {
            var amber = Color.FromRgb(242, 178, 26);
            var b = Ui.Button(label, () => { toggle(); BuildFilters(); Render(); }, selected ? amber.WithAlpha(0.2) : Colors.White.WithAlpha(0.04),
                selected ? amber : Colors.White.WithAlpha(0.6), 9, Forge.W.Bold, 8, 6, stroke: selected ? amber : Colors.White.WithAlpha(0.08));
            b.Margin = new Thickness(0, 0, 8, 6);
            row.Children.Add(b);
        }
        Chip(myDevice, $"Device: {DeviceInfo.Model}", () => myDevice = !myDevice);
        Chip(myOs, $"OS: Windows {DeviceInfo.OsVersion}", () => myOs = !myOs);
        Chip(platform != null, $"Platform: {PlatformName(platform)}", () => platform = platform switch { null => 4, 4 => 2, 2 => 1, 1 => 3, _ => null });
        Chip(durationFilter != null, $"Duration: {(durationFilter is { } d ? DurationName(d) : "All")}",
            () => durationFilter = durationFilter switch { null => 0, 0 => 1, 1 => 2, _ => null });
        filters.Content = row;
    }

    private void Render()
    {
        list.Children.Clear();
        PendingSection();
        list.Children.Add(Ui.Text("ONLINE RANKINGS", 11, Forge.Ink1, Forge.W.Bold).Pad(0, 4));
        if (loading) { list.Children.Add(new Spinner(Forge.PhasePreFlight).Align(HorizontalAlignment.Center).Pad(30)); }
        else if (error.Length > 0)
        {
            list.Children.Add(Ui.VStack(10, Ui.Text("COULD NOT LOAD LEADERBOARD", 12, Forge.Ink0, Forge.W.Bold, align: TextAlignment.Center),
                Ui.Text(error, 10, Forge.Ink1, wrap: true, align: TextAlignment.Center),
                Ui.Button("RETRY", () => _ = Load(), Colors.White, Colors.Black, 11).Align(HorizontalAlignment.Center)).Pad(30));
        }
        else
        {
            var ranked = entries.OrderByDescending(e => e.StabilityPercentage).Select((e, i) => (rank: i + 1, e)).ToList();
            string q = query.Trim().ToLowerInvariant();
            var shown = ranked.Where(x =>
                (q.Length == 0 || x.e.DeviceModel.ToLowerInvariant().Contains(q) || x.e.DeviceManufacturer.ToLowerInvariant().Contains(q) || x.e.OsVersion.ToLowerInvariant().Contains(q))
                && (!myDevice || string.Equals(x.e.DeviceModel, DeviceInfo.Model, StringComparison.OrdinalIgnoreCase))
                && (!myOs || x.e.Os == DeviceInfo.OsPlatform && x.e.OsVersion.Contains(DeviceInfo.OsVersion))
                && (platform == null || x.e.Os == platform)
                && (durationFilter == null || x.e.Type == durationFilter)).Take(300).ToList();
            if (shown.Count == 0) list.Children.Add(Ui.Text("NO ENTRIES FOUND", 11, Forge.Ink1, Forge.W.Bold, align: TextAlignment.Center).Pad(0, 40, 0, 0));
            foreach (var (rank, e) in shown) list.Children.Add(Row(rank, e));
        }
        RenderCompareBar();
    }

    private static PendingTestResult ToPending(HammerDto e, List<DeviceHammerStamp>? stamps = null) => new()
    {
        Timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
        DurationSeconds = e.Type == 0 ? 300 : e.Type == 1 ? 900 : 1800,
        TestDurationType = e.Type, TestThreadingType = e.TestThreadingType,
        MinStability = e.StabilityPercentage, FinalStability = e.StabilityPercentage,
        Stamps = stamps ?? [], DeviceModel = e.DeviceModel, DeviceManufacturer = e.DeviceManufacturer, OsVersion = e.OsVersion,
        SessionId = e.Id,
    };

    private static bool Same(PendingTestResult a, PendingTestResult b) => a.SessionId > 0 && b.SessionId > 0 ? a.SessionId == b.SessionId : a.Id == b.Id;

    private void ToggleCompare(PendingTestResult run)
    {
        int i = compare.FindIndex(r => Same(r, run));
        if (i >= 0) compare.RemoveAt(i);
        else
        {
            if (compare.Count > 0 && (compare[0].TestThreadingType ?? 1) != (run.TestThreadingType ?? 1))
            {
                string A(int? t) => (t ?? 1) == 0 ? "Single Thread" : "Multi Thread";
                MessageBox.Show(Window.GetWindow(this)!, $"Cannot compare {A(compare[0].TestThreadingType)} with {A(run.TestThreadingType)} test! Please select matching test types.",
                    "Comparison Mismatch");
                return;
            }
            compare.Add(run);
            if (compare.Count >= 2) { OpenComparison(); }
        }
        Render();
    }

    private void RenderCompareBar()
    {
        if (compare.Count == 0) { compareBar.Content = null; return; }
        string mode = (compare[0].TestThreadingType ?? 1) == 0 ? "1 THREAD" : "MULTI THREAD";
        var text = Ui.VStack(2, Ui.Text($"{compare.Count} / 2 TESTS SELECTED ({mode})", 10, Colors.Black, Forge.W.Black),
            Ui.Text(compare.Count == 1 ? $"Pick 1 more {mode} test to compare" : "Click to open the side-by-side analysis", 9, Colors.Black.WithAlpha(0.7)));
        var row = Ui.HStack(8, text.Grow(), Ui.Button("CLEAR", () => { compare.Clear(); Render(); }, Colors.Black.WithAlpha(0.2), Colors.Black, 9, radius: 12, padV: 4));
        var b = Ui.Box(row, Forge.PhaseMeasured, 16, 14);
        b.Cursor = Cursors.Hand;
        b.MouseLeftButtonUp += (_, _) => { if (compare.Count >= 2) OpenComparison(); };
        compareBar.Content = b;
    }

    private void PendingSection()
    {
        var pending = PendingResultStore.Shared.Results;
        if (pending.Count == 0) return;
        var amber = Color.FromRgb(242, 178, 26);
        list.Children.Add(Ui.Text($"PENDING OFFLINE RUNS ({pending.Count})", 11, amber, Forge.W.Black).Pad(0, 4));
        foreach (var p in pending)
        {
            bool selected = compare.Any(r => r.Id == p.Id);
            string thread = (p.TestThreadingType ?? 1) == 0 ? "1 THREAD" : "MULTI";
            var top = Ui.HStack(8,
                Ui.VStack(2, Ui.Text($"OFFLINE RUN · {DurationName(p.TestDurationType)} · {thread}", 9, amber, Forge.W.Black),
                    Ui.Text($"{p.DeviceManufacturer} {p.DeviceModel}", 13, Forge.Ink0, Forge.W.Bold)).Grow(),
                Ui.VStack(2, Ui.Text($"{p.FinalStability:0.0}%", 14, StabilityChart.ScoreColor(p.FinalStability), Forge.W.Black, align: TextAlignment.Right),
                    Ui.Text("STABILITY", 7, Forge.Ink1, Forge.W.Bold, align: TextAlignment.Right)));
            var actions = Ui.HStack(8, Ui.Button(selected ? "✓" : "⚖", () => ToggleCompare(p), selected ? Forge.PhaseMeasured : Colors.White.WithAlpha(0.08),
                selected ? Colors.Black : Forge.PhaseMeasured, 11, padV: 6));
            if (!ValidityFlag.Submittable(p.ValidityFlags ?? 0))
                actions.Children.Add(Ui.Text("NOT VERIFIED", 9, Ramp.At(0.5), Forge.W.Bold).Align(HorizontalAlignment.Left, VerticalAlignment.Center));
            else if (NetworkMonitor.Shared.IsConnected)
                actions.Children.Add(Ui.Button(uploading == p.Id ? "SUBMITTING…" : "SUBMIT", () => _ = Submit(p), Forge.PhasePreFlight, Colors.White, 9, padV: 6,
                    enabled: uploading == null));
            else
                actions.Children.Add(Ui.Text("TURN ON INTERNET TO SUBMIT", 9, Ramp.At(0.5), Forge.W.Bold).Align(HorizontalAlignment.Left, VerticalAlignment.Center));
            actions.Children.Add(Ui.Spacer());
            actions.Children.Add(Ui.Button("DELETE", () => PendingResultStore.Shared.Delete(p.Id), Colors.Transparent, Ramp.At(0.85), 9, padV: 6));
            var col = Ui.VStack(10, top, actions);
            if (uploadError != null && uploading == null) col.Children.Add(Ui.Text(uploadError, 8, Ramp.At(0.85), wrap: true));
            list.Children.Add(Ui.Box(col, amber.WithAlpha(0.06), 16, 14, amber.WithAlpha(0.2)));
        }
        list.Children.Add(Ui.Line(Colors.White.WithAlpha(0.08)).Pad(0, 8));
    }

    private async Task Submit(PendingTestResult p)
    {
        uploading = p.Id; uploadError = null; Render();
        try { await Api.SubmitStress(p); PendingResultStore.Shared.Delete(p.Id); }
        catch (Exception e) { uploadError = e.Message; }
        uploading = null;
        Render();
        _ = Load();
    }

    private FrameworkElement Row(int rank, HammerDto e)
    {
        var run = ToPending(e);
        bool selected = compare.Any(r => Same(r, run));
        bool single = (e.TestThreadingType ?? 1) == 0;
        var badge = rank <= 3
            ? (FrameworkElement)new Medal(rank)
            : Ui.Box(Ui.Text($"#{rank}", 9, Forge.Ink1, Forge.W.Bold).Align(HorizontalAlignment.Center, VerticalAlignment.Center), Colors.White.WithAlpha(0.04), 8).Size(28, 28);
        var info = Ui.VStack(2, Ui.Text($"{e.DeviceManufacturer} {e.DeviceModel}", 12, Forge.Ink0, Forge.W.Bold),
            Ui.HStack(4, Ui.Text(DeviceInfo.OsLabel(e.Os, e.OsVersion), 8, Forge.Ink1), Ui.Text("•", 8, Forge.Ink1),
                Ui.Text(single ? "1 THREAD" : "MULTI", 8, single ? Colors.Orange : Colors.LimeGreen, Forge.W.Bold)));
        var score = Ui.VStack(2, Ui.Text($"{e.StabilityPercentage:0.0}%", 13, StabilityChart.ScoreColor(e.StabilityPercentage), Forge.W.Black, align: TextAlignment.Right),
            Ui.Text("STABILITY", 7, Forge.Ink1, Forge.W.Bold, align: TextAlignment.Right));
        var open = Ui.HStack(10, badge, info.Grow(), score);
        open.Stretch = false;
        foreach (FrameworkElement c in open.Children) c.VerticalAlignment = VerticalAlignment.Center;
        var openButton = Ui.Button(open, () => OpenDetail(e), Colors.Transparent, 8, 0, 0);
        openButton.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        var compareButton = Ui.Button(selected ? "✓" : "⚖", () => ToggleCompare(run), selected ? Forge.PhaseMeasured : Colors.White.WithAlpha(0.05),
            selected ? Colors.Black : Forge.PhaseMeasured, 12, padV: 8, stroke: selected ? Forge.PhaseMeasured : Colors.White.WithAlpha(0.15));
        var row = Ui.HStack(10, compareButton, openButton.Grow());
        row.Stretch = false;
        foreach (FrameworkElement c in row.Children) c.VerticalAlignment = VerticalAlignment.Center;
        return Ui.Box(row, Color.FromArgb(153, 20, 20, 20), 16, 12, selected ? Forge.PhaseMeasured : Colors.White.WithAlpha(0.06));
    }

    private sealed class Medal(int rank) : FrameworkElement
    {
        protected override Size MeasureOverride(Size s) => new(28, 28);
        protected override void OnRender(DrawingContext dc)
        {
            var (top, bottom) = rank switch
            {
                1 => (Color.FromRgb(250, 204, 51), Color.FromRgb(230, 166, 0)),
                2 => (Color.FromRgb(217, 217, 217), Color.FromRgb(166, 166, 166)),
                _ => (Color.FromRgb(204, 128, 77), Color.FromRgb(153, 89, 51)),
            };
            dc.DrawEllipse(new LinearGradientBrush(top, bottom, 90), null, new Point(14, 14), 14, 14);
            dc.DrawText(rank.ToString(), 11, Colors.Black, new Point(14, 14), 0.5, 0.5, Forge.W.Black);
        }
    }

    // ── the run's curve ──

    private void OpenDetail(HammerDto e)
    {
        var content = new ContentControl { Content = new Spinner(Forge.PhasePreFlight).Align(HorizontalAlignment.Center).Pad(60) };
        void Close() => popup.Children.Clear();
        var close = Ui.Button(Ui.Text("✕", 12, Forge.Ink0, Forge.W.Bold), Close, Colors.White.WithAlpha(0.08), 16, 10, 6);
        var head = Ui.HStack(8, Ui.VStack(2, Ui.Text($"{e.DeviceManufacturer} {e.DeviceModel}", 16, Forge.Ink0, Forge.W.Black),
            Ui.Text($"{e.DeviceManufacturer} • {DeviceInfo.OsLabel(e.Os, e.OsVersion)}", 10, Forge.Ink1)).Grow(), close);
        var card = Ui.Box(Ui.VStack(16, head, Ui.Line(Colors.White.WithAlpha(0.1)), content), Color.FromRgb(26, 26, 26), 24, 24, Colors.White.WithAlpha(0.1));
        card.MaxWidth = 760;
        card.Margin = new Thickness(30);
        card.VerticalAlignment = VerticalAlignment.Center;
        var scrim = new Grid { Background = Color.FromArgb(217, 0, 0, 0).Brush() };
        scrim.MouseLeftButtonUp += (s, a) => { if (a.OriginalSource == scrim) Close(); };
        scrim.Children.Add(Ui.Scroll(card));
        popup.Children.Clear();
        popup.Children.Add(scrim);
        Overlays.FadeIn(card);
        _ = LoadDetail(e, content);
    }

    private async Task LoadDetail(HammerDto e, ContentControl content)
    {
        try
        {
            var stamps = await Api.FetchStamps(e.Id);
            double max = stamps.Count > 0 ? stamps.Max(s => s.Score) : 1, min = stamps.Count > 0 ? stamps.Min(s => s.Score) : 0;
            var points = stamps.Select(s => new StabilityPoint(s.ElapsedMs / 1000.0, s.Score / Math.Max(1, max) * 100)).ToList();
            var events = new List<(double, ThermalLevel)>();
            int? last = null;
            foreach (var s in stamps)
                if (s.ThermalState != last) { events.Add((s.ElapsedMs / 1000.0, (ThermalLevel)Math.Clamp(s.ThermalState, 0, 3))); last = s.ThermalState; }
            FrameworkElement R(string l, string v, Color? c = null) => Ui.HStack(0, Ui.Text(l, 10, Forge.Ink1, Forge.W.Bold), Ui.Spacer(), Ui.Text(v, 12, c ?? Forge.Ink0, Forge.W.Bold));
            var rows = Ui.Box(Ui.VStack(12, R("TEST TYPE", DurationName(e.Type)), R("STABILITY", $"{e.StabilityPercentage:0.0}%", StabilityChart.ScoreColor(e.StabilityPercentage)),
                R("PEAK SCORE (IPS)", ((long)max).ToString("N0")), R("MIN SCORE (IPS)", ((long)min).ToString("N0")), R("SAMPLES RECORDED", stamps.Count.ToString())),
                Colors.White.WithAlpha(0.03), 16, 14);
            var add = Ui.Button("⚖ ADD TO COMPARISON", () => { popup.Children.Clear(); ToggleCompare(ToPending(e, stamps)); },
                Forge.PhaseMeasured.WithAlpha(0.12), Forge.PhaseMeasured, 11, Forge.W.Bold, 14, 12, stroke: Forge.PhaseMeasured.WithAlpha(0.3));
            content.Content = Ui.VStack(20, rows, Ui.Text("PERFORMANCE CURVE", 10, Forge.Ink1, Forge.W.Black), new StabilityChart(points, events), add);
        }
        catch (Exception ex)
        {
            content.Content = Ui.VStack(12, Ui.Text("FAILED TO LOAD STAMPS", 11, Forge.Ink0, Forge.W.Bold, align: TextAlignment.Center),
                Ui.Text(ex.Message, 9, Forge.Ink1, wrap: true, align: TextAlignment.Center),
                Ui.Button("RETRY", () => _ = LoadDetail(e, content), Colors.White, Colors.Black, 10).Align(HorizontalAlignment.Center)).Pad(40);
        }
    }

    // ── two runs side by side ──

    private void OpenComparison()
    {
        var a = compare[0];
        var b = compare[1];
        void Close() { popup.Children.Clear(); compare.Clear(); Render(); }
        var view = new ContentControl();
        var scrim = new Grid { Background = Brushes.Black };
        scrim.Children.Add(Ui.Scroll(view));
        popup.Children.Clear();
        popup.Children.Add(scrim);
        Overlays.FadeIn(scrim, 1);
        _ = FillComparison(a, b, view, Close);
    }

    private static async Task<PendingTestResult> WithStamps(PendingTestResult r)
    {
        if (r.Stamps.Count > 0 || r.SessionId <= 0) return r;
        try { return r with { Stamps = await Api.FetchStamps(r.SessionId) }; } catch (Exception) { return r; }
    }

    private static async Task FillComparison(PendingTestResult rawA, PendingTestResult rawB, ContentControl view, Action close)
    {
        view.Content = new Spinner(Forge.PhaseMeasured).Align(HorizontalAlignment.Center).Pad(80);
        var a = await WithStamps(rawA);
        var b = await WithStamps(rawB);
        var an = ComparisonEngine.Analyze(a, b);
        var cyan = DualStabilityChart.ColorA;
        var amber = DualStabilityChart.ColorB;
        bool single = (a.TestThreadingType ?? 1) == 0;
        var modeChip = Ui.Box(Ui.Text(single ? "1 THREAD" : "MULTI", 9, Forge.PhaseMeasured, Forge.W.Bold), Forge.PhaseMeasured.WithAlpha(0.15), 6);
        modeChip.Padding = new Thickness(7, 3, 7, 3);
        var titleRow = Ui.HStack(8, Ui.Text("⚖ COMPARISON ENGINE", 14, Colors.White, Forge.W.Black), modeChip);
        titleRow.Stretch = false;
        var title = Ui.HStack(8, Ui.VStack(4, titleRow, Ui.Text(single ? "Single Thread Benchmark" : "Multi Thread Benchmark", 10, Forge.Ink1)).Grow(),
            Ui.Button("✕ CLOSE", close, Colors.White.WithAlpha(0.1), Colors.White, 10, radius: 16, padV: 8));
        FrameworkElement Picked(string label, Color c, PendingTestResult r) => Ui.Box(Ui.VStack(6, Ui.Text(label, 9, c, Forge.W.Black),
            Ui.Text($"{r.DeviceManufacturer} {r.DeviceModel}", 12, Forge.Ink0, Forge.W.Bold),
            Ui.Text($"OS {r.OsVersion}", 9, Forge.Ink1)), c.WithAlpha(0.07), 18, 14, c.WithAlpha(0.3)).Grow();
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
            Ui.HStack(10, Metric("FINAL STABILITY", $"{a.FinalStability:0.0}%", $"{b.FinalStability:0.0}%", null),
                Metric("PEAK SCORE (IPS)", an.PeakA.ToString("F0"), an.PeakB.ToString("F0"), $"{an.PeakDeltaPct:+0.0;-0.0}%")),
            Ui.HStack(10, Metric("THROTTLE ONSET", Onset(an.OnsetA), Onset(an.OnsetB), null),
                Metric("AVG SCORE (IPS)", an.AvgA.ToString("F0"), an.AvgB.ToString("F0"), $"{an.AvgDeltaPct:+0.0;-0.0}%"))),
            Color.FromArgb(178, 20, 20, 20), 22, 16);
        var insights = Ui.Box(Ui.VStack(10, Ui.Text("ANALYTICAL INSIGHTS", 11, cyan, Forge.W.Bold), Ui.Text(an.Summary, 11, Colors.White.WithAlpha(0.85), wrap: true)),
            Color.FromRgb(28, 28, 28), 22, 18, cyan.WithAlpha(0.2));
        var col = Ui.VStack(20, title, Ui.HStack(12, Picked("RUN A", cyan, a), Picked("RUN B", amber, b)),
            new DualStabilityChart(ComparisonEngine.Points(a), $"Run A ({a.DeviceModel})", ComparisonEngine.Points(b), $"Run B ({b.DeviceModel})"),
            metrics, insights).Pad(16);
        col.MaxWidth = 860;
        view.Content = col;
    }
}
