package com.oxydroid.controls;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Icon packs for the on-screen buttons: a set of pictures named after the game's actions, put on a
 * whole layout in one go (each button gets the icon of what it does) or picked one by one.
 *
 * <p>A pack is a zip of PNG files, white on a transparent background (the button tints them with
 * its colour), named by action: click, right_click, middle_click, zoom_in, zoom_out, pause,
 * speed_1, menu, camera_up, camera_left, camera_down, camera_right, rotate_right, keyboard,
 * toggle_controls, and the free ones a player can pick by hand. An optional pack.json gives the name: {"format": 1, "name": "..."}.
 * Any other PNG in the zip is imported too, as an icon to pick by hand. A pack of colour pictures says
 * "tint": false in pack.json: its icons are then shown in their own colours, not the button's. The packs live outside the
 * instances (app files/control_icon_packs/<id>/); the built-in ones in the APK's assets. An icon
 * put on a button is copied into the layout's own icons folder, so an exported layout carries it.
 */
public final class IconPacks {
    private IconPacks() {}

    public static final String ASSET_DIR = "control_icon_packs";
    private static final String DIR = "control_icon_packs";
    private static final int MAX_ICON_PX = 256;

    public static final class Pack {
        public final String id;
        public final String name;
        public final boolean builtIn;
        /** False for colour pictures (pack.json "tint": false): shown untinted. */
        public final boolean tint;
        /** Icon names (file names without .png), sorted. */
        public final List<String> icons;

        Pack(String id, String name, boolean builtIn, boolean tint, List<String> icons) {
            this.id = id;
            this.name = name;
            this.builtIn = builtIn;
            this.tint = tint;
            this.icons = icons;
        }
    }

    private static File importedDir() {
        return new File(com.oxydroid.AppStorage.requireSingleton().getHomePath(), DIR);
    }

