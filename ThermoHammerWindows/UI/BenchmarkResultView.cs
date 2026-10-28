using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ThermoHammer.Engine;
using ThermoHammer.Engine.Ai;
using ThermoHammer.Engine.Ux;

namespace ThermoHammer.UI;

/// <summary>The finished benchmark: total, section scores, every test's raw numbers; copy the report or submit the run.</summary>
public sealed class BenchmarkResultView : Grid
{
    private readonly BenchmarkResult result;
    private readonly BenchmarkRecord? record;
    private readonly TextBlock submitMessage = Ui.Text("", 9, Forge.Ink1, wrap: true, align: TextAlignment.Center);
    private Button? submit;
    private bool submitted;

    public BenchmarkResultView(BenchmarkResult result, BenchmarkRecord? record, Action onDismiss)
    {
        this.result = result;
        this.record = record;
        Background = Color.FromArgb(184, 0, 0, 0).Brush();
        var col = Ui.VStack(12,
            Ui.Text("THERMOHAMMER BENCHMARK", 9, Forge.Ink2, Forge.W.Bold, align: TextAlignment.Center),
            Ui.Text(result.Total.N0(), 46, Forge.Ink0, Forge.W.Black, align: TextAlignment.Center),
            Ui.Text("TOTAL POINTS", 9, Forge.Ink2, align: TextAlignment.Center),
            VerifiedPill().Align(HorizontalAlignment.Center),
            Ui.HStack(4,
                Section("CPU", result.CpuScore, BenchColor.Cpu, result.Cpu != null),
                Section("MEM", result.MemScore, BenchColor.Mem, result.Mem != null),
                Section("GPU", result.GpuScore, Forge.PhaseCalibration, result.Gpu.Count > 0),
                Section("UX", result.UxScore, Forge.PhaseMeasured, result.Ux != null),
                Section("AI", result.AiScore, BenchColor.Ai, result.Ai != null),
                Section("LLM", result.LlmScore, BenchColor.Llm, result.Llm != null)),
            Ui.Text($"{DeviceInfo.Manufacturer} {DeviceInfo.Model} · {result.GpuName}", 10, Forge.Ink1, Forge.W.Bold, align: TextAlignment.Center));
        if (!result.IsComplete)
            col.Children.Add(Ui.Text(result.NotYet.Count > 0
                ? $"Not run yet on Windows: {string.Join("; ", result.NotYet)}. Until every section runs, a run can't go to the leaderboard."
                : "Not every section ran — this run can't go to the leaderboard.", 9, Ramp.At(0.5), wrap: true, align: TextAlignment.Center));
        if (!result.IsVerified)
            col.Children.Add(Ui.Text(ValidityNote(result.ValidityFlags), 9, Ramp.At(0.5), wrap: true, align: TextAlignment.Center));
        col.Children.Add(new HealthCard(result, record));
        if (result.Cpu is { } cpu) foreach (var t in CpuTests.All) col.Children.Add(CpuCard(t, cpu));
        if (result.Mem is { } mem) foreach (var c in MemCards(mem)) col.Children.Add(c);
        foreach (var g in result.Gpu) col.Children.Add(GpuCard(g));
        if (result.Ux is { } ux) foreach (var c in UxCards(ux)) col.Children.Add(c);
        if (result.Ai is { } ai) foreach (var c in AiCards(ai)) col.Children.Add(c);
        if (result.Llm is { } llm) col.Children.Add(LlmCard(llm));
        col.Children.Add(Buttons(onDismiss));
        var card = Ui.Box(col, Forge.Raised, 18, 18);
        card.MaxWidth = 620;
        card.Margin = new Thickness(14, 30, 14, 30);
        Children.Add(Ui.Scroll(card));
    }

    /// <summary>Verified: none of the validity flags — scored on the server, eligible for the leaderboard.</summary>
    private FrameworkElement VerifiedPill()
    {
        bool ok = result.IsVerified && result.IsComplete;
        var c = ok ? Forge.PhaseMeasured : Ramp.At(0.5);
        string label = ok ? "✓ VERIFIED RUN" : !result.IsVerified ? "UNVERIFIED RUN" : "INCOMPLETE RUN";
        var row = Ui.HStack(0, Ui.Text(label, 9, c, Forge.W.Bold),
            Ui.Text(ok ? " · SCORED ON OUR SERVER" : " · NOT FOR THE LEADERBOARD", 8, c.WithAlpha(0.75)));
        row.Stretch = false;
        foreach (FrameworkElement e in row.Children) e.VerticalAlignment = VerticalAlignment.Center;
        var b = Ui.Box(row, c.WithAlpha(0.14), 12, 0, c.WithAlpha(0.5));
        b.Padding = new Thickness(12, 5, 12, 5);
        return b;
    }

