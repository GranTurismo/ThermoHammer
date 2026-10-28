using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace ThermoHammer.Engine;

/// <summary>
/// What this PC is, as the leaderboard names it: maker and model from the firmware (a self-built desktop's board
/// says "System Product Name", so it goes by its processor instead, as a Mac does: "PC (Ryzen 7 7800X3D)"), the
/// processor and graphics, Windows' version and build.
/// </summary>
public static class DeviceInfo
{
    /// <summary>OsPlatform on the server: 1 iOS, 2 Android, 3 macOS, 4 Windows.</summary>
    public const int OsPlatform = 4;

    public static string OsLabel(int os, string version) =>
        $"{(os == 2 ? "Android" : os == 3 ? "macOS" : os == 4 ? "Windows" : "iOS")} {version}";

    private static readonly Lazy<(string maker, string model)> identity = new(ReadIdentity);
    public static string Manufacturer => identity.Value.maker;
    public static string Model => identity.Value.model;

    public static string CpuName { get; } = ReadCpuName();
    public static string GpuName { get; } = ReadGpuName();

    /// <summary>"11 24H2" — Windows' major version (11 from build 22000) and its feature update.</summary>
    public static string OsVersion { get; } = ReadOsVersion();

    /// <summary>"26100.6584" — build and update revision: tells monthly updates apart.</summary>
    public static string? OsBuild { get; } = ReadOsBuild();

    public static double TotalRamGb => Win32.Memory().TotalPhys / 1e9;

    /// <summary>"x64" or "ARM64" — what the processor is (the app runs natively on both).</summary>
    public static string Architecture => RuntimeInformation.OSArchitecture == System.Runtime.InteropServices.Architecture.Arm64 ? "ARM64" : "x64";

