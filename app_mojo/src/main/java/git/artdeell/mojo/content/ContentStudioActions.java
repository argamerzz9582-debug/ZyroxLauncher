package git.artdeell.mojo.content;

import android.content.Context;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.performance.GameOptimizer;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.utils.FileUtils;

/**
 * Single-responsibility workflow coordinator extracted from {@code ContentManagerFragment} for
 * atomic config editing, SHA-1 Modrinth mod update checking &amp; batch updates, rollback snapshots,
 * and {@code options.txt} optimizer preset application.
 */
public final class ContentStudioActions {

    /** UI-lifecycle host hook so background callbacks never touch a detached Fragment. */
    public interface UiHost {
        boolean isHostActive();
        @Nullable Context getHostContext();
        void onContentMutated();
    }

    private ContentStudioActions() {
    }

    public static void openConfigEditor(@NonNull UiHost host,
                                        @NonNull Instance instance,
                                        @NonNull InstanceContentManager.ContentItem item) {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                final String content = InstanceContentManager.readConfigFileSafe(item.file);
                Tools.runOnUiThread(() -> {
                    Context ctx = host.isHostActive() ? host.getHostContext() : null;
                    if (ctx == null) return;

                    EditText editor = new EditText(ctx);
                    editor.setText(content);
                    editor.setTextSize(11f);
                    editor.setMinLines(8);
                    editor.setMaxLines(14);
                    editor.setGravity(Gravity.TOP | Gravity.START);
                    int pad = ZyroxStudioUi.dp(ctx, 10);
                    editor.setPadding(pad, pad, pad, pad);

                    new AlertDialog.Builder(ctx)
                            .setTitle("Edit config/" + item.name + " (auto .bak)")
                            .setView(editor)
                            .setPositiveButton("Save", (d, w) -> {
                                final String updated = editor.getText().toString();
                                MojoApplication.sExecutorService.execute(() -> {
                                    try {
                                        File safe = FileUtils.ensureInsideDirectory(
                                                instance.getGameDirectory(), item.file);
                                        InstanceContentManager.writeConfigFileWithBackup(safe, updated);
                                        Tools.runOnUiThread(() -> {
                                            Context c = host.isHostActive() ? host.getHostContext() : null;
                                            if (c != null) {
                                                Toast.makeText(c, "Saved config (backup written to .bak)",
                                                        Toast.LENGTH_SHORT).show();
                                                host.onContentMutated();
                                            }
                                        });
                                    } catch (Exception e) {
                                        postToast(host, e.getMessage());
                                    }
                                });
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            } catch (Exception e) {
                postToast(host, e.getMessage());
            }
        });
    }

    public static void checkModUpdates(@NonNull UiHost host,
                                       @NonNull Instance instance,
                                       @NonNull List<ModMetadataParser.ModInfo> loadedMods) {
        Context initialCtx = host.getHostContext();
        if (initialCtx == null) return;
        if (loadedMods.isEmpty()) {
            Toast.makeText(initialCtx, "No installed mods to check.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(initialCtx, "Checking SHA-1 hashes against Modrinth...",
                Toast.LENGTH_SHORT).show();

        final List<ModMetadataParser.ModInfo> snap = new ArrayList<>(loadedMods);
        final String mcVer = instance.versionId != null ? instance.versionId : "";
        final ModMetadataParser.LoaderFamily instLoader =
                ModMetadataParser.inferLoaderFromVersionId(mcVer);

        MojoApplication.sExecutorService.execute(() -> {
            try {
                final List<String> lines = new ArrayList<>();
                int limit = Math.min(25, snap.size());
                for (int i = 0; i < limit; i++) {
                    ModMetadataParser.ModInfo mod = snap.get(i);
                    if (!mod.file.isFile() || mod.corruptJar) continue;
                    ModMetadataParser.LoaderFamily loader =
                            mod.loader != ModMetadataParser.LoaderFamily.UNKNOWN
                                    ? mod.loader : instLoader;
                    InstanceContentManager.ModUpdateInfo info =
                            InstanceContentManager.checkModrinthUpdate(mod.file, mcVer, loader);
                    if (info.updateAvailable) {
                        lines.add("• " + mod.name + " (" + info.currentVersionNumber
                                + " -> " + info.latestVersionNumber + ")");
                    }
                }
                Tools.runOnUiThread(() -> {
                    Context ctx = host.isHostActive() ? host.getHostContext() : null;
                    if (ctx == null) return;
                    final boolean canRollback = ModrinthContentInstaller.hasRollbackBackup(instance);
                    if (lines.isEmpty()) {
                        if (canRollback) {
                            new AlertDialog.Builder(ctx)
                                    .setTitle("All Mods Up To Date")
                                    .setMessage("All recognized Modrinth mods match the latest verified release.\n\nA backup from your previous batch update is available if you want to roll back.")
                                    .setNeutralButton("Rollback Backup",
                                            (d, w) -> runModRollback(host, instance))
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        } else {
                            Toast.makeText(ctx,
                                    "All recognized Modrinth mods are up to date for this instance.",
                                    Toast.LENGTH_LONG).show();
                        }
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (String line : lines) sb.append(line).append('\n');
                    sb.append("\nPrevious .jar files will be backed up automatically for 1-tap rollback.");
                    AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                            .setTitle(lines.size() + " Mod Update(s) Available")
                            .setMessage(sb.toString().trim())
                            .setPositiveButton("Update All Now",
                                    (d, w) -> runBatchModUpdate(host, instance))
                            .setNegativeButton(android.R.string.cancel, null);
                    if (canRollback) {
                        builder.setNeutralButton("Rollback Last",
                                (d, w) -> runModRollback(host, instance));
                    }
                    builder.show();
                });
            } catch (Exception e) {
                postToast(host, "Update check failed: " + e.getMessage());
            }
        });
    }

    public static void showSnapshotMenu(@NonNull UiHost host, @NonNull Instance instance) {
        Context ctx = host.getHostContext();
        if (ctx == null) return;
        String[] options = {
                "Save Current Mod Enable/Disable Snapshot",
                "Restore Last Saved Snapshot"
        };
        new AlertDialog.Builder(ctx)
                .setTitle("Mod Set Rollback Snapshot")
                .setItems(options, (d, which) -> MojoApplication.sExecutorService.execute(() -> {
                    try {
                        if (which == 0) {
                            InstanceContentManager.saveModStateSnapshot(instance.getGameDirectory());
                            postToast(host, "Mod state snapshot saved!");
                        } else {
                            int changed = InstanceContentManager.restoreLatestModStateSnapshot(
                                    instance.getGameDirectory());
                            Tools.runOnUiThread(() -> {
                                Context c = host.isHostActive() ? host.getHostContext() : null;
                                if (c != null) {
                                    Toast.makeText(c,
                                            "Restored snapshot (" + changed + " mod(s) toggled).",
                                            Toast.LENGTH_LONG).show();
                                    host.onContentMutated();
                                }
                            });
                        }
                    } catch (Exception e) {
                        postToast(host, e.getMessage());
                    }
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static void runBatchModUpdate(@NonNull UiHost host, @NonNull Instance target) {
        Context ctx = host.getHostContext();
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        Toast.makeText(appCtx, "Updating outdated mods with SHA-1 verification…",
                Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                ModrinthContentInstaller.InstallSummary sum =
                        ModrinthContentInstaller.batchUpdateAllMods(target, null);
                Tools.runOnUiThread(() -> {
                    Toast.makeText(appCtx, sum.message, Toast.LENGTH_LONG).show();
                    if (host.isHostActive()) host.onContentMutated();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(appCtx,
                        "Batch update failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    public static void runModRollback(@NonNull UiHost host, @NonNull Instance target) {
        Context ctx = host.getHostContext();
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                int count = ModrinthContentInstaller.rollbackLastModUpdate(target);
                Tools.runOnUiThread(() -> {
                    Toast.makeText(appCtx, "Restored " + count + " previous mod jar(s).",
                            Toast.LENGTH_LONG).show();
                    if (host.isHostActive()) host.onContentMutated();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(appCtx,
                        "Rollback failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    public static void showGameOptimizerDialog(@NonNull Context ctx, @NonNull Instance instance) {
        final File gameDir = instance.getGameDirectory();
        final boolean hasBak = GameOptimizer.hasOptionsBackup(gameDir);
        final String currentMode = LauncherPreferences.PREF_ZYROX_OPTIMIZER_MODE;

        List<String> labels = new ArrayList<>();
        List<String> modes = new ArrayList<>();
        labels.add("Auto Smart (JVM GC + Safe Defaults)");
        modes.add(GameOptimizer.MODE_AUTO);
        labels.add("Competitive Max FPS (90/120 Hz + Fast options.txt)");
        modes.add(GameOptimizer.MODE_MAX_FPS);
        labels.add("Balanced Smooth (Crisp Visuals + High FPS)");
        modes.add(GameOptimizer.MODE_BALANCED);
        labels.add("Shader Ready (Tuned for Iris / OptiFine)");
        modes.add(GameOptimizer.MODE_SHADERS);
        labels.add("Off (Vanilla Unmodified)");
        modes.add(GameOptimizer.MODE_OFF);
        if (hasBak) {
            labels.add("Restore Previous options.txt Backup");
            modes.add("restore_bak");
        }

        new AlertDialog.Builder(ctx)
                .setTitle("Zyrox Game Optimizer • " + GameOptimizer.getModeDisplayName(currentMode))
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    final String chosen = modes.get(which);
                    if ("restore_bak".equals(chosen)) {
                        boolean ok = GameOptimizer.restoreOptionsBackup(gameDir);
                        Toast.makeText(ctx,
                                ok ? "Restored previous options.txt backup" : "No backup found",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    LauncherPreferences.PREF_ZYROX_OPTIMIZER_MODE = chosen;
                    if (LauncherPreferences.DEFAULT_PREF != null) {
                        LauncherPreferences.DEFAULT_PREF.edit()
                                .putString(GameOptimizer.PREF_KEY_MODE, chosen)
                                .apply();
                    }
                    if (!GameOptimizer.MODE_OFF.equals(chosen)
                            && !GameOptimizer.MODE_AUTO.equals(chosen)) {
                        final int totalRam = Tools.getTotalDeviceMemory(ctx);
                        final Context appCtx = ctx.getApplicationContext();
                        MojoApplication.sExecutorService.execute(() -> {
                            boolean applied = GameOptimizer.applyOptionsPreset(
                                    gameDir, chosen, totalRam, 120, true);
                            Tools.runOnUiThread(() -> Toast.makeText(appCtx,
                                    applied
                                            ? ("Applied " + GameOptimizer.getModeDisplayName(chosen)
                                                    + " (JVM + options.txt tuned, backup saved)")
                                            : ("Set JVM mode: " + GameOptimizer.getModeDisplayName(chosen)),
                                    Toast.LENGTH_LONG).show());
                        });
                    } else {
                        Toast.makeText(ctx,
                                "Optimizer mode: " + GameOptimizer.getModeDisplayName(chosen),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void postToast(@NonNull UiHost host, @Nullable String msg) {
        if (msg == null) return;
        Tools.runOnUiThread(() -> {
            Context c = host.isHostActive() ? host.getHostContext() : null;
            if (c != null) Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
        });
    }
}
