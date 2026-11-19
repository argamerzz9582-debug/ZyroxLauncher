package git.artdeell.mojo.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import git.artdeell.mojo.LwjglGlfwKeycode;
import git.artdeell.mojo.input.ControlData;
import git.artdeell.mojo.input.buttons.ControlButton;
import git.artdeell.mojo.ui.ZyroxControlGlyphRenderer;
import git.artdeell.mojo.ui.ZyroxControlGlyphRenderer.Glyph;
import git.artdeell.mojo.ui.ZyroxLoopingVideoView;
import git.artdeell.mojo.utils.ArchiveSecurity;

/**
 * Comprehensive verification suite for Zyrox Launcher v2.4.0:
 * <ol>
 *   <li>Hardware center-crop MP4 video scaling &amp; uncompressed APK streaming config.</li>
 *   <li>Official Zyrox logo icon suite (adaptive, round, square, monochrome, in-app brand badge).</li>
 *   <li>Directional arrow vectors, Unicode/variation-selector/keycode glyph resolution, keybind
 *       fallback labels, and theme-wide unclipped button padding safeguards.</li>
 * </ol>
 */
public class ZyroxV24OverhaulVerificationTest {

    @Test
    public void verifiesCenterCropVideoTransformAcrossAllDisplayAspects() {
        float[] scales = new float[2];

        // Exact 16:9 (1920x1080 on 1920x1080) -> ~1.004x uniform overscan, zero black bars
        assertTrue(ZyroxLoopingVideoView.computeCenterCropScales(1920, 1080, 1920, 1080, scales));
        assertEquals(1.004f, scales[0], 0.002f);
        assertEquals(1.004f, scales[1], 0.002f);

        // Ultrawide 20:9 phone landscape (2400x1080) -> scales Y to preserve aspect & fill width
        assertTrue(ZyroxLoopingVideoView.computeCenterCropScales(2400, 1080, 1920, 1080, scales));
        assertEquals(1.004f, scales[0], 0.002f);
        assertTrue(scales[1] > 1.24f && scales[1] < 1.27f);
        // Check rendered aspect matches source 16:9 (1.7778)
        float effectiveAspect = (2400f * scales[0]) / (1080f * scales[1]);
        assertEquals(16f / 9f, effectiveAspect, 0.01f);

        // 4:3 tablet / foldable (2048x1536) -> scales X to preserve aspect & fill height
        assertTrue(ZyroxLoopingVideoView.computeCenterCropScales(2048, 1536, 1920, 1080, scales));
        assertTrue(scales[0] > 1.33f && scales[0] < 1.35f);
        assertEquals(1.004f, scales[1], 0.002f);
        float tabletAspect = (2048f * scales[0]) / (1536f * scales[1]);
        assertEquals(16f / 9f, tabletAspect, 0.01f);

        // Reject degenerate/zero dimensions cleanly
        assertFalse(ZyroxLoopingVideoView.computeCenterCropScales(0, 1080, 1920, 1080, scales));
        assertFalse(ZyroxLoopingVideoView.computeCenterCropScales(1920, 1080, 0, 1080, scales));
        assertFalse(ZyroxLoopingVideoView.computeCenterCropScales(1920, 1080, 1920, 1080, new float[1]));
    }

    @Test
    public void verifiesBuildGradleUncompressedMp4AndOfficialLogoSuite() throws Exception {
        String gradle = readUtf8(resolveFile("build.gradle"));
        assertTrue("Version must be bumped to 2.6.0", gradle.contains("return \"2.6.0\""));
        assertTrue("MP4 assets must be stored uncompressed for openRawResourceFd streaming",
                gradle.contains("noCompress") && gradle.contains("'mp4'"));

        // Verify official Zyrox logo assets across every density and in-app crest placement
        File brandLogo = resolveFile("src/main/res/drawable-nodpi/ic_zyrox_logo.webp");
        assertTrue("drawable-nodpi/ic_zyrox_logo.webp must exist", brandLogo.isFile() && brandLogo.length() > 8_000L);
        for (String density : new String[]{"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"}) {
            for (String icon : new String[]{"ic_launcher.webp", "ic_launcher_round.webp", "ic_launcher_foreground.webp"}) {
                File f = resolveFile("src/main/res/mipmap-" + density + "/" + icon);
                assertTrue(f.getPath() + " must exist and be non-empty", f.isFile() && f.length() > 400L);
            }
        }

        String homeXml = readUtf8(resolveFile("src/main/res/layout/fragment_launcher.xml"));
        String homeLandXml = readUtf8(resolveFile("src/main/res/layout-land/fragment_launcher.xml"));
        assertEquals("Home layout and layout-land must stay byte-identical", homeXml, homeLandXml);
        assertTrue("Home hero emblem must showcase @drawable/ic_zyrox_logo",
                homeXml.contains("@drawable/ic_zyrox_logo"));

        String shellXml = readUtf8(resolveFile("src/main/res/layout/activity_mojo_ui.xml"));
        String shellLandXml = readUtf8(resolveFile("src/main/res/layout-land/activity_mojo_ui.xml"));
        assertEquals("Shell layout and layout-land must stay byte-identical", shellXml, shellLandXml);
        assertTrue("Global header must display @drawable/ic_zyrox_logo and back arrow vector",
                shellXml.contains("@drawable/ic_zyrox_logo")
                        && shellXml.contains("@drawable/ic_zyrox_arrow_left"));
    }

