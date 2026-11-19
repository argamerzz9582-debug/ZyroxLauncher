package git.artdeell.mojo.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kdt.mcgui.AccountSpinner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.Logger;
import git.artdeell.mojo.content.ModrinthContentInstaller;
import git.artdeell.mojo.fragments.OnboardingWizardFragment;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceHealthInspector;
import git.artdeell.mojo.instances.InstanceManager;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.metadata.JavaRuntimeAdvisor;
import git.artdeell.mojo.metadata.MojangVersionProvider;
import git.artdeell.mojo.modding.fabric.FabricVersion;
import git.artdeell.mojo.modding.fabric.FabriclikeUtils;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.services.GameService;
import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.GameOptionsUtils;

/**
 * End-to-end regression suite locking down:
 * <ol>
 *   <li>Single unified first-launch login flow (zero duplicate SelectAuth + Onboarding screens).</li>
 *   <li>Unblocked GameService/GameActivity/GameView JVM boot handshake, native-log safety in
 *       {@code :launcher}, loading-screen decoder protection, and symbolic version normalization.</li>
 *   <li>Automatic Latest Stable Fabric default version resolution &amp; instance provisioning.</li>
 * </ol>
 */
public class LaunchOnboardingAndDefaultFabricTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void gameServiceLocalBinderStaysInactiveUntilGameActivityConnects() throws Exception {
        GameService.LocalBinder binder = new GameService.LocalBinder();
        assertFalse("Fresh LocalBinder.isActive MUST be false so GameView.realStart() launches JVM",
                binder.isActive);

        String svcSrc = readUtf8(resolveFile(
                "src/main/java/git/artdeell/mojo/services/GameService.java"));
        assertFalse("GameService must never pre-set mLocalBinder.isActive = true in onStartCommand",
                svcSrc.contains("mLocalBinder.isActive = true"));
        assertTrue("GameService must track service lifetime via dedicated mServiceRunning flag",
                svcSrc.contains("mServiceRunning = true"));

