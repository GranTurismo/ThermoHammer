#include "vk_loader.h"

#include <dlfcn.h>

#define NRT_DEFINE(f) PFN_##f f = nullptr;
NRT_GLOBAL_FUNCS(NRT_DEFINE)
NRT_INSTANCE_FUNCS(NRT_DEFINE)
NRT_DEVICE_FUNCS(NRT_DEFINE)
NRT_RT_FUNCS(NRT_DEFINE)
#undef NRT_DEFINE

static PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;

namespace nrt {

bool loadVulkan() {
    if (getInstanceProcAddr) return true;
    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) return false;
    getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
    if (!getInstanceProcAddr) return false;
#define NRT_LOAD(f) f = reinterpret_cast<PFN_##f>(getInstanceProcAddr(VK_NULL_HANDLE, #f));
    NRT_GLOBAL_FUNCS(NRT_LOAD)
#undef NRT_LOAD
    return vkCreateInstance != nullptr;
}

void loadInstance(VkInstance instance) {
#define NRT_LOAD(f) f = reinterpret_cast<PFN_##f>(getInstanceProcAddr(instance, #f));
    NRT_INSTANCE_FUNCS(NRT_LOAD)
#undef NRT_LOAD
}

void loadDevice(VkDevice device) {
#define NRT_LOAD(f) f = reinterpret_cast<PFN_##f>(vkGetDeviceProcAddr(device, #f));
    NRT_DEVICE_FUNCS(NRT_LOAD)
    NRT_RT_FUNCS(NRT_LOAD)
#undef NRT_LOAD
    if (!vkGetBufferDeviceAddress)
        vkGetBufferDeviceAddress = reinterpret_cast<PFN_vkGetBufferDeviceAddress>(
            vkGetDeviceProcAddr(device, "vkGetBufferDeviceAddressKHR"));
}

}  // namespace nrt
