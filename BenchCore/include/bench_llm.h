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
