package git.artdeell.mojo.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.game.ContextInventoryHelperBar;
import git.artdeell.mojo.game.QuickMacroDialog;
import git.artdeell.mojo.input.ControlProfileManager;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceBackupService;
import git.artdeell.mojo.metadata.JavaRuntimeAdvisor;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.servers.ServerPinger;
import git.artdeell.mojo.ui.ZyroxLiquidGlassDrawable;
import git.artdeell.mojo.utils.GameOptionsUtils;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.utils.ZyroxHttpClient;

/**
 * Comprehensive JUnit 4 verification suite for Zyrox Launcher Phases 1–4:
 * Bug Squash &amp; Reliability, Beginner UX &amp; Crash Doctor, High-Contrast Liquid Glass &amp;
 * Adaptive GPU Blur, and the 6 High-Impact Community Features.
 */
public class ZyroxPhase1To4EcosystemTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    // ---------------------------------------------------------------------------------------------
    // Phase 1.1 — Java Runtime & GPU Calibration
    // ---------------------------------------------------------------------------------------------

    @Test
    public void javaRuntimeAdvisorMapsClassicAndModernMinecraftVersions() {
        assertEquals(8, JavaRuntimeAdvisor.resolveRequiredJava(null, "1.8.9"));
        assertEquals(8, JavaRuntimeAdvisor.resolveRequiredJava(null, "1.16.5-forge-36.2.39"));
        assertEquals(17, JavaRuntimeAdvisor.resolveRequiredJava(null, "1.20.1"));
        assertEquals(21, JavaRuntimeAdvisor.resolveRequiredJava(null, "fabric-loader-0.16.9-1.21.4"));
    }

    @Test
    public void graphicsCalibrationSummaryProducesBeginnerFriendlySentence() {
        String summary = GraphicsAdvisor.buildPlainEnglishCalibrationSummary(
                GpuUtils.GpuArchitecture.ADRENO_7XX,
                "vulkan_zink",
                8192,
                3072,
                100,
                true);
        assertTrue(summary.contains("Adreno 7xx"));
        assertTrue(summary.contains("8.0 GB RAM"));
        assertTrue(summary.contains("Turnip + Zink"));
        assertTrue(summary.contains("3072 MB"));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 1.3 & 1.4 — API Backoff & Android 14–16 Scoped Storage SAF Validation
    // ---------------------------------------------------------------------------------------------

    @Test
    public void httpClientParsesRetryAfterAndClassifiesStatusCodes() {
        assertEquals(5000L, ZyroxHttpClient.parseRetryAfterMs("5"));
        assertTrue(ZyroxHttpClient.parseRetryAfterMs("9999") <= 120_000L);
        assertTrue(ZyroxHttpClient.isRetryableStatus(429));
        assertTrue(ZyroxHttpClient.isRetryableStatus(503));
        assertFalse(ZyroxHttpClient.isRetryableStatus(404));
    }

    @Test
    public void scopedStoragePickerValidatesExtensionsCaseInsensitively() {
        assertEquals("*/*", OpenDocumentWithExtension.MIME_ALL_TYPES);
        assertTrue(OpenDocumentWithExtension.isSupportedLauncherFile("Fabulously.Optimized.MRPACK"));
        assertTrue(OpenDocumentWithExtension.isSupportedLauncherFile("sodium-fabric-0.6.5.jar"));
        assertTrue(OpenDocumentWithExtension.isSupportedLauncherFile("ComplementaryReimagined.zip"));
        assertTrue(OpenDocumentWithExtension.isSupportedLauncherFile("pvp_claw.json"));
        assertTrue(OpenDocumentWithExtension.isSupportedLauncherFile("my_pro_hud.zyrox"));
        assertFalse(OpenDocumentWithExtension.isSupportedLauncherFile("malware.apk"));
        assertFalse(OpenDocumentWithExtension.isSupportedLauncherFile("script.sh"));
        assertTrue(OpenDocumentWithExtension.hasAllowedExtension(
                "hud.ZYROX", "json", "zyrox"));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 1.5 — Token, UUID & PII Log Redactor
    // ---------------------------------------------------------------------------------------------

    @Test
    public void logRedactorScrubsTokensUuidsAndEmailsWhileKeepingStackTraces() {
        String raw = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c\n"
                + "refresh_token=M.R3_BAY.super_secret_microsoft_token_value_12345\n"
                + "uuid=550e8400-e29b-41d4-a716-446655440000 email=player@example.com\n"
                + "java.lang.OutOfMemoryError: Java heap space\n"
                + "\tat net.minecraft.client.Minecraft.run(Minecraft.java:812)";
        String clean = LogRedactor.redact(raw);
        assertFalse(clean.contains("SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"));
        assertFalse(clean.contains("super_secret_microsoft_token_value_12345"));
        assertFalse(clean.contains("550e8400-e29b-41d4-a716-446655440000"));
        assertFalse(clean.contains("player@example.com"));
        assertTrue(clean.contains(LogRedactor.REDACTED));
        assertTrue(clean.contains(LogRedactor.REDACTED_UUID));
        assertTrue(clean.contains(LogRedactor.REDACTED_EMAIL));
        assertTrue(clean.contains("java.lang.OutOfMemoryError: Java heap space"));
        assertTrue(clean.contains("net.minecraft.client.Minecraft.run(Minecraft.java:812)"));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 2.3 — Crash Doctor Missing Dependency Auto-Slug Resolver
    // ---------------------------------------------------------------------------------------------

    @Test
    public void crashDoctorExtractsMissingDependencyModrinthSlugs() {
        assertEquals("fabric-api", ZyroxCrashAnalyzer.extractMissingDependencySlug(
                "Mod 'sodium' requires any version of fabric-api, which is missing!"));
        assertEquals("cloth-config", ZyroxCrashAnalyzer.extractMissingDependencySlug(
                "Missing mandatory dependency: cloth_config >= 15.0.0"));
        assertEquals(25, ZyroxCrashAnalyzer.classFileMajorToJava(69));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 2.4 & 4.3 — Server Protocol Version Mapper & Local LAN Discovery Parser
    // ---------------------------------------------------------------------------------------------

    @Test
    public void serverPingerMapsProtocolsMatchesVersionsAndParsesLanBroadcasts() {
        assertEquals("1.8.9", ServerPinger.mapProtocolToMcVersion(47, "BungeeCord"));
        assertEquals("1.20.1", ServerPinger.mapProtocolToMcVersion(763, "Paper 1.20.1"));
        assertEquals("1.21.4", ServerPinger.mapProtocolToMcVersion(769, "Velocity"));

        assertTrue(ServerPinger.doesInstanceMatchVersion("fabric-loader-0.16.9-1.21.4", "1.21.4"));
        assertFalse(ServerPinger.doesInstanceMatchVersion("1.20.1", "1.21.4"));

        ServerPinger.LanWorldEntry lan = ServerPinger.parseLanAnnouncement(
                "[MOTD]Alex - Survival Base[/MOTD][AD]54321[/AD]", "192.168.1.42");
        assertNotNull(lan);
        assertEquals("Alex - Survival Base", lan.motd);
        assertEquals(54321, lan.port);
        assertEquals("192.168.1.42:54321", lan.address);

        assertNull(ServerPinger.parseLanAnnouncement("garbage packet", "192.168.1.42"));
        assertNull(ServerPinger.parseLanAnnouncement("[MOTD]Bad[/MOTD][AD]99999[/AD]", "192.168.1.42"));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 3 — High-Contrast 2-Layer Liquid Glass & Adaptive GPU Blur Tiering
    // ---------------------------------------------------------------------------------------------

    @Test
    public void liquidGlassEnforcesHighContrastScrimAndAdaptiveGpuTiering() {
        assertEquals(0x660B0F17, ZyroxLiquidGlassDrawable.HIGH_CONTRAST_SCRIM);
        assertEquals(0x4DFFFFFF, ZyroxLiquidGlassDrawable.SPECULAR_BORDER_TOP);
        assertEquals(0x0DFFFFFF, ZyroxLiquidGlassDrawable.SPECULAR_BORDER_BOTTOM);

        // Android 12+ (API 33), 8 GB RAM, Adreno 7xx -> Hardware RenderEffect enabled
        assertEquals(ZyroxLiquidGlassDrawable.GlassBlurTier.HARDWARE_RENDER_EFFECT,
                ZyroxLiquidGlassDrawable.resolveGlassBlurTier(
                        33, 8192, GpuUtils.GpuArchitecture.ADRENO_7XX, false));

        // Android 11 (API 30) -> Zero-overhead Frosted Gradient fallback
        assertEquals(ZyroxLiquidGlassDrawable.GlassBlurTier.FROSTED_GRADIENT_FALLBACK,
                ZyroxLiquidGlassDrawable.resolveGlassBlurTier(
                        30, 8192, GpuUtils.GpuArchitecture.ADRENO_7XX, false));

        // Low-RAM (4 GB) -> Frosted Gradient fallback
        assertEquals(ZyroxLiquidGlassDrawable.GlassBlurTier.FROSTED_GRADIENT_FALLBACK,
                ZyroxLiquidGlassDrawable.resolveGlassBlurTier(
                        34, 4096, GpuUtils.GpuArchitecture.ADRENO_7XX, false));

        // ARM Mali / PowerVR / Low-Effects toggle -> Frosted Gradient fallback
        assertEquals(ZyroxLiquidGlassDrawable.GlassBlurTier.FROSTED_GRADIENT_FALLBACK,
                ZyroxLiquidGlassDrawable.resolveGlassBlurTier(
                        34, 12288, GpuUtils.GpuArchitecture.ARM_MALI_VALHALL, false));
        assertEquals(ZyroxLiquidGlassDrawable.GlassBlurTier.FROSTED_GRADIENT_FALLBACK,
                ZyroxLiquidGlassDrawable.resolveGlassBlurTier(
                        34, 12288, GpuUtils.GpuArchitecture.ADRENO_8XX, true));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 4.1 — Zero-Lag FPS & PvP options.txt Atomic Tuner
    // ---------------------------------------------------------------------------------------------

    @Test
    public void gameOptionsUtilsAppliesZeroLagAndPreservesCustomKeybinds() throws IOException {
        File gameDir = tempFolder.newFolder("mc_game");
        File opts = new File(gameDir, "options.txt");
        writeUtf8(opts, "key_key.jump:key.keyboard.space\nrenderDistance:16\nenableVsync:true\n");

        assertTrue(GameOptionsUtils.applyZeroLagFpsOptions(gameDir, true));
        Map<String, String> map = GameOptionsUtils.readOptionsMap(opts);
        assertEquals("key.keyboard.space", map.get("key_key.jump"));
        assertEquals("8", map.get("renderDistance"));
        assertEquals("false", map.get("entityShadows"));
        assertEquals("1", map.get("particles"));
        assertEquals("false", map.get("enableVsync"));
        assertEquals("false", map.get("bobView"));
        assertEquals("false", map.get("autoJump"));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 4.2 — ZYROX:// Universal Setup Share Code Round-Trip & Tamper Protection
    // ---------------------------------------------------------------------------------------------

    @Test
    public void zyroxSetupCodeRoundTripsAndRejectsTampering() throws IOException {
        ControlProfileManager.ZyroxSetupBundle original =
                new ControlProfileManager.ZyroxSetupBundle(
                        "Pro Bedwars Claw",
                        "{\"version\":8,\"scaledAt\":100,\"mControlDataList\":[]}",
                        115, 95, 130,
                        true, 1.4f, false, true,
                        "vulkan_zink", "max_fps", 85, true);

        String code = ControlProfileManager.encodeZyroxSetupBundle(original);
        assertTrue(code.startsWith("ZYROX://v1."));
        assertTrue(ControlProfileManager.isValidZyroxSetupCode(code));

        ControlProfileManager.ZyroxSetupBundle decoded =
                ControlProfileManager.decodeZyroxSetupBundle(code);
        assertEquals("Pro Bedwars Claw", decoded.profileName);
        assertEquals(115, decoded.buttonScalePct);
        assertEquals(130, decoded.mouseSpeedPct);
        assertTrue(decoded.gyroEnabled);
        assertEquals(1.4f, decoded.gyroSensitivity, 0.01f);
        assertTrue(decoded.gyroInvertY);
        assertEquals("vulkan_zink", decoded.rendererTag);
        assertEquals("max_fps", decoded.performancePreset);
        assertEquals(85, decoded.resolutionScalePct);
        assertTrue(decoded.controlJson.contains("mControlDataList"));

        // Flip checksum hex nibble -> must fail SHA-256 verification
        char flipped = code.charAt(11) == 'a' ? 'b' : 'a';
        String tampered = code.substring(0, 11) + flipped + code.substring(12);
        boolean rejected = false;
        try {
            ControlProfileManager.decodeZyroxSetupBundle(tampered);
        } catch (IOException expected) {
            rejected = true;
        }
        assertTrue("Tampered ZYROX:// code must be rejected", rejected);
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 4.4 & 4.5 — Context-Aware Inventory Controls & 8-Slot Radial Command Wheel
    // ---------------------------------------------------------------------------------------------

    @Test
    public void contextAwareInventoryBarAndRadialWheelHitTesting() {
        assertTrue(ContextInventoryHelperBar.shouldShowInventoryBar(true, true, false, false));
        assertFalse(ContextInventoryHelperBar.shouldShowInventoryBar(true, true, true, false));
        assertFalse(ContextInventoryHelperBar.shouldShowInventoryBar(true, false, false, false));
        assertFalse(ContextInventoryHelperBar.shouldShowInventoryBar(true, true, false, true));
        assertFalse(ContextInventoryHelperBar.shouldShowInventoryBar(false, true, false, false));

        assertEquals(8, QuickMacroDialog.getDefaultRadialSlots().size());
        assertEquals(-1, QuickMacroDialog.resolveRadialSector(2f, 2f, 24f, 100f));   // center cancel
        assertEquals(0, QuickMacroDialog.resolveRadialSector(0f, -70f, 24f, 100f));  // North (/spawn)
        assertEquals(2, QuickMacroDialog.resolveRadialSector(70f, 0f, 24f, 100f));   // East (/tpa)
        assertEquals(4, QuickMacroDialog.resolveRadialSector(0f, 70f, 24f, 100f));   // South (/hub)
        assertEquals(6, QuickMacroDialog.resolveRadialSector(-70f, 0f, 24f, 100f));  // West (F3+B)
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 4.6 — Rolling 3-Snapshot Pre-Mod World/Config Safety Backup & Rollback
    // ---------------------------------------------------------------------------------------------

    @Test
    public void preModSnapshotsRetainLastThreeVerifySha256AndRollbackCleanly() throws Exception {
        File instRoot = tempFolder.newFolder("inst_alpha");
        File cfgFile = new File(new File(instRoot, "config"), "sodium-options.json");
        File worldFile = new File(new File(instRoot, "saves/MyWorld"), "level.dat");
        writeUtf8(cfgFile, "{\"quality\":\"fast\"}");
        writeUtf8(worldFile, "ORIGINAL_WORLD_STATE");

        Instance inst = new TestableInstance(instRoot);
        File snap1 = InstanceBackupService.createPreModSnapshot(inst, "mod-1");
        assertNotNull(snap1);

        // Mutate config & world across 3 more snapshots -> rolling limit must stay == 3
        writeUtf8(cfgFile, "{\"quality\":\"broken-2\"}");
        Thread.sleep(5L);
        InstanceBackupService.createPreModSnapshot(inst, "mod-2");
        Thread.sleep(5L);
        InstanceBackupService.createPreModSnapshot(inst, "mod-3");
        writeUtf8(cfgFile, "{\"quality\":\"good-checkpoint\"}");
        writeUtf8(worldFile, "SAFE_WORLD_CHECKPOINT");
        Thread.sleep(5L);
        File latest = InstanceBackupService.createPreModSnapshot(inst, "mod-4");
        assertNotNull(latest);

        List<InstanceBackupService.SnapshotInfo> list =
                InstanceBackupService.listPreModSnapshots(inst);
        assertEquals(3, list.size());

        // Simulate bad mod corrupting config & world, then run 1-tap rollback
        writeUtf8(cfgFile, "CORRUPTED!!!");
        writeUtf8(worldFile, "CORRUPTED_WORLD!!!");
        int restored = InstanceBackupService.rollbackPreModSnapshot(inst, latest);
        assertEquals(2, restored);
        assertEquals("{\"quality\":\"good-checkpoint\"}", readUtf8(cfgFile));
        assertEquals("SAFE_WORLD_CHECKPOINT", readUtf8(worldFile));
    }

    private static final class TestableInstance extends Instance {
        private final File mRoot;

        TestableInstance(File root) {
            this.mRoot = root;
            this.mInstanceRoot = root;
            this.name = "Test";
            this.versionId = "1.21.4";
        }

        @Override
        public File getGameDirectory() {
            return mRoot;
        }
    }

    private static void writeUtf8(File f, String s) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.exists()) {
            assertTrue(p.mkdirs());
        }
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readUtf8(File f) throws IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
