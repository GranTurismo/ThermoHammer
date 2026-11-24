# syntax=docker/dockerfile:1.7
#
# ThermoHammer NPU model builder (Gemma 3 1B -> LiteRT-LM .litertlm per SoC).
#
# linux/amd64 only:
#   * QAIRT's aarch64 Linux libQnnHtp.so is an on-device build ("No Snapdragon
#     SOC detected"); offline HTP compilation needs the x86_64-linux-clang libs.
#   * The LiteRT MediaTek compiler plugin and NeuroPilot host libs are x86_64.
#   * litert-converter (needed by litert-torch) has no linux/aarch64 wheel.
#
# Targets:
#   compile  vendor AOT compile only. Runs under Rosetta on Apple silicon.
#   full     compile + the litert-torch export/calibrate/quantize venv. Needs a
#            real x86-64 CPU with AVX (jaxlib); Rosetta has no AVX.
#
# Vendor SDKs come in through the build context "sdk" (a directory holding the
# archives below; build.sh downloads and checks them):
#   v2.52.0.261104.zip        Qualcomm AI Runtime (QAIRT) community SDK
#   neuropilot-66f2c33a.gz    MediaTek NeuroPilot host libs (amd64 only)
# Both are the exact archives Google's ai-edge-litert-sdk-{qualcomm,mediatek}
# 2.4.0 pip packages download; we install those packages with
# SKIP_SDK_DOWNLOAD=1 and unpack the (hash-checked) archives ourselves.

ARG BASE=python:3.11-slim-bookworm@sha256:ca2726dd4885e40d7c5eb66c255e5ffa699e5f581c760d2fa1187db12fdd47f3

FROM ${BASE} AS base
ENV PIP_NO_CACHE_DIR=1 PYTHONDONTWRITEBYTECODE=1 UV_NO_CACHE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip libatomic1 ca-certificates libc++1 libc++abi1 \
 && rm -rf /var/lib/apt/lists/* \
 && pip install --no-cache-dir uv==0.12.19

# --------------------------------------------------------------- compile
FROM base AS compile
ARG QAIRT_VERSION=2.52.0.261104
ARG QAIRT_SHA256=e4c88416efa47eb8ed32fe2bd36fe63df58a3f4b48a347de5cf565a8c38e0961
ARG NEUROPILOT_SHA256=f69434d45856964627c750e716b835988a1f07511b6196d7f070fdde26027994
COPY compile-lock-x86_64.txt sdk-pins.txt /tmp/
RUN set -eux; test "$(uname -m)" = x86_64; \
    uv venv /opt/venv-compile --python /usr/local/bin/python3.11; \
    uv pip install --python /opt/venv-compile --require-hashes -r /tmp/compile-lock-x86_64.txt; \
    SKIP_SDK_DOWNLOAD=1 uv pip install --python /opt/venv-compile --no-deps \
        --require-hashes -r /tmp/sdk-pins.txt
# QAIRT host libraries (only the host-arch lib dir + licences: ~0.5 GB, not 3 GB)
RUN --mount=type=bind,from=sdk,target=/sdk set -eux; \
    echo "$QAIRT_SHA256  /sdk/v$QAIRT_VERSION.zip" | sha256sum -c -; \
    L=x86_64-linux-clang; \
    D=/opt/venv-compile/lib/python3.11/site-packages/ai_edge_litert_sdk_qualcomm/data; \
    mkdir -p /tmp/q "$D"; \
    unzip -q /sdk/v$QAIRT_VERSION.zip "qairt/$QAIRT_VERSION/lib/$L/*" \
        "qairt/$QAIRT_VERSION/LICENSE*" "qairt/$QAIRT_VERSION/QAIRT*" -d /tmp/q || true; \
    test -f "/tmp/q/qairt/$QAIRT_VERSION/lib/$L/libQnnHtp.so"; \
    mv /tmp/q/qairt/$QAIRT_VERSION/* "$D/"; rm -rf /tmp/q; \
    echo "$QAIRT_VERSION" > "$D/QAIRT_VERSION"
# MediaTek NeuroPilot host libs (v8_0_x for MT6989/MT6991..., v9_0_x for MT6993)
RUN --mount=type=bind,from=sdk,target=/sdk set -eux; \
    echo "$NEUROPILOT_SHA256  /sdk/neuropilot-66f2c33a.gz" | sha256sum -c -; \
    D=/opt/venv-compile/lib/python3.11/site-packages/ai_edge_litert_sdk_mediatek/data; \
    mkdir -p "$D" /tmp/n; tar xzf /sdk/neuropilot-66f2c33a.gz -C /tmp/n; \
    mv /tmp/n/neuro_pilot/* "$D/"; rm -rf /tmp/n
# Google's LiteRT-LM NPU compiler driver from litert-torch 0.10.0 (vendored so
# the compile venv does not need torch).
COPY litert-torch-pin.txt /tmp/
RUN set -eux; mkdir -p /tmp/w /opt/thnpu/thnpu; \
    python -m pip download --no-deps --require-hashes -r /tmp/litert-torch-pin.txt -d /tmp/w; \
    cd /tmp/w; P=litert_torch/generative/export_hf/experimental/litert_lm_npu_compiler; \
    unzip -q litert_torch-0.10.0-py3-none-any.whl "$P/litert_lm_npu_compiler.py" "$P/litert_lm_npu_compiler_configs.py"; \
    sed 's/^from litert_torch.generative.export_hf.experimental.litert_lm_npu_compiler import litert_lm_npu_compiler_configs/from thnpu import litert_lm_npu_compiler_configs/' \
        "$P/litert_lm_npu_compiler.py" > /opt/thnpu/thnpu/litert_lm_npu_compiler.py; \
    cp "$P/litert_lm_npu_compiler_configs.py" /opt/thnpu/thnpu/; \
    grep -q "from thnpu import litert_lm_npu_compiler_configs" /opt/thnpu/thnpu/litert_lm_npu_compiler.py; \
    rm -rf /tmp/w
COPY thnpu/ /opt/thnpu/thnpu/
ENV PATH=/opt/venv-compile/bin:$PATH
WORKDIR /work

# ------------------------------------------------------------------ full
FROM compile AS full
COPY export-lock-amd64.txt /tmp/
RUN set -eux; \
    uv venv /opt/venv-export --python /usr/local/bin/python3.11; \
    uv pip install --python /opt/venv-export --require-hashes \
        --index-strategy unsafe-best-match \
        --extra-index-url https://download.pytorch.org/whl/cpu \
        -r /tmp/export-lock-amd64.txt; \
    F=/opt/venv-export/lib/python3.11/site-packages/litert_torch/generative/export_hf/model_ext/bonsai_flux2/bonsai_flux2.py; \
    sed -i 's/^from tensorflow.lite.python import schema_py_generated as schema_fb.*/from ai_edge_litert import schema_py_generated as schema_fb  # thnpu: 0.10.0 imports TF without depending on it/' "$F"; \
    grep -q "from ai_edge_litert import schema_py_generated as schema_fb" "$F"
