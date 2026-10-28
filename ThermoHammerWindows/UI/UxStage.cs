using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using ThermoHammer.Engine;
using ThermoHammer.Engine.Ux;

namespace ThermoHammer.UI;

// ─────────────────────────────────────────────────────────────────────────────
// UX STAGE — what the window shows during the UX tests: the browser tests' page
// (WebView2), the QR frame under the scanner, the photo editor's four panels.
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>
/// The web tests (UX 1–3): the pages of the phone apps (assets\web) in WebView2. The pages are laid out for a 1280×720
/// viewport and the phones render them at 1920×1080 physical pixels (a device pixel ratio of 1.5); this does the
/// same — 1920×1080 physical pixels when the window has room, the same view scaled down when it doesn't. The pages
/// fetch `media/…` and report through `window.ThermoBench`, shimmed onto WebView2's message channel; both are served
/// from the app's files under https://thbench.local/.
/// </summary>
public sealed class WebStage : Grid
{
    private const string Host = "thbench.local";
    private const double CssWidth = 1280, PixelW = 1920;

    /// <summary>The page's channel to the app — the same calls the phones' pages make on their JavaScript interface.</summary>
    private const string Shim = """
        window.ThermoBench = {
          progress: function (scene, fraction, complexity, value, index) {
            window.chrome.webview.postMessage({ k: 'p', s: String(scene), f: +fraction, c: +complexity, v: +value, i: +index });
          },
          done: function (json) { window.chrome.webview.postMessage({ k: 'd', j: String(json) }); },
          fail: function (message) { window.chrome.webview.postMessage({ k: 'f', m: String(message) }); }
        };
        """;

    private readonly BenchmarkEngine engine;
    private WebView2? web;
    private CoreWebView2Environment? env;
    private string? page;
    private int generation;
    private readonly TextBlock note = Ui.Text("", 10, Forge.Ink1, align: TextAlignment.Center);

    public WebStage(BenchmarkEngine engine)
    {
        this.engine = engine;
        Background = Brushes.Black;
        note.HorizontalAlignment = HorizontalAlignment.Center;
        note.VerticalAlignment = VerticalAlignment.Center;
        Children.Add(note);
        SizeChanged += (_, _) => Fit();
    }

    /// <summary>The page to show now (null = none); `keep` leaves the browser running between the three pages.</summary>
    public void Show(string? name, bool keep)
    {
        if (name == page && (name != null || keep)) return;
        if (name == null)
        {
            page = null;
            if (!keep) Teardown();
            return;
        }
        page = name;
        _ = Load(name, ++generation);
    }

    private void Fit()
    {
        if (web == null || ActualWidth < 4 || ActualHeight < 4) return;
        double d = VisualTreeHelper.GetDpi(this).DpiScaleX;
        double aw = ActualWidth * d, ah = ActualHeight * d;
        double pw = Math.Max(160, Math.Min(PixelW, Math.Min(aw, ah * 16 / 9)));
        web.Width = pw / d;
        web.Height = pw * 9 / 16 / d;
        // the page is laid out CssWidth wide: at pw physical pixels that is a device pixel ratio of pw / CssWidth
        web.ZoomFactor = Math.Clamp(pw / (CssWidth * d), 0.25, 5);
    }

