package git.artdeell.mojo.utils;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
     * with strict canonical path containment against ZipSlip (CWE-22).
     */
    public static void zipExtract(ZipFile zipFile, String dirName, File destination) throws IOException {
        FileUtils.ensureDirectory(destination);
        String canonicalBase = destination.getCanonicalPath();
        String canonicalPrefix = canonicalBase.endsWith(File.separator)
                ? canonicalBase
                : canonicalBase + File.separator;

        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        int dirNameLen = dirName.length();
        while (zipEntries.hasMoreElements()) {
            ZipEntry zipEntry = zipEntries.nextElement();
            String entryName = zipEntry.getName();
            if (!entryName.startsWith(dirName) || zipEntry.isDirectory()) continue;
            String relativeName = entryName.substring(dirNameLen);
            if (relativeName.isEmpty() || relativeName.indexOf('\0') >= 0) continue;

            File zipDestination = new File(destination, relativeName);
            String canonicalTarget = zipDestination.getCanonicalPath();
            if (!canonicalTarget.startsWith(canonicalPrefix)) {
                throw new IOException("Blocked ZipSlip entry outside destination: " + entryName);
            }
            FileUtils.ensureParentDirectory(zipDestination);
            try (InputStream inputStream = zipFile.getInputStream(zipEntry);
                 OutputStream outputStream = new FileOutputStream(zipDestination)) {
                IOUtils.copy(inputStream, outputStream);
            }
        }
    }

    /**
     * Extracts a ZipInputStream into {@code destination} with strict canonical path containment
     * against ZipSlip (CWE-22).
     */
    public static void zipStreamExtract(ZipInputStream inputStream, File destination) throws IOException {
        FileUtils.ensureDirectory(destination);
        String canonicalBase = destination.getCanonicalPath();
        String canonicalPrefix = canonicalBase.endsWith(File.separator)
                ? canonicalBase
                : canonicalBase + File.separator;

        ZipEntry entry;
        while ((entry = inputStream.getNextEntry()) != null) {
            String entryName = entry.getName();
            if (entryName == null || entryName.isEmpty() || entryName.indexOf('\0') >= 0) {
                continue;
            }
            File targetFile = new File(destination, entryName);
            String canonicalTarget = targetFile.getCanonicalPath();
            if (!canonicalTarget.equals(canonicalBase) && !canonicalTarget.startsWith(canonicalPrefix)) {
                throw new IOException("Blocked ZipSlip entry outside destination: " + entryName);
            }
            if (entry.isDirectory()) {
                FileUtils.ensureDirectory(targetFile);
                continue;
            }
            FileUtils.ensureParentDirectory(targetFile);
            try (FileOutputStream fs = new FileOutputStream(targetFile)) {
                IOUtils.copy(inputStream, fs);
            }
        }
    }
}
