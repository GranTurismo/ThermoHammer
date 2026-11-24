#!/usr/bin/env bash
# Build a LiteRT-LM NPU model file for Gemma 3 1B IT for one SoC.
#
#   MODEL_DIR=/path/to/gemma-3-1b-it-qat-int4-unquantized ./build.sh sm8750
#   (use the QAT int4 checkpoint: plain gemma-3-1b-it weights give wrong
#    answers at int4; google/gemma-3-1b-it-qat-int4-unquantized model.safetensors
#    sha256 3dddcbaeec1e8b310dbd98fdb581d07c948859ff6c8e7a2b5ea17c1b33ae07e7)
#   MODEL_DIR=... ./build.sh sm8650 mt6993 ...      (several SoCs, shared prep)
#
# Output: $OUT_DIR/Gemma3-1B-IT_<QUANT>_ekv1280_<soc>.litertlm (+ .report.md). QUANT is the model's form, the same
# model everywhere, the most compact one the chip's NPU runs whole (tools/npu_models/plan.py VARIANT):
#   q4      int16 activations, int4 weights: Google's "q4", every chip that can
#   q8      int16 activations, int8 weights: NPUs without int4 weights
#   q4att8  q4 with attention's two matmuls at int8: NPUs without int16 x int16 matmul
#
# Pipeline (see docker/thnpu/pipeline.py):
#   1 export     HF weights -> float split-cache .litertlm   (litert-torch 0.10.0)
#   2 calibrate  activation ranges on N prompts (CPU)         (shared by all SoCs)
#   3 quantize   a16w4 (FC) / a16w8 static quantization       (shared by all SoCs)
#   4 compile    AOT for the SoC (Qualcomm QAIRT 2.52 / MediaTek NeuroPilot)
#
# Everything runs in linux/amd64 images. Stages 1-3 need a real x86-64 CPU with
# AVX (CI). On Apple silicon they cannot run in Docker (no linux/aarch64
# litert-converter wheel; Rosetta has no AVX for jaxlib), so PREP=host runs the
# same pinned versions in a uv venv on the Mac. Stage 4 always runs in Docker
# (under Rosetta on a Mac: QNN/NeuroPilot host compilers are x86-64 only).
#
# Env: MODEL_DIR (required)  OUT_DIR=./out  CACHE_DIR=./cache  PREP=auto|docker|host
#      NUM_PROMPTS=50  DECODE_STEPS=32  QUANT=q4  REF_DIR (reference files
#      named like Google's, for the structural report)  KEEP=0
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
[ $# -ge 1 ] || { sed -n '2,20p' "$0"; exit 2; }
: "${MODEL_DIR:?set MODEL_DIR to the Gemma 3 1B IT Hugging Face folder}"
OUT_DIR="$(mkdir -p "${OUT_DIR:-$HERE/out}" && cd "${OUT_DIR:-$HERE/out}" && pwd)"
CACHE_DIR="$(mkdir -p "${CACHE_DIR:-$HERE/cache}" && cd "${CACHE_DIR:-$HERE/cache}" && pwd)"
PREP="${PREP:-auto}"
NUM_PROMPTS="${NUM_PROMPTS:-50}"; DECODE_STEPS="${DECODE_STEPS:-32}"; QUANT="${QUANT:-q4}"
case "$QUANT" in
  q4) WEIGHT_BITS=4; ATTN_BITS=0;;
  q8) WEIGHT_BITS=8; ATTN_BITS=0;;
  q4att8) WEIGHT_BITS=4; ATTN_BITS=8;;
  *) echo "unknown QUANT $QUANT (q4, q8, q4att8)" >&2; exit 2;;
esac
CACHE_LEN=1280; PREFILL=128
WORK="$OUT_DIR/work"; mkdir -p "$WORK"
# calibration is shared by every precision; each weight precision has its own quantized model
QFILE="quantized.litertlm"; [ "$QUANT" = q4 ] || QFILE="quantized-$QUANT.litertlm"

QAIRT_VERSION=2.52.0.261104
QAIRT_SHA256=e4c88416efa47eb8ed32fe2bd36fe63df58a3f4b48a347de5cf565a8c38e0961
QAIRT_URL="https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$QAIRT_VERSION/v$QAIRT_VERSION.zip"
NP_FILE=neuropilot-66f2c33a.gz
NP_SHA256=f69434d45856964627c750e716b835988a1f07511b6196d7f070fdde26027994
NP_URL=https://s3.ap-southeast-1.amazonaws.com/mediatek.neuropilot.com/66f2c33a-2005-4f0b-afef-2053c8654e4f.gz

log() { echo "[build $(date +%H:%M:%S)] $*"; }
vendor_of() { case "$(echo "$1" | tr A-Z a-z)" in sm*) echo qualcomm;; mt*) echo mediatek;; *) echo "unknown SoC $1" >&2; exit 2;; esac; }
sha_ok() { [ -f "$1" ] && [ "$( (sha256sum "$1" 2>/dev/null || shasum -a 256 "$1") | cut -d' ' -f1)" = "$2" ]; }
fetch() { # file sha url
  sha_ok "$CACHE_DIR/sdk/$1" "$2" && return
  mkdir -p "$CACHE_DIR/sdk"; log "downloading $1"
  curl -fL --retry 3 -o "$CACHE_DIR/sdk/$1.part" "$3"; mv "$CACHE_DIR/sdk/$1.part" "$CACHE_DIR/sdk/$1"
  sha_ok "$CACHE_DIR/sdk/$1" "$2" || { echo "sha256 mismatch for $1" >&2; exit 1; }
}

