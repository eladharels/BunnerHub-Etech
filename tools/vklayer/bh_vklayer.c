/*
 * bh_vklayer — BunnerHub Etech's Vulkan layer, loaded into a game's Wine
 * process by the Vulkan loader (an implicit layer, enabled only when the
 * game's environment has BH_VKLAYER=1; see extension/BhVkLayer.java).
 *
 * It reports the game's swapchain and frame rate in a status file
 * (BH_VKLAYER_STATUS) and the Android log (tag "BhVkLayer").
 *
 * Upscaling (step 2): with BH_VKLAYER_OUT=WxH (the display size) the game
 * gets swapchain images of the size it asked for (the Wine screen size), the
 * real swapchain is created at WxH (up to 4x per axis), and at present each
 * frame goes through AMD FidelityFX FSR 1 (EASU upscale, then RCAS
 * sharpening, BH_VKLAYER_SHARP stops, 0 = sharpest) into it. GameHub's
 * GameScopeVK driver hands the images to GameHub's DirectRendering surface,
 * which is scaled by display / Wine screen, so a larger frame would be
 * cropped; the app scales that surface back by in / out while the layer
 * reports upscaling (BhVkLayer), so the frame fills the screen at full
 * resolution. If the driver refuses the larger swapchain, keeps calling it
 * out of date, or the game keeps recreating it, the layer falls back to
 * pass-through and says why in the status file.
 *
 * Built by CI with the Android NDK (bionic, arm64-v8a); also builds on desktop
 * Linux for testing with a software Vulkan driver. Shaders: the .comp files in shaders/,
 * compiled to SPIR-V headers with glslangValidator (see build.yml).
 */
#include <vulkan/vulkan.h>

#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

/* AMD FidelityFX FSR 1 (MIT, tools/vklayer/ffx): constant setup on the CPU. */
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
#pragma GCC diagnostic ignored "-Wignored-qualifiers"
#pragma GCC diagnostic ignored "-Wsign-compare"
#define A_CPU 1
#include "ffx/ffx_a.h"
#include "ffx/ffx_fsr1.h"
#pragma GCC diagnostic pop

#include "shaders/easu.spv.h"
#include "shaders/rcas.spv.h"

#define LAYER_NAME "VK_LAYER_BH_upscale"
#define EXPORT __attribute__((visibility("default")))

/* ── The loader ↔ layer interface (Vulkan-Loader's vk_layer.h, Apache-2.0) ── */

typedef enum { BH_LAYER_LINK_INFO = 0, BH_LOADER_DATA_CALLBACK = 1 } bh_layer_function;

typedef struct bh_instance_link {
    struct bh_instance_link *pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    void *pfnNextGetPhysicalDeviceProcAddr;
} bh_instance_link;

typedef struct {
    VkStructureType sType; /* VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO */
    const void *pNext;
    int function;          /* bh_layer_function */
    union {
        bh_instance_link *pLayerInfo;
        void *other[2];
    } u;
} bh_instance_create_info;

typedef struct bh_device_link {
    struct bh_device_link *pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr pfnNextGetDeviceProcAddr;
} bh_device_link;

typedef VkResult(VKAPI_PTR *bh_set_device_loader_data)(VkDevice device, void *object);

typedef struct {
    VkStructureType sType; /* VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO */
    const void *pNext;
    int function;
    union {
        bh_device_link *pLayerInfo;
        bh_set_device_loader_data pfnSetDeviceLoaderData;
        void *other[2];
    } u;
} bh_device_create_info;

typedef struct {
    int sType;             /* LAYER_NEGOTIATE_INTERFACE_STRUCT = 1 */
    void *pNext;
    uint32_t loaderLayerInterfaceVersion;
    PFN_vkGetInstanceProcAddr pfnGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr pfnGetDeviceProcAddr;
    void *pfnGetPhysicalDeviceProcAddr;
} bh_negotiate;

#define LOADER_INSTANCE_CREATE_INFO ((VkStructureType)47)
#define LOADER_DEVICE_CREATE_INFO ((VkStructureType)48)

/* ── Logging and status ─────────────────────────────────────────────────── */

static void logf_(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "BhVkLayer", "%s", buf);
#endif
    fprintf(stderr, "BhVkLayer: %s\n", buf);
}

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* What the status file reports. */
static struct {
    char app[128];
    char engine[64];
    uint32_t width, height, images;
    int format, present_mode;
    uint64_t frames, frames_at_mark;
    double mark;
    double fps;
    int upscale;               /* 1 while frames are upscaled */
    uint32_t out_w, out_h;     /* real swapchain size when upscaling */
    float sharp;
    char note[160];            /* why upscaling is off, if it was asked for */
} g_stat;

static double now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static void write_status(void) {
    const char *path = getenv("BH_VKLAYER_STATUS");
    if (!path || !*path) return;
    char tmp[1024];
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    fprintf(f, "layer=" LAYER_NAME "\nstep=2\napp=%s\nengine=%s\nswapchain=%ux%u\nformat=%d\n"
               "present_mode=%d\nimages=%u\nframes=%llu\nfps=%.1f\nupscale=%s\noutput=%ux%u\nsharp=%.2f\n"
               "note=%s\ntime=%lld\n",
            g_stat.app, g_stat.engine, g_stat.width, g_stat.height, g_stat.format, g_stat.present_mode,
            g_stat.images, (unsigned long long)g_stat.frames, g_stat.fps, g_stat.upscale ? "on" : "off",
            g_stat.out_w, g_stat.out_h, g_stat.sharp, g_stat.note, (long long)time(NULL));
    fclose(f);
    rename(tmp, path);
}

/* ── Dispatch: next-layer entry points, keyed by the dispatchable handle ── */

#define MAX_ENTRIES 64

typedef struct {
    void *key; /* the loader's dispatch table pointer (first word of the handle) */
    VkInstance instance;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance destroy_instance;
    PFN_vkGetPhysicalDeviceMemoryProperties mem_props;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR surface_caps;
    PFN_vkGetPhysicalDeviceFormatProperties format_props;
} instance_entry;

