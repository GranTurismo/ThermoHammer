# Benchmark v5: a fair test for non-Snapdragon phones

Status: **planned, nothing built yet.** Decided with the owner on 2026-11-22.

## Why

The Vivo X300 Pro (Dimensity 9500, Mali-G1 Ultra) is a generation newer than the Honor 600 Pro (Snapdragon 8 Elite, Adreno 830), but it scores lower in total. The whole gap is in AI and LLM, and the cause is our software, not the chip.

Best runs in Performance mode: Honor run 5 (app 2.0.4) and X300 run 19 (app 2.1.7, 2026-11-22).

| Section | Honor | X300 | Note |
|---|---|---|---|
| CPU | 12,628 | 11,797 | the X300 wins multi-core, the Honor single-core |
| Memory | 8,890 | 10,766 | |
| GPU | 6,247 | 6,111 | the X300 wins the Vulkan RT scene by 71 %, the Honor wins the OpenGL ES scenes |
| Everyday UX | 7,700 | 10,523 | |
| **AI** | **7,328** | **3,769** | |
| **LLM** | **6,287** | **1,939** | |
| Total | 49,080 | 44,905 | without AI and LLM the X300 leads by about 10 % |

### LLM: why the X300 loses
- llama.cpp's fast GPU path (OpenCL) was written by Qualcomm and accepts only Adreno and Intel GPUs (`ggml-opencl.cpp` turns any other GPU away as "unsupported GPU"). Mali gets the generic Vulkan backend.
- Prompt speed: Honor GPU 315 tok/s, X300 GPU 12.8 tok/s, X300 CPU 39 tok/s (the counted one). On the CPU both phones are equal (37 vs 39).
- **2.1.x regression:** the new Vulkan shaders (coopmat and integer-dot, from the best-path work) made the X300's GPU prompt speed fall from 23.1 tok/s (2.0.4) to 12.8. The POCO X8 Pro Max (Mali-G925) shows the same.
- llama.cpp never uses an NPU. Google measured the same phone's NPU with LiteRT-LM: Gemma 3n E2B at 1,600+ tok/s prompt and 28 tok/s reply.

### AI: why the X300 loses
- The AI score is the **average** of the CPU, GPU and NPU points: X300 7,872 + 972 + 2,463 = 3,769. A weak GPU path pulls the whole section down.
- All six models are Qualcomm AI Hub 8-bit (w8a8) builds, prepared for Qualcomm's NPU.
- On MediaTek's NPU some operations can't run and fall back to the CPU (YOLOX's EXP / ARG_MAX). Detect: 79 inferences/s vs the Honor's 492.
- **2.1.x regression:** AI GPU 972 now vs 1,738 on 2.0.4; Detect on the GPU now scores 0 (it failed or fell under the 0.50 agreement rule); Upscale 37.6 → 18.2.

## Decisions made

1. **LLM, option B:** one task, run on every backend the device has; the fastest backend counts.
2. **New scoring version (5).** Every LLM score changes, the Honor's too. The board starts over, because the server lists only the current version. This overrides the 2026-11-10 "no recalibration" decision, for the LLM section.
3. **Backends:** llama.cpp CPU and GPU on every device (Android, iPhone/Mac via Metal, Windows), plus LiteRT-LM on the NPU on supported Android chips (Snapdragon 8-series, Dimensity 9300 / 9400 / 9500). iPhone and Mac get no NPU path: LiteRT-LM has none on Apple. It shows as "not supported".
4. **Weights:** llama.cpp keeps weights generated from a seed (no download, works offline), in the new model's exact shape and precision. The NPU runs the real vendor-compiled file, because LiteRT-LM can't run anything else.
5. **Model:** Gemma 3 1B is preferred; Qwen3 0.6B (Apache 2.0) is the fallback. Not Gemma 3 270M: too small for a GPU or NPU to show anything. No ~1 GB download on every device.
6. **NPU file is mandatory, not optional ("we need a real result").** It's delivered by **Play for On-device AI** as an AI pack targeted per chip (SoC), **install-time**, so it's on the phone when the app opens. Only supported phones get it (about +0.5–0.7 GB). We host nothing.
7. **Scoring each backend:** score a backend as a whole (its prompt and reply speed together), then take the best backend. Today's code takes the best of each phase separately, so a phone could take its prompt speed from one backend and its reply speed from another.

## Decision 2026-11-23: LLM scoring rule B

The user chose rule **B**, replacing "best backend counts" (decisions 1 and 7):
- The LLM score works like the AI section.
- The same Gemma 3 1B task runs on the CPU (llama.cpp), the GPU (llama.cpp) and the NPU (LiteRT-LM).
- Each engine is scored against its own kind: CPU against the reference CPU, GPU against the reference GPU, NPU against the reference NPU. Each engine score combines prompt and reply speed.
- LLM = (CPU + GPU + NPU) / 3.
- An engine that can't run the task (no NPU model for the chip, a GPU driver llama.cpp can't use, a wrong answer) counts 0. The other engines keep their points. The CPU always runs, so every phone scores something.
- Example: Xiaomi 11T = (CPU + 0 + 0) / 3. Honor 600 Pro = 6,000 by definition (the reference phone).

