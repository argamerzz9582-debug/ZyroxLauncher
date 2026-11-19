package git.artdeell.mojo.utils;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Shared HTTP/2 + TLS 1.2/1.3 connection-pooled network client for Zyrox Launcher.
 *
 * <p>Eliminates per-asset TCP + TLS handshake overhead across Mojang version/asset downloads,
 * Modrinth v2, CurseForge v1, and GitHub Turnip/driver catalogs while enforcing strict HTTPS-only
 * transport, bounded retry with {@code HTTP 429 Retry-After} respect, and HTTP range resumption.
 */
public final class ZyroxHttpClient {
    private static final String TAG = "ZyroxHttpClient";

    public static final String USER_AGENT = "ZyroxLauncher/2.7.0 (Android; arm64-v8a)";
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 12_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    public static final int MAX_IDLE_CONNECTIONS = 16;
    public static final long KEEP_ALIVE_MINUTES = 5L;
    public static final int MAX_CONCURRENT_REQUESTS = 32;
    public static final int MAX_CONCURRENT_PER_HOST = 12;

    private static final MediaType MEDIA_JSON = MediaType.get("application/json; charset=utf-8");
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 400L;
    private static final long MAX_BACKOFF_MS = 5_000L;

    private static volatile OkHttpClient sSharedClient;
    private static volatile OkHttpClient sBulkDownloadClient;

    private ZyroxHttpClient() {
    }

    /** Returns the shared HTTP/2 pooled client configured for JSON/API metadata requests. */
    @NonNull
    public static OkHttpClient apiClient() {
        OkHttpClient local = sSharedClient;
        if (local == null) {
            synchronized (ZyroxHttpClient.class) {
                local = sSharedClient;
                if (local == null) {
                    Dispatcher dispatcher = new Dispatcher();
                    dispatcher.setMaxRequests(MAX_CONCURRENT_REQUESTS);
                    dispatcher.setMaxRequestsPerHost(MAX_CONCURRENT_PER_HOST);

                    ConnectionPool pool = new ConnectionPool(
                            MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES);

                    local = new OkHttpClient.Builder()
                            .dispatcher(dispatcher)
                            .connectionPool(pool)
                            .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .writeTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(true)
                            .followRedirects(true)
                            .followSslRedirects(false)
                            .build();
                    sSharedClient = local;
                }
            }
        }
        return local;
    }

    /**
     * Returns a sibling client sharing the exact same HTTP/2 {@link ConnectionPool} and
     * {@link Dispatcher}, tuned with extended read timeouts for large game JAR / asset / driver streams.
     */
    @NonNull
    public static OkHttpClient downloadClient() {
        OkHttpClient local = sBulkDownloadClient;
        if (local == null) {
            synchronized (ZyroxHttpClient.class) {
                local = sBulkDownloadClient;
                if (local == null) {
                    local = apiClient().newBuilder()
                            .connectTimeout(15_000, TimeUnit.MILLISECONDS)
                            .readTimeout(45_000, TimeUnit.MILLISECONDS)
                            .build();
                    sBulkDownloadClient = local;
                }
            }
        }
        return local;
    }

