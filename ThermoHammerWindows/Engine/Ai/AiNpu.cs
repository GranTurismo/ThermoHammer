using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using Microsoft.ML.OnnxRuntime;
using Microsoft.Windows.AI.MachineLearning;

namespace ThermoHammer.Engine;

/// <summary>
/// The NPU row of the AI test. A PC's NPU isn't reachable through DirectML: each vendor has its own ONNX Runtime execution
/// provider (Qualcomm QNN, Intel OpenVINO, AMD Vitis AI), and Windows ML (Windows 11 24H2 or newer) downloads those on demand
/// through its ExecutionProviderCatalog and registers them with the ONNX Runtime the app ships. This does only that:
///   · a PC without an NPU never reaches Windows ML at all (a quick look at the PC's devices decides);
///   · only the NPU providers are fetched, never a GPU one (a gaming PC would otherwise download NVIDIA's TensorRT);
///   · the first run may download the provider: the AI section says so while it waits, and gives up after 15 minutes;
///   · whatever goes wrong comes back as a reason ("NOT RUN · …") and the NPU row scores 0, as it did before this existed.
/// Not verified on any NPU hardware yet (the developer's test machines have none).
/// </summary>
internal static class AiNpu
{
    /// <summary>The providers that drive an NPU, by the names Windows ML lists them under.</summary>
    private static readonly string[] NpuProviders = ["QNNExecutionProvider", "OpenVINOExecutionProvider", "VitisAIExecutionProvider"];
    private const int FirstBuildWithCatalog = 26100;     // Windows 11 24H2
    private static readonly TimeSpan DownloadLimit = TimeSpan.FromMinutes(15);

    private static readonly object gate = new();
    private static bool prepared;
    private static string? problem;
    private static string runtime = "";
    private static List<OrtEpDevice> devices = [];
    private static bool? hardware;

    /// <summary>The cheap answer, safe on the UI thread: why the NPU can't run here, or null when it might (not asked of Windows ML yet).</summary>
    public static string? Problem
    {
        get
        {
            lock (gate) if (prepared) return problem;
            return PreCheck();
        }
    }

    /// <summary>The provider that runs the NPU row ("QNN", "OPENVINO", "VITIS AI"); empty until <see cref="Prepare"/> found one.</summary>
    public static string Runtime { get { lock (gate) return runtime; } }

    /// <summary>The NPU devices the session is created on.</summary>
    public static IReadOnlyList<OrtEpDevice> Devices { get { lock (gate) return devices; } }

    private static string? PreCheck()
    {
        if (Environment.OSVersion.Version.Build < FirstBuildWithCatalog)
            return "Windows ML's NPU runtimes need Windows 11 version 24H2 or newer";
        return HasNpu() ? null : "this PC has no NPU";
    }

    /// <summary>
    /// Makes the NPU ready (once): downloads and registers its provider if Windows offers one, then finds the NPU device. Returns
    /// null when the NPU can run, else why not. Blocks, so call it from the benchmark's own thread. `status` shows what is going on.
    /// </summary>
    public static string? Prepare(Action<string> status)
    {
        lock (gate) if (prepared) return problem;
        string? why = PreCheck();
        string name = "";
        List<OrtEpDevice> found = [];
        if (why == null)
        {
            try
            {
                status("NPU · PREPARING THE RUNTIME · THE FIRST RUN MAY DOWNLOAD IT");
                var work = Task.Run(RegisterProviders);
                if (!work.Wait(DownloadLimit)) why = "downloading the NPU runtime took more than 15 minutes";
                else
                {
                    var npu = OrtEnv.Instance().GetEpDevices().Where(d => d.HardwareDevice.Type == OrtHardwareDeviceType.NPU).ToList();
                    if (npu.Count == 0) why = "Windows has no NPU runtime for this PC's NPU yet";
                    else
                    {
                        // one provider drives the row: the first one that lists an NPU
                        string ep = npu[0].EpName;
                        found = npu.Where(d => d.EpName == ep).ToList();
                        name = ep switch
                        {
                            "QNNExecutionProvider" => "QNN",
                            "OpenVINOExecutionProvider" => "OPENVINO",
                            "VitisAIExecutionProvider" => "VITIS AI",
                            _ => ep.Replace("ExecutionProvider", "").ToUpperInvariant(),
                        };
                    }
                }
            }
            catch (Exception e)
            {
                why = "the NPU runtime couldn't be set up (" + Short(e) + ")";
            }
        }
        lock (gate)
        {
            prepared = true;
            problem = why;
            runtime = why == null ? name : "";
            devices = found;
            return problem;
        }
    }

