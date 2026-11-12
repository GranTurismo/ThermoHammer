using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>Leaderboard tab: the benchmark board (default) or the sustained stress board — the phones' LeaderboardScreen — filtered by platform.</summary>
public sealed class LeaderboardTab : Grid
{
    private int board;
    private int? platform;                       // null: every platform
    private readonly ContentControl tabs = new(), platformTabs = new(), body = new();
    private readonly BenchmarkBoard benchmark = new();
    private readonly StressBoard stress = new();

    public LeaderboardTab()
    {
        Background = Brushes.Black;
        MaxWidth = 860;
        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        RowDefinitions.Add(new RowDefinition());
        Children.Add(tabs);
        SetRow(platformTabs, 1);
        Children.Add(platformTabs);
        SetRow(body, 2);
        Children.Add(body);
        ShowPlatforms();
        Show(0);
    }

    public void Appeared() { if (board == 0) benchmark.LoadIfEmpty(); else stress.LoadIfEmpty(); }

    /// <summary>The server's OsPlatform as the boards' tabs and empty states call it; null is every platform.</summary>
    public static string PlatformName(int? p) => p switch { 1 => "iOS", 2 => "Android", 3 => "macOS", 4 => "Windows", _ => "All" };

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

    /// <summary>ALL, then the platforms: plain text on a hairline, the chosen one underlined — lighter than the board toggle above it.</summary>
    private void ShowPlatforms()
    {
        var row = Ui.HStack(0);
        foreach (int? p in new int?[] { null, 4, 3, 2, 1 })
        {
            bool on = platform == p;
            var mark = new Border { Height = 2, CornerRadius = new CornerRadius(1), Background = (on ? Forge.PhaseMeasured : Colors.Transparent).Brush() };
            row.Children.Add(Ui.Button(Ui.VStack(6, Ui.Text(p == null ? "ALL" : PlatformName(p).ToUpperInvariant(), 9, on ? Forge.PhaseMeasured : Forge.Ink1,
                Forge.W.Bold, align: TextAlignment.Center), mark), () => Pick(p), Colors.Transparent, 0, 4, 8).Grow());
        }
        var bar = Ui.VStack(0, row, Ui.Line(Forge.Hairline));
        bar.Margin = new Thickness(16, 10, 16, 0);
        platformTabs.Content = bar;
    }

    private void Pick(int? p)
    {
        platform = p;
        ShowPlatforms();
        benchmark.Platform = p;
        stress.Platform = p;
    }

    /// <summary>A spinner with its caption under it: what an overlay shows while its numbers come from the server.</summary>
    internal static FrameworkElement Loading(string caption, double pad = 60) =>
        Ui.VStack(12, new Spinner(Forge.PhasePreFlight).Align(HorizontalAlignment.Center),
            Ui.Text(caption, 10, Forge.Ink0.WithAlpha(0.4), align: TextAlignment.Center)).Pad(pad);

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
    private int? platform;
    private readonly Flex list = Ui.VStack(8);
    private readonly Grid popup = new();
    private readonly Button refresh;
    private readonly LazyRows rows;
    // comparing two runs: click two rows (A, then B), or start from a run's details
    private bool picking;
    private readonly List<(BenchmarkDto e, int rank)> picked = [];
    private readonly ContentControl compareSlot = new();

    /// <summary>The platform tab chosen on the page (null: all); the list is filtered here, so changing it fetches nothing.</summary>
    public int? Platform { set { platform = value; if (loaded) Render(); } }

    public BenchmarkBoard()
    {
        refresh = Ui.Button(Ui.Text("↻", 18, Forge.Ink0, Forge.W.Bold), () => _ = Load(), Colors.Transparent, 8, 6, 2);
        var head = Ui.HStack(0, Ui.VStack(2, Ui.Text("DEVICE BENCHMARK", 18, Forge.Ink0, Forge.W.Black),
            Ui.Text("All six sections · best verified run per device", 9, Forge.Ink0.WithAlpha(0.4))), Ui.Spacer(), compareSlot, refresh);
        RenderCompareButton();
        var (search, _) = LeaderboardTab.Search("Search device or GPU…", q => { query = q; Render(); });
        var scroll = Ui.Scroll(Ui.VStack(8, head, search, list).Pad(16, 12));
        rows = new LazyRows(scroll, list);
        Children.Add(scroll);
        Children.Add(popup);
        // the board reloads when the connection comes back (and shows "Offline" when it goes), for as long as it is on screen
        Loaded += (_, _) => { NetworkMonitor.Shared.Changed -= ConnectionChanged; NetworkMonitor.Shared.Changed += ConnectionChanged; };
        Unloaded += (_, _) => NetworkMonitor.Shared.Changed -= ConnectionChanged;
    }

