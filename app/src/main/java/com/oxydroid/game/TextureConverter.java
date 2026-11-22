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
        if (!load()) return done;
        File[] files = dataDir.listFiles();
        if (files == null) return done;
        List<File> pairs = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        long total = 0;
        for (File assets : files) {
            if (!assets.isFile() || !assets.getName().endsWith(".assets")) continue;
            File resS = new File(dataDir, assets.getName() + ".resS");
            if (!resS.isFile()) continue;
            long pending = nativePending(assets.getAbsolutePath(), resS.getAbsolutePath());
            // A journal left by an interrupted run means one texture is half-written, whatever the
            // records say: that pair goes through the converter, which puts it back first.
            boolean interrupted = journal(workDir, assets).length() > 0;
            if (pending <= 0 && !interrupted) continue;
            pairs.add(assets);
            sizes.add(Math.max(pending, 0));
            total += Math.max(pending, 0);
        }
        if (pairs.isEmpty()) return done;
        Log.i(TAG, pairs.size() + " file(s) to convert, " + (total >> 20) + " MB of BC texture data");
        long before = 0;
        for (int i = 0; i < pairs.size(); i++) {
            File assets = pairs.get(i);
            final long base = before, all = total;
            Progress inner = progress == null ? null : (d, t) -> progress.onProgress(base + d, all);
            String summary = nativeConvert(assets.getAbsolutePath(), assets.getAbsolutePath() + ".resS",
                    journal(workDir, assets).getAbsolutePath(), inner);
            if (summary == null)
                throw new IOException("texture conversion failed for " + assets.getName() + " (see the log)");
            done.add(assets.getName() + ": " + summary);
            before += sizes.get(i);
            if (progress != null) progress.onProgress(before, total);
        }
        return done;
    }

    private static File journal(File workDir, File assets) {
        return new File(workDir, assets.getName() + ".astc-journal");
    }

    private static native int nativeGpuFormats();
    private static native long nativePending(String assets, String resS);
    private static native String nativeConvert(String assets, String resS, String journal, Progress progress);
}
