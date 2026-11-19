package git.artdeell.mojo;

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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import git.artdeell.mojo.content.InstanceContentManager;
import git.artdeell.mojo.content.ModMetadataParser;
import git.artdeell.mojo.content.ModrinthContentInstaller;
import git.artdeell.mojo.input.ControlData;
import git.artdeell.mojo.input.ControlProfileManager;
import git.artdeell.mojo.input.keyboard.TouchCharInput;
import git.artdeell.mojo.performance.GameOptimizer;
import git.artdeell.mojo.servers.ZyroxRoomBridge;
import git.artdeell.mojo.updater.AppUpdateChecker;

/**
 * End-to-end JUnit verification suite covering Phases 1–4 upgrades: GitHub Releases in-app
 * updater, manifest/FileProvider security, static pre-launch mod conflict simulator, global
 * SHA-1 shared mod pool, multi-gesture PvP combo controls, Voice-to-Chat sanitization, live
 * {@code ZYX-XXXX} room-code P2P bridge, thermal/battery governor, and {@code ZYROX://} bundles.
 */
public class ZyroxFullStackUpgradeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testGitHubUpdaterAbiSelectionChecksumAndChangelog() {
        String sha = "c149c84372187fe46cca296276e8e8f27c082f4c51c73a47dcc577d4e64a8f1a";
        String json = "{\n"
                + "  \"tag_name\": \"v2.5.0\",\n"
                + "  \"name\": \"Zyrox Launcher v2.5.0 — Liquid Glass\",\n"
                + "  \"draft\": false,\n"
                + "  \"html_url\": \"https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/tag/v2.5.0\",\n"
                + "  \"body\": \"## Highlights\\n- **Auto-Updater**: Direct APK install\\n- Fixed [SAF picker](https://example.com)\\n## SHA-256 Verification\\n```\\nabc\\n```\",\n"
                + "  \"assets\": [\n"
                + "    {\"name\": \"ZyroxLauncher-armeabi-v7a.apk\", \"size\": 42000000, \"browser_download_url\": \"https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/download/v2.5.0/ZyroxLauncher-armeabi-v7a.apk\"},\n"
                + "    {\"name\": \"ZyroxLauncher-arm64-v8a.apk\", \"size\": 65000000, \"digest\": \"sha256:" + sha + "\", \"browser_download_url\": \"https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/download/v2.5.0/ZyroxLauncher-arm64-v8a.apk\"},\n"
                + "    {\"name\": \"Zyrox.Launcher.apk\", \"size\": 68000000, \"browser_download_url\": \"https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/download/v2.5.0/Zyrox.Launcher.apk\"}\n"
                + "  ]\n"
                + "}";

        AppUpdateChecker.ReleaseInfo arm64 = AppUpdateChecker.parseReleaseJson(
                json, "2.4.0", new String[]{"arm64-v8a", "armeabi-v7a"});
        assertTrue(arm64.updateAvailable);
        assertEquals("arm64-v8a", arm64.matchedAbi);
        assertEquals("ZyroxLauncher-arm64-v8a.apk", arm64.apkAssetName);
        assertEquals(sha, arm64.expectedSha256);

        AppUpdateChecker.ReleaseInfo x86Fallback = AppUpdateChecker.parseReleaseJson(
                json, "2.4.0", new String[]{"x86_64"});
        assertEquals("universal", x86Fallback.matchedAbi);
        assertEquals("Zyrox.Launcher.apk", x86Fallback.apkAssetName);

        assertTrue(AppUpdateChecker.compareSemVer("v2.5.0", "2.5.0-beta.1") > 0);
        assertFalse(AppUpdateChecker.isTrustedGitHubDownloadUrl("http://github.com/evil.apk"));
        assertFalse(AppUpdateChecker.isTrustedGitHubDownloadUrl("https://evil.example.com/a.apk"));
        assertTrue(AppUpdateChecker.isTrustedGitHubDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/a.apk"));

        String sumFile = "SHA-256 Signing Certificate Fingerprint: "
                + "66b2f822eceeea52bf0bcffde0d5f9be362de30495232aae7ef15e9e8ce6c5af\n"
                + sha + "  Zyrox.Launcher.apk\n";
        assertEquals(sha, AppUpdateChecker.extractChecksumForAsset(sumFile, "Zyrox.Launcher.apk"));

        String formatted = AppUpdateChecker.formatChangelogPlainText(arm64.body);
        assertTrue(formatted.contains("Highlights"));
        assertTrue(formatted.contains("• Auto-Updater: Direct APK install"));
        assertTrue(formatted.contains("• Fixed SAF picker"));
        assertFalse(formatted.contains("SHA-256 Verification"));
    }

