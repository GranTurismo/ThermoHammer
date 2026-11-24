"""Rebuilds models/android/manifest.json from the files in that folder (runs on the server, from publish.sh or the CI job).

    python3 manifest.py <models/android folder>

A chip is listed only when its Gemma3-1B-IT_<form>_ekv1280_<soc>.litertlm has <file>.sha256 beside it (a copy still in
progress has none). The form is q4 (the standard), or q8 / q4att8 for an NPU that can't run q4 whole (plan.py VARIANT);
a chip with several gets q4 (sorted first). The apps (NpuServerModels.kt) download what it lists; the
server (NpuChipCatalog.cs) then requires those chips' NPU part.
"""
import json
import os
import re
import sys


def build(folder):
    chips = {}
    for name in sorted(os.listdir(folder)):
        m = re.fullmatch(r"Gemma3-1B-IT_q[48](?:att8)?_ekv1280_([a-z]+[0-9]+)\.litertlm", name)
        sha_file = os.path.join(folder, name + ".sha256")
        if not m or not os.path.isfile(sha_file) or m.group(1) in chips:     # sorted: q4 before q4att8 and q8
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