    private void ConnectionChanged() { if (!loading) _ = Load(); }

    public void LoadIfEmpty() { if (!loaded && !loading) _ = Load(); }

    private async Task Load()
    {
        if (loading) return;
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
        submittingId = r.Id; Render(true);
        try
        {
            var res = await BenchmarkUpload.Submit(r);
            BenchmarkHistoryStore.Shared.MarkSubmitted(r.Id, res.Id);
            submitNote = $"Submitted · {res.Score:N0} points · rank #{res.Rank}";
        }
        catch (Exception e) { submitNote = e.Message; }
        submittingId = null;
        await Load();
    }

    /// <summary>[keep]: only what is marked on the rows changed (a pick, a submit), so the list stays as far down as it was scrolled.</summary>
    private void Render(bool keep = false)
    {
        refresh.IsEnabled = !loading;
        rows.Stop();
        list.Children.Clear();
        RenderCompareButton();
        if (picking) list.Children.Add(PickHint());
        Unsubmitted();
        if (loading) { list.Children.Add(new Spinner(Forge.PhasePreFlight).Align(HorizontalAlignment.Center).Pad(40)); return; }
        if (error.Length > 0)
        {
            list.Children.Add(Ui.VStack(8, Ui.Text($"⚠ {error}", 12, Ramp.At(0.85), Forge.W.Bold, wrap: true, align: TextAlignment.Center),
                Ui.Button("RETRY", () => _ = Load(), Forge.Ink0.WithAlpha(0.1), Forge.Ink0).Align(HorizontalAlignment.Center)).Pad(30));
            return;
        }
        string q = query.Trim().ToLowerInvariant();
        var ofPlatform = entries.Where(e => platform == null || e.Os == platform).ToList();
        var shown = ofPlatform.Select((e, i) => (rank: i + 1, e)).Where(x => q.Length == 0 || x.e.DeviceModel.ToLowerInvariant().Contains(q)
            || x.e.DeviceManufacturer.ToLowerInvariant().Contains(q) || (x.e.GpuName?.ToLowerInvariant().Contains(q) ?? false)).ToList();
        if (shown.Count == 0)
        {
            string none = ofPlatform.Count > 0 ? "No devices match." : platform is { } p ? $"No {LeaderboardTab.PlatformName(p)} runs yet." : "No benchmark runs yet — be the first.";
            list.Children.Add(Ui.Text(none, 11, Forge.Ink0.WithAlpha(0.4), align: TextAlignment.Center).Pad(30));
            return;
        }
        int top = Math.Max(ofPlatform[0].TotalScore, 1);
        rows.Fill(shown.Count, i =>
        {
            var (rank, e) = shown[i];
            return Row(rank, e, (double)e.TotalScore / top, string.Equals(e.DeviceModel, DeviceInfo.Model, StringComparison.OrdinalIgnoreCase));
        }, keep);
    }

    private FrameworkElement Row(int rank, BenchmarkDto e, double fraction, bool mine)
    {
        var rankText = Ui.Text($"#{rank}", 11, rank <= 3 ? Ramp.At(1 - (rank - 1) * 0.2) : Forge.Ink2, Forge.W.Black).Size(40);
        var name = Ui.VStack(2, Ui.Text(e.Name, 12, Forge.Ink0, Forge.W.Bold, wrap: true),
            Ui.Text(string.Join(" · ", new[] { e.GpuName, DeviceInfo.OsLabel(e.Os, e.OsVersion) }.Where(s => !string.IsNullOrEmpty(s))), 8, Forge.Ink0.WithAlpha(0.4)));
        var score = Ui.Text(e.TotalScore.N0(), 15, Forge.Ink0, Forge.W.Black, align: TextAlignment.Right);
        int slot = picking ? picked.FindIndex(p => p.e.Id == e.Id) : -2;
        // the device and its score share the line; the long list of section scores sits below it, so it can never squeeze the name
        var top = Ui.HStack(8, rankText, name.Grow(), score, slot >= -1 ? PickMark(slot) : Ui.Text("›", 18, Forge.Ink0.WithAlpha(0.3)));
        top.Stretch = false;
        foreach (FrameworkElement c in top.Children) c.VerticalAlignment = VerticalAlignment.Center;
        // each label stays with its number when the line wraps (non-breaking spaces)
        var sections = Ui.Text($"CPU\u00A0{e.CpuScore.N0()} · MEM\u00A0{e.MemScore.N0()} · GPU\u00A0{e.Score.N0()} · UX\u00A0{e.UxScore.N0()} · " +
            $"AI\u00A0{e.AiScore.N0()} · LLM\u00A0{e.LlmScore.N0()} · " + (e.RayTracing ? $"RT\u00A0{e.Test3Fps.F(0)}\u00A0FPS" : "NO\u00A0RT"),
            7, Forge.Ink0.WithAlpha(0.4), wrap: true);
        var bar = new ScoreBar(fraction);
        Color? pickColor = slot == 0 ? BenchmarkCompareView.ColorA : slot == 1 ? BenchmarkCompareView.ColorB : null;
        var b = Ui.Box(Ui.VStack(8, top, sections, bar), Forge.Surface, 14, 12,
            pickColor ?? (mine ? Forge.PhaseMeasured.WithAlpha(0.6) : Forge.Ink0.WithAlpha(0.06)), pickColor != null ? 1.5 : 1);
        b.Cursor = Cursors.Hand;
        b.MouseLeftButtonUp += (_, _) => { if (picking) Pick(e, rank); else OpenDetail(e, rank); };
        return b;
    }