    private static FrameworkElement Section(string label, int value, Color color, bool ran) => Ui.VStack(2,
        Ui.Text(ran ? value.N0() : "—", 14, ran ? color : Forge.Ink2, Forge.W.Black, align: TextAlignment.Center),
        Ui.Text(label, 8, Forge.Ink2, align: TextAlignment.Center)).Grow();

    private static FrameworkElement Card(string label, string title, int points, Color accent, params UIElement[] content)
    {
        var head = Ui.HStack(0,
            Ui.VStack(2, Ui.Text(label, 9, accent, Forge.W.Bold), Ui.Text(title, 13, Forge.Ink0, Forge.W.Black)),
            Ui.Spacer(),
            Ui.VStack(2, Ui.Text(points.N0(), 16, Forge.Ink0, Forge.W.Black, align: TextAlignment.Right), Ui.Text("POINTS", 7, Forge.Ink2, align: TextAlignment.Right)));
        var col = Ui.VStack(8, head);
        foreach (var c in content) col.Children.Add(c);
        var b = Ui.Box(col, Forge.Surface, 14, 0, accent.WithAlpha(0.3));
        b.Padding = new Thickness(14, 12, 14, 12);
        return b;
    }

    private static FrameworkElement Metric(string label, string value, Color? color = null) =>
        Ui.VStack(2, Ui.Text(value, 12, color ?? Forge.Ink0, Forge.W.Bold), Ui.Text(label, 7, Forge.Ink2));

    private static FrameworkElement Metrics(params FrameworkElement[] m)
    {
        var row = Ui.HStack(8);
        for (int i = 0; i < m.Length; i++) { if (i > 0) row.Children.Add(Ui.Spacer()); row.Children.Add(m[i]); }
        return row;
    }

    private static FrameworkElement Note(string text, Color? color = null) => Ui.Text(text, 8, color ?? Forge.Ink2, wrap: true);

    private static FrameworkElement CpuCard(CpuTest t, CpuResult cpu)
    {
        var run = cpu.Runs.FirstOrDefault(r => r.Test == t);
        return Card($"CPU TEST {t.Number()}{(t.MultiCore() ? " · ALL CORES" : "")}", t.Title(), cpu.Points(t), BenchColor.Cpu,
            Note(run == null ? "not run" : string.Join("  ·  ", run.Rates.Select(r => $"{r.name} {(r.rate < 100 ? r.rate.F(1) : r.rate.N0())}")) + "  ops/s", Forge.Ink1));
    }

    private static IEnumerable<FrameworkElement> MemCards(MemResult m)
    {
        yield return Card("MEM TEST 1", "RAM", m.RamPoints, BenchColor.Mem, Metrics(
            Metric("READ GB/S", m.Ram.Read.F(1)), Metric("WRITE GB/S", m.Ram.Write.F(1)), Metric("COPY GB/S", m.Ram.Copy.F(1)),
            Metric("LATENCY NS", m.Ram.LatencyNs.F(0))));
        yield return Card("MEM TEST 2", "STORAGE", m.StoragePoints, BenchColor.Mem,
            Metrics(Metric("SEQ READ MB/S", m.Storage.SeqRead.N0()), Metric("SEQ WRITE MB/S", m.Storage.SeqWrite.N0())),
            Metrics(Metric("RANDOM READ IOPS", m.Storage.RandomRead.N0()), Metric("RANDOM WRITE IOPS", m.Storage.RandomWrite.N0())),
            Note($"{m.Storage.FileMb} MB test file, deleted afterwards. 4 MB sequential blocks; random 4 KB blocks from 4 threads. " +
                 (m.Storage.Direct ? "Unbuffered I/O: every transfer reached the drive." : "Windows cached the reads.")));
        yield return Card("MEM TEST 3", "DATABASE", m.DbPoints, BenchColor.Mem, Metrics(
            Metric("MESSAGES SAVED/S", m.Db.Insert.N0()), Metric("MARKED READ/S", m.Db.Update.N0()), Metric("CHATS OPENED/S", m.Db.Query.N0())));
    }

