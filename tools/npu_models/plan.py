"""What the NPU model job should do next (scoring version 5's LLM test, Gemma 3 1B on the NPU).

    python3 tools/npu_models/plan.py           a table; exit code 1 when something needs doing
    python3 tools/npu_models/plan.py --json    the same for the CI job (.github/workflows/npu-models.yml)

It compares:
  * the phone chips our pinned toolchain can compile for: the Qualcomm and MediaTek SoC lists in the LiteRT compiler
    plugins of ai-edge-litert (the version pinned in builder/docker/compile-req.in), read from the wheel itself;
  * the chips that already have a model: in the apps' Play pack (NpuModels.CHIPS in the Android source) or on the site
    (models/android/manifest.json);
  * the chips phones actually reported (thapi /benchmark/chips, or the leaderboard before that endpoint is deployed);
  * the newest ai-edge-litert on PyPI, whose plugins may know chips our pinned one doesn't.

"build" = chips the pinned toolchain knows that have no model anywhere: the job builds them before any phone with that
chip shows up ("soc:8" = with 8-bit weights, for an NPU without 4-bit ones). "alerts" = chips phones reported that no model covers and the pinned toolchain can't build: either a
newer ai-edge-litert knows them (bump the pins) or no LiteRT compiler does yet (nothing to build from; the NPU third
stays 0 for that chip, as for any phone whose NPU no compiler supports).
"""
import io
import json
import os
import re
import sys
import urllib.error
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
NPU_MODELS_KT = os.path.join(ROOT, "ThermoHammerAndroid/app/src/main/java/com/example/thermohammer/engine/llm/NpuModels.kt")
COMPILE_REQ = os.path.join(HERE, "builder/docker/compile-req.in")
SITE_MANIFEST = os.environ.get("TH_NPU_MANIFEST_URL", "https://thermohammer.gtgroup.dev/models/android/manifest.json")
API = os.environ.get("TH_API", "https://thapi.gtgroup.dev")
UA = {"User-Agent": "thermohammer-npu-models"}
# Our build.sh compiles for these chip makers (sm* = Qualcomm QNN, mt* = MediaTek NeuroPilot).
VENDOR_PLUGINS = {"qualcomm": "sm", "mediatek": "mt"}
# Chips the pinned toolchain names but can't give a publishable file (checked 2026-11-24, CI run 45417736085 and
# locally). Not rebuilt every day; remove one to try again with a new toolchain.
#   NPU_CANT_HOLD: the chip's NPU can't run this model (int16 activations, int4 weights) as a whole: its NPU third is 0,
#                  the hardware's limit, as for a phone without an NPU
#   NO_COMPILER:   the chip maker's compiler fails on it: a software gap, reported in the issue while phones use the chip
_ATTN = "Hexagon {}: attention's 100 int16 BATCH_MATMULs stay on the CPU"
_DT = "{}: NPU has no int16 activations / int4 weights (\"unsupported data type\")"
NPU_CANT_HOLD = {
    "sm8350": _ATTN.format("v68"), "sm8450": _ATTN.format("v69"), "sm8475": _ATTN.format("v69"),
    "mt6853": _DT.format("Dimensity 720"), "mt6877": _DT.format("Dimensity 900/1080"), "mt6893": _DT.format("Dimensity 1200"),
    "mt8188": _DT.format("MT8188"),
    "mt6879": "Dimensity 920-class: MDLA/EDMA \"unsupported operation\" on 1,128 ops",
}
# Every chip runs the same model in the most compact form its NPU runs whole: 4-bit weights, else 8-bit. These NPUs
# take everything but 4-bit weights (at 4 bits the 177 FULLY_CONNECTED layers stayed on the CPU, run 45417736085), so
# they get 8-bit weights: a bigger file and a slower reply than 4-bit hardware would give, but the same task, scored.
NEEDS_W8 = {
    "mt6878": "Dimensity 7300", "mt6983": "Dimensity 9000", "mt6985": "Dimensity 9200",
}
NO_COMPILER = {
    "mt6886": "NeuroPilot 8's compiler: \"Not a valid platform: mt6886\"",
    "mt6895": "NeuroPilot 8's compiler: \"Not a valid platform: mt6895\"",
    "mt8171": "NeuroPilot's compiler stops without an error",
}


def get(url, binary=False):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        data = r.read()
    return data if binary else json.loads(data)


def get_or_none(url):
    try:
        return get(url)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def pinned_version():
    m = re.search(r"^ai-edge-litert==([0-9.]+)", open(COMPILE_REQ).read(), re.M)
    return m.group(1)


