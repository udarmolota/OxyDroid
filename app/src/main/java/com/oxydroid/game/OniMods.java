package com.oxydroid.game;

import android.content.Context;
import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The mods of one Oxygen Not Included instance, as the game itself keeps them.
 *
 * The game loads mods with its own loader (KMod.Manager); nothing is injected. They live in the user
 * data folder: {@code mods/Local/<name>/}, {@code mods/Steam/<workshop id>/} and {@code mods/Dev/<name>/},
 * one folder per mod with a {@code mod_info.yaml} (required by the game), usually a {@code mod.yaml}
 * (title, description) and the mod's DLLs. The list, its ORDER (the load order) and what is switched
 * on are in {@code mods/mods.json}:
 * <pre>
 * { "version": 1, "mods": [ { "label": { "distribution_platform": 0, "id": "<folder>", "title": "...",
 *     "version": 123 }, "status": 1, "enabled": false, "enabledForDlc": ["", "EXPANSION1_ID"],
 *     "crash_count": 0, "reinstall_path": null, "staticID": "..." } ], "mod_load_in_progress": false }
 * </pre>
 * {@code distribution_platform}: 0 Local, 1 Steam, 4 Dev. A mod is on for a game variant when that
 * variant's id is in {@code enabledForDlc}: "" for the base game, "EXPANSION1_ID" for Spaced Out.
 *
 * This class only changes what is already in mods.json: it reorders entries, fills or empties
 * {@code enabledForDlc} and removes an entry with its folder. It never writes a new entry: the game
 * adds one itself, switched off, the first time it starts with the folder there (the entry carries a
 * version number only the game computes). Every field it does not know is kept as it is.
 *
 * Changes must be made while the game is not running: the game writes mods.json itself.
 */
public final class OniMods {

    /** Folder names by KMod.Label.DistributionPlatform. */
    private static final String[] PLATFORMS = { "Local", "Steam", "Epic", "Rail", "Dev" };
    /** The folders scanned for mods the list does not have yet. */
    private static final String[] SCANNED = { "Local", "Steam", "Dev" };
    /** The game variants a mod is switched on for: the base game and Spaced Out. */
    private static final String[] VARIANTS = { "", "EXPANSION1_ID" };

    public static final class Mod {
        public String platform;   // "Local", "Steam" or "Dev"
        public String id;         // the folder's name
        public String title;
        public String version;    // from mod_info.yaml, or null
        public boolean listed;    // has an entry in mods.json
        public boolean enabled;
        public boolean installed; // the folder exists
        public int index = -1;    // position in mods.json, the load order; -1 when not listed
        public int crashes;       // the game's own count of crashes it blames on the mod
    }

    private OniMods() {}

    public static File dir(File userDataDir) {
        return new File(userDataDir, "mods");
    }

    /** Listed mods in load order, then the folders the game has not listed yet, by name. */
    public static List<Mod> list(File userDataDir) {
        File mods = dir(userDataDir);
        List<Mod> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonArray entries = entries(read(mods));
        for (int i = 0; i < entries.size(); i++) {
            JsonObject e = object(entries.get(i));
            JsonObject label = e == null ? null : object(e.get("label"));
            if (label == null) continue;
            Mod m = new Mod();
            int platform = intOf(label.get("distribution_platform"));
            m.platform = platform >= 0 && platform < PLATFORMS.length ? PLATFORMS[platform] : String.valueOf(platform);
            m.id = stringOf(label.get("id"));
            m.title = stringOf(label.get("title"));
            m.listed = true;
            m.index = i;
            JsonElement on = e.get("enabledForDlc");
            m.enabled = on != null && on.isJsonArray() && on.getAsJsonArray().size() > 0;
            m.crashes = intOf(e.get("crash_count"));
            File folder = new File(new File(mods, m.platform), m.id == null ? "" : m.id);
            m.installed = m.id != null && !m.id.isEmpty() && folder.isDirectory();
            if (m.installed) m.version = yamlValue(new File(folder, "mod_info.yaml"), "version");
            if (m.title == null || m.title.isEmpty()) m.title = m.id;
            seen.add(m.platform + "/" + m.id);
            out.add(m);
        }
        List<Mod> fresh = new ArrayList<>();
        for (String platform : SCANNED) {
            File[] folders = new File(mods, platform).listFiles();
            if (folders == null) continue;
            for (File folder : folders) {
                if (!folder.isDirectory() || seen.contains(platform + "/" + folder.getName())) continue;
                Mod m = new Mod();
                m.platform = platform;
                m.id = folder.getName();
                String title = yamlValue(new File(folder, "mod.yaml"), "title");
                m.title = title == null || title.isEmpty() ? m.id : title;
                m.version = yamlValue(new File(folder, "mod_info.yaml"), "version");
                m.installed = true;
                fresh.add(m);
            }
        }
        fresh.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        out.addAll(fresh);
        return out;
    }

    /** The mods the game switches on by itself at its next start (the bridge's ModsEnable). */
    public static final String ENABLE_LIST = "oxydroid_enable.txt";

