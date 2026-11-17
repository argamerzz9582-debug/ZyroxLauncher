package git.artdeell.mojo.instances;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Unit tests for Zyrox 2.0 Instance Studio: backward-compatible v1->v2 migration, isolated cloning,
 * config export/import, SHA-256 verified backup & restore, delete impact, and startup renderer fallback.
 */
public class InstanceStudioTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File instancesDir;

    @Before
    public void setUp() throws Exception {
        File home = tmp.newFolder("game_home");
        Tools.DIR_GAME_HOME = home.getAbsolutePath();
        Tools.DIR_GAME_NEW = new File(home, ".minecraft").getAbsolutePath();
        new File(Tools.DIR_GAME_NEW).mkdirs();
        instancesDir = new File(home, "instances");
        instancesDir.mkdirs();
        LauncherPreferences.PREF_RAM_ALLOCATION = 1536;
        LauncherPreferences.PREF_RENDERER = "opengles2";
    }

    @Test
    public void migratesLegacyV1InstanceWithoutDataLoss() {
        Instance legacy = new Instance();
        legacy.schemaVersion = 0;
        legacy.name = "My 1.20.1 Fabric";
        legacy.versionId = "fabric-loader-0.16.9-1.20.1";
        legacy.sharedData = false;
        legacy.ramAllocationMb = -50; // invalid negative value

        legacy.sanitize();

        assertEquals(Instance.CURRENT_SCHEMA_VERSION, legacy.schemaVersion);
        assertEquals("My 1.20.1 Fabric", legacy.name);
        assertEquals("fabric-loader-0.16.9-1.20.1", legacy.versionId);
        assertEquals(0, legacy.ramAllocationMb);
        assertEquals(1536, legacy.getEffectiveRamAllocationMb());

        legacy.ramAllocationMb = 2560;
        assertEquals(2560, legacy.getEffectiveRamAllocationMb());
    }

    @Test
    public void clonesBacksUpVerifiesAndRestoresInstanceWithTamperDetection() throws Exception {
        File srcRoot = new File(instancesDir, "inst-alpha");
        srcRoot.mkdirs();
        Instance src = new Instance();
        src.mInstanceRoot = srcRoot;
        src.name = "Survival Pack";
        src.versionId = "1.21.4";
        src.sharedData = false;
        src.ramAllocationMb = 2048;
        src.sanitize();
        src.write();

        File worldDir = new File(srcRoot, "saves/MyWorld");
        worldDir.mkdirs();
        writeText(new File(worldDir, "level.dat"), "nbt-level-data");
        File modsDir = new File(srcRoot, "mods");
        modsDir.mkdirs();
        writeText(new File(modsDir, "sodium.jar"), "jar-bytes");

        // Verify isolated clone does not copy worlds when copyWorlds=false
        Instance cloned = InstanceManager.cloneInstance(src, instancesDir, "Survival Clone", true, false);
        assertNotEquals(srcRoot.getCanonicalPath(), cloned.getGameDirectory().getCanonicalPath());
        assertTrue(new File(cloned.getGameDirectory(), "mods/sodium.jar").isFile());
        assertFalse(new File(cloned.getGameDirectory(), "saves/MyWorld").exists());

        // Verify delete impact reporting
        InstanceManager.DeleteImpact impact = InstanceManager.computeDeleteImpact(src);
        assertFalse(impact.usesSharedDirectory);
        assertEquals(1, impact.worldCount);
        assertEquals(1, impact.modCount);
        assertTrue(impact.totalBytesToDelete > 0L);

        // Create verified SHA-256 backup & restore into new isolated directory
        File backupDir = tmp.newFolder("backups");
        File zip = InstanceManager.createInstanceBackup(src, backupDir, true, 5);
        assertTrue(zip.isFile());
        assertTrue(new File(backupDir, zip.getName() + ".sha256").isFile());

        Instance restored = InstanceManager.restoreInstanceBackup(zip, instancesDir);
        assertNotNull(restored);
        assertNotEquals(srcRoot.getCanonicalPath(), restored.getGameDirectory().getCanonicalPath());
        assertTrue(new File(restored.getGameDirectory(), "saves/MyWorld/level.dat").isFile());
        assertTrue(new File(restored.getGameDirectory(), "mods/sodium.jar").isFile());

        // Tamper with SHA-256 sidecar -> restore must reject!
        writeText(new File(backupDir, zip.getName() + ".sha256"),
                "0000000000000000000000000000000000000000000000000000000000000000  " + zip.getName());
        try {
            InstanceManager.restoreInstanceBackup(zip, instancesDir);
            fail("Expected SHA-256 mismatch failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toUpperCase().contains("SHA-256")
                    || expected.getMessage().toLowerCase().contains("checksum"));
        }
    }

    @Test
    public void exportsImportsConfigTracksRendererFallbackAndDetectsDuplicates() throws Exception {
        Instance a = new Instance();
        a.name = "PVP 1.21";
        a.versionId = "1.21.4";
        a.renderer = "opengles3_ltw";
        a.ramAllocationMb = 3072;
        a.sanitize();

        String json = InstanceManager.exportConfigJson(a);
        assertTrue(json.contains(InstanceManager.EXPORT_FORMAT));
        assertTrue(json.contains("PVP 1.21"));
        assertTrue(json.contains("3072"));

        Instance imported = InstanceManager.importConfigJson(json, instancesDir);
        assertEquals("PVP 1.21", imported.name);
        assertEquals(3072, imported.ramAllocationMb);

        // Duplicate name detection (case-insensitive)
        Instance b = new Instance();
        b.name = "pvp 1.21";
        b.versionId = "1.21.4";
        assertFalse(InstanceManager.findDuplicateSummaries(Arrays.asList(a, b)).isEmpty());

        // Last-known-good renderer fallback
        a.lastKnownGoodRenderer = "opengles3_ltw";
        a.renderer = "vulkan_zink";
        a.consecutiveStartupFailures = 3;
        assertTrue(InstanceManager.restoreLastKnownGoodRenderer(a));
        assertEquals("opengles3_ltw", a.renderer);
        assertEquals(0, a.consecutiveStartupFailures);
    }

    private static void writeText(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
