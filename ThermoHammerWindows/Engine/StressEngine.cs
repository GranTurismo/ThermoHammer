using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Windows.Threading;

namespace ThermoHammer.Engine;

public enum TestDuration { Minutes5, Minutes15, Minutes30 }

public static class TestDurations
{
    public static double Seconds(this TestDuration d) => d switch { TestDuration.Minutes15 => 15 * 60, TestDuration.Minutes30 => 30 * 60, _ => 5 * 60 };
    public static string DisplayName(this TestDuration d) => d switch { TestDuration.Minutes15 => "15 Min", TestDuration.Minutes30 => "30 Min", _ => "5 Min" };
    /// <summary>The server's HammerType: 0 = 5 min, 1 = 15 min, 2 = 30 min.</summary>
    public static int Wire(this TestDuration d) => (int)d;
}

public enum StressThreadingType { Single = 0, Multi = 1 }

// A test is a sequence of phases, not a single loop:
// PRE-FLIGHT gates → COOLDOWN → WARM-UP (unrecorded) → CALIBRATION (boost window) → MEASURED.
public enum RunPhase { Idle, Cooldown, Warmup, Calibration, Measured }

public static class RunPhases
{
    public static string Label(this RunPhase p) => p switch
    {
        RunPhase.Cooldown => "COOLDOWN", RunPhase.Warmup => "WARM-UP", RunPhase.Calibration => "CALIBRATING",
        RunPhase.Measured => "STRESS ACTIVE", _ => "IDLE",
    };
}

// Per-sample throttling attribution — on Windows from the firmware's performance cap and passive cooling, the
// workers' scheduled-time fraction and ambient system load.
public enum Attribution { None = 0, Thermal = 1, Contention = 2, Unknown = 3 }

/// <summary>Validity bitfield (persisted with every run; the same bits as the phone apps).</summary>
public static class ValidityFlag
{
    public const int WarmStarted = 1 << 0;      // thermal level already elevated during calibration
    public const int Interference = 1 << 1;     // >10% of samples starved by other load
    public const int PowerEvent = 1 << 2;       // charger plugged or unplugged mid-run (a laptop's limits change with it)
    public const int FreqMissing = 1 << 3;      // clock telemetry unavailable
    public const int ShortRun = 1 << 4;         // ended before 90% of target duration
    public const int EnvCapped = 1 << 5;        // battery saver or "best power efficiency" during the run
    public const int SuspiciousBoost = 1 << 6;
    public const int CooldownTimeout = 1 << 7;  // cooldown ended without reaching nominal
    public const int BaselineExceeded = 1 << 8; // measured run sustained >105% of calibration baseline

    /// <summary>Only verified runs go to the leaderboard: nothing set but FreqMissing, which describes the PC, not the run.</summary>
    public static bool Submittable(int flags) => (flags & ~FreqMissing) == 0;

    public static List<string> Names(int flags)
    {
        var o = new List<string>();
        if ((flags & WarmStarted) != 0) o.Add("WARM START");
        if ((flags & Interference) != 0) o.Add("INTERFERENCE");
        if ((flags & PowerEvent) != 0) o.Add("POWER EVENT");
        if ((flags & FreqMissing) != 0) o.Add("NO CLOCK DATA");
        if ((flags & ShortRun) != 0) o.Add("SHORT RUN");
        if ((flags & EnvCapped) != 0) o.Add("LOW POWER MODE");
        if ((flags & SuspiciousBoost) != 0) o.Add("BOOST ANOMALY");
        if ((flags & CooldownTimeout) != 0) o.Add("COOLDOWN TIMEOUT");
        if ((flags & BaselineExceeded) != 0) o.Add("BASELINE EXCEEDED");
        return o;
    }
}

public static class EnvFlag
{
    public const int Charging = 1 << 0;
    public const int LowPowerMode = 1 << 1;
    public const int ThermalElevated = 1 << 2;
}

