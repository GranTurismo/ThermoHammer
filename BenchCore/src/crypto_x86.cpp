// x86-64 crypto-instruction paths, the counterpart of crypto_arm.cpp for Windows PCs (and the Android emulator's
// x86_64 image): AES-CTR (AES-NI, four blocks in flight), GHASH (PCLMULQDQ) and SHA-256 (SHA-NI). Each function is
// compiled for its instructions alone (target attributes), so the file builds with the default x86-64 flags; the
// CPU's support is read with CPUID, and kernels_crypto.cpp also checks a published answer before using a path.
#include "core.hpp"

#if defined(__x86_64__) || defined(_M_X64)
#include <cpuid.h>
#include <immintrin.h>

#include <cstring>

namespace bc {

namespace {

void counterBlock(const uint8_t j0[16], uint32_t n, uint8_t out[16]) {
    std::memcpy(out, j0, 12);
    out[12] = uint8_t(n >> 24); out[13] = uint8_t(n >> 16); out[14] = uint8_t(n >> 8); out[15] = uint8_t(n);
}

__attribute__((target("aes,sse2"))) __m128i aesBlock(__m128i b, const __m128i* rk) {
    b = _mm_xor_si128(b, rk[0]);
    for (int r = 1; r < 14; r++) b = _mm_aesenc_si128(b, rk[r]);
    return _mm_aesenclast_si128(b, rk[14]);
}

/** a·b in GF(2^128) for GCM, on byte-reversed blocks (Intel's carry-less multiplication white paper, Fig. 5):
 *  the 256-bit product shifted left by one bit (the operands are bit-reflected), then reduced. */
__attribute__((target("pclmul,sse2"))) __m128i gfmul(__m128i a, __m128i b) {
    __m128i t3 = _mm_clmulepi64_si128(a, b, 0x00);
    __m128i t4 = _mm_clmulepi64_si128(a, b, 0x10);
    __m128i t5 = _mm_clmulepi64_si128(a, b, 0x01);
    __m128i t6 = _mm_clmulepi64_si128(a, b, 0x11);
    t4 = _mm_xor_si128(t4, t5);
    t5 = _mm_slli_si128(t4, 8);
    t4 = _mm_srli_si128(t4, 8);
    t3 = _mm_xor_si128(t3, t5);
    t6 = _mm_xor_si128(t6, t4);
    __m128i t7 = _mm_srli_epi32(t3, 31);
    __m128i t8 = _mm_srli_epi32(t6, 31);
    t3 = _mm_slli_epi32(t3, 1);
    t6 = _mm_slli_epi32(t6, 1);
    __m128i t9 = _mm_srli_si128(t7, 12);
    t8 = _mm_slli_si128(t8, 4);
    t7 = _mm_slli_si128(t7, 4);
    t3 = _mm_or_si128(t3, t7);
    t6 = _mm_or_si128(t6, t8);
    t6 = _mm_or_si128(t6, t9);
    t7 = _mm_slli_epi32(t3, 31);
    t8 = _mm_slli_epi32(t3, 30);
    t9 = _mm_slli_epi32(t3, 25);
    t7 = _mm_xor_si128(_mm_xor_si128(t7, t8), t9);
    t8 = _mm_srli_si128(t7, 4);
    t7 = _mm_slli_si128(t7, 12);
    t3 = _mm_xor_si128(t3, t7);
    __m128i t2 = _mm_srli_epi32(t3, 1);
    t4 = _mm_srli_epi32(t3, 2);
    t5 = _mm_srli_epi32(t3, 7);
    t2 = _mm_xor_si128(_mm_xor_si128(t2, t4), _mm_xor_si128(t5, t8));
    t3 = _mm_xor_si128(t3, t2);
    return _mm_xor_si128(t6, t3);
}

__attribute__((target("ssse3"))) __m128i reversed(const uint8_t* block) {
    return _mm_shuffle_epi8(_mm_loadu_si128(reinterpret_cast<const __m128i*>(block)),
                            _mm_set_epi8(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15));
}

}  // namespace

bool x86CryptoCompiled() { return true; }

bool x86AesSupported() {
    unsigned a, b, c, d;
    if (!__get_cpuid(1, &a, &b, &c, &d)) return false;
    return (c & bit_AES) && (c & bit_PCLMUL) && (c & bit_SSSE3) && (c & bit_SSE4_1);
}

bool x86ShaSupported() {
    unsigned a, b, c, d;
    if (!__get_cpuid(1, &a, &b, &c, &d) || !(c & bit_SSSE3) || !(c & bit_SSE4_1)) return false;
    if (__get_cpuid_max(0, nullptr) < 7) return false;
    __cpuid_count(7, 0, a, b, c, d);
    return (b & bit_SHA) != 0;
}

__attribute__((target("aes,sse2"))) void aesCtrX86(const AesKey& k, const uint8_t j0[16], const uint8_t* in, size_t len, uint8_t* out) {
    __m128i rk[15];
    for (int i = 0; i < 15; i++) rk[i] = _mm_loadu_si128(reinterpret_cast<const __m128i*>(k.rk[i]));
    uint32_t n = uint32_t(j0[12]) << 24 | uint32_t(j0[13]) << 16 | uint32_t(j0[14]) << 8 | j0[15];
    alignas(16) uint8_t cb[64];
    size_t off = 0;
    for (; off + 64 <= len; off += 64) {
        for (int j = 0; j < 4; j++) counterBlock(j0, ++n, cb + 16 * j);
        __m128i b0 = _mm_xor_si128(_mm_load_si128(reinterpret_cast<const __m128i*>(cb)), rk[0]);
        __m128i b1 = _mm_xor_si128(_mm_load_si128(reinterpret_cast<const __m128i*>(cb + 16)), rk[0]);
        __m128i b2 = _mm_xor_si128(_mm_load_si128(reinterpret_cast<const __m128i*>(cb + 32)), rk[0]);
        __m128i b3 = _mm_xor_si128(_mm_load_si128(reinterpret_cast<const __m128i*>(cb + 48)), rk[0]);
        for (int r = 1; r < 14; r++) {
            b0 = _mm_aesenc_si128(b0, rk[r]); b1 = _mm_aesenc_si128(b1, rk[r]);
            b2 = _mm_aesenc_si128(b2, rk[r]); b3 = _mm_aesenc_si128(b3, rk[r]);
        }
        b0 = _mm_aesenclast_si128(b0, rk[14]); b1 = _mm_aesenclast_si128(b1, rk[14]);
        b2 = _mm_aesenclast_si128(b2, rk[14]); b3 = _mm_aesenclast_si128(b3, rk[14]);
        const auto* src = reinterpret_cast<const __m128i*>(in + off);
        auto* dst = reinterpret_cast<__m128i*>(out + off);
        _mm_storeu_si128(dst, _mm_xor_si128(_mm_loadu_si128(src), b0));
        _mm_storeu_si128(dst + 1, _mm_xor_si128(_mm_loadu_si128(src + 1), b1));
        _mm_storeu_si128(dst + 2, _mm_xor_si128(_mm_loadu_si128(src + 2), b2));
        _mm_storeu_si128(dst + 3, _mm_xor_si128(_mm_loadu_si128(src + 3), b3));
    }
    for (; off < len; off += 16) {
        counterBlock(j0, ++n, cb);
        alignas(16) uint8_t ks[16];
        _mm_store_si128(reinterpret_cast<__m128i*>(ks), aesBlock(_mm_load_si128(reinterpret_cast<const __m128i*>(cb)), rk));
        const size_t m = len - off < 16 ? len - off : 16;
        for (size_t i = 0; i < m; i++) out[off + i] = in[off + i] ^ ks[i];
    }
}

__attribute__((target("pclmul,ssse3"))) void ghashX86(const uint8_t h[16], const uint8_t* data, size_t len, uint8_t y[16]) {
    const __m128i hr = reversed(h);
    __m128i acc = reversed(y);
    size_t off = 0;
    for (; off + 16 <= len; off += 16) acc = gfmul(_mm_xor_si128(acc, reversed(data + off)), hr);
    if (off < len) {
        uint8_t block[16] = {};
        std::memcpy(block, data + off, len - off);
        acc = gfmul(_mm_xor_si128(acc, reversed(block)), hr);
    }
    _mm_storeu_si128(reinterpret_cast<__m128i*>(y),
                     _mm_shuffle_epi8(acc, _mm_set_epi8(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)));
}

/** SHA-NI keeps the state as ABEF and CDGH; each group of four rounds is two SHA256RNDS2 calls, and the message
 *  schedule runs four words ahead (SHA256MSG1/MSG2), as in crypto_arm.cpp. */
__attribute__((target("sha,sse4.1,ssse3"))) void sha256BlocksX86(uint32_t st[8], const uint8_t* data, size_t blocks) {
    const __m128i swap = _mm_set_epi64x(0x0c0d0e0f08090a0bULL, 0x0405060700010203ULL);   // big-endian words
    __m128i t = _mm_shuffle_epi32(_mm_loadu_si128(reinterpret_cast<const __m128i*>(st)), 0xB1);        // CDAB
    __m128i s1 = _mm_shuffle_epi32(_mm_loadu_si128(reinterpret_cast<const __m128i*>(st + 4)), 0x1B);   // EFGH
    __m128i s0 = _mm_alignr_epi8(t, s1, 8);       // ABEF
    s1 = _mm_blend_epi16(s1, t, 0xF0);             // CDGH
    for (size_t b = 0; b < blocks; b++, data += 64) {
        const __m128i abef = s0, cdgh = s1;
        __m128i msg[4];
        for (int i = 0; i < 4; i++) msg[i] = _mm_shuffle_epi8(_mm_loadu_si128(reinterpret_cast<const __m128i*>(data + 16 * i)), swap);
        for (int i = 0; i < 16; i++) {
            const __m128i wk = _mm_add_epi32(msg[i & 3], _mm_loadu_si128(reinterpret_cast<const __m128i*>(kSha256K + 4 * i)));
            // words 4(i+4)… replace the group just consumed
            if (i < 12) {
                const __m128i w = _mm_add_epi32(_mm_sha256msg1_epu32(msg[i & 3], msg[(i + 1) & 3]),
                                                _mm_alignr_epi8(msg[(i + 3) & 3], msg[(i + 2) & 3], 4));
                msg[i & 3] = _mm_sha256msg2_epu32(w, msg[(i + 3) & 3]);
            }
            s1 = _mm_sha256rnds2_epu32(s1, s0, wk);
            s0 = _mm_sha256rnds2_epu32(s0, s1, _mm_shuffle_epi32(wk, 0x0E));
        }
        s0 = _mm_add_epi32(s0, abef);
        s1 = _mm_add_epi32(s1, cdgh);
    }
    t = _mm_shuffle_epi32(s0, 0x1B);               // FEBA
    s1 = _mm_shuffle_epi32(s1, 0xB1);              // DCHG
    _mm_storeu_si128(reinterpret_cast<__m128i*>(st), _mm_blend_epi16(t, s1, 0xF0));        // DCBA
    _mm_storeu_si128(reinterpret_cast<__m128i*>(st + 4), _mm_alignr_epi8(s1, t, 8));       // HGFE
}

}  // namespace bc

#else

namespace bc {
bool x86CryptoCompiled() { return false; }
bool x86AesSupported() { return false; }
bool x86ShaSupported() { return false; }
void aesCtrX86(const AesKey&, const uint8_t[16], const uint8_t*, size_t, uint8_t*) {}
void ghashX86(const uint8_t[16], const uint8_t*, size_t, uint8_t[16]) {}
void sha256BlocksX86(uint32_t[8], const uint8_t*, size_t) {}
}  // namespace bc

#endif
