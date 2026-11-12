#!/bin/bash
# Builds the Microsoft Store packages on a Mac: publishes the app for each architecture, adds the manifest and the logos and
# packs one .msix per architecture with Microsoft's makemsix. Partner Center takes both files in one submission and hands each
# PC the package for its processor (makemsix can only write "flat" bundles, which the Store doesn't accept, so no .msixbundle).
# Usage: msix/build.sh [--store] [--skip-publish] [x64|arm64]...  → msix/out/ThermoHammer_<version>_<arch>.msix
#   --store          refuse to build while msix/identity.env still holds the placeholder identity
#   --skip-publish   reuse msix/.cache/publish-<arch> from the last run
# The packages are unsigned: the Store signs what it accepts. To install one on a PC for testing, see msix/README.md.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/.."
STORE=0; SKIP_PUBLISH=0; ARCHS=()
for a in "$@"; do
    case "$a" in
        --store) STORE=1 ;;
        --skip-publish) SKIP_PUBLISH=1 ;;
        x64|arm64) ARCHS+=("$a") ;;
        *) echo "unknown argument: $a" >&2; exit 2 ;;
    esac
done
[ ${#ARCHS[@]} -gt 0 ] || ARCHS=(x64 arm64)

# shellcheck source=identity.env
. "$HERE/identity.env"
if [ "$STORE" = 1 ] && [ "$IDENTITY_NAME" = "GranTurismo.ThermoHammer" -o "$PUBLISHER" = "CN=GranTurismo" ]; then
    echo "msix/identity.env still has the placeholder identity: copy the Name and Publisher from Partner Center first." >&2; exit 1
fi

# the package version: the app's Version from the project (2.0.4 → 2.0.4.0; the Store wants the last part to be 0)
APPVER="$(sed -n 's|.*<Version>\([0-9]*\.[0-9]*\.[0-9]*\)</Version>.*|\1|p' "$ROOT/ThermoHammer.csproj" | head -1)"
[ -n "$APPVER" ] || { echo "no <Version> in ThermoHammer.csproj" >&2; exit 1; }
VERSION="$APPVER.0"

MAKEMSIX="${MAKEMSIX:-$("$HERE/fetch_makemsix.sh")}"
ICON="$ROOT/../CpuThrottlingTest/Assets.xcassets/AppIcon.appiconset/icon_1024.png"
[ -f "$ICON" ] || { echo "missing $ICON" >&2; exit 1; }
BG=06090B      # Forge.Bg, the app's background

OUT="$HERE/out"
mkdir -p "$HERE/.cache" "$OUT"

# Logos (the Images folder; not Assets, which the app already uses with another case on a case-insensitive disk)
logos() {
    local d="$1"; mkdir -p "$d"
    sq() { sips -s format png -z "$2" "$2" "$ICON" --out "$d/$1.png" >/dev/null; }
    for s in 100 200 400; do
        sq "Square44x44Logo.scale-$s"   $((44*s/100))
        sq "Square150x150Logo.scale-$s" $((150*s/100))
        sq "StoreLogo.scale-$s"         $((50*s/100))
        h=$((150*s/100)); w=$((310*s/100))
        sips -s format png -z "$h" "$h" "$ICON" --out "$d/.w.png" >/dev/null
        sips -s format png --padToHeightWidth "$h" "$w" --padColor "$BG" "$d/.w.png" --out "$d/Wide310x150Logo.scale-$s.png" >/dev/null
        rm -f "$d/.w.png"
    done
    for t in 16 24 32 48 256; do
        sq "Square44x44Logo.targetsize-$t" "$t"
        sq "Square44x44Logo.altform-unplated_targetsize-$t" "$t"
    done
    # the names the manifest gives (the first scale found stands in for them)
    cp "$d/Square44x44Logo.scale-200.png"   "$d/Square44x44Logo.png"
    cp "$d/Square150x150Logo.scale-200.png" "$d/Square150x150Logo.png"
    cp "$d/StoreLogo.scale-200.png"         "$d/StoreLogo.png"
    cp "$d/Wide310x150Logo.scale-200.png"   "$d/Wide310x150Logo.png"
}

for A in "${ARCHS[@]}"; do
    PUB="$HERE/.cache/publish-$A"
    if [ "$SKIP_PUBLISH" = 0 ] || [ ! -d "$PUB" ]; then
        rm -rf "$PUB"
        dotnet publish "$ROOT/ThermoHammer.csproj" -c Release -r "win-$A" -o "$PUB" >/dev/null
    fi
    STAGE="$HERE/.cache/stage-$A"
    rm -rf "$STAGE"; mkdir -p "$STAGE"
    cp -R "$PUB/." "$STAGE/"
    logos "$STAGE/Images"
    sed -e "s|@IDENTITY_NAME@|$IDENTITY_NAME|g" -e "s|@PUBLISHER@|$PUBLISHER|g" \
        -e "s|@PUBLISHER_DISPLAY_NAME@|$PUBLISHER_DISPLAY_NAME|g" -e "s|@VERSION@|$VERSION|g" -e "s|@ARCH@|$A|g" \
        "$HERE/AppxManifest.xml.in" > "$STAGE/AppxManifest.xml"
    "$MAKEMSIX" pack -d "$STAGE" -p "$OUT/ThermoHammer_${VERSION}_$A.msix"
done

ls -la "$OUT"
