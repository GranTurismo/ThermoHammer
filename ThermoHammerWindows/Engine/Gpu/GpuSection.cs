namespace ThermoHammer.Engine.Gpu;

/// <summary>The GPU tests, in order: Lake Valley, Neon City, Neon City RT (ray tracing where the GPU has it, else 0 points).</summary>
public sealed class GpuSection : IBenchmarkSection
{
    public void Run(BenchmarkEngine engine, BenchmarkResult r)
    {
        using var bench = GpuBench.TryCreate(out var problem) ?? throw new GpuError($"Direct3D is unavailable: {problem}");
        r.GpuName = bench.Name;
        bench.FrameSink = engine.PublishGpuFrame;
        foreach (var test in GpuTests.All)
        {
            if (!engine.IsActive) return;
            if (test.RayTracing())
            {
                // GPU Test 3 runs on Direct3D 12 with DXR ray queries, on its own device
                var why = Rt.NeonCityRt.Problem();
                if (why != null) { r.Gpu.Add(GpuTestResult.Unsupported(test)); r.RayTracingProblem = why; continue; }
                GpuTestResult? res;
                try { res = RunRayTracing(engine, test); }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    // a GPU or driver that can't run the rays scores 0 there; the rest of the benchmark goes on
                    r.Gpu.Add(GpuTestResult.Unsupported(test));
                    r.RayTracingProblem = $"ray tracing failed: {e.Message.Trim()}";
                    continue;
                }
                if (res == null) return;
                r.Gpu.Add(res);
                continue;
            }
            using var scene = MakeScene(test, bench);
            float seconds = test.Seconds();
            string detail = $"{bench.Name} · {GpuBench.Width}×{GpuBench.Height} offscreen";
            engine.PublishGpu(test, -1, 0, detail);
            var (starts, durs) = bench.Run(scene, seconds, () => engine.IsActive, (t, fps) => engine.PublishGpu(test, fps, t / seconds, detail));
            if (!engine.IsActive) return;
            r.Gpu.Add(BenchmarkScore.Summarize(test, starts, durs) ?? throw new GpuError($"{test.Title()}: too few frames rendered"));
        }
        engine.PublishGpu(null, -1, 0);
    }

    /// <summary>Neon City RT: Direct3D 12 + DXR. Null if the run was stopped.</summary>
    private static GpuTestResult? RunRayTracing(BenchmarkEngine engine, GpuTest test)
    {
        string Path(string name) => System.IO.Path.Combine(AppContext.BaseDirectory, "assets", name);
        using var rt = new Rt.NeonCityRt(new NeonCityAsset(File.ReadAllBytes(Path("neon_city.bin"))));
        rt.FrameSink = engine.PublishGpuFrame;
        float seconds = test.Seconds();
        string detail = $"{rt.Name} · {Rt.NeonCityRt.Width}×{Rt.NeonCityRt.Height} offscreen · DXR";
        engine.PublishGpu(test, -1, 0, detail);
        var (starts, durs) = rt.Run(seconds, () => engine.IsActive, (t, fps) => engine.PublishGpu(test, fps, t / seconds, detail));
        if (!engine.IsActive) return null;
        return BenchmarkScore.Summarize(test, starts, durs) ?? throw new GpuError($"{test.Title()}: too few frames rendered");
    }

    public static IGpuScene MakeScene(GpuTest test, GpuBench bench)
    {
        string Path(string name) => System.IO.Path.Combine(AppContext.BaseDirectory, "assets", name);
        switch (test)
        {
            case GpuTest.LakeValley:
                return new LakeValleyRenderer(bench, new LakeValleyAsset(File.ReadAllBytes(Path("lake_valley.bin"))), GpuBench.Width, GpuBench.Height);
            case GpuTest.NeonCity:
                return new NeonCityRenderer(bench, new NeonCityAsset(File.ReadAllBytes(Path("neon_city.bin"))), GpuBench.Width, GpuBench.Height);
            default:
                throw new GpuError($"{test.Title()} isn't in the Windows app yet");
        }
    }
}

/// <summary>`--gpuTest lake [--seconds 10] [--png file]`: renders one GPU test headless, writes its fps to %LOCALAPPDATA%\ThermoHammer\gpu-test.txt and the last frame to a PNG.</summary>
public static class GpuCheck
{
    public static void Run(string which, double seconds, string? png)
    {
        var log = new System.Text.StringBuilder();
        try
        {
            var test = which switch { "neon" => GpuTest.NeonCity, "rt" => GpuTest.NeonCityRT, _ => GpuTest.LakeValley };
            if (test == GpuTest.NeonCityRT)
            {
                using var rt = new Rt.NeonCityRt(new NeonCityAsset(File.ReadAllBytes(System.IO.Path.Combine(AppContext.BaseDirectory, "assets", "neon_city.bin"))));
                log.AppendLine($"GPU {rt.Name} · {rt.TrianglesPerFrame:N0} triangles per frame (rays: {Environment.GetEnvironmentVariable("THERMOHAMMER_RT_PLUMBING") != "1"})");
                var (rs, rd) = rt.Run((float)seconds, () => true, (_, _) => { });
                var sorted2 = rd.OrderBy(x => x).ToArray();
                log.AppendLine($"{rd.Length} frames in {seconds:0.#} s · avg {rd.Length * 1e9 / rd.Sum():0.00} fps · median {1e9 / sorted2[sorted2.Length / 2]:0.00}");
                if (BenchmarkScore.Summarize(test, rs, rd) is { } rr) log.AppendLine($"scored: {rr.Points} pts · avg {rr.AvgFps} · low {rr.LowFps} · stability {rr.StabilityPct:0}%");
                if (png != null) rt.SavePng(png);
                AppData.SaveText("gpu-test.txt", log.ToString());
                return;
            }
            using var bench = GpuBench.TryCreate(out var problem) ?? throw new GpuError($"Direct3D is unavailable: {problem}");
            log.AppendLine($"GPU {bench.Name} · feature level {bench.Level} · MSAA4 {bench.SupportsMultisample4}");
            using var scene = GpuSection.MakeScene(test, bench);
            log.AppendLine($"{test.Title()} · {scene.TrianglesPerFrame:N0} triangles per frame");
            var (starts, durs) = bench.Run(scene, (float)seconds, () => true, (_, _) => { });
            double avg = durs.Length > 0 ? durs.Length * 1e9 / durs.Sum() : 0;
            var sorted = durs.OrderBy(x => x).ToArray();
            log.AppendLine($"{durs.Length} frames in {seconds:0.#} s · avg {avg:0.00} fps · median {1e9 / sorted[sorted.Length / 2]:0.00} · 1% low {1e9 / sorted[(int)((sorted.Length - 1) * 0.99)]:0.00}");
            if (BenchmarkScore.Summarize(test, starts, durs) is { } res)
                log.AppendLine($"scored: {res.Points} pts · avg {res.AvgFps} · low {res.LowFps} · stability {res.StabilityPct:0}%");
            if (png != null) bench.SavePng(scene.Output, png);
        }
        catch (Exception e) { log.AppendLine($"FAILED: {e}"); }
        AppData.SaveText("gpu-test.txt", log.ToString());
    }
}
