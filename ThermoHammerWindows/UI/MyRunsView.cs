using System.Diagnostics;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>
/// MY RUNS — every benchmark run kept on this PC, newest first (Android's MyRunsOverlay): the score over time, each run's six
/// sections, and a submit for a verified run that hasn't been sent. It reads and writes the history itself, so the home card and the
/// leaderboard open it the same way; Esc, ✕ or a click beside the card closes it.
/// </summary>
public sealed class MyRunsView : Grid, IDisposable
{
    private const double CardWidth = 700;
    private readonly ContentControl body = new();
    private readonly Action close;
    private readonly Action? changed;
    private Guid? open;                         // the run whose sections are showing
    private Guid? submittingId;
    private (Guid id, string text)? note;       // what a run's submit answered

    /// <summary>Opens MY RUNS over everything; [changed]: a run was submitted from it (the caller shows it).</summary>
    public static void Open(Action? changed = null)
    {
        Action close = () => { };
        close = Sheets.Show(new MyRunsView(() => close(), changed), dismissOnScrim: true);
    }

    private MyRunsView(Action close, Action? changed)
    {
        this.close = close;
        this.changed = changed;
        Children.Add(Overlays.Card(body, CardWidth));
        NetworkMonitor.Shared.Changed += Render;
        Render();
    }

    public void Dispose() => NetworkMonitor.Shared.Changed -= Render;

    private static string DateTimeText(DateTime t) => t.ToString("d MMM yyyy · HH:mm", CultureInfo.InvariantCulture);
    private static string DateText(DateTime t) => t.ToString("d MMM yyyy", CultureInfo.InvariantCulture);
    private static string? Os(string? version) => version is { Length: > 0 } v ? DeviceInfo.OsLabel(DeviceInfo.OsPlatform, v) : null;

    private void Render()
    {
        var h = MyRuns.Build(BenchmarkHistoryStore.Shared.Records, DeviceInfo.Model);
        var x = Ui.Button(Ui.Text("✕", 12, Forge.Ink0, Forge.W.Bold), close, Colors.White.WithAlpha(0.08), 16, 10, 6)
            .Align(HorizontalAlignment.Right, VerticalAlignment.Top);
        var head = Ui.HStack(10, Ui.VStack(2, Ui.Text("MY RUNS", 16, Forge.Ink0, Forge.W.Black),
            Ui.Text($"Every benchmark on this {ShareLinks.Device}", 10, Forge.Ink0.WithAlpha(0.4))).Grow(), x);
        head.Stretch = false;
        var col = Ui.VStack(8, head, Ui.Line(Colors.White.WithAlpha(0.1)).Pad(0, 8, 0, 8));
        if (h.Rows.Count == 0)
        {
            col.Children.Add(Ui.Text("No benchmark runs yet", 13, Forge.Ink0, Forge.W.Bold, align: TextAlignment.Center).Pad(0, 30, 0, 0));
            col.Children.Add(Ui.Text($"Run the benchmark and every result is kept here, so you can see how your {ShareLinks.Device} does over time.",
                10, Forge.Ink1, wrap: true, align: TextAlignment.Center));
            foreach (var line in MyRuns.Footnotes(h).SkipLast(1))
                col.Children.Add(Ui.Text(line, 9, Forge.Ink2, wrap: true, align: TextAlignment.Center).Pad(0, 6, 0, 0));
            col.Children.Add(new FrameworkElement { Height = 30 });
        }
        else
        {
            col.Children.Add(Summary(h));
            if (h.Rows.Count >= 2) col.Children.Add(Chart(h));
            col.Children.Add(Ui.Text("CLICK A RUN FOR ITS SECTIONS", 8, Forge.Ink0.WithAlpha(0.3)).Pad(4, 4, 0, 0));
            foreach (var row in h.Rows) col.Children.Add(RunRow(row));
            var notes = Ui.VStack(6);
            foreach (var line in MyRuns.Footnotes(h)) notes.Children.Add(Ui.Text(line, 9, Forge.Ink2, wrap: true));
            col.Children.Add(notes.Pad(4, 8, 4, 0));
        }
        body.Content = col;
    }

