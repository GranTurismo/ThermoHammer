using System.Runtime.InteropServices;

namespace ThermoHammer.Engine;

/// <summary>Windows' counterpart of the phones' system thermal state: 0 nominal … 3 critical (the server's ThermalState).</summary>
public enum ThermalLevel { Nominal = 0, Fair = 1, Serious = 2, Critical = 3 }

public static class ThermalLevels
{
    public static string Name(this ThermalLevel t) => t switch
    {
        ThermalLevel.Fair => "FAIR", ThermalLevel.Serious => "SERIOUS", ThermalLevel.Critical => "CRITICAL", _ => "NOMINAL",
    };
}

/// <summary>One reading of what Windows says about the processor, its cooling and its power.</summary>
public sealed record TelemetrySample
{
    public ThermalLevel Thermal { get; init; }
    /// <summary>Lowest "% Performance Limit" of the cores: below 100 the firmware caps the clock (heat or power).</summary>
    public double? PerfLimitPct { get; init; }
    /// <summary>Clock of each logical processor now, MHz (base × "% Processor Performance"); empty when Windows doesn't say.</summary>
    public double[] CoreMhz { get; init; } = [];
    /// <summary>The processor's base clock, MHz.</summary>
    public double? BaseMhz { get; init; }
    /// <summary>Hottest ACPI thermal zone, °C — what the firmware cools by; null on machines without zones (most desktops).</summary>
    public double? ZoneTempC { get; init; }
    /// <summary>Lowest "% Passive Limit" of the zones: below 100 the firmware is throttling to cool.</summary>
    public double? PassiveLimitPct { get; init; }
    public bool OnAc { get; init; } = true;
    public bool HasBattery { get; init; }
    public int? BatteryPct { get; init; }
    public bool Charging { get; init; }
    public bool BatterySaver { get; init; }
    /// <summary>Windows' power mode: "BEST PERFORMANCE", "BALANCED" or "BEST POWER EFFICIENCY".</summary>
    public string PowerMode { get; init; } = "BALANCED";
    public bool LowPower => BatterySaver || PowerMode == "BEST POWER EFFICIENCY";
    public double CpuMhzAverage => CoreMhz.Length > 0 ? CoreMhz.Average() : 0;
    public bool HasClocks => CoreMhz.Length > 0 && BaseMhz is > 0;
}

/// <summary>
/// The machine's telemetry, sampled twice a second on its own thread (the stress engine and the stage read the latest).
///
/// Windows has no thermal state for apps the way iOS (ProcessInfo.thermalState) and Android (PowerManager) do. What it
/// does publish, as performance counters any user can read: each core's clock relative to its base ("% Processor
/// Performance"), the cap the firmware puts on it ("% Performance Limit" — falls when power or heat limit the chip),
/// and on most laptops the ACPI thermal zones (temperature, and "% Passive Limit": below 100 the firmware is cooling by
/// slowing the processor). The level is derived from those — see <see cref="Level"/>.
/// </summary>
public sealed class Telemetry
{
    public static readonly Telemetry Shared = new();

    private readonly object gate = new();
    private TelemetrySample current = new();
    private IntPtr query;
    private IntPtr perfCounter, limitCounter, freqCounter, zoneTempCounter, zonePassiveCounter;

    public TelemetrySample Current { get { lock (gate) return current; } }

    private Telemetry()
    {
        try
        {
            if (Win32.PdhOpenQueryW(IntPtr.Zero, IntPtr.Zero, out query) == 0)
            {
                Add(@"\Processor Information(*)\% Processor Performance", out perfCounter);
                Add(@"\Processor Information(*)\% Performance Limit", out limitCounter);
                Add(@"\Processor Information(*)\Processor Frequency", out freqCounter);
                Add(@"\Thermal Zone Information(*)\Temperature", out zoneTempCounter);
                Add(@"\Thermal Zone Information(*)\% Passive Limit", out zonePassiveCounter);
                Win32.PdhCollectQueryData(query);
            }
        }
        catch (Exception) { query = IntPtr.Zero; }
        current = Sample();
        var t = new Thread(Loop) { IsBackground = true, Name = "telemetry", Priority = ThreadPriority.AboveNormal };
        t.Start();
    }

    private void Add(string path, out IntPtr counter)
    {
        if (Win32.PdhAddEnglishCounterW(query, path, IntPtr.Zero, out counter) != 0) counter = IntPtr.Zero;
    }

    private void Loop()
    {
        while (true)
        {
            Thread.Sleep(500);
            var s = Sample();
            lock (gate) current = s;
        }
    }

    /// <summary>Counter values by instance name ("0,3" = group 0, logical processor 3; "_Total", "0,_Total"; zone names).</summary>
    private static unsafe Dictionary<string, double> Read(IntPtr counter)
    {
        var values = new Dictionary<string, double>();
        if (counter == IntPtr.Zero) return values;
        uint bytes = 0;
        uint status = Win32.PdhGetFormattedCounterArrayW(counter, Win32.PdhFmtDouble, ref bytes, out uint count, IntPtr.Zero);
        if (status != Win32.PdhMoreData || bytes == 0) return values;
        IntPtr buf = Marshal.AllocHGlobal((int)bytes);
        try
        {
            if (Win32.PdhGetFormattedCounterArrayW(counter, Win32.PdhFmtDouble, ref bytes, out count, buf) != 0) return values;
            // PDH_FMT_COUNTERVALUE_ITEM_W: LPWSTR name; { DWORD CStatus; (pad); double } — 24 bytes on 64-bit
            for (int i = 0; i < count; i++)
            {
                IntPtr item = buf + i * 24;
                string name = Marshal.PtrToStringUni(Marshal.ReadIntPtr(item)) ?? "";
                uint cstatus = (uint)Marshal.ReadInt32(item, 8);
                double v = BitConverter.Int64BitsToDouble(Marshal.ReadInt64(item, 16));
                if (cstatus <= 1) values[name] = v;     // PDH_CSTATUS_VALID_DATA / NEW_DATA
            }
        }
        finally { Marshal.FreeHGlobal(buf); }
        return values;
    }

