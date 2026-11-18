package git.artdeell.mojo.instances;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.security.AppTrustVerifier;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.NetworkPolicyHelper;
import git.artdeell.mojo.utils.ZipUtils;

/**
 * Single-responsibility SHA-256 verified ZIP backup, rolling pre-mod world/config snapshot,
 * Zip-Slip hardened rollback, retention pruning, and disk-size accounting service.
 */
public final class InstanceBackupService {

    /** Directory under {@code instance.mInstanceRoot} storing rolling pre-mod safety snapshots. */
    public static final String PRE_MOD_SNAPSHOT_DIR = ".zyrox_snapshots";
    /** File prefix for rolling pre-mod {@code config/} + {@code saves/} snapshots. */
    public static final String PRE_MOD_SNAPSHOT_PREFIX = "premod-";
    /** Maximum rolling pre-mod snapshots retained per instance (oldest pruned automatically). */
    public static final int MAX_PRE_MOD_SNAPSHOTS = 3;
    /** Metadata JSON entry inside every pre-mod safety snapshot ZIP. */
    public static final String SNAPSHOT_META_ENTRY = "zyrox_snapshot_meta.json";
    /** Bounded uncompressed budget (128 MiB) so auto pre-mod snapshots stay sub-second on mobile. */
    private static final long MAX_PRE_MOD_SNAPSHOT_BYTES = 128L * 1024L * 1024L;

    /** Immutable summary of a saved pre-mod {@code config/} + {@code saves/} safety snapshot. */
    public static final class SnapshotInfo {
        @NonNull public final File archiveFile;
        @NonNull public final String reason;
        public final long createdAtMs;
        public final long sizeBytes;

        public SnapshotInfo(@NonNull File archiveFile,
                            @NonNull String reason,
                            long createdAtMs,
                            long sizeBytes) {
            this.archiveFile = archiveFile;
            this.reason = reason;
            this.createdAtMs = createdAtMs;
            this.sizeBytes = sizeBytes;
        }

        @NonNull
        public String formatDisplayLabel() {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                    .format(new Date(createdAtMs));
            long kb = Math.max(1L, sizeBytes / 1024L);
            String sizeStr = kb >= 1024L
                    ? String.format(Locale.ROOT, "%.1f MB", kb / 1024.0)
                    : (kb + " KB");
            return ts + " • " + reason + " (" + sizeStr + ")";
        }
    }

    private InstanceBackupService() {
    }

