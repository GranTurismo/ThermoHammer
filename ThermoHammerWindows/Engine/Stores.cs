using System.Text.Json;

namespace ThermoHammer.Engine;

/// <summary>Where the app keeps its files: %LOCALAPPDATA%\ThermoHammer.</summary>
public static class AppData
{
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ThermoHammer");

    public static T? Load<T>(string name)
    {
        try { return JsonSerializer.Deserialize<T>(File.ReadAllText(Path.Combine(Dir, name)), Api.Json); }
        catch (Exception) { return default; }
    }

    public static void SaveText(string name, string text)
    {
        try { Directory.CreateDirectory(Dir); File.WriteAllText(Path.Combine(Dir, name), text); } catch (Exception) { }
    }

    /// <summary>Written to a temp file and renamed: a crash can't leave half a file.</summary>
    public static void Save<T>(string name, T value)
    {
        try
        {
            Directory.CreateDirectory(Dir);
            string path = Path.Combine(Dir, name), tmp = path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(value, Api.Json));
            File.Move(tmp, path, true);
        }
        catch (Exception) { }
    }
}

/// <summary>Small preferences (the welcome seen, dismissed cards, crash markers).</summary>
public static class Settings
{
    private static readonly Dictionary<string, JsonElement> values = AppData.Load<Dictionary<string, JsonElement>>("settings.json") ?? [];

    public static bool Bool(string key) => values.TryGetValue(key, out var v) && v.ValueKind == JsonValueKind.True;
    public static List<string> Strings(string key) =>
        values.TryGetValue(key, out var v) && v.ValueKind == JsonValueKind.Array ? v.EnumerateArray().Select(e => e.GetString() ?? "").ToList() : [];

    public static void Set(string key, object? value)
    {
        lock (values)
        {
            if (value == null) values.Remove(key);
            else values[key] = JsonSerializer.SerializeToElement(value, Api.Json);
            AppData.Save("settings.json", values);
        }
    }
}

/// <summary>A finished stress run saved before submission (and for comparisons) — the phones' PendingTestResult.</summary>
public sealed record PendingTestResult
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public double Timestamp { get; init; }
    public int DurationSeconds { get; init; }
    public int TestDurationType { get; init; }
    public int? TestThreadingType { get; init; }
    public double MinStability { get; init; }
    public double FinalStability { get; init; }
    public int WorstThermalState { get; init; }
    public List<DeviceHammerStamp> Stamps { get; init; } = [];
    public string DeviceModel { get; init; } = "";
    public string DeviceManufacturer { get; init; } = "";
    public string OsVersion { get; init; } = "";
    public int SessionId { get; init; }
    public long? BaselineScore { get; init; }
    public double? DeliveredCapacity { get; init; }
    public double? SustainedRatio { get; init; }
    public int? ThrottleOnsetSec { get; init; }
    public int? Confidence { get; init; }
    public int? ValidityFlags { get; init; }
    public string? SocModel { get; init; }
    public string? ClusterTopology { get; init; }
    public string? OsBuild { get; init; }
}

public sealed class PendingResultStore
{
    public static readonly PendingResultStore Shared = new();
    private const string File = "pending_results.json";
    public List<PendingTestResult> Results { get; private set; } = AppData.Load<List<PendingTestResult>>(File) ?? [];
    public event Action? Changed;

    public void Save(PendingTestResult r) { Results = [.. Results, r]; Persist(); }
    public void Delete(Guid id) { Results = Results.Where(r => r.Id != id).ToList(); Persist(); }
    private void Persist() { AppData.Save(File, Results); Changed?.Invoke(); }
}

/// <summary>One finished, scored run, kept on the PC (summary only) — for "did the Windows update change my PC?".</summary>
public sealed record RunRecord(Guid Id, double Timestamp, string DeviceModel, string OsVersion, string? OsBuild, int DurationType,
    int ThreadingType, double Capacity, int? OnsetSec, int ValidityFlags, int Confidence)
{
    public string OsKey => UpdateImpact.OsKey(OsVersion, OsBuild);
}

/// <summary>
/// UPDATE IMPACT — the PC compared with itself across a Windows update; nothing here is uploaded. The phones' rules:
/// same model, same test settings, clean runs only, medians of up to 3 runs a side, and a change must exceed normal
/// run-to-run noise before it's called slower or faster (UpdateImpact.swift / UpdateImpact.kt).
/// </summary>
public static class UpdateImpact
{
    public const double NoiseFloorPts = 3.0;
    public const int MaxRunsPerSide = 3, MaxHistory = 40;

