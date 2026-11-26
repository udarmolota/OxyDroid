// libvkdriverhook.so: lets the native engine run on a Vulkan driver other than the phone's own (a test
// switch, OXYDROID_VULKAN_DRIVER; see driver.c). vkshim loads this library into a linker namespace of its
// own, ahead of a private copy of the system libvulkan.so. Linked with -z global, it comes before libdl
// for every library in that namespace, so when the copy looks for the phone's driver
// ("vulkan.<board>.so", through android_load_sphal_library or android_dlopen_ext) it gets the driver
// vkshim loaded instead. Everything else passes through to the real linker. The same mechanism as the
// box64 launch (app/src/main/cpp/linker.c).
//
// With OXYDROID_SWAPCHAIN_LINEAR=1 (driver.c calls vkdriverhook_wrap) the copy gets this library instead
// of the driver: its HMI opens the driver's and hands the loader the driver's device with one change, the
// gralloc usage the driver asks for its swapchain buffers gets CPU read and write added. A Mali gralloc
// then allocates them uncompressed instead of AFBC, which PanVK builds on MediaTek phones cannot import
// (GetMemoryFdPropertiesKHR failed, VK_ERROR_INVALID_EXTERNAL_HANDLE on every swapchain; TECNO LJ7 with a
// Mali-G615, 2026-11-26).
#include <android/dlext.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <vulkan/vulkan.h>

#define EXPORT __attribute__((visibility("default"), used))

typedef void* (*LoaderDlopenExt)(const char* filename, int flags, const android_dlextinfo* info, const void* caller);

static LoaderDlopenExt g_loader_dlopen_ext;
static void* g_driver;

EXPORT void vkdriverhook_set(void* loader_dlopen_ext, void* driver)
{
    g_loader_dlopen_ext = (LoaderDlopenExt)loader_dlopen_ext;
    g_driver = driver;
}

// ------------------------------------------------------------------ the HAL wrapper

// hardware/hardware.h and hardware/hwvulkan.h are not in the NDK; these are their layouts (the HAL ABI)
// on a 64-bit process.
typedef struct hw_module_t hw_module_t;
typedef struct hw_device_t hw_device_t;
typedef struct
{
    int (*open)(const hw_module_t* module, const char* id, hw_device_t** device);
} hw_module_methods_t;
struct hw_module_t
{
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char* id;
    const char* name;
    const char* author;
    hw_module_methods_t* methods;
    void* dso;
    uint64_t reserved[32 - 7];
};
struct hw_device_t
{
    uint32_t tag;
    uint32_t version;
    hw_module_t* module;
    uint64_t reserved[12];
    int (*close)(hw_device_t* device);
};
typedef struct
{
    hw_device_t common;
    PFN_vkEnumerateInstanceExtensionProperties EnumerateInstanceExtensionProperties;
    PFN_vkCreateInstance CreateInstance;
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr;
} hwvulkan_device_t;
_Static_assert(sizeof(hw_module_t) == 248, "hw_module_t layout");
_Static_assert(sizeof(hw_device_t) == 120, "hw_device_t layout");

// The swapchain gralloc usage queries of VK_ANDROID_native_buffer (vk_android_native_buffer.h, also not in
// the NDK). Versions 3 and 4 take a struct the wrapper does not need to read.
typedef VkResult(VKAPI_PTR* PFN_Usage1)(VkDevice, VkFormat, VkImageUsageFlags, int*);
typedef VkResult(VKAPI_PTR* PFN_Usage2)(VkDevice, VkFormat, VkImageUsageFlags, VkFlags, uint64_t*, uint64_t*);
typedef VkResult(VKAPI_PTR* PFN_Usage34)(VkDevice, const void*, uint64_t*);

// CPU_READ_RARELY | CPU_WRITE_RARELY: the same bits in gralloc 0 usage, in AHardwareBuffer usage and, split
// in two, in gralloc 1 (consumer CPU_READ 0x2, producer CPU_WRITE 0x20).
#define CPU_READ 0x2u
#define CPU_WRITE 0x20u

EXPORT hw_module_t HMI;

static void* g_self;
static const hw_module_t* g_real_module;
static void (*g_log)(const char* message);
static hwvulkan_device_t* g_real_device;
static hwvulkan_device_t g_device;
static PFN_vkGetInstanceProcAddr g_real_gipa;
static PFN_vkGetDeviceProcAddr g_real_gdpa;
static PFN_Usage1 g_usage1;
static PFN_Usage2 g_usage2;
static PFN_Usage34 g_usage3;
static PFN_Usage34 g_usage4;

static void log_usage(int version, uint64_t before, uint64_t after)
{
    // Once per version: the loader asks for every swapchain.
    static int logged[5];
    if (!g_log || version < 1 || version > 4 || logged[version])
        return;
    logged[version] = 1;
    char line[160];
    snprintf(line, sizeof(line), "swapchain gralloc usage (query %d): 0x%llx -> 0x%llx, with CPU access", version,
             (unsigned long long)before, (unsigned long long)after);
    g_log(line);
}

