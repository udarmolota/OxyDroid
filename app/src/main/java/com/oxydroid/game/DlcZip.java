package com.oxydroid.game;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A DLC ZIP (SteamDownloadSpike.forDlcs: OxygenNotIncluded_DLC_&lt;ids&gt;_build&lt;build&gt;.zip in
 * /Download/OxyDroid) added to an instance: its files go into the game folder, where the DLC content
 * lives (OxygenNotIncluded_Data/StreamingAssets/...), and its DLC ids join the instance's DLC list
 * (GameInstance.DLC_LIST), which unlocks them at the next launch.
 *
 * A ZIP without that list is refused, so a game or mod ZIP picked here by mistake is not unpacked over
 * the game. The ZIP should come from the same game build as the instance: its name carries the build.
 */
public final class DlcZip {

    private DlcZip() {}

    /** Adds the DLC ZIP at {@code uri} to {@code gi}; returns the DLC ids it brought. */
    public static List<Integer> install(Context context, Uri uri, GameInstance gi) throws IOException {
        // First pass: is it a DLC ZIP at all, and which DLCs.
        List<Integer> ids = null;
        try (ZipInputStream zin = open(context, uri)) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                if (!e.isDirectory() && name(e).equals(GameInstance.DLC_LIST)) {
                    ids = parseIds(readAll(zin));
                    break;
                }
            }
        }
        if (ids == null || ids.isEmpty())
            throw new IOException("this is not a DLC ZIP from OxyDroid (it has no " + GameInstance.DLC_LIST + ")");

        File gameDir = new File(gi.getGamePath());
        String root = gameDir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream zin = open(context, uri)) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                String rel = name(e);
                if (rel.isEmpty() || rel.equals(GameInstance.DLC_LIST)) continue;
                File out = new File(gameDir, rel);
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("unsafe entry " + rel);
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("cannot create " + parent);
                try (OutputStream os = new FileOutputStream(out)) {
                    for (int n; (n = zin.read(buf)) > 0; ) os.write(buf, 0, n);
                }
            }
        }
        TreeSet<Integer> all = new TreeSet<>(gi.dlcs());
        all.addAll(ids);
        GameInstance.writeDlcList(gameDir, all);
        return ids;
    }

    private static ZipInputStream open(Context context, Uri uri) throws IOException {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open " + uri);
        return new ZipInputStream(new BufferedInputStream(in, 1 << 16));
    }

    private static String name(ZipEntry e) {
        String n = e.getName().replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        return n;
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        return sb.toString();
    }

    private static List<Integer> parseIds(String text) {
        List<Integer> ids = new ArrayList<>();
        for (String line : text.split("\n")) {
            try { if (!line.trim().isEmpty()) ids.add(Integer.parseInt(line.trim())); } catch (NumberFormatException ignored) { }
        }
        return ids;
    }
}
