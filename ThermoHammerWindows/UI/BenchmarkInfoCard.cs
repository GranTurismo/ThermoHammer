using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ThermoHammer.Engine;
using ThermoHammer.Engine.Ai;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK INFO CARD — what the benchmark runs, before it starts: every test
// with its length, then this PC's best and last score with the submit for a run
// made offline (Android's BenchmarkInfoCard), and the "unsubmitted results"
// banner (Android's PendingRunsBanner).
// ─────────────────────────────────────────────────────────────────────────────

public static class BenchmarkInfoCard
{
    /// <summary>
    /// The card. `rayTracingProblem` is why GPU Test 3 can't run here (null where it can), `rayTracingChecked` that the check is done —
    /// until then the row reads as it runs. `best` and `last` are this PC's runs of the current scoring version; `canSubmitLast`: the last
    /// run is verified, not yet submitted, and there's a connection; `lastNeedsConnection`: the same without one.
    /// </summary>
    public static FrameworkElement Build(string? rayTracingProblem, bool rayTracingChecked, BenchmarkRecord? best, BenchmarkRecord? last,
        bool canSubmitLast, bool lastNeedsConnection, bool submitting, Action onSubmitLast, string? message = null)
    {
        var col = Ui.VStack(10);
        foreach (var t in CpuTests.All)
            col.Children.Add(Row($"C{t.Number()}", BenchColor.Cpu, $"CPU · {t.Title()}",
                CpuKernels(t) + (t.MultiCore() ? " — on every core" : ""), $"{t.Seconds()}s"));
        foreach (var t in MemTests.All)
            col.Children.Add(Row($"M{t.Number()}", BenchColor.Mem, $"MEM · {t.Title()}", t switch
            {
                MemTest.Ram => "Read, write and copy on every core · random-access latency",
                MemTest.Storage => "Sequential and random 4 KB reads and writes · temp file, deleted after",
                _ => "SQLite like a messaging app · save, mark read, open chats",
            }, $"{t.Seconds()}s"));
        foreach (var t in GpuTests.All)
        {
            bool skipped = t.RayTracing() && rayTracingChecked && rayTracingProblem != null;
            col.Children.Add(Row($"{t.Number()}", BenchColor.Gpu(t), t.Title(), t switch
            {
                GpuTest.LakeValley => "Forest lake in daylight · sun shadows, water reflections",
                GpuTest.NeonCity => "Rainy neon city at night · 800+ lights, fog, wet streets",
                _ => skipped ? $"Skipped, 0 points — {rayTracingProblem}" : "Neon City with ray-traced reflections and shadows · DirectX Raytracing",
            }, skipped ? "—" : $"{t.Seconds()}s", skipped ? Forge.Ink2 : Forge.Ink0, skipped ? Ramp.At(0.5) : Forge.Ink2, badgeSize: 12));
        }
        foreach (var t in UxTests.All)
            col.Children.Add(Row($"U{t.Number()}", BenchColor.Ux(t), t.Title(), t switch
            {
                UxTest.Web => "WebView2 · page layout, Canvas, CSS and SVG animation, JavaScript",
                UxTest.Scroll => "Endless social feed built as it scrolls ever faster · no dropped frames",
                UxTest.Gallery => "12 MP JPEG, WebP and AVIF photos decoded and shown in a web page",
                UxTest.Photo => "GPU edits of a 12 MP photo · fisheye, blur, bokeh, sharpen, colour grade",
                UxTest.Video => "4K60 H.264, HEVC 10-bit, VP9, AV1 and 8K30 HEVC, AV1 · decoded flat out",
                UxTest.Export => "A 4K edit exported to H.264, HEVC, AV1 (+ 8K HEVC) · quality checked",
                _ => "32 camera frames · zxing-cpp scored",
            }, $"{t.Seconds()}s"));
        foreach (var t in AiTests.All)
            col.Children.Add(Row($"A{t.Number()}", BenchColor.Ai, t.Title(), $"{t.Model()} · CPU, GPU and NPU", $"{t.Seconds()}s"));
        col.Children.Add(Ui.Text("AI runs the phones' 8-bit models through ONNX Runtime: INT8 on the CPU, float16 on the GPU through DirectML. " +
            (AiBench.NpuProblem is { } npu ? $"The NPU scores 0 here: {npu}."
                : "The NPU counts where this PC has one its runtime supports; elsewhere it scores 0."), 8, Forge.Ink2, wrap: true));
        foreach (var a in LlmAccels.All)
            col.Children.Add(Row($"L{(int)a + 1}", BenchColor.Llm, $"LLM · {a.Label()}", a == LlmAccel.Cpu
                ? "Llama 3.2 3B, 4-bit · a 512-token prompt, then the reply token by token"
                : "The same model with every layer on the GPU · llama.cpp Vulkan", $"{a.Seconds()}s"));
        col.Children.Add(Ui.Text("The LLM runs llama.cpp on a model built from a seed in memory — nothing is downloaded. The faster of the CPU " +
            "and the GPU counts; it needs a PC with 6 GB of RAM.", 8, Forge.Ink2, wrap: true));

        if (best != null || last != null)
        {
            col.Children.Add(Ui.Line(Forge.Hairline));
            var scores = Ui.HStack(10);
            if (best != null)
                scores.Children.Add(Ui.VStack(2, Ui.Text(best.Score.N0(), 18, Forge.PhaseMeasured, Forge.W.Black), Ui.Text("BEST ON THIS PC", 7, Forge.Ink2)).Grow());
            if (last != null && last.Id != best?.Id)
                scores.Children.Add(Ui.VStack(2, Ui.Text(last.Score.N0(), 18, Forge.Ink0, Forge.W.Black), Ui.Text("LAST RUN", 7, Forge.Ink2)).Grow());
            if (lastNeedsConnection)
                scores.Children.Add(Ui.Text("TURN ON INTERNET\nTO SUBMIT", 8, Ramp.At(0.5), Forge.W.Bold, align: TextAlignment.Right));
            if (canSubmitLast)
            {
                if (submitting) scores.Children.Add(new Spinner(Forge.PhasePreFlight, 16));
                else scores.Children.Add(Ui.Button("SUBMIT ↑", onSubmitLast, Forge.PhasePreFlight.WithAlpha(0.15), Forge.PhasePreFlight, 9, Forge.W.Black, 8, 6));
            }
            scores.Stretch = false;
            foreach (FrameworkElement e in scores.Children) e.VerticalAlignment = VerticalAlignment.Center;
            col.Children.Add(scores);
            if (message != null) col.Children.Add(Ui.Text(message, 9, Forge.Ink1, wrap: true));
        }
        return Ui.Box(col, Forge.Surface, 14, 14, Forge.Hairline);
    }