    // ── picking two runs and comparing them ──

    private void RenderCompareButton() =>
        compareSlot.Content = Ui.Button(Ui.Text(picking ? "✕ CANCEL" : "⇄ COMPARE", 9, picking ? Forge.Bg : Forge.PhaseMeasured, Forge.W.Black),
            () => { if (picking) StopPicking(); else { picking = true; picked.Clear(); Render(true); } },
            picking ? Forge.PhaseMeasured : Forge.PhaseMeasured.WithAlpha(0.1), 8, 10, 7, stroke: Forge.PhaseMeasured.WithAlpha(0.5))
            .Align(HorizontalAlignment.Right, VerticalAlignment.Center).Pad(0, 0, 8, 0);

    private FrameworkElement PickHint()
    {
        string text = picked.Count == 0 ? "Click two devices to compare them" : $"Now click the one to compare with {picked[0].e.Name}";
        var row = Ui.HStack(8, Ui.Text(text, 11, Forge.Ink0, Forge.W.Bold).Grow(), Ui.Text($"{picked.Count}/2", 12, Forge.PhaseMeasured, Forge.W.Black));
        row.Stretch = false;
        return Ui.Box(row, Forge.PhaseMeasured.WithAlpha(0.08), 12, 12, Forge.PhaseMeasured.WithAlpha(0.3));
    }

    /// <summary>While picking: an empty ring, or the A / B the run was picked as.</summary>
    private static FrameworkElement PickMark(int slot)
    {
        Color? color = slot == 0 ? BenchmarkCompareView.ColorA : slot == 1 ? BenchmarkCompareView.ColorB : null;
        return new Border
        {
            Width = 24, Height = 24, CornerRadius = new CornerRadius(12), VerticalAlignment = VerticalAlignment.Center,
            Background = (color ?? Colors.Transparent).Brush(),
            BorderBrush = color == null ? Forge.Ink0.WithAlpha(0.3).Brush() : null, BorderThickness = new Thickness(color == null ? 1.5 : 0),
            Child = color == null ? null : Ui.Text(slot == 0 ? "A" : "B", 11, Forge.Bg, Forge.W.Black, align: TextAlignment.Center)
                .Align(HorizontalAlignment.Center, VerticalAlignment.Center),
        };
    }

    private void Pick(BenchmarkDto e, int rank)
    {
        int i = picked.FindIndex(p => p.e.Id == e.Id);
        if (i >= 0) picked.RemoveAt(i); else picked.Add((e, rank));
        Render(true);
        if (picked.Count == 2) OpenCompare(picked[0], picked[1]);
    }

    private void StopPicking() { picking = false; picked.Clear(); Render(true); }

