using System.Runtime.InteropServices;

namespace ThermoHammer.Engine.Llm;

// ─────────────────────────────────────────────────────────────────────────────
// LLM BENCHMARK — a 3B language model through llama.cpp, on the CPU and the GPU
// (Vulkan) — the phones' LlmSection.
//
//   The model has Llama 3.2 3B's exact shape in 4-bit (Q4_0, 1.8 GB), its
//   weights generated from a seed when the test starts (BenchCore/llm): nothing
//   is downloaded, the app carries no weights, and every op takes the real
//   model's time. Building it is not timed. Then:
//     prefill — the 512-token prompt in one batch, again until 3 s are measured;
//     decode  — 32-token replies, token by token, until 4 s are measured.
//   The CPU first tries thread counts; the GPU first runs a prompt and a reply
//   to build its pipelines.
// ─────────────────────────────────────────────────────────────────────────────

public sealed unsafe class LlmSection : IBenchmarkSection
{
    public const double PrefillS = 3, DecodeS = 4;
    public const int Reply = 32, WarmupTokens = 4;
    public const double NeedFree = 2_600_000_000;      // the model, its context and its buffers
    public const ulong NeedRam = 5_500_000_000;
    public const double NoticeS = 3;                   // how long "not run" stays up when the test can't run
    public const string Model = "LLAMA 3.2 3B · Q4_0";
    private const string RunningKey = "benchmark.llmRunning", CrashedKey = "benchmark.llmCrashed";

    private static string Build => System.Diagnostics.FileVersionInfo.GetVersionInfo(Environment.ProcessPath ?? "").FileVersion ?? "?";

    /// <summary>Accelerators whose run never finished in this build (llama.cpp's crashes can't be caught): a marker was left behind.</summary>
    private static HashSet<LlmAccel> CrashedAccels()
    {
        var crashed = Settings.Strings(CrashedKey);
        foreach (var running in Settings.Strings(RunningKey))
            if (!crashed.Contains(running)) crashed.Add(running);
        if (Settings.Strings(RunningKey).Count > 0)
        {
            Settings.Set(CrashedKey, crashed);
            Settings.Set(RunningKey, null);
        }
        return crashed.Select(s => s.Split(':')).Where(p => p.Length == 2 && p[0] == Build)
            .Select(p => LlmAccels.All.Cast<LlmAccel?>().FirstOrDefault(a => a.ToString() == p[1])).OfType<LlmAccel>().ToHashSet();
    }

    private static double FreeBytes => Win32.Memory().AvailPhys;

    [DllImport("kernel32.dll")] private static extern bool IsProcessorFeaturePresent(uint feature);

    /// <summary>Why the model can't run on this PC at all, or null.</summary>
    public static string? Problem(double free)
    {
        var m = Win32.Memory();
        if (Environment.GetEnvironmentVariable("THERMOHAMMER_LLM_FORCE") == "1") return null;   // checks under Wine, which can't report the CPU's features
        if (m.TotalPhys < NeedRam) return $"needs a PC with 6 GB of RAM (this one has {m.TotalPhys / 1e9:0.0} GB)";
        if (System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture == Architecture.X64 && !IsProcessorFeaturePresent(40))   // AVX2
            return "the CPU lacks the AVX2 instructions the model's kernels use";
        if (System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture == Architecture.Arm64 && !IsProcessorFeaturePresent(43))  // dot product
            return "the CPU lacks the ARMv8.2 dot-product instructions the model's kernels use";
        if (free < NeedFree) return $"Windows has only {free / 1e9:0.0} GB free now; the model needs {NeedFree / 1e9:0.0}";
        return null;
    }

