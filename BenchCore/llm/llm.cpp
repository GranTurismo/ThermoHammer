// LLM test — llama.cpp running a model with Llama 3.2 3B's exact shape: 28 layers 3072 wide, 24 query and 8
// key/value heads, an 8192-wide SwiGLU and a 128,256-token vocabulary whose embedding doubles as the output head;
// every matrix 4-bit (Q4_0), 1.8 GB. The weights are generated from a seed when the test starts instead of being
// downloaded: the matrix maths on 4-bit blocks does the same work whatever the numbers are, so every op takes the
// real model's time. The words it writes are random; the work is not.
//
//   Prefill: a 512-token prompt in one batch — compute-bound.
//   Decode: one token at a time, each sampled and fed back as the next input — every token reads all the
//   weights, so it is bound by memory bandwidth.
//   The CPU runs ggml's Arm kernels (the weights repacked for the dot-product instructions every phone has), the
//   GPU llama.cpp's Metal (iPhone) or OpenCL (Adreno) backend with every layer on it.
#include "bench_llm.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "gguf.h"
#include "llama.h"

namespace {

// Llama 3.2 3B
constexpr uint32_t VOCAB = 128256, EMBD = 3072, LAYERS = 28, HEADS = 24, KV_HEADS = 8, HEAD_DIM = 128, FF = 8192;
constexpr float ROPE_BASE = 500000.0f;
constexpr double PI = 3.14159265358979323846;
constexpr int Q4_0_BLOCK = 18;          // bytes per 32 weights: an fp16 scale and 16 bytes of nibbles
constexpr int TOP_K = 40;
constexpr double TEMPERATURE = 0.8;

std::mutex g_log_mutex;
std::string g_log_error;                 // llama.cpp's last error line, for the reason a step failed

void on_log(ggml_log_level level, const char* text, void*) {
    if (level != GGML_LOG_LEVEL_ERROR || !text) return;
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log_error = text;
    while (!g_log_error.empty() && (g_log_error.back() == '\n' || g_log_error.back() == ' ')) g_log_error.pop_back();
}

std::string last_log_error() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return g_log_error;
}

void init_once() {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(on_log, nullptr);
        llama_backend_init();
    });
}

ggml_backend_dev_t gpu_device() {
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) return dev;
    }
    return nullptr;
}

uint64_t mix(uint64_t z) {
    z += 0x9e3779b97f4a7c15ULL;
    z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
    z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
    return z ^ (z >> 31);
}

uint64_t name_hash(const char* s) {
    uint64_t h = 1469598103934665603ULL;
    for (; *s; s++) h = (h ^ uint8_t(*s)) * 1099511628211ULL;
    return h;
}

/**
 * Q4_0 blocks from `seed`: each chunk of blocks has its own seed, so the bytes don't depend on how many threads
 * write them. Scales 2^-8 … 2^-7 (weights with a standard deviation of about 0.02–0.04, like a trained model's),
 * so every activation stays finite through the 28 layers.
 */
void random_q4_0(uint8_t* dst, size_t blocks, uint64_t seed) {
    constexpr size_t CHUNK = 1 << 16;
    const size_t chunks = (blocks + CHUNK - 1) / CHUNK;
    auto work = [=](size_t first, size_t step) {
        for (size_t c = first; c < chunks; c += step) {
            uint64_t s = mix(seed ^ (c * 0x100000001b3ULL));
            const size_t end = std::min(blocks, (c + 1) * CHUNK);
            for (size_t b = c * CHUNK; b < end; b++) {
                uint8_t* p = dst + b * Q4_0_BLOCK;
                const uint64_t a = s = mix(s), q0 = s = mix(s), q1 = s = mix(s);
                const uint16_t scale = uint16_t(0x1C00 | (a & 0x3FF));
                std::memcpy(p, &scale, 2);
                std::memcpy(p + 2, &q0, 8);
                std::memcpy(p + 10, &q1, 8);
            }
        }
    };
    const size_t threads = std::min<size_t>(chunks, std::max(1u, std::min(8u, std::thread::hardware_concurrency())));
    if (threads <= 1) { work(0, 1); return; }
    std::vector<std::thread> pool;
    for (size_t t = 1; t < threads; t++) pool.emplace_back(work, t, threads);
    work(0, threads);
    for (auto& t : pool) t.join();
}