    @Test
    public void testManifestAndFileProviderConfiguration() throws IOException {
        File manifestFile = new File("src/main/AndroidManifest.xml");
        if (!manifestFile.isFile()) {
            manifestFile = new File("app_mojo/src/main/AndroidManifest.xml");
        }
        assertTrue(manifestFile.isFile());
        String manifest = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        assertTrue(manifest.contains("androidx.core.content.FileProvider"));
        assertTrue(manifest.contains("${applicationId}.fileprovider"));
        assertTrue(manifest.contains("@xml/file_paths"));
        assertTrue(manifest.contains("android.speech.RecognitionService"));

        File pathsFile = new File(manifestFile.getParentFile(), "res/xml/file_paths.xml");
        assertTrue(pathsFile.isFile());
        String paths = new String(Files.readAllBytes(pathsFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(paths.contains("<external-cache-path"));
        assertTrue(paths.contains("path=\"updates/\""));
    }

    @Test
    public void testPreLaunchModConflictSimulatorAndSemVerEvaluator() throws IOException {
        assertTrue(ModMetadataParser.isMinecraftVersionCompatible("1.21.4", ">=1.21 <1.22"));
        assertFalse(ModMetadataParser.isMinecraftVersionCompatible("1.20.1", ">=1.21 <1.22"));
        assertTrue(ModMetadataParser.isMinecraftVersionCompatible("1.21.4", "~1.21.2"));
        assertFalse(ModMetadataParser.isMinecraftVersionCompatible("1.22.0", "~1.21.2"));
        assertTrue(ModMetadataParser.isMinecraftVersionCompatible("1.21.8", "1.21.x"));
        assertTrue(ModMetadataParser.isMinecraftVersionCompatible("26.1.2", "26.1.x"));
        assertTrue(ModMetadataParser.isMinecraftVersionCompatible("1.20.4", "[1.20.1,1.21)"));
        assertFalse(ModMetadataParser.isMinecraftVersionCompatible("1.21.0", "[1.20.1,1.21)"));
        assertEquals("fabric-api", ModMetadataParser.resolveModrinthDependencySlug("fabric"));

        File gameDir = tmp.newFolder("instance_fabric");
        File modsDir = new File(gameDir, "mods");
        assertTrue(modsDir.mkdirs());

        // 1. Mod requiring missing 'fabric-api'
        writeFabricJar(new File(modsDir, "iris-1.8.jar"),
                "iris", "Iris Shaders", "1.8.0", "1.21.x", "\"fabric-api\": \"*\"");
        // 2. Duplicate Lithium (0.14.0 vs 0.15.1 -> older 0.14.0 should be selected to disable)
        writeFabricJar(new File(modsDir, "lithium-0.14.0.jar"),
                "lithium", "Lithium", "0.14.0", ">=1.21", "");
        writeFabricJar(new File(modsDir, "lithium-0.15.1.jar"),
                "lithium", "Lithium", "0.15.1", ">=1.21", "");
        // 3. Incompatible MC 1.16.5 mod
        writeFabricJar(new File(modsDir, "legacy-1.16.jar"),
                "legacymod", "Legacy Mod", "1.0.0", "1.16.x", "");
        // 4. Forge mod dropped into Fabric instance
        writeForgeJar(new File(modsDir, "jei-forge.jar"), "jei", "Just Enough Items", "15.0");
        // 5. Corrupt empty JAR
        assertTrue(new File(modsDir, "broken.jar").createNewFile());

        ModMetadataParser.SimulationReport report = ModMetadataParser.simulatePreLaunch(
                gameDir, "fabric-loader-0.16.10-1.21.4");
        assertEquals(6, report.scannedModCount);
        assertTrue(report.hasBlockingIssues());
        assertTrue(report.canAutoFix());
        assertTrue(report.missingDependencies.contains("fabric-api"));
        assertEquals(1, report.duplicateFilesToDisable.size());
        assertEquals("lithium-0.14.0.jar", report.duplicateFilesToDisable.get(0).getName());
        assertEquals(1, report.mcVersionMismatchFiles.size());
        assertEquals(1, report.loaderMismatchFiles.size());
        assertEquals(1, report.corruptFiles.size());
    }

    @Test
    public void testGlobalSha1SharedModPoolDeduplication() throws IOException {
        File poolRoot = tmp.newFolder(".zyrox_pool");
        File instA = tmp.newFolder("inst_a");
        File instB = tmp.newFolder("inst_b");
        File modsA = new File(instA, "mods");
        File modsB = new File(instB, "mods");
        assertTrue(modsA.mkdirs() && modsB.mkdirs());

        byte[] payload = "ZyroxSharedSodiumBinaryPayload-v0.6.13".getBytes(StandardCharsets.UTF_8);
        File dlTemp = tmp.newFile("sodium.part");
        Files.write(dlTemp.toPath(), payload);
        String sha1 = ModrinthContentInstaller.computeSha1Hex(dlTemp);

        File targetA = new File(modsA, "sodium-fabric.jar");
        InstanceContentManager.ingestIntoPoolAndLink(poolRoot, dlTemp, targetA, sha1);
        assertTrue(targetA.isFile());
        assertNotNull(InstanceContentManager.resolvePoolBlob(poolRoot, sha1));
        assertTrue(InstanceContentManager.resolvePoolBlob(poolRoot, sha1).isFile());

        // Path traversal / malformed hash rejected
        assertNull(InstanceContentManager.resolvePoolBlob(poolRoot, "../../etc/passwd"));

        // Instant zero-network pool materialization into Instance B
        File targetB = new File(modsB, "sodium-fabric.jar");
        assertTrue(InstanceContentManager.materializeFromPool(poolRoot, sha1, targetB));
        assertEquals(payload.length, targetB.length());

        // Cross-instance deduplication telemetry
        InstanceContentManager.PoolStats stats = InstanceContentManager.deduplicateModDirectories(
                poolRoot, Arrays.asList(instA, instB));
        assertEquals(1, stats.uniqueBlobs);
        assertEquals(1, stats.deduplicatedFiles);
        assertEquals(payload.length, stats.savedBytes);
    }

    @Test
    public void testMultiGestureComboAndVoiceChatSanitizer() {
        ControlData btn = new ControlData("Attack");
        assertFalse(btn.hasGestureCombos());
        assertEquals(9, btn.getEffectivePanicMacroSlot());

        btn.longPressKeycode = ControlData.SPECIALBTN_MOUSESEC;
        btn.swipeUpKeycode = ControlData.SPECIALBTN_PANIC_PEARL;
        btn.swipeDownKeycode = ControlData.SPECIALBTN_PANIC_TOTEM;
        btn.panicMacroSlot = 8;
        assertTrue(btn.hasGestureCombos());

        ControlData cloned = new ControlData(btn);
        assertEquals(ControlData.SPECIALBTN_MOUSESEC, cloned.longPressKeycode);
        assertEquals(ControlData.SPECIALBTN_PANIC_PEARL, cloned.swipeUpKeycode);
        assertEquals(ControlData.SPECIALBTN_PANIC_TOTEM, cloned.swipeDownKeycode);
        assertEquals(8, cloned.getEffectivePanicMacroSlot());

        // Verify contiguous negative indexing required by EditControlSideDialog spinner math
        List<String> specials = ControlData.buildSpecialButtonArray();
        assertEquals(14, specials.size());
        assertEquals("SPECIAL_MacroWheel", specials.get(0));
        assertEquals("SPECIAL_Keyboard", specials.get(13));

        assertEquals("rush mid behind blue bed",
                TouchCharInput.sanitizeVoiceChatText("  rush\nmid\t§cbehind   blue bed\r\n"));
    }

    @Test
    public void testZyroxRoomBridgeEncodingAndLiveP2PTunnel() throws Exception {
        String shortCode = ZyroxRoomBridge.generateShortRoomCode();
        assertTrue(shortCode.startsWith("ZYX-"));
        assertEquals(8, shortCode.length());

        String direct = ZyroxRoomBridge.encodeDirectRoomCode(shortCode, "192.168.43.1", 51234);
        ZyroxRoomBridge.ParsedRoomCode parsed = ZyroxRoomBridge.parseRoomCode(direct);
        assertEquals(shortCode, parsed.shortCode);
        assertEquals("192.168.43.1", parsed.directHost);
        assertEquals(51234, parsed.directPort);

        // Simulated local Minecraft "Open to LAN" server echoing handshake frames
        try (ServerSocket fakeMcLan = new ServerSocket()) {
            fakeMcLan.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            Thread mcThread = new Thread(() -> {
                try (Socket client = fakeMcLan.accept()) {
                    InputStream in = client.getInputStream();
                    OutputStream out = client.getOutputStream();
                    byte[] buf = new byte[64];
                    int n = in.read(buf);
                    if (n > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                }
            });
            mcThread.setDaemon(true);
            mcThread.start();

            try (ZyroxRoomBridge.HostSession host =
                         ZyroxRoomBridge.startHosting(fakeMcLan.getLocalPort(), "Test Survival")) {
                String loopbackToken = ZyroxRoomBridge.encodeDirectRoomCode(
                        host.shortCode, "127.0.0.1", host.bridgeTcpPort);
                try (ZyroxRoomBridge.ClientTunnel tunnel =
                             ZyroxRoomBridge.connectToRoom(loopbackToken);
                     Socket gameClient = new Socket("127.0.0.1", tunnel.localPort)) {
                    gameClient.setSoTimeout(3000);
                    byte[] pingPayload = "MC_SLP_PING_FRAME".getBytes(StandardCharsets.UTF_8);
                    gameClient.getOutputStream().write(pingPayload);
                    gameClient.getOutputStream().flush();

                    byte[] echoed = new byte[pingPayload.length];
                    int got = gameClient.getInputStream().read(echoed);
                    assertEquals(pingPayload.length, got);
                    assertEquals("MC_SLP_PING_FRAME", new String(echoed, StandardCharsets.UTF_8));
                }
            } finally {
                ZyroxRoomBridge.stopAll();
            }
        }
    }

    @Test
    public void testThermalGovernorAndZyroxSetupModListBundle() throws IOException {
        assertEquals(1.00f, GameOptimizer.computeThermalScaleMultiplier(0, 36.5f, 80, false, false), 0.001f);
        assertEquals(0.85f, GameOptimizer.computeThermalScaleMultiplier(2, 40.0f, 80, false, false), 0.001f);
        assertEquals(0.80f, GameOptimizer.computeThermalScaleMultiplier(3, 45.2f, 80, true, false), 0.001f);
        assertEquals(0.70f, GameOptimizer.computeThermalScaleMultiplier(4, 48.0f, 80, true, false), 0.001f);
        assertEquals(0.85f, GameOptimizer.computeThermalScaleMultiplier(0, 35.0f, 12, false, false), 0.001f);
        assertEquals(1.00f, GameOptimizer.computeThermalScaleMultiplier(0, 35.0f, 12, true, false), 0.001f);
        assertNull(GameOptimizer.formatThermalGovernorBadge(1.0f, 0, 36f));
        assertEquals("THM 80%", GameOptimizer.formatThermalGovernorBadge(0.80f, 3, 45.5f));
        assertEquals("ECO 85%", GameOptimizer.formatThermalGovernorBadge(0.85f, 0, 34.0f));

        ControlProfileManager.ZyroxSetupBundle bundle = new ControlProfileManager.ZyroxSetupBundle(
                "PvP Pro", "{\"version\":8,\"mControlDataList\":[]}",
                110, 100, 125, true, 1.4f, false, false,
                "vulkan_zink", "max_fps", 85, true,
                Arrays.asList("fabric-api", "sodium", "lithium", "immediatelyfast"));
        String code = ControlProfileManager.encodeZyroxSetupBundle(bundle);
        assertTrue(code.startsWith("ZYROX://v1."));
        ControlProfileManager.ZyroxSetupBundle decoded =
                ControlProfileManager.decodeZyroxSetupBundle(code);
        assertEquals("PvP Pro", decoded.profileName);
        assertEquals(85, decoded.resolutionScalePct);
        assertEquals(Arrays.asList("fabric-api", "sodium", "lithium", "immediatelyfast"),
                decoded.modIds);
    }

    private static void writeFabricJar(File target, String id, String name, String version,
                                       String mcDep, String extraDep) throws IOException {
        String deps = "\"minecraft\": \"" + mcDep + "\""
                + (extraDep.isEmpty() ? "" : ", " + extraDep);
        String fmj = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"version\":\"" + version + "\",\"depends\":{" + deps + "}}";
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(target))) {
            zos.putNextEntry(new ZipEntry("fabric.mod.json"));
            zos.write(fmj.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static void writeForgeJar(File target, String id, String name, String version)
            throws IOException {
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n"
                + "[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version
                + "\"\ndisplayName=\"" + name + "\"\n";
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(target))) {
            zos.putNextEntry(new ZipEntry("META-INF/mods.toml"));
            zos.write(toml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
