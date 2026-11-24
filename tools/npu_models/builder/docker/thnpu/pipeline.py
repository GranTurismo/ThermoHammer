"""ThermoHammer NPU model pipeline: Gemma 3 1B -> per-SoC .litertlm for LiteRT-LM.

Wraps Google's open-source litert-torch NPU flow
(litert_torch.generative.export_hf + experimental/{calib,litert_lm_npu_compiler}):

  export    HF checkpoint -> float .litertlm (split cache, external embedder,
            external RoPE/mask/cache-update "aux" model), embedder int4.
  calibrate profiler-based activation ranges on a prompt set (CPU, LiteRT).
  quantize  static range quantization: activations int16, weights int4
            (FULLY_CONNECTED, per channel) / int8 (everything else).
  compile   AOT compile prefill_decode + aux mask for one SoC with the
            vendor's LiteRT compiler plugin (Qualcomm QNN / MediaTek NeuroPilot).

Every stage reads/writes files under --work; each stage can run in a
different process/container (compile runs in the vendor-SDK venv).
"""

import argparse
import json
import os
import shutil
import sys
import time

VENDOR = {"sm": "qualcomm", "mt": "mediatek"}


def vendor_of(soc: str) -> str:
  v = VENDOR.get(soc.lower()[:2])
  if not v:
    raise SystemExit(f"unknown SoC family: {soc}")
  return v


