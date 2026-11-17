package git.artdeell.mojo.utils;

import java.io.File;
import java.io.IOException;

public class FileUtils {
    /**
     * Check if a file denoted by a String path exists.
     * @param filePath the path to check
     * @return whether it exists (same as File.exists())
     */
    public static boolean exists(String filePath) {
        return new File(filePath).exists();
    }

    /**
     * Get the bare file name (without path separators) from a path/URL string.
     * @param pathOrUrl the path or the URL of the file
     * @return the file's name, or null if empty or invalid
     */
    public static String getFileName(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isEmpty()) return null;
        String normalized = pathOrUrl.replace('\\', '/');
        int lastSlashIndex = normalized.lastIndexOf('/');
        String name = (lastSlashIndex == -1) ? normalized : normalized.substring(lastSlashIndex + 1);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return null;
        return name;
    }

    /**
     * Resolve a relative path under {@code baseDir} and enforce canonical containment
     * to prevent ZipSlip / TarSlip / path traversal (CWE-22).
     */
    public static File resolveInsideDirectory(File baseDir, String relativePath) throws IOException {
        if (relativePath == null || relativePath.indexOf('\0') >= 0) {
            throw new IOException("Invalid path entry");
        }
        File candidate = new File(baseDir, relativePath);
        return ensureInsideDirectory(baseDir, candidate);
    }

    /**
     * Ensure that {@code candidate}'s canonical path resides within (or equals) {@code baseDir}.
     */
    public static File ensureInsideDirectory(File baseDir, File candidate) throws IOException {
        String canonicalBase = baseDir.getCanonicalPath();
        String canonicalTarget = candidate.getCanonicalPath();
        String basePrefix = canonicalBase.endsWith(File.separator)
                ? canonicalBase
                : canonicalBase + File.separator;
        if (!canonicalTarget.equals(canonicalBase) && !canonicalTarget.startsWith(basePrefix)) {
            throw new IOException("Blocked path traversal outside target directory: " + candidate.getName());
        }
        return candidate;
    }

    /**
     * Non-throwing check whether {@code candidate}'s canonical path resides within {@code baseDir}.
     */
    public static boolean isInsideDirectory(File baseDir, File candidate) {
        if (baseDir == null || candidate == null) return false;
        try {
            ensureInsideDirectory(baseDir, candidate);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Remove the extension (all text after the last dot) from a path/URL string.
     * @param pathOrUrl the path or the URL of the file
     * @return the input with the extension removed
     */
    public static String removeExtension(String pathOrUrl) {
        int lastDotIndex = pathOrUrl.lastIndexOf('.');
        if (lastDotIndex == -1) return pathOrUrl;
        return pathOrUrl.substring(0, lastDotIndex);
    }

    /**
     * Ensure that a directory exists, is a directory and is writable.
     * @param targetFile the directory to check
     * @return if the check has succeeded
     */
    public static boolean ensureDirectorySilently(File targetFile) {
        if (targetFile.isFile()) return false;
        if (targetFile.exists()) return targetFile.canWrite();
        else return targetFile.mkdirs();
    }

    /**
     * Ensure that the parent directory of a file exists and is writable
     * @param targetFile the File whose parent should be checked
     * @return if the check has succeeded
     */
    public static boolean ensureParentDirectorySilently(File targetFile) {
        File parentFile = targetFile.getParentFile();
        if (parentFile == null) return false;
        return ensureDirectorySilently(parentFile);
    }

    /**
     * Same as ensureDirectorySilently(), but throws an IOException telling why the check failed.
     * @param targetFile the directory to check
     * @throws IOException when the checks fail
     */
    public static void ensureDirectory(File targetFile) throws IOException {
        if (targetFile.isFile()) throw new IOException("Target directory is a file");
        if (targetFile.exists()) {
            if (!targetFile.canWrite()) throw new IOException("Target directory is not writable");
        } else if (!targetFile.mkdirs()) {
            if (!targetFile.isDirectory()) throw new IOException("Unable to create target directory");
        }
    }

    /**
     * Same as ensureParentDirectorySilently(), but throws an IOException telling why the check failed.
     * @param targetFile the File whose parent should be checked
     * @throws IOException when the checks fail
     */
    public static void ensureParentDirectory(File targetFile) throws IOException {
        File parentFile = targetFile.getParentFile();
        if (parentFile == null) throw new IOException("targetFile does not have a parent");
        ensureDirectory(parentFile);
    }

    public static String escapeFileName(String name) {
        String sanitized = name.trim()
                .replace("\0", "")
                .replace("..", "_")
                .replaceAll("[\\\\/:*?\"<>| \\t\\n]", "_");
        return sanitized.isEmpty() ? "unnamed" : sanitized;
    }
}
