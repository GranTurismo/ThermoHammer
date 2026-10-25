// CPU Test 1 (MATH) kernels: matrix multiply, FFT, Mandelbrot, prime sieve, RSA-1024, n-body.
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#include "core.hpp"

namespace bc {
namespace {

constexpr double kPi = 3.14159265358979323846;

struct MatMul final : bc_kernel {
    static constexpr int n = 96;
    std::vector<double> a = std::vector<double>(n * n), b = std::vector<double>(n * n), c = std::vector<double>(n * n);
    MatMul() {
        for (int i = 0; i < n * n; i++) { a[i] = std::sin(i * 0.37); b[i] = std::cos(i * 0.11); }
    }
    int64_t run() override {
        std::fill(c.begin(), c.end(), 0.0);
        for (int i = 0; i < n; i++) {
            double* row = &c[i * n];
            for (int k = 0; k < n; k++) {
                const double aik = a[i * n + k];
                const double* bk = &b[k * n];
                for (int j = 0; j < n; j++) row[j] += aik * bk[j];
            }
        }
        double sum = 0;
        for (double v : c) sum += v;
        return int64_t(sum * 1e6);
    }
};

struct Fft final : bc_kernel {
    static constexpr int n = 4096, bits = 12;
    std::vector<double> signal = std::vector<double>(n), re = std::vector<double>(n), im = std::vector<double>(n);
    std::vector<int> rev = std::vector<int>(n);
    Fft() {
        for (int i = 0; i < n; i++) {
            signal[i] = std::sin(i * 0.05) + 0.5 * std::sin(i * 0.31);
            int r = 0;
            for (int b = 0; b < bits; b++) if ((i >> b) & 1) r |= 1 << (bits - 1 - b);
            rev[i] = r;
        }
    }
    void transform(double sign) {
        for (int i = 0; i < n; i++) {
            const int j = rev[i];
            if (j > i) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
        }
        for (int len = 2; len <= n; len <<= 1) {
            const double ang = sign * -2 * kPi / len, wr = std::cos(ang), wi = std::sin(ang);
            const int half = len / 2;
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < half; k++) {
                    const double ur = re[i + k], ui = im[i + k], xr = re[i + k + half], xi = im[i + k + half];
                    const double vr = xr * cr - xi * ci, vi = xr * ci + xi * cr;
                    re[i + k] = ur + vr; im[i + k] = ui + vi;
                    re[i + k + half] = ur - vr; im[i + k + half] = ui - vi;
                    const double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
    }
    int64_t run() override {
        re = signal;
        std::fill(im.begin(), im.end(), 0.0);
        transform(1);
        transform(-1);   // forward then inverse: back to the signal, scaled by n
        double err = 0;
        for (int i = 0; i < n; i++) err += std::fabs(re[i] / n - signal[i]);
        if (!(err < 1e-6)) return fail("FFT round trip error");
        return int64_t(re[17] / n * 1e9);
    }
};

struct Mandelbrot final : bc_kernel {
    static constexpr int w = 200, h = 120, maxIter = 256;
    int64_t run() override {
        int64_t total = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                const double cr = -2.2 + 3.0 * x / w, ci = -1.2 + 2.4 * y / h;
                double zr = 0, zi = 0;
                int it = 0;
                while (it < maxIter && zr * zr + zi * zi < 4.0) {
                    const double t = zr * zr - zi * zi + cr;
                    zi = 2 * zr * zi + ci; zr = t; it++;
                }
                total += it;
            }
        }
        return total;
    }
};

struct Sieve final : bc_kernel {
    static constexpr int limit = 2'000'000;
    std::vector<uint8_t> composite = std::vector<uint8_t>(limit);
    int64_t run() override {
        std::fill(composite.begin(), composite.end(), 0);
        int64_t count = 0;
        for (int i = 2; i < limit; i++) {
            if (composite[i]) continue;
            count++;
            for (int64_t j = int64_t(i) * i; j < limit; j += i) composite[size_t(j)] = 1;
        }
        if (count != 148'933) return fail("sieve miscounted the primes");
        return count;
    }
};

struct NBody final : bc_kernel {
    static constexpr int count = 64, steps = 8;
    double px[count], py[count], pz[count], vx[count], vy[count], vz[count];
    int64_t run() override {
        for (int i = 0; i < count; i++) {
            px[i] = std::cos(i * 1.7) * (1 + i % 5); py[i] = std::sin(i * 1.3) * (1 + i % 7); pz[i] = std::sin(i * 0.7);
            vx[i] = vy[i] = vz[i] = 0;
        }
        const double dt = 0.01;
        for (int s = 0; s < steps; s++) {
            for (int i = 0; i < count; i++) {
                double ax = 0, ay = 0, az = 0;
                for (int j = 0; j < count; j++) {
                    if (i == j) continue;
                    const double dx = px[j] - px[i], dy = py[j] - py[i], dz = pz[j] - pz[i];
                    const double d2 = dx * dx + dy * dy + dz * dz + 0.01;
                    const double inv = 1.0 / (d2 * std::sqrt(d2));
                    ax += dx * inv; ay += dy * inv; az += dz * inv;
                }
                vx[i] += ax * dt; vy[i] += ay * dt; vz[i] += az * dt;
            }
            for (int i = 0; i < count; i++) { px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt; }
        }
        double sum = 0;
        for (int i = 0; i < count; i++) sum += px[i] + py[i] + pz[i];
        return int64_t(sum * 1e9);
    }
};

// ── RSA-1024: x^e mod n then back with x^d mod n, Montgomery multiplication on machine words ──

#if defined(__SIZEOF_INT128__)
using Limb = uint64_t;
using Wide = unsigned __int128;
#else
using Limb = uint32_t;
using Wide = uint64_t;
#endif
constexpr int kW = int(sizeof(Limb) * 8);
constexpr int kL = 1024 / kW;
struct Big { Limb v[kL]; };   // little-endian limbs

// A fixed key and message (generated once from seed 42), so every phone raises the same numbers to the same powers.
constexpr const char* kN = "9fed82abd9a6d5ffe13f83c5776401d3ee06d5002d931aee3afdee9831a45817da4371b29dad10bcb0b438c882811fe4"
    "8f1fb0c1475a0305295489652ec9f8426a1615f70da1ccb78e47fafa821c6da8709b404b5298ee8666f92f31498b771ef2685fccca842ca3ce"
    "700177ba18b64236e2fea08ea4db1a919592b5db871419";
constexpr const char* kD = "594f1367d075e08fb243fbdc2c1e06ff48af3c078b6bd8e02cc55d96543288975965cc7a889efaf7c45e004fc8c13f04"
    "2e00ee7e20d05d5b668734eaf2440b8478dc2a9764f749ea0232b0271418454f0b21a2d1d630659d18f81478ccfa5036433ef38b2ea6502fe8"
    "904113c7233ce7081b82547d5811f13b48b5f833c88801";
constexpr const char* kM = "00000019ff002d4d902059e4ff9ab5c29f044aed7552332702627f7312922f83ef8c485bc07a30f2edd4253b50f0fd0a"
    "750cab754ccc9bc2a53f8a28abf3e3fc21813d25655238a643ff50113d1a85dd506e5a9ab758588dab73295b344a54b842c18a62ef48e8d550"
    "fd9d3f85d5169590b2b633956b8c0ca8499b926b5252e3";
constexpr const char* kC = "7341dfa749980b8909635b6e163d56f5c1a1371d65409be5880471c3f24eb37c83ff1a819b86097eeda2da0fef1ef902"
    "89ae9330ac89967bef9635b19ff28a2eae363656bcaad0381f6741286eabaab9657a406144f3365666611f5e5ba2400b26c646824e48d9ba73"
    "3692a9ac75d13248bc77f718057e01c2646cbd31cb28b6";

Big fromHex(const char* hex) {
    Big b{};
    const size_t len = std::strlen(hex);
    for (size_t i = 0; i < len; i++) {
        const char ch = hex[len - 1 - i];
        const Limb nib = Limb(ch <= '9' ? ch - '0' : ch - 'a' + 10);
        b.v[i / (kW / 4)] |= nib << (4 * (i % (kW / 4)));
    }
    return b;
}

bool equal(const Big& a, const Big& b) { return std::memcmp(a.v, b.v, sizeof a.v) == 0; }

/** a >= b */
bool geq(const Limb* a, const Limb* b) {
    for (int i = kL - 1; i >= 0; i--) if (a[i] != b[i]) return a[i] > b[i];
    return true;
}

void subInPlace(Limb* a, const Limb* b) {
    Limb borrow = 0;
    for (int i = 0; i < kL; i++) {
        const Wide d = Wide(a[i]) - b[i] - borrow;
        a[i] = Limb(d);
        borrow = Limb(d >> (2 * kW - 1)) & 1;
    }
}

struct Montgomery {
    Big n{}, r2{}, one{};
    Limb n0inv = 0;   // −n⁻¹ mod 2^w