def log(msg):
  print(f"[thnpu {time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ----------------------------------------------------------------- export
def cmd_export(a):
  from litert_torch.generative.export_hf import export as ex  # pylint: disable=g-import-not-at-top
  from ai_edge_quantizer import recipe as qrecipe  # pylint: disable=g-import-not-at-top

  os.makedirs(a.work, exist_ok=True)
  # Embedder table int4 per channel (same as Google's file); main model stays
  # float here and is statically quantized after calibration.
  recipe_path = os.path.join(a.work, "export_recipe_emb_int4.json")
  with open(recipe_path, "w") as f:
    json.dump(qrecipe.dynamic_wi4c_afp32(
        operation_name=qrecipe.TFLOperationName.EMBEDDING_LOOKUP), f, indent=1)
  out = os.path.join(a.work, "export")
  kw = dict(
      model=a.model,
      output_dir=out,
      prefill_lengths=[a.prefill],
      cache_length=a.cache,
      quantization_recipe=recipe_path,
      split_cache=True,
      externalize_embedder=True,
      k_ts_idx=a.k_ts_idx,
      v_ts_idx=a.v_ts_idx,
      bundle_litert_lm=True,
      keep_temporary_files=False,
  )
  if a.llm_metadata:
    kw["litert_lm_llm_metadata_override"] = a.llm_metadata
  if not a.cache_update_valid_mask:
    # litert-torch 0.10.0 gives the aux cache-update signatures a `valid_mask`
    # input; with it, sliding-window layers (22 of 26 in Gemma 3 1B) are
    # updated with a one-hot BATCH_MATMUL + SELECT "ring buffer" instead of
    # DYNAMIC_UPDATE_SLICE. The aux cache update runs on the CPU, so this is
    # slow, and Google's file has neither: plain DUS for all 52 caches. Without
    # valid_mask the library itself picks DUS for every layer.
    from litert_torch.generative.export_hf.core.split_cache import exportable_module as scm  # pylint: disable=g-import-not-at-top
    orig = scm.CacheUpdate._get_input.__func__

    def _get_input(cls, *args, **kwargs):
      d = orig(cls, *args, **kwargs)
      d.pop("valid_mask", None)
      return d

    scm.CacheUpdate._get_input = classmethod(_get_input)
  log(f"export {kw}")
  ex.export(**kw)
  log(f"export done -> {out}")


def finalize_metadata(path, llm_metadata, sp_model):
  """Swaps LlmMetadata / tokenizer sections of a .litertlm in place."""
  import io  # pylint: disable=g-import-not-at-top
  import tempfile  # pylint: disable=g-import-not-at-top
  import tomllib  # pylint: disable=g-import-not-at-top
  import litert_lm_builder as lb  # pylint: disable=g-import-not-at-top
  from litert_lm_builder import litertlm_peek  # pylint: disable=g-import-not-at-top

  with tempfile.TemporaryDirectory(dir=os.path.dirname(path)) as td:
    litertlm_peek.peek_litertlm_file(path, td, io.StringIO())
    toml_path = os.path.join(td, "model.toml")
    with open(toml_path, "rb") as f:
      t = tomllib.load(f)
    lines = []
    lines.append("[system_metadata]\nentries = [\n"
                 '  { key = "author", value_type = "String", value = "ThermoHammer thnpu (litert-torch 0.10.0 NPU flow)" },\n]\n')
    order = {"LlmMetadata": 0, "SP_Tokenizer": 1, "HF_Tokenizer": 1, "ExecutorMetadata": 2,
             "embedder": 3, "aux": 4, "prefill_decode": 5}
    secs = sorted(t["section"], key=lambda x: order.get(x.get("model_type", x["section_type"]), 9))
    for s in secs:
      s = dict(s)
      if s["section_type"] == "LlmMetadata" and llm_metadata:
        s["data_path"] = os.path.abspath(llm_metadata)
      elif s["section_type"] == "HF_Tokenizer" and sp_model:
        s = {"section_type": "SP_Tokenizer", "data_path": os.path.abspath(sp_model)}
      lines.append("[[section]]")
      for k, v in s.items():
        if k == "data_path" and not os.path.isabs(v):
          v = os.path.join(td, v)
        lines.append(f"{k} = {json.dumps(v)}")
      lines.append("")
    with open(toml_path, "w") as f:
      f.write("\n".join(lines))
    tmp_out = path + ".tmp"
    with open(tmp_out, "wb") as f:
      lb.LitertLmFileBuilder.from_toml_file(toml_path).build(f)
    os.replace(tmp_out, path)
  log(f"metadata/tokenizer finalized in {path}")


# -------------------------------------------------------------- calibrate
def cmd_calibrate(a):
  from litert_torch.generative.export_hf.experimental.calib import calibrate as cal  # pylint: disable=g-import-not-at-top
  from litert_torch.generative.export_hf.experimental.npu_export import stages  # pylint: disable=g-import-not-at-top

  data_dir = os.path.join(a.work, "calib_data")
  os.makedirs(data_dir, exist_ok=True)
  src = a.prompts or os.path.join(os.path.dirname(stages.__file__),
                                  "sample_calibration_prompts.json")
  with open(src) as f:
    prompts = json.load(f)
  prompts = prompts[: a.num_prompts]
  with open(os.path.join(data_dir, "ALL.jsonl"), "w") as f:
    for p in prompts:
      f.write(json.dumps({"text": p}) + "\n")
  log(f"calibrate: {len(prompts)} prompts from {src}, {a.decode_steps} decode steps")
  cal.calibrate(
      input_litertlm=os.path.join(a.work, "export", "model.litertlm"),
      calibration_dataset_dir=data_dir,
      calibration_result_save_dir=os.path.join(a.work, "calib"),
      calibration_dataset_format="jsonl",
      calibration_eval_task_names="ALL",
      max_calibration_decode_steps=a.decode_steps,
      use_profiler_based_calibration=True,
      enable_min_max_calibration_update=True,
      ema_smoothing_factor=0.1,
      kv_cache_max_len=a.cache,
  )
  log("calibrate done")


# --------------------------------------------------------------- quantize
def cmd_quantize(a):
  from ai_edge_quantizer import qtyping  # pylint: disable=g-import-not-at-top
  from litert_torch.generative.export_hf.experimental.calib import quant_utils  # pylint: disable=g-import-not-at-top
  from litert_torch.generative.export_hf.experimental.calib import quantize as qz  # pylint: disable=g-import-not-at-top

  orig = quant_utils.add_main_model_quant_recipe

  def recipe(q, **kw):
    q = orig(q, **kw)  # a16w8 everywhere (+ the library's special cases)
    if a.weight_bits == 4:
      log("recipe: FULLY_CONNECTED weights int4 per channel, activations int16")
      q.add_static_config(
          regex=".*",
          operation_name=qtyping.TFLOperationName.FULLY_CONNECTED,
          activation_num_bits=16,
          weight_num_bits=4,
          weight_granularity=qtyping.QuantGranularity.CHANNELWISE,
      )
    return q

  quant_utils.add_main_model_quant_recipe = recipe
  qz.quant_utils.add_main_model_quant_recipe = recipe
  out = a.quant or os.path.join(a.work, "quantized.litertlm")
  os.makedirs(os.path.dirname(out), exist_ok=True)
  qz.quantize(
      input_litertlm=os.path.join(a.work, "export", "model.litertlm"),
      output_litertlm=out,
      calibration_dir=os.path.join(a.work, "calib"),
      use_float_input_output_normalizer=a.float_norm,
      skip_mlir_passes=True,
      calibration_range_scale=a.range_scale,
  )
  for p in (out + ".intermediate.tflite", out + ".aux_intermediate.tflite"):
    if os.path.exists(p):
      os.remove(p)
  if not a.keep_export_metadata:
    sp = os.path.join(a.model, "tokenizer.model")
    finalize_metadata(out, a.final_llm_metadata, sp if os.path.exists(sp) else None)
  log(f"quantize done -> {out}")


# ---------------------------------------------------------------- compile
def cmd_compile(a):
  from absl import logging as alog  # pylint: disable=g-import-not-at-top
  alog.set_verbosity(alog.INFO)
  try:
    from thnpu import litert_lm_npu_compiler as comp  # vendored copy
  except ImportError:
    from litert_torch.generative.export_hf.experimental.litert_lm_npu_compiler import litert_lm_npu_compiler as comp  # pylint: disable=g-import-not-at-top
  vendor = vendor_of(a.soc)
  soc = a.soc.upper() if vendor == "qualcomm" else a.soc.lower()
  out = a.out or os.path.join(a.work, f"Gemma3-1B-IT_q4_ekv{a.cache}_{a.soc.lower()}.litertlm")
  if vendor == "mediatek":
    # ai-edge-litert 2.4.0's MediaTek compiler plugin dlopen()s the Bazel path
    # "external/neuro_pilot/v{8,9}_latest/host/lib/libneuron_adapter.so"
    # relative to the CWD and ignores LD_LIBRARY_PATH / sdk_libs_path
    # ("Failed to load NeuronAdapter shared library"). Give it that layout.
    import ai_edge_litert_sdk_mediatek as mtk  # pylint: disable=g-import-not-at-top
    data = mtk.get_sdk_path()
    shim = "/tmp/thnpu_np"
    os.makedirs(os.path.join(shim, "external", "neuro_pilot"), exist_ok=True)
    for name, ver in (("v8_latest", "v8"), ("v9_latest", "v9")):
      dst = os.path.join(shim, "external", "neuro_pilot", name)
      if not os.path.exists(dst):
        os.symlink(os.path.dirname(os.path.dirname(str(mtk.path_to_sdk_libs(ver)))), dst)
    os.chdir(shim)
    log(f"NeuroPilot shim at {shim} -> {data}")
  log(f"compile {vendor} {soc} -> {out}")
  comp.compile_litertlm(
      input_litertlm=a.quant or os.path.join(a.work, "quantized.litertlm"),
      output_litertlm=out,
      backend=vendor,
      soc_model=soc,
      model_name="gemma3_text",
      keep_temporary_files=a.keep,
      intermediate_dir=os.path.join(os.path.dirname(out), f"compile_{a.soc.lower()}") if a.keep else None,
      disable_aux_compilation=(vendor != "qualcomm"),
  )
  log(f"compile done -> {out} ({os.path.getsize(out):,} bytes)")


def main(argv=None):
  # Our own package dir (vendored compiler) on the path; -I drops it.
  sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
  try:
    from absl import flags  # pylint: disable=g-import-not-at-top
    flags.FLAGS.mark_as_parsed()
  except Exception:  # pylint: disable=broad-except
    pass
  p = argparse.ArgumentParser()
  p.add_argument("stage", choices=["export", "calibrate", "quantize", "compile"])
  p.add_argument("--work", required=True)
  p.add_argument("--model", default="/model")
  p.add_argument("--soc", default="sm8750")
  p.add_argument("--prefill", type=int, default=128)
  p.add_argument("--cache", type=int, default=1280)
  p.add_argument("--k_ts_idx", type=int, default=2)
  p.add_argument("--v_ts_idx", type=int, default=3)
  p.add_argument("--llm_metadata", default=None)
  p.add_argument("--prompts", default=None)
  p.add_argument("--num_prompts", type=int, default=10)
  p.add_argument("--decode_steps", type=int, default=32)
  p.add_argument("--weight_bits", type=int, default=4, choices=[4, 8])
  p.add_argument("--float_norm", action="store_true")
  p.add_argument("--range_scale", type=float, default=1.0)
  p.add_argument("--out", default=None)
  p.add_argument("--quant", default=None, help="quantized .litertlm path")
  p.add_argument("--cache_update_valid_mask", action="store_true",
                 help="keep litert-torch's valid_mask/ring-buffer cache update")
  p.add_argument("--keep_export_metadata", action="store_true",
                 help="keep the exporter's LlmMetadata + HF tokenizer")
  p.add_argument("--final_llm_metadata",
                 default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                      "gemma3_1b_it.llm_metadata.pbtext"))
  p.add_argument("--keep", action="store_true")
  a = p.parse_args(argv)
  {"export": cmd_export, "calibrate": cmd_calibrate,
   "quantize": cmd_quantize, "compile": cmd_compile}[a.stage](a)


if __name__ == "__main__":
  main()