    private void OpenCompare((BenchmarkDto e, int rank) a, (BenchmarkDto e, int rank) b)
    {
        var window = Window.GetWindow(this);
        KeyEventHandler? esc = null;
        void Close() { popup.Children.Clear(); if (window != null && esc != null) window.PreviewKeyDown -= esc; StopPicking(); }
        esc = (_, k) => { if (k.Key == Key.Escape) { Close(); k.Handled = true; } };
        if (window != null) window.PreviewKeyDown += esc;

        bool swapped = false;
        (BenchmarkDetail a, BenchmarkDetail b)? loaded = null;
        var legend = new ContentControl();
        var content = new ContentControl();
        void Show()
        {
            var (x, y) = swapped ? (b, a) : (a, b);
            legend.Content = BenchmarkCompareView.Legend(x.e, y.e);
            if (loaded is { } d)
                content.Content = BenchmarkCompareView.Body(swapped ? BenchmarkComparison.Make(d.b, d.a) : BenchmarkComparison.Make(d.a, d.b),
                    x.e, x.rank, y.e, y.rank);
        }
        FrameworkElement Message(string text, bool retry) => Ui.VStack(10, Ui.Text(retry ? $"⚠ {text}" : text, 12, retry ? Ramp.At(0.85) : Forge.Ink1, Forge.W.Bold,
            wrap: true, align: TextAlignment.Center), retry ? Ui.Button("RETRY", () => _ = Load(), Forge.Ink0.WithAlpha(0.1), Forge.Ink0).Align(HorizontalAlignment.Center) : null).Pad(30);
        async Task Load()
        {
            content.Content = LeaderboardTab.Loading("LOADING BOTH RUNS…");
            if (!NetworkMonitor.Shared.IsConnected) { content.Content = Message("Offline", true); return; }
            try
            {
                var da = BenchmarkUpload.FetchDetail(a.e.Id);
                var db = BenchmarkUpload.FetchDetail(b.e.Id);
                await Task.WhenAll(da, db);
                if (da.Result is not { } runA || db.Result is not { } runB)
                {
                    content.Content = Message("One of these runs is no longer on the board", false);
                    return;
                }
                loaded = (runA, runB);
                Show();
            }
            catch (Exception ex) { content.Content = Message(ex.Message, true); }
        }

        var swap = Ui.Button(Ui.Text("⇄", 13, Forge.Ink0, Forge.W.Bold), () => { swapped = !swapped; Show(); }, Colors.White.WithAlpha(0.08), 16, 10, 6);
        var close = Ui.Button(Ui.Text("✕", 12, Forge.Ink0, Forge.W.Bold), Close, Colors.White.WithAlpha(0.08), 16, 10, 6);
        var head = Ui.HStack(8, Ui.Text("COMPARE", 13, Forge.Ink0, Forge.W.Black).Align(HorizontalAlignment.Left, VerticalAlignment.Center), Ui.Spacer(), swap, close);
        head.Stretch = false;
        var card = Ui.Box(Ui.VStack(12, head, legend, Ui.Line(Colors.White.WithAlpha(0.1)), content), Forge.Raised, 24, 24, Colors.White.WithAlpha(0.1));
        card.MaxWidth = 860;
        card.Margin = new Thickness(30);
        card.VerticalAlignment = VerticalAlignment.Center;
        var scrim = new Grid { Background = Color.FromArgb(217, 0, 0, 0).Brush() };
        scrim.MouseLeftButtonUp += (s, ev) => { if (ev.OriginalSource == scrim) Close(); };
        scrim.Children.Add(Ui.Scroll(card));
        popup.Children.Clear();
        popup.Children.Add(scrim);
        Overlays.FadeIn(card);
        Show();
        _ = Load();
    }

    // ── one run's numbers ──

    private void OpenDetail(BenchmarkDto e, int rank)
    {
        var window = Window.GetWindow(this);
        KeyEventHandler? esc = null;
        void Close() { popup.Children.Clear(); if (window != null && esc != null) window.PreviewKeyDown -= esc; }
        esc = (_, k) => { if (k.Key == Key.Escape) { Close(); k.Handled = true; } };
        if (window != null) window.PreviewKeyDown += esc;
        var content = new ContentControl();
        var close = Ui.Button(Ui.Text("✕", 12, Forge.Ink0, Forge.W.Bold), Close, Colors.White.WithAlpha(0.08), 16, 10, 6).Align(HorizontalAlignment.Right, VerticalAlignment.Top);
        var compare = Ui.Button(Ui.Text("⇄ COMPARE", 9, Forge.PhaseMeasured, Forge.W.Black), () =>
        {
            Close();
            picking = true;
            picked.Clear();
            picked.Add((e, rank));
            Render(true);
        }, Forge.PhaseMeasured.WithAlpha(0.1), 8, 10, 7, stroke: Forge.PhaseMeasured.WithAlpha(0.5)).Align(HorizontalAlignment.Right, VerticalAlignment.Top);
        var head = Ui.HStack(10, Ui.VStack(2, Ui.Text(e.Name, 16, Forge.Ink0, Forge.W.Black),
            Ui.Text(string.Join(" · ", new[] { e.SocModel, e.GpuName, DeviceInfo.OsLabel(e.Os, e.OsVersion) }.Where(s => !string.IsNullOrEmpty(s))), 10, Forge.Ink1, wrap: true)).Grow(),
            compare, close);
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
        _ = LoadDetail(e, rank, content);
    }

