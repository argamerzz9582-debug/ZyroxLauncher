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

    public static final String PRESET_BATTERY = "battery";
    public static final String PRESET_BALANCED = "balanced";
    public static final String PRESET_MAX_FPS = "max_fps";

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
}