    explicit Montgomery(const Big& modulus) : n(modulus) {
        Limb inv = n.v[0];
        for (int i = 0; i < 6; i++) inv *= Limb(2) - n.v[0] * inv;   // Newton: correct bits double each step
        n0inv = Limb(0) - inv;
        // R² mod n with R = 2^1024: double 1 two thousand forty-eight times, reducing as we go
        Big x{};
        x.v[0] = 1;
        for (int i = 0; i < 2 * 1024; i++) {
            const Limb top = x.v[kL - 1] >> (kW - 1);
            for (int j = kL - 1; j > 0; j--) x.v[j] = (x.v[j] << 1) | (x.v[j - 1] >> (kW - 1));
            x.v[0] <<= 1;
            if (top || geq(x.v, n.v)) subInPlace(x.v, n.v);
        }
        r2 = x;
        one.v[0] = 1;
    }

    /** a·b·R⁻¹ mod n (CIOS). */
    Big mul(const Big& a, const Big& b) const {
        Limb t[kL + 2] = {};
        for (int i = 0; i < kL; i++) {
            Wide c = 0;
            for (int j = 0; j < kL; j++) {
                c = Wide(t[j]) + Wide(a.v[j]) * b.v[i] + (c >> kW);
                t[j] = Limb(c);
            }
            c = Wide(t[kL]) + (c >> kW);
            t[kL] = Limb(c);
            t[kL + 1] = Limb(c >> kW);
            const Limb m = t[0] * n0inv;
            c = Wide(t[0]) + Wide(m) * n.v[0];
            for (int j = 1; j < kL; j++) {
                c = Wide(t[j]) + Wide(m) * n.v[j] + (c >> kW);
                t[j - 1] = Limb(c);
            }
            c = Wide(t[kL]) + (c >> kW);
            t[kL - 1] = Limb(c);
            t[kL] = t[kL + 1] + Limb(c >> kW);
        }
        Big r{};
        std::memcpy(r.v, t, sizeof r.v);
        if (t[kL] || geq(r.v, n.v)) subInPlace(r.v, n.v);
        return r;
    }

