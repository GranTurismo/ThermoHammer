using System.Runtime.InteropServices;

namespace ThermoHammer.Engine;

/// <summary>BenchCore's C API (BenchCore/include/bench_core.h) in benchcore.dll — the same C++ as the phone apps.</summary>
internal static partial class BenchCore
{
    private const string Dll = "benchcore";

    public const int Matmul = 0, Fft = 1, Mandelbrot = 2, Sieve = 3, Rsa = 4, NBody = 5, Json = 6, Xml = 7,
        Zlib = 8, AesGcm = 9, Sha256 = 10;

    public const int RamRead = 0, RamWrite = 1, RamCopy = 2, RamLatency = 3;
    public const int DiskSeqWrite = 0, DiskSeqRead = 1, DiskRandomRead = 2, DiskRandomWrite = 3;
    public const int QrWidth = 1280, QrHeight = 720, QrFrames = 32, QrSeed = 7;

    [LibraryImport(Dll)] private static partial IntPtr bc_kernel_name(int id);
    [LibraryImport(Dll)] public static partial IntPtr bc_kernel_create(int id);
    [LibraryImport(Dll)] public static partial long bc_kernel_run(IntPtr kernel, out int ok);
    [LibraryImport(Dll)] private static partial IntPtr bc_kernel_error(IntPtr kernel);
    [LibraryImport(Dll)] public static partial void bc_kernel_destroy(IntPtr kernel);
    [LibraryImport(Dll)] private static partial IntPtr bc_crypto_path();

    [LibraryImport(Dll)] private static partial IntPtr bc_mem_error();
    [LibraryImport(Dll)] public static partial IntPtr bc_ram_open(long bytes, int threads);
    [LibraryImport(Dll)] public static partial double bc_ram_run(IntPtr ram, int mode, double seconds);
    [LibraryImport(Dll)] public static partial void bc_ram_close(IntPtr ram);
    [LibraryImport(Dll, StringMarshalling = StringMarshalling.Utf8)] public static partial IntPtr bc_disk_open(string path, long bytes);
    [LibraryImport(Dll)] public static partial int bc_disk_direct(IntPtr disk);
    [LibraryImport(Dll)] public static partial int bc_disk_passes(IntPtr disk);
    [LibraryImport(Dll)] public static partial double bc_disk_run(IntPtr disk, int mode, int threads, double seconds);
    [LibraryImport(Dll)] public static partial void bc_disk_close(IntPtr disk);

    [LibraryImport(Dll)] public static partial int bc_qr_supported();
    [LibraryImport(Dll)] public static partial IntPtr bc_qr_create(int count, int seed);
    [LibraryImport(Dll)] public static partial int bc_qr_count(IntPtr qr);
    [LibraryImport(Dll)] public static partial IntPtr bc_qr_luma(IntPtr qr, int index);
    [LibraryImport(Dll)] private static partial IntPtr bc_qr_text(IntPtr qr, int index);
    [LibraryImport(Dll)] public static unsafe partial void bc_qr_corners(IntPtr qr, int index, float* corners);
    [LibraryImport(Dll)] public static unsafe partial int bc_qr_scan(IntPtr qr, int index, float* found);
    [LibraryImport(Dll)] public static partial void bc_qr_destroy(IntPtr qr);

    public static string KernelName(int id) => Marshal.PtrToStringUTF8(bc_kernel_name(id)) ?? "?";
    public static string KernelError(IntPtr kernel) => Marshal.PtrToStringUTF8(bc_kernel_error(kernel)) ?? "";
    public static string CryptoPath => Marshal.PtrToStringUTF8(bc_crypto_path()) ?? "?";
    public static string MemError => Marshal.PtrToStringUTF8(bc_mem_error()) ?? "";
    public static string QrText(IntPtr qr, int index) => Marshal.PtrToStringUTF8(bc_qr_text(qr, index)) ?? "";
}

/// <summary>The Win32 calls the engine reads the machine with.</summary>
internal static partial class Win32
{
    [StructLayout(LayoutKind.Sequential)]
    public struct FileTime { public uint Low, High; public readonly long Ticks => (long)High << 32 | Low; }

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool GetSystemTimes(out FileTime idle, out FileTime kernel, out FileTime user);

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool GetThreadTimes(IntPtr thread, out FileTime creation, out FileTime exit, out FileTime kernel, out FileTime user);

    [LibraryImport("kernel32.dll")] public static partial IntPtr GetCurrentThread();
    [LibraryImport("kernel32.dll")] public static partial uint GetCurrentThreadId();
    [LibraryImport("kernel32.dll")] public static partial IntPtr OpenThread(uint access, [MarshalAs(UnmanagedType.Bool)] bool inherit, uint id);
    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool CloseHandle(IntPtr handle);
    public const uint ThreadQueryLimitedInformation = 0x0800;

    /// <summary>CPU time (user + kernel) the thread has had, in 100 ns units.</summary>
    public static long ThreadCpuTime(IntPtr thread) =>
        GetThreadTimes(thread, out _, out _, out var k, out var u) ? k.Ticks + u.Ticks : 0;

    [LibraryImport("kernel32.dll")] public static partial uint SetThreadExecutionState(uint flags);
    public const uint EsContinuous = 0x80000000, EsSystemRequired = 0x1, EsDisplayRequired = 0x2;

    [StructLayout(LayoutKind.Sequential)]
    public struct SystemPowerStatus
    {
        public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
        public int BatteryLifeTime, BatteryFullLifeTime;
    }

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool GetSystemPowerStatus(out SystemPowerStatus status);

    [LibraryImport("powrprof.dll")] public static partial uint PowerGetEffectiveOverlayScheme(out Guid scheme);

    [StructLayout(LayoutKind.Sequential)]
    public struct MemoryStatusEx
    {
        public uint Length, MemoryLoad;
        public ulong TotalPhys, AvailPhys, TotalPageFile, AvailPageFile, TotalVirtual, AvailVirtual, AvailExtendedVirtual;
    }

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool GlobalMemoryStatusEx(ref MemoryStatusEx status);

    public static MemoryStatusEx Memory()
    {
        var m = new MemoryStatusEx { Length = (uint)Marshal.SizeOf<MemoryStatusEx>() };
        GlobalMemoryStatusEx(ref m);
        return m;
    }

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool GetLogicalProcessorInformationEx(int relationship, IntPtr buffer, ref uint length);

    [LibraryImport("ntdll.dll")] public static partial int RtlGetVersion(ref OsVersionInfo info);

    [StructLayout(LayoutKind.Sequential)]
    public unsafe struct OsVersionInfo
    {
        public uint Size, Major, Minor, Build, Platform;
        public fixed char CsdVersion[128];
    }

    // PDH — the performance counters: processor performance and limits, thermal zones
    [LibraryImport("pdh.dll")] public static partial uint PdhOpenQueryW(IntPtr source, IntPtr user, out IntPtr query);
    [LibraryImport("pdh.dll", StringMarshalling = StringMarshalling.Utf16)]
    public static partial uint PdhAddEnglishCounterW(IntPtr query, string path, IntPtr user, out IntPtr counter);
    [LibraryImport("pdh.dll")] public static partial uint PdhCollectQueryData(IntPtr query);
    [LibraryImport("pdh.dll")] public static partial uint PdhCloseQuery(IntPtr query);
    [LibraryImport("pdh.dll")]
    public static partial uint PdhGetFormattedCounterArrayW(IntPtr counter, uint format, ref uint bytes, out uint count, IntPtr items);
    public const uint PdhFmtDouble = 0x00000200, PdhMoreData = 0x800007D2;
}
