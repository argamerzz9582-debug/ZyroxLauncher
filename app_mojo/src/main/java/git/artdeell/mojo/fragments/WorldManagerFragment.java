package git.artdeell.mojo.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.worlds.LevelDatNbtEditor;
import git.artdeell.mojo.worlds.WorldManager;

/**
 * Zyrox 2.0 World Manager & Verified Backup Studio.
 */
public class WorldManagerFragment extends Fragment {
    public static final String TAG = "WORLD_MANAGER_FRAGMENT";

    private Instance mInstance;
    private TextView mHeaderSummary;
    private LinearLayout mListContainer;
    private final java.util.ArrayList<Bitmap> mActiveIcons = new java.util.ArrayList<>();
    @Nullable private WorldManager.WorldEntry mPendingExportWorld;

    private final ActivityResultLauncher<String[]> mImportWorldLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onImportWorldSelected);

    private final ActivityResultLauncher<String> mExportWorldLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), this::onExportWorldDestination);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = ZyroxStudioUi.createGlassScreenRoot(ctx);
        LinearLayout panel = root.findViewById(android.R.id.content);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        mHeaderSummary = ZyroxStudioUi.createSubtitle(ctx, "Scanning instance saves...");
        bar.addView(mHeaderSummary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnShots = ZyroxStudioUi.createPrimaryButton(ctx, "Screenshots");
        btnShots.setOnClickListener(v -> Tools.swapFragment(
                requireActivity(), ScreenshotGalleryFragment.class, ScreenshotGalleryFragment.TAG, null));
        bar.addView(btnShots, ZyroxStudioUi.spacedParam(ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnImport = ZyroxStudioUi.createSecondaryButton(ctx, "Import ZIP", true);
        btnImport.setOnClickListener(v -> mImportWorldLauncher.launch(new String[]{"*/*"}));
        bar.addView(btnImport, ZyroxStudioUi.spacedParam(ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnLan = ZyroxStudioUi.createSecondaryButton(ctx, "Scan LAN", true);
        btnLan.setOnClickListener(v -> scanAndJoinLocalLanWorld());
        bar.addView(btnLan, ZyroxStudioUi.spacedParam(ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnAiFriend = ZyroxStudioUi.createSecondaryButton(ctx, "AI Friend", true);
        btnAiFriend.setOnClickListener(v -> Tools.swapFragment(
                requireActivity(),
                AiFriendFragment.class,
                AiFriendFragment.TAG,
                AiFriendFragment.createPresetArgs(
                        git.artdeell.mojo.ai.AiFriendModels.TargetConnectionMode.LAN_WORLD,
                        null,
                        null,
                        AiFriendFragment.TAB_WORLD_BRIDGE)));
        bar.addView(btnAiFriend, ZyroxStudioUi.spacedParam(ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnBackups = ZyroxStudioUi.createSecondaryButton(ctx, "Backups", true);
        btnBackups.setOnClickListener(v -> showBackupsDialog());
        bar.addView(btnBackups, ZyroxStudioUi.spacedParam(ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnRefresh = ZyroxStudioUi.createSecondaryButton(ctx, "Refresh", true);
        btnRefresh.setOnClickListener(v -> refreshWorlds());
        bar.addView(btnRefresh, ZyroxStudioUi.spacedParam(ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        panel.addView(bar);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scLp.topMargin = ZyroxStudioUi.dp(ctx, 8);
        mListContainer = new LinearLayout(ctx);
        mListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mListContainer);
        panel.addView(scroll, scLp);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mInstance = Instances.loadSelectedInstance();
        refreshWorlds();
    }

    @NonNull
    private String instanceBackupKey() {
        if (mInstance == null) return "default";
        if (mInstance.sharedData) return "shared";
        File root = mInstance.getInstanceRoot();
        return root != null ? root.getName() : (Tools.isValidString(mInstance.name) ? mInstance.name : "default");
    }

    @NonNull
    private String instanceDisplayLabel() {
        if (mInstance == null) return "Instance";
        return Tools.isValidString(mInstance.name) ? mInstance.name
                : (Tools.isValidString(mInstance.versionId) ? mInstance.versionId : "Instance");
    }

    private void scanAndJoinLocalLanWorld() {
        final Context ctx = requireContext();
        Toast.makeText(ctx, "Scanning local Wi-Fi (224.0.2.60:4445) for LAN worlds…",
                Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            final List<git.artdeell.mojo.servers.ServerPinger.LanWorldEntry> found =
                    git.artdeell.mojo.servers.ServerPinger.scanLocalLanWorlds(2200);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (found.isEmpty()) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("No Local LAN Worlds Found")
                            .setMessage("Open a Minecraft world on another device on the same Wi-Fi network and click Pause → Open to LAN, then scan again.")
                            .setPositiveButton("Scan Again", (d, w) -> scanAndJoinLocalLanWorld())
                            .setNegativeButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                String[] items = new String[found.size()];
                for (int i = 0; i < found.size(); i++) {
                    git.artdeell.mojo.servers.ServerPinger.LanWorldEntry w = found.get(i);
                    items[i] = w.motd + "  (" + w.address + ")";
                }
                new AlertDialog.Builder(requireContext())
                        .setTitle("Join Local LAN World")
                        .setItems(items, (d, which) -> {
                            git.artdeell.mojo.servers.ServerPinger.LanWorldEntry picked = found.get(which);
                            git.artdeell.mojo.servers.ServerListManager.setPendingQuickConnect(picked.address);
                            Toast.makeText(requireContext(),
                                    "Joining LAN world \"" + picked.motd + "\"…",
                                    Toast.LENGTH_SHORT).show();
                            Tools.backToMainMenu(requireActivity());
                            git.artdeell.mojo.extra.ExtraCore.setValue(
                                    git.artdeell.mojo.extra.ExtraConstants.LAUNCH_GAME, true);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private void refreshWorlds() {
        if (mInstance == null) {
            mHeaderSummary.setText(R.string.no_instance);
            return;
        }
        mHeaderSummary.setText("Scanning worlds in " + instanceDisplayLabel() + "...");
        final File gameDir = mInstance.getGameDirectory();
        final File backupDir = WorldManager.getWorldBackupsDirectory(new File(Tools.DIR_GAME_HOME), instanceBackupKey());

        MojoApplication.sExecutorService.execute(() -> {
            List<WorldManager.WorldEntry> worlds = WorldManager.listWorlds(gameDir);
            int backupCount = WorldManager.listBackups(backupDir).size();
            final java.util.ArrayList<Bitmap> decodedIcons = new java.util.ArrayList<>(worlds.size());
            for (WorldManager.WorldEntry w : worlds) {
                decodedIcons.add(decodeBoundedWorldIcon(w.iconFile, 96));
            }
            Tools.runOnUiThread(() -> {
                if (!isAdded()) {
                    for (Bitmap b : decodedIcons) {
                        if (b != null && !b.isRecycled()) b.recycle();
                    }
                    return;
                }
                renderWorlds(worlds, decodedIcons, backupCount);
            });
        });
    }

    @Override
    public void onDestroyView() {
        recycleWorldIcons();
        super.onDestroyView();
    }

    private void recycleWorldIcons() {
        if (mListContainer != null) {
            mListContainer.removeAllViews();
        }
        for (Bitmap bmp : mActiveIcons) {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
        mActiveIcons.clear();
    }

    @Nullable
    private static Bitmap decodeBoundedWorldIcon(@Nullable File iconFile, int maxPx) {
        if (iconFile == null || !iconFile.isFile()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(iconFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while ((bounds.outWidth / sample) > maxPx * 2 || (bounds.outHeight / sample) > maxPx * 2) {
                sample <<= 1;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(iconFile.getAbsolutePath(), opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void renderWorlds(@NonNull List<WorldManager.WorldEntry> worlds,
                              @NonNull List<Bitmap> icons,
                              int backupCount) {
        Context ctx = requireContext();
        recycleWorldIcons();
        mActiveIcons.addAll(icons);
        String sharedWarn = mInstance.sharedData ? " • Shared directory" : " • Isolated instance";
        mHeaderSummary.setText(instanceDisplayLabel() + " • " + worlds.size() + " world(s) • "
                + backupCount + " backup(s)" + sharedWarn);

        if (worlds.isEmpty()) {
            LinearLayout guideCard = ZyroxStudioUi.createItemCard(ctx);
            int pad = ZyroxStudioUi.dp(ctx, 14);
            guideCard.setPadding(pad, pad, pad, pad);
            guideCard.addView(ZyroxStudioUi.createTitle(ctx, "Start Your Next Adventure"));
            TextView empty = ZyroxStudioUi.createSubtitle(ctx,
                    "No singleplayer worlds saved in this instance yet. Scan your Wi-Fi to jump straight into a friend's LAN world, import a world .zip, or launch Minecraft to generate a new world.");
            empty.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, ZyroxStudioUi.dp(ctx, 12));
            guideCard.addView(empty);

            LinearLayout actions = new LinearLayout(ctx);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);

            Button btnLan = ZyroxStudioUi.createPrimaryButton(ctx, "Scan Local LAN Worlds");
            btnLan.setOnClickListener(v -> scanAndJoinLocalLanWorld());
            actions.addView(btnLan, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 36)));

            Button btnImport = ZyroxStudioUi.createSecondaryButton(ctx, "Import World (.zip)", false);
            btnImport.setOnClickListener(v -> mImportWorldLauncher.launch(new String[]{"*/*"}));
            actions.addView(btnImport, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 36), ZyroxStudioUi.dp(ctx, 8)));

            Button btnPlay = ZyroxStudioUi.createSecondaryButton(ctx, "Launch Minecraft", false);
            btnPlay.setOnClickListener(v -> {
                Tools.backToMainMenu(requireActivity());
                git.artdeell.mojo.extra.ExtraCore.setValue(
                        git.artdeell.mojo.extra.ExtraConstants.LAUNCH_GAME, true);
            });
            actions.addView(btnPlay, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 36), ZyroxStudioUi.dp(ctx, 8)));

            guideCard.addView(actions);
            mListContainer.addView(guideCard);
            return;
        }

        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (int idx = 0; idx < worlds.size(); idx++) {
            WorldManager.WorldEntry w = worlds.get(idx);
            Bitmap bmp = idx < icons.size() ? icons.get(idx) : null;
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(ctx);
            int iconPx = ZyroxStudioUi.dp(ctx, 42);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setBackgroundResource(R.drawable.bg_zyrox_badge);
            if (bmp != null && !bmp.isRecycled()) {
                icon.setImageBitmap(bmp);
            } else {
                icon.setImageResource(R.drawable.ic_px_instances);
                int pad = ZyroxStudioUi.dp(ctx, 9);
                icon.setPadding(pad, pad, pad, pad);
            }
            row.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));

            LinearLayout meta = new LinearLayout(ctx);
            meta.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mLp.setMarginStart(ZyroxStudioUi.dp(ctx, 10));
            mLp.setMarginEnd(ZyroxStudioUi.dp(ctx, 8));

            String statusTag = w.integrity == WorldManager.Integrity.VALID ? ""
                    : ("  [" + w.integrity.name() + "]");
            meta.addView(ZyroxStudioUi.createTitle(ctx, w.displayName + statusTag));

            String lastPlayed = w.lastModifiedMs > 0 ? df.format(new Date(w.lastModifiedMs)) : "Unknown";
            String detail = "Folder: " + w.folderName
                    + " • " + ZyroxStudioUi.formatBytes(w.sizeBytes)
                    + " • Last saved: " + lastPlayed
                    + (w.datapackCount > 0 ? (" • " + w.datapackCount + " datapack(s)") : "")
                    + (w.integrityNote.isEmpty() ? "" : ("\n" + w.integrityNote));
            meta.addView(ZyroxStudioUi.createSubtitle(ctx, detail));
            row.addView(meta, mLp);

            Button btnNbt = ZyroxStudioUi.createSecondaryButton(ctx, "NBT", true);
            btnNbt.setEnabled(w.integrity != WorldManager.Integrity.CORRUPT);
            btnNbt.setOnClickListener(v -> openLevelDatEditor(w));
            row.addView(btnNbt, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));

            Button btnBackup = ZyroxStudioUi.createSecondaryButton(ctx, "Backup", true);
            btnBackup.setOnClickListener(v -> createBackup(w));
            row.addView(btnBackup, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));

            Button btnExport = ZyroxStudioUi.createSecondaryButton(ctx, "Export", true);
            btnExport.setOnClickListener(v -> {
                mPendingExportWorld = w;
                mExportWorldLauncher.launch(w.folderName + "-backup.zip");
            });
            row.addView(btnExport, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

            Button btnDel = ZyroxStudioUi.createDangerButton(ctx, "Delete", true);
            btnDel.setOnClickListener(v -> confirmDeleteWorld(w));
            row.addView(btnDel, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

            card.addView(row);
            mListContainer.addView(card);
        }
    }

    private void openLevelDatEditor(@NonNull WorldManager.WorldEntry world) {
        if (!isAdded()) return;
        MojoApplication.sExecutorService.execute(() -> {
            try {
                LevelDatNbtEditor.LevelDatSummary summary = WorldManager.readWorldLevelDat(world.directory);
                Tools.runOnUiThread(() -> {
                    if (isAdded()) showLevelDatDialog(world, summary);
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(),
                                "Cannot read level.dat: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showLevelDatDialog(@NonNull WorldManager.WorldEntry world,
                                    @NonNull LevelDatNbtEditor.LevelDatSummary cur) {
        Context ctx = requireContext();
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = ZyroxStudioUi.dp(ctx, 18);
        form.setPadding(pad, ZyroxStudioUi.dp(ctx, 10), pad, ZyroxStudioUi.dp(ctx, 6));

        form.addView(ZyroxStudioUi.createSubtitle(ctx, "World Name (LevelName)"));
        final EditText nameInput = new EditText(ctx);
        nameInput.setSingleLine(true);
        nameInput.setText(cur.levelName.isEmpty() ? world.displayName : cur.levelName);
        form.addView(nameInput);

        form.addView(ZyroxStudioUi.createSubtitle(ctx, "Gamemode (GameType + Player)"));
        final Spinner gmSpinner = new Spinner(ctx);
        String[] gmLabels = {"0 • Survival", "1 • Creative", "2 • Adventure", "3 • Spectator"};
        gmSpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, gmLabels));
        gmSpinner.setSelection(Math.max(0, Math.min(3, cur.gameType)));
        form.addView(gmSpinner);

        form.addView(ZyroxStudioUi.createSubtitle(ctx, "Difficulty"));
        final Spinner diffSpinner = new Spinner(ctx);
        String[] diffLabels = {"0 • Peaceful", "1 • Easy", "2 • Normal", "3 • Hard"};
        diffSpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, diffLabels));
        diffSpinner.setSelection(Math.max(0, Math.min(3, cur.difficulty)));
        form.addView(diffSpinner);

        form.addView(ZyroxStudioUi.createSubtitle(ctx, "World Seed (numeric or text)"));
        LinearLayout seedRow = new LinearLayout(ctx);
        seedRow.setOrientation(LinearLayout.HORIZONTAL);
        seedRow.setGravity(Gravity.CENTER_VERTICAL);
        final String origSeedStr = cur.hasSeed ? String.valueOf(cur.seed) : "";
        final EditText seedInput = new EditText(ctx);
        seedInput.setSingleLine(true);
        seedInput.setHint("World seed");
        seedInput.setText(origSeedStr);
        seedRow.addView(seedInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnCopySeed = ZyroxStudioUi.createSecondaryButton(ctx, "Copy", true);
        btnCopySeed.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            String val = seedInput.getText() != null ? seedInput.getText().toString().trim() : "";
            if (cm != null && !val.isEmpty()) {
                cm.setPrimaryClip(ClipData.newPlainText("Minecraft Seed", val));
                Toast.makeText(ctx, "Copied seed: " + val, Toast.LENGTH_SHORT).show();
            }
        });
        seedRow.addView(btnCopySeed, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 32), ZyroxStudioUi.dp(ctx, 6)));
        form.addView(seedRow);

        final CheckBox chkCheats = new CheckBox(ctx);
        chkCheats.setText("Allow Cheats / Commands (allowCommands)");
        chkCheats.setChecked(cur.allowCommands);
        form.addView(chkCheats);

        final CheckBox chkHardcore = new CheckBox(ctx);
        chkHardcore.setText("Hardcore Mode (single life)");
        chkHardcore.setChecked(cur.hardcore);
        form.addView(chkHardcore);

        form.addView(ZyroxStudioUi.createSubtitle(ctx,
                "Original level.dat is automatically backed up to level.dat_old before saving."));
        scroll.addView(form);

        new AlertDialog.Builder(ctx)
                .setTitle("Quick level.dat Editor • " + world.displayName)
                .setView(scroll)
                .setPositiveButton("Save level.dat", (d, w) -> {
                    final LevelDatNbtEditor.EditSpec spec = new LevelDatNbtEditor.EditSpec()
                            .setLevelName(nameInput.getText() != null ? nameInput.getText().toString() : null)
                            .setGameType(gmSpinner.getSelectedItemPosition())
                            .setDifficulty(diffSpinner.getSelectedItemPosition())
                            .setAllowCommands(chkCheats.isChecked())
                            .setHardcore(chkHardcore.isChecked());
                    String rawSeed = seedInput.getText() != null ? seedInput.getText().toString().trim() : "";
                    if (!rawSeed.isEmpty() && !rawSeed.equals(origSeedStr)) {
                        spec.setSeed(LevelDatNbtEditor.parseMinecraftSeed(rawSeed));
                    }
                    MojoApplication.sExecutorService.execute(() -> {
                        try {
                            LevelDatNbtEditor.LevelDatSummary updated =
                                    WorldManager.editWorldLevelDat(world.directory, spec);
                            Tools.runOnUiThread(() -> {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(),
                                        "Saved level.dat (" + updated.getGameModeLabel()
                                                + ", Cheats " + (updated.allowCommands ? "ON" : "OFF") + ")",
                                        Toast.LENGTH_LONG).show();
                                refreshWorlds();
                            });
                        } catch (Exception e) {
                            Tools.runOnUiThread(() -> {
                                if (isAdded()) {
                                    Toast.makeText(requireContext(),
                                            "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                })
                .setNeutralButton("ZIP Backup First", (d, w) -> createBackup(world))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createBackup(@NonNull WorldManager.WorldEntry world) {
        if (mInstance == null || !isAdded()) return;
        Toast.makeText(requireContext(), "Creating verified SHA-256 backup of \"" + world.displayName + "\"...",
                Toast.LENGTH_SHORT).show();
        final File backupDir = WorldManager.getWorldBackupsDirectory(new File(Tools.DIR_GAME_HOME), instanceBackupKey());
        final String instName = instanceDisplayLabel();
        final String mcVer = mInstance.versionId;
        final int retention = LauncherPreferences.PREF_ZYROX_BACKUP_RETENTION;
        MojoApplication.sExecutorService.execute(() -> {
            try {
                File zip = WorldManager.backupWorld(world.directory, backupDir, instName, mcVer, retention);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(),
                            "Backup verified: " + zip.getName() + " (" + ZyroxStudioUi.formatBytes(zip.length()) + ")",
                            Toast.LENGTH_LONG).show();
                    refreshWorlds();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showBackupsDialog() {
        if (mInstance == null) return;
        File backupDir = WorldManager.getWorldBackupsDirectory(new File(Tools.DIR_GAME_HOME), instanceBackupKey());
        List<WorldManager.BackupEntry> backups = WorldManager.listBackups(backupDir);
        if (backups.isEmpty()) {
            Toast.makeText(requireContext(), "No world backups saved yet for " + instanceDisplayLabel(), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[backups.size()];
        for (int i = 0; i < backups.size(); i++) {
            WorldManager.BackupEntry b = backups.get(i);
            labels[i] = b.worldName + " — " + b.zipFile.getName()
                    + " (" + ZyroxStudioUi.formatBytes(b.sizeBytes)
                    + (b.hasSha256 ? " • SHA-256" : "") + ")";
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Restore Verified World Backup")
                .setItems(labels, (d, which) -> restoreBackup(backups.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void restoreBackup(@NonNull WorldManager.BackupEntry backup) {
        final File savesDir = WorldManager.getSavesDirectory(mInstance.getGameDirectory());
        MojoApplication.sExecutorService.execute(() -> {
            try {
                File restored = WorldManager.restoreOrImportWorldZip(
                        backup.zipFile, savesDir, backup.worldName, WorldManager.RestoreMode.RENAME_IF_EXISTS);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(),
                            "World safely restored to saves/" + restored.getName(), Toast.LENGTH_LONG).show();
                    refreshWorlds();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Restore failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void confirmDeleteWorld(@NonNull WorldManager.WorldEntry world) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete World \"" + world.displayName + "\"?")
                .setMessage("Folder: saves/" + world.folderName + "\nSize: "
                        + ZyroxStudioUi.formatBytes(world.sizeBytes)
                        + "\n\nThis permanently deletes this singleplayer world. Create a backup first if you want to keep a copy.")
                .setPositiveButton("Delete Permanently", (d, w) -> {
                    final File savesDir = WorldManager.getSavesDirectory(mInstance.getGameDirectory());
                    MojoApplication.sExecutorService.execute(() -> {
                        try {
                            WorldManager.deleteWorld(savesDir, world.directory);
                            Tools.runOnUiThread(() -> {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(), "Deleted world " + world.displayName, Toast.LENGTH_SHORT).show();
                                refreshWorlds();
                            });
                        } catch (Exception e) {
                            Tools.runOnUiThread(() -> {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(), "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                })
                .setNeutralButton("Backup First", (d, w) -> createBackup(world))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onImportWorldSelected(@Nullable Uri uri) {
        if (uri == null || mInstance == null) return;
        final File savesDir = WorldManager.getSavesDirectory(mInstance.getGameDirectory());
        final Context appCtx = requireContext().getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            File tmpZip = new File(Tools.DIR_CACHE, "world-import-" + System.currentTimeMillis() + ".zip");
            try (InputStream in = appCtx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Could not read selected archive");
                ArchiveSecurity.copyBoundedToFile(
                        in, tmpZip, ArchiveSecurity.DEFAULT_MAX_SINGLE_FILE_BYTES,
                        ArchiveSecurity.DEFAULT_MAX_TOTAL_BYTES);
                File imported = WorldManager.restoreOrImportWorldZip(
                        tmpZip, savesDir, null, WorldManager.RestoreMode.RENAME_IF_EXISTS);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(),
                            "Imported world to saves/" + imported.getName(), Toast.LENGTH_LONG).show();
                    refreshWorlds();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmpZip.delete();
            }
        });
    }

    private void onExportWorldDestination(@Nullable Uri uri) {
        WorldManager.WorldEntry target = mPendingExportWorld;
        mPendingExportWorld = null;
        if (uri == null || target == null) return;
        final Context appCtx = requireContext().getApplicationContext();
        final String instName = instanceDisplayLabel();
        final String mcVer = mInstance != null ? mInstance.versionId : null;
        MojoApplication.sExecutorService.execute(() -> {
            try (OutputStream out = appCtx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Could not open destination file");
                WorldManager.exportWorldToStream(target.directory, out, instName, mcVer);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(),
                            "Exported world \"" + target.displayName + "\" successfully", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