    private static bool IsCore(string instance) => instance.Contains(',') && !instance.Contains("_Total");

    private TelemetrySample Sample()
    {
        double? limit = null, baseMhz = null, zoneC = null, passive = null;
        double[] coreMhz = [];
        if (query != IntPtr.Zero && Win32.PdhCollectQueryData(query) == 0)
        {
            var perf = Read(perfCounter);
            var lim = Read(limitCounter);
            var freq = Read(freqCounter);
            var cores = perf.Keys.Where(IsCore)
                .OrderBy(k => int.TryParse(k.Split(',')[0], out var g) ? g : 0)
                .ThenBy(k => int.TryParse(k.Split(',')[1], out var n) ? n : 0).ToList();
            if (cores.Count > 0 && freq.Count > 0)
            {
                coreMhz = cores.Select(k => freq.GetValueOrDefault(k, freq.GetValueOrDefault("_Total")) * perf[k] / 100).ToArray();
                baseMhz = freq.GetValueOrDefault("_Total", freq.Values.Max());
            }
            var coreLimits = lim.Where(kv => IsCore(kv.Key)).Select(kv => kv.Value).ToList();
            if (coreLimits.Count > 0) limit = coreLimits.Min();
            else if (lim.TryGetValue("_Total", out var tl)) limit = tl;
            var temps = Read(zoneTempCounter).Values.Where(k => k > 200 && k < 400).ToList();   // Kelvin
            if (temps.Count > 0) zoneC = temps.Max() - 273.15;
            var passives = Read(zonePassiveCounter).Values.Where(v => v > 0 && v <= 100).ToList();
            if (passives.Count > 0) passive = passives.Min();
        }

        Win32.GetSystemPowerStatus(out var ps);
        bool hasBattery = ps.BatteryFlag != 128 && ps.BatteryFlag != 255;
        string mode = "BALANCED";
        try
        {
            if (Win32.PowerGetEffectiveOverlayScheme(out var g) == 0)
            {
                if (g == new Guid("961cc777-2547-4f9d-8174-7d86181b8a7a")) mode = "BEST POWER EFFICIENCY";
                else if (g == new Guid("ded574b5-45a0-4f42-8737-46345c09c238")) mode = "BEST PERFORMANCE";
            }
        }
        catch (Exception) { }

        return new TelemetrySample
        {
            Thermal = Level(limit, passive, zoneC),
            PerfLimitPct = limit,
            CoreMhz = coreMhz,
            BaseMhz = baseMhz,
            ZoneTempC = zoneC,
            PassiveLimitPct = passive,
            OnAc = ps.ACLineStatus != 0,
            HasBattery = hasBattery,
            BatteryPct = hasBattery && ps.BatteryLifePercent <= 100 ? ps.BatteryLifePercent : null,
            Charging = hasBattery && ps.ACLineStatus == 1 && (ps.BatteryFlag & 8) != 0,
            BatterySaver = ps.SystemStatusFlag == 1,
            PowerMode = mode,
        };
    }

    /// <summary>
    /// The four levels, from what the firmware is doing to the processor:
    /// · passive cooling on (a zone's passive limit below 100) — the firmware throttles for heat: SERIOUS, below 50 % CRITICAL;
    /// · the cores' performance cap: below 95 % FAIR, below 75 % SERIOUS, below 50 % CRITICAL;
    /// · a zone at 95 °C or more: at least SERIOUS.
    /// A desktop on a good cooler stays NOMINAL throughout, as it should.
    /// </summary>
    public static ThermalLevel Level(double? perfLimit, double? passiveLimit, double? zoneC)
    {
        var level = ThermalLevel.Nominal;
        if (perfLimit is { } l)
            level = l < 50 ? ThermalLevel.Critical : l < 75 ? ThermalLevel.Serious : l < 95 ? ThermalLevel.Fair : ThermalLevel.Nominal;
        if (passiveLimit is { } p && p < 100)
            level = (ThermalLevel)Math.Max((int)level, (int)(p < 50 ? ThermalLevel.Critical : ThermalLevel.Serious));
        if (zoneC is >= 95)
            level = (ThermalLevel)Math.Max((int)level, (int)ThermalLevel.Serious);
        return level;
    }

    /// <summary>Busy fraction of every core between two GetSystemTimes readings (kernel time includes idle).</summary>
    public static (long idle, long busy) SystemTimes()
    {
        Win32.GetSystemTimes(out var idle, out var kernel, out var user);
        return (idle.Ticks, kernel.Ticks - idle.Ticks + user.Ticks);
    }

    public static double BusyFraction((long idle, long busy) a, (long idle, long busy) b)
    {
        double busy = b.busy - a.busy, total = busy + (b.idle - a.idle);
        return total > 0 ? Math.Clamp(busy / total, 0, 1) : 0;
    }
}
