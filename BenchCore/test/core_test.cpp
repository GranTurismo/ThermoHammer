// Host test of BenchCore (any desktop: `BenchCore/test/run.sh`): every kernel repeats its checksum, and AES-GCM
// and SHA-256 match published vectors on the instruction path and the portable path, and each other.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>
#include <string>
#include <vector>

#include "../src/core.hpp"

static int failures = 0;

static void expect(bool ok, const std::string& what) {
    std::printf("%s %s\n", ok ? "  ok  " : "  FAIL", what.c_str());
    if (!ok) failures++;
}

static std::string hex(const uint8_t* p, size_t n) {
    static const char* d = "0123456789abcdef";
    std::string s;
    for (size_t i = 0; i < n; i++) { s += d[p[i] >> 4]; s += d[p[i] & 15]; }
    return s;
}

int main(int argc, char** argv) {
    const bool emit = argc > 1 && std::string(argv[1]) == "--emit";   // print a vector for an outside check
    std::printf("crypto path: %s\n", bc_crypto_path());
    std::vector<bool> paths{false};
    if (bc::cryptoHw()) paths.push_back(true);

    // AES-256-GCM, test cases 13 and 14 of McGrew & Viega's GCM specification
    bc::AesKey zeroKey{};
    const uint8_t key0[32] = {}, iv0[12] = {};
    bc::aesExpand(key0, zeroKey);
    for (bool hw : paths) {
        const char* p = hw ? "hw" : "sw";
        uint8_t tag[16], ct[16], pt[16] = {}, back[16];
        bc::gcmSeal(zeroKey, iv0, pt, 0, ct, tag, hw);
        expect(hex(tag, 16) == "530f8afbc74536b9a963b4f1c4cb738b", std::string("GCM case 13 tag ") + p);
        bc::gcmSeal(zeroKey, iv0, pt, 16, ct, tag, hw);
        expect(hex(ct, 16) == "cea7403d4d606b6e074ec5d3baf39d18", std::string("GCM case 14 ciphertext ") + p);
        expect(hex(tag, 16) == "d0d1c8a799996bf0265b98b5d48ab919", std::string("GCM case 14 tag ") + p);
        expect(bc::gcmOpen(zeroKey, iv0, ct, 16, tag, back, hw) && !std::memcmp(back, pt, 16), std::string("GCM open ") + p);
        tag[3] ^= 1;
        expect(!bc::gcmOpen(zeroKey, iv0, ct, 16, tag, back, hw), std::string("GCM rejects a bad tag ") + p);
    }
    // the two paths agree on long and odd-length messages
    const std::vector<uint8_t> text = bc::corpus(1 << 20);
    bc::AesKey key{};
    uint8_t k[32], iv[12];
    for (int i = 0; i < 32; i++) k[i] = uint8_t(i * 7 + 3);
    for (int i = 0; i < 12; i++) iv[i] = uint8_t(i * 13 + 1);
    bc::aesExpand(k, key);
    for (size_t len : {size_t(1 << 20), size_t(1000), size_t(17), size_t(64), size_t(65)}) {
        std::vector<uint8_t> a(len), b(len);
        uint8_t ta[16], tb[16];
        bc::gcmSeal(key, iv, text.data(), len, a.data(), ta, false);
        if (paths.size() > 1) {
            bc::gcmSeal(key, iv, text.data(), len, b.data(), tb, true);
            expect(a == b && !std::memcmp(ta, tb, 16), "GCM hw == sw, " + std::to_string(len) + " bytes");
        }
        if (emit && len == 1000) {
            std::printf("EMIT key=%s iv=%s pt=%s ct=%s tag=%s\n", hex(k, 32).c_str(), hex(iv, 12).c_str(),
                hex(text.data(), len).c_str(), hex(a.data(), len).c_str(), hex(ta, 16).c_str());
        }
    }

    // SHA-256: FIPS 180-2 vectors, then paths agree on every tail length
    for (bool hw : paths) {
        const char* p = hw ? "hw" : "sw";
        uint8_t d[32];
        bc::sha256(reinterpret_cast<const uint8_t*>("abc"), 3, d, hw);
        expect(hex(d, 32) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", std::string("SHA-256 abc ") + p);
        bc::sha256(nullptr, 0, d, hw);
        expect(hex(d, 32) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", std::string("SHA-256 empty ") + p);
        const char* two = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
        bc::sha256(reinterpret_cast<const uint8_t*>(two), std::strlen(two), d, hw);
        expect(hex(d, 32) == "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", std::string("SHA-256 two blocks ") + p);
    }
    if (paths.size() > 1) {
        bool same = true;
        for (size_t len = 0; len < 200; len++) {
            uint8_t a[32], b[32];
            bc::sha256(text.data(), len, a, false);
            bc::sha256(text.data(), len, b, true);
            same = same && !std::memcmp(a, b, 32);
        }
        expect(same, "SHA-256 hw == sw, every length 0–199");
        uint8_t d[32];
        bc::sha256(text.data(), text.size(), d, true);
        if (emit) std::printf("EMIT_SHA corpus=%s\n", hex(d, 32).c_str());
    }

    // every kernel: same checksum every call, and its rate on this machine
    for (int id = 0; id < BC_KERNEL_COUNT; id++) {
        bc_kernel* kernel = bc_kernel_create(id);
        int ok = 1;
        const int64_t first = bc_kernel_run(kernel, &ok);
        bool same = ok == 1;
        int runs = 0;
        const auto t0 = std::chrono::steady_clock::now();
        double el = 0;
        while (el < 0.3 && same) {
            same = bc_kernel_run(kernel, &ok) == first && ok == 1;
            runs++;
            el = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        }
        char line[160];
        std::snprintf(line, sizeof line, "%-16s checksum %lld, %.1f ops/s%s%s", bc_kernel_name(id),
            static_cast<long long>(first), runs / el, ok ? "" : " — ", ok ? "" : bc_kernel_error(kernel));
        expect(same, line);
        bc_kernel_destroy(kernel);
    }
    // MEM: every RAM and storage mode gives a positive rate, and the test file is gone afterwards
    if (bc_ram* ram = bc_ram_open(64 << 20, 4)) {
        const char* names[] = {"read", "write", "copy", "latency"};
        for (int mode = BC_RAM_READ; mode <= BC_RAM_LATENCY; mode++) {
            const double v = bc_ram_run(ram, mode, 0.2);
            expect(v > 0, std::string("RAM ") + names[mode] + ": " + std::to_string(mode == BC_RAM_LATENCY ? v : v / 1e9) +
                (mode == BC_RAM_LATENCY ? " ns" : " GB/s"));
        }
        bc_ram_close(ram);
    } else {
        expect(false, std::string("RAM open: ") + bc_mem_error());
    }
    const char* tmp = std::getenv("TMPDIR");
    const std::string path = std::string(tmp ? tmp : "/tmp") + "/bench_core_disk.bin";
    if (bc_disk* disk = bc_disk_open(path.c_str(), 64 << 20)) {
        const char* names[] = {"seq write", "seq read", "random read", "random write"};
        for (int mode = BC_DISK_SEQ_WRITE; mode <= BC_DISK_RANDOM_WRITE; mode++) {
            const double v = bc_disk_run(disk, mode, 4, 0.3);
            expect(v > 0, std::string("disk ") + names[mode] + ": " + std::to_string(mode < 2 ? v / 1e6 : v) + (mode < 2 ? " MB/s" : " IOPS"));
        }
        std::printf("        direct: %d\n", bc_disk_direct(disk));
        bc_disk_close(disk);
        expect(std::fopen(path.c_str(), "rb") == nullptr, "disk test file deleted");
    } else {
        expect(false, std::string("disk open: ") + bc_mem_error());
    }

    // QR: every frame reads back as its own text, the frames are the same every time, and they're camera-like
    if (bc_qr* qr = bc_qr_create(BC_QR_FRAMES, BC_QR_SEED)) {
        const int n = bc_qr_count(qr);
        std::set<std::string> texts;
        int read = 0, skewed = 0;
        size_t shortest = SIZE_MAX, longest = 0;
        uint64_t fingerprint = 14695981039346656037ULL;   // FNV-1a over every frame's pixels — compare across phones
        for (int i = 0; i < n; i++) {
            read += bc_qr_scan(qr, i, nullptr);
            const std::string t = bc_qr_text(qr, i);
            texts.insert(t);
            shortest = std::min(shortest, t.size()); longest = std::max(longest, t.size());
            float c[8];
            bc_qr_corners(qr, i, c);
            if (std::fabs(c[1] - c[3]) > 8) skewed++;
            const uint8_t* y = bc_qr_luma(qr, i);
            for (int p = 0; p < BC_QR_W * BC_QR_H; p++) fingerprint = (fingerprint ^ y[p]) * 1099511628211ULL;
        }
        expect(n == BC_QR_FRAMES && read == n, "QR frames decode: " + std::to_string(read) + "/" + std::to_string(n));
        expect(int(texts.size()) == n, "QR payloads all differ");
        expect(longest - shortest > 100 && skewed > n / 2, "QR frames are camera-like (" + std::to_string(skewed) + " skewed)");
        std::printf("        QR fingerprint %016llx\n", (unsigned long long)fingerprint);
        if (bc_qr* again = bc_qr_create(4, BC_QR_SEED)) {
            bool same = true;
            for (int i = 0; i < 4; i++) same = same && !std::memcmp(bc_qr_luma(qr, i), bc_qr_luma(again, i), BC_QR_W * BC_QR_H);
            expect(same, "QR frames are deterministic");
            bc_qr_destroy(again);
        }
        float found[8];
        int scans = 0, i = 0;
        const auto t0 = std::chrono::steady_clock::now();
        while (std::chrono::steady_clock::now() - t0 < std::chrono::seconds(2)) scans += bc_qr_scan(qr, i++ % n, found);
        const double el = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        std::printf("        QR %.1f scans/s, one thread (%d of %d read)\n", scans / el, scans, i);
        bc_qr_destroy(qr);
    } else {
        expect(false, "QR frames");
    }

    std::printf(failures ? "%d FAILED\n" : "all passed\n", failures);
    return failures ? 1 : 0;
}
