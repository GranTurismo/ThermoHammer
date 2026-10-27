using System.Text.Json.Serialization;
using System.ComponentModel;

namespace ThermoHammer.Api.Models;

public class HammerDto
{
    public int Id { get; set; }
    public required HammerType Type { get; set; }
    public required string DeviceManufacturer { get; set; }
    public required string DeviceModel { get; set; }
    public required OsPlatform Os { get; set; }
    public required string OsVersion { get; set; }
    public required double StabilityPercentage { get; set; }
    public required StressThreadingType TestThreadingType { get; set; }

}

public class Hammer
{
    public const int MaxShortText = 64;
    public const int MaxPatchText = 16;

    public int Id { get; set; }
    public required List<HammerStamp> Stamps { get; set; }
    public required HammerType Type { get; set; }
    public required string DeviceManufacturer { get; set; }
    public required string DeviceModel { get; set; }
    public required OsPlatform Os { get; set; }
    public required string OsVersion { get; set; }
    public required StressThreadingType TestThreadingType { get; set; }
    [JsonIgnore]
    public ThermoSession Session { get; set; }
    public int SessionId { get; set; }
    public required string Hash { get; set; }
    public double StabilityPercentage { get; set; }

    // ── Run quality & OS build — all nullable: null means "legacy run, unknown" ──
    // Needed to compare OS versions fairly: dirty runs (charging, hot start, low-power
    // mode…) must be filterable, and Android's OsVersion alone ("16") cannot tell
    // monthly updates apart. Client-reported — see HammerExtensions.ToDao.
    public int? SchemaVersion { get; set; }
    public int? ValidityFlags { get; set; }    // 0 = clean run; null = legacy / unknown
    public int? Confidence { get; set; }       // 0..100
    public int? StressMode { get; set; }       // 0=CPU 1=GPU 2=COMBINED; null = legacy CPU
    public string? SocModel { get; set; }
    public string? OsBuild { get; set; }       // Android Build.ID / iOS kern.osversion
    public string? SecurityPatch { get; set; } // Android Build.VERSION.SECURITY_PATCH
}

public class HammerRequest : HammerRequestBase
{
    public StressThreadingType TestThreadingType { get; set; } = StressThreadingType.Multi;
}

public enum HammerType
{
    FiveMinutes,
    FifteenMinutes,
    ThirtyMinutes
}

public enum StressThreadingType
{
    Single,
    Multi
}

public enum OsPlatform
{
    iOS = 1,
    Android = 2,
    macOS = 3,     // the iPhone app running on an Apple-silicon Mac
    Windows = 4    // the Windows app (ThermoHammerWindows)
}

public class HammerStamp
{
    public int Id { get; set; }
    public int HammerId { get; set; }
    public int ElapsedMs { get; set; } //milliseconds passed since start
    public long Score { get; set; } // IPS - instructions per second
    public ThermalState ThermalState { get; set; }
}

public enum ThermalState
{
    Nominal,
    Fair,
    Serious,
    Critical
}

public static class HammerExtensions
{
    /// <summary>The validity bit for "no clock data": it describes the phone (iOS, stock Android 16), not the run.</summary>
    public const int FreqMissing = 1 << 3;

    /// <summary>
    /// Only verified stress runs are accepted and shown: no validity flag but FreqMissing. Legacy clients that send no
    /// flags (null) can't be told apart and stay accepted.
    /// </summary>
    public static bool IsVerified(int? validityFlags) => validityFlags is null || (validityFlags.Value & ~FreqMissing) == 0;

