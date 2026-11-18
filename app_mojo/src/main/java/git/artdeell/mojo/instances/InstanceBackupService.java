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
 * Single-responsibility SHA-256 verified ZIP backup, safe isolated restore, retention pruning, and
 * disk-size accounting service extracted from {@link InstanceManager}.
 */
public final class InstanceBackupService {

    private InstanceBackupService() {
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
