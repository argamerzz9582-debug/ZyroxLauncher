package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import git.artdeell.mojo.utils.DownloadUtils;

/**
 * Thread-safe local metadata cache with atomic disk persistence, conditional HTTP validation
 * ({@code ETag} / {@code If-Modified-Since} -> {@code 304 Not Modified}), bounded exponential-backoff
 * retries, strict parse-before-cache verification, and automatic offline fallback.
 */
public class LocalMetadataCache {
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 12_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 25_000;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final long DEFAULT_REVALIDATE_COOLDOWN_MS = 2 * 60_000L; // 2 minutes
    private static final long BASE_RETRY_BACKOFF_MS = 350L;
    private static final long MAX_RETRY_DELAY_MS = 4_000L;
    private static final int MAX_METADATA_BYTES = 32 * 1024 * 1024; // 32 MB safety cap

    private static final Gson GSON = new Gson();

    public enum SyncSource {
        /** Returned from local cache within the short revalidation cooldown window. */
        FRESH_CACHE,
        /** Remote server returned HTTP 304 (or identical SHA-256); zero redundant rewrite. */
        NOT_MODIFIED,
        /** Remote server returned new or updated metadata and local cache was atomically updated. */
        UPDATED_FROM_NETWORK,
        /** Network sync failed; served verified local cached copy as offline fallback. */
        OFFLINE_CACHE_FALLBACK
    }

    public static final class SyncResult<T> {
        @NonNull public final T data;
        @NonNull public final SyncSource source;
        @Nullable public final IOException networkWarning;
        public final long fetchedAtMs;

        public SyncResult(@NonNull T data, @NonNull SyncSource source,
                          @Nullable IOException networkWarning, long fetchedAtMs) {
            this.data = data;
            this.source = source;
            this.networkWarning = networkWarning;
            this.fetchedAtMs = fetchedAtMs;
        }

        public boolean isFromNetwork() {
            return source == SyncSource.UPDATED_FROM_NETWORK || source == SyncSource.NOT_MODIFIED;
        }
    }

    public static final class CacheMeta {
        public String etag;
        public String lastModified;
        public String sha256;
        public long fetchedAtMs;
        public String sourceUrl;
    }

    public static final class HttpResponse {
        public final int statusCode;
        @Nullable public final String body;
        @Nullable public final String etag;
        @Nullable public final String lastModified;
        @Nullable public final String retryAfter;
        @Nullable public final String statusMessage;

        public HttpResponse(int statusCode, @Nullable String body, @Nullable String etag,
                            @Nullable String lastModified, @Nullable String retryAfter,
                            @Nullable String statusMessage) {
            this.statusCode = statusCode;
            this.body = body;
            this.etag = etag;
            this.lastModified = lastModified;
            this.retryAfter = retryAfter;
            this.statusMessage = statusMessage;
        }
    }

    public interface HttpTransport {
        @NonNull
        HttpResponse get(@NonNull String url, @NonNull Map<String, String> headers,
                         int connectTimeoutMs, int readTimeoutMs) throws IOException;
    }

    public interface Clock {
        long nowMs();
    }

    public interface Sleeper {
        void sleep(long delayMs) throws InterruptedException;
    }

    public interface MetadataParser<T> {
        @NonNull
        T parse(@NonNull String rawBody) throws DownloadUtils.ParseException;
    }

    private final File mCacheDir;
    private final HttpTransport mTransport;
    private final Clock mClock;
    private final Sleeper mSleeper;
    private final String mUserAgent;
    private final int mConnectTimeoutMs;
    private final int mReadTimeoutMs;
    private final int mMaxAttempts;
    private final ConcurrentHashMap<String, Object> mKeyLocks = new ConcurrentHashMap<>();

    public LocalMetadataCache(@NonNull File cacheDir) {
        this(cacheDir, new DefaultHttpTransport(), System::currentTimeMillis, Thread::sleep,
                "ZyroxLauncher/1.0.3", DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_MAX_ATTEMPTS);
    }

