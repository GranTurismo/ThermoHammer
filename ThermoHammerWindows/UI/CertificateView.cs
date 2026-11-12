using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ThermoHammer.Engine;

namespace ThermoHammer.UI;

/// <summary>
/// VERIFIED HEALTH CERTIFICATE — for selling a used PC. The certificate is a page on the website built from the run the server
/// verified (SITE/cert/{id}/); the PC being sold shows it as a QR code, so the buyer scans it from the machine itself. A run that
/// isn't on the leaderboard yet has to be submitted first.
/// </summary>
public sealed class CertificateView : Grid
{
    private readonly ContentControl body = new();
    private readonly BenchmarkResult result;
    private readonly BenchmarkRecord record;
    private readonly Func<HealthCard.Median?> health;
    private readonly Func<Task<bool>> submit;
    private readonly Action close;
    private bool submitting;
    private string? error;

    /// <summary>[health]: the run's PC Health, when it's known; [submit]: the result's own submit (true when the run reached the board).</summary>
    public static void Open(BenchmarkResult result, BenchmarkRecord record, Func<HealthCard.Median?> health, Func<Task<bool>> submit)
    {
        Action close = () => { };
        close = Sheets.Show(new CertificateView(result, record, health, submit, () => close()));
    }

    private CertificateView(BenchmarkResult result, BenchmarkRecord record, Func<HealthCard.Median?> health, Func<Task<bool>> submit, Action close)
    {
        this.result = result; this.record = record; this.health = health; this.submit = submit; this.close = close;
        Children.Add(Overlays.Card(body, 440));
        Build();
    }

    private void Build() => body.Content = record.ServerId is { } id ? Certificate(id) : NotOnBoard();

    private static FrameworkElement Head() => Ui.Engraved("VERIFIED HEALTH CERTIFICATE", Forge.PhaseMeasured, 11).Align(HorizontalAlignment.Center);

    /// <summary>The run has no page yet: why, and the submit.</summary>
    private FrameworkElement NotOnBoard()
    {
        var col = Ui.VStack(14, Head(),
            Ui.Text($"The certificate is a page on {ShareLinks.Display(ShareLinks.Home)}, so the run has to be on the leaderboard first.", 11,
                Forge.Ink0, Forge.W.Bold, wrap: true, align: TextAlignment.Center));
        if (record.Submitted)
            col.Children.Add(Ui.Text("This run went to the leaderboard before the app kept its page, so it has no certificate. Your next verified run will.",
                9, Forge.Ink1, wrap: true, align: TextAlignment.Center));
        else if (!NetworkMonitor.Shared.IsConnected)
            col.Children.Add(Ui.VStack(4, Ui.Text("NO INTERNET CONNECTION", 10, Ramp.At(0.5), Forge.W.Bold, align: TextAlignment.Center),
                Ui.Text("Turn on Wi-Fi or Ethernet to submit this result.", 9, Forge.Ink1, wrap: true, align: TextAlignment.Center)));
        else
        {
            if (error != null) col.Children.Add(Ui.Text(error, 9, Ramp.At(0.85), wrap: true, align: TextAlignment.Center));
            col.Children.Add(Ui.Button(submitting ? "SUBMITTING…" : "SUBMIT TO LEADERBOARD", () => _ = Submit(), Forge.PhaseMeasured, Forge.Bg, 11,
                Forge.W.Black, 10, 12, enabled: !submitting));
        }
        col.Children.Add(Ui.Button("CLOSE", close, Colors.Transparent, Forge.Ink1, 10, padV: 8));
        return col;
    }

    private async Task Submit()
    {
        submitting = true; error = null; Build();
        bool ok = await submit();
        submitting = false;
        if (!ok) error = "The run didn't reach the leaderboard — see the message under the result, then try again.";
        Build();
    }

