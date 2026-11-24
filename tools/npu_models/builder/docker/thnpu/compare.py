"""Structural comparison of two .litertlm files (ours vs. a reference).

python -I compare.py ours.litertlm ref.litertlm [--out report.md]

Reports: sections (type, model_type, size), LlmMetadata text, for every
TFLite section the signatures (input/output names, dtypes, shapes, int16
scales), op mix per subgraph, DISPATCH_OP partitions and bytecode sizes,
metadata entries (LiteRtStamp SoC, min_runtime_version), and the vendor
bytecode's build strings (QNN SDK version, HTP options).
"""
import io
import json
import mmap
import os
import re
import struct
import sys
import tempfile

from litert_lm_builder import litertlm_peek

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import inspect_tflite  # pylint: disable=g-import-not-at-top


def unpack(path, d):
  buf = io.StringIO()
  litertlm_peek.peek_litertlm_file(path, d, buf)
  return buf.getvalue()


def sections(peek_txt):
  out = []
  for m in re.finditer(
      r"Section (\d+):.*?Begin Offset: (\d+)\s+End Offset:\s+(\d+)\s+Data Type:\s+(\S+)",
      peek_txt, re.S):
    blk = peek_txt[m.start():m.end()]
    mt = re.search(r"model_type, Value \(String\): (\S+)", blk)
    out.append({"idx": int(m.group(1)), "type": m.group(4),
                "model_type": mt.group(1) if mt else "",
                "size": int(m.group(3)) - int(m.group(2))})
  return out


def bytecode_strings(path, info):
  """QNN/Neuron build strings from the first DISPATCH_OP bytecode."""
  res = {}
  with open(path, "rb") as f:
    mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
    seen = set()
    for sg in info["subgraphs"]:
      for c in sg["custom_samples"]:
        if "DISPATCH" not in c["op"]:
          continue
        h = c["custom_options_head"].encode("latin1")
        # flexbuffer map; decode properly
        from flatbuffers import flexbuffers  # pylint: disable=g-import-not-at-top
        try:
          d = flexbuffers.Loads(h)
        except Exception:  # pylint: disable=broad-except
          continue
        key = (d.get("bytecode_offset"), d.get("bytecode_size"))
        if key in seen:
          continue
        seen.add(key)
        off, size = key
        chunk = mm[off:off + min(size, 64 << 20)]
        strs = set(re.findall(rb"v2\.\d+\.\d+\.\d+[0-9_.a-f]*", chunk))
        opt = re.search(rb"OpO=[^\x00]{20,2000}", chunk)
        res[f"{d.get('name')}@{off}"] = {
            "bytecode_size": size,
            "sdk_versions": sorted(s.decode() for s in strs)[:4],
            "htp_options": (opt.group(0)[:600].decode("latin1") if opt else None),
        }
  return res


def summarize(path, workdir, tag):
  d = os.path.join(workdir, tag)
  os.makedirs(d, exist_ok=True)
  txt = unpack(path, d)
  secs = sections(txt)
  meta = re.search(r"<<<<<<<< start of LlmMetadata\n(.*?)>>>>>>>> end", txt, re.S)
  res = {"file": path, "size": os.path.getsize(path), "sections": secs,
         "llm_metadata": meta.group(1) if meta else None, "tflite": {}}
  for fn in sorted(os.listdir(d)):
    if fn.endswith(".tflite"):
      p = os.path.join(d, fn)
      sink = io.StringIO()
      old = sys.stdout
      sys.stdout = sink
      try:
        jpath = p + ".json"
        inspect_tflite.main(p, jpath)
      finally:
        sys.stdout = old
      info = json.load(open(jpath))
      mt = re.search(r"(?i)tf_lite_[a-z_]+", fn)
      res["tflite"][mt.group(0).upper() if mt else fn] = {
          "size": info["size"],
          "opcodes": info["opcodes"],
          "metadata": {m["name"]: m.get("head_text", m.get("head_hex", ""))[:200].replace("\x00", "")
                       for m in info["metadata"]},
          "subgraph_ops": [g["ops"] for g in info["subgraphs"]],
          "const_types": [g["const_tensor_types"] for g in info["subgraphs"]],
          "signatures": {s["key"]: {"in": {x["key"]: [x["dtype"], x["shape"], (x["quant"] or {}).get("scale")] for x in s["inputs"]},
                                    "out": {x["key"]: [x["dtype"], x["shape"], (x["quant"] or {}).get("scale")] for x in s["outputs"]}}
                         for s in info["signatures"]},
          "bytecode": bytecode_strings(p, info),
      }
  return res


