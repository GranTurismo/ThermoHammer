using System.Globalization;
using System.Windows;
using ThermoHammer.Engine;

namespace ThermoHammer;

public partial class App : Application
{
    /// <summary>`--autoBenchmark`: run the benchmark at start, write the report to %LOCALAPPDATA%\ThermoHammer\auto-benchmark.txt.</summary>
    public static bool AutoBenchmark { get; private set; }
    /// <summary>`--autoStress`: start a 5-minute multi-thread stress test at once (checks).</summary>
    public static bool AutoStress => Flag("--autoStress");
    /// <summary>`--screenshot &lt;file&gt; [--after &lt;seconds&gt;] [--tab leaderboard] [--welcome N] [--size WxH]`: save the window as a PNG, then quit.</summary>
    public static string[] Args { get; private set; } = [];

    public static string? Arg(string name)
    {
        int i = Array.IndexOf(Args, name);
        return i >= 0 && i + 1 < Args.Length ? Args[i + 1] : null;
    }

    public static bool Flag(string name) => Args.Contains(name);

    protected override void OnStartup(StartupEventArgs e)
    {
        // numbers as the phones print them ("1,234.5"), whatever the PC's regional settings — and what is hashed and
        // submitted (the canonical strings) can't pick up a decimal comma
        CultureInfo.DefaultThreadCurrentCulture = CultureInfo.DefaultThreadCurrentUICulture = CultureInfo.InvariantCulture;
        CultureInfo.CurrentCulture = CultureInfo.CurrentUICulture = CultureInfo.InvariantCulture;
        Args = e.Args;
        AutoBenchmark = Flag("--autoBenchmark");
        // a crash leaves its stack trace in %LOCALAPPDATA%\ThermoHammer\crash.txt (and the app says so) instead of vanishing
        DispatcherUnhandledException += (_, ex) =>
        {
            AppData.SaveText("crash.txt", $"{DateTime.Now:O}\n{ex.Exception}");
            MessageBox.Show($"ThermoHammer hit an error and has to close:\n\n{ex.Exception.Message}\n\nDetails: %LOCALAPPDATA%\\ThermoHammer\\crash.txt",
                "ThermoHammer", MessageBoxButton.OK, MessageBoxImage.Error);
        };
        AppDomain.CurrentDomain.UnhandledException += (_, ex) => AppData.SaveText("crash.txt", $"{DateTime.Now:O}\n{ex.ExceptionObject}");
        base.OnStartup(e);
        if (Arg("--gpuTest") is { } which)
        {
            // checks without the app's window: render one GPU test, write its numbers and last frame, quit
            var png = Arg("--png");
            double secs = double.TryParse(Arg("--seconds"), out var sv) ? sv : 10;
            Task.Run(() => { Engine.Gpu.GpuCheck.Run(which, secs, png); Dispatcher.Invoke(Shutdown); });
            return;
        }
        new MainWindow().Show();
    }
}