static VKAPI_ATTR VkResult VKAPI_CALL usage1(VkDevice device, VkFormat format, VkImageUsageFlags image_usage, int* usage)
{
    VkResult r = g_usage1(device, format, image_usage, usage);
    if (r == VK_SUCCESS && usage)
    {
        int before = *usage;
        *usage |= (int)(CPU_READ | CPU_WRITE);
        log_usage(1, (uint32_t)before, (uint32_t)*usage);
    }
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL usage2(VkDevice device, VkFormat format, VkImageUsageFlags image_usage,
                                             VkFlags swapchain_usage, uint64_t* consumer, uint64_t* producer)
{
    VkResult r = g_usage2(device, format, image_usage, swapchain_usage, consumer, producer);
    if (r == VK_SUCCESS && consumer && producer)
    {
        uint64_t before = *consumer | *producer;
        *consumer |= CPU_READ;
        *producer |= CPU_WRITE;
        log_usage(2, before, *consumer | *producer);
    }
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL usage3(VkDevice device, const void* info, uint64_t* usage)
{
    VkResult r = g_usage3(device, info, usage);
    if (r == VK_SUCCESS && usage)
    {
        uint64_t before = *usage;
        *usage |= CPU_READ | CPU_WRITE;
        log_usage(3, before, *usage);
    }
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL usage4(VkDevice device, const void* info, uint64_t* usage)
{
    VkResult r = g_usage4(device, info, usage);
    if (r == VK_SUCCESS && usage)
    {
        uint64_t before = *usage;
        *usage |= CPU_READ | CPU_WRITE;
        log_usage(4, before, *usage);
    }
    return r;
}

// The driver's entry point for a name, or the wrapper standing in for it.
static PFN_vkVoidFunction wrap_usage_query(const char* name, PFN_vkVoidFunction fn)
{
    if (!fn || !name)
        return fn;
    if (!strcmp(name, "vkGetSwapchainGrallocUsageANDROID"))
    {
        g_usage1 = (PFN_Usage1)fn;
        return (PFN_vkVoidFunction)usage1;
    }
    if (!strcmp(name, "vkGetSwapchainGrallocUsage2ANDROID"))
    {
        g_usage2 = (PFN_Usage2)fn;
        return (PFN_vkVoidFunction)usage2;
    }
    if (!strcmp(name, "vkGetSwapchainGrallocUsage3ANDROID"))
    {
        g_usage3 = (PFN_Usage34)fn;
        return (PFN_vkVoidFunction)usage3;
    }
    if (!strcmp(name, "vkGetSwapchainGrallocUsage4ANDROID"))
    {
        g_usage4 = (PFN_Usage34)fn;
        return (PFN_vkVoidFunction)usage4;
    }
    return fn;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL wrap_gdpa(VkDevice device, const char* name)
{
    return wrap_usage_query(name, g_real_gdpa(device, name));
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL wrap_gipa(VkInstance instance, const char* name)
{
    PFN_vkVoidFunction fn = g_real_gipa(instance, name);
    if (!fn || !name)
        return fn;
    if (!strcmp(name, "vkGetDeviceProcAddr"))
    {
        g_real_gdpa = (PFN_vkGetDeviceProcAddr)fn;
        return (PFN_vkVoidFunction)wrap_gdpa;
    }
    if (!strcmp(name, "vkGetInstanceProcAddr"))
        return (PFN_vkVoidFunction)wrap_gipa;
    return wrap_usage_query(name, fn);
}

static int wrap_close(hw_device_t* device)
{
    (void)device;
    return g_real_device && g_real_device->common.close ? g_real_device->common.close(&g_real_device->common) : 0;
}

static int wrap_open(const hw_module_t* module, const char* id, hw_device_t** device)
{
    (void)module;
    hw_device_t* real = NULL;
    int r = g_real_module->methods->open(g_real_module, id, &real);
    if (r != 0 || !real)
        return r;
    g_real_device = (hwvulkan_device_t*)real;
    g_device = *g_real_device;
    g_device.common.module = &HMI;
    g_device.common.close = wrap_close;
    g_real_gipa = g_real_device->GetInstanceProcAddr;
    g_device.GetInstanceProcAddr = wrap_gipa;
    *device = &g_device.common;
    if (g_log)
        g_log("custom driver: the loader opened the driver through the gralloc usage wrapper");
    return 0;
}

static hw_module_methods_t g_methods = { wrap_open };

// self: this library's own handle (the loader then finds the HMI above); real_hmi: the driver's HMI.
EXPORT void vkdriverhook_wrap(void* self, void* real_hmi, void (*log)(const char* message))
{
    if (!self || !real_hmi)
        return;
    g_real_module = (const hw_module_t*)real_hmi;
    HMI = *g_real_module;
    HMI.methods = &g_methods;
    g_log = log;
    g_self = self;
}

// ------------------------------------------------------------------ the linker hooks

// A Vulkan HAL driver's file name: vulkan.<board>.so (not libvulkan.so, the loader itself).
static int is_driver(const char* filename)
{
    if (!filename)
        return 0;
    const char* base = strrchr(filename, '/');
    base = base ? base + 1 : filename;
    return strncmp(base, "vulkan.", 7) == 0;
}

// What the loader gets for the phone's driver: the driver, or this library when it wraps the driver.
static void* driver_handle(void)
{
    return g_self ? g_self : g_driver;
}

EXPORT void* android_dlopen_ext(const char* filename, int flags, const android_dlextinfo* info)
{
    if (g_driver && is_driver(filename))
        return driver_handle();
    return g_loader_dlopen_ext ? g_loader_dlopen_ext(filename, flags, info, (const void*)android_dlopen_ext) : NULL;
}

EXPORT void* android_load_sphal_library(const char* filename, int flags)
{
    if (g_driver && is_driver(filename))
        return driver_handle();
    return g_loader_dlopen_ext ? g_loader_dlopen_ext(filename, flags, NULL, (const void*)android_load_sphal_library)
                               : NULL;
}
