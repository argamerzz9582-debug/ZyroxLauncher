package git.artdeell.mojo.updater;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.tasks.SpeedCalculator;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.ZyroxHttpClient;
import okhttp3.Request;

/**
 * GitHub Releases In-App Auto-Updater for Zyrox Launcher.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Asynchronous startup version check with ETag conditional caching (rate-limit safe),
 *       "Later" snooze cooldown, and persistent "Skip Version" preference.</li>
 *   <li>ABI-matched APK asset discovery ({@code arm64-v8a}, {@code armeabi-v7a}, {@code x86_64},
 *       {@code x86}, or universal {@code .apk}) constrained strictly to verified GitHub HTTPS hosts.</li>
 *   <li>Markdown changelog formatter converting headings, bold spans, inline code, links, and lists
 *       into clean structured bullet points for {@link AppUpdateDialogFragment}.</li>
 *   <li>Streaming download to {@code getExternalCacheDir()/updates/} with live {@link SpeedCalculator}
 *       throughput, optional SHA-256 verification (GitHub {@code digest}, companion {@code .sha256}
 *       asset, or embedded release checksum), package-ID verification, Android 8+
 *       {@code REQUEST_INSTALL_PACKAGES} gating, and {@link FileProvider} installation.</li>
 * </ul>
 */
public final class AppUpdateChecker {

    public static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/argamerzz9582-debug/ZyroxLauncher/releases/latest";
    public static final String RELEASES_PAGE =
            "https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/latest";
    public static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    public static final String FILE_PROVIDER_SUFFIX = ".fileprovider";

    private static final String PREF_NAME = "zyrox_update_check_v1";
    private static final String KEY_LAST_CHECK_MS = "last_check_ms";
    private static final String KEY_DISMISSED_TAG = "dismissed_tag";
    private static final String KEY_SNOOZE_UNTIL_MS = "snooze_until_ms";
    private static final String KEY_CACHED_ETAG = "cached_etag";
    private static final String KEY_CACHED_JSON = "cached_release_json";

    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 3600_000L;
    private static final long LATER_SNOOZE_INTERVAL_MS = 12L * 3600_000L;
    private static final long MAX_APK_DOWNLOAD_BYTES = 320L * 1024L * 1024L;
    private static final int MAX_CHECKSUM_TEXT_BYTES = 64 * 1024;

    private static final Pattern SHA256_HEX_PATTERN =
            Pattern.compile("(?i)\\b([0-9a-f]{64})\\b");
    private static final Pattern BODY_APK_SHA256_PATTERN = Pattern.compile(
            "(?i)(?:apk\\s*sha-?256|sha256\\s*=|sha-?256\\s*\\(apk\\))\\s*[:=]?\\s*`?([0-9a-f]{64})`?");
    private static final Pattern MD_LINK_PATTERN =
            Pattern.compile("\\[([^\\]]+)]\\([^)]+\\)");
    private static final Pattern MD_BOLD_PATTERN =
            Pattern.compile("\\*\\*([^*]+)\\*\\*");

    /** Immutable descriptor for a parsed GitHub release and its best matching APK asset. */
    public static final class ReleaseInfo {
        @NonNull public final String tagName;
        @NonNull public final String versionClean;
        @NonNull public final String title;
        @NonNull public final String body;
        @NonNull public final String htmlUrl;
        @Nullable public final String apkDownloadUrl;
        @NonNull public final String apkAssetName;
        @NonNull public final String matchedAbi;
        public final long apkSizeBytes;
        @Nullable public final String expectedSha256;
        @Nullable public final String checksumAssetUrl;
        public final boolean updateAvailable;

        public ReleaseInfo(@NonNull String tagName,
                           @NonNull String versionClean,
                           @NonNull String title,
                           @NonNull String body,
                           @NonNull String htmlUrl,
                           @Nullable String apkDownloadUrl,
                           long apkSizeBytes,
                           boolean updateAvailable) {
            this(tagName, versionClean, title, body, htmlUrl, apkDownloadUrl,
                    "ZyroxLauncher.apk", "universal", apkSizeBytes, null, null, updateAvailable);
        }

