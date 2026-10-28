namespace ThermoHammer.Engine;

/// <summary>`--headlessBenchmark [--only cpu,mem,gpu,ux,ai,llm]`: the benchmark's engine without a window (checks under Wine); the report goes to %LOCALAPPDATA%\ThermoHammer\headless-benchmark.txt.</summary>
public static class HeadlessCheck
{
    public static int Run(string? only)
    {
        var engine = BenchmarkEngine.CreateHeadless(only?.Split(',', StringSplitOptions.RemoveEmptyEntries));
        engine.Start();
        while (engine.IsRunning) Thread.Sleep(500);
        var text = engine.Result != null
            ? BenchmarkReport.Text(engine.Result, DateTime.Now.ToString("yyyy-MM-dd HH:mm"))
            : $"ended without a result: {engine.Error ?? "stopped"}";
        AppData.SaveText("headless-benchmark.txt", text);
        return 0;
    }
}
