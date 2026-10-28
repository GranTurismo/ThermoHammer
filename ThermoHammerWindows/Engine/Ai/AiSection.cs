namespace ThermoHammer.Engine.Ai;

/// <summary>
/// The AI tests: each model on the CPU, the GPU and the NPU in turn, its result on the stage — the phones' runAi. An
/// accelerator that took the app down last time (a runtime's crashes can't be caught) is skipped in this build and scores 0.
/// </summary>
public sealed class AiSection : IBenchmarkSection
{
    public const double Seconds = 1.6;          // measured per model and accelerator, as on the phones
    public const double ShowS = 3.5;            // each model's result stays up at least this long
    private const string RunningKey = "benchmark.aiRunning", CrashedKey = "benchmark.aiCrashed";

    private static string Build => System.Diagnostics.FileVersionInfo.GetVersionInfo(Environment.ProcessPath ?? "").FileVersion ?? "?";

    /// <summary>Accelerators whose run never finished in this build: a marker was left behind.</summary>
    private static HashSet<AiAccel> CrashedAccels()
    {
        var crashed = Settings.Strings(CrashedKey);
        var running = Settings.Strings(RunningKey);
        if (running.Count > 0)
        {
            foreach (var r in running) if (!crashed.Contains(r)) crashed.Add(r);
            Settings.Set(CrashedKey, crashed);
            Settings.Set(RunningKey, null);
        }
        return crashed.Select(s => s.Split(':')).Where(p => p.Length == 3 && p[0] == Build)
            .Select(p => AiTests.Accels.Cast<AiAccel?>().FirstOrDefault(a => a.ToString() == p[2])).OfType<AiAccel>().ToHashSet();
    }

    public void Run(BenchmarkEngine engine, BenchmarkResult r)
    {
        engine.PublishAi(AiTest.Classify, null, 0, -1, "LOADING MODELS");
        var bench = new AiBench();
        var inputs = new AiInputs();
        var crashed = CrashedAccels();
        string? npuProblem = AiBench.NpuProblem;
        var runs = new List<AiRun>();
        foreach (var test in AiTests.All)
        {
            if (!engine.IsActive) return;
            var (input, picture) = inputs.Input(test);
            engine.PublishAi(test, null, 0, -1, test.Model().ToUpperInvariant());
            engine.PublishAiVisual(new AiVisual(test, picture));
            long decodedAt = 0;
            for (int k = 0; k < AiTests.Accels.Length; k++)
            {
                var accel = AiTests.Accels[k];
                if (!engine.IsActive) return;
                string? skip = crashed.Contains(accel) ? "crashed the app here last time" : accel == AiAccel.Npu ? npuProblem : null;
                if (skip != null) { runs.Add(new AiRun(test, accel, 0, skip)); continue; }
                string label = accel == AiAccel.Npu ? $"NPU · {AiBench.NpuRuntime}" : accel == AiAccel.Gpu ? "GPU · DIRECTML" : "CPU · ONNX RUNTIME";
                string precision = accel == AiAccel.Gpu ? "FP16" : "INT8";
                string model = test.Model().ToUpperInvariant();
                engine.PublishAi(test, accel, k / 3.0, -1, $"{label} · {precision} · {model} · COMPILING");
                Settings.Set(RunningKey, new[] { $"{Build}:{test.Asset()}:{accel}" });
                AiBench.Measured measured;
                try
                {
                    measured = bench.Run(test, accel, input, Seconds, () => engine.IsActive,
                        (f, ips) => engine.PublishAi(test, accel, (k + f) / 3, ips, $"{label} · {precision} · {model}"));
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    runs.Add(new AiRun(test, accel, 0, AiBench.Explain(e, accel)));
                    continue;
                }
                finally { Settings.Set(RunningKey, null); }
                if (!engine.IsActive) return;
                runs.Add(new AiRun(test, accel, BenchmarkScore.Round2(measured.Ips)));
                if (decodedAt == 0)
                {
                    decodedAt = Clock.Ns();
                    engine.PublishAiVisual(inputs.Visual(test, picture, measured.Outputs));
                }
            }
            // where accelerators were skipped the model would flash by: leave its result up long enough to read
            if (decodedAt != 0)
            {
                double left = ShowS - (Clock.Ns() - decodedAt) / 1e9;
                if (left > 0) { engine.PublishAi(test, null, 1, -1, test.Model().ToUpperInvariant()); Thread.Sleep(TimeSpan.FromSeconds(left)); }
            }
        }
        r.Ai = new AiResult(FallbackChecked(runs), npuProblem == null ? AiBench.NpuRuntime : null, npuProblem);
        engine.PublishAi(null, null, 0, -1, "");
    }

    /// <summary>
    /// An "NPU" no faster than the CPU across the models means the runtime ran it on the CPU after all (its fallback is
    /// silent): those runs score 0 instead of passing for an NPU — Android's rule.
    /// </summary>
    private static List<AiRun> FallbackChecked(List<AiRun> runs)
    {
        double Ips(AiTest t, AiAccel a) => runs.FirstOrDefault(x => x.Test == t && x.Accel == a)?.Ips ?? 0;
        var ratios = AiTests.All.Select(t => (c: Ips(t, AiAccel.Cpu), n: Ips(t, AiAccel.Npu))).Where(p => p.c > 0 && p.n > 0).Select(p => p.n / p.c).ToList();
        if (ratios.Count == 0) return runs;
        double speedup = Math.Exp(ratios.Select(x => Math.Log(x)).Average());
        if (speedup >= 1.2) return runs;
        return runs.Select(x => x.Accel == AiAccel.Npu && x.Ips > 0
            ? new AiRun(x.Test, x.Accel, 0, $"no faster than the CPU ({speedup:0.00}×): the runtime fell back to it") : x).ToList();
    }
}


/// <summary>`--aiTest cpu|gpu|npu [--seconds N] [--png prefix]`: every model on one accelerator without the window, its numbers to %LOCALAPPDATA%\ThermoHammer\ai-test.txt.</summary>
public static class AiCheck
{
    public static void Run(string which, double seconds, string? pngPrefix)
    {
        var log = new System.Text.StringBuilder();
        try
        {
            var accel = which switch { "gpu" => AiAccel.Gpu, "npu" => AiAccel.Npu, _ => AiAccel.Cpu };
            var inputs = new AiInputs();
            var bench = new AiBench();
            foreach (var test in AiTests.All)
            {
                var (input, picture) = inputs.Input(test);
                try
                {
                    var m = bench.Run(test, accel, input, seconds, () => true, (_, _) => { });
                    log.AppendLine($"{test.Title()} · {accel} · {m.Ips:0.00} inf/s · ref {AiScore.Ref(test, accel)}");
                    if (pngPrefix != null)
                    {
                        var v = inputs.Visual(test, picture, m.Outputs);
                        var img = v.Result ?? picture;
                        Gpu.PngWriter.Save($"{pngPrefix}_{test.Asset()}.png", img.W, img.H, img.Rgba);
                        log.AppendLine("   " + (v.Labels != null ? string.Join(", ", v.Labels.Select(l => $"{l.name} {l.p:0.00}")) : v.Boxes != null ? $"{v.Boxes.Count} boxes: " + string.Join(", ", v.Boxes.Take(6).Select(b => b.Label)) : "picture"));
                    }
                }
                catch (Exception e) { log.AppendLine($"{test.Title()} · {accel} · FAILED: {AiBench.Explain(e, accel)}"); }
            }
        }
        catch (Exception e) { log.AppendLine($"FAILED: {e}"); }
        AppData.SaveText("ai-test.txt", log.ToString());
    }
}
