// MEM tests — RAM bandwidth and latency, and storage read/write speed. The same code on Android (JNI,
// engine/mem/MemNative.kt), iOS (Swift) and Windows (P/Invoke; Win32 file I/O instead of POSIX).
//
// Every measurement runs in short slices (a fraction of a second) so the stage can show a live rate: the RAM
// buffer and the storage file stay open between slices in a handle.
#if defined(_WIN32)
#define _FILE_OFFSET_BITS 64   // a 64-bit off_t, as on the other systems
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <sys/types.h>
#include <windows.h>
#include <malloc.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#endif

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif
#if defined(__linux__)
#include <dlfcn.h>
#endif

#include "bench_core.h"

namespace {

using Clock = std::chrono::steady_clock;

thread_local std::string lastError;

double seconds(Clock::time_point a, Clock::time_point b) { return std::chrono::duration<double>(b - a).count(); }

uint64_t xorshift(uint64_t& s) {
    s ^= s << 13; s ^= s >> 7; s ^= s << 17;
    return s;
}

std::atomic<uint64_t> sink{0};   // keeps the read loops from being optimised away

// ── RAM ──────────────────────────────────────────────────────────────────────

constexpr size_t kChaseStride = 128;   // one pointer per 128 B: neighbouring lines don't prefetch each other

struct Ram {
    uint8_t* buf = nullptr;
    size_t bytes = 0;
    int threads = 1;
    void* chaseAt = nullptr;   // where the pointer chase stopped; null until the chain is built
};

uint64_t readPass(const uint8_t* p, size_t n) {
#if defined(__ARM_NEON)
    uint64x2_t a0 = vdupq_n_u64(0), a1 = a0, a2 = a0, a3 = a0;
    for (size_t i = 0; i < n; i += 64) {
        a0 = vaddq_u64(a0, vld1q_u64(reinterpret_cast<const uint64_t*>(p + i)));
        a1 = vaddq_u64(a1, vld1q_u64(reinterpret_cast<const uint64_t*>(p + i + 16)));
        a2 = vaddq_u64(a2, vld1q_u64(reinterpret_cast<const uint64_t*>(p + i + 32)));
        a3 = vaddq_u64(a3, vld1q_u64(reinterpret_cast<const uint64_t*>(p + i + 48)));
    }
    uint64x2_t s = vaddq_u64(vaddq_u64(a0, a1), vaddq_u64(a2, a3));
    return vgetq_lane_u64(s, 0) + vgetq_lane_u64(s, 1);
#else
    const auto* q = reinterpret_cast<const uint64_t*>(p);
    uint64_t s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, s6 = 0, s7 = 0;
    for (size_t i = 0; i < n / 8; i += 8) {
        s0 += q[i]; s1 += q[i + 1]; s2 += q[i + 2]; s3 += q[i + 3];
        s4 += q[i + 4]; s5 += q[i + 5]; s6 += q[i + 6]; s7 += q[i + 7];
    }
    return s0 + s1 + s2 + s3 + s4 + s5 + s6 + s7;
#endif
}

void writePass(uint8_t* p, size_t n, uint64_t value) {
#if defined(__ARM_NEON)
    uint64x2_t v = vdupq_n_u64(value);
    for (size_t i = 0; i < n; i += 64) {
        vst1q_u64(reinterpret_cast<uint64_t*>(p + i), v);
        vst1q_u64(reinterpret_cast<uint64_t*>(p + i + 16), v);
        vst1q_u64(reinterpret_cast<uint64_t*>(p + i + 32), v);
        vst1q_u64(reinterpret_cast<uint64_t*>(p + i + 48), v);
    }
#else
    auto* q = reinterpret_cast<uint64_t*>(p);
    for (size_t i = 0; i < n / 8; i++) q[i] = value;
#endif
}

enum RamMode { kRead = BC_RAM_READ, kWrite = BC_RAM_WRITE, kCopy = BC_RAM_COPY, kLatency = BC_RAM_LATENCY };

/** Every thread streams over its own part of the buffer until the slice ends; returns bytes per second. */
double ramBandwidth(Ram& r, int mode, double secs) {
    const size_t part = (r.bytes / r.threads) & ~size_t(4095);
    std::atomic<int> ready{0};
    std::atomic<bool> go{false};
    std::vector<uint64_t> moved(r.threads, 0);
    std::vector<Clock::time_point> ends(r.threads);
    Clock::time_point start;
    std::vector<std::thread> workers;
    for (int t = 0; t < r.threads; t++) {
        workers.emplace_back([&, t] {
            uint8_t* base = r.buf + part * t;
            ready++;
            while (!go.load(std::memory_order_acquire)) {}
            const auto deadline = start + std::chrono::duration<double>(secs);
            uint64_t n = 0, acc = 0, pass = 0;
            do {
                switch (mode) {
                    case kRead: acc += readPass(base, part); n += part; break;
                    case kWrite: writePass(base, part, ++pass); n += part; break;
                    default: std::memcpy(base + part / 2, base, part / 2); n += part / 2; break;
                }
            } while (Clock::now() < deadline);
            ends[t] = Clock::now();
            moved[t] = n;
            sink += acc;
        });
    }
    while (ready.load() < r.threads) {}
    start = Clock::now();
    go.store(true, std::memory_order_release);
    for (auto& w : workers) w.join();
    Clock::time_point last = start;
    uint64_t total = 0;
    for (int t = 0; t < r.threads; t++) { total += moved[t]; if (ends[t] > last) last = ends[t]; }
    return total / seconds(start, last);
}

/** One random cycle through the whole buffer, a pointer every kChaseStride bytes. */
void buildChase(Ram& r) {
    const size_t n = r.bytes / kChaseStride;
    std::vector<uint32_t> order(n);
    for (size_t i = 0; i < n; i++) order[i] = uint32_t(i);
    uint64_t s = 0x9E3779B97F4A7C15ull;
    for (size_t i = n - 1; i > 0; i--) std::swap(order[i], order[xorshift(s) % (i + 1)]);
    for (size_t i = 0; i < n; i++) {
        auto* node = reinterpret_cast<void**>(r.buf + size_t(order[i]) * kChaseStride);
        *node = r.buf + size_t(order[(i + 1) % n]) * kChaseStride;
    }
    r.chaseAt = r.buf + size_t(order[0]) * kChaseStride;
}

/** Follows the chain on one thread; returns nanoseconds per hop. */
double ramLatency(Ram& r, double secs) {
    if (!r.chaseAt) buildChase(r);
    void* p = r.chaseAt;
    uint64_t hops = 0;
    const auto start = Clock::now();
    const auto deadline = start + std::chrono::duration<double>(secs);
    do {
        for (int i = 0; i < 4096; i++) {
            p = *static_cast<void**>(p); p = *static_cast<void**>(p); p = *static_cast<void**>(p); p = *static_cast<void**>(p);
        }
        hops += 4096 * 4;
    } while (Clock::now() < deadline);
    const double el = seconds(start, Clock::now());
    r.chaseAt = p;
    return el * 1e9 / hops;
}

// ── Storage ─────────────────────────────────────────────────────────────────

constexpr size_t kSeqBlock = 4 << 20;   // large-file transfers
constexpr size_t kRandBlock = 4096;     // what app launches and databases read and write
constexpr int kMaxRandThreads = 8;

#if defined(_WIN32)
using File = HANDLE;
const File kNoFile = INVALID_HANDLE_VALUE;
#else
using File = int;
const File kNoFile = -1;
#endif

struct Disk {
    File fd = kNoFile;
    bool direct = false;   // transfers bypass the page cache and reach the flash (checked on Android: see reallyDirect)
    bool probed = false;
    size_t size = 0;
    std::string path;
    uint8_t* seqBuf = nullptr;
    uint8_t* randBuf = nullptr;
    size_t seqAt = 0;
    int seqPasses = 0;     // completed passes over the whole file (sequential write)
    bool readFromStart = true;
};


uint8_t* alignedBuffer(size_t bytes) {
#if defined(_WIN32)
    auto* b = static_cast<uint8_t*>(_aligned_malloc(bytes, 4096));
    if (!b) return nullptr;
#else
    void* p = nullptr;
    if (posix_memalign(&p, 4096, bytes) != 0) return nullptr;
    auto* b = static_cast<uint8_t*>(p);
#endif
    uint64_t s = 0x2545F4914F6CDD1Dull;
    for (size_t i = 0; i < bytes; i += 8) { uint64_t v = xorshift(s); std::memcpy(b + i, &v, 8); }   // incompressible
    return b;
}

void freeBuffer(uint8_t* b) {
#if defined(_WIN32)
    _aligned_free(b);
#else
    free(b);
#endif
}

#if defined(_WIN32)
// Windows: the file is opened unbuffered (FILE_FLAG_NO_BUFFERING — no cache at all, which Windows always honours)
// and overlapped, so the random tests' threads really have several requests out at once (a synchronous handle
// would queue them one by one). Each transfer waits for its own completion on the thread's event.

HANDLE threadEvent() {
    thread_local struct Event {
        HANDLE h = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        ~Event() { if (h) CloseHandle(h); }
    } ev;
    return ev.h;
}

bool transfer(File f, bool write, void* buf, size_t n, uint64_t at) {
    OVERLAPPED o{};
    o.Offset = DWORD(at);
    o.OffsetHigh = DWORD(at >> 32);
    o.hEvent = threadEvent();
    DWORD done = 0;
    const BOOL ok = write ? WriteFile(f, buf, DWORD(n), nullptr, &o) : ReadFile(f, buf, DWORD(n), nullptr, &o);
    if (!ok && GetLastError() != ERROR_IO_PENDING) return false;
    return GetOverlappedResult(f, &o, &done, TRUE) && done == n;
}

ssize_t pread(File f, void* buf, size_t n, off_t at) { return transfer(f, false, buf, n, uint64_t(at)) ? ssize_t(n) : -1; }
ssize_t pwrite(File f, const void* buf, size_t n, off_t at) { return transfer(f, true, const_cast<void*>(buf), n, uint64_t(at)) ? ssize_t(n) : -1; }

int syncData(File f) { return FlushFileBuffers(f) ? 0 : -1; }

std::wstring widePath(const char* utf8) {
    const int n = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, nullptr, 0);
    std::wstring w(n > 0 ? size_t(n) : 0, L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, utf8, -1, &w[0], n);
    if (!w.empty()) w.pop_back();
    return w;
}
#else
int syncData(int fd) {
#if defined(__APPLE__)
    return fsync(fd);
#else
    return fdatasync(fd);
#endif
}
#endif

