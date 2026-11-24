"""Rebuilds models/android/manifest.json from the files in that folder (runs on the server, from publish.sh or the CI job).

    python3 manifest.py <models/android folder>

A chip is listed only when its Gemma3-1B-IT_q4_ekv1280_<soc>.litertlm has <file>.sha256 beside it and the file's size is
what was published (a copy still in progress has neither). The apps (NpuServerModels.kt) download what it lists; the
server (NpuChipCatalog.cs) then requires those chips' NPU part.
"""
import json
import os
import re
import sys


def build(folder):
    chips = {}
    for name in sorted(os.listdir(folder)):
        m = re.fullmatch(r"Gemma3-1B-IT_q4_ekv1280_([a-z]+[0-9]+)\.litertlm", name)
        sha_file = os.path.join(folder, name + ".sha256")
        if not m or not os.path.isfile(sha_file):
            continue
        sha = open(sha_file).read().split()[0].strip().lower()
        if not re.fullmatch(r"[0-9a-f]{64}", sha):
            continue
        chips[m.group(1)] = {"file": name, "sha256": sha, "size": os.path.getsize(os.path.join(folder, name))}
    tmp = os.path.join(folder, "manifest.json.part")
    with open(tmp, "w") as f:
        json.dump({"chips": chips}, f, indent=1)
    os.chmod(tmp, 0o644)
    os.replace(tmp, os.path.join(folder, "manifest.json"))
    return chips


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    print("manifest.json:", ", ".join(build(sys.argv[1])) or "(no chips)")
