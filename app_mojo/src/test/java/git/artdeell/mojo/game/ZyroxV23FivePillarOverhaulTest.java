package git.artdeell.mojo.game;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.zxing.common.BitMatrix;

import net.objecthunter.exp4j.ExpressionBuilder;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import git.artdeell.mojo.authenticator.accounts.SkinHeadRenderer;
import git.artdeell.mojo.diagnostics.GraphicsAdvisor;
import git.artdeell.mojo.diagnostics.PerformanceHudOverlay;
import git.artdeell.mojo.diagnostics.ZyroxCrashAnalyzer;
import git.artdeell.mojo.downloader.RuntimeDriverStoreDialog;
import git.artdeell.mojo.game.renderer.impl.MesaRenderSpec;
import git.artdeell.mojo.input.ControlProfileManager;
import git.artdeell.mojo.input.mouse.GyroControl;
import git.artdeell.mojo.metadata.JavaRuntimeAdvisor;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.tasks.mojson.mirrors.DownloadMirror;
import git.artdeell.mojo.ui.ZyroxGifDecoder;
import git.artdeell.mojo.ui.ZyroxSeamlessGifView;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.utils.GpuUtils.GpuArchitecture;
import git.artdeell.mojo.utils.ZyroxHttpClient;
import git.artdeell.mojo.vulkan.DriverManager;
import git.artdeell.mojo.worlds.LevelDatNbtEditor;
import git.artdeell.mojo.worlds.WorldManager;

/**
 * End-to-end JUnit verification suite for Zyrox Launcher v2.3.0 across all 5 engineering pillars.
 */