/* Device functions the layer calls (next in the chain). */
#define DEVICE_FUNCS(X)                                                                                           \
    X(DestroyDevice) X(CreateSwapchainKHR) X(DestroySwapchainKHR) X(GetSwapchainImagesKHR) X(QueuePresentKHR)   \
    X(GetDeviceQueue) X(GetDeviceQueue2) X(DeviceWaitIdle) X(QueueSubmit)                                       \
    X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) X(AllocateMemory) X(FreeMemory)                \
    X(BindImageMemory) X(CreateImageView) X(DestroyImageView) X(CreateSampler) X(DestroySampler)                \
    X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout) X(CreatePipelineLayout) X(DestroyPipelineLayout) \
    X(CreateShaderModule) X(DestroyShaderModule) X(CreateComputePipelines) X(DestroyPipeline)                   \
    X(CreateDescriptorPool) X(DestroyDescriptorPool) X(AllocateDescriptorSets) X(UpdateDescriptorSets)          \
    X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(BeginCommandBuffer)                  \
    X(EndCommandBuffer) X(CmdPipelineBarrier) X(CmdBindPipeline) X(CmdBindDescriptorSets) X(CmdPushConstants)   \
    X(CmdDispatch) X(CmdCopyImage) X(CreateSemaphore) X(DestroySemaphore) X(AcquireNextImageKHR)

typedef struct {
#define X(n) PFN_vk##n n;
    DEVICE_FUNCS(X)
#undef X
} device_funcs;

#define MAX_QUEUES 16

typedef struct {
    void *key;
    VkDevice device;
    VkPhysicalDevice phys;
    PFN_vkGetDeviceProcAddr gdpa;
    bh_set_device_loader_data set_loader_data;
    instance_entry inst; /* a copy: the instance functions this device needs */
    device_funcs f;
    struct { VkQueue queue; uint32_t family; } queues[MAX_QUEUES];
} device_entry;

static instance_entry g_instances[MAX_ENTRIES];
static device_entry g_devices[MAX_ENTRIES];

static void *key_of(const void *handle) { return handle ? *(void **)handle : NULL; }

static instance_entry *find_instance(const void *handle) {
    void *k = key_of(handle);
    for (int i = 0; i < MAX_ENTRIES; i++) if (g_instances[i].key == k && k) return &g_instances[i];
    return NULL;
}

static device_entry *find_device(const void *handle) {
    void *k = key_of(handle);
    for (int i = 0; i < MAX_ENTRIES; i++) if (g_devices[i].key == k && k) return &g_devices[i];
    return NULL;
}

/* ── Upscaling swapchains ───────────────────────────────────────────────── */

#define MAX_SWAPCHAINS 16
#define MAX_IMAGES 16

typedef struct {
    VkSwapchainKHR handle; /* the real swapchain; the game sees the same handle */
    device_entry *d;
    VkExtent2D in, out;
    VkFormat app_format;
    uint32_t count;
    VkImage real[MAX_IMAGES];
    VkImage app[MAX_IMAGES];
    VkDeviceMemory app_mem[MAX_IMAGES];
    VkImageView app_view[MAX_IMAGES];
    VkImage a, b;            /* EASU output, RCAS output (output size) */
    VkDeviceMemory a_mem, b_mem;
    VkImageView a_view, b_view;
    VkSampler sampler;
    VkDescriptorSetLayout dsl;
    VkPipelineLayout layout;
    VkPipeline easu, rcas;
    VkDescriptorPool dpool;
    VkDescriptorSet set_easu[MAX_IMAGES], set_rcas;
    VkCommandPool pool;
    uint32_t pool_family;
    VkCommandBuffer cmd[MAX_IMAGES];
    VkSemaphore done[MAX_IMAGES];
    uint32_t con_easu[16], con_rcas[4];
    uint64_t presents;
} upscaler;

static upscaler *g_swapchains[MAX_SWAPCHAINS];

/* If the driver keeps calling the larger swapchains out of date, the game would recreate its
   swapchain over and over: after 3 that fail within their first frames, stop upscaling. */
static int g_rejected, g_upscale_disabled;
static double g_created[8]; /* when the last upscaled swapchains were made (ring) */
static int g_created_next;

/* 8 upscaled swapchains within 10 s means something (Wine, the driver) makes the game
   recreate it over and over: stop upscaling. Called with g_lock held. */
static void count_creation(void) {
    double t = now_s();
    double oldest = g_created[g_created_next];
    g_created[g_created_next] = t;
    g_created_next = (g_created_next + 1) % 8;
    if (oldest > 0 && t - oldest < 10.0 && !g_upscale_disabled) {
        g_upscale_disabled = 1;
        snprintf(g_stat.note, sizeof g_stat.note, "upscale off: the game keeps having to recreate its swapchain");
        logf_("%s", g_stat.note);
    }
}

static void out_of_date(upscaler *u) {
    if (!u || u->presents >= 10 || g_upscale_disabled) return;
    u->presents = 10; /* count each swapchain once */
    if (++g_rejected >= 3) {
        g_upscale_disabled = 1;
        snprintf(g_stat.note, sizeof g_stat.note,
                 "upscale off: the game keeps having to recreate its swapchain");
        logf_("%s", g_stat.note);
        write_status();
    }
}

static upscaler *find_upscaler(VkSwapchainKHR h) {
    for (int i = 0; i < MAX_SWAPCHAINS; i++)
        if (g_swapchains[i] && g_swapchains[i]->handle == h) return g_swapchains[i];
    return NULL;
}

static uint32_t queue_family(device_entry *d, VkQueue q) {
    for (int i = 0; i < MAX_QUEUES; i++) if (d->queues[i].queue == q) return d->queues[i].family;
    return 0;
}

static void remember_queue(device_entry *d, VkQueue q, uint32_t family) {
    for (int i = 0; i < MAX_QUEUES; i++) {
        if (d->queues[i].queue == q || !d->queues[i].queue) {
            d->queues[i].queue = q;
            d->queues[i].family = family;
            return;
        }
    }
}

/* The 8-bit formats the layer handles, and the UNORM view used to read/write them. */
static VkFormat unorm_of(VkFormat f) {
    switch (f) {
    case VK_FORMAT_B8G8R8A8_UNORM:
    case VK_FORMAT_B8G8R8A8_SRGB: return VK_FORMAT_B8G8R8A8_UNORM;
    case VK_FORMAT_R8G8B8A8_UNORM:
    case VK_FORMAT_R8G8B8A8_SRGB: return VK_FORMAT_R8G8B8A8_UNORM;
    default: return VK_FORMAT_UNDEFINED;
    }
}


