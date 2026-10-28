using System.Windows.Threading;

namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK ENGINE — the run, in the phones' order: getting ready (cool and
// quiet), then CPU → MEM → GPU → UX → AI → LLM. The work runs on its own thread;
// the stage reads the published state (on the UI thread).
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>A section after MEM. One the Windows app can't run yet adds its reason to the result (the run is then incomplete).</summary>
public interface IBenchmarkSection
{
    void Run(BenchmarkEngine engine, BenchmarkResult result);
}

public sealed class BenchmarkEngine
{
    public static readonly BenchmarkEngine Shared = new();

    public enum Phase { Idle, Preparing, Running, Done }

    public Phase Current { get; private set; } = Phase.Idle;
    public bool PreparingBusy { get; private set; }
    public int PreparingElapsed { get; private set; }
    public CpuTest? CpuTest { get; private set; }
    public CpuProgress? CpuProgress { get; private set; }
    public MemTest? MemTest { get; private set; }
    public MemProgress? MemProgress { get; private set; }
    /// <summary>The GPU test on the stage now (null between tests), its fps so far, progress through its timeline, and a small BGRA frame (960×540).</summary>
    public GpuTest? GpuTest { get; private set; }
    public double GpuFps { get; private set; } = -1;
    public double GpuProgress { get; private set; }
    public string GpuDetail { get; private set; } = "";
    public byte[]? GpuFrame { get; private set; }
    public int GpuFrameNumber { get; private set; }
    /// <summary>The UX test on the stage now: progress, a detail line, a live number and its unit.</summary>
    public UxTest? UxTest { get; private set; }
    public double UxProgress { get; private set; }
    public string UxDetail { get; private set; } = "";
    public double UxValue { get; private set; } = -1;
    public string UxUnit { get; private set; } = "";
    /// <summary>The QR test's frame under the scanner (BGRA, 640×360) and where the scanner found the code (frame pixels, 1280×720).</summary>
    /// <summary>The AI model on the stage now, on which accelerator (null between them), its live rate and what it saw.</summary>
    public AiTest? AiTest { get; private set; }
    public AiAccel? AiAccel { get; private set; }
    public double AiProgress { get; private set; }
    public double AiRate { get; private set; } = -1;
    public string AiDetail { get; private set; } = "";
    public Ai.AiVisual? AiVisual { get; private set; }
    /// <summary>The LLM test on the stage now: the accelerator (null when the section is over), its phase and live rate.</summary>
    public LlmAccel? LlmAccel { get; private set; }
    public LlmPhase LlmPhase { get; private set; }
    public double LlmProgress { get; private set; }
    public double LlmRate { get; private set; } = -1;
    public string LlmDetail { get; private set; } = "";
    public int[] LlmReply { get; private set; } = [];
    public int LlmPrompts { get; private set; }
    /// <summary>The page the stage shows (the web tests), e.g. "bench.html"; null between pages.</summary>
    public string? WebPage { get; private set; }
    /// <summary>A decoded (or exported) frame now and then (UX 5–6), the player overlay (UX 5) and the export dialog (UX 6).</summary>
    public Ux.VideoBench.Preview? VideoFrame { get; private set; }
    public int VideoFrameNumber { get; private set; }
    public Ux.VideoOsd? VideoOsd { get; private set; }
    public Ux.ExportStage? ExportStage { get; private set; }
    public byte[]? QrFrame { get; private set; }
    public (float x, float y)[]? QrFound { get; private set; }
    /// <summary>Another window was in front during the run (the stage says so while it lasts).</summary>
    public bool InBackground { get; private set; }
    public BenchmarkResult? Result { get; private set; }
    public string? Error { get; private set; }
    public BenchmarkRecord? Record { get; private set; }
    public bool IsRunning => Current is Phase.Preparing or Phase.Running;

    /// <summary>Raised on the UI thread whenever something on the stage changed.</summary>
    public event Action? Changed;

