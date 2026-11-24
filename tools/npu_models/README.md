# The LLM test's NPU model files (Android, scoring version 5)

The LLM test runs Gemma 3 1B on the NPU through LiteRT-LM, and an NPU runs only a file compiled for its own chip.
Google publishes such files for seven chips. A phone with any other chip would score 0 on the NPU third because
nobody had built its file, not because of its NPU. So we build the files ourselves, with the chip makers' own
compilers, for every phone chip those compilers know, and we build them before those phones show up.

## How a chip gets its file

1. **Every day** `.github/workflows/npu-models.yml` runs `plan.py`. It compares the chips the pinned compilers can
   target with the chips that already have a file (the Play pack and the site). The difference is what gets built.
2. **Build** (`builder/build.sh <soc>…`, Docker, linux/amd64): Google's open-source litert-torch NPU flow.
   - export: split cache, prefill 128, 1,280 tokens;
   - calibration on 50 prompts;
   - quantization: int16 activations, int4 weights, Google's "q4";
   - compile: Qualcomm QAIRT 2.52 or MediaTek NeuroPilot through LiteRT 2.4's compiler plugins.

   Export, calibration and quantization run once and are shared by every chip. The compile takes 1–7 minutes per chip.
3. **Checks before anything is published:**
   - The quantized model must answer on the CPU: "Paris", and "2" apples (`builder/scripts/cpu_check.sh`).
   - `builder/docker/thnpu/verify.py` checks each file:
     - Google's LiteRT-LM layout and settings;
     - prefill and decode each a single vendor NPU program, so no op is left for the CPU;
     - stamped by the chip's own vendor;
     - a size in Google's range.

   A file that fails isn't published. Its chip stays at 0 rather than getting a slower, half-CPU build.
4. **Publish:** the file and its SHA-256 go to the site, `thermohammer.gtgroup.dev/models/android/`. `manifest.json`
   is then rebuilt from what's there (`manifest.py`, `server_install.sh`).
   - **Phones:** `NpuServerModels.kt` downloads the file over Wi-Fi only, checks its SHA-256 and keeps it.
   - **Server:** `NpuChipCatalog.cs` re-reads the manifest every 30 minutes and requires that chip's NPU part from then on.
   - Nothing needs an app update or a deploy.
5. **Chips phones report that no compiler targets** open a GitHub issue. `plan.py` reads them from thapi
   `/benchmark/chips`, or from the leaderboard before that endpoint exists. The issue says what's needed:
   - a newer ai-edge-litert knows the chip: bump the pins in `builder/docker`;
   - or none does yet.

## Run it by hand

```sh
python3 tools/npu_models/plan.py                          # what's missing, what no compiler covers
python3 tools/npu_models/fetch_weights.py ~/gemma-qat     # Google's QAT int4 checkpoint, SHA-256 checked
MODEL_DIR=~/gemma-qat tools/npu_models/builder/build.sh sm8845 mt6893
tools/npu_models/publish.sh out/Gemma3-1B-IT_q4_ekv1280_sm8845.litertlm   # to the site (ssh myserver)
```

Or use Actions → **NPU model files** → Run workflow, with the chips to rebuild, or empty for whatever is missing.
To keep the job from publishing, set the repository variable `NPU_AUTO_PUBLISH` to `false`.

**Weights:**
- The source is `google/gemma-3-1b-it-qat-int4-unquantized`, which needs the secret `HF_TOKEN` from an account that
  accepted the Gemma terms.
- Without the token, the job uses a public mirror with byte-identical files. Every file's SHA-256 is pinned in
  `fetch_weights.py` either way.
- It has to be the QAT checkpoint: plain gemma-3-1b-it at 4 bits answers "3 apples, eat one" with 6.

**Where it runs:**
- An x86-64 machine with AVX2, 16 GB of RAM (export peaks around 14 GB) and about 30 GB of disk. GitHub's
  ubuntu-latest runner qualifies, since the repository is public.
