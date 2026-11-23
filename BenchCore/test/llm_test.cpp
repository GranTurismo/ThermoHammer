// Host test of the LLM test (bench_llm.h): builds the seeded model on the CPU and the GPU, checks it runs and
// stays finite, and prints what the phones measure — prefill and decode tokens/s. Built by test/llm_run.sh.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <chrono>
#include <string>
#include <vector>

#include "bench_llm.h"

static int failures = 0;
static int g_vocab = 128256;   // the model's vocabulary: Llama 3.2's, or Gemma 3's (LLM_MODEL=gemma3)

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
        // Pinning exists on Linux and Android only: here (Mac, iOS) naming cores accepts none, and the pinned search
        // is the unpinned one with no second pass — what the apps that never ask for pinning run, unchanged
        const int cores[4] = {0, 1, 2, 3};
#if defined(__linux__)
        check(bc_llm_set_cores(llm, cores, 4, 0, 0) == 4, "the cores are accepted where pinning exists");
#else
        check(bc_llm_set_cores(llm, cores, 4, 0, 0) == 0, "no core is accepted where pinning doesn't exist");
#endif
        bc_llm_pin_report rep;
        int tp = 0, bp = 0;
        check(bc_llm_tune_threads_pinned(llm, cores, 4, &tp, &bp, &rep, nullptr, nullptr) == 1, "the pinned search runs");
        std::printf("        pinned search: decode %d threads (pinned %d), prefill %d threads (pinned %d), %d trials, %.1f s\n",
                    tp, rep.decode_pinned, bp, rep.prompt_pinned, rep.n_trials, rep.seconds);
        check(rep.n_trials > 0 && tp >= 1 && bp >= 1, "its trials are reported");
#if !defined(__linux__)
        check(rep.pinnable == 0 && !rep.decode_pinned && !rep.prompt_pinned && rep.main_core == -1, "nothing is pinned here");