    private static string Bios(string name) =>
        (Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\BIOS", name, null) as string)?.Trim() ?? "";

    private static readonly string[] placeholders =
    [
        "system product name", "to be filled by o.e.m.", "default string", "not applicable", "not specified",
        "system manufacturer", "o.e.m.", "oem", "none", "unknown", "type1productconfigid", "invalid",
    ];

    private static bool IsPlaceholder(string s) =>
        s.Length < 2 || placeholders.Any(p => s.Equals(p, StringComparison.OrdinalIgnoreCase) || s.Contains("O.E.M", StringComparison.OrdinalIgnoreCase));

    private static (string, string) ReadIdentity()
    {
        string maker = Bios("SystemManufacturer"), product = Bios("SystemProductName"), family = Bios("SystemFamily"),
            version = Bios("SystemVersion");
        maker = maker.ToUpperInvariant() switch
        {
            "LENOVO" => "Lenovo", "HP" or "HEWLETT-PACKARD" => "HP", "DELL INC." => "Dell", "ASUSTEK COMPUTER INC." => "ASUS",
            "MICRO-STAR INTERNATIONAL CO., LTD." => "MSI", "MICROSOFT CORPORATION" => "Microsoft", "ACER" => "Acer",
            "SAMSUNG ELECTRONICS CO., LTD." => "Samsung", "GIGABYTE TECHNOLOGY CO., LTD." => "Gigabyte",
            "RAZER" => "Razer", "VMWARE, INC." => "VMware", "QEMU" => "QEMU", "INNOTEK GMBH" => "VirtualBox",
            _ => maker,
        };
        // Lenovo's product name is a part number ("21D6CTO1WW"); the marketing name is its "version"
        string model = maker == "Lenovo" && !IsPlaceholder(version) && !version.StartsWith("Lenovo Product", StringComparison.OrdinalIgnoreCase)
            ? version : product;
        if (maker == "Lenovo" && model.StartsWith("Lenovo ", StringComparison.Ordinal)) model = model[7..];
        if (IsPlaceholder(model) && !IsPlaceholder(family)) model = family;
        if (IsPlaceholder(maker) || IsPlaceholder(model))
            return ("PC", $"PC ({ShortCpu(CpuName)})");
        return (maker, model);
    }

    /// <summary>"AMD Ryzen 7 7800X3D 8-Core Processor" → "Ryzen 7 7800X3D"; "Snapdragon(R) X Elite - X1E78100 - Qualcomm(R) Oryon(TM) CPU" → "Snapdragon X Elite X1E78100".</summary>
    public static string ShortCpu(string cpu)
    {
        string s = cpu.Replace("(R)", "").Replace("(TM)", "").Replace("(tm)", "").Replace("®", "").Replace("™", "");
        foreach (var cut in new[] { " CPU @", " @ ", " with Radeon", " w/ Radeon" })
        {
            int i = s.IndexOf(cut, StringComparison.OrdinalIgnoreCase);
            if (i > 0) s = s[..i];
        }
        s = System.Text.RegularExpressions.Regex.Replace(s, @"\s+\d+-Core Processor\s*$", "", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        s = s.Replace(" - Qualcomm Oryon CPU", "").Replace(" - ", " ");
        foreach (var lead in new[] { "Intel ", "AMD ", "Qualcomm " })
            if (s.StartsWith(lead, StringComparison.Ordinal)) s = s[lead.Length..];
        s = s.Replace("Processor", "").Replace("CPU", "");
        return System.Text.RegularExpressions.Regex.Replace(s, @"\s+", " ").Trim() is { Length: > 0 } t ? t : cpu.Trim();
    }

    private static string ReadCpuName() =>
        (Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\CentralProcessor\0", "ProcessorNameString", null) as string)?.Trim()
        ?? "Unknown processor";

    private static string ReadGpuName()
    {
        try
        {
            using var factory = Vortice.DXGI.DXGI.CreateDXGIFactory1<Vortice.DXGI.IDXGIFactory1>();
            for (uint i = 0; factory.EnumAdapters1(i, out var adapter).Success; i++)
            {
                using (adapter)
                {
                    var d = adapter.Description1;
                    if ((d.Flags & Vortice.DXGI.AdapterFlags.Software) == 0) return d.Description.Trim();
                }
            }
        }
        catch (Exception) { }
        return "";
    }

    private static string CurrentVersion(string name) =>
        Registry.GetValue(@"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows NT\CurrentVersion", name, null)?.ToString() ?? "";

    private static uint Build()
    {
        var v = new Win32.OsVersionInfo { Size = (uint)Marshal.SizeOf<Win32.OsVersionInfo>() };
        return Win32.RtlGetVersion(ref v) == 0 ? v.Build : (uint)Environment.OSVersion.Version.Build;
    }

    private static string ReadOsVersion()
    {
        uint build = Build();
        string feature = CurrentVersion("DisplayVersion");
        if (feature.Length == 0) feature = CurrentVersion("ReleaseId");
        return $"{(build >= 22000 ? "11" : "10")}{(feature.Length > 0 ? " " + feature : "")}";
    }

    private static string? ReadOsBuild()
    {
        uint build = Build();
        string ubr = CurrentVersion("UBR");
        return build == 0 ? null : ubr.Length > 0 ? $"{build}.{ubr}" : $"{build}";
    }

    /// <summary>
    /// Logical processors grouped by core type (Windows' efficiency class: the higher, the faster), fastest first —
    /// "PERF" and "EFF" on hybrid chips (Intel's P- and E-cores), one "CPU" group otherwise. Totals the logical count.
    /// </summary>
    public static ClusterTopology ReadTopology(int logical)
    {
        try
        {
            uint len = 0;
            Win32.GetLogicalProcessorInformationEx(0 /* RelationProcessorCore */, IntPtr.Zero, ref len);
            if (len == 0) return ClusterTopology.Flat(logical);
            IntPtr buf = Marshal.AllocHGlobal((int)len);
            try
            {
                if (!Win32.GetLogicalProcessorInformationEx(0, buf, ref len)) return ClusterTopology.Flat(logical);
                var byClass = new SortedDictionary<int, int>(Comparer<int>.Create((a, b) => b.CompareTo(a)));
                for (int off = 0; off < len;)
                {
                    IntPtr p = buf + off;
                    int size = Marshal.ReadInt32(p, 4);
                    // PROCESSOR_RELATIONSHIP: Flags (byte 8), EfficiencyClass (byte 9), GroupCount (ushort 30),
                    // GroupMask[] at 32 (KAFFINITY Mask, WORD Group, 3 WORD reserved = 16 bytes each)
                    int efficiency = Marshal.ReadByte(p, 9);
                    int groups = Marshal.ReadInt16(p, 30);
                    int threads = 0;
                    for (int g = 0; g < groups; g++) threads += System.Numerics.BitOperations.PopCount((ulong)Marshal.ReadInt64(p, 32 + 16 * g));
                    byClass[efficiency] = byClass.GetValueOrDefault(efficiency) + threads;
                    off += size;
                }
                if (byClass.Values.Sum() != logical || byClass.Count < 2) return ClusterTopology.Flat(logical);
                var clusters = new List<int[]>();
                var names = new List<string>();
                int at = 0, k = 0;
                foreach (var (_, n) in byClass)
                {
                    clusters.Add(Enumerable.Range(at, n).ToArray());
                    names.Add(k++ == 0 ? "PERF" : "EFF");
                    at += n;
                }
                return new ClusterTopology(clusters, names);
            }
            finally { Marshal.FreeHGlobal(buf); }
        }
        catch (Exception) { return ClusterTopology.Flat(logical); }
    }
}