- On an Apple-silicon Mac, `build.sh` runs export, calibration and quantization in a uv venv with the same pinned
  versions, and only the compile in Docker under Rosetta. Rosetta has no AVX, and the vendor compilers are x86-64 only.

## What's pinned, and why it matters for fairness

- **Toolchain:**
  - ai-edge-litert 2.4.0;
  - QAIRT 2.52.0.261104, the same QNN as the app's runtime;
  - NeuroPilot archive 66f2c33a;
  - litert-torch 0.10.0;
  - everything else hash-locked in `builder/docker/*.txt`.
- **Why it's pinned:** the toolchain is part of the score. A newer compiler can make the same chip faster, so it is
  pinned per scoring version.
- **Bumping it:** when a new chip needs a newer one, compare the new toolchain's sm8750 file against the pinned one
  on a real 8 Elite before publishing anything built with it.
- **Our build vs Google's** (sm8750, `verify.py` and `thnpu/compare.py`):
  - Same sections, signatures, KV-cache layout (26 layers, int16), int4 weights and model settings.
  - Sizes are within 0.03 %.
  - Differences:
    - QNN 2.52 instead of 2.34;
    - an extra `valid_mask` input on the mask step, which runtime 0.19.1 fills in;
    - the embedder outputs float32 instead of int16, and the conversion runs on the NPU.

## Coverage (LiteRT 2.4.0's compiler plugins, 2026-11-24)

**The rule:** every chip runs the same model, Gemma 3 1B from the same QAT weights, with the same task and the same
answer check. Each chip gets the most compact form its NPU runs whole. The form is in the file name, so it shows in
every run's report.

| form | what | for |
|---|---|---|
| `q4` | int16 activations, int4 weights (Google's) | every NPU that can |
| `q8` | int8 weights | NPUs without int4 weights: the int4 layers stayed on the CPU. A bigger file and a slower reply than int4 hardware would give |
| `q4att8` | q4, with attention's two matmuls per layer at int8 | Hexagon v68/v69: no int16 × int16 matmul. The answers stay right on the CPU check |

- **Files:**
  - Google's (the Play pack): sm8550, sm8650, sm8750, sm8850, mt6989, mt6991, mt6993.
  - Ours: sm8845, mt6897 and mt8189 (`q4`); sm8350, sm8450 and sm8475 (`q4att8`); mt6878, mt6983 and mt6985 (`q8`).
  - On the Honor, our sm8750 `q4` scored the same as Google's (6,540 vs 6,530).
- **No form runs right (hardware), so the NPU third is 0:** mt6853, mt6877, mt6893 (Dimensity 720, 900/1080, 1200,
  which is the 11T), mt8188 and mt6879.
  - These NPUs have no int16 arithmetic at all.
  - Gemma with int8 activations answers gibberish (CPU check), so no form of the model runs right on them.
- **The compiler fails (a software gap), so the daily issue lists these while phones use them:**
  - mt6886 (Dimensity 7200) and mt6895 (8100): NeuroPilot 8 answers "Not a valid platform".
  - mt8171: the compile stops without an error.
  - mt6899 (Dimensity 8400): not in the plugin.
  - Exynos: LiteRT's Samsung plugin knows only E9965.
  - Google Tensor: not built yet.

`plan.py` holds `VARIANT`, `NPU_CANT_HOLD` and `NO_COMPILER`. After a toolchain bump, remove a chip from a list to try
it again.

## Known risks

- **A compile that succeeds isn't proof the file runs on that chip.** MediaTek binaries need a NeuroPilot driver the
  phone's firmware may not have (LiteRT-LM issue #1268), and old chips' NPUs may not fit the model.
  - Either way the run's NPU third is 0, as if no file existed, and the run is still accepted
    (NpuLlmProbe: a crash or a wrong answer scores 0).
  - The cost is a ~0.7–1 GB download on Wi-Fi.
- **Speed on the device** is only measured on real phones. Test builds run every file in `npu-llm/compare/` after the
  scored one (adb push), so one LLM-only run compares our build with Google's on the same phone.
