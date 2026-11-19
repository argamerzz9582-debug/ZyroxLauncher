package git.artdeell.mojo.instances;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.InstanceManager.DeleteImpact;
import git.artdeell.mojo.instances.InstanceManager.HealthReport;
import git.artdeell.mojo.instances.InstanceManager.HealthStatus;

/**
 * Single-responsibility instance health auditor, safe non-destructive repair engine, duplicate
 * profile detector, and delete-impact estimator extracted from {@link InstanceManager}.
 */
public final class InstanceHealthInspector {
    private static final String[] STANDARD_SUBFOLDERS =
            new String[]{"saves", "mods", "config", "resourcepacks", "shaderpacks"};

    private interface FilePredicate {
        boolean test(@NonNull File file);
    }

    private InstanceHealthInspector() {
    }

    @NonNull
    public static HealthReport checkHealth(@NonNull Instance instance,
                                           @Nullable File versionsDir,
                                           @Nullable File controlsDir,
                                           int requiredJavaMajor,
                                           int selectedJavaMajor) {
        List<String> findings = new ArrayList<>();
        List<String> repairs = new ArrayList<>();
        HealthStatus status = HealthStatus.HEALTHY;

        if (!Tools.isValidString(instance.versionId)) {
            status = HealthStatus.CRITICAL;
            findings.add("No Minecraft version selected for this instance.");
            repairs.add("Reset version to Latest Fabric Release");
        } else if (versionsDir != null
                && !Instance.isSymbolicVersionId(instance.versionId)) {
            File verJson = new File(
                    new File(versionsDir, instance.versionId), instance.versionId + ".json");
            if (!verJson.isFile()) {
                findings.add("Version files not yet downloaded (" + instance.versionId
                        + ") — will fetch on launch.");
            }
        }

        if (requiredJavaMajor > 0 && selectedJavaMajor > 0
                && selectedJavaMajor < requiredJavaMajor) {
            status = escalate(status, HealthStatus.WARNING);
            findings.add("Java mismatch: requires Java " + requiredJavaMajor
                    + ", selected runtime is Java " + selectedJavaMajor
                    + " (auto-upgrade will trigger on launch).");
            repairs.add("Clear incompatible runtime override");
        }

        if (Tools.isValidString(instance.controlLayout) && controlsDir != null) {
            File ctrlFile = new File(controlsDir, instance.controlLayout);
            if (!ctrlFile.isFile()) {
                status = escalate(status, HealthStatus.WARNING);
                findings.add("Assigned control layout \"" + instance.controlLayout + "\" is missing.");
                repairs.add("Reset missing control layout to default");
            }
        }

        if (instance.consecutiveStartupFailures >= 2) {
            status = escalate(status, HealthStatus.WARNING);
            findings.add("Detected " + instance.consecutiveStartupFailures
                    + " consecutive incomplete startups. Consider switching renderer to Safe Compatibility"
                    + (Tools.isValidString(instance.lastKnownGoodRenderer)
                    ? " or last-known-good (" + instance.lastKnownGoodRenderer + ")." : "."));
            repairs.add("Revert renderer to last-known-good / reset crash counter");
        }

        if (instance.installer != null) {
            status = escalate(status, HealthStatus.WARNING);
            findings.add("Pending mod loader installer has not finished running yet.");
        }

        File gameDir = instance.getGameDirectory();
        int worldCount = 0;
        int enabledMods = 0;
        int disabledMods = 0;
        long bytes = 0L;

        if (gameDir == null || (!gameDir.exists() && !instance.sharedData)) {
            status = escalate(status, HealthStatus.WARNING);
            findings.add("Instance game directory does not exist yet.");
            repairs.add("Recreate standard instance subfolders (saves, mods, config, resourcepacks, shaderpacks)");
        } else if (gameDir.isDirectory()) {
            File saves = new File(gameDir, "saves");
            File[] worldDirs = saves.listFiles(
                    f -> f.isDirectory() && new File(f, "level.dat").isFile());
            if (worldDirs != null) worldCount = worldDirs.length;

            File mods = new File(gameDir, "mods");
            File[] modFiles = mods.listFiles(File::isFile);
            if (modFiles != null) {
                for (File mf : modFiles) {
                    String n = mf.getName().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".jar")) {
                        enabledMods++;
                        if (mf.length() == 0L) {
                            status = escalate(status, HealthStatus.WARNING);
                            findings.add("Empty/corrupt mod JAR found: " + mf.getName());
                        }
                    } else if (n.endsWith(".jar.disabled")) {
                        disabledMods++;
                    }
                }
            }
            if (!instance.sharedData) {
                bytes = InstanceBackupService.computeDirectoryBytes(gameDir, 20_000);
            }
        }

        if (instance.mInstanceRoot != null
                && new File(instance.mInstanceRoot, "mojo_instance.json.corrupt").isFile()) {
            findings.add("Previously recovered from a damaged metadata file (.corrupt backup preserved).");
        }

        if (findings.isEmpty()) {
            findings.add("Instance configuration, storage directories, and runtime settings are healthy.");
        }

        return new HealthReport(status, findings, repairs, worldCount, enabledMods, disabledMods, bytes);
    }

    @NonNull
    public static List<String> repairInstance(@NonNull Instance instance,
                                              @Nullable File controlsDir) throws IOException {
        List<String> applied = new ArrayList<>();
        if (!Tools.isValidString(instance.versionId)) {
            instance.versionId = Instance.VERSION_LATEST_FABRIC;
            applied.add("Set missing version to Latest Fabric Release");
        }
        if (Tools.isValidString(instance.controlLayout) && controlsDir != null) {
            File ctrl = new File(controlsDir, instance.controlLayout);
            if (!ctrl.isFile()) {
                instance.controlLayout = null;
                applied.add("Cleared missing control profile reference");
            }
        }
        if (instance.consecutiveStartupFailures >= 2) {
            if (Tools.isValidString(instance.lastKnownGoodRenderer)) {
                instance.renderer = instance.lastKnownGoodRenderer;
                applied.add("Reverted renderer to last-known-good ("
                        + instance.lastKnownGoodRenderer + ")");
            }
            instance.consecutiveStartupFailures = 0;
            applied.add("Reset consecutive startup failure counter");
        }
        File gameDir = instance.getGameDirectory();
        if (gameDir != null) {
            for (String sub : STANDARD_SUBFOLDERS) {
                File dir = new File(gameDir, sub);
                if (!dir.isDirectory() && dir.mkdirs()) {
                    applied.add("Created missing folder: " + sub + "/");
                }
            }
        }
        instance.sanitize();
        instance.write();
        if (applied.isEmpty()) {
            applied.add("Verified metadata & rewrote clean atomic backup");
        }
        return applied;
    }

    @NonNull
    public static List<String> findDuplicateSummaries(
            @NonNull List<? extends DisplayInstance> instances) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (DisplayInstance inst : instances) {
            String n = inst.name != null ? inst.name.trim().toLowerCase(Locale.ROOT) : "";
            String v = inst.versionId != null ? inst.versionId.trim().toLowerCase(Locale.ROOT) : "";
            String key = n + "|" + v;
            Integer prev = counts.get(key);
            counts.put(key, prev == null ? 1 : prev + 1);
            labels.put(key, (inst.name != null && !inst.name.isEmpty() ? inst.name : inst.versionId)
                    + " [" + inst.versionId + "]");
        }
        List<String> dupes = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                dupes.add(labels.get(e.getKey()) + " (" + e.getValue() + " profiles)");
            }
        }
        return dupes;
    }

    @NonNull
    public static DeleteImpact computeDeleteImpact(@NonNull Instance instance) {
        String label = Tools.isValidString(instance.name)
                ? instance.name
                : (Tools.isValidString(instance.versionId) ? instance.versionId : "Unnamed");
        File root = instance.mInstanceRoot;
        if (root == null || !root.isDirectory()) {
            return new DeleteImpact(label, instance.sharedData, 0, 0, 0, 0L);
        }
        if (instance.sharedData) {
            long metaBytes = InstanceBackupService.computeDirectoryBytes(root, 500);
            return new DeleteImpact(label, true, 0, 0, 0, metaBytes);
        }
        int worlds = countChildren(new File(root, "saves"),
                f -> f.isDirectory() && new File(f, "level.dat").isFile());
        int mods = countChildren(new File(root, "mods"),
                f -> f.isFile() && (f.getName().endsWith(".jar")
                        || f.getName().endsWith(".jar.disabled")));
        int shots = countChildren(new File(root, "screenshots"), File::isFile);
        long total = InstanceBackupService.computeDirectoryBytes(root, 50_000);
        return new DeleteImpact(label, false, worlds, mods, shots, total);
    }

    private static int countChildren(@NonNull File dir, @NonNull FilePredicate predicate) {
        if (!dir.isDirectory()) return 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        int count = 0;
        for (File f : list) {
            if (predicate.test(f)) count++;
        }
        return count;
    }

    @NonNull
    private static HealthStatus escalate(@NonNull HealthStatus current,
                                         @NonNull HealthStatus next) {
        return next.ordinal() > current.ordinal() ? next : current;
    }
}
