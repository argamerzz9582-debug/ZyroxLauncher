package git.artdeell.mojo.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import git.artdeell.mojo.skins.SkinStudioManager;
import git.artdeell.mojo.ui.ZyroxControlGlyphRenderer;
import git.artdeell.mojo.utils.ArchiveSecurity;

/**
 * Unit tests verifying {@link ControlProfileManager}, {@link ZyroxControlGlyphRenderer} 1:1 Liquid
 * Glass icon/label mapping against {@code assets/default.json}, and {@link SkinStudioManager}
 * 64×64 / 64×32 PNG header validation.
 */
public class ControlAndLiquidGlassTest {

    @Test
    public void mapsAllDefaultControlsToExactLiquidGlassGlyphsAndTextLabels() throws Exception {
        // Directional D-pad triangles
        assertEquals(ZyroxControlGlyphRenderer.Glyph.UP,
                ZyroxControlGlyphRenderer.resolveGlyph("▲", new int[]{87}));
        assertEquals(ZyroxControlGlyphRenderer.Glyph.DOWN,
                ZyroxControlGlyphRenderer.resolveGlyph("▼", new int[]{83}));
        assertEquals(ZyroxControlGlyphRenderer.Glyph.LEFT,
                ZyroxControlGlyphRenderer.resolveGlyph("◀", new int[]{65}));
        assertEquals(ZyroxControlGlyphRenderer.Glyph.RIGHT,
                ZyroxControlGlyphRenderer.resolveGlyph("▶", new int[]{68}));

        // Sneak center + Jump up-arrow + Gear menu icon
        assertEquals(ZyroxControlGlyphRenderer.Glyph.CENTER,
                ZyroxControlGlyphRenderer.resolveGlyph("◇", new int[]{340}));
        assertEquals(ZyroxControlGlyphRenderer.Glyph.JUMP,
                ZyroxControlGlyphRenderer.resolveGlyph("⬛", new int[]{32}));
        assertEquals(ZyroxControlGlyphRenderer.Glyph.GEAR,
                ZyroxControlGlyphRenderer.resolveGlyph("⚙", new int[]{ControlData.SPECIALBTN_MENU}));

        // All 10 named wide controls preserve crisp snow-white typography (Glyph.NONE)
        String[] textControls = {"Keyboard", "GUI", "PRI", "SEC", "Mouse", "Debug", "Chat", "Tab", "3rd", "Inv"};
        for (String label : textControls) {
            assertEquals(ZyroxControlGlyphRenderer.Glyph.NONE,
                    ZyroxControlGlyphRenderer.resolveGlyph(label, new int[]{84}));
        }

        // Audit shipped default.json control layout (zero keybind conflicts, zero broken formulas!)
        File defaultCtrl = new File("src/main/assets/default.json");
        if (defaultCtrl.isFile()) {
            try (FileInputStream in = new FileInputStream(defaultCtrl)) {
                String json = new String(ArchiveSecurity.readBytesBounded(in, 512 * 1024), StandardCharsets.UTF_8);
                ControlProfileManager.LayoutAuditReport report = ControlProfileManager.validateAndAuditJson(json);
                assertEquals(16, report.totalButtons);
                assertTrue("default.json must have zero keybind conflicts", report.keybindConflicts.isEmpty());
                assertTrue("default.json must have valid dynamic formulas", report.formulaWarnings.isEmpty());
            }
        }
    }

    @Test
    public void detectsDuplicateKeybindsAndUndersizedTouchTargets() throws Exception {
        String custom = "{\"version\":8,\"mControlDataList\":["
                + "{\"name\":\"JumpA\",\"width\":50,\"height\":50,\"dynamicX\":\"0\",\"dynamicY\":\"0\",\"keycodes\":[32,0,0,0]},"
                + "{\"name\":\"JumpB\",\"width\":20,\"height\":20,\"dynamicX\":\"50\",\"dynamicY\":\"0\",\"keycodes\":[32,0,0,0]}"
                + "]}";
        ControlProfileManager.LayoutAuditReport audit = ControlProfileManager.validateAndAuditJson(custom);
        assertEquals(2, audit.totalButtons);
        assertEquals(1, audit.keybindConflicts.size());
        assertTrue(audit.keybindConflicts.get(0).contains("SPACE"));
        assertEquals(1, audit.smallTargetWarnings.size());
        assertTrue(audit.smallTargetWarnings.get(0).contains("JumpB"));
    }

    @Test
    public void validatesSkinPng64x64And64x32AndRejectsInvalidDimensionsOrMagic() throws Exception {
        SkinStudioManager.PngValidation modern = SkinStudioManager.validateSkinPngBytes(fakePngHeader(64, 64));
        assertTrue(modern.valid);
        assertFalse(modern.legacy64x32);
        assertEquals(64, modern.width);
        assertEquals(64, modern.height);

        SkinStudioManager.PngValidation legacy = SkinStudioManager.validateSkinPngBytes(fakePngHeader(64, 32));
        assertTrue(legacy.valid);
        assertTrue(legacy.legacy64x32);

        SkinStudioManager.PngValidation wrongSize = SkinStudioManager.validateSkinPngBytes(fakePngHeader(128, 96));
        assertFalse(wrongSize.valid);

        SkinStudioManager.PngValidation notPng = SkinStudioManager.validateSkinPngBytes(
                "JPEG_NOT_A_REAL_PNG_FILE_WITH_ENOUGH_BYTES_1234567890".getBytes(StandardCharsets.UTF_8));
        assertFalse(notPng.valid);
    }

    private static byte[] fakePngHeader(int width, int height) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(baos);
        out.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        out.writeInt(13); // IHDR length
        out.writeBytes("IHDR");
        out.writeInt(width);
        out.writeInt(height);
        out.write(new byte[]{8, 6, 0, 0, 0}); // 8-bit RGBA
        out.writeInt(0); // CRC placeholder
        out.writeInt(0); // IEND
        return baos.toByteArray();
    }
}
