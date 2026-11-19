package git.artdeell.mojo.prefs;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.P;

import static git.artdeell.mojo.Architecture.is32BitsDevice;

import android.app.Activity;
import android.content.*;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

import git.artdeell.mojo.*;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.multirt.MultiRTUtils;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.utils.JREUtils;

import java.io.IOException;

import git.artdeell.mojo.R;

public class LauncherPreferences {
    public static final String PREF_KEY_CURRENT_INSTANCE = "currentInstance";
    public static final String PREF_KEY_SKIP_NOTIFICATION_CHECK = "skipNotificationPermissionCheck";

    public static SharedPreferences DEFAULT_PREF;
    public static String PREF_RENDERER = "opengles2";

	public static boolean PREF_IGNORE_NOTCH = true;
	public static float PREF_BUTTONSIZE = 100f;
	public static float PREF_MOUSESCALE = 1f;
	public static int PREF_LONGPRESS_TRIGGER = 300;
	public static String PREF_DEFAULTCTRL_PATH = Tools.CTRLDEF_FILE;
	public static String PREF_CUSTOM_JAVA_ARGS;
    public static boolean PREF_FORCE_ENGLISH = false;
    public static final String PREF_VERSION_REPOS = git.artdeell.mojo.metadata.MojangVersionProvider.OFFICIAL_MANIFEST_V2_URL;
    public static boolean PREF_DISABLE_GESTURES = false;
    public static boolean PREF_DISABLE_SWAP_HAND = false;
    public static float PREF_MOUSESPEED = 1f;
    public static int PREF_RAM_ALLOCATION;
    public static String PREF_DEFAULT_RUNTIME;
    public static boolean PREF_SUSTAINED_PERFORMANCE = false;
    public static boolean PREF_VIRTUAL_MOUSE_START = false;
    public static boolean PREF_USE_ALTERNATE_SURFACE = true;
    public static boolean PREF_JAVA_SANDBOX = true;
    public static float PREF_SCALE_FACTOR = 1f;

    public static boolean PREF_ENABLE_GYRO = false;
    public static float PREF_GYRO_SENSITIVITY = 1f;
    public static int PREF_GYRO_SAMPLE_RATE = 16;
    public static boolean PREF_GYRO_SMOOTHING = true;
    public static boolean PREF_GYRO_INVERT_X = false;
    public static boolean PREF_GYRO_INVERT_Y = false;

    public static boolean PREF_FORCE_VSYNC = false;

    public static boolean PREF_USE_ANGLE = false;

    public static boolean PREF_BUTTON_ALL_CAPS = true;
    public static boolean PREF_DUMP_SHADERS = false;
    public static float PREF_DEADZONE_SCALE = 1f;
    public static boolean PREF_BIG_CORE_AFFINITY = false;
    public static boolean PREF_ZINK_PREFER_SYSTEM_DRIVER = false;
    public static boolean PREF_VERIFY_MANIFEST = true;
    public static String PREF_DOWNLOAD_SOURCE = "default";
    public static boolean PREF_SKIP_NOTIFICATION_PERMISSION_CHECK = false;
    public static boolean PREF_RAPID_START = true;
    public static boolean PREF_VERIFY_FILES = true;

