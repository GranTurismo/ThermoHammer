namespace ThermoHammer;

public static class Program
{
    /// <summary>`--gpuTest` checks run without WPF at all; everything else starts the app.</summary>
    [STAThread]
    public static int Main(string[] args)
    {
        int at = Array.IndexOf(args, "--gpuTest");
        if (at >= 0 && at + 1 < args.Length)
        {
            string? Arg(string name) { int i = Array.IndexOf(args, name); return i >= 0 && i + 1 < args.Length ? args[i + 1] : null; }
            System.Globalization.CultureInfo.CurrentCulture = System.Globalization.CultureInfo.InvariantCulture;
            Engine.Gpu.GpuCheck.Run(args[at + 1], double.TryParse(Arg("--seconds"), out var s) ? s : 10, Arg("--png"));
            return 0;
        }
        int ux = Array.IndexOf(args, "--uxTest");
        if (ux >= 0 && ux + 1 < args.Length)
        {
            string? Arg(string name) { int i = Array.IndexOf(args, name); return i >= 0 && i + 1 < args.Length ? args[i + 1] : null; }
            System.Globalization.CultureInfo.CurrentCulture = System.Globalization.CultureInfo.InvariantCulture;
            Engine.Ux.UxCheck.Run(args[ux + 1], double.TryParse(Arg("--seconds"), out var s) ? s : 10, Arg("--png"));
            return 0;
        }
        int ai = Array.IndexOf(args, "--aiTest");
        if (ai >= 0 && ai + 1 < args.Length)
        {
            string? Arg(string name) { int i = Array.IndexOf(args, name); return i >= 0 && i + 1 < args.Length ? args[i + 1] : null; }
            System.Globalization.CultureInfo.CurrentCulture = System.Globalization.CultureInfo.InvariantCulture;
            Engine.Ai.AiCheck.Run(args[ai + 1], double.TryParse(Arg("--seconds"), out var s) ? s : 3, Arg("--png"));
            return 0;
        }
        int llm = Array.IndexOf(args, "--llmTest");
        if (llm >= 0 && llm + 1 < args.Length)
        {
            System.Globalization.CultureInfo.CurrentCulture = System.Globalization.CultureInfo.InvariantCulture;
            Engine.Llm.LlmCheck.Run(args[llm + 1]);
            return 0;
        }
        if (Array.IndexOf(args, "--headlessBenchmark") >= 0)
        {
            string? Arg(string name) { int i = Array.IndexOf(args, name); return i >= 0 && i + 1 < args.Length ? args[i + 1] : null; }
            System.Globalization.CultureInfo.CurrentCulture = System.Globalization.CultureInfo.InvariantCulture;
            return Engine.HeadlessCheck.Run(Arg("--only"));
        }
        var app = new App();
        app.InitializeComponent();
        return app.Run();
    }
}
