using System.Text.Json.Serialization;

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
}

public class Hammer
{
    public int Id { get; set; }
    public required List<HammerStamp> Stamps { get; set; }
    public required HammerType Type { get; set; }
    public required string DeviceManufacturer { get; set; }
    public required string DeviceModel { get; set; }
    public required OsPlatform Os { get; set; }
    public required string OsVersion { get; set; }
    [JsonIgnore]
    public ThermoSession Session { get; set; }
    public int SessionId { get; set; }
    public required string Hash { get; set; }
    public double StabilityPercentage { get; set; }
}

public class HammerRequest
{
    public required List<HammerStamp> Stamps { get; set; }
    public HammerType Type { get; set; }
    public required string DeviceManufacturer { get; set; }
    public required string DeviceModel { get; set; }
    public OsPlatform Os { get; set; }
    public required string OsVersion { get; set; }
    public int SessionId { get; set; }
    public required string Hash { get; set; }
}

public enum HammerType
{
    FiveMinutes,
    FifteenMinutes,
    ThirtyMinutes
}

public enum OsPlatform
{
    iOS = 1,
    Android = 2
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
    public static Hammer ToDao(this HammerRequest request)
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