/** Llama 3's long-context RoPE factors (factor 32, low/high frequency factors 1 and 4, 8192 original context). */
void rope_factors(float* dst, size_t n) {
    const double factor = 32, low = 1, high = 4, old_ctx = 8192;
    for (size_t i = 0; i < n; i++) {
        const double freq = 1.0 / std::pow(double(ROPE_BASE), double(2 * i) / HEAD_DIM);
        const double wavelen = 2 * PI / freq;
        double f;
        if (wavelen < old_ctx / high) f = 1;
        else if (wavelen > old_ctx / low) f = factor;
        else {
            const double smooth = (old_ctx / wavelen - low) / (high - low);
            f = 1 / ((1 - smooth) / factor + smooth);
        }
        dst[i] = float(f);
    }
}

struct Fill {
    uint64_t seed;
    std::vector<uint8_t> staging;
    std::string error;
};

/** llama.cpp asks for each tensor's data once it is allocated (on the CPU, repacked, or on the GPU). */
void fill_tensor(ggml_tensor* t, void* user) {
    auto* fill = static_cast<Fill*>(user);
    const size_t n = ggml_nbytes(t);
    if (fill->staging.size() < n) fill->staging.resize(n);
    uint8_t* data = fill->staging.data();
    if (t->type == GGML_TYPE_F32) {
        auto* f = reinterpret_cast<float*>(data);
        if (std::strcmp(t->name, "rope_freqs.weight") == 0) rope_factors(f, n / 4);
        else std::fill(f, f + n / 4, 1.0f);                    // RMSNorm gains
    } else if (t->type == GGML_TYPE_Q4_0) {
        random_q4_0(data, n / Q4_0_BLOCK, fill->seed ^ name_hash(t->name));
    } else {
        fill->error = std::string("unexpected tensor type for ") + t->name;
        std::memset(data, 0, n);
    }
    ggml_backend_tensor_set(t, data, 0, n);                    // repacks for the CPU kernels, uploads to the GPU
}

void add_tensor(gguf_context* g, const std::string& name, ggml_type type) {
    ggml_tensor t;
    std::memset(&t, 0, sizeof t);
    t.type = type;
    ggml_set_name(&t, name.c_str());
    gguf_add_tensor(g, &t);
}

/** The model's metadata, as a GGUF of Llama 3.2 3B would carry it — minus the tokenizer (the test feeds token ids). */
gguf_context* metadata() {
    gguf_context* g = gguf_init_empty();
    gguf_set_val_str(g, "general.architecture", "llama");
    gguf_set_val_str(g, "general.name", "Llama 3.2 3B shape, seeded weights");
    gguf_set_val_u32(g, "llama.vocab_size", VOCAB);
    gguf_set_val_u32(g, "llama.context_length", 131072);
    gguf_set_val_u32(g, "llama.embedding_length", EMBD);
    gguf_set_val_u32(g, "llama.block_count", LAYERS);
    gguf_set_val_u32(g, "llama.feed_forward_length", FF);
    gguf_set_val_u32(g, "llama.attention.head_count", HEADS);
    gguf_set_val_u32(g, "llama.attention.head_count_kv", KV_HEADS);
    gguf_set_val_u32(g, "llama.rope.dimension_count", HEAD_DIM);
    gguf_set_val_f32(g, "llama.rope.freq_base", ROPE_BASE);
    gguf_set_val_f32(g, "llama.attention.layer_norm_rms_epsilon", 1e-5f);
    gguf_set_val_str(g, "tokenizer.ggml.model", "no_vocab");
    // only the tensors listed here exist (third_party/llama.cpp/README.md): no biases, the output head tied
    add_tensor(g, "token_embd.weight", GGML_TYPE_Q4_0);
    add_tensor(g, "output_norm.weight", GGML_TYPE_F32);
    add_tensor(g, "rope_freqs.weight", GGML_TYPE_F32);
    for (uint32_t i = 0; i < LAYERS; i++) {
        const std::string b = "blk." + std::to_string(i) + ".";
        add_tensor(g, b + "attn_norm.weight", GGML_TYPE_F32);
        add_tensor(g, b + "ffn_norm.weight", GGML_TYPE_F32);
        for (const char* m : {"attn_q", "attn_k", "attn_v", "attn_output", "ffn_gate", "ffn_up", "ffn_down"})
            add_tensor(g, b + m + ".weight", GGML_TYPE_Q4_0);
    }
    return g;
}

