// The Vulkan loader, opened at run time. ggml-vulkan takes nearly every Vulkan function through the dispatcher it
// fills from vkGetInstanceProcAddr, but names three of them directly (that one, vkGetDeviceProcAddr and
// vkCmdCopyBuffer): these definitions, in place of an import library for vulkan-1.dll, find the loader when the GPU
// is first asked about. A PC with no Vulkan driver has no vulkan-1.dll: vkGetInstanceProcAddr then returns null,
// ggml-vulkan's instance setup fails and bc_llm_gpu_name() answers "".
#include <windows.h>
#include <vulkan/vulkan_core.h>

static void* loader_symbol(const char* name) {
    static HMODULE loader = LoadLibraryExA("vulkan-1.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    return loader ? reinterpret_cast<void*>(GetProcAddress(loader, name)) : nullptr;
}

extern "C" {

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* name) {
    static auto real = reinterpret_cast<PFN_vkGetInstanceProcAddr>(loader_symbol("vkGetInstanceProcAddr"));
    return real ? real(instance, name) : nullptr;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* name) {
    static auto real = reinterpret_cast<PFN_vkGetDeviceProcAddr>(loader_symbol("vkGetDeviceProcAddr"));
    return real ? real(device, name) : nullptr;
}

VKAPI_ATTR void VKAPI_CALL vkCmdCopyBuffer(VkCommandBuffer commandBuffer, VkBuffer srcBuffer, VkBuffer dstBuffer,
                                           uint32_t regionCount, const VkBufferCopy* pRegions) {
    static auto real = reinterpret_cast<PFN_vkCmdCopyBuffer>(loader_symbol("vkCmdCopyBuffer"));
    if (real) real(commandBuffer, srcBuffer, dstBuffer, regionCount, pRegions);
}

}