    private static FrameworkElement UxCard(UxTest t, int points, params UIElement[] content) =>
        Card($"UX TEST {t.Number()}", t.Title(), points, BenchColor.Ux(t), content);

    /// <summary>One clip or export: its rate, what it is, how it ran — a column of equal width, so four never run together.</summary>
    private static FrameworkElement CodecMetric(string value, string name, string how, bool ok)
    {
        var c = Ui.VStack(2, Ui.Text(value, 12, ok ? Forge.Ink0 : Ramp.At(0.85), Forge.W.Bold), Ui.Text(name, 7, Forge.Ink2), Ui.Text(how, 7, ok ? Forge.Ink1 : Ramp.At(0.85)));
        return c;
    }

    private static FrameworkElement CodecRow(IEnumerable<FrameworkElement> items, int pad = 0)
    {
        var g = new Grid();
        int n = 0;
        foreach (var i in items) { g.ColumnDefinitions.Add(new ColumnDefinition()); Grid.SetColumn(i, n++); g.Children.Add(i); }
        for (; n < pad; n++) g.ColumnDefinitions.Add(new ColumnDefinition());
        return g;
    }

    /// <summary>Codec rows × 4K30 … 8K240: ✓ real time with 10 % to spare, – too slow, ✗ can't, · not tested.</summary>
    private static FrameworkElement GridTable(List<(string name, VideoGrid.Cell[] cells)> rows, Color accent)
    {
        var g = new Grid { Margin = new Thickness(0, 4, 0, 0) };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(44) });
        foreach (var _ in VideoGrid.Tiers) g.ColumnDefinitions.Add(new ColumnDefinition());
        for (int r = 0; r <= rows.Count; r++) g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        for (int i = 0; i < VideoGrid.Tiers.Length; i++)
        {
            var t = Ui.Text(VideoGrid.Tiers[i].Label, 7, Forge.Ink2, align: TextAlignment.Center);
            Grid.SetColumn(t, i + 1);
            g.Children.Add(t);
        }
        for (int r = 0; r < rows.Count; r++)
        {
            var name = Ui.Text(rows[r].name, 9, Forge.Ink1, Forge.W.Bold);
            Grid.SetRow(name, r + 1);
            g.Children.Add(name);
            for (int i = 0; i < rows[r].cells.Length; i++)
            {
                var c = rows[r].cells[i];
                var color = c == VideoGrid.Cell.Yes ? accent : c == VideoGrid.Cell.Unsupported ? Ramp.At(0.85) : c == VideoGrid.Cell.No ? Forge.Ink2 : Forge.Ink2.WithAlpha(0.4);
                var t = Ui.Text(c.Mark(), 11, color, Forge.W.Black, align: TextAlignment.Center);
                Grid.SetRow(t, r + 1); Grid.SetColumn(t, i + 1);
                g.Children.Add(t);
            }
        }
        return g;
    }

    private static IEnumerable<FrameworkElement> UxCards(UxResult ux)
    {
        var missing = Ramp.At(0.85);
        yield return UxCard(UxTest.Web, ux.WebPoints, ux.Web is { } w
            ? Metrics(Metric("LAYOUT", w.Layout.F(0)), Metric("CANVAS", w.Canvas.F(0)), Metric("CSS", w.Css.F(0)), Metric("SVG", w.Svg.F(0)), Metric("JS OPS/S", w.Js.F(0), Forge.Ink1))
            : Note("The browser test didn't finish on this PC.", Forge.Ink1));
        yield return UxCard(UxTest.Scroll, ux.ScrollPoints, ux.Scroll is { } sc
            ? Metrics(Metric("SPEED HELD PX/S", sc.Speed.N0()), Metric("DROPPED FRAMES", $"{sc.DroppedPct:0.0}%", Forge.Ink1))
            : Note("The scrolling test didn't finish on this PC.", Forge.Ink1));
        yield return UxCard(UxTest.Gallery, ux.GalleryPoints, ux.Gallery is { } ga
            ? Metrics(Metric("JPEG MP/S", ga.Jpeg.F(1)), Metric("WEBP MP/S", ga.Webp.F(1)), Metric("AVIF MP/S", ga.Avif > 0 ? ga.Avif.F(1) : "—", ga.Avif > 0 ? Forge.Ink0 : missing))
            : Note("The gallery test didn't finish on this PC.", Forge.Ink1));
        yield return UxCard(UxTest.Photo, ux.PhotoPoints, Metric("MEGAPIXELS EDITED PER SECOND", ux.PhotoMps.F(1)),
            Note("Fisheye lens, blur, portrait bokeh, sharpening and a colour grade on a 12 MP photo, on the GPU."));
        var video = new List<UIElement>();
        if (ux.Video is { } v)
        {
            foreach (bool k8 in new[] { false, true })
                video.Add(CodecRow(v.Codecs.Where(c => c.Clip.K8() == k8).Select(c =>
                    CodecMetric(c.Fps > 0 ? c.Fps.F(0) : "—", $"{c.Clip.Res()} {c.Clip.Codec().Short()}", c.Fps > 0 ? (c.Hardware ? "HW" : "SW") : "N/A", c.Fps > 0)), pad: 4));
            video.Add(GridTable(Enum.GetValues<VideoCodec>().Select(c => (c.Short(), VideoGrid.Playback(v, c))).ToList(), BenchColor.Ux(UxTest.Video)));
            foreach (var c in v.Codecs.Where(c => c.Error != null)) video.Add(Note($"{c.Clip.Label()}: {c.Error}", missing));
        }
        video.Add(Note("Frames per second decoding each clip flat out (an unsupported one 0); 4K counts three quarters, 8K one. The grid: what plays in real time with 10 % to spare."));
        yield return UxCard(UxTest.Video, ux.VideoPoints, video.ToArray());
        var export = new List<UIElement>();
        if (ux.Export is { } e)
        {
            export.Add(CodecRow(e.Jobs.Select(j =>
            {
                bool ok = j.Fps > 0 && j.Psnr >= UxScore.MinPsnr;
                return CodecMetric(j.Fps > 0 ? j.Fps.F(0) : "—", $"{j.Job.Res()} {j.Job.Label()}",
                    j.Fps <= 0 ? "N/A" : (j.Hardware ? "HW · " : "SW · ") + (ok ? $"{j.Psnr:0.0} dB" : $"LOW {j.Psnr:0.0} dB"), ok);
            })));
            export.Add(GridTable(new[] { VideoCodec.Avc, VideoCodec.Hevc, VideoCodec.Av1 }.Select(c => (c.Short(), VideoGrid.Export(e, c))).ToList(), BenchColor.Ux(UxTest.Export)));
            foreach (var j in e.Jobs.Where(j => j.Error != null)) export.Add(Note($"{j.Job.Res()} {j.Job.Label()}: {j.Error}", missing));
        }
        export.Add(Note($"Frames per second exporting a graded edit; each export is decoded again and compared with what went in — below {(int)UxScore.MinPsnr} dB it doesn't count."));
        yield return UxCard(UxTest.Export, ux.ExportPoints, export.ToArray());
        yield return UxCard(UxTest.Qr, ux.QrPoints, ux.Qr is { } q
            ? [Metrics(Metric("ZXING SCANS/S", q.Rate.F(1)), Metric("MISSED", q.Missed.ToString(), q.Missed > 0 ? missing : Forge.Ink1)),
               Note("zxing-cpp scans 32 camera-like frames on one thread; Windows has no scanner of its own to compare with.")]
            : [Note("The QR test didn't finish on this PC.", Forge.Ink1)]);
        if (ux.Error != null) yield return Note($"UX: {ux.Error}", missing);
    }

    private static IEnumerable<FrameworkElement> AiCards(AiResult ai)
    {
        var missing = Ramp.At(0.85);
        yield return Card("AI · 6 MODELS · INT8", "CPU · GPU · NPU", ai.Score, BenchColor.Ai,
            Metrics(AiTests.Accels.Select(a => { int pts = ai.AccelScore(a); return Metric($"{a.Label()} POINTS", pts > 0 ? pts.ToString("N0") : "—", pts > 0 ? Forge.Ink0 : missing); }).ToArray()),
            Note(ai.NpuRuntime != null ? $"NPU: {ai.NpuRuntime}. CPU, GPU and NPU count a third each." : $"NPU: {ai.NpuProblem ?? "not available"} — 0 points there."));
        foreach (var t in AiTests.All)
        {
            var col = Ui.VStack(8,
                Ui.VStack(2, Ui.Text($"AI TEST {t.Number()}", 9, BenchColor.Ai, Forge.W.Bold), Ui.Text(t.Title(), 13, Forge.Ink0, Forge.W.Black), Ui.Text(t.Model(), 8, Forge.Ink2)),
                Metrics(AiTests.Accels.Select(a =>
                {
                    double ips = ai.Ips(t, a);
                    return Metric($"{a.Label()} INF/S", ips > 0 ? (ips < 100 ? ips.F(1) : ips.N0()) : "—", ips > 0 ? Forge.Ink0 : missing);
                }).ToArray()));
            foreach (var run in ai.Runs.Where(r => r.Test == t && r.Error != null && r.Accel != AiAccel.Npu))
                col.Children.Add(Note($"{run.Accel.Label()}: {run.Error}", missing));
            var box = Ui.Box(col, Forge.Surface, 14, 0, BenchColor.Ai.WithAlpha(0.3));
            box.Padding = new Thickness(14, 12, 14, 12);
            yield return box;
        }
    }

    private static string TokensPerS(double v) => v > 0 ? (v < 100 ? v.F(1) : v.N0()) : "—";

    private static FrameworkElement LlmCard(LlmResult llm)
    {
        var missing = Ramp.At(0.85);
        var content = new List<UIElement>();
        foreach (var a in LlmAccels.All)
        {
            var run = llm.Run(a);
            content.Add(Metrics(
                Metric($"{a.Label()} PREFILL TOK/S", TokensPerS(run?.Prefill ?? 0), (run?.Prefill ?? 0) > 0 ? Forge.Ink0 : missing),
                Metric($"{a.Label()} DECODE TOK/S", TokensPerS(run?.Decode ?? 0), (run?.Decode ?? 0) > 0 ? Forge.Ink0 : missing),
                Metric(a == LlmAccel.Cpu ? "THREADS" : "GPU", a == LlmAccel.Cpu ? (run is { Threads: > 0 } ? run.Threads.ToString() : "—") : (llm.GpuName ?? "—")[..Math.Min(14, (llm.GpuName ?? "—").Length)], Forge.Ink1)));
            if (llm.Error == null && run?.Error is { } err) content.Add(Note($"{a.Label()}: {err}", missing));
        }
        if (llm.Error != null) content.Add(Note($"Not run: {llm.Error}.", missing));
        content.Add(Note(llm.LargestModel > 0
            ? $"Largest common model that fits in the memory Windows has free now: {llm.LargestModel}B (4-bit, {llm.FreeGb:0.0} GB)."
            : $"Memory free now: {llm.FreeGb:0.0} GB — too little for even a 1B model.", Forge.Ink1));
        content.Add(Note($"Prefill reads a 512-token prompt; decode writes the reply token by token. The faster of the CPU and the GPU counts for each: {LlmScore.RefPrefill:0} and {LlmScore.RefDecode:0.0} tokens/s = 6000 points. The weights come from a seed — the words are random, the work is real."));
        return Card("LLM · LLAMA 3.2 3B · 4-BIT", "PROMPT · REPLY", llm.Score, BenchColor.Llm, content.ToArray());
    }

    private FrameworkElement GpuCard(GpuTestResult g) => Card($"GPU TEST {g.Test.Number()}", g.Test.Title(), g.Points, BenchColor.Gpu(g.Test),
        g.Supported
            ? Metrics(Metric("AVG FPS", g.AvgFps.F(1)), Metric("1% LOW", g.LowFps.F(1)), Metric("STABILITY", $"{g.StabilityPct:0}%"), Metric("FRAMES", g.Frames.ToString()))
            : Note($"Not run: {result.RayTracingProblem ?? "the GPU has no ray tracing"} — 0 points."));

    private FrameworkElement Buttons(Action onDismiss)
    {
        var col = Ui.VStack(10, submitMessage);
        Button? copy = null;
        copy = Ui.Button("COPY REPORT", () =>
        {
            Clipboard.SetText(BenchmarkReport.Text(result, DateTime.Now.ToString("yyyy-MM-dd HH:mm", System.Globalization.CultureInfo.InvariantCulture)));
            copy!.Content = Ui.Text("COPIED", 11, Forge.Ink0, Forge.W.Black).Align(HorizontalAlignment.Center);
        }, Forge.Interact, Forge.Ink0, 11, Forge.W.Black, 10, 12);
        col.Children.Add(Ui.HStack(10, copy.Grow(), Ui.Button("DONE", onDismiss, Forge.Interact, Forge.Ink0, 11, Forge.W.Black, 10, 12).Grow()));
        if (record is { Complete: true } rec)
        {
            if (!result.IsVerified)
            {
                col.Children.Insert(0, Ui.Text("Only verified runs can be submitted. This one is kept on this PC for your own comparisons.", 9, Forge.Ink1,
                    wrap: true, align: TextAlignment.Center));
                return col.Pad(0, 6, 0, 0);
            }
            submit = Ui.Button("SUBMIT TO LEADERBOARD", () => _ = Submit(rec), Forge.PhaseMeasured, Forge.Bg, 12, Forge.W.Black, 12, 14);
            var offline = Ui.VStack(4, Ui.Text("NO INTERNET CONNECTION", 10, Ramp.At(0.5), Forge.W.Bold, align: TextAlignment.Center),
                Ui.Text("Turn on Wi-Fi or Ethernet to submit this result. Until then it's saved on this PC — you can submit it later from the leaderboard.",
                    9, Forge.Ink1, wrap: true, align: TextAlignment.Center));
            void Online()
            {
                bool on = NetworkMonitor.Shared.IsConnected;
                submit.Visibility = on && !submitted ? Visibility.Visible : Visibility.Collapsed;
                offline.Visibility = on || submitted ? Visibility.Collapsed : Visibility.Visible;
            }
            NetworkMonitor.Shared.Changed += Online;
            Unloaded += (_, _) => NetworkMonitor.Shared.Changed -= Online;
            Online();
            col.Children.Add(offline);
            col.Children.Add(submit);
        }
        return col.Pad(0, 6, 0, 0);
    }

    private async Task Submit(BenchmarkRecord rec)
    {
        submit!.IsEnabled = false;
        submit.Content = Ui.Text("SUBMITTING…", 12, Forge.Bg, Forge.W.Black).Align(HorizontalAlignment.Center);
        try
        {
            var r = await BenchmarkUpload.Submit(rec);
            BenchmarkHistoryStore.Shared.MarkSubmitted(rec.Id);
            submitMessage.Text = $"Submitted · {r.Score:N0} points · rank #{r.Rank}";
            submitted = true;
            submit.Visibility = Visibility.Collapsed;
        }
        catch (Exception e)
        {
            submitMessage.Text = e.Message;
            submit.IsEnabled = true;
            submit.Content = Ui.Text("SUBMIT TO LEADERBOARD", 12, Forge.Bg, Forge.W.Black).Align(HorizontalAlignment.Center);
        }
    }

    private static string ValidityNote(int flags)
    {
        var notes = new List<string>();
        if ((flags & BenchFlag.PowerEvent) != 0) notes.Add("the charger was plugged or unplugged during the run");
        if ((flags & BenchFlag.EnvCapped) != 0) notes.Add("battery saver or best power efficiency was on");
        if ((flags & (BenchFlag.CooldownTimeout | BenchFlag.WarmStarted)) != 0) notes.Add("started warm");
        if ((flags & BenchFlag.Interference) != 0) notes.Add("another window was in front during the run, or the PC was busy");
        return "Unverified: " + string.Join(", ", notes) + ".";
    }
}

