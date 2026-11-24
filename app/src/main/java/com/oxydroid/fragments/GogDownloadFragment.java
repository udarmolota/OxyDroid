package com.oxydroid.fragments;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.oxydroid.AppStorage;
import com.oxydroid.R;
import com.oxydroid.StorageAccess;
import com.oxydroid.gog.GogAuth;
import com.oxydroid.gog.GogDownloadQueue;
import com.oxydroid.gog.GogDownloader;
import com.oxydroid.gog.GogLibrary;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Download from GOG": a WebView on GOG's own login page, then the account's Oxygen Not Included
 * installers as cards. Ported from Zomdroid (which took it from RimDroid, MIT).
 *
 * <p>The password is never seen here: GOG's page handles it, along with the CAPTCHA, e-mail
 * two-step and TOTP prompts a headless login could not clear. The screen only watches for the
 * navigation to Galaxy's redirect target and takes the authorisation code out of it; the cookies
 * the page leaves behind in Android's store are the other half of the credentials (see
 * {@link GogAuth}).
 *
 * <p>When a session already exists the WebView is skipped and the screen lists what the account
 * owns that OxyDroid can install: one card per Linux installer of the game, then one per Linux
 * installer of its DLC. A card's button follows its file: download, queued, percentage, then for
 * the game "Create an instance", which opens the new-instance screen with the installer already
 * chosen and every DLC installer already downloaded riding along. The transfers belong to
 * {@link GogDownloadQueue}, not to this screen, so leaving it and coming back loses nothing.
 */
public class GogDownloadFragment extends Fragment implements GogDownloadQueue.Listener {
    private static final String TAG = "OxyDroid/GOG";

    /** What the library search is asked for; the result is then matched by {@link #isOni}. */
    private static final String SEARCH_QUERY = "oxygen not included";
    /** The game's title with everything but letters and digits removed, lower case. */
    private static final String ONI_KEY = "oxygennotincluded";

    /**
     * How many products the fallback walk will open. Titles are only knowable by opening each
     * product (see {@link #probeLibrary}), so this is a ceiling on requests, not on what the user
     * owns. The library search runs first and finds the game in one request whatever the library
     * size; the walk only runs when that search failed or found nothing.
     */
    private static final int SCAN_LIMIT = 100;

    /** One library scan: the games to offer, their DLC, and whether it fell back to "any Linux game". */
    private static final class Scan {
        final List<GogLibrary.Product> games;
        final List<GogLibrary.Product> dlcs;
        final boolean fellBack;

        Scan(List<GogLibrary.Product> games, List<GogLibrary.Product> dlcs, boolean fellBack) {
            this.games = games;
            this.dlcs = dlcs;
            this.fellBack = fellBack;
        }
    }

    /**
     * The last scan's result, kept for the life of the process: coming back to this screen then
     * shows the cards, and any download still running, straight away instead of an empty list and
     * another round of requests.
     */
    private static volatile Scan lastScan;

    /** Public no-arg constructor: the navigation graph instantiates this screen by class name. */
    public GogDownloadFragment() {}

    private WebView web;
    private View progress;
    private TextView status;
    private View signedInBlock;
    /** "User ID: …" under "Signed in to GOG" in the signed-in block. */
    private TextView userLine;
    /** The redirect fires for the page load AND its sub-resources; only act on the first one. */
    private boolean codeTaken = false;
    /** True while www.gog.com is being loaded purely to make GOG hand over a www session cookie. */
    private boolean establishingSession = false;
    /** Holds one item_gog_game card per installer. */
    private ViewGroup gameList;
    /** The scroll around gameList; visible only while signed in (see showSignedIn). */
    private View gameScroll;
    /** The cards on screen, so that a queue update can restyle them in place. */
    private final List<Card> cards = new ArrayList<>();
    /**
     * Worker threads reach the UI only through this (see {@link #onUi}). requireActivity() and
     * getString() both throw once the user has left the screen, and a scan can easily outlive it.
     */
    private final Handler main = new Handler(Looper.getMainLooper());

    /** One card on screen and the installer behind it. */
    private static final class Card {
        final GogLibrary.Product game;
        final GogLibrary.File file;
        /** A DLC installer: downloaded here, installed together with the game. */
        final boolean dlc;
        final TextView subtitle;
        final Button button;
        /** Size and version: what the subtitle says whenever there is no error to report. */
        final String details;

        Card(GogLibrary.Product game, GogLibrary.File file, boolean dlc, TextView subtitle,
             Button button, String details) {
            this.game = game;
            this.file = file;
            this.dlc = dlc;
            this.subtitle = subtitle;
            this.button = button;
            this.details = details;
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_gog_download, container, false);
    }