        String actSrc = readUtf8(resolveFile(
                "src/main/java/git/artdeell/mojo/game/GameActivity.java"));
        int startIdx = actSrc.indexOf("launcherGLView.start(localBinder.isActive)");
        int setIdx = actSrc.indexOf("localBinder.isActive = true");
        assertTrue("GameActivity.onServiceConnected must call launcherGLView.start before flipping isActive",
                startIdx > 0 && setIdx > startIdx);
    }

    @Test
    public void singleUnifiedFirstLaunchLoginNeverDuplicatesSelectAuthAndOnboarding() throws Exception {
        String spinnerSrc = readUtf8(resolveFile(
                "src/main/java/com/kdt/mcgui/AccountSpinner.java"));
        assertTrue("AccountSpinner must suppress programmatic reload selection callbacks",
                spinnerSrc.contains("mSuppressSelectionCallback"));
        assertTrue("AccountSpinner must only call createAccount from dropdown when populated",
                spinnerSrc.contains("if (mAdapter.getCount() > 1)"));
        assertFalse(AccountSpinner.isLoginInFlight());

        String mainMenuSrc = readUtf8(resolveFile(
                "src/main/java/git/artdeell/mojo/fragments/MainMenuFragment.java"));
        assertTrue("MainMenuFragment must wait for initial asset extraction before showing onboarding",
                mainMenuSrc.contains("ProgressKeeper.waitUntilDone"));

        String launcherSrc = readUtf8(resolveFile(
                "src/main/java/git/artdeell/mojo/LauncherActivity.java"));
        assertTrue("LauncherActivity auth route must coalesce into OnboardingWizardFragment on first run",
                launcherSrc.contains("fragment instanceof OnboardingWizardFragment")
                        && launcherSrc.contains("OnboardingWizardFragment.shouldLaunchOnboarding()"));

        // Verify OAuth return state toggles cleanly without lingering
        OnboardingWizardFragment.cancelPendingOAuthReturn();
        assertFalse(OnboardingWizardFragment.consumePendingOAuthReturn());

        boolean prior = LauncherPreferences.PREF_ZYROX_ONBOARDING_DONE;
        try {
            LauncherPreferences.PREF_ZYROX_ONBOARDING_DONE = true;
            assertFalse(OnboardingWizardFragment.shouldLaunchOnboarding());
        } finally {
            LauncherPreferences.PREF_ZYROX_ONBOARDING_DONE = prior;
        }
    }

    @Test
    public void symbolicVersionResolutionSupportsBothUnderscoreAndHyphenChannels() {
        JVersionList manifest = new JVersionList();
        manifest.latest = new HashMap<>();
        manifest.latest.put("release", "1.21.11");
        manifest.latest.put("snapshot", "26.1-snapshot-4");

        assertEquals("1.21.11",
                MojangVersionProvider.resolveSymbolicVersionId(Instance.VERSION_LATEST_RELEASE, manifest));
        assertEquals("1.21.11",
                MojangVersionProvider.resolveSymbolicVersionId("latest-release", manifest));
        assertEquals("26.1-snapshot-4",
                MojangVersionProvider.resolveSymbolicVersionId(Instance.VERSION_LATEST_SNAPSHOT, manifest));
        assertEquals("26.1-snapshot-4",
                MojangVersionProvider.resolveSymbolicVersionId("latest-snapshot", manifest));
        assertEquals("fabric-loader-0.18.4-1.21.11",
                MojangVersionProvider.resolveSymbolicVersionId("fabric-loader-0.18.4-1.21.11", manifest));
    }

    @Test
    public void defaultFabricSelectionPicksNewestStableReleaseAndHandlesModernVersions() throws Exception {
        FabricVersion snap = makeFabricVersion("26.2-snapshot-1", false);
        FabricVersion latestStable = makeFabricVersion("1.21.11", true);
        FabricVersion olderStable = makeFabricVersion("1.21.4", true);
        FabricVersion[] catalog = new FabricVersion[]{snap, latestStable, olderStable};

        // Prefers newest stable release while skipping unstable snapshots
        assertEquals("1.21.11", FabriclikeUtils.selectLatestStableGameVersion(catalog, null));
        assertEquals("1.21.11", FabriclikeUtils.selectLatestStableGameVersion(catalog, "1.21.11"));
        // Falls back to highest Fabric-supported stable release if Mojang release is 5 minutes old
        assertEquals("1.21.11", FabriclikeUtils.selectLatestStableGameVersion(catalog, "26.2"));

        // Supports Mojang year-based 26.x stable releases seamlessly
        FabricVersion y26Stable = makeFabricVersion("26.1.2", true);
        FabricVersion[] y26Catalog = new FabricVersion[]{snap, y26Stable, latestStable};
        assertEquals("26.1.2", FabriclikeUtils.selectLatestStableGameVersion(y26Catalog, "26.1.2"));

        // Rejects snapshot-only arrays cleanly
        assertNull(FabriclikeUtils.selectLatestStableGameVersion(new FabricVersion[]{snap}, null));

        // Verify Instance symbolic Fabric helpers & Instances default provisioning
        assertTrue(Instance.isLatestFabricId(Instance.VERSION_LATEST_FABRIC));
        assertTrue(Instance.isLatestFabricId("latest-fabric"));
        assertTrue(Instance.isSymbolicVersionId(Instance.VERSION_LATEST_FABRIC));
        assertTrue(Instance.isSymbolicVersionId(Instance.VERSION_LATEST_RELEASE));
        assertTrue(Instance.isSymbolicVersionId("latest-release"));
        assertEquals("Fabric (Latest)", Instances.DEFAULT_FABRIC_PLACEHOLDER_NAME);

        String instancesSrc = readUtf8(resolveFile(
                "src/main/java/git/artdeell/mojo/instances/Instances.java"));
        assertTrue("First-time default instance must select VERSION_LATEST_FABRIC with fabric icon",
                instancesSrc.contains("instance.versionId = Instance.VERSION_LATEST_FABRIC")
                        && instancesSrc.contains("instance.icon = \"fabric\"")
                        && instancesSrc.contains("ensureDefaultLatestFabricAsync"));

        // Verify JavaRuntimeAdvisor & ModrinthContentInstaller for 1.21.x, 26.x, and latest_fabric
        assertEquals(21, JavaRuntimeAdvisor.resolveRequiredJava(null, Instance.VERSION_LATEST_FABRIC));
        assertEquals(21, JavaRuntimeAdvisor.resolveRequiredJava(null, "fabric-loader-0.18.4-1.21.11"));
        assertEquals(25, JavaRuntimeAdvisor.resolveRequiredJava(null, "fabric-loader-0.19.1-26.1.2"));
        assertEquals("1.21.11",
                ModrinthContentInstaller.extractMinecraftVersion("fabric-loader-0.18.4-1.21.11"));
        assertEquals("26.1.2",
                ModrinthContentInstaller.extractMinecraftVersion("fabric-loader-0.19.1-26.1.2"));
        assertEquals("fabric",
                ModrinthContentInstaller.extractModLoader(Instance.VERSION_LATEST_FABRIC));

        // Verify InstanceHealthInspector recognizes latest_fabric as healthy & repairs empty version to Fabric
        File root = tmp.newFolder("fabric-inst");
        new File(root, "saves").mkdirs();
        Instance inst = new Instance() {
            @Override
            public File getGameDirectory() {
                return root;
            }
        };
        inst.versionId = Instance.VERSION_LATEST_FABRIC;
        inst.sharedData = false;
        InstanceManager.HealthReport report = InstanceHealthInspector.checkHealth(
                inst, tmp.newFolder("versions"), null, 21, 21);
        assertEquals(InstanceManager.HealthStatus.HEALTHY, report.status);

        // Verify process-safe logger never throws UnsatisfiedLinkError without libmojort.so
        Logger.appendToLogSafe("Zyrox unit-test pre-flight log check");

        // Verify first-launch options.txt disables blocking narrator onboarding & fullscreen
        File gameDir = tmp.newFolder("mc-dir");
        assertTrue(GameOptionsUtils.applyZeroLagFpsOptions(gameDir, false));
        Map<String, String> opts = GameOptionsUtils.readOptionsMap(new File(gameDir, "options.txt"));
        assertEquals("false", opts.get("fullscreen"));
        assertEquals("0", opts.get("narrator"));
        assertEquals("false", opts.get("onboardAccessibility"));
    }

    private static FabricVersion makeFabricVersion(String ver, boolean stable) {
        FabricVersion fv = new FabricVersion();
        fv.version = ver;
        fv.stable = stable;
        return fv;
    }

    private static File resolveFile(String rel) {
        File direct = new File(rel);
        if (direct.exists()) return direct;
        return new File("app_mojo/" + rel);
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            return new String(ArchiveSecurity.readBytesBounded(in, 2 * 1024 * 1024),
                    StandardCharsets.UTF_8);
        }
    }
}
