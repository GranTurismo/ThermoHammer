"""Publish gate for a built Gemma 3 1B NPU file: is it the LLM test's model, and does the whole model run on the NPU?

python -I verify.py Gemma3-1B-IT_q<4|8>_ekv1280_<soc>.litertlm [--json result.json]

Exit 0 only when:
  * the file has the five sections the apps' LiteRT-LM 0.19.1 runtime expects (LlmMetadata, SentencePiece
    tokenizer, embedder, aux, prefill_decode);
  * its LlmMetadata is Google's for Gemma 3 1B IT (start token 2, stops <end_of_turn> and 1, 1,280 tokens);
  * prefill_decode has the prefill_128 and decode signatures, and each is one vendor DISPATCH_OP: the whole
    transformer was compiled for the NPU. A partial compile (some ops left for the CPU) would run, but slower: the
    chip would lose points for our build, not its NPU, so such a file is never published;
  * the compiled bytecode was stamped by the chip's own vendor (Qualcomm for sm*, MediaTek for mt*);
  * the sizes are in the range of Google's files.
"""
import json
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compare  # pylint: disable=g-import-not-at-top

VENDOR = {"sm": "Qualcomm", "mt": "MediaTek"}
# Google's 4-bit files: Snapdragon ~689-694 MB, Dimensity ~1,020 MB; ours land within 0.5 %. 8-bit weights roughly
# double the transformer's part.
SIZE_RANGE = {(4, "Qualcomm"): (600e6, 800e6), (4, "MediaTek"): (900e6, 1150e6),
              (8, "Qualcomm"): (1000e6, 1900e6), (8, "MediaTek"): (1000e6, 1900e6)}


def check(path):
  problems = []
  m = re.search(r"_q([48])_ekv1280_([a-z]+[0-9]+)\.litertlm$", os.path.basename(path))
  if not m:
    return [f"can't read the precision and chip from {os.path.basename(path)}"], {}
  bits, soc = int(m.group(1)), m.group(2)
  vendor = VENDOR.get(soc[:2])
  if not vendor:
    return [f"unknown chip maker for {soc}"], {}
  with tempfile.TemporaryDirectory() as td:
    s = compare.summarize(path, td, "f")

  kinds = {(x["type"], x["model_type"].lower()) for x in s["sections"]}
  types = {x["type"] for x in s["sections"]}
  for need in ("LlmMetadataProto", "SP_Tokenizer"):
    if need not in types:
      problems.append(f"section {need} missing")
  for mt in ("tf_lite_embedder", "tf_lite_aux", "tf_lite_prefill_decode"):
    if ("TFLiteModel", mt) not in kinds:
      problems.append(f"section {mt} missing")

  meta = s.get("llm_metadata") or ""
  for want in (r"start_token\s*\{\s*token_ids\s*\{\s*ids:\s*2\b", r'token_str:\s*"<end_of_turn>"',
               r"token_ids\s*\{\s*ids:\s*1\b", r"max_num_tokens:\s*1280\b"):
    if not re.search(want, meta):
      problems.append(f"LlmMetadata lacks {want!r}")

  main = s["tflite"].get("TF_LITE_PREFILL_DECODE")
  if not main:
    problems.append("no prefill_decode model")
  else:
    sigs = set(main["signatures"])
    for need in ("prefill_128", "decode"):
      if need not in sigs:
        problems.append(f"prefill_decode has no {need} signature (has {sorted(sigs)})")
    off_npu = [(i, ops) for i, ops in enumerate(main["subgraph_ops"]) if ops != {"CUSTOM:DISPATCH_OP": 1}]
    if off_npu:
      i, ops = off_npu[0]
      problems.append(f"prefill_decode isn't fully on the NPU: {len(off_npu)} of {len(main['subgraph_ops'])} "
                      f"subgraphs run other ops (first: subgraph {i} {ops})")
    stamp = bytes.fromhex(main["metadata"].get("LiteRtStamp", "")[:64] or "00").rstrip(b"\0").decode("latin1", "replace")
    if not stamp.startswith(vendor):
      problems.append(f"compiled by {stamp or 'nobody'}, not {vendor}")
  if not s["tflite"].get("TF_LITE_EMBEDDER"):
    problems.append("no embedder model")

  lo, hi = SIZE_RANGE[(bits, vendor)]
  if not lo <= s["size"] <= hi:
    problems.append(f"size {s['size']:,} bytes is outside {lo:,.0f}-{hi:,.0f}")
  info = {"soc": soc, "vendor": vendor, "weight_bits": bits, "size": s["size"],
          "sdk": sorted({v for t in s["tflite"].values() for b in (t.get("bytecode") or {}).values()
                         for v in (b.get("sdk_versions") or [])})}
  return problems, info


def main(argv):
  if not argv:
    print(__doc__)
    return 2
  out_json = None
  if "--json" in argv:
    i = argv.index("--json")
    out_json = argv[i + 1]
    del argv[i:i + 2]
  problems, info = check(argv[0])
  result = {"file": os.path.basename(argv[0]), "ok": not problems, "problems": problems, **info}
  if out_json:
    with open(out_json, "w") as f:
      json.dump(result, f, indent=1)
  print(("OK " if not problems else "REJECTED ") + result["file"])
  for p in problems:
    print("  - " + p)
  return 0 if not problems else 1


if __name__ == "__main__":
  sys.exit(main(sys.argv[1:]))
