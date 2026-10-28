#!/bin/bash
# Builds the Windows installers on a Mac: publishes the app and compiles installer/ThermoHammer.iss with Inno Setup
# running under CrossOver's Wine (Inno Setup installed once in the Wine prefix: C:\InnoSetup).
# Usage: installer/build.sh [x64|arm64]...   → installer/out/ThermoHammer-<version>-<arch>-Setup.exe
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/.."
WINE="${WINE:-/Applications/CrossOver.app/Contents/SharedSupport/CrossOver/bin/wineloader}"
export WINEPREFIX="${WINEPREFIX:?set WINEPREFIX to a Wine prefix with Inno Setup installed in C:\\InnoSetup}"
export WINEDEBUG=-all
ARCHS=("$@"); [ ${#ARCHS[@]} -gt 0 ] || ARCHS=(x64 arm64)
mkdir -p "$HERE/out" "$HERE/.cache"
for A in "${ARCHS[@]}"; do
    REDIST="$HERE/.cache/vc_redist.$A.exe"
    [ -f "$REDIST" ] || curl -fsSL -o "$REDIST" "https://aka.ms/vs/17/release/vc_redist.$A.exe"
    PUB="$HERE/.cache/publish-$A"
    rm -rf "$PUB"
    dotnet publish "$ROOT/ThermoHammer.csproj" -c Release -r "win-$A" -o "$PUB" >/dev/null
    z() { echo "Z:${1//\//\\}"; }
    "$WINE" 'C:\InnoSetup\ISCC.exe' "/DArch=$A" "/DPublishDir=$(z "$PUB")" "/DRedist=$(z "$REDIST")" "/DOutDir=$(z "$HERE/out")" "$(z "$HERE/ThermoHammer.iss")"
done
ls -la "$HERE/out"