    public static boolean PREF_FREEDRENO_SYSMEM = false;
    public static boolean PREF_KEYBOARD_AUTOPANNING = true;
    public static boolean PREF_MIGRATION_NOTICE = true;
    public static boolean PREF_ALSOFT_FORCE_OPENSL = false;
    public static boolean PREF_SHOW_MEMORY_WARNING_DIALOG = true;
    public static short PREF_BUTTON_TRANSPARENCY = 100;
    public static boolean PREF_UBWC_WORKAROUND = false;
    public static boolean PREF_DISABLE_BLOCKLIST = false;
    public static String PREF_VULKAN_DRIVER = null;
    public static boolean PREF_ZYROX_ANIMATED_BG = true;
    public static boolean PREF_ZYROX_REDUCED_MOTION = false;
    public static boolean PREF_ZYROX_LOW_EFFECTS = false;
    public static boolean PREF_ZYROX_PERF_HUD = false;
    public static boolean PREF_ZYROX_WARN_MOBILE_DATA = true;
    public static int PREF_ZYROX_DOWNLOAD_THREADS = 6;
    public static int PREF_ZYROX_BACKUP_RETENTION = 5;
    public static String PREF_ZYROX_GRAPHICS_PROFILE = "balanced";
    public static String PREF_ZYROX_OPTIMIZER_MODE = "auto";
    public static boolean PREF_ZYROX_CONTROL_HAPTICS = true;
    public static boolean PREF_ZYROX_EDGE_TOUCH_GUARD = false;
    public static boolean PREF_ZYROX_HUD_SNAP_GRID = true;
    public static float PREF_ZYROX_GYRO_DEADZONE = 1.0f;
    /** GIF scaling filter: {@code "bicubic"} (HD animated scenes) or {@code "nearest"} (pixel art). */
    public static String PREF_ZYROX_GIF_FILTER = "bicubic";
    /** Normalized HUD X coordinate in {@code [0, 1]}, or {@code -1f} for automatic safe placement. */
    public static float PREF_ZYROX_HUD_X = -1f;
    /** Normalized HUD Y coordinate in {@code [0, 1]}, or {@code -1f} for automatic safe placement. */
    public static float PREF_ZYROX_HUD_Y = -1f;
    /** Performance HUD scale percentage in {@code [50, 150]}. */
    public static int PREF_ZYROX_HUD_SCALE = 100;
    /** Performance HUD background glass opacity percentage in {@code [10, 100]}. */
    public static int PREF_ZYROX_HUD_OPACITY = 78;
    /** When true, locks the Performance HUD position and passes gameplay touches straight through. */
    public static boolean PREF_ZYROX_HUD_LOCKED = false;
    /** When true, collapses the Performance HUD into a compact single-row mini-pill. */
    public static boolean PREF_ZYROX_HUD_COMPACT = false;
    /** Enables 120Hz/144Hz unbuffered historical touch sampling and sub-frame aim interpolation. */
    public static boolean PREF_ZYROX_HIGH_RATE_TOUCH = true;
    /** True once the 3-step first-launch Zyrox onboarding wizard has completed or been skipped. */
    public static boolean PREF_ZYROX_ONBOARDING_DONE = false;
    /** Enables context-aware auto-switching between Inventory/GUI helper bar and Combat/Build HUD. */
    public static boolean PREF_ZYROX_CONTEXT_CONTROLS = true;
    /** Remembers whether the user expanded Advanced / Pro Mode inside InstanceEditorFragment. */
    public static boolean PREF_ZYROX_INSTANCE_PRO_MODE = false;
    /** True once the default selected launcher profile has been initialized to Latest Stable Fabric. */
    public static boolean PREF_ZYROX_DEFAULT_FABRIC_READY = false;
    /** Shows the floating hold-to-speak in-game Voice-to-Chat microphone overlay pill. */
    public static boolean PREF_ZYROX_VOICE_CHAT_OVERLAY = false;
    /** Enables real-time PowerManager thermal & battery dynamic resolution scaling in-game. */
    public static boolean PREF_ZYROX_THERMAL_GOVERNOR = true;

