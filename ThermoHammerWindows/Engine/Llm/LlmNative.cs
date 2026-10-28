using System.Runtime.InteropServices;

namespace ThermoHammer.Engine.Llm;

/// <summary>BenchCore's LLM API (BenchCore/include/bench_llm.h) in benchllm.dll — llama.cpp, the same C++ as the phone apps.</summary>
internal static unsafe partial class LlmNative
{
    private const string Dll = "benchllm";
    public const int Prompt = 512, Seed = 3;

    [LibraryImport(Dll)] public static partial long bc_llm_weight_bytes();
    [LibraryImport(Dll)] private static partial IntPtr bc_llm_gpu_name();
    [LibraryImport(Dll)] private static partial IntPtr bc_llm_gpu_api();
    [LibraryImport(Dll)] public static partial IntPtr bc_llm_create(int gpu, int threads, ulong seed, byte* err, int errSize);
    [LibraryImport(Dll)] public static partial void bc_llm_set_threads(IntPtr llm, int threads, int batchThreads);
    [LibraryImport(Dll)] public static partial int bc_llm_tune_threads(IntPtr llm, int* threads, int* batchThreads);
    [LibraryImport(Dll)] public static partial double bc_llm_prefill(IntPtr llm, int tokens);
    [LibraryImport(Dll)] public static partial void bc_llm_reset(IntPtr llm);
    [LibraryImport(Dll)] public static partial double bc_llm_next(IntPtr llm, int* token);
    [LibraryImport(Dll)] public static partial void bc_llm_cancel(IntPtr llm);
    [LibraryImport(Dll)] private static partial IntPtr bc_llm_error(IntPtr llm);
    [LibraryImport(Dll)] public static partial double bc_llm_agreement(IntPtr llm);
    [LibraryImport(Dll)] public static partial void bc_llm_destroy(IntPtr llm);
    [LibraryImport(Dll)] public static partial int bc_llm_largest_model(double bytes);

    public static string GpuName => Marshal.PtrToStringUTF8(bc_llm_gpu_name()) ?? "";
    public static string GpuApi => Marshal.PtrToStringUTF8(bc_llm_gpu_api()) ?? "";
    public static string Error(IntPtr llm) => Marshal.PtrToStringUTF8(bc_llm_error(llm)) ?? "";
}