public class ZyroxV23FivePillarOverhaulTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void resetMirrorState() {
        DownloadMirror.resetEndpointHealth();
        LauncherPreferences.PREF_DOWNLOAD_SOURCE = "default";
    }

    // =========================================================================
    // PILLAR 1: Zero-Loss ARGB_8888 GIF Scaling (Nearest-Neighbor vs Bicubic)
    // =========================================================================

    @Test
    public void pillar1_gifNearestVsCatmullRomBicubicAndFilterParsing() {
        assertEquals(ZyroxSeamlessGifView.ScalingFilter.NEAREST_NEIGHBOR,
                ZyroxSeamlessGifView.ScalingFilter.fromPreference("nearest"));
        assertEquals(ZyroxSeamlessGifView.ScalingFilter.BICUBIC,
                ZyroxSeamlessGifView.ScalingFilter.fromPreference("bicubic"));
        assertEquals(ZyroxSeamlessGifView.ScalingFilter.BICUBIC,
                ZyroxSeamlessGifView.ScalingFilter.fromPreference(null));

        // 2x2 crisp pixel-art checkerboard: pure white and pure black with 100% alpha
        int[] src = {
                0xFFFFFFFF, 0xFF000000,
                0xFF000000, 0xFFFFFFFF
        };
        int[] nearest = ZyroxGifDecoder.resampleNearestNeighbor(src, 2, 2, 4, 4);
        assertEquals(16, nearest.length);
        // Nearest-Neighbor preserves razor-sharp hard pixel corners with zero blended grays
        for (int px : nearest) {
            assertTrue("Expected crisp black/white pixel, got " + Integer.toHexString(px),
                    px == 0xFFFFFFFF || px == 0xFF000000);
        }

        // Catmull-Rom Bicubic produces smooth anti-aliased intermediate luminance while keeping alpha=255
        int[] bicubic = ZyroxGifDecoder.resampleBicubic(src, 2, 2, 6, 6);
        assertEquals(36, bicubic.length);
        boolean foundIntermediate = false;
        for (int px : bicubic) {
            assertEquals("Opaque 32-bit ARGB_8888 alpha must stay 0xFF", 0xFF, (px >>> 24) & 0xFF);
            int r = (px >>> 16) & 0xFF;
            if (r > 16 && r < 239) foundIntermediate = true;
        }
        assertTrue("Catmull-Rom bicubic should smoothly interpolate interior samples", foundIntermediate);
    }

    // =========================================================================
    // PILLAR 2: Draggable Pro HUD Clamping, Magnetic Snap, 1% Low FPS & VmRSS
    // =========================================================================

    @Test
    public void pillar2_hudSafeClampingMagneticSnapOnePercentLowAndVmRss() {
        // Off-screen negative coordinates clamp cleanly and snap to left/top edges
        float[] snappedLeft = PerformanceHudOverlay.clampAndSnapPosition(
                8f, -40f, 220, 70, 2400, 1080, null, 16f);
        assertEquals(0f, snappedLeft[0], 0.01f);
        assertEquals(0f, snappedLeft[1], 0.01f);

        // Near right edge snaps cleanly to parentW - viewW
        float[] snappedRight = PerformanceHudOverlay.clampAndSnapPosition(
                2170f, 500f, 220, 70, 2400, 1080, null, 16f);
        assertEquals(2180f, snappedRight[0], 0.01f);
        assertEquals(500f, snappedRight[1], 0.01f);

        // 1% Low FPS: 99 frames at 120 FPS (8.33 ms) + 1 hitch frame at 25 FPS (40 ms) => 1% low == 25 FPS
        long[] deltas = new long[100];
        for (int i = 0; i < 99; i++) deltas[i] = 8_333_333L;
        deltas[99] = 40_000_000L;
        double low1 = PerformanceHudOverlay.computeOnePercentLowFps(deltas, 100, new long[100]);
        assertEquals(25.0, low1, 0.25);

        // Linux /proc/self/status VmRSS parser (kB -> MB)
        String procStatus = "Name:\tcom.zyrox.launcher\nVmSize:\t8421376 kB\nVmRSS:\t  524288 kB\nThreads:\t64\n";
        assertEquals(512, PerformanceHudOverlay.parseVmRssMb(procStatus));
    }

    // =========================================================================
    // PILLAR 3: exp4j Formula Parity, HTTP/2 Security & Mirror Failover Router
    // =========================================================================

    @Test
    public void pillar3_exp4jFormulasStrictHttpsAndAutomaticMirrorFailover() {
        double btnX = new ExpressionBuilder("(${screen_width} - ${width}) / 2 + ${margin}")
                .variables("${screen_width}", "${width}", "${margin}")
                .build()
                .setVariable("${screen_width}", 2400)
                .setVariable("${width}", 200)
                .setVariable("${margin}", 12)
                .evaluate();
        assertEquals(1112.0, btnX, 0.001);

        assertTrue(ZyroxHttpClient.isStrictHttpsUrl("https://api.modrinth.com/v2/search"));
        assertFalse(ZyroxHttpClient.isStrictHttpsUrl("http://api.modrinth.com/v2/search"));
        assertFalse(ZyroxHttpClient.isStrictHttpsUrl("file:///etc/passwd"));
        assertTrue(ZyroxHttpClient.isRetryableStatus(429));
        assertTrue(ZyroxHttpClient.isRetryableStatus(503));
        assertFalse(ZyroxHttpClient.isRetryableStatus(404));

        // Automatic mirror failover + 90-second transient endpoint cooldown promotion
        String assetUrl = "https://resources.download.minecraft.net/ab/abcdef123456";
        List<String> initial = DownloadMirror.buildFailoverCandidates(assetUrl);
        assertEquals(2, initial.size());
        assertEquals(assetUrl, initial.get(0));
        assertEquals("https://bmclapi2.bangbang93.com/assets/ab/abcdef123456", initial.get(1));

        // Simulate Mojang CDN outage -> BMCLAPI HTTPS automatically promotes to #1
        DownloadMirror.recordEndpointFailure(assetUrl);
        List<String> afterFailure = DownloadMirror.buildFailoverCandidates(assetUrl);
        assertEquals("https://bmclapi2.bangbang93.com/assets/ab/abcdef123456", afterFailure.get(0));
        assertEquals(assetUrl, afterFailure.get(1));
    }

    // =========================================================================
    // PILLAR 4: GPU Micro-Arch, Turnip .adpkg, Mesa Cache & Crash Auto-Fix
    // =========================================================================

    @Test
    public void pillar4_gpuClassifierTurnipAdpkgMesaIsolationAndCrashAutoFix() throws Exception {
        // GPU architecture classification across modern Android silicon
        assertEquals(GpuArchitecture.ADRENO_8XX,
                GpuUtils.detectArchitecture("Qualcomm", "Adreno (TM) 830"));
        assertEquals(GpuArchitecture.ADRENO_7XX,
                GpuUtils.detectArchitecture("Qualcomm", "Adreno (TM) 750"));
        assertEquals(GpuArchitecture.ADRENO_6XX,
                GpuUtils.detectArchitecture("Qualcomm", "Adreno (TM) 650"));
        assertEquals(GpuArchitecture.ARM_IMMORTALIS,
                GpuUtils.detectArchitecture("ARM", "Immortalis-G720 MC12"));
        assertEquals(GpuArchitecture.SAMSUNG_XCLIPSE,
                GpuUtils.detectArchitecture("Samsung Electronics Co., Ltd.", "ANGLE (Samsung Xclipse 940)"));
        assertEquals(GpuArchitecture.POWERVR,
                GpuUtils.detectArchitecture("Imagination Technologies", "PowerVR Rogue GE8320"));

        // Optimal preset gives Adreno 7xx with Turnip + Vulkan -> Zink + custom Turnip driver
        GraphicsAdvisor.RendererPreset adrenoPreset = GraphicsAdvisor.computeOptimalPreset(
                GpuArchitecture.ADRENO_7XX, "Adreno (TM) 750", 3, true, true, true, false, true, 12288);
        assertEquals("vulkan_zink", adrenoPreset.rendererId);
        assertFalse(adrenoPreset.preferSystemVulkan);

        // Turnip .adpkg / .adpkg.zip / .zip detection + nested archive validation
        assertTrue(DriverManager.isSupportedDriverArchiveName("turnip_v25.1.0_R2.adpkg"));
        assertTrue(DriverManager.isSupportedDriverArchiveName("mesa-turnip-mrpurple.adpkg.zip"));
        assertTrue(DriverManager.isSupportedDriverArchiveName("turnip_a750.zip"));
        assertFalse(DriverManager.isSupportedDriverArchiveName("not_a_driver.apk"));

        byte[] nestedAdpkg = buildSyntheticAdpkgZip("turnip_folder/", false);
        String hash = DriverManager.validateDriver(new ByteArrayInputStream(nestedAdpkg));
        assertNotNull("Nested .adpkg containing meta.json + libvulkan_*.so must validate", hash);

        byte[] traversalZip = buildSyntheticAdpkgZip("../escape/", true);
        assertNull("Zip-Slip driver archive must be rejected",
                DriverManager.validateDriver(new ByteArrayInputStream(traversalZip)));

        // Mesa shader cache isolation per renderer + driver fingerprint
        String cacheA = MesaRenderSpec.buildShaderCacheFolderName("vulkan_zink", "turnip_25_1");
        String cacheB = MesaRenderSpec.buildShaderCacheFolderName("vulkan_zink", "turnip_25_2");
        assertNotEquals(cacheA, cacheB);

        Map<String, String> env = new HashMap<>();
        MesaRenderSpec.applyArchitectureTuning(
                env, GpuArchitecture.ADRENO_7XX, "Adreno (TM) 750", true, true, false, false);
        assertEquals("true", env.get("mesa_glthread"));

        // Turnip & JRE GitHub catalog parsers (strictly HTTPS)
        String turnipJson = "[{\"tag_name\":\"v25.1.0-R3\",\"name\":\"Turnip v25.1.0 R3\",\"assets\":["
                + "{\"name\":\"turnip_v25.1.0_r3_gmem.adpkg\",\"size\":4194304,"
                + "\"browser_download_url\":\"https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/v25.1.0/turnip.adpkg\"}]}]";
        List<RuntimeDriverStoreDialog.CatalogAsset> drivers =
                RuntimeDriverStoreDialog.parseTurnipReleases(turnipJson, GpuArchitecture.ADRENO_7XX);
        assertEquals(1, drivers.size());
        assertTrue(drivers.get(0).downloadUrl.startsWith("https://"));

        // Java runtime advisor including MC 1.16.5 (8), 1.18.2 (17), 1.21.4 (21), 26.1 (25)
        assertEquals(8, JavaRuntimeAdvisor.resolveRequiredJava(null, "1.16.5"));
        assertEquals(17, JavaRuntimeAdvisor.resolveRequiredJava(null, "1.18.2"));
        assertEquals(21, JavaRuntimeAdvisor.resolveRequiredJava(null, "1.21.4"));
        assertEquals(25, JavaRuntimeAdvisor.resolveRequiredJava(null, "26.1"));

        // Crash analyzer one-tap auto-fix generation
        ZyroxCrashAnalyzer.Diagnosis oomDiag = ZyroxCrashAnalyzer.analyze(
                "java.lang.OutOfMemoryError: Java heap space\n\tat net.minecraft.client.Minecraft.run");
        assertFalse(oomDiag.autoFixes.isEmpty());
        assertEquals(ZyroxCrashAnalyzer.AutoFixType.ADJUST_JVM_HEAP, oomDiag.autoFixes.get(0).type);

        ZyroxCrashAnalyzer.Diagnosis vkDiag = ZyroxCrashAnalyzer.analyze(
                "ZINK: vkCreateInstance failed (VK_ERROR_INCOMPATIBLE_DRIVER)");
        assertFalse(vkDiag.autoFixes.isEmpty());
        assertEquals(ZyroxCrashAnalyzer.AutoFixType.APPLY_SAFE_RENDERER, vkDiag.autoFixes.get(0).type);
    }

    // =========================================================================
    // PILLAR 5: Gyro, 120Hz Touch, QR Controls, Macros, 3D Skin/Cape & NBT Edit
    // =========================================================================

    @Test
    public void pillar5_gyroDeadzoneAnd120HzHermiteTouchInterpolation() {
        float[] out = new float[2];
        // Sub-threshold sensor noise is completely suppressed
        assertFalse(GyroControl.applyRadialDeadzone(0.0003f, -0.0002f, 0.001f, out));
        assertEquals(0f, out[0], 1e-6f);
        assertEquals(0f, out[1], 1e-6f);

        // Deliberate aim sweep preserves exact angular direction without discontinuities
        assertTrue(GyroControl.applyRadialDeadzone(0.02f, 0.01f, 0.001f, out));
        assertEquals(2.0f, out[0] / out[1], 0.01f);

        // 1-Euro adaptive alpha is heavier on micro-aim and near-1.0 (zero lag) on fast flicks
        float slowAlpha = GyroControl.computeAdaptiveSmoothingAlpha(0.005f, 0.02f);
        float fastAlpha = GyroControl.computeAdaptiveSmoothingAlpha(0.005f, 3.5f);
        assertTrue("Fast flicks must track with higher alpha than micro-jitter", fastAlpha > slowAlpha);

        // 120Hz Hermite touch sub-stepper preserves EXACT total displacement (zero camera drift!)
        float[] pairs = new float[TouchEventProcessor.MAX_INTERPOLATION_SUBSTEPS * 2];
        int steps = TouchEventProcessor.computeInterpolatedSubSteps(
                36f, -18f, 24L, 6.94f, 1.2f, -0.5f, pairs);
        assertTrue(steps >= 2 && steps <= TouchEventProcessor.MAX_INTERPOLATION_SUBSTEPS);
        float sumX = 0f, sumY = 0f;
        for (int i = 0; i < steps; i++) {
            sumX += pairs[i * 2];
            sumY += pairs[i * 2 + 1];
        }
        assertEquals(36f, sumX, 0.001f);
        assertEquals(-18f, sumY, 0.001f);
    }

    @Test
    public void pillar5_controlProfileZxingQrRoundTripAndMacroSequencer() throws Exception {
        String sampleControlJson = "{\"version\":8,\"scaledAt\":100,\"mControlDataList\":["
                + "{\"name\":\"Jump\",\"keycodes\":[32],\"width\":52,\"height\":52,\"opacity\":0.9}]}";
        String token = ControlProfileManager.encodeSharePayload("PvP Pro", sampleControlJson);
        assertTrue(token.startsWith(ControlProfileManager.SHARE_TOKEN_PREFIX));

        // Full ZXing QR encode -> ARGB pixel buffer -> ZXing QR decode -> DEFLATE unpack round-trip!
        BitMatrix matrix = ControlProfileManager.encodeQrMatrix(token, 256);
        int w = matrix.getWidth(), h = matrix.getHeight();
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                argb[y * w + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        String scannedToken = ControlProfileManager.decodeQrArgbPixels(argb, w, h);
        ControlProfileManager.DecodedShareProfile decoded =
                ControlProfileManager.decodeSharePayload(scannedToken);
        assertEquals("PvP Pro", decoded.profileName);
        assertEquals(sampleControlJson, decoded.jsonText);

        // Multi-step macro sequence script parser & JSON persistence round-trip
        List<QuickMacroDialog.MacroStep> steps = QuickMacroDialog.parseSequenceScript(
                "KEY:1; WAIT:80; ATTACK; WAIT:60; USE; CHAT:/spawn");
        assertEquals(6, steps.size());
        QuickMacroDialog.MacroSequence seq = new QuickMacroDialog.MacroSequence("Combo", steps);
        String json = QuickMacroDialog.serializeSequencesJson(Collections.singletonList(seq));
        List<QuickMacroDialog.MacroSequence> loaded = QuickMacroDialog.deserializeSequencesJson(json);
        assertEquals(1, loaded.size());
        assertEquals("Combo", loaded.get(0).label);
        assertEquals(6, loaded.get(0).steps.size());
    }

    @Test
    public void pillar5_skin3dMeshCapeHttpsResolverAndLevelDatNbtEditor() throws Exception {
        // 3D OpenGL Minecraft cuboid mesh: 36 vertices * 8 floats (pos3 + norm3 + uv2) = 288 floats
        float[] headMesh = SkinHeadRenderer.buildMinecraftBoxMesh(
                -4f, 0f, -4f, 8f, 8f, 8f, 0f, 0, 0, 8, 8, 8, 64, 64, false);
        assertEquals(SkinHeadRenderer.VERTICES_PER_BOX * SkinHeadRenderer.FLOATS_PER_VERTEX, headMesh.length);

        // Ely.by HTTP->HTTPS upgrade & OptiFine strict HTTPS URL builder
        assertEquals("https://s.optifine.net/capes/ZyroxPlayer.png",
                SkinHeadRenderer.buildOptiFineCapeUrl("ZyroxPlayer"));
        assertNull(SkinHeadRenderer.buildOptiFineCapeUrl("../bad/name"));
        String elyJson = "{\"SKIN\":{\"url\":\"http://skinsystem.ely.by/skins/a.png\"},"
                + "\"CAPE\":{\"url\":\"http://skinsystem.ely.by/capes/zyrox_cape.png\"}}";
        assertEquals("https://skinsystem.ely.by/capes/zyrox_cape.png",
                SkinHeadRenderer.parseElyByCapeUrl(elyJson));

        // Lossless GZIP big-endian level.dat NBT read + atomic edit + level.dat_old backup
        File worldDir = tmp.newFolder("SurvivalWorld");
        File levelDat = new File(worldDir, "level.dat");
        writeSyntheticLevelDat(levelDat, "My Starlight World", "1.21.4",
                LevelDatNbtEditor.GAMEMODE_SURVIVAL, false, false, 2, -8429104512345678901L);

        LevelDatNbtEditor.LevelDatSummary before = WorldManager.readWorldLevelDat(worldDir);
        assertEquals("My Starlight World", before.levelName);
        assertEquals("1.21.4", before.versionName);
        assertEquals(LevelDatNbtEditor.GAMEMODE_SURVIVAL, before.gameType);
        assertFalse(before.allowCommands);
        assertTrue(before.hasSeed);
        assertEquals(-8429104512345678901L, before.seed);

        LevelDatNbtEditor.LevelDatSummary after = LevelDatNbtEditor.applyEdits(levelDat,
                new LevelDatNbtEditor.EditSpec()
                        .setLevelName("Zyrox Creative Realm")
                        .setGameType(LevelDatNbtEditor.GAMEMODE_CREATIVE)
                        .setAllowCommands(true)
                        .setDifficulty(3)
                        .setSeed(123456789012345L));
        assertEquals("Zyrox Creative Realm", after.levelName);
        assertEquals(LevelDatNbtEditor.GAMEMODE_CREATIVE, after.gameType);
        assertTrue(after.allowCommands);
        assertEquals(3, after.difficulty);
        assertEquals(123456789012345L, after.seed);
        assertEquals("1.21.4", after.versionName); // untouched nested compound preserved losslessly!
        assertTrue("level.dat_old recovery backup must be created",
                new File(worldDir, "level.dat_old").isFile());
    }

    private static byte[] buildSyntheticAdpkgZip(String prefix, boolean omitLib) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(prefix + "meta.json"));
            String meta = "{\"schemaVersion\":1,\"name\":\"Turnip Test\",\"driverVersion\":\"25.1.0\","
                    + "\"libraryName\":\"libvulkan_freedreno.so\"}";
            zos.write(meta.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            if (!omitLib) {
                zos.putNextEntry(new ZipEntry(prefix + "libvulkan_freedreno.so"));
                zos.write(new byte[]{0x7F, 'E', 'L', 'F', 2, 1, 1, 0});
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static void writeSyntheticLevelDat(File out, String levelName, String mcVer,
                                               int gameType, boolean cheats, boolean hardcore,
                                               int difficulty, long seed) throws Exception {
        try (DataOutputStream d = new DataOutputStream(
                new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(out))))) {
            d.writeByte(LevelDatNbtEditor.TAG_COMPOUND);
            d.writeUTF(""); // root
            d.writeByte(LevelDatNbtEditor.TAG_COMPOUND);
            d.writeUTF("Data");

            d.writeByte(LevelDatNbtEditor.TAG_STRING); d.writeUTF("LevelName"); d.writeUTF(levelName);
            d.writeByte(LevelDatNbtEditor.TAG_INT); d.writeUTF("GameType"); d.writeInt(gameType);
            d.writeByte(LevelDatNbtEditor.TAG_BYTE); d.writeUTF("allowCommands"); d.writeByte(cheats ? 1 : 0);
            d.writeByte(LevelDatNbtEditor.TAG_BYTE); d.writeUTF("hardcore"); d.writeByte(hardcore ? 1 : 0);
            d.writeByte(LevelDatNbtEditor.TAG_BYTE); d.writeUTF("Difficulty"); d.writeByte(difficulty);
            d.writeByte(LevelDatNbtEditor.TAG_LONG); d.writeUTF("DayTime"); d.writeLong(6000L);
            // Include TAG_INT_ARRAY to verify lossless preservation of untouched complex NBT tags
            d.writeByte(LevelDatNbtEditor.TAG_INT_ARRAY); d.writeUTF("DragonFightGateways");
            d.writeInt(3); d.writeInt(1); d.writeInt(2); d.writeInt(3);

            // Nested Version compound
            d.writeByte(LevelDatNbtEditor.TAG_COMPOUND); d.writeUTF("Version");
            d.writeByte(LevelDatNbtEditor.TAG_STRING); d.writeUTF("Name"); d.writeUTF(mcVer);
            d.writeByte(LevelDatNbtEditor.TAG_END);

            // Nested 1.16+ WorldGenSettings compound
            d.writeByte(LevelDatNbtEditor.TAG_COMPOUND); d.writeUTF("WorldGenSettings");
            d.writeByte(LevelDatNbtEditor.TAG_LONG); d.writeUTF("seed"); d.writeLong(seed);
            d.writeByte(LevelDatNbtEditor.TAG_END);

            // Nested singleplayer Player compound
            d.writeByte(LevelDatNbtEditor.TAG_COMPOUND); d.writeUTF("Player");
            d.writeByte(LevelDatNbtEditor.TAG_INT); d.writeUTF("playerGameType"); d.writeInt(gameType);
            d.writeByte(LevelDatNbtEditor.TAG_END);

            d.writeByte(LevelDatNbtEditor.TAG_END); // end Data
            d.writeByte(LevelDatNbtEditor.TAG_END); // end root
        }
    }
}