    private async Task Load(string name, int gen)
    {
        try
        {
            if (web == null)
            {
                note.Text = "STARTING THE BROWSER…";
                string data = Path.Combine(AppData.Dir, "WebView2");
                env ??= await CoreWebView2Environment.CreateAsync(null, data);
                var control = new WebView2
                {
                    DefaultBackgroundColor = System.Drawing.Color.FromArgb(0x0B, 0x0E, 0x12),
                    HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                };
                Children.Add(control);
                web = control;
                Fit();
                await control.EnsureCoreWebView2Async(env);
                var core = control.CoreWebView2;
                core.Settings.AreDefaultContextMenusEnabled = false;
                core.Settings.AreDevToolsEnabled = false;
                core.Settings.IsStatusBarEnabled = false;
                core.Settings.IsZoomControlEnabled = false;
                core.Settings.IsPinchZoomEnabled = false;
                core.Settings.IsSwipeNavigationEnabled = false;
                await core.AddScriptToExecuteOnDocumentCreatedAsync(Shim);
                core.AddWebResourceRequestedFilter($"https://{Host}/*", CoreWebView2WebResourceContext.All);
                core.WebResourceRequested += Serve;
                core.WebMessageReceived += OnMessage;
                core.ProcessFailed += (_, e) => engine.WebFinished(null, $"the browser's {e.ProcessFailedKind} process failed");
                core.NavigationCompleted += (_, e) =>
                {
                    if (!e.IsSuccess) engine.WebFinished(null, $"the page didn't load ({e.WebErrorStatus})");
                };
                Fit();
            }
            if (gen != generation || web?.CoreWebView2 == null) return;
            note.Text = "";
            web.CoreWebView2.Navigate($"https://{Host}/{name}");
        }
        catch (Exception e) when (e is WebView2RuntimeNotFoundException)
        {
            engine.WebFinished(null, "Microsoft Edge WebView2 isn't installed on this PC");
        }
        catch (Exception e)
        {
            engine.WebFinished(null, e.Message);
        }
    }

    private void Teardown()
    {
        generation++;
        if (web == null) return;
        Children.Remove(web);
        web.Dispose();
        web = null;
        env = null;
        note.Text = "";
    }

    private void OnMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        try
        {
            using var doc = JsonDocument.Parse(e.WebMessageAsJson);
            var o = doc.RootElement;
            switch (o.GetProperty("k").GetString())
            {
                case "p":
                    engine.WebProgress(o.GetProperty("s").GetString() ?? "", o.GetProperty("f").GetDouble(), (int)o.GetProperty("c").GetDouble(),
                        o.GetProperty("v").GetDouble(), (int)o.GetProperty("i").GetDouble());
                    break;
                case "d": engine.WebFinished(o.GetProperty("j").GetString() ?? "", null); break;
                default: engine.WebFinished(null, o.GetProperty("m").GetString() ?? "the page failed"); break;
            }
        }
        catch (Exception) { /* a message the app doesn't know */ }
    }

    private static string Mime(string file) => Path.GetExtension(file).ToLowerInvariant() switch
    {
        ".html" => "text/html; charset=utf-8", ".js" => "text/javascript", ".css" => "text/css", ".json" => "application/json",
        ".png" => "image/png", ".jpg" or ".jpeg" => "image/jpeg", ".webp" => "image/webp", ".avif" => "image/avif",
        ".mp4" => "video/mp4", ".webm" => "video/webm", _ => "application/octet-stream",
    };

    /// <summary>https://thbench.local/&lt;page&gt; from assets\web, https://thbench.local/media/&lt;file&gt; from assets\media.</summary>
    private void Serve(object? sender, CoreWebView2WebResourceRequestedEventArgs e)
    {
        var uri = new Uri(e.Request.Uri);
        string rel = Uri.UnescapeDataString(uri.AbsolutePath.TrimStart('/'));
        string root = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "assets"));
        string path = Path.GetFullPath(Path.Combine(root, rel.StartsWith("media/", StringComparison.Ordinal) ? rel : Path.Combine("web", rel)));
        var core = web!.CoreWebView2;
        if (!path.StartsWith(root, StringComparison.OrdinalIgnoreCase) || !File.Exists(path))
        {
            e.Response = core.Environment.CreateWebResourceResponse(null, 404, "Not Found", "");
            return;
        }
        e.Response = core.Environment.CreateWebResourceResponse(File.OpenRead(path), 200, "OK",
            $"Content-Type: {Mime(path)}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store");
    }
}

