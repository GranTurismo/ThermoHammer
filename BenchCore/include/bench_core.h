// BenchCore — the CPU and MEM tests and the QR scanning test of the ThermoHammer benchmark, one C++ implementation
// for Android (JNI: engine/cpu/CpuNative.kt, engine/mem/MemNative.kt, engine/ux/QrNative.kt) and iOS (Swift), so
// every phone does exactly the same work.
//
// Each kernel does a fixed unit of work per call and returns a checksum; the runner compares every call with
// the first, so a kernel can't get faster by getting wrong. All inputs come from fixed seeds.
#ifndef BENCH_CORE_H
#define BENCH_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Kernel ids. */
enum {
    BC_MATMUL = 0,      // 96×96 double matrix multiply
    BC_FFT,             // 4096-point complex FFT, forward and back
    BC_MANDELBROT,      // 200×120, 256 iterations
    BC_SIEVE,           // primes below 2,000,000
    BC_RSA,             // RSA-1024 encrypt + decrypt (Montgomery)
    BC_NBODY,           // 64 bodies, 8 steps
    BC_JSON,            // a 200-post social feed: encode, parse, map back
    BC_XML,             // the same feed as XML: write, SAX-parse
    BC_ZLIB,            // 256 KB of text: deflate (level 6) + inflate
    BC_AES_GCM,         // 1 MB: AES-256-GCM seal + open
    BC_SHA256,          // 1 MB: SHA-256
    BC_KERNEL_COUNT
};

typedef struct bc_kernel bc_kernel;

/** e.g. "MATRIX MULTIPLY"; null for an unknown id. */
const char* bc_kernel_name(int id);

/** Builds the kernel's inputs; one instance per thread. Null for an unknown id. */
bc_kernel* bc_kernel_create(int id);

/** One unit of work; returns its checksum. *ok is set to 0 when the kernel's own check failed. */
int64_t bc_kernel_run(bc_kernel* kernel, int* ok);

/** Why the last run failed its check ("" if it didn't). */
const char* bc_kernel_error(const bc_kernel* kernel);

void bc_kernel_destroy(bc_kernel* kernel);

/** "ARMv8 crypto" when AES-GCM and SHA-256 use the CPU's crypto instructions, "portable" otherwise. */
const char* bc_crypto_path(void);

// ── MEM tests: RAM and storage, measured in slices so the stage can show a live rate ──

enum { BC_RAM_READ = 0, BC_RAM_WRITE, BC_RAM_COPY, BC_RAM_LATENCY };
enum { BC_DISK_SEQ_WRITE = 0, BC_DISK_SEQ_READ, BC_DISK_RANDOM_READ, BC_DISK_RANDOM_WRITE };

typedef struct bc_ram bc_ram;
typedef struct bc_disk bc_disk;

/** Why the last open or run on this thread failed. */
const char* bc_mem_error(void);

/** A buffer of `bytes` with every page already touched; null if it can't be allocated. */
bc_ram* bc_ram_open(int64_t bytes, int threads);
/** Bytes per second (READ, WRITE, COPY — all threads together) or nanoseconds per random access (LATENCY). */
double bc_ram_run(bc_ram* ram, int mode, double seconds);
void bc_ram_close(bc_ram* ram);

/** Creates the test file, past the page cache where the file system allows it; null on failure. */
bc_disk* bc_disk_open(const char* path, int64_t bytes);
/** Reads really bypass the page cache — checked when reading starts (direct I/O can be accepted and not honoured). */
int bc_disk_direct(const bc_disk* disk);
/** Whole passes over the file the sequential write has completed. */
int bc_disk_passes(const bc_disk* disk);
/** Bytes per second (sequential, 4 MB blocks) or 4 KB operations per second (random); negative on an I/O error. */
double bc_disk_run(bc_disk* disk, int mode, int threads, double seconds);
/** Closes and deletes the test file. */
void bc_disk_close(bc_disk* disk);

// ── UX test 6, QR scanning: camera-like frames of QR codes, read by zxing-cpp (qr.cpp) ──

enum { BC_QR_W = 1280, BC_QR_H = 720, BC_QR_FRAMES = 32, BC_QR_SEED = 7 };

typedef struct bc_qr bc_qr;

/** Renders `count` frames from `seed`, each checked to decode; null if they can't be made. */
bc_qr* bc_qr_create(int count, int seed);
int bc_qr_count(const bc_qr* qr);
/** Frame `index`: BC_QR_W × BC_QR_H bytes of luminance. */
const uint8_t* bc_qr_luma(const bc_qr* qr, int index);
/** The text frame `index` encodes. */
const char* bc_qr_text(const bc_qr* qr, int index);
/** Where the code was drawn in frame `index`: x, y of its 4 corners. */
void bc_qr_corners(const bc_qr* qr, int index, float corners[8]);
/** Scans frame `index` as a scanner app would (one thread): 1 if it read back the frame's own text. Where the code
 *  was found (x, y of 4 corners) goes to `found` when it isn't null. Not thread-safe: one handle per thread. */
int bc_qr_scan(bc_qr* qr, int index, float found[8]);
void bc_qr_destroy(bc_qr* qr);

#ifdef __cplusplus
}
#endif

#endif
