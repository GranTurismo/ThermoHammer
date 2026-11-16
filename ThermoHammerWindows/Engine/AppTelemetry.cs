using System.Collections.Concurrent;
using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace ThermoHammer.Engine;

/// <summary>
/// Production-only GA4 Measurement Protocol analytics and crash reporter for Windows.
/// Never runs during active benchmark/stress loops; buffers events in memory and flushes asynchronously when idle.
/// </summary>
public static class AppTelemetry
{
    private const string PendingCrashFile = "pending_crash.json";
    private const int MaxQueue = 50;

    private sealed record Ga4Event(string Name, Dictionary<string, object> Params);
    private sealed record PendingCrash(string ExcType, string ExcMsg, string TopFrame, string Mode, string Section, string AppVersion, string OsVersion);

    private static readonly ConcurrentQueue<Ga4Event> queue = new();
    private static readonly SemaphoreSlim signal = new(0);
    private static readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(5) };

#if !DEBUG
    private static volatile bool enabled;
    private static volatile bool started;
#endif
    private static volatile string activeMode = "idle";
    private static volatile string activeSection = "none";
    private static string clientId = "";
    /// <summary>GA4 counts users and sessions only for events that carry a session id and an engagement time.</summary>
    private static string sessionId = "";
    private static int crashRecorded;
    private static Dictionary<string, object> userProps = [];

    private static bool IsAutomatedRun() =>
        App.AutoBenchmark || App.AutoStress ||
        App.Flag("--screenshot") || App.Flag("--gpuTest") || App.Flag("--uxTest") ||
        App.Flag("--aiTest") || App.Flag("--llmTest") || App.Flag("--headlessBenchmark");

    private static string Clip(string? value, int maxLen = 100)
    {
        string v = (value ?? "").Trim();
        return v.Length <= maxLen ? v : v[..maxLen];
    }

    private static string AppVersion()
    {
        var v = typeof(AppTelemetry).Assembly.GetName().Version;
        return v == null ? "?" : $"{v.Major}.{v.Minor}.{Math.Max(v.Build, 0)}";
    }

    public static void Init()
    {
#if !DEBUG
        if (started || IsAutomatedRun()) return;
        started = true;
        try { Start(); }
        catch { enabled = false; }     // analytics is never a reason for the app not to start
#endif
    }

#if !DEBUG
    private static void Start()
    {
        enabled = !string.IsNullOrWhiteSpace(Ga4Config.MeasurementId) && !string.IsNullOrWhiteSpace(Ga4Config.ApiSecret);
        sessionId = DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(System.Globalization.CultureInfo.InvariantCulture);

        clientId = Settings.String("analytics.clientId") ?? "";
        if (string.IsNullOrWhiteSpace(clientId))
        {
            clientId = Guid.NewGuid().ToString();
            Settings.Set("analytics.clientId", clientId);
        }

        string ramGb = Math.Round(DeviceInfo.TotalRamGb).ToString(System.Globalization.CultureInfo.InvariantCulture);
        userProps = new Dictionary<string, object>
        {
            ["app_platform"] = new { value = "windows" },
            ["device_model"] = new { value = Clip(DeviceInfo.FullName, 36) },
            ["soc_or_cpu"] = new { value = Clip(DeviceInfo.ShortCpu(DeviceInfo.CpuName), 36) },
            ["ram_gb"] = new { value = ramGb },
            ["os_version"] = new { value = Clip($"Windows {DeviceInfo.OsVersion}", 36) },
            ["app_version"] = new { value = Clip(AppVersion(), 36) },
        };

        if (!enabled) return;

        if (AppData.Load<PendingCrash>(PendingCrashFile) is { } prev)
        {
            Enqueue("app_crash", new()
            {
                ["exc_type"] = Clip(prev.ExcType),
                ["exc_msg"] = Clip(prev.ExcMsg),
                ["top_frame"] = Clip(prev.TopFrame),
                ["active_mode"] = Clip(prev.Mode),
                ["active_section"] = Clip(prev.Section),
                ["app_version"] = Clip(prev.AppVersion),
                ["os_version"] = Clip(prev.OsVersion),
            });
            try { File.Delete(Path.Combine(AppData.Dir, PendingCrashFile)); } catch { }
        }

        Enqueue("app_open", new() { ["arch"] = DeviceInfo.Architecture });
        var t = new Thread(SenderLoop) { IsBackground = true, Name = "ga4-telemetry", Priority = ThreadPriority.BelowNormal };
        t.Start();
    }
