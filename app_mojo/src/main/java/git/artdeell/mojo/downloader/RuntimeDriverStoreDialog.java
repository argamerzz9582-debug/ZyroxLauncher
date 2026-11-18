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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.diagnostics.GraphicsAdvisor;
import git.artdeell.mojo.game.renderer.impl.MesaRenderSpec;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.multirt.MultiRTUtils;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.utils.GpuUtils.GpuArchitecture;
import git.artdeell.mojo.utils.ZyroxHttpClient;
import git.artdeell.mojo.vulkan.DriverManager;
import git.artdeell.mojo.vulkan.driver.AdrenoDriver;
import git.artdeell.mojo.vulkan.driver.BuiltinDriver;
import git.artdeell.mojo.vulkan.driver.Driver;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Verified HTTPS OpenJDK Runtime (JRE 8 / 17 / 21 / 25) &amp; One-Click Qualcomm Adreno Turnip
 * ({@code .adpkg} / {@code .adpkg.zip} / {@code .zip}) Vulkan Driver Store, Version Manager, and
 * Per-Instance GPU Driver Assignment hub.
 */
public final class RuntimeDriverStoreDialog {

    private static final String[] JRE_RELEASES_APIS = {
            "https://api.github.com/repos/MojoLauncher/android-openjdk-build-multiarch/releases?per_page=10",
            "https://api.github.com/repos/PojavLauncherTeam/android-openjdk-build-multiarch/releases?per_page=10"
    };

    /** Official community AdrenoTools Turnip (.adpkg / .zip) release catalogs with automatic failover. */
    private static final String[] TURNIP_RELEASES_APIS = {
            "https://api.github.com/repos/K11MCH1/AdrenoToolsDrivers/releases?per_page=15",
            "https://api.github.com/repos/MrPurple666/purple-turnip/releases?per_page=12"
    };

    private static final long MAX_DOWNLOAD_BYTES = 180L * 1024L * 1024L;

    public static final class CatalogAsset {
        @NonNull public final String displayLabel;
        @NonNull public final String installName;
        @NonNull public final String downloadUrl;
        public final long sizeBytes;
        public final boolean isDriver;
        public final boolean recommendedForGpu;

        public CatalogAsset(@NonNull String displayLabel,
                            @NonNull String installName,
                            @NonNull String downloadUrl,
                            long sizeBytes,
                            boolean isDriver) {
            this(displayLabel, installName, downloadUrl, sizeBytes, isDriver, false);
        }

        public CatalogAsset(@NonNull String displayLabel,
                            @NonNull String installName,
                            @NonNull String downloadUrl,
                            long sizeBytes,
                            boolean isDriver,
                            boolean recommendedForGpu) {
            this.displayLabel = displayLabel;
            this.installName = installName;
            this.downloadUrl = downloadUrl;
            this.sizeBytes = sizeBytes;
            this.isDriver = isDriver;
            this.recommendedForGpu = recommendedForGpu;
        }
    }

    private RuntimeDriverStoreDialog() {
    }

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

    /** Convenience entry point using the currently selected instance. */
    public static void show(@NonNull Context ctx) {
        showHub(ctx, null);
    }

    /** Opens the full Runtime, Turnip (.adpkg) Store, Version Manager &amp; GPU Auto-Preset Hub. */
    public static void showHub(@NonNull Context ctx, @Nullable Runnable onInstalledUi) {
        Instance selected = null;
        try {
            selected = Instances.loadSelectedInstance();
        } catch (Throwable ignored) {
        }
        showForInstance(ctx, selected, onInstalledUi);
    }

