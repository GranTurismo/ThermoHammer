// Neon City RT — GPU Test 3. The Neon City scene rendered with Vulkan: a forward HDR pass whose
// fragment shader casts ray queries (reflections on wet streets, glass and paint; shadows from the
// strongest lights and the police searchlight), a compute fog march with ray-traced searchlight
// shafts, and compute bloom/tonemap. Animation and the light grid come from Kotlin every frame.
#pragma once

#include <android/native_window.h>

#include <cstdint>
#include <string>
#include <vector>

#include "vk_loader.h"

namespace nrt {

/** Why ray queries can't run here, or "" when they can. */
std::string probeRayQuery();

/** Per-frame data from Kotlin (NeonRtScene). `frame` is FRAME_FLOATS floats laid out as the Frame UBO. */
struct FrameInput {
    const float* frame;
    const float* lights;          // 16 floats per light
    int lightCount;
    const int32_t* cells;         // CELLS entries: offset << 8 | count
    const uint16_t* indices;      // light lists
    int indexCount;
    const float* actors;          // 20 floats per instance, batches back to back
    const int32_t* actorCounts;   // instances per actor batch
    bool readback;
};

constexpr int FRAME_FLOATS = 88;
constexpr int CELLS = 128 * 336;
constexpr int INSTANCE_FLOATS = 20;

struct Buffer {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
    void* map = nullptr;
    VkDeviceAddress addr = 0;
};

struct Image {
    VkImage img = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t w = 0, h = 0;
};

class NeonRt {
public:
    /** `asset` = assets/neon_city.bin; `actorMeshes`/`actorCapacity` = Kotlin's actor batches in order. */
    NeonRt(const uint8_t* asset, size_t size, const std::vector<std::string>& actorMeshes,
           const std::vector<int>& actorCapacity, int width, int height, bool rayTracing);
    ~NeonRt();

    /** Renders one frame and waits for the GPU to finish it. */
    void render(const FrameInput& in);
    /** Shows the last frame on the attached window (if any). */
    void present();
    void setWindow(ANativeWindow* window);
    /** RGBA8 pixels of the last frame rendered with `readback`, top row first. */
    const uint8_t* pixels() const { return static_cast<const uint8_t*>(readbackBuf_.map); }

    std::string deviceName;
    bool rayTracing;
    long long triangles = 0;

private:
    struct Mesh { uint32_t firstVertex, firstIndex, indexCount, vertexCount; bool chunk; std::string name; };
    struct Draw { uint32_t mesh, firstInstance, count; int actor; int instanced; float seed; };

    void createDevice();
    void loadScene(const uint8_t* asset, size_t size, const std::vector<std::string>& actorMeshes,
                   const std::vector<int>& actorCapacity);
    void buildBlas();
    void createTargets();
    void createPipelines();
    void createDescriptors();
    void writeSceneSet();
    void writeFrameBuffers(const FrameInput& in, uint32_t& tlasInstances);
    void recordFrame(uint32_t tlasInstances, bool readback);
    void destroySwapchain();

    Buffer makeBuffer(VkDeviceSize size, VkBufferUsageFlags usage, bool hostVisible);
    Buffer upload(const void* data, VkDeviceSize size, VkBufferUsageFlags usage);
    void destroy(Buffer& b);
    Image makeImage(uint32_t w, uint32_t h, VkFormat format, VkImageUsageFlags usage);
    void destroy(Image& i);
    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags props);
    VkCommandBuffer beginOnce();
    void endOnce(VkCommandBuffer cb);
    VkShaderModule shader(const uint32_t* code, size_t bytes);
    void ensureLightIndexCapacity(int count);

    int width_, height_;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    VkPhysicalDeviceMemoryProperties memProps_{};
    VkCommandPool pool_ = VK_NULL_HANDLE;
    VkCommandBuffer cmd_ = VK_NULL_HANDLE;
    VkCommandBuffer presentCmd_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    VkFence presentFence_ = VK_NULL_HANDLE;
    uint32_t frame_ = 0;

    // scene
    std::vector<Mesh> meshes_;
    std::vector<Draw> draws_;
    std::vector<Draw> actorDraws_;      // this frame's actor batches
    std::vector<int> actorMesh_;        // mesh per actor batch
    std::vector<int> actorOffset_;      // first instance of each batch in actorInst_
    uint32_t staticInstances_ = 0;      // TLAS instances that never move
    std::vector<uint32_t> staticMeshOfInstance_;
    uint32_t lightCount_ = 0;
    uint32_t actorCapacity_ = 0;
    Buffer vertices_, indices_, meshInfo_, staticInst_, actorInst_;
    Buffer frameUbo_, lights_, cells_, lightIdx_, extras_;
    int lightIdxCapacity_ = 0;

    // ray tracing
    std::vector<VkAccelerationStructureKHR> blas_;
    std::vector<Buffer> blasBuf_;
    std::vector<VkDeviceAddress> blasAddr_;
    VkAccelerationStructureKHR tlas_ = VK_NULL_HANDLE;
    Buffer tlasBuf_, tlasScratch_, tlasInstances_;
    VkDeviceSize scratchAlign_ = 256;

    // targets
    VkFormat depthFormat_ = VK_FORMAT_D32_SFLOAT;
    Image color_, depth_, fog_, combined_, final_;
    static constexpr int BLOOM = 5;
    Image down_[BLOOM], up_[BLOOM];
    Buffer readbackBuf_;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkFramebuffer framebuffer_ = VK_NULL_HANDLE;
    VkSampler sampler_ = VK_NULL_HANDLE;

    // pipelines
    VkDescriptorSetLayout sceneLayout_ = VK_NULL_HANDLE, postLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout gfxLayout_ = VK_NULL_HANDLE, compLayout_ = VK_NULL_HANDLE;
    VkPipeline scenePipe_ = VK_NULL_HANDLE, skyPipe_ = VK_NULL_HANDLE, glowPipe_ = VK_NULL_HANDLE,
               rainPipe_ = VK_NULL_HANDLE, fogPipe_ = VK_NULL_HANDLE, combinePipe_ = VK_NULL_HANDLE,
               downPipe_ = VK_NULL_HANDLE, upPipe_ = VK_NULL_HANDLE, finalPipe_ = VK_NULL_HANDLE;
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;
    VkDescriptorSet sceneSet_ = VK_NULL_HANDLE;
    VkDescriptorSet fogSet_ = VK_NULL_HANDLE, combineSet_ = VK_NULL_HANDLE, finalSet_ = VK_NULL_HANDLE;
    VkDescriptorSet downSet_[BLOOM]{}, upSet_[BLOOM]{};

    // presentation
    ANativeWindow* window_ = nullptr;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    std::vector<VkImage> swapImages_;
    std::vector<VkSemaphore> acquireSems_, renderSems_;
    uint32_t acquireIdx_ = 0;
    VkExtent2D swapExtent_{};
};

}  // namespace nrt