    @Override
    @SuppressLint("SetJavaScriptEnabled")   // GOG's login page does not work without it
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        GogLibrary.init(requireContext());
        web           = v.findViewById(R.id.web_gog);
        progress      = v.findViewById(R.id.progress_gog);
        status        = v.findViewById(R.id.tv_gog_status);
        signedInBlock = v.findViewById(R.id.block_gog_signed_in);
        userLine      = v.findViewById(R.id.tv_gog_user);
        gameList      = v.findViewById(R.id.gog_game_list);
        gameScroll    = v.findViewById(R.id.scroll_gog_games);

        // Disclaimer card: collapsed by default; tapping the header expands or collapses it and the
        // arrow turns to match. Wired before the signed-in early return below, because the card is
        // on screen in both states.
        final View discText = v.findViewById(R.id.tv_gog_disclaimer_text);
        final ImageView discArrow = v.findViewById(R.id.gog_disclaimer_expand_iv);
        v.findViewById(R.id.gog_disclaimer_header).setOnClickListener(x -> {
            boolean show = discText.getVisibility() != View.VISIBLE;
            discText.setVisibility(show ? View.VISIBLE : View.GONE);
            discArrow.setRotation(show ? 180 : 0);
        });

        v.findViewById(R.id.btn_gog_check).setOnClickListener(x -> probeLibrary());
        v.findViewById(R.id.btn_gog_sign_out).setOnClickListener(x -> {
            GogAuth.signOut();
            lastScan = null;
            status.setText("");
            clearCards();
            showSignedIn(false);
            codeTaken = false;
            if (web != null) web.loadUrl(GogAuth.authUrl());
        });

        GogDownloadQueue.get().setListener(this);