def fmt_io(dct):
  groups = {}
  for k, (dt, sh, sc) in dct.items():
    m = re.match(r"(kv_(?:cache|slice)_[kv])_(\d+)$", k)
    base = m.group(1) + "_*" if m else k
    groups.setdefault((base, dt, tuple(sh)), []).append(sc)
  return {f"{b} {dt} {list(sh)}" + (f" x{len(v)}" if len(v) > 1 else ""): v
          for (b, dt, sh), v in groups.items()}


def compare(a, b):
  lines = [f"# {os.path.basename(a['file'])} ({a['size']:,} B) vs {os.path.basename(b['file'])} ({b['size']:,} B)", ""]
  lines.append("## Sections")
  for x in (a, b):
    lines.append(f"- {os.path.basename(x['file'])}: " + ", ".join(
        f"{s['type']}{'/' + s['model_type'] if s['model_type'] else ''} {s['size']:,}" for s in x["sections"]))
  lines.append("\n## LlmMetadata")
  same = (a["llm_metadata"] or "").strip() == (b["llm_metadata"] or "").strip()
  lines.append("identical" if same else f"OURS:\n```\n{a['llm_metadata']}```\nREF:\n```\n{b['llm_metadata']}```")
  for key in sorted(set(a["tflite"]) | set(b["tflite"])):
    ta, tb = a["tflite"].get(key), b["tflite"].get(key)
    lines.append(f"\n## {key}")
    if not ta or not tb:
      lines.append(f"only in {'ours' if ta else 'ref'}")
      continue
    lines.append(f"- size: ours {ta['size']:,} / ref {tb['size']:,}")
    lines.append(f"- metadata: ours {ta['metadata']} / ref {tb['metadata']}")
    lines.append(f"- opcodes only in ours: {sorted(set(ta['opcodes']) - set(tb['opcodes']))}; only in ref: {sorted(set(tb['opcodes']) - set(ta['opcodes']))}")
    lines.append(f"- subgraph ops ours: {ta['subgraph_ops']}")
    lines.append(f"- subgraph ops ref : {tb['subgraph_ops']}")
    lines.append(f"- const tensor types ours: {ta['const_types']}")
    lines.append(f"- const tensor types ref : {tb['const_types']}")
    for bk, bv in (("ours", ta["bytecode"]), ("ref", tb["bytecode"])):
      for name, v in bv.items():
        lines.append(f"- bytecode {bk} {name}: size {v['bytecode_size']:,}, sdk {v['sdk_versions']}")
        if v["htp_options"]:
          lines.append(f"  - options: `{v['htp_options'][:300]}`")
    sa, sb = ta["signatures"], tb["signatures"]
    lines.append(f"- signatures only in ours: {sorted(set(sa) - set(sb))}; only in ref: {sorted(set(sb) - set(sa))}")
    for s in sorted(set(sa) & set(sb)):
      for d in ("in", "out"):
        fa, fb = fmt_io(sa[s][d]), fmt_io(sb[s][d])
        if set(fa) != set(fb):
          lines.append(f"  - {s} {d}: ours-only {sorted(set(fa) - set(fb))} | ref-only {sorted(set(fb) - set(fa))}")
        else:
          lines.append(f"  - {s} {d}: same names/dtypes/shapes ({len(sa[s][d])} tensors)")
  return "\n".join(lines)


if __name__ == "__main__":
  args = sys.argv[1:]
  out = None
  if "--out" in args:
    i = args.index("--out"); out = args[i + 1]; del args[i:i + 2]
  with tempfile.TemporaryDirectory() as td:
    ra = summarize(args[0], td, "ours")
    rb = summarize(args[1], td, "ref")
  rep = compare(ra, rb)
  print(rep)
  if out:
    with open(out, "w") as f:
      f.write(rep + "\n")
    with open(out + ".json", "w") as f:
      json.dump({"ours": ra, "ref": rb}, f, indent=1, default=str)
