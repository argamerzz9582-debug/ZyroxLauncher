package git.artdeell.mojo.worlds;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.InstanceManager;
import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.NetworkPolicyHelper;
import git.artdeell.mojo.utils.ZipUtils;

/**
 * Zyrox 2.0 World Manager & Verified Backup Engine.
 *
 * <p>Provides per-instance world discovery, integrity checks ({@code level.dat}, gzip signature,
 * {@code region/}, session lock, {@code icon.png}), timestamped ZIP backups with metadata and
 * SHA-256 verification, retention pruning, conflict-safe restore, SAF ZIP import/export with full
 * Zip-Slip/bomb guards, and safe deletion impact details.
 */
public final class WorldManager {
    public static final String BACKUP_META_ENTRY = "zyrox_world_backup.json";

    public enum Integrity {
        VALID,
        WARNING,
        CORRUPT
    }

    public enum RestoreMode {
        FAIL_ON_CONFLICT,
        RENAME_IF_EXISTS,
        OVERWRITE_CONFIRMED
    }

    public static final class WorldEntry {
        @NonNull public final File directory;
        @NonNull public final String folderName;
        @NonNull public final String displayName;
        @Nullable public final File iconFile;
        public final long sizeBytes;
        public final long lastModifiedMs;
        public final int datapackCount;
        @NonNull public final Integrity integrity;
        @NonNull public final String integrityNote;

        public WorldEntry(@NonNull File directory,
                          @NonNull String folderName,
                          @NonNull String displayName,
                          @Nullable File iconFile,
                          long sizeBytes,
                          long lastModifiedMs,
                          int datapackCount,
                          @NonNull Integrity integrity,
                          @NonNull String integrityNote) {
            this.directory = directory;
            this.folderName = folderName;
            this.displayName = displayName;
            this.iconFile = iconFile;
            this.sizeBytes = sizeBytes;
            this.lastModifiedMs = lastModifiedMs;
            this.datapackCount = datapackCount;
            this.integrity = integrity;
            this.integrityNote = integrityNote;
        }
    }

    public static final class BackupEntry {
        @NonNull public final File zipFile;
        @NonNull public final String worldName;
        @NonNull public final String instanceName;
        public final long sizeBytes;
        public final long createdAtMs;
        public final boolean hasSha256;

        public BackupEntry(@NonNull File zipFile,
                           @NonNull String worldName,
                           @NonNull String instanceName,
                           long sizeBytes,
                           long createdAtMs,
                           boolean hasSha256) {
            this.zipFile = zipFile;
            this.worldName = worldName;
            this.instanceName = instanceName;
            this.sizeBytes = sizeBytes;
            this.createdAtMs = createdAtMs;
            this.hasSha256 = hasSha256;
        }
    }

    private WorldManager() {
    }

    @NonNull
    public static File getSavesDirectory(@NonNull File gameDir) {
        return new File(gameDir, "saves");
    }

    @NonNull
    public static File getWorldBackupsDirectory(@NonNull File gameHome, @Nullable String instanceId) {
        String bucket = (instanceId != null && !instanceId.trim().isEmpty())
                ? FileUtils.escapeFileName(instanceId.trim()) : "default";
        if (bucket.isEmpty()) bucket = "default";
        return new File(new File(new File(gameHome, "backups"), "worlds"), bucket);
    }

    /**
     * Lists all worlds inside {@code <gameDir>/saves}, sorted newest-modified first.
     */
    @NonNull
    public static List<WorldEntry> listWorlds(@NonNull File gameDir) {
        File savesDir = getSavesDirectory(gameDir);
        if (!savesDir.isDirectory()) return Collections.emptyList();
        File[] children = savesDir.listFiles(File::isDirectory);
        if (children == null || children.length == 0) return Collections.emptyList();

        List<WorldEntry> result = new ArrayList<>(children.length);
        for (File dir : children) {
            if (dir.getName().startsWith(".")) continue;
            result.add(inspectWorld(dir));
        }
        Collections.sort(result, (a, b) -> Long.compare(b.lastModifiedMs, a.lastModifiedMs));
        return result;
    }