    /// <summary>Sections after MEM, in run order (GPU, UX, AI, LLM) — added as the Windows port grows.</summary>
    public List<IBenchmarkSection> LaterSections { get; } = [new Gpu.GpuSection(), new Ux.UxSection(), new Ai.AiSection(), new Llm.LlmSection()];

    public const double CooldownMaxS = 30;
    public const float QuietShare = 0.85f;     // starts once it gets ≥ 85 % of the CPU…
    public const float QuietSteady = 0.05f;    // …or 3 checks in a row agree within 5 points (the machine's normal)
    public const double QuietPollS = 3, QuietMaxS = 60;

    private readonly Dispatcher? ui = System.Windows.Application.Current?.Dispatcher;   // null in the window-less checks (`--headlessBenchmark`)
    /// <summary>Checks only: run just these sections (cpu, mem, gpu, ux, ai, llm), without the cool-down wait.</summary>
    public HashSet<string>? Only { get; set; }
    public bool HasBrowser => ui != null;
    private readonly object gate = new();
    private bool active, lostFront;
    public bool IsActive { get { lock (gate) return active; } }

    private BenchmarkEngine() { }

    /// <summary>A separate engine for window-less checks.</summary>
    public static BenchmarkEngine CreateHeadless(string[]? only) => new() { Only = only?.ToHashSet() };

    private void Ui(Action a)
    {
        if (ui == null) { a(); Changed?.Invoke(); return; }
        ui.BeginInvoke(() => { a(); Changed?.Invoke(); });
    }

    /// <summary>The GPU test on the stage now (worker thread).</summary>
    public void PublishGpu(GpuTest? test, double fps, double progress, string detail = "")
        => Ui(() => { GpuTest = test; GpuFps = fps; GpuProgress = progress; GpuDetail = detail; });

    /// <summary>The UX test on the stage now (worker thread); null between tests.</summary>
    public void PublishUx(UxTest? test, double progress = 0, string detail = "", double value = -1, string unit = "")
        => Ui(() => { UxTest = test; UxProgress = progress; UxDetail = detail; UxValue = value; UxUnit = unit; });

    /// <summary>The AI model on the stage now, on which accelerator (null between them), and its live rate (worker thread).</summary>
    public void PublishAi(AiTest? test, AiAccel? accel, double progress, double rate, string detail)
        => Ui(() => { UxTest = null; AiTest = test; AiAccel = accel; AiProgress = progress; AiRate = rate; AiDetail = detail; });

    public void PublishAiVisual(Ai.AiVisual? v) => Ui(() => AiVisual = v);

    public void PublishVideoFrame(Ux.VideoBench.Preview? frame) { if (frame != null) Ui(() => { VideoFrame = frame; VideoFrameNumber++; }); }

    public void PublishVideoOsd(Ux.VideoOsd? osd) => Ui(() => VideoOsd = osd);

    public void PublishExportStage(Ux.ExportStage? stage) => Ui(() => ExportStage = stage);

    /// <summary>The LLM test on the stage now: the accelerator (null when the section is over), its phase and live rate (worker thread).</summary>
    public void PublishLlm(LlmAccel? accel, LlmPhase phase, double progress, double rate, string detail, int[]? reply = null, int prompts = 0)
        => Ui(() =>
        {
            UxTest = null; AiTest = null; AiVisual = null;
            LlmAccel = accel; LlmPhase = phase; LlmProgress = progress; LlmRate = rate; LlmDetail = detail; LlmReply = reply ?? []; LlmPrompts = prompts;
        });

    /// <summary>The QR frame the scanner is on (worker thread).</summary>
    public void PublishQr(byte[] bgra, (float x, float y)[]? found) => Ui(() => { QrFrame = bgra; QrFound = found; });

    // ── web tests — the stage's WebView2 reports here ──

    /// <summary>A web test's outcome: the page's numbers, or why it failed.</summary>
    public sealed record WebOutcome(Dictionary<string, double> Values, string? Error)
    {
        /// <summary>The number the page reported under `key`, kept to 2 decimals (what is hashed and submitted).</summary>
        public double Number(string key)
        {
            if (Error != null) throw new BenchException(Error);
            return Values.TryGetValue(key, out var v) ? BenchmarkScore.Round2(v) : throw new BenchException($"no {key} in the result");
        }
    }

