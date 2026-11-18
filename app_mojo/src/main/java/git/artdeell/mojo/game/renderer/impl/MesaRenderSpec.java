package git.artdeell.mojo.game.renderer.impl;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.game.renderer.RenderSpec;
import git.artdeell.mojo.game.renderer.def.Renderers;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.utils.GpuUtils.GpuArchitecture;
import git.artdeell.mojo.utils.plugins.LibraryPlugin;
import git.artdeell.mojo.vulkan.driver.BuiltinDriver;
import git.artdeell.mojo.vulkan.driver.Driver;
import git.artdeell.mojoexec.MojoExec;

/**
 * Mesa3D RenderSpec providing Desktop Mesa, Vulkan Zink (with Adreno Turnip {@code .adpkg}
 * per-instance driver isolation and architecture-matched descriptors), and Freedreno KGSL.
 */
public class MesaRenderSpec implements RenderSpec {
    public static final String SHADER_CACHE_ROOT_DIR = "mesa_shader_cache";

    /** Active Vulkan/Turnip driver hash bound by {@code GameRenderer.overrideVulkanDriver(Instance)}. */
    @Nullable
    private static volatile String sBoundDriverHash;

    public static void setBoundDriver(@Nullable Driver driver) {
        sBoundDriverHash = (driver == null || driver instanceof BuiltinDriver)
                ? "system" : sanitizeCacheToken(driver.getHash());
    }

    /**
     * Computes a deterministic, traversal-safe per-renderer + per-driver shader cache folder name
     * so switching Turnip {@code .adpkg} versions never reuses incompatible cached pipelines.
     */
    @NonNull
    public static String buildShaderCacheFolderName(@Nullable String rendererTag,
                                                    @Nullable String driverHash) {
        String r = sanitizeCacheToken(rendererTag != null ? rendererTag : "mesa");
        String d = sanitizeCacheToken(driverHash != null ? driverHash : "system");
        return r + "_" + d;
    }

    /** Purges all isolated Mesa/Zink/Turnip shader disk caches; returns deleted byte count. */
    public static long purgeShaderCaches() {
        if (Tools.DIR_CACHE == null) return 0L;
        File root = new File(Tools.DIR_CACHE, SHADER_CACHE_ROOT_DIR);
        if (!root.isDirectory()) return 0L;
        long bytes = countBytes(root);
        org.apache.commons.io.FileUtils.deleteQuietly(root);
        return bytes;
    }

    /**
     * Populates Turnip / Zink / Freedreno environment variables tailored to {@code arch} and
     * whether a custom AdrenoTools Turnip {@code .adpkg} driver is active.
     */
    public static void applyArchitectureTuning(@NonNull Map<String, String> envMap,
                                               @NonNull GpuArchitecture arch,
                                               @Nullable String rawRenderer,
                                               boolean isZink,
                                               boolean customTurnipActive,
                                               boolean forceSysmemPref,
                                               boolean forceUbwcPref) {
        envMap.put("mesa_glthread", "true");
        envMap.put("MESA_NO_ERROR", "1");

        boolean sysmem = forceSysmemPref || GpuUtils.needsAdrenoSysmemWorkaround(rawRenderer);
        boolean ubwc = forceUbwcPref || GpuUtils.needsAdrenoUbwcWorkaround(rawRenderer);

        if (isZink) {
            // Adreno 6xx benefits from lazy descriptor updates; Adreno 7xx/8xx & RDNA excel on auto/cached
            if (arch == GpuArchitecture.ADRENO_6XX || arch == GpuArchitecture.ADRENO_LEGACY) {
                envMap.put("ZINK_DESCRIPTORS", "lazy");
            } else {
                envMap.put("ZINK_DESCRIPTORS", "auto");
            }
            if (customTurnipActive || arch.isAdrenoFamily()) {
                List<String> tuFlags = new ArrayList<>(3);
                tuFlags.add("noconform");
                if (sysmem) tuFlags.add("sysmem");
                envMap.put("TU_DEBUG", joinComma(tuFlags));
                if (ubwc) {
                    envMap.put("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1");
                }
            }
        } else if (arch.isAdrenoFamily()) {
            if (sysmem) envMap.put("FD_MESA_DEBUG", "sysmem");
            if (ubwc) envMap.put("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1");
        }
    }