    public static Hammer ToDao(this HammerRequestBase request)
    {
        double maxScore = request.Stamps != null && request.Stamps.Count > 0 ? request.Stamps.Max(s => s.Score) : 0;
        double stability = 100;

        if (request.Stamps != null && request.Stamps.Count > 0 && maxScore > 0)
        {
            int secondHalfStart = request.Stamps.Count / 2;
            var secondHalfStamps = request.Stamps.Skip(secondHalfStart).ToList();
            double averageSecondHalf = secondHalfStamps.Count > 0 ? secondHalfStamps.Average(s => s.Score) : 0;
            stability = (averageSecondHalf / maxScore) * 100;
        }

        // Schema v2: client-computed delivered capacity (AUC vs calibrated baseline)
        // is a fairer sustained-performance metric — prefer it when present.
        if (request.DeliveredCapacity is > 0 and <= 150)
        {
            stability = request.DeliveredCapacity.Value;
        }

        var threadType = StressThreadingType.Multi;

        if (request is HammerRequest hammerRequest)
            threadType = hammerRequest.TestThreadingType;

        return new Hammer
        {
            Stamps = request.Stamps!,
            Type = request.Type,
            DeviceManufacturer = request.DeviceManufacturer,
            DeviceModel = request.DeviceModel,
            Os = request.Os,
            OsVersion = request.OsVersion,
            SessionId = request.SessionId,
            Hash = request.Hash,
            StabilityPercentage = stability,
            TestThreadingType = threadType,
            SchemaVersion = request.SchemaVersion,
            ValidityFlags = request.ValidityFlags is >= 0 ? request.ValidityFlags : null,
            Confidence = request.Confidence is >= 0 and <= 100 ? request.Confidence : null,
            StressMode = request.StressMode is >= 0 and <= 2 ? request.StressMode : null,
            SocModel = Clip(request.SocModel, Hammer.MaxShortText),
            OsBuild = Clip(request.OsBuild, Hammer.MaxShortText),
            SecurityPatch = Clip(request.SecurityPatch, Hammer.MaxPatchText)
        };
    }

    /// <summary>Trim and cap free text from the client; empty becomes null.</summary>
    private static string? Clip(string? value, int max)
    {
        var v = value?.Trim();
        if (string.IsNullOrEmpty(v)) return null;
        return v.Length <= max ? v : v[..max];
    }

    public static HammerDto ToDto(this Hammer hammer)
    {
        return new HammerDto
        {
            Id = hammer.Id,
            Type = hammer.Type,
            DeviceManufacturer = hammer.DeviceManufacturer,
            DeviceModel = hammer.DeviceModel,
            Os = hammer.Os,
            OsVersion = hammer.OsVersion,
            StabilityPercentage = hammer.StabilityPercentage,
            TestThreadingType = hammer.TestThreadingType
        };
    }
}

public class HammerRequestBase
{
    public required List<HammerStamp> Stamps { get; set; }
    public HammerType Type { get; set; }
    public required string DeviceManufacturer { get; set; }
    public required string DeviceModel { get; set; }
    public OsPlatform Os { get; set; }
    public required string OsVersion { get; set; }
    public int SessionId { get; set; }
    public required string Hash { get; set; }

    // ── Schema v2 — additive optional fields; null for legacy clients ──
    public int? SchemaVersion { get; set; }
    public long? BaselineScore { get; set; }
    public double? DeliveredCapacity { get; set; }
    public double? SustainedRatio { get; set; }
    public int? ThrottleOnsetSec { get; set; }
    public int? Confidence { get; set; }
    public int? ValidityFlags { get; set; }
    public string? SocModel { get; set; }
    public string? ClusterTopology { get; set; }
    public string? Governor { get; set; }
    public string? HashV2 { get; set; }

    // ── OS build identity — optional; lets monthly updates be told apart ──
    public string? OsBuild { get; set; }
    public string? SecurityPatch { get; set; }

    // ── GPU channel (3D benchmark) — null on CPU-only / legacy runs ──
    public int? StressMode { get; set; }              // 0=CPU 1=GPU 2=COMBINED
    public double? GpuBaselineFps { get; set; }
    public double? GpuDeliveredCapacity { get; set; }
    public double? GpuSustainedRatio { get; set; }
    public string? GpuName { get; set; }
}