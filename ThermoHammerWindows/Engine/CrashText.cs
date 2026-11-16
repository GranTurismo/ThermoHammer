using System.Text.RegularExpressions;

namespace ThermoHammer.Engine;

/// <summary>What a crash report may say about the PC it came from.</summary>
public static class CrashText
{
    private static readonly Regex UserFolder = new(@"(?i)([\\/]Users[\\/])[^\\/:*?""<>|\r\n]+", RegexOptions.Compiled);

    /// <summary>
    /// Crash text without the Windows account name: file paths in exception messages and stack traces carry it
    /// (C:\Users\name\…), and neither Google Analytics' terms nor the privacy policy allow personal data in what is sent.
    /// The name itself is replaced wherever else it appears; one under three letters is left (it would mangle words).
    /// </summary>
    public static string Scrub(string text, string? userName)
    {
        text = UserFolder.Replace(text, "$1<user>");
        if (userName is { Length: >= 3 }) text = text.Replace(userName, "<user>", StringComparison.OrdinalIgnoreCase);
        return text;
    }
}
