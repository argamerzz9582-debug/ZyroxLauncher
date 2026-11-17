package git.artdeell.mojo.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import git.artdeell.mojo.utils.ArchiveSecurity;

/**
 * Unit and resource regression tests for {@link MojangSplashDetector} and the pure full-bleed
 * cherry-blossom GIF loading screen ({@code R.raw.zyrox_loading_scene}).
 */
public class MojangSplashAndLoadingScreenTest {

    private static final int W = 48;
    private static final int H = 27;

    @Test
    public void rejectsNullInvalidAndTransparentBuffersAsUnreadable() {
        assertEquals(MojangSplashDetector.FRAME_UNREADABLE,
                MojangSplashDetector.classifyFrame(null, W, H));
        assertEquals(MojangSplashDetector.FRAME_UNREADABLE,
                MojangSplashDetector.classifyFrame(new int[0], W, H));
        assertEquals(MojangSplashDetector.FRAME_UNREADABLE,
                MojangSplashDetector.classifyFrame(new int[10], W, H));
        assertEquals(MojangSplashDetector.FRAME_UNREADABLE,
                MojangSplashDetector.classifyFrame(new int[W * H], 0, H));

        int[] transparent = new int[W * H];
        assertEquals(MojangSplashDetector.FRAME_UNREADABLE,
                MojangSplashDetector.classifyFrame(transparent, W, H));
        assertFalse(MojangSplashDetector.hasVisibleSplashFrame(transparent, W, H));
    }

    @Test
    public void keepsLoadingGifAliveDuringBlackGlClearFrames() {
        int[] solidBlack = new int[W * H];
        Arrays.fill(solidBlack, 0xFF000000);
        assertEquals(MojangSplashDetector.FRAME_OPAQUE_BLANK,
                MojangSplashDetector.classifyFrame(solidBlack, W, H));
        assertFalse(MojangSplashDetector.hasVisibleSplashFrame(solidBlack, W, H));

        int[] nearBlackDither = new int[W * H];
        Arrays.fill(nearBlackDither, 0xFF050507);
        assertEquals(MojangSplashDetector.FRAME_OPAQUE_BLANK,
                MojangSplashDetector.classifyFrame(nearBlackDither, W, H));
        assertFalse(MojangSplashDetector.hasVisibleSplashFrame(nearBlackDither, W, H));
    }

    @Test
    public void detectsOfficialMojangStudiosCrimsonWhiteAndMonochromeSplashScreens() {
        // 1. Official Mojang Studios crimson splash (#EF323D, Minecraft 1.14–1.21.x–26.x+)
        int[] crimsonSplash = new int[W * H];
        Arrays.fill(crimsonSplash, 0xFFEF323D);
        assertEquals(MojangSplashDetector.FRAME_VISIBLE_SPLASH,
                MojangSplashDetector.classifyFrame(crimsonSplash, W, H));
        assertTrue(MojangSplashDetector.hasVisibleSplashFrame(crimsonSplash, W, H));

        // 2. Classic Mojang pure-white splash (#FFFFFF, Minecraft 1.7.10–1.15.2)
        int[] whiteSplash = new int[W * H];
        Arrays.fill(whiteSplash, 0xFFFFFFFF);
        assertEquals(MojangSplashDetector.FRAME_VISIBLE_SPLASH,
                MojangSplashDetector.classifyFrame(whiteSplash, W, H));
        assertTrue(MojangSplashDetector.hasVisibleSplashFrame(whiteSplash, W, H));

        // 3. Official monochrome dark-mode Mojang Studios splash (black bg + central white logo)
        int[] monochromeSplash = new int[W * H];
        Arrays.fill(monochromeSplash, 0xFF000000);
        int logoY = H / 2;
        for (int x = W / 3; x < (W * 2) / 3; x++) {
            monochromeSplash[logoY * W + x] = 0xFFFFFFFF;
        }
        assertEquals(MojangSplashDetector.FRAME_VISIBLE_SPLASH,
                MojangSplashDetector.classifyFrame(monochromeSplash, W, H));
        assertTrue(MojangSplashDetector.hasVisibleSplashFrame(monochromeSplash, W, H));

        // 4. Forge / NeoForge early loading dark window with bottom progress bar
        int[] forgeEarlyBar = new int[W * H];
        Arrays.fill(forgeEarlyBar, 0xFF020204);
        int barY = H - 2;
        for (int x = 6; x < 24; x++) {
            forgeEarlyBar[barY * W + x] = 0xFFCB3D35;
        }
        assertEquals(MojangSplashDetector.FRAME_VISIBLE_SPLASH,
                MojangSplashDetector.classifyFrame(forgeEarlyBar, W, H));
        assertTrue(MojangSplashDetector.hasVisibleSplashFrame(forgeEarlyBar, W, H));
    }