/// <summary>The run against its model and the PC's own history; the model's statistics load in the background.</summary>
public sealed class HealthCard : ContentControl
{
    private readonly BenchmarkResult result;
    private readonly BenchmarkRecord? record;
    private ModelStats? stats;
    private bool loaded;

    public HealthCard(BenchmarkResult result, BenchmarkRecord? record)
    {
        this.result = result;
        this.record = record;
        Build();
        if (NetworkMonitor.Shared.IsConnected) _ = Load();
    }

    private async Task Load()
    {
        try { stats = await BenchmarkUpload.FetchModelStats(DeviceInfo.Model); } catch (Exception) { stats = null; }
        loaded = true;
        Build();
    }

    private void Build()
    {
        string model = DeviceInfo.Model;
        var report = PcHealth.Assess(result, model, stats,
            BenchmarkHistoryStore.Shared.Records.Where(r => r.Version == BenchmarkScore.Version).ToList(), record, DeviceInfo.OsVersion);
        var accent = report.Verdict switch
        {
            PcHealth.Verdict.Faster or PcHealth.Verdict.OnPar => Forge.PhaseMeasured,
            PcHealth.Verdict.Slower => Ramp.At(0.5),
            PcHealth.Verdict.MuchSlower => Ramp.At(0.85),
            _ => Forge.PhaseCooldown,
        };
        bool online = NetworkMonitor.Shared.IsConnected;
        string head = online && !loaded ? $"Comparing with other {model} units…"
            : online && stats == null ? "Couldn't reach our server to compare — your own runs below" : report.Headline;
        var top = Ui.HStack(8, Ui.VStack(2, Ui.Text("PC HEALTH", 9, accent, Forge.W.Bold), Ui.Text(head, 12, Forge.Ink0, Forge.W.Black, wrap: true)).Grow());
        if (report.Percent is { } p)
            top.Children.Add(Ui.VStack(2, Ui.Text($"{p}%", 18, accent, Forge.W.Black, align: TextAlignment.Right), Ui.Text("OF TYPICAL", 7, Forge.Ink2, align: TextAlignment.Right)));
        var col = Ui.VStack(8, top);
        if (report.Percent != null && report.Median is { } median)
        {
            col.Children.Add(new SpreadBar(report.Total, median, report.P25 ?? median, report.P75 ?? median, report.Best ?? median, accent));
            col.Children.Add(Ui.Text($"{report.Runs} verified runs of this model · middle half {report.P25 ?? 0:N0}–{report.P75 ?? 0:N0} · best {report.Best ?? 0:N0}", 7, Forge.Ink2));
        }
        foreach (var f in report.Findings)
        {
            var mark = Ui.Text(f.Severity == 2 ? "▲" : f.Severity == 1 ? "•" : "·", 9, f.Severity == 2 ? Ramp.At(0.85) : f.Severity == 1 ? Ramp.At(0.5) : Forge.Ink2);
            col.Children.Add(Ui.HStack(4, mark, Ui.Text(f.Text, 9, f.Severity > 0 ? Forge.Ink0 : Forge.Ink1, wrap: true).Grow()));
        }
        var b = Ui.Box(col, Forge.Surface, 14, 0, accent.WithAlpha(0.45));
        b.Padding = new Thickness(14, 12, 14, 12);
        Content = b;
    }
}