    private FrameworkElement Certificate(int id)
    {
        string url = ShareLinks.Cert(id);
        string device = PcHealth.Name();
        var col = Ui.VStack(10, Head(),
            Ui.Text(device, 20, Forge.Ink0, Forge.W.Bold, display: true, wrap: true, align: TextAlignment.Center),
            Ui.Text($"{DeviceInfo.OsLabel(DeviceInfo.OsPlatform, record.OsVersion ?? DeviceInfo.OsVersion)} · " +
                    record.Timestamp.ToString("d MMM yyyy", System.Globalization.CultureInfo.InvariantCulture), 10, Forge.Ink1, align: TextAlignment.Center),
            Ui.VStack(1, Ui.Text(result.Total.N0(), 30, Forge.Ink0, Forge.W.Black, align: TextAlignment.Center),
                Ui.Text("TOTAL POINTS", 8, Forge.Ink2, Forge.W.Bold, align: TextAlignment.Center)));
        if (health() is { } h)
            col.Children.Add(Ui.Text($"{h.Percent}% of the median {h.Model} ({h.Runs} runs)", 10, Forge.PhaseMeasured, Forge.W.Bold, wrap: true,
                align: TextAlignment.Center));
        if (QrCode(url) is { } qr) col.Children.Add(qr.Pad(0, 6, 0, 0));
        else col.Children.Add(Ui.Text("The QR code couldn't be drawn here — the link below opens the same page.", 9, Ramp.At(0.5), wrap: true, align: TextAlignment.Center));
        col.Children.Add(Ui.Text(ShareLinks.Display(url), 11, Forge.PhaseMeasured, Forge.W.Bold, wrap: true, align: TextAlignment.Center));
        col.Children.Add(Ui.Text($"Buyer: scan this code from the {ShareLinks.Device} being sold. The page shows this exact run as the server verified it.",
            9, Forge.Ink1, wrap: true, align: TextAlignment.Center));
        col.Children.Add(Ui.HStack(10,
            Ui.Button("SHARE LINK", () => ShareSheet.Link(ShareLinks.CertText(device, url), url), Forge.PhaseMeasured, Forge.Bg, 10, Forge.W.Black, 10, 11).Grow(),
            Ui.Button("DONE", close, Forge.Interact, Forge.Ink0, 10, Forge.W.Black, 10, 11).Grow()));
        return col;
    }

    private static FrameworkElement? QrCode(string url)
    {
        try
        {
            if (BenchCore.QrEncode(url) is not { } code) return null;
            return new QrCodeView(code.side, code.modules) { Width = 264, Height = 264 }.Align(HorizontalAlignment.Center, VerticalAlignment.Top);
        }
        catch (Exception) { return null; }   // benchcore.dll missing or too old
    }
}

/// <summary>
/// A QR code that stays sharp at any size and display scaling: dark modules on white with the 4-module quiet zone, each module a
/// whole number of screen pixels, drawn without anti-aliasing.
/// </summary>
public sealed class QrCodeView : FrameworkElement
{
    private const int Quiet = 4;
    private readonly int side;
    private readonly byte[] modules;

    public QrCodeView(int side, byte[] modules)
    {
        this.side = side;
        this.modules = modules;
        SnapsToDevicePixels = true;
        UseLayoutRounding = true;
        RenderOptions.SetEdgeMode(this, EdgeMode.Aliased);
    }

    protected override void OnRender(DrawingContext dc)
    {
        double scale = VisualTreeHelper.GetDpi(this).DpiScaleX;
        int n = side + 2 * Quiet;
        int px = Math.Max(1, (int)Math.Floor(Math.Min(ActualWidth, ActualHeight) * scale / n));   // screen pixels per module
        double unit = px / scale, size = unit * n;
        double ox = Math.Round((ActualWidth - size) / 2 * scale) / scale, oy = Math.Round((ActualHeight - size) / 2 * scale) / scale;
        dc.DrawRectangle(Brushes.White, null, new Rect(ox, oy, size, size));
        for (int y = 0; y < side; y++)
            for (int x = 0; x < side;)
            {
                if (modules[y * side + x] == 0) { x++; continue; }
                int run = 1;
                while (x + run < side && modules[y * side + x + run] != 0) run++;   // a row's dark modules in one rectangle
                dc.DrawRectangle(Brushes.Black, null, new Rect(ox + (Quiet + x) * unit, oy + (Quiet + y) * unit, run * unit, unit));
                x += run;
            }
    }
}
