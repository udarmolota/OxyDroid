package com.oxydroid.game;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The game's BC textures (DXT1, DXT5, BC7: made for PC GPUs) converted once to ASTC, in place, for a
 * GPU that cannot sample BC (Mali, PowerVR). Without this Unity unpacks each of them to RGBA32 on load,
 * four to eight times the memory, which Oxygen Not Included's 1.5 GB of them does not allow.
 *
 * The work is native (app/src/main/cpp/texconv/texconv.cpp, liboxydroidtex.so); see there for what is
 * rewritten and how an interrupted run is recovered. The files keep their size, and a pair with
 * nothing left to convert is not written to, so {@link #convertIfNeeded} is called at every launch.
 *
 * ONE-WAY: a converted instance no longer has its BC data. On a GPU that samples BC the conversion is
 * not run, and ASTC textures still work there (every Android Vulkan GPU the engine runs on has ASTC).
 */
public final class TextureConverter {
    private static final String TAG = "OxyDroid/texconv";

    /** In the instance's environment field: 1 converts on any GPU (a test on an Adreno), 0 never. */
    public static final String ENV = "OXYDROID_ASTC";

    public interface Progress {
        /** Bytes of BC texture data done and in all, over every file of the instance. */
        void onProgress(long doneBytes, long totalBytes);
    }

    private static Boolean loaded;

    private TextureConverter() {}

    private static synchronized boolean load() {
        if (loaded == null) {
            try {
                System.loadLibrary("oxydroidtex");
                loaded = true;
            } catch (Throwable e) {
                Log.e(TAG, "liboxydroidtex.so not loaded", e);
                loaded = false;
            }
        }
        return loaded;
    }

    /**
     * Whether this phone needs the conversion: its GPU has no BC formats and has ASTC. {@code override}
     * is the value of {@link #ENV} from the instance's environment field, or null.
     */
    public static boolean wanted(String override) {
        if ("0".equals(override)) return false;
        if (!load()) return false;
        if ("1".equals(override)) return true;
        int formats = nativeGpuFormats();
        Log.i(TAG, "GPU texture formats: " + (formats < 0 ? "unknown" : ((formats & 1) != 0 ? "BC " : "no BC ")
                + ((formats & 2) != 0 ? "ASTC" : "no ASTC")));
        return formats >= 0 && (formats & 1) == 0 && (formats & 2) != 0;
    }

    /**
     * Converts every .assets/.resS pair of {@code dataDir} that still holds BC textures. Returns the
     * summaries of the pairs that had work, empty when there was none. {@code workDir} keeps the
     * journal of the texture being rewritten.
     */
    public static List<String> convertIfNeeded(File dataDir, File workDir, Progress progress) throws IOException {
        List<String> done = new ArrayList<>();
        if (!load()) {
            history(workDir, "converter library not loaded: textures left as they are");
            return done;
        }
        File[] files = dataDir.listFiles();
        if (files == null) return done;
        List<File> pairs = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        long total = 0;
        int checked = 0, unreadable = 0;
        for (File assets : files) {
            if (!assets.isFile() || !assets.getName().endsWith(".assets")) continue;
            File resS = new File(dataDir, assets.getName() + ".resS");
            if (!resS.isFile()) continue;
            checked++;
            long pending = nativePending(assets.getAbsolutePath(), resS.getAbsolutePath());
            if (pending < 0) unreadable++;
            // A journal left by an interrupted run means one texture is half-written, whatever the
            // records say: that pair goes through the converter, which puts it back first.
            boolean interrupted = journal(workDir, assets).length() > 0;
            if (pending <= 0 && !interrupted) continue;
            pairs.add(assets);
            sizes.add(Math.max(pending, 0));
            total += Math.max(pending, 0);
        }
        String unread = unreadable > 0 ? ", " + unreadable + " not readable" : "";
        if (pairs.isEmpty()) {
            // Every launch comes here; the history gets this once, so it says the instance was looked at.
            // No pairs at all is the usual case after the first launch: the files live in the archive.
            if (checked > 0 && !historyFile(workDir).isFile())
                history(workDir, "checked " + checked + " file(s)" + unread + ": no BC texture data, nothing to convert");
            return done;
        }
        Log.i(TAG, pairs.size() + " file(s) to convert, " + (total >> 20) + " MB of BC texture data");
        history(workDir, "checked " + checked + " file(s)" + unread + ": converting " + pairs.size()
                + ", " + (total >> 20) + " MB of BC texture data");
        long started = System.currentTimeMillis();
        long before = 0;
        for (int i = 0; i < pairs.size(); i++) {
            File assets = pairs.get(i);
            final long base = before, all = total;
            Progress inner = progress == null ? null : (d, t) -> progress.onProgress(base + d, all);
            String summary = nativeConvert(assets.getAbsolutePath(), assets.getAbsolutePath() + ".resS",
                    journal(workDir, assets).getAbsolutePath(), inner);
            if (summary == null) {
                history(workDir, "FAILED on " + assets.getName() + " (see the launcher log)");
                throw new IOException("texture conversion failed for " + assets.getName() + " (see the log)");
            }
            done.add(assets.getName() + ": " + summary);
            history(workDir, "  " + assets.getName() + ": " + summary);
            before += sizes.get(i);
            if (progress != null) progress.onProgress(before, total);
        }
        history(workDir, "done in " + (System.currentTimeMillis() - started) / 1000 + " s");
        return done;
    }

    /**
     * The conversion's own record, kept in the instance (native_engine/textures.log) for a report: the
     * launcher's log of the first launch, where it ran, is soon rotated away, and afterwards the files
     * live only in the player's archive, where they cannot be checked again.
     */
    private static final String HISTORY = "textures.log";

    private static File historyFile(File workDir) {
        return new File(workDir, HISTORY);
    }

    private static void history(File workDir, String line) {
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        try (java.io.FileWriter out = new java.io.FileWriter(historyFile(workDir), true)) {
            out.write(stamp + " " + line + "\n");
        } catch (IOException e) {
            Log.w(TAG, "texture history not written: " + e);
        }
    }

    /** The conversion's record for a report, or a note that there is none (it never ran on this instance). */
    public static String history(File workDir) {
        File f = historyFile(workDir);
        if (!f.isFile()) return "(none: no conversion ran on this instance)";
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "unreadable: " + e;
        }
    }

    /** For a report: what the GPU samples and how much BC texture data the instance still holds. */
    public static String describe(File dataDir) {
        if (!load()) return "converter library not loaded";
        int formats = nativeGpuFormats();
        String gpu = formats < 0 ? "GPU formats unknown"
                : "GPU: BC " + ((formats & 1) != 0 ? "yes" : "NO") + ", ASTC " + ((formats & 2) != 0 ? "yes" : "NO");
        long pending = 0;
        int unreadable = 0, checked = 0;
        File[] files = dataDir.listFiles();
        if (files != null) {
            for (File assets : files) {
                if (!assets.isFile() || !assets.getName().endsWith(".assets")) continue;
                File resS = new File(dataDir, assets.getName() + ".resS");
                if (!resS.isFile()) continue;
                checked++;
                long n = nativePending(assets.getAbsolutePath(), resS.getAbsolutePath());
                if (n < 0) unreadable++; else pending += n;
            }
        }
        // After the first launch the big data files live only in the player's archive (NativeEngine
        // moveIntoArchive): what they hold was settled by the conversion before that launch.
        if (checked == 0) return "data files are in the player's archive (converted before the first launch if needed); " + gpu;
        return (pending == 0 ? "all converted to ASTC" : (pending >> 20) + " MB still in BC formats")
                + (unreadable > 0 ? ", " + unreadable + " file(s) not readable" : "") + "; " + gpu;
    }

    private static File journal(File workDir, File assets) {
        return new File(workDir, assets.getName() + ".astc-journal");
    }

    private static native int nativeGpuFormats();
    private static native long nativePending(String assets, String resS);
    private static native String nativeConvert(String assets, String resS, String journal, Progress progress);
}