## iPhone/Mac NPU path: ANEMLL on the Neural Engine (feasibility test, 2026-11-23)

- **Problem:** with rule B an engine that doesn't exist counts 0. LiteRT-LM has no Apple NPU path, so every iPhone and Mac would lose a third of its LLM score.
- **Engine:** ANEMLL (github.com/Anemll/Anemll, MIT, Swift package AnemllCore). It runs Gemma 3 1B on the Neural Engine through Core ML (chunked FFN, LUT-quantized weights, batch-64 prefill, sliding-window rotation).
- **Measured on the M5 Mac** with the prebuilt `anemll/anemll-google-gemma-3-1b-it-ctx4096_0.3.5` (LUT6 FFN and head, LUT8 embeddings, 1.44 GB):
  - "Paris" answered right;
  - prompt (~610 tokens) 626–661 tok/s;
  - reply 53.6–54.7 tok/s;
  - first load 118 s (Neural Engine compile, once), later loads 1 s.
  - For comparison, the M5 with llama.cpp: Metal 2,205/155, CPU 419/138. Honor NPU: 5,394/51.
- **To do for version 5:**
  - Convert our own build at 4-bit (LUT4), with a context like the Android files (~1,280), to match the Android NPU files' precision and size (~0.7–0.8 GB). This needs the gated Gemma weights.
  - Integrate AnemllCore into the iOS/Mac app as the LLM's NPU engine: 512-token prompt, 32-token reply, Paris check.
  - Deliver the model as an App Store background asset pack (Apple's equivalent of Play AI packs).
  - Compile it for the Neural Engine ahead of the benchmark: the first compile took about 2 minutes on the M5.
- **Windows:** Copilot+ PC NPUs have the same gap; still open.

## LLM test v5: design

**Task (the same on every backend):** the chosen model, at one precision, with the same prompt and reply length. Today the prompt is 512 tokens and replies are 32 tokens, which fits the NPU files' 1,280-token context.

| Backend | Devices | Weights | Answer check |
|---|---|---|---|
| llama.cpp CPU | all | generated from a seed | the reference for the GPU (as today) |
| llama.cpp GPU | all with a usable GPU | generated from a seed | compared with the CPU (as today) |
| LiteRT-LM NPU | Snapdragon 8-series, Dimensity 9300–9500 | the real vendor file | the real model's known reply to a fixed prompt |

**Rules**
- A backend that answers wrongly scores 0, and the details say so. The best other backend counts.
- A phone with a supported chip and **no NPU result** (pack missing, sideloaded copy, failed delivery) is an **incomplete run and can't submit**. The server enforces this with its own list of supported SoCs.
- The details page shows every backend's row (e.g. "NPU 1,600 · GPU 13 · CPU 39 tok/s") and labels unsupported ones.
- Each run records the engine version and model file version, because a LiteRT update can move scores.

**Fairness requirements, checked before anything is scored**
- The Qualcomm and MediaTek NPU files must be the same model at the same precision. Gemma 3 1B NPU files as of 2026-11-23 (one file per chip; a phone gets only its own):
  - 4-bit Qualcomm: sm8550, sm8650, sm8750 and sm8850, ~690 MB each;
  - 4-bit MediaTek: mt6989, mt6991 and mt6993, ~1,030 MB each (larger for the same precision);
  - 8-bit Google Tensor: G5 and G6, ~2,020 MB each (not the same precision);
  - generic CPU/GPU: `gemma3-1b-it-int4.litertlm`, 584 MB.

  Chips not on this list get no NPU file. Gemma 3 270M exists for both (mt6993 555 MB, sm8750 461 MB, both 8-bit).
- llama.cpp's precision must match the NPU file's (e.g. 4-bit vs 4-bit). The formats differ slightly between engines; the amount of work must match.

**Per platform**
- **Android:** LiteRT-LM NPU path, AI packs per SoC, the new model shape for llama.cpp, a fix or fallback for the Mali Vulkan regression (it doesn't change the score, but the GPU row should be right). Local test copies install with bundletool so they get the packs.
- **iPhone / Mac:** only the new model shape (llama.cpp CPU + Metal). The smaller model needs far less memory than today's 2.6 GB free / 6 GB RAM requirement. More iPhones can run it, and the risk of a memory kill drops (the friend's iPhone 17 Pro crash is suspected to be one).
- **Windows:** only the new model shape (benchllm.dll).
- **Server (thapi):** `BenchmarkScoring.CurrentVersion = 5`, new fields (NPU prompt and reply speed, engine, model file version), new LLM point constants from a fresh Honor reference run, the supported-SoC rule in `Problem()`, and the new rows in `BenchmarkDetail`.

**Release**
- Upload Android, iOS and Windows, then switch the server only when all three are live (store reviews take different times).
- Old app versions: Submit must fail with a clear "update the app to submit" message.

## AI answer check: reviewed 2026-11-23

- **The check's code is correct.** All six models have UINT8 inputs and outputs, each with one scale and zero point. Byte-wise Pearson correlation therefore equals the correlation of the real values. The NPU gets 0.99–1.00 and Mali GPUs mostly pass.
- **Every Adreno GPU fails it since 2.1.5:**
  - Motorola Adreno 829: 5 of 6 models;
  - S23 Ultra Adreno 740: 6 of 6;
  - Honor Adreno 830: 5 of 6 (only Upscale passes).
- The speeds are the same as in 2.0, before the check existed, so the Adreno GPU AI points counted before 2.1.5 (including the GPU part of the 6000 reference) were very likely wrong answers.
- **Suspect:** LiteRT's default GPU math on Adreno (FP16). LiteRT 2.3.0 also offers FP32, FP16 with FP32 accumulation, and infinite-float capping. The app uses none of them and skips GPU tuning on Adreno.
- **Proposed:** a test-build probe of the Adreno GPU at FP32 and at FP16 with FP32 accumulation (agreement and speed). If one fixes it, version 5 runs every GPU at a precision that gives right answers, with the same setting everywhere.

## AI section: fixes for non-Snapdragon phones (proposed, not decided)

1. **Find and fix the 2.1.x AI GPU regression on Mali** (972 vs 1,738, Detect now 0). Compare 2.0.4's GPU delegate settings with the 2.1.x AI tuning / affinity / ADPF changes.
2. **Detect model on MediaTek:** re-export YOLOX without the operations MediaTek's NPU can't run (EXP, ARG_MAX). Check the other five models for CPU fallback too.
3. **Score rule:** the average of CPU, GPU and NPU punishes any phone with one weak path. Options: keep the average, take the best backend, or a weighting. Each accelerator has its own reference today (Honor = 6000 per accelerator), so "best of" needs a common scale first.
4. **Model source:** the models are Qualcomm AI Hub builds. Consider vendor-neutral sources, or per-vendor compiled files through AI packs, like the LLM.
5. Any change here is a scoring change. It goes into version 5 or waits.

## CPU and GPU: checks before version 5

**CPU (fair today, keep; add evidence).** It's the same C++ code on every device (BenchCore), results are checked, AES and SHA use the chip's crypto instructions as real apps do, and the scores are geometric means. It measures what an app gets by default: no core pinning, and the power mode counts. Open question: the X300 loses single-core although Geekbench has the D9500 about 13 % ahead. Its only fast core is one C1-Ultra (4.21 GHz), next to 3 at 3.5 GHz and 4 at 2.7 GHz, while the 8 Elite has 2 at 4.32 GHz plus 6 at 3.53 GHz. Kernel windows are short (0.3 s warm-up, about 1.3 s timed per Math kernel). **Plan:** record which core and clock each kernel ran at. Decide only after seeing it.

**GPU (OpenGL ES scenes likely unfair to Mali).**
- Lake Valley and Neon City use PC-style MSAA: multisampled renderbuffers resolved with `glBlitFramebuffer` (`LakeValleyRenderer.kt` ~316/523, `NeonCityRenderer.kt` ~323/543).
- There is no `EXT_multisampled_render_to_texture` and no `glInvalidateFramebuffer`, and every frame ends with `glFinish()`.
- Arm's Mali guidance warns against exactly this pattern, and it affects most non-Snapdragon phones on the board (X300, POCO X8, Xiaomi 17T, Vivo T3, Xiaomi 11T all have Mali).
- The Vulkan RT scene (no such pattern) is where the X300 wins by 71 %.

**Plan:** an A/B probe with on-tile MSAA (`EXT_multisampled_render_to_texture`) plus invalidation, and frame timing that doesn't drain the GPU every frame. Measure on the Honor and the X300. That's the recommended mobile practice on Adreno too, so it's fair to both. If the gap closes, ship it in version 5.

**Recommendation (2026-11-22):**
- **CPU:** don't change the test. Only add the core and clock recording (it changes no score). No core pinning: that would measure the chip's peak instead of what apps get.
- **GPU:** after the A/B confirms it, change the two OpenGL ES scenes:
  1. on-tile MSAA plus invalidation of buffers that aren't needed;
  2. up to 2 frames in flight, timed by fences, instead of `glFinish()` after every frame.
- **No new scenes needed:** Blender, `lake_valley.bin` and `neon_city.bin` stay as they are. Only the Android renderer code changes (`LakeValleyRenderer.kt`, `NeonCityRenderer.kt`, `GlKit.kt`; `GpuBench.kt` for timing), with the current path kept as a fallback on GPUs without the extension (e.g. for Neon City's HDR format). The A/B also compares the output images pixel by pixel to prove the picture is unchanged.
- **The iOS renderer already does change 1:** memoryless MSAA textures, `.multisampleResolve`, depth `.dontCare` (`LakeValleyRenderer.swift` ~277–454). So the MSAA fix brings Android in line with iOS. Windows (PC GPUs) needs nothing.
- Change 2 (frames in flight): iOS also waits for every frame (`waitUntilCompleted`). If adopted, do it on every platform so the timing method stays the same, or not at all. Lower priority than change 1.
- Keep the GPU point constants (no recalibration). Leave the Vulkan RT scene as it is. A Vulkan port of the raster scenes would be the most modern option, but it's a large job: later, if at all.

## Progress: GPU tile-friendly drawing (branch `feature/v5-gpu-tile`, worktree `.claude/worktrees/v5-gpu`)

Started 2026-11-22. Scenes and assets untouched; same picture. **Off by default everywhere:** scored runs keep the version 4 path until version 5 switches it on.

- **Android:**
  - `engine/gpu/GlTile.kt` plus `cpp/gl_tile_jni.cpp` (`libthgltile.so`): `GL_EXT_multisampled_render_to_texture`.
  - `LakeValleyRenderer` and `NeonCityRenderer` take a `tileFriendly` flag. When on:
    - 4× MSAA is drawn on-tile and resolved straight into the scene texture (Neon City also resolves depth into its depth texture), with no blit;
    - depth that nobody reads is discarded (reflection, Lake Valley main);
    - full-screen passes don't load the old target (the additive bloom up-chain keeps it).
  - Fallback: the classic blit plus discards, when the driver lacks the extension or refuses the framebuffer.
  - `GpuScene.drawPath` says which path ran.
- **A/B probe (test builds only, `TestBuild.PROBES`: debug and bench, never Play):**
  - After the scored GPU tests, each OpenGL scene runs classic, tile, tile, classic (2 s warm-up plus 8 s each).
  - Then one moment (t = 12.5 s) is drawn classic twice and tile-friendly once, and compared pixel by pixel.
  - The result is report lines `GPU A/B …` (also in logcat, tag `GpuAbProbe`), stored as `BenchmarkResult.gpuAb`.
- **Windows:** `GpuBench.TileFriendly` (off) plus `Discard(view)` via `ID3D11DeviceContext1.DiscardView`, for the multisample buffers after resolve, the reflection depth, and full-screen targets (not the additive up-chain). It helps Snapdragon X laptops; desktop GPUs ignore it. Checked by compiling the GPU files on the Mac. There's no ARM laptop to measure on.
- **macOS / iOS:** no change needed: Metal already uses memoryless MSAA with `.multisampleResolve` and `.dontCare`.
- **Probe runs are never submittable:** the new validity flag `TEST_PROBES` ("a test build ran its extra probes"). The probe adds about 2 minutes of GPU load, which warms the phone for the later sections.
- **Checked on emulators (2026-11-22):** host GPU and SwiftShader. Neither offers `GL_EXT_multisampled_render_to_texture`, so only the fallback path ran. Its pictures were identical to classic (mean difference 0.000 levels, 0.000 % of pixels), fps within noise, no errors, no crash. **The on-tile path is untested until a real phone runs it.**
- **Test APK:** `~/Downloads/ThermoHammer-2.1.7-bench-gpu-ab.apk` (bench build: release-optimised, debug-signed, app id `…thermohammer.bench`, named "ThermoHammer Bench", installs next to the Play app).
- **Honor result (2026-11-23, Adreno 830; on-tile really used):**
  - Lake Valley: +0.3 %, picture identical.
  - Neon City: +2.4 %, **but the picture differs** (mean 10.5 levels, 61 % of pixels).
  - Likely cause: the on-tile depth texture isn't resolved the way the classic depth blit is, and fog and motion blur read it.
  - Planned fix: Neon City keeps the classic resolve (with discards) on Android.
- **Next:** that Neon City fix, then the X300 run (Mali), where the gain should show.

## Progress: LiteRT-LM NPU probe (test builds only, 2026-11-23, same branch)

- **Model files exist as a matched pair:** `Gemma3-1B-IT_q4_ekv1280_<soc>.litertlm` (4-bit, 1,280-token context) for sm8750 (Honor 8 Elite), mt6993 (X300 D9500), mt6991 (POCO), sm8550 (S23 Ultra), sm8650, sm8850, mt6989, at huggingface.co/litert-community/Gemma3-1B-IT. They are **gated behind Gemma's licence** (HTTP 401 without a Hugging Face login). Qwen3-0.6B is public but has only an mt6993 NPU build.
- **Library:** `com.google.ai.edge.litertlm:litertlm-android:0.19.1`, only in bench and debug builds. It is one native library (`liblitertlm_jni.so`, 22 MB), minSdk 24, built with Kotlin 2.4. It has a built-in `benchmark(modelPath, backend, …, prefillTokens, decodeTokens, cacheDir)` returning prompt and reply tokens/s, time to first token and load time (`@OptIn(ExperimentalApi::class)`).
- **NPU dispatch ABI:** LiteRT-LM 0.19 wants `LiteRtDispatchQueryInterface`, which LiteRT 2.4.0's dispatch libraries export. The app's 2.3.0 ones export `LiteRtDispatchGetApi`. So `build.gradle.kts` extracts 2.4.0's `libLiteRtDispatch_{Qualcomm,MediaTek}.so` into test-build assets (`litertlm-npu/`). At run time they go into `code_cache/litertlm-npu/` with links to the app's `libQnn*` and `libneuron*`. The scored AI test keeps 2.3.0. The 2.4.0 dispatch libraries depend only on system libraries; the Qualcomm one points `ADSP_LIBRARY_PATH` at that folder.
- **Probe** (`src/nonRelease/.../engine/llm/NpuLlmProbe.kt`; Play builds get a no-op stand-in in `src/release`):
  - It runs after the scored LLM test, under the crash marker `LLM:npu`.
  - It picks the `.litertlm` whose name contains the phone's `Build.SOC_MODEL`, from `/sdcard/Android/data/com.granturismo.thermohammer.bench/files/npu-llm/`. It gets there by adb push, or by "Open with / Share → NPU model for ThermoHammer Bench" (`NpuModelImportActivity`, test-build manifests only).
  - Speed: `benchmark(…, prefillTokens = 512, decodeTokens = 32)`.
  - Answer check: a greedy (top-k 1) "capital of France" reply must contain "Paris".
  - On a phone without an NPU path (the emulator), a generic `.litertlm` runs on the CPU as a smoke test.
  - Report lines start `LLM NPU · LiteRT-LM`. The run gets `TEST_PROBES`.
- **Not yet:** llama.cpp running the same Gemma 3 1B shape (needed before scoring); MediaTek NPU in the bench APK (no NeuroPilot adapter in bench; the Play modules have it).

## Results: Honor run, 2026-11-23 11:28 (bench APK, Performance mode)

- **GPU A/B:** no gain on the Adreno 830. Lake Valley on-tile +0.1 %, Neon City discards -0.6 % (noise). Both pictures are identical. Only a Mali result (X300) can still justify the change.
- **AI GPU precision probe:** precision is not the cause.
  - FP32 and FP16 with FP32 accumulation stay wrong (agreement about 0).
  - Super resolution, correct by default, turns wrong (0.00) under all three settings, even infinity capping alone.
  - Next: LiteRT's OpenGL backend, BUFFER storage, and whether the GPU's output changes with its input.
- **Vulkan llama.cpp on Adreno:** ran with no crash, so the fault patch works. Its answers are wrong (correlation 0.00).
- **LLM NPU:** `Failed to create engine: UNKNOWN … llm_litert_npu_compiled_model_executor.cc:3458`.
  - No phone log yet.
  - Suspect: the LiteRT 2.4 dispatch library running on the app's older QNN libraries.
  - Next build: the probe's own warning and error log lines go into the report, plus an adb hook to run only the NPU probe.
- **LLM NPU, fixed at 11:50 the same day:** prompt **5,301 tok/s** (512 tokens), reply **64.05 tok/s** (32 tokens), first token 0.11 s, load 1.29 s. The answer is "Paris" (right).
  - Cause: the app's QNN 2.51 has System API 1.15.0, and LiteRT 2.4's dispatch library needs at least 1.16.0. QNN 2.52.0 (Maven, 2026-11-18) has it.
  - The bench build now uses QNN 2.52 (`qnnRuntimeBench`). Play's npu/ modules are still on 2.51, so **version 5 must move them to 2.52**, which also changes the scored AI NPU runtime.
  - For comparison, llama.cpp on the same phone with the 3B test model: prompt 306.56 tok/s (GPU), reply 17.20 tok/s (CPU). The model sizes differ, so nothing is comparable until llama.cpp runs the Gemma 3 1B shape.
  - Scoring warning: with "best backend counts", an NPU prompt speed ~10× llama.cpp's makes the LLM score mostly a question of whether Google made a file for the phone's chip.
- **Full benchmark on QNN 2.52, 12:02 the same day:**
  - The scored AI NPU is still right on all six models (0.99–1.00), at speeds about 2 % below the 2.51 run. CPU AI was also 1 % lower and the phone was hotter, so this looks like heat.
  - The NPU LLM inside a full run: prompt 5,427 tok/s, reply 48.9 tok/s (64 when cool), answer right. Heat costs the reply about a quarter, not the prompt, so version 5 needs a cool-down or a fixed place in the run order for it.

## Results: GPU AI bug found and fixed, Gemma shape in llama.cpp (2026-11-23, ~13:00)

- **The GPU AI bug:** on Adreno, LiteRT 2.3's GPU OpenCL path never writes its answer into the app's output buffers.
  - This holds for every OpenCL variant: default, buffers, textures, FP32.
  - The outputs read the same before any run, after 1 run, 300 ms later and after 12 runs.
  - The one "right" model (super resolution, 1.00) was the CPU's previous answer, left in reused memory.
  - LiteRT's **OpenGL** path answers right: classify 0.99–1.00, segment 0.80, upscale 1.00, depth 0.99–1.00, sound 0.98–1.00.
  - YOLOX (detect) doesn't compile on OpenGL.
- **Fix (version 5 branch):**
  - The GPU tuning now covers Adreno too, so the tuner's rule ("a default with wrong answers gives way to the fastest setting that answers right") picks OpenGL.
  - Outputs are filled with a marker before each run, so an answer that never arrives fails with a reason instead of passing on leftover memory.
  - The precision probe is removed.
  - Still to do: the speed on OpenGL (from a full run), and a bug report to Google with the repro (`RUN_AI_GPU_DIAG`, `mode=deep`).
- **llama.cpp with Gemma 3 1B's shape** (`bc_llm_set_model`, seeded Q4_0, 0.56 GB):

  | Device | Backend | Prompt | Reply |
  |---|---|---|---|
  | Mac M5 | CPU | 419 tok/s | 138 tok/s |
  | Mac M5 | Metal | 2,205 tok/s | 155 tok/s |
  | Honor (quick hook) | CPU | 130 tok/s | 50 tok/s |
  | Honor (quick hook) | GPU OpenCL | 932 tok/s | 40 tok/s |
  | Honor | NPU (LiteRT-LM) | 5,300 tok/s | 49–64 tok/s |

  The GPU agrees with the CPU. Test builds report it as "LLM GEMMA 3 1B SHAPE" before the NPU line.
- **X300 build:** `ThermoHammer-2.1.7-bench-x300.apk`, made with bundletool (base plus mtk_v9). Every build on this branch is on QNN 2.52.

## Memory: the database test measured a warm-up, not the phone (fixed 2026-11-23)

- **Before:** the Honor's MEM 3 swung between about 9.3k and 15.6k points from run to run (updates 51k–185k/s).
- **Cause:** SQLite's rate on a phone climbs for 1–2 s before it levels off. Seen in 100 ms buckets on the Honor: updates go from ~15k/s to a steady ~400k/s after 1.2–2.1 s, with a different climb each time. The 2-second steps timed from a cold start measured the climb.
- **Ruled out by tests on the phone:** WAL checkpoints between the steps, the storage test running before, a pause.
- **Fix (`MemBench.warmUp`):** each step first runs untimed until its rate levels off (five 100 ms buckets within 10 %, at most 4 s), then it is timed as before.
- **Result, five runs:** updates 381k–390k/s (±1 %), queries 42.6k–44.1k (±2 %), inserts 9.8k–11.3k (±7 %, lower than the cold numbers because the steady state includes the WAL growing). The test takes about 14 s instead of 8.
- **Version 5:** MEM 3's point references must be re-set from these warmed-up numbers. Updates now read ~2–7× the old numbers.

## Order of work (status 2026-11-23 12:10)

1. **Finish the Android probes (report-only, nothing scored):**
   - Done on the Honor:
     - The NPU LLM works (QNN 2.52).
     - GPU MSAA A/B: no gain on Adreno.
     - The scored AI NPU is fine on QNN 2.52.
     - Precision is ruled out as the GPU AI cause.
   - a. **The GPU AI bug:** LiteRT's other GPU routes on the Honor (OpenGL, BUFFER storage), and whether the output changes with the input. Either a route that's right, or a bug report for Google.
   - b. **llama.cpp with the Gemma 3 1B shape** (seeded weights), run on the Honor: CPU, GPU and NPU on the same model.
   - c. **The X300:**
     - MediaTek NPU adapter in the bench build, plus the mt6993 model (1,020 MB).
     - The NPU LLM, GPU A/B on Mali, and the AI GPU and Detect checks.
   - d. CPU core and clock recording (optional).
2. **Decide** from the numbers:
   - the LLM rule (how far the NPU counts against llama.cpp);
   - the model size (the MediaTek file is about 1 GB);
   - whether to keep the GPU change;
   - the AI GPU rule.
3. **Build version 5:**
   - Android: Play AI packs per chip, QNN 2.52 in the npu/ modules, the Gemma licence notice and terms.
   - The server (version 5).
   - iOS/Mac and Windows: llama.cpp with the Gemma 3 1B shape.
4. **Release:** all three apps, then the server switch (the leaderboard starts over).

## Open questions

- Play for On-device AI is in beta, and its size limits for AI packs are unknown. The first test upload will show whether a ~660 MB pack is accepted.
- The Gemma license if we ship Gemma files. The Hugging Face login is for our one download only; app users never log in, because the file arrives with the app. Redistribution is allowed (Gemma Terms of Use 3.1, checked 2026-11-23, terms last modified 2026-04-01; Gemma 3 is listed in the appendix). Four conditions apply:
  - a "Notice" text file that says "Gemma is provided under and subject to the Gemma Terms of Use found at gemma.ai/resources/terms";
  - a copy of the Terms in the app (e.g. an open-source licences screen);
  - the Prohibited Use Policy written into the app's terms of use, plus a notice to users that Gemma is subject to it;
  - modified files marked as modified (we don't modify them).

  The alternative is Qwen3 0.6B (Apache 2.0, no gate), but it has no Snapdragon NPU build.
- Whether a Gemma 3 1B NPU file exists for the Dimensity 9500 at the same precision as the Snapdragon one.
- The Honor's own NPU speed for the LLM (unmeasured, maybe faster than its GPU).

## Version 5 contract (2026-11-23, what every app and the server implement)

Decided by the user on 2026-11-23: the reference phone (Honor VKP-NX9, Snapdragon 8 Elite) **in Performance mode** scores 6,000 in every test, and all three apps ship together with the server switch.

- **`VERSION = 5`** everywhere (`BenchmarkScore.VERSION`, `BenchmarkScoring.CurrentVersion`).
- **References:** exactly those in `ThermoHammerAndroid/app/src/main/java/com/example/thermohammer/engine/Benchmark.kt` (commit 9e97eee) and in the server's `BenchmarkScoring`.
  - Each platform repeats them and pins them with the same calibration test: Android `BenchmarkScoreTest`, `the reference phone's medians score 6000 on every test`; server `BenchmarkMappingTests.TheReferencePhonesMediansScore6000OnEveryTest`.
  - Export: one 4K encoder's rate is the reference, so the Honor (no AV1 encoder, no 8K encoder) scores 3,000 on export.
- **LLM, rule B:** Gemma 3 1B on every engine.
  - **CPU and GPU:** llama.cpp with BenchCore's Gemma shape (`bc_llm_set_model(BC_LLM_MODEL_GEMMA3_1B)` before the runs).
  - **NPU:** Android uses LiteRT-LM with Google's per-chip file (Play AI pack `gemma_npu`). iPhone/Mac uses ANEMLL on the Neural Engine. Windows has no NPU engine yet, so it counts 0 and the report says so.
  - **Scoring:** each engine scores `6000 × sqrt(prefill/refPrefill × decode/refDecode)` (0 if either is 0) against its own engine's references:

    | Engine | Prefill reference (tok/s) | Decode reference (tok/s) |
    |---|---|---|
    | CPU | 160 | 55 |
    | GPU | 890 | 32 |
    | NPU | 5,450 | 52 |

  - LLM = (CPU + GPU + NPU) / 3.
  - The "prefill/decode points" shown are the best engine's per phase (display only).
- **Payload / signed text:** three fields after `llmFreeGb` and before `validityFlags`:
  - `llmPrefillNpu` and `llmDecodeNpu` (2 decimals; 0 = no NPU result or a wrong answer);
  - `llmNpu` (engine and model file, e.g. `LiteRT-LM 0.19.1 · Gemma3-1B-IT_q4_ekv1280_sm8750`; empty when none).
  - Canonical example (all three apps' tests and the server's): see `BenchmarkScoreTest."canonical string is what the server re-derives"`.
- **Phone Health parts:** `llm.cpu`, `llm.gpu` and `llm.npu` (each engine's points) replace `llm.prefill` and `llm.decode`.
- **Database test:** each step (insert, update, query) warms up untimed until its rate levels off (`MemBench.warmUp`: 100 ms buckets; the last 5 within 10 % of each other and ≥ 85 % of the best; at most 4 s). The references assume this.
- **Incomplete runs:**
  - An Android run on a chip in `NpuModels.CHIPS` without its NPU model sets `ValidityFlag.NPU_MODEL_MISSING` (1 shl 10), and the server refuses it too.
  - An older app version gets "The leaderboard moved to a new scoring version. Update ThermoHammer and run the benchmark again to submit."
- **Release:** the apps go up held (Play managed publishing, App Store manual release, Partner Center manual publish). Once all three are approved, the server deploys (migration `AddBenchmarkLlmNpu`, adding 6 columns) and the apps are released together. The board starts over, because it lists only the current version.

## Decisions and status, 2026-11-23 evening

- **Scale:** the Honor in Performance mode = 6,000 (the user's rule: Balanced only if it kept old records valid, and it couldn't). References = the median of 11 Honor runs (commit 9e97eee).
- **GPU tile-friendly anti-aliasing:** dropped. It gained nothing on Adreno (5 runs) or Mali (11T).
- **Android:**
  - Gemma NPU files as one fast-follow Play AI pack, `gemma_npu`. It's split per chip by the existing device groups: SM8550, SM8650, SM8750, SM8850, MT6989, MT6991, MT6993.
  - Google Tensor's files are 8-bit, so they're left out (Pixels count NPU 0).
  - A supported chip without its file gets `NPU_MODEL_MISSING` and can't submit.
- **iPhone/Mac:**
  - ANEMLL's core is vendored (MIT).
  - Our own conversion of Gemma 3 1B: LUT4 layers and head, LUT8 embeddings, context 1,280, ANEMLL 0.3.5 at commit 8d2791c.
  - Delivered from **the user's server** (user's choice over Apple-hosted asset packs): `thermohammer.gtgroup.dev/models/gemma3-1b-it-lut4-ctx1280-v1.aar`. It downloads once over Wi-Fi with a SHA-256 check, then compiles for the Neural Engine in the background.
  - A14/M1 can't load a 1,280-context chunked model (ANEMLL's limit), so those devices count NPU 0.
- **Windows:** no NPU LLM engine; that third counts 0.
- **New chips:** a chip without a Google file counts NPU 0 until we add its file (an app update for the pack plus the server's `NpuLlmChips`).

## Release runbook (version 5)

Nothing below has run. Steps 1–4 can happen in any order; step 5 is the switch.

1. **Android NPU model decision (morning test):**
   - On the Honor's test build, use the checklist picker to run LLM only. The report has two NPU lines:
     - the chip's own file (Google, per chip);
     - `LLM NPU GENERIC`: one file for every chip, compiled on the phone.
   - If the generic file is about as fast, ship it: one pack for every chip, so new chips work on day one. Otherwise ship the per-chip pack.
   - The 11T can try the generic file on MediaTek: import it with "NPU model for ThermoHammer Bench".
2. **Android:**
   - Put the chosen model file(s) in `gemma.npu.dir`.
   - Run `./gradlew :ai:gemma_npu:linkGemmaModels`, then `THERMOHAMMER_VK_GLSLC=/opt/homebrew/bin/glslc ./gradlew :app:bundleRelease`. It refuses to build while a chip's file is missing.
   - Upload to **internal testing** first and install from Play on the Honor. That's the only real test of AI pack delivery.
   - Then production with **managed publishing** (held until step 5).
3. **iPhone/Mac:**
   - Put the model on the server: `ThermoHammerWeb/deploy/upload_model.sh <scratchpad>/anepack/gemma3-1b-it-lut4-ctx1280-v1.aar` (997 MB, SHA-256 `af5e93c1…`).
   - Run `deploy/deploy.sh`; thweb gets the `models` volume and route.
   - Check `curl -I https://thermohammer.gtgroup.dev/models/gemma3-1b-it-lut4-ctx1280-v1.aar` → 200.
   - **The iPhone app must not go live before the model is online:** without it, every run on a capable device is incomplete.
   - Archive and upload iOS and macOS 2.2.0 (46). Submit both with **manual release**.
4. **Windows:**
   - Packages: `ThermoHammerWindows/msix/out/ThermoHammer_2.2.0.0_{x64,arm64}.msix` (also in Downloads). Exports were checked against the app's imports.
   - Run one VM test (Gemma shape on Vulkan, database warm-up).
   - Partner Center submission with **manual publish**.
5. **The switch, once all three stores have approved:**
   1. Apply `ThermoHammer.Api/tools/migrate_v5_AddBenchmarkLlmNpu.sql` (adds 6 columns, idempotent; the version 4 server ignores them, so this can go first).
   2. Merge `feature/v5-gpu-tile` into main and push. GitHub Actions deploys thapi with CurrentVersion 5, and version 4 apps get "update the app".
   3. Release all three store versions.
   4. Run `ThermoHammerWeb/deploy/deploy.sh`: the website's version 5 pages and the empty-board state.
   5. Check: a version 5 submission from the Honor, the board, a run page, the "update" message from an old build.
