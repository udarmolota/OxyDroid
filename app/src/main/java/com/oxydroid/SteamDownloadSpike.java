package com.oxydroid;

import android.util.Log;

import androidx.annotation.NonNull;

import in.dragonbra.javasteam.depotdownloader.DepotDownloader;
import in.dragonbra.javasteam.depotdownloader.IDownloadListener;
import in.dragonbra.javasteam.depotdownloader.data.AppItem;
import in.dragonbra.javasteam.depotdownloader.data.DownloadItem;
import in.dragonbra.javasteam.depotdownloader.data.PubFileItem;
import in.dragonbra.javasteam.enums.EDepotFileFlag;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.cdn.Client;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamcontent.CDNAuthToken;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.types.AsyncJobMultiple;
import in.dragonbra.javasteam.types.ChunkData;
import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.util.log.DefaultLogListener;
import in.dragonbra.javasteam.util.log.LogManager;

import com.oxydroid.game.GameDescriptor;
import com.oxydroid.game.GameInstance;
import com.oxydroid.game.GameInstanceManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.GlobalScope;

/**
 * SPIKE — Milestone 1 of the in-app Steam downloader (memory in_app_game_downloader.md).
 *
 * Proves JavaSteam's high-level {@link DepotDownloader} can fetch the RimWorld (Linux) depot
 * ON THE DEVICE: log in (credentials → Steam-Mobile approval push, same as Milestone 0), receive
 * the account's licenses, then run DepotDownloader for the RimWorld Linux depot. The downloader
 * fetches the manifest, the (ownership-gated) depot key, downloads + decompresses the CDN chunks
 * (VZip via xz / VZstd via zstd-jni) and lays them out under an absolute install dir.
 *
 * NO token storage: the refresh token is kept ONLY in memory (a field) for this run, never written
 * to disk — exactly per the project constraint. The in-memory copy lets us re-logon transparently
 * after a transient CM disconnect WITHOUT another Steam Mobile approval.
 *
 * Resilience: the first on-device test failed because the CM websocket dropped (the app was briefly
 * backgrounded for the Steam Mobile approval) and we gave up on the first disconnect. Now we
 * reconnect + retry the download up to {@link #MAX_DOWNLOAD_ATTEMPTS} times. The download params
 * mirror the user's known-good DepotDownloader command exactly (explicit depot + manifest) to
 * remove the DLC-depot iteration and the manifest-version-resolution round trip.
 *
 * Run on a BACKGROUND thread (blocks in a callback loop + awaitCompletion). Pure network client.
 */
public class SteamDownloadSpike implements Runnable, IDownloadListener, Cancellable {

    private static final String TAG = "OxyDroid/SteamDL";

    /** Oxygen Not Included on Steam: `DepotDownloader -app 457140 -depot 457143`, newest public build. */
    public static final int APP_ID = 457140;
    private static final int LINUX_DEPOT = 457143;
    /**
     * The macOS depot of the same app. One file of it is needed: the simulation library, which the
     * Linux depot has for x86-64 only and the macOS one with an arm64 slice (NativeEngine.macSimDll,
     * unity/src/main/cpp/simdll). It must be the same build as the Linux files.
     */
    private static final int MAC_DEPOT = 457142;
    private static final String MAC_SIMDLL = "SimDLL.bundle/Contents/MacOS/SimDLL";

    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    /** Re-tries of the INITIAL sign-in if the CM connection drops mid-approval (Steam-Mobile excursion). */
    private static final int MAX_AUTH_ATTEMPTS = 3;

    public interface Listener {
        /** Steam Guard code prompt (only if the account is not approved via the Steam Mobile push). */
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
        /** Human-readable progress line for an on-screen status view (NOT a toast). */
        void onProgress(String message);
        /** Overall depot progress 0..100 (for a determinate progress bar). Default no-op so older
         *  listeners (e.g. the debug button) need not implement it. */
        default void onPercent(int percent) {}
        /** Terminal message (success or error). */
        void onDone(String message);
    }

    // Chunks in flight. The wall clock of a depot download is CDN round-trips, not disk or CPU, so a
    // dozen requests at once multiplies throughput; memory stays at one chunk buffer per worker
    // (about 1 MB), which is the whole reason this pipeline replaced JavaSteam's DepotDownloader —
    // that one pre-allocates every file and buffers chunks in parallel, and on a 256 MB app heap a
    // multi-GB game walked into OutOfMemoryError partway through (reported twice, 2026-07-25).
    private static final int MAX_WORKERS = 12;
    /** Steam's CDN edge nodes 503 under load; retry patiently on another server rather than fail. */
    private static final int MAX_CHUNK_TRIES = 30;

    private final String username, password;
    private final String instanceName;   // GAME mode: download lands in instances/<name>
    private final String installDir;      // GAME mode: ABSOLUTE path = AppStorage.getInstanceDir(name)
    private final boolean manifestOnly;
    private final long manifestId;        // 0 = default to the recommended 1.5 build; >0 = pin this build
    private final List<Long> workshopIds; // MODS mode (non-null) → download these Workshop items (logged-in)
    private final Listener listener;
    /** DLC mode: only the content of the DLCs the account owns, into an instance that has the game. */
    private boolean dlcOnly;
    /** Workshop mode with an instance: the items are installed into its mods (OniMods), not zipped to Download. */
    private File modsUserDataDir;
    /** The depot the pipeline is downloading now (the game's Linux depot, or a DLC's). */
    private volatile int currentDepot = LINUX_DEPOT;

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private List<License> licenseList;

    // In-memory session (never persisted) — reused to re-logon after a reconnect without re-approval.
    private volatile String accountName;
    private volatile String refreshToken;

    private volatile boolean running;
    private volatile Thread workerThread;          // the depot-download thread (interrupted on cancel)
    private volatile boolean cancelled;            // user requested cancel
    private volatile boolean doneEmitted;          // ensure exactly one terminal onDone
    private volatile boolean downloadStarted;     // a download attempt is queued/running this connection
    private volatile boolean downloadInProgress;   // a DepotDownloader is actively running
    private volatile boolean downloadCompleted;    // succeeded — stop everything
    private volatile Throwable lastError;          // set by onDownloadFailed; checked after awaitCompletion
    /** Steam build id of the manifest being downloaded (0 when a manifest id was pinned by hand). */
    private volatile long buildId;
    private int downloadAttempts;
    private int authAttempts;                      // initial-sign-in attempts (drops during approval)

    /** GAME mode: download the game into instances/&lt;name&gt; and make it launchable. */
    public SteamDownloadSpike(String username, String password, String instanceName,
                              boolean manifestOnly, long manifestId, Listener listener) {
        this.username = username;
        this.password = password;
        this.instanceName = instanceName;
        // Download straight into the instance's game dir; once the game binary lands there,
        // GameInstance.isInstalled() is true and it becomes launchable.
        this.installDir = AppStorage.requireSingleton().getInstanceDir(instanceName).getAbsolutePath();
        this.manifestOnly = manifestOnly;
        this.manifestId = manifestId;
        this.workshopIds = null;
        this.listener = listener;
    }

    /** MODS mode ctor. */
    private SteamDownloadSpike(String username, String password,
                               List<Long> workshopIds, Listener listener) {
        this.username = username;
        this.password = password;
        this.workshopIds = workshopIds;
        this.listener = listener;
        this.instanceName = null;
        this.installDir = null;
        this.manifestOnly = false;
        this.manifestId = 0L;
    }

