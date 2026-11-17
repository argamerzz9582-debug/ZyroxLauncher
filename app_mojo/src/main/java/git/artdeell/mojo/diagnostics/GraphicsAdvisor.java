package git.artdeell.mojo.diagnostics;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import git.artdeell.mojo.Architecture;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.game.renderer.def.Renderers;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.utils.GpuUtils;

/**
 * Zyrox 2.0 Graphics Center & Renderer Intelligence Advisor.
 *
 * <p>Inspects real device GPU, OpenGL ES, Vulkan, CPU, and RAM capabilities; manages named graphics
 * profiles applied only after explicit user confirmation; supports last-known-good renderer fallback
 * recovery; and exports clean, redacted device graphics diagnostics.
 */
public final class GraphicsAdvisor {

    public enum Profile {
        SAFE_COMPATIBILITY(
                "safe",
                "Safe Compatibility",
                75,
                false,
                true,
                true,
                false,
                "Conservative 75% render scale, VSync on, low UI effects, and stable fallback rendering for troubleshooting."
        ),
        BALANCED(
                "balanced",
                "Balanced (Recommended)",
                85,
                true,
                true,
                false,
                true,
                "Balanced 85% render scale, VSync enabled, smooth stability and image clarity on most phones."
        ),
        PERFORMANCE(
                "performance",
                "Performance",
                70,
                true,
                false,
                true,
                false,
                "Lower 70% render scale, uncapped VSync, sustained performance mode, and lightweight UI overhead."
        ),
        QUALITY(
                "quality",
                "Quality",
                100,
                true,
                true,
                false,
                true,
                "Native 100% display resolution, VSync enabled, and full visual fidelity for flagship GPUs."
        ),
        BATTERY_SAVER(
                "battery",
                "Battery Saver",
                65,
                false,
                true,
                true,
                false,
                "Efficient 65% render scale, VSync frame pacing, and static launcher backdrop to reduce heat and battery drain."
        );

        @NonNull public final String id;
        @NonNull public final String title;
        public final int resolutionPercent;
        public final boolean sustainedPerformance;
        public final boolean forceVsync;
        public final boolean lowEffects;
        public final boolean animatedBg;
        @NonNull public final String description;

        Profile(@NonNull String id,
                @NonNull String title,
                int resolutionPercent,
                boolean sustainedPerformance,
                boolean forceVsync,
                boolean lowEffects,
                boolean animatedBg,
                @NonNull String description) {
            this.id = id;
            this.title = title;
            this.resolutionPercent = resolutionPercent;
            this.sustainedPerformance = sustainedPerformance;
            this.forceVsync = forceVsync;
            this.lowEffects = lowEffects;
            this.animatedBg = animatedBg;
            this.description = description;
        }

        @Nullable
        public static Profile fromId(@Nullable String id) {
            if (id == null) return null;
            for (Profile p : values()) {
                if (p.id.equalsIgnoreCase(id)) return p;
            }
            return null;
        }
    }

    private GraphicsAdvisor() {
    }

    /**
     * Applies a graphics profile after explicit user confirmation. Never modifies saves, worlds, or mods.
     */
    public static void applyProfile(@NonNull Profile profile) {
        if (LauncherPreferences.DEFAULT_PREF == null) return;
        LauncherPreferences.PREF_SCALE_FACTOR = profile.resolutionPercent / 100f;
        LauncherPreferences.PREF_SUSTAINED_PERFORMANCE = profile.sustainedPerformance;
        LauncherPreferences.PREF_FORCE_VSYNC = profile.forceVsync;
        LauncherPreferences.PREF_ZYROX_LOW_EFFECTS = profile.lowEffects;
        LauncherPreferences.PREF_ZYROX_ANIMATED_BG = profile.animatedBg;
        LauncherPreferences.PREF_ZYROX_REDUCED_MOTION = (profile == Profile.BATTERY_SAVER);
        LauncherPreferences.PREF_ZYROX_GRAPHICS_PROFILE = profile.id;

        LauncherPreferences.DEFAULT_PREF.edit()
                .putInt("resolutionRatio", profile.resolutionPercent)
                .putBoolean("sustainedPerformance", profile.sustainedPerformance)
                .putBoolean("force_vsync", profile.forceVsync)
                .putBoolean("zyroxLowEffects", profile.lowEffects)
                .putBoolean("zyroxAnimatedBg", profile.animatedBg)
                .putBoolean("zyroxReducedMotion", LauncherPreferences.PREF_ZYROX_REDUCED_MOTION)
                .putString("zyroxGraphicsProfile", profile.id)
                .apply();
    }