    public void Run(BenchmarkEngine engine, BenchmarkResult r)
    {
        engine.PublishLlm(LlmAccel.Cpu, LlmPhase.Build, 0, -1, "PREPARING");
        double free = FreeBytes;
        double freeGb = BenchmarkScore.Round2(free / 1e9);
        int largest = 0;
        string gpuName = "", gpuApi = "";
        string? problem = Problem(free);
        try
        {
            largest = LlmNative.bc_llm_largest_model(free);
            gpuName = LlmNative.GpuName;
            gpuApi = LlmNative.GpuApi;
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException or BadImageFormatException)
        {
            problem ??= "the LLM runtime (benchllm.dll) isn't part of this build";
        }
        if (problem != null)
        {
            // nothing can run: say why for a moment, every accelerator scores 0
            engine.PublishLlm(LlmAccel.Cpu, LlmPhase.Build, 0, -1, $"NOT RUN · {problem.ToUpperInvariant()}");
            Thread.Sleep(TimeSpan.FromSeconds(NoticeS));
            r.Llm = new LlmResult(LlmAccels.All.Select(a => new LlmRun(a, 0, 0, Error: problem)).ToList(), null, freeGb, largest, problem);
            engine.PublishLlm(null, LlmPhase.Build, 0, -1, "");
            return;
        }
        var crashed = CrashedAccels();
        var runs = new List<LlmRun>();
        int threads = 4;
        foreach (var accel in LlmAccels.All)
        {
            if (!engine.IsActive) return;
            string? skip = crashed.Contains(accel) ? "crashed the app here last time"
                : accel == LlmAccel.Gpu && gpuName.Length == 0 ? "llama.cpp found no Vulkan GPU it can run on" : null;
            if (skip != null) { runs.Add(new LlmRun(accel, 0, 0, Error: skip)); continue; }
            Settings.Set(RunningKey, new[] { $"{Build}:{accel}" });
            LlmRun? run;
            try { run = Measure(accel, threads, () => engine.IsActive, (phase, progress, rate, detail, reply, prompts) => engine.PublishLlm(accel, phase, progress, rate, detail, reply, prompts), gpuApi); }
            finally { Settings.Set(RunningKey, null); }
            if (run == null) return;     // stopped
            if (run.Threads > 0) threads = run.Threads;
            runs.Add(run);
        }
        bool gpuRan = runs.Any(x => x.Accel == LlmAccel.Gpu && x.Decode > 0);
        r.Llm = new LlmResult(runs, gpuRan && gpuName.Length > 0 ? gpuName : null, freeGb, largest);
        engine.PublishLlm(null, LlmPhase.Build, 0, -1, "");
    }

