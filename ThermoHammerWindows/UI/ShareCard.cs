using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>
/// SHARE CARD — "Can your PC beat this?": a run as a 1080 × 1350 PNG, drawn with WPF's 2D API (a DrawingVisual rendered by a
/// RenderTargetBitmap at 96 dpi, so one unit is one pixel), not a screenshot of the window. The phones draw the same card.
/// </summary>
public static class ShareCard
{
    public const int Width = 1080, Height = 1350;
    private const double M = 64;   // side margins
    private const double Ppd = 1;  // pixels per DIP at 96 dpi

    /// <summary>
    /// Where the cards are written (overwritten each time): the Store package's own cache folder — a real path the app that takes the
    /// share can open (the package's %LOCALAPPDATA% is redirected for this process only) — or, installed without a package,
    /// %LOCALAPPDATA%\ThermoHammer\cache\share.
    /// </summary>
    public static string Folder => folder.Value;

    private static readonly Lazy<string> folder = new(() =>
    {
        try { return Path.Combine(global::Windows.Storage.ApplicationData.Current.LocalCacheFolder.Path, "share"); }
        catch (Exception) { return Path.Combine(AppData.Dir, "cache", "share"); }   // no package identity
    });

    public static BitmapSource Render(ShowcaseRun run)
    {
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen()) Draw(dc, run);
        var bmp = new RenderTargetBitmap(Width, Height, 96, 96, PixelFormats.Pbgra32);
        bmp.Render(visual);
        bmp.Freeze();
        return bmp;
    }

    /// <summary>Renders the card and writes it as share/thermohammer-{run|stress}-{id or time}.png; returns the bitmap and the file.</summary>
    public static (BitmapSource image, string path) Save(ShowcaseRun run)
    {
        var image = Render(run);
        Directory.CreateDirectory(Folder);
        string path = Path.Combine(Folder, ShareLinks.CardFileName(run.Stress, run.ServerId, DateTimeOffset.Now));
        var enc = new PngBitmapEncoder();
        enc.Frames.Add(BitmapFrame.Create(image));
        using (var f = File.Create(path)) enc.Save(f);
        return (image, path);
    }

    private static FormattedText T(string s, double size, Color c, Forge.W w = Forge.W.Regular, bool display = false, double maxWidth = 0, int maxLines = 0) =>
        RunArt.Text(s, size, c, w, display, Ppd, maxWidth, maxLines);

    private static void Draw(DrawingContext dc, ShowcaseRun run)
    {
        double w = Width, right = w - M;
        // the forge background: deep, with a faint teal glow behind the number
        dc.DrawRectangle(Forge.Bg.Brush(), null, new Rect(0, 0, w, Height));
        var glow = new RadialGradientBrush(Forge.PhaseMeasured.WithAlpha(0.10), Forge.PhaseMeasured.WithAlpha(0))
        {
            Center = new Point(0.3, 0.38), GradientOrigin = new Point(0.3, 0.38), RadiusX = 0.8, RadiusY = 0.55,
        };
        dc.DrawRectangle(glow, null, new Rect(0, 0, w, Height));

        // THERMOHAMMER · VERIFIED
        RunArt.Tracked(dc, "THERMOHAMMER", 28, Forge.PhaseMeasured, Forge.W.Black, M, M + 28, 6, Ppd);
        if (run.Verified) RunArt.VerifiedPill(dc, right, M + 18, 22, Ppd);

        // the device (up to two lines) and what it ran
        var name = T(run.Device, 64, Forge.Ink0, Forge.W.Bold, display: true, maxWidth: w - 2 * M, maxLines: 2);
        double nameTop = M + 78;
        dc.DrawText(name, new Point(M, nameTop));
        double y = nameTop + name.Height + 14;
        var sub = T(run.SubLine, 30, Forge.Ink2, Forge.W.Medium, maxWidth: w - 2 * M, maxLines: 1);
        dc.DrawText(sub, new Point(M, y));
        y += sub.Height;

        // footer, from the bottom up: the link, the challenge
        double linkBase = Height - M - 6;
        RunArt.Draw(dc, T(ShareLinks.Display(run.Url), 32, Forge.PhaseMeasured, Forge.W.Bold, maxWidth: w - 2 * M), M, linkBase);
        double challengeBase = linkBase - 62;
        RunArt.Draw(dc, T(ShareLinks.Challenge, 44, Forge.Ink0, Forge.W.Black, maxWidth: w - 2 * M), M, challengeBase);
        double bottom = challengeBase - 44 - 56;   // the content ends above the challenge's capitals, with room

        if (run.Stress) Stress(dc, run, y + 40, bottom);
        else Benchmark(dc, run, y + 40, bottom);
    }

    /// <summary>The big number at 190 px, smaller only if it wouldn't fit between the margins.</summary>
    private static FormattedText Huge(string s, Color c)
    {
        var t = T(s, 190, c, Forge.W.Black);
        double room = Width - 2 * M;
        return t.WidthIncludingTrailingWhitespace <= room ? t : T(s, 190 * room / t.WidthIncludingTrailingWhitespace, c, Forge.W.Black);
    }

    private static void Benchmark(DrawingContext dc, ShowcaseRun run, double top, double bottom)
    {
        // the total, huge (the capitals of a 190 px mono are ~140 px tall), its label under it
        double totalBase = top + 140;
        RunArt.Draw(dc, Huge(ShareLinks.Points(run.Total), Forge.Ink0), M - 6, totalBase);
        double labelBase = totalBase + 56;
        RunArt.Tracked(dc, "BENCHMARK SCORE", 26, Forge.Ink2, Forge.W.Bold, M, labelBase, 5, Ppd);
        double barsTop = labelBase + 40;
        int n = Math.Max(1, run.Sections.Count);
        double rowH = Math.Min(84, (bottom - barsTop) / n);
        RunArt.Bars(dc, new Rect(M, barsTop, Width - 2 * M, rowH * run.Sections.Count), run.Sections, 32, Ppd);
    }

    private static void Stress(DrawingContext dc, ShowcaseRun run, double top, double bottom)
    {
        double capBase = top + 140;
        RunArt.Draw(dc, Huge($"{ShareLinks.Percent(run.Capacity)}%", Ramp.ForCapacity(run.Capacity)), M - 6, capBase);
        double labelBase = capBase + 56;
        RunArt.Tracked(dc, run.StressLabel, 26, Forge.Ink2, Forge.W.Bold, M, labelBase, 4, Ppd);
        double onsetBase = bottom;
        double cardTop = labelBase + 34, cardBottom = onsetBase - 46;
        double cardH = Math.Clamp(cardBottom - cardTop, 200, 440);
        var card = new Rect(M, cardTop, Width - 2 * M, cardH);
        RunArt.Curve(dc, card, run.Curve, run.CurveSeconds, run.OnsetSec, 1, Ppd);
        var onsetColor = run.OnsetSec != null ? Ramp.At(0.62) : Forge.PhaseMeasured;
        RunArt.Draw(dc, T(StressCurve.OnsetLine(run.OnsetSec), 30, onsetColor, Forge.W.Bold), M, card.Bottom + 46);
    }
}