    public static final String KEY_ZYROX_GIF_FILTER = "zyroxGifFilter";
    public static final String KEY_ZYROX_HUD_X = "zyroxHudPosX";
    public static final String KEY_ZYROX_HUD_Y = "zyroxHudPosY";
    public static final String KEY_ZYROX_HUD_SCALE = "zyroxHudScale";
    public static final String KEY_ZYROX_HUD_OPACITY = "zyroxHudOpacity";
    public static final String KEY_ZYROX_HUD_LOCKED = "zyroxHudLocked";
    public static final String KEY_ZYROX_HUD_COMPACT = "zyroxHudCompact";
    public static final String KEY_ZYROX_HIGH_RATE_TOUCH = "zyroxHighRateTouch";
    public static final String KEY_ZYROX_ONBOARDING_DONE = "zyroxOnboardingDone";
    public static final String KEY_ZYROX_CONTEXT_CONTROLS = "zyroxContextControls";
    public static final String KEY_ZYROX_INSTANCE_PRO_MODE = "zyroxInstanceProMode";
    public static final String KEY_ZYROX_DEFAULT_FABRIC_READY = "zyroxDefaultFabricReady";
    public static final String KEY_ZYROX_VOICE_CHAT_OVERLAY = "zyroxVoiceChatOverlay";
    public static final String KEY_ZYROX_THERMAL_GOVERNOR = "zyroxThermalGovernor";