    /// <summary>The kernels a CPU test times, as one sentence fragment ("Matmul · fft · …"); the names come from BenchCore.</summary>
    private static string CpuKernels(CpuTest t)
    {
        string s;
        try { s = string.Join(" · ", t.Labels()).ToLowerInvariant(); }
        catch (Exception) { return t.Title().ToLowerInvariant() is { Length: > 0 } f ? char.ToUpperInvariant(f[0]) + f[1..] : ""; }
        return s.Length > 0 ? char.ToUpperInvariant(s[0]) + s[1..] : s;
    }

    /// <summary>One test: its badge, its name and what it does, its length.</summary>
    private static FrameworkElement Row(string badge, Color color, string title, string text, string seconds, Color? titleColor = null,
        Color? textColor = null, double badgeSize = 10)
    {
        var chip = Ui.Box(Ui.Text(badge, badgeSize, color, Forge.W.Black, align: TextAlignment.Center).Align(HorizontalAlignment.Center, VerticalAlignment.Center),
            color.WithAlpha(0.15), 7).Size(26, 26);
        var words = Ui.VStack(2, Ui.Text(title, 12, titleColor ?? Forge.Ink0, Forge.W.Bold), Ui.Text(text, 8, textColor ?? Forge.Ink2, wrap: true));
        var row = Ui.HStack(10, chip, words.Grow(), Ui.Text(seconds, 10, Forge.Ink1));
        row.Stretch = false;
        foreach (FrameworkElement e in row.Children) e.VerticalAlignment = VerticalAlignment.Center;
        return row;
    }

    /// <summary>"UNSUBMITTED RESULTS": offline stress runs are waiting on this PC; a click opens the leaderboard, where they're submitted.</summary>
    public static FrameworkElement PendingBanner(int count, Action onClick)
    {
        var amber = Ramp.At(0.5);
        var icon = Ui.Box(Ui.Text("⚠", 11, amber, Forge.W.Black).Align(HorizontalAlignment.Center, VerticalAlignment.Center), amber.WithAlpha(0.15), 6).Size(22, 22);
        var words = Ui.VStack(2, Ui.Text("UNSUBMITTED RESULTS", 10, Forge.Ink0, Forge.W.Bold),
            Ui.Text($"{count} locally saved run{(count > 1 ? "s" : "")} — click to submit", 8, Forge.Ink1, wrap: true));
        var row = Ui.HStack(12, icon, words.Grow(), Ui.Text("→", 14, Forge.Ink2, Forge.W.Bold));
        row.Stretch = false;
        foreach (FrameworkElement e in row.Children) e.VerticalAlignment = VerticalAlignment.Center;
        var b = Ui.Button(row, onClick, Forge.Raised, 14, 16, 12, stroke: amber.WithAlpha(0.3));
        b.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        return b;
    }
}

