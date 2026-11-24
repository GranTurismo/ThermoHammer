"""Downloads the weights every ThermoHammer NPU model file is built from: Google's Gemma 3 1B IT QAT int4 checkpoint
(unquantized safetensors), checked file by file against the SHA-256s below.

    python3 tools/npu_models/fetch_weights.py <folder>

Why QAT: at 4-bit weights, the plain gemma-3-1b-it gives wrong answers ("3 apples, eat one" -> 6); the QAT checkpoint
is trained for 4 bits and is what Google's own NPU files were built from.

Sources, in order: Google's gated repo (needs HF_TOKEN from an account that accepted the Gemma terms), then a public
mirror whose files are byte-identical (same SHA-256). Either way nothing unverified is kept.
"""
import hashlib
import os
import sys
import urllib.request

REPOS = ["google/gemma-3-1b-it-qat-int4-unquantized", "f14xuanlv/gemma-3-1b-it-qat-int4-unquantized"]
FILES = {
    "added_tokens.json": "50b2f405ba56a26d4913fd772089992252d7f942123cc0a034d96424221ba946",
    "config.json": "fe40a10186f2d026eeca90f55b9f749ee72a38a39f295bd1acb9085fc5a4d363",
    "generation_config.json": "13da6aad6852a008419f46df754b3452fc96387a4213c25811509d474e5a4776",
    "model.safetensors": "3dddcbaeec1e8b310dbd98fdb581d07c948859ff6c8e7a2b5ea17c1b33ae07e7",
    "special_tokens_map.json": "2f7b0adf4fb469770bb1490e3e35df87b1dc578246c5e7e6fc76ecf33213a397",
    "tokenizer_config.json": "3055fe361d3191a709c9150d74acb068d07de52c62cdf38faa1416560ed9c91f",
    "tokenizer.json": "7d4046bf0505a327dd5a0abbb427ecd4fc82f99c2ceaa170bc61ecde12809b0c",
    "tokenizer.model": "1299c11d7cf632ef3b4e11937501358ada021bbdf7c47638d13c0ee982f2e79c",
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(repo, name, dest, token):
    url = f"https://huggingface.co/{repo}/resolve/main/{name}"
    headers = {"User-Agent": "thermohammer-npu-models"}
    if token and repo.startswith("google/"):
        headers["Authorization"] = f"Bearer {token}"
    part = dest + ".part"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r, open(part, "wb") as out:
        while chunk := r.read(1 << 20):
            out.write(chunk)
    os.replace(part, dest)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    folder = sys.argv[1]
    os.makedirs(folder, exist_ok=True)
    token = os.environ.get("HF_TOKEN", "")
    repos = REPOS if token else REPOS[1:]
    for name, want in FILES.items():
        dest = os.path.join(folder, name)
        if os.path.isfile(dest) and sha256(dest) == want:
            print(f"ok    {name}")
            continue
        for repo in repos:
            try:
                fetch(repo, name, dest, token)
            except Exception as e:  # pylint: disable=broad-except
                print(f"      {name} from {repo}: {e}")
                continue
            if sha256(dest) == want:
                print(f"ok    {name} ({repo})")
                break
            os.remove(dest)
            print(f"      {name} from {repo}: SHA-256 differs, dropped")
        else:
            print(f"FAILED {name}: no source gave the expected file")
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