    private readonly object webLock = new();
    private (string? json, string? error)? webResult;
    private ManualResetEventSlim? webWait;

    /// <summary>From the page (UI thread): Web browsing reports each ramping scene; scrolling and gallery send their HUD line and big number.</summary>
    public void WebProgress(string scene, double fraction, int complexity, double value, int index)
    {
        switch (UxTest)
        {
            case Engine.UxTest.Web:
                string[] units = ["CARDS", "SHAPES", "TILES", "PATHS"];
                UxProgress = Math.Min(1, Math.Max(0, (index + fraction) / 5));
                UxDetail = $"{scene.ToUpperInvariant()} · {complexity:N0} {(index >= 0 && index < units.Length ? units[index] : "OPS")}";
                UxValue = value > 0 ? value : -1;
                UxUnit = value > 0 ? "FPS" : "";
                break;
            case Engine.UxTest.Scroll or Engine.UxTest.Gallery:
                UxProgress = Math.Min(1, Math.Max(0, fraction));
                UxDetail = scene.ToUpperInvariant();
                UxValue = value > 0 ? value : -1;
                UxUnit = UxTest == Engine.UxTest.Scroll ? "PX/S" : "MP/S";
                break;
            default: return;
        }
        Changed?.Invoke();
    }

    /// <summary>The page is done (its JSON) or failed (UI thread). Only the first call counts.</summary>
    public void WebFinished(string? json, string? error)
    {
        lock (webLock)
        {
            if (webResult != null) return;
            webResult = (json, error);
            webWait?.Set();
        }
    }