    /**
     * Opens the Runtime &amp; Turnip Driver Hub scoped to {@code targetInstance} so newly
     * downloaded or already-installed {@code .adpkg} drivers can be assigned in a single tap.
     */
    public static void showForInstance(@NonNull Context ctx,
                                       @Nullable Instance targetInstance,
                                       @Nullable Runnable onUpdatedUi) {
        String arch = detectJreArchToken();
        GpuUtils.GLInfo gl = GpuUtils.getGlInfo();
        int installedCount = DriverManager.getDriverPaths().size();
        Driver globalDrv = DriverManager.getPreferredDriver();

        List<String> options = new ArrayList<>(5);
        options.add("1-Click Download Adreno Turnip (.adpkg) Drivers");
        options.add("Manage Installed Turnip Versions (" + installedCount
                + " installed • Active: " + globalDrv.getName() + ")");
        if (targetInstance != null) {
            String instName = Tools.isValidString(targetInstance.name)
                    ? targetInstance.name : targetInstance.versionId;
            options.add("Assign GPU Driver to \"" + instName + "\"");
        }
        options.add("Auto-Apply Optimal GPU Preset (" + gl.architecture.displayName + ")");
        options.add("OpenJDK Java Runtimes (JRE 8 / 17 / 21 / 25 • " + arch + ")");
        options.add("Purge Mesa / Zink / Turnip Shader Cache");

        new AlertDialog.Builder(ctx)
                .setTitle("Zyrox GPU Driver (.adpkg) & Runtime Store")
                .setItems(options.toArray(new CharSequence[0]), (d, which) -> {
                    int idx = which;
                    if (targetInstance == null && idx >= 2) idx++;
                    switch (idx) {
                        case 0:
                            showTurnipDriverStore(ctx, targetInstance, onUpdatedUi);
                            break;
                        case 1:
                            showInstalledDriverManager(ctx, targetInstance, onUpdatedUi);
                            break;
                        case 2:
                            if (targetInstance != null) {
                                showInstanceDriverPicker(ctx, targetInstance, onUpdatedUi);
                            }
                            break;
                        case 3: {
                            GraphicsAdvisor.RendererPreset preset =
                                    GraphicsAdvisor.applyOptimalPreset(
                                            ctx, targetInstance,
                                            targetInstance != null ? targetInstance.versionId : null);
                            Toast.makeText(ctx, preset.presetName + " applied!",
                                    Toast.LENGTH_LONG).show();
                            if (onUpdatedUi != null) onUpdatedUi.run();
                            break;
                        }
                        case 4:
                            showJreStore(ctx, onUpdatedUi);
                            break;
                        case 5: {
                            long freed = MesaRenderSpec.purgeShaderCaches();
                            Toast.makeText(ctx, "Cleared "
                                            + ZyroxStudioUi.formatBytes(freed)
                                            + " of cached Mesa/Turnip shaders.",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static void showJreStore(@NonNull Context ctx, @Nullable Runnable onInstalledUi) {
        final String arch = detectJreArchToken();
        Toast.makeText(ctx, "Fetching verified OpenJDK builds (" + arch + ")…",
                Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<CatalogAsset> items = fetchJreCatalog(arch);
                Tools.runOnUiThread(() -> presentCatalogDialog(
                        ctx, "Install OpenJDK Runtime (" + arch + ")", items, null, onInstalledUi));
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(ctx,
                        "Could not load JRE catalog: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    public static void showTurnipDriverStore(@NonNull Context ctx, @Nullable Runnable onInstalledUi) {
        showTurnipDriverStore(ctx, null, onInstalledUi);
    }

    public static void showTurnipDriverStore(@NonNull Context ctx,
                                             @Nullable Instance targetInstance,
                                             @Nullable Runnable onInstalledUi) {
        if (!DriverManager.isSupportedByDevice() || !"arm64".equals(detectJreArchToken())) {
            Toast.makeText(ctx,
                    "Turnip .adpkg Vulkan drivers require Android 9+ on 64-bit arm64 Snapdragon Adreno.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final GpuArchitecture gpuArch = GpuUtils.getGlInfo().architecture;
        Toast.makeText(ctx, "Fetching Adreno Turnip (.adpkg) catalog…",
                Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<CatalogAsset> items = fetchTurnipCatalog(gpuArch);
                Tools.runOnUiThread(() -> presentCatalogDialog(
                        ctx, "1-Click Adreno Turnip (.adpkg) Drivers",
                        items, targetInstance, onInstalledUi));
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(ctx,
                        "Could not load Turnip catalog: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Interactive version manager for all installed Adreno Turnip drivers: activate globally,
     * assign to an instance, inspect version/API metadata, or uninstall old driver revisions.
     */
    public static void showInstalledDriverManager(@NonNull Context ctx,
                                                  @Nullable Instance targetInstance,
                                                  @Nullable Runnable onChangedUi) {
        List<Driver> drivers = DriverManager.getDrivers();
        CharSequence[] rows = new CharSequence[drivers.size()];
        for (int i = 0; i < drivers.size(); i++) {
            Driver drv = drivers.get(i);
            StringBuilder row = new StringBuilder();
            if (DriverManager.isPreferredDriver(drv)) {
                row.append("[ACTIVE] ");
            }
            if (targetInstance != null && targetInstance.hasCustomVulkanDriver()) {
                String tok = targetInstance.getVulkanDriver();
                if ((drv instanceof BuiltinDriver
                        && DriverManager.SYSTEM_DRIVER_TOKEN.equalsIgnoreCase(tok))
                        || (drv.getHash() != null && drv.getHash().equals(tok))) {
                    row.append("[INSTANCE] ");
                }
            }
            row.append(DriverManager.formatDriverLabel(drv));
            long disk = DriverManager.getDriverDiskBytes(drv);
            if (disk > 0L) {
                row.append(" • ").append(ZyroxStudioUi.formatBytes(disk));
            }
            rows[i] = row.toString();
        }

        new AlertDialog.Builder(ctx)
                .setTitle("Installed Vulkan / Turnip Drivers")
                .setItems(rows, (d, which) -> showSingleDriverActions(
                        ctx, drivers.get(which), targetInstance, onChangedUi))
                .setNeutralButton("+ Download .adpkg",
                        (d, w) -> showTurnipDriverStore(ctx, targetInstance, onChangedUi))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showSingleDriverActions(@NonNull Context ctx,
                                                @NonNull Driver driver,
                                                @Nullable Instance targetInstance,
                                                @Nullable Runnable onChangedUi) {
        boolean isBuiltin = driver instanceof BuiltinDriver;
        List<String> actions = new ArrayList<>(4);
        actions.add("Set as Global Active Vulkan Driver");
        if (targetInstance != null) {
            String instName = Tools.isValidString(targetInstance.name)
                    ? targetInstance.name : targetInstance.versionId;
            actions.add("Assign Only to Instance \"" + instName + "\"");
        }
        if (!isBuiltin) {
            actions.add("Uninstall This Driver Version");
        }

        StringBuilder subtitle = new StringBuilder();
        if (driver instanceof AdrenoDriver) {
            AdrenoDriver ad = (AdrenoDriver) driver;
            if (Tools.isValidString(ad.author)) subtitle.append("Author: ").append(ad.author).append('\n');
            if (Tools.isValidString(ad.driverVersion)) subtitle.append("Version: ").append(ad.driverVersion).append('\n');
            if (Tools.isValidString(ad.libraryName)) subtitle.append("Library: ").append(ad.libraryName).append('\n');
            if (Tools.isValidString(ad.description)) subtitle.append(ad.description);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle(DriverManager.formatDriverLabel(driver))
                .setItems(actions.toArray(new CharSequence[0]), (d, which) -> {
                    if (which == 0) {
                        DriverManager.setPreferredDriver(driver);
                        Toast.makeText(ctx, "Global driver set: " + DriverManager.formatDriverLabel(driver),
                                Toast.LENGTH_SHORT).show();
                        if (onChangedUi != null) onChangedUi.run();
                    } else if (targetInstance != null && which == 1) {
                        targetInstance.vulkanDriver = isBuiltin
                                ? DriverManager.SYSTEM_DRIVER_TOKEN : driver.getHash();
                        targetInstance.maybeWrite();
                        Toast.makeText(ctx, "Assigned to instance: "
                                        + DriverManager.formatDriverLabel(driver),
                                Toast.LENGTH_SHORT).show();
                        if (onChangedUi != null) onChangedUi.run();
                    } else if (!isBuiltin) {
                        boolean ok = DriverManager.removeDriver(driver.getHash());
                        if (ok && targetInstance != null
                                && driver.getHash() != null
                                && driver.getHash().equals(targetInstance.vulkanDriver)) {
                            targetInstance.vulkanDriver = null;
                            targetInstance.maybeWrite();
                        }
                        Toast.makeText(ctx, ok ? "Removed " + driver.getName() : "Could not remove driver",
                                Toast.LENGTH_SHORT).show();
                        if (onChangedUi != null) onChangedUi.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (subtitle.length() > 0) {
            // Note: AlertDialog cannot show message + items simultaneously on AppCompat, so keep title concise.
            b.setTitle(DriverManager.formatDriverLabel(driver));
        }
        b.show();
    }

    public static void showInstanceDriverPicker(@NonNull Context ctx,
                                                @NonNull Instance instance,
                                                @Nullable Runnable onChangedUi) {
        List<Driver> drivers = DriverManager.getDrivers();
        String[] labels = new String[drivers.size() + 1];
        String[] tokens = new String[drivers.size() + 1];
        labels[0] = "Inherit Global Default ("
                + DriverManager.formatDriverLabel(DriverManager.getPreferredDriver()) + ")";
        tokens[0] = null;
        int checked = 0;

        for (int i = 0; i < drivers.size(); i++) {
            Driver drv = drivers.get(i);
            String tok = (drv instanceof BuiltinDriver)
                    ? DriverManager.SYSTEM_DRIVER_TOKEN : drv.getHash();
            tokens[i + 1] = tok;
            labels[i + 1] = DriverManager.formatDriverLabel(drv);
            if (instance.hasCustomVulkanDriver() && tok != null
                    && tok.equalsIgnoreCase(instance.vulkanDriver)) {
                checked = i + 1;
            }
        }

        new AlertDialog.Builder(ctx)
                .setTitle("GPU Driver — "
                        + (Tools.isValidString(instance.name) ? instance.name : instance.versionId))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    instance.vulkanDriver = tokens[which];
                    instance.maybeWrite();
                    Toast.makeText(ctx, "Instance GPU driver: " + labels[which],
                            Toast.LENGTH_SHORT).show();
                    if (onChangedUi != null) onChangedUi.run();
                    dialog.dismiss();
                })
                .setNeutralButton("+ Get Turnip .adpkg",
                        (d, w) -> showTurnipDriverStore(ctx, instance, onChangedUi))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void presentCatalogDialog(@NonNull Context ctx,
                                             @NonNull String title,
                                             @NonNull List<CatalogAsset> items,
                                             @Nullable Instance targetInstance,
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
                .setItems(labels, (d, which) -> installCatalogAsset(
                        ctx, items.get(which), targetInstance, onInstalledUi))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void installCatalogAsset(@NonNull Context ctx,
                                            @NonNull CatalogAsset asset,
                                            @Nullable Instance targetInstance,
                                            @Nullable Runnable onInstalledUi) {
        if (!ProgressLayout.ensureNoOngoingTasks(ctx)) return;
        final Context appCtx = ctx.getApplicationContext();
        final String progressKey = asset.isDriver
                ? ProgressLayout.DRIVER_INSTALL : ProgressLayout.UNPACK_RUNTIME;
        Toast.makeText(appCtx, "Downloading " + asset.installName + "…", Toast.LENGTH_SHORT).show();

        MojoApplication.sExecutorService.execute(() -> {
            String ext = asset.isDriver
                    ? (asset.installName.toLowerCase(Locale.ROOT).endsWith(".adpkg") ? ".adpkg" : ".zip")
                    : ".tar.xz";
            File cacheFile = new File(Tools.DIR_CACHE,
                    "zyrox_pkg_" + System.currentTimeMillis() + ext);
            try {
                ProgressLayout.setProgress(progressKey, 5, R.string.zyrox_pkg_downloading);
                downloadHttpsToFile(asset.downloadUrl, cacheFile, progressKey);

                String installedDriverHash = null;
                if (asset.isDriver) {
                    ProgressLayout.setProgress(progressKey, 75, R.string.driver_progress_validate);
                    try (InputStream in = new FileInputStream(cacheFile)) {
                        installedDriverHash = DriverManager.validateDriver(in);
                    }
                    if (installedDriverHash == null) {
                        throw new IOException(
                                "Downloaded file is not a compatible AdrenoTools Turnip (.adpkg) driver");
                    }
                    ProgressLayout.setProgress(progressKey, 88, R.string.driver_progress_install);
                    try (InputStream in = new FileInputStream(cacheFile)) {
                        DriverManager.installDriverContent(installedDriverHash, in, true);
                    }
                    // Activate immediately: bind to targetInstance when scoped, else set as global Turnip
                    if (targetInstance != null) {
                        targetInstance.vulkanDriver = installedDriverHash;
                        targetInstance.maybeWrite();
                    } else {
                        DriverManager.setPreferredDriverByHash(installedDriverHash);
                    }
                } else {
                    ProgressLayout.setProgress(progressKey, 80, R.string.multirt_progress_caching);
                    try (InputStream in = new FileInputStream(cacheFile)) {
                        MultiRTUtils.installRuntimeNamed(Tools.NATIVE_LIB_DIR, in, asset.installName);
                    }
                    MultiRTUtils.postPrepare(asset.installName);
                }

                final String finalHash = installedDriverHash;
                Tools.runOnUiThread(() -> {
                    String suffix = "";
                    if (finalHash != null) {
                        suffix = targetInstance != null
                                ? " (assigned to instance)" : " (activated globally)";
                    }
                    Toast.makeText(appCtx,
                            "Installed " + asset.installName + suffix + "!",
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
                String json = ZyroxHttpClient.getStringOrThrow(
                        apiUrl, Collections.singletonMap("Accept", "application/vnd.github+json"));
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
    public static List<CatalogAsset> parseJreReleases(@NonNull String json, @NonNull String arch) {
        List<CatalogAsset> out = new ArrayList<>();
        String archSuffix = "-" + arch.toLowerCase(Locale.ROOT) + ".tar.xz";
        String altSuffix = "aarch64.tar.xz";
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) return out;
        for (JsonElement relEl : root.getAsJsonArray()) {
            if (!relEl.isJsonObject()) continue;
            JsonObject rel = relEl.getAsJsonObject();
            String relTag = optStr(rel, "tag_name", "");
            if (!rel.has("assets") || !rel.get("assets").isJsonArray()) continue;
            for (JsonElement aEl : rel.getAsJsonArray("assets")) {
                if (!aEl.isJsonObject()) continue;
                JsonObject a = aEl.getAsJsonObject();
                String name = optStr(a, "name", "");
                String low = name.toLowerCase(Locale.ROOT);
                boolean match = low.endsWith(archSuffix)
                        || ("arm64".equalsIgnoreCase(arch) && low.endsWith(altSuffix));
                if (!match) continue;
                String url = optStr(a, "browser_download_url", "");
                if (!ZyroxHttpClient.isStrictHttpsUrl(url)) continue;
                long size = a.has("size") && !a.get("size").isJsonNull()
                        ? a.get("size").getAsLong() : 0L;
                String cleanName = name.replace(".tar.xz", "");
                String label = cleanName
                        + (relTag.isEmpty() ? "" : " [" + relTag + "]")
                        + (size > 0 ? " • " + ZyroxStudioUi.formatBytes(size) : "");
                out.add(new CatalogAsset(label, cleanName, url, size, false));
                if (out.size() >= 18) return out;
            }
        }
        return out;
    }

    @NonNull
    private static List<CatalogAsset> fetchTurnipCatalog(@NonNull GpuArchitecture gpuArch)
            throws IOException {
        List<CatalogAsset> combined = new ArrayList<>();
        IOException lastErr = null;
        for (String apiUrl : TURNIP_RELEASES_APIS) {
            try {
                String json = ZyroxHttpClient.getStringOrThrow(
                        apiUrl, Collections.singletonMap("Accept", "application/vnd.github+json"));
                combined.addAll(parseTurnipReleases(json, gpuArch));
                if (combined.size() >= 16) break;
            } catch (IOException e) {
                lastErr = e;
            }
        }
        if (combined.isEmpty() && lastErr != null) throw lastErr;
        // Bubble GPU-architecture-matched Turnip .adpkg builds to the top
        Collections.sort(combined, (a, b) -> Boolean.compare(b.recommendedForGpu, a.recommendedForGpu));
        return combined;
    }

    /**
     * Parses GitHub Releases JSON for AdrenoTools Turnip {@code .adpkg}, {@code .adpkg.zip}, and
     * {@code .zip} driver packages, annotating builds matched to the detected {@link GpuArchitecture}.
     */
    @NonNull
    public static List<CatalogAsset> parseTurnipReleases(@NonNull String json,
                                                         @NonNull GpuArchitecture gpuArch) {
        List<CatalogAsset> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) return out;
        JsonArray releases = root.getAsJsonArray();
        for (JsonElement relEl : releases) {
            if (!relEl.isJsonObject()) continue;
            JsonObject rel = relEl.getAsJsonObject();
            String relTitle = optStr(rel, "name", optStr(rel, "tag_name", "Turnip"));
            String relBody = optStr(rel, "body", "");
            if (!rel.has("assets") || !rel.get("assets").isJsonArray()) continue;
            for (JsonElement aEl : rel.getAsJsonArray("assets")) {
                if (!aEl.isJsonObject()) continue;
                JsonObject a = aEl.getAsJsonObject();
                String name = optStr(a, "name", "");
                if (!DriverManager.isSupportedDriverArchiveName(name)) continue;
                String url = optStr(a, "browser_download_url", "");
                if (!ZyroxHttpClient.isStrictHttpsUrl(url)) continue;
                long size = a.has("size") && !a.get("size").isJsonNull()
                        ? a.get("size").getAsLong() : 0L;

                String combinedLower = (relTitle + " " + name + " " + relBody).toLowerCase(Locale.ROOT);
                boolean isTurnipOrQualcomm = combinedLower.contains("turnip")
                        || combinedLower.contains("mesa")
                        || combinedLower.contains("adreno")
                        || combinedLower.contains("qualcomm")
                        || name.toLowerCase(Locale.ROOT).endsWith(".adpkg")
                        || name.toLowerCase(Locale.ROOT).endsWith(".adpkg.zip");
                if (!isTurnipOrQualcomm) continue;

                boolean recommended = matchesGpuArch(combinedLower, gpuArch);
                String formatBadge = name.toLowerCase(Locale.ROOT).contains(".adpkg")
                        ? " [.adpkg]" : " [AdrenoTools]";
                String label = (recommended ? "[REC] " : "")
                        + relTitle + " — " + name + formatBadge
                        + (size > 0 ? " • " + ZyroxStudioUi.formatBytes(size) : "");
                out.add(new CatalogAsset(label, name, url, size, true, recommended));
                if (out.size() >= 24) return out;
            }
        }
        return out;
    }

    private static boolean matchesGpuArch(@NonNull String textLower,
                                          @NonNull GpuArchitecture gpuArch) {
        boolean isTurnip = textLower.contains("turnip") || textLower.contains("mesa");
        switch (gpuArch) {
            case ADRENO_8XX:
                return textLower.contains("a8") || textLower.contains("8xx")
                        || textLower.contains("830") || textLower.contains("elite")
                        || textLower.contains("gen 4");
            case ADRENO_7XX:
                return isTurnip && (textLower.contains("a7") || textLower.contains("7xx")
                        || textLower.contains("gmem") || !textLower.contains("a6xx-only"));
            case ADRENO_6XX:
                return isTurnip && !textLower.contains("a7xx only") && !textLower.contains("a8xx");
            default:
                return false;
        }
    }

    private static void downloadHttpsToFile(@NonNull String httpsUrl,
                                            @NonNull File target,
                                            @NonNull String progressKey) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create package cache folder");
        }
        try (Response resp = ZyroxHttpClient.openDownloadResponse(httpsUrl, 0L)) {
            if (!resp.isSuccessful()) {
                throw new IOException("Download server returned HTTP " + resp.code());
            }
            ResponseBody body = resp.body();
            if (body == null) {
                throw new IOException("Empty package response body");
            }
            long contentLen = body.contentLength();
            if (contentLen > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Package exceeds safety limit");
            }
            try (InputStream in = body.byteStream();
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buf = new byte[32 * 1024];
                long downloaded = 0L;
                int lastPct = -1;
                int n;
                while ((n = in.read(buf)) != -1) {
                    downloaded += n;
                    if (downloaded > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Package exceeded safety limit");
                    }
                    out.write(buf, 0, n);
                    if (contentLen > 0) {
                        int pct = 5 + (int) ((downloaded * 68L) / contentLen);
                        if (pct != lastPct) {
                            lastPct = pct;
                            ProgressLayout.setProgress(progressKey, pct, R.string.zyrox_pkg_downloading);
                        }
                    }
                }
            }
        }
    }

    @NonNull
    private static String optStr(@NonNull JsonObject obj,
                                 @NonNull String key,
                                 @NonNull String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }
}
