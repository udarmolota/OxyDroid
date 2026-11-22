package com.oxydroid;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup / restore of an Oxygen Not Included instance's saves and settings, as a zip.
 *
 * The game keeps everything in its user-data dir, {@code <instance>/unity3d/Klei/Oxygen Not Included/}
 * (on a PC: {@code Documents/Klei/OxygenNotIncluded} on Windows,
 * {@code ~/.config/unity3d/Klei/Oxygen Not Included} on Linux).
 *
 * SAVES: one folder per colony, {@code <Colony>/<Colony>.sav} with its preview picture and an
 * {@code auto_save/} folder. The game puts a colony in {@code save_files/} or, when it is a "cloud"
 * colony (the default), in {@code cloud_save_files/<SteamID>/}. That number differs between a PC and
 * this launcher, and the game only lists the folder of the SteamID it runs under, so:
 * <ul>
 * <li>export writes every colony, local and cloud, under {@code save_files/} in the zip;</li>
 * <li>import takes {@code save_files/...} and {@code cloud_save_files/<any id>/...} found at any depth
 * of the zip, and a bare colony folder or {@code .sav} file too, and puts them all into the local
 * {@code save_files/}, which the game always lists.</li>
 * </ul>
 *
 * SETTINGS: {@code kplayerprefs.yaml} (the game's options), {@code keybindings.json} and
 * {@code settings.yml}, under {@code settings/} in the zip. On import the lines of kplayerprefs.yaml
 * that describe the other machine are left out: resolution, full screen, refresh rate and the path of
 * the last save.
 *
 * Saves and settings stay SEPARABLE — callers pass {@link #SAVES} and/or {@link #CONFIG} — so moving
 * saves to another device does not drag this device's settings along.
 */
public final class GameDataTransfer {

    public static final String SAVES  = "Saves";     // part names, as passed by the menu
    public static final String CONFIG = "Config";
    private static final String[] ALL_PARTS = { SAVES, CONFIG };

    private static final String USER_DIR = com.oxydroid.game.GameDescriptor.ONI.userDataDirectory();
    private static final String LOCAL_SAVES = "save_files";
    private static final String CLOUD_SAVES = "cloud_save_files";
    private static final String PREFS = "kplayerprefs.yaml";
    /** Settings files in the user-data dir; in the zip under "settings/". */
    private static final String[] SETTINGS_FILES = { PREFS, "keybindings.json", "settings.yml" };
    /** Keys of kplayerprefs.yaml that belong to the machine they were written on. */
    private static final String[] MACHINE_KEYS = {
            "ResolutionWidth", "ResolutionHeight", "RefreshRateNumerator", "RefreshRateDenominator",
            "FullScreen", "SaveFilenameKey/",
    };

    public static final class Result {
        public final List<String> items = new ArrayList<>(); // which parts were handled
        public String error;
        public long bytes;
        public boolean ok() { return error == null && !items.isEmpty(); }
    }

    private GameDataTransfer() {}

    /** Zip the requested parts of the instance at {@code instanceDir} into {@code rawOut}. */
    public static Result export(File instanceDir, OutputStream rawOut, String... parts) {
        Set<String> use = parts(parts);
        Result r = new Result();
        File user = new File(instanceDir, USER_DIR);
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[1 << 16];
            Set<String> written = new HashSet<>();   // a local and a cloud colony may share a name
            if (use.contains(SAVES)) {
                boolean any = false;
                List<File> roots = new ArrayList<>();
                roots.add(new File(user, LOCAL_SAVES));
                File[] ids = new File(user, CLOUD_SAVES).listFiles();
                if (ids != null) for (File id : ids) if (id.isDirectory()) roots.add(id);
                for (File root : roots) {
                    File[] kids = root.listFiles();
                    if (kids == null) continue;
                    for (File f : kids) {
                        String name = LOCAL_SAVES + "/" + f.getName();
                        if (f.isDirectory()) any |= zipDir(f, name, zos, buf, r, written);
                        else any |= zipFile(f, name, zos, buf, r, written);
                    }
                }
                if (any) r.items.add("saves");
            }
            if (use.contains(CONFIG)) {
                boolean any = false;
                for (String name : SETTINGS_FILES) {
                    File file = new File(user, name);
                    if (file.isFile()) any |= zipFile(file, "settings/" + name, zos, buf, r, written);
                }
                if (any) r.items.add("settings");
            }
            if (r.items.isEmpty())
                r.error = "Nothing to export yet — play the game once so it writes its saves and settings.";
        } catch (IOException e) {
            r.error = msg(e);
        }
        return r;
    }

    /** Restore the requested parts from a backup zip into the instance at {@code instanceDir}. */
    public static Result importZip(File zipFile, File instanceDir, String... allowedParts) {
        Set<String> use = parts(allowedParts);
        Result r = new Result();
        File user = new File(instanceDir, USER_DIR);
        final String rootCanon;
        try { rootCanon = instanceDir.getCanonicalPath(); } catch (IOException e) { r.error = msg(e); return r; }

        Set<String> done = new LinkedHashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                String part = null;
                File out = null;
                boolean filterPrefs = false;
                if (use.contains(CONFIG)) {
                    String settings = settingsFile(name);
                    if (settings != null && !e.isDirectory()) {
                        out = new File(user, settings);
                        part = "settings";
                        filterPrefs = settings.equals(PREFS);
                    }
                }
                if (out == null && use.contains(SAVES)) {
                    String rel = saveTarget(name);
                    if (rel != null) { out = new File(new File(user, LOCAL_SAVES), rel); part = "saves"; }
                }
                if (out == null) { zis.closeEntry(); continue; }   // not ours: ignore

                String oc = out.getCanonicalPath();
                if (!oc.startsWith(rootCanon + File.separator)) { zis.closeEntry(); continue; }   // zip-slip guard
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    if (filterPrefs) {
                        ByteArrayOutputStream all = new ByteArrayOutputStream();
                        int n;
                        while ((n = zis.read(buf)) > 0) all.write(buf, 0, n);
                        byte[] kept = withoutMachineKeys(new String(all.toByteArray(), StandardCharsets.UTF_8))
                                .getBytes(StandardCharsets.UTF_8);
                        try (OutputStream os = new FileOutputStream(out)) { os.write(kept); }
                        r.bytes += kept.length;
                    } else {
                        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                            int n;
                            while ((n = zis.read(buf)) > 0) { os.write(buf, 0, n); r.bytes += n; }
                        }
                    }
                }
                done.add(part);
                zis.closeEntry();
            }
            r.items.addAll(done);
            if (done.isEmpty())
                r.error = use.contains(SAVES)
                        ? "No colonies found in this zip (a save_files or cloud_save_files folder, or .sav files)."
                        : "No game settings found in this zip (kplayerprefs.yaml).";
        } catch (IOException ex) {
            r.error = msg(ex);
        }
        return r;
    }

    /**
     * Where a zip entry lands inside the local save_files folder, or null when it is not a save:
     * what follows the LAST "save_files" segment, or what follows "cloud_save_files/&lt;SteamID&gt;",
     * wherever in the zip those folders sit; failing both, a ".sav" file with its own folder
     * ("Colony/Colony.sav", or a bare "Colony.sav", which gets a folder named after it).
     */
    static String saveTarget(String entry) {
        String[] seg = entry.split("/");
        for (int i = seg.length - 1; i >= 0; i--) {
            int from = -1;
            if (seg[i].equals(LOCAL_SAVES)) from = i + 1;
            else if (seg[i].equals(CLOUD_SAVES)) from = i + 2;   // skip the SteamID folder
            if (from < 0) continue;
            if (from >= seg.length) return null;                  // the folder itself
            return join(seg, from);
        }
        if (!entry.endsWith(".sav") || seg.length == 0) return null;
        String file = seg[seg.length - 1];
        if (seg.length >= 2 && !seg[seg.length - 2].isEmpty()) {
            // "…/Colony/auto_save/x.sav" keeps its colony folder; "…/Colony/Colony.sav" too.
            boolean auto = seg[seg.length - 2].equals("auto_save") && seg.length >= 3;
            return auto ? join(seg, seg.length - 3) : join(seg, seg.length - 2);
        }
        return file.substring(0, file.length() - 4) + "/" + file;
    }

    /** The settings file a zip entry is ("settings/kplayerprefs.yaml", or the bare name at any depth), or null. */
    static String settingsFile(String entry) {
        String last = entry.substring(entry.lastIndexOf('/') + 1);
        for (String name : SETTINGS_FILES) if (last.equals(name)) return name;
        return null;
    }

    /** kplayerprefs.yaml without the entries that describe the machine it came from. */
    static String withoutMachineKeys(String yaml) {
        StringBuilder out = new StringBuilder(yaml.length());
        for (String line : yaml.split("\n", -1)) {
            String t = line.trim();
            boolean drop = false;
            for (String key : MACHINE_KEYS) if (t.startsWith(key + ":")) { drop = true; break; }
            if (!drop) out.append(line).append('\n');
        }
        if (out.length() > 0 && !yaml.endsWith("\n")) out.setLength(out.length() - 1);
        return out.toString();
    }

    private static String join(String[] seg, int from) {
        StringBuilder b = new StringBuilder();
        for (int j = from; j < seg.length; j++) { if (b.length() > 0) b.append('/'); b.append(seg[j]); }
        return b.toString();
    }

    private static Set<String> parts(String[] parts) {
        return new LinkedHashSet<>(java.util.Arrays.asList(
                (parts == null || parts.length == 0) ? ALL_PARTS : parts));
    }

    private static boolean zipFile(File f, String name, ZipOutputStream zos, byte[] buf, Result r,
                                   Set<String> written) throws IOException {
        if (!written.add(name)) return false;
        zos.putNextEntry(new ZipEntry(name));
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            int n;
            while ((n = in.read(buf)) > 0) { zos.write(buf, 0, n); r.bytes += n; }
        }
        zos.closeEntry();
        return true;
    }

    private static boolean zipDir(File dir, String prefix, ZipOutputStream zos, byte[] buf, Result r,
                                  Set<String> written) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        boolean any = false;
        for (File f : kids) {
            String name = prefix + "/" + f.getName();
            if (f.isDirectory()) any |= zipDir(f, name, zos, buf, r, written);
            else any |= zipFile(f, name, zos, buf, r, written);
        }
        return any;
    }

    private static String msg(Exception e) { return e.getMessage() != null ? e.getMessage() : "I/O error"; }
}
