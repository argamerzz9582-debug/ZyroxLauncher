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
    public void verifiesBundledStarlightLoadingGifAndLogDrivenProgressBar() throws Exception {
        // 1. Verify bundled Starlight sunrise loading scene GIF header, dimensions & infinite loop
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
            assertEquals(500, width);
            assertEquals(240, height);
            String headAscii = new String(header, 0, read, StandardCharsets.ISO_8859_1);
            assertTrue("GIF must carry NETSCAPE2.0 infinite loop extension",
                    headAscii.contains("NETSCAPE2.0"));
        }

        // 2. Verify activity_basemain.xml renders the full-bleed GIF + sleek log-driven progress HUD
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
        assertTrue("Loading screen must include log-driven progress HUD container",
                loadingBlock.contains("@+id/main_loading_progress_hud"));
        assertTrue("Loading screen must include stage text, percent/ETA text, and ProgressBar",
                loadingBlock.contains("@+id/main_loading_stage_text")
                        && loadingBlock.contains("@+id/main_loading_percent_text")
                        && loadingBlock.contains("@+id/main_loading_progress_bar"));
        assertFalse("Loading screen must not reference loading_screen_warning",
                loadingBlock.contains("loading_screen_warning"));

        // 3. Verify warning string remains purged from string tables
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
    public void decodesHomeAndLoadingGifsIntoSeamlessZeroBufferLoops() throws Exception {
        // 1. Verify Home screen Starlight Firefly lakeside GIF (540x304, 59 frames @ 30ms)
        verifySeamlessGifAsset("src/main/res/raw/zyrox_home_scene.gif", 540, 304, 59, 30);

        // 2. Verify Loading screen Starlight Sunrise lakeside GIF (500x240, 48 frames @ 40ms)
        verifySeamlessGifAsset("src/main/res/raw/zyrox_loading_scene.gif", 500, 240, 48, 40);
    }

    private static void verifySeamlessGifAsset(String relPath,
                                               int expectedW,
                                               int expectedH,
                                               int expectedFrames,
                                               int expectedDelayMs) throws Exception {
        File gifFile = resolveModuleFile(relPath);
        assertTrue(relPath + " must exist", gifFile.isFile());
        byte[] gifBytes;
        try (FileInputStream in = new FileInputStream(gifFile)) {
            gifBytes = ArchiveSecurity.readBytesBounded(in, 8 * 1024 * 1024);
        }

        ZyroxGifDecoder.GifMetadata meta = ZyroxGifDecoder.inspectMetadata(gifBytes);
        assertTrue("Metadata must parse for " + relPath, meta != null);
        assertEquals(expectedW, meta.width);
        assertEquals(expectedH, meta.height);
        assertEquals(expectedFrames, meta.frameCount);
        assertEquals(expectedDelayMs, meta.medianDelayMs);

        // Decode raw (unblended) at 1x full native resolution into lossless 32-bit ARGB_8888
        ZyroxGifDecoder.SeamlessSequence<int[]> rawSeq = ZyroxGifDecoder.decodeSeamlessLoop(
                gifBytes, false, null, (w, h, px) -> Arrays.copyOf(px, px.length), null);
        assertTrue(rawSeq != null);
        assertEquals(expectedW, rawSeq.width);
        assertEquals(expectedH, rawSeq.height);
        assertEquals(expectedFrames, rawSeq.frames.size());
        double rawWrap = meanArgbDiff(rawSeq.frames.get(rawSeq.frames.size() - 1), rawSeq.frames.get(0));

        // Decode C¹-continuous seamless loop at full 1x native ARGB_8888 resolution & verify preview parity
        AtomicInteger previewCount = new AtomicInteger();
        final int[][] previewHolder = new int[1][];
        ZyroxGifDecoder.SeamlessSequence<int[]> seamless = ZyroxGifDecoder.decodeSeamlessLoop(
                gifBytes, true, null,
                (w, h, px) -> Arrays.copyOf(px, px.length),
                (preview, w, h) -> {
                    previewCount.incrementAndGet();
                    previewHolder[0] = preview;
                });
        assertTrue("Seamless sequence must decode cleanly for " + relPath, seamless != null);
        assertEquals(expectedW, seamless.width);
        assertEquals(expectedH, seamless.height);
        assertEquals(expectedDelayMs * 1_000_000L, seamless.frameDurationNs);
        assertEquals("Preview callback fires exactly once", 1, previewCount.get());
        assertTrue("Preview frame matches seamless frame 0 identically (zero startup snap)",
                Arrays.equals(previewHolder[0], seamless.frames.get(0)));

        List<int[]> frames = seamless.frames;
        int m = frames.size();
        assertTrue("Seamless loop retains healthy frame count", m >= 20 && m < expectedFrames);

        // Every frame must contain full 32-bit ARGB_8888 illuminated scene pixels (no RGB_565 truncation)
        for (int i = 0; i < m; i++) {
            int[] f = frames.get(i);
            assertEquals(expectedW * expectedH, f.length);
            long lumaSum = 0L;
            int samples = 0;
            for (int p = 0; p < f.length; p += 97) {
                int argb = f[p];
                assertEquals("Opaque scene frame alpha must be 0xFF", 0xFF, (argb >>> 24) & 0xFF);
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                lumaSum += (r * 77 + g * 150 + b * 29) >> 8;
                samples++;
            }
            long avgLuma = lumaSum / samples;
            assertTrue(relPath + " frame " + i + " must contain valid illuminated pixels (luma=" + avgLuma + ")",
                    avgLuma >= 18 && avgLuma <= 230);
        }

        // Verify Nearest-Neighbor keeps crisp hard steps while Catmull-Rom Bicubic interpolates smoothly
        int[] checker2x2 = {0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000};
        int[] nn4x4 = ZyroxGifDecoder.resampleNearestNeighbor(checker2x2, 2, 2, 4, 4);
        assertEquals(0xFF000000, nn4x4[0]);
        assertEquals(0xFF000000, nn4x4[1]);
        assertEquals(0xFFFFFFFF, nn4x4[2]);
        int[] bc4x4 = ZyroxGifDecoder.resampleBicubic(checker2x2, 2, 2, 4, 4);
        assertEquals(16, bc4x4.length);
        int midR = (bc4x4[5] >>> 16) & 0xFF;
        assertTrue("Bicubic center sample interpolates between 0 and 255", midR > 16 && midR < 239);

        // Verify seamless wrap (frame[M-1] -> frame[0]) matches normal per-frame motion cleanly
        double stepSum = 0.0;
        for (int i = 0; i < m - 1; i++) {
            stepSum += meanArgbDiff(frames.get(i), frames.get(i + 1));
        }
        double avgStep = stepSum / (m - 1);
        double seamlessWrap = meanArgbDiff(frames.get(m - 1), frames.get(0));
        if (rawWrap > avgStep * 2.0) {
            assertTrue(relPath + " seamless wrap (" + seamlessWrap + ") must strongly reduce raw cut (" + rawWrap + ")",
                    seamlessWrap < rawWrap * 0.65);
        }
        assertTrue(relPath + " seamless wrap (" + seamlessWrap + ") must stay smooth relative to step (" + avgStep + ")",
                seamlessWrap <= Math.max(avgStep * 1.65, 2.5));

        // Verify pre-cancelled flag aborts cleanly with zero work
        AtomicBoolean cancelled = new AtomicBoolean(true);
        assertTrue(ZyroxGifDecoder.decodeSeamlessLoop(
                gifBytes, true, cancelled, (w, h, px) -> px, null) == null);
    }

    private static double meanArgbDiff(int[] a, int[] b) {
        long diff = 0L;
        int samples = 0;
        for (int i = 0; i < a.length; i += 13) {
            int ca = a[i];
            int cb = b[i];
            diff += Math.abs(((ca >>> 16) & 0xFF) - ((cb >>> 16) & 0xFF));
            diff += Math.abs(((ca >>> 8) & 0xFF) - ((cb >>> 8) & 0xFF));
            diff += Math.abs((ca & 0xFF) - (cb & 0xFF));
            samples += 3;
        }
        return (double) diff / (double) samples;
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
