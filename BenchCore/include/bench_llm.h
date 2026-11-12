// BenchCore LLM test — llama.cpp (third_party/llama.cpp) running a model with Llama 3.2 3B's exact shape, every
// matrix 4-bit (Q4_0), whose weights are generated from a seed when the test starts: nothing is downloaded and the
// app carries no weights. The same C++ on Android (JNI: engine/llm/LlmNative.kt) and iOS (Swift), so every phone
// runs the same model through the same code.
#ifndef BENCH_LLM_H
#define BENCH_LLM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    BC_LLM_PROMPT = 512,   // tokens in the prefill prompt
    BC_LLM_CTX = 1024,     // context the test reserves (prompt or reply)
    BC_LLM_SEED = 3
};

typedef struct bc_llm bc_llm;

/** Bytes the model's weights take. */
int64_t bc_llm_weight_bytes(void);

/** Where the GPU's compiled kernels may be kept between runs (OpenCL); call before the first bc_llm_create. */
void bc_llm_set_cache_dir(const char* dir);

/** The GPU llama.cpp would run on (e.g. "Apple M5", "QUALCOMM Adreno(TM) 830", "Mali-G715"), "" when it has none. */
const char* bc_llm_gpu_name(void);

/** How it runs there: "Metal", "OpenCL" (Adreno) or "Vulkan" (other Android GPUs); "" when there's no GPU. */
const char* bc_llm_gpu_api(void);

/**
 * Builds the model with its weights generated from `seed`: on the CPU (gpu = 0) or with every layer on the GPU.
 * Not timed. Null on failure, with the reason in `err`.
 */
bc_llm* bc_llm_create(int gpu, int threads, uint64_t seed, char* err, int err_size);

/** Threads for decoding and for prefill. */
void bc_llm_set_threads(bc_llm* llm, int threads, int batch_threads);

/**
 * Tries the thread counts worth trying on this CPU with short runs and keeps the fastest for each phase
 * (the core layouts of phones differ too much for one rule). Returns 0 on failure.
 */
int bc_llm_tune_threads(bc_llm* llm, int* threads, int* batch_threads);

/**
 * Called on the tuning thread just before each trial run: `phase` 0 = decode, 1 = prefill; `threads` is the count
 * about to be timed, `trial` counts from 1 within the phase (0: the warm-up run before the first trial), `cores` is
 * the most threads there are. A run takes seconds on a slow CPU, so this is what keeps the screen moving.
 */
typedef void (*bc_llm_tune_progress)(void* user, int phase, int threads, int trial, int cores);

/** bc_llm_tune_threads, reporting each trial to `progress` (may be null). */
int bc_llm_tune_threads_progress(bc_llm* llm, int* threads, int* batch_threads, bc_llm_tune_progress progress, void* user);

/**
 * Pinning the CPU threads to named cores (Linux and Android; elsewhere these do nothing and report no cores). `cores`
 * lists the core numbers the CPU test may use, fastest first; a pool of n threads then puts one thread on each of the
 * first n (ggml's cpumask with strict_cpu). Without a call nothing is ever pinned. The calling thread is pinned too
 * (it works as the pool's first thread) and gets its own mask back on bc_llm_destroy. Returns the cores accepted.
 */
int bc_llm_set_cores(bc_llm* llm, const int* cores, int n, int pin_decode, int pin_prompt);

/** One timed trial of bc_llm_tune_threads_pinned. */
typedef struct {
    int phase;      /* 0 decode, 1 prompt */
    int pinned;     /* 1: the n fastest cores, one thread each; 0: n threads wherever the system puts them */
    int threads;
    int tokens;     /* what was timed: 6 tokens when decoding, a 64-token prompt */
    double seconds;
} bc_llm_trial;

enum { BC_LLM_MAX_TRIALS = 64 };

/** What bc_llm_tune_threads_pinned tried and chose. */
typedef struct {
    int pinnable;                          /* cores of the list that can be used */
    int decode_pinned, prompt_pinned;      /* 1: the pinned result won (by bc_llm's margin) and is used */
    int decode_skipped, prompt_skipped;    /* 1: no pinned pass: the unpinned one had already taken too long */
    int decode_threads, prompt_threads;
    int main_core;                         /* the core the calling thread sits on alone afterwards, -1 if none */
    double seconds;                        /* the whole tuning */
    int n_trials;
    bc_llm_trial trials[BC_LLM_MAX_TRIALS];
} bc_llm_pin_report;

/** bc_llm_tune_progress with whether the trial is pinned. */
typedef void (*bc_llm_tune_progress_pinned)(void* user, int phase, int pinned, int threads, int trial, int cores);

/**
 * bc_llm_tune_threads over `cores`: each phase is searched unpinned and pinned (the n fastest of `cores` for n threads)
 * and the pinned result is used only if it is clearly faster. Leaves the model set up as chosen. Returns 0 on failure.
 */
int bc_llm_tune_threads_pinned(bc_llm* llm, const int* cores, int n_cores, int* threads, int* batch_threads,
                               bc_llm_pin_report* report, bc_llm_tune_progress_pinned progress, void* user);

/** One prompt of `tokens` (≤ BC_LLM_PROMPT) in a single batch, from an empty context: seconds, < 0 on failure. */
double bc_llm_prefill(bc_llm* llm, int tokens);

/** Empties the context: the next bc_llm_next starts a new reply. */
void bc_llm_reset(bc_llm* llm);

/** Generates one token (sampled from the 40 likeliest, fed back as the next input): seconds, < 0 on failure. Its id → *token. */
double bc_llm_next(bc_llm* llm, int* token);

/** Makes a running bc_llm_prefill or bc_llm_next return early with a failure (any thread). */
void bc_llm_cancel(bc_llm* llm);

/** Why the last call failed ("" if it didn't). */
const char* bc_llm_error(const bc_llm* llm);

/**
 * How closely the last prefill's output on the GPU matched the CPU's for the same prompt (correlation of the logits;
 * 1 = the same, and 1 when the CPU hasn't run it). Below 0.9 the prefill fails: the GPU computed wrong numbers.
 */
double bc_llm_agreement(const bc_llm* llm);

/**
 * The logits (every 97th of the vocabulary) the last bc_llm_prefill produced, for comparing two builds of the same
 * model — the Android app's two CPU libraries. Returns how many there are (0 before a prefill) and copies up to `cap`
 * of them to `out` (may be null to just ask).
 */
int bc_llm_last_sample(const bc_llm* llm, float* out, int cap);

/**
 * Records the logits sample a CPU run of this seed gave for a prompt of `tokens`, as if this library had run it: the
 * Android app runs its CPU test in one of two libraries and the GPU, in the other, has to agree with it.
 */
void bc_llm_set_reference(uint64_t seed, int tokens, const float* sample, int n);

/** What the GPU backends said at debug level about the device they chose (Vulkan's and OpenCL's device lines), one per line. */
const char* bc_llm_backend_log(void);

/** Makes the GPU run on the named backend ("Vulkan", "OpenCL") instead of the usual choice; "" restores it. */
void bc_llm_set_gpu_api(const char* api);

void bc_llm_destroy(bc_llm* llm);

/**
 * The largest common open model, in billions of parameters, whose 4-bit build fits in `bytes` of memory with
 * room for a 4K context (1, 3, 4, 8, 14, 24, 32 or 70); 0 if none does.
 */
int bc_llm_largest_model(double bytes);

#ifdef __cplusplus
}
#endif

#endif