    /**
     * Best-effort silent wrapper around {@link #createPreModSnapshot(Instance, String)} invoked
     * automatically before installing, updating, or removing mods. Never throws.
     */
    @Nullable
    public static File createPreModSnapshotQuietly(@Nullable Instance instance,
                                                   @NonNull String reason) {
        if (instance == null) return null;
        try {
            return createPreModSnapshot(instance, reason);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Phase 4.6 — Creates an atomic, SHA-256 verified rolling compressed snapshot of the
     * instance's {@code config/}, {@code saves/}, and {@code options.txt} before mod changes,
     * automatically pruning older archives to retain strictly the latest {@value #MAX_PRE_MOD_SNAPSHOTS}.
     */
    @Nullable
    public static File createPreModSnapshot(@NonNull Instance instance,
                                            @NonNull String reason) throws IOException {
        File gameDir = instance.getGameDirectory();
        File snapshotRoot = resolveSnapshotDir(instance);
        if (gameDir == null || snapshotRoot == null || !gameDir.isDirectory()) {
            return null;
        }

        File configDir = new File(gameDir, "config");
        File savesDir = new File(gameDir, "saves");
        File optionsFile = new File(gameDir, "options.txt");
        boolean hasConfig = configDir.isDirectory() && hasAnyFiles(configDir);
        boolean hasSaves = savesDir.isDirectory() && hasAnyFiles(savesDir);
        boolean hasOptions = optionsFile.isFile() && optionsFile.length() > 0L;
        if (!hasConfig && !hasSaves && !hasOptions) {
            return null; // Fresh empty instance — nothing to snapshot yet
        }

        FileUtils.ensureDirectory(snapshotRoot);
        if (!NetworkPolicyHelper.hasEnoughDiskSpace(snapshotRoot, 8L * 1024L * 1024L)) {
            throw new IOException("Insufficient disk space for pre-mod safety snapshot.");
        }

        String safeReason = FileUtils.escapeFileName(reason);
        if (safeReason.isEmpty()) safeReason = "mod-change";
        if (safeReason.length() > 28) safeReason = safeReason.substring(0, 28);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        File finalZip = new File(snapshotRoot, PRE_MOD_SNAPSHOT_PREFIX + stamp + "_" + safeReason + ".zip");
        File tempZip = new File(snapshotRoot, finalZip.getName() + ".tmp");

        int[] fileCounter = new int[]{0};
        long[] byteCounter = new long[]{0L};
        boolean includedSaves = false;

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(tempZip)))) {
            if (hasOptions && optionsFile.length() <= 1024L * 1024L) {
                writeSingleFileEntry(optionsFile, "options.txt", zos, fileCounter, byteCounter);
            }
            if (hasConfig) {
                zipSubtreeBounded(configDir, "config", zos, fileCounter, byteCounter,
                        MAX_PRE_MOD_SNAPSHOT_BYTES);
            }
            if (hasSaves) {
                long savesBytes = computeDirectoryBytes(savesDir, 15_000);
                if (byteCounter[0] + savesBytes <= MAX_PRE_MOD_SNAPSHOT_BYTES) {
                    zipSubtreeBounded(savesDir, "saves", zos, fileCounter, byteCounter,
                            MAX_PRE_MOD_SNAPSHOT_BYTES);
                    includedSaves = true;
                }
            }
            if (fileCounter[0] == 0) {
                zos.finish();
                //noinspection ResultOfMethodCallIgnored
                tempZip.delete();
                return null;
            }
            JsonObject meta = new JsonObject();
            meta.addProperty("format", "zyrox-premod-snapshot-v1");
            meta.addProperty("reason", safeReason);
            meta.addProperty("versionId", instance.versionId != null ? instance.versionId : "");
            meta.addProperty("includesSaves", includedSaves);
            meta.addProperty("fileCount", fileCounter[0]);
            meta.addProperty("uncompressedBytes", byteCounter[0]);
            meta.addProperty("createdAtMs", System.currentTimeMillis());
            zos.putNextEntry(new ZipEntry(SNAPSHOT_META_ENTRY));
            zos.write(Tools.GLOBAL_GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
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
            throw new IOException("Could not finalize pre-mod safety snapshot.");
        }

        String sha256 = computeSha256Hex(finalZip);
        File shaFile = new File(snapshotRoot, finalZip.getName() + ".sha256");
        try (FileOutputStream out = new FileOutputStream(shaFile, false)) {
            out.write((sha256 + "  " + finalZip.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        }

        pruneOldBackups(snapshotRoot, PRE_MOD_SNAPSHOT_PREFIX, MAX_PRE_MOD_SNAPSHOTS);
        return finalZip;
    }

    /** Lists available rolling pre-mod snapshots for {@code instance}, ordered newest-first. */
    @NonNull
    public static java.util.List<SnapshotInfo> listPreModSnapshots(@Nullable Instance instance) {
        java.util.List<SnapshotInfo> list = new java.util.ArrayList<>();
        if (instance == null) return list;
        File dir = resolveSnapshotDir(instance);
        if (dir == null || !dir.isDirectory()) return list;
        File[] zips = dir.listFiles((d, name) ->
                name.startsWith(PRE_MOD_SNAPSHOT_PREFIX) && name.endsWith(".zip"));
        if (zips == null || zips.length == 0) return list;
        Arrays.sort(zips, (a, b) -> {
            int cmp = Long.compare(b.lastModified(), a.lastModified());
            return cmp != 0 ? cmp : b.getName().compareTo(a.getName());
        });
        for (File zip : zips) {
            String base = zip.getName().substring(0, zip.getName().length() - 4);
            int us = base.indexOf('_');
            String reason = (us > 0 && us + 1 < base.length())
                    ? base.substring(us + 1) : "mod-change";
            list.add(new SnapshotInfo(zip, reason, zip.lastModified(), zip.length()));
        }
        return list;
    }

    /**
     * Phase 4.6 — Verifies SHA-256 integrity and restores {@code config/}, {@code saves/}, and
     * {@code options.txt} from a pre-mod safety snapshot with strict canonical Zip-Slip enforcement.
     *
     * @return number of restored files.
     */
    public static int rollbackPreModSnapshot(@NonNull Instance instance,
                                             @NonNull File archiveFile) throws IOException {
        if (!archiveFile.isFile()) {
            throw new IOException("Snapshot archive not found.");
        }
        File gameDir = instance.getGameDirectory();
        if (gameDir == null) {
            throw new IOException("Instance game directory unavailable.");
        }
        File shaFile = new File(archiveFile.getParentFile(), archiveFile.getName() + ".sha256");
        if (shaFile.isFile()) {
            String expected = readFirstToken(shaFile);
            String actual = computeSha256Hex(archiveFile);
            if (!expected.isEmpty() && !expected.equalsIgnoreCase(actual)) {
                throw new IOException("Snapshot SHA-256 integrity check failed.");
            }
        }
        FileUtils.ensureDirectory(gameDir);
        String canonicalRoot = gameDir.getCanonicalPath() + File.separator;
        int restored = 0;
        long totalBytes = 0L;
        byte[] buf = new byte[16 * 1024];

        try (ZipFile zip = new ZipFile(archiveFile)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || SNAPSHOT_META_ENTRY.equals(name)) continue;
                // Strictly allowlist config/, saves/, and options.txt only
                if (!("options.txt".equals(name)
                        || name.startsWith("config/")
                        || name.startsWith("saves/"))) {
                    continue;
                }
                if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                    throw new IOException("Blocked unsafe snapshot entry: " + name);
                }
                File dest = new File(gameDir, name);
                String canonicalDest = dest.getCanonicalPath();
                if (!canonicalDest.startsWith(canonicalRoot)) {
                    throw new IOException("Zip-Slip blocked during snapshot rollback: " + name);
                }
                File parent = dest.getParentFile();
                if (parent != null) FileUtils.ensureDirectory(parent);
                File tmp = new File(dest.getParentFile(), dest.getName() + ".rollback.tmp");
                try (java.io.InputStream in = zip.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(tmp, false)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        totalBytes += n;
                        if (totalBytes > MAX_PRE_MOD_SNAPSHOT_BYTES * 2L) {
                            throw new IOException("Snapshot exceeds safe extraction ceiling.");
                        }
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } catch (IOException e) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    throw e;
                }
                if (dest.exists() && !dest.delete()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    throw new IOException("Could not overwrite " + dest.getName());
                }
                if (!tmp.renameTo(dest)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    throw new IOException("Could not finalize restored file " + dest.getName());
                }
                restored++;
            }
        }
        return restored;
    }

    @Nullable
    private static File resolveSnapshotDir(@NonNull Instance instance) {
        if (instance.mInstanceRoot != null) {
            return new File(instance.mInstanceRoot, PRE_MOD_SNAPSHOT_DIR);
        }
        File gd = instance.getGameDirectory();
        return gd != null ? new File(gd, PRE_MOD_SNAPSHOT_DIR) : null;
    }

    private static boolean hasAnyFiles(@NonNull File dir) {
        File[] children = dir.listFiles();
        return children != null && children.length > 0;
    }

    private static void writeSingleFileEntry(@NonNull File file,
                                             @NonNull String entryPath,
                                             @NonNull ZipOutputStream zos,
                                             int[] fileCount,
                                             long[] byteCount) throws IOException {
        ZipEntry entry = new ZipEntry(entryPath);
        entry.setTime(file.lastModified());
        zos.putNextEntry(entry);
        byte[] buf = new byte[16 * 1024];
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                zos.write(buf, 0, n);
                byteCount[0] += n;
            }
        }
        zos.closeEntry();
        fileCount[0]++;
    }

    private static void zipSubtreeBounded(@NonNull File dir,
                                          @NonNull String relPrefix,
                                          @NonNull ZipOutputStream zos,
                                          int[] fileCount,
                                          long[] byteCount,
                                          long maxBytes) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (byteCount[0] >= maxBytes) return;
            String name = child.getName();
            if (name.endsWith(".tmp") || name.endsWith(".lock") || "session.lock".equals(name)) {
                continue;
            }
            String entryPath = relPrefix + "/" + name;
            if (child.isDirectory()) {
                zipSubtreeBounded(child, entryPath, zos, fileCount, byteCount, maxBytes);
            } else if (child.isFile() && child.length() + byteCount[0] <= maxBytes) {
                writeSingleFileEntry(child, entryPath, zos, fileCount, byteCount);
            }
        }
    }

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
        if (!NetworkPolicyHelper.hasEnoughDiskSpace(
                backupsDir, Math.max(4L * 1024 * 1024, estimated / 2))) {
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
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(tempZip)))) {
            zipDirectoryFiltered(instance.mInstanceRoot, "", zos,
                    includeWorlds, fileCounter, byteCounter);

            JsonObject meta = new JsonObject();
            meta.addProperty("format", "zyrox-instance-backup-v2");
            meta.addProperty("name", instance.name != null ? instance.name : "");
            meta.addProperty("versionId", instance.versionId != null ? instance.versionId : "");
            meta.addProperty("includesWorlds", includeWorlds);
            meta.addProperty("fileCount", fileCounter[0]);
            meta.addProperty("uncompressedBytes", byteCounter[0]);
            meta.addProperty("createdAtMs", System.currentTimeMillis());
            byte[] metaBytes = Tools.GLOBAL_GSON.toJson(meta).getBytes(StandardCharsets.UTF_8);
            zos.putNextEntry(new ZipEntry(InstanceManager.BACKUP_META_ENTRY));
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

        String sha256 = computeSha256Hex(finalZip);
        File shaFile = new File(backupsDir, finalZip.getName() + ".sha256");
        try (FileOutputStream out = new FileOutputStream(shaFile, false)) {
            out.write((sha256 + "  " + finalZip.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        }

        pruneOldBackups(backupsDir, safeLabel + "-", Math.max(1, maxBackupsToKeep));
        return finalZip;
    }

    @NonNull
    public static Instance restoreInstanceBackup(@NonNull File backupZip,
                                                 @NonNull File instancesDir) throws IOException {
        if (!backupZip.isFile()) {
            throw new IOException("Backup archive not found");
        }
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
                throw new IOException(
                        "Archive is not a valid Zyrox instance backup (missing mojo_instance.json)");
            }
            ZipUtils.zipExtract(zf, "", newRoot);
            //noinspection ResultOfMethodCallIgnored
            new File(newRoot, InstanceManager.BACKUP_META_ENTRY).delete();
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

    public static void pruneOldBackups(@NonNull File dir, @NonNull String prefix, int keepCount) {
        if (keepCount < 1 || !dir.isDirectory()) return;
        File[] zips = dir.listFiles(
                f -> f.isFile() && f.getName().startsWith(prefix) && f.getName().endsWith(".zip"));
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

    private static void accumulateBytes(@NonNull File node,
                                        long[] sum,
                                        int[] visited,
                                        int maxFiles) {
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
            if (name.endsWith(".tmp") || InstanceManager.BACKUP_META_ENTRY.equals(name)) continue;
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

    @NonNull
    private static String readFirstToken(@NonNull File file) {
        byte[] buf = new byte[128];
        try (FileInputStream in = new FileInputStream(file)) {
            int n = in.read(buf);
            if (n <= 0) return "";
            String text = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            int sp = text.indexOf(' ');
            return sp > 0 ? text.substring(0, sp).trim() : text;
        } catch (IOException e) {
            return "";
        }
    }
}
