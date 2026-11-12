using System.Globalization;
using System.Text;

namespace ThermoHammer.Engine;

/// <summary>
/// SHARE LINKS — the website's pages for a run and the words that go with them, the same as the phones' (Windows says "PC" where
/// they say "phone"). With no server id a link goes to the site's front page, never to a page that doesn't exist.
/// </summary>
public static class ShareLinks
{
    public const string Site = "https://thermohammer.gtgroup.dev";
    public const string PlayStore = "https://play.google.com/store/apps/details?id=com.granturismo.thermohammer";
    public const string AppStore = "https://apps.apple.com/app/thermohammer/id6791439615";
    /// <summary>What the texts call this kind of device.</summary>
    public const string Device = "PC";

    public static string Home => Site + "/";
    public static string Run(int? id) => id is > 0 ? $"{Site}/run/{Inv(id.Value)}/" : Home;
    public static string Stress(int? id) => id is > 0 ? $"{Site}/stress/{Inv(id.Value)}/" : Home;
    public static string Cert(int id) => $"{Site}/cert/{Inv(id)}/";

    /// <summary>A link as images print it: "thermohammer.gtgroup.dev/run/123" (no scheme, no trailing slash).</summary>
    public static string Display(string url)
    {
        string s = url;
        foreach (var scheme in new[] { "https://", "http://" })
            if (s.StartsWith(scheme, StringComparison.OrdinalIgnoreCase)) s = s[scheme.Length..];
        return s.TrimEnd('/');
    }

    /// <summary>"12,345" — the app's thousands separators whatever the PC's region.</summary>
    public static string Points(int v) => v.ToString("N0", CultureInfo.InvariantCulture);

    /// <summary>A capacity as the texts give it: a whole percent.</summary>
    public static string Percent(double v) => Math.Round(v, MidpointRounding.AwayFromZero).ToString("0", CultureInfo.InvariantCulture);

    public static string BenchmarkText(string device, int total, string url, bool mine) => mine
        ? $"My {device} scored {Points(total)} on the ThermoHammer benchmark. Can your {Device} beat it? {url}"
        : $"{device} scored {Points(total)} on ThermoHammer. Can your {Device} beat it? {url}";

    public static string StressText(string device, double capacity, int minutes, string url, bool mine) => mine
        ? $"My {device} kept {Percent(capacity)}% of its speed through a {Inv(minutes)}-minute ThermoHammer stress test. Can your {Device} do better? {url}"
        : $"{device} kept {Percent(capacity)}% of its speed through a {Inv(minutes)}-minute ThermoHammer stress test. {url}";

    public static string CertText(string device, string url) => $"Health certificate for my {device}: {url}";

    /// <summary>The share card's footer.</summary>
    public static string Challenge => $"CAN YOUR {Device} BEAT THIS?";

    /// <summary>"thermohammer-run-123.png" / "thermohammer-stress-1731420000.png" (the time, in Unix seconds, when the run has no id yet).</summary>
    public static string CardFileName(bool stress, int? id, DateTimeOffset at) =>
        $"thermohammer-{(stress ? "stress" : "run")}-{(id is > 0 ? Inv(id.Value) : at.ToUnixTimeSeconds().ToString(CultureInfo.InvariantCulture))}.png";

    /// <summary>"thermohammer-benchmark-lenovo-thinkpad-x1-20261112-1430.csv".</summary>
    public static string CsvFileName(bool stress, string device, DateTime at) =>
        $"thermohammer-{(stress ? "stress" : "benchmark")}-{Slug(device)}-{at.ToString("yyyyMMdd-HHmm", CultureInfo.InvariantCulture)}.csv";

    /// <summary>"Lenovo ThinkPad X1 (Gen 12)" → "lenovo-thinkpad-x1-gen-12": letters and digits, everything else one dash.</summary>
    public static string Slug(string s)
    {
        var b = new StringBuilder();
        foreach (char c in s.Normalize(NormalizationForm.FormKD))
        {
            if (c < 128 && char.IsLetterOrDigit(c)) b.Append(char.ToLowerInvariant(c));
            else if (b.Length > 0 && b[^1] != '-' && CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark) b.Append('-');
        }
        string slug = b.ToString().Trim('-');
        return slug.Length > 0 ? slug : "device";
    }

    private static string Inv(int v) => v.ToString(CultureInfo.InvariantCulture);
}