#endif

    public static void SetPhaseContext(string mode, string section)
    {
        activeMode = mode;
        activeSection = section;
    }

    public static void ClearPhaseContext() => SetPhaseContext("idle", "none");

    public static void RecordCrash(object? exceptionObject)
    {
#if !DEBUG
        // an unhandled exception on the UI thread reaches both handlers in App: the first one reports it
        if (!started || Interlocked.Exchange(ref crashRecorded, 1) == 1) return;
        var ex = exceptionObject as Exception;
        string excType = ex?.GetType().Name ?? exceptionObject?.GetType().Name ?? "UnhandledError";
        string excMsg = CrashText.Scrub(ex?.Message ?? exceptionObject?.ToString() ?? "", Environment.UserName);
        string stack = CrashText.Scrub(ex?.StackTrace ?? "", Environment.UserName);
        string topFrame = stack
            .Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .FirstOrDefault(l => l.Contains("ThermoHammer", StringComparison.Ordinal))
            ?? stack.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).FirstOrDefault()
            ?? "";

        var crash = new PendingCrash(Clip(excType), Clip(excMsg), Clip(topFrame), activeMode, activeSection, AppVersion(), $"Windows {DeviceInfo.OsVersion}");
        AppData.Save(PendingCrashFile, crash);

        if (!enabled) return;
        try
        {
            var evt = new Ga4Event("app_crash", new()
            {
                ["exc_type"] = crash.ExcType,
                ["exc_msg"] = crash.ExcMsg,
                ["top_frame"] = crash.TopFrame,
                ["active_mode"] = Clip(crash.Mode),
                ["active_section"] = Clip(crash.Section),
                ["app_version"] = Clip(crash.AppVersion),
                ["os_version"] = Clip(crash.OsVersion),
            });
            using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(1500));
            if (SendBatchAsync([evt], cts.Token).GetAwaiter().GetResult())
            {
                try { File.Delete(Path.Combine(AppData.Dir, PendingCrashFile)); } catch { }
            }
        }
        catch { }
#endif
    }

    public static void LogStressStarted(string mode, int durationMin, string threading)
    {
        SetPhaseContext("stress", $"stress_{mode}");
        Enqueue("stress_started", new()
        {
            ["mode"] = Clip(mode),
            ["duration_min"] = durationMin,
            ["threads"] = Clip(threading),
        });
    }

    public static void LogStressFinished(string mode, int durationMin, string status, bool verified, double capacity)
    {
        ClearPhaseContext();
        Enqueue("stress_finished", new()
        {
            ["mode"] = Clip(mode),
            ["duration_min"] = durationMin,
            ["status"] = Clip(status),
            ["verified"] = verified ? 1 : 0,
            ["delivered_capacity"] = Math.Round(capacity, 2),
        });
    }

    public static void LogBenchmarkStarted(bool partial)
    {
        SetPhaseContext("benchmark", "preparing");
        Enqueue("benchmark_started", new() { ["mode"] = partial ? "partial" : "full" });
    }

    public static void LogBenchmarkFinished(string status, int totalScore, bool verified, int durationSec)
    {
        ClearPhaseContext();
        Enqueue("benchmark_finished", new()
        {
            ["status"] = Clip(status),
            ["total_score"] = totalScore,
            ["verified"] = verified ? 1 : 0,
            ["duration_s"] = durationSec,
        });
    }

    public static void LogResultUploaded(string kind, bool success) =>
        Enqueue("result_uploaded", new() { ["kind"] = Clip(kind), ["success"] = success ? 1 : 0 });

    public static void FlushOnExit()
    {
#if !DEBUG
        if (!enabled || queue.IsEmpty) return;
        try
        {
            var batch = Drain(25);
            if (batch.Count == 0) return;
            using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(1200));
            SendBatchAsync(batch, cts.Token).GetAwaiter().GetResult();
        }
        catch { }
#endif
    }

    private static void Enqueue(string name, Dictionary<string, object> parameters)
    {
#if !DEBUG
        if (!enabled) return;
        while (queue.Count >= MaxQueue) queue.TryDequeue(out _);
        queue.Enqueue(new Ga4Event(name, parameters));
        signal.Release();
#endif
    }

    private static List<Ga4Event> Drain(int max)
    {
        var list = new List<Ga4Event>(max);
        while (list.Count < max && queue.TryDequeue(out var item)) list.Add(item);
        return list;
    }

    private static void SenderLoop()
    {
        while (true)
        {
            signal.Wait();
            while (signal.CurrentCount > 0) signal.Wait(0);
            while (StressEngine.Shared.IsRunning || BenchmarkEngine.Shared.IsRunning)
                Thread.Sleep(1000);
            while (!queue.IsEmpty && !StressEngine.Shared.IsRunning && !BenchmarkEngine.Shared.IsRunning)
            {
                var batch = Drain(25);
                if (batch.Count == 0) break;
                try { SendBatchAsync(batch, CancellationToken.None).GetAwaiter().GetResult(); } catch { }
            }
        }
    }

    private static async Task<bool> SendBatchAsync(List<Ga4Event> batch, CancellationToken ct)
    {
        string url = $"https://www.google-analytics.com/mp/collect?measurement_id={Uri.EscapeDataString(Ga4Config.MeasurementId)}&api_secret={Uri.EscapeDataString(Ga4Config.ApiSecret)}";
        var payload = new
        {
            client_id = clientId,
            user_properties = userProps,
            events = batch.Select(e => new
            {
                name = e.Name,
                @params = new Dictionary<string, object>(e.Params) { ["session_id"] = sessionId, ["engagement_time_msec"] = 100 },
            }),
        };
        using var content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json");
        using var resp = await http.PostAsync(url, content, ct).ConfigureAwait(false);
        return resp.IsSuccessStatusCode;
    }
}