/// <summary>
/// The card on the home screen, kept up to date by itself: it reads this PC's runs, follows the connection and the benchmark (a run that just
/// ended becomes its "last run"), checks for DirectX Raytracing in the background, and submits the last run when asked.
/// </summary>
public sealed class BenchmarkInfoPanel : ContentControl
{
    private static string? rayTracingProblem;
    private static bool rayTracingChecked;

    private readonly double maxHeight;
    private bool submitting;
    private string? message;
    private string key = "";

    /// <summary>`maxHeight`: the card scrolls inside that height (it is about 25 rows tall) — leave it out where the page scrolls.</summary>
    public BenchmarkInfoPanel(double maxHeight = double.PositiveInfinity)
    {
        this.maxHeight = maxHeight;
        HorizontalContentAlignment = HorizontalAlignment.Stretch;
        Loaded += (_, _) =>
        {
            NetworkMonitor.Shared.Changed += Refresh;
            BenchmarkEngine.Shared.Changed += OnBenchmark;
            Refresh();
            if (!rayTracingChecked) _ = CheckRayTracing();
        };
        Unloaded += (_, _) =>
        {
            NetworkMonitor.Shared.Changed -= Refresh;
            BenchmarkEngine.Shared.Changed -= OnBenchmark;
        };
        Refresh();
    }

    private void OnBenchmark() { if (!BenchmarkEngine.Shared.IsRunning) Refresh(); }

    /// <summary>Building a Direct3D 12 device takes a moment: not on the UI thread. The answer is kept for the run's plan too.</summary>
    private async Task CheckRayTracing()
    {
        string? why;
        try { why = await Task.Run(() => BenchmarkEngine.RayTracingSkipReason); }
        catch (Exception e) { why = $"the ray tracing check failed: {e.Message.Trim()}"; }
        rayTracingProblem = why;
        rayTracingChecked = true;
        Refresh();
    }

    private void Refresh()
    {
        var store = BenchmarkHistoryStore.Shared;
        var best = store.Best;
        var last = store.Records.LastOrDefault(r => r.Version == BenchmarkScore.Version);
        bool online = NetworkMonitor.Shared.IsConnected;
        // only a verified, complete run that hasn't gone yet can be submitted from here
        bool unsent = last is { Complete: true, Submitted: false, ValidityFlags: 0 };
        string k = $"{best?.Id}|{best?.Score}|{last?.Id}|{last?.Submitted}|{online}|{unsent}|{submitting}|{rayTracingChecked}|{rayTracingProblem}|{message}";
        if (k == key && Content != null) return;
        key = k;
        var card = BenchmarkInfoCard.Build(rayTracingProblem, rayTracingChecked, best, last, unsent && online, unsent && !online, submitting,
            () => { if (last != null) _ = SubmitLast(last); }, message);
        Content = double.IsInfinity(maxHeight) ? card : new ScrollViewer
        {
            Content = card, MaxHeight = maxHeight, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Focusable = false,
        };
    }

    private async Task SubmitLast(BenchmarkRecord rec)
    {
        if (submitting) return;
        submitting = true;
        message = null;
        Refresh();
        try
        {
            var r = await BenchmarkUpload.Submit(rec);
            BenchmarkHistoryStore.Shared.MarkSubmitted(rec.Id, r.Id);
            message = $"Submitted · {r.Score:N0} points · rank #{r.Rank}";
        }
        catch (Exception e) { message = e.Message; }
        submitting = false;
        Refresh();
    }
}

/// <summary>The banner as a control of its own: it follows the saved offline runs and hides itself when there are none.</summary>
public sealed class PendingRunsBanner : ContentControl
{
    private readonly Action onClick;

    public PendingRunsBanner(Action onClick)
    {
        this.onClick = onClick;
        HorizontalContentAlignment = HorizontalAlignment.Stretch;
        Loaded += (_, _) => { PendingResultStore.Shared.Changed += Refresh; Refresh(); };
        Unloaded += (_, _) => PendingResultStore.Shared.Changed -= Refresh;
        Refresh();
    }

    private void Refresh()
    {
        int count = PendingResultStore.Shared.Results.Count;
        Visibility = count > 0 ? Visibility.Visible : Visibility.Collapsed;
        Content = count > 0 ? BenchmarkInfoCard.PendingBanner(count, onClick) : null;
    }
}