        public ReleaseInfo(@NonNull String tagName,
                           @NonNull String versionClean,
                           @NonNull String title,
                           @NonNull String body,
                           @NonNull String htmlUrl,
                           @Nullable String apkDownloadUrl,
                           @NonNull String apkAssetName,
                           @NonNull String matchedAbi,
                           long apkSizeBytes,
                           @Nullable String expectedSha256,
                           @Nullable String checksumAssetUrl,
                           boolean updateAvailable) {
            this.tagName = tagName;
            this.versionClean = versionClean;
            this.title = title;
            this.body = body;
            this.htmlUrl = htmlUrl;
            this.apkDownloadUrl = apkDownloadUrl;
            this.apkAssetName = apkAssetName;
            this.matchedAbi = matchedAbi;
            this.apkSizeBytes = apkSizeBytes;
            this.expectedSha256 = expectedSha256;
            this.checksumAssetUrl = checksumAssetUrl;
            this.updateAvailable = updateAvailable;
        }
    }

    /** Callback for release metadata discovery. */
    public interface UpdateCallback {
        void onResult(@Nullable ReleaseInfo info, @Nullable String error);
    }

    /** Callback for live streaming in-app APK download progress and verification. */
    public interface ApkDownloadListener {
        void onProgress(long downloadedBytes, long totalBytes, double bytesPerSec,
                        @NonNull String phaseLabel);
        void onCompleted(@NonNull File verifiedApkFile, boolean sha256Verified);
        void onCancelled();
        void onFailed(@NonNull String errorMessage);
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
        String preA = extractPreRelease(a);
        String preB = extractPreRelease(b);
        if (preA.isEmpty() && !preB.isEmpty()) return 1;
        if (!preA.isEmpty() && preB.isEmpty()) return -1;
        return preA.compareToIgnoreCase(preB);
    }

