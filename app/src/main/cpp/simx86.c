/*
 * libsimx86.so: Oxygen Not Included's Linux simulation library (x86-64) run under box64, inside the
 * game's own ARM64 process.
 *
 * The native engine normally runs the arm64 slice of the macOS build's SimDLL (unity/.../simdll).
 * A player who has only the Linux build (Steam without the macOS depot, GOG) has
 * OxygenNotIncluded_Data/Plugins/x86_64/libSimDLL.so instead: this library loads that file with
 * box64 and gives libSimDLL.so (unity module) a native function for each of its exports, which
 * calls the x86-64 one through the emulator. The simulation then runs emulated (dynarec), the rest of
 * the game stays native.
 *
 * Here box64 is the guest, not the host: the process is the Android Unity player with ARM64 Mono,
 * and no x86 program runs. box64's initialize() is given libSimDLL.so as its "program": it maps it,
 * loads what it needs (libstdc++.so.6 and libgcc_s.so.1 from the launcher's x86-64 library bundle,
 * libc/libm/libpthread wrapped onto bionic), relocates it and runs its constructors. emulate() is
 * never called: the exports are looked up in box64's symbol map and called with RunFunctionFmt,
 * each calling thread with its own emulator state.
 *
 * Built from the same box64 objects as liboxydroidlinker, but WITHOUT linker.c: that library's
 * process-wide dlopen/dlsym would take over the game's own (see texconv.cpp), and this library is
 * loaded into the game process. It is not linked with -z global either, so box64's mmap/munmap do
 * not interpose on the player's.
 *
 * Signals: box64 installs its handlers for SIGSEGV/SIGBUS/SIGILL/SIGABRT, over those of Mono (which
 * uses SIGSEGV for null references in managed code) and the player. simx86_load() saves the old ones
 * and puts a dispatcher in front: a fault in box64's own code, in a dynarec block, or on a page
 * box64 protects goes to box64; any other to the handler that was there before.
 */
#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <ucontext.h>
#include <unistd.h>

#include <android/log.h>

#include "box64context.h"
#include "bridge.h"
#include "callback.h"
#include "librarian.h"
#include "wrapper.h"

/* Straight to logcat: logger.h's file copy lives in liboxydroid, which the game process does not load. */
#define LOG_TAG "oxydroid-simx86"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct elfheader_s elfheader_t;
int initialize(int argc, const char** argv, char** env, x64emu_t** emulator, elfheader_t** elfheader, int exec);
int GetGlobalSymbolStartEnd(lib_t* maplib, const char* name, uintptr_t* start, uintptr_t* end, elfheader_t* self,
                            int version, const char* vername, int veropt, void** elfsym);
void* FindDynablockFromNativeAddress(void* p);
int isprotectedDB(uintptr_t addr, size_t size);
void RunElfInit(elfheader_t* h, x64emu_t* emu);
extern size_t box64_pagesize;
extern char** environ;

/* ---- signals ---------------------------------------------------------------------------------- */

static const int routed_signals[] = { SIGSEGV, SIGBUS, SIGILL, SIGABRT };
#define ROUTED (sizeof(routed_signals) / sizeof(routed_signals[0]))
static struct sigaction previous_action[ROUTED];
static struct sigaction box64_action[ROUTED];
static uintptr_t self_start, self_end;

static int routed_index(int sig) {
    for (unsigned i = 0; i < ROUTED; i++)
        if (routed_signals[i] == sig) return (int)i;
    return -1;
}

static void call_action(const struct sigaction* a, int sig, siginfo_t* info, void* uc) {
    if (a->sa_flags & SA_SIGINFO) {
        if (a->sa_sigaction) a->sa_sigaction(sig, info, uc);
        return;
    }
    if (a->sa_handler == SIG_IGN) return;
    if (a->sa_handler == SIG_DFL) {
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }
    a->sa_handler(sig);
}

static int belongs_to_box64(int sig, siginfo_t* info, void* uc) {
    uintptr_t pc = (uintptr_t)((ucontext_t*)uc)->uc_mcontext.pc;
    if (pc >= self_start && pc < self_end) return 1;          // box64's interpreter, wrappers, helpers
    if (FindDynablockFromNativeAddress((void*)pc)) return 1;  // translated x86 code
    // A write to x86 code box64 has translated: it protects those pages to see the code change.
    if (sig == SIGSEGV && info && isprotectedDB((uintptr_t)info->si_addr, 1)) return 1;
    return 0;
}