static int memory_type(device_entry *d, uint32_t bits, VkMemoryPropertyFlags want) {
    VkPhysicalDeviceMemoryProperties mp;
    d->inst.mem_props(d->phys, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return (int)i;
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) if (bits & (1u << i)) return (int)i;
    return -1;
}

static VkResult make_image(device_entry *d, VkFormat format, VkExtent2D ext, VkImageUsageFlags usage,
                           VkImageCreateFlags flags, const VkSwapchainCreateInfoKHR *sci, VkImage *img,
                           VkDeviceMemory *mem) {
    VkImageCreateInfo ici = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.flags = flags;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = format;
    ici.extent.width = ext.width;
    ici.extent.height = ext.height;
    ici.extent.depth = 1;
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = usage;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (sci && sci->imageSharingMode == VK_SHARING_MODE_CONCURRENT) {
        ici.sharingMode = VK_SHARING_MODE_CONCURRENT;
        ici.queueFamilyIndexCount = sci->queueFamilyIndexCount;
        ici.pQueueFamilyIndices = sci->pQueueFamilyIndices;
    }
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkResult r = d->f.CreateImage(d->device, &ici, NULL, img);
    if (r != VK_SUCCESS) return r;
    VkMemoryRequirements req;
    d->f.GetImageMemoryRequirements(d->device, *img, &req);
    int type = memory_type(d, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type < 0) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = (uint32_t)type;
    r = d->f.AllocateMemory(d->device, &mai, NULL, mem);
    if (r != VK_SUCCESS) return r;
    return d->f.BindImageMemory(d->device, *img, *mem, 0);
}

static VkResult make_view(device_entry *d, VkImage img, VkFormat format, VkImageUsageFlags usage, VkImageView *view) {
    (void)usage;
    VkImageViewCreateInfo vci = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = img;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = format;
    vci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vci.subresourceRange.levelCount = 1;
    vci.subresourceRange.layerCount = 1;
    return d->f.CreateImageView(d->device, &vci, NULL, view);
}

static void destroy_upscaler(upscaler *u) {
    device_entry *d = u->d;
    VkDevice dev = d->device;
    d->f.DeviceWaitIdle(dev);
    if (u->pool) d->f.DestroyCommandPool(dev, u->pool, NULL);
    for (uint32_t i = 0; i < MAX_IMAGES; i++) {
        if (u->done[i]) d->f.DestroySemaphore(dev, u->done[i], NULL);
        if (u->app_view[i]) d->f.DestroyImageView(dev, u->app_view[i], NULL);
        if (u->app[i]) d->f.DestroyImage(dev, u->app[i], NULL);
        if (u->app_mem[i]) d->f.FreeMemory(dev, u->app_mem[i], NULL);
    }
    if (u->dpool) d->f.DestroyDescriptorPool(dev, u->dpool, NULL);
    if (u->easu) d->f.DestroyPipeline(dev, u->easu, NULL);
    if (u->rcas) d->f.DestroyPipeline(dev, u->rcas, NULL);
    if (u->layout) d->f.DestroyPipelineLayout(dev, u->layout, NULL);
    if (u->dsl) d->f.DestroyDescriptorSetLayout(dev, u->dsl, NULL);
    if (u->sampler) d->f.DestroySampler(dev, u->sampler, NULL);
    if (u->a_view) d->f.DestroyImageView(dev, u->a_view, NULL);
    if (u->b_view) d->f.DestroyImageView(dev, u->b_view, NULL);
    if (u->a) d->f.DestroyImage(dev, u->a, NULL);
    if (u->b) d->f.DestroyImage(dev, u->b, NULL);
    if (u->a_mem) d->f.FreeMemory(dev, u->a_mem, NULL);
    if (u->b_mem) d->f.FreeMemory(dev, u->b_mem, NULL);
    free(u);
}

static VkResult make_pipeline(device_entry *d, upscaler *u, const uint32_t *code, size_t size, VkPipeline *out) {
    VkShaderModuleCreateInfo smci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smci.codeSize = size;
    smci.pCode = code;
    VkShaderModule mod;
    VkResult r = d->f.CreateShaderModule(d->device, &smci, NULL, &mod);
    if (r != VK_SUCCESS) return r;
    VkComputePipelineCreateInfo pci = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pci.stage.module = mod;
    pci.stage.pName = "main";
    pci.layout = u->layout;
    r = d->f.CreateComputePipelines(d->device, VK_NULL_HANDLE, 1, &pci, NULL, out);
    d->f.DestroyShaderModule(d->device, mod, NULL);
    return r;
}