    @Test
    public void verifiesArrowButtonsGlyphCoverageAndUnclippedButtonStyles() throws Exception {
        // 1. All 4 directional vector arrows exist
        for (String dir : new String[]{"up", "down", "left", "right"}) {
            File vec = resolveFile("src/main/res/drawable/ic_zyrox_arrow_" + dir + ".xml");
            assertTrue(vec.getPath() + " must exist", vec.isFile());
        }

        // 2. Java GUI installer uses crisp vector arrow drawables (never missing font triangles)
        String awtXml = readUtf8(resolveFile("src/main/res/layout/activity_java_gui_launcher.xml"));
        assertTrue(awtXml.contains("@drawable/ic_zyrox_arrow_up"));
        assertTrue(awtXml.contains("@drawable/ic_zyrox_arrow_down"));
        assertTrue(awtXml.contains("@drawable/ic_zyrox_arrow_left"));
        assertTrue(awtXml.contains("@drawable/ic_zyrox_arrow_right"));
        assertFalse(awtXml.contains("android:text=\"▲\""));
        assertFalse(awtXml.contains("android:text=\"▼\""));
        assertFalse(awtXml.contains("android:text=\"◀\""));
        assertFalse(awtXml.contains("android:text=\"▶\""));

        // 3. Master button theme clears oversized Material minSize & vertical padding so labels never clip
        String stylesXml = readUtf8(resolveFile("src/main/res/values/styles.xml"));
        assertTrue(stylesXml.contains("<item name=\"android:minWidth\">0dp</item>"));
        assertTrue(stylesXml.contains("<item name=\"android:minHeight\">0dp</item>"));
        assertTrue(stylesXml.contains("<item name=\"android:paddingTop\">0dp</item>"));
        assertTrue(stylesXml.contains("<item name=\"android:paddingBottom\">0dp</item>"));
        assertTrue(stylesXml.contains("<item name=\"android:includeFontPadding\">false</item>"));

        // 4. Broad Unicode arrow + variation-selector + unlabeled keycode resolution
        assertEquals(Glyph.UP, ZyroxControlGlyphRenderer.resolveGlyph("⬆\uFE0F", null));
        assertEquals(Glyph.DOWN, ZyroxControlGlyphRenderer.resolveGlyph("⬇\uFE0F", null));
        assertEquals(Glyph.LEFT, ZyroxControlGlyphRenderer.resolveGlyph("⬅\uFE0F", null));
        assertEquals(Glyph.RIGHT, ZyroxControlGlyphRenderer.resolveGlyph("➡\uFE0F", null));
        assertEquals(Glyph.UP, ZyroxControlGlyphRenderer.resolveGlyph("▴", null));
        assertEquals(Glyph.DOWN, ZyroxControlGlyphRenderer.resolveGlyph("▾", null));
        assertEquals(Glyph.LEFT, ZyroxControlGlyphRenderer.resolveGlyph("◂", null));
        assertEquals(Glyph.RIGHT, ZyroxControlGlyphRenderer.resolveGlyph("▸", null));
        assertEquals(Glyph.GEAR, ZyroxControlGlyphRenderer.resolveGlyph("⚙\uFE0F", null));

        // Unlabeled directional & scroll keys recover crisp vector arrows automatically
        assertEquals(Glyph.UP, ZyroxControlGlyphRenderer.resolveGlyph("", new int[]{LwjglGlfwKeycode.GLFW_KEY_UP}));
        assertEquals(Glyph.DOWN, ZyroxControlGlyphRenderer.resolveGlyph("", new int[]{LwjglGlfwKeycode.GLFW_KEY_DOWN}));
        assertEquals(Glyph.LEFT, ZyroxControlGlyphRenderer.resolveGlyph("", new int[]{LwjglGlfwKeycode.GLFW_KEY_LEFT}));
        assertEquals(Glyph.RIGHT, ZyroxControlGlyphRenderer.resolveGlyph("", new int[]{LwjglGlfwKeycode.GLFW_KEY_RIGHT}));
        assertEquals(Glyph.UP, ZyroxControlGlyphRenderer.resolveGlyph("", new int[]{ControlData.SPECIALBTN_SCROLLUP}));
        assertEquals(Glyph.DOWN, ZyroxControlGlyphRenderer.resolveGlyph("", new int[]{ControlData.SPECIALBTN_SCROLLDOWN}));

        // Unlabeled regular action keys recover readable short text labels
        assertEquals("PRI", ControlButton.inferKeybindFallbackLabel(new int[]{ControlData.SPECIALBTN_MOUSEPRI}));
        assertEquals("SEC", ControlButton.inferKeybindFallbackLabel(new int[]{ControlData.SPECIALBTN_MOUSESEC}));
        assertEquals("F5", ControlButton.inferKeybindFallbackLabel(new int[]{LwjglGlfwKeycode.GLFW_KEY_F5}));
        assertEquals("Q", ControlButton.inferKeybindFallbackLabel(new int[]{LwjglGlfwKeycode.GLFW_KEY_Q}));
        assertEquals("ESC", ControlButton.inferKeybindFallbackLabel(new int[]{LwjglGlfwKeycode.GLFW_KEY_ESCAPE}));
    }

    private static File resolveFile(String rel) {
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
