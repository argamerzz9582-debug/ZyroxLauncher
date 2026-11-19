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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import git.artdeell.mojo.content.ModrinthContentInstaller;
import git.artdeell.mojo.diagnostics.WaypointManager;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.metadata.CurseForgeProvider;
import git.artdeell.mojo.metadata.ModrinthProvider;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.servers.ServerListManager;
import git.artdeell.mojo.servers.ZyroxRoomBridge;
import git.artdeell.mojo.updater.AppUpdateChecker;
import git.artdeell.mojo.utils.GameOptionsUtils;
import git.artdeell.mojo.utils.ZyroxHttpClient;

/**
 * Comprehensive JUnit regression suite covering Zyrox Launcher v2.6.0:
 * Task 1 (1-Click FPS Boost, clean MC/loader parsing, URL encoding, options.txt tuning),
 * Task 2 (Modrinth/CurseForge strict version + loader compatibility &amp; Recommended version),
 * Task 3 (Updater rate-limit resilience, ZyroxRoomBridge CGNAT + leak-free socket teardown),
 * Task 4 (Waypoints &amp; Death Pill, Per-Server Control Profile Auto-Switch, 5s Auto-Reconnect).
 */
public class ZyroxV260RegressionTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // =========================================================================
    // TASK 1: Clean MC Version & Mod Loader Extraction + FPS Boost Engine
    // =========================================================================

    @Test
    public void extractCleanMinecraftVersion_neverConfusesModLoaderNumbersWithMcVersion() {
        assertEquals("1.21.4",
                Instance.extractCleanMinecraftVersion("fabric-loader-0.16.10-1.21.4"));
        assertEquals("1.20.1",
                Instance.extractCleanMinecraftVersion("fabric-loader-0.15.11-1.20.1"));
        assertEquals("1.21",
                Instance.extractCleanMinecraftVersion("fabric-loader-0.16.0-1.21"));
        assertEquals("26.1.2",
                Instance.extractCleanMinecraftVersion("fabric-loader-0.16.14-26.1.2"));
        assertEquals("1.21.1",
                Instance.extractCleanMinecraftVersion("quilt-loader-0.27.1-1.21.1"));
        assertEquals("1.20.1",
                Instance.extractCleanMinecraftVersion("1.20.1-forge-47.3.0"));
        assertEquals("1.12.2",
                Instance.extractCleanMinecraftVersion("1.12.2-forge-14.23.5.2860"));
        assertEquals("1.21.1",
                Instance.extractCleanMinecraftVersion("1.21.1-neoforge-21.1.72"));
        assertEquals("1.21.4",
                Instance.extractCleanMinecraftVersion("neoforge-21.4.88"));
        assertEquals("1.21",
                Instance.extractCleanMinecraftVersion("neoforge-21.0.167"));
        assertEquals("1.21.4",
                Instance.extractCleanMinecraftVersion("1.21.4"));
        assertEquals("24w14a",
                Instance.extractCleanMinecraftVersion("24w14a"));
        assertNull(Instance.extractCleanMinecraftVersion(Instance.VERSION_LATEST_RELEASE));
        assertNull(Instance.extractCleanMinecraftVersion(Instance.VERSION_LATEST_FABRIC));
    }

    @Test
    public void detectModLoader_distinguishesFabricQuiltNeoForgeForgeAndVanilla() {
        assertEquals(Instance.LOADER_FABRIC,
                Instance.detectModLoader("fabric-loader-0.16.10-1.21.4"));
        assertEquals(Instance.LOADER_FABRIC,
                Instance.detectModLoader(Instance.VERSION_LATEST_FABRIC));
        assertEquals(Instance.LOADER_QUILT,
                Instance.detectModLoader("quilt-loader-0.27.1-1.21.1"));
        assertEquals(Instance.LOADER_NEOFORGE,
                Instance.detectModLoader("neoforge-21.4.88"));
        assertEquals(Instance.LOADER_NEOFORGE,
                Instance.detectModLoader("1.21.1-neoforge-21.1.72"));
        assertEquals(Instance.LOADER_FORGE,
                Instance.detectModLoader("1.20.1-forge-47.3.0"));
        assertEquals(Instance.LOADER_VANILLA,
                Instance.detectModLoader("1.21.4"));
        assertNull(ModrinthContentInstaller.extractModLoader("1.21.4"));
    }

    @Test
    public void supportsFabricLoader_allowsModernAndYearNumberedReleasesOnly() {
        assertTrue(Instance.supportsFabricLoader("1.14"));
        assertTrue(Instance.supportsFabricLoader("1.20.1"));
        assertTrue(Instance.supportsFabricLoader("1.21.4"));
        assertTrue(Instance.supportsFabricLoader("26.1.2"));
        assertFalse(Instance.supportsFabricLoader("1.13.2"));
        assertFalse(Instance.supportsFabricLoader("1.12.2"));
        assertFalse(Instance.supportsFabricLoader("1.8.9"));
    }

    @Test
    public void buildModrinthProjectVersionsUrl_properlyEncodesLoaderAndGameVersionArrays() {
        String url = ZyroxHttpClient.buildModrinthProjectVersionsUrl(
                "https://api.modrinth.com/v2",
                "sodium",
                "1.21.4",
                "fabric");
        assertTrue(url.startsWith("https://api.modrinth.com/v2/project/sodium/version?"));
        assertTrue(url.contains("game_versions=%5B%221.21.4%22%5D"));
        assertTrue(url.contains("loaders=%5B%22fabric%22%5D"));

        String quiltUrl = ZyroxHttpClient.buildModrinthProjectVersionsUrl(
                "https://api.modrinth.com/v2/",
                "lithium",
                "1.21.1",
                "quilt", "fabric");
        assertTrue(quiltUrl.contains("loaders=%5B%22quilt%22%2C%22fabric%22%5D"));
        assertTrue(quiltUrl.contains("game_versions=%5B%221.21.1%22%5D"));
    }

    @Test
    public void fpsBoostCatalogAndDetailedReport_coverAllLoadersWithInstalledVsSkippedBreakdown() {
        List<ModrinthContentInstaller.FpsModSlot> fabric =
                ModrinthContentInstaller.getFpsBoostCatalog("fabric", true);
        assertTrue(fabric.size() >= 6);
        assertEquals("Sodium", fabric.get(0).displayName);

        List<ModrinthContentInstaller.FpsModSlot> neoforge =
                ModrinthContentInstaller.getFpsBoostCatalog("neoforge", true);
        assertTrue(neoforge.size() >= 4);

        List<ModrinthContentInstaller.FpsModSlot> forge =
                ModrinthContentInstaller.getFpsBoostCatalog("forge", false);
        assertTrue(forge.size() >= 3);

        ModrinthContentInstaller.InstallSummary summary =
                new ModrinthContentInstaller.InstallSummary(
                        3, 1, 1,
                        Arrays.asList("sodium.jar", "lithium.jar", "ferritecore.jar"),
                        Arrays.asList("Sodium", "Lithium", "FerriteCore"),
                        Collections.emptyList(),
                        Collections.singletonList("Fabric API"),
                        Collections.singletonList("Entity Culling (no 26.1.2 build yet)"),
                        "26.1.2",
                        "fabric",
                        "fabric-loader-0.16.14-26.1.2",
                        true,
                        "Installed 3 FPS mods");
        String report = summary.buildDetailedReport();
        assertTrue(report.contains("Auto-installed Fabric Loader"));
        assertTrue(report.contains("Sodium"));
        assertTrue(report.contains("Fabric API"));
        assertTrue(report.contains("Entity Culling"));
    }

    @Test
    public void applyFpsBoostOptions_writesSafeOptionsAndPreservesCustomSettings() throws Exception {
        File gameDir = tmp.newFolder("mc_fps_opts");
        Tools.write(new File(gameDir, "options.txt").getAbsolutePath(),
                "fov:1.0\nrenderDistance:16\nsimulationDistance:12\nentityShadows:true\n"
                        + "particles:0\nenableVsync:true\nkey_key.jump:key.keyboard.space\n");

        assertTrue(GameOptionsUtils.applyFpsBoostOptions(gameDir));

        String updated = Tools.read(new File(gameDir, "options.txt").getAbsolutePath());
        assertTrue(updated.contains("renderDistance:8"));
        assertTrue(updated.contains("simulationDistance:6"));
        assertTrue(updated.contains("entityShadows:false"));
        assertTrue(updated.contains("particles:1"));
        assertTrue(updated.contains("enableVsync:false"));
        assertTrue(updated.contains("key_key.jump:key.keyboard.space"));
        assertTrue(updated.contains("fov:1.0"));
    }

    // =========================================================================
    // TASK 2: Strict Modrinth / CurseForge Compatibility & Recommended Ranking
    // =========================================================================

    @Test
    public void modrinthSearchFacets_enforceStrictVersionAndLoaderAndRelaxCleanly() {
        SearchFilters f = new SearchFilters();
        f.isModpack = false;
        f.assetType = SearchFilters.ASSET_MOD;
        f.mcVersion = "fabric-loader-0.16.10-1.21.4";
        f.loader = "fabric";
        f.category = "optimization";
        f.strictCompatibility = true;

        String strictFacets = ModrinthProvider.buildSearchFacets(f);
        assertTrue(strictFacets.contains("[\"project_type:mod\"]"));
        assertTrue(strictFacets.contains("[\"versions:1.21.4\"]"));
        assertTrue(strictFacets.contains("[\"categories:fabric\"]"));
        assertTrue(strictFacets.contains("[\"categories:optimization\"]"));
        assertFalse(strictFacets.contains("0.16.10"));

        // Quilt accepts native Quilt plus Quilt-compatible Fabric builds
        f.loader = "quilt";
        String quiltFacets = ModrinthProvider.buildSearchFacets(f);
        assertTrue(quiltFacets.contains("categories:quilt"));
        assertTrue(quiltFacets.contains("categories:fabric"));

        // Resource packs lock clean MC version without attaching a modloader facet
        f.assetType = SearchFilters.ASSET_RESOURCEPACK;
        String rpFacets = ModrinthProvider.buildSearchFacets(f);
        assertTrue(rpFacets.contains("[\"project_type:resourcepack\"]"));
        assertTrue(rpFacets.contains("[\"versions:1.21.4\"]"));
        assertFalse(rpFacets.contains("categories:quilt"));

        // Disabling Strict Compatibility removes version & loader locks while keeping type/category
        f.assetType = SearchFilters.ASSET_MOD;
        f.strictCompatibility = false;
        String looseFacets = ModrinthProvider.buildSearchFacets(f);
        assertTrue(looseFacets.contains("[\"project_type:mod\"]"));
        assertTrue(looseFacets.contains("[\"categories:optimization\"]"));
        assertFalse(looseFacets.contains("versions:"));
        assertFalse(looseFacets.contains("categories:quilt"));
    }

    @Test
    public void curseForgeLoaderMapping_matchesOfficialCurseForgeEnumIds() {
        assertEquals(1, CurseForgeProvider.toModLoaderType("forge"));
        assertEquals(4, CurseForgeProvider.toModLoaderType("fabric"));
        assertEquals(5, CurseForgeProvider.toModLoaderType("quilt"));
        assertEquals(6, CurseForgeProvider.toModLoaderType("neoforge"));
        assertEquals(0, CurseForgeProvider.toModLoaderType("vanilla"));
        assertEquals(0, CurseForgeProvider.toModLoaderType(null));
    }

    @Test
    public void computeRecommendedIndex_prefersLatestStableReleaseForPreferredLoader() {
        String[] types = new String[]{"alpha", "beta", "release", "release"};
        String[] loaders = new String[]{"fabric", "fabric", "neoforge", "fabric"};

        assertEquals(3, ModDetail.computeRecommendedIndex(types, loaders, "fabric"));
        assertEquals(2, ModDetail.computeRecommendedIndex(types, loaders, "neoforge"));
    }

    // =========================================================================
    // TASK 3: Updater Rate-Limit Resilience & ZyroxRoomBridge Socket Safety
    // =========================================================================

    @Test
    public void updaterRateLimitAndSemverComparison_workDeterministically() {
        assertTrue(AppUpdateChecker.compareSemVer("v2.6.0", "2.5.0") > 0);
        assertEquals(0, AppUpdateChecker.compareSemVer("v2.6.0", "2.6.0"));
        assertTrue(AppUpdateChecker.compareSemVer("v2.5.0", "v2.6.0") < 0);

        AppUpdateChecker.GitHubRateLimitException ex =
                new AppUpdateChecker.GitHubRateLimitException(
                        403, AppUpdateChecker.formatRateLimitMessage(403, "0", null));
        assertEquals(403, ex.statusCode);
        assertTrue(AppUpdateChecker.classifyUpdateError(ex).contains("rate limit"));
        assertTrue(AppUpdateChecker.RELEASES_PAGE.startsWith("https://github.com/"));
    }

    @Test
    public void zyroxRoomBridge_detectsCgnatAndClosesAllSocketsCleanly() throws Exception {
        assertTrue(ZyroxRoomBridge.isCarrierGradeNatIpv4("100.64.0.1"));
        assertTrue(ZyroxRoomBridge.isCarrierGradeNatIpv4("100.100.42.19"));
        assertTrue(ZyroxRoomBridge.isCarrierGradeNatIpv4("100.127.255.254"));
        assertFalse(ZyroxRoomBridge.isCarrierGradeNatIpv4("100.63.255.255"));
        assertFalse(ZyroxRoomBridge.isCarrierGradeNatIpv4("100.128.0.1"));
        assertFalse(ZyroxRoomBridge.isCarrierGradeNatIpv4("192.168.1.25"));
        assertFalse(ZyroxRoomBridge.isCarrierGradeNatIpv4("10.0.0.8"));
        assertTrue(ZyroxRoomBridge.isCellularInterfaceName("rmnet_data0"));
        assertFalse(ZyroxRoomBridge.isCellularInterfaceName("wlan0"));

        // Spin up a local echo "Minecraft LAN server" on 127.0.0.1 and verify full bridge teardown
        try (ServerSocket fakeMc = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            Thread echo = new Thread(() -> {
                try (Socket s = fakeMc.accept();
                     InputStream in = s.getInputStream();
                     OutputStream out = s.getOutputStream()) {
                    byte[] buf = new byte[32];
                    int n = in.read(buf);
                    if (n > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            });
            echo.setDaemon(true);
            echo.start();

            ZyroxRoomBridge.HostSession host =
                    ZyroxRoomBridge.startHosting(fakeMc.getLocalPort(), "Test World");
            String directToHost = ZyroxRoomBridge.encodeDirectRoomCode(
                    host.shortCode, "127.0.0.1", host.bridgeTcpPort);

            ZyroxRoomBridge.ClientTunnel client = ZyroxRoomBridge.connectToRoom(directToHost);
            assertEquals("127.0.0.1:" + client.localPort, client.getLoopbackAddress());

            try (Socket gameClient = new Socket("127.0.0.1", client.localPort)) {
                gameClient.setSoTimeout(3000);
                byte[] ping = "ZYROX-PING".getBytes(StandardCharsets.UTF_8);
                gameClient.getOutputStream().write(ping);
                gameClient.getOutputStream().flush();
                byte[] reply = new byte[ping.length];
                int n = gameClient.getInputStream().read(reply);
                assertEquals("ZYROX-PING", new String(reply, 0, n, StandardCharsets.UTF_8));
            }
            ZyroxRoomBridge.stopAll();
            assertFalse(host.isRunning());
            assertFalse(client.isRunning());
            assertNull(ZyroxRoomBridge.getActiveHost());
            assertNull(ZyroxRoomBridge.getActiveClient());
        }
    }

    // =========================================================================
    // TASK 4: Waypoints/Death Pill, Per-Server Controls & 5s Auto-Reconnect
    // =========================================================================

    @Test
    public void waypointManager_parsesF3ClipboardDeathLogsAndPersistsAtomically() throws Exception {
        WaypointManager.CoordinateSample ow = WaypointManager.parseF3ClipboardCommand(
                "/execute in minecraft:overworld run tp @s -248.42 69.00 1042.85 179.40 12.10");
        assertNotNull(ow);
        assertEquals(-249, ow.x);
        assertEquals(69, ow.y);
        assertEquals(1042, ow.z);
        assertEquals(WaypointManager.DIM_OVERWORLD, ow.dimension);

        WaypointManager.CoordinateSample nether = WaypointManager.parseF3ClipboardCommand(
                "/execute in minecraft:the_nether run tp @s 88.1 128.0 -44.9 0 0");
        assertNotNull(nether);
        assertEquals(WaypointManager.DIM_NETHER, nether.dimension);

        assertTrue(WaypointManager.isDeathLogLine(
                "[12:04:11] [Render thread/INFO]: [CHAT] Alex was slain by Zombie", "Alex"));
        assertFalse(WaypointManager.isDeathLogLine(
                "[12:04:11] [Render thread/INFO]: [CHAT] Bob was slain by Zombie", "Alex"));

        File gameDir = tmp.newFolder("mc_waypoints");
        WaypointManager.Waypoint base = WaypointManager.addAndPin(
                gameDir, "Main Base", 120, 72, -340, WaypointManager.DIM_OVERWORLD, false);
        WaypointManager.Waypoint death = WaypointManager.addAndPin(
                gameDir, "Death 12:05", -40, 35, 910, WaypointManager.DIM_NETHER, true);

        List<WaypointManager.Waypoint> loaded = WaypointManager.load(gameDir);
        assertEquals(2, loaded.size());
        WaypointManager.Waypoint active = WaypointManager.findActiveWaypoint(loaded);
        assertNotNull(active);
        assertEquals(death.id, active.id);
        assertTrue(death.formatHudBadge().contains("Nether"));
        assertEquals("/execute in minecraft:overworld run tp @s 120 72 -340",
                base.formatTeleportCommand());

        WaypointManager.setPinned(gameDir, base.id);
        WaypointManager.Waypoint repinned =
                WaypointManager.findActiveWaypoint(WaypointManager.load(gameDir));
        assertNotNull(repinned);
        assertEquals(base.id, repinned.id);

        WaypointManager.delete(gameDir, death.id);
        assertEquals(1, WaypointManager.load(gameDir).size());
    }

    @Test
    public void serverListManager_perServerControlProfileAndAutoReconnectMatching() throws Exception {
        assertEquals("pvp_claw.json",
                ServerListManager.sanitizeControlProfileName("pvp_claw.json"));
        assertEquals("smp_build.json",
                ServerListManager.sanitizeControlProfileName("smp_build"));
        assertNull(ServerListManager.sanitizeControlProfileName("../../etc/passwd"));
        assertNull(ServerListManager.sanitizeControlProfileName(".hidden.json"));

        File gameDir = tmp.newFolder("mc_servers");
        ServerListManager.ServerEntry pvp =
                new ServerListManager.ServerEntry("Minemen", "minemen.club");
        pvp.controlProfile = "pvp_claw.json";
        ServerListManager.ServerEntry smp =
                new ServerListManager.ServerEntry("Friends SMP", "smp.example.org:25570");
        smp.favorite = true;

        ServerListManager.saveServers(gameDir, Arrays.asList(pvp, smp));

        List<ServerListManager.ServerEntry> reloaded = ServerListManager.loadServers(gameDir);
        assertEquals(2, reloaded.size());
        assertEquals("pvp_claw.json", reloaded.get(0).controlProfile);
        assertNull(reloaded.get(1).controlProfile);

        // Export & re-import round-trips per-server controlProfile
        String exportedJson = ServerListManager.exportServersJson(reloaded);
        List<ServerListManager.ServerEntry> reimported =
                ServerListManager.importServersJson(exportedJson);
        assertEquals("pvp_claw.json", reimported.get(0).controlProfile);

        // Matches default port 25565 and trailing DNS dot from vanilla Connecting log
        assertEquals("pvp_claw.json",
                ServerListManager.findControlProfileForServer(gameDir, "minemen.club.:25565"));
        assertNull(ServerListManager.findControlProfileForServer(gameDir, "smp.example.org:25570"));

        // Connecting & disconnect log parsers
        assertEquals("minemen.club:25565", ServerListManager.parseConnectingLogEndpoint(
                "[14:22:01] [Render thread/INFO]: Connecting to minemen.club., 25565"));
        assertTrue(ServerListManager.isUnexpectedServerDisconnectLog(
                "Internal Exception: io.netty.handler.timeout.ReadTimeoutException"));
        assertTrue(ServerListManager.isUnexpectedServerDisconnectLog(
                "[Render thread/INFO]: Lost connection: Timed out"));
        assertFalse(ServerListManager.isUnexpectedServerDisconnectLog(
                "You are banned from this server!"));
        assertFalse(ServerListManager.isUnexpectedServerDisconnectLog(
                "Kicked by an operator"));
        assertFalse(ServerListManager.isUnexpectedServerDisconnectLog(
                "Stopping!"));
    }
}
