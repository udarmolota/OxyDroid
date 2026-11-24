package com.oxydroid.game;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Native engine (experimental): runs the game on Unity's own ARM64 Android player instead of the Linux
 * player under box64, with the game's C# on OxyDroid's ARM64 Mono through il2mono. Only present when
 * the APK was built with the ":unity" module (tools/unity-native/prepare_unity_module.sh).
 *
 * Preparing an instance builds nothing big: the player's data (globalgamemanagers, levels, assets) goes
 * into one stored zip the player mounts in place of its APK; asset bundles, saves and the game's
 * managed assemblies are read straight from the instance. Research and plan:
 * docs/research/unity-native-integration-plan.md.
 */
public final class NativeEngine {
    private static final String TAG = "OxyDroid/NativeEngine";
    private static final String ACTIVITY = "com.oxydroid.nativeunity.NativeUnityActivity";
    private static final String EXTRA_DATA_ARCHIVE = "oxydroid.dataArchive";
    private static final String EXTRA_ENV = "oxydroid.env";
    private static final String EXTRA_OVERLAY = "oxydroid.overlay";
    /** The instance being played (its controls layout and settings), read by NativeGameOverlay. */
    public static final String EXTRA_INSTANCE = "oxydroid.instance";
    /** Display mode (refresh rate) the frame-rate mode picked, 0 = leave the panel alone; for NativeGameOverlay. */
    public static final String EXTRA_DISPLAY_MODE = "oxydroid.displayMode";
    private static final String EXTRA_BACKGROUND_TEXT = "oxydroid.backgroundText";

    /** Player files packaged in the APK; they win over the game's copies of the same names. */
    private static final String PLAYER_ASSETS = "bin/Data";
    /** Android builds of engine/package assemblies that replace the game's Linux copies. */
    private static final String MANAGED_OVERRIDES_ASSETS = "oxydroid_native/Managed";

    /** Unity BuildTarget: the Android player only loads serialized files tagged as Android. */
    private static final int TARGET_ANDROID = 13;
    private static final int TARGET_LINUX64 = 24;

    private NativeEngine() {}

