using System.Runtime.InteropServices;
using Vortice.DXGI;
using ThermoHammer.Engine.Ux;

namespace ThermoHammer.Engine.Llm;

/// <summary>
/// Which GPU llama.cpp's Vulkan backend runs the model on. Left alone it uses the first GPU the Vulkan loader lists; every other
/// GPU-bound test of the benchmark (GpuBench, the media tests) uses the adapter with the most video memory
/// (MediaDevice.BestAdapter), which on a PC with two GPUs can be another one — the LLM row would then measure the integrated GPU
/// next to a GPU score of the discrete one. So before benchllm.dll starts the backend (it reads the variable once, when it
/// first lists devices) this finds the Vulkan device that is the same adapter — by LUID, Vulkan's
/// VkPhysicalDeviceIDProperties against DXGI's — and sets GGML_VK_VISIBLE_DEVICES to its index. Anything that goes wrong leaves
/// llama.cpp's own choice in place; a variable the user set themselves is never overridden.
/// </summary>
internal static unsafe partial class LlmGpuChoice
{
    /// <summary>What was decided, for the logs: "Vulkan device 1 = NVIDIA GeForce RTX 4060 Laptop GPU" or why nothing was set.</summary>
    public static string Decision { get; private set; } = "not decided yet";

    private sealed record VkGpu(int Index, string Name, uint Vendor, uint Device, uint Type, byte[]? Luid, bool Storage16);

    /// <summary>Once per process, before the first call into benchllm.dll (LlmNative's static constructor).</summary>
    public static void Select()
    {
        try
        {
            if (!string.IsNullOrEmpty(Environment.GetEnvironmentVariable("GGML_VK_VISIBLE_DEVICES")))
            {
                Decision = $"GGML_VK_VISIBLE_DEVICES={Environment.GetEnvironmentVariable("GGML_VK_VISIBLE_DEVICES")} was set outside the app";
                return;
            }
            string name; uint vendor, device; byte[] luid;
            using (var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>())
            using (var adapter = MediaDevice.BestAdapter(factory))
            {
                if (adapter == null) { Decision = "no hardware adapter: llama.cpp's own choice"; return; }
                var d = adapter.Description1;
                name = d.Description.Trim(); vendor = d.VendorId; device = d.DeviceId;
                luid = [.. BitConverter.GetBytes(d.Luid.LowPart), .. BitConverter.GetBytes(d.Luid.HighPart)];
            }
            var gpus = VulkanGpus();
            // real GPUs only; the Direct3D-on-Vulkan compatibility layer lists every adapter a second time ("Microsoft Direct3D12 (…)")
            var real = gpus.Where(g => g.Type is 1 or 2 && g.Storage16 && !g.Name.StartsWith("Microsoft Direct3D12", StringComparison.Ordinal)).ToList();
            var match = real.FirstOrDefault(g => g.Luid != null && g.Luid.AsSpan().SequenceEqual(luid))
                ?? real.FirstOrDefault(g => g.Vendor == vendor && g.Device == device)
                ?? real.FirstOrDefault(g => string.Equals(g.Name, name, StringComparison.OrdinalIgnoreCase));
            if (match == null)
            {
                Decision = gpus.Count == 0 ? $"no Vulkan devices to choose from ({name} is the benchmark's GPU): llama.cpp's own choice"
                    : $"{name} (the benchmark's GPU) has no Vulkan device llama.cpp can use: llama.cpp's own choice";
                return;
            }
            if (real.Count == 1) { Decision = $"{match.Name} is the only Vulkan GPU: llama.cpp's own choice"; return; }   // nothing to steer
            string index = match.Index.ToString(System.Globalization.CultureInfo.InvariantCulture);
            SetEnv("GGML_VK_VISIBLE_DEVICES", index);
            Decision = $"Vulkan device {index} = {match.Name} (of {real.Count} GPUs)";
        }
        catch (Exception e) { Decision = $"couldn't choose ({e.GetType().Name}: {e.Message}): llama.cpp's own choice"; }
    }