/// <summary>A frame handed over as BGRA bytes, fitted into the stage; `Overlay` draws over it in frame pixels.</summary>
public class BgraFrameView : FrameworkElement
{
    private WriteableBitmap? bitmap;
    private int w, h;
    public Action<DrawingContext, double, double, double>? Overlay;   // (dc, offsetX, offsetY, scale)
    public double AspectRatio { get; set; } = 16.0 / 9;

    public void Set(byte[]? bgra, int width, int height)
    {
        if (bgra == null) { bitmap = null; InvalidateVisual(); return; }
        if (bitmap == null || w != width || h != height)
        {
            w = width; h = height;
            bitmap = new WriteableBitmap(width, height, 96, 96, PixelFormats.Bgr32, null);
        }
        bitmap.WritePixels(new Int32Rect(0, 0, width, height), bgra, width * 4, 0);
        InvalidateVisual();
    }

    protected override Size MeasureOverride(Size s)
    {
        double bw = double.IsInfinity(s.Width) ? 900 : s.Width, bh = double.IsInfinity(s.Height) ? bw / AspectRatio : s.Height;
        bw = Math.Min(bw, bh * AspectRatio);
        return new Size(bw, bw / AspectRatio);
    }

    protected override void OnRender(DrawingContext dc)
    {
        var r = new Rect(0, 0, ActualWidth, ActualHeight);
        dc.PushClip(new RectangleGeometry(r, 10, 10));
        dc.DrawRectangle(Brushes.Black, null, r);
        if (bitmap != null)
        {
            double s = Math.Min(r.Width / w, r.Height / h), ox = (r.Width - w * s) / 2, oy = (r.Height - h * s) / 2;
            dc.DrawImage(bitmap, new Rect(ox, oy, w * s, h * s));
            Overlay?.Invoke(dc, ox, oy, s);
        }
        dc.Pop();
    }
}

/// <summary>The QR test's frame under the scanner, and the code the scanner found in it.</summary>
public sealed class QrStageView : BgraFrameView
{
    private (float x, float y)[]? found;
    private static readonly Color Accent = BenchColor.Ux(UxTest.Qr);

    public QrStageView() { Overlay = DrawFound; }

    public void Update(BenchmarkEngine engine)
    {
        found = engine.QrFound;
        Set(engine.QrFrame, 640, 360);
    }

    private void DrawFound(DrawingContext dc, double ox, double oy, double scale)
    {
        if (found is not { Length: >= 3 } pts) return;
        // frame pixels (1280×720) onto the fitted preview (640×360 scaled by `scale`)
        var mapped = pts.Select(p => new Point(ox + p.x / 2 * scale, oy + p.y / 2 * scale)).ToArray();
        var geo = new StreamGeometry();
        using (var c = geo.Open())
        {
            c.BeginFigure(mapped[0], false, true);
            foreach (var p in mapped.Skip(1)) c.LineTo(p, true, false);
        }
        geo.Freeze();
        dc.DrawGeometry(null, Accent.WithAlpha(0.8).Pen(2), geo);
        foreach (var p in mapped) dc.DrawEllipse(null, Accent.Pen(2), p, 5, 5);
    }
}


/// <summary>The video test: the decoded frame with a player's chrome over it — timecode, scrub bar, format, bitrate and how far past real time it runs.</summary>
public sealed class VideoStageView : BgraFrameView
{
    private static readonly Color Accent = BenchColor.Ux(UxTest.Video);
    private VideoOsd? osd;
    private int shown = -1;

    public VideoStageView() { Overlay = DrawOsd; }

    public void Update(BenchmarkEngine engine)
    {
        osd = engine.VideoOsd;
        if (engine.VideoFrameNumber != shown && engine.VideoFrame is { } f)
        {
            shown = engine.VideoFrameNumber;
            Set(f.Bgra, f.Width, f.Height);
        }
        else InvalidateVisual();
    }

    private static string Tc(double s) => $"{(int)(s / 60):00}:{s % 60:00.00}";

