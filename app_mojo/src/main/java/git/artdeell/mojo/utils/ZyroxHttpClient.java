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

    public static final String USER_AGENT = "ZyroxLauncher/2.3.0 (Android; arm64-v8a)";
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
