#!/bin/bash
# Builds the LLM host test twice — the app's dot-product CPU kernels and its i8mm ones (libbenchllm_i8mm.so's flags) —
# runs the CPU test on each, and checks the two builds answer the same prompt alike: the correlation of their logit
# samples must be at least 0.9, the bar the app's own probe and its GPU-against-CPU check use. Needs an Apple silicon
# Mac with i8mm (M2 and later). Prints both builds' tokens/s; they are this Mac's, not a phone's.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
D="${TMPDIR:-/tmp}"
if [ "$(sysctl -n hw.optional.arm.FEAT_I8MM 2>/dev/null)" != "1" ]; then echo "this Mac has no i8mm"; exit 2; fi
echo "== dot product build"
LLM_DUMP_SELFTEST="$D/llm_selftest_dotprod.txt" LLM_DUMP_SAMPLE="$D/llm_sample_dotprod.txt" "$HERE/llm_run.sh" cpu
echo "== i8mm build"
LLM_I8MM=1 LLM_DUMP_SELFTEST="$D/llm_selftest_i8mm.txt" LLM_DUMP_SAMPLE="$D/llm_sample_i8mm.txt" "$HERE/llm_run.sh" cpu
python3 - "$D/llm_sample_dotprod.txt" "$D/llm_sample_i8mm.txt" "$D/llm_selftest_dotprod.txt" "$D/llm_selftest_i8mm.txt" <<'PY'
import math, sys
def corr(a, b):
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    ab = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    aa = sum((x - ma) ** 2 for x in a)
    bb = sum((y - mb) ** 2 for y in b)
    return ab / math.sqrt(aa * bb)
a = [float(x) for x in open(sys.argv[1])]
b = [float(x) for x in open(sys.argv[2])]
assert len(a) == len(b) > 1000, (len(a), len(b))
r = corr(a, b)
print("logit sample correlation, i8mm against dot product: %.6f (%d logits)" % (r, len(a)))
# the app's few-millisecond kernel self-test (bc_llm_kernel_selftest): the prompt's logits, then the next token's
sa = [float(x) for x in open(sys.argv[3])]
sb = [float(x) for x in open(sys.argv[4])]
assert len(sa) == len(sb) == 2048, (len(sa), len(sb))
rp, rd = corr(sa[:1024], sb[:1024]), corr(sa[1024:], sb[1024:])
print("kernel self-test, i8mm against dot product: prompt %.6f, next token %.6f" % (rp, rd))
sys.exit(0 if r >= 0.9 and min(rp, rd) >= 0.99 else 1)
PY
