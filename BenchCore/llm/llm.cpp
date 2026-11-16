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
//   GPU llama.cpp's Metal (iPhone), OpenCL (Adreno) or Vulkan (other Android GPUs) backend with every layer on it.
//   A GPU's answer to the prompt has to agree with the CPU's: a driver that computes wrong numbers fast doesn't count.
//   On Linux and Android the CPU's threads can be pinned to named cores (bc_llm_set_cores): only when a caller asks, and
//   the Android app asks only after measuring that it helps. iOS and Windows never call it, so they run as before.
#include "bench_llm.h"
#include "bench_guard.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#endif
#if defined(__linux__)
#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "gguf.h"
#include "llama.h"

namespace {

// Llama 3.2 3B
constexpr uint32_t VOCAB = 128256, EMBD = 3072, LAYERS = 28, HEADS = 24, KV_HEADS = 8, HEAD_DIM = 128, FF = 8192;
constexpr float ROPE_BASE = 500000.0f;

/** A Llama-shaped model's dimensions: the test's own is Llama 3.2 3B's, the kernel self-test's is a tiny one. */
struct Shape { uint32_t vocab, embd, layers, heads, kv_heads, head_dim, ff; };
constexpr Shape FULL{VOCAB, EMBD, LAYERS, HEADS, KV_HEADS, HEAD_DIM, FF};
/** Small enough to build and run in a few milliseconds, and every matrix still a repackable Q4_0 one (rows a multiple of 4, columns of 32). */
constexpr Shape TINY{1024, 512, 2, 8, 2, 64, 1536};
constexpr double PI = 3.14159265358979323846;
constexpr int Q4_0_BLOCK = 18;          // bytes per 32 weights: an fp16 scale and 16 bytes of nibbles
constexpr int TOP_K = 40;
constexpr double TEMPERATURE = 0.8;
constexpr uint32_t SAMPLE_STEP = 97;    // every 97th logit: what the finiteness and agreement checks look at
constexpr double MIN_AGREEMENT = 0.9;   // correlation with the CPU's logits a GPU's must reach

std::mutex g_log_mutex;
std::string g_log_error;                 // llama.cpp's last error line, for the reason a step failed
std::vector<std::string> g_backend_log;  // what the GPU backends say about the device they chose (see keep_backend_line)

/**
 * The GPU backends write their decisions at debug level — Vulkan's "ggml_vulkan: 0 = <device> | uma | fp16 | warp size |
 * shared memory | int dot | matrix cores" line and its "No suitable matrix core mode" warning, OpenCL's device line —
 * and nothing else would ever show them. Kept (a few dozen short lines) for the report: bc_llm_backend_log.
 */
void keep_backend_line(const char* text) {
    if (std::strncmp(text, "ggml_vulkan:", 12) != 0 && std::strncmp(text, "ggml_opencl:", 12) != 0) return;
    std::string line(text);
    while (!line.empty() && (line.back() == '\n' || line.back() == ' ')) line.pop_back();
    if (line.size() > 400) line.resize(400);
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_backend_log.size() < 40) g_backend_log.push_back(line);
}

void on_log(ggml_log_level level, const char* text, void*) {
    if (text) keep_backend_line(text);
#ifdef __ANDROID__
    if (text && (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR))   // the whole story, in logcat
        __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "llama", "%s", text);
#endif
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

std::string g_gpu_api;                   // "" = the choice below; a backend's name makes it the one used (bc_llm_set_gpu_api)

/**
 * The GPU to run on: OpenCL's when it has one (it takes only Adreno GPUs, which its kernels are written for —
 * Vulkan lists the Adreno too), otherwise the first: Vulkan's on other Android GPUs, Metal's on iOS. A caller can
 * name the backend instead (bc_llm_set_gpu_api): the Android app does for its report-only run of Vulkan on an Adreno.
 */
ggml_backend_dev_t gpu_device() {
    ggml_backend_dev_t first = nullptr;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
        const char* reg = ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev));
        if (!g_gpu_api.empty()) {
            if (g_gpu_api == reg) return dev;
            continue;
        }
        if (std::strcmp(reg, "OpenCL") == 0) return dev;
        if (!first) first = dev;
    }
    return first;
}

