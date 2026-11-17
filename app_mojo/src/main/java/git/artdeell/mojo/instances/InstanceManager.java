package git.artdeell.mojo.instances;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.security.AppTrustVerifier;
import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.JSONUtils;
import git.artdeell.mojo.utils.ZipUtils;
import git.artdeell.mojo.utils.NetworkPolicyHelper;

/**
 * Zyrox 2.0 Instance Studio engine: safe isolated cloning, config JSON import/export, instance
 * archive backup & verified restore, health diagnostics, repair, duplicate detection, and delete
 * impact calculation.
 */
public final class InstanceManager {
    public static final String EXPORT_FORMAT = "zyrox-instance-config-v2";
    public static final String BACKUP_META_ENTRY = "zyrox_instance_backup.json";

    private InstanceManager() {
    }

    public enum HealthStatus {
        HEALTHY,
        WARNING,
        CRITICAL
    }

    public static final class HealthReport {
        @NonNull public final HealthStatus status;
        @NonNull public final List<String> findings;
        @NonNull public final List<String> repairableActions;
        public final int worldCount;
        public final int enabledModCount;
        public final int disabledModCount;
        public final long directoryBytes;

        public HealthReport(@NonNull HealthStatus status,
                            @NonNull List<String> findings,
                            @NonNull List<String> repairableActions,
                            int worldCount,
                            int enabledModCount,
                            int disabledModCount,
                            long directoryBytes) {
            this.status = status;
            this.findings = Collections.unmodifiableList(findings);
            this.repairableActions = Collections.unmodifiableList(repairableActions);
            this.worldCount = worldCount;
            this.enabledModCount = enabledModCount;
            this.disabledModCount = disabledModCount;
            this.directoryBytes = directoryBytes;
        }
    }

    public static final class DeleteImpact {
        @NonNull public final String displayName;
        public final boolean usesSharedDirectory;
        public final int worldCount;
        public final int modCount;
        public final int screenshotCount;
        public final long totalBytesToDelete;

        public DeleteImpact(@NonNull String displayName,
                            boolean usesSharedDirectory,
                            int worldCount,
                            int modCount,
                            int screenshotCount,
                            long totalBytesToDelete) {
            this.displayName = displayName;
            this.usesSharedDirectory = usesSharedDirectory;
            this.worldCount = worldCount;
            this.modCount = modCount;
            this.screenshotCount = screenshotCount;
            this.totalBytesToDelete = totalBytesToDelete;
        }

