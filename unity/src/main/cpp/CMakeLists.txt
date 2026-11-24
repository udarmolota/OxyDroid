cmake_minimum_required(VERSION 3.22.1)
project(oxydroid_native_unity C)

# il2mono: libil2cpp.so for Unity's Android player, backed by OxyDroid's ARM64 Mono
# (libmonobdwgc-2.0.so in the app's native libs, loaded at run time). gen/stubs.c holds the weak
# "UNIMPLEMENTED" default for every il2cpp_* API function; regenerate it with
# il2mono/gen_stubs.py from the IL2CPP sources of a Unity Gradle export when the Unity version changes.
add_library(il2cpp SHARED
    il2mono/core.c
    il2mono/api_init.c
    il2mono/api_runtime.c
    il2mono/api_more.c
    il2mono/pad.c
    il2mono/config.c
    il2mono/bepinex.c
    il2mono/monomod.c
    il2mono/gen/stubs.c)
target_compile_options(il2cpp PRIVATE -std=c11 -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(il2cpp log dl)
target_link_options(il2cpp PRIVATE -Wl,--no-undefined)

# ARM64 stand-in for Valheim's x86_64 DiskSpacePlugin (checked before every save).
add_library(DiskSpacePlugin SHARED plugins/disk_space_plugin.c)
target_compile_options(DiskSpacePlugin PRIVATE -fvisibility=hidden -Wall)

# Unity's Swappy wrapper (frame pacing), loaded by libunity when present. Its source comes with Unity's
# Android Support package and is copied by prepare_unity_module.sh into unity/prepared/swappy; built as
# Unity's own CMake file for it does.
set(SWAPPY_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../prepared/swappy)
if(EXISTS ${SWAPPY_SRC}/UnitySwappyWrapper.cpp)
    enable_language(CXX)
    find_package(games-frame-pacing REQUIRED CONFIG)
    # Unity's entry point is renamed and fronted by swappy_switch.cpp (a OXYDROID_NO_SWAPPY test switch).
    set_source_files_properties(${SWAPPY_SRC}/UnitySwappyWrapper.cpp PROPERTIES
        COMPILE_DEFINITIONS "UnitySwappyWrapperInit=UnitySwappyWrapperInitUnity")
    add_library(swappywrapper SHARED ${SWAPPY_SRC}/UnitySwappyWrapper.cpp swappy_switch.cpp)
    target_compile_definitions(swappywrapper PRIVATE EXTERNAL_FRAME_PACING_CODE)
    target_include_directories(swappywrapper PRIVATE ${SWAPPY_SRC})
    target_link_options(swappywrapper PRIVATE -Wl,--wrap=__android_log_print)
    target_link_libraries(swappywrapper android games-frame-pacing::swappy_static log)
endif()

# libvkshim.so: an observing Vulkan shim. libunity's dlopen("libvulkan.so") is patched to this name
# (patch_libunity_target.py); it depends on the system libvulkan.so, defines the resolvers and the
# frame-delivery calls, and logs them (tag VKSHIM) without changing anything.
# With OXYDROID_VULKAN_DRIVER it runs the engine on another Vulkan driver through a private copy of the
# system loader (vkshim/driver.c, libvkdriverhook.so; linker namespaces from the app's liblinkernsbypass).
enable_language(CXX)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../app/src/main/cpp/liblinkernsbypass
                 ${CMAKE_CURRENT_BINARY_DIR}/linkernsbypass)
add_library(vkshim SHARED vkshim/vkshim.c vkshim/driver.c vkshim/perf.c)
target_compile_options(vkshim PRIVATE -std=c11 -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(vkshim vulkan log dl linkernsbypass)
set_target_properties(vkshim PROPERTIES LINKER_LANGUAGE CXX)

add_library(vkdriverhook SHARED vkshim/driver_hook.c)
target_compile_options(vkdriverhook PRIVATE -fvisibility=hidden -Wall -Wextra)
target_link_options(vkdriverhook PRIVATE "-Wl,-z,global")

# libSimDLL.so: Oxygen Not Included's simulation library. The Linux game ships it as x86-64 only; this
# one loads the arm64 slice of the macOS build (the player's own file) with the Mach-O loader in
# macho/ and forwards the game's P/Invokes to it (simdll/simdll.c).
add_library(SimDLL SHARED
    simdll/simdll.c
    macho/macho_loader.c
    macho/macho_darwin.c
    macho/macho_unwind.c
    macho/macho_string.cpp)
target_compile_options(SimDLL PRIVATE -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter -Wno-unused-function)
set_source_files_properties(simdll/simdll.c macho/macho_loader.c macho/macho_darwin.c macho/macho_unwind.c
    PROPERTIES COMPILE_OPTIONS "-std=gnu11")
target_compile_features(SimDLL PRIVATE cxx_std_17)
target_link_libraries(SimDLL log dl m)
target_link_options(SimDLL PRIVATE -Wl,--no-undefined)

# The C++ exception runtime the dylib is bound to: our own libc++abi + libunwind, whole, with the
# dynamic-FDE registration the loader needs (macho/macho_eh.c). Kept out of libSimDLL.so so it can never
# shadow libc++_shared's runtime for anyone else; only oxydroid_macho_eh_* is exported. The archive
# paths come from the toolchain itself.
execute_process(COMMAND ${CMAKE_C_COMPILER} --target=${CMAKE_C_COMPILER_TARGET} -print-file-name=libunwind.a
                OUTPUT_VARIABLE MACHO_EH_LIBUNWIND OUTPUT_STRIP_TRAILING_WHITESPACE)
execute_process(COMMAND ${CMAKE_C_COMPILER} --target=${CMAKE_C_COMPILER_TARGET} -print-file-name=libc++abi.a
                OUTPUT_VARIABLE MACHO_EH_LIBCXXABI OUTPUT_STRIP_TRAILING_WHITESPACE)
if(NOT EXISTS "${MACHO_EH_LIBCXXABI}" OR NOT EXISTS "${MACHO_EH_LIBUNWIND}")
    message(FATAL_ERROR "Mach-O exception runtime archives not found in the selected NDK")
endif()
add_library(oxydroid_macho_eh SHARED macho/macho_eh.c)
add_dependencies(SimDLL oxydroid_macho_eh)
target_link_libraries(oxydroid_macho_eh PRIVATE c++_shared log)
target_link_options(oxydroid_macho_eh PRIVATE
    "-Wl,--whole-archive" "${MACHO_EH_LIBCXXABI}" "${MACHO_EH_LIBUNWIND}" "-Wl,--no-whole-archive"
    "-Wl,--exclude-libs,ALL")