    /// <summary>Shows `test`'s page on the stage and waits for its JSON (worker thread). Null if the run was stopped.</summary>
    public WebOutcome? RunWebPage(UxTest test)
    {
        if (ui == null) return new WebOutcome([], "no browser in this mode");
        using var wait = new ManualResetEventSlim();
        lock (webLock) { webResult = null; webWait = wait; }
        Ui(() => { UxTest = test; UxProgress = 0; UxDetail = "LOADING"; UxValue = -1; UxUnit = ""; WebPage = test.Page(); });
        var deadline = DateTime.UtcNow.AddSeconds(test.Seconds() * 4 + 60);
        (string? json, string? error)? outcome = null;
        while (outcome == null && IsActive)
        {
            if (wait.Wait(250)) lock (webLock) outcome = webResult;
            else if (DateTime.UtcNow > deadline) outcome = (null, "timed out");
        }
        Ui(() => WebPage = null);
        lock (webLock) webWait = null;
        if (!IsActive || outcome is not { } o) return null;
        if (o.error != null) return new WebOutcome([], o.error);
        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(o.json ?? "");
            var values = new Dictionary<string, double>();
            foreach (var prop in doc.RootElement.EnumerateObject())
                if (prop.Value.ValueKind == System.Text.Json.JsonValueKind.Number) values[prop.Name] = prop.Value.GetDouble();
            return new WebOutcome(values, null);
        }
        catch (Exception) { return new WebOutcome([], "unreadable result"); }
    }

    /// <summary>A finished frame, as the benchmark's display copy (worker thread).</summary>
    public void PublishGpuFrame(byte[] bgra)
    {
        var copy = (byte[])bgra.Clone();
        if (ui == null) return;
        ui.BeginInvoke(() => { GpuFrame = copy; GpuFrameNumber++; Changed?.Invoke(); });
    }

    public void Start()
    {
        if (IsRunning) return;
        lock (gate) { active = true; lostFront = false; }
        Result = null; Error = null; Record = null; CpuTest = null; MemTest = null; CpuProgress = null; MemProgress = null;
        InBackground = false;
        Current = Phase.Preparing;
        Win32.SetThreadExecutionState(Win32.EsContinuous | Win32.EsSystemRequired | Win32.EsDisplayRequired);
        var t = new Thread(RunAll, 8 << 20) { IsBackground = true, Name = "benchmark", Priority = ThreadPriority.AboveNormal };
        t.Start();
        Changed?.Invoke();
    }

    public void Stop() { lock (gate) active = false; }

    public void DismissResult()
    {
        Current = Phase.Idle; Result = null; Record = null; Error = null;
        Changed?.Invoke();
    }

    /// <summary>
    /// The run shares the screen: a window covered by another app has its web content throttled (the browser tests
    /// stall), and whatever is in front competes for the processor. Losing the front during the run flags it unverified.
    /// Called by the main window when it's activated or deactivated.
    /// </summary>
    public void WindowActive(bool isActive)
    {
        if (!IsRunning) return;
        if (!isActive && Current == Phase.Running) lock (gate) lostFront = true;   // getting ready doesn't count
        InBackground = !isActive;
        Changed?.Invoke();
    }

    private static bool Charging() => false;   // a PC on its charger is how it's normally used (as on a Mac)

    private void RunAll()
    {
        var r = new BenchmarkResult { GpuName = DeviceInfo.GpuName };
        var tel = Telemetry.Shared.Current;
        if (tel.LowPower) r.ValidityFlags |= BenchFlag.EnvCapped;
        bool onAc = tel.OnAc;

        // getting ready: cool, and nothing else eating the processor. Not cooling all the way doesn't flag the run:
        // the wait is a courtesy, not a condition (as on the phones)
        if (Only == null)
        {
            bool cooled = CoolDown();
            var quiet = WaitUntilQuiet();
            if (cooled && quiet.waited >= 20) CoolDown();   // it had cooled; the busy spell may have warmed it
            if (!quiet.quiet) r.ValidityFlags |= BenchFlag.Interference;
            r.StartCpuShare = quiet.share;
            r.QuietWaitSec = quiet.waited;
        }
        if (!IsActive) { Finish(null, null); return; }
        Ui(() => Current = Phase.Running);

        try
        {
            if (Only == null || Only.Contains("cpu")) r.Cpu = RunCpu();
            if (!IsActive) { Finish(null, null); return; }
            if (Telemetry.Shared.Current.OnAc != onAc) r.ValidityFlags |= BenchFlag.PowerEvent;   // a laptop plugged or unplugged
            if (Only == null || Only.Contains("mem")) r.Mem = RunMem();
            if (!IsActive) { Finish(null, null); return; }
            foreach (var section in LaterSections)
            {
                if (Only != null && !Only.Contains(section.GetType().Name.Replace("Section", "").ToLowerInvariant())) continue;
                if (Telemetry.Shared.Current.OnAc != onAc) r.ValidityFlags |= BenchFlag.PowerEvent;
                section.Run(this, r);
                if (!IsActive) { Finish(null, null); return; }
            }
            if (Telemetry.Shared.Current.OnAc != onAc || Telemetry.Shared.Current.LowPower && !tel.LowPower)
                r.ValidityFlags |= Telemetry.Shared.Current.LowPower ? BenchFlag.EnvCapped : BenchFlag.PowerEvent;
            lock (gate) if (lostFront) r.ValidityFlags |= BenchFlag.Interference;
            Finish(r, null);
        }
        catch (Exception e)
        {
            Finish(null, IsActive ? e.Message : null);   // a stop is not an error
        }
    }

    private void Finish(BenchmarkResult? r, string? error)
    {
        lock (gate) active = false;
        var record = r == null ? null : BenchmarkRecord.From(r);
        Ui(() =>
        {
            Win32.SetThreadExecutionState(Win32.EsContinuous);   // the UI thread set it (the state is per thread)
            CpuTest = null; MemTest = null; InBackground = false; GpuTest = null; GpuFrame = null; UxTest = null; QrFrame = null; QrFound = null; AiTest = null; AiAccel = null; AiVisual = null; LlmAccel = null;
            VideoFrame = null; VideoOsd = null; ExportStage = null;
            Error = error;
            Result = r;
            Record = record;
            if (record != null) BenchmarkHistoryStore.Shared.Add(record);
            Current = r != null || error != null ? Phase.Done : Phase.Idle;
        });
        // checks from a console (`ThermoHammer.exe --autoBenchmark`): the report to a file beside the settings
        if (App.AutoBenchmark)
            AppData.SaveText("auto-benchmark.txt", r != null ? BenchmarkReport.Text(r, DateTime.Now.ToString("yyyy-MM-dd HH:mm")) : $"benchmark ended: {error ?? "stopped"}");
    }

    /// <summary>Waits (up to 30 s) for the thermal level to return to nominal.</summary>
    private bool CoolDown()
    {
        var t0 = DateTime.UtcNow;
        while (IsActive)
        {
            if (Telemetry.Shared.Current.Thermal == ThermalLevel.Nominal) return true;
            double waited = (DateTime.UtcNow - t0).TotalSeconds;
            if (waited >= CooldownMaxS) return false;
            Ui(() => { PreparingBusy = false; PreparingElapsed = (int)waited; });
            Thread.Sleep(1000);
        }
        return true;
    }

    /// <summary>
    /// Holds the run while something else is eating the processor. A share that holds steady below the bar is the
    /// machine's normal, not work that will pass, so the run starts. Not quiet if it never settled within a minute.
    /// </summary>
    private (bool quiet, float share, int waited) WaitUntilQuiet()
    {
        var t0 = DateTime.UtcNow;
        var recent = new List<float>();
        float share = 1;
        try
        {
            while (IsActive)
            {
                share = QuietProbe.CpuShare();
                double waited = (DateTime.UtcNow - t0).TotalSeconds;
                if (share >= QuietShare) return (true, share, (int)waited);
                recent.Add(share);
                if (recent.Count > 3) recent.RemoveAt(0);
                if (recent.Count == 3 && recent.Max() - recent.Min() <= QuietSteady) return (true, share, (int)waited);
                if (waited >= QuietMaxS) return (false, share, (int)waited);
                Ui(() => { PreparingBusy = true; PreparingElapsed = (int)waited; });
                Thread.Sleep(TimeSpan.FromSeconds(QuietPollS));
            }
            return (true, share, (int)(DateTime.UtcNow - t0).TotalSeconds);
        }
        finally { Ui(() => PreparingBusy = false); }
    }

    private CpuResult RunCpu()
    {
        var bench = new CpuBench();
        var runs = new List<CpuTestRun>();
        long last = 0;
        foreach (var test in CpuTests.All)
        {
            if (!IsActive) break;
            var labels = test.Labels();
            Ui(() => { CpuTest = test; CpuProgress = new CpuProgress(labels[0], 0, -1, test.MultiCore() ? bench.Threads : 1); });
            runs.Add(bench.Run(test, () => IsActive, p =>
            {
                long now = Clock.Ns();
                if (now - last <= 120_000_000) return;
                last = now;
                Ui(() => CpuProgress = p);
            }));
        }
        Ui(() => CpuTest = null);
        return new CpuResult(runs);
    }

    private MemResult RunMem()
    {
        var bench = new MemBench();
        RamResult? ram = null; StorageResult? storage = null; DbResult? db = null;
        foreach (var test in MemTests.All)
        {
            if (!IsActive) break;
            Ui(() => { MemTest = test; MemProgress = null; });
            void Report(MemProgress p) => Ui(() => MemProgress = p);
            switch (test)
            {
                case Engine.MemTest.Ram: ram = bench.Ram(() => IsActive, Report); break;
                case Engine.MemTest.Storage: storage = bench.Storage(() => IsActive, Report); break;
                default: db = bench.Database(() => IsActive, Report); break;
            }
        }
        Ui(() => MemTest = null);
        if (ram == null || storage == null || db == null) throw new BenchException("stopped");
        return new MemResult(ram, storage, db);
    }
}