    private async Task LoadDetail(BenchmarkDto e, int rank, ContentControl content)
    {
        FrameworkElement Message(string text, bool retry) => Ui.VStack(10, Ui.Text(retry ? $"⚠ {text}" : text, 12, retry ? Ramp.At(0.85) : Forge.Ink1, Forge.W.Bold, wrap: true,
            align: TextAlignment.Center), retry ? Ui.Button("RETRY", () => _ = LoadDetail(e, rank, content), Forge.Ink0.WithAlpha(0.1), Forge.Ink0).Align(HorizontalAlignment.Center) : null).Pad(30);
        content.Content = LeaderboardTab.Loading("LOADING RUN...");
        if (!NetworkMonitor.Shared.IsConnected) { content.Content = Message("Offline", true); return; }
        try
        {
            var d = await BenchmarkUpload.FetchDetail(e.Id);
            content.Content = d == null ? Message("This run is no longer on the board", false) : DetailBody(d, e, rank);
        }
        catch (Exception ex) { content.Content = Message(ex.Message, true); }
    }

    /// <summary>The run's total and rank, then each section as a card that opens into its groups and numbers (all closed to begin with).</summary>
    private static FrameworkElement DetailBody(BenchmarkDetail d, BenchmarkDto e, int rank)
    {
        var meta = string.Join(" · ", new[] { d.AppVersion is { } v ? $"app {v}" : null, d.OsBuild, d.Date, e.Runs > 1 ? $"best of {e.Runs} runs" : null }
            .Where(s => !string.IsNullOrEmpty(s)));
        var totalRow = Ui.HStack(8, Ui.VStack(2, Ui.Text("TOTAL SCORE", 10, Forge.Ink0.WithAlpha(0.4), Forge.W.Black),
            Ui.Text(d.TotalScore.N0(), 26, Forge.Ink0, Forge.W.Black)).Grow(), rank > 0 ? Ui.Text($"#{rank}", 20, Ramp.At(1), Forge.W.Black) : null);
        totalRow.Stretch = false;
        foreach (FrameworkElement c in totalRow.Children) c.VerticalAlignment = VerticalAlignment.Center;
        var col = Ui.VStack(8, Ui.Text(meta, 9, Forge.Ink2, wrap: true).Pad(0, 0, 0, 6), Ui.Box(totalRow, Colors.White.WithAlpha(0.03), 16, 14),
            Ui.HStack(10, Tool("SHARE", () => ShareSheet.Run(ShowcaseRun.ForBoard(d, e))).Grow(),
                Tool("PRESENT", () => PresentWindow.Open(ShowcaseRun.ForBoard(d, e))).Grow()),
            Ui.Text("CLICK A SECTION FOR ITS NUMBERS", 8, Forge.Ink0.WithAlpha(0.3)).Pad(4, 4, 0, 0));
        foreach (var s in d.Sections)
        {
            var chevron = Ui.Text("▸", 12, Forge.Ink0.WithAlpha(0.4));
            var head = Ui.HStack(8, Ui.Text(s.Title, 12, Forge.Ink0, Forge.W.Black).Grow(), Ui.Text(s.Score.N0(), 14, Forge.PhaseMeasured, Forge.W.Black), chevron);
            head.Stretch = false;
            head.Background = Brushes.Transparent;   // the whole line takes the click, not just its text
            head.Cursor = Cursors.Hand;
            foreach (FrameworkElement c in head.Children) c.VerticalAlignment = VerticalAlignment.Center;
            var groups = Ui.VStack(10);
            groups.Visibility = Visibility.Collapsed;
            foreach (var g in s.Groups)
            {
                groups.Children.Add(Ui.Line(Forge.Hairline));
                groups.Children.Add(Ui.HStack(8, Ui.Text(g.Title.ToUpperInvariant(), 9, Forge.Ink1, Forge.W.Bold), Ui.Spacer(),
                    g.Points is { } p ? Ui.Text($"{p.N0()} PTS", 9, Forge.Ink1, Forge.W.Bold) : null));
                foreach (var r in g.Rows)
                    groups.Children.Add(Ui.HStack(12, Ui.Text(r.Label, 10, Forge.Ink1), Ui.Text(r.Value, 10, Forge.Ink0, Forge.W.Bold, wrap: true, align: TextAlignment.Right).Grow()));
            }
            head.MouseLeftButtonUp += (_, _) =>
            {
                bool open = groups.Visibility != Visibility.Visible;
                groups.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
                chevron.Text = open ? "▾" : "▸";
            };
            col.Children.Add(Ui.Box(Ui.VStack(10, head, groups), Colors.White.WithAlpha(0.03), 16, 14, Forge.Hairline));
        }
        return col;
    }

