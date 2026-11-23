package com.oxydroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.oxydroid.R;
import com.oxydroid.game.GameInstance;
import com.oxydroid.game.GameInstanceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Multiplayer for one instance, from the drawer: the instance is picked at the top (by hand when
 * there are several), Crossplay and Steam achievements switched on or off for it. With either on,
 * every native launch signs in to Steam first: crossplay hands the game a ticket for its PlayFab
 * login, achievements sends the new ones to the account (SteamTicketLogin, SteamStatsSync). Both need
 * the native engine; for another engine the switches are greyed out with a note.
 */
public class MultiplayerFragment extends Fragment {
    private Spinner spInstance;
    private Switch swCrossplay, swAchievements;
    private View tvNativeOnly;
    private android.widget.TextView tvAchievements;
    private android.widget.Button btnMyAchievements;
    private final List<GameInstance> instances = new ArrayList<>();
    private boolean binding;
    private View btnSync;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_multiplayer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_mp_instance);
        swCrossplay = v.findViewById(R.id.sw_crossplay);
        swAchievements = v.findViewById(R.id.sw_steam_achievements);
        tvNativeOnly = v.findViewById(R.id.tv_mp_native_only);
        tvAchievements = v.findViewById(R.id.tv_achievements_summary);
        btnMyAchievements = v.findViewById(R.id.btn_my_achievements);
        btnMyAchievements.setOnClickListener(x -> showAchievements());
        btnSync = v.findViewById(R.id.btn_sync_achievements);
        btnSync.setOnClickListener(x -> askSignIn());

        GameInstanceManager.requireSingleton().reload();
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        List<String> names = new ArrayList<>();
        // Position 0 is a prompt: with several instances a pre-selected one would switch these on for
        // the wrong game without the spinner ever being touched. A single one is selected up front.
        names.add(getString(instances.isEmpty() ? R.string.no_instances : R.string.choose_instance_prompt));
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(a);
        if (instances.size() == 1) spInstance.setSelection(1);
        spInstance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        swCrossplay.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            GameInstance gi = current();
            if (gi != null) gi.settings().setCrossplay(on);
        });
        swAchievements.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            GameInstance gi = current();
            if (gi != null) gi.settings().setSteamAchievements(on);
        });
        refresh();
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance == null ? -1 : spInstance.getSelectedItemPosition();
        return (i >= 1 && i <= instances.size()) ? instances.get(i - 1) : null;
    }

    private void refresh() {
        GameInstance gi = current();
        boolean nativeEngine = gi != null && com.oxydroid.game.NativeEngine.isAvailable() && gi.settings().isNativeEngine();
        binding = true;
        swCrossplay.setChecked(gi != null && gi.settings().isCrossplay());
        swCrossplay.setEnabled(nativeEngine);
        swAchievements.setChecked(gi != null && gi.settings().isSteamAchievements());
        swAchievements.setEnabled(nativeEngine);
        binding = false;
        tvNativeOnly.setVisibility(View.GONE);   // a note about crossplay, which is hidden
        fillAchievements(gi);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (spInstance != null) fillAchievements(current());   // a game may have marked some meanwhile
    }

    // ---- My achievements: the last sign-in's list plus what waits on the phone ----

    private void fillAchievements(@Nullable GameInstance gi) {
        if (gi == null) {
            tvAchievements.setText("");
            btnMyAchievements.setVisibility(View.GONE);
            return;
        }
        com.oxydroid.SteamAchievementList l =
                com.oxydroid.SteamAchievementList.load(com.oxydroid.game.NativeEngine.steamStatsFile(gi));
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (l.synced > 0) {
            String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(new java.util.Date(l.synced * 1000));
            sb.append("\uD83C\uDFC6  ").append(getString(R.string.steam_achievements_summary, l.unlocked, l.entries.size()));
            if (l.pending > 0) sb.append('\n').append(getString(R.string.steam_achievements_pending, l.pending));
            int start = sb.length();
            sb.append('\n').append(getString(R.string.steam_achievements_updated, when));
            // The update time a size smaller and dimmer than the count (14sp text, 12sp here).
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(12, true), start, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan(tvAchievements.getCurrentTextColor() & 0xB3FFFFFF),
                    start, sb.length(), 0);
        } else {
            sb.append(getString(R.string.steam_achievements_never));
        }
        tvAchievements.setText(sb);
        btnMyAchievements.setVisibility(l.entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- Sync: sign in to Steam, exchange the achievements, sign out. No game launch. ----

    private static boolean gameRunning(android.content.Context context) {
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> procs = am != null ? am.getRunningAppProcesses() : null;
        if (procs == null) return false;
        String name = context.getPackageName() + ":unity";
        for (android.app.ActivityManager.RunningAppProcessInfo p : procs)
            if (name.equals(p.processName)) return true;
        return false;
    }

    /** Steam login and password (the login filled in from last time); the password is not kept. */
    private void askSignIn() {
        GameInstance gi = current();
        if (gi == null) {
            android.widget.Toast.makeText(requireContext(), instances.isEmpty() ? R.string.no_instances
                    : R.string.choose_instance_prompt, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The game's Steam stand-in owns the achievements file while the game runs.
        if (gameRunning(requireContext())) {
            android.widget.Toast.makeText(requireContext(), R.string.steam_achievements_close_game,
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        View form = getLayoutInflater().inflate(R.layout.dialog_crossplay_login, null);
        com.google.android.material.textfield.TextInputEditText etUser = form.findViewById(R.id.et_crossplay_user);
        com.google.android.material.textfield.TextInputEditText etPass = form.findViewById(R.id.et_crossplay_pass);
        String lastLogin = com.oxydroid.LauncherPreferences.requireSingleton().getSteamLoginName();
        etUser.setText(lastLogin);
        if (!lastLogin.isEmpty()) etPass.requestFocus();
        androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_OxyDroid_Dialog_Buttons)
                        .setTitle(R.string.crossplay_signin_title)
                        .setView(form)
                        .setPositiveButton(R.string.steam_achievements_sync_go, null)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            String user = etUser.getText() == null ? "" : etUser.getText().toString().trim();
            String pass = etPass.getText() == null ? "" : etPass.getText().toString();
            if (user.isEmpty() || pass.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), R.string.cloud_saves_need_login, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            com.oxydroid.LauncherPreferences.requireSingleton().setSteamLoginName(user);
            dialog.dismiss();
            sync(gi, user, pass);
        }));
        dialog.show();
    }

    /** The sign-in and the exchange (SteamTicketLogin without a ticket) behind a waiting dialog. */
    private void sync(GameInstance gi, String user, String pass) {
        final android.content.Context app = requireContext().getApplicationContext();
        final androidx.appcompat.app.AlertDialog waiting =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_OxyDroid_Dialog_Buttons)
                        .setTitle(R.string.crossplay_signin_title)
                        .setMessage(R.string.crossplay_step_connect)
                        .setCancelable(false)
                        .create();
        final com.oxydroid.SteamTicketLogin[] login = new com.oxydroid.SteamTicketLogin[1];
        login[0] = new com.oxydroid.SteamTicketLogin(user, pass, null,
                com.oxydroid.game.NativeEngine.steamStatsFile(gi), new com.oxydroid.SteamTicketLogin.Listener() {
            @Override
            public java.util.concurrent.CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
                java.util.concurrent.CompletableFuture<String> fut = new java.util.concurrent.CompletableFuture<>();
                ui.post(() -> {
                    if (!isAdded()) { fut.complete(""); return; }
                    android.widget.EditText et = new android.widget.EditText(requireContext());
                    et.setHint(email != null ? getString(R.string.cloud_saves_code_email, email)
                                             : getString(R.string.cloud_saves_code));
                    et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_OxyDroid_Dialog_Buttons)
                            .setTitle(prevWrong ? R.string.cloud_saves_code_wrong : R.string.cloud_saves_code_title)
                            .setView(et)
                            .setCancelable(false)
                            .setPositiveButton(android.R.string.ok, (d, w) -> fut.complete(et.getText().toString().trim()))
                            .show();
                });
                return fut;
            }

            @Override
            public void onProgress(String step) {
                int text = "approve".equals(step) ? R.string.crossplay_step_approve
                        : "achievements".equals(step) ? R.string.crossplay_step_achievements : R.string.crossplay_step_connect;
                ui.post(() -> waiting.setMessage(app.getString(text)));
            }

            @Override
            public void onReady(com.oxydroid.SteamTicketLogin.Result result) {
                ui.post(() -> done(waiting, result.achievementsError != null
                        ? app.getString(R.string.steam_achievements_sync_failed, result.achievementsError)
                        : app.getString(R.string.steam_achievements_sync_done, Math.max(0, result.achievementsSent))));
            }

            @Override
            public void onFailed(String message) {
                ui.post(() -> done(waiting, app.getString(R.string.steam_achievements_sync_failed, message)));
            }
        });
        waiting.setButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE, getString(android.R.string.cancel),
                (d, w) -> login[0].cancel());
        waiting.show();
        btnSync.setEnabled(false);
        // The player leaves for Steam Mobile to approve: the keep-alive notification holds this process.
        com.oxydroid.DownloadKeepAliveService.start(app, app.getString(R.string.crossplay_keepalive));
        new Thread(() -> {
            try {
                login[0].run();
            } finally {
                com.oxydroid.DownloadKeepAliveService.stop(app);
            }
        }, "AchievementsSync").start();
    }

    private void done(androidx.appcompat.app.AlertDialog waiting, String message) {
        try {
            if (waiting.isShowing()) waiting.dismiss();
        } catch (RuntimeException ignored) {
            // the screen is gone
        }
        if (!isAdded()) return;
        btnSync.setEnabled(true);
        fillAchievements(current());
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.card_steam_achievements)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Unlocked first (newest first), then the ones waiting to be sent, then the locked ones. */
    private void showAchievements() {
        GameInstance gi = current();
        if (gi == null) return;
        com.oxydroid.SteamAchievementList l =
                com.oxydroid.SteamAchievementList.load(com.oxydroid.game.NativeEngine.steamStatsFile(gi));
        List<com.oxydroid.SteamAchievementList.Entry> sorted = new ArrayList<>(l.entries);
        java.util.Collections.sort(sorted, (a, b) -> {
            int ra = a.unlocked ? 0 : a.pending ? 1 : 2, rb = b.unlocked ? 0 : b.pending ? 1 : 2;
            if (ra != rb) return ra - rb;
            return Long.compare(b.time, a.time);
        });
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        int dim = tvAchievements.getCurrentTextColor() & 0xB3FFFFFF;
        CharSequence[] rows = new CharSequence[sorted.size()];
        for (int i = 0; i < rows.length; i++) {
            com.oxydroid.SteamAchievementList.Entry e = sorted.get(i);
            String note = e.unlocked ? (e.time > 0 ? df.format(new java.util.Date(e.time * 1000)) : null)
                    : e.pending ? getString(R.string.steam_achievements_waiting) : null;
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(
                    (e.unlocked ? "✅ " : e.pending ? "⏳ " : "○ ") + e.name);
            if (note != null) {
                // The date or "waiting" on its own line, smaller and dimmer, as on the summary.
                int start = sb.length();
                sb.append("\n     ").append(note);
                sb.setSpan(new android.text.style.AbsoluteSizeSpan(12, true), start, sb.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(dim), start, sb.length(), 0);
            }
            rows[i] = sb;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.steam_achievements_title) + "  " + l.unlocked + " / " + l.entries.size())
                .setItems(rows, null)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
