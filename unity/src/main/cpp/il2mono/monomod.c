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
