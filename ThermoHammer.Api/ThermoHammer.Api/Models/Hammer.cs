namespace ThermoHammer.Api.Models;

public class Hammer
{
    public int Id { get; set; }
    public HammerStamp[] Stamps { get; set; }
    public string DeviceManufacturer { get; set; }
    public string DeviceModel { get; set; }
    public OsPlatform Os { get; set; }
    public string OsVersion { get; set; }
}

public enum OsPlatform
{
    Android, iOS
}

public class HammerStamp
{
    public int Id { get; set; }
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