    @NonNull
    static int[] parseVersionParts(@Nullable String raw) {
        int[] out = {0, 0, 0};
        if (raw == null) return out;
        String clean = stripVersionPrefix(raw);
        int dash = clean.indexOf('-');
        if (dash >= 0) clean = clean.substring(0, dash);
        int plus = clean.indexOf('+');
        if (plus >= 0) clean = clean.substring(0, plus);
        String[] parts = clean.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                String digits = parts[i].replaceAll("[^0-9]", "");
                out[i] = digits.isEmpty() ? 0 : Math.max(0, Integer.parseInt(digits));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    @NonNull
    private static String extractPreRelease(@Nullable String raw) {
        if (raw == null) return "";
        String clean = stripVersionPrefix(raw);
        int plus = clean.indexOf('+');
        if (plus >= 0) clean = clean.substring(0, plus);
        int dash = clean.indexOf('-');
        return (dash >= 0 && dash + 1 < clean.length()) ? clean.substring(dash + 1) : "";
    }

    @NonNull
    private static String stripVersionPrefix(@NonNull String raw) {
        String clean = raw.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1).trim();
        }
        return clean;
    }

    /** Persists the user's explicit "Skip Version" choice so auto-checks stay silent for {@code tag}. */
    public static void skipVersion(@NonNull Context ctx, @NonNull String tagName) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DISMISSED_TAG, tagName.trim())
                .apply();
    }

    /** Snoozes automatic update prompts for 12 hours when the user taps "Later". */
    public static void snoozeUpdatePrompt(@NonNull Context ctx) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_SNOOZE_UNTIL_MS, System.currentTimeMillis() + LATER_SNOOZE_INTERVAL_MS)
                .apply();
    }

    /** Returns {@code true} if {@code tagName} was permanently skipped by the user. */
    public static boolean isVersionSkipped(@NonNull Context ctx, @NonNull String tagName) {
        String skipped = ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DISMISSED_TAG, "");
        return skipped != null && skipped.equalsIgnoreCase(tagName.trim());
    }

    /** Background rate-limit-safe check used on startup (zero toast on offline or up-to-date). */
    public static void checkSilentlyIfDue(@NonNull Context ctx, @NonNull UpdateCallback cb) {
        final Context appCtx = ctx.getApplicationContext();
        final SharedPreferences sp = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        if (now < sp.getLong(KEY_SNOOZE_UNTIL_MS, 0L)) {
            return;
        }
        long last = sp.getLong(KEY_LAST_CHECK_MS, 0L);
        final String skipped = sp.getString(KEY_DISMISSED_TAG, "");
        if (now - last < AUTO_CHECK_INTERVAL_MS) {
            return;
        }
        sp.edit().putLong(KEY_LAST_CHECK_MS, now).apply();

        MojoApplication.sExecutorService.execute(() -> {
            try {
                ReleaseInfo info = fetchLatestReleaseCached(sp, BuildConfig.VERSION_NAME, false);
                if (info != null && info.updateAvailable
                        && !info.tagName.equalsIgnoreCase(skipped != null ? skipped : "")) {
                    Tools.runOnUiThread(() -> cb.onResult(info, null));
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** Explicit user-triggered update check (bypasses snooze/skip so manual checks always work). */
    public static void checkNowInteractive(@NonNull Context ctx) {
        Toast.makeText(ctx, "Checking GitHub Releases for updates…", Toast.LENGTH_SHORT).show();
        final Context appCtx = ctx.getApplicationContext();
        final SharedPreferences sp = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        MojoApplication.sExecutorService.execute(() -> {
            try {
                ReleaseInfo info = fetchLatestReleaseCached(sp, BuildConfig.VERSION_NAME, true);
                Tools.runOnUiThread(() -> {
                    if (info != null && info.updateAvailable) {
                        showUpdateDialog(ctx, info);
                    } else {
                        Toast.makeText(appCtx,
                                "Zyrox Launcher v" + BuildConfig.VERSION_NAME + " is up to date!",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(appCtx,
                        "Could not check updates: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Displays the Liquid Glass {@link AppUpdateDialogFragment} (with safe AlertDialog fallback). */
    public static void showUpdateDialog(@NonNull Context ctx, @NonNull ReleaseInfo info) {
        if (ctx instanceof FragmentActivity) {
            FragmentActivity activity = (FragmentActivity) ctx;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            FragmentManager fm = activity.getSupportFragmentManager();
            if (!fm.isStateSaved()) {
                if (fm.findFragmentByTag(AppUpdateDialogFragment.TAG) != null) {
                    return;
                }
                AppUpdateDialogFragment.newInstance(info).show(fm, AppUpdateDialogFragment.TAG);
                return;
            }
        }
        // Fallback when invoked with a non-FragmentActivity context
        String targetUrl = isTrustedGitHubDownloadUrl(info.apkDownloadUrl)
                ? info.apkDownloadUrl : info.htmlUrl;
        new AlertDialog.Builder(ctx)
                .setTitle(info.title.isEmpty() ? ("Update Available • " + info.tagName) : info.title)
                .setMessage("v" + BuildConfig.VERSION_NAME + " → " + info.tagName + "\n\n"
                        + formatChangelogPlainText(info.body))
                .setPositiveButton("Update Now", (d, w) -> openHttpsUrl(ctx, targetUrl))
                .setNeutralButton("Skip Version", (d, w) -> skipVersion(ctx, info.tagName))
                .setNegativeButton("Later", (d, w) -> snoozeUpdatePrompt(ctx))
                .show();
    }

    /** Opens an HTTPS release page strictly over TLS. */
    public static void openHttpsUrl(@NonNull Context ctx, @Nullable String url) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("https://")) return;
        try {
            if (ctx instanceof android.app.Activity) {
                Tools.openURL((android.app.Activity) ctx, url);
            } else {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private static ReleaseInfo fetchLatestReleaseCached(@NonNull SharedPreferences sp,
                                                        @NonNull String currentVersion,
                                                        boolean manualForce) throws IOException {
        Request.Builder reqBuilder = new Request.Builder()
                .url(LATEST_RELEASE_API)
                .get()
                .header("User-Agent", ZyroxHttpClient.USER_AGENT)
                .header("Accept", "application/vnd.github+json");
        String cachedEtag = sp.getString(KEY_CACHED_ETAG, null);
        String cachedJson = sp.getString(KEY_CACHED_JSON, null);
        if (!manualForce && cachedEtag != null && !cachedEtag.isEmpty()
                && cachedJson != null && !cachedJson.isEmpty()) {
            reqBuilder.header("If-None-Match", cachedEtag);
        }

        try (okhttp3.Response res = ZyroxHttpClient.apiClient().newCall(reqBuilder.build()).execute()) {
            int code = res.code();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && cachedJson != null) {
                return parseReleaseJson(cachedJson, currentVersion, getDeviceAbis());
            }
            if (code == 403 || code == 429) {
                if (cachedJson != null && !cachedJson.isEmpty()) {
                    return parseReleaseJson(cachedJson, currentVersion, getDeviceAbis());
                }
                throw new IOException("GitHub Releases API rate limit reached — try again shortly");
            }
            okhttp3.ResponseBody rb = res.body();
            String bodyText = rb != null ? rb.string() : "";
            if (!res.isSuccessful() || bodyText.trim().isEmpty()) {
                throw new IOException("GitHub API HTTP " + code);
            }
            SharedPreferences.Editor ed = sp.edit().putString(KEY_CACHED_JSON, bodyText);
            String newEtag = res.header("ETag");
            if (newEtag != null && !newEtag.isEmpty()) {
                ed.putString(KEY_CACHED_ETAG, newEtag);
            }
            ed.apply();
            return parseReleaseJson(bodyText, currentVersion, getDeviceAbis());
        }
    }

    /** Direct uncached GitHub Releases lookup against {@link #LATEST_RELEASE_API}. */
    @NonNull
    public static ReleaseInfo fetchLatestRelease(@NonNull String currentVersion) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        ZyroxHttpClient.StatusTextResponse res =
                ZyroxHttpClient.getStringWithStatus(LATEST_RELEASE_API, headers);
        if (!res.isSuccessful() || res.body == null || res.body.trim().isEmpty()) {
            throw new IOException("GitHub API HTTP " + res.statusCode);
        }
        return parseReleaseJson(res.body, currentVersion, getDeviceAbis());
    }

    /** Parses a GitHub release JSON document using the current device ABI priority list. */
    @NonNull
    public static ReleaseInfo parseReleaseJson(@NonNull String json,
                                               @NonNull String currentVersion) {
        return parseReleaseJson(json, currentVersion, getDeviceAbis());
    }

    /**
     * Parses a GitHub release JSON payload and selects the highest-priority ABI-compatible APK
     * asset plus optional SHA-256 verification metadata.
     */
    @NonNull
    public static ReleaseInfo parseReleaseJson(@NonNull String json,
                                               @NonNull String currentVersion,
                                               @Nullable String[] preferredAbis) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        String tag = opt(obj, "tag_name", "v" + currentVersion).trim();
        String clean = stripVersionPrefix(tag);
        String title = opt(obj, "name", "Zyrox Launcher " + tag).trim();
        if (title.isEmpty()) title = "Zyrox Launcher " + tag;
        String body = opt(obj, "body", "");
        String html = opt(obj, "html_url", RELEASES_PAGE);
        if (!html.toLowerCase(Locale.ROOT).startsWith("https://")) {
            html = RELEASES_PAGE;
        }

        String bestApkUrl = null;
        String bestApkName = "ZyroxLauncher.apk";
        String bestAbi = "universal";
        long bestApkSize = 0L;
        String bestDigestSha256 = null;
        int bestScore = -1;
        String checksumAssetUrl = null;

        String[] abis = (preferredAbis != null && preferredAbis.length > 0)
                ? preferredAbis : new String[]{"arm64-v8a", "armeabi-v7a"};

        if (obj.has("assets") && obj.get("assets").isJsonArray()) {
            JsonArray assets = obj.getAsJsonArray("assets");
            for (JsonElement el : assets) {
                if (!el.isJsonObject()) continue;
                JsonObject a = el.getAsJsonObject();
                String name = opt(a, "name", "").trim();
                String url = opt(a, "browser_download_url", "").trim();
                if (!isTrustedGitHubDownloadUrl(url) || name.isEmpty()) continue;

                String lowName = name.toLowerCase(Locale.ROOT);
                if (lowName.endsWith(".sha256")
                        || lowName.endsWith(".sha256.txt")
                        || lowName.contains("sha256sums")
                        || lowName.contains("checksum")) {
                    if (checksumAssetUrl == null || lowName.contains(bestApkName.toLowerCase(Locale.ROOT))) {
                        checksumAssetUrl = url;
                    }
                    continue;
                }
                if (!lowName.endsWith(".apk")) continue;

                String assetAbi = detectApkAbiTag(lowName);
                int score = scoreAbiCandidate(assetAbi, abis);
                if (score > bestScore) {
                    bestScore = score;
                    bestApkUrl = url;
                    bestApkName = name;
                    bestAbi = assetAbi;
                    bestApkSize = (a.has("size") && !a.get("size").isJsonNull())
                            ? Math.max(0L, a.get("size").getAsLong()) : 0L;
                    bestDigestSha256 = parseGitHubAssetDigest(opt(a, "digest", ""));
                }
            }
        }

        if (bestDigestSha256 == null) {
            bestDigestSha256 = extractApkSha256FromBody(body);
        }

        boolean isDraft = obj.has("draft") && !obj.get("draft").isJsonNull()
                && obj.get("draft").getAsBoolean();
        boolean newer = !isDraft && compareSemVer(clean, currentVersion) > 0;
        return new ReleaseInfo(tag, clean, title, body, html, bestApkUrl, bestApkName,
                bestAbi, bestApkSize, bestDigestSha256, checksumAssetUrl, newer);
    }

    /** Detects an explicit ABI qualifier inside an APK filename, or {@code "universal"}. */
    @NonNull
    public static String detectApkAbiTag(@NonNull String fileName) {
        String low = fileName.toLowerCase(Locale.ROOT);
        if (low.contains("arm64-v8a") || low.contains("arm64_v8a") || low.contains("aarch64")) {
            return "arm64-v8a";
        }
        if (low.contains("armeabi-v7a") || low.contains("armeabi_v7a") || low.contains("armv7")) {
            return "armeabi-v7a";
        }
        if (low.contains("x86_64") || low.contains("x86-64") || low.contains("amd64")) {
            return "x86_64";
        }
        if (low.contains("-x86.") || low.contains("_x86.") || low.contains("-x86-")) {
            return "x86";
        }
        return "universal";
    }

    private static int scoreAbiCandidate(@NonNull String assetAbi, @NonNull String[] deviceAbis) {
        for (int i = 0; i < deviceAbis.length; i++) {
            if (assetAbi.equalsIgnoreCase(deviceAbis[i])) {
                return 100 - (i * 10);
            }
        }
        return "universal".equals(assetAbi) ? 50 : -1;
    }

    @Nullable
    private static String parseGitHubAssetDigest(@NonNull String rawDigest) {
        String trimmed = rawDigest.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("sha256:") && trimmed.length() == 7 + 64) {
            String hex = trimmed.substring(7);
            if (SHA256_HEX_PATTERN.matcher(hex).matches()) {
                return hex;
            }
        }
        return null;
    }

    @Nullable
    static String extractApkSha256FromBody(@Nullable String body) {
        if (body == null || body.isEmpty()) return null;
        Matcher m = BODY_APK_SHA256_PATTERN.matcher(body);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Parses a checksum file ({@code <sha256>  <filename>} or single hash) matching {@code apkName}.
     */
    @Nullable
    public static String extractChecksumForAsset(@Nullable String checksumText,
                                                 @NonNull String apkName) {
        if (checksumText == null || checksumText.trim().isEmpty()) return null;
        String targetLow = apkName.toLowerCase(Locale.ROOT);
        String singleCandidate = null;
        int lineMatches = 0;
        for (String line : checksumText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            // Ignore signing-certificate fingerprint lines so we only match APK file digests
            String lowLine = trimmed.toLowerCase(Locale.ROOT);
            if (lowLine.contains("cert") || lowLine.contains("certificate")
                    || lowLine.contains("fingerprint")) {
                continue;
            }
            Matcher hm = SHA256_HEX_PATTERN.matcher(trimmed);
            if (hm.find()) {
                String hex = hm.group(1).toLowerCase(Locale.ROOT);
                if (lowLine.contains(targetLow) || lowLine.contains(".apk")) {
                    return hex;
                }
                singleCandidate = hex;
                lineMatches++;
            }
        }
        return lineMatches == 1 ? singleCandidate : null;
    }

    /** Validates that a download URL points strictly to official GitHub HTTPS release hosts. */
    public static boolean isTrustedGitHubDownloadUrl(@Nullable String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return false;
        try {
            URL u = new URL(rawUrl.trim());
            if (!"https".equalsIgnoreCase(u.getProtocol())) return false;
            String host = u.getHost().toLowerCase(Locale.ROOT);
            return "github.com".equals(host)
                    || host.endsWith(".githubusercontent.com")
                    || host.endsWith(".githubassets.com");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parses a GitHub Markdown changelog into structured readable lines (section headers &amp;
     * clean bullet points), stripping code-fence checksum blocks and raw Markdown syntax.
     */
    @NonNull
    public static List<String> parseChangelogLines(@Nullable String markdownBody) {
        if (markdownBody == null || markdownBody.trim().isEmpty()) {
            return Collections.singletonList(
                    "• Performance improvements, stability fixes, and latest Minecraft support.");
        }
        List<String> out = new ArrayList<>();
        boolean inCodeFence = false;
        boolean skipChecksumSection = false;

        for (String rawLine : markdownBody.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("```")) {
                inCodeFence = !inCodeFence;
                continue;
            }
            if (inCodeFence || line.isEmpty() || line.equals("---") || line.equals("***")) {
                continue;
            }
            if (line.startsWith("#")) {
                String heading = line.replaceFirst("^#{1,6}\\s*", "").trim();
                String lowH = heading.toLowerCase(Locale.ROOT);
                skipChecksumSection = lowH.contains("sha-256")
                        || lowH.contains("signing")
                        || lowH.contains("download & upgrade");
                if (!skipChecksumSection && !heading.isEmpty()) {
                    out.add("§H§" + cleanInlineMarkdown(heading));
                }
                continue;
            }
            if (skipChecksumSection) continue;

            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
                out.add("• " + cleanInlineMarkdown(line.substring(2).trim()));
            } else if (line.matches("^\\d+\\.\\s+.*")) {
                out.add("• " + cleanInlineMarkdown(line.replaceFirst("^\\d+\\.\\s+", "")));
            } else {
                out.add(cleanInlineMarkdown(line));
            }
        }
        if (out.isEmpty()) {
            out.add("• Performance improvements, stability fixes, and security updates.");
        }
        return out;
    }

    /** Formats a GitHub Markdown changelog into a rich styled {@link CharSequence} for UI display. */
    @NonNull
    public static CharSequence formatMarkdownChangelog(@Nullable String markdownBody) {
        List<String> lines = parseChangelogLines(markdownBody);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean isHeader = line.startsWith("§H§");
            if (isHeader) {
                line = line.substring(3);
                if (sb.length() > 0) sb.append("\n");
                int start = sb.length();
                sb.append(line);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(0xFFF6D37A), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("\n");
                continue;
            }
            appendBoldAwareLine(sb, line);
            if (i < lines.size() - 1) {
                sb.append("\n");
            }
        }
        return sb;
    }

    /** Plain-text changelog formatter (JVM-test-friendly and used for fallback dialogs). */
    @NonNull
    public static String formatChangelogPlainText(@Nullable String markdownBody) {
        List<String> lines = parseChangelogLines(markdownBody);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            String clean = line.startsWith("§H§") ? line.substring(3) : line;
            sb.append(clean.replace("**", ""));
        }
        return sb.toString();
    }

    private static void appendBoldAwareLine(@NonNull SpannableStringBuilder sb,
                                            @NonNull String line) {
        Matcher m = MD_BOLD_PATTERN.matcher(line);
        int cursor = 0;
        while (m.find()) {
            if (m.start() > cursor) {
                sb.append(line, cursor, m.start());
            }
            int boldStart = sb.length();
            sb.append(m.group(1));
            sb.setSpan(new StyleSpan(Typeface.BOLD), boldStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFFF8FBFF), boldStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            cursor = m.end();
        }
        if (cursor < line.length()) {
            sb.append(line.substring(cursor).replace("**", ""));
        }
    }

    @NonNull
    private static String cleanInlineMarkdown(@NonNull String text) {
        String unlinked = MD_LINK_PATTERN.matcher(text).replaceAll("$1");
        return unlinked.replace("`", "").trim();
    }

    /** Resolves {@code getExternalCacheDir()/updates/} with safe fallback to internal cache. */
    @NonNull
    public static File resolveUpdatesCacheDirectory(@NonNull Context context) throws IOException {
        File base = context.getExternalCacheDir();
        if (base == null || (!base.exists() && !base.mkdirs()) || !base.canWrite()) {
            base = context.getCacheDir();
        }
        File updatesDir = new File(base, "updates");
        FileUtils.ensureDirectory(updatesDir);
        return updatesDir;
    }

    /**
     * Streams the release APK asynchronously into {@code getExternalCacheDir()/updates/} with
     * live speed calculation, SHA-256 verification (when available), and package-name validation.
     */
    public static void downloadUpdateApkAsync(@NonNull Context context,
                                              @NonNull ReleaseInfo release,
                                              @NonNull AtomicBoolean cancelSignal,
                                              @NonNull ApkDownloadListener listener) {
        final Context appCtx = context.getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            File partFile = null;
            try {
                if (!isTrustedGitHubDownloadUrl(release.apkDownloadUrl)) {
                    Tools.runOnUiThread(() ->
                            listener.onFailed("No trusted HTTPS APK asset found in this release."));
                    return;
                }
                File updatesDir = resolveUpdatesCacheDirectory(appCtx);
                String safeVersion = FileUtils.escapeFileName(release.versionClean);
                File finalApk = new File(updatesDir, "ZyroxLauncher-" + safeVersion + ".apk");
                pruneStaleUpdateFiles(updatesDir, finalApk.getName());

                // Resolve expected SHA-256 from release metadata or companion .sha256 asset
                String expectedSha256 = release.expectedSha256;
                if (expectedSha256 == null && isTrustedGitHubDownloadUrl(release.checksumAssetUrl)) {
                    try {
                        ZyroxHttpClient.StatusTextResponse sumRes =
                                ZyroxHttpClient.getStringWithStatus(release.checksumAssetUrl, null);
                        if (sumRes.isSuccessful() && sumRes.body != null
                                && sumRes.body.length() <= MAX_CHECKSUM_TEXT_BYTES) {
                            expectedSha256 = extractChecksumForAsset(sumRes.body, release.apkAssetName);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                // Instant reuse if identical verified APK was already downloaded in this session
                if (finalApk.isFile() && finalApk.length() > 65536L
                        && expectedSha256 != null
                        && expectedSha256.equalsIgnoreCase(computeFileSha256(finalApk))
                        && verifyDownloadedApkArchive(appCtx, finalApk)) {
                    Tools.runOnUiThread(() -> listener.onCompleted(finalApk, true));
                    return;
                }

                partFile = new File(updatesDir, finalApk.getName() + ".part");
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                SpeedCalculator speedCalc = new SpeedCalculator(24);
                long downloaded = 0L;
                try (okhttp3.Response res = ZyroxHttpClient.openDownloadResponse(
                        release.apkDownloadUrl, null)) {
                    okhttp3.ResponseBody body = res.body();
                    if (!res.isSuccessful() || body == null) {
                        throw new IOException("APK download failed (HTTP " + res.code() + ")");
                    }
                    long contentLength = body.contentLength();
                    final long totalBytes = contentLength > 0 ? contentLength : release.apkSizeBytes;
                    if (totalBytes > MAX_APK_DOWNLOAD_BYTES) {
                        throw new IOException("APK exceeds safe size limit");
                    }

                    long lastUiUpdateMs = 0L;
                    try (InputStream in = new BufferedInputStream(body.byteStream(), 32768);
                         BufferedOutputStream out = new BufferedOutputStream(
                                 new FileOutputStream(partFile, false), 32768)) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (cancelSignal.get()) {
                                //noinspection ResultOfMethodCallIgnored
                                partFile.delete();
                                Tools.runOnUiThread(listener::onCancelled);
                                return;
                            }
                            downloaded += n;
                            if (downloaded > MAX_APK_DOWNLOAD_BYTES) {
                                throw new IOException("Download exceeded safe size cap");
                            }
                            out.write(buf, 0, n);
                            sha256.update(buf, 0, n);

                            long now = System.currentTimeMillis();
                            if (now - lastUiUpdateMs >= 110L || downloaded == totalBytes) {
                                lastUiUpdateMs = now;
                                final long snapBytes = downloaded;
                                final double rawSpeed = speedCalc.feed(snapBytes);
                                final double safeSpeed = (Double.isNaN(rawSpeed)
                                        || Double.isInfinite(rawSpeed) || rawSpeed < 0d)
                                        ? 0d : rawSpeed;
                                final String label = totalBytes > 0
                                        ? ("Downloading " + ZyroxStudioUi.formatBytes(snapBytes)
                                            + " / " + ZyroxStudioUi.formatBytes(totalBytes))
                                        : ("Downloading " + ZyroxStudioUi.formatBytes(snapBytes));
                                Tools.runOnUiThread(() ->
                                        listener.onProgress(snapBytes, totalBytes, safeSpeed, label));
                            }
                        }
                        out.flush();
                    }
                    if (totalBytes > 0 && downloaded != totalBytes) {
                        throw new IOException("Incomplete download (" + downloaded + "/" + totalBytes + " B)");
                    }
                }

                Tools.runOnUiThread(() ->
                        listener.onProgress(1000L, 1000L, 0d, "Verifying package integrity…"));

                String actualSha256 = toLowerHex(sha256.digest());
                boolean checksumVerified = false;
                if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                    if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                        //noinspection ResultOfMethodCallIgnored
                        partFile.delete();
                        throw new IOException("SHA-256 verification mismatch — aborted corrupted update");
                    }
                    checksumVerified = true;
                }

                if (finalApk.exists() && !finalApk.delete()) {
                    throw new IOException("Could not replace previous cached update APK");
                }
                if (!partFile.renameTo(finalApk)) {
                    throw new IOException("Could not finalize downloaded update APK");
                }
                partFile = null;

                if (!verifyDownloadedApkArchive(appCtx, finalApk)) {
                    //noinspection ResultOfMethodCallIgnored
                    finalApk.delete();
                    throw new IOException("Downloaded file is not a valid Zyrox Launcher package");
                }

                final boolean verifiedFlag = checksumVerified;
                Tools.runOnUiThread(() -> listener.onCompleted(finalApk, verifiedFlag));
            } catch (Exception e) {
                if (partFile != null && partFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    partFile.delete();
                }
                final String msg = e.getMessage() != null ? e.getMessage() : "Download failed";
                Tools.runOnUiThread(() -> listener.onFailed(msg));
            }
        });
    }

    /** Verifies Android 8.0+ {@code REQUEST_INSTALL_PACKAGES} permission state. */
    public static boolean canInstallPackages(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /** Creates the system settings intent allowing this app to install its own verified updates. */
    @NonNull
    public static Intent createInstallPermissionSettingsIntent(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
        }
        return new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }

    /**
     * Launches the Android system package installer for a verified local update APK via
     * {@link FileProvider} ({@code application/vnd.android.package-archive}).
     */
    public static void launchApkInstaller(@NonNull Context context, @NonNull File apkFile)
            throws IOException {
        if (!apkFile.isFile() || apkFile.length() <= 0L) {
            throw new IOException("Update APK file not found on storage");
        }
        String authority = context.getPackageName() + FILE_PROVIDER_SUFFIX;
        Uri contentUri = FileProvider.getUriForFile(context, authority, apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(contentUri, APK_MIME_TYPE);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(installIntent);
    }

    /** Formats download throughput in human-readable {@code MB/s} or {@code KB/s}. */
    @NonNull
    public static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec <= 1024d) return "";
        if (bytesPerSec >= 1024d * 1024d) {
            return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / (1024d * 1024d));
        }
        return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSec / 1024d);
    }

    private static boolean verifyDownloadedApkArchive(@NonNull Context context,
                                                      @NonNull File apkFile) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pkgInfo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? pm.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                        PackageManager.PackageInfoFlags.of(0))
                    : pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (pkgInfo == null || pkgInfo.packageName == null) {
                return false;
            }
            return context.getPackageName().equals(pkgInfo.packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void pruneStaleUpdateFiles(@NonNull File updatesDir, @NonNull String keepName) {
        File[] existing = updatesDir.listFiles();
        if (existing == null) return;
        for (File f : existing) {
            if (f.isFile() && !keepName.equals(f.getName())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    @NonNull
    private static String computeFileSha256(@NonNull File file)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(file), 32768)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return toLowerHex(md.digest());
    }

    @NonNull
    private static String toLowerHex(@NonNull byte[] digest) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int v = digest[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    @NonNull
    private static String[] getDeviceAbis() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0) {
            return abis;
        }
        return new String[]{"arm64-v8a", "armeabi-v7a"};
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
