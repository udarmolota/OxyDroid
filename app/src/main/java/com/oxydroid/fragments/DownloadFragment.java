package com.oxydroid.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.oxydroid.R;
import com.oxydroid.SteamAnonModDownloader;
import com.oxydroid.SteamDownloadSpike;
import com.oxydroid.SteamDownloadState;
import com.oxydroid.StorageAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Download game (Steam)" screen — front-end for the in-app Steam downloader (SteamDownloadSpike).
 * One page, two tabs sharing the Steam login + console log:
 *   • Game — downloads the newest public build of Valheim into instances/&lt;name&gt; (launchable).
 *   • Mods — Workshop (anonymous) — coming soon.
 * Login is sent to Steam (like DepotDownloader) and never stored; the token lives only in memory.
 */
public class DownloadFragment extends Fragment implements SteamDownloadState.View {

    /**
     * Workshop item ids handed over by the Steam Workshop screen (WorkshopFragment): the Game page
     * opens on its Mods tab with them filled in, for the signed-in download. Taken once.
     */
    public static final java.util.List<Long> PENDING_WORKSHOP_IDS = new java.util.ArrayList<>();

    private final Handler mainH = new Handler(Looper.getMainLooper());

    private EditText etInstance, etUser, etPass, etManifest, etModsIds, etModsBrowserId;
    private Button btnStart, btnModsStart, btnModsBrowser, btnCancel, btnDlcStart, btnDlcAdd, btnDlcFromZip, btnModsInstall;
    private View sectionDlc;

