"""Structural dump of a .tflite flatbuffer (signatures, ops, metadata, buffers).

Usage: python -I inspect_tflite.py model.tflite [--json out.json]
Reads the file lazily (mmap) so large models are fine.
"""
import collections
import json
import mmap
import sys

try:
  from ai_edge_litert import schema_py_generated as S
except ImportError:  # inside the litert-torch image
  from tflite_schema import schema_py_generated as S  # type: ignore

TT = {v: k for k, v in vars(S.TensorType).items() if not k.startswith("_")}
BO = {v: k for k, v in vars(S.BuiltinOperator).items() if not k.startswith("_")}


def s(b):
  return b.decode("utf-8", "replace") if isinstance(b, bytes) else b


def tensor_info(sg, ti, model):
  t = sg.Tensors(ti)
  shape = [int(x) for x in t.ShapeAsNumpy()] if t.ShapeLength() else []
  q = t.Quantization()
  qd = None
  if q is not None and q.ScaleLength():
    qd = {"n_scales": q.ScaleLength(), "qdim": q.QuantizedDimension()}
    if q.ScaleLength() == 1:
      qd["scale"] = float(q.Scale(0))
      qd["zp"] = int(q.ZeroPoint(0)) if q.ZeroPointLength() else 0
  if q is not None and q.DetailsType():
    qd = (qd or {}) | {"details_type": int(q.DetailsType())}
  return {"name": s(t.Name()), "shape": shape, "dtype": TT.get(t.Type(), t.Type()),
          "buffer": t.Buffer(), "quant": qd}


