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
}