// The CPU's logits for each prompt it ran (by seed and length), sampled: what a GPU's must agree with.
std::mutex g_ref_mutex;
std::map<std::pair<uint64_t, int>, std::vector<float>> g_ref;

/** Pearson correlation of two samples. */
double correlation(const std::vector<float>& a, const std::vector<float>& b) {
    const size_t n = std::min(a.size(), b.size());
    double ma = 0, mb = 0;
    for (size_t i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
    ma /= double(n);
    mb /= double(n);
    double ab = 0, aa = 0, bb = 0;
    for (size_t i = 0; i < n; i++) {
        const double x = a[i] - ma, y = b[i] - mb;
        ab += x * y;
        aa += x * x;
        bb += y * y;
    }
    return aa > 0 && bb > 0 ? ab / std::sqrt(aa * bb) : 0;
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
void rope_factors(float* dst, size_t n, uint32_t head_dim) {
    const double factor = 32, low = 1, high = 4, old_ctx = 8192;
    for (size_t i = 0; i < n; i++) {
        const double freq = 1.0 / std::pow(double(ROPE_BASE), double(2 * i) / head_dim);
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
    uint32_t head_dim = HEAD_DIM;
};

/** llama.cpp asks for each tensor's data once it is allocated (on the CPU, repacked, or on the GPU). */
void fill_tensor(ggml_tensor* t, void* user) {
    auto* fill = static_cast<Fill*>(user);
    const size_t n = ggml_nbytes(t);
    if (fill->staging.size() < n) fill->staging.resize(n);
    uint8_t* data = fill->staging.data();
    if (t->type == GGML_TYPE_F32) {
        auto* f = reinterpret_cast<float*>(data);
        if (std::strcmp(t->name, "rope_freqs.weight") == 0) rope_factors(f, n / 4, fill->head_dim);
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
gguf_context* metadata(const Shape& m = FULL) {
    gguf_context* g = gguf_init_empty();
    gguf_set_val_str(g, "general.architecture", "llama");
    gguf_set_val_str(g, "general.name", "Llama 3.2 3B shape, seeded weights");
    gguf_set_val_u32(g, "llama.vocab_size", m.vocab);
    gguf_set_val_u32(g, "llama.context_length", 131072);
    gguf_set_val_u32(g, "llama.embedding_length", m.embd);
    gguf_set_val_u32(g, "llama.block_count", m.layers);
    gguf_set_val_u32(g, "llama.feed_forward_length", m.ff);
    gguf_set_val_u32(g, "llama.attention.head_count", m.heads);
    gguf_set_val_u32(g, "llama.attention.head_count_kv", m.kv_heads);
    gguf_set_val_u32(g, "llama.rope.dimension_count", m.head_dim);
    gguf_set_val_f32(g, "llama.rope.freq_base", ROPE_BASE);
    gguf_set_val_f32(g, "llama.attention.layer_norm_rms_epsilon", 1e-5f);
    gguf_set_val_str(g, "tokenizer.ggml.model", "no_vocab");
    // only the tensors listed here exist (third_party/llama.cpp/README.md): no biases, the output head tied
    add_tensor(g, "token_embd.weight", GGML_TYPE_Q4_0);
    add_tensor(g, "output_norm.weight", GGML_TYPE_F32);
    add_tensor(g, "rope_freqs.weight", GGML_TYPE_F32);
    for (uint32_t i = 0; i < m.layers; i++) {
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
    bool gpu = false;
    bool broken = false;                     // a call into the backend threw: the handle answers every call with a failure and is only freed
    double agreement = 1;                    // the last prefill's correlation with the CPU's output
    std::vector<float> last_sample;          // the last prefill's sampled logits: what two builds' outputs are compared by
    int threads = 0, batch_threads = 0;
    // the CPU's worker threads, kept for the whole run as llama-bench keeps them: without a pool ggml starts and
    // ends its threads for every graph — every token — and on a phone threads that new never look busy enough
    // for the scheduler to raise their cores' clocks
    ggml_threadpool* pool = nullptr;
    ggml_threadpool* batch_pool = nullptr;
    std::vector<ggml_threadpool*> retired;   // replaced pools: ggml pauses the old one when it switches, so they
                                             // live until the next graph has run
    // Pinning (bc_llm_set_cores; Linux and Android only). `cores` are the cores the CPU threads may use, fastest first.
    // A pinned pool of n threads puts one thread on each of the first n cores (ggml's cpumask with strict_cpu).
    std::vector<int> cores;
    bool pin_decode = false, pin_prompt = false;             // what the next pools are made with
    bool pool_pinned_decode = false, pool_pinned_prompt = false;   // what the current pools were made with
#if defined(__linux__)
    // ggml also pins the thread that makes a pool (worker 0: the one that then calls llama_decode) and nothing ever
    // un-pins it, so every thread we may have changed keeps its own original mask here, to put back
    std::vector<std::pair<long, cpu_set_t>> touched;
    long main_tid = 0;                                       // where the calling thread was last put (0: unknown)
    int main_core = -1;                                      // -1: on its original mask
#endif

    bool fail(const char* what, int code = 0) {
        const std::string why = last_log_error();
        error = cancelled ? std::string("stopped") : std::string(what) + (code ? " (" + std::to_string(code) + ")" : "")
            + (why.empty() ? "" : ": " + why);
        return false;
    }

    /** A call into llama.cpp threw (a GPU driver that lost its device, say): this handle is done, and says why. */
    bool fail_threw(const char* what, const std::string& thrown) {
        broken = true;
        const std::string why = last_log_error();
        error = cancelled ? std::string("stopped") : std::string(what) + ": the GPU backend threw " + thrown
            + (why.empty() ? "" : " (" + why + ")");
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

const char* bc_llm_gpu_api(void) {
    init_once();
    ggml_backend_dev_t dev = gpu_device();
    if (!dev) return "";
    const char* name = ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev));
    return std::strcmp(name, "MTL") == 0 ? "Metal" : name;
}

bc_llm* bc_llm_create(int gpu, int threads, uint64_t seed, char* err, int err_size) {
    init_once();
    ggml_backend_dev_t dev = gpu ? gpu_device() : nullptr;
    if (gpu && !dev) { put(err, err_size, "llama.cpp found no GPU it can run on (Metal, OpenCL or Vulkan)"); return nullptr; }

    auto* llm = new bc_llm();
    llm->gpu = gpu != 0;
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
    std::string thrown;
    const bool built = bc_guard::call([&] { llm->model = llama_model_init_from_user(meta, fill_tensor, &fill, mp); }, thrown);
    gguf_free(meta);
    std::vector<uint8_t>().swap(fill.staging);
    if (!built) {
        llm->fail_threw("the model could not be built", thrown);
        put(err, err_size, llm->error);
        bc_llm_destroy(llm);
        return nullptr;
    }
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
    if (!bc_guard::call([&] { llm->ctx = llama_init_from_model(llm->model, cp); }, thrown)) {
        llm->fail_threw("the context could not be created", thrown);
        put(err, err_size, llm->error);
        bc_llm_destroy(llm);
        return nullptr;
    }
    if (!llm->ctx) {
        llm->fail("llama.cpp could not create a context");
        put(err, err_size, llm->error);
        bc_llm_destroy(llm);
        return nullptr;
    }
    bc_llm_set_threads(llm, cp.n_threads, cp.n_threads);
    return llm;
}

#if defined(__linux__)
static long this_tid() { return long(syscall(__NR_gettid)); }

/** The first time a thread is about to be pinned (by us or by ggml for the pool it makes): keeps its own mask. */
static void remember_affinity(bc_llm* llm) {
    const long tid = this_tid();
    for (const auto& t : llm->touched) if (t.first == tid) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof set, &set) == 0) llm->touched.emplace_back(tid, set);
}

static void restore_this_thread(bc_llm* llm) {
    const long tid = this_tid();
    for (auto& t : llm->touched)
        if (t.first == tid) { sched_setaffinity(0, sizeof t.second, &t.second); break; }
    llm->main_tid = 0;
}

static void restore_all_threads(bc_llm* llm) {
    // a thread that has ended just answers ESRCH
    for (auto& t : llm->touched) sched_setaffinity(pid_t(t.first), sizeof t.second, &t.second);
    llm->touched.clear();
    llm->main_tid = 0;
}

/** The core the pool of n threads gives worker 0, the one that calls llama_decode: ggml hands strict masks out in core-number order, the caller last. */
static int main_core(const bc_llm* llm, int n) {
    int best = -1;
    for (int i = 0; i < n && i < int(llm->cores.size()); i++) best = std::max(best, llm->cores[size_t(i)]);
    return best;
}

/**
 * ggml pins the calling thread (worker 0) once, when it makes a pool — and the decode and prompt pools are made one
 * after the other on the same thread, so the second one's mask is what is left. Before each run the thread is put where
 * the pool it is about to use wants it (or back on its own mask when that pool isn't pinned).
 */
static void apply_main(bc_llm* llm, bool decode) {
    if (llm->cores.empty()) return;                              // never asked to pin: the thread is left alone
    const bool pinned = decode ? llm->pool_pinned_decode : llm->pool_pinned_prompt;
    const int want = pinned ? main_core(llm, decode ? llm->threads : llm->batch_threads) : -1;
    const long tid = this_tid();
    if (llm->main_tid == tid && llm->main_core == want) return;
    if (want >= 0) {
        remember_affinity(llm);
        cpu_set_t set;
        CPU_ZERO(&set);
        CPU_SET(want, &set);
        sched_setaffinity(0, sizeof set, &set);
    } else restore_this_thread(llm);
    llm->main_tid = tid;
    llm->main_core = want;
}

/** The core the calling thread is on alone right now, or -1 when its mask isn't a single core. */
static int single_core_of_this_thread() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof set, &set) != 0 || CPU_COUNT(&set) != 1) return -1;
    for (int i = 0; i < CPU_SETSIZE; i++) if (CPU_ISSET(i, &set)) return i;
    return -1;
}
#else
static void apply_main(bc_llm*, bool) {}
static void remember_affinity(bc_llm*) {}
static void restore_this_thread(bc_llm*) {}
static void restore_all_threads(bc_llm*) {}
static int single_core_of_this_thread() { return -1; }
#endif

/**
 * A pool of `threads`: pinned, it has one thread on each of the first cores of llm->cores (ggml's cpumask and
 * strict_cpu), never more threads than cores; unpinned, ggml's defaults. A pool's workers start with the mask of the
 * thread that makes it, so an unpinned pool is made only after that thread has its own mask back.
 */
static ggml_threadpool* new_pool(bc_llm* llm, int threads, bool pin) {
    ggml_threadpool_params p = ggml_threadpool_params_default(threads);
#if defined(__linux__)
    if (pin && !llm->cores.empty()) {
        remember_affinity(llm);
        p.n_threads = std::min(threads, int(llm->cores.size()));
        for (int i = 0; i < p.n_threads; i++) p.cpumask[llm->cores[size_t(i)]] = true;
        p.strict_cpu = true;
    } else restore_this_thread(llm);
#else
    (void) llm; (void) pin;
#endif
    return ggml_threadpool_new(&p);
}

static void free_retired(bc_llm* llm) {
    for (auto* p : llm->retired) ggml_threadpool_free(p);
    llm->retired.clear();
}

void bc_llm_set_threads(bc_llm* llm, int threads, int batch_threads) {
    threads = std::max(1, threads);
    batch_threads = std::max(1, batch_threads);
    const bool pin_d = llm->pin_decode && !llm->cores.empty(), pin_p = llm->pin_prompt && !llm->cores.empty();
    if (threads == llm->threads && batch_threads == llm->batch_threads && llm->pool &&
        pin_d == llm->pool_pinned_decode && pin_p == llm->pool_pinned_prompt) return;
    if (llm->pool) llm->retired.push_back(llm->pool);
    if (llm->batch_pool && llm->batch_pool != llm->pool) llm->retired.push_back(llm->batch_pool);
    llm->threads = threads;
    llm->batch_threads = batch_threads;
    llm->pool_pinned_decode = pin_d;
    llm->pool_pinned_prompt = pin_p;
    llm->pool = new_pool(llm, threads, pin_d);
    llm->batch_pool = (batch_threads == threads && pin_d == pin_p) ? llm->pool : new_pool(llm, batch_threads, pin_p);
#if defined(__linux__)
    llm->main_tid = 0;                                           // making the pools moved the calling thread: apply_main puts it right
#endif
    llama_set_n_threads(llm->ctx, threads, batch_threads);
    if (llm->pool && llm->batch_pool) llama_attach_threadpool(llm->ctx, llm->pool, llm->batch_pool);
}

int bc_llm_set_cores(bc_llm* llm, const int* cores, int n, int pin_decode, int pin_prompt) {
    llm->cores.clear();
    llm->pin_decode = llm->pin_prompt = false;
#if defined(__linux__)
    for (int i = 0; cores && i < n; i++) {
        const int c = cores[i];
        if (c < 0 || c >= GGML_MAX_N_THREADS || c >= CPU_SETSIZE) continue;
        if (std::find(llm->cores.begin(), llm->cores.end(), c) == llm->cores.end()) llm->cores.push_back(c);
    }
    llm->pin_decode = pin_decode != 0 && !llm->cores.empty();
    llm->pin_prompt = pin_prompt != 0 && !llm->cores.empty();
    if (llm->cores.empty()) restore_this_thread(llm);
#else
    (void) cores; (void) n; (void) pin_decode; (void) pin_prompt;
#endif
    if (llm->ctx && llm->threads > 0) bc_llm_set_threads(llm, llm->threads, llm->batch_threads);
    return int(llm->cores.size());
}

int bc_llm_tune_threads(bc_llm* llm, int* threads, int* batch_threads) {
    return bc_llm_tune_threads_progress(llm, threads, batch_threads, nullptr, nullptr);
}

int bc_llm_tune_threads_progress(bc_llm* llm, int* threads, int* batch_threads, bc_llm_tune_progress progress, void* user) {
    // Most threads first, one fewer at a time, until fewer have lost twice in a row. Every op ends with all the
    // threads waiting for the slowest: past the cores that keep up — with memory when decoding, with the rest of
    // the phone (its UI, its system) on a busy chip — more threads only add waiting.
    const int cores = int(std::max(1u, std::thread::hardware_concurrency()));
    if (progress) progress(user, 0, cores, 0, cores);          // trial 0: the first run, which sets up buffers and kernels
    if (bc_llm_prefill(llm, 32) < 0) return 0;
    double best = 1e30;
    int tg = cores, worse = 0, trial = 0;
    for (int n = cores; n >= 1 && worse < 2; n--) {
        if (progress) progress(user, 0, n, ++trial, cores);
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
    trial = 0;
    for (int n = cores; n >= 1 && worse < 2; n--) {
        if (progress) progress(user, 1, n, ++trial, cores);
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

static constexpr int DECODE_TRIAL_TOKENS = 6, PROMPT_TRIAL_TOKENS = 64;
static constexpr double PIN_MIN_GAIN = 1.05;    // pinned must be at least this much faster than the best unpinned count to be used
static constexpr double PIN_PASS_MAX_S = 15.0;  // a phone whose unpinned pass of a phase took longer than this doesn't get a second pass

/** One decode trial: the thread count is already set. False when a token failed. */
static bool decode_trial(bc_llm* llm, double* seconds) {
    bc_llm_reset(llm);
    for (int i = 0; i < 2; i++) if (bc_llm_next(llm, nullptr) < 0) return false;   // warms the decode path
    double s = 0;
    for (int i = 0; i < DECODE_TRIAL_TOKENS; i++) {
        const double t = bc_llm_next(llm, nullptr);
        if (t < 0) return false;
        s += t;
    }
    *seconds = s;
    return true;
}

struct Pass {
    int threads = 0;                        // the count that was fastest
    double seconds = 1e30;                  // and its time
    double elapsed = 0;                     // what the whole pass took
    bool ok = true;
};

int bc_llm_tune_threads_pinned(bc_llm* llm, const int* cores, int n_cores, int* threads, int* batch_threads,
                               bc_llm_pin_report* report, bc_llm_tune_progress_pinned progress, void* user) {
    // The same search as bc_llm_tune_threads, twice per phase: as it was (the most threads first, on whatever cores
    // the system gives) and pinned (one thread on each of the fastest cores, never more threads than cores). The pinned
    // result is used only where it beats the unpinned best by PIN_MIN_GAIN: pinning can also hurt, when the system
    // wants one of those cores for itself.
    const auto started = std::chrono::steady_clock::now();
    bc_llm_pin_report local;
    bc_llm_pin_report* rep = report ? report : &local;
    std::memset(rep, 0, sizeof *rep);
    rep->main_core = -1;
    const int hw = int(std::max(1u, std::thread::hardware_concurrency()));
    const int pinnable = bc_llm_set_cores(llm, cores, n_cores, 0, 0);
    rep->pinnable = pinnable;
    if (progress) progress(user, 0, 0, hw, 0, hw);              // trial 0: the first run, which sets up buffers and kernels
    if (bc_llm_prefill(llm, 32) < 0) return 0;

    const auto record = [&](int phase, bool pinned, int n, int tokens, double s) {
        if (rep->n_trials >= BC_LLM_MAX_TRIALS) return;
        rep->trials[rep->n_trials++] = {phase, pinned ? 1 : 0, n, tokens, s};
    };
    const auto run_pass = [&](int phase, bool pinned, int first, int fixed_decode) -> Pass {
        Pass pass;
        const auto t0 = std::chrono::steady_clock::now();
        int worse = 0, trial = 0;
        for (int n = first; n >= 1 && worse < 2; n--) {
            if (progress) progress(user, phase, pinned ? 1 : 0, n, ++trial, first);
            double s = 0;
            if (phase == 0) {
                llm->pin_decode = pinned;
                llm->pin_prompt = false;
                bc_llm_set_threads(llm, n, hw);
                if (!decode_trial(llm, &s)) { pass.ok = false; return pass; }
            } else {
                llm->pin_decode = fixed_decode != 0 && pinnable > 0;   // what the decode phase settled on
                llm->pin_prompt = pinned;
                bc_llm_set_threads(llm, rep->decode_threads, n);
                s = bc_llm_prefill(llm, PROMPT_TRIAL_TOKENS);
                if (s < 0) { pass.ok = false; return pass; }
            }
            record(phase, pinned, n, phase == 0 ? DECODE_TRIAL_TOKENS : PROMPT_TRIAL_TOKENS, s);
            if (s < pass.seconds) { pass.seconds = s; pass.threads = n; worse = 0; } else worse++;
        }
        pass.elapsed = seconds_since(t0);
        return pass;
    };

    // decode
    Pass plain = run_pass(0, false, hw, 0);
    if (!plain.ok) return 0;
    int tg = plain.threads;
    bool pin_d = false;
    if (pinnable >= 1) {
        if (plain.elapsed > PIN_PASS_MAX_S) rep->decode_skipped = 1;
        else {
            Pass pinned = run_pass(0, true, pinnable, 0);
            if (!pinned.ok) return 0;
            if (pinned.seconds * PIN_MIN_GAIN <= plain.seconds) { pin_d = true; tg = pinned.threads; }
        }
    }
    rep->decode_pinned = pin_d ? 1 : 0;
    rep->decode_threads = tg;

    // prompt
    plain = run_pass(1, false, hw, pin_d ? 1 : 0);
    if (!plain.ok) return 0;
    int pp = plain.threads;
    bool pin_p = false;
    if (pinnable >= 1) {
        if (plain.elapsed > PIN_PASS_MAX_S) rep->prompt_skipped = 1;
        else {
            Pass pinned = run_pass(1, true, pinnable, pin_d ? 1 : 0);
            if (!pinned.ok) return 0;
            if (pinned.seconds * PIN_MIN_GAIN <= plain.seconds) { pin_p = true; pp = pinned.threads; }
        }
    }
    rep->prompt_pinned = pin_p ? 1 : 0;
    rep->prompt_threads = pp;

    llm->pin_decode = pin_d;
    llm->pin_prompt = pin_p;
    bc_llm_set_threads(llm, tg, pp);
    apply_main(llm, true);
    rep->main_core = single_core_of_this_thread();
    rep->seconds = seconds_since(started);
    if (threads) *threads = tg;
    if (batch_threads) *batch_threads = pp;
    return 1;
}

double bc_llm_prefill(bc_llm* llm, int tokens) {
    tokens = std::max(1, std::min(int(BC_LLM_PROMPT), tokens));
    if (llm->broken) return -1;
    int32_t r = 0;
    double s = 0;
    std::string thrown;
    if (!bc_guard::call([&] {
            apply_main(llm, false);
            llama_memory_clear(llama_get_memory(llm->ctx), true);
            const auto t0 = std::chrono::steady_clock::now();
            r = llama_decode(llm->ctx, llama_batch_get_one(llm->prompt.data(), tokens));
            llama_synchronize(llm->ctx);
            s = seconds_since(t0);
            free_retired(llm);
        }, thrown)) {
        llm->fail_threw("the prompt did not run", thrown);
        return -1;
    }
    if (r != 0) { llm->fail("the prompt did not run", r); return -1; }
    const float* logits = nullptr;
    if (!bc_guard::call([&] { logits = llama_get_logits_ith(llm->ctx, -1); }, thrown)) {
        llm->fail_threw("the prompt's output could not be read", thrown);
        return -1;
    }
    if (!logits) { llm->fail("no output"); return -1; }
    std::vector<float> sample;
    for (uint32_t i = 0; i < VOCAB; i += SAMPLE_STEP) {
        if (!std::isfinite(logits[i])) { llm->error = "the model's output was not a number"; return -1; }
        sample.push_back(logits[i]);
    }
    llm->last_sample = sample;
    {
        std::lock_guard<std::mutex> lock(g_ref_mutex);
        const auto key = std::make_pair(llm->seed, tokens);
        if (!llm->gpu) g_ref[key] = sample;
        else if (const auto ref = g_ref.find(key); ref != g_ref.end()) llm->agreement = correlation(ref->second, sample);
    }
    if (llm->agreement < MIN_AGREEMENT) {
        char why[96];
        std::snprintf(why, sizeof why, "the GPU's results differ from the CPU's (correlation %.2f)", llm->agreement);
        llm->error = why;
        return -1;
    }
    llm->error.clear();
    return s;
}

void bc_llm_reset(bc_llm* llm) {
    if (llm->broken) return;
    std::string thrown;
    if (!bc_guard::call([&] { llama_memory_clear(llama_get_memory(llm->ctx), true); }, thrown)) {
        llm->fail_threw("the reply could not be reset", thrown);
        return;
    }
    llm->rng = mix(llm->seed ^ 0x5eed);                         // every reply draws the same numbers
    llm->next = llm->prompt[0];
}

double bc_llm_next(bc_llm* llm, int* token) {
    if (llm->broken) return -1;
    llama_token in = llm->next;
    int32_t r = 0;
    std::chrono::steady_clock::time_point t0;
    std::string thrown;
    if (!bc_guard::call([&] {
            apply_main(llm, true);
            t0 = std::chrono::steady_clock::now();
            r = llama_decode(llm->ctx, llama_batch_get_one(&in, 1));
            free_retired(llm);
        }, thrown)) {
        llm->fail_threw("the token did not run", thrown);
        return -1;
    }
    if (r != 0) { llm->fail(r == 1 ? "the context is full" : "the token did not run", r); return -1; }
    const float* logits = nullptr;
    if (!bc_guard::call([&] { logits = llama_get_logits_ith(llm->ctx, -1); }, thrown)) {   // waits for the GPU
        llm->fail_threw("the token's output could not be read", thrown);
        return -1;
    }
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

double bc_llm_agreement(const bc_llm* llm) { return llm->agreement; }

int bc_llm_last_sample(const bc_llm* llm, float* out, int cap) {
    const int n = int(llm->last_sample.size());
    if (out && cap > 0) std::memcpy(out, llm->last_sample.data(), sizeof(float) * size_t(std::min(n, cap)));
    return n;
}

namespace {
/**
 * The self-test model (TINY) reading a prompt and one more token: on the CPU (`dev` null: no device, nothing
 * offloaded — the kernel self-test) or with every layer on `dev` (the GPU self-test). The logits of both go to `out`.
 */
int tiny_selftest(ggml_backend_dev_t dev, int threads, float* out, int cap, char* err, int err_size) {
    constexpr int PROMPT = 24;
    const int need = 2 * int(TINY.vocab);
    if (!out || cap < need) { put(err, err_size, "the self-test's output buffer is too small"); return 0; }
    llama_model_params mp = llama_model_default_params();
    ggml_backend_dev_t devices[2] = {dev, nullptr};
    mp.devices = devices;
    mp.n_gpu_layers = dev ? -1 : 0;
    mp.split_mode = LLAMA_SPLIT_MODE_NONE;
    mp.load_mode = LLAMA_LOAD_MODE_NONE;
    gguf_context* meta = metadata(TINY);
    Fill fill{BC_LLM_SEED, {}, {}, TINY.head_dim};
    llama_model* model = llama_model_init_from_user(meta, fill_tensor, &fill, mp);
    gguf_free(meta);
    int n = 0;
    if (!model || !fill.error.empty()) {
        put(err, err_size, fill.error.empty() ? "llama.cpp could not build the self-test model" : fill.error);
        if (model) llama_model_free(model);
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 64;
    cp.n_batch = cp.n_ubatch = 32;
    cp.n_seq_max = 1;
    cp.n_threads = cp.n_threads_batch = std::max(1, threads);
    cp.no_perf = true;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) put(err, err_size, "llama.cpp could not create the self-test context");
    else {
        // a prompt (the matrix-matrix kernels), then one more token (the matrix-vector ones): the logits of both
        std::vector<llama_token> prompt(PROMPT);
        uint64_t rng = mix(BC_LLM_SEED);
        for (auto& t : prompt) t = llama_token((rng = mix(rng)) % TINY.vocab);
        bool ok = llama_decode(ctx, llama_batch_get_one(prompt.data(), PROMPT)) == 0;
        const float* logits = ok ? llama_get_logits_ith(ctx, -1) : nullptr;
        if (logits) {
            std::memcpy(out, logits, sizeof(float) * TINY.vocab);
            llama_token next = prompt[0];
            ok = llama_decode(ctx, llama_batch_get_one(&next, 1)) == 0;
            logits = ok ? llama_get_logits_ith(ctx, -1) : nullptr;
            if (logits) std::memcpy(out + TINY.vocab, logits, sizeof(float) * TINY.vocab);
        }
        bool finite = logits != nullptr;
        for (int i = 0; finite && i < need; i++) finite = std::isfinite(out[i]);
        if (!finite) put(err, err_size, "the self-test model's output was missing or not a number");
        else n = need;
        llama_free(ctx);
    }
    llama_model_free(model);
    return n;
}
}  // namespace

int bc_llm_kernel_selftest(int threads, float* out, int cap, char* err, int err_size) {
    init_once();
    return tiny_selftest(nullptr, threads, out, cap, err, err_size);
}

int bc_llm_gpu_selftest(char* err, int err_size) {
    init_once();
    ggml_backend_dev_t dev = gpu_device();
    if (!dev) { put(err, err_size, "llama.cpp found no GPU it can run on (Metal, OpenCL or Vulkan)"); return 0; }
    std::vector<float> logits(static_cast<size_t>(BC_LLM_SELFTEST_LOGITS), 0.0f);
    int n = 0;
    std::string thrown;
    // a backend that throws is an answer; one that crashes is what the caller's own process is for
    if (!bc_guard::call([&] { n = tiny_selftest(dev, 1, logits.data(), int(logits.size()), err, err_size); }, thrown)) {
        put(err, err_size, "the GPU self-test threw " + thrown);
        return 0;
    }
    return n > 0 ? 1 : 0;
}

void bc_llm_set_reference(uint64_t seed, int tokens, const float* sample, int n) {
    if (!sample || n <= 0) return;
    std::lock_guard<std::mutex> lock(g_ref_mutex);
    g_ref[std::make_pair(seed, tokens)] = std::vector<float>(sample, sample + n);
}

const char* bc_llm_backend_log(void) {
    init_once();
    static std::string text;
    std::lock_guard<std::mutex> lock(g_log_mutex);
    text.clear();
    for (const auto& l : g_backend_log) text += l + "\n";
    return text.c_str();
}

void bc_llm_set_gpu_api(const char* api) { g_gpu_api = api ? api : ""; }

void bc_llm_destroy(bc_llm* llm) {
    if (!llm) return;
    std::string thrown;
    if (llm->ctx) bc_guard::call([&] { llama_free(llm->ctx); }, thrown);      // a device that was lost can throw again here
    if (llm->pool) llm->retired.push_back(llm->pool);
    if (llm->batch_pool && llm->batch_pool != llm->pool) llm->retired.push_back(llm->batch_pool);
    free_retired(llm);
    restore_all_threads(llm);                  // nothing this test pinned stays pinned: the app's threads are reused
    if (llm->model) bc_guard::call([&] { llama_model_free(llm->model); }, thrown);
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
