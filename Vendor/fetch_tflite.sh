#!/bin/bash
# Fetches TensorFlow Lite 2.17.0 for iOS (the AI test's CPU and GPU runs: XNNPACK and the Metal delegate) into
# Vendor/TensorFlowLiteC — the prebuilt static frameworks Google publishes for its CocoaPods pod, checked against
# a pinned SHA-256. Too big for the repo (≈130 MB), so run this once before building the iOS app.
#
# Google ships no Mac Catalyst slice, so the Mac app gets one made from the iPhone (ios-arm64) slice: the same
# object file, only its LC_BUILD_VERSION platform retagged from iOS to Mac Catalyst. The machine code is byte for
# byte the iPhone app's, so the Mac's AI scores come from exactly the code the phones run. (Both are arm64 with the
# same ABI; the objects use nothing macOS lacks, and the Metal delegate's "iOS 13/14 or later" checks answer
# correctly in a Catalyst process — checked with __isPlatformVersionAtLeast on macOS 27.)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
URL="https://dl.google.com/tflite-release/ios/prod/tensorflow/lite/release/ios/release/32/20240729-115310/TensorFlowLiteC/2.17.0/0c10b3543e01f547/TensorFlowLiteC-2.17.0.tar.gz"
SHA="9667b476015f136e5b332ce040e12822c4ac6d5c58947882ddc809cdff0fb99e"
DEST="$HERE/TensorFlowLiteC"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ -d "$DEST/TensorFlowLiteC.xcframework" ] && [ -d "$DEST/TensorFlowLiteCMetal.xcframework" ]; then
    echo "TensorFlowLiteC already in $DEST"
else
    curl -fL --retry 3 -o "$TMP/tflite.tar.gz" "$URL"
    echo "$SHA  $TMP/tflite.tar.gz" | shasum -a 256 -c -
    tar -xzf "$TMP/tflite.tar.gz" -C "$TMP"
    mkdir -p "$DEST"
    cp -R "$TMP"/TensorFlowLiteC-2.17.0/Frameworks/TensorFlowLiteC.xcframework "$TMP"/TensorFlowLiteC-2.17.0/Frameworks/TensorFlowLiteCMetal.xcframework "$DEST/"
    echo "TensorFlowLiteC 2.17.0 → $DEST"
fi

# $1: TensorFlowLiteC or TensorFlowLiteCMetal — adds an ios-arm64-maccatalyst slice to its xcframework
add_catalyst_slice() {
    local name="$1" xc="$DEST/$1.xcframework" work="$TMP/$1"
    if [ -d "$xc/ios-arm64-maccatalyst" ]; then echo "$name: Mac Catalyst slice already there"; return; fi
    mkdir -p "$work/catalyst"
    cp -R "$xc/ios-arm64/$name.framework" "$work/catalyst/"
    lipo -thin arm64 "$xc/ios-arm64/$name.framework/$name" -output "$work/$name.o"
    python3 -I - "$work/$name.o" <<'PY'
import struct, sys
path = sys.argv[1]
b = bytearray(open(path, "rb").read())
magic, _, _, filetype, ncmds = struct.unpack_from("<IiiII", b, 0)
assert magic == 0xFEEDFACF and filetype == 1, "expected a 64-bit Mach-O object (MH_OBJECT)"
off, patched = 32, 0
for _ in range(ncmds):
    cmd, size = struct.unpack_from("<II", b, off)
    if cmd == 0x32:                                   # LC_BUILD_VERSION: cmd, cmdsize, platform, minos, sdk, ntools
        platform, = struct.unpack_from("<I", b, off + 8)
        assert platform == 2, f"expected platform 2 (iOS), found {platform}"
        struct.pack_into("<II", b, off + 8, 6, 14 << 16)   # 6 = PLATFORM_MACCATALYST, minos 14.0 (Catalyst on arm64)
        patched += 1
    off += size
assert patched == 1, f"{patched} LC_BUILD_VERSION commands"
open(path, "wb").write(b)
PY
    cp "$work/$name.o" "$work/catalyst/$name.framework/$name"
    xcodebuild -create-xcframework -framework "$xc/ios-arm64/$name.framework" \
        -framework "$xc/ios-arm64_x86_64-simulator/$name.framework" \
        -framework "$work/catalyst/$name.framework" -output "$work/$name.xcframework" >/dev/null
    [ -f "$xc/PrivacyInfo.xcprivacy" ] && cp "$xc/PrivacyInfo.xcprivacy" "$work/$name.xcframework/"
    rm -rf "$xc"
    mv "$work/$name.xcframework" "$xc"
    echo "$name: Mac Catalyst slice added"
}
add_catalyst_slice TensorFlowLiteC
add_catalyst_slice TensorFlowLiteCMetal