/// <summary>Stamp v2 — the unit of recorded truth (emitted ~1 Hz during MEASURED).</summary>
public sealed record StampV2(long TNs, long WindowNs, long IpsTotal, long[] IpsPerWorker, double SchedFracAvg,
    int ThermalState, double SystemLoad, int Attribution, int EnvFlags, double CpuMhz)
{
    public int ElapsedMs => (int)(TNs / 1_000_000);
}

public sealed record GateResult(string Id, string Title, bool Blocking, bool Passed, string Detail);

public sealed record PreflightReport(List<GateResult> Gates, bool CanRun, int ValidityHints);

/// <summary>CPU cluster topology: worker indices per core type, fastest first.</summary>
public sealed class ClusterTopology(List<int[]> clusters, List<string> names)
{
    public List<int[]> Clusters { get; } = clusters;
    public List<string> Names { get; } = names;
    public int TotalCores => Clusters.Sum(c => c.Length);
    public int SlowestClusterIdx => Names.FindIndex(n => !n.Contains("PERF", StringComparison.OrdinalIgnoreCase)) is var i and >= 0 ? i : Names.Count - 1;
    public string Describe() => string.Join("|", Clusters.Select((c, i) => $"{c.Length}x{Names[i]}"));
    public static ClusterTopology Flat(int n) => new([Enumerable.Range(0, n).ToArray()], ["CPU"]);
}

/// <summary>Final scorecard — replaces the single "stability" number.</summary>
public sealed class Scorecard
{
    public double BaselineIps;
    public double SustainedRatio = 100;
    public double DeliveredCapacity = 100;
    public double? MinSustained;
    public int? ThrottleOnsetSec;
    public double TimeInThrottlePct;
    public int Confidence = 100;
    public int ValidityFlags;
    public bool WarmStarted;
    public bool IsVerified => ValidityFlag.Submittable(ValidityFlags);
}

/// <summary>Live state, read and written on the UI thread.</summary>
public sealed class StressState
{
    public RunPhase Phase = RunPhase.Idle;
    public double Elapsed;
    public double Duration;
    public List<StampV2> Stamps = [];
    public List<(RunPhase phase, double ips)> PreRunIps = [];
    public double LiveIps;
    public double BaselineIps;
    public Attribution Attribution = Attribution.None;
    public double[] WorkerRatios = [];
    public ThermalLevel Thermal = ThermalLevel.Nominal;
    public double SystemLoad;
    public ThermalLevel CooldownFrom = ThermalLevel.Nominal;
    public double CooldownElapsed;
    public Scorecard? Scorecard;
}

/// <summary>
/// The sustained stress test: one spinning worker per logical processor (or one), counted in iterations per second,
/// against the baseline of its own first seconds. The same phases and rules as the iPhone app (StressEngine.swift).
/// </summary>
public sealed class StressEngine
{
    public static readonly StressEngine Shared = new();

    public StressState State { get; private set; } = new();
    public bool IsRunning { get; private set; }
    public bool WasCompleted { get; private set; }
    public bool AbortedByUser { get; private set; }
    public StressThreadingType ThreadingType { get; private set; } = StressThreadingType.Multi;
    public TestDuration Duration { get; private set; } = TestDuration.Minutes5;
    public int CoreCount { get; }
    public ClusterTopology Topology { get; }

    /// <summary>Raised on the UI thread whenever the state changed.</summary>
    public event Action? Changed;
    /// <summary>Raised on the UI thread when a run ends (completed or not).</summary>
    public event Action? RunEnded;

    private readonly Dispatcher ui = System.Windows.Application.Current.Dispatcher;
    private readonly long[] counters;          // one per worker, 16 longs apart (no false sharing)
    private const int Stride = 16;
    private readonly IntPtr[] workerHandles;
    private volatile bool keepAlive;
    private Thread? session;

    private double[] calIps = [];
    private List<double> calList = [];
    private double[] workerBaselines;
    private double[] displayBaselines;
    private bool powerEventSeen, thermalEscalatedAtStart;