#define TRY(x)                                                     \
    do {                                                           \
        VkResult r_ = (x);                                         \
        if (r_ != VK_SUCCESS) {                                    \
            snprintf(why, why_len, "%.60s failed (%d)", #x, (int)r_); \
            return r_;                                             \
        }                                                          \
    } while (0)

/* Everything except the command buffers (those need the present queue's family). */
static VkResult setup_upscaler(upscaler *u, const VkSwapchainCreateInfoKHR *sci, float sharp, char *why,
                               size_t why_len) {
    device_entry *d = u->d;
    VkDevice dev = d->device;
    VkFormat view_fmt = unorm_of(u->app_format);

    uint32_t n = 0;
    TRY(d->f.GetSwapchainImagesKHR(dev, u->handle, &n, NULL));
    if (n == 0 || n > MAX_IMAGES) {
        snprintf(why, why_len, "%u swapchain images", n);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    u->count = n;
    TRY(d->f.GetSwapchainImagesKHR(dev, u->handle, &u->count, u->real));

    /* The game's images: its size and format, readable by the EASU shader. */
    for (uint32_t i = 0; i < u->count; i++) {
        TRY(make_image(d, u->app_format, u->in, sci->imageUsage | VK_IMAGE_USAGE_SAMPLED_BIT,
                       VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, sci, &u->app[i], &u->app_mem[i]));
        TRY(make_view(d, u->app[i], view_fmt, VK_IMAGE_USAGE_SAMPLED_BIT, &u->app_view[i]));
    }
    /* A: EASU output (also read by RCAS). B: RCAS output, written in the swapchain's channel
       order and copied byte for byte into the real image (both are 32-bit formats). */
    TRY(make_image(d, VK_FORMAT_R8G8B8A8_UNORM, u->out, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, 0,
                   NULL, &u->a, &u->a_mem));
    TRY(make_view(d, u->a, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                  &u->a_view));
    TRY(make_image(d, VK_FORMAT_R8G8B8A8_UNORM, u->out, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                   0, NULL, &u->b, &u->b_mem));
    TRY(make_view(d, u->b, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT, &u->b_view));

    VkSamplerCreateInfo sci2 = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sci2.magFilter = sci2.minFilter = VK_FILTER_LINEAR;
    sci2.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sci2.addressModeU = sci2.addressModeV = sci2.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci2.maxLod = 0.0f;
    TRY(d->f.CreateSampler(dev, &sci2, NULL, &u->sampler));

    VkDescriptorSetLayoutBinding bind[2] = {
        {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL},
        {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL},
    };
    VkDescriptorSetLayoutCreateInfo dslci = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dslci.bindingCount = 2;
    dslci.pBindings = bind;
    TRY(d->f.CreateDescriptorSetLayout(dev, &dslci, NULL, &u->dsl));
    VkPushConstantRange pcr = {VK_SHADER_STAGE_COMPUTE_BIT, 0, 64};
    VkPipelineLayoutCreateInfo plci = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &u->dsl;
    plci.pushConstantRangeCount = 1;
    plci.pPushConstantRanges = &pcr;
    TRY(d->f.CreatePipelineLayout(dev, &plci, NULL, &u->layout));
    TRY(make_pipeline(d, u, bh_easu_spv, sizeof bh_easu_spv, &u->easu));
    TRY(make_pipeline(d, u, bh_rcas_spv, sizeof bh_rcas_spv, &u->rcas));

    VkDescriptorPoolSize ps[2] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, u->count + 1},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, u->count + 1},
    };
    VkDescriptorPoolCreateInfo dpci = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpci.maxSets = u->count + 1;
    dpci.poolSizeCount = 2;
    dpci.pPoolSizes = ps;
    TRY(d->f.CreateDescriptorPool(dev, &dpci, NULL, &u->dpool));
    VkDescriptorSetLayout layouts[MAX_IMAGES + 1];
    for (uint32_t i = 0; i <= u->count; i++) layouts[i] = u->dsl;
    VkDescriptorSet sets[MAX_IMAGES + 1];
    VkDescriptorSetAllocateInfo dsai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsai.descriptorPool = u->dpool;
    dsai.descriptorSetCount = u->count + 1;
    dsai.pSetLayouts = layouts;
    TRY(d->f.AllocateDescriptorSets(dev, &dsai, sets));
    for (uint32_t i = 0; i < u->count; i++) u->set_easu[i] = sets[i];
    u->set_rcas = sets[u->count];

    for (uint32_t i = 0; i <= u->count; i++) {
        int rcas = i == u->count;
        VkDescriptorImageInfo in = {u->sampler, rcas ? u->a_view : u->app_view[i],
                                    rcas ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorImageInfo out = {VK_NULL_HANDLE, rcas ? u->b_view : u->a_view, VK_IMAGE_LAYOUT_GENERAL};
        VkWriteDescriptorSet w[2] = {{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}, {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}};
        w[0].dstSet = w[1].dstSet = sets[i];
        w[0].dstBinding = 0;
        w[0].descriptorCount = 1;
        w[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        w[0].pImageInfo = &in;
        w[1].dstBinding = 1;
        w[1].descriptorCount = 1;
        w[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        w[1].pImageInfo = &out;
        d->f.UpdateDescriptorSets(dev, 2, w, 0, NULL);
    }

    VkSemaphoreCreateInfo semci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < u->count; i++) TRY(d->f.CreateSemaphore(dev, &semci, NULL, &u->done[i]));

    FsrEasuCon(u->con_easu, u->con_easu + 4, u->con_easu + 8, u->con_easu + 12, (AF1)u->in.width,
               (AF1)u->in.height, (AF1)u->in.width, (AF1)u->in.height, (AF1)u->out.width, (AF1)u->out.height);
    FsrRcasCon(u->con_rcas, sharp);
    u->con_rcas[2] = unorm_of(u->app_format) == VK_FORMAT_B8G8R8A8_UNORM; /* store as BGRA */
    return VK_SUCCESS;
}

static void image_barrier(VkImageMemoryBarrier *b, VkImage img, VkAccessFlags src, VkAccessFlags dst,
                          VkImageLayout from, VkImageLayout to) {
    memset(b, 0, sizeof *b);
    b->sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b->srcAccessMask = src;
    b->dstAccessMask = dst;
    b->oldLayout = from;
    b->newLayout = to;
    b->srcQueueFamilyIndex = b->dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b->image = img;
    b->subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b->subresourceRange.levelCount = 1;
    b->subresourceRange.layerCount = 1;
}

/* One command buffer per image: game image → EASU → A → RCAS → B → copy → real image. */
static VkResult record(upscaler *u, uint32_t family) {
    device_entry *d = u->d;
    VkDevice dev = d->device;
    if (u->pool) {
        d->f.DeviceWaitIdle(dev);
        d->f.DestroyCommandPool(dev, u->pool, NULL);
        u->pool = VK_NULL_HANDLE;
    }
    VkCommandPoolCreateInfo cpci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpci.queueFamilyIndex = family;
    VkResult r = d->f.CreateCommandPool(dev, &cpci, NULL, &u->pool);
    if (r != VK_SUCCESS) return r;
    u->pool_family = family;
    VkCommandBufferAllocateInfo cbai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbai.commandPool = u->pool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = u->count;
    r = d->f.AllocateCommandBuffers(dev, &cbai, u->cmd);
    if (r != VK_SUCCESS) return r;

    uint32_t gx = (u->out.width + 7) / 8, gy = (u->out.height + 7) / 8;
    for (uint32_t i = 0; i < u->count; i++) {
        VkCommandBuffer cb = u->cmd[i];
        /* Command buffers the layer makes need the loader's dispatch pointer. */
        if (d->set_loader_data) d->set_loader_data(dev, cb);
        else *(void **)cb = *(void **)dev;

        VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;
        r = d->f.BeginCommandBuffer(cb, &bi);
        if (r != VK_SUCCESS) return r;

        VkImageMemoryBarrier b[2];
        image_barrier(&b[0], u->app[i], VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                      VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        image_barrier(&b[1], u->a, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                      VK_IMAGE_LAYOUT_GENERAL);
        d->f.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0,
                                NULL, 0, NULL, 2, b);
        d->f.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, u->easu);
        d->f.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, u->layout, 0, 1, &u->set_easu[i], 0, NULL);
        d->f.CmdPushConstants(cb, u->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, 64, u->con_easu);
        d->f.CmdDispatch(cb, gx, gy, 1);

        image_barrier(&b[0], u->a, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL,
                      VK_IMAGE_LAYOUT_GENERAL);
        image_barrier(&b[1], u->b, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                      VK_IMAGE_LAYOUT_GENERAL);
        d->f.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, NULL, 0, NULL, 2, b);
        d->f.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, u->rcas);
        d->f.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, u->layout, 0, 1, &u->set_rcas, 0, NULL);
        d->f.CmdPushConstants(cb, u->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, 16, u->con_rcas);
        d->f.CmdDispatch(cb, gx, gy, 1);

        VkImageMemoryBarrier b3[3];
        image_barrier(&b3[0], u->b, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL,
                      VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        image_barrier(&b3[1], u->real[i], 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        image_barrier(&b3[2], u->app[i], 0, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                      VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        d->f.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 3, b3);
        VkImageCopy copy;
        memset(&copy, 0, sizeof copy);
        copy.srcSubresource.aspectMask = copy.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copy.srcSubresource.layerCount = copy.dstSubresource.layerCount = 1;
        copy.extent.width = u->out.width;
        copy.extent.height = u->out.height;
        copy.extent.depth = 1;
        d->f.CmdCopyImage(cb, u->b, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, u->real[i],
                          VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        image_barrier(&b3[0], u->real[i], VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                      VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        d->f.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL,
                                0, NULL, 1, b3);
        r = d->f.EndCommandBuffer(cb);
        if (r != VK_SUCCESS) return r;
    }
    return VK_SUCCESS;
}

/* BH_VKLAYER_OUT=WxH → the output size for a game swapchain of `in` (each axis up to 4x;
   the aspect may change: the app scales the surface back by in / out), or 0x0 for none. */
static VkExtent2D target_size(VkExtent2D in) {
    VkExtent2D none = {0, 0};
    const char *s = getenv("BH_VKLAYER_OUT");
    unsigned w = 0, h = 0;
    if (!s || sscanf(s, "%ux%u", &w, &h) != 2 || !w || !h || !in.width || !in.height) return none;
    if (w > in.width * 4) w = in.width * 4;
    if (h > in.height * 4) h = in.height * 4;
    if (w < in.width || h < in.height) return none;            /* would shrink an axis */
    if (w < in.width * 21 / 20 && h < in.height * 21 / 20) return none; /* about the same size */
    VkExtent2D out = {w, h};
    return out;
}

static float sharpness(void) {
    const char *s = getenv("BH_VKLAYER_SHARP");
    float v = s && *s ? (float)atof(s) : 0.25f;
    return v < 0 ? 0 : v > 2 ? 2 : v;
}

/* ── Instance ───────────────────────────────────────────────────────────── */

static VKAPI_ATTR VkResult VKAPI_CALL bh_CreateInstance(const VkInstanceCreateInfo *ci,
                                                         const VkAllocationCallbacks *alloc,
                                                         VkInstance *out) {
    bh_instance_create_info *link = (bh_instance_create_info *)ci->pNext;
    while (link && !(link->sType == LOADER_INSTANCE_CREATE_INFO && link->function == BH_LAYER_LINK_INFO))
        link = (bh_instance_create_info *)link->pNext;
    if (!link || !link->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr next_gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext; /* hand the chain on */
    PFN_vkCreateInstance next_create = (PFN_vkCreateInstance)next_gipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult r = next_create(ci, alloc, out);
    if (r != VK_SUCCESS) return r;

    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_ENTRIES; i++) {
        instance_entry *e = &g_instances[i];
        if (e->key) continue;
        e->key = key_of(*out);
        e->instance = *out;
        e->gipa = next_gipa;
        e->destroy_instance = (PFN_vkDestroyInstance)next_gipa(*out, "vkDestroyInstance");
        e->mem_props = (PFN_vkGetPhysicalDeviceMemoryProperties)next_gipa(*out, "vkGetPhysicalDeviceMemoryProperties");
        e->surface_caps = (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)next_gipa(
            *out, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
        e->format_props = (PFN_vkGetPhysicalDeviceFormatProperties)next_gipa(*out, "vkGetPhysicalDeviceFormatProperties");
        break;
    }
    if (ci->pApplicationInfo) {
        if (ci->pApplicationInfo->pApplicationName)
            snprintf(g_stat.app, sizeof g_stat.app, "%s", ci->pApplicationInfo->pApplicationName);
        if (ci->pApplicationInfo->pEngineName)
            snprintf(g_stat.engine, sizeof g_stat.engine, "%s", ci->pApplicationInfo->pEngineName);
    }
    write_status();
    pthread_mutex_unlock(&g_lock);
    logf_("instance created (app \"%s\", engine \"%s\")", g_stat.app, g_stat.engine);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL bh_DestroyInstance(VkInstance instance, const VkAllocationCallbacks *alloc) {
    pthread_mutex_lock(&g_lock);
    instance_entry *e = find_instance(instance);
    PFN_vkDestroyInstance next = e ? e->destroy_instance : NULL;
    if (e) memset(e, 0, sizeof *e);
    pthread_mutex_unlock(&g_lock);
    if (next) next(instance, alloc);
}

/* ── Device ─────────────────────────────────────────────────────────────── */

static VKAPI_ATTR VkResult VKAPI_CALL bh_CreateDevice(VkPhysicalDevice phys, const VkDeviceCreateInfo *ci,
                                                       const VkAllocationCallbacks *alloc, VkDevice *out) {
    bh_device_create_info *link = (bh_device_create_info *)ci->pNext;
    while (link && !(link->sType == LOADER_DEVICE_CREATE_INFO && link->function == BH_LAYER_LINK_INFO))
        link = (bh_device_create_info *)link->pNext;
    if (!link || !link->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    bh_set_device_loader_data set_data = NULL;
    for (bh_device_create_info *c = (bh_device_create_info *)ci->pNext; c; c = (bh_device_create_info *)c->pNext)
        if (c->sType == LOADER_DEVICE_CREATE_INFO && c->function == BH_LOADER_DATA_CALLBACK)
            set_data = c->u.pfnSetDeviceLoaderData;

    PFN_vkGetInstanceProcAddr next_gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr next_gdpa = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;

    pthread_mutex_lock(&g_lock);
    instance_entry *ie = find_instance(phys); /* a physical device shares its instance's dispatch */
    instance_entry inst;
    if (ie) inst = *ie;
    else memset(&inst, 0, sizeof inst);
    pthread_mutex_unlock(&g_lock);
    PFN_vkCreateDevice next_create = (PFN_vkCreateDevice)next_gipa(inst.instance, "vkCreateDevice");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult r = next_create(phys, ci, alloc, out);
    if (r != VK_SUCCESS) return r;

    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_ENTRIES; i++) {
        device_entry *e = &g_devices[i];
        if (e->key) continue;
        memset(e, 0, sizeof *e);
        e->key = key_of(*out);
        e->device = *out;
        e->phys = phys;
        e->gdpa = next_gdpa;
        e->set_loader_data = set_data;
        e->inst = inst;
#define X(n) e->f.n = (PFN_vk##n)next_gdpa(*out, "vk" #n);
        DEVICE_FUNCS(X)
#undef X
        break;
    }
    pthread_mutex_unlock(&g_lock);
    logf_("device created");
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL bh_DestroyDevice(VkDevice device, const VkAllocationCallbacks *alloc) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(device);
    PFN_vkDestroyDevice next = e ? e->f.DestroyDevice : NULL;
    for (int i = 0; e && i < MAX_SWAPCHAINS; i++) {
        if (g_swapchains[i] && g_swapchains[i]->d == e) { /* left behind by the game */
            destroy_upscaler(g_swapchains[i]);
            g_swapchains[i] = NULL;
        }
    }
    if (e) memset(e, 0, sizeof *e);
    write_status(); /* the final count */
    pthread_mutex_unlock(&g_lock);
    logf_("device destroyed after %llu frames", (unsigned long long)g_stat.frames);
    if (next) next(device, alloc);
}

static VKAPI_ATTR void VKAPI_CALL bh_GetDeviceQueue(VkDevice device, uint32_t family, uint32_t index, VkQueue *q) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(device);
    PFN_vkGetDeviceQueue next = e ? e->f.GetDeviceQueue : NULL;
    pthread_mutex_unlock(&g_lock);
    if (!next) return;
    next(device, family, index, q);
    pthread_mutex_lock(&g_lock);
    if (*q) remember_queue(e, *q, family);
    pthread_mutex_unlock(&g_lock);
}

static VKAPI_ATTR void VKAPI_CALL bh_GetDeviceQueue2(VkDevice device, const VkDeviceQueueInfo2 *info, VkQueue *q) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(device);
    PFN_vkGetDeviceQueue2 next = e ? e->f.GetDeviceQueue2 : NULL;
    pthread_mutex_unlock(&g_lock);
    if (!next) return;
    next(device, info, q);
    pthread_mutex_lock(&g_lock);
    if (*q) remember_queue(e, *q, info->queueFamilyIndex);
    pthread_mutex_unlock(&g_lock);
}

/* ── Swapchain ──────────────────────────────────────────────────────────── */

static void note(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_stat.note, sizeof g_stat.note, fmt, ap);
    va_end(ap);
    logf_("%s", g_stat.note);
}

