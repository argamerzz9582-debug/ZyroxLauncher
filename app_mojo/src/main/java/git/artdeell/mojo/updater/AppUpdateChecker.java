package git.artdeell.mojo.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * Lightweight, privacy-respecting HTTPS GitHub Release update checker for Zyrox Launcher.
 *
 * <p>Never downloads or executes APKs silently — presents verified tag, changelog summary, and
 * direct HTTPS release/APK link on explicit user confirmation.
 */
public final class AppUpdateChecker {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/argamerzz9582-debug/ZyroxLauncher/releases/latest";
    private static final String RELEASES_PAGE =
            "https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/latest";
    private static final String USER_AGENT =
            "ZyroxLauncher/" + BuildConfig.VERSION_NAME + " (Android)";

    private static final String PREF_NAME = "zyrox_update_check_v1";
    private static final String KEY_LAST_CHECK_MS = "last_check_ms";
    private static final String KEY_DISMISSED_TAG = "dismissed_tag";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 3600_000L;

    public static final class ReleaseInfo {
        @NonNull public final String tagName;
        @NonNull public final String versionClean;
        @NonNull public final String title;
        @NonNull public final String body;
        @NonNull public final String htmlUrl;
        @Nullable public final String apkDownloadUrl;
        public final long apkSizeBytes;
        public final boolean updateAvailable;

        public ReleaseInfo(@NonNull String tagName,
                           @NonNull String versionClean,
                           @NonNull String title,
                           @NonNull String body,
                           @NonNull String htmlUrl,
                           @Nullable String apkDownloadUrl,
                           long apkSizeBytes,
                           boolean updateAvailable) {
            this.tagName = tagName;
            this.versionClean = versionClean;
            this.title = title;
            this.body = body;
            this.htmlUrl = htmlUrl;
            this.apkDownloadUrl = apkDownloadUrl;
            this.apkSizeBytes = apkSizeBytes;
            this.updateAvailable = updateAvailable;
        }
    }

    public interface UpdateCallback {
        void onResult(@Nullable ReleaseInfo info, @Nullable String error);
    }

    private AppUpdateChecker() {}