// Without direct I/O reads would come from RAM and writes would only reach RAM. Linux (Android) can drop a
// file's cached pages and push a range to the flash; Darwin can't do either per range, but F_NOCACHE (set at
// open) keeps the file out of the cache in the first place.

/** Drop the file's cached pages before reading. */
void evict(Disk& d) {
    if (d.direct) return;
    syncData(d.fd);
#if defined(__linux__)
    posix_fadvise(d.fd, 0, 0, POSIX_FADV_DONTNEED);
#endif
}

/** Send this block to the flash and wait, as O_DIRECT does (sync_file_range on Android 8+; else a data sync). */
void reach(Disk& d, off_t at, size_t bytes) {
    if (d.direct) return;
#if defined(__linux__)
    using SyncRange = int (*)(int, off64_t, off64_t, unsigned int);
    static const auto syncRange = reinterpret_cast<SyncRange>(dlsym(RTLD_DEFAULT, "sync_file_range"));
    if (syncRange) { syncRange(d.fd, at, off64_t(bytes), SYNC_FILE_RANGE_WAIT_BEFORE | SYNC_FILE_RANGE_WRITE | SYNC_FILE_RANGE_WAIT_AFTER); return; }
#else
    (void)at; (void)bytes;
#endif
    syncData(d.fd);
}

