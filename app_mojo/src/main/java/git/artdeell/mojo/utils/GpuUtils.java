package git.artdeell.mojo.utils;

import static android.os.Build.VERSION.SDK_INT;

import android.content.pm.PackageManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hardware OpenGL ES / Vulkan capability probe and mobile GPU micro-architecture classifier.
 */
public class GpuUtils {
    public static String GLES_VERSION_PREFIX = "OpenGL ES ";

    private static final Pattern ADRENO_MODEL_PATTERN =
            Pattern.compile("adreno\\s*(?:\\(tm\\)\\s*)?([0-9]{3,4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern MALI_G_MODEL_PATTERN =
            Pattern.compile("(?:mali|immortalis)[-\\s]*g([0-9]{2,4})", Pattern.CASE_INSENSITIVE);

    /** Fine-grained Android GPU architecture families used for Zink / Freedreno / LTW presets. */
    public enum GpuArchitecture {
        ADRENO_8XX("Qualcomm Adreno 8xx (Snapdragon 8 Elite Gen)"),
        ADRENO_7XX("Qualcomm Adreno 7xx"),
        ADRENO_6XX("Qualcomm Adreno 6xx"),
        ADRENO_LEGACY("Qualcomm Adreno Legacy (2xx–5xx)"),
        ARM_IMMORTALIS("ARM Immortalis (Flagship Valhall/5th-Gen)"),
        ARM_MALI_VALHALL("ARM Mali Valhall"),
        ARM_MALI_BIFROST_OR_OLDER("ARM Mali Bifrost / Midgard"),
        SAMSUNG_XCLIPSE("Samsung Xclipse (AMD RDNA)"),
        POWERVR("Imagination PowerVR"),
        DESKTOP_OR_VIRGL("Desktop / ChromeOS VirGL"),
        UNKNOWN("Generic Mobile GPU");

        @NonNull public final String displayName;

        GpuArchitecture(@NonNull String displayName) {
            this.displayName = displayName;
        }

        public boolean isAdrenoFamily() {
            return this == ADRENO_8XX || this == ADRENO_7XX
                    || this == ADRENO_6XX || this == ADRENO_LEGACY;
        }

        public boolean isArmFamily() {
            return this == ARM_IMMORTALIS || this == ARM_MALI_VALHALL
                    || this == ARM_MALI_BIFROST_OR_OLDER;
        }

        /** True when Mesa Turnip ({@code .adpkg}) custom Vulkan drivers target this GPU family. */
        public boolean supportsTurnipDrivers() {
            return this == ADRENO_6XX || this == ADRENO_7XX || this == ADRENO_8XX;
        }

        /** True when Mesa Freedreno KGSL direct driver is viable on this GPU. */
        public boolean supportsFreedrenoKgsl() {
            return isAdrenoFamily();
        }

        /** True when high-throughput flagship defaults (100% scale, cached descriptors) are safe. */
        public boolean isFlagshipTier() {
            return this == ADRENO_8XX || this == ADRENO_7XX
                    || this == ARM_IMMORTALIS || this == SAMSUNG_XCLIPSE
                    || this == DESKTOP_OR_VIRGL;
        }
    }

    private static GLInfo info;

    /**
     * Classifies a GPU micro-architecture from its raw {@code GL_VENDOR} and {@code GL_RENDERER}
     * strings. Deterministic and pure (safe to call from JVM unit tests without an EGL context).
     */
    @NonNull
    public static GpuArchitecture detectArchitecture(@Nullable String vendor,
                                                     @Nullable String renderer) {
        String v = vendor != null ? vendor.toLowerCase(Locale.ROOT) : "";
        String r = renderer != null ? renderer.toLowerCase(Locale.ROOT) : "";

        if (r.contains("adreno") || v.contains("qualcomm")) {
            int model = parseAdrenoModelNumber(renderer);
            if (model >= 800) return GpuArchitecture.ADRENO_8XX;
            if (model >= 700) return GpuArchitecture.ADRENO_7XX;
            if (model >= 600) return GpuArchitecture.ADRENO_6XX;
            if (model >= 200) return GpuArchitecture.ADRENO_LEGACY;
            return GpuArchitecture.ADRENO_6XX;
        }

        if (r.contains("xclipse") || (v.contains("samsung") && r.contains("xclipse"))) {
            return GpuArchitecture.SAMSUNG_XCLIPSE;
        }

        if (r.contains("immortalis")) {
            return GpuArchitecture.ARM_IMMORTALIS;
        }

        if (r.contains("mali") || v.equals("arm") || v.contains("arm ")) {
            int maliModel = parseMaliGModelNumber(renderer);
            // Mali-G57, G68, G77, G78, G310, G510, G610, G615, G710, G715, G720 are Valhall/5th-Gen
            if (maliModel >= 300 || maliModel == 57 || maliModel == 68
                    || maliModel == 77 || maliModel == 78) {
                return GpuArchitecture.ARM_MALI_VALHALL;
            }
            return GpuArchitecture.ARM_MALI_BIFROST_OR_OLDER;
        }

        if (r.contains("powervr") || v.contains("imagination")) {
            return GpuArchitecture.POWERVR;
        }

        if (r.contains("virgl") || r.contains("virtio") || r.contains("radeon")
                || r.contains("geforce") || r.contains("intel") || r.contains("mesa")) {
            return GpuArchitecture.DESKTOP_OR_VIRGL;
        }

        return GpuArchitecture.UNKNOWN;
    }

    /** Extracts the 3–4 digit Adreno model number (e.g. {@code 750}, {@code 830}), or {@code -1}. */
    public static int parseAdrenoModelNumber(@Nullable String renderer) {
        if (renderer == null || renderer.isEmpty()) return -1;
        Matcher m = ADRENO_MODEL_PATTERN.matcher(renderer);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Extracts the numeric suffix of a {@code Mali-Gxx} / {@code Immortalis-Gxxx}, or {@code -1}. */
    public static int parseMaliGModelNumber(@Nullable String renderer) {
        if (renderer == null || renderer.isEmpty()) return -1;
        Matcher m = MALI_G_MODEL_PATTERN.matcher(renderer);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Returns {@code true} for entry-level Adreno 6xx GPUs (610/612/615/616/618/619/620) and
     * legacy Adreno 5xx parts where {@code sysmem} avoids GMEM tile-binning hangs under Zink/KGSL.
     */
    public static boolean needsAdrenoSysmemWorkaround(@Nullable String renderer) {
        int model = parseAdrenoModelNumber(renderer);
        if (model <= 0) return false;
        return model < 600 || (model >= 605 && model <= 620);
    }

    /** Returns {@code true} for Adreno 6xx parts prone to UBWC texture-swizzle artifacts. */
    public static boolean needsAdrenoUbwcWorkaround(@Nullable String renderer) {
        int model = parseAdrenoModelNumber(renderer);
        return model >= 610 && model <= 630;
    }

    private static int getMajorGLVersion(String versionString) {
        if (versionString == null) return 2;
        if (versionString.startsWith(GLES_VERSION_PREFIX)) {
            versionString = versionString.substring(GLES_VERSION_PREFIX.length());
        }
        int firstDot = versionString.indexOf('.');
        if (firstDot <= 0) return 2;
        String majorVersion = versionString.substring(0, firstDot).trim();
        return Integer.parseInt(majorVersion);
    }

    private static GLInfo queryInfo(int contextGLVersion, boolean forcedMsaa) {
        String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        String versionString = GLES20.glGetString(GLES30.GL_VERSION);
        int version = 2;
        try {
            version = getMajorGLVersion(versionString);
        } catch (NumberFormatException e) {
            Log.w("GLInfoUtils", "Failed to parse GL version number, falling back to 2", e);
        }
        version = Math.min(version, contextGLVersion);
        return new GLInfo(
                vendor != null ? vendor : "<Unknown>",
                renderer != null ? renderer : "<Unknown>",
                version,
                forcedMsaa);
    }

    private static void initDummyInfo() {
        Log.e("GLInfoUtils", "An error happened during info query. Will use dummy info.");
        info = new GLInfo("<Unknown>", "<Unknown>", 2, false);
    }

    private static EGLContext tryCreateContext(EGLDisplay eglDisplay, EGLConfig config, int majorVersion) {
        int[] attrs = new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, majorVersion, EGL14.EGL_NONE};
        EGLContext context = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, attrs, 0);
        if (EGL14.EGL_NO_CONTEXT.equals(context) || context == null) {
            Log.e("GLInfoUtils", "Failed to create a context with major version " + majorVersion);
            return null;
        }
        return context;
    }

    private static EGLContext tryMakeCurrent(EGLDisplay eglDisplay,
                                             EGLConfig config,
                                             EGLSurface surface,
                                             int majorVersion) {
        EGLContext context = tryCreateContext(eglDisplay, config, majorVersion);
        if (context == null) return null;
        boolean ok = EGL14.eglMakeCurrent(eglDisplay, surface, surface, context);
        if (!ok) {
            Log.i("GLInfoUtils", "Failed to make context GL version " + majorVersion + " current");
            EGL14.eglDestroyContext(eglDisplay, context);
            return null;
        }
        return context;
    }

    private static boolean isMSAAConfig(EGLDisplay eglDisplay, EGLConfig eglConfig) {
        int[] sampleBuffers = new int[]{0};
        EGL14.eglGetConfigAttrib(eglDisplay, eglConfig, EGL14.EGL_SAMPLE_BUFFERS, sampleBuffers, 0);
        return sampleBuffers[0] != 0;
    }

    private static boolean initAndQueryInfo() {
        int[] eglVersion = new int[2];
        EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY
                || !EGL14.eglInitialize(eglDisplay, eglVersion, 0, eglVersion, 1)) {
            return false;
        }
        int[] eglAttributes = new int[]{
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] config = new EGLConfig[1];
        int[] numConfigs = new int[]{0};
        if (!EGL14.eglChooseConfig(eglDisplay, eglAttributes, 0, config, 0, 1, numConfigs, 0)
                || numConfigs[0] == 0) {
            EGL14.eglTerminate(eglDisplay);
            return false;
        }

        boolean forcedMsaa = isMSAAConfig(eglDisplay, config[0]);
        int[] pbufferAttrs = new int[]{EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE};
        EGLSurface surface = EGL14.eglCreatePbufferSurface(eglDisplay, config[0], pbufferAttrs, 0);
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglTerminate(eglDisplay);
            return false;
        }

