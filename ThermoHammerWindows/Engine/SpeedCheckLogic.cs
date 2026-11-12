using System.Globalization;

namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// FIND MY LOST SPEED — the 30-second check's arithmetic, what it concludes and
// how two checks compare: the phones' rules (SpeedCheck.kt / .swift) with
// Windows' own causes (battery saver, the power mode, running on battery).
// No Windows calls here: the runner is SpeedCheck.cs.
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>
/// One 30-second check: the work done in each 1-second window (kernel calls per second, all cores) and the PC's power state when it
/// ran — battery saver, on mains power, Windows' power mode ("BEST PERFORMANCE", "BALANCED", "BEST POWER EFFICIENCY") — and its
/// thermal level at the end. [Timestamp] is Unix seconds.
/// </summary>
public sealed record SpeedResult(double Timestamp, List<double> Rates, bool PowerSaver, bool OnAc, bool HasBattery, string PowerMode,
    ThermalLevel Thermal)
{
    public double Burst => SpeedMath.Burst(Rates);
    public double Sustained => SpeedMath.Sustained(Rates);
    /// <summary>0..1: how much of the burst speed was lost once it settled.</summary>
    public double Drop => SpeedMath.Drop(Burst, Sustained);
}

/// <summary>What a cause is, so the screen can pick its colour and buttons.</summary>
public enum SpeedCause { PowerSaving, PowerMode, OnBattery, HeldBackWhileCool, Heat, NoLostSpeed }

public sealed record SpeedFinding(SpeedCause Cause, string Title, string Text);

/// <summary>Every cause that applies, strongest first; [Note] is the last benchmark against its model, when known.</summary>
public sealed record SpeedDiagnosis(List<SpeedFinding> Findings, string? Note)
{
    /// <summary>Something here is fixed in Settings › System › Power &amp; battery.</summary>
    public bool PowerSettingsHelp => Findings.Any(f => f.Cause is SpeedCause.PowerSaving or SpeedCause.PowerMode or SpeedCause.OnBattery or SpeedCause.HeldBackWhileCool);
}

public enum SpeedChange { Faster, Slower, NoChange }

/// <summary>The second check against the first: sustained₂ ÷ sustained₁.</summary>
public sealed record SpeedComparison(double Ratio, SpeedChange Change, string Title, string Text);

/// <summary>The last benchmark's PC Health verdict when it was below the model: how far below the median, and the model.</summary>
public sealed record HealthNote(int PercentBelow, string Model);

public static class SpeedMath
{
    public const int Seconds = 30;
    /// <summary>A drop this big (25 %) is lost speed.</summary>
    public const double LostDrop = 0.25;
    public const double FasterRatio = 1.10, SlowerRatio = 0.90;
    /// <summary>A first check is compared with for this long (the user went to Settings and came back).</summary>
    public static readonly TimeSpan Expiry = TimeSpan.FromMinutes(60);

    public const string BatterySaverSteps = "Settings › System › Power & battery › Battery saver — turn it off.";
    public const string PowerModeSteps = "Settings › System › Power & battery › Power mode — choose Best performance.";
    public const string PowerSettingsUri = "ms-settings:powersleep";

    /// <summary>Mean of seconds 1–5.</summary>
    public static double Burst(IReadOnlyList<double> rates) => rates.Count == 0 ? 0 : rates.Take(5).Average();

    /// <summary>Median of seconds 11–30 (of whatever came after the first 10 seconds, or of everything for a check cut short).</summary>
    public static double Sustained(IReadOnlyList<double> rates)
    {
        var tail = rates.Skip(10).Take(20).ToList();
        return StressCurve.Median(tail.Count > 0 ? tail : [.. rates]);
    }

    public static double Drop(double burst, double sustained) => burst > 0 ? 1 - sustained / burst : 0;

    /// <summary>Thermal levels Windows counts as cool: nominal and fair (the phones' "light" and below).</summary>
    public static bool IsCool(ThermalLevel t) => t <= ThermalLevel.Fair;