    /**
     * MODS mode: download public Workshop items by id → zips. Needs a LOGGED-IN account (Workshop UGC
     * content download is not available anonymously in JavaSteam's DepotDownloader — it hangs resolving
     * the UGC; same as `depotdownloader -app 294100 -pubfile <id>`, which runs under your account).
     */
    public static SteamDownloadSpike forMods(String username, String password, List<Long> workshopIds, Listener listener) {
        return new SteamDownloadSpike(username, password, workshopIds, listener);
    }

    /**
     * Steam Workshop items of Oxygen Not Included, with the player's account (the Workshop gives an
     * item's files only to an account that owns the game), installed straight into the mods of the
     * instance whose game data folder is {@code userDataDir}, as the Mods screen's ZIP import does.
     */
    public static SteamDownloadSpike forWorkshop(String username, String password, List<Long> workshopIds,
                                                 File userDataDir, Listener listener) {
        SteamDownloadSpike d = new SteamDownloadSpike(username, password, workshopIds, listener);
        d.modsUserDataDir = userDataDir;
        return d;
    }

    /** DLC mode ctor: the content is downloaded into a work folder, not an instance. */
    private SteamDownloadSpike(String username, String password, File workDir, Listener listener) {
        this.username = username;
        this.password = password;
        this.instanceName = null;
        this.installDir = workDir.getAbsolutePath();
        this.manifestOnly = false;
        this.manifestId = 0L;
        this.workshopIds = null;
        this.listener = listener;
    }

    /**
     * DLC mode: the content of the DLCs the account owns, packed into one ZIP in /Download/OxyDroid
     * (lastDlcZip), with the list of its DLCs inside. The player adds it to an instance afterwards (now
     * or for a new instance later: game.DlcZip). The game download puts the owned DLCs straight into
     * its instance instead, and its backup ZIP has them.
     */
    public static SteamDownloadSpike forDlcs(String username, String password, Listener listener) {
        File work = new File(AppStorage.requireSingleton().getCachePath(), "dlc_download");
        SteamDownloadSpike d = new SteamDownloadSpike(username, password, work, listener);
        d.dlcOnly = true;
        return d;
    }

    /** The mod ZIPs the last Workshop download made (for the Game page's "install into an instance"). */
    public static final java.util.List<File> lastModZips = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The DLC ZIP the last DLC download made (for the screen's "add to an instance"), or null. */
    public static volatile File lastDlcZip;

    private void progress(String m) {
        Log.i(TAG, m);
        if (listener != null) listener.onProgress(m);
    }

    private void done(String m) {
        if (doneEmitted) return;     // exactly one terminal message (cancel + a late retry-finish can race)
        doneEmitted = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }

    /** {@link Cancellable}: stop the running download — break the blocking await + the CM loop. */
    @Override
    public void cancel() {
        cancelled = true;
        running = false;
        downloadCompleted = true;            // prevent the disconnect handler from reconnecting/retrying
        Thread w = workerThread;
        if (w != null) w.interrupt();        // break DepotDownloader's awaitCompletion()
        try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        done("Download cancelled.");
    }

    @Override
    public void run() {
        try {
            LogManager.addListener(new DefaultLogListener());

            // Default client (same as the proven Milestone-0 auth spike). Depot CDN chunks are
            // downloaded by DepotDownloader's own ktor client, so no custom HTTP config is needed.
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);

            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            manager.subscribe(LicenseListCallback.class, this::onLicenseList);

            running = true;
            progress("Tip: stay on ONE network (Wi-Fi or mobile) until the download finishes — "
                    + "switching mid-download can drop the sign-in.");
            progress("Connecting to Steam...");
            steamClient.connect();
            while (running) {
                manager.runWaitCallbacks(1000L);
            }
            Log.i(TAG, "Spike loop ended");
            // Safety net: never leave the UI stuck in "downloading" if the loop ended without a
            // terminal result (e.g. a disconnect path that set running=false but did not call done()).
            if (!doneEmitted) done("Stopped before finishing — please try again.");
        } catch (Throwable t) {
            Log.e(TAG, "SteamDownloadSpike crashed", t);
            done("crash: " + t);
        } finally {
            // ALWAYS tear the connection down on the way out. Otherwise a failed/finished session
            // (e.g. auth cancelled when the device switched Wi-Fi↔mobile) leaves an ORPHANED
            // SteamClient whose background ktor/reader thread keeps running; when the network then
            // changes its socket dies and the coroutine throws an UNCAUGHT exception on its own
            // thread — which hard-crashes the whole app (not caught by any try/catch here, never
            // logged). Disconnecting here cancels those coroutines cleanly so nothing is left to die.
            try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void onConnected(ConnectedCallback cb) {
        try {
            // Reconnect path: reuse the in-memory token, no second Steam Mobile approval.
            if (refreshToken != null) {
                progress("Reconnected. Logging on with cached session...");
                logOnWithToken();
                return;
            }

            progress("Connected. Authenticating '" + username + "'...");
            authAttempts++;
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.persistentSession = false;          // do NOT persist — ephemeral token, no storage
            details.deviceFriendlyName = "OxyDroid";
            details.authenticator = new PushAuthenticator();

            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            progress("Approve the sign-in in your Steam Mobile app...");

            AuthPollResult poll = session.pollingWaitForResult().get();
            accountName = poll.getAccountName();
            refreshToken = poll.getRefreshToken();       // kept in memory only
            Log.i(TAG, "AUTH OK: account=" + accountName
                    + " tokenLen=" + (refreshToken == null ? 0 : refreshToken.length()));
            logOnWithToken();
        } catch (Throwable t) {
            Log.e(TAG, "Auth failed", t);
            // A CancellationException here = the CM connection DROPPED while we waited for the Steam
            // Mobile approval (e.g. the user briefly switched to the Steam app to approve), NOT a real
            // auth failure (wrong password / declined). Treat it as a transient drop: keep `running`
            // true so onDisconnected reconnects and re-runs the sign-in (a fresh approval push), up to
            // MAX_AUTH_ATTEMPTS. Only give up on a genuine failure or once attempts are exhausted.
            boolean connectionDropped =
                    (t instanceof java.util.concurrent.CancellationException)
                            || (t.getCause() instanceof java.util.concurrent.CancellationException);
            if (connectionDropped && !cancelled && authAttempts < MAX_AUTH_ATTEMPTS) {
                progress("Sign-in interrupted by a connection drop — reconnecting and asking you to "
                        + "approve again (attempt " + authAttempts + "/" + MAX_AUTH_ATTEMPTS + ")…");
                // running stays true → onDisconnected reconnects → onConnected re-runs the sign-in.
            } else {
                done("auth error: " + describe(t));
                running = false;
            }
        }
    }

    private void logOnWithToken() {
        LogOnDetails lod = new LogOnDetails();
        lod.setUsername(accountName);
        lod.setAccessToken(refreshToken);
        lod.setLoginID(149);
        progress("Logging in...");
        steamUser.logOn(lod);
    }

    private void onDisconnected(DisconnectedCallback cb) {
        Log.i(TAG, "Disconnected (userInitiated=" + cb.isUserInitiated() + ")");
        // Stop for good on a user-initiated disconnect, on success, or once retries are exhausted.
        if (cb.isUserInitiated() || downloadCompleted || downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS
                || (refreshToken == null && authAttempts >= MAX_AUTH_ATTEMPTS)) {
            running = false;
            if (!downloadCompleted && !cb.isUserInitiated()) {
                done("connection lost; gave up after " + downloadAttempts + " attempt(s). "
                        + (lastError != null ? describe(lastError) : ""));
            }
            return;
        }
        // Transient drop mid-download — reconnect and let onLicenseList re-trigger the attempt.
        progress("Connection lost — reconnecting (attempt " + (downloadAttempts + 1) + ")...");
        downloadStarted = false;
        try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
        if (!running) return;   // cancelled while waiting to reconnect
        steamClient.connect();   // → onConnected reuses the cached token (no re-approval)
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("logOn failed: " + cb.getResult());
            running = false;
            return;
        }
        progress("Logged on. Waiting for license list...");
        // The download starts in onLicenseList (DepotDownloader needs the account's licenses).
    }

    private void onLicenseList(LicenseListCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("license list failed: " + cb.getResult());
            running = false;
            return;
        }
        licenseList = cb.getLicenseList();
        progress("Got " + (licenseList == null ? 0 : licenseList.size()) + " licenses.");

        // Start exactly one download attempt per (re)connect; never while one is in flight or done.
        if (downloadStarted || downloadInProgress || downloadCompleted) return;
        downloadStarted = true;
        Runnable job = (workshopIds != null) ? this::downloadMods : this::download;
        Thread t = new Thread(job, "rd-depot-dl");
        workerThread = t;            // tracked so cancel() can interrupt the blocking download
        t.start();
    }