    /**
     * Recommends the safest compatible renderer tag based on real device GPU features without making
     * exaggerated performance claims.
     */
    @NonNull
    public static String recommendRendererTag(int glesMajor, boolean hasVulkan, boolean modernMinecraft) {
        if (modernMinecraft) {
            if (glesMajor >= 3) return Renderers.LTW_RENDERER;
            if (hasVulkan) return Renderers.ZINK_RENDERER;
            return Renderers.GL4ES_RENDERER;
        }
        return Renderers.GL4ES_RENDERER;
    }

    /**
     * Builds a truthful, redacted hardware and renderer diagnostic report for user inspection or copy.
     */
    @NonNull
    public static String buildDiagnosticsSummary(@NonNull Context context) {
        GpuUtils.GLInfo gl = GpuUtils.getGlInfo();
        boolean vulkan = GpuUtils.checkVulkanSupport(context.getPackageManager());
        int totalRam = Tools.getTotalDeviceMemory(context);
        int freeRam = Tools.getFreeDeviceMemory(context);
        int cores = Runtime.getRuntime().availableProcessors();
        String abi = Architecture.archAsString(Architecture.getDeviceArchitecture());
        int resPct = Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f);

        String recommendedModern = recommendRendererTag(gl.glesMajorVersion, vulkan, true);
        StringBuilder sb = new StringBuilder(512);
        sb.append("Zyrox Graphics & Device Diagnostics\n");
        sb.append("───────────────────────────────────\n");
        sb.append("Device Model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android SDK: API ").append(Build.VERSION.SDK_INT)
                .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n");
        sb.append("CPU / ABI: ").append(abi).append(" • ").append(cores).append(" cores\n");
        sb.append("Memory: ").append(freeRam).append(" MB free / ").append(totalRam)
                .append(" MB total (Default Heap: ").append(LauncherPreferences.PREF_RAM_ALLOCATION).append(" MB)\n");
        sb.append("GPU Vendor: ").append(gl.vendor).append('\n');
        sb.append("GPU Renderer: ").append(gl.renderer).append('\n');
        sb.append("OpenGL ES: ").append(gl.glesMajorVersion).append(".x");
        if (gl.forcedMsaa) sb.append(" (4x MSAA forced in Developer Options — disable for LTW!)");
        sb.append('\n');
        sb.append("Vulkan Support: ").append(vulkan ? "Supported" : "Not reported").append('\n');
        sb.append("Active Profile: ").append(LauncherPreferences.PREF_ZYROX_GRAPHICS_PROFILE.toUpperCase(Locale.ROOT))
                .append(" • Scale ").append(resPct).append("% • VSync ")
                .append(LauncherPreferences.PREF_FORCE_VSYNC ? "ON" : "OFF").append('\n');
        sb.append("Global Renderer: ").append(LauncherPreferences.PREF_RENDERER).append('\n');
        sb.append("Recommended (1.17+ / Sodium): ").append(recommendedModern).append('\n');
        sb.append("Recommended (Legacy <=1.16.5): ").append(Renderers.GL4ES_RENDERER).append('\n');
        if (gl.isAdreno()) {
            sb.append("GPU Note: Qualcomm Adreno detected — LTW works well for 1.17+/Sodium; Freedreno/Zink available for Vulkan/shader workloads.");
        } else if (gl.isArm()) {
            sb.append("GPU Note: ARM Mali/Immortalis detected — LTW recommended for 1.17+ and Sodium; GL4ES recommended for <=1.16.5.");
        } else {
            sb.append("GPU Note: Use LTW for modern OpenGL 3.2+ Minecraft releases or GL4ES for legacy versions.");
        }
        return LogRedactor.redact(sb.toString());
    }
}