    private void DrawOsd(DrawingContext dc, double ox, double oy, double scale)
    {
        if (osd is not { } o) return;
        var r = new Rect(10, ActualHeight - 62, ActualWidth - 20, 52);
        dc.DrawRoundedRectangle(Color.FromArgb(158, 6, 9, 11).Brush(), null, r, 10, 10);
        double y = r.Y + 9;
        dc.DrawText("▶", 8, Accent, new Point(r.X + 12, y), w: Forge.W.Bold);
        dc.DrawText($"{Tc(o.PositionS)} / {Tc(o.DurationS)}", 8, Forge.Ink0, new Point(r.X + 28, y), w: Forge.W.Bold);
        string speed = o.Speed > 0 ? $"×{o.Speed:0.0} REAL TIME" : "×— REAL TIME";
        var sp = Forge.Text(speed, 8, Forge.Bg, Forge.W.Bold);
        double right = r.Right - 12;
        var pill = new Rect(right - sp.WidthIncludingTrailingWhitespace - 12, y - 3, sp.WidthIncludingTrailingWhitespace + 12, sp.Height + 6);
        dc.DrawRoundedRectangle(Accent.Brush(), null, pill, 5, 5);
        dc.DrawText(sp, new Point(pill.X + 6, pill.Y + 3));
        dc.DrawText($"{o.Mbps} MBPS", 8, Forge.Ink1, new Point(pill.X - 10, y), 1, 0, Forge.W.Bold);
        var fmt = Forge.Text($"{o.Format}", 8, Forge.Ink1, Forge.W.Bold);
        dc.DrawText(fmt, new Point(pill.X - 10 - 70 - fmt.WidthIncludingTrailingWhitespace, y));
        double track = r.Width - 24, f = o.DurationS > 0 ? Math.Clamp(o.PositionS / o.DurationS, 0, 1) : 0, ty = r.Bottom - 14;
        dc.DrawRoundedRectangle(Forge.Ink2.WithAlpha(0.35).Brush(), null, new Rect(r.X + 12, ty - 1.5, track, 3), 1.5, 1.5);
        dc.DrawRoundedRectangle(Accent.Brush(), null, new Rect(r.X + 12, ty - 1.5, track * f, 3), 1.5, 1.5);
        dc.DrawEllipse(Forge.Ink0.Brush(), null, new Point(r.X + 12 + track * f, ty), 4.5, 4.5);
    }
}


/// <summary>An editor's export sheet: the file, its format and encoder, a progress bar of the frames written (the job is a fixed timeline, so this is the export's real progress), time spent and left, the step, and the render queue.</summary>
public sealed class ExportDialog : Instrument
{
    private static readonly Color Accent = BenchColor.Ux(UxTest.Export);
    private ExportStage? stage;
    public void Update(ExportStage? st) { stage = st; InvalidateVisual(); }
    protected override bool Animating => false;
    protected override Size MeasureOverride(Size s) => new(Math.Min(double.IsInfinity(s.Width) ? 640 : s.Width, 640), 150);