    /**
     * MODS mode (Workshop items are small, so this one still uses JavaSteam's own downloader).
     * ANONYMOUS download of public Workshop items (by published-file id) into temp work
     * dirs, each packed into /Download/OxyDroid/workshop_&lt;id&gt;.zip. No login, no licenses.
     */
    private void downloadMods() {
        downloadInProgress = true;
        downloadCompleted = true;   // single pass; no reconnect-retry
        AppStorage storage = AppStorage.requireSingleton();
        File downloadsDir = storage.getDownloadsDir();
        File tmpRoot = new File(storage.getCachePath(), "mod_work");
        List<License> licenses = licenseList != null ? licenseList : Collections.emptyList();
        int ok = 0, skipped = 0;
        if (modsUserDataDir == null) lastModZips.clear();
        try {
            if (modsUserDataDir == null && !downloadsDir.exists() && !downloadsDir.mkdirs()) {
                done("Cannot create downloads folder: " + downloadsDir + " (grant All-files access?)");
                running = false;
                return;
            }
            for (Long id : workshopIds) {
                if (!running) { progress("Aborted (disconnected)."); break; }
                progress("=== Workshop mod " + id + " (app " + APP_ID + ") ===");
                File work = new File(tmpRoot, String.valueOf(id));
                deleteRecursive(work);
                if (!work.mkdirs()) { progress("✗ " + id + ": cannot create work dir"); skipped++; continue; }
                lastError = null;
                boolean got = false;
                String title = null;
                // Our own pipeline, as for the game: JavaSteam's DepotDownloader skips Oxygen Not
                // Included's Workshop items ("unsupported file type First", i.e. Community) and then
                // waits out its 20 minutes (2026-11-24).
                try {
                    SteamAnonModDownloader.PubInfo info = SteamAnonModDownloader.resolvePublishedFile(id);
                    title = info.title;
                    if (!info.ok || info.hcontentFile == 0) {
                        progress("✗ " + id + ": not found, or no content on Steam.");
                    } else {
                        progress("'" + info.title + "'…");
                        got = downloadWorkshopItem(info.hcontentFile, work) && hasFiles(work);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "mod " + id + " download crashed", t);
                    lastError = t;
                }
                if (got && modsUserDataDir != null) {
                    File zip = new File(tmpRoot, "workshop_" + id + ".zip");
                    try {
                        deleteRecursive(new File(work, ".DepotDownloader"));
                        ZipUtil.zipDir(work, zip);
                        List<String> odd = new ArrayList<>();
                        List<String> names = com.oxydroid.game.OniMods.importZip(zip, modsUserDataDir, String.valueOf(id), odd);
                        progress("✓ mod " + id + " installed: " + String.join(", ", names)
                                + (odd.isEmpty() ? "" : " (no mod.yaml / DLL found: the game may not load it)"));
                        ok++;
                    } catch (Throwable t) {
                        Log.e(TAG, "install failed for mod " + id, t);
                        progress("✗ " + id + ": install failed — " + describe(t));
                        skipped++;
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        zip.delete();
                    }
                } else if (got) {
                    try {
                        // "<Title>_<id>.zip": readable in the Download folder, the id keeps it unique.
                        File zip = new File(downloadsDir, (title == null ? "workshop"
                                : SteamAnonModDownloader.sanitizeFileName(title)) + "_" + id + ".zip");
                        deleteRecursive(new File(work, ".DepotDownloader"));
                        ZipUtil.zipDir(work, zip);
                        AppStorage.requireSingleton().publishToMediaIndex(zip, "application/zip");
                        lastModZips.add(zip);
                        progress("✓ mod " + id + " → " + zip.getAbsolutePath());
                        ok++;
                    } catch (Throwable t) {
                        Log.e(TAG, "zip failed for mod " + id, t);
                        progress("✗ " + id + ": zip failed — " + describe(t));
                        skipped++;
                    }
                } else {
                    progress("✗ mod " + id + " — failed"
                            + (lastError != null ? (": " + describe(lastError)) : ""));
                    skipped++;
                }
                deleteRecursive(work);
            }
            done(modsUserDataDir != null
                    ? "Workshop: " + ok + " installed, " + skipped + " failed."
                    : "Mods done: " + ok + " packed, " + skipped + " skipped. Saved to " + downloadsDir);
        } catch (Throwable t) {
            Log.e(TAG, "downloadMods crashed", t);
            done("Mods error: " + describe(t));
        } finally {
            running = false;
            downloadInProgress = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
        }
    }

    /**
     * One Workshop item's content (its manifest is the item's hcontent_file) from the game's Workshop
     * depot into {@code work}, with the signed-in session: Klei's items are given only to owners.
     */
    private boolean downloadWorkshopItem(long manifestGid, File work) throws Exception {
        SteamApps apps = steamClient.getHandler(SteamApps.class);
        SteamContent content = steamClient.getHandler(SteamContent.class);
        KeyValue app = appInfo();
        int depot = app == null ? APP_ID : app.get("depots").get("workshopdepot").asInteger(APP_ID);
        byte[] key = getDepotKey(apps, depot, APP_ID);
        if (key == null) { progress("✗ no key for the Workshop depot — is the game owned on this account?"); return false; }
        List<Server> servers = awaitDeferred(content.getServersForSteamPipe(null, null, GlobalScope.INSTANCE), 30000);
        if (servers == null || servers.isEmpty()) { progress("✗ no CDN servers"); return false; }
        long requestCode = awaitDeferred(
                content.getManifestRequestCode(depot, APP_ID, manifestGid, "public", null, GlobalScope.INSTANCE), 30000);
        Client cdn = new Client(steamClient);
        Map<String, String> tokens = new HashMap<>();
        DepotManifest manifest = null;
        Exception lastErr = null;
        int cursor = 0;
        for (int i = 0; i < servers.size() && manifest == null; i++) {
            Server srv = servers.get(i);
            try {
                manifest = cdn.downloadManifestFuture(depot, manifestGid, requestCode, srv, key, null,
                        cdnTokenFor(content, depot, srv, tokens)).get(90, TimeUnit.SECONDS);
                cursor = i;
            } catch (Exception e) {
                lastErr = e;
                Log.w(TAG, "workshop manifest via " + srv.getHost() + " failed: " + describe(e));
            }
        }
        if (manifest == null) { progress("✗ manifest download failed: " + describe(lastErr)); return false; }
        for (FileData f : manifest.getFiles()) {
            if (!running) return false;
            String rel = sanitizeRel(f.getFileName());
            if (rel == null) continue;
            File out = new File(work, rel);
            if (f.getFlags().contains(EDepotFileFlag.Directory)) { //noinspection ResultOfMethodCallIgnored
                out.mkdirs(); continue; }
            File parent = out.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                raf.setLength(f.getTotalSize());
                for (ChunkData chunk : f.getChunks()) {
                    byte[] dest = new byte[Math.max(chunk.getCompressedLength(), chunk.getUncompressedLength())];
                    int written = -1;
                    Exception chunkErr = null;
                    for (int t = 0; t < MAX_CHUNK_TRIES && written < 0; t++) {
                        if (!running) return false;
                        Server srv = servers.get(Math.floorMod(cursor++, servers.size()));
                        try {
                            written = cdn.downloadDepotChunkFuture(depot, chunk, srv, dest, key, null,
                                    cdnTokenFor(content, depot, srv, tokens)).get(120, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            chunkErr = e;
                            Log.w(TAG, "workshop chunk via " + srv.getHost() + " failed: " + describe(e));
                            try { Thread.sleep(500L); } catch (InterruptedException ie) { return false; }
                        }
                    }
                    if (written < 0) throw chunkErr != null ? chunkErr : new java.io.IOException("chunk download failed");
                    raf.seek(chunk.getOffset());
                    raf.write(dest, 0, written);
                }
            }
        }
        return true;
    }