    /// <summary>A run's SHARE / PRESENT / EXPORT CSV in a detail.</summary>
    internal static Button Tool(string label, Action act) => Ui.Button(label, act, Forge.PhaseMeasured.WithAlpha(0.1), Forge.PhaseMeasured, 10,
        Forge.W.Black, 8, 9, stroke: Forge.PhaseMeasured.WithAlpha(0.4));

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
    private readonly Button refresh;
    private readonly LazyRows rows;
    private readonly List<PendingTestResult> compare = [];
    private string? uploadError;
    private Guid? uploading;

    /// <summary>The platform tab chosen on the page (null: all); the list is filtered here, so changing it fetches nothing.</summary>
    public int? Platform { set { platform = value; if (loaded) Render(); } }

    public StressBoard()
    {
        refresh = Ui.Button(Ui.Text("↻", 18, Forge.Ink0, Forge.W.Bold), () => _ = Load(), Colors.Transparent, 8, 6, 2);
        var head = Ui.HStack(0, Ui.VStack(2, Ui.Text("GLOBAL LEADERBOARD", 18, Forge.Ink0, Forge.W.Black),
            Ui.Text("Select 2 Tests to Compare side-by-side", 10, Forge.Ink1)), Ui.Spacer(), refresh);
        var (search, _) = LeaderboardTab.Search("Search model, vendor, or OS...", q => { query = q; Render(); });
        var scroll = Ui.Scroll(Ui.VStack(10, head, search, filters, list).Pad(16, 12, 16, 80));
        rows = new LazyRows(scroll, list);
        Children.Add(scroll);
        compareBar.VerticalAlignment = VerticalAlignment.Bottom;
        compareBar.Margin = new Thickness(16);
        Children.Add(compareBar);
        Children.Add(popup);
        PendingResultStore.Shared.Changed += () => Render(true);
        // the board reloads when the connection comes back (and shows "Offline" when it goes), for as long as it is on screen
        Loaded += (_, _) => { NetworkMonitor.Shared.Changed -= ConnectionChanged; NetworkMonitor.Shared.Changed += ConnectionChanged; };
        Unloaded += (_, _) => NetworkMonitor.Shared.Changed -= ConnectionChanged;
        BuildFilters();
    }

    private void ConnectionChanged() { if (!loading) _ = Load(); }

    public void LoadIfEmpty() { if (!loaded && !loading) _ = Load(); else Render(); }

