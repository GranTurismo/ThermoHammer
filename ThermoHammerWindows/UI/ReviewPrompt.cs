using System.Windows;
using System.Windows.Interop;
using ThermoHammer.Engine;
using Windows.ApplicationModel;
using Windows.Services.Store;

namespace ThermoHammer.UI;

/// <summary>The Microsoft Store's rating dialog, asked for when <see cref="ReviewPolicy"/> allows it.</summary>
public static class ReviewPrompt
{
    private const string LastKey = "review.lastAskedAt", AsksKey = "review.asks", RatedKey = "review.rated";

    /// <summary>Call as the result of a run that just finished is closed: the run is over and nothing is interrupted.</summary>
    public static async void OnResultClosed(bool verified, bool complete)
    {
        try
        {
            var now = DateTimeOffset.UtcNow;
            double last = Settings.Number(LastKey);
            int asks = (int)Settings.Number(AsksKey);
            if (!ReviewPolicy.ShouldAsk(now, last > 0 ? DateTimeOffset.FromUnixTimeSeconds((long)last) : null, asks,
                    Settings.Bool(RatedKey), verified, complete)) return;
            if (!FromTheStore()) return;          // the installer build has no Store listing to rate: nothing asked, nothing counted
            var hwnd = new WindowInteropHelper(Application.Current.MainWindow).Handle;
            if (hwnd == IntPtr.Zero) return;
            var store = StoreContext.GetDefault();
            WinRT.Interop.InitializeWithWindow.Initialize(store, hwnd);   // a desktop app's Store dialogs need their owner window
            Settings.Set(LastKey, now.ToUnixTimeSeconds());
            Settings.Set(AsksKey, asks + 1);
            var result = await store.RequestRateAndReviewAppAsync();
            if (result.Status == StoreRateAndReviewStatus.Succeeded) Settings.Set(RatedKey, true);
        }
        catch (Exception) { }   // no Store on this PC or no connection: there is nothing to ask
    }

    /// <summary>Installed from the Microsoft Store (a Store-signed package), not from the installer or a sideloaded package.</summary>
    private static bool FromTheStore()
    {
        try { return Package.Current.SignatureKind == PackageSignatureKind.Store; }
        catch (Exception) { return false; }       // no package identity
    }
}