    private const double CooldownMaxS = 30, CooldownStallS = 45, WarmupS = 12, CalibrationS = 20;

    private StressEngine()
    {
        CoreCount = Environment.ProcessorCount;
        Topology = DeviceInfo.ReadTopology(CoreCount);
        counters = new long[CoreCount * Stride];
        workerHandles = new IntPtr[CoreCount];
        workerBaselines = Enumerable.Repeat(1.0, CoreCount).ToArray();
        displayBaselines = Enumerable.Repeat(1.0, CoreCount).ToArray();
        State.Thermal = Telemetry.Shared.Current.Thermal;
    }

    private void Ui(Action a) => ui.Invoke(() => { a(); Changed?.Invoke(); });

    /// <summary>The gates before a run — re-checked while the launch check is open.</summary>
    public static PreflightReport RunPreflight()
    {
        var gates = new List<GateResult>();
        int hints = 0;
        var t = Telemetry.Shared.Current;
        // a PC runs on its charger: that's how it's used (a laptop on battery runs slower by design)
        gates.Add(new("charger", "POWER", false, true,
            !t.HasBattery ? "mains power" : t.OnAc ? $"on the charger{(t.BatteryPct is { } b ? $" · battery {b}%" : "")}"
                : $"on battery{(t.BatteryPct is { } c ? $" {c}%" : "")} — a laptop runs slower unplugged"));
        if (t.HasBattery && !t.OnAc && t.BatteryPct is < 20)
            gates[^1] = gates[^1] with { Blocking = true, Passed = false, Detail = $"battery {t.BatteryPct}% — plug in first" };
        gates.Add(new("thermal", "THERMAL", false, true,
            t.Thermal == ThermalLevel.Nominal ? "nominal" : $"{t.Thermal.Name().ToLowerInvariant()} — cooldown will run first"));
        gates.Add(new("lowpower", "POWER MODE", false, !t.LowPower,
            t.BatterySaver ? "battery saver on — run flagged LOW POWER MODE"
                : t.LowPower ? "best power efficiency — run flagged LOW POWER MODE" : t.PowerMode.ToLowerInvariant()));
        if (t.LowPower) hints |= ValidityFlag.EnvCapped;
        gates.Add(new("clocks", "CLOCK TELEMETRY", false, t.HasClocks,
            t.HasClocks ? $"{t.CpuMhzAverage / 1000:0.00} GHz now · base {t.BaseMhz / 1000:0.00} GHz" : "Windows reports no clocks here"));
        // ambient CPU load (workers not yet started → everything is external)
        var a = Telemetry.SystemTimes();
        Thread.Sleep(800);
        double f = Telemetry.BusyFraction(a, Telemetry.SystemTimes());
        int pct = (int)Math.Round(f * 100);
        bool loadOk = f < 0.35;
        gates.Add(new("bgload", "BACKGROUND LOAD", true, loadOk, loadOk ? $"{pct}% ambient" : $"{pct}% busy — close other apps"));
        return new PreflightReport(gates, gates.All(g => !g.Blocking || g.Passed), hints);
    }

    public void StartTest(TestDuration duration, StressThreadingType threading)
    {
        if (IsRunning) return;
        Duration = duration;
        ThreadingType = threading;
        WasCompleted = false;
        AbortedByUser = false;
        powerEventSeen = false;
        thermalEscalatedAtStart = false;
        calList = [];
        workerBaselines = Enumerable.Repeat(1.0, CoreCount).ToArray();
        displayBaselines = Enumerable.Repeat(1.0, CoreCount).ToArray();
        Array.Clear(counters);
        var t = Telemetry.Shared.Current;
        State = new StressState
        {
            Phase = RunPhase.Cooldown,
            Duration = duration.Seconds(),
            WorkerRatios = new double[CoreCount],
            Thermal = t.Thermal,
            CooldownFrom = t.Thermal,
        };
        IsRunning = true;
        keepAlive = true;
        Win32.SetThreadExecutionState(Win32.EsContinuous | Win32.EsSystemRequired | Win32.EsDisplayRequired);
        session = new Thread(RunSession) { IsBackground = true, Name = "stress-session", Priority = ThreadPriority.Highest };
        session.Start();
        Changed?.Invoke();
    }

