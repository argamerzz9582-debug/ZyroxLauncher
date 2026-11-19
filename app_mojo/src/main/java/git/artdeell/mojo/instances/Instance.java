package git.artdeell.mojo.instances;

import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.JSONUtils;

public class Instance extends DisplayInstance {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public static final int ARGS_MODE_REPLACE = 0;
    public static final int ARGS_MODE_MERGE_DEFAULT_FIRST = 1;
    public static final int ARGS_MODE_MERGE_INSTANCE_FIRST = 2;
    public static final int ARGS_MODE_LAST = ARGS_MODE_MERGE_INSTANCE_FIRST;

    public static final String VERSION_LATEST_RELEASE = "latest_release";
    public static final String VERSION_LATEST_SNAPSHOT = "latest_snapshot";
    public static final String VERSION_LATEST_FABRIC = "latest_fabric";

    /** Matches both {@code "latest_fabric"} and {@code "latest-fabric"} (case-insensitive). */
    public static boolean isLatestFabricId(@Nullable String versionId) {
        if (versionId == null) return false;
        String trimmed = versionId.trim();
        return VERSION_LATEST_FABRIC.equalsIgnoreCase(trimmed)
                || "latest-fabric".equalsIgnoreCase(trimmed);
    }

    /** Matches both {@code "latest_release"} and {@code "latest-release"} (case-insensitive). */
    public static boolean isLatestReleaseId(@Nullable String versionId) {
        if (versionId == null) return false;
        String trimmed = versionId.trim();
        return VERSION_LATEST_RELEASE.equalsIgnoreCase(trimmed)
                || "latest-release".equalsIgnoreCase(trimmed);
    }

    /** Matches both {@code "latest_snapshot"} and {@code "latest-snapshot"} (case-insensitive). */
    public static boolean isLatestSnapshotId(@Nullable String versionId) {
        if (versionId == null) return false;
        String trimmed = versionId.trim();
        return VERSION_LATEST_SNAPSHOT.equalsIgnoreCase(trimmed)
                || "latest-snapshot".equalsIgnoreCase(trimmed);
    }

    /** Returns {@code true} if {@code versionId} is a dynamic symbolic channel alias. */
    public static boolean isSymbolicVersionId(@Nullable String versionId) {
        return isLatestFabricId(versionId)
                || isLatestReleaseId(versionId)
                || isLatestSnapshotId(versionId);
    }

    public static final String PRESET_BATTERY = "battery";
    public static final String PRESET_BALANCED = "balanced";
    public static final String PRESET_MAX_FPS = "max_fps";

    public static final String LOADER_FABRIC = "fabric";
    public static final String LOADER_QUILT = "quilt";
    public static final String LOADER_NEOFORGE = "neoforge";
    public static final String LOADER_FORGE = "forge";
    public static final String LOADER_VANILLA = "vanilla";

