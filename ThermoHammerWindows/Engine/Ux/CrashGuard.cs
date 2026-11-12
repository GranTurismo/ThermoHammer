using System.Diagnostics;

namespace ThermoHammer.Engine;

/// <summary>
/// The "running" markers of the tests whose native code can take the whole app down (ONNX Runtime and DirectML, llama.cpp and the
/// Vulkan driver under it, Media Foundation's codecs): a crash can't be caught, so a test leaves a marker while one of its runs is in
/// flight, and a marker found at the next launch means the app went down there. The iOS app's rules:
///   · the marker is cleared when the run ends, and when the process exits normally (the window closed, Environment.Exit) —
///     only a process that died inside the run (a crash, Task Manager, a power cut) leaves one behind;
///   · one unfinished run is a strike, not a verdict: an accelerator (a clip, a job) is skipped after two strikes in the same
///     build, so a single kill by hand doesn't zero it for good;
///   · a run that finishes clears its own earlier strikes (they were something else).
/// Each entry is "build:id[:id…]"; the part named by `groupAt` is what the strikes are counted per.
/// </summary>
internal sealed class CrashGuard
{
    public const int StrikesToSkip = 2;
    /// <summary>What a skipped run's result says (the iOS wording).</summary>
    public const string Reason = "crashed the app twice in this version";

    private static readonly object gate = new();
    private static readonly List<CrashGuard> all = [];
    private static readonly string build = ReadBuild();

    static CrashGuard()
    {
        // closing the window (or any other normal exit) mid-run is not a crash: the markers must not outlive it
        AppDomain.CurrentDomain.ProcessExit += (_, _) => { lock (gate) foreach (var g in all) g.EndLocked(); };
    }

    private readonly string runningKey, crashedKey;
    private bool marked;

    /// <summary>`name`: "ai", "llm", "video", "export" — the settings keys are benchmark.{name}Running and benchmark.{name}Crashed.</summary>
    public CrashGuard(string name)
    {
        runningKey = $"benchmark.{name}Running";
        crashedKey = $"benchmark.{name}Crashed";
        lock (gate) all.Add(this);
    }

    private static string ReadBuild()
    {
        try { return (FileVersionInfo.GetVersionInfo(Environment.ProcessPath ?? "").FileVersion ?? "?").Replace(':', '_'); }
        catch (Exception) { return "?"; }
    }

    /// <summary>
    /// The ids (entry part `groupAt`) that took the app down twice in this build. A marker the last launch left behind
    /// becomes one more strike now — call this once, before the first run of the section.
    /// </summary>
    public HashSet<string> Skipped(int groupAt)
    {
        try { return SkippedCore(groupAt); }
        catch (Exception) { return []; }   // the settings file couldn't be read or written: nothing is skipped
    }

    private HashSet<string> SkippedCore(int groupAt)
    {
        lock (gate)
        {
            var strikes = Settings.Strings(crashedKey);
            var left = Settings.Strings(runningKey);
            if (left.Count > 0)
            {
                strikes.AddRange(left);
                Settings.Set(crashedKey, strikes);
                Settings.Set(runningKey, null);
            }
            var count = new Dictionary<string, int>();
            foreach (var s in strikes)
            {
                var p = s.Split(':');
                if (p.Length > groupAt && p[0] == build) count[p[groupAt]] = count.GetValueOrDefault(p[groupAt]) + 1;
            }
            return count.Where(c => c.Value >= StrikesToSkip).Select(c => c.Key).ToHashSet();
        }
    }

    /// <summary>A run of `entry` ("model:accel", "clip"…) starts: the marker goes up.</summary>
    public void Begin(string entry)
    {
        lock (gate)
        {
            marked = true;
            try { Settings.Set(runningKey, new[] { $"{build}:{entry}" }); } catch (Exception) { }
        }
    }

    /// <summary>The run is over, however it ended (finished, failed with an exception, stopped): the marker comes down.</summary>
    public void End()
    {
        lock (gate) EndLocked();
    }

    private void EndLocked()
    {
        if (!marked) return;
        marked = false;
        try { Settings.Set(runningKey, null); } catch (Exception) { }
    }

    /// <summary>The run of `entry` finished: its earlier unfinished runs were chance, not this accelerator's crash.</summary>
    public void Finished(string entry)
    {
        lock (gate)
        {
            try
            {
                var strikes = Settings.Strings(crashedKey);
                var kept = strikes.Where(s => !s.EndsWith(":" + entry, StringComparison.Ordinal)).ToList();
                if (kept.Count != strikes.Count) Settings.Set(crashedKey, kept.Count == 0 ? null : kept);
            }
            catch (Exception) { }   // bookkeeping only: it must never turn a good measurement into a failed one
        }
    }
}