    public enum Verdict { Slower, Faster, NoClearChange }

    public sealed record Side(List<RunRecord> Runs)
    {
        public RunRecord Latest => Runs[0];
        public double Median
        {
            get
            {
                var v = Runs.Select(r => r.Capacity).OrderBy(x => x).ToList();
                int m = v.Count / 2;
                return v.Count % 2 == 1 ? v[m] : (v[m - 1] + v[m]) / 2;
            }
        }
        public double Spread => Runs.Count < 2 ? 0 : Runs.Max(r => r.Capacity) - Runs.Min(r => r.Capacity);
    }

    public abstract record Insight(string Key)
    {
        /// <summary>Prompt and result are dismissed separately.</summary>
        public abstract string DismissKey { get; }
    }

    /// <summary>The PC updated and has no usable test since — offer to repeat the old one.</summary>
    public sealed record Prompt(string Key, RunRecord Last, string NowOsVersion, string? NowOsBuild) : Insight(Key)
    {
        public override string DismissKey => $"prompt:{Key}";
    }

    public sealed record Comparison(string Key, Side Before, Side After, double DeltaPts, double ThresholdPts, Verdict Verdict) : Insight(Key)
    {
        public bool Weak => Before.Runs.Count < 2 || After.Runs.Count < 2;
        public override string DismissKey => $"result:{Key}";
    }

    public static string OsKey(string osVersion, string? osBuild) => $"{osVersion}|{osBuild ?? ""}";

    /// <summary>Usable as evidence when nothing but the "no clock data" flag is set.</summary>
    public static bool IsComparable(RunRecord r) => (r.ValidityFlags & ~ValidityFlag.FreqMissing) == 0;

    private static bool SameSettings(RunRecord a, RunRecord b) => a.DurationType == b.DurationType && a.ThreadingType == b.ThreadingType;

    public static Insight? For(List<RunRecord> history, string deviceModel, string osVersion, string? osBuild)
    {
        string cur = OsKey(osVersion, osBuild);
        var mine = history.Where(r => r.DeviceModel == deviceModel).OrderByDescending(r => r.Timestamp).ToList();
        var baseline = mine.FirstOrDefault(r => r.OsKey != cur && IsComparable(r));
        if (baseline == null) return null;
        string key = $"{baseline.OsKey}->{cur}";
        var after = mine.Where(r => r.OsKey == cur && IsComparable(r) && SameSettings(r, baseline)).Take(MaxRunsPerSide).ToList();
        if (after.Count == 0) return new Prompt(key, baseline, osVersion, osBuild);
        var before = mine.Where(r => r.OsKey == baseline.OsKey && IsComparable(r) && SameSettings(r, baseline)).Take(MaxRunsPerSide).ToList();
        Side b = new(before), a = new(after);
        double delta = a.Median - b.Median, threshold = Math.Max(NoiseFloorPts, Math.Max(b.Spread, a.Spread));
        var verdict = delta <= -threshold ? Verdict.Slower : delta >= threshold ? Verdict.Faster : Verdict.NoClearChange;
        return new Comparison(key, b, a, delta, threshold, verdict);
    }

    public static List<RunRecord> AppendCapped(List<RunRecord> existing, RunRecord run) =>
        existing.Where(r => r.Id != run.Id).Append(run).OrderBy(r => r.Timestamp).TakeLast(MaxHistory).ToList();
}

public sealed class RunHistoryStore
{
    public static readonly RunHistoryStore Shared = new();
    private const string File = "run_history.json", DismissedKey = "updateImpact.dismissed";
    public List<RunRecord> Records { get; private set; } = AppData.Load<List<RunRecord>>(File) ?? [];
    private HashSet<string> dismissed = [.. Settings.Strings(DismissedKey)];

    public void Add(RunRecord r) { Records = UpdateImpact.AppendCapped(Records, r); AppData.Save(File, Records); }

    public UpdateImpact.Insight? Insight(string model, string osVersion, string? osBuild) =>
        UpdateImpact.For(Records, model, osVersion, osBuild) is { } i && !dismissed.Contains(i.DismissKey) ? i : null;

    public void Dismiss(UpdateImpact.Insight i)
    {
        dismissed.Add(i.DismissKey);
        Settings.Set(DismissedKey, dismissed.TakeLast(50).ToList());
    }
}
