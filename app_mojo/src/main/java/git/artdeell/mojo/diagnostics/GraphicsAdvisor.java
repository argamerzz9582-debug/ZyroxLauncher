package git.artdeell.mojo.diagnostics;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

import git.artdeell.mojo.Architecture;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.game.renderer.RendererCache;
import git.artdeell.mojo.game.renderer.def.Renderers;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.metadata.JavaRuntimeAdvisor;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.utils.GpuUtils.GpuArchitecture;
import git.artdeell.mojo.vulkan.DriverManager;
import git.artdeell.mojo.vulkan.driver.BuiltinDriver;
import git.artdeell.mojo.vulkan.driver.Driver;

/**
 * Zyrox Graphics Center &amp; Hardware Architecture Auto-Preset Advisor.
 *
 * <p>Detects Qualcomm Adreno (8xx / 7xx / 6xx / legacy), ARM Immortalis / Mali Valhall / Bifrost,
 * Samsung Xclipse (RDNA), PowerVR, and ChromeOS/VirGL GPUs via {@link GpuUtils} and computes/applies
 * hardware-calibrated Zink (Turnip/Vulkan), Freedreno (KGSL), LTW (GLES 3), or GL4ES presets.
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

    /** Complete GPU-architecture-matched renderer &amp; driver tuning preset. */
    public static final class RendererPreset {
        @NonNull public final GpuArchitecture architecture;
        @NonNull public final String presetName;
        @NonNull public final String rendererId;
        @NonNull public final Profile baseProfile;
        public final int recommendedScalePercent;
        public final boolean enableFreedrenoSysmem;
        public final boolean enableUbwcWorkaround;
        public final boolean preferSystemVulkan;
        public final boolean recommendsTurnipInstall;
        @NonNull public final String summary;

        public RendererPreset(@NonNull GpuArchitecture architecture,
                              @NonNull String presetName,
                              @NonNull String rendererId,
                              @NonNull Profile baseProfile,
                              int recommendedScalePercent,
                              boolean enableFreedrenoSysmem,
                              boolean enableUbwcWorkaround,
                              boolean preferSystemVulkan,
                              boolean recommendsTurnipInstall,
                              @NonNull String summary) {
            this.architecture = architecture;
            this.presetName = presetName;
            this.rendererId = rendererId;
            this.baseProfile = baseProfile;
            this.recommendedScalePercent = recommendedScalePercent;
            this.enableFreedrenoSysmem = enableFreedrenoSysmem;
            this.enableUbwcWorkaround = enableUbwcWorkaround;
            this.preferSystemVulkan = preferSystemVulkan;
            this.recommendsTurnipInstall = recommendsTurnipInstall;
            this.summary = summary;
        }
    }

    private GraphicsAdvisor() {
    }

    /**
     * Pure deterministic preset computation across all GPU micro-architectures.
     */
    @NonNull
    public static RendererPreset computeOptimalPreset(@NonNull GpuArchitecture arch,
                                                      @Nullable String rawRendererString,
                                                      int glesMajor,
                                                      boolean hasVulkan,
                                                      boolean hasMesaLibrary,
                                                      boolean hasCustomTurnipInstalled,
                                                      boolean hasShadersInstalled,
                                                      boolean modernMinecraft,
                                                      int totalRamMb) {
        boolean sysmem = GpuUtils.needsAdrenoSysmemWorkaround(rawRendererString);
        boolean ubwc = GpuUtils.needsAdrenoUbwcWorkaround(rawRendererString);
        boolean highRam = totalRamMb >= 7500;
        boolean lowRam = totalRamMb > 0 && totalRamMb < 4500;

        // Legacy Minecraft (<= 1.16.5 without OpenGL 3.2 core profile)
        if (!modernMinecraft && !hasShadersInstalled) {
            int scale = arch.isFlagshipTier() && highRam ? 100 : (lowRam ? 75 : 85);
            return new RendererPreset(
                    arch,
                    "Legacy GL4ES Fast-Path",
                    Renderers.GL4ES_RENDERER,
                    scale >= 95 ? Profile.QUALITY : Profile.BALANCED,
                    scale,
                    sysmem,
                    ubwc,
                    false,
                    false,
                    arch.displayName + ": selected GL4ES for fixed-function/GL 2.1 Minecraft at "
                            + scale + "% resolution scale."
            );
        }

        switch (arch) {
            case ADRENO_8XX:
            case ADRENO_7XX: {
                int scale = highRam ? 100 : 90;
                if (hasVulkan && hasMesaLibrary && (hasCustomTurnipInstalled || hasShadersInstalled)) {
                    return new RendererPreset(
                            arch,
                            "Adreno Flagship Zink + Turnip (GL 4.6)",
                            Renderers.ZINK_RENDERER,
                            Profile.QUALITY,
                            scale,
                            false,
                            false,
                            !hasCustomTurnipInstalled,
                            !hasCustomTurnipInstalled,
                            arch.displayName + ": applied Mesa Zink (OpenGL 4.6 over Vulkan) at "
                                    + scale + "% scale"
                                    + (hasCustomTurnipInstalled
                                    ? " using active Adreno Turnip driver."
                                    : " — install a Turnip .adpkg in Driver Store for peak shader throughput.")
                    );
                }
                return new RendererPreset(
                        arch,
                        "Adreno Flagship LTW Direct (GLES 3)",
                        glesMajor >= 3 ? Renderers.LTW_RENDERER : Renderers.ZINK_RENDERER,
                        Profile.QUALITY,
                        scale,
                        false,
                        false,
                        false,
                        hasVulkan && !hasCustomTurnipInstalled,
                        arch.displayName + ": applied LTW high-FPS OpenGL ES 3.x pipeline at "
                                + scale + "% scale (install Turnip .adpkg in Driver Store to unlock Zink GL 4.6 shaders)."
                );
            }

            case ADRENO_6XX: {
                int scale = lowRam ? 75 : 85;
                if (hasVulkan && hasMesaLibrary && hasCustomTurnipInstalled) {
                    return new RendererPreset(
                            arch,
                            "Adreno 6xx Zink + Turnip Tuned",
                            Renderers.ZINK_RENDERER,
                            Profile.BALANCED,
                            scale,
                            sysmem,
                            ubwc,
                            false,
                            false,
                            arch.displayName + ": applied Zink + Turnip at " + scale + "% scale"
                                    + (sysmem ? " with TU_DEBUG=sysmem stability guard" : "")
                                    + (ubwc ? " and UBWC swizzle workaround." : ".")
                    );
                }
                if (hasShadersInstalled && hasMesaLibrary) {
                    return new RendererPreset(
                            arch,
                            "Adreno 6xx Freedreno KGSL",
                            Renderers.FREEDRENO_RENDERER,
                            Profile.BALANCED,
                            scale,
                            sysmem,
                            ubwc,
                            false,
                            hasVulkan,
                            arch.displayName + ": applied native Mesa Freedreno KGSL at " + scale
                                    + "% scale (sysmem=" + (sysmem ? "on" : "off")
                                    + ", ubwc=" + (ubwc ? "on" : "off") + ")."
                    );
                }
                return new RendererPreset(
                        arch,
                        "Adreno 6xx LTW Balanced",
                        glesMajor >= 3 ? Renderers.LTW_RENDERER : Renderers.GL4ES_RENDERER,
                        Profile.BALANCED,
                        scale,
                        sysmem,
                        ubwc,
                        false,
                        hasVulkan,
                        arch.displayName + ": applied LTW at " + scale
                                + "% scale for smooth Sodium/1.17+ frame pacing."
                );
            }

            case ADRENO_LEGACY: {
                int scale = 70;
                String renderer = hasMesaLibrary ? Renderers.FREEDRENO_RENDERER
                        : (glesMajor >= 3 ? Renderers.LTW_RENDERER : Renderers.GL4ES_RENDERER);
                String name = hasMesaLibrary ? "Adreno Legacy Freedreno (GL 3.3)" : "Adreno Legacy Safe";
                return new RendererPreset(
                        arch,
                        name,
                        renderer,
                        Profile.PERFORMANCE,
                        scale,
                        true,
                        false,
                        false,
                        false,
                        arch.displayName + ": applied " + renderer + " at " + scale
                                + "% scale with FD_MESA_DEBUG=sysmem and GL 3.3 core override."
                );
            }

            case SAMSUNG_XCLIPSE: {
                int scale = highRam ? 95 : 85;
                if (hasVulkan && hasMesaLibrary) {
                    return new RendererPreset(
                            arch,
                            "Xclipse RDNA Vulkan Zink (GL 4.6)",
                            Renderers.ZINK_RENDERER,
                            Profile.QUALITY,
                            scale,
                            false,
                            false,
                            true,
                            false,
                            arch.displayName + ": applied Mesa Zink (GL 4.6) over native AMD RDNA system Vulkan at "
                                    + scale + "% scale."
                    );
                }
                return new RendererPreset(
                        arch,
                        "Xclipse LTW High-Speed",
                        glesMajor >= 3 ? Renderers.LTW_RENDERER : Renderers.GL4ES_RENDERER,
                        Profile.BALANCED,
                        scale,
                        false,
                        false,
                        true,
                        false,
                        arch.displayName + ": applied LTW at " + scale + "% scale."
                );
            }

            case ARM_IMMORTALIS: {
                int scale = highRam ? 95 : 85;
                String renderer = (hasShadersInstalled && hasVulkan && hasMesaLibrary)
                        ? Renderers.ZINK_RENDERER
                        : (glesMajor >= 3 ? Renderers.LTW_RENDERER : Renderers.ZINK_RENDERER);
                return new RendererPreset(
                        arch,
                        hasShadersInstalled && Renderers.ZINK_RENDERER.equals(renderer)
                                ? "Immortalis Vulkan Zink Shader Mode"
                                : "Immortalis LTW Flagship",
                        renderer,
                        Profile.QUALITY,
                        scale,
                        false,
                        false,
                        true,
                        false,
                        arch.displayName + ": applied " + renderer + " at " + scale
                                + "% scale using native Immortalis driver."
                );
            }

            case ARM_MALI_VALHALL: {
                int scale = lowRam ? 75 : 85;
                return new RendererPreset(
                        arch,
                        "Mali Valhall LTW Optimized",
                        glesMajor >= 3 ? Renderers.LTW_RENDERER
                                : (hasVulkan && hasMesaLibrary ? Renderers.ZINK_RENDERER : Renderers.GL4ES_RENDERER),
                        Profile.BALANCED,
                        scale,
                        false,
                        false,
                        true,
                        false,
                        arch.displayName + ": applied LTW (OpenGL ES 3.2) at " + scale
                                + "% scale — optimal latency & throughput on ARM Mali Valhall."
                );
            }

            case ARM_MALI_BIFROST_OR_OLDER:
            case POWERVR: {
                int scale = 70;
                return new RendererPreset(
                        arch,
                        arch == GpuArchitecture.POWERVR ? "PowerVR LTW/GL4ES Safe" : "Mali Bifrost LTW Efficiency",
                        glesMajor >= 3 ? Renderers.LTW_RENDERER : Renderers.GL4ES_RENDERER,
                        Profile.PERFORMANCE,
                        scale,
                        false,
                        false,
                        true,
                        false,
                        arch.displayName + ": applied lightweight "
                                + (glesMajor >= 3 ? "LTW" : "GL4ES") + " preset at " + scale + "% scale."
                );
            }

            case DESKTOP_OR_VIRGL: {
                String renderer = hasVulkan && hasMesaLibrary ? Renderers.ZINK_RENDERER
                        : (glesMajor >= 3 ? Renderers.LTW_RENDERER : Renderers.GL4ES_RENDERER);
                return new RendererPreset(
                        arch,
                        "Desktop / ChromeOS Native Preset",
                        renderer,
                        Profile.QUALITY,
                        100,
                        false,
                        false,
                        true,
                        false,
                        arch.displayName + ": applied " + renderer + " at 100% native scale."
                );
            }

            case UNKNOWN:
            default: {
                int scale = lowRam ? 75 : 85;
                String renderer = recommendRendererTag(glesMajor, hasVulkan && hasMesaLibrary, true);
                return new RendererPreset(
                        arch,
                        "Universal Compatible Preset",
                        renderer,
                        Profile.BALANCED,
                        scale,
                        false,
                        false,
                        true,
                        false,
                        arch.displayName + ": applied " + renderer + " at " + scale + "% scale."
                );
            }
        }
    }

    /**
     * Inspects the live device GPU architecture, installed Turnip drivers, Mesa availability, RAM,
     * and instance shaderpacks, then persists the optimal renderer &amp; driver workarounds.
     */
    @NonNull
    public static RendererPreset applyOptimalPreset(@NonNull Context context,
                                                    @Nullable Instance instance,
                                                    @Nullable String versionId) {
        GpuUtils.GLInfo gl = GpuUtils.getGlInfo();
        boolean vulkan = GpuUtils.checkVulkanSupport(context.getPackageManager());
        boolean hasMesa = Build.VERSION.SDK_INT >= 29
                && Tools.NATIVE_LIB_DIR != null
                && new File(Tools.NATIVE_LIB_DIR, "libEGL_mesa.so").exists();

        Driver activeDriver = instance != null
                ? DriverManager.resolveDriverForInstance(instance)
                : DriverManager.getPreferredDriver();
        boolean hasTurnip = !(activeDriver instanceof BuiltinDriver)
                || !DriverManager.getDriverPaths().isEmpty();

        boolean hasShaders = false;
        if (instance != null) {
            File shaderDir = new File(instance.getGameDirectory(), "shaderpacks");
            File[] packs = shaderDir.listFiles(f -> f.isFile() && f.getName().endsWith(".zip"));
            hasShaders = packs != null && packs.length > 0;
        }

        boolean modernMc = true;
        if (Tools.isValidString(versionId)) {
            int reqJava = JavaRuntimeAdvisor.resolveRequiredJava(null, versionId);
            modernMc = reqJava >= 16;
        }

        int totalRam = Tools.getTotalDeviceMemory(context);
        RendererPreset preset = computeOptimalPreset(
                gl.architecture,
                gl.renderer,
                gl.glesMajorVersion,
                vulkan,
                hasMesa,
                hasTurnip,
                hasShaders,
                modernMc,
                totalRam);

        // Verify selected renderer is present in RendererCache; fall back safely if excluded.
        String chosenRenderer = preset.rendererId;
        try {
            RendererCache cache = RendererCache.getCompatibleRenderers(context);
            if (cache != null && cache.rendererIds != null
                    && !cache.rendererIds.contains(chosenRenderer)) {
                chosenRenderer = cache.rendererIds.contains(Renderers.LTW_RENDERER)
                        ? Renderers.LTW_RENDERER : Renderers.GL4ES_RENDERER;
            }
        } catch (Throwable ignored) {
        }

        applyProfile(preset.baseProfile);
        LauncherPreferences.PREF_SCALE_FACTOR = preset.recommendedScalePercent / 100f;
        LauncherPreferences.PREF_FREEDRENO_SYSMEM = preset.enableFreedrenoSysmem;
        LauncherPreferences.PREF_UBWC_WORKAROUND = preset.enableUbwcWorkaround;
        LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER = preset.preferSystemVulkan;

        if (LauncherPreferences.DEFAULT_PREF != null) {
            android.content.SharedPreferences.Editor ed = LauncherPreferences.DEFAULT_PREF.edit()
                    .putInt("resolutionRatio", preset.recommendedScalePercent)
                    .putBoolean("freedreno_sysmem", preset.enableFreedrenoSysmem)
                    .putBoolean("ubwc_workaround", preset.enableUbwcWorkaround)
                    .putBoolean("zinkPreferSystemDriver", preset.preferSystemVulkan);
            if (instance == null) {
                LauncherPreferences.PREF_RENDERER = chosenRenderer;
                ed.putString("renderer", chosenRenderer);
            }
            ed.apply();
        }

        if (instance != null) {
            instance.renderer = chosenRenderer;
            // Auto-bind first installed Turnip driver on Adreno 6xx/7xx/8xx Zink if none bound yet
            if (Renderers.ZINK_RENDERER.equals(chosenRenderer)
                    && gl.architecture.supportsTurnipDrivers()
                    && !instance.hasCustomVulkanDriver()
                    && LauncherPreferences.PREF_VULKAN_DRIVER == null) {
                java.util.List<String> installed = DriverManager.getDriverPaths();
                if (!installed.isEmpty()) {
                    instance.vulkanDriver = installed.get(0);
                }
            }
            instance.maybeWrite();
        }

        if (!chosenRenderer.equals(preset.rendererId)) {
            return new RendererPreset(
                    preset.architecture,
                    preset.presetName,
                    chosenRenderer,
                    preset.baseProfile,
                    preset.recommendedScalePercent,
                    preset.enableFreedrenoSysmem,
                    preset.enableUbwcWorkaround,
                    preset.preferSystemVulkan,
                    preset.recommendsTurnipInstall,
                    preset.summary);
        }
        return preset;
    }

    public static void applyProfile(@NonNull Profile profile) {
        LauncherPreferences.PREF_SCALE_FACTOR = profile.resolutionPercent / 100f;
        LauncherPreferences.PREF_SUSTAINED_PERFORMANCE = profile.sustainedPerformance;
        LauncherPreferences.PREF_FORCE_VSYNC = profile.forceVsync;
        LauncherPreferences.PREF_ZYROX_LOW_EFFECTS = profile.lowEffects;
        LauncherPreferences.PREF_ZYROX_ANIMATED_BG = profile.animatedBg;
        LauncherPreferences.PREF_ZYROX_REDUCED_MOTION = (profile == Profile.BATTERY_SAVER);
        LauncherPreferences.PREF_ZYROX_GRAPHICS_PROFILE = profile.id;

        if (LauncherPreferences.DEFAULT_PREF == null) return;
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

    @NonNull
    public static String recommendRendererTag(int glesMajor, boolean hasVulkan, boolean modernMinecraft) {
        if (modernMinecraft) {
            if (glesMajor >= 3) return Renderers.LTW_RENDERER;
            if (hasVulkan) return Renderers.ZINK_RENDERER;
            return Renderers.GL4ES_RENDERER;
        }
        return Renderers.GL4ES_RENDERER;
    }

    @NonNull
    public static String buildDiagnosticsSummary(@NonNull Context context) {
        GpuUtils.GLInfo gl = GpuUtils.getGlInfo();
        boolean vulkan = GpuUtils.checkVulkanSupport(context.getPackageManager());
        boolean hasMesa = Build.VERSION.SDK_INT >= 29
                && Tools.NATIVE_LIB_DIR != null
                && new File(Tools.NATIVE_LIB_DIR, "libEGL_mesa.so").exists();
        Driver activeDriver = DriverManager.getPreferredDriver();
        boolean hasTurnip = !(activeDriver instanceof BuiltinDriver);
        int totalRam = Tools.getTotalDeviceMemory(context);
        int freeRam = Tools.getFreeDeviceMemory(context);
        int cores = Runtime.getRuntime().availableProcessors();
        String abi = Architecture.archAsString(Architecture.getDeviceArchitecture());
        int resPct = Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f);

        RendererPreset autoPreset = computeOptimalPreset(
                gl.architecture, gl.renderer, gl.glesMajorVersion,
                vulkan, hasMesa, hasTurnip, false, true, totalRam);

        StringBuilder sb = new StringBuilder(640);
        sb.append("Zyrox Graphics & Device Diagnostics\n");
        sb.append("-----------------------------------\n");
        sb.append("Device Model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android SDK: API ").append(Build.VERSION.SDK_INT)
                .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n");
        sb.append("CPU / ABI: ").append(abi).append(" • ").append(cores).append(" cores\n");
        sb.append("Memory: ").append(freeRam).append(" MB free / ").append(totalRam)
                .append(" MB total (Default Heap: ")
                .append(LauncherPreferences.PREF_RAM_ALLOCATION).append(" MB)\n");
        sb.append("GPU Vendor: ").append(gl.vendor).append('\n');
        sb.append("GPU Renderer: ").append(gl.renderer).append('\n');
        sb.append("GPU Architecture: ").append(gl.architecture.displayName).append('\n');
        sb.append("OpenGL ES: ").append(gl.glesMajorVersion).append(".x");
        if (gl.forcedMsaa) sb.append(" (4x MSAA forced in Developer Options — disable for LTW!)");
        sb.append('\n');
        sb.append("Vulkan / Turnip: ").append(vulkan ? "Supported" : "Not reported");
        if (vulkan) {
            sb.append(" • Driver: ").append(activeDriver.getName());
            int pkgCount = DriverManager.getDriverPaths().size();
            if (pkgCount > 0) sb.append(" (").append(pkgCount).append(" .adpkg installed)");
        }
        sb.append('\n');
        sb.append("Active Profile: ")
                .append(LauncherPreferences.PREF_ZYROX_GRAPHICS_PROFILE.toUpperCase(Locale.ROOT))
                .append(" • Scale ").append(resPct).append("% • VSync ")
                .append(LauncherPreferences.PREF_FORCE_VSYNC ? "ON" : "OFF").append('\n');
        sb.append("Global Renderer: ").append(LauncherPreferences.PREF_RENDERER).append('\n');
        sb.append("Auto-Preset: ").append(autoPreset.presetName)
                .append(" [").append(autoPreset.rendererId).append("]\n");
        sb.append("Note: ").append(autoPreset.summary);
        return LogRedactor.redact(sb.toString());
    }
}
