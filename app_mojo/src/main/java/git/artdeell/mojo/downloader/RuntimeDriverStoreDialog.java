package git.artdeell.mojo.downloader;

import android.content.Context;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kdt.mcgui.ProgressLayout;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.multirt.MultiRTUtils;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.vulkan.DriverManager;

/**
 * In-App Verified HTTPS OpenJDK Runtime (JRE 8 / 17 / 21 / 25) &amp; Snapdragon Adreno Turnip
 * Vulkan Driver catalog installer.
 *
 * <p>Auto-detects the device CPU ABI ({@code arm64}, {@code arm}, {@code x86_64}, {@code x86}) so
 * users can download and install compatible Java runtimes and Mesa Turnip drivers in 1 tap without
 * hunting archives externally.
 */
public final class RuntimeDriverStoreDialog {

    private static final String[] JRE_RELEASES_APIS = {
            "https://api.github.com/repos/MojoLauncher/android-openjdk-build-multiarch/releases?per_page=10",
            "https://api.github.com/repos/PojavLauncherTeam/android-openjdk-build-multiarch/releases?per_page=10"
    };
    private static final String TURNIP_RELEASES_API =
            "https://api.github.com/repos/K11MCH1/AdrenoToolsDrivers/releases?per_page=12";
    private static final String USER_AGENT =
            "ZyroxLauncher/" + BuildConfig.VERSION_NAME + " (Android)";
    private static final long MAX_DOWNLOAD_BYTES = 180L * 1024L * 1024L;

    public static final class CatalogAsset {
        @NonNull public final String displayLabel;
        @NonNull public final String installName;
        @NonNull public final String downloadUrl;
        public final long sizeBytes;
        public final boolean isDriver;

        public CatalogAsset(@NonNull String displayLabel,
                            @NonNull String installName,
                            @NonNull String downloadUrl,
                            long sizeBytes,
                            boolean isDriver) {
            this.displayLabel = displayLabel;
            this.installName = installName;
            this.downloadUrl = downloadUrl;
            this.sizeBytes = sizeBytes;
            this.isDriver = isDriver;
        }
    }

    private RuntimeDriverStoreDialog() {}