    /** Whether this APK carries the native player module. */
    public static boolean isAvailable() {
        try {
            Class.forName(ACTIVITY);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Prepares the instance (only what changed) and returns the intent that starts the player. */
    public static Intent prepare(Context context, GameInstance instance) throws IOException {
        return prepare(context, instance, null);
    }

    /**
     * The game's Steam achievements and stats on this phone, kept by the libsteam_api stub
     * (OXYDROID_STEAM_STATS) and sent to the account by SteamStatsSync.
     */
    public static File steamStatsFile(GameInstance instance) {
        return new File(new File(instance.getGamePath(), "native_engine"), "steam_stats.txt");
    }

    /** The note the game leaves when it asks to be restarted (bridge RestartPatch.cs). */
    public static File restartFlag(GameInstance instance) {
        return new File(new File(instance.getGamePath(), "native_engine"), "restart_requested");
    }

    /** Where the crossplay sign-in leaves the Steam ticket for the game (read once, then deleted). */
    public static File steamTicketFile(GameInstance instance) {
        return new File(new File(instance.getGamePath(), "native_engine"), "steam_ticket.bin");
    }

    /**
     * As above; {@code steam} is the launch's Steam sign-in (null = offline as always). With a ticket
     * (crossplay) the game's Steam stub answers with the real SteamID, name and session ticket, and
     * Valheim logs in to PlayFab at start (ShouldTryAutoLogin). Without one a ticket left from an
     * earlier launch is removed.
     */
    public static Intent prepare(Context context, GameInstance instance, com.oxydroid.SteamTicketLogin.Result steam)
            throws IOException {
        return prepare(context, instance, steam, null);
    }

    /**
     * As above; {@code textures} hears about the one-time conversion of the game's textures to ASTC,
     * which a GPU without BC formats needs (TextureConverter) and which takes minutes.
     */
    public static Intent prepare(Context context, GameInstance instance, com.oxydroid.SteamTicketLogin.Result steam,
                                 TextureConverter.Progress textures) throws IOException {
        File gameDir = new File(instance.getGamePath());
        File dataDir = new File(gameDir, instance.getDataDirectoryName());
        if (!new File(dataDir, "globalgamemanagers").isFile())
            throw new IOException("game data not found in " + dataDir);
        // The simulation library exists for Linux as x86-64 only; the native engine runs the arm64 slice
        // of the macOS build's (unity/src/main/cpp/simdll). Say so here, not at the game's first tick.
        File simdll = macSimDll(gameDir);
        // OXYDROID_SIMDLL=x86 in the environment field (a test for now): the Linux build's own x86-64
        // SimDLL, run under box64 inside the game (app/src/main/cpp/simx86.c), instead of the macOS one.
        File simdllX86 = null;
        // Without the macOS file the Linux one runs emulated (the launcher has said so before the launch).
        if ("x86".equals(userEnvironmentValue(instance.settings(), "OXYDROID_SIMDLL"))
                || (!simdll.isFile() && linuxSimDll(dataDir).isFile())) {
            simdllX86 = linuxSimDll(dataDir);
            if (!simdllX86.isFile())
                throw new IOException("the Linux SimDLL is missing: " + simdllX86);
        } else if (!simdll.isFile())
            throw new IOException("the macOS SimDLL is missing: copy OxygenNotIncluded.app/Contents/PlugIns/"
                    + "SimDLL.bundle/Contents/MacOS/SimDLL from the macOS version of the same game build to "
                    + simdll);
        File workDir = new File(gameDir, "native_engine");
        if (!workDir.isDirectory() && !workDir.mkdirs()) throw new IOException("cannot create " + workDir);

        // Before the data archive is built from these files: it then takes the converted ones.
        // OXYDROID_FAKE_MALI=1 in the environment field: a test of the no-BC path on a GPU that has
        // BC. The textures are converted as on a Mali, the cinematics are off as there, and the Vulkan
        // shim (vkshim.c, which reads the variable itself) hides the BC formats from the engine.
        String astc = userEnvironmentValue(instance.settings(), TextureConverter.ENV);
        if (astc == null && fakeMali(instance.settings())) astc = "1";
        if (TextureConverter.wanted(astc)) {
            for (String line : TextureConverter.convertIfNeeded(dataDir, workDir, textures))
                com.oxydroid.LauncherLog.line("textures: " + line);
        }

        long apkStamp = apkStamp(context);
        File overrides = extractManagedOverrides(context, apkStamp);
        File archive = buildDataArchive(context, dataDir, workDir, apkStamp);
        File etc = prepareMonoConfig(dataDir, workDir);
        seedGameDefaults(instance.getUserDataDir());

        List<String> env = new ArrayList<>();
        env.add("OXYDROID_IL2MONO_MANAGED=" + overrides.getAbsolutePath() + ":" + new File(dataDir, "Managed").getAbsolutePath());
        env.add("OXYDROID_IL2MONO_ETC=" + etc.getAbsolutePath());
        env.add("OXYDROID_IL2MONO_CWD=" + gameDir.getAbsolutePath()); // steam_appid.txt lives here
        env.add("OXYDROID_IL2MONO_STREAMING_ASSETS=" + new File(dataDir, "StreamingAssets").getAbsolutePath());
        env.add("OXYDROID_IL2MONO_PERSISTENT_DATA=" + instance.getUserDataDir().getAbsolutePath());
        if (simdllX86 != null) {
            env.add("OXYDROID_SIMDLL_X86=" + simdllX86.getAbsolutePath());
            env.add("OXYDROID_SIMDLL_X86_LIBS=" + com.oxydroid.AppStorage.requireSingleton().getLibsLinuxX86Path());
            Log.i(TAG, "simulation: the Linux x86-64 SimDLL under box64 (emulated)");
        } else {
            env.add("OXYDROID_SIMDLL_MACHO=" + simdll.getAbsolutePath());
        }
        // The game restarts itself after a change of mods; the bridge (RestartPatch.cs) leaves this
        // note instead, and the launcher starts the game again when it finds it. A note from an
        // earlier run that was never picked up must not restart this one.
        File restart = restartFlag(instance);
        //noinspection ResultOfMethodCallIgnored
        restart.delete();
        env.add("OXYDROID_RESTART_FLAG=" + restart.getAbsolutePath());
        // Mods installed since the last start, switched on by the bridge (OniMods.markForEnable).
        env.add("OXYDROID_MODS_ENABLE=" + OniMods.enableList(instance.getUserDataDir()).getAbsolutePath());

        addMods(context, instance, gameDir, dataDir, env);
        env.add("OXYDROID_STEAM_STATS=" + steamStatsFile(instance).getAbsolutePath());
        // The DLCs whose content was downloaded with an account that owns them (GameInstance.DLC_LIST):
        // the Steam stand-in says they are owned and installed, and the game unlocks them.
        List<Integer> dlcs = instance.dlcs();
        if (!dlcs.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (Integer id : dlcs) ids.append(ids.length() == 0 ? "" : ",").append(id);
            env.add("OXYDROID_STEAM_DLCS=" + ids);
            Log.i(TAG, "DLCs: " + ids);
        }
        boolean crossplay = steam != null && steam.ticketFile != null;
        if (crossplay) {
            env.add("OXYDROID_STEAM_TICKET=" + steam.ticketFile.getAbsolutePath());
            env.add("OXYDROID_STEAM_ID=" + Long.toUnsignedString(steam.steamId));
            if (steam.persona != null && !steam.persona.isEmpty()) env.add("OXYDROID_STEAM_PERSONA=" + steam.persona);
        } else {
            steamTicketFile(instance).delete();
        }
        int displayMode = graphicsSettings(context, instance.settings(), env);
        addUserEnvironment(context, instance.settings(), env);
        com.oxydroid.LauncherLog.line("native engine environment: " + launchSummary(env));
        VideoPatch.restore(gameDir);   // clips an earlier test build made unreadable

        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), ACTIVITY);
        intent.putExtra(EXTRA_DATA_ARCHIVE, archive.getAbsolutePath());
        intent.putExtra(EXTRA_ENV, env.toArray(new String[0]));
        intent.putExtra(EXTRA_OVERLAY, com.oxydroid.NativeGameOverlay.class.getName());
        intent.putExtra(EXTRA_INSTANCE, instance.getName());
        intent.putExtra(EXTRA_DISPLAY_MODE, displayMode);
        // "Keep running in the background" (a hosted world): the player goes on off screen, with this
        // notification meanwhile (NativeUnityActivity, BackgroundRunService).
        if (instance.settings().isKeepRunningInBackground())
            intent.putExtra(EXTRA_BACKGROUND_TEXT, context.getString(com.oxydroid.R.string.keep_running_notice));
        // Unity's own command line (the player reads the "unity" extra): ours (below), then the instance's
        // environment field for tests, OXYDROID_UNITY_ARGS=-force-gfx-direct,-other (commas for spaces).
        StringBuilder unityArgs = new StringBuilder();
        String secondary = null, userArgs = null;
        for (String e : env) {
            if (e.startsWith(NO_SECONDARY + "=")) secondary = e.substring(NO_SECONDARY.length() + 1);
            if (e.startsWith("OXYDROID_UNITY_ARGS=")) userArgs = e.substring("OXYDROID_UNITY_ARGS=".length());
        }
        // Vulkan, always: the game's Linux build lists OpenGL Core before Vulkan, the Android player takes
        // the first entry for OpenGL ES, and the game has no GLES shaders ("Your device does not match the
        // hardware requirements of this application").
        unityArgs.append("-force-vulkan");
        if ("1".equals(secondary)) unityArgs.append(" -vulkan-disable-secondary-commandbuffers");
        if (userArgs != null && !userArgs.isEmpty())
            unityArgs.append(unityArgs.length() > 0 ? " " : "").append(userArgs.replace(',', ' '));
        if (unityArgs.length() > 0) intent.putExtra("unity", unityArgs.toString());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Mods: the Mods screen's master switch, as in the box64 launch on native Mono (GameLauncher): the
     * bundled BepInEx is refreshed in the instance and il2mono starts its preloader (bepinex.c). The
     * DOORSTOP_* variables stand in for what Doorstop would derive from the game's executable, so
     * BepInEx finds the same game root, BepInEx folder and Managed folder as in the box64 launch.
     */
    private static void addMods(Context context, GameInstance instance, File gameDir, File dataDir, List<String> env) {
        if (!instance.settings().isModSupport()) return;
        try {
            com.oxydroid.ModManager.ensureBepInEx(context, gameDir);
        } catch (IOException e) {
            Log.e(TAG, "Mods: could not set up BepInEx, starting without mods", e);
            com.oxydroid.LauncherLog.line("Mods: BepInEx setup FAILED, starting without mods: " + e);
            return;
        }
        String dataName = dataDir.getName();
        String stem = dataName.endsWith("_Data") ? dataName.substring(0, dataName.length() - 5) : dataName;
        env.add("OXYDROID_BEPINEX_PRELOADER=" + com.oxydroid.ModManager.preloader(gameDir).getAbsolutePath());
        env.add("DOORSTOP_PROCESS_PATH=" + new File(gameDir, stem + ".x86_64").getAbsolutePath());
        env.add("DOORSTOP_MANAGED_FOLDER_DIR=" + new File(dataDir, "Managed").getAbsolutePath());
        Log.i(TAG, "Mods: BepInEx " + com.oxydroid.ModManager.BEPINEX_VERSION + ", "
                + com.oxydroid.ModManager.countEnabled(gameDir) + " mod(s) enabled");
    }

    /**
     * The launcher's graphics settings for the player, as environment for il2mono and the bridge:
     * the render resolution preset (the fixed 720p mode becomes 720 lines filling the screen:
     * the player has no black margins) and the frame-rate cap, with "off" capping at the screen's top
     * rate. Returns the display mode the frame-rate mode wants, 0 = none.
     */
    private static int graphicsSettings(Context context, com.oxydroid.InstanceSettings is, List<String> env) {
        // No texture reduction: Oxygen Not Included is 2D, and dropping mip levels of its atlases and UI
        // sprites blurs everything on screen. The game's own settings are left as the game writes them.
        // Mali: the game's cinematics are off (bridge NoCinematics.cs; OXYDROID_SKIP_VIDEO=0 in the user's
        // environment turns them back on). Starting the intro lost the GPU on a Mali-G615 and froze the
        // picture; weaker Mali decoders refuse the clip anyway.
        if (com.oxydroid.GpuInfo.isMali() || fakeMali(is)) env.add("OXYDROID_SKIP_VIDEO=1");
        // The landscape the overlay locks the activity to, for the bridge to lock the player to as well.
        if (is.isReverseLandscape()) env.add("OXYDROID_REVERSE_LANDSCAPE=1");
        if (is.getHudScale() > 100) env.add("OXYDROID_HUD_SCALE=" + is.getHudScale());
        // Unity without secondary command buffers. On a Mali-G615 Arm's driver loses the device on them
        // (the main menu, or the world right after: VK_ERROR_DEVICE_LOST, the picture frozen); with the
        // flag it ran the native engine at 50+ fps against 20-30 on the mix (POCO X6 Pro, 2026-10-07).
        // The G57, G720 and G925 run without it, and on the G720 it cost about 10 fps of 120
        // (2026-10-08), so Auto turns it on for the G615 only; another GPU with the same fault has the
        // setting. The player's Vulkan compatibility setting (Auto / On / Off) decides, and
        // OXYDROID_NO_SECONDARY_CMDBUFFERS=0/1 in the environment field overrides that (added later).
        int vkCompat = is.getVulkanCompat();
        boolean noSecondary = vkCompat == com.oxydroid.InstanceSettings.VK_COMPAT_ON
                || (vkCompat == com.oxydroid.InstanceSettings.VK_COMPAT_AUTO && needsVulkanCompat());
        if (noSecondary) env.add(NO_SECONDARY + "=1");
        Log.i(TAG, "vulkan compatibility " + vkCompat + " -> no secondary command buffers: " + noSecondary);

        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        android.view.Display display = dm != null ? dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) : null;
        if (display != null) {
            android.view.Display.Mode mode = display.getMode();
            int sLong = Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
            int sShort = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight());
            int w, h;
            if (is.getFixedResMode() != com.oxydroid.InstanceSettings.FIXED_NONE) {
                h = Math.min(720, sShort);
                w = Math.round((float) sLong * h / sShort);
            } else {
                float scale = is.getEffectiveRenderScale(sLong, sShort);
                w = Math.round(sLong * scale);
                h = Math.round(sShort * scale);
            }
            env.add("OXYDROID_SCREEN_WIDTH=" + w);
            env.add("OXYDROID_SCREEN_HEIGHT=" + h);
            Log.i(TAG, "render resolution " + w + "x" + h + " on " + sLong + "x" + sShort);
        }

