zxing-cpp v2.3.0 (https://github.com/zxing-cpp/zxing-cpp, Apache-2.0, see LICENSE) — only what the QR scanning
test needs: the core sources and `qrcode/` plus `libzueci/`. Left out: the other barcode formats and the
multi-format wrappers (MultiFormatReader/Writer, ReadBarcode, WriteBarcode, DecodeHints, the C and C++ APIs).

Local changes:
- `src/Version.h` is written by hand (upstream generates it with CMake).
- `src/Utf.cpp`: a small byte view replaces `std::basic_string_view<uint8_t>`, which libc++ 19 (NDK 28) no
  longer supports.
