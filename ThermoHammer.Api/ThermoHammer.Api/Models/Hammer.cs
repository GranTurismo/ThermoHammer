using System.Text.Json.Serialization;

namespace ThermoHammer.Api.Models;

public class HammerDto
{
    public int Id { get; set; }
    public List<HammerStamp> Stamps { get; set; }
    public HammerType Type { get; set; }
    public string DeviceManufacturer { get; set; }
    public string DeviceModel { get; set; }
    public OsPlatform Os { get; set; }
    public string OsVersion { get; set; }
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
    public static HammerDto ToDto(this Hammer hammer)
    {
        return new HammerDto
        {
            Id = hammer.Id,
            Stamps = hammer.Stamps,
            Type = hammer.Type,
            DeviceManufacturer = hammer.DeviceManufacturer,
            DeviceModel = hammer.DeviceModel,
            Os = hammer.Os,
            OsVersion = hammer.OsVersion
        };
    }
}