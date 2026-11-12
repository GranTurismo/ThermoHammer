namespace ThermoHammer.Engine.Ai;

/// <summary>
/// The AI tests: each model on the CPU, the GPU and the NPU in turn, its result on the stage — the phones' runAi. An
/// accelerator that took the app down in two runs of this build (a runtime's crashes can't be caught) is skipped and scores 0.
/// </summary>
public sealed class AiSection : IBenchmarkSection
{
    public const double Seconds = 1.6;          // measured per model and accelerator, as on the phones
    public const double ShowS = 3.5;            // each model's result stays up at least this long
    public const double SkipNoticeS = 1.5;      // how long "crashed the app twice" stays up, once per accelerator

    // markers "build:model:accel" (CrashGuard); the strikes are counted per accelerator, as on iOS
    private static readonly CrashGuard Guard = new("ai");

    /// <summary>Accelerators that took the app down in two runs of this build; a marker left by the last launch counts as one.</summary>
    private static HashSet<AiAccel> CrashedAccels()
    {
        var names = Guard.Skipped(2);
        return AiTests.Accels.Where(a => names.Contains(a.ToString())).ToHashSet();
    }

    public void Run(BenchmarkEngine engine, BenchmarkResult r)
    {
        engine.PublishAi(AiTest.Classify, null, 0, -1, "LOADING MODELS");
        var bench = new AiBench();
        var inputs = new AiInputs();
        var crashed = CrashedAccels();
        // the NPU first: it may have to download its runtime, which the stage says while it waits
        string? npuProblem = AiBench.PrepareNpu(msg => engine.PublishAi(AiTests.All[0], AiAccel.Npu, 0, -1, msg));
        if (!engine.IsActive) return;
        var runs = new List<AiRun>();
        var announced = new HashSet<AiAccel>();
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
                string? skip = crashed.Contains(accel) ? CrashGuard.Reason : accel == AiAccel.Npu ? npuProblem : null;
                if (skip != null)
                {
                    runs.Add(new AiRun(test, accel, 0, skip));
                    if (crashed.Contains(accel) && announced.Add(accel))     // say why once, so the zero isn't a mystery
                    {
                        engine.PublishAi(test, accel, k / 3.0, -1, $"{accel.ToString().ToUpperInvariant()} · SKIPPED · {skip.ToUpperInvariant()} · 0 POINTS");
                        Thread.Sleep(TimeSpan.FromSeconds(SkipNoticeS));
                    }
                    continue;
                }
                string label = accel == AiAccel.Npu ? $"NPU · {AiBench.NpuRuntime}" : accel == AiAccel.Gpu ? "GPU · DIRECTML" : "CPU · ONNX RUNTIME";
                string precision = accel == AiAccel.Gpu ? "FP16" : "INT8";
                string model = test.Model().ToUpperInvariant();
                engine.PublishAi(test, accel, k / 3.0, -1, $"{label} · {precision} · {model} · COMPILING");
                string entry = $"{test.Asset()}:{accel}";
                Guard.Begin(entry);
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
                finally { Guard.End(); }
                if (!engine.IsActive) return;
                Guard.Finished(entry);      // it ran here: earlier unfinished runs of this model were chance
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
            if (accel == AiAccel.Npu)
            {
                string? why = AiBench.PrepareNpu(m => log.AppendLine(m));
                log.AppendLine(why == null ? $"NPU provider: {AiBench.NpuRuntime}" : $"NPU not available: {why}");
            }
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