    protected override void OnRender(DrawingContext dc)
    {
        var r = new Rect(0, 0, ActualWidth, ActualHeight);
        dc.DrawRoundedRectangle(Forge.Surface.WithAlpha(0.94).Brush(), Accent.WithAlpha(0.35).Pen(1), r, 14, 14);
        if (stage is not { } st) return;
        var j = st.Job;
        double x = 16, w = r.Width - 32, y = 13;
        int active = Math.Max(0, st.Queue.FindIndex(q => q.Job == j));
        bool ok = st.Step is "ENCODING" or "FINISHING THE FILE" or "CHECKING QUALITY";
        dc.DrawText($"⤓ EXPORTING  {active + 1} OF {st.Queue.Count}", 8, Accent, new Point(x, y), w: Forge.W.Bold, tracking: 1);
        var sp = Forge.Text(st.SpeedText, 8, Forge.Bg, Forge.W.Bold);
        var pill = new Rect(x + w - sp.WidthIncludingTrailingWhitespace - 12, y - 3, sp.WidthIncludingTrailingWhitespace + 12, sp.Height + 6);
        dc.DrawRoundedRectangle(Accent.Brush(), null, pill, 5, 5);
        dc.DrawText(sp, new Point(pill.X + 6, pill.Y + 3));
        y += 24;
        dc.DrawText(j.FileName(), 12, Forge.Ink0, new Point(x, y), w: Forge.W.Black);
        y += 20;
        dc.DrawText($"{j.Label()} · {j.Width()}×{j.Height()} · {j.Fps()} FPS · {j.Mbps()} MBPS · {(st.Hardware ? "HARDWARE" : "SOFTWARE")}", 8, Forge.Ink1, new Point(x, y));
        y += 22;
        double barW = w - 56;
        dc.DrawRoundedRectangle(Forge.Ink2.WithAlpha(0.3).Brush(), null, new Rect(x, y, barW, 11), 5.5, 5.5);
        if (st.Progress > 0.001)
            dc.DrawRoundedRectangle(new LinearGradientBrush(Accent.WithAlpha(0.7), Accent, 0), null, new Rect(x, y, Math.Max(11, barW * st.Progress), 11), 5.5, 5.5);
        dc.DrawText($"{(int)(st.Progress * 100)}%", 15, Forge.Ink0, new Point(x + w, y - 3), 1, 0, Forge.W.Black);
        y += 22;
        dc.DrawText($"{st.FramesDone} / {j.Frames()} FRAMES · {st.ElapsedS:0.0} S · {st.TimeLeftText}{st.Step}", 8, ok ? Forge.Ink1 : Ramp.At(0.85), new Point(x, y));
        y += 22;
        dc.DrawLine(Forge.Hairline.Pen(1), new Point(x, y), new Point(x + w, y));
        y += 9;
        double colW = w / Math.Max(1, st.Queue.Count);
        for (int i = 0; i < st.Queue.Count; i++)
        {
            var item = st.Queue[i];
            string mark = item.State switch { ExportStage.State.Done => "✓", ExportStage.State.Active => "●", ExportStage.State.Skipped => "✗", _ => "○" };
            var color = item.State switch { ExportStage.State.Done => Accent, ExportStage.State.Active => Forge.Ink0, ExportStage.State.Skipped => Ramp.At(0.85), _ => Forge.Ink2 };
            dc.DrawText($"{mark} {item.Job.Res()} {item.Job.Label()}", 8, color, new Point(x + i * colW, y), w: Forge.W.Bold);
            string sub = item.State switch
            {
                ExportStage.State.Done => $"{item.Fps:0} FPS · {item.Psnr:0.0} dB",
                ExportStage.State.Active => st.Fps > 0 ? $"{st.Fps:0} FPS" : "…",
                ExportStage.State.Skipped => "0 POINTS", _ => "QUEUED",
            };
            dc.DrawText(sub, 7, Forge.Ink2, new Point(x + i * colW, y + 13));
        }
    }
}

/// <summary>The export test: the frame being encoded, and an editor's export sheet under it.</summary>
public sealed class ExportStageView : Grid
{
    private readonly BgraFrameView frame = new();
    private readonly ExportDialog dialog = new();
    private int shown = -1;

    public ExportStageView()
    {
        RowDefinitions.Add(new RowDefinition());
        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        frame.Margin = new Thickness(0, 0, 0, 12);
        frame.VerticalAlignment = VerticalAlignment.Center;
        frame.HorizontalAlignment = HorizontalAlignment.Center;
        SetRow(dialog, 1);
        dialog.HorizontalAlignment = HorizontalAlignment.Center;
        Children.Add(frame);
        Children.Add(dialog);
    }

    public void Update(BenchmarkEngine engine)
    {
        if (engine.VideoFrameNumber != shown && engine.VideoFrame is { } f)
        {
            shown = engine.VideoFrameNumber;
            frame.Set(f.Bgra, f.Width, f.Height);
        }
        dialog.Update(engine.ExportStage);
    }
}
