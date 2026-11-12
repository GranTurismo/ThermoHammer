using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace ThermoHammer.UI;

/// <summary>
/// The window's top layer: cards opened over everything else — the share menu, the health certificate, the speed check — from
/// wherever they're asked for (the result, the verdict, the leaderboard). Esc closes the top one.
/// </summary>
public static class Sheets
{
    private static Grid? host;
    private static readonly List<(Grid scrim, FrameworkElement content, Action? closed)> open = [];

    /// <summary>The main window's layer the sheets go into.</summary>
    public static void Attach(Grid layer) => host = layer;

    public static bool IsOpen => open.Count > 0;

    /// <summary>
    /// Shows [content] centred on a scrim and returns what closes it. [dismissOnScrim]: a click beside it closes it. Closing disposes
    /// content that is IDisposable, then calls [closed].
    /// </summary>
    public static Action Show(FrameworkElement content, bool dismissOnScrim = false, Action? closed = null)
    {
        if (host == null) return () => { };
        var scrim = new Grid { Background = Color.FromArgb(200, 0, 0, 0).Brush() };
        content.HorizontalAlignment = HorizontalAlignment.Center;
        content.VerticalAlignment = VerticalAlignment.Center;
        scrim.Children.Add(Ui.Scroll(content).Align(HorizontalAlignment.Stretch, VerticalAlignment.Center));
        void Close() => Remove(scrim);
        if (dismissOnScrim) scrim.MouseLeftButtonUp += (_, e) => { if (e.OriginalSource == scrim) Close(); };
        open.Add((scrim, content, closed));
        host.Children.Add(scrim);
        Overlays.FadeIn(content);
        return Close;
    }

    /// <summary>A short message with an OK.</summary>
    public static void Message(string title, string text)
    {
        Action close = () => { };
        close = Show(Overlays.Card(Ui.VStack(12,
            Ui.Text(title, 12, Ramp.At(0.85), Forge.W.Black),
            Ui.Text(text, 10, Forge.Ink1, wrap: true),
            Ui.Button("OK", () => close(), Forge.Interact, Forge.Ink0, 11)), 380), true);
    }

    /// <summary>Esc: closes the top sheet; false when none is open.</summary>
    public static bool CloseTop()
    {
        if (open.Count == 0) return false;
        Remove(open[^1].scrim);
        return true;
    }

    private static void Remove(Grid scrim)
    {
        int i = open.FindIndex(o => o.scrim == scrim);
        if (i < 0) return;
        var (_, content, closed) = open[i];
        open.RemoveAt(i);
        host?.Children.Remove(scrim);
        if (content is IDisposable d) d.Dispose();
        closed?.Invoke();
    }
}
