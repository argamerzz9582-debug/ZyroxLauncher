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
