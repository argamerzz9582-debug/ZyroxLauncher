package git.artdeell.mojo.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import git.artdeell.mojo.ui.ZyroxGifDecoder;
import git.artdeell.mojo.utils.ArchiveSecurity;

/**
 * Unit and resource regression tests for {@link MojangSplashDetector} and the bundled
 * Minecraft-theme backdrop loops used by the Home and Loading screens.
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
    public void verifiesBundledFullHdLoadingAndHomeVideosAndProgressHud() throws Exception {
        // 1. Every bundled Minecraft backdrop (res/raw/zyrox_backdrop_*.mp4) must be a fast-start,
        //    muted 1080p H.264 loop with a matching first-frame WebP poster.
        File rawDir = resolveModuleFile("src/main/res/raw");
        File[] clips = rawDir.listFiles((d, n) -> n.startsWith("zyrox_backdrop_") && n.endsWith(".mp4"));
        assertTrue("At least the default home (snow) and loading (diamond) backdrops must ship",
                clips != null && clips.length >= 2);
        long totalBytes = 0L;
        for (File clip : clips) {
            verifyFastStartH264Mp4("src/main/res/raw/" + clip.getName());
            totalBytes += clip.length();
            String id = clip.getName().substring("zyrox_backdrop_".length(), clip.getName().length() - 4);
            File poster = resolveModuleFile("src/main/res/drawable-nodpi/bg_zyrox_backdrop_" + id + ".webp");
            assertTrue("poster for " + id + " must exist", poster.isFile() && poster.length() > 10_000L);
        }
        assertTrue("default backdrops must exist",
                resolveModuleFile("src/main/res/raw/zyrox_backdrop_snow.mp4").isFile()
                        && resolveModuleFile("src/main/res/raw/zyrox_backdrop_diamond.mp4").isFile());
        assertTrue("Backdrop clips must stay within a 40 MB APK budget (was 36 MB for two clips)",
                totalBytes < 40_000_000L);

        // Obsolete non-Minecraft clips/posters and low-res GIFs must be gone (zero dead weight)
        for (String obsolete : new String[]{
                "src/main/res/raw/zyrox_home_scene.mp4", "src/main/res/raw/zyrox_loading_scene.mp4",
                "src/main/res/raw/zyrox_home_scene.gif", "src/main/res/raw/zyrox_loading_scene.gif",
                "src/main/res/drawable-nodpi/bg_zyrox_shader_scene.webp",
                "src/main/res/drawable-nodpi/bg_zyrox_loading_poster.webp"}) {
            assertFalse(obsolete + " must be removed", resolveModuleFile(obsolete).exists());
        }

        // Verify activity_basemain.xml renders full-bleed Loading MP4 + Zyrox logo + progress HUD
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
        assertTrue("Loading screen must include official Zyrox logo badge",
                loadingBlock.contains("@drawable/ic_zyrox_logo"));
        assertTrue("Loading screen must include log-driven progress HUD container",
                loadingBlock.contains("@+id/main_loading_progress_hud"));
        assertTrue("Loading screen must include stage text, percent/ETA text, and ProgressBar",
                loadingBlock.contains("@+id/main_loading_stage_text")
                        && loadingBlock.contains("@+id/main_loading_percent_text")
                        && loadingBlock.contains("@+id/main_loading_progress_bar"));
        assertFalse("Loading screen must not reference loading_screen_warning",
                loadingBlock.contains("loading_screen_warning"));

        // Verify warning string remains purged from string tables
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

    @Test
    public void verifiesCustomGifResamplingFiltersRemainCrisp() {
        // Verify Nearest-Neighbor keeps crisp hard steps while Catmull-Rom Bicubic interpolates smoothly
        int[] checker2x2 = {0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000};
        int[] nn4x4 = ZyroxGifDecoder.resampleNearestNeighbor(checker2x2, 2, 2, 4, 4);
        assertEquals(0xFF000000, nn4x4[0]);
        assertEquals(0xFF000000, nn4x4[1]);
        assertEquals(0xFFFFFFFF, nn4x4[2]);
        int[] bc4x4 = ZyroxGifDecoder.resampleBicubic(checker2x2, 2, 2, 4, 4);
        assertEquals(16, bc4x4.length);
        int midR = (bc4x4[5] >>> 16) & 0xFF;
        assertTrue("Bicubic center sample interpolates between 0 and 239", midR > 16 && midR < 239);

        // Verify pre-cancelled flag aborts cleanly with zero work
        AtomicBoolean cancelled = new AtomicBoolean(true);
        assertTrue(ZyroxGifDecoder.decodeSeamlessLoop(
                new byte[32], true, cancelled, (w, h, px) -> px, null) == null);
    }

    private static void verifyFastStartH264Mp4(String relPath) throws Exception {
        File mp4 = resolveModuleFile(relPath);
        assertTrue(relPath + " must be bundled in res/raw", mp4.isFile());
        assertTrue(relPath + " must be a rich Full-HD H.264 video (> 2 MB)", mp4.length() > 2_000_000L);
        assertTrue(relPath + " must stay under 15 MB (short seamless loop)", mp4.length() < 15_000_000L);

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mp4, "r")) {
            long fileLen = raf.length();
            long pos = 0L;
            boolean sawFtyp = false;
            long moovOffset = -1L;
            long mdatOffset = -1L;
            byte[] boxHdr = new byte[8];
            while (pos + 8 <= fileLen) {
                raf.seek(pos);
                raf.readFully(boxHdr);
                long size = ((boxHdr[0] & 0xFFL) << 24)
                        | ((boxHdr[1] & 0xFFL) << 16)
                        | ((boxHdr[2] & 0xFFL) << 8)
                        | (boxHdr[3] & 0xFFL);
                String type = new String(boxHdr, 4, 4, StandardCharsets.US_ASCII);
                if (size == 1L) {
                    size = raf.readLong();
                } else if (size == 0L) {
                    size = fileLen - pos;
                }
                assertTrue("Valid ISO BMFF atom size in " + relPath, size >= 8L);
                if ("ftyp".equals(type)) sawFtyp = true;
                if ("moov".equals(type) && moovOffset < 0L) {
                    moovOffset = pos;
                    int readLen = (int) Math.min(size, 512 * 1024L);
                    byte[] moovBytes = new byte[readLen];
                    raf.seek(pos);
                    raf.readFully(moovBytes);
                    String moovLatin = new String(moovBytes, StandardCharsets.ISO_8859_1);
                    assertTrue(relPath + " must encode H.264 (avc1) video", moovLatin.contains("avc1"));
                    assertFalse(relPath + " must strip audio tracks (mp4a) to avoid audio focus disruption",
                            moovLatin.contains("mp4a"));
                }
                if ("mdat".equals(type) && mdatOffset < 0L) {
                    mdatOffset = pos;
                }
                pos += size;
            }
            assertTrue(relPath + " must start with ISO BMFF ftyp atom", sawFtyp);
            assertTrue(relPath + " must contain moov atom", moovOffset > 0L);
            assertTrue(relPath + " must contain mdat atom", mdatOffset > 0L);
            assertTrue(relPath + " must be +faststart optimized (moov before mdat)",
                    moovOffset < mdatOffset);
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