static VKAPI_ATTR VkResult VKAPI_CALL bh_CreateSwapchainKHR(VkDevice device, const VkSwapchainCreateInfoKHR *ci,
                                                             const VkAllocationCallbacks *alloc,
                                                             VkSwapchainKHR *out) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(device);
    pthread_mutex_unlock(&g_lock);
    if (!e || !e->f.CreateSwapchainKHR) return VK_ERROR_INITIALIZATION_FAILED;

    VkExtent2D target = target_size(ci->imageExtent);
    upscaler *u = NULL;
    int retired = 0; /* a create succeeded, so ci->oldSwapchain is retired now */
    VkResult r;
    pthread_mutex_lock(&g_lock);
    g_stat.note[0] = 0;
    g_stat.upscale = 0;
    g_stat.out_w = g_stat.out_h = 0;
    g_stat.sharp = sharpness();
    pthread_mutex_unlock(&g_lock);

    if (target.width && g_upscale_disabled) {
        target.width = target.height = 0;
        pthread_mutex_lock(&g_lock);
        snprintf(g_stat.note, sizeof g_stat.note, "upscale off: the game keeps having to recreate its swapchain");
        pthread_mutex_unlock(&g_lock);
    } else if (!target.width && getenv("BH_VKLAYER_OUT")) {
        note("no upscale: the game's %ux%u is already about the display size", ci->imageExtent.width,
             ci->imageExtent.height);
    }
    if (target.width) {
        VkSurfaceCapabilitiesKHR caps;
        memset(&caps, 0, sizeof caps);
        int ok = 1;
        if (!e->inst.mem_props || !e->inst.surface_caps) {
            note("upscale off: instance functions not found");
            ok = 0;
        } else if (unorm_of(ci->imageFormat) == VK_FORMAT_UNDEFINED) {
            note("upscale off: format %d not handled", ci->imageFormat);
            ok = 0;
        } else if (ci->imageArrayLayers != 1) {
            note("upscale off: %u array layers", ci->imageArrayLayers);
            ok = 0;
        } else if (e->inst.surface_caps(e->phys, ci->surface, &caps) != VK_SUCCESS ||
                   !(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) {
            note("upscale off: the swapchain can't be a copy target (usage 0x%x)", caps.supportedUsageFlags);
            ok = 0;
        }
        if (ok) {
            VkSwapchainCreateInfoKHR real = *ci;
            real.imageExtent = target;
            real.imageUsage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            r = e->f.CreateSwapchainKHR(device, &real, alloc, out);
            if (r != VK_SUCCESS) {
                note("upscale off: driver refused a %ux%u swapchain (%d)", target.width, target.height, (int)r);
            } else {
                retired = 1;
                u = (upscaler *)calloc(1, sizeof *u);
                char why[160] = "out of memory";
                VkResult sr = VK_ERROR_OUT_OF_HOST_MEMORY;
                if (u) {
                    u->handle = *out;
                    u->d = e;
                    u->in = ci->imageExtent;
                    u->out = target;
                    u->app_format = ci->imageFormat;
                    sr = setup_upscaler(u, ci, g_stat.sharp, why, sizeof why);
                }
                if (sr != VK_SUCCESS) {
                    note("upscale off: %s", why);
                    if (u) destroy_upscaler(u);
                    u = NULL;
                    e->f.DestroySwapchainKHR(device, *out, alloc);
                }
            }
        }
    }

    if (u) {
        pthread_mutex_lock(&g_lock);
        int slot = -1;
        for (int i = 0; i < MAX_SWAPCHAINS; i++) if (!g_swapchains[i]) { slot = i; break; }
        if (slot >= 0) {
            g_swapchains[slot] = u;
            count_creation();
        }
        pthread_mutex_unlock(&g_lock);
        if (slot < 0) {
            note("upscale off: too many swapchains");
            destroy_upscaler(u);
            u = NULL;
            e->f.DestroySwapchainKHR(device, *out, alloc);
        }
    }
    if (!u) {
        /* Pass-through. An oldSwapchain is retired once a create succeeds, so don't pass it again. */
        VkSwapchainCreateInfoKHR plain = *ci;
        if (retired) plain.oldSwapchain = VK_NULL_HANDLE;
        r = e->f.CreateSwapchainKHR(device, &plain, alloc, out);
        if (r != VK_SUCCESS) return r;
    }

    pthread_mutex_lock(&g_lock);
    g_stat.width = ci->imageExtent.width;
    g_stat.height = ci->imageExtent.height;
    g_stat.format = ci->imageFormat;
    g_stat.present_mode = ci->presentMode;
    g_stat.images = u ? u->count : ci->minImageCount;
    g_stat.upscale = u != NULL;
    if (u) {
        g_stat.out_w = u->out.width;
        g_stat.out_h = u->out.height;
    }
    write_status();
    pthread_mutex_unlock(&g_lock);
    logf_("swapchain %ux%u, format %d, present mode %d, %u images%s", ci->imageExtent.width, ci->imageExtent.height,
          ci->imageFormat, ci->presentMode, g_stat.images, u ? ", upscaled with FSR 1" : "");
    if (u) logf_("FSR 1: %ux%u -> %ux%u, sharpness %.2f", u->in.width, u->in.height, u->out.width, u->out.height,
                 g_stat.sharp);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL bh_DestroySwapchainKHR(VkDevice device, VkSwapchainKHR sc,
                                                          const VkAllocationCallbacks *alloc) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(device);
    upscaler *u = NULL;
    for (int i = 0; i < MAX_SWAPCHAINS; i++) {
        if (g_swapchains[i] && g_swapchains[i]->handle == sc) {
            u = g_swapchains[i];
            g_swapchains[i] = NULL;
        }
    }
    pthread_mutex_unlock(&g_lock);
    if (u) destroy_upscaler(u);
    if (e && e->f.DestroySwapchainKHR) e->f.DestroySwapchainKHR(device, sc, alloc);
}

