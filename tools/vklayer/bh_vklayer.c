/*
 * bh_vklayer — BunnerHub Etech's Vulkan layer, loaded into a game's Wine
 * process by the Vulkan loader (an implicit layer, enabled only when the
 * game's environment has BH_VKLAYER=1; see extension/BhVkLayer.java).
 *
 * Step 1 (this file): a pass-through layer that proves it is loaded and sits
 * on the game's present path. It changes nothing; it records the game's
 * swapchain (size, format, present mode, image count) and frame rate in a
 * status file (BH_VKLAYER_STATUS) and the Android log (tag "BhVkLayer").
 *
 * Later steps add FSR 1 / CAS upscaling at present time (AMD FidelityFX,
 * MIT), the way Wine's fullscreen hack and gamescope do it.
 *
 * Built by CI with the Android NDK (bionic, arm64-v8a); also builds on desktop
 * Linux for testing with a software Vulkan driver.
 */
#include <vulkan/vulkan.h>

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

#define LAYER_NAME "VK_LAYER_BH_upscale"
#define EXPORT __attribute__((visibility("default")))

/* ── The loader ↔ layer interface (Vulkan-Loader's vk_layer.h, Apache-2.0) ── */

typedef enum { BH_LAYER_LINK_INFO = 0 } bh_layer_function;

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

typedef struct {
    VkStructureType sType; /* VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO */
    const void *pNext;
    int function;
    union {
        bh_device_link *pLayerInfo;
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
    fprintf(f, "layer=" LAYER_NAME "\nstep=1\napp=%s\nengine=%s\nswapchain=%ux%u\nformat=%d\n"
               "present_mode=%d\nimages=%u\nframes=%llu\nfps=%.1f\ntime=%lld\n",
            g_stat.app, g_stat.engine, g_stat.width, g_stat.height, g_stat.format, g_stat.present_mode,
            g_stat.images, (unsigned long long)g_stat.frames, g_stat.fps, (long long)time(NULL));
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
    PFN_vkCreateDevice create_device;
} instance_entry;

typedef struct {
    void *key;
    VkDevice device;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkDestroyDevice destroy_device;
    PFN_vkCreateSwapchainKHR create_swapchain;
    PFN_vkQueuePresentKHR queue_present;
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
        if (g_instances[i].key) continue;
        g_instances[i].key = key_of(*out);
        g_instances[i].instance = *out;
        g_instances[i].gipa = next_gipa;
        g_instances[i].destroy_instance = (PFN_vkDestroyInstance)next_gipa(*out, "vkDestroyInstance");
        g_instances[i].create_device = (PFN_vkCreateDevice)next_gipa(*out, "vkCreateDevice");
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

    PFN_vkGetInstanceProcAddr next_gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr next_gdpa = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;

    pthread_mutex_lock(&g_lock);
    instance_entry *ie = find_instance(phys); /* a physical device shares its instance's dispatch */
    VkInstance inst = ie ? ie->instance : VK_NULL_HANDLE;
    pthread_mutex_unlock(&g_lock);
    PFN_vkCreateDevice next_create = (PFN_vkCreateDevice)next_gipa(inst, "vkCreateDevice");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult r = next_create(phys, ci, alloc, out);
    if (r != VK_SUCCESS) return r;

    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_ENTRIES; i++) {
        if (g_devices[i].key) continue;
        g_devices[i].key = key_of(*out);
        g_devices[i].device = *out;
        g_devices[i].gdpa = next_gdpa;
        g_devices[i].destroy_device = (PFN_vkDestroyDevice)next_gdpa(*out, "vkDestroyDevice");
        g_devices[i].create_swapchain = (PFN_vkCreateSwapchainKHR)next_gdpa(*out, "vkCreateSwapchainKHR");
        g_devices[i].queue_present = (PFN_vkQueuePresentKHR)next_gdpa(*out, "vkQueuePresentKHR");
        break;
    }
    pthread_mutex_unlock(&g_lock);
    logf_("device created");
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL bh_DestroyDevice(VkDevice device, const VkAllocationCallbacks *alloc) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(device);
    PFN_vkDestroyDevice next = e ? e->destroy_device : NULL;
    if (e) memset(e, 0, sizeof *e);
    write_status(); /* the final count */
    pthread_mutex_unlock(&g_lock);
    logf_("device destroyed after %llu frames", (unsigned long long)g_stat.frames);
    if (next) next(device, alloc);
}

/* ── Swapchain and present: observed, not changed (step 1) ──────────────── */

static VKAPI_ATTR VkResult VKAPI_CALL bh_CreateSwapchainKHR(VkDevice device, const VkSwapchainCreateInfoKHR *ci,
                                                             const VkAllocationCallbacks *alloc,
                                                             VkSwapchainKHR *out) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(device);
    PFN_vkCreateSwapchainKHR next = e ? e->create_swapchain : NULL;
    pthread_mutex_unlock(&g_lock);
    if (!next) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = next(device, ci, alloc, out);
    if (r == VK_SUCCESS) {
        pthread_mutex_lock(&g_lock);
        g_stat.width = ci->imageExtent.width;
        g_stat.height = ci->imageExtent.height;
        g_stat.format = ci->imageFormat;
        g_stat.present_mode = ci->presentMode;
        g_stat.images = ci->minImageCount;
        write_status();
        pthread_mutex_unlock(&g_lock);
        logf_("swapchain %ux%u, format %d, present mode %d, %u images", ci->imageExtent.width,
              ci->imageExtent.height, ci->imageFormat, ci->presentMode, ci->minImageCount);
    }
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL bh_QueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *info) {
    pthread_mutex_lock(&g_lock);
    device_entry *e = find_device(queue); /* a queue shares its device's dispatch */
    PFN_vkQueuePresentKHR next = e ? e->queue_present : NULL;
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
    pthread_mutex_unlock(&g_lock);
    return next ? next(queue, info) : VK_ERROR_DEVICE_LOST;
}

/* ── Entry points ───────────────────────────────────────────────────────── */

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL bh_GetDeviceProcAddr(VkDevice device, const char *name);

static PFN_vkVoidFunction device_hook(const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)bh_GetDeviceProcAddr;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)bh_DestroyDevice;
    if (!strcmp(name, "vkCreateSwapchainKHR")) return (PFN_vkVoidFunction)bh_CreateSwapchainKHR;
    if (!strcmp(name, "vkQueuePresentKHR")) return (PFN_vkVoidFunction)bh_QueuePresentKHR;
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