/** Drop each block right after reading it, so no later read finds it in RAM. */
void forget(Disk& d, off_t at, size_t bytes) {
#if defined(__linux__)
    if (!d.direct) posix_fadvise(d.fd, at, off_t(bytes), POSIX_FADV_DONTNEED);
#else
    (void)d; (void)at; (void)bytes;
#endif
}

/**
 * The kernel can accept O_DIRECT and still go through the page cache (an encrypted file system without inline
 * encryption hardware does). Reading a block twice tells: the second read answered from RAM takes a few µs, while
 * no flash returns 4 KB in under ~12 µs.
 */
bool reallyDirect(Disk& d) {
    const size_t blocks = d.size / kRandBlock;
    uint64_t s = 0x51ED270B27F5AB1Bull;
    std::vector<double> again;
    for (int i = 0; i < 15; i++) {
        const off_t at = off_t((xorshift(s) % blocks) * kRandBlock);
        if (pread(d.fd, d.randBuf, kRandBlock, at) != ssize_t(kRandBlock)) return false;
        const auto t0 = Clock::now();
        if (pread(d.fd, d.randBuf, kRandBlock, at) != ssize_t(kRandBlock)) return false;
        again.push_back(seconds(t0, Clock::now()) * 1e6);
    }
    std::nth_element(again.begin(), again.begin() + 7, again.end());
    return again[7] > 12.0;
}

