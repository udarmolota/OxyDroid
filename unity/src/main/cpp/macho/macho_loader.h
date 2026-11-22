#ifndef OXYDROID_MACHO_LOADER_H
#define OXYDROID_MACHO_LOADER_H
/*
 * In-process loader for a macOS ARM64 dylib. Taken from Zomdroid (where it loads Project Zomboid's
 * JNI libraries) and extended for Oxygen Not Included's simulation library.
 *
 * Why this exists: Klei ships SimDLL for Linux as x86-64 only. The macOS build of the same game
 * version carries a plain arm64 slice with libc/libc++ imports only - no Objective-C, no pointer
 * authentication, classic dyld info. So: map the arm64 slice, apply dyld's rebase/bind opcodes
 * against bionic, libc++_shared and our Darwin shims, read the export trie, run the static
 * constructors, and the game's own native code runs on the phone's CPU.
 *
 * Supported beyond Zomdroid's loader: thread-local variables (__thread_vars), and the Darwin-only
 * imports in macho_darwin.c and macho_string.cpp.
 *
 * Deliberately not supported (each is a one-line "rejected" in the log, never a crash): arm64e,
 * chained fixups, thread-local initializer functions, Objective-C, dependencies other than
 * libc++/libSystem, and any import nothing here can provide.
 */
#include <stddef.h>
#include <stdint.h>

typedef struct macho_lib macho_lib_t;

/* Loads the arm64 slice of the dylib at path. Prints its own "[macho] ..." lines (loaded or
 * rejected, with the reason); returns NULL on any rejection. The mapping is never freed. */
macho_lib_t* macho_load(const char* path);

/* Export lookup by C name, i.e. without the Mach-O leading underscore. NULL when not exported. */
void* macho_dlsym(macho_lib_t* lib, const char* name);

/* File name the library was loaded from (for log lines). */
const char* macho_lib_name(const macho_lib_t* lib);

/* True when per-instruction detail should be logged: OXYDROID_NATIVE_VERBOSE=1. */
int macho_verbose(void);

#endif /* OXYDROID_MACHO_LOADER_H */