def main(path, out_json=None):
  with open(path, "rb") as f:
    mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
  buf = memoryview(mm)
  m = S.Model.GetRootAs(buf, 0)
  info = {"file": path, "size": len(buf), "version": m.Version(),
          "description": s(m.Description())}
  opcodes = []
  for i in range(m.OperatorCodesLength()):
    oc = m.OperatorCodes(i)
    code = max(oc.BuiltinCode(), oc.DeprecatedBuiltinCode())
    name = BO.get(code, str(code))
    if name == "CUSTOM":
      name = "CUSTOM:" + s(oc.CustomCode())
    opcodes.append(name)
  info["opcodes"] = opcodes
  # metadata
  md = []
  for i in range(m.MetadataLength()):
    e = m.Metadata(i)
    b = m.Buffers(e.Buffer())
    size = b.DataLength() if b.DataLength() else b.Size()
    entry = {"name": s(e.Name()), "buffer": e.Buffer(), "size": int(size),
             "offset": int(b.Offset())}
    raw = b.DataAsNumpy().tobytes() if b.DataLength() else (
        bytes(buf[b.Offset():b.Offset() + min(b.Size(), 4096)]) if b.Offset() > 1 else b"")
    if raw:
      txt = raw[:400]
      entry["head_hex"] = txt[:32].hex()
      printable = sum(32 <= c < 127 for c in txt) / max(1, len(txt))
      if printable > 0.8:
        entry["head_text"] = txt.decode("latin1")
    md.append(entry)
  info["metadata"] = md
  # buffers summary
  nbuf = m.BuffersLength()
  total_inline = total_ext = 0
  big = []
  for i in range(nbuf):
    b = m.Buffers(i)
    dl = b.DataLength()
    if dl:
      total_inline += dl
      sz = dl
    else:
      sz = b.Size() if b.Offset() > 1 else 0
      total_ext += sz
    if sz > 1_000_000:
      big.append((i, int(sz), int(b.Offset())))
  info["buffers"] = {"count": nbuf, "inline_bytes": int(total_inline),
                     "offset_bytes": int(total_ext), "big": big[:40],
                     "n_big": len(big)}
  # subgraphs
  sgs = []
  for gi in range(m.SubgraphsLength()):
    sg = m.Subgraphs(gi)
    ops = collections.Counter()
    custom = []
    for oi in range(sg.OperatorsLength()):
      op = sg.Operators(oi)
      name = opcodes[op.OpcodeIndex()]
      ops[name] += 1
      if name.startswith("CUSTOM:") and len(custom) < 8:
        co = op.CustomOptionsAsNumpy().tobytes() if op.CustomOptionsLength() else b""
        custom.append({"op": name, "n_in": op.InputsLength(), "n_out": op.OutputsLength(),
                       "custom_options_len": len(co),
                       "custom_options_head": co[:200].decode("latin1")})
    qtypes = collections.Counter()
    for ti in range(sg.TensorsLength()):
      t = sg.Tensors(ti)
      if t.Buffer() and m.Buffers(t.Buffer()) is not None:
        b = m.Buffers(t.Buffer())
        if b.DataLength() or b.Offset() > 1:
          q = t.Quantization()
          nq = q.ScaleLength() if q is not None else 0
          qtypes[f"{TT.get(t.Type())}/{'perchan' if nq > 1 else ('pertensor' if nq == 1 else 'float')}"] += 1
    sgs.append({"index": gi, "name": s(sg.Name()), "n_tensors": sg.TensorsLength(),
                "n_ops": sg.OperatorsLength(), "ops": dict(ops.most_common()),
                "const_tensor_types": dict(qtypes), "custom_samples": custom,
                "inputs": [tensor_info(sg, sg.Inputs(j), m)["name"] for j in range(sg.InputsLength())][:5]})
  info["subgraphs"] = sgs
  # signatures
  sigs = []
  for si in range(m.SignatureDefsLength()):
    sd = m.SignatureDefs(si)
    sg = m.Subgraphs(sd.SubgraphIndex())
    ins = []
    for j in range(sd.InputsLength()):
      tm = sd.Inputs(j)
      ti = tensor_info(sg, tm.TensorIndex(), m)
      ins.append({"key": s(tm.Name()), **{k: ti[k] for k in ("shape", "dtype", "quant")}})
    outs = []
    for j in range(sd.OutputsLength()):
      tm = sd.Outputs(j)
      ti = tensor_info(sg, tm.TensorIndex(), m)
      outs.append({"key": s(tm.Name()), **{k: ti[k] for k in ("shape", "dtype", "quant")}})
    sigs.append({"key": s(sd.SignatureKey()), "subgraph": sd.SubgraphIndex(),
                 "inputs": ins, "outputs": outs})
  info["signatures"] = sigs
  if out_json:
    with open(out_json, "w") as f:
      json.dump(info, f, indent=1)
  # human summary
  print(f"# {path}  size={len(buf):,}  version={info['version']}  desc={info['description']!r}")
  print(f"opcodes({len(opcodes)}): {opcodes}")
  print(f"buffers: {info['buffers']['count']} inline={total_inline:,} offset={total_ext:,} big>1MB={len(big)}")
  for e in md:
    print(f"metadata {e['name']}: size={e['size']:,} off={e['offset']} {e.get('head_text', e.get('head_hex', ''))[:300]!r}")
  for g in sgs:
    print(f"subgraph {g['index']} {g['name']!r}: tensors={g['n_tensors']} ops={g['n_ops']} {g['ops']}")
    print(f"   const tensors: {g['const_tensor_types']}")
    for c in g["custom_samples"][:3]:
      print(f"   custom: {c}")
  for sd in sigs:
    print(f"signature {sd['key']!r} -> subgraph {sd['subgraph']}")
    for x in sd["inputs"]:
      print(f"   in  {x['key']:<28} {x['dtype']:<8} {x['shape']} {x['quant'] or ''}")
    for x in sd["outputs"]:
      print(f"   out {x['key']:<28} {x['dtype']:<8} {x['shape']} {x['quant'] or ''}")


if __name__ == "__main__":
  a = sys.argv[1:]
  oj = None
  if "--json" in a:
    i = a.index("--json"); oj = a[i + 1]; del a[i:i + 2]
  main(a[0], oj)