    /**
     * Inspects a single world folder without modifying any world files.
     */
    @NonNull
    public static WorldEntry inspectWorld(@NonNull File worldDir) {
        String folderName = worldDir.getName();
        File levelDat = new File(worldDir, "level.dat");
        File levelDatOld = new File(worldDir, "level.dat_old");
        File regionDir = new File(worldDir, "region");
        File icon = new File(worldDir, "icon.png");
        File datapacksDir = new File(worldDir, "datapacks");

        Integrity integrity;
        String note;
        if (!levelDat.isFile() || levelDat.length() < 10L) {
            if (levelDatOld.isFile() && levelDatOld.length() >= 10L) {
                integrity = Integrity.WARNING;
                note = "Missing level.dat (level.dat_old backup available)";
            } else {
                integrity = Integrity.CORRUPT;
                note = "Missing or empty level.dat";
            }
        } else if (!hasGzipHeader(levelDat)) {
            integrity = Integrity.WARNING;
            note = "Unusual level.dat header (non-gzip format)";
        } else if (!regionDir.isDirectory()) {
            integrity = Integrity.WARNING;
            note = "Valid level.dat (no Overworld region/ chunks saved yet)";
        } else {
            integrity = Integrity.VALID;
            note = "Healthy world save";
        }

        long modified = levelDat.isFile() ? Math.max(levelDat.lastModified(), worldDir.lastModified())
                : worldDir.lastModified();
        long bytes = InstanceManager.computeDirectoryBytes(worldDir, 30_000);
        int dpCount = 0;
        if (datapacksDir.isDirectory()) {
            File[] dps = datapacksDir.listFiles(f ->
                    (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip"))
                            || (f.isDirectory() && new File(f, "pack.mcmeta").isFile()));
            if (dps != null) dpCount = dps.length;
        }
        File validIcon = (icon.isFile() && icon.length() > 0L && icon.length() <= 2L * 1024L * 1024L)
                ? icon : null;
        return new WorldEntry(worldDir, folderName, folderName, validIcon,
                bytes, modified, dpCount, integrity, note);
    }

    /**
     * Creates a timestamped ZIP backup of {@code worldDir} inside {@code backupsDir} with metadata,
     * companion SHA-256 verification file, disk-space pre-check, and post-success retention pruning.
     */
    @NonNull
    public static File backupWorld(@NonNull File worldDir,
                                   @NonNull File backupsDir,
                                   @Nullable String instanceName,
                                   @Nullable String mcVersion,
                                   int retentionKeepCount) throws IOException {
        if (!worldDir.isDirectory()) {
            throw new IOException("World directory does not exist: " + worldDir.getName());
        }
        File levelDat = new File(worldDir, "level.dat");
        File levelDatOld = new File(worldDir, "level.dat_old");
        if (!levelDat.isFile() && !levelDatOld.isFile()) {
            throw new IOException("Cannot back up invalid world folder (missing level.dat)");
        }

        FileUtils.ensureDirectory(backupsDir);
        long worldSize = InstanceManager.computeDirectoryBytes(worldDir, 40_000);
        if (!NetworkPolicyHelper.hasEnoughDiskSpace(backupsDir, Math.max(4L * 1024L * 1024L, worldSize / 2L))) {
            throw new IOException("Insufficient free disk space to back up world (needs ~"
                    + NetworkPolicyHelper.formatBytes(worldSize) + ")");
        }

        String safeName = FileUtils.escapeFileName(worldDir.getName());
        if (safeName.isEmpty()) safeName = "world";
        String prefix = safeName + "--";
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        File finalZip = new File(backupsDir, prefix + stamp + ".zip");
        File tempZip = new File(backupsDir, finalZip.getName() + ".tmp");

        int[] files = new int[]{0};
        long[] bytes = new long[]{0L};
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tempZip)))) {
            writeWorldEntries(worldDir, "", zos, files, bytes);

            JsonObject meta = new JsonObject();
            meta.addProperty("format", "zyrox-world-backup-v2");
            meta.addProperty("worldName", worldDir.getName());
            meta.addProperty("instanceName", instanceName != null ? instanceName : "");
            meta.addProperty("minecraftVersion", mcVersion != null ? mcVersion : "");
            meta.addProperty("fileCount", files[0]);
            meta.addProperty("uncompressedBytes", bytes[0]);
            meta.addProperty("createdAtMs", System.currentTimeMillis());
            byte[] metaBytes = Tools.GLOBAL_GSON.toJson(meta).getBytes(StandardCharsets.UTF_8);
            zos.putNextEntry(new ZipEntry(BACKUP_META_ENTRY));
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
            throw new IOException("Failed to finalize world backup archive");
        }

        String sha256 = InstanceManager.computeSha256Hex(finalZip);
        File shaFile = new File(backupsDir, finalZip.getName() + ".sha256");
        try (FileOutputStream out = new FileOutputStream(shaFile, false)) {
            out.write((sha256 + "  " + finalZip.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        }

        if (retentionKeepCount > 0) {
            InstanceManager.pruneOldBackups(backupsDir, prefix, retentionKeepCount);
        }
        return finalZip;
    }

    /**
     * Lists existing world backups inside {@code backupsDir}, newest first.
     */
    @NonNull
    public static List<BackupEntry> listBackups(@NonNull File backupsDir) {
        if (!backupsDir.isDirectory()) return Collections.emptyList();
        File[] zips = backupsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".zip"));
        if (zips == null || zips.length == 0) return Collections.emptyList();
        Arrays.sort(zips, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<BackupEntry> out = new ArrayList<>(zips.length);
        for (File zip : zips) {
            String worldName = zip.getName().replace(".zip", "");
            int sep = worldName.indexOf("--");
            if (sep > 0) worldName = worldName.substring(0, sep);
            String instName = "";
            long created = zip.lastModified();
            try (ZipFile zf = new ZipFile(zip)) {
                String metaText = ArchiveSecurity.readZipEntryTextBounded(zf, BACKUP_META_ENTRY, 65536);
                if (metaText != null) {
                    JsonObject obj = new JsonParser().parse(metaText).getAsJsonObject();
                    if (obj.has("worldName")) worldName = obj.get("worldName").getAsString();
                    if (obj.has("instanceName")) instName = obj.get("instanceName").getAsString();
                    if (obj.has("createdAtMs")) created = obj.get("createdAtMs").getAsLong();
                }
            } catch (Exception ignored) {
            }
            boolean hasSha = new File(backupsDir, zip.getName() + ".sha256").isFile();
            out.add(new BackupEntry(zip, worldName, instName, zip.length(), created, hasSha));
        }
        return out;
    }

    /**
     * Returns true if restoring/importing a world named {@code worldName} would collide with an
     * existing folder inside {@code savesDir}.
     */
    public static boolean hasRestoreConflict(@NonNull File savesDir, @NonNull String worldName) {
        String safe = FileUtils.escapeFileName(worldName.trim());
        if (safe.isEmpty()) return false;
        return new File(savesDir, safe).exists();
    }

    /**
     * Verifies and restores or imports a world ZIP archive into {@code savesDir} with full Zip-Slip
     * and size protection, automatic nested-root detection, and explicit conflict handling.
     */
    @NonNull
    public static File restoreOrImportWorldZip(@NonNull File archiveZip,
                                               @NonNull File savesDir,
                                               @Nullable String preferredWorldName,
                                               @NonNull RestoreMode mode) throws IOException {
        if (!archiveZip.isFile()) {
            throw new IOException("World archive does not exist");
        }
        // Verify companion SHA-256 if present
        File shaFile = new File(archiveZip.getParentFile(), archiveZip.getName() + ".sha256");
        if (shaFile.isFile()) {
            try (FileInputStream in = new FileInputStream(shaFile)) {
                String line = new String(ArchiveSecurity.readBytesBounded(in, 4096), StandardCharsets.UTF_8).trim();
                int sp = line.indexOf(' ');
                String expected = sp > 0 ? line.substring(0, sp).trim() : line;
                String actual = InstanceManager.computeSha256Hex(archiveZip);
                if (!expected.isEmpty() && !expected.equalsIgnoreCase(actual)) {
                    throw new IOException("Backup SHA-256 checksum mismatch — refusing to restore corrupted archive");
                }
            }
        }
        if (!NetworkPolicyHelper.hasEnoughDiskSpace(savesDir, archiveZip.length() * 2L)) {
            throw new IOException("Insufficient free disk space to extract world archive");
        }

        FileUtils.ensureDirectory(savesDir);
        try (ZipFile zf = new ZipFile(archiveZip)) {
            String levelPrefix = detectWorldRootPrefix(zf);
            if (levelPrefix == null) {
                throw new IOException("Invalid Minecraft world archive: no level.dat found");
            }

            String worldName = preferredWorldName;
            if (worldName == null || worldName.trim().isEmpty()) {
                String metaText = ArchiveSecurity.readZipEntryTextBounded(zf, BACKUP_META_ENTRY, 65536);
                if (metaText != null) {
                    try {
                        JsonObject obj = new JsonParser().parse(metaText).getAsJsonObject();
                        if (obj.has("worldName")) worldName = obj.get("worldName").getAsString();
                    } catch (Exception ignored) {
                    }
                }
            }
            if ((worldName == null || worldName.trim().isEmpty()) && !levelPrefix.isEmpty()) {
                String stripped = levelPrefix.endsWith("/")
                        ? levelPrefix.substring(0, levelPrefix.length() - 1) : levelPrefix;
                int slash = stripped.lastIndexOf('/');
                worldName = slash >= 0 ? stripped.substring(slash + 1) : stripped;
            }
            if (worldName == null || worldName.trim().isEmpty()) {
                worldName = archiveZip.getName().replaceAll("(?i)\\.zip$", "");
            }

            String safeFolder = FileUtils.escapeFileName(worldName.trim());
            if (safeFolder.isEmpty() || ".".equals(safeFolder) || "..".equals(safeFolder)) {
                safeFolder = "Restored_World";
            }

            File targetDir = new File(savesDir, safeFolder);
            if (targetDir.exists()) {
                if (mode == RestoreMode.FAIL_ON_CONFLICT) {
                    throw new IOException("World \"" + safeFolder + "\" already exists");
                } else if (mode == RestoreMode.RENAME_IF_EXISTS) {
                    int suffix = 2;
                    while (targetDir.exists()) {
                        targetDir = new File(savesDir, safeFolder + " (" + suffix + ")");
                        suffix++;
                    }
                }
            }

            // Extract into a staging directory first so a failed restore never destroys an existing world!
            File stagingDir = new File(savesDir, ".zyrox_restore_" + System.currentTimeMillis());
            FileUtils.ensureDirectory(stagingDir);
            boolean extracted = false;
            try {
                ZipUtils.zipExtract(zf, levelPrefix, stagingDir);
                //noinspection ResultOfMethodCallIgnored
                new File(stagingDir, BACKUP_META_ENTRY).delete();
                if (!new File(stagingDir, "level.dat").isFile()) {
                    throw new IOException("Extracted world is missing level.dat");
                }
                if (targetDir.exists() && mode == RestoreMode.OVERWRITE_CONFIRMED) {
                    org.apache.commons.io.FileUtils.deleteDirectory(targetDir);
                }
                if (!stagingDir.renameTo(targetDir)) {
                    throw new IOException("Failed to move restored world into saves folder");
                }
                extracted = true;
                return targetDir;
            } finally {
                if (!extracted && stagingDir.exists()) {
                    org.apache.commons.io.FileUtils.deleteQuietly(stagingDir);
                }
            }
        }
    }

    /**
     * Streams a world backup ZIP directly to a user-chosen SAF {@link OutputStream}.
     */
    public static void exportWorldToStream(@NonNull File worldDir,
                                           @NonNull OutputStream outStream,
                                           @Nullable String instanceName,
                                           @Nullable String mcVersion) throws IOException {
        File tempDir = new File(Tools.DIR_CACHE, "world_export");
        File backup = backupWorld(worldDir, tempDir, instanceName, mcVersion, 1);
        byte[] buf = new byte[16 * 1024];
        try (InputStream in = new FileInputStream(backup)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                outStream.write(buf, 0, n);
            }
            outStream.flush();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(tempDir, backup.getName() + ".sha256").delete();
        }
    }

    /**
     * Deletes a world folder after explicit user confirmation, with containment checks so nothing
     * outside {@code saves/} can ever be deleted.
     */
    public static void deleteWorld(@NonNull File savesDir, @NonNull File worldDir) throws IOException {
        File safeTarget = ArchiveSecurity.resolveSafeEntry(savesDir, worldDir.getName());
        if (safeTarget.equals(savesDir.getCanonicalFile()) || !safeTarget.isDirectory()) {
            throw new IOException("Refusing to delete invalid world directory");
        }
        org.apache.commons.io.FileUtils.deleteDirectory(safeTarget);
    }

    /**
     * Locates the shallowest entry path prefix containing {@code level.dat}, validating every entry
     * against Zip-Slip syntax first.
     */
    @Nullable
    private static String detectWorldRootPrefix(@NonNull ZipFile zf) throws IOException {
        Enumeration<? extends ZipEntry> entries = zf.entries();
        String bestPrefix = null;
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String raw = e.getName();
            if (raw == null) continue;
            if (raw.indexOf('\0') >= 0) throw new IOException("Blocked archive with NUL byte");
            String norm = raw.replace('\\', '/');
            if (norm.startsWith("/") || norm.contains("../") || norm.equals("..")) {
                throw new IOException("Blocked unsafe path in world archive: " + raw);
            }
            if ("level.dat".equals(norm)) {
                return "";
            }
            if (norm.endsWith("/level.dat")) {
                String prefix = norm.substring(0, norm.length() - "level.dat".length());
                if (bestPrefix == null || prefix.length() < bestPrefix.length()) {
                    bestPrefix = prefix;
                }
            }
        }
        return bestPrefix;
    }

    private static void writeWorldEntries(@NonNull File current,
                                          @NonNull String prefix,
                                          @NonNull ZipOutputStream zos,
                                          int[] fileCount,
                                          long[] byteCount) throws IOException {
        File[] list = current.listFiles();
        if (list == null) return;
        byte[] buf = new byte[16 * 1024];
        for (File child : list) {
            String name = child.getName();
            // Skip live session.lock so restored worlds never complain about stale locks
            if ("session.lock".equals(name) || name.endsWith(".tmp")) continue;
            String entryName = prefix.isEmpty() ? name : (prefix + "/" + name);
            if (child.isDirectory()) {
                writeWorldEntries(child, entryName, zos, fileCount, byteCount);
            } else if (child.isFile()) {
                ZipEntry entry = new ZipEntry(entryName);
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

    private static boolean hasGzipHeader(@NonNull File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            int b0 = in.read();
            int b1 = in.read();
            return b0 == 0x1F && b1 == 0x8B;
        } catch (IOException e) {
            return false;
        }
    }
}