#endif
        bc_llm_set_cores(llm, nullptr, 0, 0, 0);
        tg = tp;
        pp = bp;
    }
    double s = bc_llm_prefill(llm, BC_LLM_PROMPT);              // warm-up
    check(s > 0, "prefill runs");
    double total = 0;
    int passes = 0;
    while (total < 3 && s > 0) { s = bc_llm_prefill(llm, BC_LLM_PROMPT); total += s; passes++; }
    if (s > 0) std::printf("        prefill %.1f tokens/s (%d × %d)\n", passes * BC_LLM_PROMPT / total, passes, int(BC_LLM_PROMPT));
    // the logits sample of that prompt: what the Android app compares its two CPU libraries' answers by
    const int samples = bc_llm_last_sample(llm, nullptr, 0);
    check(samples > 1000, "a logit sample is kept for the last prompt");
    std::vector<float> sample(size_t(samples > 0 ? samples : 0), 0.0f);
    check(bc_llm_last_sample(llm, sample.data(), samples) == samples, "the sample can be copied out");
    if (!gpu && std::getenv("LLM_DUMP_SAMPLE")) {
        if (FILE* f = std::fopen(std::getenv("LLM_DUMP_SAMPLE"), "w")) {
            for (float v : sample) std::fprintf(f, "%.9g\n", v);
            std::fclose(f);
        }
    }
    if (gpu) {
        std::printf("        agreement with the CPU %.6f\n", bc_llm_agreement(llm));
        check(s > 0 && bc_llm_agreement(llm) >= 0.9, "GPU agrees with the CPU");
    }
    else std::printf("        %s\n", bc_llm_error(llm));

    int first[8] = {}, again[8] = {};
    bc_llm_reset(llm);
    double d = 0;
    int tokens = 0;
    bool ok = true;
    for (int i = 0; i < 64 && ok; i++) {
        int t = 0;
        const double step = bc_llm_next(llm, &t);
        ok = step > 0 && t >= 0 && t < g_vocab;
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

/** The few-millisecond kernel self-test: finite, the same on every call and with any thread count, and quick. */
static void selftest() {
    std::printf("── kernel self-test\n");
    std::vector<float> a(BC_LLM_SELFTEST_LOGITS), b(BC_LLM_SELFTEST_LOGITS), c(BC_LLM_SELFTEST_LOGITS);
    char err[256] = "";
    auto timed = [&](int threads, std::vector<float>& out) {
        const auto t0 = std::chrono::steady_clock::now();
        const int n = bc_llm_kernel_selftest(threads, out.data(), int(out.size()), err, sizeof err);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        return std::make_pair(n, ms);
    };
    const auto first = timed(4, a);
    check(first.first == BC_LLM_SELFTEST_LOGITS, "the self-test runs");
    if (first.first != BC_LLM_SELFTEST_LOGITS) { std::printf("        %s\n", err); return; }
    const auto again = timed(4, b);
    const auto single = timed(1, c);
    std::printf("        %.0f ms the first time, %.0f ms the second, %.0f ms on one thread\n", first.second, again.second, single.second);
    check(again.first == BC_LLM_SELFTEST_LOGITS && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0, "the same answer every time");
    // the logits are not a constant, and one thread agrees with four to rounding
    double lo = a[0], hi = a[0], worst = 0;
    for (float v : a) { lo = std::min<double>(lo, v); hi = std::max<double>(hi, v); }
    for (size_t i = 0; i < a.size(); i++) worst = std::max<double>(worst, std::fabs(double(a[i]) - double(c[i])));
    check(hi > lo, "the logits are not flat");
    check(worst <= 1e-3 * (hi - lo), "one thread and four agree");
    char small[16] = "";
    float few[8];
    check(bc_llm_kernel_selftest(1, few, 8, small, sizeof small) == 0 && *small, "a buffer that is too small fails with a reason");
    if (const char* path = std::getenv("LLM_DUMP_SELFTEST")) {          // for llm_i8mm_check.sh
        if (FILE* f = std::fopen(path, "w")) { for (float v : a) std::fprintf(f, "%.9g\n", v); std::fclose(f); }
    }
}

int main(int argc, char** argv) {
    // LLM_MODEL=gemma3: version 5's candidate shape (Gemma 3 1B) instead of the scored test's
    if (const char* m = std::getenv("LLM_MODEL"); m && std::strcmp(m, "gemma3") == 0) {
        bc_llm_set_model(BC_LLM_MODEL_GEMMA3_1B);
        g_vocab = 262144;
    }
    std::printf("model: %s\n", bc_llm_model_name());
    std::printf("weights %.2f GB · largest model in 8 GB: %dB, in 3 GB: %dB, in 0.5 GB: %dB\n",
                bc_llm_weight_bytes() / 1e9, bc_llm_largest_model(8e9), bc_llm_largest_model(3e9), bc_llm_largest_model(0.5e9));
    check(bc_llm_largest_model(8e9) == 8 && bc_llm_largest_model(3e9) == 3 && bc_llm_largest_model(0.5e9) == 0, "largest model");
    const bool cpu = argc < 2 || std::strcmp(argv[1], "gpu") != 0;
    const bool gpu = argc < 2 || std::strcmp(argv[1], "cpu") != 0;
    // naming a backend that isn't there leaves no GPU; naming none brings the usual choice back
    const std::string usual = bc_llm_gpu_name();
    bc_llm_set_gpu_api("NoSuchBackend");
    check(*bc_llm_gpu_name() == 0, "a GPU backend that doesn't exist leaves no GPU");
    bc_llm_set_gpu_api("");
    check(usual == bc_llm_gpu_name(), "naming none restores the usual GPU");
    check(bc_llm_backend_log() != nullptr, "the backends' own device lines can be read");
    if (cpu) { selftest(); run(0); }
    if (gpu && *bc_llm_gpu_name()) run(1);
    std::printf(failures ? "%d FAILED\n" : "all passed\n", failures);
    return failures ? 1 : 0;
}
