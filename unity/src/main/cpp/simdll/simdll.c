/*
 * libSimDLL.so for the native engine: Oxygen Not Included's simulation library.
 *
 * Klei ships SimDLL for Linux as x86-64 only, so there is nothing an ARM64 Mono could load from the
 * Linux game files. The macOS build of the same game version has an arm64 slice; this library loads
 * that file with the Mach-O loader (../macho) and forwards the game's P/Invokes to it.
 *
 * The file is the player's own, from the macOS depot of their copy of the game:
 *   OxygenNotIncluded.app/Contents/PlugIns/SimDLL.bundle/Contents/MacOS/SimDLL
 * The launcher passes its path in OXYDROID_SIMDLL_MACHO. It must come from the same game build as
 * the Linux files: the managed code and the simulation exchange binary messages.
 *
 * Forwarding needs no translation. Every function below takes integers, pointers and at most four
 * floats, all of which travel in registers, where Apple's and Android's ARM64 conventions agree.
 * The one callback the game hands over (SIM_Initialize) is register-only as well, so Mono's native
 * entry for the delegate is passed through as is.
 *
 * The list is the complete set of "SimDLL" DllImports in the game's managed code (Sim,
 * ConduitTemperatureManager, Klei.KProfilerPlugin), game build 26798728.
 */
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>

#include "../macho/logger.h"
#include "../macho/macho_loader.h"

#define LOG_TAG "oxydroid-simdll"

static macho_lib_t* simdll_lib;
static pthread_once_t simdll_once = PTHREAD_ONCE_INIT;

static void simdll_load(void) {
    const char* path = getenv("OXYDROID_SIMDLL_MACHO");
    if (!path || !path[0]) {
        LOGE("OXYDROID_SIMDLL_MACHO is not set: the macOS SimDLL file was not provided");
        return;
    }
    simdll_lib = macho_load(path);
    if (!simdll_lib) LOGE("the macOS SimDLL at %s could not be loaded (see the [macho] lines)", path);
    else LOGI("simulation library loaded from %s", path);
}

/* Without the simulation the game cannot run a single tick, and a wrong return value here would only
 * move the crash somewhere less readable: stop with the reason in the log. */
static void* simdll_resolve(const char* name) {
    pthread_once(&simdll_once, simdll_load);
    void* f = simdll_lib ? macho_dlsym(simdll_lib, name) : NULL;
    if (!f) {
        LOGE("SimDLL function %s is unavailable: stopping", name);
        abort();
    }
    return f;
}

#define SIM_EXPORT __attribute__((visibility("default")))
#define SIM_FORWARD(ret, name, params, args)                                         \
    SIM_EXPORT ret name params {                                                     \
        static ret (*target) params;                                                 \
        ret (*f) params = __atomic_load_n(&target, __ATOMIC_RELAXED);                \
        if (!f) {                                                                    \
            f = (ret (*) params)simdll_resolve(#name);                               \
            __atomic_store_n(&target, f, __ATOMIC_RELAXED);                          \
        }                                                                            \
        return f args;                                                               \
    }

/* Sim */
SIM_FORWARD(void, SIM_Initialize, (void* message_handler), (message_handler))
SIM_FORWARD(void, SIM_Shutdown, (void), ())
SIM_FORWARD(void*, SIM_HandleMessage, (int32_t id, int32_t length, uint8_t* msg), (id, length, msg))
SIM_FORWARD(void*, SIM_HandleMessages, (int32_t id, int32_t length, int32_t count, uint8_t* msg), (id, length, count, msg))
SIM_FORWARD(uint8_t*, SIM_BeginSave, (int32_t* size, int32_t x, int32_t y), (size, x, y))
SIM_FORWARD(void, SIM_EndSave, (void), ())
SIM_FORWARD(void, SIM_DebugCrash, (void), ())
SIM_FORWARD(char*, SYSINFO_Acquire, (void), ())
SIM_FORWARD(void, SYSINFO_Release, (void), ())

/* ConduitTemperatureManager (the bool arrives from Mono as a 4-byte 0 or 1) */
SIM_FORWARD(void, ConduitTemperatureManager_Initialize, (void), ())
SIM_FORWARD(void, ConduitTemperatureManager_Shutdown, (void), ())
SIM_FORWARD(int32_t, ConduitTemperatureManager_Add,
            (float temperature, float mass, int32_t element_hash, int32_t structure_handle,
             float heat_capacity, float thermal_conductivity, int32_t insulated),
            (temperature, mass, element_hash, structure_handle, heat_capacity, thermal_conductivity, insulated))
SIM_FORWARD(int32_t, ConduitTemperatureManager_Set,
            (int32_t handle, float temperature, float mass, int32_t element_hash),
            (handle, temperature, mass, element_hash))
SIM_FORWARD(void, ConduitTemperatureManager_Remove, (int32_t handle), (handle))
SIM_FORWARD(void*, ConduitTemperatureManager_Update, (float dt, void* conductivity_data), (dt, conductivity_data))
SIM_FORWARD(void, ConduitTemperatureManager_Clear, (void), ())

/* Klei.KProfilerPlugin (strings arrive as char*) */
SIM_FORWARD(void, kprofiler_load_plugin, (void), ())
SIM_FORWARD(void, kprofiler_unload_plugin, (void), ())
SIM_FORWARD(void, kprofiler_start_http_control_listener, (int32_t port), (port))
SIM_FORWARD(void, kprofiler_start_http_data_sender, (int32_t port), (port))
SIM_FORWARD(void, kprofiler_start_file_data_sender, (const char* filename), (filename))
SIM_FORWARD(void, kprofiler_flush_data_sender, (void), ())
SIM_FORWARD(void, kprofiler_stop_data_sender, (void), ())
SIM_FORWARD(void, kprofiler_start_profiling, (void), ())
SIM_FORWARD(void, kprofiler_stop_profiling, (int32_t broadcast_info), (broadcast_info))
SIM_FORWARD(uint64_t, kprofile_record_string, (const char* str), (str))
SIM_FORWARD(uint64_t, kprofiler_get_thread_uid, (void), ())
SIM_FORWARD(void, kprofiler_set_thread_info, (uint64_t thread_id, uint64_t name, uint64_t category), (thread_id, name, category))
SIM_FORWARD(void, kprofiler_begin_section, (uint64_t name, uint64_t category, int64_t gc_alloc_count), (name, category, gc_alloc_count))
SIM_FORWARD(void, kprofiler_end_section, (int64_t gc_alloc_count), (gc_alloc_count))
SIM_FORWARD(void, kprofiler_ping, (uint64_t name, uint64_t category, double value), (name, category, value))
SIM_FORWARD(void, kprofiler_counter, (uint64_t name, double value), (name, value))