    public LocalMetadataCache(@NonNull File cacheDir,
                              @NonNull HttpTransport transport,
                              @NonNull Clock clock,
                              @NonNull Sleeper sleeper,
                              @NonNull String userAgent,
                              int connectTimeoutMs,
                              int readTimeoutMs,
                              int maxAttempts) {
        mCacheDir = cacheDir;
        mTransport = transport;
        mClock = clock;
        mSleeper = sleeper;
        mUserAgent = userAgent;
        mConnectTimeoutMs = Math.max(1_000, connectTimeoutMs);
        mReadTimeoutMs = Math.max(1_000, readTimeoutMs);
        mMaxAttempts = Math.max(1, maxAttempts);
    }

    @NonNull
    public File getCacheDir() {
        return mCacheDir;
    }

    /**
     * Immediately reads and parses a cached entry from local disk without performing any network I/O.
     * Returns {@code null} if no cached copy exists or if the cached file is unreadable/corrupt.
     */
    @Nullable
    public <T> T readCached(@NonNull String cacheKey, @NonNull MetadataParser<T> parser) {
        String safeKey = sanitizeKey(cacheKey);
        synchronized (lockFor(safeKey)) {
            File dataFile = resolveSafeFile(safeKey);
            if (!dataFile.isFile() || !dataFile.canRead() || dataFile.length() == 0) {
                return null;
            }
            try {
                String raw = readUtf8File(dataFile);
                if (raw.isEmpty()) return null;
                return parser.parse(raw);
            } catch (IOException | DownloadUtils.ParseException | RuntimeException ignored) {
                return null;
            }
        }
    }

    @Nullable
    public CacheMeta readMeta(@NonNull String cacheKey) {
        String safeKey = sanitizeKey(cacheKey);
        synchronized (lockFor(safeKey)) {
            return readMetaLocked(safeKey);
        }
    }