HOST_ARCH="$(uname -m)"
if [ "$PREP" = auto ]; then
  if [ "$HOST_ARCH" = x86_64 ] && grep -qw avx2 /proc/cpuinfo 2>/dev/null; then PREP=docker; else PREP=host; fi
fi

image() { # target platform -> tag
  local tag="thnpu:$1-${2#linux/}"
  docker buildx build --platform "$2" --target "$1" --build-context sdk="$CACHE_DIR/sdk" \
    -t "$tag" --load "$HERE/docker" >&2
  echo "$tag"
}

# ------------------------------------------------------------- stages 1-3
run_prep() { # stage args...
  local stage=$1; shift
  if [ "$PREP" = docker ]; then
    docker run --rm --platform linux/amd64 -v "$MODEL_DIR":/model:ro -v "$WORK":/work "$FULL_IMG" \
      /opt/venv-export/bin/python -I /opt/thnpu/thnpu/pipeline.py "$stage" --work /work/common --model /model "$@"
  else
    "$HOST_VENV/bin/python" -I "$HERE/docker/thnpu/pipeline.py" "$stage" --work "$WORK/common" --model "$MODEL_DIR" "$@"
  fi
}
w() { if [ "$PREP" = docker ]; then echo "/work/$1"; else echo "$WORK/$1"; fi; }

setup_prep() {
  if [ "$PREP" = docker ]; then
    FULL_IMG="$(image full linux/amd64)"
  else
    HOST_VENV="$CACHE_DIR/venv-export-$(uname -s)-$HOST_ARCH"
    if [ ! -x "$HOST_VENV/bin/python" ]; then
      log "creating $HOST_VENV (same pinned versions as the Docker image)"
      uv venv -q --python 3.11 "$HOST_VENV"
      VIRTUAL_ENV="$HOST_VENV" uv pip install -q --index-url https://pypi.org/simple -r "$HERE/docker/export-pins.txt"
      f="$HOST_VENV/lib/python3.11/site-packages/litert_torch/generative/export_hf/model_ext/bonsai_flux2/bonsai_flux2.py"
      sed -i.bak 's/^from tensorflow.lite.python import schema_py_generated as schema_fb.*/from ai_edge_litert import schema_py_generated as schema_fb  # thnpu patch/' "$f"
    fi
  fi
}

prep() { # export -> calibrate -> quantize, once for all SoCs (Qualcomm and
        # MediaTek use the same KV layout k_ts_idx=2/v_ts_idx=3 and recipe)
  local q="$WORK/common/$QFILE"
  [ -f "$q" ] && { log "reuse $q"; return; }
  [ -n "${FULL_IMG:-}${HOST_VENV:-}" ] || setup_prep
  if [ ! -f "$WORK/common/export/model.litertlm" ]; then
    log "stage 1 export"; run_prep export --prefill $PREFILL --cache $CACHE_LEN
  fi
  if [ ! -f "$WORK/common/calib/.done" ]; then
    log "stage 2 calibrate ($NUM_PROMPTS prompts, $DECODE_STEPS decode steps)"
    run_prep calibrate --cache $CACHE_LEN --num_prompts "$NUM_PROMPTS" --decode_steps "$DECODE_STEPS"
    [ -f "$WORK/common/calib/.done" ] || { echo "calibration left no .done marker" >&2; exit 1; }   # pipeline.py writes it
  fi
  log "stage 3 quantize $QUANT (int$WEIGHT_BITS FC weights, int16 activations, attention int${ATTN_BITS/#0/16})"
  run_prep quantize --quant "$(w "common/$QFILE")" --weight_bits "$WEIGHT_BITS" --attn_bits "$ATTN_BITS"
}

# --------------------------------------------------------------- stage 4
fetch "v$QAIRT_VERSION.zip" "$QAIRT_SHA256" "$QAIRT_URL"
fetch "$NP_FILE" "$NP_SHA256" "$NP_URL"
for SOC in "$@"; do
  SOC="$(echo "$SOC" | tr A-Z a-z)"; V="$(vendor_of "$SOC")"
  prep
  PLAT=linux/amd64; IMG="${COMPILE_IMG:-$(image compile "$PLAT")}"; COMPILE_IMG="$IMG"
  NAME="Gemma3-1B-IT_${QUANT}_ekv${CACHE_LEN}_${SOC}.litertlm"
  log "stage 4 compile $SOC ($V) in $IMG"
  docker run --rm --platform "$PLAT" -v "$WORK":/work -v "$OUT_DIR":/out "$IMG" \
    python -I /opt/thnpu/thnpu/pipeline.py compile --work /work/common --soc "$SOC" \
      --quant "/work/common/$QFILE" --out "/out/$NAME" $( [ "${KEEP:-0}" = 1 ] && echo --keep )
  if [ -n "${REF_DIR:-}" ] && [ -f "$REF_DIR/$NAME" ]; then
    docker run --rm --platform "$PLAT" -v "$OUT_DIR":/out -v "$REF_DIR":/ref:ro "$IMG" \
      python -I /opt/thnpu/thnpu/compare.py "/out/$NAME" "/ref/$NAME" --out "/out/$NAME.report.md" >/dev/null
    log "report: $OUT_DIR/$NAME.report.md"
  fi
  log "done: $OUT_DIR/$NAME ($(wc -c < "$OUT_DIR/$NAME") bytes)"
done