    /** Matches official Mojang releases: classic {@code 1.x(.y)} and year-based {@code 26.x(.y)+}. */
    private static final java.util.regex.Pattern MOJANG_RELEASE_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?:^|[^0-9.])((?:1\\.[0-9]{1,2}|(?:2[6-9]|[3-9][0-9])\\.[0-9]{1,2})(?:\\.[0-9]{1,2})?)(?=$|[^0-9.])");
    /** Generic strict numeric SemVer fallback {@code X.Y(.Z)} after stripping loader tokens. */
    private static final java.util.regex.Pattern GENERIC_MC_SEMVER_PATTERN =
            java.util.regex.Pattern.compile("(?:^|[^0-9.])([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)(?=$|[^0-9.])");
    /** Matches standalone NeoForge version strings such as {@code neoforge-21.4.88} or {@code 20.4.237-beta}. */
    private static final java.util.regex.Pattern NEOFORGE_STANDALONE_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?i)(?:^|-)neoforge-([0-9]{2})\\.([0-9]{1,2})\\.[0-9]+");

    // Core Mojo/Zyrox v1 fields (preserved 100% intact for backward compatibility)
    public InstanceInstaller installer;
    public String renderer;
    public String jvmArgs;
    public int argsMode;
    public String selectedRuntime;
    public String controlLayout;
    public boolean sharedData;

    // Zyrox 2.0 Schema v2 optional extensions (non-destructive defaults on v1 upgrade)
    public int schemaVersion;
    /** Optional per-instance heap allocation in MB; {@code <= 0} inherits global default. */
    public int ramAllocationMb;
    public long createdAtMs;
    public long lastPlayedMs;
    public int launchCount;
    public String lastKnownGoodRenderer;
    public int consecutiveStartupFailures;
    public String notes;
    public String groupTag;
    /**
     * Optional per-instance Vulkan / Adreno Turnip ({@code .adpkg}) driver folder ID;
     * {@code null} or blank inherits global {@code LauncherPreferences.PREF_VULKAN_DRIVER}, while
     * {@code "default"} explicitly forces the device system Vulkan ICD.
     */
    public String vulkanDriver;
    /**
     * When {@code true}, the user explicitly locked {@link #selectedRuntime} in Pro Mode so
     * {@code JavaRuntimeAdvisor} will never overwrite it during silent pre-launch checks.
     */
    public boolean runtimeLocked;
    /**
     * When {@code true}, the user explicitly locked {@link #renderer} in Pro Mode so
     * {@code GraphicsAdvisor} will preserve it during silent pre-launch checks.
     */
    public boolean rendererLocked;
    /**
     * Simple-Mode performance preset ({@code "battery"}, {@code "balanced"}, or {@code "max_fps"});
     * {@code null} inherits {@code "balanced"} / global optimizer mode.
     */
    public String performancePreset;
    /**
     * Optional per-instance resolution scale percentage ({@code 50..100}, e.g. {@code 75} or
     * {@code 100}); {@code 0} inherits the global resolution scale preference.
     */
    public int resolutionScalePercent;
    /**
     * Optional one-shot flag set by Onboarding / OptiFine fallback so the Zero-Lag FPS Pack
     * (and optional Iris shader mod when {@link #pendingAutoIncludeIris} is true) installs
     * automatically once the modloader finishes installing.
     */
    public boolean pendingAutoFpsPack;
    public boolean pendingAutoIncludeIris;
    /** Optional Pro-Mode per-instance environment variable overrides ({@code KEY -> VALUE}). */
    @Nullable
    public java.util.Map<String, String> envVars;

    protected Instance() {
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
    }

    @Override
    protected void sanitize() {
        super.sanitize();
        migrateSchemaIfNeeded();
        sanitizeArgs();
    }

    /**
     * Non-destructive migration from legacy v0/v1 {@code mojo_instance.json} to Schema v2.
     * Never wipes existing profile properties, game directories, or user preferences.
     */
    protected void migrateSchemaIfNeeded() {
        if (schemaVersion < CURRENT_SCHEMA_VERSION) {
            schemaVersion = CURRENT_SCHEMA_VERSION;
        }
        if (ramAllocationMb < 0) {
            ramAllocationMb = 0;
        } else if (ramAllocationMb > 0 && ramAllocationMb < 256) {
            ramAllocationMb = 256;
        } else if (ramAllocationMb > 32768) {
            ramAllocationMb = 32768;
        }
        if (launchCount < 0) launchCount = 0;
        if (consecutiveStartupFailures < 0) consecutiveStartupFailures = 0;
        if (createdAtMs < 0L) createdAtMs = 0L;
        if (lastPlayedMs < 0L) lastPlayedMs = 0L;
        if (createdAtMs == 0L && mInstanceRoot != null && mInstanceRoot.exists()) {
            long mod = mInstanceRoot.lastModified();
            if (mod > 0L) createdAtMs = mod;
        }
        if (vulkanDriver != null) {
            String trimmed = vulkanDriver.trim();
            if (trimmed.isEmpty() || trimmed.contains("..")
                    || trimmed.contains("/") || trimmed.contains("\\")) {
                vulkanDriver = null;
            } else {
                vulkanDriver = trimmed;
            }
        }
        if (performancePreset != null) {
            String p = performancePreset.trim().toLowerCase(java.util.Locale.ROOT);
            if (PRESET_BATTERY.equals(p) || PRESET_BALANCED.equals(p) || PRESET_MAX_FPS.equals(p)) {
                performancePreset = p;
            } else {
                performancePreset = null;
            }
        }
        if (resolutionScalePercent < 0) {
            resolutionScalePercent = 0;
        } else if (resolutionScalePercent > 0 && resolutionScalePercent < 50) {
            resolutionScalePercent = 50;
        } else if (resolutionScalePercent > 100) {
            resolutionScalePercent = 100;
        }
        if (!Tools.isValidString(selectedRuntime)) {
            runtimeLocked = false;
        }
        if (!Tools.isValidString(renderer)) {
            rendererLocked = false;
        }
    }

    /** Returns the normalized Simple-Mode preset ({@code battery}, {@code balanced}, {@code max_fps}). */
    @NonNull
    public String getEffectivePerformancePreset() {
        if (PRESET_BATTERY.equals(performancePreset)
                || PRESET_BALANCED.equals(performancePreset)
                || PRESET_MAX_FPS.equals(performancePreset)) {
            return performancePreset;
        }
        return PRESET_BALANCED;
    }

    /** Returns the effective render resolution scale percentage ({@code 50..100}) for this instance. */
    public int getEffectiveResolutionScalePercent() {
        if (resolutionScalePercent >= 50 && resolutionScalePercent <= 100) {
            return resolutionScalePercent;
        }
        return Math.max(50, Math.min(100, Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f)));
    }

    private void sanitizeArgs() {
        if (argsMode < 0 || argsMode > ARGS_MODE_LAST) {
            argsMode = 0;
            jvmArgs = null;
        }
    }

    /**
     * Atomically writes the current contents of the instance to persistent storage while retaining
     * a rolling {@code .bak} backup for corruption recovery.
     */
    public void write() throws IOException {
        schemaVersion = CURRENT_SCHEMA_VERSION;
        if (createdAtMs <= 0L) {
            createdAtMs = System.currentTimeMillis();
        }
        JSONUtils.writeToFileAtomic(Instances.metadataLocation(mInstanceRoot), this, true);
    }

    /**
     * Try to write the contents of the instance, ignore any exceptions.
     */
    public void maybeWrite() {
        try {
            write();
        } catch (IOException e) {
            Log.e("Instance", "Failed to write instance metadata", e);
        }
    }

    /**
     * Encode the Bitmap as the new profile icon with required encoding settings.
     */
    public void encodeNewIcon(Bitmap bitmap) throws IOException {
        try (FileOutputStream fileOutputStream = new FileOutputStream(getInstanceIconLocation())) {
            bitmap.compress(
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                            ? Bitmap.CompressFormat.WEBP
                            : Bitmap.CompressFormat.WEBP_LOSSY,
                    60,
                    fileOutputStream
            );
        }
    }

    public String getLaunchRenderer() {
        if (Tools.isValidString(renderer)) return renderer;
        return LauncherPreferences.PREF_RENDERER;
    }

    /** Returns {@code true} when this instance specifies an explicit Vulkan / Turnip driver ID. */
    public boolean hasCustomVulkanDriver() {
        return Tools.isValidString(vulkanDriver)
                && !vulkanDriver.contains("..")
                && !vulkanDriver.contains("/")
                && !vulkanDriver.contains("\\");
    }

    /**
     * Resolves the effective Vulkan / Turnip driver ID for this instance (per-instance override
     * when present, otherwise {@link LauncherPreferences#PREF_DRIVER}).
     */
    @NonNull
    public String getVulkanDriver() {
        if (hasCustomVulkanDriver()) return vulkanDriver;
        return Tools.isValidString(LauncherPreferences.PREF_VULKAN_DRIVER)
                ? LauncherPreferences.PREF_VULKAN_DRIVER
                : git.artdeell.mojo.vulkan.DriverManager.SYSTEM_DRIVER_TOKEN;
    }

    /**
     * Resolves the effective heap allocation in MB for this instance (per-instance override if set,
     * otherwise the user's global RAM preference).
     */
    public int getEffectiveRamAllocationMb() {
        if (ramAllocationMb >= 256) return ramAllocationMb;
        return Math.max(256, LauncherPreferences.PREF_RAM_ALLOCATION);
    }

    public String getLaunchArgs() {
        String globalArgs = LauncherPreferences.PREF_CUSTOM_JAVA_ARGS != null
                ? LauncherPreferences.PREF_CUSTOM_JAVA_ARGS : "";
        if (!Tools.isValidString(jvmArgs)) return globalArgs;
        switch (argsMode) {
            case ARGS_MODE_REPLACE:
                return jvmArgs;
            case ARGS_MODE_MERGE_DEFAULT_FIRST:
                return (globalArgs + " " + jvmArgs).trim();
            case ARGS_MODE_MERGE_INSTANCE_FIRST:
                return (jvmArgs + " " + globalArgs).trim();
            default:
                return jvmArgs;
        }
    }

    public String getLaunchControls() {
        if (!Tools.isValidString(controlLayout)) return LauncherPreferences.PREF_DEFAULTCTRL_PATH;
        // Reject traversal characters in control layout filename
        if (controlLayout.contains("..") || controlLayout.contains("/") || controlLayout.contains("\\")) {
            return LauncherPreferences.PREF_DEFAULTCTRL_PATH;
        }
        return Tools.CTRLMAP_PATH + "/" + controlLayout;
    }

    public File getGameDirectory() {
        if (sharedData) return Instances.SHARED_DATA_DIRECTORY;
        return mInstanceRoot;
    }

    @Nullable
    public File getInstanceRoot() {
        return mInstanceRoot;
    }

    /** Marks a game launch attempt for last-played metadata and renderer crash-loop detection. */
    public void recordLaunchStarted(@NonNull String activeRenderer) {
        this.lastPlayedMs = System.currentTimeMillis();
        this.launchCount = Math.max(0, this.launchCount) + 1;
        this.consecutiveStartupFailures = Math.max(0, this.consecutiveStartupFailures) + 1;
        maybeWrite();
    }

    /** Called once the native Minecraft game window initializes cleanly. */
    public void recordLaunchWindowReady(@NonNull String activeRenderer) {
        this.consecutiveStartupFailures = 0;
        if (Tools.isValidString(activeRenderer)) {
            this.lastKnownGoodRenderer = activeRenderer;
        }
        maybeWrite();
    }

    /**
     * Resolves the clean base Mojang Minecraft version (e.g. {@code "1.21.4"}, {@code "1.20.1"},
     * {@code "26.1.2"}) for Modrinth / CurseForge queries — never returning dirty loader wrappers
     * such as {@code "fabric-loader-0.16.9-1.21.4"}, {@code "neoforge-21.4.88"}, or
     * {@code "1.21.4-OptiFine_HD_U_J2"}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Symbolic channel aliases ({@code latest_release}, {@code latest_snapshot},
     *       {@code latest_fabric}) via synced Mojang / Fabric metadata catalogs.</li>
     *   <li>On-disk {@code versions/<id>/<id>.json} {@code inheritsFrom} chain (bounded to 4 hops)
     *       and manifest {@code clientVersion} / {@code id} metadata.</li>
     *   <li>Deterministic loader-prefix stripping, NeoForge version mapping, and regex extraction.</li>
     * </ol>
     */
    @Nullable
    public String getCleanMinecraftVersion() {
        return resolveCleanMinecraftVersion(this.versionId);
    }

    /** Static variant of {@link #getCleanMinecraftVersion()} accepting any raw {@code versionId}. */
    @Nullable
    public static String resolveCleanMinecraftVersion(@Nullable String rawVersionId) {
        if (rawVersionId == null) return null;
        String trimmed = rawVersionId.trim();
        if (trimmed.isEmpty()) return null;

        // 1. Resolve symbolic channel aliases against cached official catalogs
        if (isSymbolicVersionId(trimmed)) {
            return resolveSymbolicMinecraftVersion(trimmed);
        }

        // 2. Inspect local version manifest JSON inheritsFrom chain if installed on disk
        String inherited = readInheritedMinecraftVersionFromDisk(trimmed);
        if (inherited != null) {
            return inherited;
        }

        // 3. Deterministic loader-aware string extraction
        return extractCleanMinecraftVersion(trimmed);
    }

    /**
     * Pure deterministic Minecraft version extractor that strips Fabric, Quilt, Legacy Fabric,
     * Forge, NeoForge, and OptiFine loader version segments before matching the true base game
     * version (safe in unit tests without filesystem access).
     */
    @Nullable
    public static String extractCleanMinecraftVersion(@Nullable String rawVersionId) {
        if (rawVersionId == null) return null;
        String s = rawVersionId.trim();
        if (s.isEmpty() || isSymbolicVersionId(s)) return null;

        String lower = s.toLowerCase(java.util.Locale.ROOT);

        // Fabric / Quilt / Legacy Fabric: "<kind>-loader-<loaderVer>-<mcVer>"
        String[] fabricPrefixes = {
                "legacy-fabric-loader-",
                "fabric-loader-",
                "quilt-loader-"
        };
        for (String prefix : fabricPrefixes) {
            if (lower.startsWith(prefix) && s.length() > prefix.length()) {
                String rest = s.substring(prefix.length());
                // Skip "<loaderVer>-" (loader version starts with digits, e.g. 0.16.9 or 1.0.0+beta.1)
                int dash = rest.indexOf('-');
                while (dash > 0 && dash + 1 < rest.length()) {
                    String after = rest.substring(dash + 1);
                    String candidate = matchMojangOrGenericVersion(after);
                    if (candidate != null && after.startsWith(candidate)) {
                        return candidate;
                    }
                    dash = rest.indexOf('-', dash + 1);
                }
                int lastDash = rest.lastIndexOf('-');
                if (lastDash > 0 && lastDash + 1 < rest.length()) {
                    String tail = matchMojangOrGenericVersion(rest.substring(lastDash + 1));
                    if (tail != null) return tail;
                }
            }
        }

        // Forge / NeoForge with explicit MC prefix: "1.20.1-forge-47.3.0", "1.21.1-neoforge-21.1.72",
        // or "forge-1.20.1-47.3.0"
        int forgeIdx = lower.indexOf("-forge-");
        if (forgeIdx > 0) {
            String pre = matchMojangOrGenericVersion(s.substring(0, forgeIdx));
            if (pre != null) return pre;
        }
        int neoIdx = lower.indexOf("-neoforge-");
        if (neoIdx > 0) {
            String pre = matchMojangOrGenericVersion(s.substring(0, neoIdx));
            if (pre != null) return pre;
        }
        if (lower.startsWith("forge-") && s.length() > 6) {
            String afterForge = s.substring(6);
            int nextDash = afterForge.indexOf('-');
            String firstToken = nextDash > 0 ? afterForge.substring(0, nextDash) : afterForge;
            String mc = matchMojangRelease(firstToken);
            if (mc != null) return mc;
            if (nextDash > 0 && nextDash + 1 < afterForge.length()) {
                String tailMc = matchMojangRelease(afterForge.substring(nextDash + 1));
                if (tailMc != null) return tailMc;
            }
        }

        // Standalone NeoForge scheme: "neoforge-21.4.88" -> "1.21.4", "neoforge-21.0.167" -> "1.21",
        // "neoforge-20.4.237" -> "1.20.4", "neoforge-26.1.12" -> "26.1"
        java.util.regex.Matcher neoMatcher = NEOFORGE_STANDALONE_PATTERN.matcher(s);
        if (neoMatcher.find()) {
            try {
                int neoMajor = Integer.parseInt(neoMatcher.group(1));
                int neoMinor = Integer.parseInt(neoMatcher.group(2));
                if (neoMajor >= 20 && neoMajor <= 25) {
                    return neoMinor == 0 ? ("1." + neoMajor) : ("1." + neoMajor + "." + neoMinor);
                } else if (neoMajor >= 26) {
                    return neoMajor + "." + neoMinor;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // OptiFine: "1.21.4-OptiFine_HD_U_J2" or "OptiFine_1.20.1_HD_U_I6"
        int ofIdx = lower.indexOf("-optifine");
        if (ofIdx > 0) {
            String pre = matchMojangOrGenericVersion(s.substring(0, ofIdx));
            if (pre != null) return pre;
        }

        // Standard Mojang release pattern (1.x[.y] or 26.x[.y]+)
        String mojang = matchMojangRelease(s);
        if (mojang != null) {
            return mojang;
        }

        // Official snapshot tags (e.g. "24w44a", "1.21.4-pre2", "1.21.4-rc1")
        if (git.artdeell.mojo.metadata.CurseForgeProvider.isMinecraftVersionTag(s)) {
            int preDash = s.indexOf('-');
            if (preDash > 0) {
                String base = matchMojangRelease(s.substring(0, preDash));
                if (base != null) return base;
            }
            return s;
        }

        // Fallback strict numeric X.Y(.Z) when string is a clean version token
        if (!lower.contains("forge") && !lower.contains("loader")) {
            java.util.regex.Matcher generic = GENERIC_MC_SEMVER_PATTERN.matcher(s);
            if (generic.find()) {
                return generic.group(1);
            }
        }
        return null;
    }

    @Nullable
    private static String matchMojangRelease(@NonNull String text) {
        java.util.regex.Matcher m = MOJANG_RELEASE_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    @Nullable
    private static String matchMojangOrGenericVersion(@NonNull String text) {
        String mojang = matchMojangRelease(text);
        if (mojang != null) return mojang;
        java.util.regex.Matcher g = GENERIC_MC_SEMVER_PATTERN.matcher(text);
        return g.find() ? g.group(1) : null;
    }

    @Nullable
    private static String resolveSymbolicMinecraftVersion(@NonNull String symbolicId) {
        try {
            git.artdeell.mojo.metadata.VersionMetadataRepository repo =
                    git.artdeell.mojo.metadata.VersionMetadataRepository.getInstance();
            git.artdeell.mojo.JVersionList catalog = repo.getCurrentCatalog();
            if (isLatestSnapshotId(symbolicId)) {
                String snap = git.artdeell.mojo.metadata.MojangVersionProvider.findLatestByType(
                        catalog, git.artdeell.mojo.metadata.MojangVersionProvider.TYPE_SNAPSHOT);
                if (snap != null && !snap.trim().isEmpty()) {
                    String clean = extractCleanMinecraftVersion(snap.trim());
                    return clean != null ? clean : snap.trim();
                }
            }
            String latestRelease = git.artdeell.mojo.metadata.MojangVersionProvider.findLatestByType(
                    catalog, git.artdeell.mojo.metadata.MojangVersionProvider.TYPE_RELEASE);
            if (isLatestFabricId(symbolicId)) {
                try {
                    git.artdeell.mojo.modding.fabric.FabricVersion[] fVers =
                            repo.getFabricProvider().getGameVersions(false);
                    String picked = git.artdeell.mojo.modding.fabric.FabriclikeUtils
                            .selectLatestStableGameVersion(fVers, latestRelease);
                    if (picked != null && !picked.trim().isEmpty()) {
                        return picked.trim();
                    }
                } catch (Throwable ignored) {
                }
            }
            if (latestRelease != null && !latestRelease.trim().isEmpty()) {
                return latestRelease.trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static String readInheritedMinecraftVersionFromDisk(@NonNull String versionId) {
        if (Tools.DIR_HOME_VERSION == null
                || versionId.contains("..")
                || versionId.contains("/")
                || versionId.contains("\\")) {
            return null;
        }
        String cursor = versionId;
        for (int hop = 0; hop < 4 && cursor != null && !cursor.isEmpty(); hop++) {
            File jsonFile = new File(Tools.DIR_HOME_VERSION, cursor + "/" + cursor + ".json");
            if (!jsonFile.isFile() || jsonFile.length() <= 0L || jsonFile.length() > 4L * 1024L * 1024L) {
                break;
            }
            try {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser
                        .parseString(Tools.read(jsonFile.getAbsolutePath()))
                        .getAsJsonObject();
                if (obj.has("inheritsFrom") && !obj.get("inheritsFrom").isJsonNull()) {
                    String parent = obj.get("inheritsFrom").getAsString().trim();
                    if (!parent.isEmpty() && !parent.equals(cursor)) {
                        if (git.artdeell.mojo.metadata.CurseForgeProvider.isReleaseMinecraftVersion(parent)) {
                            return parent;
                        }
                        cursor = parent;
                        continue;
                    }
                }
                if (obj.has("clientVersion") && !obj.get("clientVersion").isJsonNull()) {
                    String cv = extractCleanMinecraftVersion(obj.get("clientVersion").getAsString());
                    if (cv != null) return cv;
                }
                if (obj.has("id") && !obj.get("id").isJsonNull()) {
                    String idVal = obj.get("id").getAsString().trim();
                    if (git.artdeell.mojo.metadata.CurseForgeProvider.isReleaseMinecraftVersion(idVal)) {
                        return idVal;
                    }
                    String extracted = extractCleanMinecraftVersion(idVal);
                    if (extracted != null) return extracted;
                }
                break;
            } catch (Throwable ignored) {
                break;
            }
        }
        if (!cursor.equals(versionId)) {
            return extractCleanMinecraftVersion(cursor);
        }
        return null;
    }

    /**
     * Detects this instance's active modloader family: {@code "fabric"}, {@code "quilt"},
     * {@code "neoforge"}, {@code "forge"}, or {@code "vanilla"}.
     */
    @NonNull
    public String getDetectedModLoader() {
        return detectModLoader(this.versionId);
    }

    /**
     * Deterministically resolves the canonical Modrinth loader slug ({@code fabric}, {@code quilt},
     * {@code neoforge}, {@code forge}, or {@code vanilla}) from a {@code versionId} and, when
     * needed, its on-disk {@code versions/<id>/<id>.json} metadata.
     */
    @NonNull
    public static String detectModLoader(@Nullable String versionId) {
        if (versionId == null) return LOADER_VANILLA;
        String trimmed = versionId.trim();
        if (trimmed.isEmpty()) return LOADER_VANILLA;
        if (isLatestFabricId(trimmed)) return LOADER_FABRIC;
        if (isLatestReleaseId(trimmed) || isLatestSnapshotId(trimmed)) return LOADER_VANILLA;

        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("quilt")) return LOADER_QUILT;
        if (lower.contains("fabric")) return LOADER_FABRIC;
        if (lower.contains("neoforge")) return LOADER_NEOFORGE;
        if (lower.contains("forge")) return LOADER_FORGE;

        // Inspect on-disk version JSON mainClass & library coordinates for custom-named profiles
        if (Tools.DIR_HOME_VERSION != null
                && !trimmed.contains("..")
                && !trimmed.contains("/")
                && !trimmed.contains("\\")) {
            File jsonFile = new File(Tools.DIR_HOME_VERSION, trimmed + "/" + trimmed + ".json");
            if (jsonFile.isFile() && jsonFile.length() > 0L && jsonFile.length() <= 4L * 1024L * 1024L) {
                try {
                    String raw = Tools.read(jsonFile.getAbsolutePath()).toLowerCase(java.util.Locale.ROOT);
                    if (raw.contains("org.quiltmc:quilt-loader") || raw.contains("quiltloader")) {
                        return LOADER_QUILT;
                    }
                    if (raw.contains("net.fabricmc:fabric-loader") || raw.contains("knotclient")) {
                        return LOADER_FABRIC;
                    }
                    if (raw.contains("net.neoforged") || raw.contains("neoforge")) {
                        return LOADER_NEOFORGE;
                    }
                    if (raw.contains("net.minecraftforge") || raw.contains("fmlclient")
                            || raw.contains("forgeclient")) {
                        return LOADER_FORGE;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return LOADER_VANILLA;
    }

    /** Formats a loader slug into a user-facing label (e.g. {@code "Fabric"}, {@code "NeoForge"}). */
    @NonNull
    public static String formatLoaderDisplayName(@Nullable String loader) {
        if (loader == null) return "Vanilla";
        switch (loader.trim().toLowerCase(java.util.Locale.ROOT)) {
            case LOADER_FABRIC: return "Fabric";
            case LOADER_QUILT: return "Quilt";
            case LOADER_NEOFORGE: return "NeoForge";
            case LOADER_FORGE: return "Forge";
            case LOADER_VANILLA:
            default: return "Vanilla";
        }
    }

    /**
     * Returns {@code true} if {@code cleanMcVersion} supports official Fabric Loader installation
     * (Minecraft {@code 1.14+} and all year-based {@code 26.x+} releases).
     */
    public static boolean supportsFabricLoader(@Nullable String cleanMcVersion) {
        if (cleanMcVersion == null || cleanMcVersion.trim().isEmpty()) return false;
        String[] parts = cleanMcVersion.trim().split("\\.");
        if (parts.length < 2) return false;
        try {
            int major = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
            int minor = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
            if (major >= 26) return true;
            return major == 1 && minor >= 14;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
