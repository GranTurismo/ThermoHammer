// ARMv8 crypto-extension paths: AES-CTR (AESE/AESMC, four blocks in flight), GHASH (PMULL) and SHA-256
// (SHA256H/H2/SU0/SU1). Compiled with the extension enabled (+crypto for Android arm64, on by default for Apple
// arm64) and only called when the CPU reports it (cryptoHw); elsewhere this file is just stubs.
#include <cstring>

#include "core.hpp"

#if defined(__aarch64__) && defined(__ARM_FEATURE_AES) && defined(__ARM_FEATURE_SHA2)
#include <arm_neon.h>

namespace bc {

bool armCryptoCompiled() { return true; }

namespace {

uint8x16_t aesBlock(uint8x16_t b, const uint8x16_t* rk) {
    for (int r = 0; r < 13; r++) b = vaesmcq_u8(vaeseq_u8(b, rk[r]));
    return veorq_u8(vaeseq_u8(b, rk[13]), rk[14]);
}

void counterBlock(const uint8_t j0[16], uint32_t n, uint8_t out[16]) {
    std::memcpy(out, j0, 12);
    out[12] = uint8_t(n >> 24); out[13] = uint8_t(n >> 16); out[14] = uint8_t(n >> 8); out[15] = uint8_t(n);
}

/** a·b in GF(2^128), both bit-reflected (bit i = coefficient of x^i), mod x^128 + x^7 + x^2 + x + 1. */
uint64x2_t gfmul(uint64x2_t a, uint64x2_t b) {
    const poly64_t a0 = vgetq_lane_u64(a, 0), a1 = vgetq_lane_u64(a, 1), b0 = vgetq_lane_u64(b, 0), b1 = vgetq_lane_u64(b, 1);
    const uint64x2_t lo = vreinterpretq_u64_p128(vmull_p64(a0, b0));
    const uint64x2_t hi = vreinterpretq_u64_p128(vmull_p64(a1, b1));
    const uint64x2_t mid = veorq_u64(vreinterpretq_u64_p128(vmull_p64(a0, b1)), vreinterpretq_u64_p128(vmull_p64(a1, b0)));
    uint64_t p0 = vgetq_lane_u64(lo, 0), p1 = vgetq_lane_u64(lo, 1) ^ vgetq_lane_u64(mid, 0);
    uint64_t p2 = vgetq_lane_u64(hi, 0) ^ vgetq_lane_u64(mid, 1);
    const uint64_t p3 = vgetq_lane_u64(hi, 1);
    const uint64x2_t t = vreinterpretq_u64_p128(vmull_p64(p3, 0x87));   // x^192 ≡ x^64·(x^7 + x^2 + x + 1)
    p1 ^= vgetq_lane_u64(t, 0); p2 ^= vgetq_lane_u64(t, 1);
    const uint64x2_t u = vreinterpretq_u64_p128(vmull_p64(p2, 0x87));   // x^128 ≡ x^7 + x^2 + x + 1
    p0 ^= vgetq_lane_u64(u, 0); p1 ^= vgetq_lane_u64(u, 1);
    return vcombine_u64(vcreate_u64(p0), vcreate_u64(p1));
}

uint64x2_t reflected(const uint8_t* block) { return vreinterpretq_u64_u8(vrbitq_u8(vld1q_u8(block))); }

}  // namespace

void aesCtrArm(const AesKey& k, const uint8_t j0[16], const uint8_t* in, size_t len, uint8_t* out) {
    uint8x16_t rk[15];
    for (int i = 0; i < 15; i++) rk[i] = vld1q_u8(k.rk[i]);
    uint32_t n = uint32_t(j0[12]) << 24 | uint32_t(j0[13]) << 16 | uint32_t(j0[14]) << 8 | j0[15];
    uint8_t cb[64];
    size_t off = 0;
    for (; off + 64 <= len; off += 64) {
        for (int j = 0; j < 4; j++) counterBlock(j0, ++n, cb + 16 * j);
        uint8x16_t b0 = vld1q_u8(cb), b1 = vld1q_u8(cb + 16), b2 = vld1q_u8(cb + 32), b3 = vld1q_u8(cb + 48);
        for (int r = 0; r < 13; r++) {
            b0 = vaesmcq_u8(vaeseq_u8(b0, rk[r])); b1 = vaesmcq_u8(vaeseq_u8(b1, rk[r]));
            b2 = vaesmcq_u8(vaeseq_u8(b2, rk[r])); b3 = vaesmcq_u8(vaeseq_u8(b3, rk[r]));
        }
        b0 = veorq_u8(vaeseq_u8(b0, rk[13]), rk[14]); b1 = veorq_u8(vaeseq_u8(b1, rk[13]), rk[14]);
        b2 = veorq_u8(vaeseq_u8(b2, rk[13]), rk[14]); b3 = veorq_u8(vaeseq_u8(b3, rk[13]), rk[14]);
        vst1q_u8(out + off, veorq_u8(vld1q_u8(in + off), b0));
        vst1q_u8(out + off + 16, veorq_u8(vld1q_u8(in + off + 16), b1));
        vst1q_u8(out + off + 32, veorq_u8(vld1q_u8(in + off + 32), b2));
        vst1q_u8(out + off + 48, veorq_u8(vld1q_u8(in + off + 48), b3));
    }
    for (; off < len; off += 16) {
        counterBlock(j0, ++n, cb);
        uint8_t ks[16];
        vst1q_u8(ks, aesBlock(vld1q_u8(cb), rk));
        const size_t m = len - off < 16 ? len - off : 16;
        for (size_t i = 0; i < m; i++) out[off + i] = in[off + i] ^ ks[i];
    }
}

void ghashArm(const uint8_t h[16], const uint8_t* data, size_t len, uint8_t y[16]) {
    const uint64x2_t hr = reflected(h);
    uint64x2_t acc = reflected(y);
    size_t off = 0;
    for (; off + 16 <= len; off += 16) acc = gfmul(veorq_u64(acc, reflected(data + off)), hr);
    if (off < len) {
        uint8_t block[16] = {};
        std::memcpy(block, data + off, len - off);
        acc = gfmul(veorq_u64(acc, reflected(block)), hr);
    }
    vst1q_u8(y, vrbitq_u8(vreinterpretq_u8_u64(acc)));
}

void sha256BlocksArm(uint32_t st[8], const uint8_t* data, size_t blocks) {
    uint32x4_t s0 = vld1q_u32(st), s1 = vld1q_u32(st + 4);
    for (size_t b = 0; b < blocks; b++, data += 64) {
        uint32x4_t msg[4];
        for (int i = 0; i < 4; i++) msg[i] = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 16 * i)));
        uint32x4_t abcd = s0, efgh = s1;
        for (int i = 0; i < 16; i++) {
            const uint32x4_t wk = vaddq_u32(msg[i & 3], vld1q_u32(kSha256K + 4 * i));
            // words 4(i+4)… replace the group just consumed
            if (i < 12) msg[i & 3] = vsha256su1q_u32(vsha256su0q_u32(msg[i & 3], msg[(i + 1) & 3]), msg[(i + 2) & 3], msg[(i + 3) & 3]);
            const uint32x4_t prev = abcd;
            abcd = vsha256hq_u32(abcd, efgh, wk);
            efgh = vsha256h2q_u32(efgh, prev, wk);
        }
        s0 = vaddq_u32(s0, abcd);
        s1 = vaddq_u32(s1, efgh);
    }
    vst1q_u32(st, s0);
    vst1q_u32(st + 4, s1);
}

}  // namespace bc

#else

namespace bc {
bool armCryptoCompiled() { return false; }
void aesCtrArm(const AesKey&, const uint8_t[16], const uint8_t*, size_t, uint8_t*) {}
void ghashArm(const uint8_t[16], const uint8_t*, size_t, uint8_t[16]) {}
void sha256BlocksArm(uint32_t[8], const uint8_t*, size_t) {}
}  // namespace bc

#endif