    public static File enableList(File userDataDir) {
        return new File(dir(userDataDir), ENABLE_LIST);
    }

    /**
     * Mod folders just installed into mods/Local/: the game lists a new folder only at its next start,
     * and switched off. These names go to ENABLE_LIST, and the game's own start switches them on
     * (tools/unity-native/bridge/ModsEnable.cs), so a new mod works without a visit to the game's menu.
     */
    public static void markForEnable(File userDataDir, List<String> localFolders) {
        if (localFolders == null || localFolders.isEmpty()) return;
        File f = enableList(userDataDir);
        File parent = f.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
            for (String name : localFolders) w.write("Local/" + name + "\n");
        } catch (IOException e) {
            android.util.Log.w("OxyDroid/Mods", "enable list not written: " + e);
        }
    }

    /** Switches a listed mod on (for the base game and Spaced Out) or off. */
    public static void setEnabled(File userDataDir, Mod mod, boolean enabled) throws IOException {
        File mods = dir(userDataDir);
        JsonObject root = read(mods);
        JsonObject e = entry(root, mod);
        if (e == null) throw new IOException("the game has not listed this mod yet");
        JsonArray on = new JsonArray();
        if (enabled) for (String variant : VARIANTS) on.add(variant);
        e.add("enabledForDlc", on);
        e.addProperty("enabled", enabled);
        if (enabled) e.addProperty("crash_count", 0);   // a mod the game switched off for crashing gets a new start
        write(mods, root);
    }

    /** Moves the listed mod at {@code from} to position {@code to} of the load order. */
    public static void move(File userDataDir, int from, int to) throws IOException {
        File mods = dir(userDataDir);
        JsonObject root = read(mods);
        JsonArray entries = entries(root);
        if (from < 0 || from >= entries.size() || to < 0 || to >= entries.size() || from == to) return;
        List<JsonElement> order = new ArrayList<>();
        for (JsonElement e : entries) order.add(e);
        order.add(to, order.remove(from));
        JsonArray moved = new JsonArray();
        for (JsonElement e : order) moved.add(e);
        root.add("mods", moved);
        write(mods, root);
    }

    /** Removes the mod's folder and, when it is listed, its entry. */
    public static void delete(File userDataDir, Mod mod) throws IOException {
        File mods = dir(userDataDir);
        if (mod.id == null || mod.id.isEmpty() || mod.id.contains("/") || mod.id.contains("..")) throw new IOException("bad mod id");
        File folder = new File(new File(mods, mod.platform), mod.id);
        if (folder.isDirectory() && !deleteTree(folder)) throw new IOException("cannot delete " + folder.getName());
        JsonObject root = read(mods);
        JsonObject e = entry(root, mod);
        if (e != null) {
            JsonArray kept = new JsonArray();
            for (JsonElement other : entries(root)) if (other != e) kept.add(other);
            root.add("mods", kept);
            write(mods, root);
        }
    }

    // ------------------------------------------------------------------ import and export

    /**
     * Installs every mod found in a zip into {@code mods/Local}. A mod is a folder of the zip (or the
     * zip's top level) that holds a mod_info.yaml, a mod.yaml, a DLL or a translation (.po) and has no
     * such folder above it, so these all work: one mod zipped with or without its folder, several mods
     * side by side, a copy of a PC's mods folder (Local, Steam and Dev alike), and a zip this class
     * exported. A mod already there under the same name is replaced. Returns the folder names installed.
     *
     * A zip with none of those signs is not refused: it is installed whole as one folder (its single
     * top folder, when it has just one) and its name goes to {@code unrecognized}, for the caller to
     * say that the game may not load it. What a mod is, the game decides.
     */
    public static List<String> importZip(File zip, File userDataDir, String fallbackName,
                                         List<String> unrecognized) throws IOException {
        File local = new File(dir(userDataDir), "Local");
        List<String> installed = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip)) {
            Set<String> roots = new TreeSet<>();   // "" is the zip's top level; others end with "/"
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                String file = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (file.startsWith("._")) continue;   // macOS resource forks
                if (file.equals("mod_info.yaml") || file.equals("mod.yaml") || file.endsWith(".dll") || file.endsWith(".po"))
                    roots.add(name.substring(0, name.lastIndexOf('/') + 1));
            }
            boolean recognized = !roots.isEmpty();
            if (!recognized) {
                // No sign of a mod: the whole zip as one folder. A zip that is one folder with
                // everything inside becomes that folder, not a folder in a folder.
                Set<String> topFolders = new TreeSet<>();
                boolean topFiles = false, any = false;
                for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String name = e.getName().replace('\\', '/');
                    any = true;
                    int slash = name.indexOf('/');
                    if (slash < 0) topFiles = true; else topFolders.add(name.substring(0, slash + 1));
                }
                if (!any) throw new IOException("the zip is empty");
                roots.add(!topFiles && topFolders.size() == 1 ? topFolders.iterator().next() : "");
            }
            List<String> top = new ArrayList<>();
            for (String r : roots) {
                boolean nested = false;
                for (String other : roots) nested |= !other.equals(r) && r.startsWith(other);
                if (!nested) top.add(r);
            }
            String localCanon = local.getCanonicalPath();
            for (String root : top) {
                String name = root.isEmpty() ? fallbackName
                        : root.substring(root.lastIndexOf('/', root.length() - 2) + 1, root.length() - 1);
                name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (name.isEmpty() || name.equals(".") || name.equals("..")) name = fallbackName;
                File target = new File(local, name);
                if (!target.getCanonicalPath().startsWith(localCanon + File.separator)) continue;
                if (target.exists() && !deleteTree(target)) throw new IOException("cannot replace " + name);
                if (!target.mkdirs()) throw new IOException("cannot create " + target);
                String targetCanon = target.getCanonicalPath();
                byte[] buf = new byte[1 << 16];
                for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    String entry = e.getName().replace('\\', '/');
                    if (e.isDirectory() || !entry.startsWith(root)) continue;
                    File out = new File(target, entry.substring(root.length()));
                    if (!out.getCanonicalPath().startsWith(targetCanon + File.separator)) continue;   // zip-slip guard
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
                    try (InputStream in = zf.getInputStream(e);
                         OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
                installed.add(name);
                if (!recognized && unrecognized != null) unrecognized.add(name);
            }
        }
        return installed;
    }

    /** As {@link #importZip}, for a document picked by the player. */
    public static List<String> importUri(Context context, Uri uri, File userDataDir, String fallbackName,
                                         List<String> unrecognized) throws IOException {
        File copy = File.createTempFile("mods", ".zip", context.getCacheDir());
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(copy)) {
                if (in == null) throw new IOException("cannot open the file");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return importZip(copy, userDataDir, fallbackName, unrecognized);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
    }

    /** Every mod folder of the instance as {@code mods/<Local|Steam|Dev>/<name>/...}. Returns their number. */
    public static int exportZip(File userDataDir, OutputStream rawOut) throws IOException {
        File mods = dir(userDataDir);
        int count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[1 << 16];
            for (String platform : SCANNED) {
                File[] folders = new File(mods, platform).listFiles();
                if (folders == null) continue;
                for (File folder : folders) {
                    if (!folder.isDirectory()) continue;
                    zipTree(folder, "mods/" + platform + "/" + folder.getName(), zos, buf);
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject read(File mods) {
        File f = new File(mods, "mods.json");
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
                JsonElement e = JsonParser.parseString(new String(all.toByteArray(), StandardCharsets.UTF_8));
                if (e.isJsonObject()) return e.getAsJsonObject();
            } catch (Exception ignored) {
                // unreadable: treated as no list; nothing is written unless the player changes something
            }
        }
        return new JsonObject();
    }

    private static void write(File mods, JsonObject root) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();
        File tmp = new File(mods, "mods.json.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(gson.toJson(root).getBytes(StandardCharsets.UTF_8));
        }
        File f = new File(mods, "mods.json");
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("cannot write mods.json");
        }
    }

    private static JsonArray entries(JsonObject root) {
        JsonElement e = root.get("mods");
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject entry(JsonObject root, Mod mod) {
        for (JsonElement el : entries(root)) {
            JsonObject e = object(el);
            JsonObject label = e == null ? null : object(e.get("label"));
            if (label == null) continue;
            int platform = intOf(label.get("distribution_platform"));
            String name = platform >= 0 && platform < PLATFORMS.length ? PLATFORMS[platform] : String.valueOf(platform);
            if (name.equals(mod.platform) && String.valueOf(mod.id).equals(stringOf(label.get("id")))) return e;
        }
        return null;
    }

    private static JsonObject object(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static int intOf(JsonElement e) {
        try {
            return e == null || e.isJsonNull() ? 0 : e.getAsInt();
        } catch (RuntimeException x) {
            return 0;
        }
    }

    private static String stringOf(JsonElement e) {
        try {
            return e == null || e.isJsonNull() ? null : e.getAsString();
        } catch (RuntimeException x) {
            return null;
        }
    }

    /** The value of a top-level {@code key: value} line of a small YAML file, unquoted; null when absent. */
    private static String yamlValue(File file, String key) {
        if (!file.isFile() || file.length() > 1 << 20) return null;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
            for (String line : new String(all.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("﻿")) line = line.substring(1);
                if (!line.startsWith(key + ":")) continue;
                String v = line.substring(key.length() + 1).trim();
                if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0))
                    v = v.substring(1, v.length() - 1);
                return v;
            }
        } catch (IOException ignored) {
            // no value
        }
        return null;
    }

    private static void zipTree(File dir, String prefix, ZipOutputStream zos, byte[] buf) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String name = prefix + "/" + f.getName();
            if (f.isDirectory()) {
                zipTree(f, name, zos, buf);
            } else {
                zos.putNextEntry(new ZipEntry(name));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
    }

    private static boolean deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) if (!deleteTree(k)) return false;
        return f.delete();
    }
}