    @Override
    public String library() {
        return "libEGL_mesa.so";
    }

    @Override
    public void setupEnvironment(Context context, Map<String, String> envMap) {
        File cacheDir = resolveIsolatedCacheDir(tag(), sBoundDriverHash);
        String cachePath = cacheDir.getAbsolutePath();
        envMap.put("MESA_GLSL_CACHE_DIR", cachePath);
        envMap.put("MESA_SHADER_CACHE_DIR", cachePath);
        envMap.put("MESA_SHADER_CACHE_MAX_SIZE", "256M");
    }

    @NonNull
    private static File resolveIsolatedCacheDir(@NonNull String rendererTag,
                                                @Nullable String driverHash) {
        File base = Tools.DIR_CACHE != null ? Tools.DIR_CACHE : new File(".");
        File root = new File(base, SHADER_CACHE_ROOT_DIR);
        File isolated = new File(root, buildShaderCacheFolderName(rendererTag, driverHash));
        if (!isolated.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            isolated.mkdirs();
        }
        return isolated.isDirectory() ? isolated : base;
    }

    protected boolean hasMesa() {
        return SDK_INT >= 29 && Tools.NATIVE_LIB_DIR != null
                && new File(Tools.NATIVE_LIB_DIR, this.library()).exists();
    }

    @Override
    public boolean compatibleDevice(Context context) {
        return hasMesa() && GpuUtils.checkChromebook(context.getPackageManager());
    }

    @Override
    public String name() {
        return "Mesa";
    }

    @Override
    public int displayName() {
        return R.string.mcl_setting_renderer_mesa_desktop;
    }

    @Override
    public String tag() {
        return Renderers.MESA_RENDERER;
    }

    @Override
    public boolean setupRenderer() {
        return MojoExec.prepareEgl(library(), true, false, 3);
    }

    public static class ZinkRenderSpec extends MesaRenderSpec {
        @Override
        public String name() {
            return "ZINK";
        }

        @Override
        public String tag() {
            return Renderers.ZINK_RENDERER;
        }

        @Override
        public int displayName() {
            return R.string.mcl_setting_renderer_vulkan_zink;
        }

        @Override
        public void setupEnvironment(Context context, Map<String, String> envMap) {
            envMap.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            envMap.put("MESA_GLSL_VERSION_OVERRIDE", "460");
            envMap.put("MESA_GL_VERSION_OVERRIDE", "4.6");
            GpuUtils.GLInfo gl = GpuUtils.getGlInfo();
            boolean customTurnip = sBoundDriverHash != null && !"system".equals(sBoundDriverHash);
            applyArchitectureTuning(
                    envMap,
                    gl.architecture,
                    gl.renderer,
                    true,
                    customTurnip,
                    LauncherPreferences.PREF_FREEDRENO_SYSMEM,
                    LauncherPreferences.PREF_UBWC_WORKAROUND);
            super.setupEnvironment(context, envMap);
        }

        @Override
        public boolean setupRenderer() {
            MojoExec.preloadVulkan();
            return super.setupRenderer();
        }

        @Override
        public boolean compatibleDevice(Context context) {
            return hasMesa() && GpuUtils.checkVulkanSupport(context.getPackageManager());
        }
    }

    public static class FreedrenoRenderSpec extends MesaRenderSpec {
        @Override
        public String name() {
            return "Freedreno";
        }

        @Override
        public int displayName() {
            return R.string.mcl_setting_renderer_freedreno_kgsl;
        }

        @Override
        public String tag() {
            return Renderers.FREEDRENO_RENDERER;
        }