static VKAPI_ATTR VkResult VKAPI_CALL bh_GetSwapchainImagesKHR(VkDevice device, VkSwapchainKHR sc, uint32_t *count,
                                                                VkImage *images) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(device);
    upscaler *u = find_upscaler(sc);
    if (u) {
        VkResult r = VK_SUCCESS;
        if (!images) {
            *count = u->count;
        } else {
            uint32_t n = *count < u->count ? *count : u->count;
            memcpy(images, u->app, n * sizeof *images);
            if (n < u->count) r = VK_INCOMPLETE;
            *count = n;
        }
        pthread_mutex_unlock(&g_lock);
        return r;
    }
    PFN_vkGetSwapchainImagesKHR next = e ? e->f.GetSwapchainImagesKHR : NULL;
    pthread_mutex_unlock(&g_lock);
    return next ? next(device, sc, count, images) : VK_ERROR_INITIALIZATION_FAILED;
}

/* The real swapchain is larger than its window on purpose, so a driver's "suboptimal" (the
   size doesn't match) is expected; passing it on would make the game recreate its swapchain
   on every frame. Out-of-date still goes through. */
static VKAPI_ATTR VkResult VKAPI_CALL bh_AcquireNextImageKHR(VkDevice device, VkSwapchainKHR sc, uint64_t timeout,
                                                              VkSemaphore sem, VkFence fence, uint32_t *index) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(device);
    PFN_vkAcquireNextImageKHR next = e ? e->f.AcquireNextImageKHR : NULL;
    int ours = find_upscaler(sc) != NULL;
    pthread_mutex_unlock(&g_lock);
    if (!next) return VK_ERROR_DEVICE_LOST;
    VkResult r = next(device, sc, timeout, sem, fence, index);
    if (ours && r == VK_ERROR_OUT_OF_DATE_KHR) {
        pthread_mutex_lock(&g_lock);
        out_of_date(find_upscaler(sc));
        pthread_mutex_unlock(&g_lock);
    }
    return ours && r == VK_SUBOPTIMAL_KHR ? VK_SUCCESS : r;
}

