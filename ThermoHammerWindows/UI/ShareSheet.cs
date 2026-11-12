using System.Text;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ThermoHammer.Engine;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Windows.Storage.Streams;

namespace ThermoHammer.UI;

/// <summary>
/// SHARE — a small menu for a run's card and its link: SHARE… opens Windows' share sheet (DataTransferManager, through the interop
/// for desktop windows: IDataTransferManagerInterop.ShowShareUIForWindow) with the PNG as a file; SAVE IMAGE…, COPY IMAGE and COPY
/// LINK work without it (pasting into a chat or a document is how a PC shares most often, and the share sheet can fail without a
/// word on an unpackaged desktop app). CSV files are saved with the usual Save dialog.
/// </summary>
public static class ShareSheet
{
    private sealed record Pending(string Text, string Url, bool LinkOnly, StorageFile? File);

    private static DataTransferManager? manager;
    private static Pending? pending;

    /// <summary>The run's share card and text (the link in it): the menu with SHARE…, SAVE IMAGE…, COPY IMAGE, COPY LINK.</summary>
    public static void Run(ShowcaseRun run)
    {
        BitmapSource image;
        string path;
        try { (image, path) = ShareCard.Save(run); }
        catch (Exception e) { Sheets.Message("COULDN'T MAKE THE IMAGE", e.Message); return; }
        Menu(run.Text, run.Url, image, path);
    }

    /// <summary>A link with its text (the health certificate): the menu with SHARE… and COPY LINK.</summary>
    public static void Link(string text, string url) => Menu(text, url, null, null);

    /// <summary>Windows' share sheet for the main window; false when it can't be shown (the menu says so).</summary>
    private static async Task<bool> SystemShare(string text, string url, string? imagePath, bool linkOnly)
    {
        try
        {
            var hwnd = new WindowInteropHelper(Application.Current.MainWindow).Handle;
            if (hwnd == IntPtr.Zero) return false;
            var file = imagePath != null ? await StorageFile.GetFileFromPathAsync(imagePath) : null;
            if (manager == null)
            {
                manager = DataTransferManagerInterop.GetForWindow(hwnd);
                manager.DataRequested += OnDataRequested;
            }
            pending = new Pending(text, url, linkOnly, file);
            DataTransferManagerInterop.ShowShareUIForWindow(hwnd);
            return true;
        }
        catch (Exception) { return false; }
    }

    /// <summary>What the chosen app gets: a title, the text (with the link), the link itself for a link, the PNG as a file and as a bitmap.</summary>
    private static void OnDataRequested(DataTransferManager sender, DataRequestedEventArgs args)
    {
        if (pending is not { } p) return;
        try
        {
            var data = args.Request.Data;
            data.Properties.Title = "ThermoHammer";
            data.Properties.Description = p.Text;
            data.SetText(p.Text);
            if (p.LinkOnly && Uri.TryCreate(p.Url, UriKind.Absolute, out var uri)) data.SetWebLink(uri);
            if (p.File is { } file)
            {
                data.SetStorageItems(new IStorageItem[] { file });
                data.SetBitmap(RandomAccessStreamReference.CreateFromFile(file));
            }
        }
        catch (Exception e) { args.Request.FailWithDisplayText(e.Message); }
    }

    /// <summary>The share menu: SHARE… (Windows' share sheet) first, then SAVE IMAGE… / COPY IMAGE (with an image) and COPY LINK.</summary>
    private static void Menu(string text, string url, BitmapSource? image, string? imagePath)
    {
        var status = Ui.Text("", 9, Forge.PhaseMeasured, Forge.W.Bold, wrap: true, align: TextAlignment.Center);
        void Done(string what) { status.Foreground = Forge.PhaseMeasured.Brush(); status.Text = what; }
        void Failed(string why) { status.Foreground = Ramp.At(0.85).Brush(); status.Text = why; }
        FrameworkElement Item(string label, Action act) => Ui.Button(label, () =>
        {
            try { act(); }
            catch (Exception e) { Failed(e.Message); }
        }, Forge.Interact, Forge.Ink0, 10, Forge.W.Black, 10, 11);
        var col = Ui.VStack(10,
            Ui.Engraved("SHARE", Forge.PhaseMeasured, 11),
            Ui.Text(text, 9, Forge.Ink1, wrap: true));
        col.Children.Add(Item("SHARE…", async () =>
        {
            status.Text = "";
            if (!await SystemShare(text, url, imagePath, linkOnly: image == null))
                Failed("Windows couldn't open the share panel — use one of the options below");
        }));
        if (image != null && imagePath != null)
        {
            col.Children.Add(Item("SAVE IMAGE…", () =>
            {
                var dlg = new Microsoft.Win32.SaveFileDialog
                {
                    FileName = Path.GetFileName(imagePath), DefaultExt = ".png", AddExtension = true, Filter = "PNG image (*.png)|*.png",
                };
                if (dlg.ShowDialog(Application.Current.MainWindow) != true) return;
                File.Copy(imagePath, dlg.FileName, true);
                Done("SAVED");
            }));
            col.Children.Add(Item("COPY IMAGE", () => { System.Windows.Clipboard.SetImage(image); Done("IMAGE COPIED"); }));
        }
        col.Children.Add(Item("COPY LINK", () => { System.Windows.Clipboard.SetText(url); Done("LINK COPIED"); }));
        col.Children.Add(status);
        Action close = () => { };
        col.Children.Add(Ui.Button("CLOSE", () => close(), Colors.Transparent, Forge.Ink1, 10, padV: 8));
        close = Sheets.Show(Overlays.Card(col, 340), dismissOnScrim: true);
    }

    /// <summary>EXPORT CSV: the Save dialog, then the file as UTF-8 (no byte-order mark).</summary>
    public static void SaveCsv(string fileName, string csv)
    {
        try
        {
            var dlg = new Microsoft.Win32.SaveFileDialog { FileName = fileName, DefaultExt = ".csv", AddExtension = true, Filter = "CSV file (*.csv)|*.csv" };
            if (dlg.ShowDialog(Application.Current.MainWindow) != true) return;
            File.WriteAllText(dlg.FileName, csv, new UTF8Encoding(false));
        }
        catch (Exception e) { Sheets.Message("COULDN'T SAVE THE FILE", e.Message); }
    }
}
