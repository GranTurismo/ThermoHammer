using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace ThermoHammer.UI;

/// <summary>
/// A long list built a page at a time, as it is scrolled toward. The boards show every run (the phones' lazy lists do
/// too), but only what has been scrolled near is ever made into elements: thousands of rows of text and buttons would
/// take seconds to build and lay out, and the page would stutter.
/// </summary>
public sealed class LazyRows
{
    private const int Page = 40;
    private readonly ScrollViewer scroll;
    private readonly Panel target;
    private Func<int, UIElement>? build;
    private int total, built;
    private bool queued;

    /// <summary>[target] is the panel inside [scroll] the rows are added to (its last children).</summary>
    public LazyRows(ScrollViewer scroll, Panel target)
    {
        this.scroll = scroll;
        this.target = target;
        scroll.ScrollChanged += (_, _) => Queue();
    }

    /// <summary>
    /// Adds the first page of [count] rows after what is already in the panel. With [keep] it adds as many as were built
    /// before, so a refresh (a pick, a deleted run) leaves the list where the reader scrolled it.
    /// </summary>
    public void Fill(int count, Func<int, UIElement> row, bool keep = false)
    {
        int upto = keep ? Math.Max(built, Page) : Page;
        total = count;
        build = row;
        built = 0;
        Add(upto);
        Queue();
    }

    /// <summary>The panel is being emptied for a spinner or a message: no more rows come until the next <see cref="Fill"/>.</summary>
    public void Stop() => build = null;

    private void Add(int n)
    {
        while (built < total && n-- > 0) target.Children.Add(build!(built++));
    }

    private void Queue()
    {
        if (queued || build == null || built >= total) return;
        queued = true;
        target.Dispatcher.BeginInvoke(DispatcherPriority.Background, (Action)(() =>
        {
            queued = false;
            if (build == null || built >= total || !scroll.IsLoaded || scroll.ViewportHeight <= 0) return;
            if (scroll.ScrollableHeight - scroll.VerticalOffset < Math.Max(800, scroll.ViewportHeight)) Add(Page);
        }));
    }
}

/// <summary>A short message on the app's own card instead of a system message box.</summary>
public static class BoardNotice
{
    /// <summary>Shows [title] and [text] over [host] until OK (or Esc) is pressed.</summary>
    public static void Show(Panel host, string title, string text)
    {
        var window = Window.GetWindow(host);
        KeyEventHandler? esc = null;
        Grid? scrim = null;
        void Close()
        {
            host.Children.Remove(scrim!);
            if (window != null && esc != null) window.PreviewKeyDown -= esc;
        }
        esc = (_, k) => { if (k.Key == Key.Escape) { Close(); k.Handled = true; } };
        if (window != null) window.PreviewKeyDown += esc;
        var card = Overlays.Card(Ui.VStack(12,
            Ui.Text(title, 12, Ramp.At(0.85), Forge.W.Black),
            Ui.Text(text, 10, Forge.Ink1, wrap: true),
            Ui.Button("OK", Close, Forge.Interact, Forge.Ink0, 11)), 380);
        scrim = Overlays.Scrim(card);
        host.Children.Add(scrim);
        Overlays.FadeIn(card);
    }
}