    @NonNull
    public static String detectJreArchToken() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String abi : abis) {
                if ("arm64-v8a".equalsIgnoreCase(abi)) return "arm64";
                if ("x86_64".equalsIgnoreCase(abi)) return "x86_64";
                if ("armeabi-v7a".equalsIgnoreCase(abi)) return "arm";
                if ("x86".equalsIgnoreCase(abi)) return "x86";
            }
        }
        return "arm64";
    }

    /** Opens chooser between Java Runtime Store (JRE 8/17/21+) and Adreno Turnip Driver Store. */
    public static void showHub(@NonNull Context ctx, @Nullable Runnable onInstalledUi) {
        String arch = detectJreArchToken();
        CharSequence[] options = {
                "OpenJDK Java Runtimes (JRE 8 / 17 / 21 • " + arch + ")",
                "Adreno Turnip / Mesa Vulkan Drivers (Snapdragon arm64)"
        };
        new AlertDialog.Builder(ctx)
                .setTitle("Zyrox Runtime & Driver Store")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showJreStore(ctx, onInstalledUi);
                    } else {
                        showTurnipDriverStore(ctx, onInstalledUi);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static void showJreStore(@NonNull Context ctx, @Nullable Runnable onInstalledUi) {
        final String arch = detectJreArchToken();
        Toast.makeText(ctx, "Fetching verified OpenJDK builds (" + arch + ")…", Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<CatalogAsset> items = fetchJreCatalog(arch);
                Tools.runOnUiThread(() -> presentCatalogDialog(
                        ctx, "Install OpenJDK Runtime (" + arch + ")", items, onInstalledUi));
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(ctx,
                        "Could not load JRE catalog: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    public static void showTurnipDriverStore(@NonNull Context ctx, @Nullable Runnable onInstalledUi) {
        if (!"arm64".equals(detectJreArchToken())) {
            Toast.makeText(ctx, "Turnip Vulkan drivers require a 64-bit Snapdragon Adreno device.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(ctx, "Fetching Mesa Turnip Adreno Vulkan drivers…", Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<CatalogAsset> items = fetchTurnipCatalog();
                Tools.runOnUiThread(() -> presentCatalogDialog(
                        ctx, "Install Adreno Turnip Driver", items, onInstalledUi));
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(ctx,
                        "Could not load Turnip catalog: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void presentCatalogDialog(@NonNull Context ctx,
                                             @NonNull String title,
                                             @NonNull List<CatalogAsset> items,
                                             @Nullable Runnable onInstalledUi) {
        if (items.isEmpty()) {
            Toast.makeText(ctx, "No matching packages found for this device right now.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).displayLabel;
        }
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setItems(labels, (d, which) -> installCatalogAsset(ctx, items.get(which), onInstalledUi))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void installCatalogAsset(@NonNull Context ctx,
                                            @NonNull CatalogAsset asset,
                                            @Nullable Runnable onInstalledUi) {
        if (!ProgressLayout.ensureNoOngoingTasks(ctx)) return;
        final Context appCtx = ctx.getApplicationContext();
        final String progressKey = asset.isDriver
                ? ProgressLayout.DRIVER_INSTALL : ProgressLayout.UNPACK_RUNTIME;
        Toast.makeText(appCtx, "Downloading " + asset.installName + "…", Toast.LENGTH_SHORT).show();

        MojoApplication.sExecutorService.execute(() -> {
            File cacheFile = new File(Tools.DIR_CACHE,
                    "zyrox_pkg_" + System.currentTimeMillis() + (asset.isDriver ? ".zip" : ".tar.xz"));
            try {
                ProgressLayout.setProgress(progressKey, 5, R.string.zyrox_pkg_downloading);
                downloadHttpsToFile(asset.downloadUrl, cacheFile, progressKey);
                if (asset.isDriver) {
                    String target;
                    ProgressLayout.setProgress(progressKey, 75, R.string.driver_progress_validate);
                    try (InputStream in = new FileInputStream(cacheFile)) {
                        target = DriverManager.validateDriver(in);
                    }
                    if (target == null) {
                        throw new IOException("Downloaded archive is not a compatible AdrenoTools driver");
                    }
                    ProgressLayout.setProgress(progressKey, 88, R.string.driver_progress_install);
                    try (InputStream in = new FileInputStream(cacheFile)) {
                        DriverManager.installDriverContent(target, in, true);
                    }
                } else {
                    ProgressLayout.setProgress(progressKey, 80, R.string.multirt_progress_caching);
                    try (InputStream in = new FileInputStream(cacheFile)) {
                        MultiRTUtils.installRuntimeNamed(Tools.NATIVE_LIB_DIR, in, asset.installName);
                    }
                    MultiRTUtils.postPrepare(asset.installName);
                }
                Tools.runOnUiThread(() -> {
                    Toast.makeText(appCtx, "Installed " + asset.installName + " successfully!",
                            Toast.LENGTH_LONG).show();
                    if (onInstalledUi != null) onInstalledUi.run();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(appCtx,
                        "Install failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
                ProgressLayout.clearProgress(progressKey);
            }
        });
    }

    @NonNull
    private static List<CatalogAsset> fetchJreCatalog(@NonNull String arch) throws IOException {
        IOException lastErr = null;
        for (String apiUrl : JRE_RELEASES_APIS) {
            try {
                String json = httpGetJson(apiUrl);
                List<CatalogAsset> list = parseJreReleases(json, arch);
                if (!list.isEmpty()) return list;
            } catch (IOException e) {
                lastErr = e;
            }
        }
        if (lastErr != null) throw lastErr;
        return new ArrayList<>();
    }

    @NonNull
    private static List<CatalogAsset> parseJreReleases(@NonNull String json, @NonNull String arch) {
        List<CatalogAsset> out = new ArrayList<>();
        String archSuffix = "-" + arch.toLowerCase(Locale.ROOT) + ".tar.xz";
        String altSuffix = "aarch64.tar.xz";
        JsonArray releases = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement relEl : releases) {
            if (!relEl.isJsonObject()) continue;
            JsonObject rel = relEl.getAsJsonObject();
            String relTag = optStr(rel, "tag_name", "");
            if (!rel.has("assets") || !rel.get("assets").isJsonArray()) continue;
            for (JsonElement aEl : rel.getAsJsonArray("assets")) {
                if (!aEl.isJsonObject()) continue;
                JsonObject a = aEl.getAsJsonObject();
                String name = optStr(a, "name", "");
                String lower = name.toLowerCase(Locale.ROOT);
                String url = optStr(a, "browser_download_url", "");
                if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) continue;
                boolean matches = lower.endsWith(archSuffix)
                        || ("arm64".equals(arch) && lower.endsWith(altSuffix));
                if (!matches) continue;
                long sz = a.has("size") && !a.get("size").isJsonNull() ? a.get("size").getAsLong() : 0L;
                String cleanName = name.replace(".tar.xz", "")
                        + (relTag.isEmpty() ? "" : "-" + relTag.replaceAll("[^a-zA-Z0-9._-]", ""));
                String label = name.replace(".tar.xz", "")
                        + (relTag.isEmpty() ? "" : " (" + relTag + ")")
                        + (sz > 0 ? " • " + ZyroxStudioUi.formatBytes(sz) : "");
                out.add(new CatalogAsset(label, cleanName, url, sz, false));
                if (out.size() >= 14) return out;
            }
        }
        return out;
    }

    @NonNull
    private static List<CatalogAsset> fetchTurnipCatalog() throws IOException {
        String json = httpGetJson(TURNIP_RELEASES_API);
        List<CatalogAsset> out = new ArrayList<>();
        JsonArray releases = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement relEl : releases) {
            if (!relEl.isJsonObject()) continue;
            JsonObject rel = relEl.getAsJsonObject();
            String relName = optStr(rel, "name", optStr(rel, "tag_name", "Driver"));
            if (!rel.has("assets") || !rel.get("assets").isJsonArray()) continue;
            for (JsonElement aEl : rel.getAsJsonArray("assets")) {
                if (!aEl.isJsonObject()) continue;
                JsonObject a = aEl.getAsJsonObject();
                String name = optStr(a, "name", "");
                String url = optStr(a, "browser_download_url", "");
                if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")
                        || !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
                    continue;
                }
                long sz = a.has("size") && !a.get("size").isJsonNull() ? a.get("size").getAsLong() : 0L;
                String label = relName + " (" + name + ")"
                        + (sz > 0 ? " • " + ZyroxStudioUi.formatBytes(sz) : "");
                out.add(new CatalogAsset(label, name.replace(".zip", ""), url, sz, true));
                if (out.size() >= 14) return out;
            }
        }
        return out;
    }

    private static void downloadHttpsToFile(@NonNull String httpsUrl,
                                            @NonNull File outFile,
                                            @NonNull String progressKey) throws IOException {
        if (!httpsUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("Blocked non-HTTPS URL");
        }
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(httpsUrl).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            long contentLen = conn.getContentLengthLong();
            long read = 0L;
            int lastPct = 5;
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    read += n;
                    if (read > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Archive exceeds safe limit");
                    }
                    out.write(buf, 0, n);
                    if (contentLen > 0) {
                        int pct = 5 + (int) Math.min(68L, (read * 68L) / contentLen);
                        if (pct >= lastPct + 3) {
                            lastPct = pct;
                            ProgressLayout.setProgress(progressKey, pct, R.string.zyrox_pkg_downloading);
                        }
                    }
                }
                out.getFD().sync();
            }
        } finally {
            conn.disconnect();
        }
    }

    @NonNull
    private static String httpGetJson(@NonNull String httpsUrl) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(httpsUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("GitHub API HTTP " + code);
            }
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 4 * 1024 * 1024) break;
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    @NonNull
    private static String optStr(@NonNull JsonObject o, @NonNull String k, @NonNull String def) {
        if (!o.has(k) || o.get(k).isJsonNull()) return def;
        try {
            return o.get(k).getAsString();
        } catch (Throwable ignored) {
            return def;
        }
    }
}
