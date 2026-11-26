package git.artdeell.mojo.utils;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Hardened archive extraction and bounded entry-inspection guards protecting against
 * Zip Slip (CWE-22), symlink escape (CWE-59), null-byte injection, and decompression bombs.
 */
public final class ArchiveSecurity {
    public static final int DEFAULT_MAX_ENTRIES = 65_536;
    public static final long DEFAULT_MAX_SINGLE_FILE_BYTES = 1024L * 1024L * 1024L; // 1 GiB
    public static final long DEFAULT_MAX_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L;   // 4 GiB
    public static final int DEFAULT_MAX_METADATA_BYTES = 512 * 1024;                 // 512 KiB

    private ArchiveSecurity() {
    }

    public static final class ExtractionLimits {
        public final int maxEntries;
        public final long maxSingleFileBytes;
        public final long maxTotalBytes;

        public ExtractionLimits(int maxEntries, long maxSingleFileBytes, long maxTotalBytes) {
            this.maxEntries = Math.max(1, maxEntries);
            this.maxSingleFileBytes = Math.max(1024L, maxSingleFileBytes);
            this.maxTotalBytes = Math.max(this.maxSingleFileBytes, maxTotalBytes);
        }

        @NonNull
        public static ExtractionLimits defaults() {
            return new ExtractionLimits(
                    DEFAULT_MAX_ENTRIES,
                    DEFAULT_MAX_SINGLE_FILE_BYTES,
                    DEFAULT_MAX_TOTAL_BYTES
            );
        }
    }

    /**
     * Normalizes an archive entry path, rejects unsafe syntax (null bytes, drive letters, absolute
     * roots, {@code ..} traversal across both {@code /} and {@code \}), and verifies strict
     * canonical containment inside {@code destinationRoot} without crossing symlink parents.
     */
    @NonNull
    public static File resolveSafeEntry(@NonNull File destinationRoot, @Nullable String rawEntryName)
            throws IOException {
        if (rawEntryName == null) {
            throw new IOException("Blocked archive entry with null name");
        }
        if (rawEntryName.indexOf('\0') >= 0) {
            throw new IOException("Blocked archive entry containing NUL byte");
        }
        // Normalize Windows backslash separators so "..\\evil" is caught uniformly on POSIX/Android.
        String normalized = rawEntryName.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            throw new IOException("Blocked empty archive entry name");
        }
        if (normalized.startsWith("/") || normalized.startsWith("~")) {
            throw new IOException("Blocked absolute archive entry path: " + rawEntryName);
        }
        // Reject Windows drive-letter prefixes such as "C:/..." or "D:evil"
        if (normalized.length() >= 2
                && Character.isLetter(normalized.charAt(0))
                && normalized.charAt(1) == ':') {
            throw new IOException("Blocked drive-prefixed archive entry path: " + rawEntryName);
        }
        String[] segments = normalized.split("/");
        StringBuilder cleanRel = new StringBuilder(normalized.length());
        for (String seg : segments) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                throw new IOException("Blocked parent-traversal archive entry: " + rawEntryName);
            }
            if (cleanRel.length() > 0) cleanRel.append(File.separatorChar);
            cleanRel.append(seg);
        }
        if (cleanRel.length() == 0) {
            return destinationRoot.getCanonicalFile();
        }

        File canonicalRoot = destinationRoot.getCanonicalFile();
        String rootPath = canonicalRoot.getPath();
        String rootPrefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;

        File candidate = new File(canonicalRoot, cleanRel.toString());
        String canonicalTarget = candidate.getCanonicalPath();
        if (!canonicalTarget.startsWith(rootPrefix)) {
            throw new IOException("Blocked ZipSlip entry outside destination: " + rawEntryName);
        }
        rejectSymlinkChain(canonicalRoot, candidate);
        return candidate;
    }

    private static void rejectSymlinkChain(@NonNull File canonicalRoot, @NonNull File target)
            throws IOException {
        if (!SdkLevel.atLeast(Build.VERSION_CODES.O)) return;
        File cur = target;
        while (cur != null && !cur.equals(canonicalRoot)) {
            try {
                if (cur.exists() && Files.isSymbolicLink(cur.toPath())) {
                    throw new IOException("Blocked archive extraction through symbolic link: " + cur.getName());
                }
            } catch (SecurityException ignored) {
            }
            cur = cur.getParentFile();
        }
    }

    /**
     * Copies at most {@code maxFileBytes} from {@code in} to {@code targetFile}, ensuring total
     * extracted bytes across the archive do not exceed {@code remainingTotalBytes}. Automatically
     * removes any partial output file on failure.
     *
     * @return number of bytes written
     */
    public static long copyBoundedToFile(@NonNull InputStream in,
                                         @NonNull File targetFile,
                                         long maxFileBytes,
                                         long remainingTotalBytes) throws IOException {
        FileUtils.ensureParentDirectory(targetFile);
        long allowed = Math.min(maxFileBytes, remainingTotalBytes);
        if (allowed < 0) {
            throw new IOException("Archive exceeds maximum total extracted size limit");
        }
        long written = 0L;
        byte[] buffer = new byte[16 * 1024];
        boolean completed = false;
        try (OutputStream out = new FileOutputStream(targetFile)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                written += n;
                if (written > allowed) {
                    throw new IOException("Archive entry exceeds safe extraction size limit: " + targetFile.getName());
                }
                out.write(buffer, 0, n);
            }
            out.flush();
            completed = true;
            return written;
        } finally {
            if (!completed && targetFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.delete();
            }
        }
    }

    /**
     * Reads up to {@code maxBytes} from an {@link InputStream} into a byte array, throwing an
     * {@link IOException} if the stream exceeds the cap.
     */
    @NonNull
    public static byte[] readBytesBounded(@NonNull InputStream in, int maxBytes) throws IOException {
        int cap = Math.max(1, maxBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, 8192));
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > cap) {
                throw new IOException("Stream exceeded maximum allowed size of " + cap + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Safely reads a UTF-8 metadata entry from a ZIP/JAR without extracting or executing anything.
     * Returns {@code null} if the entry does not exist.
     */
    @Nullable
    public static String readZipEntryTextBounded(@NonNull ZipFile zipFile,
                                                 @NonNull String entryPath,
                                                 int maxBytes) throws IOException {
        ZipEntry entry = zipFile.getEntry(entryPath);
        if (entry == null || entry.isDirectory()) return null;
        long declared = entry.getSize();
        if (declared > maxBytes) {
            throw new IOException("Metadata entry too large: " + entryPath);
        }
        try (InputStream is = zipFile.getInputStream(entry)) {
            byte[] bytes = readBytesBounded(is, maxBytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
