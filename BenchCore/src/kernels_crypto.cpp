// CPU Test 3 (COMPRESSION & CRYPTO) kernels: zlib, AES-256-GCM and SHA-256. AES-GCM and SHA-256 use the CPU's
// crypto instructions where it has them (as the system libraries on Android, iOS and Windows do): ARMv8 crypto,
// or AES-NI, PCLMULQDQ and SHA-NI on x86; portable code otherwise. Tests check both paths against each other and
// against published vectors.
#include <cstring>
#include <vector>
#include <zlib.h>

#if defined(__ANDROID__) && defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif
#if defined(_WIN32) && defined(__aarch64__)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#ifndef PF_ARM_V8_CRYPTO_INSTRUCTIONS_AVAILABLE
#define PF_ARM_V8_CRYPTO_INSTRUCTIONS_AVAILABLE 30
#endif
#endif

#include "core.hpp"

namespace bc {

#if defined(__x86_64__) || defined(_M_X64)
#define BC_X86 1
// The x86 paths can't be run where they're built (a Mac: Rosetta has no SHA-NI), so each must reproduce a
// published answer on the CPU before it's used — GCM test case 14, SHA-256("abc").
bool gcmKnownAnswer();
bool shaKnownAnswer();
#endif

bool cryptoHw() {
    static const bool hw = [] {
#if defined(BC_X86)
        return x86CryptoCompiled() && x86AesSupported() && gcmKnownAnswer();
#else
        if (!armCryptoCompiled()) return false;
#if defined(__APPLE__)
        return true;   // every 64-bit Apple chip has the ARMv8 crypto extension
#elif defined(__ANDROID__) && defined(__aarch64__)
        const unsigned long caps = getauxval(AT_HWCAP);
        return (caps & HWCAP_AES) && (caps & HWCAP_PMULL) && (caps & HWCAP_SHA2);
#elif defined(_WIN32) && defined(__aarch64__)
        return IsProcessorFeaturePresent(PF_ARM_V8_CRYPTO_INSTRUCTIONS_AVAILABLE) != 0;
#else
        return false;
#endif
#endif
    }();
    return hw;
}

bool shaHw() {
#if defined(BC_X86)
    static const bool hw = x86CryptoCompiled() && x86ShaSupported() && shaKnownAnswer();
    return hw;
#else
    return cryptoHw();
#endif
}

namespace {

// the instruction path of this CPU architecture
void aesCtrHw(const AesKey& k, const uint8_t j0[16], const uint8_t* in, size_t len, uint8_t* out) {
#if defined(BC_X86)
    aesCtrX86(k, j0, in, len, out);
#else
    aesCtrArm(k, j0, in, len, out);
#endif
}

void ghashHw(const uint8_t h[16], const uint8_t* data, size_t len, uint8_t y[16]) {
#if defined(BC_X86)
    ghashX86(h, data, len, y);
#else
    ghashArm(h, data, len, y);
#endif
}

void sha256BlocksHw(uint32_t st[8], const uint8_t* data, size_t blocks) {
#if defined(BC_X86)
    sha256BlocksX86(st, data, blocks);
#else
    sha256BlocksArm(st, data, blocks);
#endif
}


// ── AES-256, portable ───────────────────────────────────────────────────────

const uint8_t kSbox[256] = {
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16};

uint8_t xtime(uint8_t x) { return uint8_t((x << 1) ^ ((x >> 7) * 0x1b)); }

void aesBlockSw(const AesKey& k, const uint8_t in[16], uint8_t out[16]) {
    uint8_t s[16];
    for (int i = 0; i < 16; i++) s[i] = in[i] ^ k.rk[0][i];
    for (int round = 1; round <= 14; round++) {
        uint8_t t[16];
        for (int i = 0; i < 16; i++) t[i] = kSbox[s[(i + 4 * (i % 4)) % 16]];   // SubBytes + ShiftRows
        if (round < 14) {
            for (int c = 0; c < 4; c++) {   // MixColumns
                uint8_t* col = t + 4 * c;
                const uint8_t a0 = col[0], a1 = col[1], a2 = col[2], a3 = col[3], all = a0 ^ a1 ^ a2 ^ a3;
                col[0] ^= all ^ xtime(a0 ^ a1);
                col[1] ^= all ^ xtime(a1 ^ a2);
                col[2] ^= all ^ xtime(a2 ^ a3);
                col[3] ^= all ^ xtime(a3 ^ a0);
            }
        }
        for (int i = 0; i < 16; i++) s[i] = t[i] ^ k.rk[round][i];
    }
    std::memcpy(out, s, 16);
}

void inc32(uint8_t ctr[16]) {
    for (int i = 15; i >= 12; i--) if (++ctr[i]) break;
}

void aesCtrSw(const AesKey& k, const uint8_t j0[16], const uint8_t* in, size_t len, uint8_t* out) {
    uint8_t ctr[16], ks[16];
    std::memcpy(ctr, j0, 16);
    for (size_t off = 0; off < len; off += 16) {
        inc32(ctr);
        aesBlockSw(k, ctr, ks);
        const size_t n = len - off < 16 ? len - off : 16;
        for (size_t i = 0; i < n; i++) out[off + i] = in[off + i] ^ ks[i];
    }
}

// ── GHASH, portable: the same bit-reflected arithmetic as the PMULL path ──

uint8_t rbit8(uint8_t b) {
    b = uint8_t((b & 0xF0) >> 4 | (b & 0x0F) << 4);
    b = uint8_t((b & 0xCC) >> 2 | (b & 0x33) << 2);
    return uint8_t((b & 0xAA) >> 1 | (b & 0x55) << 1);
}

struct U128 { uint64_t lo, hi; };

/** A GCM block → a 128-bit integer whose bit i is the coefficient of x^i. */
U128 reflect(const uint8_t b[16]) {
    U128 r{0, 0};
    for (int i = 0; i < 8; i++) r.lo |= uint64_t(rbit8(b[i])) << (8 * i);
    for (int i = 0; i < 8; i++) r.hi |= uint64_t(rbit8(b[8 + i])) << (8 * i);
    return r;
}

void unreflect(U128 v, uint8_t out[16]) {
    for (int i = 0; i < 8; i++) out[i] = rbit8(uint8_t(v.lo >> (8 * i)));
    for (int i = 0; i < 8; i++) out[8 + i] = rbit8(uint8_t(v.hi >> (8 * i)));
}

U128 clmul(uint64_t a, uint64_t b) {
    U128 r{0, 0};
    for (int i = 0; i < 64; i++) {
        if ((b >> i) & 1) {
            r.lo ^= a << i;
            if (i) r.hi ^= a >> (64 - i);
        }
    }
    return r;
}

/** a·b in GF(2^128) mod x^128 + x^7 + x^2 + x + 1. */
U128 gfmul(U128 a, U128 b) {
    const U128 lo = clmul(a.lo, b.lo), hi = clmul(a.hi, b.hi);
    const U128 m1 = clmul(a.lo, b.hi), m2 = clmul(a.hi, b.lo);
    uint64_t p0 = lo.lo, p1 = lo.hi ^ m1.lo ^ m2.lo, p2 = hi.lo ^ m1.hi ^ m2.hi, p3 = hi.hi;
    const U128 t = clmul(p3, 0x87);   // x^192 ≡ x^64·(x^7 + x^2 + x + 1)
    p1 ^= t.lo; p2 ^= t.hi;
    const U128 u = clmul(p2, 0x87);   // x^128 ≡ x^7 + x^2 + x + 1
    p0 ^= u.lo; p1 ^= u.hi;
    return {p0, p1};
}

void ghashSw(const uint8_t h[16], const uint8_t* data, size_t len, uint8_t y[16]) {
    const U128 hr = reflect(h);
    U128 acc = reflect(y);
    for (size_t off = 0; off < len; off += 16) {
        uint8_t block[16] = {};
        std::memcpy(block, data + off, len - off < 16 ? len - off : 16);
        const U128 x = reflect(block);
        acc = gfmul({acc.lo ^ x.lo, acc.hi ^ x.hi}, hr);
    }
    unreflect(acc, y);
}

void gcmTag(const AesKey& k, const uint8_t j0[16], const uint8_t* cipher, size_t len, uint8_t tag[16], bool hw) {
    uint8_t h[16] = {}, zero[16] = {};
    aesBlockSw(k, zero, h);
    uint8_t y[16] = {};
    if (hw) ghashHw(h, cipher, len, y); else ghashSw(h, cipher, len, y);
    uint8_t lens[16] = {};
    const uint64_t bits = uint64_t(len) * 8;
    for (int i = 0; i < 8; i++) lens[15 - i] = uint8_t(bits >> (8 * i));   // len(A) = 0, then len(C)
    if (hw) ghashHw(h, lens, 16, y); else ghashSw(h, lens, 16, y);
    uint8_t ek[16];
    aesBlockSw(k, j0, ek);
    for (int i = 0; i < 16; i++) tag[i] = y[i] ^ ek[i];
}

// ── SHA-256, portable ───────────────────────────────────────────────────────

}  // namespace

const uint32_t kSha256K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

namespace {

uint32_t ror(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

void sha256BlocksSw(uint32_t st[8], const uint8_t* data, size_t blocks) {
    for (size_t b = 0; b < blocks; b++, data += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; i++) {
            w[i] = uint32_t(data[4 * i]) << 24 | uint32_t(data[4 * i + 1]) << 16 | uint32_t(data[4 * i + 2]) << 8 | data[4 * i + 3];
        }
        for (int i = 16; i < 64; i++) {
            const uint32_t s0 = ror(w[i - 15], 7) ^ ror(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const uint32_t s1 = ror(w[i - 2], 17) ^ ror(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = st[0], bb = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
        for (int i = 0; i < 64; i++) {
            const uint32_t t1 = h + (ror(e, 6) ^ ror(e, 11) ^ ror(e, 25)) + ((e & f) ^ (~e & g)) + kSha256K[i] + w[i];
            const uint32_t t2 = (ror(a, 2) ^ ror(a, 13) ^ ror(a, 22)) + ((a & bb) ^ (a & c) ^ (bb & c));
            h = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2;
        }
        st[0] += a; st[1] += bb; st[2] += c; st[3] += d; st[4] += e; st[5] += f; st[6] += g; st[7] += h;
    }
}

// ── the kernels ─────────────────────────────────────────────────────────────

struct Zlib final : bc_kernel {
    std::vector<uint8_t> input = corpus(256 * 1024);
    std::vector<uint8_t> packed = std::vector<uint8_t>(input.size() + 1024), unpacked = std::vector<uint8_t>(input.size());
    z_stream d{}, inf{};   // one stream each, reset per call: times compression, not zlib's setup
    bool ready = false;
    Zlib() { ready = deflateInit(&d, 6) == Z_OK && inflateInit(&inf) == Z_OK; }
    ~Zlib() override { deflateEnd(&d); inflateEnd(&inf); }
    int64_t run() override {
        if (!ready) return fail("zlib init");
        deflateReset(&d);
        d.next_in = input.data(); d.avail_in = uInt(input.size());
        d.next_out = packed.data(); d.avail_out = uInt(packed.size());
        if (deflate(&d, Z_FINISH) != Z_STREAM_END) return fail("deflate");
        const size_t n = packed.size() - d.avail_out;
        inflateReset(&inf);
        inf.next_in = packed.data(); inf.avail_in = uInt(n);
        inf.next_out = unpacked.data(); inf.avail_out = uInt(unpacked.size());
        if (inflate(&inf, Z_FINISH) != Z_STREAM_END || unpacked.size() - inf.avail_out != input.size() ||
            unpacked[input.size() / 2] != input[input.size() / 2]) {
            return fail("zlib round trip");
        }
        return int64_t(n);
    }
};

struct AesGcm final : bc_kernel {
    AesKey key{};
    std::vector<uint8_t> data = corpus(1 << 20);
    std::vector<uint8_t> sealed = std::vector<uint8_t>(data.size()), opened = std::vector<uint8_t>(data.size());
    uint64_t counter = 0;
    AesGcm() {
        uint8_t k[32];
        for (int i = 0; i < 32; i++) k[i] = uint8_t(i * 7 + 3);
        aesExpand(k, key);
    }
    int64_t run() override {
        uint8_t iv[12] = {}, tag[16];
        counter++;
        for (int i = 0; i < 8; i++) iv[i] = uint8_t(counter >> (8 * i));   // a fresh nonce per message
        const bool hw = cryptoHw();
        gcmSeal(key, iv, data.data(), data.size(), sealed.data(), tag, hw);
        if (!gcmOpen(key, iv, sealed.data(), sealed.size(), tag, opened.data(), hw) || opened[12345] != data[12345]) {
            return fail("AES-GCM round trip");
        }
        return int64_t(opened.size()) + opened[12345];   // the tag changes with every nonce; the plaintext doesn't
    }
};

struct Sha256 final : bc_kernel {
    std::vector<uint8_t> data = corpus(1 << 20);
    int64_t run() override {
        uint8_t d[32];
        sha256(data.data(), data.size(), d, shaHw());
        return int64_t(d[0]) << 8 | d[1];
    }
};

}  // namespace

void aesExpand(const uint8_t key[32], AesKey& out) {
    uint8_t w[240];
    std::memcpy(w, key, 32);
    uint8_t rcon = 1;
    for (int i = 32; i < 240; i += 4) {
        uint8_t t[4] = {w[i - 4], w[i - 3], w[i - 2], w[i - 1]};
        if (i % 32 == 0) {
            const uint8_t first = t[0];
            t[0] = uint8_t(kSbox[t[1]] ^ rcon); t[1] = kSbox[t[2]]; t[2] = kSbox[t[3]]; t[3] = kSbox[first];
            rcon = xtime(rcon);
        } else if (i % 32 == 16) {
            for (uint8_t& b : t) b = kSbox[b];
        }
        for (int j = 0; j < 4; j++) w[i + j] = w[i - 32 + j] ^ t[j];
    }
    std::memcpy(out.rk, w, 240);
}

void gcmSeal(const AesKey& k, const uint8_t iv[12], const uint8_t* in, size_t len, uint8_t* out, uint8_t tag[16], bool hw) {
    uint8_t j0[16] = {};
    std::memcpy(j0, iv, 12);
    j0[15] = 1;
    if (hw) aesCtrHw(k, j0, in, len, out); else aesCtrSw(k, j0, in, len, out);
    gcmTag(k, j0, out, len, tag, hw);
}

bool gcmOpen(const AesKey& k, const uint8_t iv[12], const uint8_t* in, size_t len, const uint8_t tag[16], uint8_t* out, bool hw) {
    uint8_t j0[16] = {}, expect[16];
    std::memcpy(j0, iv, 12);
    j0[15] = 1;
    gcmTag(k, j0, in, len, expect, hw);
    uint8_t diff = 0;
    for (int i = 0; i < 16; i++) diff |= uint8_t(expect[i] ^ tag[i]);
    if (diff) return false;
    if (hw) aesCtrHw(k, j0, in, len, out); else aesCtrSw(k, j0, in, len, out);
    return true;
}

void sha256(const uint8_t* data, size_t len, uint8_t digest[32], bool hw) {
    uint32_t st[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    const size_t full = len / 64;
    if (hw) sha256BlocksHw(st, data, full); else sha256BlocksSw(st, data, full);
    uint8_t tail[128] = {};
    const size_t rest = len - full * 64;
    std::memcpy(tail, data + full * 64, rest);
    tail[rest] = 0x80;
    const size_t tailBlocks = rest + 9 > 64 ? 2 : 1;
    const uint64_t bits = uint64_t(len) * 8;
    for (int i = 0; i < 8; i++) tail[tailBlocks * 64 - 1 - i] = uint8_t(bits >> (8 * i));
    if (hw) sha256BlocksHw(st, tail, tailBlocks); else sha256BlocksSw(st, tail, tailBlocks);
    for (int i = 0; i < 8; i++) {
        digest[4 * i] = uint8_t(st[i] >> 24); digest[4 * i + 1] = uint8_t(st[i] >> 16);
        digest[4 * i + 2] = uint8_t(st[i] >> 8); digest[4 * i + 3] = uint8_t(st[i]);
    }
}

#if defined(BC_X86)
bool gcmKnownAnswer() {
    static const uint8_t ct14[16] = {0xce, 0xa7, 0x40, 0x3d, 0x4d, 0x60, 0x6b, 0x6e, 0x07, 0x4e, 0xc5, 0xd3, 0xba, 0xf3, 0x9d, 0x18};
    static const uint8_t tag14[16] = {0xd0, 0xd1, 0xc8, 0xa7, 0x99, 0x99, 0x6b, 0xf0, 0x26, 0x5b, 0x98, 0xb5, 0xd4, 0x8a, 0xb9, 0x19};
    AesKey k{};
    const uint8_t key[32] = {}, iv[12] = {}, pt[16] = {};
    uint8_t ct[16], tag[16];
    aesExpand(key, k);
    gcmSeal(k, iv, pt, 16, ct, tag, true);
    return !std::memcmp(ct, ct14, 16) && !std::memcmp(tag, tag14, 16);
}

bool shaKnownAnswer() {
    static const uint8_t abc[32] = {0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
                                    0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad};
    uint8_t d[32];
    sha256(reinterpret_cast<const uint8_t*>("abc"), 3, d, true);
    return !std::memcmp(d, abc, 32);
}
#endif

bc_kernel* makeZlib() { return new Zlib(); }
bc_kernel* makeAesGcm() { return new AesGcm(); }
bc_kernel* makeSha256() { return new Sha256(); }

}  // namespace bc