/* ── Present ────────────────────────────────────────────────────────────── */

#define MAX_WAITS 16

static VKAPI_ATTR VkResult VKAPI_CALL bh_QueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *info) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(queue); /* a queue shares its device's dispatch */
    PFN_vkQueuePresentKHR next = e ? e->f.QueuePresentKHR : NULL;
    g_stat.frames++;
    double t = now_s();
    if (g_stat.mark == 0) {
        g_stat.mark = t;
        g_stat.frames_at_mark = g_stat.frames;
    } else if (t - g_stat.mark >= 1.0) {
        g_stat.fps = (g_stat.frames - g_stat.frames_at_mark) / (t - g_stat.mark);
        g_stat.mark = t;
        g_stat.frames_at_mark = g_stat.frames;
        write_status();
    }
    if (!next) {
        pthread_mutex_unlock(&g_lock);
        return VK_ERROR_DEVICE_LOST;
    }

    /* Upscale the presented images of our swapchains in one submit that waits for the game. */
    VkCommandBuffer cmds[MAX_SWAPCHAINS];
    VkSemaphore signals[MAX_SWAPCHAINS];
    uint32_t n = 0;
    uint32_t family = queue_family(e, queue);
    for (uint32_t k = 0; k < info->swapchainCount && n < MAX_SWAPCHAINS; k++) {
        upscaler *u = find_upscaler(info->pSwapchains[k]);
        uint32_t idx = info->pImageIndices[k];
        if (!u || idx >= u->count) continue;
        if (!u->pool || u->pool_family != family) {
            VkResult rr = record(u, family);
            if (rr != VK_SUCCESS) {
                logf_("recording the upscale commands failed (%d)", (int)rr);
                continue;
            }
        }
        u->presents++;
        cmds[n] = u->cmd[idx];
        signals[n] = u->done[idx];
        n++;
    }
    if (n == 0) {
        pthread_mutex_unlock(&g_lock);
        return next(queue, info);
    }
    VkPipelineStageFlags stages[MAX_WAITS];
    uint32_t waits = info->waitSemaphoreCount < MAX_WAITS ? info->waitSemaphoreCount : MAX_WAITS;
    for (uint32_t i = 0; i < waits; i++) stages[i] = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = waits;
    si.pWaitSemaphores = info->pWaitSemaphores;
    si.pWaitDstStageMask = stages;
    si.commandBufferCount = n;
    si.pCommandBuffers = cmds;
    si.signalSemaphoreCount = n;
    si.pSignalSemaphores = signals;
    PFN_vkQueueSubmit submit = e->f.QueueSubmit;
    pthread_mutex_unlock(&g_lock);

    VkResult r = submit(queue, 1, &si, VK_NULL_HANDLE);
    if (r != VK_SUCCESS) return r;
    VkPresentInfoKHR pi = *info;
    pi.waitSemaphoreCount = n;
    pi.pWaitSemaphores = signals;
    r = next(queue, &pi);
    if (r == VK_ERROR_OUT_OF_DATE_KHR || info->pResults) {
        pthread_mutex_lock(&g_lock);
        for (uint32_t k = 0; k < info->swapchainCount; k++) {
            VkResult rk = info->pResults ? info->pResults[k] : r;
            if (rk == VK_ERROR_OUT_OF_DATE_KHR) out_of_date(find_upscaler(info->pSwapchains[k]));
        }
        pthread_mutex_unlock(&g_lock);
    }
    if (r == VK_SUBOPTIMAL_KHR && n == info->swapchainCount) r = VK_SUCCESS;
    if (info->pResults)
        for (uint32_t k = 0; k < info->swapchainCount; k++)
            if (info->pResults[k] == VK_SUBOPTIMAL_KHR) {
                pthread_mutex_lock(&g_lock);
                if (find_upscaler(info->pSwapchains[k])) info->pResults[k] = VK_SUCCESS;
                pthread_mutex_unlock(&g_lock);
            }
    return r;
}

