// Internals shared by the BenchCore kernels (not part of the public API).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "bench_core.h"

struct bc_kernel {
    virtual ~bc_kernel() = default;
    virtual int64_t run() = 0;
    std::string error;

    int64_t fail(const char* why) {
        if (error.empty()) error = why;
        return 0;
    }
};

namespace bc {

/** splitmix64 — the one seeded generator every kernel's inputs come from, identical on every platform. */
struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed) {}
    uint64_t next() {
        uint64_t z = (s += 0x9E3779B97F4A7C15ull);
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }
    int below(int n) { return int(next() % uint64_t(n)); }
    double unit() { return double(next() >> 11) * (1.0 / 9007199254740992.0); }
};

/** Text of the social feed's posts, `bytes` long — the input of zlib, AES-GCM and SHA-256. */
std::vector<uint8_t> corpus(size_t bytes);

bc_kernel* makeMatMul();
bc_kernel* makeFft();
bc_kernel* makeMandelbrot();
bc_kernel* makeSieve();
bc_kernel* makeRsa();
bc_kernel* makeNBody();
bc_kernel* makeJson();
bc_kernel* makeXml();
bc_kernel* makeZlib();
bc_kernel* makeAesGcm();
bc_kernel* makeSha256();

// ── crypto: the CPU's ARMv8 instructions where it has them, portable code otherwise ──

bool cryptoHw();   // AES, PMULL and SHA-256 instructions present

struct AesKey { uint8_t rk[15][16]; };   // AES-256 round keys
void aesExpand(const uint8_t key[32], AesKey& out);

/** AES-256-GCM with a 96-bit nonce and no associated data; `hw` picks the instruction path (tests use both). */
void gcmSeal(const AesKey& k, const uint8_t iv[12], const uint8_t* in, size_t len, uint8_t* out, uint8_t tag[16], bool hw);
bool gcmOpen(const AesKey& k, const uint8_t iv[12], const uint8_t* in, size_t len, const uint8_t tag[16], uint8_t* out, bool hw);

void sha256(const uint8_t* data, size_t len, uint8_t digest[32], bool hw);

extern const uint32_t kSha256K[64];

// ARMv8 kernels (crypto_arm.cpp — compiled with the crypto extension; never called without it)
void aesCtrArm(const AesKey& k, const uint8_t j0[16], const uint8_t* in, size_t len, uint8_t* out);
void ghashArm(const uint8_t h[16], const uint8_t* data, size_t len, uint8_t y[16]);
void sha256BlocksArm(uint32_t state[8], const uint8_t* data, size_t blocks);
bool armCryptoCompiled();

}  // namespace bc