    /** A DLC ZIP picked by hand ("Add DLC from a ZIP..."), then an instance to add it to. */
    private final androidx.activity.result.ActivityResultLauncher<String[]> pickDlcZip =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) chooseInstanceForDlc(uri); });
    private ProgressBar progress;
    private TextView tvStatus;
    private View blockLogin, sectionGame, sectionMods, sectionModsBrowser, tvLoginNote, btnInstallContent;
    private android.content.Context appCtx;   // for the keep-alive service (valid even if detached)

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        appCtx = requireContext().getApplicationContext();
        etInstance = v.findViewById(R.id.et_dl_instance);
        // Pre-fill the same default the ZIP-install screen uses ("ONI", or "ONI-2"…), so the
        // field is never left blank. The download lands directly in instances/<name>, and re-running a
        // download with the SAME name resumes it — so the name is worth showing, not guessing.
        if (etInstance.getText().length() == 0)
            etInstance.setText(com.oxydroid.AppStorage.freeDefaultInstanceName());
        etUser     = v.findViewById(R.id.et_dl_user);
        etPass     = v.findViewById(R.id.et_dl_pass);
        etManifest = v.findViewById(R.id.et_dl_manifest);
        btnStart   = v.findViewById(R.id.btn_dl_start);
        etModsIds  = v.findViewById(R.id.et_mods_ids);
        btnModsStart = v.findViewById(R.id.btn_mods_start);
        etModsBrowserId = v.findViewById(R.id.et_mods_browser_id);
        btnModsBrowser = v.findViewById(R.id.btn_mods_browser);
        progress   = v.findViewById(R.id.progress_dl);
        tvStatus   = v.findViewById(R.id.tv_dl_status);
        blockLogin  = v.findViewById(R.id.block_login);
        sectionGame = v.findViewById(R.id.section_game);
        sectionMods = v.findViewById(R.id.section_mods);
        sectionModsBrowser = v.findViewById(R.id.section_mods_browser);
        tvLoginNote = v.findViewById(R.id.tv_login_note);

        btnInstallContent = v.findViewById(R.id.btn_install_content);
        sectionDlc = v.findViewById(R.id.section_dlc);
        btnModsInstall = v.findViewById(R.id.btn_mods_install);
        btnModsInstall.setOnClickListener(view -> chooseInstanceForMods());
        btnDlcStart = v.findViewById(R.id.btn_dlc_start);
        btnDlcAdd = v.findViewById(R.id.btn_dlc_add);
        btnDlcFromZip = v.findViewById(R.id.btn_dlc_from_zip);
        btnDlcStart.setOnClickListener(view -> startDlcs());
        btnDlcAdd.setOnClickListener(view -> {
            java.io.File zip = SteamDownloadSpike.lastDlcZip;
            if (zip != null && zip.isFile()) chooseInstanceForDlc(android.net.Uri.fromFile(zip));
        });
        btnDlcFromZip.setOnClickListener(view ->
                pickDlcZip.launch(new String[] { "application/zip", "application/x-zip-compressed", "application/octet-stream" }));
        showDlcAdd();
        btnCancel = v.findViewById(R.id.btn_dl_cancel);
        tvStatus.setMovementMethod(new ScrollingMovementMethod());   // make the log area scrollable
        btnStart.setOnClickListener(view -> startGame());
        btnModsStart.setOnClickListener(view -> startMods());
        btnModsBrowser.setOnClickListener(view -> openModInBrowser());
        btnCancel.setOnClickListener(view -> confirmCancel());
        btnInstallContent.setOnClickListener(view ->
                androidx.navigation.Navigation.findNavController(view).navigate(R.id.action_open_install));

        // Game / Mods selector → swap the visible section. The login block is for Game (mods download
        // anonymously), and "install downloaded content" is for Mods only (Game makes an instance directly).
        com.google.android.material.button.MaterialButtonToggleGroup toggle =
                v.findViewById(R.id.toggle_dl_type);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean game = checkedId == R.id.btn_type_game;
            boolean mods = checkedId == R.id.btn_type_mods;
            boolean dlc = checkedId == R.id.btn_type_dlc;
            sectionGame.setVisibility(game ? View.VISIBLE : View.GONE);
            sectionDlc.setVisibility(dlc ? View.VISIBLE : View.GONE);
            sectionMods.setVisibility(mods ? View.VISIBLE : View.GONE);
            // Game and DLC sign in; Workshop mods are public links and need no sign-in. The
            // third-party browser download and RimWorld's content installer are not offered.
            sectionModsBrowser.setVisibility(View.GONE);
            blockLogin.setVisibility(mods ? View.GONE : View.VISIBLE);
            tvLoginNote.setVisibility(mods ? View.GONE : View.VISIBLE);
            btnInstallContent.setVisibility(View.GONE);
        });
        toggle.check(R.id.btn_type_game);   // default to Game
        // Game / DLC / Mods. Ids handed over by the Steam Workshop screen open the Mods tab, filled in.
        showModsInstall();
        synchronized (PENDING_WORKSHOP_IDS) {
            if (!PENDING_WORKSHOP_IDS.isEmpty()) {
                StringBuilder ids = new StringBuilder();
                for (Long id : PENDING_WORKSHOP_IDS) ids.append(ids.length() == 0 ? "" : " ").append(id);
                PENDING_WORKSHOP_IDS.clear();
                etModsIds.setText(ids);
                toggle.check(R.id.btn_type_mods);
            }
        }

        // Re-attach to a download already running in the background (the worker thread outlives this
        // fragment via SteamDownloadState), restoring the log + progress + "busy" state on return.
        SteamDownloadState st = SteamDownloadState.get();
        st.setView(this);
        tvStatus.setText(st.getLog());
        scrollLogToBottom();
        boolean busy = st.isDownloading();
        setControlsEnabled(!busy);
        btnCancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            progress.setVisibility(View.VISIBLE);
            if (st.isIndeterminate() || st.getPercent() < 0) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setProgress(st.getPercent());
            }
        } else {
            progress.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        SteamDownloadState.get().clearView(this);
        super.onDestroyView();
    }

    // ---- Game ----
    private void startGame() {
        if (SteamDownloadState.get().isDownloading()) return;
        final String name = text(etInstance);
        if (name.isEmpty()) { etInstance.setError(getString(R.string.error_name_required)); return; }
        if (!loginFilled()) return;

        long manifestId = 0L;   // 0 = the newest public build
        try {
            String mt = text(etManifest);
            if (!mt.isEmpty()) manifestId = Long.parseLong(mt);
        } catch (NumberFormatException ignored) { /* blank/invalid → default */ }

        SteamDownloadState st = SteamDownloadState.get();
        SteamDownloadSpike dl = new SteamDownloadSpike(text(etUser), etPass.getText().toString(), name,
                /* manifestOnly */ false, manifestId, st);
        // The backup archive is packed after the bar hits 100%, and that step is long enough that
        // people close the app thinking it hung. Say so before they start waiting.
        st.onProgress(getString(R.string.download_backup_note));
        st.begin(appCtx, name);          // adviseInstance = name → auto-set GPU driver on success
        st.setActive(dl);
        beginUi();
        new Thread(dl, "rd-download").start();
    }

    // ---- DLC: the DLCs the account owns, into a ZIP in /Download/OxyDroid, then into an instance ----
    private void startDlcs() {
        if (SteamDownloadState.get().isDownloading()) return;
        if (!loginFilled()) return;
        // The copy goes to the public Download folder: All-files access on Android 11+.
        if (!StorageAccess.hasAllFilesAccess()) {
            StorageAccess.requestAllFilesAccess(requireActivity());
            return;
        }
        SteamDownloadState st = SteamDownloadState.get();
        SteamDownloadSpike dl = SteamDownloadSpike.forDlcs(text(etUser), etPass.getText().toString(), st);
        st.begin(appCtx, null);
        st.setActive(dl);
        beginUi();
        new Thread(dl, "rd-dlc-download").start();
    }

    /** The button under the download: the ZIP the last DLC download made, named on it. */
    private void showDlcAdd() {
        java.io.File zip = SteamDownloadSpike.lastDlcZip;
        boolean has = zip != null && zip.isFile();
        btnDlcAdd.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) btnDlcAdd.setText(getString(R.string.download_dlc_add, dlcCount(zip.getName())));
    }

    /** How many DLCs a DLC ZIP holds, from its name: OxygenNotIncluded_DLC_<id>_<id>..._build<n>.zip. */
    private static int dlcCount(String name) {
        int n = 0;
        for (String part : name.split("_"))
            if (part.matches("\\d+")) n++;
        return Math.max(n, 1);
    }

    /** Which instance the DLC ZIP goes into: one with the game; the only one is not asked about. */
    private void chooseInstanceForDlc(android.net.Uri zip) {
        List<com.oxydroid.game.GameInstance> withGame = new ArrayList<>();
        for (com.oxydroid.game.GameInstance gi : com.oxydroid.game.GameInstanceManager.requireSingleton().getInstances())
            if (gi.isInstalled()) withGame.add(gi);
        if (withGame.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_instances, Toast.LENGTH_SHORT).show();
            return;
        }
        if (withGame.size() == 1) { addDlc(zip, withGame.get(0)); return; }
        String[] names = new String[withGame.size()];
        for (int i = 0; i < names.length; i++) names[i] = withGame.get(i).getName();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.download_dlc_choose)
                .setItems(names, (d, which) -> addDlc(zip, withGame.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addDlc(android.net.Uri zip, com.oxydroid.game.GameInstance gi) {
        final androidx.appcompat.app.AlertDialog wait = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                .setMessage(R.string.download_dlc_installing)
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String msg;
            try {
                List<Integer> ids = com.oxydroid.game.DlcZip.install(appCtx, zip, gi);
                msg = appCtx.getString(R.string.download_dlc_added, gi.getName(), ids.toString());
            } catch (Exception e) {
                msg = appCtx.getString(R.string.download_dlc_failed, e.getMessage());
            }
            final String fMsg = msg;
            mainH.post(() -> {
                try { wait.dismiss(); } catch (RuntimeException ignored) { }
                if (isAdded())
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                            .setMessage(fMsg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
            });
        }, "oxy-dlc-add").start();
    }

    // ---- Mods (by Workshop ID) — ANONYMOUS, no login. We resolve each item's owning game
    //      (consumer_app_id) ourselves, so this works for ANY public Workshop item, not just
    //      RimWorld. Items that require ownership fall back to the browser/ggntw option below. ----
    private void startMods() {
        if (SteamDownloadState.get().isDownloading()) return;
        // Bare ids or Workshop links, one or many.
        List<Long> ids = WorkshopIds.parse(text(etModsIds));
        if (ids.isEmpty()) {
            etModsIds.setError(getString(R.string.workshop_ids_missing));
            return;
        }
        // Mods zip into the public /Download folder → needs All-files access on Android 11+.
        if (!StorageAccess.hasAllFilesAccess()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                    .setTitle("Storage access needed")
                    .setMessage("Mods are saved as zips into the public Download folder so you can see, "
                            + "share and reuse them. Please grant \"All files access\" to OxyDroid, "
                            + "then tap Download again.")
                    .setPositiveButton("Grant", (d, w) -> StorageAccess.requestAllFilesAccess(requireActivity()))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        // No sign-in: Oxygen Not Included's Workshop mods are single files at public links
        // (WorkshopHttpDownloader).
        SteamDownloadState st = SteamDownloadState.get();
        com.oxydroid.WorkshopHttpDownloader dl = new com.oxydroid.WorkshopHttpDownloader(ids, st);
        st.begin(appCtx, null);
        st.setActive(dl);
        beginUi();
        new Thread(dl, "oxy-workshop-dl").start();
    }

    /** Under the download: the mod ZIPs it made, into an instance. */
    private void showModsInstall() {
        int n = 0;
        for (java.io.File z : SteamDownloadSpike.lastModZips) if (z.isFile()) n++;
        btnModsInstall.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        if (n > 0) btnModsInstall.setText(getString(R.string.download_mods_install, n));
    }

    private void chooseInstanceForMods() {
        List<com.oxydroid.game.GameInstance> withGame = new ArrayList<>();
        for (com.oxydroid.game.GameInstance gi : com.oxydroid.game.GameInstanceManager.requireSingleton().getInstances())
            if (gi.isInstalled()) withGame.add(gi);
        if (withGame.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_instances, Toast.LENGTH_SHORT).show();
            return;
        }
        if (withGame.size() == 1) { installMods(withGame.get(0)); return; }
        String[] names = new String[withGame.size()];
        for (int i = 0; i < names.length; i++) names[i] = withGame.get(i).getName();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.download_mods_choose)
                .setItems(names, (d, which) -> installMods(withGame.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The downloaded mod ZIPs into the instance's mods (as the Mods screen's ZIP import), switched on at the next start. */
    private void installMods(com.oxydroid.game.GameInstance gi) {
        final androidx.appcompat.app.AlertDialog wait = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                .setMessage(R.string.download_mods_installing)
                .setCancelable(false)
                .show();
        final List<java.io.File> zips = new ArrayList<>(SteamDownloadSpike.lastModZips);
        new Thread(() -> {
            List<String> installed = new ArrayList<>(), failed = new ArrayList<>();
            java.io.File userDir = gi.getUserDataDir();
            for (java.io.File zip : zips) {
                if (!zip.isFile()) continue;
                String base = zip.getName().endsWith(".zip") ? zip.getName().substring(0, zip.getName().length() - 4) : zip.getName();
                try {
                    installed.addAll(com.oxydroid.game.OniMods.importZip(zip, userDir, base, new ArrayList<>()));
                } catch (Exception e) {
                    failed.add(zip.getName() + ": " + e.getMessage());
                }
            }
            com.oxydroid.game.OniMods.markForEnable(userDir, installed);
            StringBuilder msg = new StringBuilder(appCtx.getString(R.string.download_mods_installed, gi.getName(), installed.size()));
            for (String n : installed) msg.append("\n• ").append(n);
            if (!failed.isEmpty()) {
                msg.append("\n\n").append(appCtx.getString(R.string.mods_copy_failed));
                for (String f : failed) msg.append("\n• ").append(f);
            }
            mainH.post(() -> {
                try { wait.dismiss(); } catch (RuntimeException ignored) { }
                if (isAdded())
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                            .setMessage(msg.toString())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
            });
        }, "oxy-mods-install").start();
    }

    private void confirmCancel() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                .setMessage(R.string.download_cancel_confirm)
                .setPositiveButton(R.string.download_cancel, (d, w) -> SteamDownloadState.get().cancel())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Mods without login: ask the ggntw.com third-party service for a download link, open it in the
    //      browser (same approach as Zomdroid). The user downloads the .zip there, then Installs it. ----
    private void openModInBrowser() {
        final String id = text(etModsBrowserId);
        if (id.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a Workshop ID", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(requireContext(), "Requesting download link…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String resultUrl = null, err = null;
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.ggntw.com/steam.request").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                conn.setRequestProperty("Origin", "https://ggntw.com");
                conn.setRequestProperty("Referer", "https://ggntw.com/");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                String body = new org.json.JSONObject().put("url",
                        "https://steamcommunity.com/sharedfiles/filedetails/?id=" + id).toString();
                conn.getOutputStream().write(body.getBytes("UTF-8"));
                int code = conn.getResponseCode();
                java.io.InputStream is = code == 200 ? conn.getInputStream() : conn.getErrorStream();
                java.util.Scanner sc = new java.util.Scanner(is).useDelimiter("\\A");
                String resp = sc.hasNext() ? sc.next() : "";
                sc.close();
                conn.disconnect();
                if (resp.startsWith("http")) {
                    resultUrl = resp.trim();
                } else {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(resp);
                        resultUrl = o.optString("url", o.optString("link", null));
                    } catch (Exception ignored) {}
                }
                if (resultUrl == null || resultUrl.isEmpty()) err = "service returned: " + resp;
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fUrl = resultUrl, fErr = err;
            mainH.post(() -> {
                if (!isAdded()) return;
                if (fUrl != null && !fUrl.isEmpty()) {
                    try {
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(fUrl)));
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "Can't open browser: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(requireContext(), "Download link failed — " + (fErr != null ? fErr : "no link"),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "rd-ggntw").start();
    }

    // ---- shared ----
    private boolean loginFilled() {
        if (text(etUser).isEmpty()) { etUser.setError("Required"); return false; }
        if (etPass.getText().toString().isEmpty()) { etPass.setError("Required"); return false; }
        return true;
    }

    private void beginUi() {
        setControlsEnabled(false);
        btnCancel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);   // until the first real percent arrives
        tvStatus.setText("");              // SteamDownloadState already cleared its log buffer in begin()
    }

    /** Enable/disable the start controls (everything except Cancel) while a download runs. */
    private void setControlsEnabled(boolean enabled) {
        if (btnStart != null) btnStart.setEnabled(enabled);
        if (btnDlcStart != null) btnDlcStart.setEnabled(enabled);
        if (btnDlcAdd != null) btnDlcAdd.setEnabled(enabled);
        if (btnDlcFromZip != null) btnDlcFromZip.setEnabled(enabled);
        if (btnModsStart != null) btnModsStart.setEnabled(enabled);
        if (btnModsBrowser != null) btnModsBrowser.setEnabled(enabled);
        if (etInstance != null) etInstance.setEnabled(enabled);
        if (etUser != null) etUser.setEnabled(enabled);
        if (etPass != null) etPass.setEnabled(enabled);
        if (etManifest != null) etManifest.setEnabled(enabled);
        if (etModsIds != null) etModsIds.setEnabled(enabled);
    }

    // ---- SteamDownloadState.View (callbacks already posted to the main thread by the state) ----
    @Override
    public void onLog(CharSequence fullLog) {
        if (tvStatus == null) return;
        tvStatus.setText(fullLog);
        scrollLogToBottom();
    }

    @Override
    public void onPercent(int percent, boolean indeterminate) {
        if (progress == null) return;
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(indeterminate);
        if (!indeterminate) progress.setProgress(Math.max(0, Math.min(100, percent)));
    }

    @Override
    public void onFinished(String message) {
        setControlsEnabled(true);
        if (btnDlcAdd != null) showDlcAdd();
        if (btnModsInstall != null) showModsInstall();
        if (progress != null) progress.setVisibility(View.GONE);
        if (btnCancel != null) btnCancel.setVisibility(View.GONE);
        if (isAdded()) {
            Toast.makeText(requireContext().getApplicationContext(), message, Toast.LENGTH_LONG).show();
        }
        // The worker already applied the recommendation, even if this fragment was detached when
        // the download finished. Here we only show its one-time explanation.
        com.oxydroid.GpuDriverAdvisor.Result driver = SteamDownloadState.get().takeDriverResult();
        if (driver != null && driver.applied && driver.mobileGlues && isAdded()) showDriverResult(driver);
    }

    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
        final CompletableFuture<String> fut = new CompletableFuture<>();
        mainH.post(() -> {
            if (!isAdded()) { fut.complete(""); return; }
            final EditText etCode = new EditText(requireActivity());
            etCode.setHint(email != null ? ("Code emailed to " + email) : "Steam Guard code");
            etCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(prevWrong ? "Code incorrect — try again" : "Enter Steam Guard code")
                    .setView(etCode)
                    .setCancelable(false)
                    .setPositiveButton("OK", (d, w) -> fut.complete(etCode.getText().toString().trim()))
                    .show();
        });
        return fut;
    }

    /** Off-thread GPU detect → set the instance's recommended driver → inform the user. */
    private void showDriverResult(com.oxydroid.GpuDriverAdvisor.Result r) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.renderer_default_title)
                .setMessage(R.string.renderer_default_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void scrollLogToBottom() {
        if (tvStatus == null) return;
        android.text.Layout layout = tvStatus.getLayout();
        if (layout != null) {
            int y = layout.getLineTop(tvStatus.getLineCount()) - tvStatus.getHeight();
            tvStatus.scrollTo(0, Math.max(0, y));
        }
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}