    /** True if {@code dir} holds at least one file besides DepotDownloader's own state. */
    private static boolean hasFiles(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.getName().equals(".DepotDownloader")) continue;
            if (f.isFile() || hasFiles(f)) return true;
        }
        return false;
    }

    /** True if {@code dir} contains an About/About.xml anywhere — proof a real mod/DLC was downloaded. */
    private static boolean containsAboutXml(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.getName().equals(".DepotDownloader")) continue;
            if (f.isDirectory()) {
                File about = new File(f, "About/About.xml");
                if (about.exists()) return true;
                if (containsAboutXml(f)) return true;
            }
        }
        return false;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String sanitizeName(String s) {
        if (s == null) return "dlc";
        String r = s.replaceAll("[^A-Za-z0-9 _.\\-()]", "").trim();
        return r.isEmpty() ? "dlc" : r;
    }

    private void download() {
        downloadInProgress = true;
        downloadAttempts++;
        lastError = null;
        // RESUME-FRIENDLY: we deliberately do NOT wipe installDir/.DepotDownloader. DepotDownloader
        // uses that cache + the partial files to skip already-downloaded chunks, so re-tapping
        // "Download" for the SAME instance + manifest CONTINUES a half-finished download instead of
        // restarting from zero — essential for big multi-GB downloads (start at home, finish later
        // on another network). The earlier hard-crash was the orphaned SteamClient (fixed in run()'s
        // finally), not this cache, so wiping here would only cost the user their progress.
        // Mark the instance as "being downloaded" until finalizeInstance clears it, so the launcher
        // does not offer a ▶ button on a copy that is still missing most of its assets. Not for a DLC
        // download: that goes to a work folder and never reaches finalizeInstance (an earlier version
        // downloaded DLCs into the instance and left the marker there: "not finished" on a complete game).
        if (!dlcOnly) try {
            File marker = new File(installDir, GameInstance.DOWNLOAD_MARKER);
            marker.getParentFile().mkdirs();
            if (!marker.isFile()) new java.io.FileOutputStream(marker).close();
        } catch (Throwable t) {
            Log.w(TAG, "cannot write the download marker", t);
        }
        if (downloadAttempts == 1) {
            File state = new File(installDir, ".DepotDownloader");
            if (state.exists()) progress("Found partial download — resuming where it stopped…");
        }
        // Manifest: an explicit user build wins, otherwise an empty list = the newest build of the
        // public branch.
        List<Long> manifests = (manifestId > 0) ? List.of(manifestId) : List.of();
        String verLabel = (manifestId > 0) ? ("manifest " + manifestId) : "newest public build";
        progress((manifestOnly ? "[manifest-only] " : "")
                + "[attempt " + downloadAttempts + "] " + verLabel + " → " + installDir);
        try {
            runPipeline();
        } catch (Throwable t) {
            if (!running) {
                done("Download cancelled.");
            } else {
                Log.e(TAG, "download crashed", t);
                lastError = t;
                progress("attempt " + downloadAttempts + " failed: " + describe(t));
                if (downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS) {
                    done("download FAILED after " + downloadAttempts + " attempts: " + describe(t));
                    running = false;
                    try { steamUser.logOff(); } catch (Throwable ignored) {}
                }
            }
        } finally {
            downloadInProgress = false;
        }
    }

    /**
     * Manifest, then every file through a small worker pool.
     *
     * A free worker first takes a CHUNK of a file already started, and only with none left opens the
     * next file: most of the game's bytes sit in a few big files, which one worker per file would
     * fetch over a single connection. Few files are open at a time for the same reason. Resume stays
     * per file: a name is recorded as done only once the whole file is written. A file already on
     * the phone with the manifest's SHA-1 is kept, so an update fetches only what changed.
     * Chunk requests round-robin over Steam's CDN servers on every attempt, so the workers spread
     * over the edge nodes instead of hammering one (which is what makes a node start refusing).
     */
    private void runPipeline() throws Exception {
        SteamApps apps = steamClient.getHandler(SteamApps.class);
        SteamContent content = steamClient.getHandler(SteamContent.class);

        File outDir = new File(installDir);
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            done("Cannot create the instance folder: " + outDir);
            running = false; return;
        }

        Client cdn = new Client(steamClient);
        List<Server> servers = awaitDeferred(
                content.getServersForSteamPipe(null, null, GlobalScope.INSTANCE), 30000);
        if (servers == null || servers.isEmpty()) { done("No CDN servers available."); running = false; return; }

        if (!dlcOnly) {
            long gid = (manifestId > 0) ? manifestId : resolveManifestGid(LINUX_DEPOT);
            if (gid == 0L) { done("Could not resolve a build to download."); running = false; return; }
            progress("Depot " + LINUX_DEPOT + ", manifest " + Long.toUnsignedString(gid)
                    + (buildId > 0 ? (", build " + buildId) : ""));
            if (!downloadDepot(apps, content, cdn, servers, LINUX_DEPOT, gid, outDir, true)) return;

            fetchMacSimDll(apps, content, cdn, servers, outDir);
            if (!running) { done("Download cancelled."); return; }
        }

        String dlcs = downloadDlcs(apps, content, cdn, servers, outDir);
        if (!running) { done("Download cancelled."); return; }

        downloadCompleted = true;
        if (dlcOnly) {
            File zip = packDlcZip(outDir);
            done(zip == null ? "DLCs: " + dlcs + "." : "DLCs saved: " + zip.getName() + " (" + dlcs + ").");
        } else {
            finalizeInstance();
            done("Instance '" + instanceName + "' downloaded — ready to launch.");
        }
        running = false;
        try { steamUser.logOff(); } catch (Throwable ignored) {}
    }

    /**
     * One depot's files into {@code outDir}: manifest, then every file through a small worker pool.
     *
     * A free worker first takes a CHUNK of a file already started, and only with none left opens the
     * next file: most of the game's bytes sit in a few big files, which one worker per file would
     * fetch over a single connection. Few files are open at a time for the same reason. Resume stays
     * per file: a name is recorded as done only once the whole file is written. A file already on
     * the phone with the manifest's SHA-1 is kept, so an update fetches only what changed.
     * Chunk requests round-robin over Steam's CDN servers on every attempt, so the workers spread
     * over the edge nodes instead of hammering one (which is what makes a node start refusing).
     *
     * Returns false when it stopped with its own terminal message (no key, no manifest, cancelled,
     * manifest only); a failed chunk throws, for the download's retry.
     */
    private boolean downloadDepot(SteamApps apps, SteamContent content, Client cdn, List<Server> servers,
                                  int depot, long gid, File outDir, boolean game) throws Exception {
        currentDepot = depot;
        byte[] depotKey = getDepotKey(apps, depot, APP_ID);
        if (depotKey == null) {
            done(game ? "No depot key — is Oxygen Not Included owned on this account?"
                    : "No key for the DLC depot " + depot + " — is the DLC owned on this account?");
            running = false; return false;
        }

        long requestCode = awaitDeferred(
                content.getManifestRequestCode(depot, APP_ID, gid, "public", null, GlobalScope.INSTANCE), 30000);

        Map<String, String> tokenCache = new HashMap<>();   // CDN tokens are per depot
        DepotManifest manifest = null;
        Exception lastErr = null;
        int serverIdx = 0;
        for (int i = 0; i < servers.size(); i++) {
            Server srv = servers.get(i);
            try {
                manifest = cdn.downloadManifestFuture(depot, gid, requestCode, srv, depotKey, null,
                        cdnTokenFor(content, depot, srv, tokenCache)).get(120, TimeUnit.SECONDS);
                serverIdx = i;
                break;
            } catch (Exception e) {
                lastErr = e;
                Log.w(TAG, "manifest of " + depot + " via " + srv.getHost() + " failed: " + describe(e));
            }
        }
        if (manifest == null) { done("Manifest download failed: " + describe(lastErr)); running = false; return false; }

        List<FileData> files = manifest.getFiles();
        long totalBytes = 0;
        for (FileData f : files)
            if (!f.getFlags().contains(EDepotFileFlag.Directory)) totalBytes += f.getTotalSize();
        progress("Manifest OK: " + files.size() + " files, " + (totalBytes / (1024 * 1024)) + " MB.");
        if (manifestOnly) {
            downloadCompleted = true;
            done("Manifest fetched OK (no content downloaded).");
            running = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
            return false;
        }

        // Resume: names already written in full during an earlier run of this same build.
        File doneListFile = new File(outDir, ".oxydroid_complete_" + depot + "_" + Long.toUnsignedString(gid));
        Set<String> doneSet = loadDoneSet(doneListFile);

        final AtomicLong doneBytes = new AtomicLong(0);
        final AtomicInteger serverCursor = new AtomicInteger(serverIdx);
        final AtomicInteger lastPct = new AtomicInteger(-1);
        final AtomicLong lastEmit = new AtomicLong(0);
        final AtomicReference<Throwable> firstError = new AtomicReference<>();

        final List<FileData> pending = new ArrayList<>();
        for (FileData f : files) {
            String rel = sanitizeRel(f.getFileName());
            if (rel == null) continue;
            File outFile = new File(outDir, rel);
            if (f.getFlags().contains(EDepotFileFlag.Directory)) { outFile.mkdirs(); continue; }
            if (doneSet.contains(rel) && outFile.isFile() && outFile.length() == f.getTotalSize()) {
                doneBytes.addAndGet(f.getTotalSize());
                continue;
            }
            pending.add(f);
        }
        if (!doneSet.isEmpty())
            progress("Resuming — " + (files.size() - pending.size()) + " files already downloaded.");
        progress("Downloading " + pending.size() + " files with " + MAX_WORKERS + " workers…");

        final long fTotal = totalBytes;
        final byte[] fKey = depotKey;
        final List<Server> fServers = servers;
        final Client fCdn = cdn;
        final SteamContent fContent = content;
        final Map<String, String> fTokens = tokenCache;
        final File fOutDir = outDir, fDoneList = doneListFile;

        final java.util.concurrent.ConcurrentLinkedQueue<FileData> queue =
                new java.util.concurrent.ConcurrentLinkedQueue<>(pending);
        final java.util.concurrent.ConcurrentLinkedQueue<ChunkJob> chunks =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        final Set<OpenFile> open = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicInteger opening = new AtomicInteger(0);
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(MAX_WORKERS);
        final List<Thread> pool = new ArrayList<>(MAX_WORKERS);
        for (int w = 0; w < MAX_WORKERS; w++) {
            Thread t = new Thread(() -> {
                try {
                    while (running && firstError.get() == null) {
                        ChunkJob job = chunks.poll();
                        if (job != null) {
                            downloadChunk(job, open, fDoneList, fCdn, fContent, fServers, fKey, fTokens,
                                    serverCursor, doneBytes, fTotal, lastPct, lastEmit);
                            continue;
                        }
                        // Counted before the poll: a file being opened still brings chunks, and a
                        // worker leaving now would leave a big last file to fewer connections.
                        opening.incrementAndGet();
                        FileData f = queue.poll();
                        if (f != null) {
                            try {
                                openFile(f, fOutDir, fDoneList, chunks, open, doneBytes, fTotal, lastPct, lastEmit);
                            } finally {
                                opening.decrementAndGet();
                            }
                            continue;
                        }
                        if (opening.decrementAndGet() == 0 && chunks.isEmpty()) return;
                        Thread.sleep(20);
                    }
                } catch (InterruptedException cancelled) {
                    // The running flag says so.
                } catch (Throwable e) {
                    firstError.compareAndSet(null, e);
                } finally {
                    latch.countDown();
                }
            }, "vd-depot-dl-" + w);
            t.setDaemon(true);
            pool.add(t);
            t.start();
        }
        try {
            latch.await();
        } catch (InterruptedException cancelled) {
            running = false;
            for (Thread t : pool) t.interrupt();
            boolean drained = false;
            while (!drained) {
                try { latch.await(); drained = true; } catch (InterruptedException ignored) { }
            }
        }
        for (OpenFile o : open) o.close();   // what a failure or a cancel left half written
        if (firstError.get() != null) throw new Exception(describe(firstError.get()), firstError.get());
        if (!running) { done("Download cancelled."); return false; }
        return true;
    }

    /**
     * The DLCs of the game this account owns, and the content of each: every depot of the game whose
     * "dlcappid" is an owned DLC, for Linux or for every system, goes into the instance like the game's
     * own depot (the DLC content lives inside the game folder). A DLC without such a depot ships with
     * the game. The owned DLCs whose content is in place are written to the instance's DLC list
     * (GameInstance.DLC_LIST), which the launch hands to the game's Steam stand-in. Returns a summary.
     */
    private String downloadDlcs(SteamApps apps, SteamContent content, Client cdn, List<Server> servers,
                                File outDir) throws Exception {
        KeyValue app = appInfo();
        if (app == null) { progress("DLCs: the game's Steam data could not be read; skipped."); return "not checked"; }
        if (buildId == 0) buildId = app.get("depots").get("branches").get("public").get("buildid").asLong(0L);
        java.util.Set<Integer> dlcs = new java.util.TreeSet<>();
        String list = app.get("extended").get("listofdlc").asString();
        for (String id : (list == null ? "" : list).split(",")) {
            try { if (!id.trim().isEmpty()) dlcs.add(Integer.parseInt(id.trim())); } catch (NumberFormatException ignored) { }
        }
        java.util.Set<Integer> owned = ownedApps();
        if (owned == null) { progress("DLCs: the account's purchases could not be read; skipped."); return "not checked"; }
        dlcs.retainAll(owned);
        if (dlcs.isEmpty()) {
            progress("DLCs on this account: none.");
            com.oxydroid.game.GameInstance.writeDlcList(outDir, dlcs);
            return "none on this account";
        }
        progress("DLCs on this account: " + dlcs + ".");

        java.util.Set<Integer> ready = new java.util.TreeSet<>(dlcs);
        KeyValue depots = app.get("depots");
        for (KeyValue d : depots.getChildren()) {
            int depot;
            try { depot = Integer.parseInt(d.getName()); } catch (Exception e) { continue; }
            int dlc = d.get("dlcappid").asInteger(0);
            if (dlc == 0 || !dlcs.contains(dlc)) continue;
            String os = d.get("config").get("oslist").asString();
            if (os != null && !os.isEmpty() && !os.contains("linux")) continue;
            String gidText = d.get("manifests").get("public").get("gid").asString();
            if (gidText == null || gidText.isEmpty()) continue;
            String name = d.get("name").asString();
            progress("DLC " + dlc + ": depot " + depot + (name == null || name.isEmpty() ? "" : " (" + name + ")") + "…");
            boolean ok;
            try {
                ok = downloadDepot(apps, content, cdn, servers, depot, Long.parseUnsignedLong(gidText), outDir, false);
            } catch (Exception e) {
                if (!running) throw e;
                progress("DLC " + dlc + ": depot " + depot + " failed — " + describe(e));
                ok = false;
            }
            if (!running) return "cancelled";
            if (!ok) {
                ready.remove(dlc);
                if (doneEmitted) {
                    // downloadDepot ended the download with its own message: keep what is complete.
                    com.oxydroid.game.GameInstance.writeDlcList(outDir, ready);
                    return "stopped";
                }
            }
        }
        com.oxydroid.game.GameInstance.writeDlcList(outDir, ready);
        return ready.isEmpty() ? "none ready" : ready.size() + " ready " + ready;
    }

    /**
     * DLC mode: the downloaded DLC content (entries relative to the game folder) and its DLC list into
     * /Download/OxyDroid/OxygenNotIncluded_DLC_&lt;ids&gt;_build&lt;build&gt;.zip; the work folder is then
     * removed. Null when there is nothing to pack or packing failed (said in the log).
     */
    private File packDlcZip(File workDir) {
        List<Integer> ids = new ArrayList<>();
        File list = new File(workDir, com.oxydroid.game.GameInstance.DLC_LIST);
        try {
            if (list.isFile())
                for (String line : new String(java.nio.file.Files.readAllBytes(list.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).split("\n"))
                    if (!line.trim().isEmpty()) ids.add(Integer.parseInt(line.trim()));
        } catch (Exception e) {
            Log.w(TAG, "DLC list unreadable", e);
        }
        if (ids.isEmpty()) return null;
        File[] kids = workDir.listFiles();
        if (kids != null)
            for (File k : kids)
                if (k.isFile() && k.getName().startsWith(".oxydroid_complete_")) //noinspection ResultOfMethodCallIgnored
                    k.delete();
        StringBuilder name = new StringBuilder("OxygenNotIncluded_DLC");
        for (Integer id : ids) name.append('_').append(id);
        name.append('_').append(buildId > 0 ? "build" + buildId : "unknown").append(".zip");
        AppStorage storage = AppStorage.requireSingleton();
        File zip = new File(storage.getDownloadsDir(), name.toString());
        try {
            progress("Packing the DLC copy: " + zip.getAbsolutePath());
            ZipUtil.zipDir(workDir, zip);
            storage.publishToMediaIndex(zip, "application/zip");
            lastDlcZip = zip;
            progress("DLC copy saved: " + zip.getName() + " (" + (zip.length() / (1024 * 1024)) + " MB).");
            deleteRecursive(workDir);
            return zip;
        } catch (Throwable t) {
            Log.e(TAG, "DLC zip failed", t);
            progress("DLC copy not saved: " + describe(t) + " (grant All-files access?)");
            return null;
        }
    }

    /** The game's PICS app data (depots, DLC list), or null. */
    private KeyValue appInfo() {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            long token = 0L;
            try {
                PICSTokensCallback tk = apps.picsGetAccessTokens(List.of(APP_ID), Collections.emptyList())
                        .toFuture().get(20, TimeUnit.SECONDS);
                Long t = tk.getAppTokens().get(APP_ID);
                if (t != null) token = t;
            } catch (Throwable ignored) { }
            AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                    apps.picsGetProductInfo(List.of(new PICSRequest(APP_ID, token)), Collections.emptyList())
                            .toFuture().get(40, TimeUnit.SECONDS);
            for (PICSProductInfoCallback cb : rs.getResults()) {
                PICSProductInfo info = cb.getApps().get(APP_ID);
                if (info != null) return info.getKeyValues();
            }
        } catch (Throwable t) {
            Log.w(TAG, "app info failed", t);
        }
        return null;
    }

    /** Every app id the account's licenses (packages) grant, or null when they cannot be read. */
    private java.util.Set<Integer> ownedApps() {
        try {
            List<PICSRequest> packages = new ArrayList<>();
            if (licenseList != null)
                for (License l : licenseList) packages.add(new PICSRequest(l.getPackageID(), l.getAccessToken()));
            if (packages.isEmpty()) return new java.util.HashSet<>();
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                    apps.picsGetProductInfo(Collections.emptyList(), packages).toFuture().get(60, TimeUnit.SECONDS);
            java.util.Set<Integer> owned = new java.util.HashSet<>();
            for (PICSProductInfoCallback cb : rs.getResults())
                for (PICSProductInfo pkg : cb.getPackages().values())
                    for (KeyValue a : pkg.getKeyValues().get("appids").getChildren()) owned.add(a.asInteger(0));
            return owned;
        } catch (Throwable t) {
            Log.w(TAG, "owned apps failed", t);
            return null;
        }
    }

    /**
     * The simulation library from the macOS depot, into macos/SimDLL of the instance. Only that one
     * file is fetched (about 2 MB of a multi-GB depot). The manifest is the public branch's, i.e. the
     * same build as a Linux download of the public branch; with a Linux manifest pinned by hand the
     * two can differ, which is said in the log. Throws when the file cannot be had: without it the
     * game stops at its first simulation step, so the download must not report success.
     */
    private void fetchMacSimDll(SteamApps apps, SteamContent content, Client cdn, List<Server> servers,
                                File outDir) throws Exception {
        progress("Simulation library: macOS depot " + MAC_DEPOT + "…");
        long gid = resolveManifestGid(MAC_DEPOT);
        if (gid == 0L) throw new java.io.IOException("no public manifest for the macOS depot " + MAC_DEPOT);
        if (manifestId > 0)
            progress("Note: the Linux build was chosen by hand; the simulation library is the newest "
                    + "public one and may not match it.");
        byte[] key = getDepotKey(apps, MAC_DEPOT, APP_ID);
        if (key == null) throw new java.io.IOException("no key for the macOS depot " + MAC_DEPOT);
        long requestCode = awaitDeferred(
                content.getManifestRequestCode(MAC_DEPOT, APP_ID, gid, "public", null, GlobalScope.INSTANCE), 30000);
        Map<String, String> tokens = new HashMap<>();   // CDN tokens are per depot: not the Linux cache
        DepotManifest manifest = null;
        Exception lastErr = null;
        for (Server srv : servers) {
            try {
                manifest = cdn.downloadManifestFuture(MAC_DEPOT, gid, requestCode, srv, key, null,
                        cdnTokenFor(content, MAC_DEPOT, srv, tokens)).get(120, TimeUnit.SECONDS);
                break;
            } catch (Exception e) {
                lastErr = e;
                Log.w(TAG, "macOS manifest via " + srv.getHost() + " failed: " + describe(e));
            }
        }
        if (manifest == null) throw new java.io.IOException("macOS manifest download failed: " + describe(lastErr));

        FileData wanted = null;
        for (FileData f : manifest.getFiles()) {
            String name = f.getFileName() == null ? "" : f.getFileName().replace('\\', '/');
            if (name.endsWith(MAC_SIMDLL) && !f.getFlags().contains(EDepotFileFlag.Directory)) { wanted = f; break; }
        }
        if (wanted == null) throw new java.io.IOException(MAC_SIMDLL + " is not in the macOS depot");

        File target = com.oxydroid.game.NativeEngine.macSimDll(outDir);
        if (sameFile(target, wanted)) { progress("Simulation library already up to date."); return; }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new java.io.IOException("cannot create " + parent);
        File tmp = new File(parent, target.getName() + ".part");
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            raf.setLength(wanted.getTotalSize());
            int cursor = 0;
            for (ChunkData chunk : wanted.getChunks()) {
                byte[] dest = new byte[Math.max(chunk.getCompressedLength(), chunk.getUncompressedLength())];
                int written = -1;
                Exception chunkErr = null;
                for (int t = 0; t < MAX_CHUNK_TRIES && written < 0; t++) {
                    if (!running) return;
                    Server srv = servers.get(Math.floorMod(cursor++, servers.size()));
                    try {
                        written = cdn.downloadDepotChunkFuture(MAC_DEPOT, chunk, srv, dest, key, null,
                                cdnTokenFor(content, MAC_DEPOT, srv, tokens)).get(120, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        chunkErr = e;
                        Log.w(TAG, "SimDLL chunk via " + srv.getHost() + " failed: " + describe(e));
                    }
                }
                if (written < 0) throw chunkErr != null ? chunkErr : new java.io.IOException("chunk download failed");
                raf.seek(chunk.getOffset());
                raf.write(dest, 0, written);
            }
        }
        if (!sameFile(tmp, wanted)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new java.io.IOException("the downloaded simulation library does not match its checksum");
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!tmp.renameTo(target)) throw new java.io.IOException("cannot place " + target);
        progress("Simulation library downloaded (" + (target.length() / 1024) + " KB).");
    }

    /** The public branch's manifest for a depot of the app, from PICS; also records the build id. */
    private long resolveManifestGid(int depotId) {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            long token = 0L;
            try {
                PICSTokensCallback tk = apps.picsGetAccessTokens(List.of(APP_ID), Collections.emptyList())
                        .toFuture().get(20, TimeUnit.SECONDS);
                Long t = tk.getAppTokens().get(APP_ID);
                if (t != null) token = t;
            } catch (Throwable ignored) { }
            AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                    apps.picsGetProductInfo(List.of(new PICSRequest(APP_ID, token)), Collections.emptyList())
                            .toFuture().get(40, TimeUnit.SECONDS);
            for (PICSProductInfoCallback cb : rs.getResults()) {
                PICSProductInfo info = cb.getApps().get(APP_ID);
                if (info == null) continue;
                KeyValue depots = info.getKeyValues().get("depots");
                buildId = depots.get("branches").get("public").get("buildid").asLong(0L);
                KeyValue depot = depots.get(String.valueOf(depotId));
                String gid = depot.get("manifests").get("public").get("gid").asString();
                if (gid != null && !gid.isEmpty()) return Long.parseUnsignedLong(gid);
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveManifestGid failed", t);
        }
        return 0L;
    }

    private byte[] getDepotKey(SteamApps apps, int depot, int appId) {
        try {
            DepotKeyCallback dk = apps.getDepotDecryptionKey(depot, appId).toFuture().get(30, TimeUnit.SECONDS);
            if (dk.getResult() == EResult.OK && dk.getDepotKey() != null && dk.getDepotKey().length == 32)
                return dk.getDepotKey();
            Log.w(TAG, "depot key result=" + dk.getResult());
        } catch (Throwable t) {
            Log.e(TAG, "getDepotKey failed", t);
        }
        return null;
    }

    /** A file being written: open until its last chunk is in, so only a few are open at a time. */
    private static final class OpenFile {
        final String rel;
        final RandomAccessFile raf;
        final AtomicInteger chunksLeft;
        OpenFile(String rel, RandomAccessFile raf, int chunks) {
            this.rel = rel; this.raf = raf; this.chunksLeft = new AtomicInteger(chunks);
        }
        void close() {
            try { raf.close(); } catch (Exception ignored) { }
        }
    }

    private static final class ChunkJob {
        final OpenFile file;
        final ChunkData chunk;
        ChunkJob(OpenFile file, ChunkData chunk) { this.file = file; this.chunk = chunk; }
    }

    /** Keeps the file if the phone already has this version of it, otherwise queues its chunks. */
    private void openFile(FileData f, File outDir, File doneListFile,
                          java.util.Queue<ChunkJob> chunks, Set<OpenFile> open, AtomicLong doneBytes,
                          long totalBytes, AtomicInteger lastPct, AtomicLong lastEmit) throws Exception {
        String rel = sanitizeRel(f.getFileName());
        if (rel == null) return;
        File outFile = new File(outDir, rel);
        File parent = outFile.getParentFile();
        if (parent != null) parent.mkdirs();

        if (sameFile(outFile, f)) {
            appendDone(doneListFile, rel);
            emitProgress(doneBytes.addAndGet(f.getTotalSize()), totalBytes, lastPct, lastEmit);
            return;
        }
        RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
        raf.setLength(f.getTotalSize());
        List<ChunkData> list = f.getChunks();
        if (list.isEmpty()) {
            try { raf.close(); } catch (Exception ignored) { }
            appendDone(doneListFile, rel);
            return;
        }
        OpenFile o = new OpenFile(rel, raf, list.size());
        open.add(o);
        for (ChunkData c : list) chunks.add(new ChunkJob(o, c));
    }

    /** Whether the file on the phone is the manifest's: same size, same SHA-1. */
    private boolean sameFile(File file, FileData f) {
        byte[] want = f.getFileHash();
        if (want == null || want.length == 0 || f.getTotalSize() == 0
                || !file.isFile() || file.length() != f.getTotalSize()) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[256 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                if (!running) return false;
                sha1.update(buf, 0, n);
            }
            return java.util.Arrays.equals(sha1.digest(), want);
        } catch (Exception e) {
            return false;
        }
    }

    /** One chunk, written at its offset. Runs on a pool thread. */
    private void downloadChunk(ChunkJob job, Set<OpenFile> open, File doneListFile, Client cdn,
                               SteamContent content, List<Server> servers, byte[] depotKey,
                               Map<String, String> tokenCache, AtomicInteger serverCursor,
                               AtomicLong doneBytes, long totalBytes,
                               AtomicInteger lastPct, AtomicLong lastEmit) throws Exception {
        ChunkData chunk = job.chunk;
        byte[] dest = new byte[Math.max(chunk.getCompressedLength(), chunk.getUncompressedLength())];
        int written = -1;
        Exception chunkErr = null;
        for (int t = 0; t < MAX_CHUNK_TRIES && written < 0; t++) {
            if (!running) return;
            // Round-robin per attempt: sticky servers would point every worker at one edge
            // node, and a retry would land on the node that just failed.
            Server srv = servers.get(Math.floorMod(serverCursor.getAndIncrement(), servers.size()));
            try {
                written = cdn.downloadDepotChunkFuture(currentDepot, chunk, srv, dest, depotKey, null,
                        cdnTokenFor(content, currentDepot, srv, tokenCache)).get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                chunkErr = e;
                Log.w(TAG, "chunk via " + srv.getHost() + " failed (try " + (t + 1) + "/"
                        + MAX_CHUNK_TRIES + "): " + describe(e));
                long now = System.currentTimeMillis();
                long prev = lastEmit.get();
                if (now - prev > 1500 && lastEmit.compareAndSet(prev, now))
                    progress("Steam CDN busy — retrying… (" + (doneBytes.get() / (1024 * 1024))
                            + " / " + (totalBytes / (1024 * 1024)) + " MB)");
                long backoff = Math.min(10000L, 500L * (1L << Math.min(t, 4)));
                try { Thread.sleep(backoff); } catch (InterruptedException ignored) { return; }
            }
        }
        if (written < 0) throw chunkErr != null ? chunkErr : new java.io.IOException("chunk download failed");
        // Positional: other workers write other chunks of this file at the same time.
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(dest, 0, written);
        long pos = chunk.getOffset();
        while (buf.hasRemaining()) pos += job.file.raf.getChannel().write(buf, pos);
        emitProgress(doneBytes.addAndGet(written), totalBytes, lastPct, lastEmit);

        if (job.file.chunksLeft.decrementAndGet() == 0) {
            job.file.close();
            open.remove(job.file);
            if (running) appendDone(doneListFile, job.file.rel);
        }
    }

    private void emitProgress(long total, long totalBytes, AtomicInteger lastPct, AtomicLong lastEmit) {
        long now = System.currentTimeMillis();
        int pct = totalBytes > 0 ? (int) (total * 100 / totalBytes) : 0;
        long prev = lastEmit.get();
        if (pct != lastPct.get() && now - prev > 500 && lastEmit.compareAndSet(prev, now)) {
            lastPct.set(pct);
            progress(pct + "%  (" + (total / (1024 * 1024)) + " / " + (totalBytes / (1024 * 1024)) + " MB)");
            if (listener != null) listener.onPercent(pct);
        }
    }

    private String cdnTokenFor(SteamContent content, int depot, Server s, Map<String, String> cache) {
        String host = s.getHost() != null ? s.getHost() : s.getVHost();
        if (host == null) return null;
        // Guarded rather than a ConcurrentHashMap: a null token is a real cached answer, and that map
        // forbids null values. The lock is held across the fetch on purpose — every worker wants the
        // same host's token at the same moment, and one request beats six identical ones.
        synchronized (cache) {
            if (cache.containsKey(host)) return cache.get(host);
            String token = null;
            try {
                CDNAuthToken tok = awaitDeferred(content.getCDNAuthToken(APP_ID, depot, host, GlobalScope.INSTANCE), 15000);
                if (tok != null && tok.getResult() == EResult.OK) token = tok.getToken();
            } catch (Throwable ignored) { }
            cache.put(host, token);
            return token;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaitDeferred(Deferred<T> d, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!d.isCompleted()) {
            if (System.currentTimeMillis() > deadline) throw new TimeoutException("deferred timed out");
            Thread.sleep(40);
        }
        return (T) d.getCompleted();
    }

    /** Relative paths already written in full (the resume marker). */
    private static Set<String> loadDoneSet(File f) {
        Set<String> out = new HashSet<>();
        if (f == null || !f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Several workers finish files at once, so appending is synchronized and flushed at once. */
    private static synchronized void appendDone(File f, String rel) {
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(rel);
            w.write('\n');
        } catch (Exception ignored) { }
    }

    private static String sanitizeRel(String name) {
        if (name == null) return null;
        String rel = name.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty() || rel.contains("../")) return null;
        return rel;
    }

    /**
     * Make the freshly-downloaded instance launchable: mark the binary executable, set it as the
     * active instance, and refresh the instance list so the launcher picks it up. Mirrors the tail
     * of InstallerService.installInstance (minus the zip-flatten, which depot layout doesn't need).
     */
    private void finalizeInstance() {
        try {
            File dir = new File(installDir);
            //noinspection ResultOfMethodCallIgnored
            new File(dir, GameInstance.DOWNLOAD_MARKER).delete();   // the copy is complete now
            File bin = new File(dir, GameDescriptor.ONI.executable());
            if (bin.exists()) bin.setExecutable(true, false);
            // Nothing to set up in the game folder: the native engine brings its own Steam library
            // and reads the game files as they are.
            // The install-time save fix (Assembly-CSharp bspatch) used to run here. Removed
            // entirely 2026-08-28 — root-fixed in box64 (see InstallerService for the history).
            LauncherPreferences.requireSingleton().setLastInstanceName(instanceName);
            GameInstanceManager.requireSingleton().reload();
            progress("Instance '" + instanceName + "' is now installed (" + APP_ID + ").");
            backupInstanceZip(new File(installDir));
        } catch (Throwable t) {
            Log.e(TAG, "finalizeInstance failed", t);
        }
    }

    /**
     * Copy the freshly-installed instance into a version-tagged zip under /Download/OxyDroid,
     * same folder DLC/mod archives already land in. Purely a safety net for a botched or
     * corrupted app-private install (uninstall/reinstall wipes files/instances/, but the public
     * Downloads copy survives) — never fatal if it fails, and never blocks re-download: a fresh
     * download's finalizeInstance() always overwrites the previous backup for that version.
     */
    private void backupInstanceZip(File instanceDir) {
        try {
            AppStorage storage = AppStorage.requireSingleton();
            String versionTag = readVersionTag(instanceDir);
            File zip = new File(storage.getDownloadsDir(), "OxygenNotIncluded_" + versionTag + ".zip");
            // Several GB of game go into one archive here. It is the slowest part of the whole
            // install and it happens AFTER the progress bar has reached 100%, which is exactly where
            // people think the app has hung and close it. Say what is happening, and how long.
            progress("Download finished. Packing a backup copy (a few GB — this takes a few minutes; "
                    + "keep the app open): " + zip.getAbsolutePath());
            ZipUtil.zipDir(instanceDir, zip);
            // Otherwise the file picker does not list it when the player adds an instance from it.
            storage.publishToMediaIndex(zip, "application/zip");
            progress("Backup saved: " + zip.getName() + " ("
                    + (zip.length() / (1024 * 1024)) + " MB). You can delete it from the Downloads "
                    + "folder if you need the space.");
        } catch (Throwable t) {
            Log.e(TAG, "backupInstanceZip failed (non-fatal)", t);
            progress("Backup copy skipped: " + describe(t));
        }
    }

    /**
     * What to call the backup archive. The game keeps its own version number inside compiled code, so
     * the honest identifier is Steam's: the build id of the branch we downloaded, or the manifest id
     * when the player pinned one by hand. "unknown" only if neither is known.
     */
    private String readVersionTag(File instanceDir) {
        if (buildId > 0) return "build" + buildId;
        if (manifestId > 0) return "manifest" + Long.toUnsignedString(manifestId);
        return "unknown";
    }

    /** Exception class + message + first useful cause/frame — getMessage() alone is often null. */
    private static String describe(Throwable t) {
        if (t == null) return "null";
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append("  <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        StackTraceElement[] st = t.getStackTrace();
        if (st != null && st.length > 0) sb.append("  @ ").append(st[0]);
        return sb.toString();
    }

    /** Approves via Steam Mobile push when possible; otherwise asks the UI for a Steam Guard code. */
    private class PushAuthenticator implements IAuthenticator {
        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            Log.i(TAG, "acceptDeviceConfirmation -> true (Steam Mobile approval push)");
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, null)
                    : CompletableFuture.completedFuture("");
        }
        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, email)
                    : CompletableFuture.completedFuture("");
        }
    }
}