        @Override
        public boolean compatibleDevice(Context context) {
            return hasMesa() && GpuUtils.getGlInfo().isAdreno();
        }

        @Override
        public void setupEnvironment(Context context, Map<String, String> envMap) {
            GpuUtils.GLInfo gl = GpuUtils.getGlInfo();
            envMap.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
            applyArchitectureTuning(
                    envMap,
                    gl.architecture,
                    gl.renderer,
                    false,
                    false,
                    LauncherPreferences.PREF_FREEDRENO_SYSMEM,
                    LauncherPreferences.PREF_UBWC_WORKAROUND);
            if (gl.isAdreno500Lower()) {
                envMap.put("MESA_GL_VERSION_OVERRIDE", "3.3");
                envMap.put("MESA_GLSL_VERSION_OVERRIDE", "330");
            } else {
                envMap.put("MESA_GL_VERSION_OVERRIDE", "4.5");
                envMap.put("MESA_GLSL_VERSION_OVERRIDE", "450");
            }
            super.setupEnvironment(context, envMap);
        }
    }

    public static class ExtMesaRenderSpec extends MesaRenderSpec {
        private LibraryPlugin provider;

        protected String plugin() {
            return LibraryPlugin.ID_MESA_PLUGIN;
        }

        @Override
        public String name() {
            return "Mesa (external)";
        }

        @Override
        public String librarySearchPath() {
            return provider != null ? provider.getLibraryPath() : null;
        }

        @Override
        public String tag() {
            return Renderers.MESA_RENDERER_EXT;
        }

        @Override
        public int displayName() {
            return R.string.mcl_setting_renderer_mesa_desktop_ext;
        }

        private boolean discover(Context context) {
            if (provider == null) provider = LibraryPlugin.discoverPlugin(context, plugin());
            return provider != null;
        }

        @Override
        public boolean compatibleDevice(Context context) {
            return discover(context) && provider.checkLibraries(library());
        }

        @Override
        public void setupEnvironment(Context context, Map<String, String> envMap) {
            discover(context);
            super.setupEnvironment(context, envMap);
        }

        @Override
        public boolean setupRenderer() {
            if (provider == null) return false;
            return MojoExec.prepareEgl(provider.resolveAbsolutePath(library()), true, false, 0);
        }
    }

    public static class LegacyZinkRenderSpec extends ExtMesaRenderSpec {
        @Override
        protected String plugin() {
            return LibraryPlugin.ID_ZINK_PLUGIN;
        }

        @Override
        public String name() {
            return "ZINK (Legacy)";
        }

        @Override
        public String tag() {
            return Renderers.LEGACYZINK_RENDERER;
        }

        @Override
        public int displayName() {
            return R.string.mcl_setting_renderer_vulkan_lzink;
        }

        @Override
        public void setupEnvironment(Context context, Map<String, String> envMap) {
            super.setupEnvironment(context, envMap);
            envMap.put("MESA_GL_VERSION_OVERRIDE", "4.3");
            envMap.put("MESA_GLSL_VERSION_OVERRIDE", "460");
            envMap.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        }

        @Override
        public boolean setupRenderer() {
            MojoExec.preloadVulkan();
            return super.setupRenderer();
        }

        @Override
        public boolean compatibleDevice(Context context) {
            return GpuUtils.checkVulkanSupport(context.getPackageManager())
                    && super.compatibleDevice(context);
        }
    }

    @NonNull
    private static String sanitizeCacheToken(@NonNull String raw) {
        String clean = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (clean.length() > 32) clean = clean.substring(0, 32);
        return clean.isEmpty() ? "default" : clean;
    }

    @NonNull
    private static String joinComma(@NonNull List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static long countBytes(@NonNull File node) {
        if (node.isFile()) return Math.max(0L, node.length());
        File[] list = node.listFiles();
        if (list == null) return 0L;
        long sum = 0L;
        for (File f : list) sum += countBytes(f);
        return sum;
    }
}
