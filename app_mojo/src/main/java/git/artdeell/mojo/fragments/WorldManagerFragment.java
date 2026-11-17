package git.artdeell.mojo.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import git.artdeell.mojo.worlds.WorldManager;

/**
 * Zyrox 2.0 World Manager & Verified Backup Studio.
 */
public class WorldManagerFragment extends Fragment {
    public static final String TAG = "WORLD_MANAGER_FRAGMENT";

    private Instance mInstance;
    private TextView mHeaderSummary;
    private LinearLayout mListContainer;
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

        Button btnImport = ZyroxStudioUi.createSecondaryButton(ctx, "Import ZIP", true);
        btnImport.setOnClickListener(v -> mImportWorldLauncher.launch(new String[]{"application/zip", "application/octet-stream"}));
        bar.addView(btnImport, ZyroxStudioUi.spacedParam(ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

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
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                renderWorlds(worlds, backupCount);
            });
        });
    }

    private void renderWorlds(@NonNull List<WorldManager.WorldEntry> worlds, int backupCount) {
        Context ctx = requireContext();
        mListContainer.removeAllViews();
        String sharedWarn = mInstance.sharedData ? " • Shared directory" : " • Isolated instance";
        mHeaderSummary.setText(instanceDisplayLabel() + " • " + worlds.size() + " world(s) • "
                + backupCount + " backup(s)" + sharedWarn);

        if (worlds.isEmpty()) {
            TextView empty = ZyroxStudioUi.createSubtitle(ctx,
                    "No singleplayer worlds found yet in this instance's saves/ folder.\n"
                            + "Create a world in Minecraft, import a world .zip, or restore from Backups.");
            empty.setPadding(ZyroxStudioUi.dp(ctx, 12), ZyroxStudioUi.dp(ctx, 24),
                    ZyroxStudioUi.dp(ctx, 12), ZyroxStudioUi.dp(ctx, 24));
            mListContainer.addView(empty);
            return;
        }

        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (WorldManager.WorldEntry w : worlds) {
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(ctx);
            int iconPx = ZyroxStudioUi.dp(ctx, 42);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setBackgroundResource(R.drawable.bg_zyrox_badge);
            if (w.iconFile != null && w.iconFile.isFile()) {
                Bitmap bmp = BitmapFactory.decodeFile(w.iconFile.getAbsolutePath());
                if (bmp != null) icon.setImageBitmap(bmp);
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

    private void createBackup(@NonNull WorldManager.WorldEntry world) {
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