static void dispatch_signal(int sig, siginfo_t* info, void* uc) {
    int i = routed_index(sig);
    if (i < 0) return;
    call_action(belongs_to_box64(sig, info, uc) ? &box64_action[i] : &previous_action[i], sig, info, uc);
}

static int find_self(struct dl_phdr_info* info, size_t size, void* data) {
    (void)size;
    uintptr_t probe = (uintptr_t)data;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_X)) continue;
        uintptr_t start = info->dlpi_addr + ph->p_vaddr, end = start + ph->p_memsz;
        if (probe >= start && probe < end) {
            self_start = start;
            self_end = end;
            return 1;
        }
    }
    return 0;
}

/* ---- loading ---------------------------------------------------------------------------------- */

static const char* self_path(void) {
    Dl_info info;
    if (!dladdr((void*)self_path, &info) || !info.dli_fname) return "/system/bin/sh";
    // Mapped straight from the APK: "<apk>!/lib/arm64-v8a/libsimx86.so". box64 only needs a real file.
    const char* bang = strchr(info.dli_fname, '!');
    if (!bang) return info.dli_fname;
    static char apk[4096];
    size_t len = (size_t)(bang - info.dli_fname);
    if (len >= sizeof(apk)) return "/system/bin/sh";
    memcpy(apk, info.dli_fname, len);
    apk[len] = 0;
    return apk;
}

static int loaded;

/**
 * Loads the x86-64 SimDLL at {@code path}; {@code libs} is the search path for the x86-64 libraries
 * it needs (BOX64_LD_LIBRARY_PATH). Returns 0 when it is ready. Called once, from libSimDLL.so.
 */
__attribute__((visibility("default")))
int simx86_load(const char* path, const char* libs) {
    if (loaded) return 0;
    if (libs && libs[0]) setenv("BOX64_LD_LIBRARY_PATH", libs, 1);
    setenv("BOX64_NOBANNER", "1", 0);
    if (!getenv("BOX64_LOG")) setenv("BOX64_LOG", "1", 1);

    // box64 refuses a "program" without the execute bit, and a file unpacked from the game's ZIP
    // into the instance has none. The file is the app's own.
    struct stat st;
    if (stat(path, &st) == 0 && !(st.st_mode & S_IXUSR) && chmod(path, st.st_mode | S_IXUSR) != 0)
        LOGE("could not make %s executable", path);

    box64_pagesize = sysconf(_SC_PAGESIZE);
    if (!box64_pagesize) box64_pagesize = 4096;

    for (unsigned i = 0; i < ROUTED; i++) sigaction(routed_signals[i], NULL, &previous_action[i]);

    // initialize() wants its argv as one block of memory (it compacts the strings in place) and
    // keeps it: neither is freed.
    const char* self = self_path();
    size_t a = strlen(self) + 1, b = strlen(path) + 1;
    char* strings = malloc(a + b);
    const char** argv = malloc(3 * sizeof(char*));
    if (!strings || !argv) return -1;
    memcpy(strings, self, a);
    memcpy(strings + a, path, b);
    argv[0] = strings;
    argv[1] = strings + a;
    argv[2] = NULL;

    x64emu_t* emu = NULL;
    elfheader_t* elf = NULL;
    // initialize() names the calling thread after its "program" (prctl PR_SET_NAME): this is one of
    // the game's own threads, whose name the logs and crash reports should keep.
    char thread_name[17] = { 0 };
    prctl(PR_GET_NAME, thread_name);
    LOGI("loading %s under box64 (x86-64 libraries from %s)", path, libs ? libs : "(none)");
    int r = initialize(2, argv, environ, &emu, &elf, 0);
    if (thread_name[0]) prctl(PR_SET_NAME, thread_name);
    if (r != 0 || !my_context) {
        LOGE("box64 could not load %s (%d): see the box64 lines in Player.log", path, r);
        return -1;
    }
    // The "program"'s own constructors (its static tables, gElementIndices among them): box64 runs
    // a program's from its start routine (__libc_start_main), which never runs here. Its libraries'
    // ran inside initialize().
    RunElfInit(elf, emu);

    dl_iterate_phdr(find_self, (void*)(uintptr_t)self_path);
    struct sigaction dispatcher;
    memset(&dispatcher, 0, sizeof(dispatcher));
    dispatcher.sa_sigaction = dispatch_signal;
    dispatcher.sa_flags = SA_SIGINFO | SA_RESTART | SA_NODEFER | SA_ONSTACK;
    sigemptyset(&dispatcher.sa_mask);
    for (unsigned i = 0; i < ROUTED; i++) {
        sigaction(routed_signals[i], NULL, &box64_action[i]);
        sigaction(routed_signals[i], &dispatcher, NULL);
    }
    loaded = 1;
    LOGI("simulation library loaded under box64; signals routed (box64 code %p-%p)",
         (void*)self_start, (void*)self_end);
    return 0;
}