    /** Compares semantic versions like {@code 2.2.0} vs {@code v2.1.0}. Returns {@code > 0} if {@code a > b}. */
    public static int compareSemVer(@Nullable String a, @Nullable String b) {
        int[] pa = parseVersionParts(a);
        int[] pb = parseVersionParts(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) {
                return Integer.compare(pa[i], pb[i]);
            }
        }
        return 0;
    }

    @NonNull
    static int[] parseVersionParts(@Nullable String raw) {
        int[] out = {0, 0, 0};
        if (raw == null) return out;
        String clean = raw.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }
        int dash = clean.indexOf('-');
        if (dash >= 0) clean = clean.substring(0, dash);
        String[] parts = clean.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                out[i] = Math.max(0, Integer.parseInt(parts[i].replaceAll("[^0-9]", "")));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Background throttled check used on Home screen (zero toast on offline/up-to-date). */
    public static void checkSilentlyIfDue(@NonNull Context ctx, @NonNull UpdateCallback cb) {
        final Context appCtx = ctx.getApplicationContext();
        SharedPreferences sp = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = sp.getLong(KEY_LAST_CHECK_MS, 0L);
        if (now - last < AUTO_CHECK_INTERVAL_MS) return;
        sp.edit().putLong(KEY_LAST_CHECK_MS, now).apply();
        String dismissed = sp.getString(KEY_DISMISSED_TAG, "");

        MojoApplication.sExecutorService.execute(() -> {
            try {
                ReleaseInfo info = fetchLatestRelease(BuildConfig.VERSION_NAME);
                if (info.updateAvailable && !info.tagName.equalsIgnoreCase(dismissed)) {
                    Tools.runOnUiThread(() -> cb.onResult(info, null));
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** Explicit user-triggered check (e.g. from Security &amp; About screen). */
    public static void checkNowInteractive(@NonNull Context ctx) {
        Toast.makeText(ctx, "Checking GitHub Releases for updates…", Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                ReleaseInfo info = fetchLatestRelease(BuildConfig.VERSION_NAME);
                Tools.runOnUiThread(() -> {
                    if (info.updateAvailable) {
                        showUpdateDialog(ctx, info);
                    } else {
                        Toast.makeText(ctx,
                                "Zyrox Launcher v" + BuildConfig.VERSION_NAME + " is up to date!",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(ctx,
                        "Could not check updates: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    public static void showUpdateDialog(@NonNull Context ctx, @NonNull ReleaseInfo info) {
        StringBuilder msg = new StringBuilder();
        msg.append("Installed: v").append(BuildConfig.VERSION_NAME)
                .append("  →  Latest: ").append(info.tagName);
        if (info.apkSizeBytes > 0) {
            msg.append(" (").append(ZyroxStudioUi.formatBytes(info.apkSizeBytes)).append(")");
        }
        String cleanNotes = info.body
                .replace("### ", "")
                .replace("## ", "")
                .replace("**", "")
                .trim();
        if (!cleanNotes.isEmpty()) {
            if (cleanNotes.length() > 520) {
                cleanNotes = cleanNotes.substring(0, 520).trim() + "…";
            }
            msg.append("\n\n").append(cleanNotes);
        }

        String targetUrl = (info.apkDownloadUrl != null && info.apkDownloadUrl.startsWith("https://"))
                ? info.apkDownloadUrl
                : info.htmlUrl;

        new AlertDialog.Builder(ctx)
                .setTitle(info.title.isEmpty() ? ("Update Available • " + info.tagName) : info.title)
                .setMessage(msg.toString())
                .setPositiveButton("Download APK", (d, w) -> Tools.openURL(ctx, targetUrl))
                .setNeutralButton("Release Notes", (d, w) -> Tools.openURL(ctx, info.htmlUrl))
                .setNegativeButton("Later", (d, w) -> ctx.getApplicationContext()
                        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_DISMISSED_TAG, info.tagName).apply())
                .show();
    }

    @NonNull
    public static ReleaseInfo fetchLatestRelease(@NonNull String currentVersion) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("GitHub API HTTP " + code);
            }
            String json;
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 1024 * 1024) break;
                    out.write(buf, 0, n);
                }
                json = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            return parseReleaseJson(json, currentVersion);
        } finally {
            conn.disconnect();
        }
    }

    @NonNull
    public static ReleaseInfo parseReleaseJson(@NonNull String json,
                                               @NonNull String currentVersion) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        String tag = opt(obj, "tag_name", "v" + currentVersion);
        String clean = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        String title = opt(obj, "name", "Zyrox Launcher " + tag);
        String body = opt(obj, "body", "");
        String html = opt(obj, "html_url", RELEASES_PAGE);
        if (!html.toLowerCase(Locale.ROOT).startsWith("https://")) {
            html = RELEASES_PAGE;
        }
        String apkUrl = null;
        long apkSize = 0L;
        if (obj.has("assets") && obj.get("assets").isJsonArray()) {
            JsonArray assets = obj.getAsJsonArray("assets");
            for (JsonElement el : assets) {
                if (!el.isJsonObject()) continue;
                JsonObject a = el.getAsJsonObject();
                String name = opt(a, "name", "");
                String url = opt(a, "browser_download_url", "");
                if (name.toLowerCase(Locale.ROOT).endsWith(".apk")
                        && url.toLowerCase(Locale.ROOT).startsWith("https://")) {
                    apkUrl = url;
                    apkSize = a.has("size") && !a.get("size").isJsonNull() ? a.get("size").getAsLong() : 0L;
                    break;
                }
            }
        }
        boolean newer = compareSemVer(clean, currentVersion) > 0;
        return new ReleaseInfo(tag, clean, title, body, html, apkUrl, apkSize, newer);
    }

    @NonNull
    private static String opt(@NonNull JsonObject o, @NonNull String k, @NonNull String def) {
        if (!o.has(k) || o.get(k).isJsonNull()) return def;
        try {
            return o.get(k).getAsString();
        } catch (Throwable ignored) {
            return def;
        }
    }
}