bool fail(const char* what) {
#if defined(_WIN32)
    const DWORD code = GetLastError();
    char msg[256] = "";
    FormatMessageA(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, nullptr, code, 0, msg, sizeof msg, nullptr);
    for (char* c = msg; *c; c++) if (*c == '\r' || *c == '\n') { *c = 0; break; }
    lastError = std::string(what) + ": " + (msg[0] ? msg : ("error " + std::to_string(code)).c_str());
#else
    lastError = std::string(what) + ": " + std::strerror(errno);
#endif
    return false;
}

/** Sequential slice: 4 MB transfers from where the last one stopped, wrapping at the end of the file. */
double diskSequential(Disk& d, bool write, double secs) {
    if (!write && d.readFromStart) {
        if (!d.probed) { d.direct = d.direct && reallyDirect(d); d.probed = true; }
        evict(d);
#if defined(__linux__)
        if (!d.direct) posix_fadvise(d.fd, 0, 0, POSIX_FADV_SEQUENTIAL);
#endif
        d.seqAt = 0;
        d.readFromStart = false;
    }
    const auto start = Clock::now();
    const auto deadline = start + std::chrono::duration<double>(secs);
    uint64_t moved = 0;
    do {
        const ssize_t n = write ? pwrite(d.fd, d.seqBuf, kSeqBlock, off_t(d.seqAt))
                                : pread(d.fd, d.seqBuf, kSeqBlock, off_t(d.seqAt));
        if (n != ssize_t(kSeqBlock)) { if (n >= 0) errno = EIO; fail(write ? "write" : "read"); return -1; }
        if (!write) forget(d, off_t(d.seqAt), kSeqBlock);
        moved += kSeqBlock;
        d.seqAt += kSeqBlock;
        if (d.seqAt >= d.size) {
            d.seqAt = 0;
            if (write) d.seqPasses++; else evict(d);
        }
    } while (Clock::now() < deadline);
    if (write && syncData(d.fd) != 0) { fail("fsync"); return -1; }   // what was written is on the flash
    if (write) d.readFromStart = true;
    return moved / seconds(start, Clock::now());
}

/** Random 4 KB slice from several threads at once; returns operations per second. */
double diskRandom(Disk& d, bool write, int threads, double secs) {
    if (!write) {
        evict(d);
#if defined(__linux__)
        if (!d.direct) posix_fadvise(d.fd, 0, 0, POSIX_FADV_RANDOM);   // no readahead either
#endif
    }
    const size_t blocks = d.size / kRandBlock;
    std::atomic<bool> failed{false};
    std::vector<uint64_t> ops(threads, 0);
    const auto start = Clock::now();
    const auto deadline = start + std::chrono::duration<double>(secs);
    std::vector<std::thread> workers;
    for (int t = 0; t < threads; t++) {
        workers.emplace_back([&, t] {
            uint8_t* buf = d.randBuf + size_t(t) * kRandBlock;
            uint64_t s = 0x9E3779B97F4A7C15ull * (t + 1) + uint64_t(d.seqPasses);
            uint64_t n = 0;
            do {
                for (int i = 0; i < 16; i++) {
                    const off_t at = off_t((xorshift(s) % blocks) * kRandBlock);
                    const ssize_t r = write ? pwrite(d.fd, buf, kRandBlock, at) : pread(d.fd, buf, kRandBlock, at);
                    if (r != ssize_t(kRandBlock)) { failed = true; return; }
                    if (write) reach(d, at, kRandBlock); else forget(d, at, kRandBlock);
                }
                n += 16;
            } while (Clock::now() < deadline);
            ops[t] = n;
        });
    }
    for (auto& w : workers) w.join();
    if (failed) { errno = EIO; fail(write ? "random write" : "random read"); return -1; }
    if (write && syncData(d.fd) != 0) { fail("fsync"); return -1; }
    uint64_t total = 0;
    for (auto n : ops) total += n;
    return total / seconds(start, Clock::now());
}

}  // namespace