    /** Returns {@code true} only for well-formed {@code https://} URLs with a non-empty host. */
    public static boolean isStrictHttpsUrl(@Nullable String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return false;
        try {
            URL parsed = new URL(trimmed);
            return "https".equalsIgnoreCase(parsed.getProtocol())
                    && parsed.getHost() != null
                    && !parsed.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the current active/idle HTTP/2 pooled socket count for diagnostics. */
    public static int getPooledConnectionCount() {
        OkHttpClient client = sSharedClient;
        return client != null ? client.connectionPool().connectionCount() : 0;
    }

    /**
     * Executes an HTTPS GET returning the UTF-8 JSON/text body, or {@code null} on failure after
     * bounded retry (honoring {@code HTTP 429 Retry-After}).
     */
    @Nullable
    public static String getString(@NonNull String url, @Nullable Map<String, String> headers) {
        if (!isStrictHttpsUrl(url)) {
            Log.w(TAG, "Blocked non-HTTPS GET request");
            return null;
        }
        for (int attempt = 1; attempt <= DEFAULT_MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) return null;
            Request.Builder req = new Request.Builder()
                    .url(url.trim())
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json");
            applyHeaders(req, headers);
            try (Response resp = apiClient().newCall(req.build()).execute()) {
                if (!"https".equalsIgnoreCase(resp.request().url().scheme())) {
                    Log.w(TAG, "Blocked HTTPS->HTTP downgrade redirect");
                    return null;
                }
                int code = resp.code();
                if (code >= 200 && code < 300) {
                    ResponseBody body = resp.body();
                    return body != null ? body.string() : "";
                }
                if (isRetryableStatus(code) && attempt < DEFAULT_MAX_ATTEMPTS) {
                    if (!sleepBackoff(attempt, resp.header("Retry-After"))) return null;
                    continue;
                }
                Log.w(TAG, "HTTPS GET returned HTTP " + code + " for " + resp.request().url().host());
                return null;
            } catch (IOException e) {
                if (attempt >= DEFAULT_MAX_ATTEMPTS) {
                    Log.w(TAG, "HTTPS GET failed after " + attempt + " attempts", e);
                    return null;
                }
                if (!sleepBackoff(attempt, null)) return null;
            }
        }
        return null;
    }

    /**
     * Strict variant of {@link #getString(String, Map)} that throws {@link IOException} on network
     * or non-2xx HTTP errors (used by downloaders that trigger mirror failover on error).
     */
    @NonNull
    public static String getStringOrThrow(@NonNull String url,
                                          @Nullable Map<String, String> headers) throws IOException {
        if (!isStrictHttpsUrl(url)) {
            throw new IOException("Blocked non-HTTPS URL");
        }
        Request.Builder req = new Request.Builder()
                .url(url.trim())
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, */*");
        applyHeaders(req, headers);
        try (Response resp = apiClient().newCall(req.build()).execute()) {
            if (!"https".equalsIgnoreCase(resp.request().url().scheme())) {
                throw new IOException("Blocked insecure redirect to HTTP");
            }
            if (!resp.isSuccessful()) {
                throw new HttpStatusException(resp.code(),
                        "HTTP " + resp.code() + " from " + resp.request().url().host());
            }
            ResponseBody body = resp.body();
            return body != null ? body.string() : "";
        }
    }

    /** Executes an HTTPS POST with a UTF-8 JSON body and bounded transient retry. */
    @Nullable
    public static String postJson(@NonNull String url,
                                  @NonNull String jsonBody,
                                  @Nullable Map<String, String> headers) {
        if (!isStrictHttpsUrl(url)) {
            Log.w(TAG, "Blocked non-HTTPS POST request");
            return null;
        }
        RequestBody body = RequestBody.create(jsonBody, MEDIA_JSON);
        for (int attempt = 1; attempt <= DEFAULT_MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) return null;
            Request.Builder req = new Request.Builder()
                    .url(url.trim())
                    .post(body)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json");
            applyHeaders(req, headers);
            try (Response resp = apiClient().newCall(req.build()).execute()) {
                if (!"https".equalsIgnoreCase(resp.request().url().scheme())) {
                    return null;
                }
                int code = resp.code();
                if (code >= 200 && code < 300) {
                    ResponseBody respBody = resp.body();
                    return respBody != null ? respBody.string() : "";
                }
                if (isRetryableStatus(code) && attempt < DEFAULT_MAX_ATTEMPTS) {
                    if (!sleepBackoff(attempt, resp.header("Retry-After"))) return null;
                    continue;
                }
                Log.w(TAG, "HTTPS POST returned HTTP " + code + " for " + resp.request().url().host());
                return null;
            } catch (IOException e) {
                if (attempt >= DEFAULT_MAX_ATTEMPTS) {
                    Log.w(TAG, "HTTPS POST failed after " + attempt + " attempts", e);
                    return null;
                }
                if (!sleepBackoff(attempt, null)) return null;
            }
        }
        return null;
    }

    /**
     * Opens a pooled HTTP/2 download {@link Response} with optional byte-range resumption.
     * Caller MUST close the returned {@link Response}.
     */
    @NonNull
    public static Response openDownloadResponse(@NonNull String url,
                                                long resumeOffsetBytes) throws IOException {
        if (!isStrictHttpsUrl(url)) {
            throw new IOException("Blocked insecure download URL");
        }
        Request.Builder req = new Request.Builder()
                .url(url.trim())
                .get()
                .header("User-Agent", USER_AGENT);
        if (resumeOffsetBytes > 0L) {
            req.header("Range", "bytes=" + resumeOffsetBytes + "-");
        }
        Response resp = downloadClient().newCall(req.build()).execute();
        if (!"https".equalsIgnoreCase(resp.request().url().scheme())) {
            resp.close();
            throw new IOException("Blocked insecure redirect to HTTP");
        }
        return resp;
    }

    /**
     * Opens a pooled HTTP/2 bounded byte-range {@link Response} ({@code Range: bytes=start-end}).
     * Caller MUST close the returned {@link Response}.
     */
    @NonNull
    public static Response openRangeResponse(@NonNull String url,
                                             long startByte,
                                             long endByteInclusive) throws IOException {
        if (!isStrictHttpsUrl(url)) {
            throw new IOException("Blocked insecure download URL");
        }
        Request.Builder req = new Request.Builder()
                .url(url.trim())
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=" + startByte + "-" + endByteInclusive);
        Response resp = downloadClient().newCall(req.build()).execute();
        if (!"https".equalsIgnoreCase(resp.request().url().scheme())) {
            resp.close();
            throw new IOException("Blocked insecure redirect to HTTP");
        }
        return resp;
    }

    /** Executes a fast pooled HTTP/2 {@code HEAD} probe returning {@code Content-Length}, or {@code -1}. */
    public static long headContentLength(@NonNull String url) throws IOException {
        if (!isStrictHttpsUrl(url)) return -1L;
        Request req = new Request.Builder()
                .url(url.trim())
                .head()
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response resp = apiClient().newCall(req).execute()) {
            if (!resp.isSuccessful()) return -1L;
            String len = resp.header("Content-Length");
            if (len == null || len.trim().isEmpty()) return -1L;
            try {
                return Long.parseLong(len.trim());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }
    }

    /** Convenience wrapper returning the raw HTTPS response {@link InputStream}. */
    @NonNull
    public static InputStream openStream(@NonNull String url) throws IOException {
        Response resp = openDownloadResponse(url, 0L);
        if (!resp.isSuccessful()) {
            int code = resp.code();
            resp.close();
            throw new HttpStatusException(code, "HTTP " + code + " opening stream");
        }
        ResponseBody body = resp.body();
        if (body == null) {
            resp.close();
            throw new IOException("Empty HTTP response body");
        }
        return body.byteStream();
    }

    /** Typed HTTPS text response surfacing HTTP status &amp; {@code Retry-After} for fallback UI. */
    public static final class StatusTextResponse {
        public final int statusCode;
        @Nullable public final String body;
        public final long retryAfterMs;

        public StatusTextResponse(int statusCode, @Nullable String body, long retryAfterMs) {
            this.statusCode = statusCode;
            this.body = body;
            this.retryAfterMs = Math.max(0L, retryAfterMs);
        }

        public boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300 && body != null;
        }

        public boolean isRateLimitedOrForbidden() {
            return statusCode == 401 || statusCode == 403 || statusCode == 429;
        }
    }