    public void Abort()
    {
        AbortedByUser = true;
        keepAlive = false;
    }

    // ── workers ──

    private void StartWorkers()
    {
        int active = ThreadingType == StressThreadingType.Single ? 1 : CoreCount;
        var ready = new CountdownEvent(active);
        for (int i = 0; i < active; i++)
        {
            int index = i;
            var th = new Thread(() => Worker(index, ready)) { IsBackground = true, Name = $"th-worker-{i}", Priority = ThreadPriority.AboveNormal };
            th.Start();
        }
        ready.Wait(2000);
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization)]
    private void Worker(int index, CountdownEvent ready)
    {
        workerHandles[index] = Win32.OpenThread(Win32.ThreadQueryLimitedInformation, false, Win32.GetCurrentThreadId());
        ready.Signal();
        ulong v1 = 0xAAAAAAAAAAAAAAAA, v2 = 0x5555555555555555, v3 = 0x3333333333333333, v4 = 0x7777777777777777;
        double f1 = 1.0000001, f2 = 2.0000002, f3 = 3.0000003, f4 = 4.0000004;
        var l1 = new ulong[4096];
        Array.Fill(l1, 0x123456789ABCDEF0UL);
        ref ulong cache = ref MemoryMarshal.GetArrayDataReference(l1);
        int idx = 0;
        ref long counter = ref counters[index * Stride];
        while (keepAlive)
        {
            for (int k = 0; k < 50_000; k++)
            {
                v1 = (v1 ^ (v2 + 7)) * 3; f1 = Math.FusedMultiplyAdd(1.0000001, 0.0000001, f1);
                v2 = (v2 ^ (v3 + 13)) * 5; f2 = Math.FusedMultiplyAdd(1.0000002, 0.0000002, f2);
                v3 = (v3 ^ (v4 + 17)) * 7; f3 = Math.FusedMultiplyAdd(1.0000003, 0.0000003, f3);
                v4 = (v4 ^ (v1 + 19)) * 11; f4 = Math.FusedMultiplyAdd(1.0000004, 0.0000004, f4);
                Unsafe.Add(ref cache, idx) ^= v1;
                idx = (idx + 1) & 4095;
            }
            Interlocked.Add(ref counter, 50_000);
        }
        ulong sink = v1 + v2 + v3 + v4 + (ulong)(long)(f1 + f2 + f3 + f4);
        if (sink == 0) Debug.WriteLine("optimizer fallback");
        IntPtr h = Interlocked.Exchange(ref workerHandles[index], IntPtr.Zero);
        if (h != IntPtr.Zero) Win32.CloseHandle(h);
    }

    private long[] WorkerCpuTimes()
    {
        int active = ThreadingType == StressThreadingType.Single ? 1 : CoreCount;
        var t = new long[active];
        for (int i = 0; i < active; i++) t[i] = workerHandles[i] != IntPtr.Zero ? Win32.ThreadCpuTime(workerHandles[i]) : 0;
        return t;
    }

    // ── the session ──

    private static long NowNs() => (long)(Stopwatch.GetTimestamp() * (1e9 / Stopwatch.Frequency));

    private void RunSession()
    {
        // COOLDOWN — no load, wait for the nominal level
        bool cooldownTimedOut = false;
        long cdStart = NowNs(), stallStart = cdStart;
        var lastLevel = Telemetry.Shared.Current.Thermal;
        while (keepAlive)
        {
            var ts = Telemetry.Shared.Current.Thermal;
            if (ts == ThermalLevel.Nominal) break;
            if (ts < lastLevel) { lastLevel = ts; stallStart = NowNs(); }
            long now = NowNs();
            double elapsed = (now - cdStart) / 1e9, stalled = (now - stallStart) / 1e9;
            if (elapsed >= CooldownMaxS || stalled >= CooldownStallS) { cooldownTimedOut = true; break; }
            Ui(() => { State.CooldownElapsed = elapsed; State.Thermal = ts; });
            Thread.Sleep(1000);
        }
        if (!keepAlive) { FinishAbort(); return; }

        // WORKERS ON — WARM-UP + CALIBRATION + MEASURED
        StartWorkers();
        Ui(() => State.Phase = RunPhase.Warmup);
        var prevHost = Telemetry.SystemTimes();
        var prevWorkerTimes = WorkerCpuTimes();
        var prevCounters = new long[CoreCount];
        long prevTickNs = NowNs(), workersOnNs = prevTickNs, measuredStartNs = 0;
        int contentionStamps = 0, thermalStamps = 0, above105 = 0, envAcc = 0;
        double stampAccumWork = 0;
        long stampLastNs = prevTickNs;
        var lastAttr = Attribution.None;
        bool chargingAtStart = Telemetry.Shared.Current.OnAc, calStarted = false;

        while (keepAlive)
        {
            long now = NowNs();
            long dt = Math.Max(1, now - prevTickNs);
            prevTickNs = now;

            // throughput
            var deltas = new double[CoreCount];
            double total = 0;
            for (int i = 0; i < CoreCount; i++)
            {
                long cur = Volatile.Read(ref counters[i * Stride]);
                deltas[i] = cur - prevCounters[i];
                prevCounters[i] = cur;
                total += deltas[i];
            }
            double ips = total * 1e9 / dt;
            var perWorkerIps = deltas.Select(d => d * 1e9 / dt).ToArray();

            // scheduled fraction (CPU time the workers got of the wall time)
            var wTimes = WorkerCpuTimes();
            double schedFrac = 1;
            if (wTimes.Length == prevWorkerTimes.Length && wTimes.Length > 0 && wTimes.All(x => x > 0))
            {
                double sum = 0;
                for (int i = 0; i < wTimes.Length; i++) sum += Math.Min(1, (wTimes[i] - prevWorkerTimes[i]) * 100.0 / dt);
                schedFrac = sum / wTimes.Length;
            }
            prevWorkerTimes = wTimes;
            var host = Telemetry.SystemTimes();
            double sysLoad = Telemetry.BusyFraction(prevHost, host);
            prevHost = host;
            var tel = Telemetry.Shared.Current;
            var ts = tel.Thermal;
            if (tel.OnAc != chargingAtStart && measuredStartNs > 0) powerEventSeen = true;

            double sinceOn = (now - workersOnNs) / 1e9;
            var phase = sinceOn < WarmupS ? RunPhase.Warmup : sinceOn < WarmupS + CalibrationS ? RunPhase.Calibration : RunPhase.Measured;

            if (phase == RunPhase.Warmup)
            {
                Publish(RunPhase.Warmup, ips, perWorkerIps, ts, sysLoad);
            }
            else if (phase == RunPhase.Calibration)
            {
                if (!calStarted)
                {
                    calStarted = true;
                    thermalEscalatedAtStart = ts != ThermalLevel.Nominal;
                }
                calList.Add(ips);
                Publish(RunPhase.Calibration, ips, perWorkerIps, ts, sysLoad);
            }
            else
            {
                double baseline = P95(calList);
                if (measuredStartNs == 0)
                {
                    measuredStartNs = now;
                    stampLastNs = now;
                    stampAccumWork = 0;
                    chargingAtStart = tel.OnAc;
                    int workers = ThreadingType == StressThreadingType.Single ? 1 : CoreCount;
                    workerBaselines = Enumerable.Repeat(Math.Max(baseline / workers, 1), CoreCount).ToArray();
                    Ui(() => { State.Phase = RunPhase.Measured; State.BaselineIps = baseline; State.Stamps = []; State.Elapsed = 0; });
                    for (int i = 0; i < CoreCount; i++) prevCounters[i] = Volatile.Read(ref counters[i * Stride]);
                    Thread.Sleep(250);
                    continue;
                }

                // attribution — the firmware's level, the workers' scheduled fraction, the throughput
                Attribution attr;
                if (ts >= ThermalLevel.Serious) attr = Attribution.Thermal;
                else if (schedFrac < 0.75) attr = Attribution.Contention;     // workers starved — external load, NOT thermal
                else if (ips < baseline * 0.90) attr = Attribution.Thermal;   // fully scheduled but throughput down → clocks
                else attr = Attribution.None;
                lastAttr = attr;
                if (attr == Attribution.Contention) contentionStamps++;
                if (attr == Attribution.Thermal) thermalStamps++;
                if (ips > baseline * 1.05) above105++;

                stampAccumWork += total;
                long window = now - stampLastNs;
                if (window >= 1_000_000_000)
                {
                    int env = 0;
                    if (tel.Charging) env |= EnvFlag.Charging;
                    if (tel.LowPower) env |= EnvFlag.LowPowerMode;
                    if (ts != ThermalLevel.Nominal) env |= EnvFlag.ThermalElevated;
                    envAcc |= env;
                    var stamp = new StampV2(now - measuredStartNs, window, (long)(stampAccumWork * 1e9 / window),
                        perWorkerIps.Select(x => (long)x).ToArray(), schedFrac, (int)ts, sysLoad, (int)lastAttr, env, tel.CpuMhzAverage);
                    stampLastNs = now;
                    stampAccumWork = 0;
                    long started = measuredStartNs;
                    Ui(() => { State.Stamps.Add(stamp); State.Elapsed = (now - started) / 1e9; });
                }
                var ratios = perWorkerIps.Select((x, i) => Math.Min(1.10, x / Math.Max(1, workerBaselines[i]))).ToArray();
                Ui(() =>
                {
                    State.LiveIps = ips; State.Attribution = attr; State.SystemLoad = sysLoad; State.Thermal = ts;
                    State.WorkerRatios = ratios;
                });
                if ((now - measuredStartNs) / 1e9 >= Duration.Seconds()) break;
            }
            Thread.Sleep(250);
        }

        // VERDICT
        keepAlive = false;
        var sc = BuildScorecard(cooldownTimedOut, contentionStamps, thermalStamps, above105, envAcc);
        bool reachedEnd = measuredStartNs > 0 && (NowNs() - measuredStartNs) / 1e9 >= Duration.Seconds() * 0.9;
        Ui(() =>
        {
            Win32.SetThreadExecutionState(Win32.EsContinuous);   // the UI thread set it (the state is per thread)
            State.Scorecard = sc;
            WasCompleted = reachedEnd && !AbortedByUser;
            IsRunning = false;
            State.Phase = RunPhase.Idle;
        });
        ui.Invoke(() => RunEnded?.Invoke());
    }

    private void FinishAbort()
    {
        keepAlive = false;
        Ui(() => { Win32.SetThreadExecutionState(Win32.EsContinuous); IsRunning = false; State.Phase = RunPhase.Idle; });
        ui.Invoke(() => RunEnded?.Invoke());
    }

    private void Publish(RunPhase phase, double ips, double[] workerIps, ThermalLevel ts, double load)
    {
        // pre-run gauges: normalise against each worker's running peak
        for (int i = 0; i < workerIps.Length; i++) if (workerIps[i] > displayBaselines[i]) displayBaselines[i] = workerIps[i];
        var ratios = workerIps.Select((w, i) => Math.Min(1.10, w / Math.Max(1, displayBaselines[i]))).ToArray();
        Ui(() =>
        {
            State.Phase = phase;
            State.LiveIps = ips;
            State.PreRunIps.Add((phase, ips));
            State.WorkerRatios = ratios;
            State.Thermal = ts;
            State.SystemLoad = load;
        });
    }

    private static double P95(List<double> xs)
    {
        if (xs.Count == 0) return 1;
        var s = xs.OrderBy(x => x).ToList();
        return s[Math.Min(s.Count - 1, (int)(s.Count * 0.95))];
    }

    private Scorecard BuildScorecard(bool cooldownTimedOut, int contentionStamps, int thermalStamps, int above105, int envAcc)
    {
        var sc = new Scorecard();
        List<StampV2> stamps = ui.Invoke(() => State.Stamps.ToList());
        double elapsed = ui.Invoke(() => State.Elapsed), duration = ui.Invoke(() => State.Duration);
        double baseline = P95(calList);
        sc.BaselineIps = baseline;
        bool clocks = stamps.Any(s => s.CpuMhz > 0);
        if (stamps.Count == 0 || baseline <= 0)
        {
            sc.ValidityFlags |= ValidityFlag.ShortRun | (clocks ? 0 : ValidityFlag.FreqMissing);
            sc.Confidence = 20;
            return sc;
        }

        // AUC delivered capacity with real dt
        double work = 0, totalNs = 0;
        foreach (var s in stamps) { work += (double)s.IpsTotal * s.WindowNs; totalNs += s.WindowNs; }
        sc.DeliveredCapacity = Math.Min(150, work / (baseline * totalNs) * 100);

        // sustained ratio: median of the last quarter
        var q = stamps.Skip(stamps.Count - Math.Max(1, stamps.Count / 4)).Select(s => (double)s.IpsTotal).OrderBy(x => x).ToList();
        sc.SustainedRatio = Math.Min(150, q[q.Count / 2] / baseline * 100);

        // throttle onset, debounced: first <90% held ≥3 stamps
        int streak = 0;
        foreach (var s in stamps)
        {
            streak = s.IpsTotal < baseline * 0.9 ? streak + 1 : 0;
            if (streak >= 3) { sc.ThrottleOnsetSec = s.ElapsedMs / 1000 - 2; break; }
        }

        // min sustained 5 s mean over thermal-attributed stamps
        var window = new Queue<double>();
        double? min = null;
        foreach (var s in stamps.Where(s => s.Attribution == (int)Attribution.Thermal))
        {
            window.Enqueue(s.IpsTotal / baseline * 100);
            if (window.Count > 5) window.Dequeue();
            if (window.Count == 5) min = Math.Min(min ?? double.MaxValue, window.Average());
        }
        sc.MinSustained = min;
        sc.TimeInThrottlePct = (double)thermalStamps / stamps.Count * 100;
        sc.WarmStarted = thermalEscalatedAtStart;

        int flags = clocks ? 0 : ValidityFlag.FreqMissing;
        // a warm start lowers the confidence, it doesn't flag the run (as on the phones)
        if ((double)contentionStamps / stamps.Count > 0.10) flags |= ValidityFlag.Interference;
        if (powerEventSeen) flags |= ValidityFlag.PowerEvent;
        if ((envAcc & EnvFlag.LowPowerMode) != 0) flags |= ValidityFlag.EnvCapped;
        if (elapsed < duration * 0.9) flags |= ValidityFlag.ShortRun;
        if (above105 >= 3) flags |= ValidityFlag.BaselineExceeded;
        sc.ValidityFlags = flags;

        int conf = 100;
        if (thermalEscalatedAtStart) conf -= 15;
        if ((flags & ValidityFlag.Interference) != 0) conf -= 20;
        if ((flags & ValidityFlag.ShortRun) != 0) conf -= 25;
        if ((flags & ValidityFlag.EnvCapped) != 0) conf -= 10;
        if (cooldownTimedOut) conf -= 15;
        if ((flags & ValidityFlag.BaselineExceeded) != 0) conf -= 10;
        if ((flags & ValidityFlag.FreqMissing) != 0) conf -= 10;   // no clock telemetry — the phones' honesty tax
        sc.Confidence = Math.Max(0, conf);
        return sc;
    }
}