// ── C API (bench_core.h) ──────────────────────────────────────────────────────

struct bc_ram : Ram {};
struct bc_disk : Disk {};

extern "C" {

const char* bc_mem_error(void) { return lastError.c_str(); }

bc_ram* bc_ram_open(int64_t bytes, int threads) {
    auto* r = new bc_ram();
    r->bytes = size_t(bytes) & ~size_t(4095);
    r->threads = threads < 1 ? 1 : threads;
#if defined(_WIN32)
    void* p = VirtualAlloc(nullptr, r->bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!p) { fail("VirtualAlloc"); delete r; return nullptr; }
#else
    void* p = mmap(nullptr, r->bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (p == MAP_FAILED) { fail("mmap"); delete r; return nullptr; }
#endif
    r->buf = static_cast<uint8_t*>(p);
    std::memset(r->buf, 1, r->bytes);   // fault every page in now, not while timing
    return r;
}

double bc_ram_run(bc_ram* r, int mode, double secs) {
    return mode == BC_RAM_LATENCY ? ramLatency(*r, secs) : ramBandwidth(*r, mode, secs);
}

void bc_ram_close(bc_ram* r) {
#if defined(_WIN32)
    if (r->buf) VirtualFree(r->buf, 0, MEM_RELEASE);
#else
    if (r->buf) munmap(r->buf, r->bytes);
#endif
    delete r;
}

bc_disk* bc_disk_open(const char* path, int64_t bytes) {
    auto* d = new bc_disk();
    d->path = path;
    d->size = size_t(bytes) / kSeqBlock * kSeqBlock;
#if defined(_WIN32)
    d->fd = CreateFileW(widePath(path).c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                        FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_NO_BUFFERING | FILE_FLAG_OVERLAPPED, nullptr);
    d->direct = d->fd != kNoFile;
    d->probed = true;   // unbuffered I/O on Windows never goes through the cache: nothing to check
#elif defined(__APPLE__)
    d->fd = open(path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    d->direct = d->fd >= 0 && fcntl(d->fd, F_NOCACHE, 1) == 0;   // Darwin's way past the cache
#else
    d->fd = open(path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC | O_DIRECT, 0600);
    d->direct = d->fd >= 0;
    if (d->fd < 0 && errno == EINVAL) d->fd = open(path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
#endif
    d->seqBuf = alignedBuffer(kSeqBlock);
    d->randBuf = alignedBuffer(kRandBlock * kMaxRandThreads);
    if (d->fd == kNoFile || !d->seqBuf || !d->randBuf) {
        fail("open");
#if defined(_WIN32)
        if (d->fd != kNoFile) { CloseHandle(d->fd); DeleteFileW(widePath(path).c_str()); }
#else
        if (d->fd >= 0) { close(d->fd); unlink(d->path.c_str()); }
#endif
        freeBuffer(d->seqBuf); freeBuffer(d->randBuf); delete d;
        return nullptr;
    }
    return d;
}

int bc_disk_direct(const bc_disk* d) { return d->direct ? 1 : 0; }

int bc_disk_passes(const bc_disk* d) { return d->seqPasses; }

double bc_disk_run(bc_disk* d, int mode, int threads, double secs) {
    const int t = threads < 1 ? 1 : threads > kMaxRandThreads ? kMaxRandThreads : threads;
    switch (mode) {
        case BC_DISK_SEQ_WRITE: return diskSequential(*d, true, secs);
        case BC_DISK_SEQ_READ: return diskSequential(*d, false, secs);
        case BC_DISK_RANDOM_READ: return diskRandom(*d, false, t, secs);
        default: return diskRandom(*d, true, t, secs);
    }
}

void bc_disk_close(bc_disk* d) {
#if defined(_WIN32)
    CloseHandle(d->fd);
    DeleteFileW(widePath(d->path.c_str()).c_str());
#else
    close(d->fd);
    unlink(d->path.c_str());
#endif
    freeBuffer(d->seqBuf);
    freeBuffer(d->randBuf);
    delete d;
}

}
