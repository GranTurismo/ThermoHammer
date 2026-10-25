// UX Test 6, QR SCANNING — the frames a phone camera would hand a scanner, and zxing-cpp reading them. The same
// code on Android (JNI, engine/ux/QrNative.kt) and iOS (Swift), so every phone renders the same frames and runs
// the same decoder.
//
//   Seeded 1280×720 luminance frames, each a different QR code (payloads of 10–180 characters, so versions
//   2–14) photographed at an angle: rotation, perspective, defocus, uneven light, sensor noise, some low
//   contrast. Every frame is checked to decode before timing (a frame that doesn't is re-shot with a steadier
//   camera), so all phones do identical work. The random numbers follow java.util.Random, as the Android app's
//   first (Kotlin) renderer did, so the payloads and camera poses are the ones it drew.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "bench_core.h"

#include "Barcode.h"
#include "BitMatrix.h"
#include "HybridBinarizer.h"
#include "ImageView.h"
#include "PerspectiveTransform.h"
#include "Quadrilateral.h"
#include "ReaderOptions.h"
#include "qrcode/QRErrorCorrectionLevel.h"
#include "qrcode/QRReader.h"
#include "qrcode/QRWriter.h"

namespace {

constexpr int W = BC_QR_W, H = BC_QR_H;
constexpr int QUIET = 4;    // modules of white around the code
const char ALPHABET[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#=&";
constexpr int ALPHABET_LEN = sizeof(ALPHABET) - 1;

/** java.util.Random, bit for bit. */
struct JavaRandom {
    uint64_t s;
    explicit JavaRandom(int64_t seed) : s((uint64_t(seed) ^ 0x5DEECE66DULL) & ((1ULL << 48) - 1)) {}
    int32_t next(int bits) {
        s = (s * 0x5DEECE66DULL + 0xBULL) & ((1ULL << 48) - 1);
        return int32_t(uint32_t(s >> (48 - bits)));
    }
    int32_t nextInt(int32_t bound) {
        int32_t r = next(31);
        int32_t m = bound - 1;
        if ((bound & m) == 0) return int32_t((int64_t(bound) * r) >> 31);
        for (int32_t u = r; int64_t(u) - (r = u % bound) + m > INT32_MAX; u = next(31)) {}
        return r;
    }
    float nextFloat() { return float(next(24)) / float(1 << 24); }
};

struct Frame {
    std::vector<uint8_t> luma;
    std::string text;
    float corners[8];
};

void blur(std::vector<uint8_t>& y, int x0, int y0, int x1, int y1) {
    std::vector<uint8_t> src(y);
    for (int py = std::max(1, y0); py < std::min(H - 1, y1 + 1); py++)
        for (int px = std::max(1, x0); px < std::min(W - 1, x1 + 1); px++) {
            int s = 0;
            for (int oy = -1; oy <= 1; oy++)
                for (int ox = -1; ox <= 1; ox++) s += src[(py + oy) * W + px + ox];
            y[py * W + px] = uint8_t(s / 9);
        }
}

Frame render(int index, int seed, float calm) {
    JavaRandom rng(int64_t(seed) * 7919 + index);
    Frame f;
    f.text = "TH-QR-" + std::to_string(index) + ":";
    for (int n = 10 + rng.nextInt(170); n > 0; n--) f.text += ALPHABET[rng.nextInt(ALPHABET_LEN)];
    ZXing::BitMatrix matrix = ZXing::QRCode::Writer()
        .setMargin(0).setErrorCorrectionLevel(ZXing::QRCode::ErrorCorrectionLevel::Medium).encode(f.text, 0, 0);
    const int modules = matrix.width();
    const int span = modules + 2 * QUIET;

    // the code's square, then the camera: size, rotation, perspective, position
    float module = 3.4f + rng.nextFloat() * 3.2f;
    float side = std::min(float(span) * module, float(H) * 0.9f);
    float angle = (rng.nextFloat() - 0.5f) * 1.2f * calm;
    float cx = float(W) / 2.f + (rng.nextFloat() - 0.5f) * (float(W) - side * 1.3f);
    float cy = float(H) / 2.f + (rng.nextFloat() - 0.5f) * std::max(0.f, float(H) - side * 1.3f);
    const float unit[8] = {-0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f};
    const float ca = float(std::cos(double(angle))), sa = float(std::sin(double(angle)));
    for (int k = 0; k < 4; k++) {
        float ux = unit[k * 2] * side, uy = unit[k * 2 + 1] * side;
        float jitter = side * 0.11f * calm;
        f.corners[k * 2] = cx + ux * ca - uy * sa + (rng.nextFloat() - 0.5f) * jitter;
        f.corners[k * 2 + 1] = cy + ux * sa + uy * ca + (rng.nextFloat() - 0.5f) * jitter;
    }
    const double sp = span;
    ZXing::PerspectiveTransform toCode(
        ZXing::QuadrilateralF({f.corners[0], f.corners[1]}, {f.corners[2], f.corners[3]}, {f.corners[4], f.corners[5]},
                              {f.corners[6], f.corners[7]}),
        ZXing::QuadrilateralF({0, 0}, {sp, 0}, {sp, sp}, {0, sp}));

    bool lowContrast = rng.nextFloat() < 0.25f;
    int white = lowContrast ? 175 + rng.nextInt(30) : 205 + rng.nextInt(40);
    int black = lowContrast ? 70 + rng.nextInt(25) : 20 + rng.nextInt(40);
    float lightX = rng.nextFloat() * float(W), lightY = rng.nextFloat() * float(H);
    int bgBase = 90 + rng.nextInt(70);
    int noiseAmp = 6 + rng.nextInt(10);

    f.luma.resize(size_t(W) * H);
    int x0 = W, x1 = 0, y0 = H, y1 = 0;
    for (int k = 0; k < 4; k++) {
        x0 = std::min(x0, int(f.corners[k * 2]) - 2); x1 = std::max(x1, int(f.corners[k * 2]) + 2);
        y0 = std::min(y0, int(f.corners[k * 2 + 1]) - 2); y1 = std::max(y1, int(f.corners[k * 2 + 1]) + 2);
    }
    x0 = std::clamp(x0, 0, W - 1); x1 = std::clamp(x1, 0, W - 1); y0 = std::clamp(y0, 0, H - 1); y1 = std::clamp(y1, 0, H - 1);
    uint32_t noise = uint32_t(seed) * 1103515245u + uint32_t(index) * 12345u + 1u;
    for (int py = 0; py < H; py++)
        for (int px = 0; px < W; px++) {
            // background: a lit surface with a soft gradient (desk, screen, poster)
            float v = float(bgBase) + 30.f * ((float(px) - float(W) / 2.f) / float(W)) + 20.f * ((float(py) - float(H) / 2.f) / float(H));
            if (toCode.isValid() && px >= x0 && px <= x1 && py >= y0 && py <= y1) {
                ZXing::PointF p = toCode(ZXing::PointF(px + 0.5, py + 0.5));
                if (p.x >= 0 && p.y >= 0 && p.x < sp && p.y < sp) {
                    int mx = int(p.x) - QUIET, my = int(p.y) - QUIET;
                    bool dark = mx >= 0 && mx < modules && my >= 0 && my < modules && matrix.get(mx, my);
                    v = float(dark ? black : white);
                }
            }
            // light falls off from one side; sensor noise on top
            float dx = (float(px) - lightX) / float(W), dy = (float(py) - lightY) / float(H);
            v *= 1.f - 0.35f * std::min(1.f, dx * dx + dy * dy);
            noise = noise * 1664525u + 1013904223u;
            v += float(int(noise >> 24) - 128) / 128.f * float(noiseAmp);
            f.luma[size_t(py) * W + px] = uint8_t(std::clamp(int(v), 0, 255));
        }
    // defocus over the code: a 3×3 box blur, twice for a soft lens
    int passes = rng.nextFloat() < 0.5f ? 1 : 2;
    for (int i = 0; i < passes; i++) blur(f.luma, x0, y0, x1, y1);
    return f;
}

/** What a scanner app does with one frame: binarize → find → decode (one thread, no second tries). */
struct Scanner {
    ZXing::ReaderOptions opts;
    ZXing::QRCode::Reader reader;
    Scanner() : opts(options()), reader(opts) {}

    static ZXing::ReaderOptions options() {
        ZXing::ReaderOptions o;
        o.setFormats(ZXing::BarcodeFormat::QRCode).setTryHarder(false).setTryRotate(false).setTryInvert(false)
            .setTryDownscale(false);
        return o;
    }

    /** Whether the frame read back as `text`; where the code was found goes to `found` (4 corners). */
    bool scan(const uint8_t* luma, const std::string& text, float* found) const {
        ZXing::HybridBinarizer image(ZXing::ImageView(luma, W, H, ZXing::ImageFormat::Lum));
        ZXing::Barcode r = reader.decode(image);
        if (!r.isValid()) return false;
        if (found) {
            const auto& q = r.position();
            for (int k = 0; k < 4; k++) { found[k * 2] = float(q[k].x); found[k * 2 + 1] = float(q[k].y); }
        }
        return r.text() == text;
    }
};

}  // namespace

struct bc_qr {
    std::vector<Frame> frames;
    Scanner scanner;
};

extern "C" {

bc_qr* bc_qr_create(int count, int seed) {
    try {
        auto* qr = new bc_qr;
        for (int i = 0; i < count; i++) {
            // a frame that fails to decode is re-shot with a calmer camera — deterministic either way
            float calm = 1.f;
            while (true) {
                Frame f = render(i, seed, calm);
                if (qr->scanner.scan(f.luma.data(), f.text, nullptr) || calm < 0.2f) {
                    qr->frames.push_back(std::move(f));
                    break;
                }
                calm *= 0.6f;
            }
        }
        return qr;
    } catch (...) {
        return nullptr;
    }
}

int bc_qr_count(const bc_qr* qr) { return int(qr->frames.size()); }

const uint8_t* bc_qr_luma(const bc_qr* qr, int index) { return qr->frames[index].luma.data(); }

const char* bc_qr_text(const bc_qr* qr, int index) { return qr->frames[index].text.c_str(); }

void bc_qr_corners(const bc_qr* qr, int index, float corners[8]) { memcpy(corners, qr->frames[index].corners, sizeof(float) * 8); }

int bc_qr_scan(bc_qr* qr, int index, float found[8]) {
    try {
        const Frame& f = qr->frames[index];
        return qr->scanner.scan(f.luma.data(), f.text, found) ? 1 : 0;
    } catch (...) {
        return 0;
    }
}

void bc_qr_destroy(bc_qr* qr) { delete qr; }

}  // extern "C"