        @NonNull
        public String buildConfirmationMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append("Permanently delete instance \"").append(displayName).append("\"?\n\n");
            if (usesSharedDirectory) {
                sb.append("• Storage Mode: Shared directory (shared worlds/mods will NOT be deleted)\n");
                sb.append("• Profile folder removed: ")
                        .append(NetworkPolicyHelper.formatBytes(totalBytesToDelete)).append('\n');
            } else {
                sb.append("• Worlds deleted: ").append(worldCount).append('\n');
                sb.append("• Mods deleted: ").append(modCount).append('\n');
                sb.append("• Screenshots deleted: ").append(screenshotCount).append('\n');
                sb.append("• Total data freed: ")
                        .append(NetworkPolicyHelper.formatBytes(totalBytesToDelete)).append('\n');
            }
            sb.append("\nThis action cannot be undone unless you create a backup first.");
            return sb.toString();
        }
    }

    /**
     * Creates a fully isolated clone of {@code source} inside {@code instancesDir}. Never sets
     * {@code sharedData = true} on the cloned instance so edits/mods/configs never mutate the source.
     */
    @NonNull
    public static Instance cloneInstance(@NonNull Instance source,
                                         @NonNull File instancesDir,
                                         @Nullable String customCloneName,
                                         boolean copyConfigsAndMods,
                                         boolean copyWorlds) throws IOException {
        FileUtils.ensureDirectory(instancesDir);
        String baseName = Tools.isValidString(customCloneName)
                ? customCloneName.trim()
                : ((Tools.isValidString(source.name) ? source.name.trim() : source.versionId) + " (Copy)");

        File sourceGameDir = source.getGameDirectory();
        if ((copyConfigsAndMods || copyWorlds) && sourceGameDir != null && sourceGameDir.isDirectory()) {
            long estimate = estimateCloneBytes(sourceGameDir, copyConfigsAndMods, copyWorlds);
            if (!NetworkPolicyHelper.hasEnoughDiskSpace(instancesDir, estimate)) {
                throw new IOException("Not enough free disk space to clone instance (requires ~"
                        + NetworkPolicyHelper.formatBytes(estimate) + ")");
            }
        }

        File newRoot = Instances.findNewInstanceRoot(instancesDir, baseName);
        FileUtils.ensureDirectory(newRoot);

        boolean succeeded = false;
        try {
            // Copy instance icon if present
            if (source.mInstanceRoot != null) {
                File srcIcon = new File(source.mInstanceRoot, "icon.webp");
                if (srcIcon.isFile()) {
                    copyFile(srcIcon, new File(newRoot, "icon.webp"));
                }
            }

            if (sourceGameDir != null && sourceGameDir.isDirectory()
                    && !sourceGameDir.getCanonicalFile().equals(newRoot.getCanonicalFile())) {
                if (copyConfigsAndMods) {
                    copyOptionalFile(sourceGameDir, newRoot, "options.txt");
                    copyOptionalFile(sourceGameDir, newRoot, "servers.dat");
                    copyOptionalDir(sourceGameDir, newRoot, "config");
                    copyOptionalDir(sourceGameDir, newRoot, "mods");
                    copyOptionalDir(sourceGameDir, newRoot, "resourcepacks");
                    copyOptionalDir(sourceGameDir, newRoot, "shaderpacks");
                }
                if (copyWorlds) {
                    copyOptionalDir(sourceGameDir, newRoot, "saves");
                }
            }

            Instance clone = new Instance();
            clone.mInstanceRoot = newRoot;
            clone.schemaVersion = Instance.CURRENT_SCHEMA_VERSION;
            clone.name = baseName;
            clone.versionId = source.versionId;
            clone.icon = source.icon;
            clone.renderer = source.renderer;
            clone.jvmArgs = source.jvmArgs;
            clone.argsMode = source.argsMode;
            clone.selectedRuntime = source.selectedRuntime;
            clone.controlLayout = source.controlLayout;
            // Critical guarantee: cloned instances NEVER share mutable storage with the source!
            clone.sharedData = false;
            clone.ramAllocationMb = source.ramAllocationMb;
            clone.notes = source.notes;
            clone.groupTag = source.groupTag;
            clone.createdAtMs = System.currentTimeMillis();
            clone.lastPlayedMs = 0L;
            clone.launchCount = 0;
            clone.lastKnownGoodRenderer = source.lastKnownGoodRenderer;
            clone.consecutiveStartupFailures = 0;
            clone.sanitize();
            clone.write();
            succeeded = true;
            return clone;
        } finally {
            if (!succeeded && newRoot.exists()) {
                org.apache.commons.io.FileUtils.deleteQuietly(newRoot);
            }
        }
    }

    /**
     * Exports a portable, credential-free JSON configuration describing {@code instance}.
     */
    @NonNull
    public static String exportConfigJson(@NonNull Instance instance) {
        JsonObject obj = new JsonObject();
        obj.addProperty("format", EXPORT_FORMAT);
        obj.addProperty("schemaVersion", Instance.CURRENT_SCHEMA_VERSION);
        obj.addProperty("exportedAtMs", System.currentTimeMillis());
        if (instance.name != null) obj.addProperty("name", instance.name);
        if (instance.versionId != null) obj.addProperty("versionId", instance.versionId);
        if (instance.icon != null) obj.addProperty("icon", instance.icon);
        if (instance.renderer != null) obj.addProperty("renderer", instance.renderer);
        if (instance.selectedRuntime != null) obj.addProperty("selectedRuntime", instance.selectedRuntime);
        if (instance.jvmArgs != null) obj.addProperty("jvmArgs", instance.jvmArgs);
        obj.addProperty("argsMode", instance.argsMode);
        if (instance.controlLayout != null) obj.addProperty("controlLayout", instance.controlLayout);
        obj.addProperty("ramAllocationMb", instance.ramAllocationMb);
        if (instance.notes != null) obj.addProperty("notes", instance.notes);
        if (instance.groupTag != null) obj.addProperty("groupTag", instance.groupTag);
        return Tools.GLOBAL_GSON.toJson(obj);
    }

    /**
     * Validates and imports a Zyrox instance configuration JSON into a fresh isolated instance.
     */
    @NonNull
    public static Instance importConfigJson(@NonNull String jsonText, @NonNull File instancesDir)
            throws IOException {
        if (jsonText.length() > ArchiveSecurity.DEFAULT_MAX_METADATA_BYTES) {
            throw new IOException("Instance configuration file exceeds maximum safe size");
        }
        JsonObject obj;
        try {
            obj = new JsonParser().parse(jsonText).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Invalid instance configuration JSON", e);
        }
        String versionId = optString(obj, "versionId");
        if (versionId == null || versionId.trim().isEmpty()) {
            throw new IOException("Instance configuration is missing required versionId");
        }
        if (versionId.contains("..") || versionId.contains("/") || versionId.contains("\\")) {
            throw new IOException("Rejected unsafe versionId in instance configuration");
        }
        String name = optString(obj, "name");
        if (name == null || name.trim().isEmpty()) {
            name = versionId + " (Imported)";
        }

        FileUtils.ensureDirectory(instancesDir);
        File newRoot = Instances.findNewInstanceRoot(instancesDir, name);
        FileUtils.ensureDirectory(newRoot);

        Instance inst = new Instance();
        inst.mInstanceRoot = newRoot;
        inst.schemaVersion = Instance.CURRENT_SCHEMA_VERSION;
        inst.name = name.trim();
        inst.versionId = versionId.trim();
        inst.icon = optString(obj, "icon");
        inst.renderer = optString(obj, "renderer");
        inst.selectedRuntime = optString(obj, "selectedRuntime");
        inst.jvmArgs = sanitizeImportedJvmArgs(optString(obj, "jvmArgs"));
        inst.argsMode = optInt(obj, "argsMode", 0);
        String ctrl = optString(obj, "controlLayout");
        if (ctrl != null && !ctrl.contains("..") && !ctrl.contains("/") && !ctrl.contains("\\")) {
            inst.controlLayout = ctrl;
        }
        inst.ramAllocationMb = optInt(obj, "ramAllocationMb", 0);
        inst.notes = optString(obj, "notes");
        inst.groupTag = optString(obj, "groupTag");
        inst.sharedData = false;
        inst.createdAtMs = System.currentTimeMillis();
        inst.sanitize();
        inst.write();
        return inst;
    }

    /** Blocks untrusted `-javaagent:` or native library override flags in imported profile configs. */
    @Nullable
    private static String sanitizeImportedJvmArgs(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String[] parts = raw.trim().split("\\s+");
        StringBuilder clean = new StringBuilder();
        for (String token : parts) {
            String low = token.toLowerCase(Locale.ROOT);
            if (low.startsWith("-javaagent:")
                    || low.startsWith("-agentlib:")
                    || low.startsWith("-agentpath:")
                    || low.startsWith("-xx:onoutofmemoryerror")
                    || low.startsWith("-xx:onerror")
                    || low.startsWith("-djava.library.path=")) {
                continue;
            }
            if (clean.length() > 0) clean.append(' ');
            clean.append(token);
        }
        return clean.length() == 0 ? null : clean.toString();
    }

    /**
     * Inspects an instance's metadata, folders, mods, saves, control layout, and startup stability.
     */
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
            repairs.add("Reset version to Latest Release");
        } else if (versionsDir != null
                && !Instance.VERSION_LATEST_RELEASE.equals(instance.versionId)
                && !Instance.VERSION_LATEST_SNAPSHOT.equals(instance.versionId)) {
            File verJson = new File(new File(versionsDir, instance.versionId), instance.versionId + ".json");
            if (!verJson.isFile()) {
                findings.add("Version files not yet downloaded (" + instance.versionId + ") — will fetch on launch.");
            }
        }

        if (requiredJavaMajor > 0 && selectedJavaMajor > 0 && selectedJavaMajor < requiredJavaMajor) {
            status = escalate(status, HealthStatus.WARNING);
            findings.add("Java mismatch: requires Java " + requiredJavaMajor
                    + ", selected runtime is Java " + selectedJavaMajor + " (auto-upgrade will trigger on launch).");
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
            File[] worldDirs = saves.listFiles(f -> f.isDirectory() && new File(f, "level.dat").isFile());
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
                bytes = computeDirectoryBytes(gameDir, 20_000);
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

    /**
     * Performs safe, non-destructive repairs on an instance (never deletes worlds, saves, or mods).
     *
     * @return list of repairs performed
     */
    @NonNull
    public static List<String> repairInstance(@NonNull Instance instance, @Nullable File controlsDir)
            throws IOException {
        List<String> applied = new ArrayList<>();
        if (!Tools.isValidString(instance.versionId)) {
            instance.versionId = Instance.VERSION_LATEST_RELEASE;
            applied.add("Set missing version to Latest Release");
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
                applied.add("Reverted renderer to last-known-good (" + instance.lastKnownGoodRenderer + ")");
            }
            instance.consecutiveStartupFailures = 0;
            applied.add("Reset consecutive startup failure counter");
        }
        File gameDir = instance.getGameDirectory();
        if (gameDir != null) {
            for (String sub : new String[]{"saves", "mods", "config", "resourcepacks", "shaderpacks"}) {
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

    /**
     * Detects duplicate instances sharing the same normalized name and version ID.
     */
    @NonNull
    public static List<String> findDuplicateSummaries(@NonNull List<? extends DisplayInstance> instances) {
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

    /**
     * Computes exact files and disk size affected before deleting an instance.
     */
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
            long metaBytes = computeDirectoryBytes(root, 500);
            return new DeleteImpact(label, true, 0, 0, 0, metaBytes);
        }
        int worlds = countChildren(new File(root, "saves"), f -> f.isDirectory() && new File(f, "level.dat").isFile());
        int mods = countChildren(new File(root, "mods"), f -> f.isFile()
                && (f.getName().endsWith(".jar") || f.getName().endsWith(".jar.disabled")));
        int shots = countChildren(new File(root, "screenshots"), File::isFile);
        long total = computeDirectoryBytes(root, 50_000);
        return new DeleteImpact(label, false, worlds, mods, shots, total);
    }

    /**
     * Creates a verified ZIP backup of an instance (metadata, icon, configs, mods, and optionally worlds)
     * inside {@code backupsDir}, enforcing free-space check and backup retention only after success.
     */
    @NonNull
    public static File createInstanceBackup(@NonNull Instance instance,
                                            @NonNull File backupsDir,
                                            boolean includeWorlds,
                                            int maxBackupsToKeep) throws IOException {
        if (instance.mInstanceRoot == null || !instance.mInstanceRoot.isDirectory()) {
            throw new IOException("Instance directory does not exist");
        }
        FileUtils.ensureDirectory(backupsDir);
        long estimated = computeDirectoryBytes(instance.mInstanceRoot, 50_000);
        if (!NetworkPolicyHelper.hasEnoughDiskSpace(backupsDir, Math.max(4L * 1024 * 1024, estimated / 2))) {
            throw new IOException("Insufficient free disk space to create instance backup");
        }

        String safeLabel = FileUtils.escapeFileName(
                Tools.isValidString(instance.name) ? instance.name : instance.versionId);
        if (safeLabel.isEmpty()) safeLabel = "instance";
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File finalZip = new File(backupsDir, safeLabel + "-" + stamp + ".zip");
        File tempZip = new File(backupsDir, finalZip.getName() + ".tmp");

        int[] fileCounter = new int[]{0};
        long[] byteCounter = new long[]{0L};
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tempZip)))) {
            zipDirectoryFiltered(instance.mInstanceRoot, "", zos, includeWorlds, fileCounter, byteCounter);

            JsonObject meta = new JsonObject();
            meta.addProperty("format", "zyrox-instance-backup-v2");
            meta.addProperty("name", instance.name != null ? instance.name : "");
            meta.addProperty("versionId", instance.versionId != null ? instance.versionId : "");
            meta.addProperty("includesWorlds", includeWorlds);
            meta.addProperty("fileCount", fileCounter[0]);
            meta.addProperty("uncompressedBytes", byteCounter[0]);
            meta.addProperty("createdAtMs", System.currentTimeMillis());
            byte[] metaBytes = Tools.GLOBAL_GSON.toJson(meta).getBytes(StandardCharsets.UTF_8);
            ZipEntry metaEntry = new ZipEntry(BACKUP_META_ENTRY);
            zos.putNextEntry(metaEntry);
            zos.write(metaBytes);
            zos.closeEntry();
            zos.finish();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tempZip.delete();
            throw e;
        }

        if (!tempZip.renameTo(finalZip)) {
            //noinspection ResultOfMethodCallIgnored
            tempZip.delete();
            throw new IOException("Failed to finalize instance backup archive");
        }

        // Write companion SHA-256 verification file
        String sha256 = computeSha256Hex(finalZip);
        File shaFile = new File(backupsDir, finalZip.getName() + ".sha256");
        try (FileOutputStream out = new FileOutputStream(shaFile, false)) {
            out.write((sha256 + "  " + finalZip.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        }

        // Apply retention policy only AFTER the new backup & checksum are verified on disk
        pruneOldBackups(backupsDir, safeLabel + "-", Math.max(1, maxBackupsToKeep));
        return finalZip;
    }

    /**
     * Verifies and restores an instance backup ZIP into a brand-new isolated instance directory
     * (never silently overwrites an existing instance).
     */
    @NonNull
    public static Instance restoreInstanceBackup(@NonNull File backupZip,
                                                 @NonNull File instancesDir) throws IOException {
        if (!backupZip.isFile()) {
            throw new IOException("Backup archive not found");
        }
        // Verify companion SHA-256 checksum if present
        File shaFile = new File(backupZip.getParentFile(), backupZip.getName() + ".sha256");
        if (shaFile.isFile()) {
            String expected = readFirstToken(shaFile);
            String actual = computeSha256Hex(backupZip);
            if (!expected.isEmpty() && !expected.equalsIgnoreCase(actual)) {
                throw new IOException("Backup SHA-256 verification failed — archive may be corrupted");
            }
        }
        if (!NetworkPolicyHelper.hasEnoughDiskSpace(instancesDir, backupZip.length() * 2L)) {
            throw new IOException("Insufficient free disk space to restore backup");
        }

        FileUtils.ensureDirectory(instancesDir);
        File newRoot = Instances.findNewInstanceRoot(instancesDir, "restored");
        FileUtils.ensureDirectory(newRoot);
        boolean ok = false;
        try (ZipFile zf = new ZipFile(backupZip)) {
            if (zf.getEntry("mojo_instance.json") == null) {
                throw new IOException("Archive is not a valid Zyrox instance backup (missing mojo_instance.json)");
            }
            ZipUtils.zipExtract(zf, "", newRoot);
            //noinspection ResultOfMethodCallIgnored
            new File(newRoot, BACKUP_META_ENTRY).delete();
            Instance restored = Instances.readWithRecovery(newRoot, Instance.class);
            if (restored == null) {
                throw new IOException("Restored instance metadata could not be parsed");
            }
            restored.sharedData = false;
            restored.sanitize();
            if (Tools.isValidString(restored.name) && !restored.name.endsWith(" (Restored)")) {
                restored.name = restored.name + " (Restored)";
            }
            restored.write();
            ok = true;
            return restored;
        } finally {
            if (!ok && newRoot.exists()) {
                org.apache.commons.io.FileUtils.deleteQuietly(newRoot);
            }
        }
    }

    /**
     * Records a launch attempt timestamp, increments launch count and pending startup failure
     * counter (cleared once {@link #recordLaunchWindowReady(Instance, String)} fires).
     */
    public static void recordLaunchAttempt(@Nullable Instance instance) {
        if (instance == null) return;
        instance.lastPlayedMs = System.currentTimeMillis();
        instance.launchCount = Math.max(0, instance.launchCount) + 1;
        instance.consecutiveStartupFailures = Math.max(0, instance.consecutiveStartupFailures) + 1;
        try {
            instance.write();
        } catch (Throwable ignored) {
            // Non-fatal telemetry metadata (may be unrooted in unit tests).
        }
    }

    /**
     * Marks a launch as successfully reaching an active game window, resetting failure counters
     * and recording {@code rendererTag} as the instance's last-known-good renderer.
     */
    public static void recordLaunchWindowReady(@Nullable Instance instance,
                                               @Nullable String rendererTag) {
        if (instance == null) return;
        instance.consecutiveStartupFailures = 0;
        if (rendererTag != null && !rendererTag.trim().isEmpty()) {
            instance.lastKnownGoodRenderer = rendererTag.trim();
        }
        try {
            instance.write();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Non-destructively restores {@code instance.lastKnownGoodRenderer} after repeated renderer
     * startup crashes. Returns {@code true} if a fallback renderer was applied.
     */
    public static boolean restoreLastKnownGoodRenderer(@Nullable Instance instance) {
        if (instance == null || instance.lastKnownGoodRenderer == null) return false;
        String safe = instance.lastKnownGoodRenderer.trim();
        if (safe.isEmpty()) return false;
        instance.renderer = safe;
        instance.consecutiveStartupFailures = 0;
        try {
            instance.write();
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static void pruneOldBackups(@NonNull File dir, @NonNull String prefix, int keepCount) {
        if (keepCount < 1 || !dir.isDirectory()) return;
        File[] zips = dir.listFiles(f -> f.isFile() && f.getName().startsWith(prefix) && f.getName().endsWith(".zip"));
        if (zips == null || zips.length <= keepCount) return;
        Arrays.sort(zips, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keepCount; i < zips.length; i++) {
            File old = zips[i];
            File sha = new File(dir, old.getName() + ".sha256");
            //noinspection ResultOfMethodCallIgnored
            old.delete();
            //noinspection ResultOfMethodCallIgnored
            sha.delete();
        }
    }

    @NonNull
    public static String computeSha256Hex(@NonNull File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16 * 1024];
            try (FileInputStream in = new FileInputStream(file)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return AppTrustVerifier.formatSha256Hex(md.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    public static long computeDirectoryBytes(@Nullable File dir, int maxFiles) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return Math.max(0L, dir.length());
        long[] sum = new long[]{0L};
        int[] visited = new int[]{0};
        accumulateBytes(dir, sum, visited, Math.max(1, maxFiles));
        return sum[0];
    }

    private static void accumulateBytes(@NonNull File node, long[] sum, int[] visited, int maxFiles) {
        if (visited[0] >= maxFiles) return;
        File[] children = node.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (visited[0]++ >= maxFiles) return;
            if (child.isFile()) {
                sum[0] += Math.max(0L, child.length());
            } else if (child.isDirectory()) {
                accumulateBytes(child, sum, visited, maxFiles);
            }
        }
    }

    private static void zipDirectoryFiltered(@NonNull File current,
                                             @NonNull String relPrefix,
                                             @NonNull ZipOutputStream zos,
                                             boolean includeWorlds,
                                             int[] fileCount,
                                             long[] byteCount) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        byte[] buf = new byte[16 * 1024];
        for (File child : children) {
            String name = child.getName();
            if (name.endsWith(".tmp") || BACKUP_META_ENTRY.equals(name)) continue;
            if (!includeWorlds && relPrefix.isEmpty() && "saves".equals(name)) continue;
            if (relPrefix.isEmpty() && ("logs".equals(name) || "crash-reports".equals(name))) continue;
            String entryPath = relPrefix.isEmpty() ? name : (relPrefix + "/" + name);
            if (child.isDirectory()) {
                zipDirectoryFiltered(child, entryPath, zos, includeWorlds, fileCount, byteCount);
            } else if (child.isFile()) {
                ZipEntry entry = new ZipEntry(entryPath);
                entry.setTime(child.lastModified());
                zos.putNextEntry(entry);
                try (FileInputStream in = new FileInputStream(child)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        zos.write(buf, 0, n);
                        byteCount[0] += n;
                    }
                }
                zos.closeEntry();
                fileCount[0]++;
            }
        }
    }

    private static long estimateCloneBytes(@NonNull File gameDir, boolean configsMods, boolean worlds) {
        long sum = 0L;
        if (configsMods) {
            sum += computeDirectoryBytes(new File(gameDir, "config"), 5000);
            sum += computeDirectoryBytes(new File(gameDir, "mods"), 5000);
            sum += computeDirectoryBytes(new File(gameDir, "resourcepacks"), 2000);
            sum += computeDirectoryBytes(new File(gameDir, "shaderpacks"), 2000);
        }
        if (worlds) {
            sum += computeDirectoryBytes(new File(gameDir, "saves"), 20_000);
        }
        return sum;
    }

    private static void copyOptionalFile(@NonNull File srcRoot, @NonNull File dstRoot, @NonNull String name)
            throws IOException {
        File src = new File(srcRoot, name);
        if (src.isFile()) {
            copyFile(src, new File(dstRoot, name));
        }
    }

    private static void copyOptionalDir(@NonNull File srcRoot, @NonNull File dstRoot, @NonNull String name)
            throws IOException {
        File src = new File(srcRoot, name);
        if (src.isDirectory()) {
            copyDirectoryRecursive(src, new File(dstRoot, name));
        }
    }

    private static void copyDirectoryRecursive(@NonNull File src, @NonNull File dst) throws IOException {
        FileUtils.ensureDirectory(dst);
        File[] children = src.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = new File(dst, child.getName());
            if (child.isDirectory()) {
                copyDirectoryRecursive(child, target);
            } else if (child.isFile()) {
                copyFile(child, target);
            }
        }
    }

    private static void copyFile(@NonNull File src, @NonNull File dst) throws IOException {
        FileUtils.ensureParentDirectory(dst);
        byte[] buf = new byte[16 * 1024];
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst, false)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private interface FilePredicate {
        boolean test(File f);
    }

    private static int countChildren(@NonNull File dir, @NonNull FilePredicate predicate) {
        if (!dir.isDirectory()) return 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        int c = 0;
        for (File f : list) {
            if (predicate.test(f)) c++;
        }
        return c;
    }

    @NonNull
    private static String readFirstToken(@NonNull File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = ArchiveSecurity.readBytesBounded(in, 4096);
            String s = new String(data, StandardCharsets.UTF_8).trim();
            int sp = s.indexOf(' ');
            return sp > 0 ? s.substring(0, sp).trim() : s;
        } catch (IOException e) {
            return "";
        }
    }

    @NonNull
    private static HealthStatus escalate(@NonNull HealthStatus current, @NonNull HealthStatus next) {
        return next.ordinal() > current.ordinal() ? next : current;
    }

    @Nullable
    private static String optString(@NonNull JsonObject obj, @NonNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int optInt(@NonNull JsonObject obj, @NonNull String key, int fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }
}