    public static string ModeName(string powerMode) => powerMode.Trim().ToUpperInvariant();

    public static SpeedDiagnosis Diagnose(SpeedResult r, HealthNote? health = null)
    {
        var o = new List<SpeedFinding>();
        if (r.PowerSaver)
            o.Add(new(SpeedCause.PowerSaving, "POWER SAVING IS ON", "Battery saver caps the CPU. " + BatterySaverSteps));
        if (ModeName(r.PowerMode) != "BEST PERFORMANCE")
            o.Add(new(SpeedCause.PowerMode, $"POWER MODE: {ModeName(r.PowerMode)}", PowerModeSteps));
        if (r.HasBattery && !r.OnAc)
            o.Add(new(SpeedCause.OnBattery, "ON BATTERY", "Many laptops slow the CPU on battery. Plug in and check again."));
        if (r.Drop >= LostDrop && IsCool(r.Thermal))
            o.Add(new(SpeedCause.HeldBackWhileCool, "HELD BACK WHILE COOL",
                $"Your {ShareLinks.Device} slows down after a short burst even though it isn't hot — a power setting is holding it back, not heat. " + PowerModeSteps));
        if (r.Drop >= LostDrop && !IsCool(r.Thermal))
            // the phones say "take it out of its case, unplug it": a PC's equivalent is its vents (and unplugging a laptop slows it down)
            o.Add(new(SpeedCause.Heat, "HEAT", "It slowed down because it got hot. Clear its vents, let it cool, and check again."));
        if (o.Count == 0)
            o.Add(new(SpeedCause.NoLostSpeed, "NO LOST SPEED FOUND",
                $"Your {ShareLinks.Device} kept {Kept(r)}% of its burst speed. Nothing is holding it back."));
        string? note = health is { PercentBelow: > 0 } h ? $"Your last benchmark was {h.PercentBelow}% below the median {h.Model}." : null;
        return new SpeedDiagnosis(o, note);
    }

    /// <summary>Percent of the burst speed kept, 100 at most.</summary>
    public static int Kept(SpeedResult r) => (int)Math.Round(Math.Min(100, (1 - r.Drop) * 100), MidpointRounding.AwayFromZero);

    public static SpeedComparison Compare(SpeedResult first, SpeedResult second)
    {
        double ratio = first.Sustained > 0 ? second.Sustained / first.Sustained : 1;
        if (ratio >= FasterRatio)
            return new(ratio, SpeedChange.Faster, ratio.ToString("0.00", CultureInfo.InvariantCulture) + "× FASTER",
                $"You got back {(int)Math.Round((ratio - 1) * 100, MidpointRounding.AwayFromZero)}% speed.");
        if (ratio <= SlowerRatio)
            return new(ratio, SpeedChange.Slower, "SLOWER", "It's slower now — change the setting back.");
        return new(ratio, SpeedChange.NoChange, "NO CHANGE", $"That setting wasn't holding your {ShareLinks.Device} back.");
    }

    /// <summary>A saved first check still to compare with ([now] in Unix seconds).</summary>
    public static bool IsFresh(SpeedResult r, double now) => now - r.Timestamp < Expiry.TotalSeconds && now >= r.Timestamp - 60;

    /// <summary>
    /// The stress run lost speed while it was cool — a power setting rather than heat: it kept less than 80 % and at the throttle onset
    /// the thermal level was nominal or fair (the stamp at the onset second, or the first after it).
    /// </summary>
    public static bool SlowedWhileCool(double capacity, int? onsetSec, IReadOnlyList<(int ElapsedMs, int Thermal)> stamps)
    {
        if (capacity >= 80 || onsetSec is not { } onset || stamps.Count == 0) return false;
        int i = 0;
        while (i < stamps.Count - 1 && stamps[i].ElapsedMs / 1000 < onset) i++;
        return IsCool((ThermalLevel)Math.Clamp(stamps[i].Thermal, 0, 3));
    }
}
