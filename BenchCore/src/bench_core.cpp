// The public C API of BenchCore (include/bench_core.h).
#include "core.hpp"

namespace {

struct Entry { const char* name; bc_kernel* (*make)(); };

const Entry kKernels[BC_KERNEL_COUNT] = {
    {"MATRIX MULTIPLY", bc::makeMatMul}, {"FFT", bc::makeFft}, {"MANDELBROT", bc::makeMandelbrot},
    {"PRIME SIEVE", bc::makeSieve}, {"RSA 1024", bc::makeRsa}, {"N-BODY", bc::makeNBody},
    {"JSON", bc::makeJson}, {"XML", bc::makeXml}, {"ZLIB", bc::makeZlib},
    {"AES-256-GCM", bc::makeAesGcm}, {"SHA-256", bc::makeSha256}};

}  // namespace

extern "C" {

const char* bc_kernel_name(int id) { return id >= 0 && id < BC_KERNEL_COUNT ? kKernels[id].name : nullptr; }

bc_kernel* bc_kernel_create(int id) { return id >= 0 && id < BC_KERNEL_COUNT ? kKernels[id].make() : nullptr; }

int64_t bc_kernel_run(bc_kernel* kernel, int* ok) {
    const int64_t v = kernel->run();
    if (ok) *ok = kernel->error.empty() ? 1 : 0;
    return v;
}

const char* bc_kernel_error(const bc_kernel* kernel) { return kernel->error.c_str(); }

void bc_kernel_destroy(bc_kernel* kernel) { delete kernel; }

int bc_qr_supported(void) {
#if defined(BC_QR_FMA)
    return __builtin_cpu_supports("fma") && __builtin_cpu_supports("avx") ? 1 : 0;
#else
    return 1;
#endif
}

const char* bc_crypto_path(void) {
#if defined(__x86_64__) || defined(_M_X64)
    return bc::cryptoHw() ? (bc::shaHw() ? "AES-NI + SHA-NI" : "AES-NI, portable SHA") : bc::shaHw() ? "SHA-NI, portable AES" : "portable";
#else
    return bc::cryptoHw() ? "ARMv8 crypto" : "portable";
#endif
}

}
