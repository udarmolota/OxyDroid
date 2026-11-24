package com.oxydroid.fragments;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.oxydroid.R;
import com.oxydroid.game.GameInstance;
import com.oxydroid.game.GameInstanceManager;
import com.oxydroid.game.OniMods;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Mods of one Oxygen Not Included instance (the screen idea from RimDroid, the game's own rules
 * underneath: game.OniMods). One list in the game's load order: a switch, up / down and delete per
 * mod. The button at the top installs mods from one or more zips, right here (as on SyxDroid's Mods
 * screen); the Import screen does the same and also exports them. A mod the game
 * has not listed yet (just imported) is shown below the others and cannot be switched on or moved
 * until the game has started once with it. Everything here must be done while the game is not
 * running: the game writes its mod list itself.
 */
public class OniModsFragment extends Fragment {

    private Spinner spInstance;
    private LinearLayout list;
    private TextView tvEmpty;
    private final List<GameInstance> instances = new ArrayList<>();
    private View btnInstall;

    private static final String[] ZIP_TYPES = {"application/zip", "application/x-zip-compressed", "application/octet-stream"};
    private final ActivityResultLauncher<String[]> pickZips =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::install);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_oni_mods, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_oni_mods_instance);
        list = v.findViewById(R.id.ll_oni_mods_list);
        tvEmpty = v.findViewById(R.id.tv_oni_mods_empty);

        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        // Position 0 is a prompt, never an instance: with several instances a pre-selected one would
        // put a mod into the wrong game. With exactly one there is nothing to choose, and it is
        // selected. The same rule as the Import and cloud saves screens.
        List<String> names = new ArrayList<>();
        names.add(getString(instances.isEmpty() ? R.string.no_instances : R.string.choose_instance_prompt));
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(adapter);
        if (instances.size() == 1) spInstance.setSelection(1);
        spInstance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        btnInstall = v.findViewById(R.id.btn_oni_mods_import);
        btnInstall.setOnClickListener(x -> {
            if (current() == null) {
                Toast.makeText(requireContext(), instances.isEmpty() ? R.string.no_instances
                        : R.string.choose_instance_prompt, Toast.LENGTH_SHORT).show();
                return;
            }
            pickZips.launch(ZIP_TYPES);
        });
    }

    /** The picked zips into the selected instance, off the UI thread; then what was installed. */
    private void install(@Nullable List<Uri> uris) {
        final GameInstance gi = current();
        if (uris == null || uris.isEmpty() || gi == null || !isAdded()) return;
        final Context app = requireContext().getApplicationContext();
        final File userDir = gi.getUserDataDir();
        btnInstall.setEnabled(false);
        Toast.makeText(app, R.string.transfer_mods_installing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<String> done = new ArrayList<>(), failed = new ArrayList<>(), odd = new ArrayList<>();
            for (Uri uri : uris) {
                String file = displayName(app, uri);
                if (file == null) file = String.valueOf(uri.getLastPathSegment());
                String base = file.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                        ? file.substring(0, file.length() - 4) : file;
                try {
                    done.addAll(OniMods.importUri(app, uri, userDir, base, odd));
                } catch (Exception e) {
                    failed.add(file + ": " + e.getMessage());
                }
            }
            final StringBuilder msg = new StringBuilder(app.getString(R.string.transfer_mods_imported, done.size()));
            for (String n : done) msg.append("\n• ").append(n);
            if (!failed.isEmpty()) {
                msg.append("\n\n").append(app.getString(R.string.mods_copy_failed));
                for (String f : failed) msg.append("\n• ").append(f);
            }
            if (!odd.isEmpty()) {
                msg.append("\n\n").append(app.getString(R.string.oni_mods_unrecognized));
                for (String n : odd) msg.append("\n• ").append(n);
            }
            // New mods are switched on by the game's next start (OniMods.markForEnable).
            OniMods.markForEnable(userDir, done);
            if (!done.isEmpty()) msg.append("\n\n").append(app.getString(R.string.oni_mods_imported_next));
            android.app.Activity act = getActivity();
            if (act == null) return;
            act.runOnUiThread(() -> {
                if (!isAdded()) return;
                btnInstall.setEnabled(true);
                refresh();
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(gi.getName())
                        .setMessage(msg.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "mods-install").start();
    }

    @Nullable
    private static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (RuntimeException ignored) {
            // no name: the caller falls back to the address
        }
        return null;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();   // back from the file picker, or from the game (which may have listed new mods)
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance == null ? -1 : spInstance.getSelectedItemPosition() - 1;   // 0 is the prompt
        return i >= 0 && i < instances.size() ? instances.get(i) : null;
    }

    private void refresh() {
        if (!isAdded() || list == null) return;
        list.removeAllViews();
        GameInstance gi = current();
        if (gi == null) {   // no instance chosen yet: an empty screen, not "no mods"
            tvEmpty.setVisibility(View.GONE);
            return;
        }
        final File userDir = gi.getUserDataDir();
        final int gameBuild = gameBuild(gi);
        List<OniMods.Mod> mods = OniMods.list(userDir);
        tvEmpty.setVisibility(mods.isEmpty() ? View.VISIBLE : View.GONE);
        int listed = 0;
        for (OniMods.Mod m : mods) if (m.listed) listed++;
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (OniMods.Mod m : mods) {
            View row = inflater.inflate(R.layout.item_oni_mod, list, false);
            ((TextView) row.findViewById(R.id.tv_oni_mod_name)).setText(m.title);
            ((TextView) row.findViewById(R.id.tv_oni_mod_sub)).setText(describe(m, gameBuild));

            Switch sw = row.findViewById(R.id.sw_oni_mod);
            sw.setChecked(m.enabled);
            sw.setEnabled(m.listed && m.installed);
            sw.setOnCheckedChangeListener((b, on) -> change(() -> OniMods.setEnabled(userDir, m, on)));

            View up = row.findViewById(R.id.btn_oni_mod_up), down = row.findViewById(R.id.btn_oni_mod_down);
            boolean canUp = m.listed && m.index > 0, canDown = m.listed && m.index < listed - 1;
            up.setVisibility(m.listed ? View.VISIBLE : View.INVISIBLE);
            down.setVisibility(m.listed ? View.VISIBLE : View.INVISIBLE);
            up.setEnabled(canUp);
            down.setEnabled(canDown);
            up.setAlpha(canUp ? 1f : 0.3f);
            down.setAlpha(canDown ? 1f : 0.3f);
            up.setOnClickListener(x -> change(() -> OniMods.move(userDir, m.index, m.index - 1)));
            down.setOnClickListener(x -> change(() -> OniMods.move(userDir, m.index, m.index + 1)));

            row.findViewById(R.id.btn_oni_mod_delete).setOnClickListener(x ->
                    new MaterialAlertDialogBuilder(requireContext())
                            .setMessage(getString(R.string.oni_mods_delete_confirm, m.title))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.oni_mods_delete, (d, w) -> change(() -> OniMods.delete(userDir, m)))
                            .show());
            list.addView(row);
        }
    }

    /**
     * The line under a mod's title: the oldest game build it runs on (green when the instance's game
     * is that build or newer, red when it is older), its version, and what stands in its way. Where
     * the mod comes from (Local/Steam/Dev) is not shown: mods installed here are all Local.
     */
    private CharSequence describe(OniMods.Mod m, int gameBuild) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (m.minBuild > 0) {
            sb.append(getString(R.string.oni_mods_build, m.minBuild));
            if (gameBuild > 0)
                sb.setSpan(new android.text.style.ForegroundColorSpan(gameBuild >= m.minBuild ? 0xFF43A047 : 0xFFE53935),
                        0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (m.version != null && !m.version.isEmpty()) sep(sb).append(m.version);
        if (!m.installed) sep(sb).append(getString(R.string.oni_mods_missing));
        else if (!m.listed) sep(sb).append(getString(R.string.oni_mods_new));
        if (m.crashes > 0) sep(sb).append(getString(R.string.oni_mods_crashes, m.crashes));
        return sb;
    }

    private static android.text.SpannableStringBuilder sep(android.text.SpannableStringBuilder sb) {
        return sb.length() == 0 ? sb : sb.append(" · ");
    }

    /** The instance's game build (764025 of "U60-764025"), from the game's own log; -1 before its first start. */
    private static int gameBuild(GameInstance gi) {
        String v = gi == null ? null : com.oxydroid.ReportInfo.shortGameVersion(gi);
        if (v == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-(\\d+)").matcher(v);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private interface Change {
        void run() throws java.io.IOException;
    }

    /** One change to the mod list, then the list again from the files. */
    private void change(Change c) {
        try {
            c.run();
        } catch (java.io.IOException e) {
            Toast.makeText(requireContext(), getString(R.string.oni_mods_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
        refresh();
    }
}