        com.oxydroid.FpsPlanner.Plan fp = com.oxydroid.FpsPlanner.plan(display, is.getFpsMode());
        int cap = fp.fps > 0 ? fp.fps : com.oxydroid.FpsPlanner.maxHz(display);
        if (cap > 0) env.add("OXYDROID_FPS_CAP=" + cap);
        Log.i(TAG, "fps mode " + is.getFpsMode() + " -> cap " + cap + " @ " + fp.refreshHz + " Hz"
                + (fp.modeId != 0 ? " (display mode " + fp.modeId + ")" : ""));
        return fp.modeId;
    }

    /**
     * The instance's own environment variables (card Advanced), as in the box64 launch: applied after
     * everything above, so a tester can override a setting (OXYDROID_SCREEN_WIDTH=0 keeps the screen's
     * own resolution, for one) without a new build.
     */
    private static void addUserEnvironment(Context context, com.oxydroid.InstanceSettings is, List<String> env) {
        String raw = is.getEnvVars();
        if (raw == null || raw.trim().isEmpty()) return;
        for (String token : raw.trim().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq <= 0) continue;
            if (token.startsWith(VULKAN_DRIVER + "="))
                token = VULKAN_DRIVER + "=" + vulkanDriverPath(context, token.substring(eq + 1));
            env.add(token);
        }
    }

    /** OXYDROID_FAKE_MALI=1 in the instance's environment field (see prepare). */
    private static boolean fakeMali(com.oxydroid.InstanceSettings is) {
        return "1".equals(userEnvironmentValue(is, "OXYDROID_FAKE_MALI"));
    }

    /** The value NAME has in the instance's environment field, or null. */
    private static String userEnvironmentValue(com.oxydroid.InstanceSettings is, String name) {
        String raw = is.getEnvVars();
        if (raw == null) return null;
        for (String token : raw.trim().split("\\s+"))
            if (token.startsWith(name + "=")) return token.substring(name.length() + 1);
        return null;
    }

    private static final String VULKAN_DRIVER = "OXYDROID_VULKAN_DRIVER";
    private static final String NO_SECONDARY = "OXYDROID_NO_SECONDARY_CMDBUFFERS";

    /** Vulkan compatibility's Auto: the GPUs known to need it, the Mali-G615 (see graphicsSettings). */
    public static boolean needsVulkanCompat() {
        String renderer = com.oxydroid.GpuInfo.query().renderer;
        return renderer != null && renderer.contains("G615");
    }


    /**
     * OXYDROID_VULKAN_DRIVER (a test switch: the engine on another Vulkan driver, vkshim/driver.c) takes
     * "custom" for the driver imported in the settings (an AdrenoTools zip or a .so), a bare name for one
     * of the bundled drivers (the Turnip builds unpacked with the other libraries, or one shipped among
     * the app's own native libraries, such as a test build's libpanvk.so), or an absolute path.
     */
    private static String vulkanDriverPath(Context context, String value) {
        if (value.startsWith("/")) return value;
        if (value.equals("custom")) return com.oxydroid.CustomDriverInstaller.driverFile().getAbsolutePath();
        File bundled = new File(com.oxydroid.AppStorage.requireSingleton().getHomePath(),
                com.oxydroid.C.deps.LIBS_ANDROID_ARM64 + "/" + value);
        File own = new File(context.getApplicationInfo().nativeLibraryDir, value);
        return (!bundled.isFile() && own.isFile() ? own : bundled).getAbsolutePath();
    }

    /** The launch settings worth seeing in a report: everything but il2mono's paths. */
    private static String launchSummary(List<String> env) {
        StringBuilder sb = new StringBuilder();
        for (String e : env) {
            if (e.startsWith("OXYDROID_IL2MONO_") || e.startsWith("DOORSTOP_")) continue;
            // The player's SteamID and name stay out of the log (it goes into bug reports).
            if (e.startsWith("OXYDROID_STEAM_") && !e.startsWith("OXYDROID_STEAM_TICKET=")) continue;
            if (sb.length() > 0) sb.append(' ');
            if (e.startsWith("OXYDROID_STEAM_TICKET=")) {
                sb.append("crossplay=on");
                continue;
            }
            sb.append(e.startsWith("OXYDROID_BEPINEX_PRELOADER=") ? "mods=on" : e);
        }
        return sb.length() > 0 ? sb.toString() : "(defaults)";
    }

    private static long apkStamp(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- managed overrides: copied out of the APK once per install ---------------------------

    private static File extractManagedOverrides(Context context, long apkStamp) throws IOException {
        File dir = new File(context.getFilesDir(), "native_engine/Managed");
        File stamp = new File(dir, ".stamp");
        if (readStamp(stamp) == apkStamp && apkStamp != 0) return dir;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        // Start empty: an assembly a newer APK no longer carries must not stay behind and shadow the
        // game's own copy (the Android Input System builds did until 2026-10).
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (f.isFile() && !f.delete()) throw new IOException("cannot delete " + f);
        AssetManager assets = context.getAssets();
        String[] names = assets.list(MANAGED_OVERRIDES_ASSETS);
        if (names == null || names.length == 0) throw new IOException("no managed overrides in the APK");
        for (String name : names) {
            try (InputStream in = assets.open(MANAGED_OVERRIDES_ASSETS + "/" + name);
                 OutputStream out = new FileOutputStream(new File(dir, name))) {
                copy(in, out);
            }
        }
        writeStamp(stamp, apkStamp);
        Log.i(TAG, "extracted " + names.length + " managed overrides");
        return dir;
    }

    // ---- data archive -------------------------------------------------------------------------

    // Folders and files of the Linux data dir the player must not see (or that come from the APK).
    private static boolean isExcluded(String relative) {
        return relative.startsWith("Managed/") || relative.startsWith("MonoBleedingEdge/")
                || relative.startsWith("Plugins/") || relative.startsWith("StreamingAssets/")
                || relative.equals("boot.config") || relative.equals("Resources/unity default resources")
                || relative.endsWith(".png");
    }

    private static File buildDataArchive(Context context, File dataDir, File workDir, long apkStamp) throws IOException {
        File archive = new File(workDir, "data.zip");
        File stampFile = new File(workDir, "data.zip.stamp");
        File movedFile = new File(workDir, MOVED_LIST);
        java.util.Map<String, long[]> moved = readMoved(movedFile);
        // The data files: those on disk, and the big ones that live only in the archive (moveIntoArchive).
        java.util.TreeSet<String> gameFiles = new java.util.TreeSet<>();
        List<String> onDisk = new ArrayList<>();
        listFiles(dataDir, "", onDisk);
        gameFiles.addAll(onDisk);
        gameFiles.addAll(moved.keySet());
        long stamp = apkStamp;
        for (String rel : gameFiles) {
            if (isExcluded(rel)) continue;
            File f = new File(dataDir, rel);
            long[] m = f.isFile() ? null : moved.get(rel);
            long length = m != null ? m[0] : f.length(), modified = m != null ? m[1] : f.lastModified();
            stamp = stamp * 31 + length * 7 + modified;
        }
        if (archive.isFile() && readStamp(stampFile) == stamp) {
            moveIntoArchive(dataDir, archive, movedFile, moved);
            return archive;
        }

        File tmp = new File(workDir, "data.zip.tmp");
        AssetManager assets = context.getAssets();
        // A file kept only in the archive is copied from the old archive into the new one; one on disk
        // wins over it (a new download of the game, or a mod, may have put it back).
        java.util.zip.ZipFile old = archive.isFile() && !moved.isEmpty() ? new java.util.zip.ZipFile(archive) : null;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tmp))) {
            List<String> playerFiles = new ArrayList<>();
            listAssets(assets, PLAYER_ASSETS, "", playerFiles);
            for (String rel : playerFiles) {
                byte[] bytes;
                try (InputStream in = assets.open(PLAYER_ASSETS + "/" + rel)) {
                    bytes = readAll(in);
                }
                putStored(zip, "assets/bin/Data/" + rel, bytes);
            }
            for (String rel : gameFiles) {
                if (isExcluded(rel)) continue;
                File f = new File(dataDir, rel);
                if (!f.isFile()) {
                    copyStoredEntry(zip, old, "assets/bin/Data/" + rel);
                    continue;
                }
                if (rel.equals("ScriptingAssemblies.json")) {
                    putStored(zip, "assets/bin/Data/" + rel, addBridgeAssembly(readFile(f)));
                } else if (rel.equals("RuntimeInitializeOnLoads.json")) {
                    putStored(zip, "assets/bin/Data/" + rel, addBridgeInitializer(readFile(f)));
                } else {
                    putStoredFile(zip, "assets/bin/Data/" + rel, f);
                }
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        } finally {
            if (old != null) old.close();
        }
        if (archive.exists() && !archive.delete()) throw new IOException("cannot replace " + archive);
        if (!tmp.renameTo(archive)) throw new IOException("cannot rename " + tmp);
        writeStamp(stampFile, stamp);
        Log.i(TAG, "built " + archive + " (" + archive.length() / (1024 * 1024) + " MB)");
        moveIntoArchive(dataDir, archive, movedFile, moved);
        return archive;
    }

    /**
     * Files of the data folder at least this big live only in data.zip once it holds them: the archive is
     * the player's copy of the folder, and keeping both doubled the space an instance takes (about 2.7 GB
     * of Oxygen Not Included's 6). Small files stay: the launcher's own checks read some of them
     * (globalgamemanagers, the assembly manifests) and they hardly count. As in StormDroid.
     *
     * The texture conversion for GPUs without BC formats (TextureConverter) rewrites the .assets/.resS
     * pairs on disk, and runs in prepare() before this archive is built: on such a GPU the archive is made
     * from the converted files and holds them from then on.
     */
    private static final long MOVE_MIN_BYTES = 16L * 1024 * 1024;
    /** Lines "relative path TAB length TAB lastModified" of the files that live only in the archive. */
    private static final String MOVED_LIST = "data.zip.moved";

    /**
     * Removes the big data files the archive now holds, recording each first (so the archive's stamp still
     * sees them, and a rebuild copies them from the old archive). Only files whose archive entry has the
     * same size are removed.
     */
    private static void moveIntoArchive(File dataDir, File archive, File movedFile, java.util.Map<String, long[]> moved) {
        List<String> onDisk = new ArrayList<>();
        listFiles(dataDir, "", onDisk);
        List<File> remove = new ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive)) {
            for (String rel : onDisk) {
                if (isExcluded(rel)) continue;
                File f = new File(dataDir, rel);
                if (f.length() < MOVE_MIN_BYTES) continue;
                ZipEntry e = zip.getEntry("assets/bin/Data/" + rel);
                if (e == null || e.getSize() != f.length()) continue;
                moved.put(rel, new long[] { f.length(), f.lastModified() });
                remove.add(f);
            }
        } catch (IOException e) {
            Log.w(TAG, "data archive not checked, the data files stay: " + e);
            return;
        }
        if (remove.isEmpty()) return;
        try {
            writeMoved(movedFile, moved);
        } catch (IOException e) {
            Log.w(TAG, "could not record the moved data files, they stay: " + e);
            return;
        }
        long freed = 0;
        for (File f : remove) {
            long len = f.length();
            if (f.delete()) freed += len;
        }
        Log.i(TAG, "data files kept only in the archive: " + remove.size() + ", " + (freed >> 20) + " MB freed");
    }

    private static java.util.Map<String, long[]> readMoved(File f) {
        java.util.Map<String, long[]> out = new java.util.HashMap<>();
        if (!f.isFile()) return out;
        try {
            for (String line : new String(readFile(f), StandardCharsets.UTF_8).split("\n")) {
                String[] p = line.split("\t");
                if (p.length == 3) out.put(p[0], new long[] { Long.parseLong(p[1].trim()), Long.parseLong(p[2].trim()) });
            }
        } catch (Exception e) {
            Log.w(TAG, "moved list unreadable: " + e);
        }
        return out;
    }

    private static void writeMoved(File f, java.util.Map<String, long[]> moved) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, long[]> e : new java.util.TreeMap<>(moved).entrySet())
            sb.append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\n');
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("cannot rename " + tmp);
    }

    /** One stored entry of the old archive, copied as it is. */
    private static void copyStoredEntry(ZipOutputStream zip, java.util.zip.ZipFile old, String name) throws IOException {
        ZipEntry src = old != null ? old.getEntry(name) : null;
        if (src == null)
            throw new IOException("the game's data file " + name + " is missing: add the instance again from its archive");
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(src.getSize());
        entry.setCompressedSize(src.getSize());
        entry.setCrc(src.getCrc());
        zip.putNextEntry(entry);
        byte[] buf = new byte[1 << 16];
        try (InputStream in = old.getInputStream(src)) {
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
        }
        zip.closeEntry();
    }

    // The bridge assembly (OxyDroidBridge.dll: virtual gamepad, keyboard and mouse) is not part of the
    // game: list it and its [RuntimeInitializeOnLoadMethod] so the player loads and starts it.
    private static byte[] addBridgeAssembly(byte[] json) throws IOException {
        try {
            JSONObject root = new JSONObject(new String(json, StandardCharsets.UTF_8));
            JSONArray names = root.getJSONArray("names");
            JSONArray types = root.getJSONArray("types");
            for (int i = 0; i < names.length(); i++)
                if ("OxyDroidBridge.dll".equals(names.getString(i))) return json;
            names.put("OxyDroidBridge.dll");
            types.put(16);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("ScriptingAssemblies.json: " + e.getMessage(), e);
        }
    }

    private static byte[] addBridgeInitializer(byte[] json) throws IOException {
        try {
            JSONObject root = new JSONObject(new String(json, StandardCharsets.UTF_8));
            JSONArray list = root.getJSONArray("root");
            for (int i = 0; i < list.length(); i++)
                if ("OxyDroidBridge".equals(list.getJSONObject(i).optString("assemblyName"))) return json;
            JSONObject entry = new JSONObject();
            entry.put("assemblyName", "OxyDroidBridge");
            entry.put("nameSpace", "OxyDroid");
            entry.put("className", "Bridge");
            entry.put("methodName", "Initialize");
            entry.put("loadTypes", 1); // BeforeSceneLoad
            entry.put("isUnityClass", false);
            list.put(entry);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("RuntimeInitializeOnLoads.json: " + e.getMessage(), e);
        }
    }

    /**
     * Offset of the target-platform int in a Unity SerializedFile header (format v22+), or -1 when the
     * file is not one: 48-byte header, then the unityVersion C string, then int32 target.
     */
    static int targetPlatformOffset(byte[] head, int length) {
        if (length < 64) return -1;
        int version = ((head[8] & 0xff) << 24) | ((head[9] & 0xff) << 16) | ((head[10] & 0xff) << 8) | (head[11] & 0xff);
        if (version < 22 || version > 64) return -1;
        int end = 48;
        while (end < length && end < 120 && head[end] != 0) {
            if (head[end] < 0x20 || head[end] > 0x7e) return -1; // printable, e.g. "6000.0.75f1"
            end++;
        }
        if (end == 48 || end + 5 > length || head[end] != 0) return -1;
        boolean littleEndian = head[16] == 0;
        int off = end + 1;
        int target = littleEndian
                ? (head[off] & 0xff) | ((head[off + 1] & 0xff) << 8) | ((head[off + 2] & 0xff) << 16) | ((head[off + 3] & 0xff) << 24)
                : ((head[off] & 0xff) << 24) | ((head[off + 1] & 0xff) << 16) | ((head[off + 2] & 0xff) << 8) | (head[off + 3] & 0xff);
        return target == TARGET_LINUX64 ? off : -1;
    }

    // Writes a file as a STORED entry; a Linux-tagged serialized file gets its target set to Android
    // on the way (the Android player refuses any other target, "created for another build target").
    private static void putStoredFile(ZipOutputStream zip, String name, File file) throws IOException {
        byte[] head = new byte[256];
        int headLen;
        try (InputStream in = new FileInputStream(file)) {
            headLen = Math.max(0, in.read(head));
        }
        int targetOff = targetPlatformOffset(head, headLen);
        if (targetOff >= 0) {
            boolean le = head[16] == 0;
            head[targetOff] = (byte) (le ? TARGET_ANDROID : 0);
            head[targetOff + 1] = 0;
            head[targetOff + 2] = 0;
            head[targetOff + 3] = (byte) (le ? 0 : TARGET_ANDROID);
        }
        // STORED needs size and CRC before the data: one pass to checksum, one to write.
        CRC32 crc = new CRC32();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(file)) {
            long pos = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                patchHead(buf, n, pos, head, headLen);
                crc.update(buf, 0, n);
                pos += n;
            }
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(file.length());
        entry.setCompressedSize(file.length());
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        try (InputStream in = new FileInputStream(file)) {
            long pos = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                patchHead(buf, n, pos, head, headLen);
                zip.write(buf, 0, n);
                pos += n;
            }
        }
        zip.closeEntry();
    }

    private static void patchHead(byte[] buf, int n, long pos, byte[] head, int headLen) {
        for (int i = 0; i < n && pos + i < headLen; i++) buf[i] = head[(int) (pos + i)];
    }

    private static void putStored(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * The game's own settings a phone wants from the first launch, written into its settings file
     * (kplayerprefs.yaml: three maps, "strings", "ints" and "floats") only where the key is not there
     * yet, so whatever the player sets in the game's options stays:
     * <ul>
     * <li>LowResTextures = 1, the game's "Low resolution textures": the PC textures at full size took
     * 2.8 GB of graphics memory on a phone, 1.3 GB with this on;</li>
     * <li>UIScalePref = 110, the "UI size" slider in percent (chosen at the default render resolution,
     * the highest the launcher offers; the game draws its interface larger at lower resolutions): the PC interface is too small to read
     * and to hit on a phone screen.</li>
     * </ul>
     * Best-effort: a failure here must not stop the launch.
     */
    static void seedGameDefaults(File userDataDir) {
        try {
            File prefs = new File(userDataDir, "kplayerprefs.yaml");
            // A new file carries all three maps: the game reads each of them without a null check.
            String text = prefs.isFile() ? new String(readFile(prefs), StandardCharsets.UTF_8)
                    : "strings: {}\nints: {}\nfloats: {}\n";
            String seeded = seedPref(seedPref(text, "ints", "LowResTextures", "1"), "floats", "UIScalePref", "110");
            if (seeded.equals(text)) return;
            if (!userDataDir.isDirectory() && !userDataDir.mkdirs()) return;
            try (OutputStream out = new FileOutputStream(prefs)) {
                out.write(seeded.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "game defaults written to " + prefs);
        } catch (Exception e) {
            Log.w(TAG, "game defaults not written", e);
        }
    }

    /** Adds "  key: value" to a top-level map of the settings file unless the key is already in the file. */
    static String seedPref(String yaml, String section, String key, String value) {
        String entry = "  " + key + ": " + value + "\n";
        if (yaml.contains("\n  " + key + ":") || yaml.startsWith("  " + key + ":")) return yaml;
        String text = (yaml.isEmpty() || yaml.endsWith("\n")) ? yaml : yaml + "\n";
        String head = section + ":";
        int at = text.startsWith(head) ? 0 : text.indexOf("\n" + head);
        if (at < 0) return text + head + "\n" + entry;            // no such map yet
        if (at > 0) at++;                                          // past the newline before it
        int lineEnd = text.indexOf('\n', at);
        if (lineEnd < 0) lineEnd = text.length();
        // "ints:" alone opens a block; anything after the colon ("ints: {}") is an empty inline map.
        return text.substring(0, at) + head + "\n" + entry + text.substring(Math.min(lineEnd + 1, text.length()));
    }

    /**
     * Where the launcher keeps the macOS build's SimDLL inside a game folder: {@code macos/SimDLL}, a
     * copy of {@code OxygenNotIncluded.app/Contents/PlugIns/SimDLL.bundle/Contents/MacOS/SimDLL}.
     */
    public static File macSimDll(File gameDir) {
        return new File(new File(gameDir, "macos"), "SimDLL");
    }

    /** The Linux build's own simulation library, x86-64: run under box64 when asked (see prepare). */
    public static File linuxSimDll(File dataDir) {
        return new File(dataDir, "Plugins/x86_64/libSimDLL.so");
    }

    // ---- Mono config ----------------------------------------------------------------------------

    // The game's etc/mono, with System.Native mapped to the libmono-native.so in the app's own
    // native libs instead of the Linux layout's $mono_libdir.
    /** Sends a library's dlopen to il2mono (libil2cpp.so); its other functions stay where they were. */
    private static final String DLOPEN_ENTRY =
            "<dllentry dll=\"libil2cpp.so\" name=\"dlopen\" target=\"il2mono_dlopen\"/>";

    /**
     * libc for managed code: bionic's libc.so, with the three functions whose ABI differs from glibc's
     * sent to il2mono (il2mono/monomod.c): MonoMod (inside Harmony) asks for __errno_location, glibc's
     * sysconf numbering and mmap with a 32-bit offset, and without them no mod's patches apply.
     */
    private static final String GLIBC_ENTRIES = "<dllentry dll=\"libil2cpp.so\" name=\"__errno_location\" target=\"il2mono_errno_location\"/><dllentry dll=\"libil2cpp.so\" name=\"sysconf\" target=\"il2mono_sysconf\"/><dllentry dll=\"libil2cpp.so\" name=\"mmap\" target=\"il2mono_mmap\"/>";

    /**
     * A dllmap for {@code dll} that sends GLIBC_ENTRIES to il2mono and everything else to bionic's
     * libc.so. The plain map after the one with the entries is the one Mono meets first (it reads the
     * newest first): without it every function of the library is looked up in libil2cpp.so (see
     * System.Native below).
     */
    private static String libcMap(String dll) {
        return "\t<dllmap dll=\"" + dll + "\" target=\"libc.so\">" + GLIBC_ENTRIES + "</dllmap>\n"
                + "\t<dllmap dll=\"" + dll + "\" target=\"libc.so\"/>\n";
    }

    private static File prepareMonoConfig(File dataDir, File workDir) throws IOException {
        File src = new File(dataDir, "MonoBleedingEdge/etc");
        File dst = new File(workDir, "etc");
        copyTree(src, dst);
        File config = new File(dst, "mono/config");
        if (config.isFile()) {
            // P/Invoke names: Android's libc is libc.so (libdl, libm likewise; pthread is inside libc),
            // not the glibc sonames the Linux config and Linux mods use (BepInEx calls uname() through
            // "libc.so.6"). Under box64 those names reached box64's own libraries. Names handed to
            // dlopen() itself (MonoMod's DynDll) never meet the dllmap: dlopen goes to il2mono's
            // il2mono_dlopen, which translates them.
            String text = new String(readFile(config), StandardCharsets.UTF_8)
                    // System.Native's two stat entry points go through il2mono, which hides
                    // /system/build.prop from managed code: MonoMod (inside the game's Harmony) takes
                    // that file for "this is Android" and refuses to patch anything (il2mono/monomod.c).
                    .replace("<dllmap dll=\"System.Native\" target=\"$mono_libdir/libmono-native.so\" os=\"!windows\" />",
                            "<dllmap dll=\"System.Native\" target=\"libmono-native.so\" os=\"!windows\">"
                            + "<dllentry dll=\"libil2cpp.so\" name=\"SystemNative_Stat2\" target=\"il2mono_stat2\"/>"
                            + "<dllentry dll=\"libil2cpp.so\" name=\"SystemNative_LStat2\" target=\"il2mono_lstat2\"/>"
                            + "</dllmap>\n"
                            // Mono takes a library's target from the entry it meets first, and it meets
                            // the newest first: with the entries above alone, every System.Native
                            // function was looked up in libil2cpp.so (EntryPointNotFoundException at the
                            // game's first file check). This plain entry, read last, is met first; the
                            // two entries above still win for their own functions.
                            + "\t<dllmap dll=\"System.Native\" target=\"libmono-native.so\" os=\"!windows\" />")
                    .replace("$mono_libdir/libmono-native.so", "libmono-native.so")
                    // The Linux config's "libc" (libc.so.6): bionic's libc with glibc's ABI where it
                    // differs, for MonoMod; the same for mods that name libc.so.6 itself.
                    .replace("<dllmap dll=\"libc\" target=\"libc.so.6\" os=\"!windows\"/>", libcMap("libc").trim())
                    .replace("target=\"libc.so.6\"", "target=\"libc.so\"")
                    .replace("</configuration>",
                            libcMap("libc.so.6")
                            + "\t<dllmap dll=\"libdl.so.2\" target=\"libdl.so\">" + DLOPEN_ENTRY + "</dllmap>\n"
                            + "\t<dllmap dll=\"dl\">" + DLOPEN_ENTRY + "</dllmap>\n"
                            + "\t<dllmap dll=\"libdl\">" + DLOPEN_ENTRY + "</dllmap>\n"
                            + "\t<dllmap dll=\"libm.so.6\" target=\"libm.so\"/>\n"
                            + "\t<dllmap dll=\"libpthread.so.0\" target=\"libc.so\"/>\n"
                            // PlayFab Party (crossplay): Valheim's PlayFabParty.dll asks for the Linux
                            // file name; the Android build is libparty.so (NativeUnityActivity.loadParty).
                            + "\t<dllmap dll=\"libParty.so\" target=\"libparty.so\"/>\n"
                            // FMOD Studio: the game's wrapper imports everything, the core FMOD5_*
                            // functions too, from "fmodstudio" (NativeUnityActivity.loadFmod).
                            + "\t<dllmap dll=\"fmodstudio\" target=\"libfmodstudio.so\"/>\n"
                            // The simulation: libSimDLL.so of this APK (it loads the macOS arm64 build).
                            + "\t<dllmap dll=\"SimDLL\" target=\"libSimDLL.so\"/>\n"
                            + "</configuration>");
            try (OutputStream out = new FileOutputStream(config)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        }
        return dst;
    }

    // ---- small file helpers -------------------------------------------------------------------

    private static void listFiles(File dir, String prefix, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String rel = prefix + k.getName();
            if (k.isDirectory()) listFiles(k, rel + "/", out);
            else out.add(rel);
        }
    }

    private static void listAssets(AssetManager assets, String root, String prefix, List<String> out) throws IOException {
        String path = prefix.isEmpty() ? root : root + "/" + prefix.substring(0, prefix.length() - 1);
        String[] names = assets.list(path);
        if (names == null) return;
        for (String name : names) {
            String rel = prefix + name;
            String[] sub = assets.list(root + "/" + rel);
            if (sub != null && sub.length > 0) listAssets(assets, root, rel + "/", out);
            else out.add(rel);
        }
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("cannot create " + dst);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else if (src.isFile()) {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
        }
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static long readStamp(File f) {
        try {
            return Long.parseLong(new String(readFile(f), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private static void writeStamp(File f, long value) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(Long.toString(value).getBytes(StandardCharsets.UTF_8));
        }
    }
}
