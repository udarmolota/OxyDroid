// MonoMod (the detour engine inside Harmony) on the native engine.
//
// Oxygen Not Included ships Harmony 2.4 for its own patches and for mods. MonoMod's platform
// detection reports "Android" when both /data and /system/build.prop exist, and then throws
// NotImplementedException for that OS before it patches anything. With that one path hidden it sees
// Linux on Arm64, which it supports. File.Exists reaches the file system through libmono-native's
// SystemNative_Stat2 / SystemNative_LStat2; Mono's config sends exactly those two here
// (NativeEngine.prepareMonoConfig), every other System.Native function goes to the library as before.
// The box64 engine does the same in box64/src/wrapped/wrappedlibmonobdwgc.c.
#include <dlfcn.h>
#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "il2mono.h"

typedef int32_t (*stat2_fn)(const char* path, void* output);

static stat2_fn real_stat2, real_lstat2;

static stat2_fn mono_native(const char* name)
{
    static void* lib;
    if (!lib) lib = dlopen("libmono-native.so", RTLD_NOW);
    stat2_fn f = lib ? (stat2_fn)dlsym(lib, name) : NULL;
    if (!f) LOGE("libmono-native.so has no %s: file checks from managed code will fail", name);
    return f;
}

static int hidden(const char* path)
{
    static int reported;
    if (!path || strcmp(path, "/system/build.prop")) return 0;
    if (!reported)
    {
        reported = 1;
        LOGI("hiding /system/build.prop from managed code (MonoMod platform detection)");
    }
    return 1;
}

IL2MONO_API int32_t il2mono_stat2(const char* path, void* output)
{
    if (hidden(path)) { errno = ENOENT; return -1; }
    if (!real_stat2 && !(real_stat2 = mono_native("SystemNative_Stat2"))) { errno = ENOSYS; return -1; }
    return real_stat2(path, output);
}

IL2MONO_API int32_t il2mono_lstat2(const char* path, void* output)
{
    if (hidden(path)) { errno = ENOENT; return -1; }
    if (!real_lstat2 && !(real_lstat2 = mono_native("SystemNative_LStat2"))) { errno = ENOSYS; return -1; }
    return real_lstat2(path, output);
}

// glibc's ABI for the three libc imports of MonoMod's Interop.Unix whose bionic counterparts differ.
// Without them its type initializer throws and Harmony patches nothing: every mod with a DLL fails
// ("The type initializer for 'MonoMod.Core.Interop.Unix' threw an exception", S25 2026-11-22). Mono's
// config sends exactly these three of "libc" here (NativeEngine.prepareMonoConfig); MonoMod's other
// libc imports (read, write, pipe2, close, munmap, mprotect, mincore) have the same ABI in bionic and
// stay with libc.so. The same shims as StormDroid's il2mono/bepinex.c and RimDroid's rdglibc.c.

// bionic calls it __errno.
IL2MONO_API int* il2mono_errno_location(void)
{
    return &errno;
}

// glibc numbers sysconf's names differently from bionic: translate the ones desktop code asks for
// (MonoMod asks for the page size, glibc's 30).
IL2MONO_API long il2mono_sysconf(int name)
{
    switch (name)
    {
        case 0: return sysconf(_SC_ARG_MAX);
        case 2: return sysconf(_SC_CLK_TCK);
        case 4: return sysconf(_SC_OPEN_MAX);
        case 30: return sysconf(_SC_PAGESIZE);
        case 31: return sysconf(_SC_RTSIG_MAX);
        case 83: return sysconf(_SC_NPROCESSORS_CONF);
        case 84: return sysconf(_SC_NPROCESSORS_ONLN);
        case 85: return sysconf(_SC_PHYS_PAGES);
        case 86: return sysconf(_SC_AVPHYS_PAGES);
        default:
            LOGW("libc sysconf(%d): glibc name not translated, returning -1", name);
            errno = EINVAL;
            return -1;
    }
}

// MonoMod declares the offset as a 32-bit int: the upper half of the register is not ours to trust.
IL2MONO_API void* il2mono_mmap(void* addr, size_t length, int prot, int flags, int fd, int offset)
{
    return mmap(addr, length, prot, flags, fd, (off_t)offset);
}
