llama.cpp b11700 (commit 23f7aab, https://github.com/ggml-org/llama.cpp, MIT, see LICENSE) — only what the LLM test
needs: `include/`, `src/` (every model's source, which llama-model.cpp links against), ggml's core, its CPU backend
with the Arm kernels (`ggml-cpu/`, without the x86, RISC-V, … arches, AMX, KleidiAI and SpacemiT code), its Metal
backend (iOS), its OpenCL backend (Android, Adreno) and its Vulkan backend (Android, other GPUs; with its shader
generator, which the Android build runs on the computer building it). No CMake: the Android CMakeLists.txt and project.yml list the
sources, and BenchCore/test/llm_run.sh builds them on a Mac.

Local changes:
- `ggml/src/ggml-version.h` and `src/llama-version.h` are written by hand (upstream generates them with CMake).
- `ggml/src/ggml-cpu/amx/` keeps only its headers (ggml-cpu.cpp includes them; the code is x86-only).
- `src/llama-model-loader.cpp`: for a model built from metadata (`llama_model_init_from_user`), an optional tensor
  the metadata doesn't list is left out instead of being created as F32 — so the test's model has no biases and a
  tied output head, exactly like Llama 3.2 3B.
- `ggml/src/ggml-opencl/kernels/embed_kernel.py` is gone: the Android CMakeLists.txt embeds the kernels itself.
- `ggml/src/ggml-vulkan/ggml-vulkan-buffers.cpp`: host buffers come from `posix_memalign` instead of `aligned_alloc`,
  which Android has only from API 28 (the app supports 24).
- `ggml/src/ggml-vulkan/ggml-vulkan.cpp`: its three direct `vkGetPhysicalDeviceFeatures2` calls go through its Vulkan
  dispatcher like every other call — Android's libvulkan exports that Vulkan 1.1 function only from API 28.
- `ggml/src/ggml-vulkan/ggml-vulkan.cpp`: `VK_KHR_16bit_storage` (core since Vulkan 1.1, its feature checked anyway) is
  enabled only when the driver lists it — the emulator's doesn't, and device creation failed.