    /**
     * Fetches metadata with conditional HTTP validation, atomic caching, bounded retry, and offline fallback.
     *
     * @param cacheKey           Logical cache key (e.g. {@code "version_list"})
     * @param url                Official HTTPS metadata endpoint URL
     * @param forceRefresh       If {@code true}, bypasses cooldown and validates immediately with remote server
     *                           (still sends {@code If-None-Match} / {@code If-Modified-Since} to avoid re-downloading unchanged bodies)
     * @param revalidateAfterMs  Minimum age in milliseconds before automatic revalidation triggers
     * @param parser             Validates & parses the metadata payload
     */
    @NonNull
    public <T> SyncResult<T> sync(@NonNull String cacheKey,
                                  @NonNull String url,
                                  boolean forceRefresh,
                                  long revalidateAfterMs,
                                  @NonNull MetadataParser<T> parser) throws IOException, DownloadUtils.ParseException {
        requireHttpsUrl(url);
        final String safeKey = sanitizeKey(cacheKey);

        synchronized (lockFor(safeKey)) {
            File dataFile = resolveSafeFile(safeKey);
            CacheMeta existingMeta = readMetaLocked(safeKey);
            String cachedRaw = null;
            T cachedParsed = null;

            if (dataFile.isFile() && dataFile.canRead() && dataFile.length() > 0) {
                try {
                    String raw = readUtf8File(dataFile);
                    if (!raw.isEmpty()) {
                        cachedParsed = parser.parse(raw);
                        cachedRaw = raw;
                    }
                } catch (IOException | DownloadUtils.ParseException | RuntimeException ignored) {
                    // Corrupt cached file — force a fresh network download and overwrite it cleanly.
                    cachedRaw = null;
                    cachedParsed = null;
                    existingMeta = null;
                }
            }

            long now = mClock.nowMs();
            long cachedTimestamp = existingMeta != null && existingMeta.fetchedAtMs > 0
                    ? existingMeta.fetchedAtMs
                    : (dataFile.isFile() ? dataFile.lastModified() : 0L);

            boolean sameSource = existingMeta == null || existingMeta.sourceUrl == null
                    || url.equals(existingMeta.sourceUrl);

            if (!forceRefresh && cachedParsed != null && sameSource && cachedTimestamp > 0
                    && now >= cachedTimestamp && (now - cachedTimestamp) < revalidateAfterMs) {
                return new SyncResult<>(cachedParsed, SyncSource.FRESH_CACHE, null, cachedTimestamp);
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", mUserAgent);
            headers.put("Accept", "application/json, application/xml, text/xml, text/plain, */*");
            // Only send conditional headers when we hold a valid parsed local copy from the same URL.
            if (cachedParsed != null && existingMeta != null && sameSource) {
                if (isNonEmpty(existingMeta.etag)) {
                    headers.put("If-None-Match", existingMeta.etag);
                }
                if (isNonEmpty(existingMeta.lastModified)) {
                    headers.put("If-Modified-Since", existingMeta.lastModified);
                }
            }

            try {
                HttpResponse response = executeWithRetries(url, Collections.unmodifiableMap(headers));
                long completedAt = mClock.nowMs();

                if (response.statusCode == HttpURLConnection.HTTP_NOT_MODIFIED && cachedParsed != null) {
                    CacheMeta updatedMeta = new CacheMeta();
                    updatedMeta.etag = isNonEmpty(response.etag) ? response.etag : existingMeta.etag;
                    updatedMeta.lastModified = isNonEmpty(response.lastModified)
                            ? response.lastModified : existingMeta.lastModified;
                    updatedMeta.sha256 = isNonEmpty(existingMeta.sha256)
                            ? existingMeta.sha256 : sha256Hex(cachedRaw);
                    updatedMeta.fetchedAtMs = completedAt;
                    updatedMeta.sourceUrl = url;
                    writeMetaSilentlyLocked(safeKey, updatedMeta);
                    return new SyncResult<>(cachedParsed, SyncSource.NOT_MODIFIED, null, completedAt);
                }

                if (response.statusCode != HttpURLConnection.HTTP_OK || !isNonEmpty(response.body)) {
                    throw new IOException("Unexpected HTTP " + response.statusCode + " from " + url);
                }

                // Always parse & validate BEFORE touching disk so invalid payloads never overwrite valid cache.
                T freshParsed = parser.parse(response.body);
                String newSha = sha256Hex(response.body);
                String prevSha = (existingMeta != null && isNonEmpty(existingMeta.sha256))
                        ? existingMeta.sha256
                        : (cachedRaw != null ? sha256Hex(cachedRaw) : null);

                boolean contentUnchanged = cachedParsed != null && newSha.equals(prevSha);
                if (!contentUnchanged) {
                    writeFileAtomically(dataFile, response.body);
                }

                CacheMeta newMeta = new CacheMeta();
                newMeta.etag = response.etag;
                newMeta.lastModified = response.lastModified;
                newMeta.sha256 = newSha;
                newMeta.fetchedAtMs = completedAt;
                newMeta.sourceUrl = url;
                writeMetaSilentlyLocked(safeKey, newMeta);

                return new SyncResult<>(
                        freshParsed,
                        contentUnchanged ? SyncSource.NOT_MODIFIED : SyncSource.UPDATED_FROM_NETWORK,
                        null,
                        completedAt
                );
            } catch (IOException | DownloadUtils.ParseException error) {
                if (cachedParsed != null) {
                    IOException warning = (error instanceof IOException)
                            ? (IOException) error
                            : new IOException("Remote metadata failed validation; using cached metadata", error);
                    return new SyncResult<>(cachedParsed, SyncSource.OFFLINE_CACHE_FALLBACK, warning, cachedTimestamp);
                }
                throw error;
            }
        }
    }

    /**
     * Fetches an uncached HTTPS resource (such as a loader profile JSON or checksum file) with
     * bounded retry, timeout enforcement, and clean failure reporting.
     */
    @NonNull
    public String fetchDirect(@NonNull String url) throws IOException {
        requireHttpsUrl(url);
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", mUserAgent);
        HttpResponse response = executeWithRetries(url, Collections.unmodifiableMap(headers));
        if (response.statusCode != HttpURLConnection.HTTP_OK || response.body == null) {
            throw new IOException("Unexpected HTTP " + response.statusCode + " from " + url);
        }
        return response.body;
    }

    public void invalidate(@NonNull String cacheKey) {
        String safeKey = sanitizeKey(cacheKey);
        synchronized (lockFor(safeKey)) {
            File dataFile = resolveSafeFile(safeKey);
            File metaFile = resolveSafeFile(safeKey + ".meta.json");
            //noinspection ResultOfMethodCallIgnored
            dataFile.delete();
            //noinspection ResultOfMethodCallIgnored
            metaFile.delete();
        }
    }

    public boolean clearAll() {
        if (!mCacheDir.exists()) return true;
        boolean allDeleted = true;
        File[] children = mCacheDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    allDeleted = false;
                }
            }
        }
        return allDeleted;
    }

    @NonNull
    private HttpResponse executeWithRetries(@NonNull String url,
                                            @NonNull Map<String, String> headers) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= mMaxAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Metadata request interrupted for " + url);
            }
            try {
                HttpResponse response = mTransport.get(url, headers, mConnectTimeoutMs, mReadTimeoutMs);
                int code = response.statusCode;
                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    return response;
                }
                String detail = response.statusMessage != null ? (": " + response.statusMessage) : "";
                IOException httpErr = new IOException("HTTP " + code + detail + " from " + url);
                if (!isRetryableStatus(code) || attempt == mMaxAttempts) {
                    throw httpErr;
                }
                lastError = httpErr;
                sleepBeforeRetry(attempt, response.retryAfter);
            } catch (IOException e) {
                lastError = e;
                // Do not retry permanent client errors thrown above
                if (isNonRetryableException(e) || attempt == mMaxAttempts) {
                    throw e;
                }
                sleepBeforeRetry(attempt, null);
            }
        }
        throw lastError != null ? lastError : new IOException("Unable to fetch metadata from " + url);
    }

    private void sleepBeforeRetry(int attempt, @Nullable String retryAfterHeader) throws IOException {
        long delayMs = BASE_RETRY_BACKOFF_MS * (1L << Math.min(attempt - 1, 3));
        if (isNonEmpty(retryAfterHeader)) {
            try {
                long serverSec = Long.parseLong(retryAfterHeader.trim());
                if (serverSec > 0) {
                    delayMs = Math.max(delayMs, serverSec * 1_000L);
                }
            } catch (NumberFormatException ignored) {
                // Non-numeric Retry-After date — fall back to exponential backoff.
            }
        }
        delayMs = Math.min(delayMs, MAX_RETRY_DELAY_MS);
        try {
            mSleeper.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Metadata retry interrupted", ie);
        }
    }

    private static boolean isRetryableStatus(int code) {
        return code == HttpURLConnection.HTTP_CLIENT_TIMEOUT // 408
                || code == 429                               // Too Many Requests
                || (code >= 500 && code <= 599);             // Transient server errors
    }

    private static boolean isNonRetryableException(@NonNull IOException e) {
        String msg = e.getMessage();
        if (msg == null || !msg.startsWith("HTTP ")) return false;
        try {
            int space = msg.indexOf(' ', 5);
            int colon = msg.indexOf(':', 5);
            int end = (colon != -1 && (space == -1 || colon < space)) ? colon : space;
            int code = Integer.parseInt(end > 5 ? msg.substring(5, end) : msg.substring(5));
            return !isRetryableStatus(code);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    @Nullable
    private CacheMeta readMetaLocked(@NonNull String safeKey) {
        File metaFile = resolveSafeFile(safeKey + ".meta.json");
        if (!metaFile.isFile() || !metaFile.canRead()) return null;
        try {
            String raw = readUtf8File(metaFile);
            return GSON.fromJson(raw, CacheMeta.class);
        } catch (IOException | JsonSyntaxException | RuntimeException ignored) {
            return null;
        }
    }

    private void writeMetaSilentlyLocked(@NonNull String safeKey, @NonNull CacheMeta meta) {
        try {
            File metaFile = resolveSafeFile(safeKey + ".meta.json");
            writeFileAtomically(metaFile, GSON.toJson(meta));
        } catch (IOException ignored) {
            // Non-fatal: payload itself is safely persisted.
        }
    }

    private void writeFileAtomically(@NonNull File destination, @NonNull String content) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create metadata cache directory: " + parent);
        }
        File tempFile = new File(parent, destination.getName() + ".tmp");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(bytes);
            fos.flush();
            fos.getFD().sync();
        }
        if (!tempFile.renameTo(destination)) {
            // Windows / cross-fs fallback: delete target and retry rename, else direct overwrite.
            if (destination.exists() && !destination.delete()) {
                try (FileOutputStream fos = new FileOutputStream(destination)) {
                    fos.write(bytes);
                    fos.flush();
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
                return;
            }
            if (!tempFile.renameTo(destination)) {
                try (FileOutputStream fos = new FileOutputStream(destination)) {
                    fos.write(bytes);
                    fos.flush();
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
            }
        }
    }

    @NonNull
    private File resolveSafeFile(@NonNull String safeKey) {
        return new File(mCacheDir, safeKey);
    }

    @NonNull
    private Object lockFor(@NonNull String safeKey) {
        Object existing = mKeyLocks.get(safeKey);
        if (existing != null) return existing;
        Object created = new Object();
        Object prior = mKeyLocks.putIfAbsent(safeKey, created);
        return prior != null ? prior : created;
    }

    @NonNull
    static String sanitizeKey(@NonNull String rawKey) {
        String trimmed = rawKey.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Cache key must not be empty");
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '+') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String normalized = sb.toString();
        while (normalized.contains("..")) {
            normalized = normalized.replace("..", "_");
        }
        if (normalized.startsWith(".")) {
            normalized = "_" + normalized.substring(1);
        }
        return normalized;
    }

    public static void requireHttpsUrl(@Nullable String rawUrl) throws IOException {
        if (!isNonEmpty(rawUrl)) {
            throw new IOException("Metadata URL is missing");
        }
        URL parsed = new URL(rawUrl);
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            throw new IOException("Blocked non-HTTPS metadata URL: " + rawUrl);
        }
    }

    @NonNull
    static String sha256Hex(@NonNull String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @NonNull
    private static String readUtf8File(@NonNull File file) throws IOException {
        long len = file.length();
        if (len > MAX_METADATA_BYTES) {
            throw new IOException("Cached metadata exceeds safety limit: " + len);
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            return readStreamBounded(fis, MAX_METADATA_BYTES);
        }
    }

    @NonNull
    static String readStreamBounded(@NonNull InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Metadata response exceeds maximum allowed size");
            }
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean deleteRecursively(@NonNull File target) {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return !target.exists() || target.delete();
    }

    private static boolean isNonEmpty(@Nullable String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Default {@link HttpURLConnection}-based HTTPS transport honoring conditional headers. */
    public static final class DefaultHttpTransport implements HttpTransport {
        @NonNull
        @Override
        public HttpResponse get(@NonNull String url, @NonNull Map<String, String> headers,
                                int connectTimeoutMs, int readTimeoutMs) throws IOException {
            URL parsedUrl = new URL(url);
            if (!"https".equalsIgnoreCase(parsedUrl.getProtocol())) {
                throw new IOException("Blocked non-HTTPS request: " + url);
            }
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) parsedUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                conn.setUseCaches(false);
                conn.setDoInput(true);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        conn.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
                conn.connect();

                // Verify any redirect stayed on HTTPS
                URL finalUrl = conn.getURL();
                if (finalUrl != null && !"https".equalsIgnoreCase(finalUrl.getProtocol())) {
                    throw new IOException("Blocked insecure redirect to non-HTTPS host");
                }

                int status = conn.getResponseCode();
                String etag = conn.getHeaderField("ETag");
                String lastModified = conn.getHeaderField("Last-Modified");
                String retryAfter = conn.getHeaderField("Retry-After");
                String statusMessage = conn.getResponseMessage();

                if (status == HttpURLConnection.HTTP_OK) {
                    try (InputStream is = conn.getInputStream()) {
                        String body = readStreamBounded(is, MAX_METADATA_BYTES);
                        return new HttpResponse(status, body, etag, lastModified, retryAfter, statusMessage);
                    }
                }
                return new HttpResponse(status, null, etag, lastModified, retryAfter, statusMessage);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }
}
