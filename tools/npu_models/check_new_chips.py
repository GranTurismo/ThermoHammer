"""What the NPU model build should do next: the Android chips seen in this scoring version's runs that have no Gemma NPU
model yet (GET thapi /benchmark/chips), and for each, whether Google has published one since (litert-community on
Hugging Face) — then it only needs mirroring — or it has to be built (tools/npu_models/build, the chip makers' compilers).

    python3 tools/npu_models/check_new_chips.py            → a table; exit code 1 when something needs doing
    python3 tools/npu_models/check_new_chips.py --json     → the same for a CI job
"""
import json, re, sys, urllib.request

API = "https://thapi.gtgroup.dev/benchmark/chips"
HF_TREE = "https://huggingface.co/api/models/litert-community/Gemma3-1B-IT/tree/main"


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "thermohammer-npu-models"}), timeout=30) as r:
        return json.load(r)


def main():
    chips = get(API)["chips"]
    google = {}
    for f in get(HF_TREE):
        m = re.fullmatch(r"Gemma3-1B-IT_q4_ekv1280_([a-z]+[0-9]+)\.litertlm", f.get("path", ""))
        if m:
            google[m.group(1)] = f["path"]
    todo = [{"soc": c["soc"], "runs": c["runs"], "devices": c["devices"],
             "action": "mirror Google's file" if c["soc"] in google else "build"}
            for c in chips if not c["hasNpuModel"]]
    if "--json" in sys.argv:
        print(json.dumps(todo, indent=1))
    else:
        for t in todo:
            print(f"{t['soc']:10} {t['runs']:4} runs  {t['action']:22} {', '.join(t['devices'])}")
        print(f"{len(todo)} chip(s) without an NPU model" if todo else "every chip seen has an NPU model")
    sys.exit(1 if todo else 0)


if __name__ == "__main__":
    main()
