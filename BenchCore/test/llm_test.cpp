// Host test of the LLM test (bench_llm.h): builds the seeded model on the CPU and the GPU, checks it runs and
// stays finite, and prints what the phones measure — prefill and decode tokens/s. Built by test/llm_run.sh.
#include <cstdio>
#include <cstring>
#include <chrono>

#include "bench_llm.h"

static int failures = 0;

static void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "  ok  " : "  FAIL", what);
    if (!ok) failures++;
}

static void run(int gpu) {
    std::printf("── %s%s\n", gpu ? "GPU · " : "CPU", gpu ? bc_llm_gpu_name() : "");
    char err[512] = "";
    const auto t0 = std::chrono::steady_clock::now();
    bc_llm* llm = bc_llm_create(gpu, 4, BC_LLM_SEED, err, sizeof err);
    const double build = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    check(llm != nullptr, "model built");
    if (!llm) { std::printf("        %s\n", err); return; }
    std::printf("        built in %.2f s\n", build);
    int tg = 0, pp = 0;
    if (!gpu) {
        check(bc_llm_tune_threads(llm, &tg, &pp) == 1, "threads tuned");
        std::printf("        decode %d threads, prefill %d threads\n", tg, pp);
    }
    double s = bc_llm_prefill(llm, BC_LLM_PROMPT);              // warm-up
    check(s > 0, "prefill runs");
    double total = 0;
    int passes = 0;
    while (total < 3 && s > 0) { s = bc_llm_prefill(llm, BC_LLM_PROMPT); total += s; passes++; }
    if (s > 0) std::printf("        prefill %.1f tokens/s (%d × %d)\n", passes * BC_LLM_PROMPT / total, passes, int(BC_LLM_PROMPT));
    else std::printf("        %s\n", bc_llm_error(llm));

    int first[8] = {}, again[8] = {};
    bc_llm_reset(llm);
    double d = 0;
    int tokens = 0;
    bool ok = true;
    for (int i = 0; i < 64 && ok; i++) {
        int t = 0;
        const double step = bc_llm_next(llm, &t);
        ok = step > 0 && t >= 0 && t < 128256;
        if (i < 8) first[i] = t;
        if (i >= 4) { d += step; tokens++; }                    // the first tokens warm up
    }
    check(ok, "decode runs, tokens in the vocabulary");
    if (ok) std::printf("        decode %.1f tokens/s · tokens %d %d %d %d %d %d %d %d\n", tokens / d,
                        first[0], first[1], first[2], first[3], first[4], first[5], first[6], first[7]);
    else std::printf("        %s\n", bc_llm_error(llm));
    bc_llm_destroy(llm);

    // the same seed builds the same model: the same reply to the same first token
    if (bc_llm* twin = bc_llm_create(gpu, tg ? tg : 4, BC_LLM_SEED, err, sizeof err)) {
        bc_llm_reset(twin);
        for (int i = 0; i < 8; i++) bc_llm_next(twin, &again[i]);
        check(std::memcmp(first, again, sizeof first) == 0, "same seed → same tokens");
        bc_llm_destroy(twin);
    }
}

int main(int argc, char** argv) {
    std::printf("weights %.2f GB · largest model in 8 GB: %dB, in 3 GB: %dB, in 0.5 GB: %dB\n",
                bc_llm_weight_bytes() / 1e9, bc_llm_largest_model(8e9), bc_llm_largest_model(3e9), bc_llm_largest_model(0.5e9));
    check(bc_llm_largest_model(8e9) == 8 && bc_llm_largest_model(3e9) == 3 && bc_llm_largest_model(0.5e9) == 0, "largest model");
    const bool cpu = argc < 2 || std::strcmp(argv[1], "gpu") != 0;
    const bool gpu = argc < 2 || std::strcmp(argv[1], "cpu") != 0;
    if (cpu) run(0);
    if (gpu && *bc_llm_gpu_name()) run(1);
    std::printf(failures ? "%d FAILED\n" : "all passed\n", failures);
    return failures ? 1 : 0;
}
