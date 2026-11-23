package com.oxydroid.game;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The macOS build's simulation library, which the native engine runs (see NativeEngine.macSimDll):
 * {@code OxygenNotIncluded.app/Contents/PlugIns/SimDLL.bundle/Contents/MacOS/SimDLL}, kept in an
 * instance as {@code macos/SimDLL}. A player who imports the game as a ZIP has to bring it from the
 * macOS build, and it may come in any of the shapes that build is found in: the file itself, a ZIP
 * of the whole macOS game, of OxygenNotIncluded.app or of SimDLL.bundle, or inside the game's own
 * ZIP next to the Linux build. Whatever the shape, the file is recognised by its Mach-O header.
 */
public final class MacSimDll {

    /** Where the file sits inside a macOS build, from the .app down. */
    private static final String BUNDLE_PATH = "SimDLL.bundle/Contents/MacOS/SimDLL";

    private MacSimDll() {}

    public static boolean present(File gameDir) {
        return NativeEngine.macSimDll(gameDir).isFile();
    }

    /** A Mach-O file: universal (fat), 64-bit or 32-bit, either byte order. */
    static boolean isMachO(byte[] head) {
        if (head == null || head.length < 4) return false;
        int be = (head[0] & 0xff) << 24 | (head[1] & 0xff) << 16 | (head[2] & 0xff) << 8 | (head[3] & 0xff);
        return be == 0xCAFEBABE || be == 0xCAFEBABF        // universal
                || be == 0xFEEDFACF || be == 0xCFFAEDFE    // 64-bit
                || be == 0xFEEDFACE || be == 0xCEFAEDFE;   // 32-bit
    }

    private static boolean isMachO(File f) {
        if (!f.isFile() || f.length() < 4096) return false;
        byte[] head = new byte[4];
        try (InputStream in = new FileInputStream(f)) {
            return in.read(head) == 4 && isMachO(head);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The macOS SimDLL somewhere below {@code dir} (an extracted ZIP), or null: the file at the
     * bundle's path first, else any Mach-O file named SimDLL.
     */
    @Nullable
    public static File find(File dir) {
        File named = null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory()) {
                File inner = find(k);
                if (inner != null && inner.getPath().replace('\\', '/').endsWith(BUNDLE_PATH)) return inner;
                if (inner != null && named == null) named = inner;
            } else if (k.getName().equals("SimDLL") && isMachO(k)) {
                if (k.getPath().replace('\\', '/').endsWith(BUNDLE_PATH)) return k;
                if (named == null) named = k;
            }
        }
        return named;
    }

    /** Puts {@code file} in place as the instance's macOS SimDLL (moves it when it can). */
    public static void place(File file, File gameDir) throws IOException {
        File dest = NativeEngine.macSimDll(gameDir);
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        if (dest.exists() && !dest.delete()) throw new IOException("cannot replace " + dest);
        if (file.renameTo(dest)) return;
        try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
    }

    /**
     * The file the player picked, in any of the shapes above, as the instance's macOS SimDLL. Throws
     * with a short reason when the file holds no macOS SimDLL.
     */
    public static void install(Context context, Uri uri, File gameDir) throws IOException {
        File copy = File.createTempFile("simdll", ".bin", context.getCacheDir());
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(copy)) {
                if (in == null) throw new IOException("cannot open the file");
                copy(in, out);
            }
            byte[] head = new byte[4];
            try (InputStream in = new FileInputStream(copy)) {
                if (in.read(head) != 4) throw new IOException("the file is empty");
            }
            if (isMachO(head)) {
                place(copy, gameDir);
                return;
            }
            if (head[0] != 'P' || head[1] != 'K') throw new IOException("not the macOS SimDLL, nor a ZIP");
            File out = File.createTempFile("simdll", ".bin", context.getCacheDir());
            try {
                if (!extractFromZip(copy, out)) throw new IOException("no macOS SimDLL in this ZIP");
                place(out, gameDir);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
    }

    /** The SimDLL entry of a ZIP (the bundle's path first, else a Mach-O entry named SimDLL) into out. */
    private static boolean extractFromZip(File zip, File out) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry best = null;
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (!base.equals("SimDLL")) continue;
                if (name.endsWith(BUNDLE_PATH)) { best = e; break; }
                if (best == null) best = e;
            }
            if (best == null) return false;
            try (InputStream in = new BufferedInputStream(zf.getInputStream(best));
                 OutputStream os = new FileOutputStream(out)) {
                copy(in, os);
            }
        }
        return isMachO(out);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