def plugin_socs(version):
    """Phone SoCs (Build.SOC_MODEL, lower case) the version's Qualcomm and MediaTek compiler plugins accept."""
    rel = get(f"https://pypi.org/pypi/ai-edge-litert/{version}/json")
    wheel = next(u["url"] for u in rel["urls"]
                 if u["filename"].endswith(".whl") and "manylinux" in u["filename"] and "x86_64" in u["filename"])
    z = zipfile.ZipFile(io.BytesIO(get(wheel, binary=True)))
    socs = set()
    for vendor, prefix in VENDOR_PLUGINS.items():
        src = z.read(f"ai_edge_litert/aot/vendors/{vendor}/target.py").decode()
        body = re.search(r"class SocModel\(.*?\):(.*?)\nclass ", src, re.S).group(1)
        socs |= {v.lower() for v in re.findall(r'^\s+[A-Z0-9_]+ = "([A-Za-z0-9_]+)"', body, re.M)
                 if v.lower().startswith(prefix)}
    return socs


# The Play pack's chips, for a checkout without the version 5 Android code (main before the release).
PACK_FALLBACK = {"sm8550", "sm8650", "sm8750", "sm8850", "mt6989", "mt6991", "mt6993"}


def pack_chips():
    if not os.path.isfile(NPU_MODELS_KT):
        return set(PACK_FALLBACK)
    m = re.search(r"val CHIPS = setOf\(([^)]*)\)", open(NPU_MODELS_KT).read())
    return set(re.findall(r'"([a-z0-9]+)"', m.group(1)))


def published_chips():
    doc = get_or_none(SITE_MANIFEST)
    return set((doc or {}).get("chips", {})), doc is not None


def seen_chips():
    """Android chips phones reported in runs: {soc: [devices]}."""
    doc = get_or_none(f"{API}/benchmark/chips")
    if doc is not None:
        return {c["soc"].lower(): c.get("devices", []) for c in doc["chips"]}, "/benchmark/chips"
    seen = {}
    for r in get(f"{API}/benchmark/leaderboard"):
        if r.get("os") == 2 and r.get("socModel"):      # 2 = Android
            seen.setdefault(r["socModel"].lower(), []).append(r.get("deviceModel", ""))
    return seen, "leaderboard (fallback)"


def plan():
    pinned = pinned_version()
    latest = get("https://pypi.org/pypi/ai-edge-litert/json")["info"]["version"]
    can_build = plugin_socs(pinned)
    newer = plugin_socs(latest) if latest != pinned else can_build
    pack = pack_chips()
    published, site_up = published_chips()
    seen, seen_source = seen_chips()
    have = pack | published
    alerts = []
    for soc in sorted(set(seen) - have - set(NPU_CANT_HOLD) - (can_build - set(NO_COMPILER))):
        devices = ", ".join(sorted(set(seen[soc])))[:200]
        if soc in NO_COMPILER:
            alerts.append({"soc": soc, "devices": devices, "why": f"listed by the plugin, but {NO_COMPILER[soc]}"})
        elif soc in newer:
            alerts.append({"soc": soc, "devices": devices,
                           "why": f"ai-edge-litert {latest} can compile for it (pinned: {pinned}): bump the pins in "
                                  f"tools/npu_models/builder/docker and rebuild"})
        else:
            alerts.append({"soc": soc, "devices": devices,
                           "why": f"no LiteRT compiler (ai-edge-litert {latest}) targets it yet"})
    return {
        "pinned": pinned, "latest": latest, "site_manifest": site_up, "seen_source": seen_source,
        "can_build": sorted(can_build), "pack": sorted(pack), "published": sorted(published),
        # "soc" = 4-bit weights, "soc:8" = 8-bit (NEEDS_W8)
        "build": sorted(f"{s}:8" if s in NEEDS_W8 else s for s in can_build - have - set(NPU_CANT_HOLD) - set(NO_COMPILER)),
        "needs_w8": NEEDS_W8,
        "npu_cant_hold": NPU_CANT_HOLD,
        "no_compiler": NO_COMPILER,
        "alerts": alerts,
        "newer_toolchain_adds": sorted(newer - can_build),
    }


def main():
    p = plan()
    if "--json" in sys.argv:
        print(json.dumps(p, indent=1))
        return 0
    print(f"toolchain: ai-edge-litert {p['pinned']} (newest {p['latest']})")
    print(f"can build: {' '.join(p['can_build'])}")
    print(f"have:      pack {' '.join(p['pack'])} | site {' '.join(p['published']) or '-'}"
          + ("" if p["site_manifest"] else " (site manifest not found)"))
    print(f"build:     {' '.join(p['build']) or 'nothing'}")
    print(f"NPU can't hold the model: {' '.join(p['npu_cant_hold']) or '-'}")
    print(f"8-bit weights:            {' '.join(p['needs_w8']) or '-'}")
    print(f"compiler fails:           {' '.join(p['no_compiler']) or '-'}")
    for a in p["alerts"]:
        print(f"ALERT {a['soc']:10} {a['why']}  [{a['devices']}]")
    if p["newer_toolchain_adds"]:
        print(f"newer toolchain adds: {' '.join(p['newer_toolchain_adds'])}")
    return 1 if p["build"] or p["alerts"] else 0


if __name__ == "__main__":
    sys.exit(main())