    // ── BEST · LATEST · RUNS ──

    private static FrameworkElement Summary(MyRuns.History h)
    {
        static FrameworkElement Cell(string label, string value, string sub, Color color) => Ui.VStack(2,
            Ui.Text(label, 8, Forge.Ink0.WithAlpha(0.4), Forge.W.Black), Ui.Text(value, 17, color, Forge.W.Black),
            Ui.Text(sub, 8, Forge.Ink0.WithAlpha(0.4))).Grow();
        var row = Ui.HStack(8,
            h.Best is { } b ? Cell("BEST", b.Score.N0(), DateText(b.Timestamp), Forge.PhaseMeasured) : null,
            h.Latest is { } l ? Cell("LATEST", l.Score.N0(), DateText(l.Timestamp), Forge.Ink0) : null,
            Cell("RUNS", h.Rows.Count.ToString(CultureInfo.InvariantCulture), $"{h.Verified} verified", Forge.Ink0));
        row.Stretch = false;
        return Ui.Box(row, Colors.White.WithAlpha(0.03), 16, 14);
    }

    // ── the score over time ──

    private static FrameworkElement Chart(MyRuns.History h)
    {
        var runs = h.Chronological;
        int hi = runs.Max(r => r.Record.Score), lo = runs.Min(r => r.Record.Score);
        var title = Ui.HStack(8, Ui.Text("SCORE OVER TIME", 8, Forge.Ink0.WithAlpha(0.4), Forge.W.Black).Grow(),
            Ui.Text($"{lo.N0()} – {hi.N0()}", 8, Forge.Ink0.WithAlpha(0.4)));
        var dates = Ui.HStack(8, Ui.Text(DateText(runs[0].Record.Timestamp), 8, Forge.Ink0.WithAlpha(0.4)).Grow(),
            Ui.Text(DateText(runs[^1].Record.Timestamp), 8, Forge.Ink0.WithAlpha(0.4)));
        return Ui.Box(Ui.VStack(6, title, new ScoreChart(runs) { Height = 110 }, dates,
            Ui.Text("● verified   ○ unverified   ◎ best", 8, Forge.Ink0.WithAlpha(0.3))), Colors.White.WithAlpha(0.03), 16, 14);
    }

