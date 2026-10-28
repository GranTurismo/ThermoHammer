# ThermoHammer for Windows

WPF on .NET 10 for x64 and ARM64 PCs: the stress test, the thermal X-Ray, the leaderboard and the benchmark (CPU, MEM, GPU,
UX, AI, LLM) of the phone apps, with the same scoring and the same server.

## Build (on a Mac or on Windows)

```
cd ThermoHammerWindows
native/build.sh            # benchcore.dll (CPU, MEM, QR) for x64 and arm64 → runtimes/win-<arch>/native/
native/build_llm.sh        # benchllm.dll (llama.cpp: CPU + Vulkan) → runtimes/win-<arch>/native/
dotnet publish -c Release -r win-x64   # or win-arm64
```

`native/*.sh` cross-compile with llvm-mingw (fetched once into `native/.toolchain`). The app project copies the DLLs it finds
in `runtimes/`. Assets come from the phones' own files: `ThermoHammerAndroid/.../assets` (scenes, web pages, media, AI labels)
and `Assets/` (the editing photos as JPEG, the ONNX models).

The ONNX models are made from the phones' `.tflite` files by `BenchmarkScene/build_onnx_models.py`.

## What runs where

| Section | How |
| --- | --- |
| CPU, MEM, QR | BenchCore (C++, `benchcore.dll`) — the same code as the phones |
| GPU 1–2 | Direct3D 11, HLSL ports of the Metal shaders (`Engine/Gpu`), compiled when the test starts |
| GPU 3 (ray tracing) | Direct3D 12 + DXR 1.1 ray queries (`Engine/Gpu/Rt`, the same `NeonCity.hlsl` compiled by DXC with `NC_RT=1`); a PC without DXR 1.1 (or without bindless tier 3) scores 0 there and the GPU score counts 2/3. **Written but never run on real DXR hardware** (Wine has no DXIL/DXR) |
| UX web tests | the phones' pages in WebView2 (`UI/UxStage.cs`), laid out 1280×720 at a device pixel ratio of 1.5 |
| UX photo editing | Direct3D 11 (`Engine/Ux/PhotoEditScene.cs`) |
| UX video, export | Media Foundation: DXVA decoders, hardware encoders through the sink writer, a D3D11 video processor between them |
| AI | ONNX Runtime: CPU (int8), DirectML on the GPU (float16); the NPU row needs a vendor provider (QNN, OpenVINO, Vitis AI) and scores 0 until one is added |
| LLM | llama.cpp through `benchllm.dll`: CPU and Vulkan |

## Checks without a window

The checks run on a Mac under CrossOver's Wine (real Direct3D 11 on the Mac's GPU):

```
ThermoHammer.exe --gpuTest lake|neon --seconds 8 --png frame.png
ThermoHammer.exe --uxTest qr|photo|video --seconds 6 --png frame.png
ThermoHammer.exe --aiTest cpu|gpu|npu --png prefix
ThermoHammer.exe --llmTest cpu|gpu
ThermoHammer.exe --headlessBenchmark --only gpu,ux,ai,llm
```

Their numbers land in `%LOCALAPPDATA%\ThermoHammer\{gpu,ux,ai,llm}-test.txt` and `headless-benchmark.txt`.
`THERMOHAMMER_SHADER_DEBUG=1` compiles shaders without optimisation and logs them (Wine's HLSL compiler is slow);
`THERMOHAMMER_MF_SOFTWARE=1` keeps Media Foundation off Direct3D (Wine). Wine has no WebView2, DirectML or hardware
codecs: those parts need a real PC.

With a window: `--autoBenchmark [--only gpu,ux,...] [--stay]`, `--autoStress`, `--screenshot <png> --after 8,40,75 [--tab leaderboard]`
(`--screenshot` renders the WPF tree, so WebView2 and other native surfaces come out black: for those, capture the desktop).
