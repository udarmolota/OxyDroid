package com.oxydroid;

public class C {

    // From the package name, as the manifest's ${applicationId}: the debug build is com.oxydroid.nativeunity,
    // and a fixed "com.oxydroid" here sent Manage storage to the release app's provider (or to none).
    public static final String STORAGE_PROVIDER_AUTHORITY = BuildConfig.APPLICATION_ID + ".STORAGE_PROVIDER_AUTHORITY";

    public static class deps {
        public static final String ROOT = "dependencies";
        // Content revision of assets/bundles/libs.tar.xz. BUMP whenever the bundle changes so existing
        // installs (which already have areDependenciesInstalled=true) re-extract it on next app open and
        // pick up added/updated libs — a plain boolean flag would leave updaters on the old libs.
        // Re-extraction only overwrites members by name: a file dropped from the bundle stays on an
        // install that already had it (harmless — nothing loads it).
        //   v1 (OxyDroid 0.1.0, numbering restarted with the version) = the RimDroid-era bundle minus
        //        13 libraries nothing loads: the Java-game leftovers inherited from Zomdroid
        //        (libjassimp64, libsqlitejdbc, lwjgl-3.2.3 and lwjgl-3.3.6, libjniwrapper,
        //        libpthread_wrapper), libjemalloc with its only dependant libc++_shared, and an
        //        ARM64 glibc/ that referenced only itself. Checked two ways before removal: no
        //        reference anywhere in the code, and no DT_NEEDED on them from any bundled library.
        //        Recompressed at xz -9e on the way (4.7 MB of the 7.4 MB saved is that alone).
        //   v2 = + libvulkan_panvk_frist370.so: FristOneRR PanVK v3.7.0 (Mesa 27.0.0-devel, kbase,
        //        built for Mali-G57), github.com/FristOneRR/FristOneRR-Panvk-Driver release v3.7.0,
        //        library SHA-256 e6e92930…a6eda12801; with its Mesa licence as LICENSE-PanVK-FristOneRR.txt.
        public static final int BUNDLE_VERSION = 2;
        // x86_64 game libs (libgcc_s.so.1, libstdc++, the X11 client libs, etc.)
        public static final String LIBS_LINUX_X86_64 = ROOT + "/linux-x86_64";
        // Android ARM64 renderer libs — all in one flat directory
        public static final String LIBS_ANDROID_ARM64 = ROOT + "/android-arm64-v8a";
        public static final String LIBS_GL4ES  = LIBS_ANDROID_ARM64;
        public static final String LIBS_ZINK   = LIBS_ANDROID_ARM64;
        // Custom Vulkan driver (user-supplied)
        public static final String CUSTOM_DRIVER_FILENAME = "custom_driver.so";
        public static final String CUSTOM_DRIVER = LIBS_ANDROID_ARM64 + "/" + CUSTOM_DRIVER_FILENAME;
    }

    public static class assets {
        public static final String BUNDLES = "bundles";
        public static final String BUNDLES_LIBS = BUNDLES + "/libs.tar.xz";
    }

    public static class mime {
        /** SAF filter for installing a game / DLC / mod: ZIP only, deliberately. A bare GOG {@code .sh}
         *  installer is NOT offered — its reported type varies wildly by document provider and most
         *  file explorers won't hand one over at all, so the supported workflow is to WRAP the .sh in
         *  a zip. octet-stream is included
         *  because some providers report zips as that. */
        public static final String[] GAME_ARCHIVE = {
            "application/zip", "application/x-zip-compressed", "application/octet-stream",
        };
    }

    public static class shprefs {
        public static final String NAME = "com.oxydroid.PREFS";

        public static class keys {
            public static final String LAUNCHER_VERSION          = "launcherVersion";
            public static final String GAME_INSTANCES            = "gameInstances";
            public static final String LAUNCHER_PREFS            = "launcherPrefs";
            public static final String ARE_DEPENDENCIES_INSTALLED = "areDependenciesInstalled";
            public static final String DEPENDENCIES_BUNDLE_VERSION = "dependenciesBundleVersion";
            public static final String IS_LEGAL_NOTICE_ACCEPTED  = "isLegalNoticeAccepted";
        }
    }

    public static class files {
        public static final String RIMWORLD_BIN = "RimWorldLinux";
    }
}