    /** base^exp mod n, square-and-multiply over the exponent's bits. */
    Big pow(const Big& base, const Big& exp) const {
        const Big x = mul(base, r2);
        Big acc = mul(one, r2);
        bool started = false;
        for (int i = kL * kW - 1; i >= 0; i--) {
            const bool bit = (exp.v[i / kW] >> (i % kW)) & 1;
            if (started) acc = mul(acc, acc);
            if (bit) { acc = mul(acc, x); started = true; }
        }
        return mul(acc, one);
    }
};

struct Rsa final : bc_kernel {
    Montgomery mont{fromHex(kN)};
    Big d = fromHex(kD), msg = fromHex(kM), expected = fromHex(kC), e{};
    Rsa() { e.v[0] = 65537; }
    int64_t run() override {
        const Big c = mont.pow(msg, e);
        const Big m = mont.pow(c, d);
        if (!equal(c, expected) || !equal(m, msg)) return fail("RSA round trip failed");
        return int64_t(m.v[0] & 0x7fffffff);
    }
};

}  // namespace

bc_kernel* makeMatMul() { return new MatMul(); }
bc_kernel* makeFft() { return new Fft(); }
bc_kernel* makeMandelbrot() { return new Mandelbrot(); }
bc_kernel* makeSieve() { return new Sieve(); }
bc_kernel* makeRsa() { return new Rsa(); }
bc_kernel* makeNBody() { return new NBody(); }

}  // namespace bc