    /** The built-in packs, then the imported ones. */
    @NonNull
    public static List<Pack> list(@NonNull Context context) {
        List<Pack> packs = new ArrayList<>();
        try {
            String[] ids = context.getAssets().list(ASSET_DIR);
            if (ids != null) {
                for (String id : ids) {
                    String[] files = context.getAssets().list(ASSET_DIR + "/" + id);
                    if (files == null) continue;
                    byte[] json = null;
                    try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + id + "/pack.json")) {
                        json = readAll(in);
                    } catch (IOException ignored) {
                    }
                    packs.add(new Pack(id, packName(json, id), true, packTint(json), iconNames(files)));
                }
            }
        } catch (IOException ignored) {
        }
        File[] dirs = importedDir().listFiles(File::isDirectory);
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                byte[] json = null;
                File jsonFile = new File(d, "pack.json");
                if (jsonFile.isFile()) {
                    try (InputStream in = new java.io.FileInputStream(jsonFile)) {
                        json = readAll(in);
                    } catch (IOException ignored) {
                    }
                }
                String[] files = d.list();
                packs.add(new Pack(d.getName(), packName(json, d.getName()), false, packTint(json),
                        iconNames(files != null ? files : new String[0])));
            }
        }
        return packs;
    }

    private static List<String> iconNames(String[] files) {
        List<String> icons = new ArrayList<>();
        for (String f : files)
            if (f.toLowerCase(Locale.ROOT).endsWith(".png")) icons.add(f.substring(0, f.length() - 4));
        java.util.Collections.sort(icons);
        return icons;
    }

    private static boolean packTint(byte[] json) {
        if (json == null) return true;
        try {
            return new JSONObject(new String(json, StandardCharsets.UTF_8)).optBoolean("tint", true);
        } catch (Exception e) {
            return true;
        }
    }

    private static String packName(byte[] json, String fallback) {
        if (json == null) return fallback;
        try {
            String n = new JSONObject(new String(json, StandardCharsets.UTF_8)).optString("name", "").trim();
            return n.isEmpty() ? fallback : n;
        } catch (Exception e) {
            return fallback;
        }
    }

    @Nullable
    public static Bitmap load(@NonNull Context context, @NonNull Pack pack, @NonNull String icon) {
        if (pack.builtIn) {
            try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + pack.id + "/" + icon + ".png")) {
                return BitmapFactory.decodeStream(in);
            } catch (IOException e) {
                return null;
            }
        }
        File f = new File(new File(importedDir(), pack.id), icon + ".png");
        return f.isFile() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    /**
     * Imports a pack zip: every PNG (scaled down to 256 px, transparent borders trimmed, as a single
     * imported icon is) and pack.json. Its id comes from the zip's file name. Runs on the caller's
     * thread. Returns the pack.
     */
    @NonNull
    public static Pack importZip(@NonNull Context context, @NonNull Uri uri, @Nullable String displayName)
            throws IOException {
        String base = displayName != null ? displayName.replaceAll("(?i)\\.zip$", "") : "pack";
        String id = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (id.isEmpty()) id = "pack";
        File dir = new File(importedDir(), id);
        File tmp = new File(importedDir(), id + ".importing");
        deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        int count = 0;
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("cannot open the file");
            ZipInputStream zip = new ZipInputStream(raw);
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                // Only the file name: an entry path must never leave the pack folder (zip slip).
                String name = new File(e.getName()).getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("pack.json")) {
                    writeFile(new File(tmp, "pack.json"), readAll(zip));
                } else if (lower.endsWith(".png")) {
                    String icon = lower.substring(0, lower.length() - 4).replaceAll("[^a-z0-9_-]+", "_");
                    Bitmap bmp = decodeScaled(readAll(zip));
                    if (bmp == null || icon.isEmpty()) continue;
                    try (FileOutputStream out = new FileOutputStream(new File(tmp, icon + ".png"))) {
                        trimTransparentBorder(bmp).compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    count++;
                }
            }
        } catch (IOException e) {
            deleteTree(tmp);
            throw e;
        }
        if (count == 0) {
            deleteTree(tmp);
            throw new IOException("no PNG pictures in the zip");
        }
        deleteTree(dir);
        if (!tmp.renameTo(dir)) {
            deleteTree(tmp);
            throw new IOException("cannot store the pack");
        }
        for (Pack p : list(context))
            if (!p.builtIn && p.id.equals(id)) return p;
        throw new IOException("the pack did not store");
    }

    public static boolean delete(@NonNull Pack pack) {
        return !pack.builtIn && deleteTree(new File(importedDir(), pack.id));
    }

    // ---- which icon a button gets ----------------------------------------------------------

    /**
     * An icon of a built-in pack by the file name a layout stores for it ("pack_&lt;id&gt;_&lt;icon&gt;.png"),
     * read straight from the APK's assets, or null. Lets the bundled default layout carry icons
     * without an icons folder: a new instance has none until something is saved.
     */
    @Nullable
    public static Bitmap loadBuiltInByFileName(@NonNull Context context, @Nullable String fileName) {
        if (fileName == null || !fileName.startsWith("pack_") || !fileName.endsWith(".png")) return null;
        try {
            String[] ids = context.getAssets().list(ASSET_DIR);
            if (ids == null) return null;
            for (String id : ids) {
                String prefix = "pack_" + id + "_";
                if (!fileName.startsWith(prefix)) continue;
                String icon = fileName.substring(prefix.length(), fileName.length() - 4);
                try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + id + "/" + icon + ".png")) {
                    return BitmapFactory.decodeStream(in);
                } catch (IOException ignored) {
                    // not in this pack: another pack's id may be a prefix of this one
                }
            }
        } catch (IOException ignored) {
            // no packs in the APK
        }
        return null;
    }

    private static final Map<Set<String>, String> ACTIONS = new HashMap<>();

    private static void action(String icon, String... bindings) {
        ACTIONS.put(new HashSet<>(Arrays.asList(bindings)), icon);
    }

    // Oxygen Not Included's default keys (the game's own keybindings, and the launcher's default
    // layout): a button bound to exactly this set gets the icon of that name. The names are the ones
    // the built-in RimDroid pack uses, so it applies as it is; a pack of one's own names its files
    // the same way (IconPacksFragment.ACTION_NAMES).
    static {
        action("click", "MOUSE_BUTTON_LEFT");
        action("right_click", "MOUSE_BUTTON_RIGHT");
        action("middle_click", "MOUSE_BUTTON_WHEEL");
        action("zoom_in", "MOUSE_WHEEL_UP");
        action("zoom_out", "MOUSE_WHEEL_DOWN");
        action("pause", "KEY_SPACE");
        action("speed_1", "KEY_TAB");
        action("menu", "KEY_ESCAPE");
        action("camera_up", "KEY_W");
        action("camera_left", "KEY_A");
        action("camera_down", "KEY_S");
        action("camera_right", "KEY_D");
        action("rotate_right", "KEY_O");
        action("keyboard", "UI_TOGGLE_KEYBOARD");
        action("toggle_controls", "UI_TOGGLE_OVERLAY");
    }

    /** The action a button performs, as an icon name, or null when it has no icon of its own. */
    @Nullable
    public static String actionFor(@NonNull AbstractControlElement element) {
        return actionFor(element.getBindings());
    }

    @Nullable
    public static String actionFor(@Nullable GLFWBinding[] bindings) {
        Set<String> set = new HashSet<>();
        if (bindings != null)
            for (GLFWBinding b : bindings)
                if (b != null) set.add(b.name());
        return set.isEmpty() ? null : ACTIONS.get(set);
    }

    private static boolean isButton(AbstractControlElement.Type type) {
        switch (type) {
            case BUTTON_CIRCLE: case BUTTON_RECT:
            case DPAD_UP: case DPAD_RIGHT: case DPAD_DOWN: case DPAD_LEFT:
                return true;
            default:
                return false;
        }
    }

    /**
     * The same as {@link #apply} on an instance's saved layout, without the editor (the drawer's
     * icon pack screen). An instance still on the bundled default gets that default first.
     * Returns how many buttons got an icon, or -1 when the layout could not be read or saved.
     */
    public static int applyToInstance(@NonNull Context context, @NonNull Pack pack, @NonNull String instanceName) {
        String json = ControlsStorage.readLayoutJson(instanceName);
        if (json == null) json = ControlsStorage.readAsset(context, ControlsStorage.DEFAULT_ASSET);
        List<ControlElementDescription> layout = ControlsStorage.parse(json);
        File dir = ControlsStorage.iconsDir(instanceName);
        if (layout == null || dir == null || (!dir.isDirectory() && !dir.mkdirs())) return -1;
        int applied = 0;
        for (int i = 0; i < layout.size(); i++) {
            ControlElementDescription d = layout.get(i);
            String icon = isButton(d.type) ? actionFor(d.bindings) : null;
            if (icon == null || !pack.icons.contains(icon)) continue;
            String fileName = copyIcon(context, pack, icon, dir);
            if (fileName == null) continue;
            layout.set(i, d.withIcon(fileName, !pack.tint));
            applied++;
        }
        if (applied > 0 && !ControlsStorage.writeLayoutJson(instanceName, ControlsStorage.toJson(layout))) return -1;
        return applied;
    }

    /** Writes one pack icon into a layout's icons folder; its file name there, or null. */
    @Nullable
    private static String copyIcon(Context context, Pack pack, String icon, File dir) {
        Bitmap bmp = load(context, pack, icon);
        if (bmp == null) return null;
        String fileName = "pack_" + pack.id + "_" + icon + ".png";
        try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            return fileName;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Puts the pack on every button whose action it has an icon for; the others keep what they show.
     * Returns how many buttons got an icon.
     */
    public static int apply(@NonNull Context context, @NonNull Pack pack, @NonNull InputControlsView view) {
        int applied = 0;
        for (AbstractControlElement el : view.getControlElements()) {
            if (!(el instanceof ButtonControlElement)) continue;
            String icon = actionFor(el);
            if (icon != null && pack.icons.contains(icon) && setIcon(context, pack, icon, el, view)) applied++;
        }
        view.invalidate();
        return applied;
    }

    /** Copies one pack icon into the layout's icons folder and shows it on the element. */
    public static boolean setIcon(@NonNull Context context, @NonNull Pack pack, @NonNull String icon,
                                  @NonNull AbstractControlElement el, @NonNull InputControlsView view) {
        File dir = view.getControlsIconsDir();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return false;
        String fileName = copyIcon(context, pack, icon, dir);
        if (fileName == null) return false;
        boolean noTint = !pack.tint;
        if (el instanceof ButtonControlElement) ((ButtonControlElement) el).setCustomIcon(fileName, noTint);
        else if (el instanceof DpadControlElement) ((DpadControlElement) el).setCustomIcon(fileName, noTint);
        else if (el instanceof RadialMenuControlElement) ((RadialMenuControlElement) el).setCustomIcon(fileName, noTint);
        else return false;
        return true;
    }

    // ---- pictures -------------------------------------------------------------------------

    @Nullable
    private static Bitmap decodeScaled(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > MAX_ICON_PX || bounds.outHeight / sample > MAX_ICON_PX) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    /**
     * Crops fully/near-transparent borders off an icon so its visible content maps directly to the
     * on-screen box. Returns the original bitmap if it has no trimmable border (fully opaque to the
     * edges) or is entirely transparent.
     */
    public static Bitmap trimTransparentBorder(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        final int ALPHA_MIN = 8; // treat alpha <= 8 as transparent
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int a = (px[row + x] >>> 24) & 0xff;
                if (a > ALPHA_MIN) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return src;               // fully transparent — leave as-is
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return src; // nothing to trim
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    // ---- files ----------------------------------------------------------------------------

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static boolean deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        return !f.exists() || f.delete();
    }
}
