package git.artdeell.mojo.game;

import androidx.annotation.Nullable;

/**
 * Pure, zero-allocation detector determining when Minecraft has drawn the first real visible frame
 * of Mojang's official loading screen (rather than an empty {@code glClear(0, 0, 0)} buffer right
 * after {@code glfwInit}/{@code glfwCreateWindow}).
 *
 * <p>Supports all official Minecraft Java Edition &amp; mod-loader splash screens:
 * <ul>
 *   <li>Mojang Studios vibrant crimson splash ({@code #EF323D}, 1.14–1.21.x–26.x+)</li>
 *   <li>Mojang Studios monochrome dark-mode splash (black canvas + white central logo &amp; bar)</li>
 *   <li>Classic Mojang white splash ({@code #FFFFFF}, 1.7–1.15)</li>
 *   <li>Forge / NeoForge early display &amp; custom resource-pack splash screens</li>
 * </ul>
 */
public final class MojangSplashDetector {

    /** Frame readback was null, invalid, or still uninitialized transparent alpha-0 memory. */
    public static final int FRAME_UNREADABLE = 0;
    /** Frame readback succeeded, but the surface is still an empty black {@code glClear} buffer. */
    public static final int FRAME_OPAQUE_BLANK = 1;
    /** Frame contains real rendered Mojang / mod-loader splash artwork, logo, or progress bar. */
    public static final int FRAME_VISIBLE_SPLASH = 2;

    /** Minimum bright pixel luminance (0..255) indicating visible logo, bar, or backdrop artwork. */
    private static final int LIT_LUMA_THRESHOLD = 42;
    /** Minimum overall average luminance indicating a lit backdrop (Mojang Red ~108, White ~255). */
    private static final int BACKDROP_AVG_LUMA_THRESHOLD = 28;

    private MojangSplashDetector() {
    }

    /**
     * Classifies a downsampled ARGB_8888 surface probe (e.g. {@code 48x27}) into
     * {@link #FRAME_UNREADABLE}, {@link #FRAME_OPAQUE_BLANK}, or {@link #FRAME_VISIBLE_SPLASH}.
     *
     * <p>Distinguishing {@link #FRAME_OPAQUE_BLANK} from {@link #FRAME_UNREADABLE} ensures the
     * loading GIF never cuts off prematurely onto a black {@code glClear} window during long
     * modded startups, while still falling back gracefully on drivers that block readback.
     */
    public static int classifyFrame(@Nullable int[] argbPixels, int width, int height) {
        if (argbPixels == null || width <= 0 || height <= 0) return FRAME_UNREADABLE;
        int total = width * height;
        if (argbPixels.length < total) return FRAME_UNREADABLE;

        long lumaSum = 0L;
        int opaqueCount = 0;
        int litCount = 0;
        int centerLitCount = 0;
        int mojangRedCount = 0;

        int cxMin = width / 6;
        int cxMax = width - cxMin;
        int cyMin = height / 5;
        int cyMax = height - (height / 8);

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            boolean inCenterY = (y >= cyMin && y <= cyMax);
            for (int x = 0; x < width; x++) {
                int c = argbPixels[rowOffset + x];
                int a = (c >>> 24) & 0xFF;
                if (a < 32) continue;
                opaqueCount++;

                int r = (c >>> 16) & 0xFF;
                int g = (c >>> 8) & 0xFF;
                int b = c & 0xFF;

                // Fast integer BT.601 luma approximation (0..255)
                int luma = (r * 77 + g * 150 + b * 29) >> 8;
                lumaSum += luma;

                if (r >= 175 && g <= 95 && b <= 105 && (r - g) >= 100) {
                    mojangRedCount++;
                }

                if (luma >= LIT_LUMA_THRESHOLD || r >= 72 || g >= 72 || b >= 72) {
                    litCount++;
                    if (inCenterY && x >= cxMin && x <= cxMax) {
                        centerLitCount++;
                    }
                }
            }
        }

        // Uninitialized transparent SurfaceTexture readback
        if (opaqueCount < total / 2) {
            return FRAME_UNREADABLE;
        }

        // 1. Mojang Studios iconic crimson splash (#EF323D)
        if (mojangRedCount >= Math.max(8, total / 5)) {
            return FRAME_VISIBLE_SPLASH;
        }

        // 2. Classic white Mojang splash, colored resource-pack splash, or full-screen loading UI
        long avgLuma = lumaSum / opaqueCount;
        if (avgLuma >= BACKDROP_AVG_LUMA_THRESHOLD) {
            return FRAME_VISIBLE_SPLASH;
        }

        // 3. Monochrome dark-mode Mojang Studios logo / white progress bar / Forge early loading text
        int minCenterLogoPixels = Math.max(4, total / 220);
        if (centerLitCount >= minCenterLogoPixels) {
            return FRAME_VISIBLE_SPLASH;
        }

        if (litCount >= Math.max(8, total / 110)) {
            return FRAME_VISIBLE_SPLASH;
        }

        return FRAME_OPAQUE_BLANK;
    }

    /**
     * Convenience wrapper returning {@code true} iff {@link #classifyFrame} detects real visible
     * splash artwork ({@link #FRAME_VISIBLE_SPLASH}).
     */
    public static boolean hasVisibleSplashFrame(@Nullable int[] argbPixels, int width, int height) {
        return classifyFrame(argbPixels, width, height) == FRAME_VISIBLE_SPLASH;
    }

    /**
     * Secondary log-line helper matching lines emitted once Minecraft's official Mojang
     * resource-reload splash overlay or title screen is active.
     */
    public static boolean isMojangSplashLogLine(@Nullable String line) {
        if (line == null || line.isEmpty()) return false;
        return line.contains("Reloading ResourceManager")
                || (line.contains("Created: ") && line.contains("minecraft:textures/atlas"))
                || line.contains("OpenAL initialized")
                || line.contains("Sound engine started")
                || line.contains("MOJANG STUDIOS");
    }
}
