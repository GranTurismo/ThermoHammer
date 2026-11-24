#!/bin/bash
# CPU sanity check of a quantized (not yet NPU-compiled) .litertlm with Google's
# litert-torch sampling executor (int16 activations / int4 weights on the
# LiteRT CPU kernels). Greedy decoding.
# Usage: cpu_check.sh <venv> <quantized.litertlm> <hf_model_dir> [steps]
set -e
V=$1; Q=$2; M=$3; N=${4:-40}
T=$(mktemp -d)
"$V/bin/python" -I -c "import io,sys; from litert_lm_builder import litertlm_peek as p; p.peek_litertlm_file(sys.argv[1], sys.argv[2], io.StringIO())" "$Q" "$T"
MAIN=$(ls "$T"/*prefill_decode*.tflite); EMB=$(ls "$T"/*embedder*.tflite); AUX=$(ls "$T"/*aux*.tflite)
for P in "What is the capital of France? Answer in one sentence." \
         "Write a haiku about a hot phone." \
         "If I have 3 apples and eat one, how many are left? Explain briefly." \
         "Translate to German: The weather is nice today."; do
  echo "Q: $P"
  "$V/bin/python" -I -m litert_torch.generative.export_hf.experimental.calib.sampling_executor_main \
    --model_path="$MAIN" --embedder_model_path="$EMB" --auxiliary_model_path="$AUX" \
    --transformers_model_path="$M" --kv_cache_max_len=1280 --prompt="$P" --max_decode_steps=$N \
    --stop_token=106 --enable_formatting=True --stream_output=False 2>&1 | grep -A1 "^Response" | tail -1
done
rm -rf "$T"