        // The WebView is set up in both states: signing out from the signed-in view reloads the
        // login page into it, and that needs the client below to catch the redirect.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handle(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // The redirect can arrive as a plain navigation rather than one to override, so
                // check here too. handle() is idempotent.
                handle(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (establishingSession && url != null && url.contains("gog.com")) finishSignIn();
            }
        });

        if (GogAuth.isSignedIn()) {
            showSignedIn(true);
            Scan shown = lastScan;
            // Who is signed in is shown in the signed-in block; the status line is for notes only.
            status.setText(shown != null && shown.fellBack ? getString(R.string.gog_no_game) : "");
            if (shown != null) render(shown);
            return;
        }

        web.loadUrl(GogAuth.authUrl());
    }

    /**
     * Swap between the login WebView and the signed-in controls. The game list and the WebView share
     * the leftover height, so exactly one of them may be visible.
     */
    private void showSignedIn(boolean signedIn) {
        signedInBlock.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        gameScroll.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (web != null) web.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        if (signedIn) userLine.setText(getString(R.string.gog_user_id, String.valueOf(GogAuth.userId())));
    }

    /** @return true when the URL was the login callback and it was consumed. */
    private boolean handle(String url) {
        String code = GogAuth.codeFrom(url);
        if (code == null || codeTaken) return false;
        codeTaken = true;

        if (web != null) web.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_login_finishing);

        new Thread(() -> {
            String error = null;
            try {
                // Flush now: the tokens are useless without the cookies the page just set, and
                // Android writes them out lazily.
                CookieManager.getInstance().flush();
                GogAuth.signInWithCode(code);
            } catch (Exception e) {
                Log.e(TAG, "sign-in failed", e);
                error = e.getMessage();
            }
            final String err = error;
            onUi(() -> {
                if (err == null) {
                    establishSession();
                } else {
                    progress.setVisibility(View.GONE);
                    status.setText(getString(R.string.gog_login_failed, err));
                }
            });
        }, "oxy-gog-token").start();
        return true;
    }

    /**
     * Having the tokens is not enough to read the library: those endpoints live on www.gog.com and
     * authorise by cookie, but the OAuth flow only ever touched auth/login/embed, so no www cookie
     * exists yet. Load one www page so GOG turns that login into a www session. If it never
     * finishes, carry on anyway: GogAuth.cookieHeader() falls back to sending the login-host
     * cookies, which may well be accepted.
     */
    private void establishSession() {
        establishingSession = true;
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_login_finishing);
        if (web != null) web.loadUrl("https://www.gog.com/account");
        status.postDelayed(() -> { if (establishingSession) finishSignIn(); }, 15000);
    }

    /** Settle the sign-in once the www page has loaded (or the wait for it was given up). */
    private void finishSignIn() {
        if (!establishingSession || !isAdded() || getView() == null) return;
        establishingSession = false;
        CookieManager.getInstance().flush();
        progress.setVisibility(View.GONE);
        Toast.makeText(requireContext(), R.string.gog_login_ok, Toast.LENGTH_SHORT).show();
        showSignedIn(true);
        status.setText("");
    }

    /**
     * List what the account owns that OxyDroid can install.
     *
     * <p>First GOG's library search is asked for the game by title: one request whatever the
     * library size (an account with 350+ games was told it had no game when the walk below was the
     * only way, Zomdroid 2026-11-21). The walk stays as the fallback, for when the search fails or
     * finds nothing: GOG's owned-games endpoint returns bare ids, so a title is only knowable by
     * opening each product, which is why the walk reports its progress and stops at
     * {@link #SCAN_LIMIT}. Everything with a Linux installer is collected on the way through, so
     * that an account without the game still has something to show rather than an empty screen.
     */
    private void probeLibrary() {
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_checking);
        clearCards();

        new Thread(() -> {
            List<GogLibrary.Product> oni = new ArrayList<>();
            List<GogLibrary.Product> anyLinux = new ArrayList<>();
            Throwable error = null;
            try {
                try {
                    for (Long id : GogLibrary.searchOwned(SEARCH_QUERY)) {
                        GogLibrary.Product p = GogLibrary.details(id);
                        if (p == null || !hasLinux(p)) continue;
                        if (isOni(p.title)) oni.add(p);
                    }
                } catch (Exception searchFailed) {
                    Log.w(TAG, "library search failed, walking the library instead", searchFailed);
                }
                List<Long> owned = oni.isEmpty() ? GogLibrary.ownedIds() : new ArrayList<>();
                int of = Math.min(owned.size(), SCAN_LIMIT);
                int scanned = 0;
                for (Long id : owned) {
                    if (scanned >= SCAN_LIMIT) break;
                    // Resolved on the UI side: a fragment the user has left has no resources.
                    final int at = ++scanned;
                    onUi(() -> status.setText(getString(R.string.gog_scanning, at, of)));

                    GogLibrary.Product p = GogLibrary.details(id);
                    if (p == null || !hasLinux(p)) continue;
                    anyLinux.add(p);
                    if (isOni(p.title)) oni.add(p);
                }
            } catch (Throwable t) {
                Log.e(TAG, "library scan failed", t);
                error = t;
            }

            final Scan scan = oni.isEmpty()
                    ? new Scan(anyLinux, new ArrayList<>(), !anyLinux.isEmpty())
                    : split(oni);
            final Throwable err = error;
            if (err == null && !scan.games.isEmpty()) lastScan = scan;
            Log.i(TAG, "library scan: " + oni.size() + " oni product(s), " + scan.dlcs.size()
                    + " dlc, " + anyLinux.size() + " walked with a linux installer");
            onUi(() -> {
                progress.setVisibility(View.GONE);
                if (err != null) {
                    status.setText(getString(R.string.gog_probe_failed,
                            String.valueOf(err.getMessage())));
                    return;
                }
                if (scan.games.isEmpty()) { status.setText(R.string.gog_nothing_linux); return; }
                status.setText(scan.fellBack ? getString(R.string.gog_no_game) : "");
                render(scan);
            });
        }, "oxy-gog-probe").start();
    }

    /**
     * Tell the game from its DLC among the products whose title names the game. GOG lists owned DLC
     * twice: nested in the game's own record, and (in the search) as products of their own, titled
     * "Oxygen Not Included - …". The product titled exactly like the game is the game; if none is,
     * every match is offered as a game, the same loose rule Zomdroid uses for edition names.
     */
    private static Scan split(List<GogLibrary.Product> matches) {
        List<GogLibrary.Product> games = new ArrayList<>();
        List<GogLibrary.Product> loose = new ArrayList<>();
        for (GogLibrary.Product p : matches) {
            if (ONI_KEY.equals(key(p.title))) games.add(p);
            else loose.add(p);
        }
        if (games.isEmpty()) return new Scan(matches, new ArrayList<>(), false);
        List<GogLibrary.Product> dlcs = new ArrayList<>();
        for (GogLibrary.Product g : games)
            for (GogLibrary.Product d : g.dlcs)
                if (!d.linuxInstallers().isEmpty()) dlcs.add(d);
        dlcs.addAll(loose);
        return new Scan(games, dlcs, false);
    }

    private static boolean hasLinux(GogLibrary.Product p) {
        if (!p.linuxInstallers().isEmpty()) return true;
        for (GogLibrary.Product d : p.dlcs) if (!d.linuxInstallers().isEmpty()) return true;
        return false;
    }

    /** Lay out the cards: one per Linux installer of each game, then one per DLC installer. */
    private void render(Scan scan) {
        clearCards();
        // The same installer can arrive twice (a DLC nested in the game and found on its own).
        Set<String> seen = new HashSet<>();
        for (GogLibrary.Product game : scan.games)
            for (GogLibrary.File f : oneLanguage(game.linuxInstallers()))
                if (seen.add(f.manualUrl)) addCard(game, f, false);
        for (GogLibrary.Product dlc : scan.dlcs)
            for (GogLibrary.File f : oneLanguage(dlc.linuxInstallers()))
                if (seen.add(f.manualUrl)) addCard(dlc, f, true);
        refreshCards();
    }

    private void addCard(GogLibrary.Product game, GogLibrary.File f, boolean dlc) {
        View row = getLayoutInflater().inflate(R.layout.item_gog_game, gameList, false);
        TextView titleView = row.findViewById(R.id.gog_item_title);
        titleView.setText(game.title == null || game.title.isEmpty() ? f.name : game.title);

        String version = f.version == null ? "" : f.version.trim();
        String details = version.isEmpty()
                ? f.size : getString(R.string.gog_item_installer, f.size, version);
        if (dlc) details = getString(R.string.gog_item_dlc, details);

        Card card = new Card(game, f, dlc, row.findViewById(R.id.gog_item_subtitle),
                row.findViewById(R.id.gog_item_button), details);
        card.button.setOnClickListener(x -> onCardButton(card));
        cards.add(card);
        gameList.addView(row);
    }

    private void clearCards() {
        cards.clear();
        if (gameList != null) gameList.removeAllViews();
    }

    @Override
    public void onQueueChanged() {
        if (getView() != null) refreshCards();
    }

    private static File downloadsDir() {
        return AppStorage.requireSingleton().getDownloadsDir();
    }

    /** Bring every card's button and subtitle in line with the state of its file. */
    private void refreshCards() {
        File destDir = downloadsDir();
        GogDownloadQueue queue = GogDownloadQueue.get();
        for (Card c : cards) {
            GogDownloadQueue.Entry e = queue.entryFor(c.file, destDir);
            GogDownloadQueue.Phase phase = e == null ? null : e.phase;
            c.subtitle.setText(c.details);
            if (phase == GogDownloadQueue.Phase.QUEUED) {
                c.button.setText(R.string.gog_queued);
                c.button.setEnabled(false);
            } else if (phase == GogDownloadQueue.Phase.RUNNING) {
                c.button.setText(e.percent < 0
                        ? getString(R.string.gog_downloading)
                        : getString(R.string.gog_percent, e.percent));
                c.button.setEnabled(false);
            } else if (GogDownloader.completed(c.file, destDir) != null) {
                if (c.dlc) {
                    // A DLC has nothing of its own to do: it goes in with the game's instance.
                    c.subtitle.setText(getString(R.string.gog_dlc_with_game, c.details));
                    c.button.setText(R.string.gog_dlc_ready);
                    c.button.setEnabled(false);
                } else {
                    c.button.setText(R.string.gog_create_instance);
                    // Only Oxygen Not Included goes further. Another game is listed when the account
                    // has no ONI, so downloads can still be tried, but the installer would reject it.
                    c.button.setEnabled(isOni(c.game.title));
                }
            } else {
                // Never asked for, downloaded and since deleted, or failed: offer it (again).
                if (phase == GogDownloadQueue.Phase.FAILED) c.subtitle.setText(failureText(e.failure));
                c.button.setText(R.string.gog_download);
                c.button.setEnabled(true);
            }
        }
    }

    /** A card's button: the next step for a file already on the phone, otherwise download it. */
    private void onCardButton(Card c) {
        File destDir = downloadsDir();
        File done = GogDownloader.completed(c.file, destDir);
        if (done != null) {
            if (c.dlc || !isOni(c.game.title)) return;   // shut for these, see refreshCards
            createInstance(done);
            return;
        }
        // The public Download/OxyDroid folder is the point (the user can see and reuse the file,
        // next to the Steam downloads), and writing there needs All-files access on Android 11+.
        if (!ensureAllFilesAccess()) return;
        GogDownloadQueue.get().enqueue(requireContext(), c.file, destDir);
    }

    /**
     * Create an instance from the game installer, with every DLC installer that has finished
     * downloading. A DLC still on its way would be left out silently, so ask first.
     */
    private void createInstance(File installer) {
        File destDir = downloadsDir();
        GogDownloadQueue queue = GogDownloadQueue.get();
        ArrayList<String> extras = new ArrayList<>();
        boolean pending = false;
        for (Card c : cards) {
            if (!c.dlc) continue;
            GogDownloadQueue.Entry e = queue.entryFor(c.file, destDir);
            if (e != null && (e.phase == GogDownloadQueue.Phase.QUEUED
                    || e.phase == GogDownloadQueue.Phase.RUNNING)) {
                pending = true;
                continue;
            }
            File f = GogDownloader.completed(c.file, destDir);
            if (f != null) extras.add(f.getAbsolutePath());
        }
        if (!pending) {
            openNewInstance(installer, extras);
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.gog_dlc_pending_title)
                .setMessage(R.string.gog_dlc_pending_message)
                .setPositiveButton(R.string.gog_create_anyway, (d, w) -> openNewInstance(installer, extras))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Hand the installer to the new-instance screen by path, through the graph's existing global
     * action. That screen's own picker is ZIP-only, and most file managers would not show it a bare
     * .sh anyway.
     */
    private void openNewInstance(File installer, ArrayList<String> extras) {
        if (!isAdded()) return;
        Bundle args = new Bundle();
        args.putString(NewInstanceFragment.ARG_PRESELECTED_FILE, installer.getAbsolutePath());
        if (!extras.isEmpty()) args.putStringArrayList(NewInstanceFragment.ARG_EXTRA_FILES, extras);
        NavHostFragment.findNavController(this).navigate(R.id.action_install_instance, args);
    }

    /** A title with everything but letters and digits removed, lower case. */
    private static String key(String title) {
        return title == null ? "" : title.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Matches the game by title, loosely about spacing, punctuation and suffixes: GOG's titles can
     * carry edition names, and missing the game over one is worse than showing a card the user can
     * ignore.
     */
    private static boolean isOni(String title) {
        return key(title).contains(ONI_KEY);
    }

    /**
     * GOG lists the same installer once per language, which would put a dozen identical cards on
     * screen. Keep a single language, English when the account has it, otherwise whichever came
     * first, so that a multi-part installer still shows every one of its parts.
     */
    private static List<GogLibrary.File> oneLanguage(List<GogLibrary.File> installers) {
        String want = null;
        for (GogLibrary.File f : installers)
            if ("English".equalsIgnoreCase(f.language)) { want = f.language; break; }
        if (want == null && !installers.isEmpty()) want = installers.get(0).language;

        List<GogLibrary.File> out = new ArrayList<>();
        for (GogLibrary.File f : installers)
            if (want == null || want.equals(f.language)) out.add(f);
        return out;
    }

    /**
     * The line to show when a download stops early. A dropped connection gets its own message: it
     * is the everyday failure on a phone, and a recoverable one (what already arrived stays in the
     * part file for the next attempt), so the user is told to tap again rather than shown a socket
     * error. The exception itself is in the log either way. UI thread only.
     */
    private String failureText(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            boolean dropped = c instanceof java.net.SocketException           // incl. refused connects
                    || c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.UnknownHostException             // no network at all
                    || (c instanceof javax.net.ssl.SSLException
                        && !(c instanceof javax.net.ssl.SSLHandshakeException));
            if (dropped)
                return getString(R.string.gog_download_interrupted, getString(R.string.gog_download));
        }
        return getString(R.string.gog_download_failed, t == null ? "?" : String.valueOf(t.getMessage()));
    }

    /** All-files access for the public Download/OxyDroid folder, asked the way the Steam screen does. */
    private boolean ensureAllFilesAccess() {
        if (StorageAccess.hasAllFilesAccess()) return true;
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.gog_storage_title)
                .setMessage(R.string.gog_storage_message)
                .setPositiveButton(R.string.gog_storage_grant,
                        (d, w) -> StorageAccess.requestAllFilesAccess(requireActivity()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return false;
    }

    /** Run {@code r} on the UI thread, or drop it if the screen is gone by the time it gets there. */
    private void onUi(Runnable r) {
        main.post(() -> { if (isAdded() && getView() != null) r.run(); });
    }

    @Override
    public void onDestroyView() {
        GogDownloadQueue.get().clearListener(this);
        cards.clear();
        establishingSession = false;
        if (web != null) {
            web.stopLoading();
            web.setWebViewClient(new WebViewClient());
            web.destroy();
            web = null;
        }
        super.onDestroyView();
    }
}
