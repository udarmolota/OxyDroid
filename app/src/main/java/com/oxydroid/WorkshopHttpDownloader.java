package com.oxydroid;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Oxygen Not Included's Workshop mods, straight from their download links.
 *
 * Unlike RimWorld (whose mods are SteamPipe depot content, a manifest plus chunks), Oxygen Not
 * Included keeps its Workshop mods in Steam's older user-content storage: a mod is ONE file,
 * "mod_publish_data_file.zip", at a public https link that Steam's Web API gives for the item
 * (ISteamRemoteStorage/GetPublishedFileDetails: file_url). Its hcontent_file is that file's handle,
 * not a depot manifest, which is why a SteamPipe download of it got 404 from every server
 * (2026-11-24). No Steam sign-in is needed.
 *
 * Each mod goes to /Download/OxyDroid/&lt;Title&gt;_&lt;id&gt;.zip (SteamDownloadSpike.lastModZips for the
 * Game page's "install into an instance"); the Mods import unwraps that zip as it is.
 */
public class WorkshopHttpDownloader implements Runnable, Cancellable {

    private static final String TAG = "OxyDroid/WorkshopDL";

    private final List<Long> ids;
    private final SteamAnonModDownloader.Listener listener;
    private volatile boolean running = true;
    private volatile Thread thread;

    public WorkshopHttpDownloader(List<Long> ids, SteamAnonModDownloader.Listener listener) {
        this.ids = ids;
        this.listener = listener;
    }

    private void progress(String m) {
        Log.i(TAG, m);
        if (listener != null) listener.onProgress(m);
    }

    @Override public void cancel() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    @Override public void run() {
        thread = Thread.currentThread();
        AppStorage storage = AppStorage.requireSingleton();
        File dir = storage.getDownloadsDir();
        SteamDownloadSpike.lastModZips.clear();
        int ok = 0, failed = 0;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            if (listener != null) listener.onDone("Cannot create " + dir + " (grant All-files access?)");
            return;
        }
        for (int i = 0; i < ids.size() && running; i++) {
            long id = ids.get(i);
            try {
                SteamAnonModDownloader.PubInfo info = SteamAnonModDownloader.resolvePublishedFile(id);
                if (!info.ok) { progress("✗ " + id + ": not found on Steam."); failed++; continue; }
                if (info.consumerAppId != SteamDownloadSpike.APP_ID) {
                    progress("✗ " + id + ": '" + info.title + "' is not an Oxygen Not Included mod."); failed++; continue;
                }
                if (info.fileUrl == null || info.fileUrl.isEmpty()) {
                    progress("✗ " + id + ": '" + info.title + "' has no download link."); failed++; continue;
                }
                File zip = new File(dir, SteamAnonModDownloader.sanitizeFileName(info.title) + "_" + id + ".zip");
                progress("'" + info.title + "' (" + Math.max(1, info.fileSize / 1024) + " KB)…");
                if (!fetch(info.fileUrl, zip, i, ids.size())) {
                    failed++;
                    continue;
                }
                storage.publishToMediaIndex(zip, "application/zip");
                SteamDownloadSpike.lastModZips.add(zip);
                progress("✓ " + zip.getName());
                ok++;
            } catch (Throwable t) {
                Log.e(TAG, "mod " + id, t);
                progress("✗ " + id + ": " + t.getMessage());
                failed++;
            }
        }
        if (listener != null)
            listener.onDone(!running ? "Download cancelled."
                    : "Mods: " + ok + " downloaded" + (failed > 0 ? ", " + failed + " failed" : "") + ". Saved to " + dir);
    }

    /** The file at {@code url} into {@code dest}, through a .part file. */
    private boolean fetch(String url, File dest, int index, int count) throws Exception {
        File part = new File(dest.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) { progress("✗ HTTP " + code + " for " + dest.getName()); return false; }
            long total = c.getContentLengthLong();
            long done = 0;
            int lastPct = -1;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (!running) return false;
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0 && listener != null) {
                        // Over the whole list: this mod's share of it.
                        int pct = (int) ((index * 100L + done * 100L / total) / count);
                        if (pct != lastPct) { listener.onPercent(pct); lastPct = pct; }
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        if (!part.renameTo(dest)) { progress("✗ cannot save " + dest.getName()); return false; }
        return true;
    }
}