        int contextGLVersion = 3;
        EGLContext context = tryMakeCurrent(eglDisplay, config[0], surface, contextGLVersion);
        if (context == null) {
            contextGLVersion = 2;
            context = tryMakeCurrent(eglDisplay, config[0], surface, contextGLVersion);
        }
        if (context == null) {
            EGL14.eglDestroySurface(eglDisplay, surface);
            EGL14.eglTerminate(eglDisplay);
            return false;
        }

        info = queryInfo(contextGLVersion, forcedMsaa);
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(eglDisplay, surface);
        EGL14.eglDestroyContext(eglDisplay, context);
        EGL14.eglTerminate(eglDisplay);
        return true;
    }

    public static boolean checkVulkanSupport(PackageManager packageManager) {
        if (SDK_INT >= Build.VERSION_CODES.N && packageManager != null) {
            return packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
                    && packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
        }
        return false;
    }

    public static boolean checkChromebook(PackageManager packageManager) {
        return (packageManager != null
                && (packageManager.hasSystemFeature("org.chromium.arc")
                || packageManager.hasSystemFeature("org.chromium.arc.device_management")))
                || (Build.MODEL != null && Build.MODEL.startsWith("sdk_gpc_"));
    }

    @NonNull
    public static GLInfo getGlInfo() {
        if (info != null) return info;
        Log.i("GLInfoUtils", "Querying graphics device info...");
        boolean ok = false;
        try {
            ok = initAndQueryInfo();
        } catch (Throwable e) {
            Log.e("GLInfoUtils", "Throwable when trying to initialize GL info", e);
        }
        if (!ok) initDummyInfo();
        return info;
    }

    public static class GLInfo {
        public final String vendor;
        public final String renderer;
        public final int glesMajorVersion;
        public final boolean forcedMsaa;
        @NonNull public final GpuArchitecture architecture;
        public final int adrenoModel;

        public GLInfo(String vendor, String renderer, int glesMajorVersion, boolean forcedMsaa) {
            this.vendor = vendor != null ? vendor : "<Unknown>";
            this.renderer = renderer != null ? renderer : "<Unknown>";
            this.glesMajorVersion = glesMajorVersion;
            this.forcedMsaa = forcedMsaa;
            this.architecture = detectArchitecture(this.vendor, this.renderer);
            this.adrenoModel = parseAdrenoModelNumber(this.renderer);
        }

        public boolean isAdreno() {
            return architecture.isAdrenoFamily();
        }

        public boolean isAdreno500Lower() {
            return architecture == GpuArchitecture.ADRENO_LEGACY;
        }

        public boolean isArm() {
            return architecture.isArmFamily();
        }

        public boolean isXclipse() {
            return architecture == GpuArchitecture.SAMSUNG_XCLIPSE;
        }

        public boolean supportsTurnip() {
            return SDK_INT >= Build.VERSION_CODES.P && architecture.supportsTurnipDrivers();
        }

        public boolean needsSysmemWorkaround() {
            return needsAdrenoSysmemWorkaround(renderer);
        }

        public boolean needsUbwcWorkaround() {
            return needsAdrenoUbwcWorkaround(renderer);
        }
    }
}