/// <summary>The model's middle half as a band, its median as a tick, this run as a marker — on one scale.</summary>
public sealed class SpreadBar : Instrument
{
    private readonly int total, median, p25, p75, best;
    private readonly Color accent;
    private readonly long start = Environment.TickCount64;

    public SpreadBar(int total, int median, int p25, int p75, int best, Color accent)
    {
        this.total = total; this.median = median; this.p25 = p25; this.p75 = p75; this.best = best; this.accent = accent;
        Height = 22;
    }

    protected override bool Animating => Environment.TickCount64 - start < 1000;

    protected override void OnRender(DrawingContext dc)
    {
        double lo = Math.Min(p25, total) * 0.85, hi = Math.Max(best, total) * 1.05, w = ActualWidth, h = ActualHeight;
        double f = Math.Min(1, (Environment.TickCount64 - start) / 900.0), eased = 1 - Math.Pow(1 - f, 3);
        double shown = lo + (total - lo) * eased;
        double X(double v) => Math.Clamp((v - lo) / (hi - lo), 0, 1) * w;
        double mid = h * 0.62;
        dc.DrawRoundedRectangle(Forge.Ink0.WithAlpha(0.07).Brush(), null, new Rect(0, mid - 3, w, 6), 3, 3);
        dc.DrawRoundedRectangle(Forge.Ink0.WithAlpha(0.22).Brush(), null, new Rect(X(p25), mid - 3, Math.Max(2, X(p75) - X(p25)), 6), 3, 3);
        dc.DrawLine(Forge.Ink0.WithAlpha(0.7).Pen(2), new Point(X(median), mid - 6), new Point(X(median), mid + 6));
        double x = X(shown);
        var tri = new StreamGeometry();
        using (var c = tri.Open())
        {
            c.BeginFigure(new Point(x, mid - 4), true, true);
            c.LineTo(new Point(x - 5, 0), false, false);
            c.LineTo(new Point(x + 5, 0), false, false);
        }
        tri.Freeze();
        dc.DrawGeometry(accent.Brush(), null, tri);
        dc.DrawEllipse(accent.Brush(), null, new Point(x, mid), 4, 4);
    }
}
