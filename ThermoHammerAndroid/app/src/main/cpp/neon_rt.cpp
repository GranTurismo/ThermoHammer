#include "neon_rt.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>

#define NRT_LOG(...) __android_log_print(ANDROID_LOG_INFO, "NeonRt", __VA_ARGS__)
#define VK_CHECK(x)                                                                        \
    do {                                                                                   \
        VkResult r_ = (x);                                                                 \
        if (r_ != VK_SUCCESS) throw std::runtime_error(std::string(#x) + " = " + std::to_string(r_)); \
    } while (0)

namespace {

// SPIR-V compiled from shaders/ at build time (CMakeLists.txt)
const uint32_t kSceneVert[] =
#include "scene_vert.inc"
;
const uint32_t kSceneFrag[] =
#include "scene_frag.inc"
;
const uint32_t kSceneFragRt[] =
#include "scene_frag_rt.inc"
;
const uint32_t kSkyVert[] =
#include "sky_vert.inc"
;
const uint32_t kSkyFrag[] =
#include "sky_frag.inc"
;
const uint32_t kGlowVert[] =
#include "glow_vert.inc"
;
const uint32_t kGlowFrag[] =
#include "glow_frag.inc"
;
const uint32_t kRainVert[] =
#include "rain_vert.inc"
;
const uint32_t kRainFrag[] =
#include "rain_frag.inc"
;
const uint32_t kFogComp[] =
#include "fog_comp.inc"
;
const uint32_t kFogCompRt[] =
#include "fog_comp_rt.inc"
;
const uint32_t kCombineComp[] =
#include "combine_comp.inc"
;
const uint32_t kDownComp[] =
#include "down_comp.inc"
;
const uint32_t kUpComp[] =
#include "up_comp.inc"
;
const uint32_t kFinalComp[] =
#include "final_comp.inc"
;

constexpr uint32_t RAIN = 36000;
constexpr uint32_t MAX_LIGHTS = 4096;
constexpr uint32_t VERTEX_BYTES = 20;
constexpr VkFormat HDR = VK_FORMAT_R16G16B16A16_SFLOAT;

struct PostPush { float texelX, texelY, p0, p1; };
struct ScenePush { int32_t actor, instanced; float seed; int32_t pad; };

const char* kRtExtensions[] = {VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME, VK_KHR_RAY_QUERY_EXTENSION_NAME,
                               VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME};

bool hasExtension(const std::vector<VkExtensionProperties>& all, const char* name) {
    for (auto& e : all) if (strcmp(e.extensionName, name) == 0) return true;
    return false;
}

std::vector<VkExtensionProperties> deviceExtensions(VkPhysicalDevice d) {
    uint32_t n = 0;
    vkEnumerateDeviceExtensionProperties(d, nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> e(n);
    vkEnumerateDeviceExtensionProperties(d, nullptr, &n, e.data());
    return e;
}

std::string rayQueryProblem(VkPhysicalDevice d) {
    VkPhysicalDeviceProperties p;
    vkGetPhysicalDeviceProperties(d, &p);
    if (p.apiVersion < VK_API_VERSION_1_2) return "GPU driver is older than Vulkan 1.2";
    auto ext = deviceExtensions(d);
    for (auto name : kRtExtensions) if (!hasExtension(ext, name)) return std::string("missing ") + name;
    VkPhysicalDeviceRayQueryFeaturesKHR rq{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR};
    VkPhysicalDeviceAccelerationStructureFeaturesKHR as{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR};
    VkPhysicalDeviceVulkan12Features v12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    as.pNext = &rq;
    v12.pNext = &as;
    VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    f2.pNext = &v12;
    vkGetPhysicalDeviceFeatures2(d, &f2);
    if (!as.accelerationStructure) return "acceleration structures unsupported";
    if (!rq.rayQuery) return "ray queries unsupported";
    if (!v12.bufferDeviceAddress) return "buffer device address unsupported";
    return "";
}

VkInstance createInstance(uint32_t apiVersion) {
    uint32_t n = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> avail(n);
    vkEnumerateInstanceExtensionProperties(nullptr, &n, avail.data());
    std::vector<const char*> ext;
    for (auto name : {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME})
        if (hasExtension(avail, name)) ext.push_back(name);
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "ThermoHammer";
    app.pEngineName = "NeonRt";
    app.apiVersion = apiVersion;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = (uint32_t)ext.size();
    ci.ppEnabledExtensionNames = ext.data();
    VkInstance inst = VK_NULL_HANDLE;
    VK_CHECK(vkCreateInstance(&ci, nullptr, &inst));
    nrt::loadInstance(inst);
    return inst;
}

/** {pos, euler XYZ, scale} → 3×4 row-major transform (Blender XYZ euler: R = Rz·Ry·Rx). */
void eulerRows(const float* t, float* out) {
    float cx = cosf(t[3]), sx = sinf(t[3]), cy = cosf(t[4]), sy = sinf(t[4]), cz = cosf(t[5]), sz = sinf(t[5]);
    float kx = t[6], ky = t[7], kz = t[8];
    out[0] = cz * cy * kx; out[1] = (cz * sy * sx - sz * cx) * ky; out[2] = (cz * sy * cx + sz * sx) * kz; out[3] = t[0];
    out[4] = sz * cy * kx; out[5] = (sz * sy * sx + cz * cx) * ky; out[6] = (sz * sy * cx - cz * sx) * kz; out[7] = t[1];
    out[8] = -sy * kx; out[9] = cy * sx * ky; out[10] = cy * cx * kz; out[11] = t[2];
}

VkDeviceSize alignUp(VkDeviceSize v, VkDeviceSize a) { return (v + a - 1) / a * a; }

}  // namespace

namespace nrt {

std::string probeRayQuery() {
    if (!loadVulkan()) return "no Vulkan driver";
    uint32_t api = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion) vkEnumerateInstanceVersion(&api);
    if (api < VK_API_VERSION_1_2) return "Vulkan loader is older than 1.2";
    VkInstance inst;
    try {
        inst = createInstance(VK_API_VERSION_1_2);
    } catch (const std::exception& e) {
        return e.what();
    }
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(inst, &n, devs.data());
    std::string why = "no Vulkan GPU";
    for (auto d : devs) {
        why = rayQueryProblem(d);
        if (why.empty()) break;
    }
    vkDestroyInstance(inst, nullptr);
    return why;
}

// ── setup ──────────────────────────────────────────────────────────────────

NeonRt::NeonRt(const uint8_t* asset, size_t size, const std::vector<std::string>& actorMeshes,
               const std::vector<int>& actorCapacity, int width, int height, bool rt)
    : rayTracing(rt), width_(width), height_(height) {
    if (!loadVulkan()) throw std::runtime_error("no Vulkan driver");
    createDevice();
    loadScene(asset, size, actorMeshes, actorCapacity);
    if (rayTracing) buildBlas();
    createTargets();
    createDescriptors();
    createPipelines();
    NRT_LOG("ready: %s, %zu meshes, %u static instances, ray tracing %s", deviceName.c_str(), meshes_.size(),
            staticInstances_, rayTracing ? "on" : "off");
}

void NeonRt::createDevice() {
    instance_ = createInstance(rayTracing ? VK_API_VERSION_1_2 : VK_API_VERSION_1_1);
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(instance_, &n, nullptr);
    if (n == 0) throw std::runtime_error("no Vulkan GPU");
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(instance_, &n, devs.data());
    phys_ = devs[0];
    if (rayTracing) {
        for (auto d : devs) if (rayQueryProblem(d).empty()) { phys_ = d; break; }
        std::string why = rayQueryProblem(phys_);
        if (!why.empty()) throw std::runtime_error(why);
    }
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(phys_, &props);
    deviceName = props.deviceName;
    vkGetPhysicalDeviceMemoryProperties(phys_, &memProps_);

    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(phys_, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(phys_, &qn, qf.data());
    queueFamily_ = UINT32_MAX;
    for (uint32_t i = 0; i < qn; i++)
        if ((qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) { queueFamily_ = i; break; }
    if (queueFamily_ == UINT32_MAX) throw std::runtime_error("no graphics+compute queue");

    auto avail = deviceExtensions(phys_);
    std::vector<const char*> ext;
    if (hasExtension(avail, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) ext.push_back(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    if (rayTracing) for (auto e : kRtExtensions) ext.push_back(e);

    VkPhysicalDeviceRayQueryFeaturesKHR rq{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR};
    VkPhysicalDeviceAccelerationStructureFeaturesKHR as{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR};
    VkPhysicalDeviceVulkan12Features v12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    if (rayTracing) {
        rq.rayQuery = VK_TRUE;
        as.accelerationStructure = VK_TRUE;
        as.pNext = &rq;
        v12.bufferDeviceAddress = VK_TRUE;
        v12.pNext = &as;
        f2.pNext = &v12;
    }
    float prio = 1.f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = queueFamily_;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &f2;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = (uint32_t)ext.size();
    dci.ppEnabledExtensionNames = ext.data();
    VK_CHECK(vkCreateDevice(phys_, &dci, nullptr, &device_));
    loadDevice(device_);
    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);

    if (rayTracing) {
        VkPhysicalDeviceAccelerationStructurePropertiesKHR asp{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR};
        VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        p2.pNext = &asp;
        vkGetPhysicalDeviceProperties2(phys_, &p2);
        scratchAlign_ = std::max<VkDeviceSize>(asp.minAccelerationStructureScratchOffsetAlignment, 1);
    }

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = queueFamily_;
    VK_CHECK(vkCreateCommandPool(device_, &pci, nullptr, &pool_));
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = pool_;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    VK_CHECK(vkAllocateCommandBuffers(device_, &ai, &cmd_));
    VK_CHECK(vkAllocateCommandBuffers(device_, &ai, &presentCmd_));
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VK_CHECK(vkCreateFence(device_, &fci, nullptr, &fence_));
    VK_CHECK(vkCreateFence(device_, &fci, nullptr, &presentFence_));
}

uint32_t NeonRt::memoryType(uint32_t bits, VkMemoryPropertyFlags props) {
    for (uint32_t i = 0; i < memProps_.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (memProps_.memoryTypes[i].propertyFlags & props) == props) return i;
    throw std::runtime_error("no suitable memory type");
}

Buffer NeonRt::makeBuffer(VkDeviceSize size, VkBufferUsageFlags usage, bool hostVisible) {
    Buffer b;
    b.size = std::max<VkDeviceSize>(size, 16);
    bool address = rayTracing && (usage & (VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
                                           VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
                                           VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR));
    if (address) usage |= VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    ci.size = b.size;
    ci.usage = usage;
    VK_CHECK(vkCreateBuffer(device_, &ci, nullptr, &b.buf));
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(device_, b.buf, &req);
    VkMemoryAllocateFlagsInfo flags{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO};
    flags.flags = VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.pNext = address ? &flags : nullptr;
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = memoryType(req.memoryTypeBits, hostVisible
        ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VK_CHECK(vkAllocateMemory(device_, &mai, nullptr, &b.mem));
    VK_CHECK(vkBindBufferMemory(device_, b.buf, b.mem, 0));
    if (hostVisible) VK_CHECK(vkMapMemory(device_, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map));
    if (address) {
        VkBufferDeviceAddressInfo bai{VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO};
        bai.buffer = b.buf;
        b.addr = vkGetBufferDeviceAddress(device_, &bai);
    }
    return b;
}

Buffer NeonRt::upload(const void* data, VkDeviceSize size, VkBufferUsageFlags usage) {
    Buffer staging = makeBuffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
    memcpy(staging.map, data, size);
    Buffer b = makeBuffer(size, usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
    VkCommandBuffer cb = beginOnce();
    VkBufferCopy region{0, 0, size};
    vkCmdCopyBuffer(cb, staging.buf, b.buf, 1, &region);
    endOnce(cb);
    destroy(staging);
    return b;
}

void NeonRt::destroy(Buffer& b) {
    if (b.map) vkUnmapMemory(device_, b.mem);
    if (b.buf) vkDestroyBuffer(device_, b.buf, nullptr);
    if (b.mem) vkFreeMemory(device_, b.mem, nullptr);
    b = Buffer{};
}

Image NeonRt::makeImage(uint32_t w, uint32_t h, VkFormat format, VkImageUsageFlags usage) {
    Image im;
    im.w = w; im.h = h; im.format = format;
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = format;
    ci.extent = {w, h, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_CHECK(vkCreateImage(device_, &ci, nullptr, &im.img));
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(device_, im.img, &req);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VK_CHECK(vkAllocateMemory(device_, &mai, nullptr, &im.mem));
    VK_CHECK(vkBindImageMemory(device_, im.img, im.mem, 0));
    bool depth = format == VK_FORMAT_D32_SFLOAT || format == VK_FORMAT_X8_D24_UNORM_PACK32 ||
                 format == VK_FORMAT_D24_UNORM_S8_UINT || format == VK_FORMAT_D16_UNORM;
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = im.img;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = format;
    vci.subresourceRange = {static_cast<VkImageAspectFlags>(depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT), 0, 1, 0, 1};
    VK_CHECK(vkCreateImageView(device_, &vci, nullptr, &im.view));
    return im;
}

void NeonRt::destroy(Image& i) {
    if (i.view) vkDestroyImageView(device_, i.view, nullptr);
    if (i.img) vkDestroyImage(device_, i.img, nullptr);
    if (i.mem) vkFreeMemory(device_, i.mem, nullptr);
    i = Image{};
}

VkCommandBuffer NeonRt::beginOnce() {
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = pool_;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    VkCommandBuffer cb;
    VK_CHECK(vkAllocateCommandBuffers(device_, &ai, &cb));
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_CHECK(vkBeginCommandBuffer(cb, &bi));
    return cb;
}

void NeonRt::endOnce(VkCommandBuffer cb) {
    VK_CHECK(vkEndCommandBuffer(cb));
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    VK_CHECK(vkQueueSubmit(queue_, 1, &si, VK_NULL_HANDLE));
    VK_CHECK(vkQueueWaitIdle(queue_));
    vkFreeCommandBuffers(device_, pool_, 1, &cb);
}

VkShaderModule NeonRt::shader(const uint32_t* code, size_t bytes) {
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    ci.codeSize = bytes;
    ci.pCode = code;
    VkShaderModule m;
    VK_CHECK(vkCreateShaderModule(device_, &ci, nullptr, &m));
    return m;
}

// ── scene ──────────────────────────────────────────────────────────────────

void NeonRt::loadScene(const uint8_t* a, size_t size, const std::vector<std::string>& actorMeshes,
                       const std::vector<int>& actorCapacity) {
    size_t p = 0;
    auto need = [&](size_t n) { if (p + n > size) throw std::runtime_error("scene asset truncated"); };
    auto u32 = [&]() { need(4); uint32_t v; memcpy(&v, a + p, 4); p += 4; return v; };
    auto name16 = [&]() { need(16); std::string s(reinterpret_cast<const char*>(a + p), strnlen(reinterpret_cast<const char*>(a + p), 16)); p += 16; return s; };
    need(8);
    if (memcmp(a, "NCSC", 4) != 0) throw std::runtime_error("not a Neon City scene");
    p = 4;
    if (u32() != 1) throw std::runtime_error("unsupported scene asset version");

    std::vector<uint8_t> vtx;
    std::vector<uint32_t> idx;
    uint32_t meshCount = u32();
    for (uint32_t m = 0; m < meshCount; m++) {
        Mesh mesh;
        mesh.name = name16();
        uint32_t nv = u32(), ni = u32(), flags = u32();
        p += 4;  // maxZ
        bool wide = flags & 1;
        mesh.chunk = flags & 2;
        mesh.firstVertex = (uint32_t)(vtx.size() / VERTEX_BYTES);
        mesh.firstIndex = (uint32_t)idx.size();
        mesh.vertexCount = nv;
        mesh.indexCount = ni;
        need((size_t)nv * VERTEX_BYTES);
        vtx.insert(vtx.end(), a + p, a + p + (size_t)nv * VERTEX_BYTES);
        p += (size_t)nv * VERTEX_BYTES;
        need((size_t)ni * (wide ? 4 : 2));
        for (uint32_t i = 0; i < ni; i++) {
            uint32_t v;
            if (wide) { memcpy(&v, a + p + i * 4, 4); } else { uint16_t s; memcpy(&s, a + p + i * 2, 2); v = s; }
            idx.push_back(v);
        }
        p += (size_t)ni * (wide ? 4 : 2);
        if (!wide && (ni & 1)) p += 2;
        meshes_.push_back(mesh);
    }
    auto meshIndex = [&](const std::string& n) {
        for (size_t i = 0; i < meshes_.size(); i++) if (meshes_[i].name == n) return (int)i;
        throw std::runtime_error("mesh " + n + " missing");
    };

    // static instances: each chunk once in world space, then every instance group
    std::vector<float> rows;
    std::vector<uint32_t> instMesh;
    auto addInstance = [&](uint32_t mesh, const float* r12) {
        rows.insert(rows.end(), r12, r12 + 12);
        rows.insert(rows.end(), 8, 0.f);
        instMesh.push_back(mesh);
    };
    const float identity[12] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    for (uint32_t m = 0; m < meshCount; m++) {
        if (!meshes_[m].chunk) continue;
        draws_.push_back({m, (uint32_t)instMesh.size(), 1, 0, 0, 0.f});
        addInstance(m, identity);
        triangles += meshes_[m].indexCount / 3;
    }
    uint32_t groupCount = u32();
    for (uint32_t g = 0; g < groupCount; g++) {
        name16();
        uint32_t mesh = u32(), count = u32();
        need((size_t)count * 36);
        draws_.push_back({mesh, (uint32_t)instMesh.size(), count, 0, 1, g * 7.31f});
        for (uint32_t i = 0; i < count; i++) {
            float t[9], r[12];
            memcpy(t, a + p + (size_t)i * 36, 36);
            eulerRows(t, r);
            addInstance(mesh, r);
        }
        p += (size_t)count * 36;
        triangles += (long long)count * (meshes_[mesh].indexCount / 3);
    }
    staticInstances_ = (uint32_t)instMesh.size();

    // actor batches, animated from Kotlin every frame
    uint32_t off = 0;
    for (size_t b = 0; b < actorMeshes.size(); b++) {
        int m = meshIndex(actorMeshes[b]);
        actorMesh_.push_back(m);
        actorOffset_.push_back((int)off);
        off += (uint32_t)std::max(actorCapacity[b], 0);
        triangles += (long long)std::max(actorCapacity[b], 0) * (meshes_[m].indexCount / 3);
    }
    actorCapacity_ = off;

    VkBufferUsageFlags asInput = rayTracing ? VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR : 0;
    vertices_ = upload(vtx.data(), vtx.size(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | asInput);
    indices_ = upload(idx.data(), idx.size() * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | asInput);
    std::vector<uint32_t> info;
    for (auto& m : meshes_) info.insert(info.end(), {m.firstVertex, m.firstIndex, m.indexCount, m.vertexCount});
    meshInfo_ = upload(info.data(), info.size() * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    staticInst_ = upload(rows.data(), rows.size() * 4, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
    actorInst_ = makeBuffer((VkDeviceSize)std::max(actorCapacity_, 1u) * INSTANCE_FLOATS * 4, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true);

    frameUbo_ = makeBuffer(FRAME_FLOATS * 4, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
    lights_ = makeBuffer(MAX_LIGHTS * 64, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
    cells_ = makeBuffer(CELLS * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
    memset(cells_.map, 0, CELLS * 4);
    lightIdxCapacity_ = 1 << 17;
    lightIdx_ = makeBuffer((VkDeviceSize)lightIdxCapacity_ * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
    extras_ = makeBuffer((VkDeviceSize)(staticInstances_ + actorCapacity_) * 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
    memset(extras_.map, 0, extras_.size);

    if (rayTracing) {
        // TLAS instance records: the static part is written once (after the BLAS exist)
        tlasInstances_ = makeBuffer((VkDeviceSize)(staticInstances_ + actorCapacity_) * sizeof(VkAccelerationStructureInstanceKHR),
                                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true);
        auto* inst = static_cast<VkAccelerationStructureInstanceKHR*>(tlasInstances_.map);
        for (uint32_t i = 0; i < staticInstances_; i++) {
            memcpy(inst[i].transform.matrix, &rows[(size_t)i * INSTANCE_FLOATS], 48);
            inst[i].instanceCustomIndex = instMesh[i];
            inst[i].mask = 0xFF;
            inst[i].instanceShaderBindingTableRecordOffset = 0;
            inst[i].flags = VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR | VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR;
            inst[i].accelerationStructureReference = 0;  // filled in buildBlas
        }
        staticMeshOfInstance_ = instMesh;
    }
}

void NeonRt::buildBlas() {
    struct Pending { VkAccelerationStructureGeometryKHR geo; VkAccelerationStructureBuildGeometryInfoKHR info; uint32_t prims; };
    std::vector<Pending> pending(meshes_.size());
    VkDeviceSize maxScratch = 0;
    blas_.resize(meshes_.size(), VK_NULL_HANDLE);
    blasBuf_.resize(meshes_.size());
    blasAddr_.resize(meshes_.size(), 0);
    for (size_t m = 0; m < meshes_.size(); m++) {
        auto& mesh = meshes_[m];
        auto& pd = pending[m];
        pd.prims = mesh.indexCount / 3;
        if (pd.prims == 0) continue;
        pd.geo = VkAccelerationStructureGeometryKHR{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};
        pd.geo.geometryType = VK_GEOMETRY_TYPE_TRIANGLES_KHR;
        pd.geo.flags = VK_GEOMETRY_OPAQUE_BIT_KHR;
        auto& tri = pd.geo.geometry.triangles;
        tri.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR;
        tri.vertexFormat = VK_FORMAT_R32G32B32_SFLOAT;
        tri.vertexData.deviceAddress = vertices_.addr + (VkDeviceAddress)mesh.firstVertex * VERTEX_BYTES;
        tri.vertexStride = VERTEX_BYTES;
        tri.maxVertex = mesh.vertexCount - 1;
        tri.indexType = VK_INDEX_TYPE_UINT32;
        tri.indexData.deviceAddress = indices_.addr + (VkDeviceAddress)mesh.firstIndex * 4;
        pd.info = VkAccelerationStructureBuildGeometryInfoKHR{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};
        pd.info.type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
        pd.info.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
        pd.info.mode = VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
        pd.info.geometryCount = 1;
        pd.info.pGeometries = &pd.geo;
        VkAccelerationStructureBuildSizesInfoKHR sizes{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR};
        vkGetAccelerationStructureBuildSizesKHR(device_, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, &pd.info, &pd.prims, &sizes);
        blasBuf_[m] = makeBuffer(sizes.accelerationStructureSize, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
        VkAccelerationStructureCreateInfoKHR ci{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR};
        ci.buffer = blasBuf_[m].buf;
        ci.size = sizes.accelerationStructureSize;
        ci.type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
        VK_CHECK(vkCreateAccelerationStructureKHR(device_, &ci, nullptr, &blas_[m]));
        pd.info.dstAccelerationStructure = blas_[m];
        maxScratch = std::max(maxScratch, sizes.buildScratchSize);
    }
    Buffer scratch = makeBuffer(maxScratch + scratchAlign_, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
    VkCommandBuffer cb = beginOnce();
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
    mb.dstAccessMask = VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
    for (size_t m = 0; m < meshes_.size(); m++) {
        auto& pd = pending[m];
        if (pd.prims == 0) continue;
        pd.info.pGeometries = &pd.geo;
        pd.info.scratchData.deviceAddress = alignUp(scratch.addr, scratchAlign_);
        VkAccelerationStructureBuildRangeInfoKHR range{pd.prims, 0, 0, 0};
        const VkAccelerationStructureBuildRangeInfoKHR* pr = &range;
        vkCmdBuildAccelerationStructuresKHR(cb, 1, &pd.info, &pr);
        // the scratch buffer is shared: each build waits for the previous one
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                             VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0, 1, &mb, 0, nullptr, 0, nullptr);
    }
    endOnce(cb);
    destroy(scratch);
    for (size_t m = 0; m < meshes_.size(); m++) {
        if (!blas_[m]) continue;
        VkAccelerationStructureDeviceAddressInfoKHR ai{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR};
        ai.accelerationStructure = blas_[m];
        blasAddr_[m] = vkGetAccelerationStructureDeviceAddressKHR(device_, &ai);
    }
    auto* inst = static_cast<VkAccelerationStructureInstanceKHR*>(tlasInstances_.map);
    for (uint32_t i = 0; i < staticInstances_; i++) inst[i].accelerationStructureReference = blasAddr_[staticMeshOfInstance_[i]];

    // TLAS sized for every static instance plus a full set of actors; rebuilt each frame
    VkAccelerationStructureGeometryKHR geo{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};
    geo.geometryType = VK_GEOMETRY_TYPE_INSTANCES_KHR;
    geo.geometry.instances.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR;
    geo.geometry.instances.data.deviceAddress = tlasInstances_.addr;
    VkAccelerationStructureBuildGeometryInfoKHR info{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};
    info.type = VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
    info.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
    info.mode = VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
    info.geometryCount = 1;
    info.pGeometries = &geo;
    uint32_t maxInstances = staticInstances_ + actorCapacity_;
    VkAccelerationStructureBuildSizesInfoKHR sizes{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR};
    vkGetAccelerationStructureBuildSizesKHR(device_, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, &info, &maxInstances, &sizes);
    tlasBuf_ = makeBuffer(sizes.accelerationStructureSize, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
    VkAccelerationStructureCreateInfoKHR ci{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR};
    ci.buffer = tlasBuf_.buf;
    ci.size = sizes.accelerationStructureSize;
    ci.type = VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
    VK_CHECK(vkCreateAccelerationStructureKHR(device_, &ci, nullptr, &tlas_));
    tlasScratch_ = makeBuffer(sizes.buildScratchSize + scratchAlign_,
                              VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
}

// ── render targets ───────────────────────────────────────────────────────

void NeonRt::createTargets() {
    for (VkFormat f : {VK_FORMAT_D32_SFLOAT, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D16_UNORM}) {
        VkFormatProperties fp;
        vkGetPhysicalDeviceFormatProperties(phys_, f, &fp);
        auto need = VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        if ((fp.optimalTilingFeatures & need) == need) { depthFormat_ = f; break; }
    }
    uint32_t w = width_, h = height_;
    color_ = makeImage(w, h, HDR, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    depth_ = makeImage(w, h, depthFormat_, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    auto post = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    fog_ = makeImage(w / 2, h / 2, HDR, post);
    combined_ = makeImage(w, h, HDR, post);
    for (int l = 0; l < BLOOM; l++) {
        uint32_t lw = std::max(1u, w >> (l + 1)), lh = std::max(1u, h >> (l + 1));
        down_[l] = makeImage(lw, lh, HDR, post);
        up_[l] = makeImage(lw, lh, HDR, post);
    }
    final_ = makeImage(w, h, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    readbackBuf_ = makeBuffer((VkDeviceSize)w * h * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);

    // post images live in GENERAL for their whole life
    VkCommandBuffer cb = beginOnce();
    std::vector<VkImageMemoryBarrier> bars;
    std::vector<Image*> general = {&fog_, &combined_, &final_};
    for (int l = 0; l < BLOOM; l++) { general.push_back(&down_[l]); general.push_back(&up_[l]); }
    for (Image* im : general) {
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = im->img;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        bars.push_back(b);
    }
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0,
                         nullptr, (uint32_t)bars.size(), bars.data());
    endOnce(cb);

    VkAttachmentDescription att[2]{};
    att[0].format = HDR;
    att[0].samples = VK_SAMPLE_COUNT_1_BIT;
    att[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att[0].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att[0].finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    att[1] = att[0];
    att[1].format = depthFormat_;
    att[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
    VkAttachmentReference colorRef{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkAttachmentReference depthRef{1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &colorRef;
    sub.pDepthStencilAttachment = &depthRef;
    VkSubpassDependency dep[2]{};
    dep[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    dep[0].dstSubpass = 0;
    dep[0].srcStageMask = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    dep[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
    dep[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    dep[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    dep[1].srcSubpass = 0;
    dep[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    dep[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    dep[1].dstStageMask = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    dep[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    dep[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    VkRenderPassCreateInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rp.attachmentCount = 2;
    rp.pAttachments = att;
    rp.subpassCount = 1;
    rp.pSubpasses = &sub;
    rp.dependencyCount = 2;
    rp.pDependencies = dep;
    VK_CHECK(vkCreateRenderPass(device_, &rp, nullptr, &renderPass_));
    VkImageView views[2] = {color_.view, depth_.view};
    VkFramebufferCreateInfo fb{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fb.renderPass = renderPass_;
    fb.attachmentCount = 2;
    fb.pAttachments = views;
    fb.width = w;
    fb.height = h;
    fb.layers = 1;
    VK_CHECK(vkCreateFramebuffer(device_, &fb, nullptr, &framebuffer_));

    VkSamplerCreateInfo sc{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sc.magFilter = sc.minFilter = VK_FILTER_LINEAR;
    sc.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sc.addressModeU = sc.addressModeV = sc.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sc.maxLod = 0.f;
    VK_CHECK(vkCreateSampler(device_, &sc, nullptr, &sampler_));
}

// ── descriptors ──────────────────────────────────────────────────────────

void NeonRt::createDescriptors() {
    std::vector<VkDescriptorSetLayoutBinding> b;
    auto add = [&](uint32_t binding, VkDescriptorType type) { b.push_back({binding, type, 1, VK_SHADER_STAGE_ALL, nullptr}); };
    add(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    for (uint32_t i = 1; i <= 7; i++) add(i, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    add(8, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
    if (rayTracing) add(9, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
    VkDescriptorSetLayoutCreateInfo lci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    lci.bindingCount = (uint32_t)b.size();
    lci.pBindings = b.data();
    VK_CHECK(vkCreateDescriptorSetLayout(device_, &lci, nullptr, &sceneLayout_));
    b.clear();
    b.push_back({0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr});
    b.push_back({1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr});
    b.push_back({2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr});
    lci.bindingCount = (uint32_t)b.size();
    lci.pBindings = b.data();
    VK_CHECK(vkCreateDescriptorSetLayout(device_, &lci, nullptr, &postLayout_));

    const uint32_t postSets = 3 + 2 * BLOOM;
    std::vector<VkDescriptorPoolSize> sizes = {
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1},
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 7},
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1 + 2 * postSets},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, postSets}};
    if (rayTracing) sizes.push_back({VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 1});
    VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pci.maxSets = 1 + postSets;
    pci.poolSizeCount = (uint32_t)sizes.size();
    pci.pPoolSizes = sizes.data();
    VK_CHECK(vkCreateDescriptorPool(device_, &pci, nullptr, &descPool_));

    auto alloc = [&](VkDescriptorSetLayout layout) {
        VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        ai.descriptorPool = descPool_;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts = &layout;
        VkDescriptorSet s;
        VK_CHECK(vkAllocateDescriptorSets(device_, &ai, &s));
        return s;
    };
    sceneSet_ = alloc(sceneLayout_);
    writeSceneSet();

    auto post = [&](VkDescriptorSet s, const Image& a, VkImageLayout la, const Image& bimg, VkImageLayout lb, const Image& dst) {
        VkDescriptorImageInfo ia{sampler_, a.view, la}, ib{sampler_, bimg.view, lb}, id{VK_NULL_HANDLE, dst.view, VK_IMAGE_LAYOUT_GENERAL};
        VkWriteDescriptorSet w[3]{};
        for (int i = 0; i < 3; i++) {
            w[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            w[i].dstSet = s;
            w[i].dstBinding = i;
            w[i].descriptorCount = 1;
        }
        w[0].descriptorType = w[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        w[0].pImageInfo = &ia;
        w[1].pImageInfo = &ib;
        w[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        w[2].pImageInfo = &id;
        vkUpdateDescriptorSets(device_, 3, w, 0, nullptr);
    };
    const auto RO = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, G = VK_IMAGE_LAYOUT_GENERAL;
    fogSet_ = alloc(postLayout_);
    post(fogSet_, color_, RO, color_, RO, fog_);
    combineSet_ = alloc(postLayout_);
    post(combineSet_, color_, RO, fog_, G, combined_);
    for (int l = 0; l < BLOOM; l++) {
        downSet_[l] = alloc(postLayout_);
        const Image& src = l == 0 ? combined_ : down_[l - 1];
        post(downSet_[l], src, G, src, G, down_[l]);
    }
    for (int l = 0; l < BLOOM - 1; l++) {
        upSet_[l] = alloc(postLayout_);
        const Image& lower = l == BLOOM - 2 ? down_[BLOOM - 1] : up_[l + 1];
        post(upSet_[l], lower, G, down_[l], G, up_[l]);
    }
    finalSet_ = alloc(postLayout_);
    post(finalSet_, combined_, G, up_[0], G, final_);
}

void NeonRt::writeSceneSet() {
    VkDescriptorBufferInfo bi[8] = {
        {frameUbo_.buf, 0, VK_WHOLE_SIZE}, {lights_.buf, 0, VK_WHOLE_SIZE}, {cells_.buf, 0, VK_WHOLE_SIZE},
        {lightIdx_.buf, 0, VK_WHOLE_SIZE}, {vertices_.buf, 0, VK_WHOLE_SIZE}, {indices_.buf, 0, VK_WHOLE_SIZE},
        {meshInfo_.buf, 0, VK_WHOLE_SIZE}, {extras_.buf, 0, VK_WHOLE_SIZE}};
    std::vector<VkWriteDescriptorSet> w;
    for (uint32_t i = 0; i < 8; i++) {
        VkWriteDescriptorSet x{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        x.dstSet = sceneSet_;
        x.dstBinding = i;
        x.descriptorCount = 1;
        x.descriptorType = i == 0 ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        x.pBufferInfo = &bi[i];
        w.push_back(x);
    }
    VkDescriptorImageInfo depthInfo{sampler_, depth_.view, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet d{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    d.dstSet = sceneSet_;
    d.dstBinding = 8;
    d.descriptorCount = 1;
    d.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    d.pImageInfo = &depthInfo;
    w.push_back(d);
    VkWriteDescriptorSetAccelerationStructureKHR asw{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR};
    if (rayTracing) {
        asw.accelerationStructureCount = 1;
        asw.pAccelerationStructures = &tlas_;
        VkWriteDescriptorSet x{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        x.pNext = &asw;
        x.dstSet = sceneSet_;
        x.dstBinding = 9;
        x.descriptorCount = 1;
        x.descriptorType = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
        w.push_back(x);
    }
    vkUpdateDescriptorSets(device_, (uint32_t)w.size(), w.data(), 0, nullptr);
}

// ── pipelines ────────────────────────────────────────────────────────────

void NeonRt::createPipelines() {
    VkPushConstantRange gpr{VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(ScenePush)};
    VkPipelineLayoutCreateInfo lci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    lci.setLayoutCount = 1;
    lci.pSetLayouts = &sceneLayout_;
    lci.pushConstantRangeCount = 1;
    lci.pPushConstantRanges = &gpr;
    VK_CHECK(vkCreatePipelineLayout(device_, &lci, nullptr, &gfxLayout_));
    VkDescriptorSetLayout both[2] = {sceneLayout_, postLayout_};
    VkPushConstantRange cpr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(PostPush)};
    lci.setLayoutCount = 2;
    lci.pSetLayouts = both;
    lci.pPushConstantRanges = &cpr;
    VK_CHECK(vkCreatePipelineLayout(device_, &lci, nullptr, &compLayout_));

    enum Blend { OPAQUE, PREMULT };
    auto graphics = [&](const uint32_t* vs, size_t vsb, const uint32_t* fs, size_t fsb, bool vertexInput,
                        bool depthWrite, VkCompareOp cmp, Blend blend) {
        VkShaderModule v = shader(vs, vsb), f = shader(fs, fsb);
        VkPipelineShaderStageCreateInfo st[2]{};
        st[0] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, v, "main", nullptr};
        st[1] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, f, "main", nullptr};
        VkVertexInputBindingDescription vb[2] = {{0, VERTEX_BYTES, VK_VERTEX_INPUT_RATE_VERTEX},
                                                 {1, INSTANCE_FLOATS * 4, VK_VERTEX_INPUT_RATE_INSTANCE}};
        VkVertexInputAttributeDescription va[8] = {
            {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0}, {1, 0, VK_FORMAT_R8G8B8A8_SNORM, 12},
            {2, 0, VK_FORMAT_R8G8B8A8_UNORM, 16}, {3, 1, VK_FORMAT_R32G32B32A32_SFLOAT, 0},
            {4, 1, VK_FORMAT_R32G32B32A32_SFLOAT, 16}, {5, 1, VK_FORMAT_R32G32B32A32_SFLOAT, 32},
            {6, 1, VK_FORMAT_R32G32B32A32_SFLOAT, 48}, {7, 1, VK_FORMAT_R32G32B32A32_SFLOAT, 64}};
        VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        if (vertexInput) {
            vi.vertexBindingDescriptionCount = 2;
            vi.pVertexBindingDescriptions = vb;
            vi.vertexAttributeDescriptionCount = 8;
            vi.pVertexAttributeDescriptions = va;
        }
        VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        vp.viewportCount = 1;
        vp.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        rs.polygonMode = VK_POLYGON_MODE_FILL;
        rs.cullMode = VK_CULL_MODE_NONE;
        rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rs.lineWidth = 1.f;
        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineDepthStencilStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
        ds.depthTestEnable = VK_TRUE;
        ds.depthWriteEnable = depthWrite;
        ds.depthCompareOp = cmp;
        VkPipelineColorBlendAttachmentState cb{};
        cb.colorWriteMask = 0xF;
        if (blend == PREMULT) {
            cb.blendEnable = VK_TRUE;
            cb.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
            cb.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            cb.colorBlendOp = VK_BLEND_OP_ADD;
            cb.srcAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
            cb.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            cb.alphaBlendOp = VK_BLEND_OP_ADD;
        }
        VkPipelineColorBlendStateCreateInfo cbs{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        cbs.attachmentCount = 1;
        cbs.pAttachments = &cb;
        VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dy{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        dy.dynamicStateCount = 2;
        dy.pDynamicStates = dyn;
        VkGraphicsPipelineCreateInfo ci{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        ci.stageCount = 2;
        ci.pStages = st;
        ci.pVertexInputState = &vi;
        ci.pInputAssemblyState = &ia;
        ci.pViewportState = &vp;
        ci.pRasterizationState = &rs;
        ci.pMultisampleState = &ms;
        ci.pDepthStencilState = &ds;
        ci.pColorBlendState = &cbs;
        ci.pDynamicState = &dy;
        ci.layout = gfxLayout_;
        ci.renderPass = renderPass_;
        VkPipeline p;
        VK_CHECK(vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &ci, nullptr, &p));
        vkDestroyShaderModule(device_, v, nullptr);
        vkDestroyShaderModule(device_, f, nullptr);
        return p;
    };
    auto compute = [&](const uint32_t* cs, size_t bytes) {
        VkShaderModule m = shader(cs, bytes);
        VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        ci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, m, "main", nullptr};
        ci.layout = compLayout_;
        VkPipeline p;
        VK_CHECK(vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &ci, nullptr, &p));
        vkDestroyShaderModule(device_, m, nullptr);
        return p;
    };
#define SPV(x) x, sizeof(x)
    scenePipe_ = rayTracing ? graphics(SPV(kSceneVert), SPV(kSceneFragRt), true, true, VK_COMPARE_OP_LESS, OPAQUE)
                            : graphics(SPV(kSceneVert), SPV(kSceneFrag), true, true, VK_COMPARE_OP_LESS, OPAQUE);
    skyPipe_ = graphics(SPV(kSkyVert), SPV(kSkyFrag), false, false, VK_COMPARE_OP_LESS_OR_EQUAL, OPAQUE);
    glowPipe_ = graphics(SPV(kGlowVert), SPV(kGlowFrag), false, false, VK_COMPARE_OP_LESS, PREMULT);
    rainPipe_ = graphics(SPV(kRainVert), SPV(kRainFrag), false, false, VK_COMPARE_OP_LESS, PREMULT);
    fogPipe_ = rayTracing ? compute(SPV(kFogCompRt)) : compute(SPV(kFogComp));
    combinePipe_ = compute(SPV(kCombineComp));
    downPipe_ = compute(SPV(kDownComp));
    upPipe_ = compute(SPV(kUpComp));
    finalPipe_ = compute(SPV(kFinalComp));
#undef SPV
}

// ── per frame ────────────────────────────────────────────────────────────

void NeonRt::ensureLightIndexCapacity(int count) {
    if (count <= lightIdxCapacity_) return;
    vkDeviceWaitIdle(device_);
    destroy(lightIdx_);
    lightIdxCapacity_ = count * 2;
    lightIdx_ = makeBuffer((VkDeviceSize)lightIdxCapacity_ * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
    writeSceneSet();
}

void NeonRt::writeFrameBuffers(const FrameInput& in, uint32_t& tlasInstances) {
    memcpy(frameUbo_.map, in.frame, FRAME_FLOATS * 4);
    lightCount_ = (uint32_t)std::min<int>(std::max(in.lightCount, 0), MAX_LIGHTS);
    memcpy(lights_.map, in.lights, (size_t)lightCount_ * 64);
    memcpy(cells_.map, in.cells, CELLS * 4);
    ensureLightIndexCapacity(in.indexCount);
    auto* li = static_cast<uint32_t*>(lightIdx_.map);
    for (int i = 0; i < in.indexCount; i++) li[i] = in.indices[i];

    actorDraws_.clear();
    tlasInstances = staticInstances_;
    auto* inst = rayTracing ? static_cast<VkAccelerationStructureInstanceKHR*>(tlasInstances_.map) : nullptr;
    auto* extras = static_cast<float*>(extras_.map);
    const float* src = in.actors;
    for (size_t b = 0; b < actorMesh_.size(); b++) {
        uint32_t cap = (b + 1 < actorOffset_.size() ? actorOffset_[b + 1] : actorCapacity_) - actorOffset_[b];
        uint32_t count = std::min<uint32_t>((uint32_t)std::max(in.actorCounts[b], 0), cap);
        float* dst = static_cast<float*>(actorInst_.map) + (size_t)actorOffset_[b] * INSTANCE_FLOATS;
        memcpy(dst, src, (size_t)count * INSTANCE_FLOATS * 4);
        uint32_t mesh = actorMesh_[b];
        if (count > 0) {
            int kind = meshes_[mesh].name.rfind("car_", 0) == 0 ? 1 : meshes_[mesh].name.rfind("ped_", 0) == 0 ? 2 : 3;
            actorDraws_.push_back({mesh, (uint32_t)actorOffset_[b], count, kind, 1, 0.f});
        }
        for (uint32_t i = 0; i < count; i++) {
            const float* r = src + (size_t)i * INSTANCE_FLOATS;
            uint32_t t = tlasInstances++;
            memcpy(extras + (size_t)t * 4, r + 12, 16);
            if (inst) {
                memcpy(inst[t].transform.matrix, r, 48);
                inst[t].instanceCustomIndex = mesh;
                inst[t].mask = 0xFF;
                inst[t].instanceShaderBindingTableRecordOffset = 0;
                inst[t].flags = VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR | VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR;
                inst[t].accelerationStructureReference = blasAddr_[mesh];
            }
        }
        src += (size_t)std::max(in.actorCounts[b], 0) * INSTANCE_FLOATS;
    }
}

void NeonRt::render(const FrameInput& in) {
    uint32_t tlasInstances = 0;
    writeFrameBuffers(in, tlasInstances);
    recordFrame(tlasInstances, in.readback);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd_;
    VK_CHECK(vkQueueSubmit(queue_, 1, &si, fence_));
    VK_CHECK(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX));
    VK_CHECK(vkResetFences(device_, 1, &fence_));
    frame_++;
}

void NeonRt::recordFrame(uint32_t tlasInstances, bool readback) {
    VK_CHECK(vkResetCommandBuffer(cmd_, 0));
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_CHECK(vkBeginCommandBuffer(cmd_, &bi));
    VkCommandBuffer cb = cmd_;

    if (rayTracing) {
        // the city moves (cars, people, trains): rebuild the top-level structure every frame
        VkAccelerationStructureGeometryKHR geo{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};
        geo.geometryType = VK_GEOMETRY_TYPE_INSTANCES_KHR;
        geo.geometry.instances.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR;
        geo.geometry.instances.data.deviceAddress = tlasInstances_.addr;
        VkAccelerationStructureBuildGeometryInfoKHR info{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};
        info.type = VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
        info.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
        info.mode = VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
        info.dstAccelerationStructure = tlas_;
        info.geometryCount = 1;
        info.pGeometries = &geo;
        info.scratchData.deviceAddress = alignUp(tlasScratch_.addr, scratchAlign_);
        VkAccelerationStructureBuildRangeInfoKHR range{tlasInstances, 0, 0, 0};
        const VkAccelerationStructureBuildRangeInfoKHR* pr = &range;
        vkCmdBuildAccelerationStructuresKHR(cb, 1, &info, &pr);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
        mb.dstAccessMask = VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0,
                             nullptr, 0, nullptr);
    }

    VkClearValue clear[2];
    clear[0].color = {{0.f, 0.f, 0.f, 1.f}};
    clear[1].depthStencil = {1.f, 0};
    VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rp.renderPass = renderPass_;
    rp.framebuffer = framebuffer_;
    rp.renderArea = {{0, 0}, {(uint32_t)width_, (uint32_t)height_}};
    rp.clearValueCount = 2;
    rp.pClearValues = clear;
    vkCmdBeginRenderPass(cb, &rp, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0.f, 0.f, (float)width_, (float)height_, 0.f, 1.f};
    VkRect2D sc{{0, 0}, {(uint32_t)width_, (uint32_t)height_}};
    vkCmdSetViewport(cb, 0, 1, &vp);
    vkCmdSetScissor(cb, 0, 1, &sc);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, gfxLayout_, 0, 1, &sceneSet_, 0, nullptr);

    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, scenePipe_);
    VkDeviceSize zero = 0;
    vkCmdBindVertexBuffers(cb, 0, 1, &vertices_.buf, &zero);
    vkCmdBindIndexBuffer(cb, indices_.buf, 0, VK_INDEX_TYPE_UINT32);
    auto draw = [&](const Draw& d) {
        ScenePush pc{d.actor, d.instanced, d.seed, 0};
        vkCmdPushConstants(cb, gfxLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof pc, &pc);
        const Mesh& m = meshes_[d.mesh];
        vkCmdDrawIndexed(cb, m.indexCount, d.count, m.firstIndex, (int32_t)m.firstVertex, d.firstInstance);
    };
    vkCmdBindVertexBuffers(cb, 1, 1, &staticInst_.buf, &zero);
    for (auto& d : draws_) draw(d);
    vkCmdBindVertexBuffers(cb, 1, 1, &actorInst_.buf, &zero);
    for (auto& d : actorDraws_) draw(d);

    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, skyPipe_);
    vkCmdDraw(cb, 3, 1, 0, 0);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, glowPipe_);
    vkCmdDraw(cb, 6, lightCount_, 0, 0);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, rainPipe_);
    vkCmdDraw(cb, 6, RAIN, 0, 0);
    vkCmdEndRenderPass(cb);

    // post: fog march → combine → bloom down/up → tonemap
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    auto barrier = [&]() {
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb,
                             0, nullptr, 0, nullptr);
    };
    auto dispatch = [&](VkPipeline p, VkDescriptorSet set, const Image& dst, const Image& src, float p0, float p1) {
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p);
        VkDescriptorSet sets[2] = {sceneSet_, set};
        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, compLayout_, 0, 2, sets, 0, nullptr);
        PostPush pc{1.f / src.w, 1.f / src.h, p0, p1};
        vkCmdPushConstants(cb, compLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof pc, &pc);
        vkCmdDispatch(cb, (dst.w + 7) / 8, (dst.h + 7) / 8, 1);
    };
    dispatch(fogPipe_, fogSet_, fog_, fog_, (float)(frame_ % 64), 0.f);
    barrier();
    dispatch(combinePipe_, combineSet_, combined_, combined_, 0.f, 0.f);
    barrier();
    for (int l = 0; l < BLOOM; l++) {
        dispatch(downPipe_, downSet_[l], down_[l], l == 0 ? combined_ : down_[l - 1], l == 0 ? 1.f : 0.f, 0.f);
        barrier();
    }
    for (int l = BLOOM - 2; l >= 0; l--) {
        dispatch(upPipe_, upSet_[l], up_[l], l == BLOOM - 2 ? down_[BLOOM - 1] : up_[l + 1], 0.f, 0.f);
        barrier();
    }
    dispatch(finalPipe_, finalSet_, final_, final_, (float)(frame_ % 997), 0.f);

    if (readback) {
        VkMemoryBarrier tb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        tb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        tb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &tb, 0,
                             nullptr, 0, nullptr);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageExtent = {final_.w, final_.h, 1};
        vkCmdCopyImageToBuffer(cb, final_.img, VK_IMAGE_LAYOUT_GENERAL, readbackBuf_.buf, 1, &region);
        VkMemoryBarrier hb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        hb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        hb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &hb, 0, nullptr, 0, nullptr);
    }
    VK_CHECK(vkEndCommandBuffer(cb));
}

// ── presentation ─────────────────────────────────────────────────────────

void NeonRt::destroySwapchain() {
    if (device_) vkDeviceWaitIdle(device_);
    for (auto s : acquireSems_) vkDestroySemaphore(device_, s, nullptr);
    for (auto s : renderSems_) vkDestroySemaphore(device_, s, nullptr);
    acquireSems_.clear();
    renderSems_.clear();
    swapImages_.clear();
    if (swapchain_) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    if (surface_) vkDestroySurfaceKHR(instance_, surface_, nullptr);
    swapchain_ = VK_NULL_HANDLE;
    surface_ = VK_NULL_HANDLE;
    if (window_) ANativeWindow_release(window_);
    window_ = nullptr;
}

void NeonRt::setWindow(ANativeWindow* window) {
    destroySwapchain();
    window_ = window;
    if (!window_ || !vkCreateAndroidSurfaceKHR || !vkCreateSwapchainKHR) return;
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = window_;
    if (vkCreateAndroidSurfaceKHR(instance_, &sci, nullptr, &surface_) != VK_SUCCESS) { surface_ = VK_NULL_HANDLE; return; }
    VkBool32 supported = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(phys_, queueFamily_, surface_, &supported);
    VkSurfaceCapabilitiesKHR caps;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(phys_, surface_, &caps);
    if (!supported || !(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) return;
    uint32_t n = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(phys_, surface_, &n, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(n);
    vkGetPhysicalDeviceSurfaceFormatsKHR(phys_, surface_, &n, formats.data());
    if (formats.empty()) return;
    VkSurfaceFormatKHR fmt = formats[0];
    for (auto& f : formats)
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) { fmt = f; break; }
    vkGetPhysicalDeviceSurfacePresentModesKHR(phys_, surface_, &n, nullptr);
    std::vector<VkPresentModeKHR> modes(n);
    vkGetPhysicalDeviceSurfacePresentModesKHR(phys_, surface_, &n, modes.data());
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
    for (auto m : {VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR})
        if (std::find(modes.begin(), modes.end(), m) != modes.end()) { mode = m; break; }
    swapExtent_ = caps.currentExtent.width == UINT32_MAX ? VkExtent2D{(uint32_t)width_, (uint32_t)height_} : caps.currentExtent;
    uint32_t count = std::max(caps.minImageCount, 3u);
    if (caps.maxImageCount) count = std::min(count, caps.maxImageCount);
    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    for (auto a : {VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR, VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR})
        if (caps.supportedCompositeAlpha & a) { alpha = a; break; }
    VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    ci.surface = surface_;
    ci.minImageCount = count;
    ci.imageFormat = fmt.format;
    ci.imageColorSpace = fmt.colorSpace;
    ci.imageExtent = swapExtent_;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
        ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    ci.compositeAlpha = alpha;
    ci.presentMode = mode;
    ci.clipped = VK_TRUE;
    if (vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_) != VK_SUCCESS) { swapchain_ = VK_NULL_HANDLE; return; }
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, nullptr);
    swapImages_.resize(n);
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, swapImages_.data());
    VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    acquireSems_.resize(n + 1);
    renderSems_.resize(n);
    for (auto& s : acquireSems_) VK_CHECK(vkCreateSemaphore(device_, &sem, nullptr, &s));
    for (auto& s : renderSems_) VK_CHECK(vkCreateSemaphore(device_, &sem, nullptr, &s));
}

void NeonRt::present() {
    if (!swapchain_) return;
    VkSemaphore acquired = acquireSems_[acquireIdx_++ % acquireSems_.size()];
    uint32_t idx = 0;
    VkResult r = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, acquired, VK_NULL_HANDLE, &idx);
    if (r == VK_ERROR_OUT_OF_DATE_KHR) {
        ANativeWindow* w = window_;
        if (w) ANativeWindow_acquire(w);
        setWindow(w);
        return;
    }
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return;

    VK_CHECK(vkResetCommandBuffer(presentCmd_, 0));
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_CHECK(vkBeginCommandBuffer(presentCmd_, &bi));
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = swapImages_[idx];
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(presentCmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr,
                         0, nullptr, 1, &b);
    VkImageBlit blit{};
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.srcOffsets[1] = {(int32_t)final_.w, (int32_t)final_.h, 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.dstOffsets[1] = {(int32_t)swapExtent_.width, (int32_t)swapExtent_.height, 1};
    vkCmdBlitImage(presentCmd_, final_.img, VK_IMAGE_LAYOUT_GENERAL, swapImages_[idx],
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = 0;
    vkCmdPipelineBarrier(presentCmd_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                         nullptr, 0, nullptr, 1, &b);
    VK_CHECK(vkEndCommandBuffer(presentCmd_));
    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &acquired;
    si.pWaitDstStageMask = &waitStage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &presentCmd_;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &renderSems_[idx];
    VK_CHECK(vkQueueSubmit(queue_, 1, &si, presentFence_));
    // synchronous: the next frame may overwrite the image this blit reads
    VK_CHECK(vkWaitForFences(device_, 1, &presentFence_, VK_TRUE, UINT64_MAX));
    VK_CHECK(vkResetFences(device_, 1, &presentFence_));
    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &renderSems_[idx];
    pi.swapchainCount = 1;
    pi.pSwapchains = &swapchain_;
    pi.pImageIndices = &idx;
    vkQueuePresentKHR(queue_, &pi);
}

// ── teardown ─────────────────────────────────────────────────────────────

NeonRt::~NeonRt() {
    if (!device_) {
        if (instance_) vkDestroyInstance(instance_, nullptr);
        return;
    }
    vkDeviceWaitIdle(device_);
    destroySwapchain();
    for (VkPipeline p : {scenePipe_, skyPipe_, glowPipe_, rainPipe_, fogPipe_, combinePipe_, downPipe_, upPipe_, finalPipe_})
        if (p) vkDestroyPipeline(device_, p, nullptr);
    if (gfxLayout_) vkDestroyPipelineLayout(device_, gfxLayout_, nullptr);
    if (compLayout_) vkDestroyPipelineLayout(device_, compLayout_, nullptr);
    if (descPool_) vkDestroyDescriptorPool(device_, descPool_, nullptr);
    if (sceneLayout_) vkDestroyDescriptorSetLayout(device_, sceneLayout_, nullptr);
    if (postLayout_) vkDestroyDescriptorSetLayout(device_, postLayout_, nullptr);
    if (framebuffer_) vkDestroyFramebuffer(device_, framebuffer_, nullptr);
    if (renderPass_) vkDestroyRenderPass(device_, renderPass_, nullptr);
    if (sampler_) vkDestroySampler(device_, sampler_, nullptr);
    for (Image* im : {&color_, &depth_, &fog_, &combined_, &final_}) destroy(*im);
    for (int l = 0; l < BLOOM; l++) { destroy(down_[l]); destroy(up_[l]); }
    if (tlas_) vkDestroyAccelerationStructureKHR(device_, tlas_, nullptr);
    for (auto a : blas_) if (a) vkDestroyAccelerationStructureKHR(device_, a, nullptr);
    for (auto& b : blasBuf_) destroy(b);
    for (Buffer* b : {&vertices_, &indices_, &meshInfo_, &staticInst_, &actorInst_, &frameUbo_, &lights_, &cells_,
                      &lightIdx_, &extras_, &tlasBuf_, &tlasScratch_, &tlasInstances_, &readbackBuf_})
        destroy(*b);
    if (fence_) vkDestroyFence(device_, fence_, nullptr);
    if (presentFence_) vkDestroyFence(device_, presentFence_, nullptr);
    if (pool_) vkDestroyCommandPool(device_, pool_, nullptr);
    vkDestroyDevice(device_, nullptr);
    if (instance_) vkDestroyInstance(instance_, nullptr);
}

}  // namespace nrt
