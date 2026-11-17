package git.artdeell.mojo.utils;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class ZipUtils {
    /**
     * Gets an InputStream for a given ZIP entry, throwing an IOException if the ZIP entry does not
     * exist.
     */
    public static InputStream getEntryStream(ZipFile zipFile, String entryPath) throws IOException {
        ZipEntry entry = zipFile.getEntry(entryPath);
        if (entry == null) throw new IOException("No entry in ZIP file: " + entryPath);
        return zipFile.getInputStream(entry);
    }

    /**
     * Extracts all files in a ZipFile inside of a given directory to a given destination directory
     * with strict canonical path containment against ZipSlip (CWE-22), symlink traversal, and
     * decompression bomb limits.
     */
    public static void zipExtract(ZipFile zipFile, String dirName, File destination) throws IOException {
        zipExtract(zipFile, dirName, destination, ArchiveSecurity.ExtractionLimits.defaults());
    }

    /**
     * Extracts all files in a ZipFile matching {@code dirName} with explicit size/entry limits.
     */
    public static void zipExtract(@NonNull ZipFile zipFile,
                                  @NonNull String dirName,
                                  @NonNull File destination,
                                  @NonNull ArchiveSecurity.ExtractionLimits limits) throws IOException {
        FileUtils.ensureDirectory(destination);
        File canonicalRoot = destination.getCanonicalFile();
        String prefix = dirName.replace('\\', '/');
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        int prefixLen = prefix.length();
        int extractedEntries = 0;
        long totalBytes = 0L;

        while (zipEntries.hasMoreElements()) {
            ZipEntry zipEntry = zipEntries.nextElement();
            String rawName = zipEntry.getName();
            if (rawName == null) continue;
            if (rawName.indexOf('\0') >= 0) {
                throw new IOException("Blocked ZIP entry with NUL byte");
            }
            String normalizedName = rawName.replace('\\', '/');
            if (!normalizedName.startsWith(prefix)) continue;
            String relativeName = normalizedName.substring(prefixLen);
            while (relativeName.startsWith("/") && !prefix.isEmpty()) {
                relativeName = relativeName.substring(1);
            }
            if (relativeName.isEmpty()) continue;

            extractedEntries++;
            if (extractedEntries > limits.maxEntries) {
                throw new IOException("Blocked archive exceeding maximum entry limit (" + limits.maxEntries + ")");
            }

            File zipDestination = ArchiveSecurity.resolveSafeEntry(canonicalRoot, relativeName);
            if (zipEntry.isDirectory() || normalizedName.endsWith("/")) {
                if (!zipDestination.equals(canonicalRoot)) {
                    FileUtils.ensureDirectory(zipDestination);
                }
                continue;
            }
            try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
                totalBytes += ArchiveSecurity.copyBoundedToFile(
                        inputStream,
                        zipDestination,
                        limits.maxSingleFileBytes,
                        limits.maxTotalBytes - totalBytes
                );
            }
        }
    }

    /**
     * Extracts a ZipInputStream into {@code destination} with strict canonical path containment
     * against ZipSlip (CWE-22), Windows backslash traversal, and decompression bombs.
     */
    public static void zipStreamExtract(ZipInputStream inputStream, File destination) throws IOException {
        zipStreamExtract(inputStream, destination, ArchiveSecurity.ExtractionLimits.defaults());
    }

    /**
     * Extracts a ZipInputStream into {@code destination} with explicit size and entry bounds.
     */
    public static void zipStreamExtract(@NonNull ZipInputStream inputStream,
                                        @NonNull File destination,
                                        @NonNull ArchiveSecurity.ExtractionLimits limits) throws IOException {
        FileUtils.ensureDirectory(destination);
        File canonicalRoot = destination.getCanonicalFile();
        int entryCount = 0;
        long totalBytes = 0L;

        ZipEntry entry;
        while ((entry = inputStream.getNextEntry()) != null) {
            String entryName = entry.getName();
            if (entryName == null || entryName.isEmpty()) continue;
            entryCount++;
            if (entryCount > limits.maxEntries) {
                throw new IOException("Blocked archive stream exceeding maximum entry limit (" + limits.maxEntries + ")");
            }
            File targetFile = ArchiveSecurity.resolveSafeEntry(canonicalRoot, entryName);
            if (entry.isDirectory() || entryName.endsWith("/") || entryName.endsWith("\\")) {
                if (!targetFile.equals(canonicalRoot)) {
                    FileUtils.ensureDirectory(targetFile);
                }
                continue;
            }
            if (targetFile.equals(canonicalRoot)) continue;
            totalBytes += ArchiveSecurity.copyBoundedToFile(
                    inputStream,
                    targetFile,
                    limits.maxSingleFileBytes,
                    limits.maxTotalBytes - totalBytes
            );
        }
    }
}
