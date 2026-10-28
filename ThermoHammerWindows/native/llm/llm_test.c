// Smoke test for benchllm.dll: loads it the way the app does (LoadLibrary, no import library), builds the model on the
// CPU (or the GPU), runs a prefill and a few tokens and prints the timings. Cross-compiled by llvm-mingw, runs on
// Windows or under Wine:
//   x86_64-w64-mingw32-clang -O2 llm_test.c -o llm_test.exe
//   llm_test.exe [gpu=0] [threads=4] [prefill_tokens=64] [decode_tokens=8]
// gpu: 0 the CPU, 1 the GPU, 2 the CPU's prefill first and then the GPU's (which is checked against it).
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>

typedef struct bc_llm bc_llm;
enum { BC_LLM_PROMPT = 512, BC_LLM_CTX = 1024, BC_LLM_SEED = 3 };

#define FN(ret, name, args) static ret(*name) args
FN(int64_t, weight_bytes, (void));
FN(const char*, gpu_name, (void));
FN(const char*, gpu_api, (void));
FN(bc_llm*, create, (int, int, uint64_t, char*, int));
FN(double, prefill, (bc_llm*, int));
FN(void, reset, (bc_llm*));
FN(double, next, (bc_llm*, int*));
FN(const char*, error, (const bc_llm*));
FN(double, agreement, (const bc_llm*));
FN(void, destroy, (bc_llm*));
FN(int, largest_model, (double));

static double now(void) {
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (double)c.QuadPart / (double)f.QuadPart;
}

static size_t working_set_mb(void) {
    // the commit charge of the machine (private bytes would need psapi): enough to watch the 2 GB come and go
    MEMORYSTATUSEX m = {sizeof m};
    GlobalMemoryStatusEx(&m);
    return (size_t)((m.ullTotalPageFile - m.ullAvailPageFile) >> 20);
}

static int run(int gpu, int threads, int prefill_tokens, int decode_tokens) {
    char err[512] = "";
    const double t0 = now();
    bc_llm* llm = create(gpu, threads, BC_LLM_SEED, err, sizeof err);
    printf("create(gpu=%d, threads=%d): %s in %.2f s%s%s\n", gpu, threads, llm ? "ok" : "FAILED", now() - t0,
           llm ? "" : ": ", llm ? "" : err);
    if (!llm) return 3;
    printf("commit after create: %zu MB\n", working_set_mb());

    double s = prefill(llm, prefill_tokens);
    printf("prefill %d tokens: %.3f s (%.1f tok/s) error=\"%s\" agreement=%.6f\n", prefill_tokens, s,
           s > 0 ? prefill_tokens / s : 0.0, error(llm), agreement(llm));
    if (s < 0) { destroy(llm); return 4; }
    printf("commit after prefill: %zu MB\n", working_set_mb());

    reset(llm);
    double total = 0;
    for (int i = 0; i < decode_tokens; i++) {
        int tok = -1;
        double t = next(llm, &tok);
        printf("  token %d: id %d in %.3f s (%.2f tok/s) error=\"%s\"\n", i, tok, t, t > 0 ? 1 / t : 0.0, error(llm));
        if (t < 0) { destroy(llm); return 5; }
        if (i > 0) total += t;
    }
    if (decode_tokens > 1) printf("decode: %.2f tok/s (tokens 2..%d)\n", (decode_tokens - 1) / total, decode_tokens);
    destroy(llm);
    return 0;
}

#define LOAD(var, sym)                                                             \
    do {                                                                           \
        *(void**)&var = (void*)GetProcAddress(dll, sym);                           \
        if (!var) { printf("missing export %s\n", sym); return 2; }                \
    } while (0)

int main(int argc, char** argv) {
    const int gpu = argc > 1 ? atoi(argv[1]) : 0;
    const int threads = argc > 2 ? atoi(argv[2]) : 4;
    const int prefill_tokens = argc > 3 ? atoi(argv[3]) : 64;
    const int decode_tokens = argc > 4 ? atoi(argv[4]) : 8;
    setvbuf(stdout, NULL, _IONBF, 0);

    HMODULE dll = LoadLibraryA("benchllm.dll");
    if (!dll) { printf("LoadLibrary(benchllm.dll) failed: %lu\n", GetLastError()); return 1; }
    LOAD(weight_bytes, "bc_llm_weight_bytes");
    LOAD(gpu_name, "bc_llm_gpu_name");
    LOAD(gpu_api, "bc_llm_gpu_api");
    LOAD(create, "bc_llm_create");
    LOAD(prefill, "bc_llm_prefill");
    LOAD(reset, "bc_llm_reset");
    LOAD(next, "bc_llm_next");
    LOAD(error, "bc_llm_error");
    LOAD(agreement, "bc_llm_agreement");
    LOAD(destroy, "bc_llm_destroy");
    LOAD(largest_model, "bc_llm_largest_model");

    printf("weights: %lld bytes (%.2f GB)\n", (long long)weight_bytes(), (double)weight_bytes() / 1e9);
    printf("largest model in 16 GB: %dB, in 4 GB: %dB\n", largest_model(16e9), largest_model(4e9));
    const double t0 = now();
    printf("gpu: \"%s\" via \"%s\" (%.2f s to find out)\n", gpu_name(), gpu_api(), now() - t0);
    printf("commit before create: %zu MB\n", working_set_mb());

    int rc = 0;
    if (gpu == 2) {                      // the GPU's check against the CPU's output needs the CPU to have run the same prompt
        printf("--- CPU reference run\n");
        rc = run(0, threads, prefill_tokens, 0);
        printf("--- GPU run\n");
    }
    if (!rc) rc = run(gpu != 0, threads, prefill_tokens, decode_tokens);
    printf(rc ? "failed (%d)\n" : "done\n", rc);
    return rc;
}