static uintptr_t x86_symbol(const char* name) {
    uintptr_t start = 0, end = 0;
    if (!loaded || !GetGlobalSymbolStartEnd(my_context->maplib, name, &start, &end, NULL, -1, NULL, 0, NULL))
        return 0;
    return start;
}

/* ---- the exports ------------------------------------------------------------------------------ */

/* One native function per SimDLL export (the list and the types are those of ../../../../unity/src/
 * main/cpp/simdll/simdll.c). RunFunctionFmt's letters: i int32, p pointer, f float, d double,
 * U uint64, I int64. */
#define X86_FN(name) static uintptr_t x86_addr_##name;
#define X86_CALL(name, fmt, ...) RunFunctionFmt(x86_addr_##name, fmt, ##__VA_ARGS__)
#define X86_VOID(name, params, fmt, ...) \
    X86_FN(name) static void x86_##name params { X86_CALL(name, fmt, ##__VA_ARGS__); }
#define X86_RET(ret, name, params, fmt, ...) \
    X86_FN(name) static ret x86_##name params { return (ret)X86_CALL(name, fmt, ##__VA_ARGS__); }

/* SIM_Initialize hands over the game's message handler, a native (Mono) function the x86 code will
 * call: it gets a bridge, x86 code that calls it. pFpppppp passes six integer registers and returns
 * one, which fits a handler of integers and pointers (int (int message_id, IntPtr data)). */
static uintptr_t x86_addr_SIM_Initialize;
static void x86_SIM_Initialize(void* handler) {
    uintptr_t bridge = handler ? AddCheckBridge(my_context->system, pFpppppp, handler, 0, "SimMessageHandler") : 0;
    RunFunctionFmt(x86_addr_SIM_Initialize, "p", (void*)bridge);
}
X86_VOID(SIM_Shutdown, (void), "")
X86_RET(void*, SIM_HandleMessage, (int32_t id, int32_t length, uint8_t* msg), "iip", id, length, msg)
X86_RET(void*, SIM_HandleMessages, (int32_t id, int32_t length, int32_t count, uint8_t* msg), "iiip", id, length, count, msg)
X86_RET(uint8_t*, SIM_BeginSave, (int32_t* size, int32_t x, int32_t y), "pii", size, x, y)
X86_VOID(SIM_EndSave, (void), "")
X86_VOID(SIM_DebugCrash, (void), "")
X86_RET(char*, SYSINFO_Acquire, (void), "")
X86_VOID(SYSINFO_Release, (void), "")

X86_VOID(ConduitTemperatureManager_Initialize, (void), "")
X86_VOID(ConduitTemperatureManager_Shutdown, (void), "")
X86_RET(int32_t, ConduitTemperatureManager_Add,
        (float temperature, float mass, int32_t element_hash, int32_t structure_handle, float heat_capacity,
         float thermal_conductivity, int32_t insulated),
        "ffiiffi", temperature, mass, element_hash, structure_handle, heat_capacity, thermal_conductivity, insulated)
X86_RET(int32_t, ConduitTemperatureManager_Set, (int32_t handle, float temperature, float mass, int32_t element_hash),
        "iffi", handle, temperature, mass, element_hash)
