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
        return InstanceHealthInspector.checkHealth(
                instance, versionsDir, controlsDir, requiredJavaMajor, selectedJavaMajor);
    }

    @NonNull
    public static List<String> repairInstance(@NonNull Instance instance, @Nullable File controlsDir)
            throws IOException {
        return InstanceHealthInspector.repairInstance(instance, controlsDir);
    }

    @NonNull
    public static List<String> findDuplicateSummaries(@NonNull List<? extends DisplayInstance> instances) {
        return InstanceHealthInspector.findDuplicateSummaries(instances);
    }

    @NonNull
    public static DeleteImpact computeDeleteImpact(@NonNull Instance instance) {
        return InstanceHealthInspector.computeDeleteImpact(instance);
    }

    @NonNull
    public static File createInstanceBackup(@NonNull Instance instance,
                                            @NonNull File backupsDir,
                                            boolean includeWorlds,
                                            int maxBackupsToKeep) throws IOException {
        return InstanceBackupService.createInstanceBackup(
                instance, backupsDir, includeWorlds, maxBackupsToKeep);
    }

    @NonNull
    public static Instance restoreInstanceBackup(@NonNull File backupZip,
                                                 @NonNull File instancesDir) throws IOException {
        return InstanceBackupService.restoreInstanceBackup(backupZip, instancesDir);
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
        InstanceBackupService.pruneOldBackups(dir, prefix, keepCount);
    }

    @NonNull
    public static String computeSha256Hex(@NonNull File file) throws IOException {
        return InstanceBackupService.computeSha256Hex(file);
    }

    public static long computeDirectoryBytes(@Nullable File dir, int maxFiles) {
        return InstanceBackupService.computeDirectoryBytes(dir, maxFiles);
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