    public static void loadPreferences(Context ctx) {
        //Required for CTRLDEF_FILE and MultiRT
        Tools.initStorageConstants(ctx);
        boolean isDevicePowerful = isDevicePowerful(ctx);

        PREF_RENDERER = DEFAULT_PREF.getString("renderer", "opengles2");
        PREF_BUTTONSIZE = DEFAULT_PREF.getInt("buttonscale", 100);
        PREF_MOUSESCALE = DEFAULT_PREF.getInt("mousescale", 100)/100f;
        PREF_MOUSESPEED = ((float)DEFAULT_PREF.getInt("mousespeed",100))/100f;
        PREF_IGNORE_NOTCH = DEFAULT_PREF.getBoolean("ignoreNotch", true);
		PREF_LONGPRESS_TRIGGER = DEFAULT_PREF.getInt("timeLongPressTrigger", 300);
		PREF_DEFAULTCTRL_PATH = DEFAULT_PREF.getString("defaultCtrl", Tools.CTRLDEF_FILE);
        PREF_FORCE_ENGLISH = DEFAULT_PREF.getBoolean("force_english", false);
        PREF_DISABLE_GESTURES = DEFAULT_PREF.getBoolean("disableGestures",false);
        PREF_DISABLE_SWAP_HAND = DEFAULT_PREF.getBoolean("disableDoubleTap", false);
        PREF_RAM_ALLOCATION = DEFAULT_PREF.getInt("allocation", findBestRAMAllocation(ctx));
        PREF_CUSTOM_JAVA_ARGS = DEFAULT_PREF.getString("javaArgs", "");
        PREF_SUSTAINED_PERFORMANCE = DEFAULT_PREF.getBoolean("sustainedPerformance", isDevicePowerful);
        PREF_VIRTUAL_MOUSE_START = DEFAULT_PREF.getBoolean("mouse_start", false);
        PREF_USE_ALTERNATE_SURFACE = DEFAULT_PREF.getBoolean("alternate_surface", isDevicePowerful);
        PREF_JAVA_SANDBOX = DEFAULT_PREF.getBoolean("java_sandbox", true);
        PREF_SCALE_FACTOR = DEFAULT_PREF.getInt("resolutionRatio", findBestResolution(ctx, isDevicePowerful))/100f;
        PREF_ENABLE_GYRO = DEFAULT_PREF.getBoolean("enableGyro", false);
        PREF_GYRO_SENSITIVITY = ((float)DEFAULT_PREF.getInt("gyroSensitivity", 100))/100f;
        PREF_GYRO_SAMPLE_RATE = DEFAULT_PREF.getInt("gyroSampleRate", 16);
        PREF_GYRO_SMOOTHING = DEFAULT_PREF.getBoolean("gyroSmoothing", true);
        PREF_GYRO_INVERT_X = DEFAULT_PREF.getBoolean("gyroInvertX", false);
        PREF_GYRO_INVERT_Y = DEFAULT_PREF.getBoolean("gyroInvertY", false);
        PREF_FORCE_VSYNC = DEFAULT_PREF.getBoolean("force_vsync", isDevicePowerful);
        PREF_USE_ANGLE = DEFAULT_PREF.getBoolean("use_angle", false);
        PREF_BUTTON_ALL_CAPS = DEFAULT_PREF.getBoolean("buttonAllCaps", true);
        PREF_DUMP_SHADERS = DEFAULT_PREF.getBoolean("dump_shaders", false);
        PREF_DEADZONE_SCALE = ((float) DEFAULT_PREF.getInt("gamepad_deadzone_scale", 100))/100f;
        PREF_BIG_CORE_AFFINITY = DEFAULT_PREF.getBoolean("bigCoreAffinity", false);
        PREF_ZINK_PREFER_SYSTEM_DRIVER = DEFAULT_PREF.getBoolean("zinkPreferSystemDriver", !hasVulkanDrivers());
        PREF_DOWNLOAD_SOURCE = DEFAULT_PREF.getString("downloadSource", "default");
        PREF_VERIFY_MANIFEST = DEFAULT_PREF.getBoolean("verifyManifest", true);
        PREF_SKIP_NOTIFICATION_PERMISSION_CHECK = DEFAULT_PREF.getBoolean(PREF_KEY_SKIP_NOTIFICATION_CHECK, false);
        PREF_VERIFY_FILES = DEFAULT_PREF.getBoolean("checkGameFiles", true);
        PREF_RAPID_START = DEFAULT_PREF.getBoolean("fastStartupCheck", true);
        PREF_FREEDRENO_SYSMEM = DEFAULT_PREF.getBoolean("freedrenoSysmem", false);
        PREF_KEYBOARD_AUTOPANNING = DEFAULT_PREF.getBoolean("keyboardAutoPanning", true);
        PREF_MIGRATION_NOTICE = DEFAULT_PREF.getBoolean("migrationNotice", true);
        PREF_ALSOFT_FORCE_OPENSL = DEFAULT_PREF.getBoolean("alsoftForceOpenSL", false);
        PREF_SHOW_MEMORY_WARNING_DIALOG = DEFAULT_PREF.getBoolean("showMemoryWarning", true);
        PREF_BUTTON_TRANSPARENCY = (short) DEFAULT_PREF.getInt("buttonTransparency", 100);
        PREF_UBWC_WORKAROUND = DEFAULT_PREF.getBoolean("ubwcWorkaround", false);
        PREF_DISABLE_BLOCKLIST = DEFAULT_PREF.getBoolean("disableServerBlocklist", true);
        PREF_VULKAN_DRIVER = DEFAULT_PREF.getString("customVulkanDriver", null);
        PREF_ZYROX_ANIMATED_BG = DEFAULT_PREF.getBoolean("zyroxAnimatedBg", true);
        PREF_ZYROX_REDUCED_MOTION = DEFAULT_PREF.getBoolean("zyroxReducedMotion", false);
        PREF_ZYROX_LOW_EFFECTS = DEFAULT_PREF.getBoolean("zyroxLowEffects", false);
        PREF_ZYROX_PERF_HUD = DEFAULT_PREF.getBoolean("zyroxPerfHud", false);
        PREF_ZYROX_WARN_MOBILE_DATA = DEFAULT_PREF.getBoolean("zyroxWarnMobileData", true);
        PREF_ZYROX_DOWNLOAD_THREADS = Math.max(1, Math.min(8, DEFAULT_PREF.getInt("zyroxDownloadThreads", 6)));
        PREF_ZYROX_BACKUP_RETENTION = Math.max(1, Math.min(25, DEFAULT_PREF.getInt("zyroxBackupRetention", 5)));
        PREF_ZYROX_GRAPHICS_PROFILE = DEFAULT_PREF.getString("zyroxGraphicsProfile", "balanced");
        PREF_ZYROX_OPTIMIZER_MODE = git.artdeell.mojo.performance.GameOptimizer.normalizeMode(
                DEFAULT_PREF.getString(git.artdeell.mojo.performance.GameOptimizer.PREF_KEY_MODE, "auto"));
        PREF_ZYROX_CONTROL_HAPTICS = DEFAULT_PREF.getBoolean("zyroxControlHaptics", true);
        PREF_ZYROX_EDGE_TOUCH_GUARD = DEFAULT_PREF.getBoolean("zyroxEdgeTouchGuard", false);
        PREF_ZYROX_HUD_SNAP_GRID = DEFAULT_PREF.getBoolean("zyroxHudSnapGrid", true);
        PREF_ZYROX_GYRO_DEADZONE = Math.max(0f, Math.min(3f,
                DEFAULT_PREF.getInt("zyroxGyroDeadzone", 100) / 100f));
        String rawGifFilter = DEFAULT_PREF.getString(KEY_ZYROX_GIF_FILTER, "bicubic");
        PREF_ZYROX_GIF_FILTER = "nearest".equalsIgnoreCase(rawGifFilter) ? "nearest" : "bicubic";
        PREF_ZYROX_HUD_X = sanitizeHudCoord(DEFAULT_PREF.getFloat(KEY_ZYROX_HUD_X, -1f));
        PREF_ZYROX_HUD_Y = sanitizeHudCoord(DEFAULT_PREF.getFloat(KEY_ZYROX_HUD_Y, -1f));
        PREF_ZYROX_HUD_SCALE = clampHudScale(DEFAULT_PREF.getInt(KEY_ZYROX_HUD_SCALE, 100));
        PREF_ZYROX_HUD_OPACITY = clampHudOpacity(DEFAULT_PREF.getInt(KEY_ZYROX_HUD_OPACITY, 78));
        PREF_ZYROX_HUD_LOCKED = DEFAULT_PREF.getBoolean(KEY_ZYROX_HUD_LOCKED, false);
        PREF_ZYROX_HUD_COMPACT = DEFAULT_PREF.getBoolean(KEY_ZYROX_HUD_COMPACT, false);
        PREF_ZYROX_HIGH_RATE_TOUCH = DEFAULT_PREF.getBoolean(KEY_ZYROX_HIGH_RATE_TOUCH, true);
        PREF_ZYROX_ONBOARDING_DONE = DEFAULT_PREF.getBoolean(KEY_ZYROX_ONBOARDING_DONE, false);
        PREF_ZYROX_CONTEXT_CONTROLS = DEFAULT_PREF.getBoolean(KEY_ZYROX_CONTEXT_CONTROLS, true);
        PREF_ZYROX_INSTANCE_PRO_MODE = DEFAULT_PREF.getBoolean(KEY_ZYROX_INSTANCE_PRO_MODE, false);
        PREF_ZYROX_DEFAULT_FABRIC_READY = DEFAULT_PREF.getBoolean(KEY_ZYROX_DEFAULT_FABRIC_READY, false);
        PREF_ZYROX_VOICE_CHAT_OVERLAY = DEFAULT_PREF.getBoolean(KEY_ZYROX_VOICE_CHAT_OVERLAY, false);
        PREF_ZYROX_THERMAL_GOVERNOR = DEFAULT_PREF.getBoolean(KEY_ZYROX_THERMAL_GOVERNOR, true);
        git.artdeell.mojo.downloader.DownloadQueueManager.getInstance().setConcurrencyLimit(PREF_ZYROX_DOWNLOAD_THREADS);

        String argLwjglLibname = "-Dorg.lwjgl.opengl.libname=";
        for (String arg : JREUtils.parseJavaArguments(PREF_CUSTOM_JAVA_ARGS)) {
            if (arg.startsWith(argLwjglLibname)) {
                // purge arg
                DEFAULT_PREF.edit().putString("javaArgs",
                    PREF_CUSTOM_JAVA_ARGS.replace(arg, "")).apply();
            }
        }
        if(DEFAULT_PREF.contains("defaultRuntime")) {
            PREF_DEFAULT_RUNTIME = DEFAULT_PREF.getString("defaultRuntime","");
        }else{
            if(MultiRTUtils.getRuntimes().isEmpty()) {
                PREF_DEFAULT_RUNTIME = "";
                return;
            }
            PREF_DEFAULT_RUNTIME = MultiRTUtils.getRuntimes().get(0).name;
            LauncherPreferences.DEFAULT_PREF.edit().putString("defaultRuntime",LauncherPreferences.PREF_DEFAULT_RUNTIME).apply();
        }
    }