    private async Task Load()
    {
        if (loading) return;
        if (!NetworkMonitor.Shared.IsConnected) { error = "Offline"; Render(); return; }
        loading = true; error = ""; Render();
        try { entries = await Api.FetchLeaderboard(); loaded = true; }
        catch (Exception e) { error = e.Message; }
        loading = false;
        Render();
    }

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
        Chip(durationFilter != null, $"Duration: {(durationFilter is { } d ? DurationName(d) : "All")}",
            () => durationFilter = durationFilter switch { null => 0, 0 => 1, 1 => 2, _ => null });
        filters.Content = row;
    }

    /// <summary>[keep]: only what is marked changed (a pick, a pending run), so the list stays as far down as it was scrolled.</summary>
    private void Render(bool keep = false)
    {
        refresh.IsEnabled = !loading;
        rows.Stop();
        list.Children.Clear();
        PendingSection();
        list.Children.Add(Ui.Text("ONLINE RANKINGS", 11, Forge.Ink1, Forge.W.Bold).Pad(0, 4));
        if (loading) { list.Children.Add(LeaderboardTab.Loading("LOADING LEADERBOARD...", 30)); }
        else if (error.Length > 0)
        {
            list.Children.Add(Ui.VStack(10, Ui.Text("COULD NOT LOAD LEADERBOARD", 12, Forge.Ink0, Forge.W.Bold, align: TextAlignment.Center),
                Ui.Text(error, 10, Forge.Ink1, wrap: true, align: TextAlignment.Center),
                Ui.Button("RETRY", () => _ = Load(), Colors.White, Colors.Black, 11).Align(HorizontalAlignment.Center)).Pad(30));
        }
        else
        {
            var ranked = entries.Where(e => platform == null || e.Os == platform).OrderByDescending(e => e.StabilityPercentage)
                .Select((e, i) => (rank: i + 1, e)).ToList();
            string q = query.Trim().ToLowerInvariant();
            var shown = ranked.Where(x =>
                (q.Length == 0 || x.e.DeviceModel.ToLowerInvariant().Contains(q) || x.e.DeviceManufacturer.ToLowerInvariant().Contains(q) || x.e.OsVersion.ToLowerInvariant().Contains(q))
                && (!myDevice || string.Equals(x.e.DeviceModel, DeviceInfo.Model, StringComparison.OrdinalIgnoreCase))
                && (!myOs || x.e.Os == DeviceInfo.OsPlatform && x.e.OsVersion.Contains(DeviceInfo.OsVersion))
                && (durationFilter == null || x.e.Type == durationFilter)).ToList();
            if (ranked.Count == 0 && platform is { } p)
                list.Children.Add(Ui.Text($"No {LeaderboardTab.PlatformName(p)} runs yet.", 11, Forge.Ink1, Forge.W.Bold, align: TextAlignment.Center).Pad(0, 40, 0, 0));
            else if (shown.Count == 0) list.Children.Add(Ui.Text("NO ENTRIES FOUND", 11, Forge.Ink1, Forge.W.Bold, align: TextAlignment.Center).Pad(0, 40, 0, 0));
            rows.Fill(shown.Count, i => Row(shown[i].rank, shown[i].e), keep);
        }
        RenderCompareBar();
    }

    /// <summary>A board row as a run to compare (the online run's curve is fetched when it is needed).</summary>
    internal static PendingTestResult ToPending(HammerDto e, List<DeviceHammerStamp>? stamps = null) => new()
    {
        Timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
        DurationSeconds = e.Type == 0 ? 300 : e.Type == 1 ? 900 : 1800,
        TestDurationType = e.Type, TestThreadingType = e.TestThreadingType,
        MinStability = e.StabilityPercentage, FinalStability = e.StabilityPercentage,
        Stamps = stamps ?? [], DeviceModel = e.DeviceModel, DeviceManufacturer = e.DeviceManufacturer, OsVersion = e.OsVersion,
        SessionId = e.Id,
    };

    internal static bool Same(PendingTestResult a, PendingTestResult b) => a.SessionId > 0 && b.SessionId > 0 ? a.SessionId == b.SessionId : a.Id == b.Id;

    private void ToggleCompare(PendingTestResult run)
    {
        int i = compare.FindIndex(r => Same(r, run));
        if (i >= 0) compare.RemoveAt(i);
        else
        {
            if (compare.Count > 0 && (compare[0].TestThreadingType ?? 1) != (run.TestThreadingType ?? 1))
            {
                string A(int? t) => (t ?? 1) == 0 ? "Single Thread" : "Multi Thread";
                BoardNotice.Show(popup, "COMPARISON MISMATCH",
                    $"Cannot compare {A(compare[0].TestThreadingType)} with {A(run.TestThreadingType)} test! Please select matching test types.");
                return;
            }
            compare.Add(run);
            if (compare.Count >= 2) { OpenComparison(); }
        }
        Render(true);
    }

    private void RenderCompareBar()
    {
        if (compare.Count == 0) { compareBar.Content = null; return; }
        string mode = (compare[0].TestThreadingType ?? 1) == 0 ? "1 THREAD" : "MULTI THREAD";
        var text = Ui.VStack(2, Ui.Text($"{compare.Count} / 2 TESTS SELECTED ({mode})", 10, Colors.Black, Forge.W.Black),
            Ui.Text(compare.Count == 1 ? $"Click 1 more {mode} test to compare!" : "Click to view detailed side-by-side analysis", 9, Colors.Black.WithAlpha(0.7)));
        var row = Ui.HStack(8, Ui.Text("⚖", 16, Colors.Black).Align(HorizontalAlignment.Left, VerticalAlignment.Center), text.Grow(),
            Ui.Button("CLEAR", () => { compare.Clear(); Render(true); }, Colors.Black.WithAlpha(0.2), Colors.Black, 9, radius: 12, padV: 4));
        foreach (FrameworkElement c in row.Children) c.VerticalAlignment = VerticalAlignment.Center;
        row.Stretch = false;
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
                    Ui.Text(p.Name, 13, Forge.Ink0, Forge.W.Bold)).Grow(),
                Ui.VStack(2, Ui.Text($"{p.FinalStability:0.0}%", 14, StabilityChart.ScoreColor(p.FinalStability), Forge.W.Black, align: TextAlignment.Right),
                    Ui.Text("STABILITY", 7, Forge.Ink1, Forge.W.Bold, align: TextAlignment.Right)));
            var actions = Ui.HStack(8, Ui.Button(selected ? "✓" : "⚖", () => ToggleCompare(p), selected ? Forge.PhaseMeasured : Colors.White.WithAlpha(0.08),
                selected ? Colors.Black : Forge.PhaseMeasured, 11, padV: 6));
            if (!ValidityFlag.Submittable(p.ValidityFlags ?? 0))
                actions.Children.Add(Ui.Text("NOT VERIFIED", 9, Ramp.At(0.5), Forge.W.Bold).Align(HorizontalAlignment.Left, VerticalAlignment.Center));
            else if (NetworkMonitor.Shared.IsConnected)
            {
                if (uploading == p.Id)
                {
                    var uploadingRow = Ui.HStack(6, new Spinner(Forge.PhasePreFlight) { LayoutTransform = new ScaleTransform(0.4, 0.4) },
                        Ui.Text("UPLOADING...", 9, Forge.PhasePreFlight));
                    uploadingRow.Stretch = false;
                    foreach (FrameworkElement c in uploadingRow.Children) c.VerticalAlignment = VerticalAlignment.Center;
                    actions.Children.Add(uploadingRow.Align(HorizontalAlignment.Left, VerticalAlignment.Center));
                }
                else
                    actions.Children.Add(Ui.Button("SUBMIT", () => _ = Submit(p), Forge.PhasePreFlight, Colors.White, 9, padV: 6, enabled: uploading == null));
            }
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
        uploading = p.Id; uploadError = null; Render(true);
        try
        {
            var id = await Api.SubmitStress(p);
            PendingResultStore.Shared.Delete(p.Id);
            if (id is { } serverId) RunHistoryStore.Shared.SetServerId(p.Id, serverId);   // the run's page, for sharing it
        }
        catch (Exception e) { uploadError = e.Message; }
        uploading = null;
        Render(true);
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
        var info = Ui.VStack(2, Ui.Text(e.Name, 12, Forge.Ink0, Forge.W.Bold),
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
        var content = new ContentControl { Content = LeaderboardTab.Loading("LOADING STAMP DATA...") };
        void Close() => popup.Children.Clear();
        var close = Ui.Button(Ui.Text("✕", 12, Forge.Ink0, Forge.W.Bold), Close, Colors.White.WithAlpha(0.08), 16, 10, 6);
        var head = Ui.HStack(8, Ui.VStack(2, Ui.Text(e.Name, 16, Forge.Ink0, Forge.W.Black),
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
        content.Content = LeaderboardTab.Loading("LOADING STAMP DATA...");
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
            var summary = Ui.Box(Ui.VStack(12, R("TEST TYPE", DurationName(e.Type)), R("STABILITY", $"{e.StabilityPercentage:0.0}%", StabilityChart.ScoreColor(e.StabilityPercentage)),
                R("PEAK SCORE (IPS)", ((long)max).ToString("N0")), R("MIN SCORE (IPS)", ((long)min).ToString("N0")), R("SAMPLES RECORDED", stamps.Count.ToString())),
                Colors.White.WithAlpha(0.03), 16, 14);
            var add = Ui.Button("⚖ ADD TO COMPARISON", () => { popup.Children.Clear(); ToggleCompare(ToPending(e, stamps)); },
                Forge.PhaseMeasured.WithAlpha(0.12), Forge.PhaseMeasured, 11, Forge.W.Bold, 14, 12, stroke: Forge.PhaseMeasured.WithAlpha(0.3));
            var tools = Ui.HStack(10,
                BenchmarkBoard.Tool("SHARE", () => ShareSheet.Run(ShowcaseRun.ForBoard(e, stamps))).Grow(),
                BenchmarkBoard.Tool("PRESENT", () => PresentWindow.Open(ShowcaseRun.ForBoard(e, stamps))).Grow(),
                BenchmarkBoard.Tool("EXPORT CSV", () => ShareSheet.SaveCsv(ShareLinks.CsvFileName(true, e.Name, e.CreatedAt?.ToLocalTime() ?? DateTime.Now),
                    RunCsv.BoardStress(stamps.Select(x => (x.ElapsedMs, x.Score, x.ThermalState))))).Grow());
            content.Content = Ui.VStack(20, summary, tools, Ui.Text("PERFORMANCE CURVE", 10, Forge.Ink1, Forge.W.Black), new StabilityChart(points, events), add);
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
        void Close() { popup.Children.Clear(); compare.Clear(); Render(true); }
        var scrim = new Grid { Background = Brushes.Black };
        scrim.Children.Add(Ui.Scroll(new RunComparison(compare.ElementAtOrDefault(0), compare.ElementAtOrDefault(1), entries, Close)));
        popup.Children.Clear();
        popup.Children.Add(scrim);
        Overlays.FadeIn(scrim, 1);
    }
}