double seconds_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
}

void put(char* err, int size, const std::string& s) {
    if (err && size > 0) std::snprintf(err, size_t(size), "%s", s.c_str());
}

}  // namespace

struct bc_llm {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::atomic<bool> cancelled{false};
    std::vector<llama_token> prompt;
    llama_token next = 0;
    uint64_t seed = 0, rng = 0;
    std::string error;
    int threads = 0, batch_threads = 0;
    // the CPU's worker threads, kept for the whole run as llama-bench keeps them: without a pool ggml starts and
    // ends its threads for every graph — every token — and on a phone threads that new never look busy enough
    // for the scheduler to raise their cores' clocks
    ggml_threadpool* pool = nullptr;
    ggml_threadpool* batch_pool = nullptr;
    std::vector<ggml_threadpool*> retired;   // replaced pools: ggml pauses the old one when it switches, so they
                                             // live until the next graph has run

    bool fail(const char* what, int code = 0) {
        const std::string why = last_log_error();
        error = cancelled ? std::string("stopped") : std::string(what) + (code ? " (" + std::to_string(code) + ")" : "")
            + (why.empty() ? "" : ": " + why);
        return false;
    }
};

static bool on_abort(void* data) { return static_cast<bc_llm*>(data)->cancelled.load(); }

extern "C" {

int64_t bc_llm_weight_bytes(void) {
    const int64_t per_layer = int64_t(EMBD) * (EMBD + 2 * KV_HEADS * HEAD_DIM + EMBD) + 3 * int64_t(EMBD) * FF;
    return (per_layer * LAYERS + int64_t(EMBD) * VOCAB) / 32 * Q4_0_BLOCK + int64_t(4) * EMBD * (2 * LAYERS + 1);
}

void bc_llm_set_cache_dir(const char* dir) {
    if (dir && *dir) setenv("GGML_OPENCL_KERNEL_CACHE_DIR", dir, 1);
}

const char* bc_llm_gpu_name(void) {
    init_once();
    static std::string name;
    ggml_backend_dev_t dev = gpu_device();
    name = dev ? ggml_backend_dev_description(dev) : "";
    return name.c_str();
}

bc_llm* bc_llm_create(int gpu, int threads, uint64_t seed, char* err, int err_size) {
    init_once();
    ggml_backend_dev_t dev = gpu ? gpu_device() : nullptr;
    if (gpu && !dev) { put(err, err_size, "llama.cpp found no GPU it can run on"); return nullptr; }

    auto* llm = new bc_llm();
    llm->seed = seed;
    llm->rng = mix(seed);
    llm->prompt.resize(BC_LLM_PROMPT);
    for (auto& t : llm->prompt) t = llama_token((llm->rng = mix(llm->rng)) % VOCAB);
    llm->next = llm->prompt[0];

    ggml_backend_dev_t devices[2] = {dev, nullptr};             // the CPU run: no device, nothing offloaded
    llama_model_params mp = llama_model_default_params();
    mp.devices = devices;
    mp.n_gpu_layers = gpu ? -1 : 0;
    mp.split_mode = LLAMA_SPLIT_MODE_NONE;
    mp.load_mode = LLAMA_LOAD_MODE_NONE;
    gguf_context* meta = metadata();
    Fill fill{seed, {}, {}};
    llm->model = llama_model_init_from_user(meta, fill_tensor, &fill, mp);
    gguf_free(meta);
    std::vector<uint8_t>().swap(fill.staging);
    if (!llm->model || !fill.error.empty()) {
        llm->fail(fill.error.empty() ? "llama.cpp could not build the model" : fill.error.c_str());
        put(err, err_size, llm->error);
        bc_llm_destroy(llm);
        return nullptr;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = BC_LLM_CTX;
    cp.n_batch = BC_LLM_PROMPT;
    cp.n_ubatch = BC_LLM_PROMPT;
    cp.n_seq_max = 1;
    cp.n_threads = cp.n_threads_batch = std::max(1, threads);
    cp.offload_kqv = gpu != 0;
    cp.op_offload = gpu != 0;
    cp.no_perf = true;
    cp.abort_callback = on_abort;
    cp.abort_callback_data = llm;
    llm->ctx = llama_init_from_model(llm->model, cp);
    if (!llm->ctx) {
        llm->fail("llama.cpp could not create a context");
        put(err, err_size, llm->error);
        bc_llm_destroy(llm);
        return nullptr;
    }
    bc_llm_set_threads(llm, cp.n_threads, cp.n_threads);
    return llm;
}

static ggml_threadpool* new_pool(int threads) {
    ggml_threadpool_params p = ggml_threadpool_params_default(threads);
    return ggml_threadpool_new(&p);
}

static void free_retired(bc_llm* llm) {
    for (auto* p : llm->retired) ggml_threadpool_free(p);
    llm->retired.clear();
}

void bc_llm_set_threads(bc_llm* llm, int threads, int batch_threads) {
    threads = std::max(1, threads);
    batch_threads = std::max(1, batch_threads);
    if (threads == llm->threads && batch_threads == llm->batch_threads && llm->pool) return;
    if (llm->pool) llm->retired.push_back(llm->pool);
    if (llm->batch_pool && llm->batch_pool != llm->pool) llm->retired.push_back(llm->batch_pool);
    llm->threads = threads;
    llm->batch_threads = batch_threads;
    llm->pool = new_pool(threads);
    llm->batch_pool = batch_threads == threads ? llm->pool : new_pool(batch_threads);
    llama_set_n_threads(llm->ctx, threads, batch_threads);
    if (llm->pool && llm->batch_pool) llama_attach_threadpool(llm->ctx, llm->pool, llm->batch_pool);
}

int bc_llm_tune_threads(bc_llm* llm, int* threads, int* batch_threads) {
    // Most threads first, one fewer at a time, until fewer have lost twice in a row. Every op ends with all the
    // threads waiting for the slowest: past the cores that keep up — with memory when decoding, with the rest of
    // the phone (its UI, its system) on a busy chip — more threads only add waiting.
    const int cores = int(std::max(1u, std::thread::hardware_concurrency()));
    if (bc_llm_prefill(llm, 32) < 0) return 0;                 // first run: buffers, kernels
    double best = 1e30;
    int tg = cores, worse = 0;
    for (int n = cores; n >= 1 && worse < 2; n--) {
        bc_llm_set_threads(llm, n, cores);
        bc_llm_reset(llm);
        for (int i = 0; i < 2; i++) if (bc_llm_next(llm, nullptr) < 0) return 0;   // warms the decode path
        double s = 0;
        for (int i = 0; i < 6; i++) {
            const double t = bc_llm_next(llm, nullptr);
            if (t < 0) return 0;
            s += t;
        }
        if (s < best) { best = s; tg = n; worse = 0; } else worse++;
    }
    best = 1e30;
    int pp = cores;
    worse = 0;
    for (int n = cores; n >= 1 && worse < 2; n--) {
        bc_llm_set_threads(llm, tg, n);
        const double s = bc_llm_prefill(llm, 64);
        if (s < 0) return 0;
        if (s < best) { best = s; pp = n; worse = 0; } else worse++;
    }
    bc_llm_set_threads(llm, tg, pp);
    if (threads) *threads = tg;
    if (batch_threads) *batch_threads = pp;
    return 1;
}

double bc_llm_prefill(bc_llm* llm, int tokens) {
    tokens = std::max(1, std::min(int(BC_LLM_PROMPT), tokens));
    llama_memory_clear(llama_get_memory(llm->ctx), true);
    const auto t0 = std::chrono::steady_clock::now();
    const int32_t r = llama_decode(llm->ctx, llama_batch_get_one(llm->prompt.data(), tokens));
    llama_synchronize(llm->ctx);
    const double s = seconds_since(t0);
    free_retired(llm);
    if (r != 0) { llm->fail("the prompt did not run", r); return -1; }
    const float* logits = llama_get_logits_ith(llm->ctx, -1);
    if (!logits) { llm->fail("no output"); return -1; }
    for (uint32_t i = 0; i < VOCAB; i += 97) {
        if (!std::isfinite(logits[i])) { llm->error = "the model's output was not a number"; return -1; }
    }
    llm->error.clear();
    return s;
}

void bc_llm_reset(bc_llm* llm) {
    llama_memory_clear(llama_get_memory(llm->ctx), true);
    llm->rng = mix(llm->seed ^ 0x5eed);                         // every reply draws the same numbers
    llm->next = llm->prompt[0];
}

double bc_llm_next(bc_llm* llm, int* token) {
    llama_token in = llm->next;
    const auto t0 = std::chrono::steady_clock::now();
    const int32_t r = llama_decode(llm->ctx, llama_batch_get_one(&in, 1));
    free_retired(llm);
    if (r != 0) { llm->fail(r == 1 ? "the context is full" : "the token did not run", r); return -1; }
    const float* logits = llama_get_logits_ith(llm->ctx, -1);  // waits for the GPU
    if (!logits) { llm->fail("no output"); return -1; }
    // sampled as chat apps do: the 40 likeliest, temperature 0.8 (greedy picks would repeat one token forever
    // with weights that were never trained)
    float top_v[TOP_K];
    int top_i[TOP_K];
    int filled = 0, low = 0;
    bool finite = true;
    for (uint32_t i = 0; i < VOCAB; i++) {
        const float v = logits[i];
        finite = finite && std::isfinite(v);
        if (filled < TOP_K) {
            top_v[filled] = v;
            top_i[filled++] = int(i);
            if (filled == TOP_K) low = int(std::min_element(top_v, top_v + TOP_K) - top_v);
        } else if (v > top_v[low]) {
            top_v[low] = v;
            top_i[low] = int(i);
            low = int(std::min_element(top_v, top_v + TOP_K) - top_v);
        }
    }
    if (!finite) { llm->error = "the model's output was not a number"; return -1; }
    const float high = *std::max_element(top_v, top_v + TOP_K);
    double p[TOP_K], sum = 0;
    for (int k = 0; k < TOP_K; k++) sum += p[k] = std::exp(double(top_v[k] - high) / TEMPERATURE);
    double u = double((llm->rng = mix(llm->rng)) >> 11) / double(1ULL << 53) * sum;
    int pick = TOP_K - 1;
    for (int k = 0; k < TOP_K; k++) if ((u -= p[k]) <= 0) { pick = k; break; }
    const double s = seconds_since(t0);
    llm->next = llama_token(top_i[pick]);
    if (token) *token = top_i[pick];
    llm->error.clear();
    return s;
}

void bc_llm_cancel(bc_llm* llm) { llm->cancelled = true; }

const char* bc_llm_error(const bc_llm* llm) { return llm->error.c_str(); }

void bc_llm_destroy(bc_llm* llm) {
    if (!llm) return;
    if (llm->ctx) llama_free(llm->ctx);
    if (llm->pool) llm->retired.push_back(llm->pool);
    if (llm->batch_pool && llm->batch_pool != llm->pool) llm->retired.push_back(llm->batch_pool);
    free_retired(llm);
    if (llm->model) llama_model_free(llm->model);
    delete llm;
}

int bc_llm_largest_model(double bytes) {
    // typical 4-bit (Q4_K_M) builds, GB: Llama 3.2 1B and 3B, Gemma 3 4B, Llama 3.1 8B, Qwen3 14B, Mistral Small
    // 24B, Qwen3 32B, Llama 3.3 70B — plus 0.6 GB for a 4K context and the runtime
    static const struct { int billions; double gb; } MODELS[] = {
        {70, 42.5}, {32, 19.8}, {24, 14.3}, {14, 9.0}, {8, 4.9}, {4, 2.5}, {3, 2.0}, {1, 0.8},
    };
    for (const auto& m : MODELS) if ((m.gb + 0.6) * 1e9 <= bytes) return m.billions;
    return 0;
}

}  // extern "C"