    /**
     * This functions aims at finding the best default RAM amount,
     * according to the RAM amount of the physical device.
     * Put not enough RAM ? Minecraft will lag and crash.
     * Put too much RAM ?
     * The GC will lag, android won't be able to breathe properly.
     * @param ctx Context needed to get the total memory of the device.
     * @return The best default value found.
     */
    private static int findBestRAMAllocation(Context ctx){
        int deviceRam = Tools.getTotalDeviceMemory(ctx);
        if (deviceRam < 1024) return 296;
        if (deviceRam < 1536) return 448;
        if (deviceRam < 2048) return 656;
        // Limit the max for 32 bits devices more harshly
        if (is32BitsDevice()) return 696;

        if (deviceRam < 3064) return 936;
        if (deviceRam < 4096) return 1144;
        if (deviceRam < 6144) return 1536;
        return 2048; //Default RAM allocation for 64 bits
    }

    /// Find a correct resolution for the device
    ///
    /// Some devices are shipped with a ridiculously high resolution, which can cause performance issues
    /// This function will try to find a resolution that is good enough for the device
    private static int findBestResolution(Context context, boolean isDevicePowerful) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int minSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        int targetSide = isDevicePowerful ? 1080 : 720;
        if (minSide <= targetSide) return 100; // No need to scale down