    /// <summary>Total score per run, oldest to newest, evenly spaced: filled dots are verified runs, hollow ones unverified, the ring the best.</summary>
    private sealed class ScoreChart(IReadOnlyList<MyRuns.Row> runs) : FrameworkElement
    {
        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, ht = ActualHeight, pad = 8;
            if (runs.Count < 2 || w <= 2 * pad) return;
            int hi = runs.Max(r => r.Record.Score), lo = runs.Min(r => r.Record.Score);
            double span = hi - lo;
            // a flat history sits mid-height; otherwise the lowest run near the bottom, the highest near the top
            double Y(int score) => span <= 0 ? ht / 2 : pad + (ht - 2 * pad) * (1 - (score - lo) / span);
            double X(int i) => pad + (w - 2 * pad) * i / (runs.Count - 1);
            var grid = Colors.White.WithAlpha(0.05).Pen(1);
            for (int g = 0; g <= 2; g++)
            {
                double gy = pad + (ht - 2 * pad) * g / 2;
                dc.DrawLine(grid, new Point(0, gy), new Point(w, gy));
            }
            var path = new StreamGeometry();
            using (var ctx = path.Open())
            {
                ctx.BeginFigure(new Point(X(0), Y(runs[0].Record.Score)), false, false);
                for (int i = 1; i < runs.Count; i++) ctx.LineTo(new Point(X(i), Y(runs[i].Record.Score)), true, true);
            }
            path.Freeze();
            var line = new Pen(Forge.PhaseMeasured.WithAlpha(0.6).Brush(), 2)
                { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, LineJoin = PenLineJoin.Round };
            line.Freeze();
            dc.DrawGeometry(null, line, path);
            const double dot = 3.5;
            var ring = Forge.PhaseMeasured.Pen(1.5);
            var hollow = Forge.Ink2.Pen(1.5);
            for (int i = 0; i < runs.Count; i++)
            {
                var c = new Point(X(i), Y(runs[i].Record.Score));
                if (runs[i].IsBest) dc.DrawEllipse(null, ring, c, dot * 2, dot * 2);
                if (runs[i].Record.ValidityFlags == 0) dc.DrawEllipse(Forge.PhaseMeasured.Brush(), null, c, dot, dot);
                else dc.DrawEllipse(Forge.Raised.Brush(), hollow, c, dot, dot);
            }
        }
    }

    // ── one run ──

    private FrameworkElement RunRow(MyRuns.Row row)
    {
        var r = row.Record;
        bool isOpen = open == r.Id;
        var score = Ui.HStack(0, Ui.Text(r.Score.N0(), 15, Forge.Ink0, Forge.W.Black),
            row.IsBest ? Ui.Text("  BEST", 8, Forge.PhaseMeasured, Forge.W.Black).Align(HorizontalAlignment.Left, VerticalAlignment.Bottom).Pad(0, 0, 0, 2) : null);
        score.Stretch = false;
        var meta = Ui.Text(string.Join(" · ", new[] { DateTimeText(r.Timestamp), Os(r.OsVersion) }.Where(s => s != null)), 8, Forge.Ink0.WithAlpha(0.4));
        var top = Ui.HStack(8, Ui.VStack(2, score, meta).Grow(),
            row.ChangePct is { } pct ? Ui.Text(MyRuns.ChangeText(pct), 10, pct >= 0.05 ? Forge.PhaseMeasured : pct <= -0.05 ? Ramp.At(0.85) : Forge.Ink2, Forge.W.Bold) : null,
            Chip(row.Status));
        top.Stretch = false;
        foreach (FrameworkElement c in top.Children) c.VerticalAlignment = VerticalAlignment.Center;
        var col = Ui.VStack(0, top);
        if (isOpen)
        {
            col.Children.Add(Sections(r.Sections()).Pad(0, 10, 0, 0));
            col.Children.Add(Ui.Text(string.Join(" · ", new[] { Os(r.OsVersion), r.OsBuild, r.GpuName }.Where(s => !string.IsNullOrEmpty(s))),
                8, Forge.Ink0.WithAlpha(0.4), wrap: true).Pad(0, 8, 0, 0));
            if (r.ValidityFlags != 0)
                col.Children.Add(Ui.Text(BenchmarkResultView.ValidityNote(r.ValidityFlags) + " Only verified runs can go to the leaderboard.", 9,
                    Ramp.At(0.5), wrap: true).Pad(0, 6, 0, 0));
            // Windows sends complete runs only, as the result screen and the home card do
            bool sendable = row.CanSubmit && r.Complete;
            if (row.CanSubmit && !r.Complete)
                col.Children.Add(Ui.Text("Not every section ran — this run can't go to the leaderboard.", 9, Ramp.At(0.5), wrap: true).Pad(0, 8, 0, 0));
            else if (sendable && submittingId == r.Id)
                col.Children.Add(new Spinner(Forge.PhasePreFlight, 18).Align(HorizontalAlignment.Left).Pad(0, 10, 0, 0));
            else if (sendable && NetworkMonitor.Shared.IsConnected)
                col.Children.Add(Pill("SUBMIT ↑", Forge.PhasePreFlight, () => _ = Submit(r), submittingId == null).Pad(0, 10, 0, 0));
            else if (sendable)
                col.Children.Add(Ui.Text("Not on the leaderboard yet. Turn on the internet to submit it.", 9, Ramp.At(0.5), wrap: true).Pad(0, 8, 0, 0));
            else if (r.Submitted && r.ServerId is { } id)
                col.Children.Add(Pill("↗ OPEN ITS PAGE", Forge.PhaseMeasured, () => OpenPage(id)).Pad(0, 10, 0, 0));
            if (note is { } n && n.id == r.Id)
                col.Children.Add(Ui.Text(n.text, 9, Forge.Ink1, wrap: true).Pad(0, 6, 0, 0));
        }
        var b = Ui.Box(col, Forge.Surface, 14, 12, row.IsBest ? Forge.PhaseMeasured.WithAlpha(0.6) : Forge.Ink0.WithAlpha(0.06));
        b.Cursor = Cursors.Hand;
        // a click on SUBMIT or OPEN is the button's (it marks the release handled), so it doesn't fold the run as well
        b.MouseLeftButtonUp += (_, _) => { open = isOpen ? null : r.Id; Render(); };
        return b;
    }

    private static FrameworkElement Chip(MyRuns.RunStatus status)
    {
        var (label, color) = status switch
        {
            MyRuns.RunStatus.Submitted => ("SUBMITTED", Forge.PhaseMeasured),
            MyRuns.RunStatus.NotSubmitted => ("NOT SUBMITTED", Ramp.At(0.5)),
            _ => ("UNVERIFIED", Forge.Ink2),
        };
        var b = Ui.Box(Ui.Text(label, 7, color, Forge.W.Black), color.WithAlpha(0.12), 6);
        b.Padding = new Thickness(6, 4, 6, 4);
        return b;
    }

    /// <summary>The six section scores, two rows of three, each in its section's colour.</summary>
    private static FrameworkElement Sections(SectionScores s)
    {
        (string label, int score, Color color)[] cells =
        [
            ("CPU", s.Cpu, BenchColor.Cpu), ("MEM", s.Mem, BenchColor.Mem), ("GPU", s.Gpu, Forge.PhaseCalibration),
            ("UX", s.Ux, Forge.PhaseMeasured), ("AI", s.Ai, BenchColor.Ai), ("LLM", s.Llm, BenchColor.Llm),
        ];
        var col = Ui.VStack(6);
        foreach (var line in cells.Chunk(3))
        {
            var row = Ui.HStack(6);
            foreach (var (label, score, color) in line)
            {
                var cell = Ui.Box(Ui.VStack(2, Ui.Text(label, 8, color, Forge.W.Black), Ui.Text(score.N0(), 12, Forge.Ink0, Forge.W.Black)),
                    color.WithAlpha(0.08), 8);
                cell.Padding = new Thickness(8, 6, 8, 6);
                row.Children.Add(cell.Grow());
            }
            col.Children.Add(row);
        }
        return col;
    }

    private static Button Pill(string label, Color color, Action act, bool enabled = true) =>
        Ui.Button(label, act, color.WithAlpha(0.1), color, 9, Forge.W.Black, 8, 9, enabled, stroke: color.WithAlpha(0.5));

    private static void OpenPage(int serverId)
    {
        try { Process.Start(new ProcessStartInfo(ShareLinks.Run(serverId)) { UseShellExecute = true }); }
        catch (Exception) { /* no browser to open it with */ }
    }

    private async Task Submit(BenchmarkRecord r)
    {
        if (submittingId != null) return;
        submittingId = r.Id;
        note = null;
        Render();
        try
        {
            var res = await BenchmarkUpload.Submit(r);
            BenchmarkHistoryStore.Shared.MarkSubmitted(r.Id, res.Id);
            note = (r.Id, $"Submitted · rank #{res.Rank}");
            changed?.Invoke();
        }
        catch (Exception e) { note = (r.Id, $"Submit failed: {e.Message}"); }
        submittingId = null;
        Render();
    }
}
