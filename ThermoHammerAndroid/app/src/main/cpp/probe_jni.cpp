// What this phone offers the LLM test, for its report: the CPU's feature words, the Vulkan device llama.cpp's Vulkan
// backend runs on, and — where the GPU isn't an Adreno, so llama.cpp's OpenCL kernels aren't used — what the phone's
// OpenCL driver is. Report only: nothing here is scored, and nothing runs a kernel (the OpenCL part only asks questions).
// Built into libbenchllm.so (class LlmNative, engine/llm/LlmNative.kt), not into the CPU-only i8mm library.
//
// The Vulkan loader (libvulkan.so, which the library already links for llama.cpp) is asked through
// vkGetInstanceProcAddr only: Android's loader exports the Vulkan 1.1 entry points only from API 28, and a phone with
// no driver answers vkCreateInstance with an error instead of crashing. OpenCL goes through cl_loader.c, which finds the
// driver at run time, so a phone with none reports that and nothing else.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#define CL_TARGET_OPENCL_VERSION 300
#include <CL/cl.h>

#include <jni.h>
#include <sys/auxv.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <set>
#include <string>
#include <vector>

extern "C" VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* pName);

namespace {

/** A text builder: printf-style appends, lines separated by '\n'. */
struct Text {
    std::string s;
    void line(const char* fmt, ...) __attribute__((format(printf, 2, 3))) {
        char buf[1024];
        va_list ap;
        va_start(ap, fmt);
        std::vsnprintf(buf, sizeof buf, fmt, ap);
        va_end(ap);
        if (!s.empty()) s += '\n';
        s += buf;
    }
};

/**
 * Driver strings are the phone's, not ours, and NewStringUTF aborts the app on bytes that aren't valid modified
 * UTF-8: keep printable ASCII and well-formed two- and three-byte sequences (this report's own "·" and "×"), and
 * turn everything else — control bytes, stray continuation bytes, four-byte forms — into '?'.
 */
std::string ascii(const std::string& v) {
    std::string out;
    const auto cont = [&](size_t i) { return i < v.size() && (static_cast<unsigned char>(v[i]) & 0xC0) == 0x80; };
    for (size_t i = 0; i < v.size();) {
        const unsigned char c = static_cast<unsigned char>(v[i]);
        if (c == '\n' || (c >= 0x20 && c <= 0x7e)) { out += char(c); i++; }
        else if (c >= 0xC2 && c <= 0xDF && cont(i + 1)) { out.append(v, i, 2); i += 2; }
        else if (c >= 0xE0 && c <= 0xEF && cont(i + 1) && cont(i + 2)) {
            const unsigned char c1 = static_cast<unsigned char>(v[i + 1]);
            const bool overlong = c == 0xE0 && c1 < 0xA0, surrogate = c == 0xED && c1 >= 0xA0;
            if (overlong || surrogate) { out += '?'; i++; } else { out.append(v, i, 3); i += 3; }
        }
        else { out += '?'; i++; }
    }
    return out;
}

jstring to_java(JNIEnv* env, const std::string& s) { return env->NewStringUTF(ascii(s).c_str()); }

// ── Vulkan ───────────────────────────────────────────────────────────────────

const char* vendor_name(uint32_t id) {
    switch (id) {
        case 0x13B5: return "Arm";
        case 0x5143: return "Qualcomm";
        case 0x1010: return "Imagination";
        case 0x1002: return "AMD";
        case 0x10DE: return "NVIDIA";
        case 0x8086: return "Intel";
        case 0x144D: return "Samsung";
        case 0x1AE0: return "Google";
        default: return "?";
    }
}

const char* component_name(VkComponentTypeKHR t) {
    switch (t) {
        case VK_COMPONENT_TYPE_FLOAT16_KHR: return "f16";
        case VK_COMPONENT_TYPE_FLOAT32_KHR: return "f32";
        case VK_COMPONENT_TYPE_FLOAT64_KHR: return "f64";
        case VK_COMPONENT_TYPE_SINT8_KHR: return "i8";
        case VK_COMPONENT_TYPE_SINT16_KHR: return "i16";
        case VK_COMPONENT_TYPE_SINT32_KHR: return "i32";
        case VK_COMPONENT_TYPE_UINT8_KHR: return "u8";
        case VK_COMPONENT_TYPE_UINT16_KHR: return "u16";
        case VK_COMPONENT_TYPE_UINT32_KHR: return "u32";
        case VK_COMPONENT_TYPE_BFLOAT16_KHR: return "bf16";
        default: return "?";
    }
}

std::string version(uint32_t v) {
    char b[32];
    std::snprintf(b, sizeof b, "%u.%u.%u", VK_API_VERSION_MAJOR(v), VK_API_VERSION_MINOR(v), VK_API_VERSION_PATCH(v));
    return b;
}

std::string subgroup_ops(VkSubgroupFeatureFlags f) {
    static const struct { VkSubgroupFeatureFlagBits bit; const char* name; } OPS[] = {
        {VK_SUBGROUP_FEATURE_BASIC_BIT, "basic"}, {VK_SUBGROUP_FEATURE_VOTE_BIT, "vote"},
        {VK_SUBGROUP_FEATURE_ARITHMETIC_BIT, "arithmetic"}, {VK_SUBGROUP_FEATURE_BALLOT_BIT, "ballot"},
        {VK_SUBGROUP_FEATURE_SHUFFLE_BIT, "shuffle"}, {VK_SUBGROUP_FEATURE_SHUFFLE_RELATIVE_BIT, "shuffle-relative"},
        {VK_SUBGROUP_FEATURE_CLUSTERED_BIT, "clustered"}, {VK_SUBGROUP_FEATURE_QUAD_BIT, "quad"}};
    std::string out;
    for (const auto& o : OPS) if (f & o.bit) out += (out.empty() ? "" : " ") + std::string(o.name);
    return out.empty() ? "none" : out;
}

std::string vulkan_report(const std::string& llama_gpu) {
    auto gipa = vkGetInstanceProcAddr;
    auto create_instance = reinterpret_cast<PFN_vkCreateInstance>(gipa(nullptr, "vkCreateInstance"));
    if (!create_instance) return "Vulkan · the loader has no vkCreateInstance";
    uint32_t loader = VK_API_VERSION_1_0;
    if (auto enum_version = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(gipa(nullptr, "vkEnumerateInstanceVersion")))
        enum_version(&loader);

    // a 1.0 instance needs VK_KHR_get_physical_device_properties2 to be asked about features
    std::vector<const char*> instance_ext;
    static const char* const GPDP2 = "VK_KHR_get_physical_device_properties2";
    if (loader < VK_API_VERSION_1_1) {
        auto enum_ext = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(gipa(nullptr, "vkEnumerateInstanceExtensionProperties"));
        uint32_t n = 0;
        if (enum_ext && enum_ext(nullptr, &n, nullptr) == VK_SUCCESS && n) {
            std::vector<VkExtensionProperties> e(n);
            if (enum_ext(nullptr, &n, e.data()) == VK_SUCCESS)
                for (uint32_t i = 0; i < n; i++) if (!std::strcmp(e[i].extensionName, GPDP2)) instance_ext.push_back(GPDP2);
        }
    }
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "ThermoHammer probe";
    app.apiVersion = loader >= VK_API_VERSION_1_3 ? VK_API_VERSION_1_3 : loader;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = uint32_t(instance_ext.size());
    ci.ppEnabledExtensionNames = instance_ext.data();
    VkInstance inst = VK_NULL_HANDLE;
    const VkResult made = create_instance(&ci, nullptr, &inst);
    if (made != VK_SUCCESS || !inst) {
        char b[96];
        std::snprintf(b, sizeof b, "Vulkan · loader %s · no usable driver (vkCreateInstance returned %d)", version(loader).c_str(), int(made));
        return b;
    }

#define VKI(name) reinterpret_cast<PFN_##name>(gipa(inst, #name))
    auto destroy_instance = VKI(vkDestroyInstance);
    auto enum_devices = VKI(vkEnumeratePhysicalDevices);
    auto get_props = VKI(vkGetPhysicalDeviceProperties);
    auto get_mem = VKI(vkGetPhysicalDeviceMemoryProperties);
    auto enum_dev_ext = VKI(vkEnumerateDeviceExtensionProperties);
    auto get_props2 = VKI(vkGetPhysicalDeviceProperties2);
    auto get_feats2 = VKI(vkGetPhysicalDeviceFeatures2);
    if (!get_props2) get_props2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(gipa(inst, "vkGetPhysicalDeviceProperties2KHR"));
    if (!get_feats2) get_feats2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(gipa(inst, "vkGetPhysicalDeviceFeatures2KHR"));
    auto get_coop = VKI(vkGetPhysicalDeviceCooperativeMatrixPropertiesKHR);
#undef VKI
    Text out;
    uint32_t count = 0;
    if (!enum_devices || !get_props || enum_devices(inst, &count, nullptr) != VK_SUCCESS) count = 0;
    std::vector<VkPhysicalDevice> devs(count);
    if (count && enum_devices(inst, &count, devs.data()) != VK_SUCCESS) count = 0;
    out.line("Vulkan · loader %s · %u device%s", version(loader).c_str(), count, count == 1 ? "" : "s");

    for (uint32_t d = 0; d < count; d++) {
        VkPhysicalDeviceProperties p{};
        get_props(devs[d], &p);
        std::set<std::string> ext;
        if (enum_dev_ext) {
            uint32_t n = 0;
            if (enum_dev_ext(devs[d], nullptr, &n, nullptr) == VK_SUCCESS && n) {
                std::vector<VkExtensionProperties> e(n);
                if (enum_dev_ext(devs[d], nullptr, &n, e.data()) == VK_SUCCESS) for (const auto& x : e) ext.insert(x.extensionName);
            }
        }
        const auto has = [&](const char* name) { return ext.count(name) != 0; };
        const bool v11 = p.apiVersion >= VK_API_VERSION_1_1, v12 = p.apiVersion >= VK_API_VERSION_1_2, v13 = p.apiVersion >= VK_API_VERSION_1_3;
        static const char* const TYPES[] = {"other", "integrated GPU", "discrete GPU", "virtual GPU", "CPU"};

        // properties: subgroup (1.1), driver identity (1.2), subgroup size range and integer dot product (1.3, or their extensions)
        VkPhysicalDeviceSubgroupProperties sub{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
        VkPhysicalDeviceVulkan12Properties p12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_PROPERTIES};
        VkPhysicalDeviceVulkan13Properties p13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_PROPERTIES};
        VkPhysicalDeviceSubgroupSizeControlProperties sgsc{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_PROPERTIES};
        VkPhysicalDeviceShaderIntegerDotProductProperties dotp{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_PROPERTIES};
        const bool props2 = get_props2 && (v11 || !instance_ext.empty());
        if (props2) {
            VkPhysicalDeviceProperties2 q{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
            void** tail = &q.pNext;
            const auto chain = [&](auto& s) { *tail = &s; tail = &s.pNext; };
            if (v11) chain(sub);
            if (v12) chain(p12);
            if (v13) chain(p13);
            else {
                if (has("VK_EXT_subgroup_size_control")) chain(sgsc);
                if (has("VK_KHR_shader_integer_dot_product")) chain(dotp);
            }
            get_props2(devs[d], &q);
        }

        // features: 16- and 8-bit storage, float16 and int8 arithmetic, integer dot product, cooperative matrices
        VkPhysicalDeviceVulkan11Features f11{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES};
        VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
        VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
        VkPhysicalDevice16BitStorageFeatures s16{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
        VkPhysicalDevice8BitStorageFeatures s8{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_8BIT_STORAGE_FEATURES};
        VkPhysicalDeviceShaderFloat16Int8Features fi{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
        VkPhysicalDeviceShaderIntegerDotProductFeatures dotf{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_FEATURES};
        VkPhysicalDeviceCooperativeMatrixFeaturesKHR coop{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_COOPERATIVE_MATRIX_FEATURES_KHR};
        const bool coop_ext = has("VK_KHR_cooperative_matrix");
        const bool feats2 = get_feats2 && (v11 || !instance_ext.empty());
        if (feats2) {
            VkPhysicalDeviceFeatures2 q{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
            void** tail = &q.pNext;
            const auto chain = [&](auto& s) { *tail = &s; tail = &s.pNext; };
            if (v12) { chain(f11); chain(f12); }
            else {
                if (v11) chain(s16);
                if (has("VK_KHR_8bit_storage")) chain(s8);
                if (has("VK_KHR_shader_float16_int8")) chain(fi);
            }
            if (v13) chain(f13);
            else if (has("VK_KHR_shader_integer_dot_product")) chain(dotf);
            if (coop_ext) chain(coop);
            get_feats2(devs[d], &q);
        }
        const bool storage16 = v12 ? f11.storageBuffer16BitAccess : s16.storageBuffer16BitAccess;
        const bool storage8 = v12 ? f12.storageBuffer8BitAccess : s8.storageBuffer8BitAccess;
        const bool float16 = v12 ? f12.shaderFloat16 : fi.shaderFloat16;
        const bool int8 = v12 ? f12.shaderInt8 : fi.shaderInt8;
        const bool dot_feature = v13 ? f13.shaderIntegerDotProduct : dotf.shaderIntegerDotProduct;

        const bool is_llama = !llama_gpu.empty() && llama_gpu == p.deviceName;
        out.line("  %s (%s 0x%04x, %s, API %s, driver 0x%08x)%s", p.deviceName, vendor_name(p.vendorID), p.vendorID,
                 p.deviceType < 5 ? TYPES[p.deviceType] : "?", version(p.apiVersion).c_str(), p.driverVersion,
                 is_llama ? " · the GPU llama.cpp runs on" : "");
        if (v12) out.line("    driver id %d · %s · %s", int(p12.driverID), ascii(p12.driverName).c_str(), ascii(p12.driverInfo).c_str());
        if (v11) {
            const uint32_t lo = v13 ? p13.minSubgroupSize : sgsc.minSubgroupSize, hi = v13 ? p13.maxSubgroupSize : sgsc.maxSubgroupSize;
            out.line("    subgroup size %u%s · operations %s · in compute %s", sub.subgroupSize,
                     (lo || hi) ? (" (selectable " + std::to_string(lo) + "–" + std::to_string(hi) + ")").c_str() : "",
                     subgroup_ops(sub.supportedOperations).c_str(), (sub.supportedStages & VK_SHADER_STAGE_COMPUTE_BIT) ? "yes" : "no");
        }
        if (feats2) {
            out.line("    storage 16-bit buffers %s · 8-bit buffers %s · float16 arithmetic %s · int8 arithmetic %s",
                     storage16 ? "yes" : "no", storage8 ? "yes" : "no", float16 ? "yes" : "no", int8 ? "yes" : "no");
            const bool accel8 = v13 ? p13.integerDotProduct8BitSignedAccelerated : dotp.integerDotProduct8BitSignedAccelerated;
            const bool accel8mixed = v13 ? p13.integerDotProduct8BitMixedSignednessAccelerated : dotp.integerDotProduct8BitMixedSignednessAccelerated;
            const bool accel4x8 = v13 ? p13.integerDotProduct4x8BitPackedSignedAccelerated : dotp.integerDotProduct4x8BitPackedSignedAccelerated;
            out.line("    integer dot product %s (8-bit signed accelerated %s, mixed signedness %s, 4x8-bit packed %s) · cooperative matrix KHR %s NV %s",
                     dot_feature ? "yes" : "no", accel8 ? "yes" : "no", accel8mixed ? "yes" : "no", accel4x8 ? "yes" : "no",
                     coop_ext ? (coop.cooperativeMatrix ? "yes" : "listed, off") : "no", has("VK_NV_cooperative_matrix") ? "yes" : "no");
        } else {
            out.line("    no Vulkan 1.1 property queries: storage, float16 and integer-dot support can't be read");
        }
        if (coop_ext && get_coop && coop.cooperativeMatrix) {
            uint32_t n = 0;
            if (get_coop(devs[d], &n, nullptr) == VK_SUCCESS && n) {
                std::vector<VkCooperativeMatrixPropertiesKHR> cm(n, VkCooperativeMatrixPropertiesKHR{VK_STRUCTURE_TYPE_COOPERATIVE_MATRIX_PROPERTIES_KHR});
                if (get_coop(devs[d], &n, cm.data()) == VK_SUCCESS) {
                    std::string shapes;
                    for (uint32_t i = 0; i < n && i < 8; i++) {
                        char b[80];
                        std::snprintf(b, sizeof b, "%s%ux%ux%u %s*%s+%s", i ? ", " : "", cm[i].MSize, cm[i].NSize, cm[i].KSize,
                                      component_name(cm[i].AType), component_name(cm[i].BType), component_name(cm[i].CType));
                        shapes += b;
                    }
                    out.line("    cooperative matrix shapes (%u): %s%s", n, shapes.c_str(), n > 8 ? ", …" : "");
                }
            }
        }
        VkPhysicalDeviceMemoryProperties mem{};
        if (get_mem) get_mem(devs[d], &mem);
        double local_gb = 0;
        for (uint32_t h = 0; h < mem.memoryHeapCount; h++) if (mem.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) local_gb += double(mem.memoryHeaps[h].size) / 1e9;
        out.line("    compute shared memory %u KiB · workgroup invocations %u · storage buffer range %.1f GB · device-local heaps %.1f GB",
                 p.limits.maxComputeSharedMemorySize / 1024, p.limits.maxComputeWorkGroupInvocations,
                 double(p.limits.maxStorageBufferRange) / 1e9, local_gb);
    }
    if (destroy_instance) destroy_instance(inst, nullptr);
    return out.s;
}

// ── OpenCL ───────────────────────────────────────────────────────────────────

std::string cl_string(cl_device_id dev, cl_device_info what) {
    size_t n = 0;
    if (clGetDeviceInfo(dev, what, 0, nullptr, &n) != CL_SUCCESS || !n) return "";
    std::string v(n, '\0');
    if (clGetDeviceInfo(dev, what, n, v.data(), nullptr) != CL_SUCCESS) return "";
    while (!v.empty() && v.back() == '\0') v.pop_back();
    return v;
}

std::string cl_platform_string(cl_platform_id plat, cl_platform_info what) {
    size_t n = 0;
    if (clGetPlatformInfo(plat, what, 0, nullptr, &n) != CL_SUCCESS || !n) return "";
    std::string v(n, '\0');
    if (clGetPlatformInfo(plat, what, n, v.data(), nullptr) != CL_SUCCESS) return "";
    while (!v.empty() && v.back() == '\0') v.pop_back();
    return v;
}

/** Whether any of the words is among the device's extensions (space-separated names). */
bool has_ext(const std::set<std::string>& ext, std::initializer_list<const char*> names) {
    for (const char* n : names) if (ext.count(n)) return true;
    return false;
}

std::string opencl_report() {
    cl_uint np = 0;
    const cl_int r = clGetPlatformIDs(0, nullptr, &np);
    if (r != CL_SUCCESS || !np) {
        char b[128];
        std::snprintf(b, sizeof b, "OpenCL · libOpenCL.so didn't load or lists no platform (clGetPlatformIDs returned %d)", int(r));
        return b;
    }
    std::vector<cl_platform_id> plats(np);
    clGetPlatformIDs(np, plats.data(), nullptr);
    Text out;
    out.line("OpenCL · %u platform%s", np, np == 1 ? "" : "s");
    for (cl_platform_id pl : plats) {
        out.line("  %s (%s) · %s", cl_platform_string(pl, CL_PLATFORM_NAME).c_str(), cl_platform_string(pl, CL_PLATFORM_VENDOR).c_str(),
                 cl_platform_string(pl, CL_PLATFORM_VERSION).c_str());
        cl_uint nd = 0;
        if (clGetDeviceIDs(pl, CL_DEVICE_TYPE_ALL, 0, nullptr, &nd) != CL_SUCCESS || !nd) { out.line("    no devices"); continue; }
        std::vector<cl_device_id> devs(nd);
        clGetDeviceIDs(pl, CL_DEVICE_TYPE_ALL, nd, devs.data(), nullptr);
        for (cl_device_id dev : devs) {
            cl_uint cus = 0;
            cl_ulong local = 0, global = 0;
            size_t wg = 0;
            clGetDeviceInfo(dev, CL_DEVICE_MAX_COMPUTE_UNITS, sizeof cus, &cus, nullptr);
            clGetDeviceInfo(dev, CL_DEVICE_LOCAL_MEM_SIZE, sizeof local, &local, nullptr);
            clGetDeviceInfo(dev, CL_DEVICE_GLOBAL_MEM_SIZE, sizeof global, &global, nullptr);
            clGetDeviceInfo(dev, CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeof wg, &wg, nullptr);
            out.line("    %s · %s · %s · driver %s · %u compute units · work-group %zu · local memory %llu KiB · global memory %.1f GB",
                     cl_string(dev, CL_DEVICE_NAME).c_str(), cl_string(dev, CL_DEVICE_VERSION).c_str(),
                     cl_string(dev, CL_DEVICE_OPENCL_C_VERSION).c_str(), cl_string(dev, CL_DRIVER_VERSION).c_str(), cus, wg,
                     (unsigned long long) (local / 1024), double(global) / 1e9);
            const std::string list = cl_string(dev, CL_DEVICE_EXTENSIONS);
            std::set<std::string> ext;
            for (size_t i = 0; i < list.size();) {
                const size_t j = list.find(' ', i);
                const std::string w = list.substr(i, j == std::string::npos ? std::string::npos : j - i);
                if (!w.empty()) ext.insert(w);
                if (j == std::string::npos) break;
                i = j + 1;
            }
            out.line("    subgroups %s · Arm integer dot product %s · Khronos integer dot product %s · Arm matrix multiply %s · fp16 %s · Arm scheduling controls %s · command buffers %s",
                     has_ext(ext, {"cl_khr_subgroups", "cl_khr_subgroup_shuffle", "cl_khr_subgroup_ballot"}) ? "yes" : "no",
                     has_ext(ext, {"cl_arm_integer_dot_product_int8", "cl_arm_integer_dot_product_accumulate_int8"}) ? "yes" : "no",
                     has_ext(ext, {"cl_khr_integer_dot_product"}) ? "yes" : "no", has_ext(ext, {"cl_arm_matrix_multiply"}) ? "yes" : "no",
                     has_ext(ext, {"cl_khr_fp16"}) ? "yes" : "no", has_ext(ext, {"cl_arm_scheduling_controls"}) ? "yes" : "no",
                     has_ext(ext, {"cl_khr_command_buffer"}) ? "yes" : "no");
            out.line("    extensions (%zu): %s", ext.size(), list.c_str());
        }
    }
    return out.s;
}

}  // namespace

#define PROBE_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_thermohammer_engine_llm_LlmNative_##name

/** [AT_HWCAP, AT_HWCAP2]: the kernel's words for what every core of this CPU can do (decoded in CpuFeatures.kt). */
PROBE_JNI(jlongArray, hwcaps)(JNIEnv* env, jclass) {
    const jlong v[2] = {jlong(getauxval(AT_HWCAP)), jlong(getauxval(AT_HWCAP2))};
    jlongArray a = env->NewLongArray(2);
    if (a) env->SetLongArrayRegion(a, 0, 2, v);
    return a;
}

/** The Vulkan devices and what they offer llama.cpp's shaders, as text lines; `llamaGpu` marks the one it runs on. */
PROBE_JNI(jstring, vulkanReport)(JNIEnv* env, jclass, jstring llamaGpu) {
    const char* g = llamaGpu ? env->GetStringUTFChars(llamaGpu, nullptr) : nullptr;
    const std::string name = g ? g : "";
    if (g) env->ReleaseStringUTFChars(llamaGpu, g);
    return to_java(env, vulkan_report(name));
}

/** What the phone's OpenCL driver says about itself (questions only, no kernels), as text lines. */
PROBE_JNI(jstring, openClReport)(JNIEnv* env, jclass) { return to_java(env, opencl_report()); }

/**
 * Which of llama.cpp's Vulkan shader families this library was BUILT with. That is fixed when the shaders are compiled
 * (CMakeLists.txt asks glslc whether it knows each GLSL extension): a family that glslc couldn't compile isn't in the
 * library at all, and the backend can't use it however well the phone's driver supports it. What the driver offers
 * is a run-time question, answered by vulkanReport and by the backend's own device line.
 */
PROBE_JNI(jstring, vulkanBuildFeatures)(JNIEnv* env, jclass) {
    const auto yn = [](bool b) { return b ? "yes" : "no"; };
#ifdef GGML_VULKAN_COOPMAT_GLSLC_SUPPORT
    const bool coopmat = true;
#else
    const bool coopmat = false;
#endif
#ifdef GGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT
    const bool int_dot = true;
#else
    const bool int_dot = false;
#endif
#ifdef GGML_VULKAN_BFLOAT16_GLSLC_SUPPORT
    const bool bf16 = true;
#else
    const bool bf16 = false;
#endif
    Text t;
    t.line("Vulkan shaders built with: cooperative matrix %s · integer dot product (the int8 matrix kernels) %s · bf16 %s",
           yn(coopmat), yn(int_dot), yn(bf16));
    return to_java(env, t.s);
}