        float ratio = (100f * targetSide / minSide);
        // The value must match the seekbar values
        int increment = context.getResources().getInteger(R.integer.resolution_seekbar_increment);
        return (int) (Math.ceil(ratio / increment) * increment);
    }

    /// Check if the device is considered powerful.
    /// Powerful devices will have some energy saving tweaks enabled by default
    private static boolean isDevicePowerful(Context context) {
        if (SDK_INT < Build.VERSION_CODES.Q) return false;
        if (Tools.getTotalDeviceMemory(context) <= 4096) return false;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        if (Math.min(metrics.widthPixels, metrics.heightPixels) < 1080) return false;
        if (Runtime.getRuntime().availableProcessors() <= 4) return false;
        if (hasAllCoreSameFreq()) return false;
        return true;
    }

    private static boolean hasAllCoreSameFreq() {
        int coreCount = Runtime.getRuntime().availableProcessors();
        try {
            String freq0 = Tools.read("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq");
            String freqX = Tools.read("/sys/devices/system/cpu/cpu" + (coreCount - 1) + "/cpufreq/cpuinfo_max_freq");
            if(freq0.equals(freqX)) return true;
        } catch (IOException e) {
            Log.e("LauncherPreferences", "Failed to read CPU frequencies", e);
        }
        return false;
    }

    /** Check if the device has a display cutout */
    public static boolean hasNotch(Activity activity) {
        if (Build.VERSION.SDK_INT < P) return false;
        try {
            final Rect cutout;
            if(SDK_INT >= Build.VERSION_CODES.S){
                cutout = activity.getWindowManager().getCurrentWindowMetrics().getWindowInsets().getDisplayCutout().getBoundingRects().get(0);
            } else {
                cutout = activity.getWindow().getDecorView().getRootWindowInsets().getDisplayCutout().getBoundingRects().get(0);
            }
            return cutout.width() != 0 || cutout.height() != 0;
        }catch (Exception e){
            Log.i("NOTCH DETECTION", "No notch detected, or the device if in split screen mode");
            return false;
        }
    }

    public static boolean hasVulkanDrivers() {
        return GpuUtils.getGlInfo().isAdreno();
    }

    /**
     * Returns true when either the user enabled Reduced Motion in Zyrox Settings or Android system
     * animator duration scale is turned off.
     */
    public static boolean isReducedMotionEnabled() {
        if (PREF_ZYROX_REDUCED_MOTION) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return !android.animation.ValueAnimator.areAnimatorsEnabled();
        }
        return false;
    }

    public static boolean isReducedMotionEnabled(@androidx.annotation.Nullable Context ctx) {
        if (isReducedMotionEnabled()) return true;
        if (ctx != null) {
            try {
                float scale = android.provider.Settings.Global.getFloat(
                        ctx.getContentResolver(),
                        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
                return scale <= 0.01f;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static float sanitizeHudCoord(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v) || v < 0f) return -1f;
        return Math.max(0f, Math.min(1f, v));
    }

    public static int clampHudScale(int percent) {
        return Math.max(50, Math.min(150, percent));
    }

    public static int clampHudOpacity(int percent) {
        return Math.max(10, Math.min(100, percent));
    }

    public static void persistGifFilter(@androidx.annotation.NonNull String filterValue) {
        String clean = "nearest".equalsIgnoreCase(filterValue) ? "nearest" : "bicubic";
        PREF_ZYROX_GIF_FILTER = clean;
        if (DEFAULT_PREF != null) {
            DEFAULT_PREF.edit().putString(KEY_ZYROX_GIF_FILTER, clean).apply();
        }
    }

    public static void persistHudPosition(float normalizedX, float normalizedY) {
        PREF_ZYROX_HUD_X = sanitizeHudCoord(normalizedX);
        PREF_ZYROX_HUD_Y = sanitizeHudCoord(normalizedY);
        if (DEFAULT_PREF != null) {
            DEFAULT_PREF.edit()
                    .putFloat(KEY_ZYROX_HUD_X, PREF_ZYROX_HUD_X)
                    .putFloat(KEY_ZYROX_HUD_Y, PREF_ZYROX_HUD_Y)
                    .apply();
        }
    }

    public static void resetHudPosition() {
        PREF_ZYROX_HUD_X = -1f;
        PREF_ZYROX_HUD_Y = -1f;
        if (DEFAULT_PREF != null) {
            DEFAULT_PREF.edit()
                    .putFloat(KEY_ZYROX_HUD_X, -1f)
                    .putFloat(KEY_ZYROX_HUD_Y, -1f)
                    .apply();
        }
    }

    public static void persistHudScale(int scalePercent) {
        PREF_ZYROX_HUD_SCALE = clampHudScale(scalePercent);
        if (DEFAULT_PREF != null) {
            DEFAULT_PREF.edit().putInt(KEY_ZYROX_HUD_SCALE, PREF_ZYROX_HUD_SCALE).apply();
        }
    }

    public static void persistHudOpacity(int opacityPercent) {
        PREF_ZYROX_HUD_OPACITY = clampHudOpacity(opacityPercent);
        if (DEFAULT_PREF != null) {
            DEFAULT_PREF.edit().putInt(KEY_ZYROX_HUD_OPACITY, PREF_ZYROX_HUD_OPACITY).apply();
        }
    }

    public static void persistHudLocked(boolean locked) {
        PREF_ZYROX_HUD_LOCKED = locked;
        if (DEFAULT_PREF != null) {
            DEFAULT_PREF.edit().putBoolean(KEY_ZYROX_HUD_LOCKED, locked).apply();
        }
    }

    public static void persistHudCompact(boolean compact) {
        PREF_ZYROX_HUD_COMPACT = compact;
        if (DEFAULT_PREF != null) {
            DEFAULT_PREF.edit().putBoolean(KEY_ZYROX_HUD_COMPACT, compact).apply();
        }
    }
}