    private static async Task RegisterProviders()
    {
        var catalog = ExecutionProviderCatalog.GetDefault();
        foreach (var provider in catalog.FindAllProviders().Where(p => NpuProviders.Contains(p.Name)))
        {
            var result = await provider.EnsureReadyAsync();
            if (result.Status == ExecutionProviderReadyResultState.Success) provider.TryRegister();
        }
    }

    private static string Short(Exception e)
    {
        string m = e.GetBaseException().Message.Trim();
        int nl = m.IndexOf('\n');
        return nl > 0 ? m[..nl] : m;
    }

    // ── does this PC have an NPU? ──
    // NPUs are devices of the "ComputeAccelerator" setup class (ONNX Runtime's own NPU discovery asks for that class); a driver
    // that files its NPU under "System" is recognised by name. A failure to look means "can't tell": Windows ML then decides.

    private static readonly Guid ComputeAccelerator = new("F01A9D53-3FF6-48D2-9F97-C8A7004BE10C");
    private static readonly Guid SystemClass = new("4D36E97D-E325-11CE-BFC1-08002BE10318");
    private static readonly Regex NpuName = new(@"\b(NPU|IPU)\b|AI Boost|Hexagon|Neural|Ryzen AI", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static bool HasNpu()
    {
        lock (gate)
        {
            if (hardware is { } known) return known;
            try { hardware = DeviceNames(ComputeAccelerator).Any() || DeviceNames(SystemClass).Any(n => NpuName.IsMatch(n)); }
            catch (Exception) { hardware = true; }
            return hardware.Value;
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DevInfoData { public uint Size; public Guid ClassGuid; public uint DevInst; public IntPtr Reserved; }

    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern IntPtr SetupDiGetClassDevsW(ref Guid classGuid, string? enumerator, IntPtr hwnd, uint flags);
    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern bool SetupDiEnumDeviceInfo(IntPtr set, uint index, ref DevInfoData data);
    [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool SetupDiGetDeviceRegistryPropertyW(IntPtr set, ref DevInfoData data, uint property, out uint type, byte[] buffer, uint size, out uint required);
    [DllImport("setupapi.dll")]
    private static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);

    /// <summary>The descriptions of the devices of one setup class that are present.</summary>
    private static IEnumerable<string> DeviceNames(Guid cls)
    {
        const uint present = 0x2, deviceDesc = 0x0, friendlyName = 0xC;
        var list = new List<string>();
        IntPtr set = SetupDiGetClassDevsW(ref cls, null, IntPtr.Zero, present);
        if (set == IntPtr.Zero || set == new IntPtr(-1)) return list;
        try
        {
            var data = new DevInfoData { Size = (uint)Marshal.SizeOf<DevInfoData>() };
            for (uint i = 0; SetupDiEnumDeviceInfo(set, i, ref data); i++)
            {
                string text = "";
                foreach (uint prop in new[] { friendlyName, deviceDesc })
                {
                    var buffer = new byte[512];
                    if (SetupDiGetDeviceRegistryPropertyW(set, ref data, prop, out _, buffer, (uint)buffer.Length, out _))
                    {
                        text = System.Text.Encoding.Unicode.GetString(buffer).TrimEnd('\0');
                        if (text.Length > 0) break;
                    }
                }
                list.Add(text.Length > 0 ? text : "device");
            }
        }
        finally { SetupDiDestroyDeviceInfoList(set); }
        return list;
    }
}