    @Test
    public void matchesMojangSplashLogMarkersAndIgnoresEarlyJvmLines() {
        assertTrue(MojangSplashDetector.isMojangSplashLogLine(
                "[Render thread/INFO]: Reloading ResourceManager: vanilla"));
        assertTrue(MojangSplashDetector.isMojangSplashLogLine(
                "[Render thread/INFO]: Created: 1024x512x4 minecraft:textures/atlas/gui.png-atlas"));
        assertTrue(MojangSplashDetector.isMojangSplashLogLine(
                "[Render thread/INFO]: Sound engine started"));

        assertFalse(MojangSplashDetector.isMojangSplashLogLine(null));
        assertFalse(MojangSplashDetector.isMojangSplashLogLine(""));
        assertFalse(MojangSplashDetector.isMojangSplashLogLine(
                "[main/INFO]: Setting user: Steve"));
        assertFalse(MojangSplashDetector.isMojangSplashLogLine(
                "[Render thread/INFO]: Backend library: LWJGL version 3.3.3"));
    }

    @Test
    public void verifiesBundledAnimatedGifOnlyLoadingScreenAndNoCrashWarningText() throws Exception {
        // 1. Verify bundled animated cherry-blossom scene GIF header, dimensions & infinite loop
        File gif = resolveModuleFile("src/main/res/raw/zyrox_loading_scene.gif");
        assertTrue("zyrox_loading_scene.gif must be bundled in res/raw", gif.isFile());
        assertTrue("zyrox_loading_scene.gif must be non-trivial multi-frame GIF", gif.length() > 500_000L);
        try (FileInputStream in = new FileInputStream(gif)) {
            byte[] header = new byte[4096];
            int read = in.read(header);
            assertTrue(read >= 64);
            String sig = new String(header, 0, 6, StandardCharsets.US_ASCII);
            assertEquals("GIF89a", sig);
            int width = (header[6] & 0xFF) | ((header[7] & 0xFF) << 8);
            int height = (header[8] & 0xFF) | ((header[9] & 0xFF) << 8);
            assertEquals(640, width);
            assertEquals(360, height);
            String headAscii = new String(header, 0, read, StandardCharsets.ISO_8859_1);
            assertTrue("GIF must carry NETSCAPE2.0 infinite loop extension",
                    headAscii.contains("NETSCAPE2.0"));
        }

        // 2. Verify activity_basemain.xml shows ONLY the GIF view inside @id/main_loading_screen
        File baseLayout = resolveModuleFile("src/main/res/layout/activity_basemain.xml");
        assertTrue(baseLayout.isFile());
        String layoutXml = readUtf8(baseLayout);
        int start = layoutXml.indexOf("@+id/main_loading_screen");
        assertTrue("main_loading_screen must exist", start > 0);
        int close = layoutXml.indexOf("</FrameLayout>", start);
        assertTrue(close > start);
        String loadingBlock = layoutXml.substring(start, close);
        assertTrue("Loading screen must render MinecraftLoadingLandscapeView",
                loadingBlock.contains("MinecraftLoadingLandscapeView"));
        assertFalse("Loading screen must not overlay any TextView on the GIF",
                loadingBlock.contains("<TextView"));
        assertFalse("Loading screen must not overlay any ProgressBar on the GIF",
                loadingBlock.contains("<ProgressBar"));
        assertFalse("Loading screen must not reference loading_screen_warning",
                loadingBlock.contains("loading_screen_warning"));

        // 3. Verify warning string was purged from string tables
        for (String rel : new String[]{
                "src/main/res/values/strings.xml",
                "src/main/res/values-ru/strings.xml"
        }) {
            File stringsFile = resolveModuleFile(rel);
            assertTrue(stringsFile.isFile());
            String xml = readUtf8(stringsFile);
            assertFalse(rel + " must not contain loading_screen_warning",
                    xml.contains("loading_screen_warning"));
            assertFalse(rel + " must not contain 'stuck here' warning text",
                    xml.toLowerCase().contains("stuck here"));
        }
    }

    private static File resolveModuleFile(String rel) {
        File direct = new File(rel);
        if (direct.exists()) return direct;
        return new File("app_mojo/" + rel);
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            return new String(ArchiveSecurity.readBytesBounded(in, 2 * 1024 * 1024), StandardCharsets.UTF_8);
        }
    }
}