    /**
     * Executes an HTTPS GET returning status code, UTF-8 body, and parsed {@code Retry-After}
     * cooldown so callers (such as {@code CurseForgeProvider}) can gracefully switch to fallback
     * providers on rate limits or invalid API keys without stalling or crashing.
     */
    @NonNull
    public static StatusTextResponse getStringWithStatus(@NonNull String url,
                                                         @Nullable Map<String, String> headers) {
        if (!isStrictHttpsUrl(url)) {
            Log.w(TAG, "Blocked non-HTTPS GET request");
            return new StatusTextResponse(-1, null, 0L);
        }
        int lastCode = -1;
        long lastRetryAfterMs = 0L;
        for (int attempt = 1; attempt <= DEFAULT_MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                return new StatusTextResponse(lastCode, null, lastRetryAfterMs);
            }
            Request.Builder req = new Request.Builder()
                    .url(url.trim())
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json");
            applyHeaders(req, headers);
            try (Response resp = apiClient().newCall(req.build()).execute()) {
                if (!"https".equalsIgnoreCase(resp.request().url().scheme())) {
                    Log.w(TAG, "Blocked HTTPS->HTTP downgrade redirect");
                    return new StatusTextResponse(-1, null, 0L);
                }
                int code = resp.code();
                lastCode = code;
                lastRetryAfterMs = parseRetryAfterMs(resp.header("Retry-After"));
                if (code >= 200 && code < 300) {
                    ResponseBody body = resp.body();
                    return new StatusTextResponse(code, body != null ? body.string() : "", 0L);
                }
                if (code == 401 || code == 403 || (code == 429 && lastRetryAfterMs > MAX_BACKOFF_MS)) {
                    return new StatusTextResponse(code, null, lastRetryAfterMs);
                }
                if (isRetryableStatus(code) && attempt < DEFAULT_MAX_ATTEMPTS) {
                    if (!sleepBackoff(attempt, resp.header("Retry-After"))) {
                        return new StatusTextResponse(code, null, lastRetryAfterMs);
                    }
                    continue;
                }
                return new StatusTextResponse(code, null, lastRetryAfterMs);
            } catch (IOException e) {
                if (attempt >= DEFAULT_MAX_ATTEMPTS) {
                    Log.w(TAG, "HTTPS GET with status failed after " + attempt + " attempts", e);
                    return new StatusTextResponse(lastCode, null, lastRetryAfterMs);
                }
                if (!sleepBackoff(attempt, null)) {
                    return new StatusTextResponse(lastCode, null, lastRetryAfterMs);
                }
            }
        }
        return new StatusTextResponse(lastCode, null, lastRetryAfterMs);
    }

    /**
     * Fetches an HTTPS HTML document with bounded timeout, automatic retry, and strict redirect
     * scheme validation (used by resilient {@code OptiFineScraper} / {@code OFDownloadPageScraper}).
     */
    @NonNull
    public static String getHtmlOrThrow(@NonNull String url) throws IOException {
        String normalized = url.trim();
        if (normalized.startsWith("http://optifine.net/")
                || normalized.startsWith("http://www.optifine.net/")) {
            normalized = "https://" + normalized.substring("http://".length());
        }
        if (!isStrictHttpsUrl(normalized)) {
            throw new IOException("Blocked non-HTTPS scrape URL");
        }
        IOException lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Request req = new Request.Builder()
                    .url(normalized)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .build();
            try (Response resp = apiClient().newCall(req).execute()) {
                if (!"https".equalsIgnoreCase(resp.request().url().scheme())) {
                    throw new IOException("Blocked insecure redirect to HTTP");
                }
                if (resp.isSuccessful()) {
                    ResponseBody body = resp.body();
                    String html = body != null ? body.string() : "";
                    if (!html.trim().isEmpty()) {
                        return html;
                    }
                    throw new IOException("Empty HTML response from " + resp.request().url().host());
                }
                lastErr = new HttpStatusException(resp.code(),
                        "HTTP " + resp.code() + " from " + resp.request().url().host());
                if (!isRetryableStatus(resp.code()) || attempt >= 2) {
                    throw lastErr;
                }
                if (!sleepBackoff(attempt, resp.header("Retry-After"))) {
                    throw lastErr;
                }
            } catch (IOException e) {
                lastErr = e;
                if (attempt >= 2 || !sleepBackoff(attempt, null)) {
                    throw lastErr;
                }
            }
        }
        throw lastErr != null ? lastErr : new IOException("Failed to download HTML page");
    }

    /** Parses an HTTP {@code Retry-After} header (seconds) into clamped milliseconds. */
    public static long parseRetryAfterMs(@Nullable String retryAfterHeader) {
        if (retryAfterHeader == null || retryAfterHeader.trim().isEmpty()) return 0L;
        try {
            long secs = Long.parseLong(retryAfterHeader.trim());
            if (secs > 0L) {
                return Math.min(300_000L, secs * 1000L);
            }
        } catch (NumberFormatException ignored) {
        }
        return 0L;
    }

    /**
     * RFC 3986 UTF-8 query-component encoder (encodes brackets, quotes, commas, colons, and spaces
     * as {@code %20} so strict API gateways like Modrinth v2 never reject query arrays).
     */
    @NonNull
    public static String encodeQueryComponent(@NonNull String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("%7E", "~");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 always available", e);
        }
    }

    /**
     * Formats non-blank strings into a valid JSON string array literal (e.g. {@code ["1.21.4"]}).
     */
    @NonNull
    public static String toJsonStringArray(@Nullable String... items) {
        StringBuilder sb = new StringBuilder("[");
        if (items != null) {
            boolean first = true;
            for (String raw : items) {
                if (raw == null) continue;
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) continue;
                if (!first) sb.append(',');
                sb.append('"');
                for (int i = 0; i < trimmed.length(); i++) {
                    char c = trimmed.charAt(i);
                    if (c == '\\' || c == '"') {
                        sb.append('\\').append(c);
                    } else if (c >= 0x20) {
                        sb.append(c);
                    }
                }
                sb.append('"');
                first = false;
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Builds a properly JSON-formatted and UTF-8 URL-encoded Modrinth v2
     * {@code /project/{id|slug}/version?game_versions=["1.21.4"]&loaders=["fabric"]} endpoint URL.
     */
    @NonNull
    public static String buildModrinthProjectVersionsUrl(@NonNull String apiBaseUrl,
                                                         @NonNull String projectIdOrSlug,
                                                         @Nullable String cleanMcVersion,
                                                         @Nullable String... loaders) {
        String normalizedBase = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl;
        StringBuilder url = new StringBuilder(normalizedBase)
                .append("/project/")
                .append(encodeQueryComponent(projectIdOrSlug.trim()))
                .append("/version");
        char sep = '?';
        if (cleanMcVersion != null && !cleanMcVersion.trim().isEmpty()) {
            url.append(sep)
                    .append("game_versions=")
                    .append(encodeQueryComponent(toJsonStringArray(cleanMcVersion.trim())));
            sep = '&';
        }
        String loaderJson = toJsonStringArray(loaders);
        if (!"[]".equals(loaderJson)) {
            url.append(sep)
                    .append("loaders=")
                    .append(encodeQueryComponent(loaderJson));
        }
        return url.toString();
    }

    public static boolean isRetryableStatus(int code) {
        return code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    private static void applyHeaders(@NonNull Request.Builder builder,
                                     @Nullable Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue();
            if (k != null && !k.isEmpty() && v != null) {
                builder.header(k, v);
            }
        }
    }

    private static boolean sleepBackoff(int attempt, @Nullable String retryAfterHeader) {
        long delayMs = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << Math.max(0, attempt - 1)));
        if (retryAfterHeader != null) {
            try {
                long secs = Long.parseLong(retryAfterHeader.trim());
                if (secs > 0) delayMs = Math.min(MAX_BACKOFF_MS, secs * 1000L);
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Typed {@link IOException} carrying the upstream HTTP status code for mirror failover logic. */
    public static final class HttpStatusException extends IOException {
        public final int statusCode;

        public HttpStatusException(int statusCode, @NonNull String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