X86_VOID(ConduitTemperatureManager_Remove, (int32_t handle), "i", handle)
X86_RET(void*, ConduitTemperatureManager_Update, (float dt, void* conductivity_data), "fp", dt, conductivity_data)
X86_VOID(ConduitTemperatureManager_Clear, (void), "")

X86_VOID(kprofiler_load_plugin, (void), "")
X86_VOID(kprofiler_unload_plugin, (void), "")
X86_VOID(kprofiler_start_http_control_listener, (int32_t port), "i", port)
X86_VOID(kprofiler_start_http_data_sender, (int32_t port), "i", port)
X86_VOID(kprofiler_start_file_data_sender, (const char* filename), "p", filename)
X86_VOID(kprofiler_flush_data_sender, (void), "")
X86_VOID(kprofiler_stop_data_sender, (void), "")
X86_VOID(kprofiler_start_profiling, (void), "")
X86_VOID(kprofiler_stop_profiling, (int32_t broadcast_info), "i", broadcast_info)
X86_RET(uint64_t, kprofile_record_string, (const char* str), "p", str)
X86_RET(uint64_t, kprofiler_get_thread_uid, (void), "")
X86_VOID(kprofiler_set_thread_info, (uint64_t thread_id, uint64_t name, uint64_t category), "UUU", thread_id, name, category)
X86_VOID(kprofiler_begin_section, (uint64_t name, uint64_t category, int64_t gc_alloc_count), "UUI", name, category, gc_alloc_count)
X86_VOID(kprofiler_end_section, (int64_t gc_alloc_count), "I", gc_alloc_count)
X86_VOID(kprofiler_ping, (uint64_t name, uint64_t category, double value), "UUd", name, category, value)
X86_VOID(kprofiler_counter, (uint64_t name, double value), "Ud", name, value)

struct x86_export { const char* name; uintptr_t* addr; void* native; };
#define E(name) { #name, &x86_addr_##name, (void*)x86_##name }
static const struct x86_export exports[] = {
    E(SIM_Initialize), E(SIM_Shutdown), E(SIM_HandleMessage), E(SIM_HandleMessages), E(SIM_BeginSave),
    E(SIM_EndSave), E(SIM_DebugCrash), E(SYSINFO_Acquire), E(SYSINFO_Release),
    E(ConduitTemperatureManager_Initialize), E(ConduitTemperatureManager_Shutdown), E(ConduitTemperatureManager_Add),
    E(ConduitTemperatureManager_Set), E(ConduitTemperatureManager_Remove), E(ConduitTemperatureManager_Update),
    E(ConduitTemperatureManager_Clear),
    E(kprofiler_load_plugin), E(kprofiler_unload_plugin), E(kprofiler_start_http_control_listener),
    E(kprofiler_start_http_data_sender), E(kprofiler_start_file_data_sender), E(kprofiler_flush_data_sender),
    E(kprofiler_stop_data_sender), E(kprofiler_start_profiling), E(kprofiler_stop_profiling),
    E(kprofile_record_string), E(kprofiler_get_thread_uid), E(kprofiler_set_thread_info),
    E(kprofiler_begin_section), E(kprofiler_end_section), E(kprofiler_ping), E(kprofiler_counter),
};
#undef E

/**
 * The native function for the SimDLL export {@code name}: it calls the x86-64 one. NULL when the
 * library is not loaded, the name is not one of the exports above, or libSimDLL.so lacks it.
 */
__attribute__((visibility("default")))
void* simx86_resolve(const char* name) {
    for (unsigned i = 0; i < sizeof(exports) / sizeof(exports[0]); i++) {
        if (strcmp(exports[i].name, name) != 0) continue;
        uintptr_t addr = __atomic_load_n(exports[i].addr, __ATOMIC_ACQUIRE);
        if (!addr) {
            addr = x86_symbol(name);
            if (!addr) {
                LOGE("the x86-64 SimDLL has no %s", name);
                return NULL;
            }
            __atomic_store_n(exports[i].addr, addr, __ATOMIC_RELEASE);
        }
        return exports[i].native;
    }
    LOGE("%s is not a SimDLL export known to the x86-64 forwarder", name);
    return NULL;
}