/* ── Entry points ───────────────────────────────────────────────────────── */

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL bh_GetDeviceProcAddr(VkDevice device, const char *name);

static PFN_vkVoidFunction device_hook(const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)bh_GetDeviceProcAddr;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)bh_DestroyDevice;
    if (!strcmp(name, "vkGetDeviceQueue")) return (PFN_vkVoidFunction)bh_GetDeviceQueue;
    if (!strcmp(name, "vkGetDeviceQueue2")) return (PFN_vkVoidFunction)bh_GetDeviceQueue2;
    if (!strcmp(name, "vkCreateSwapchainKHR")) return (PFN_vkVoidFunction)bh_CreateSwapchainKHR;
    if (!strcmp(name, "vkDestroySwapchainKHR")) return (PFN_vkVoidFunction)bh_DestroySwapchainKHR;
    if (!strcmp(name, "vkGetSwapchainImagesKHR")) return (PFN_vkVoidFunction)bh_GetSwapchainImagesKHR;
    if (!strcmp(name, "vkQueuePresentKHR")) return (PFN_vkVoidFunction)bh_QueuePresentKHR;
    if (!strcmp(name, "vkAcquireNextImageKHR")) return (PFN_vkVoidFunction)bh_AcquireNextImageKHR;
    return NULL;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL bh_GetDeviceProcAddr(VkDevice device, const char *name) {
    PFN_vkVoidFunction hook = device_hook(name);
    if (hook) return hook;
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(device);
    PFN_vkGetDeviceProcAddr next = e ? e->gdpa : NULL;
    pthread_mutex_unlock(&g_lock);
    return next ? next(device, name) : NULL;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL bh_GetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)bh_GetInstanceProcAddr;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)bh_CreateInstance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)bh_DestroyInstance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)bh_CreateDevice;
    PFN_vkVoidFunction hook = device_hook(name);
    if (hook) return hook;
    pthread_mutex_lock(&g_lock);
    instance_entry *e = find_instance(instance);
    PFN_vkGetInstanceProcAddr next = e ? e->gipa : NULL;
    pthread_mutex_unlock(&g_lock);
    return next ? next(instance, name) : NULL;
}

/* Loader interface version 2+: the loader asks for our entry points here. */
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(bh_negotiate *v) {
    if (!v || v->sType != 1) return VK_ERROR_INITIALIZATION_FAILED;
    if (v->loaderLayerInterfaceVersion > 2) v->loaderLayerInterfaceVersion = 2;
    v->pfnGetInstanceProcAddr = bh_GetInstanceProcAddr;
    v->pfnGetDeviceProcAddr = bh_GetDeviceProcAddr;
    v->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
