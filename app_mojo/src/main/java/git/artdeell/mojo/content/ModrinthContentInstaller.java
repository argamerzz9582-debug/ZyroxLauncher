package git.artdeell.mojo.content;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceBackupService;
import git.artdeell.mojo.metadata.ModPlatformRepository;
import git.artdeell.mojo.metadata.MojangVersionProvider;
import git.artdeell.mojo.metadata.VersionMetadataRepository;
import git.artdeell.mojo.modding.fabric.FabriclikeUtils;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.GameOptionsUtils;
import git.artdeell.mojo.utils.ZyroxHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Verified Modrinth v2 direct content installer, automatic {@code required}-dependency resolver,
 * loader-aware 1-Tap FPS Boost Pack installer (with automatic Fabric Loader upgrade for Vanilla
 * MC 1.14+), and 1-Tap Batch Mod Updater (with atomic rollback backup).
 *
 * <p>Security invariants: strictly {@code https://} connections on trusted CDN hosts, pooled
 * {@link ZyroxHttpClient} transport, mandatory SHA-1 digest verification when supplied,
 * path-traversal-proof filename sanitization, and bounded payload sizes.
 */
public final class ModrinthContentInstaller {

    public static final String TYPE_MODPACK = "modpack";
    public static final String TYPE_MOD = "mod";
    public static final String TYPE_SHADER = "shader";
    public static final String TYPE_RESOURCEPACK = "resourcepack";

    public static final String MODRINTH_API = ModPlatformRepository.MODRINTH_API_BASE;
    private static final String USER_AGENT = "ZyroxLauncher/" + BuildConfig.VERSION_NAME
            + " (Android; https://github.com/argamerzz9582-debug/ZyroxLauncher)";
    private static final String BACKUP_DIR_NAME = ".zyrox_mod_backup";
    private static final long MAX_ARTIFACT_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_DEPENDENCY_DEPTH = 3;
    private static final int MAX_AUTO_DEPS = 10;

    private static final String[] TRUSTED_CDN_SUFFIXES = {
            "modrinth.com",
            "github.com",
            "githubusercontent.com",
            "forgecdn.net",
            "curseforge.com"
    };

    private static final Map<String, String> API_HEADERS = createApiHeaders();

    @NonNull
    private static Map<String, String> createApiHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", USER_AGENT);
        h.put("Accept", "application/json");
        return Collections.unmodifiableMap(h);
    }

    public interface ProgressCallback {
        void onProgress(@NonNull String status);
    }

    /**
     * Represents one logical performance mod slot in the 1-Tap FPS Boost catalog. Ordered fallback
     * slugs guarantee conflict-free installs (e.g. {@code sodium} falling back to {@code embeddium}
     * on NeoForge without ever installing both).
     */
    public static final class FpsModSlot {
        @NonNull public final String displayName;
        @NonNull public final String[] candidateSlugs;
        @NonNull public final String[] alreadyPresentTokens;

        public FpsModSlot(@NonNull String displayName,
                          @NonNull String[] candidateSlugs,
                          @NonNull String[] alreadyPresentTokens) {
            this.displayName = displayName;
            this.candidateSlugs = candidateSlugs;
            this.alreadyPresentTokens = alreadyPresentTokens;
        }
    }

    /** Detailed, user-friendly report returned by all Modrinth install &amp; update operations. */
    public static final class InstallSummary {
        public final int installedCount;
        public final int dependencyCount;
        public final int skippedCount;
        @NonNull public final List<String> installedFiles;
        @NonNull public final List<String> installedLabels;
        @NonNull public final List<String> alreadyPresentLabels;
        @NonNull public final List<String> dependencyLabels;
        @NonNull public final List<String> skippedLabels;
        @Nullable public final String cleanMinecraftVersion;
        @Nullable public final String resolvedLoader;
        @Nullable public final String autoInstalledLoaderId;
        public final boolean optionsOptimized;
        @NonNull public final String message;

        public InstallSummary(int installedCount,
                              int dependencyCount,
                              int skippedCount,
                              @NonNull List<String> installedFiles,
                              @NonNull String message) {
            this(installedCount, dependencyCount, skippedCount, installedFiles,
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    null, null, null, false, message);
        }

        public InstallSummary(int installedCount,
                              int dependencyCount,
                              int skippedCount,
                              @NonNull List<String> installedFiles,
                              @NonNull List<String> installedLabels,
                              @NonNull List<String> alreadyPresentLabels,
                              @NonNull List<String> dependencyLabels,
                              @NonNull List<String> skippedLabels,
                              @Nullable String cleanMinecraftVersion,
                              @Nullable String resolvedLoader,
                              @Nullable String autoInstalledLoaderId,
                              boolean optionsOptimized,
                              @NonNull String message) {
            this.installedCount = installedCount;
            this.dependencyCount = dependencyCount;
            this.skippedCount = skippedCount;
            this.installedFiles = Collections.unmodifiableList(new ArrayList<>(installedFiles));
            this.installedLabels = Collections.unmodifiableList(new ArrayList<>(installedLabels));
            this.alreadyPresentLabels = Collections.unmodifiableList(new ArrayList<>(alreadyPresentLabels));
            this.dependencyLabels = Collections.unmodifiableList(new ArrayList<>(dependencyLabels));
            this.skippedLabels = Collections.unmodifiableList(new ArrayList<>(skippedLabels));
            this.cleanMinecraftVersion = cleanMinecraftVersion;
            this.resolvedLoader = resolvedLoader;
            this.autoInstalledLoaderId = autoInstalledLoaderId;
            this.optionsOptimized = optionsOptimized;
            this.message = message;
        }

        /** Multi-line report suitable for a completion dialog showing installed vs. skipped mods. */
        @NonNull
        public String buildDetailedReport() {
            if (cleanMinecraftVersion == null) {
                return message;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Target: Minecraft ").append(cleanMinecraftVersion);
            if (resolvedLoader != null) {
                sb.append(" • ").append(Instance.formatLoaderDisplayName(resolvedLoader));
            }
            if (autoInstalledLoaderId != null && !autoInstalledLoaderId.isEmpty()) {
                sb.append("\n\n✓ Auto-installed Fabric Loader:\n  ").append(autoInstalledLoaderId);
            }
            if (!installedLabels.isEmpty()) {
                sb.append("\n\n✓ Installed (").append(installedLabels.size()).append("): ")
                        .append(joinComma(installedLabels));
            }
            if (!dependencyLabels.isEmpty()) {
                sb.append("\n✓ Required Dependencies (").append(dependencyLabels.size()).append("): ")
                        .append(joinComma(dependencyLabels));
            }
            if (!alreadyPresentLabels.isEmpty()) {
                sb.append("\n• Already Installed (").append(alreadyPresentLabels.size()).append("): ")
                        .append(joinComma(alreadyPresentLabels));
            }
            if (!skippedLabels.isEmpty()) {
                sb.append("\n• Skipped (").append(skippedLabels.size()).append("): ")
                        .append(joinComma(skippedLabels));
            }
            if (optionsOptimized) {
                sb.append("\n\n✓ Optimized options.txt: renderDistance=8, entityShadows=false, particles=1, enableVsync=false");
            }
            return sb.toString();
        }
    }

    private ModrinthContentInstaller() {
    }

    /**
     * Resolves the clean base Mojang Minecraft release (e.g. {@code "1.21.4"}, {@code "1.20.1"},
     * {@code "26.1.2"}) from an instance {@code versionId}, checking symbolic channels, on-disk
     * {@code inheritsFrom} metadata, and loader-aware version extraction.
     */
    @Nullable
    public static String extractMinecraftVersion(@Nullable String versionId) {
        return Instance.resolveCleanMinecraftVersion(versionId);
    }

    /**
     * Detects canonical Modrinth loader slug ({@code fabric}, {@code quilt}, {@code neoforge},
     * {@code forge}), or {@code null} when the version is plain Vanilla.
     */
    @Nullable
    public static String extractModLoader(@Nullable String versionId) {
        String detected = Instance.detectModLoader(versionId);
        return Instance.LOADER_VANILLA.equals(detected) ? null : detected;
    }

    /** Detects canonical loader for an {@link Instance}, defaulting to {@code fabric} when vanilla. */
    @NonNull
    public static String detectInstanceLoader(@Nullable Instance instance) {
        if (instance == null) return Instance.LOADER_FABRIC;
        String detected = instance.getDetectedModLoader();
        return Instance.LOADER_VANILLA.equals(detected) ? Instance.LOADER_FABRIC : detected;
    }

    /**
     * Resolves an instance's clean Minecraft version on a background thread, syncing official
     * Mojang metadata if a symbolic alias ({@code latest_release}, {@code latest_snapshot},
     * {@code latest_fabric}) is encountered before the catalog is warm.
     */
    @Nullable
    public static String resolveCleanMinecraftVersionOnline(@NonNull Instance instance) {
        String clean = instance.getCleanMinecraftVersion();
        if (clean != null && !clean.isEmpty()) {
            return clean;
        }
        String raw = instance.versionId;
        if (Instance.isSymbolicVersionId(raw)) {
            try {
                VersionMetadataRepository repo = VersionMetadataRepository.getInstance();
                JVersionList catalog = repo.getMojangProvider().loadCatalog(false);
                if (catalog != null) {
                    String type = Instance.isLatestSnapshotId(raw)
                            ? MojangVersionProvider.TYPE_SNAPSHOT
                            : MojangVersionProvider.TYPE_RELEASE;
                    String found = MojangVersionProvider.findLatestByType(catalog, type);
                    if (found != null && !found.trim().isEmpty()) {
                        String extracted = Instance.extractCleanMinecraftVersion(found.trim());
                        return extracted != null ? extracted : found.trim();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Resolves a modern Modrinth-compatible Minecraft release dynamically from {@code versionId}
     * or the synced official Mojang release catalog.
     */
    @NonNull
    public static String resolveModernGameVersion(@Nullable String versionId) {
        String mc = extractMinecraftVersion(versionId);
        if (mc != null) return mc;
        try {
            JVersionList catalog = VersionMetadataRepository.getInstance().getCurrentCatalog();
            String latestRelease = MojangVersionProvider.findLatestByType(
                    catalog, MojangVersionProvider.TYPE_RELEASE);
            if (latestRelease != null && !latestRelease.trim().isEmpty()) {
                return latestRelease.trim();
            }
        } catch (Throwable ignored) {
        }
        return "1.21.4";
    }

    /**
     * Returns the curated, loader-aware FPS Boost mod slots for {@code loader}:
     * <ul>
     *   <li><b>Fabric / Quilt</b>: Sodium, Lithium, FerriteCore, ImmediatelyFast, Entity Culling (+ Iris)</li>
     *   <li><b>NeoForge</b>: Sodium (fallback Embeddium), Lithium, FerriteCore, ImmediatelyFast, Entity Culling (+ Iris/Oculus)</li>
     *   <li><b>Forge</b>: Embeddium (fallback Rubidium), FerriteCore, ImmediatelyFast, Entity Culling (+ Oculus)</li>
     * </ul>
     * Direct {@code required} dependencies (including {@code fabric-api}) are automatically
     * resolved and downloaded from Modrinth's dependency metadata.
     */
    @NonNull
    public static List<FpsModSlot> getFpsBoostCatalog(@NonNull String loader,
                                                      boolean includeShadersMod) {
        String norm = loader.trim().toLowerCase(Locale.ROOT);
        List<FpsModSlot> slots = new ArrayList<>();
        if (Instance.LOADER_FORGE.equals(norm)) {
            slots.add(new FpsModSlot("Embeddium",
                    new String[]{"embeddium", "rubidium"},
                    new String[]{"embeddium", "rubidium", "sodium"}));
            slots.add(new FpsModSlot("FerriteCore",
                    new String[]{"ferrite-core"},
                    new String[]{"ferritecore", "ferrite-core"}));
            slots.add(new FpsModSlot("ImmediatelyFast",
                    new String[]{"immediatelyfast"},
                    new String[]{"immediatelyfast"}));
            slots.add(new FpsModSlot("Entity Culling",
                    new String[]{"entityculling"},
                    new String[]{"entityculling"}));
            if (includeShadersMod) {
                slots.add(new FpsModSlot("Oculus",
                        new String[]{"oculus"},
                        new String[]{"oculus", "iris"}));
            }
        } else if (Instance.LOADER_NEOFORGE.equals(norm)) {
            // Sodium 0.6+ publishes official NeoForge builds on MC 1.21+; Embeddium covers 1.20.x–1.21.1
            slots.add(new FpsModSlot("Sodium / Embeddium",
                    new String[]{"sodium", "embeddium"},
                    new String[]{"sodium", "embeddium", "rubidium"}));
            slots.add(new FpsModSlot("Lithium",
                    new String[]{"lithium"},
                    new String[]{"lithium", "radium", "canary"}));
            slots.add(new FpsModSlot("FerriteCore",
                    new String[]{"ferrite-core"},
                    new String[]{"ferritecore", "ferrite-core"}));
            slots.add(new FpsModSlot("ImmediatelyFast",
                    new String[]{"immediatelyfast"},
                    new String[]{"immediatelyfast"}));
            slots.add(new FpsModSlot("Entity Culling",
                    new String[]{"entityculling"},
                    new String[]{"entityculling"}));
            if (includeShadersMod) {
                slots.add(new FpsModSlot("Iris / Oculus",
                        new String[]{"iris", "oculus"},
                        new String[]{"iris", "oculus"}));
            }
        } else {
            // Fabric & Quilt
            slots.add(new FpsModSlot("Sodium",
                    new String[]{"sodium"},
                    new String[]{"sodium"}));
            slots.add(new FpsModSlot("Lithium",
                    new String[]{"lithium"},
                    new String[]{"lithium"}));
            slots.add(new FpsModSlot("FerriteCore",
                    new String[]{"ferrite-core"},
                    new String[]{"ferritecore", "ferrite-core"}));
            slots.add(new FpsModSlot("ImmediatelyFast",
                    new String[]{"immediatelyfast"},
                    new String[]{"immediatelyfast"}));
            slots.add(new FpsModSlot("Entity Culling",
                    new String[]{"entityculling"},
                    new String[]{"entityculling"}));
            if (includeShadersMod) {
                slots.add(new FpsModSlot("Iris Shaders",
                        new String[]{"iris"},
                        new String[]{"iris"}));
            }
        }
        return slots;
    }

    /** Returns the Modrinth {@code loaders} query array (Quilt accepts native Quilt + Fabric mods). */
    @NonNull
    public static String[] getQueryLoadersForLoader(@Nullable String loader) {
        if (loader == null || loader.trim().isEmpty()) {
            return new String[0];
        }
        String norm = loader.trim().toLowerCase(Locale.ROOT);
        if (Instance.LOADER_VANILLA.equals(norm)) {
            return new String[0];
        }
        if (Instance.LOADER_QUILT.equals(norm)) {
            return new String[]{Instance.LOADER_QUILT, Instance.LOADER_FABRIC};
        }
        return new String[]{norm};
    }

    /**
     * 1-Tap "Zero-Lag" FPS Boost Pack:
     * <ol>
     *   <li>Resolves the clean Mojang Minecraft version (handling Fabric/Quilt/Forge/NeoForge/OptiFine
     *       IDs, {@code inheritsFrom}, and {@code latest_fabric}/{@code latest_release}).</li>
     *   <li>If the instance is Vanilla on MC 1.14+ (or 26.x+), automatically installs the latest
     *       compatible Fabric Loader via {@link FabriclikeUtils#FABRIC_UTILS} and binds the instance.</li>
     *   <li>Creates a pre-mod safety snapshot, independently resolves every compatible performance
     *       mod for the active loader, and auto-installs all {@code required} dependencies
     *       (including {@code fabric-api}) with SHA-1 verification.</li>
     *   <li>Applies competitive {@code options.txt} tweaks ({@code renderDistance:8},
     *       {@code entityShadows:false}, {@code particles:1}, {@code enableVsync:false}) via
     *       {@link GameOptionsUtils}.</li>
     * </ol>
     */
    @NonNull
    public static InstallSummary installZeroLagFpsPack(@NonNull Instance instance,
                                                       boolean includeIrisShaders,
                                                       @Nullable ProgressCallback cb) throws IOException {
        String autoInstalledLoaderId = null;

        // Materialize symbolic "latest_fabric" onto a concrete verified Fabric profile
        if (Instance.isLatestFabricId(instance.versionId)) {
            if (cb != null) {
                cb.onProgress("Installing latest stable Fabric Loader…");
            }
            FabriclikeUtils.ResolvedFabricRelease rel =
                    FabriclikeUtils.FABRIC_UTILS.resolveAndInstallLatestStable();
            instance.versionId = rel.versionId;
            instance.write();
            notifyVersionChanged();
            autoInstalledLoaderId = rel.versionId;
        }

        String cleanMc = resolveCleanMinecraftVersionOnline(instance);
        if (cleanMc == null || cleanMc.isEmpty()) {
            throw new IOException("Could not determine base Minecraft version from '"
                    + instance.versionId + "'. Select a Minecraft version in Instance Settings first.");
        }

        String loader = instance.getDetectedModLoader();
        File gameDir = instance.getGameDirectory();
        FileUtils.ensureDirectory(gameDir);

        // Vanilla profile handling: auto-install Fabric Loader on MC 1.14+ / 26.x+
        if (Instance.LOADER_VANILLA.equals(loader)) {
            if (!Instance.supportsFabricLoader(cleanMc)) {
                boolean optionsApplied = GameOptionsUtils.applyFpsBoostOptions(gameDir);
                String msg = "Applied low-latency options.txt settings for Minecraft " + cleanMc
                        + " (Fabric FPS mods require MC 1.14+; install Forge for legacy versions).";
                return new InstallSummary(
                        0, 0, 0,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.singletonList("Fabric Loader requires Minecraft 1.14+"),
                        cleanMc, Instance.LOADER_VANILLA, null, optionsApplied, msg);
            }
            if (cb != null) {
                cb.onProgress("Installing Fabric Loader for Minecraft " + cleanMc + "…");
            }
            String fabricVersionId = FabriclikeUtils.FABRIC_UTILS.installLatestForGameVersion(cleanMc);
            instance.versionId = fabricVersionId;
            instance.write();
            notifyVersionChanged();
            autoInstalledLoaderId = fabricVersionId;
            loader = Instance.LOADER_FABRIC;
        }

        InstanceBackupService.createPreModSnapshotQuietly(
                instance, includeIrisShaders ? "fps-iris-pack" : "zero-lag-fps-pack");

        InstallSummary modSummary = executeLoaderFpsBoostInstall(
                instance, cleanMc, loader, autoInstalledLoaderId, includeIrisShaders, true, cb);
        return modSummary;
    }

    /**
     * 1-Click FPS Boost Pack (also auto-upgrades Vanilla MC 1.14+ instances to Fabric Loader and
     * applies safe {@code options.txt} optimizations).
     */
    @NonNull
    public static InstallSummary installFpsBoostPack(@NonNull Instance instance,
                                                     boolean includeIrisShaders,
                                                     @Nullable ProgressCallback cb) throws IOException {
        return installZeroLagFpsPack(instance, includeIrisShaders, cb);
    }

    /** Convenience overload installing the core FPS Boost bundle without shaders. */
    @NonNull
    public static InstallSummary installFpsBoostPack(@NonNull Instance instance,
                                                     @Nullable ProgressCallback cb) throws IOException {
        return installZeroLagFpsPack(instance, false, cb);
    }

    @NonNull
    private static InstallSummary executeLoaderFpsBoostInstall(@NonNull Instance instance,
                                                               @NonNull String cleanMc,
                                                               @NonNull String loader,
                                                               @Nullable String autoInstalledLoaderId,
                                                               boolean includeIrisShaders,
                                                               boolean applyOptionsTweaks,
                                                               @Nullable ProgressCallback cb) throws IOException {
        File gameDir = instance.getGameDirectory();
        File modsDir = new File(gameDir, "mods");
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw new IOException("Could not create mods directory: " + modsDir.getAbsolutePath());
        }

        String[] queryLoaders = getQueryLoadersForLoader(loader);
        List<FpsModSlot> slots = getFpsBoostCatalog(loader, includeIrisShaders);
        Set<String> existingLower = listExistingJarNamesLower(modsDir);

        List<String> installedFiles = new ArrayList<>();
        List<String> installedLabels = new ArrayList<>();
        List<String> alreadyPresentLabels = new ArrayList<>();
        List<String> dependencyLabels = new ArrayList<>();
        List<String> skippedLabels = new ArrayList<>();
        Set<String> visitedProjects = new HashSet<>();

        // On Fabric, ensure Fabric API is present up-front since Sodium/Iris/ecosystem mods rely on it
        if (Instance.LOADER_FABRIC.equals(loader)
                && !containsAny(existingLower, "fabric-api", "fabric_api")) {
            if (cb != null) {
                cb.onProgress("Resolving required Fabric API for MC " + cleanMc + "…");
            }
            try {
                ResolvedDownload apiRes = downloadBestProjectVersion(
                        "fabric-api", cleanMc, loader, queryLoaders, modsDir, cb);
                visitedProjects.add("fabric-api");
                visitedProjects.add("p7dr8msh");
                if (apiRes.status == DownloadStatus.DOWNLOADED && apiRes.file != null) {
                    installedFiles.add(apiRes.file.getName());
                    existingLower.add(apiRes.file.getName().toLowerCase(Locale.ROOT));
                    dependencyLabels.add("Fabric API");
                } else if (apiRes.status == DownloadStatus.ALREADY_UP_TO_DATE) {
                    alreadyPresentLabels.add("Fabric API");
                }
            } catch (Throwable ignored) {
                // Non-fatal: Sodium/Lithium/FerriteCore still install independently even if Fabric API lags
            }
        }

        for (int i = 0; i < slots.size(); i++) {
            FpsModSlot slot = slots.get(i);
            if (containsAny(existingLower, slot.alreadyPresentTokens)) {
                alreadyPresentLabels.add(slot.displayName);
                continue;
            }

            if (cb != null) {
                cb.onProgress("FPS Boost (" + (i + 1) + "/" + slots.size() + "): checking "
                        + slot.displayName + " for MC " + cleanMc + "…");
            }

            boolean satisfied = false;
            String lastReason = "no " + Instance.formatLoaderDisplayName(loader)
                    + " build for MC " + cleanMc;

            for (String slug : slot.candidateSlugs) {
                ResolvedDownload res = downloadBestProjectVersion(
                        slug, cleanMc, loader, queryLoaders, modsDir, cb);
                String label = resolveFriendlySlugLabel(slot.displayName, slug);

                if (res.status == DownloadStatus.DOWNLOADED && res.file != null) {
                    installedFiles.add(res.file.getName());
                    installedLabels.add(label);
                    existingLower.add(res.file.getName().toLowerCase(Locale.ROOT));
                    visitedProjects.add(slug.toLowerCase(Locale.ROOT));
                    if (res.projectId != null) {
                        visitedProjects.add(res.projectId.toLowerCase(Locale.ROOT));
                    }
                    // Auto-resolve direct & nested required dependencies for this mod
                    if (res.versionObject != null) {
                        resolveDependenciesFromVersionObject(
                                res.versionObject, cleanMc, loader, queryLoaders, modsDir,
                                existingLower, visitedProjects, installedFiles, dependencyLabels,
                                0, cb);
                    }
                    satisfied = true;
                    break;
                } else if (res.status == DownloadStatus.ALREADY_UP_TO_DATE) {
                    alreadyPresentLabels.add(label);
                    if (res.versionObject != null) {
                        resolveDependenciesFromVersionObject(
                                res.versionObject, cleanMc, loader, queryLoaders, modsDir,
                                existingLower, visitedProjects, installedFiles, dependencyLabels,
                                0, cb);
                    }
                    satisfied = true;
                    break;
                } else if (res.failureReason != null && !res.failureReason.isEmpty()) {
                    lastReason = res.failureReason;
                }
            }

            if (!satisfied) {
                skippedLabels.add(slot.displayName + " (" + lastReason + ")");
            }
        }

        // Apply low-latency options.txt optimizations (renderDistance:8, entityShadows:false, particles:1, enableVsync:false)
        boolean optionsApplied = false;
        if (applyOptionsTweaks) {
            if (cb != null) {
                cb.onProgress("Applying low-latency options.txt tweaks…");
            }
            boolean isPvp = Instance.PRESET_MAX_FPS.equalsIgnoreCase(instance.performancePreset)
                    && instance.name != null
                    && instance.name.toLowerCase(Locale.ROOT).contains("pvp");
            GameOptionsUtils.applyZeroLagFpsOptions(gameDir, isPvp);
            optionsApplied = GameOptionsUtils.applyFpsBoostOptions(gameDir);
        }

        int newMods = installedLabels.size();
        int depCount = dependencyLabels.size();
        int skipCount = skippedLabels.size();
        String loaderDisplay = Instance.formatLoaderDisplayName(loader);

        String summaryMsg = buildConciseSummaryMessage(
                cleanMc, loaderDisplay, autoInstalledLoaderId,
                installedLabels, alreadyPresentLabels, dependencyLabels, skippedLabels,
                optionsApplied);

        return new InstallSummary(
                newMods,
                depCount,
                skipCount,
                installedFiles,
                installedLabels,
                alreadyPresentLabels,
                dependencyLabels,
                skippedLabels,
                cleanMc,
                loader,
                autoInstalledLoaderId,
                optionsApplied,
                summaryMsg);
    }

    @NonNull
    private static String buildConciseSummaryMessage(@NonNull String cleanMc,
                                                     @NonNull String loaderDisplay,
                                                     @Nullable String autoInstalledLoaderId,
                                                     @NonNull List<String> installed,
                                                     @NonNull List<String> alreadyPresent,
                                                     @NonNull List<String> deps,
                                                     @NonNull List<String> skipped,
                                                     boolean optionsApplied) {
        StringBuilder sb = new StringBuilder();
        if (autoInstalledLoaderId != null) {
            sb.append("Auto-installed Fabric • ");
        }
        if (!installed.isEmpty()) {
            sb.append("Installed ").append(installed.size()).append(" FPS mod(s) (")
                    .append(joinComma(installed)).append(")");
            if (!deps.isEmpty()) {
                sb.append(" + ").append(joinComma(deps));
            }
            sb.append(" for ").append(loaderDisplay).append(' ').append(cleanMc);
            if (!skipped.isEmpty()) {
                sb.append(" • ").append(skipped.size()).append(" skipped");
            }
        } else if (!alreadyPresent.isEmpty()) {
            sb.append("FPS Boost already up to date (").append(alreadyPresent.size())
                    .append(" active: ").append(joinComma(alreadyPresent)).append(")");
            if (!deps.isEmpty()) {
                sb.append(" • Added ").append(joinComma(deps));
            }
        } else {
            sb.append("No Modrinth builds released yet for ").append(loaderDisplay)
                    .append(' ').append(cleanMc);
            if (optionsApplied) {
                sb.append(" — applied low-latency options.txt FPS tuning instead");
            }
        }
        if (optionsApplied && (!installed.isEmpty() || !alreadyPresent.isEmpty())) {
            sb.append(" • options.txt optimized (RD:8, VSync OFF)");
        }
        return sb.toString();
    }

    /**
     * Installs a single verified Modrinth mod by project slug (e.g. {@code fabric-api},
     * {@code cloth-config}, {@code indium}) matched to the instance's clean Minecraft version and
     * loader, including any required dependencies. Powers Crash Doctor's 1-Tap Dependency Fix.
     */
    @NonNull
    public static InstallSummary installModBySlug(@NonNull Instance instance,
                                                  @NonNull String slug,
                                                  @Nullable ProgressCallback cb) throws IOException {
        String cleanSlug = slug.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\-_]", "");
        if (cleanSlug.isEmpty()) {
            throw new IOException("Invalid Modrinth project slug.");
        }
        String mcVer = resolveCleanMinecraftVersionOnline(instance);
        if (mcVer == null) {
            throw new IOException("Could not detect clean Minecraft version from '" + instance.versionId + "'.");
        }
        String loader = instance.getDetectedModLoader();
        if (Instance.LOADER_VANILLA.equals(loader)) {
            if (Instance.supportsFabricLoader(mcVer)) {
                if (cb != null) {
                    cb.onProgress("Installing Fabric Loader for Minecraft " + mcVer + "…");
                }
                instance.versionId = FabriclikeUtils.FABRIC_UTILS.installLatestForGameVersion(mcVer);
                instance.write();
                notifyVersionChanged();
                loader = Instance.LOADER_FABRIC;
            } else {
                loader = Instance.LOADER_FABRIC;
            }
        }
        File modsDir = new File(instance.getGameDirectory(), "mods");
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw new IOException("Could not create mods directory.");
        }
        InstanceBackupService.createPreModSnapshotQuietly(instance, "install-" + cleanSlug);
        String loaderDisplay = Instance.formatLoaderDisplayName(loader);
        if (cb != null) {
            cb.onProgress("Resolving " + cleanSlug + " for " + loaderDisplay + " " + mcVer + "…");
        }

        String[] queryLoaders = getQueryLoadersForLoader(loader);
        ResolvedDownload res = downloadBestProjectVersion(
                cleanSlug, mcVer, loader, queryLoaders, modsDir, cb);
        if (res.status == DownloadStatus.FAILED || res.file == null) {
            throw new IOException("No compatible release of '" + cleanSlug + "' found for "
                    + loaderDisplay + " " + mcVer
                    + (res.failureReason != null ? " (" + res.failureReason + ")." : "."));
        }

        List<String> files = new ArrayList<>();
        files.add(res.file.getName());
        List<String> depLabels = new ArrayList<>();
        if (res.versionObject != null) {
            Set<String> existing = listExistingJarNamesLower(modsDir);
            Set<String> visited = new HashSet<>();
            visited.add(cleanSlug);
            if (res.projectId != null) visited.add(res.projectId.toLowerCase(Locale.ROOT));
            resolveDependenciesFromVersionObject(
                    res.versionObject, mcVer, loader, queryLoaders, modsDir,
                    existing, visited, files, depLabels, 0, cb);
        }

        String msg = (res.status == DownloadStatus.ALREADY_UP_TO_DATE
                ? ("Verified existing " + res.file.getName())
                : ("Installed " + res.file.getName()))
                + (!depLabels.isEmpty() ? (" + " + joinComma(depLabels)) : "")
                + " for " + loaderDisplay + " " + mcVer + ".";
        return new InstallSummary(1, depLabels.size(), 0, files, msg);
    }

    /**
     * Installs a single Mod, Shader Pack, or Resource Pack directly into {@code instance}, and for
     * mods automatically resolves and downloads {@code required} Modrinth dependencies.
     */
    @NonNull
    public static InstallSummary installDirectArtifact(@NonNull Instance instance,
                                                       @NonNull String projectType,
                                                       @Nullable String projectId,
                                                       @NonNull String displayTitle,
                                                       @NonNull String downloadUrl,
                                                       @Nullable String expectedSha1,
                                                       @Nullable ProgressCallback cb) throws IOException {
        return installDirectArtifact(instance, projectType, projectId, null,
                displayTitle, downloadUrl, expectedSha1, cb);
    }

    /**
     * Installs a specific version artifact into {@code instance}, resolving {@code required}
     * dependencies via {@code versionId} (preferred) or {@code expectedSha1} hash lookup.
     */
    @NonNull
    public static InstallSummary installDirectArtifact(@NonNull Instance instance,
                                                       @NonNull String projectType,
                                                       @Nullable String projectId,
                                                       @Nullable String versionId,
                                                       @NonNull String displayTitle,
                                                       @NonNull String downloadUrl,
                                                       @Nullable String expectedSha1,
                                                       @Nullable ProgressCallback cb) throws IOException {
        String autoInstalledLoaderId = null;
        if (TYPE_MOD.equalsIgnoreCase(projectType)) {
            String cleanMc = resolveCleanMinecraftVersionOnline(instance);
            String detectedLoader = instance.getDetectedModLoader();
            if (Instance.LOADER_VANILLA.equals(detectedLoader)
                    && cleanMc != null
                    && Instance.supportsFabricLoader(cleanMc)) {
                if (cb != null) {
                    cb.onProgress("Installing Fabric Loader for Minecraft " + cleanMc + "…");
                }
                autoInstalledLoaderId = FabriclikeUtils.FABRIC_UTILS.installLatestForGameVersion(cleanMc);
                instance.versionId = autoInstalledLoaderId;
                instance.write();
                notifyVersionChanged();
            }
            InstanceBackupService.createPreModSnapshotQuietly(instance, "mod-install");
        }

        validateTrustedHttpsUrl(downloadUrl);
        File gameDir = instance.getGameDirectory();
        File targetDir;
        String fallbackExt;
        switch (projectType) {
            case TYPE_SHADER:
                targetDir = new File(gameDir, "shaderpacks");
                fallbackExt = ".zip";
                break;
            case TYPE_RESOURCEPACK:
                targetDir = new File(gameDir, "resourcepacks");
                fallbackExt = ".zip";
                break;
            case TYPE_MOD:
            default:
                targetDir = new File(gameDir, "mods");
                fallbackExt = ".jar";
                break;
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Unable to create " + targetDir.getName() + " folder");
        }

        String fileName = sanitizeFileName(extractFileNameFromUrl(downloadUrl), displayTitle + fallbackExt);
        File outFile = FileUtils.resolveInsideDirectory(targetDir, fileName);
        if (!isExistingFileVerified(outFile, expectedSha1)) {
            if (cb != null) cb.onProgress("Downloading " + displayTitle + "…");
            downloadVerified(downloadUrl, expectedSha1, outFile);
        }

        List<String> installedFiles = new ArrayList<>();
        installedFiles.add(outFile.getName());
        List<String> depLabels = new ArrayList<>();

        // Auto-resolve required Modrinth dependencies (e.g. fabric-api) for installed mods
        if (TYPE_MOD.equalsIgnoreCase(projectType)) {
            String mcVer = resolveCleanMinecraftVersionOnline(instance);
            String loader = instance.getDetectedModLoader();
            if (mcVer != null && !Instance.LOADER_VANILLA.equals(loader)) {
                Set<String> existing = listExistingJarNamesLower(targetDir);
                Set<String> visited = new HashSet<>();
                if (projectId != null && !projectId.isEmpty()) {
                    visited.add(projectId.toLowerCase(Locale.ROOT));
                }
                String[] queryLoaders = getQueryLoadersForLoader(loader);
                try {
                    JsonObject verObj = fetchModrinthVersionMetadata(versionId, expectedSha1);
                    if (verObj != null) {
                        String pid = optString(verObj, "project_id");
                        if (pid != null) visited.add(pid.toLowerCase(Locale.ROOT));
                        resolveDependenciesFromVersionObject(
                                verObj, mcVer, loader, queryLoaders, targetDir,
                                existing, visited, installedFiles, depLabels, 0, cb);
                    }
                } catch (Throwable ignored) {
                    // Primary mod is already verified & installed; non-fatal if dep lookup times out
                }
            }
        }

        int depsInstalled = depLabels.size();
        String msg = (autoInstalledLoaderId != null ? "Auto-installed Fabric • " : "")
                + "Installed " + displayTitle
                + (depsInstalled > 0 ? (" + " + joinComma(depLabels)) : "")
                + " into " + targetDir.getName() + "/";
        return new InstallSummary(
                1 + depsInstalled,
                depsInstalled,
                0,
                installedFiles,
                Collections.singletonList(displayTitle),
                Collections.emptyList(),
                depLabels,
                Collections.emptyList(),
                instance.getCleanMinecraftVersion(),
                instance.getDetectedModLoader(),
                autoInstalledLoaderId,
                false,
                msg);
    }

    /**
     * 1-Tap Batch Mod Updater: upgrades all outdated Modrinth-hosted {@code .jar} mods in
     * {@code <instance>/mods/} using the instance's clean Minecraft version &amp; loader while
     * preserving previous jars in {@code .zyrox_mod_backup/} for instant 1-tap rollback.
     */
    @NonNull
    public static InstallSummary batchUpdateAllMods(@NonNull Instance instance,
                                                    @Nullable ProgressCallback cb) throws IOException {
        String mcVer = resolveCleanMinecraftVersionOnline(instance);
        String loader = extractModLoader(instance.versionId);
        if (mcVer == null || loader == null) {
            throw new IOException("Select a Fabric, Quilt, Forge, or NeoForge instance to check mod updates.");
        }

        File modsDir = new File(instance.getGameDirectory(), "mods");
        File[] jars = modsDir.listFiles(f -> f.isFile()
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return new InstallSummary(0, 0, 0, Collections.emptyList(), "No enabled .jar mods to update.");
        }

        if (cb != null) cb.onProgress("Saving safety snapshot & hashing " + jars.length + " installed mod(s)…");
        InstanceBackupService.createPreModSnapshotQuietly(instance, "batch-mod-update");
        Map<String, File> sha1ToFile = new LinkedHashMap<>();
        for (File jar : jars) {
            sha1ToFile.put(computeSha1Hex(jar), jar);
        }

        JsonObject req = new JsonObject();
        JsonArray hashesArr = new JsonArray();
        for (String h : sha1ToFile.keySet()) hashesArr.add(h);
        req.add("hashes", hashesArr);
        req.addProperty("algorithm", "sha1");
        JsonArray loadersArr = new JsonArray();
        for (String qLoader : getQueryLoadersForLoader(loader)) {
            loadersArr.add(qLoader);
        }
        req.add("loaders", loadersArr);
        JsonArray gameVersArr = new JsonArray();
        gameVersArr.add(mcVer);
        req.add("game_versions", gameVersArr);

        if (cb != null) cb.onProgress("Checking Modrinth for verified updates…");
        String respStr = httpPostJson(MODRINTH_API + "/version_files/update", req.toString());
        JsonObject resp = JsonParser.parseString(respStr).getAsJsonObject();

        File backupDir = new File(instance.getGameDirectory(), BACKUP_DIR_NAME);
        boolean backupCleared = false;
        List<String> updated = new ArrayList<>();
        int upToDate = 0;

        for (Map.Entry<String, File> entry : sha1ToFile.entrySet()) {
            String oldSha = entry.getKey();
            File oldJar = entry.getValue();
            if (!resp.has(oldSha) || !resp.get(oldSha).isJsonObject()) {
                continue;
            }
            JsonObject latestVer = resp.getAsJsonObject(oldSha);
            JsonObject primaryFile = pickPrimaryFile(latestVer);
            if (primaryFile == null) continue;
            String newSha = getFileSha1(primaryFile);
            String url = optString(primaryFile, "url");
            String rawName = optString(primaryFile, "filename");
            if (url == null || newSha == null || oldSha.equalsIgnoreCase(newSha)) {
                upToDate++;
                continue;
            }

            String safeName = sanitizeFileName(rawName, oldJar.getName());
            if (cb != null) cb.onProgress("Updating " + oldJar.getName() + " → " + safeName + "…");

            File newJar = new File(modsDir, safeName + ".updating");
            downloadVerified(url, newSha, newJar);

            if (!backupCleared) {
                clearDirectoryFiles(backupDir);
                if (!backupDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    backupDir.mkdirs();
                }
                backupCleared = true;
            }
            File backupCopy = new File(backupDir, oldJar.getName());
            copyFile(oldJar, backupCopy);
            if (!oldJar.delete()) {
                //noinspection ResultOfMethodCallIgnored
                newJar.delete();
                continue;
            }
            File finalDest = new File(modsDir, safeName);
            if (finalDest.exists()) {
                //noinspection ResultOfMethodCallIgnored
                finalDest.delete();
            }
            if (newJar.renameTo(finalDest)) {
                updated.add(finalDest.getName());
            } else {
                copyFile(newJar, finalDest);
                //noinspection ResultOfMethodCallIgnored
                newJar.delete();
                updated.add(finalDest.getName());
            }
        }

        String loaderDisplay = Instance.formatLoaderDisplayName(loader);
        String msg = updated.isEmpty()
                ? ("All " + upToDate + " Modrinth mod(s) are already up to date for "
                + loaderDisplay + " " + mcVer + "!")
                : ("Updated " + updated.size()
                + " mod(s) with SHA-1 verification (previous jars backed up for rollback).");
        return new InstallSummary(updated.size(), 0, upToDate, updated, msg);
    }

    public static boolean hasRollbackBackup(@Nullable Instance instance) {
        if (instance == null) return false;
        File dir = new File(instance.getGameDirectory(), BACKUP_DIR_NAME);
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
        return files != null && files.length > 0;
    }

    /** Restores backed-up mod jars from the last batch update. */
    public static int rollbackLastModUpdate(@NonNull Instance instance) throws IOException {
        File backupDir = new File(instance.getGameDirectory(), BACKUP_DIR_NAME);
        File modsDir = new File(instance.getGameDirectory(), "mods");
        File[] backups = backupDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
        if (backups == null || backups.length == 0) return 0;
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw new IOException("Mods folder missing");
        }
        int restored = 0;
        for (File bak : backups) {
            File dest = new File(modsDir, sanitizeFileName(bak.getName(), "restored.jar"));
            copyFile(bak, dest);
            //noinspection ResultOfMethodCallIgnored
            bak.delete();
            restored++;
        }
        return restored;
    }

    @Nullable
    private static JsonObject fetchModrinthVersionMetadata(@Nullable String versionId,
                                                           @Nullable String sha1) {
        if (versionId != null && !versionId.trim().isEmpty()) {
            String trimmed = versionId.trim();
            // CurseForge numeric fileIds consist exclusively of digits; Modrinth IDs are base62
            if (!trimmed.matches("^[0-9]+$")) {
                try {
                    String json = httpGet(MODRINTH_API + "/version/"
                            + ZyroxHttpClient.encodeQueryComponent(trimmed));
                    JsonElement el = JsonParser.parseString(json);
                    if (el.isJsonObject()) return el.getAsJsonObject();
                } catch (Throwable ignored) {
                }
            }
        }
        if (sha1 != null && !sha1.trim().isEmpty()) {
            try {
                String json = httpGet(MODRINTH_API + "/version_file/"
                        + ZyroxHttpClient.encodeQueryComponent(sha1.trim()) + "?algorithm=sha1");
                JsonElement el = JsonParser.parseString(json);
                if (el.isJsonObject()) return el.getAsJsonObject();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void resolveDependenciesFromVersionObject(@NonNull JsonObject versionObj,
                                                             @NonNull String cleanMc,
                                                             @NonNull String primaryLoader,
                                                             @NonNull String[] queryLoaders,
                                                             @NonNull File modsDir,
                                                             @NonNull Set<String> existingLower,
                                                             @NonNull Set<String> visitedProjects,
                                                             @NonNull List<String> installedFiles,
                                                             @NonNull List<String> depLabels,
                                                             int depth,
                                                             @Nullable ProgressCallback cb) {
        if (depth >= MAX_DEPENDENCY_DEPTH
                || depLabels.size() >= MAX_AUTO_DEPS
                || !versionObj.has("dependencies")
                || !versionObj.get("dependencies").isJsonArray()) {
            return;
        }

        JsonArray deps = versionObj.getAsJsonArray("dependencies");
        for (JsonElement el : deps) {
            if (depLabels.size() >= MAX_AUTO_DEPS || !el.isJsonObject()) continue;
            JsonObject dep = el.getAsJsonObject();
            if (!"required".equalsIgnoreCase(optString(dep, "dependency_type"))) {
                continue;
            }

            String depProjId = optString(dep, "project_id");
            String depVerId = optString(dep, "version_id");

            if (depProjId != null) {
                String key = depProjId.toLowerCase(Locale.ROOT);
                if (!visitedProjects.add(key)) continue;
                if ("p7dr8msh".equals(key) && containsAny(existingLower, "fabric-api", "fabric_api")) {
                    continue;
                }
                if ("qvifycyj".equals(key) && containsAny(existingLower, "qfapi", "quilted_fabric_api")) {
                    continue;
                }
            } else if (depVerId != null) {
                if (!visitedProjects.add("ver:" + depVerId)) continue;
            } else {
                continue;
            }

            try {
                ResolvedDownload depDl = null;
                if (depVerId != null && !depVerId.isEmpty()) {
                    depDl = tryDownloadSpecificVersion(depVerId, cleanMc, queryLoaders, modsDir, cb);
                }
                if ((depDl == null || depDl.status == DownloadStatus.FAILED)
                        && depProjId != null && !depProjId.isEmpty()) {
                    if (cb != null) {
                        cb.onProgress("Resolving required dependency for MC " + cleanMc + "…");
                    }
                    depDl = downloadBestProjectVersion(
                            depProjId, cleanMc, primaryLoader, queryLoaders, modsDir, cb);
                }
                if (depDl == null || depDl.file == null) continue;

                if (depDl.projectId != null) {
                    visitedProjects.add(depDl.projectId.toLowerCase(Locale.ROOT));
                }
                existingLower.add(depDl.file.getName().toLowerCase(Locale.ROOT));
                if (depDl.status == DownloadStatus.DOWNLOADED) {
                    installedFiles.add(depDl.file.getName());
                    String friendly = friendlyDependencyLabel(depDl.projectId, depDl.file.getName());
                    if (!depLabels.contains(friendly)) {
                        depLabels.add(friendly);
                    }
                }
                if (depDl.versionObject != null) {
                    resolveDependenciesFromVersionObject(
                            depDl.versionObject, cleanMc, primaryLoader, queryLoaders, modsDir,
                            existingLower, visitedProjects, installedFiles, depLabels,
                            depth + 1, cb);
                }
            } catch (Throwable ignored) {
                // Continue installing remaining required dependencies independently
            }
        }
    }

    @Nullable
    private static ResolvedDownload tryDownloadSpecificVersion(@NonNull String versionId,
                                                               @NonNull String cleanMc,
                                                               @NonNull String[] queryLoaders,
                                                               @NonNull File modsDir,
                                                               @Nullable ProgressCallback cb) {
        try {
            String json = httpGet(MODRINTH_API + "/version/"
                    + ZyroxHttpClient.encodeQueryComponent(versionId));
            JsonObject verObj = JsonParser.parseString(json).getAsJsonObject();
            if (!isVersionCompatible(verObj, cleanMc, queryLoaders)) {
                return null;
            }
            return downloadFromVersionObject(verObj, versionId, modsDir, cb);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static ResolvedDownload downloadBestProjectVersion(@NonNull String slugOrId,
                                                               @NonNull String cleanMc,
                                                               @NonNull String primaryLoader,
                                                               @NonNull String[] queryLoaders,
                                                               @NonNull File modsDir,
                                                               @Nullable ProgressCallback cb) {
        try {
            String url = ZyroxHttpClient.buildModrinthProjectVersionsUrl(
                    MODRINTH_API, slugOrId, cleanMc, queryLoaders);
            String body = httpGet(url);
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonArray()) {
                return ResolvedDownload.failed("invalid response");
            }
            JsonArray arr = parsed.getAsJsonArray();
            JsonObject best = selectBestVersion(arr, primaryLoader);
            if (best == null) {
                return ResolvedDownload.failed("no " + Instance.formatLoaderDisplayName(primaryLoader)
                        + " release for MC " + cleanMc);
            }
            return downloadFromVersionObject(best, slugOrId, modsDir, cb);
        } catch (ZyroxHttpClient.HttpStatusException httpEx) {
            if (httpEx.statusCode == 404) {
                return ResolvedDownload.failed("unavailable on Modrinth");
            } else if (httpEx.statusCode == 429 || httpEx.statusCode == 403) {
                return ResolvedDownload.failed("rate-limited");
            }
            return ResolvedDownload.failed("HTTP " + httpEx.statusCode);
        } catch (IOException ioEx) {
            String msg = ioEx.getMessage();
            if (msg != null && msg.length() > 42) {
                msg = msg.substring(0, 39) + "…";
            }
            return ResolvedDownload.failed(msg != null ? msg : "network error");
        } catch (Throwable t) {
            return ResolvedDownload.failed("lookup failed");
        }
    }

    @NonNull
    private static ResolvedDownload downloadFromVersionObject(@NonNull JsonObject verObj,
                                                              @NonNull String fallbackSlug,
                                                              @NonNull File modsDir,
                                                              @Nullable ProgressCallback cb) throws IOException {
        JsonObject fileObj = pickPrimaryFile(verObj);
        if (fileObj == null) {
            return ResolvedDownload.failed("no artifact attached");
        }
        String dlUrl = optString(fileObj, "url");
        String rawName = optString(fileObj, "filename");
        String sha1 = getFileSha1(fileObj);
        String projectId = optString(verObj, "project_id");
        if (dlUrl == null) {
            return ResolvedDownload.failed("missing file URL");
        }
        String safeName = sanitizeFileName(rawName, fallbackSlug + ".jar");
        if (!safeName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            safeName = safeName + ".jar";
        }
        File dest = FileUtils.resolveInsideDirectory(modsDir, safeName);
        if (isExistingFileVerified(dest, sha1)) {
            return new ResolvedDownload(DownloadStatus.ALREADY_UP_TO_DATE, dest, verObj, projectId, null);
        }
        if (cb != null) {
            cb.onProgress("Downloading " + safeName + "…");
        }
        downloadVerified(dlUrl, sha1, dest);
        return new ResolvedDownload(DownloadStatus.DOWNLOADED, dest, verObj, projectId, null);
    }

    /**
     * Selects the top recommended Modrinth build from a newest-first array, strongly preferring
     * stable {@code release} builds matching {@code preferredLoader} over {@code beta}/{@code alpha}.
     */
    @Nullable
    public static JsonObject selectBestVersion(@Nullable JsonArray versions,
                                               @Nullable String preferredLoader) {
        if (versions == null || versions.size() == 0) return null;
        String pref = preferredLoader != null ? preferredLoader.trim().toLowerCase(Locale.ROOT) : null;

        JsonObject anyRelease = null;
        JsonObject prefBeta = null;
        JsonObject anyBeta = null;
        JsonObject firstValid = null;

        for (JsonElement el : versions) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (pickPrimaryFile(obj) == null) continue;
            if (firstValid == null) firstValid = obj;

            String type = optString(obj, "version_type");
            String normType = type != null ? type.toLowerCase(Locale.ROOT) : "release";
            boolean loaderMatches = pref == null || versionSupportsLoader(obj, pref);

            if ("release".equals(normType)) {
                if (loaderMatches) return obj;
                if (anyRelease == null) anyRelease = obj;
            } else if ("beta".equals(normType)) {
                if (loaderMatches && prefBeta == null) prefBeta = obj;
                if (anyBeta == null) anyBeta = obj;
            }
        }
        if (anyRelease != null) return anyRelease;
        if (prefBeta != null) return prefBeta;
        if (anyBeta != null) return anyBeta;
        return firstValid;
    }

    /** Verifies a Modrinth version JSON object declares {@code cleanMc} and an allowed loader. */
    public static boolean isVersionCompatible(@Nullable JsonObject verObj,
                                              @Nullable String cleanMc,
                                              @Nullable String... allowedLoaders) {
        if (verObj == null) return false;
        if (cleanMc != null && !cleanMc.trim().isEmpty()) {
            if (!verObj.has("game_versions") || !verObj.get("game_versions").isJsonArray()) {
                return false;
            }
            boolean foundMc = false;
            String target = cleanMc.trim();
            for (JsonElement gv : verObj.getAsJsonArray("game_versions")) {
                if (!gv.isJsonNull() && target.equalsIgnoreCase(gv.getAsString().trim())) {
                    foundMc = true;
                    break;
                }
            }
            if (!foundMc) return false;
        }
        if (allowedLoaders != null && allowedLoaders.length > 0) {
            if (!verObj.has("loaders") || !verObj.get("loaders").isJsonArray()) {
                return false;
            }
            Set<String> present = new LinkedHashSet<>();
            for (JsonElement ld : verObj.getAsJsonArray("loaders")) {
                if (!ld.isJsonNull()) {
                    present.add(ld.getAsString().trim().toLowerCase(Locale.ROOT));
                }
            }
            boolean foundLoader = false;
            for (String allowed : allowedLoaders) {
                if (allowed != null && present.contains(allowed.trim().toLowerCase(Locale.ROOT))) {
                    foundLoader = true;
                    break;
                }
            }
            if (!foundLoader) return false;
        }
        return true;
    }

    private static boolean versionSupportsLoader(@NonNull JsonObject verObj, @NonNull String loader) {
        if (!verObj.has("loaders") || !verObj.get("loaders").isJsonArray()) return false;
        for (JsonElement el : verObj.getAsJsonArray("loaders")) {
            if (!el.isJsonNull() && loader.equalsIgnoreCase(el.getAsString().trim())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static JsonObject pickPrimaryFile(@NonNull JsonObject versionObj) {
        if (!versionObj.has("files") || !versionObj.get("files").isJsonArray()) return null;
        JsonArray files = versionObj.getAsJsonArray("files");
        if (files.size() == 0) return null;
        JsonObject fallback = null;
        for (JsonElement el : files) {
            if (!el.isJsonObject()) continue;
            JsonObject fo = el.getAsJsonObject();
            if (optString(fo, "url") == null) continue;
            if (fallback == null) fallback = fo;
            if (fo.has("primary") && !fo.get("primary").isJsonNull() && fo.get("primary").getAsBoolean()) {
                return fo;
            }
        }
        return fallback;
    }

    @Nullable
    private static String getFileSha1(@NonNull JsonObject fileObj) {
        if (!fileObj.has("hashes") || !fileObj.get("hashes").isJsonObject()) return null;
        return optString(fileObj.getAsJsonObject("hashes"), "sha1");
    }

    private static boolean isExistingFileVerified(@NonNull File file, @Nullable String expectedSha1) {
        if (!file.isFile() || file.length() <= 0L) return false;
        if (expectedSha1 == null || expectedSha1.trim().isEmpty()) return false;
        try {
            return expectedSha1.trim().equalsIgnoreCase(computeSha1Hex(file));
        } catch (IOException e) {
            return false;
        }
    }

    private static void downloadVerified(@NonNull String httpsUrl,
                                         @Nullable String expectedSha1,
                                         @NonNull File targetFile) throws IOException {
        validateTrustedHttpsUrl(httpsUrl);

        // Instant zero-network link if SHA-1 is already verified inside .zyrox_pool/
        if (expectedSha1 != null && !expectedSha1.isEmpty()
                && InstanceContentManager.tryLinkFromSharedPool(expectedSha1, targetFile)) {
            return;
        }

        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory " + parent.getAbsolutePath());
        }
        File tmp = new File(parent, targetFile.getName() + ".part");

        Request request = new Request.Builder()
                .url(httpsUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        try (Response response = ZyroxHttpClient.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ZyroxHttpClient.HttpStatusException(
                        response.code(), "Download failed (HTTP " + response.code() + ")");
            }
            if (!"https".equalsIgnoreCase(response.request().url().scheme())) {
                throw new IOException("Blocked insecure HTTPS-to-HTTP redirect");
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty artifact response body");
            }

            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            long total = 0L;
            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(tmp, false)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_ARTIFACT_BYTES) {
                        throw new IOException("Artifact exceeds safe 256 MB size limit");
                    }
                    sha1.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                out.getFD().sync();
            }
            if (total <= 0L) {
                throw new IOException("Downloaded artifact was empty");
            }

            String actual = toHex(sha1.digest());
            if (expectedSha1 != null && !expectedSha1.trim().isEmpty()
                    && !expectedSha1.trim().equalsIgnoreCase(actual)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("SHA-1 verification failed for " + targetFile.getName());
            }
            // Store verified artifact once in .zyrox_pool/<sha1>/ and link into instance
            InstanceContentManager.ingestIntoSharedPoolAndLink(tmp, targetFile, actual);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Download failed: " + e.getMessage(), e);
        }
    }

    private static void validateTrustedHttpsUrl(@NonNull String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed download URL", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Blocked non-HTTPS URL");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("Missing host in download URL");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String suffix : TRUSTED_CDN_SUFFIXES) {
            if (lower.equals(suffix) || lower.endsWith("." + suffix)) {
                return;
            }
        }
        throw new IOException("Blocked untrusted download host: " + lower);
    }

    @NonNull
    private static String httpGet(@NonNull String httpsUrl) throws IOException {
        validateTrustedHttpsUrl(httpsUrl);
        return ZyroxHttpClient.getStringOrThrow(httpsUrl, API_HEADERS);
    }

    @NonNull
    private static String httpPostJson(@NonNull String httpsUrl, @NonNull String json) throws IOException {
        validateTrustedHttpsUrl(httpsUrl);
        String resp = ZyroxHttpClient.postJson(httpsUrl, json, API_HEADERS);
        if (resp == null) {
            throw new IOException("Modrinth update API request failed");
        }
        return resp;
    }

    @NonNull
    public static String computeSha1Hex(@NonNull File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @NonNull
    private static String toHex(@NonNull byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    @NonNull
    public static String sanitizeFileName(@Nullable String candidate, @NonNull String fallback) {
        String raw = (candidate == null || candidate.trim().isEmpty()) ? fallback : candidate.trim();
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        if (slash >= 0) raw = raw.substring(slash + 1);
        raw = raw.replace("..", "_").replaceAll("[^a-zA-Z0-9._+\\-() ]", "_").trim();
        while (raw.startsWith(".")) {
            raw = raw.substring(1).trim();
        }
        if (raw.isEmpty()) {
            raw = fallback.replaceAll("[^a-zA-Z0-9._+\\-]", "_");
        }
        return raw.length() > 120 ? raw.substring(raw.length() - 120) : raw;
    }

    @Nullable
    private static String extractFileNameFromUrl(@NonNull String url) {
        try {
            String path = new URL(url).getPath();
            if (path == null || path.isEmpty()) return null;
            int idx = path.lastIndexOf('/');
            String seg = idx >= 0 ? path.substring(idx + 1) : path;
            return URLDecoder.decode(seg, "UTF-8");
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static String optString(@NonNull JsonObject obj, @NonNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            String val = obj.get(key).getAsString();
            return val != null && !val.trim().isEmpty() ? val.trim() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static Set<String> listExistingJarNamesLower(@NonNull File modsDir) {
        Set<String> set = new HashSet<>();
        File[] files = modsDir.listFiles(f -> f.isFile()
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files != null) {
            for (File f : files) set.add(f.getName().toLowerCase(Locale.ROOT));
        }
        return set;
    }

    private static boolean containsAny(@NonNull Set<String> names, @NonNull String... tokens) {
        for (String n : names) {
            String norm = n.replace("-", "").replace("_", "");
            for (String t : tokens) {
                String tn = t.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
                if (n.contains(t.toLowerCase(Locale.ROOT)) || norm.contains(tn)) return true;
            }
        }
        return false;
    }

    private static void clearDirectoryFiles(@NonNull File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static void copyFile(@NonNull File src, @NonNull File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        }
    }

    private static void notifyVersionChanged() {
        try {
            ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private static String resolveFriendlySlugLabel(@NonNull String slotName, @NonNull String slug) {
        if ("sodium".equalsIgnoreCase(slug)) return "Sodium";
        if ("embeddium".equalsIgnoreCase(slug)) return "Embeddium";
        if ("rubidium".equalsIgnoreCase(slug)) return "Rubidium";
        if ("iris".equalsIgnoreCase(slug)) return "Iris Shaders";
        if ("oculus".equalsIgnoreCase(slug)) return "Oculus";
        return slotName;
    }

    @NonNull
    private static String friendlyDependencyLabel(@Nullable String projectId, @NonNull String fileName) {
        if ("P7dR8mSH".equalsIgnoreCase(projectId)) return "Fabric API";
        if ("qvIfYCYJ".equalsIgnoreCase(projectId)) return "Quilted Fabric API";
        if ("9s6osm5g".equalsIgnoreCase(projectId)) return "Cloth Config";
        if ("lhGA9TYQ".equalsIgnoreCase(projectId)) return "Architectury API";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("fabric-api") || lower.startsWith("fabric_api")) return "Fabric API";
        if (lower.startsWith("qfapi") || lower.startsWith("quilted_fabric_api")) {
            return "Quilted Fabric API";
        }
        if (lower.startsWith("cloth-config")) return "Cloth Config";
        if (lower.startsWith("architectury")) return "Architectury API";
        if (lower.startsWith("yet_another_config_lib") || lower.startsWith("yacl")) return "YACL";
        return fileName;
    }

    @NonNull
    private static String joinComma(@NonNull List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private enum DownloadStatus {
        DOWNLOADED,
        ALREADY_UP_TO_DATE,
        FAILED
    }

    private static final class ResolvedDownload {
        @NonNull final DownloadStatus status;
        @Nullable final File file;
        @Nullable final JsonObject versionObject;
        @Nullable final String projectId;
        @Nullable final String failureReason;

        ResolvedDownload(@NonNull DownloadStatus status,
                         @Nullable File file,
                         @Nullable JsonObject versionObject,
                         @Nullable String projectId,
                         @Nullable String failureReason) {
            this.status = status;
            this.file = file;
            this.versionObject = versionObject;
            this.projectId = projectId;
            this.failureReason = failureReason;
        }

        @NonNull
        static ResolvedDownload failed(@NonNull String reason) {
            return new ResolvedDownload(DownloadStatus.FAILED, null, null, null, reason);
        }
    }
}
