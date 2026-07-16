using System.Text.Json.Serialization;

namespace ThermoHammer.Api.Models;

public class HammerDto
{
    public int Id { get; set; }
    public HammerType Type { get; set; }
    public string DeviceManufacturer { get; set; }
    public string DeviceModel { get; set; }
    public OsPlatform Os { get; set; }
    public string OsVersion { get; set; }
    public int StabilityPercentage { get; set; }
}

public class Hammer
{
    public int Id { get; set; }
    public List<HammerStamp> Stamps { get; set; }
    public HammerType Type { get; set; }
    public string DeviceManufacturer { get; set; }
    public string DeviceModel { get; set; }
    public OsPlatform Os { get; set; }
    public string OsVersion { get; set; }
    [JsonIgnore]
    public ThermoSession Session { get; set; }
    public int SessionId { get; set; }
    public string Hash { get; set; }
    public int StabilityPercentage { get; set; }
}

public class HammerRequest
{
    public List<HammerStamp> Stamps { get; set; }
    public HammerType Type { get; set; }
    public string DeviceManufacturer { get; set; }
    public string DeviceModel { get; set; }
    public OsPlatform Os { get; set; }
    public string OsVersion { get; set; }
    public int SessionId { get; set; }
    public string Hash { get; set; }
}

public enum HammerType
{
    FiveMinutes,
    FifteenMinutes,
    ThirtyMinutes
}

public enum OsPlatform
{
    Android, iOS
}

public class HammerStamp
{
    public int Id { get; set; }
    public int HammerId { get; set; }
    public int ElapsedMs { get; set; } //milliseconds passed since start
    public int Score { get; set; } // IPS - instructions per second
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
    public static Hammer ToDao(this HammerRequest request)
    {
        double maxScore = request.Stamps != null && request.Stamps.Count > 0 ? request.Stamps.Max(s => s.Score) : 0;
        double minScore = request.Stamps != null && request.Stamps.Count > 0 ? request.Stamps.Min(s => s.Score) : 0;
        int stability = maxScore > 0 ? (int)Math.Round((minScore / maxScore) * 100) : 100;

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
            StabilityPercentage = stability
        };
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
            StabilityPercentage = hammer.StabilityPercentage
        };
    }
}