    /// <summary>
    /// benchllm.dll reads the variable with the C runtime's getenv, which keeps its own copy of the environment: Win32's
    /// SetEnvironmentVariable alone wouldn't reach it. The universal CRT (the DLL is linked to it) is the one that gets the
    /// call; it updates the process environment too.
    /// </summary>
    private static void SetEnv(string name, string value)
    {
        Environment.SetEnvironmentVariable(name, value);
        try { _putenv_s(name, value); } catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException) { }
    }

    [LibraryImport("ucrtbase.dll", EntryPoint = "_putenv_s", StringMarshalling = StringMarshalling.Utf8)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static partial int _putenv_s(string name, string value);

    // ── the Vulkan loader, asked once for its physical devices ──

    [StructLayout(LayoutKind.Sequential)]
    private struct AppInfo { public int sType; public IntPtr pNext, appName; public uint appVersion; public IntPtr engineName; public uint engineVersion, apiVersion; }

    [StructLayout(LayoutKind.Sequential)]
    private struct InstanceInfo { public int sType; public IntPtr pNext; public uint flags; public AppInfo* app; public uint layerCount; public IntPtr layers; public uint extCount; public IntPtr exts; }

    [LibraryImport("vulkan-1.dll")] [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static partial int vkCreateInstance(InstanceInfo* info, IntPtr allocator, out IntPtr instance);
    [LibraryImport("vulkan-1.dll")] [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static partial void vkDestroyInstance(IntPtr instance, IntPtr allocator);
    [LibraryImport("vulkan-1.dll")] [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static partial int vkEnumeratePhysicalDevices(IntPtr instance, uint* count, IntPtr* devices);
    [LibraryImport("vulkan-1.dll")] [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static partial void vkGetPhysicalDeviceProperties2(IntPtr device, byte* properties2);
    [LibraryImport("vulkan-1.dll")] [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static partial void vkGetPhysicalDeviceFeatures2(IntPtr device, byte* features2);

    // structure types (vulkan_core.h)
    private const int StructProperties2 = 1000059001, StructFeatures2 = 1000059000, StructIdProperties = 1000071004, StructStorage16 = 1000083000;
    // sizes and offsets, 64-bit layout: VkPhysicalDeviceProperties2 = header(16) + VkPhysicalDeviceProperties(824); the properties
    // start with apiVersion, driverVersion, vendorID, deviceID, deviceType (4 bytes each) and a 256-byte deviceName
    private const int Props2Size = 848, PropsAt = 16, VendorAt = PropsAt + 8, DeviceAt = PropsAt + 12, TypeAt = PropsAt + 16, NameAt = PropsAt + 20;
    // VkPhysicalDeviceIDProperties: deviceUUID[16] driverUUID[16] deviceLUID[8] deviceNodeMask deviceLUIDValid
    private const int IdSize = 64, LuidAt = 48, LuidValidAt = 60;
    // VkPhysicalDeviceFeatures2 = header(16) + 55 VkBool32 (220), and VkPhysicalDevice16BitStorageFeatures: storageBuffer16BitAccess first
    private const int Features2Size = 240, Storage16Size = 32, Storage16At = 16;

    private static List<VkGpu> VulkanGpus()
    {
        var list = new List<VkGpu>();
        try
        {
            var app = new AppInfo { sType = 0, apiVersion = (1u << 22) | (1u << 12) };       // VK_STRUCTURE_TYPE_APPLICATION_INFO, Vulkan 1.1
            var info = new InstanceInfo { sType = 1, app = &app };                            // VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
            if (vkCreateInstance(&info, IntPtr.Zero, out var instance) != 0 || instance == IntPtr.Zero) return list;
            try
            {
                uint count = 0;
                if (vkEnumeratePhysicalDevices(instance, &count, null) < 0 || count == 0) return list;
                var devices = new IntPtr[count];
                fixed (IntPtr* d = devices) if (vkEnumeratePhysicalDevices(instance, &count, d) < 0) return list;
                byte* props = stackalloc byte[Props2Size], id = stackalloc byte[IdSize], feat = stackalloc byte[Features2Size], s16 = stackalloc byte[Storage16Size];
                for (int i = 0; i < count; i++)
                {
                    new Span<byte>(props, Props2Size).Clear(); new Span<byte>(id, IdSize).Clear();
                    new Span<byte>(feat, Features2Size).Clear(); new Span<byte>(s16, Storage16Size).Clear();
                    *(int*)props = StructProperties2; *(byte**)(props + 8) = id;
                    *(int*)id = StructIdProperties;
                    *(int*)feat = StructFeatures2; *(byte**)(feat + 8) = s16;
                    *(int*)s16 = StructStorage16;
                    vkGetPhysicalDeviceProperties2(devices[i], props);
                    vkGetPhysicalDeviceFeatures2(devices[i], feat);
                    var nameBytes = new ReadOnlySpan<byte>(props + NameAt, 256);
                    int end = nameBytes.IndexOf((byte)0);
                    string name = System.Text.Encoding.UTF8.GetString(end < 0 ? nameBytes : nameBytes[..end]).Trim();
                    byte[]? luid = null;
                    if (*(uint*)(id + LuidValidAt) != 0) luid = new ReadOnlySpan<byte>(id + LuidAt, 8).ToArray();
                    list.Add(new VkGpu(i, name, *(uint*)(props + VendorAt), *(uint*)(props + DeviceAt), *(uint*)(props + TypeAt), luid, *(uint*)(s16 + Storage16At) != 0));
                }
            }
            finally { vkDestroyInstance(instance, IntPtr.Zero); }
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException or BadImageFormatException) { }   // no Vulkan driver
        return list;
    }
}
