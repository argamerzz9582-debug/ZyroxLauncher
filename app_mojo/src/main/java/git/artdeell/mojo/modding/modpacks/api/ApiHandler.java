package git.artdeell.mojo.modding.modpacks.api;

import android.util.ArrayMap;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import git.artdeell.mojo.Tools;

/**
 * Hardened HTTPS JSON client for official Modrinth and CurseForge APIs.
 * Enforces explicit timeouts, descriptive User-Agent identification, rate-limit ({@code HTTP 429}
 * {@code Retry-After}) handling, bounded transient retries, and safe malformed-response guards.
 */
@SuppressWarnings("unused")
public class ApiHandler {
    private static final String TAG = "ApiHandler";
    private static final String API_USER_AGENT = "ZyroxLauncher/1.0.3 (Android)";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_RETRY_MS = 450L;
    private static final long MAX_RETRY_MS = 5_000L;
    private static final Gson GSON = new Gson();

    public final String baseUrl;
    public final Map<String, String> additionalHeaders;

    public ApiHandler(String url) {
        baseUrl = url;
        additionalHeaders = null;
    }

    public ApiHandler(String url, String apiKey) {
        baseUrl = url;
        additionalHeaders = new ArrayMap<>();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            additionalHeaders.put("x-api-key", apiKey);
        }
    }

    public <T> T get(String endpoint, Class<T> tClass) {
        return getFullUrl(additionalHeaders, baseUrl + "/" + endpoint, tClass);
    }

    public <T> T get(String endpoint, HashMap<String, Object> query, Class<T> tClass) {
        return getFullUrl(additionalHeaders, baseUrl + "/" + endpoint, query, tClass);
    }

    public <T> T post(String endpoint, T body, Class<T> tClass) {
        return postFullUrl(additionalHeaders, baseUrl + "/" + endpoint, body, tClass);
    }

    public <T> T post(String endpoint, HashMap<String, Object> query, T body, Class<T> tClass) {
        return postFullUrl(additionalHeaders, baseUrl + "/" + endpoint, query, body, tClass);
    }

    public static String getRaw(String url) {
        return getRaw(null, url);
    }

    public static String getRaw(Map<String, String> headers, String url) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) return null;
            HttpURLConnection conn = null;
            try {
                URL parsed = new URL(url);
                if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
                    Log.w(TAG, "Blocked non-HTTPS API request");
                    return null;
                }
                conn = (HttpURLConnection) parsed.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", API_USER_AGENT);
                conn.setRequestProperty("Accept", "application/json");
                addHeaders(conn, headers);
                conn.connect();

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    try (InputStream inputStream = conn.getInputStream()) {
                        return Tools.read(inputStream);
                    }
                }
                if (isRetryableStatus(code) && attempt < MAX_ATTEMPTS) {
                    String retryAfter = conn.getHeaderField("Retry-After");
                    if (!sleepForRetry(attempt, retryAfter)) return null;
                    continue;
                }
                Log.w(TAG, "Provider API returned HTTP " + code + " for " + parsed.getHost());
                return null;
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    Log.w(TAG, "Provider API GET failed after " + attempt + " attempts", e);
                    return null;
                }
                if (!sleepForRetry(attempt, null)) return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    public static String postRaw(String url, String body) {
        return postRaw(null, url, body);
    }

    public static String postRaw(Map<String, String> headers, String url, String body) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) return null;
            HttpURLConnection conn = null;
            try {
                URL parsed = new URL(url);
                if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
                    Log.w(TAG, "Blocked non-HTTPS API POST request");
                    return null;
                }
                conn = (HttpURLConnection) parsed.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", API_USER_AGENT);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                addHeaders(conn, headers);
                conn.setDoOutput(true);

                byte[] input = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream outputStream = conn.getOutputStream()) {
                    outputStream.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    try (InputStream inputStream = conn.getInputStream()) {
                        return Tools.read(inputStream);
                    }
                }
                if (isRetryableStatus(code) && attempt < MAX_ATTEMPTS) {
                    String retryAfter = conn.getHeaderField("Retry-After");
                    if (!sleepForRetry(attempt, retryAfter)) return null;
                    continue;
                }
                Log.w(TAG, "Provider API POST returned HTTP " + code + " for " + parsed.getHost());
                return null;
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    Log.w(TAG, "Provider API POST failed after " + attempt + " attempts", e);
                    return null;
                }
                if (!sleepForRetry(attempt, null)) return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    private static boolean isRetryableStatus(int code) {
        return code == 408 || code == 429 || (code >= 500 && code <= 599);
    }

    private static boolean sleepForRetry(int attempt, String retryAfter) {
        long delayMs = BASE_RETRY_MS * (1L << Math.min(attempt - 1, 3));
        if (retryAfter != null && !retryAfter.trim().isEmpty()) {
            try {
                long sec = Long.parseLong(retryAfter.trim());
                if (sec > 0) delayMs = Math.max(delayMs, sec * 1_000L);
            } catch (NumberFormatException ignored) {
            }
        }
        delayMs = Math.min(delayMs, MAX_RETRY_MS);
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void addHeaders(HttpURLConnection connection, Map<String, String> headers) {
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    connection.addRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static String parseQueries(HashMap<String, Object> query) {
        if (query == null || query.isEmpty()) return "";
        StringBuilder params = new StringBuilder("?");
        for (String param : query.keySet()) {
            String value = Objects.toString(query.get(param));
            params.append(urlEncodeUTF8(param))
                    .append("=")
                    .append(urlEncodeUTF8(value))
                    .append("&");
        }
        return params.substring(0, params.length() - 1);
    }

    private static <T> T parseJsonSafe(String raw, Class<T> tClass) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return GSON.fromJson(raw, tClass);
        } catch (JsonSyntaxException e) {
            Log.w(TAG, "Malformed JSON returned by provider API", e);
            return null;
        }
    }

    public static <T> T getFullUrl(String url, Class<T> tClass) {
        return getFullUrl(null, url, tClass);
    }

    public static <T> T getFullUrl(String url, HashMap<String, Object> query, Class<T> tClass) {
        return getFullUrl(null, url, query, tClass);
    }

    public static <T> T postFullUrl(String url, T body, Class<T> tClass) {
        return postFullUrl(null, url, body, tClass);
    }

    public static <T> T postFullUrl(String url, HashMap<String, Object> query, T body, Class<T> tClass) {
        return postFullUrl(null, url, query, body, tClass);
    }

    public static <T> T getFullUrl(Map<String, String> headers, String url, Class<T> tClass) {
        return parseJsonSafe(getRaw(headers, url), tClass);
    }

    public static <T> T getFullUrl(Map<String, String> headers, String url, HashMap<String, Object> query, Class<T> tClass) {
        return getFullUrl(headers, url + parseQueries(query), tClass);
    }

    public static <T> T postFullUrl(Map<String, String> headers, String url, T body, Class<T> tClass) {
        return parseJsonSafe(postRaw(headers, url, body.toString()), tClass);
    }

    public static <T> T postFullUrl(Map<String, String> headers, String url, HashMap<String, Object> query, T body, Class<T> tClass) {
        return parseJsonSafe(postRaw(headers, url + parseQueries(query), body.toString()), tClass);
    }

    private static String urlEncodeUTF8(String input) {
        try {
            return URLEncoder.encode(input, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 is required");
        }
    }
}
