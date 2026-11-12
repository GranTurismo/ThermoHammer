#!/bin/bash
# Builds Microsoft's makemsix (github.com/microsoft/msix-packaging) for this Mac, once, into msix/.tools.
# build.sh calls it when makemsix is missing. Needs git, cmake and Xcode's command-line tools.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$HERE/.tools"
SRC="$TOOLS/msix-packaging"
if [ -x "$SRC/.vs/bin/makemsix" ]; then echo "$SRC/.vs/bin/makemsix"; exit 0; fi
mkdir -p "$TOOLS"
[ -d "$SRC" ] || git clone --depth 1 https://github.com/microsoft/msix-packaging.git "$SRC" >&2
cd "$SRC"
./makemac.sh --pack -arch "$(uname -m)" --skip-samples --skip-tests >&2
echo "$SRC/.vs/bin/makemsix"