    /// <summary>Prefill and decode tokens/s on `accel`; a run that fails keeps its reason. Null if the run was stopped.</summary>
    public static LlmRun? Measure(LlmAccel accel, int threads, Func<bool> isActive, Action<LlmPhase, double, double, string, int[]?, int> show, string gpuApi)
    {
        bool gpu = accel == LlmAccel.Gpu;
        string where = gpu ? $"GPU · {(gpuApi.Length > 0 ? gpuApi.ToUpperInvariant() : "VULKAN")}" : "CPU";
        void Show(LlmPhase phase, double progress, double rate, string detail, int[]? reply = null, int prompts = 0) =>
            show(phase, progress, rate, detail, reply, prompts);
        Show(LlmPhase.Build, 0, -1, $"{where} · BUILDING THE MODEL · 1.8 GB OF WEIGHTS FROM A SEED");
        var err = new byte[512];
        IntPtr h;
        fixed (byte* e = err) h = LlmNative.bc_llm_create(gpu ? 1 : 0, threads, LlmNative.Seed, e, err.Length);
        if (h == IntPtr.Zero)
        {
            string why = System.Text.Encoding.UTF8.GetString(err).TrimEnd('\0');
            return new LlmRun(accel, 0, 0, Error: why.Length == 0 ? "the model could not be built" : why);
        }
        // a prompt on a slow CPU takes seconds: a stopped run cancels it from here
        bool finished = false;
        var watcher = new Thread(() =>
        {
            while (!Volatile.Read(ref finished))
            {
                if (!isActive()) { LlmNative.bc_llm_cancel(h); return; }
                Thread.Sleep(100);
            }
        }) { IsBackground = true };
        watcher.Start();
        try
        {
            LlmRun? Failed()
            {
                if (!isActive()) return null;
                string why = LlmNative.Error(h);
                return new LlmRun(accel, 0, 0, Error: why.Length == 0 ? "failed" : why);
            }
            int decodeThreads = threads, batchThreads = threads;
            if (gpu)
            {
                Show(LlmPhase.Warmup, 0, -1, $"{where} · BUILDING AND WARMING THE PIPELINES");
                if (LlmNative.bc_llm_prefill(h, LlmNative.Prompt) < 0) return Failed();
                LlmNative.bc_llm_reset(h);
                for (int i = 0; i < WarmupTokens; i++) if (LlmNative.bc_llm_next(h, null) < 0) return Failed();
            }
            else
            {
                Show(LlmPhase.Warmup, 0, -1, $"{where} · FINDING THE FASTEST THREAD COUNT");
                int t = 0, b = 0;
                if (LlmNative.bc_llm_tune_threads(h, &t, &b) == 0) return Failed();
                decodeThreads = t; batchThreads = b;
                Show(LlmPhase.Warmup, 0, -1, $"{where} · {t} THREADS DECODE · {b} PREFILL");
            }
            string threadsNote = gpu ? "" : $" · {decodeThreads} THREADS";
            const int prompt = LlmNative.Prompt;

            double promptS = 0; int prompts = 0;
            while (prompts == 0 || promptS < PrefillS)
            {
                if (!isActive()) return null;
                Show(LlmPhase.Prefill, Math.Min(0.5, promptS / PrefillS / 2), prompts > 0 ? prompts * prompt / promptS : -1,
                    $"{where} · PREFILL · {prompt}-TOKEN PROMPT", prompts: prompts);
                double s = LlmNative.bc_llm_prefill(h, prompt);
                if (s < 0) return Failed();
                promptS += s;
                prompts++;
            }
            double prefill = prompts * prompt / promptS;
            Show(LlmPhase.Prefill, 0.5, prefill, $"{where} · PREFILL · {prompt}-TOKEN PROMPT", prompts: prompts);

            double decodeS = 0; int tokens = 0;
            long shown = 0;
            while (tokens == 0 || decodeS < DecodeS)
            {
                if (!isActive()) return null;
                LlmNative.bc_llm_reset(h);
                var replyIds = new List<int>();
                for (int i = 0; i < Reply; i++)
                {
                    int token = 0;
                    double s = LlmNative.bc_llm_next(h, &token);
                    if (s < 0) return Failed();
                    decodeS += s;
                    tokens++;
                    replyIds.Add(token);
                    long now = Clock.Ns();
                    if (now - shown > 80_000_000 || i == Reply - 1)
                    {
                        shown = now;
                        Show(LlmPhase.Decode, 0.5 + Math.Min(0.5, decodeS / DecodeS / 2), tokens / decodeS,
                            $"{where} · DECODE · TOKEN BY TOKEN{threadsNote}", replyIds.ToArray(), prompts);
                    }
                }
            }
            return new LlmRun(accel, BenchmarkScore.Round2(prefill), BenchmarkScore.Round2(tokens / decodeS),
                gpu ? 0 : decodeThreads, null, gpu ? 0 : batchThreads);
        }
        finally
        {
            Volatile.Write(ref finished, true);
            LlmNative.bc_llm_destroy(h);
        }
    }
}


/// <summary>`--llmTest cpu|gpu`: the LLM test on one accelerator without the window, its numbers to %LOCALAPPDATA%\ThermoHammer\llm-test.txt.</summary>
public static class LlmCheck
{
    public static void Run(string which)
    {
        var log = new System.Text.StringBuilder();
        try
        {
            var accel = which == "gpu" ? LlmAccel.Gpu : LlmAccel.Cpu;
            double free = Win32.Memory().AvailPhys;
            log.AppendLine($"free {free / 1e9:0.0} GB · problem: {LlmSection.Problem(free) ?? "none"} · gpu {LlmNative.GpuName} ({LlmNative.GpuApi}) · largest {LlmNative.bc_llm_largest_model(free)}B");
            var run = LlmSection.Measure(accel, 4, () => true, (phase, progress, rate, detail, reply, prompts) =>
            {
                if (Environment.GetEnvironmentVariable("THERMOHAMMER_SHADER_DEBUG") == "1") Console.Error.WriteLine($"{phase} {progress:0.00} {rate:0.0} {detail}");
            }, LlmNative.GpuApi);
            log.AppendLine(run == null ? "stopped" : run.Error != null ? $"{accel} FAILED: {run.Error}"
                : $"{accel} · prefill {run.Prefill:0.00} tok/s · decode {run.Decode:0.00} tok/s · threads {run.Threads} / {run.BatchThreads}");
        }
        catch (Exception e) { log.AppendLine($"FAILED: {e}"); }
        AppData.SaveText("llm-test.txt", log.ToString());
    }
}
