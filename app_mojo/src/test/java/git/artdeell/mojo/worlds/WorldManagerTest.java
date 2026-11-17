package git.artdeell.mojo.worlds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Unit tests for {@link WorldManager}: scanning, integrity checking, SHA-256 backups,
 * non-destructive conflict-safe restore, and contained world deletion.
 */
public class WorldManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void scansWorldsCreatesVerifiedBackupsRestoresSafelyAndGuardsDeletion() throws Exception {
        File gameDir = tmp.newFolder("instance_dir");
        File savesDir = new File(gameDir, "saves");
        File validWorld = new File(savesDir, "Hardcore_Season_2");
        new File(validWorld, "region").mkdirs();
        writeFile(new File(validWorld, "level.dat"), "valid-level-dat-bytes");
        writeFile(new File(validWorld, "region/r.0.0.mca"), "chunk-data-payload-12345");
        writeFile(new File(validWorld, "session.lock"), "lock");

        File brokenWorld = new File(savesDir, "BrokenFolder");
        brokenWorld.mkdirs();
        writeFile(new File(brokenWorld, "notes.txt"), "missing level.dat");

        List<WorldManager.WorldEntry> worlds = WorldManager.listWorlds(gameDir);
        assertEquals(2, worlds.size());

        WorldManager.WorldEntry foundValid = worlds.get(0).folderName.equals("Hardcore_Season_2")
                ? worlds.get(0) : worlds.get(1);
        WorldManager.WorldEntry foundBroken = worlds.get(0).folderName.equals("BrokenFolder")
                ? worlds.get(0) : worlds.get(1);
        assertEquals(WorldManager.Integrity.VALID, foundValid.integrity);
        assertNotEquals(WorldManager.Integrity.VALID, foundBroken.integrity);

        // Create verified backup (session.lock excluded, SHA-256 sidecar written)
        File backupDir = tmp.newFolder("world_backups");
        File backupZip = WorldManager.backupWorld(validWorld, backupDir, "TestInstance", "1.21.4", 2);
        assertTrue(backupZip.isFile());
        assertTrue(new File(backupDir, backupZip.getName() + ".sha256").isFile());

        List<WorldManager.BackupEntry> backups = WorldManager.listBackups(backupDir);
        assertEquals(1, backups.size());
        assertTrue(backups.get(0).hasSha256);

        // Restore while original still exists -> RENAME_IF_EXISTS must NOT overwrite original!
        assertTrue(WorldManager.hasRestoreConflict(savesDir, "Hardcore_Season_2"));
        File restored = WorldManager.restoreOrImportWorldZip(
                backupZip, savesDir, "Hardcore_Season_2", WorldManager.RestoreMode.RENAME_IF_EXISTS);
        assertNotEquals(validWorld.getCanonicalPath(), restored.getCanonicalPath());
        assertTrue(new File(restored, "level.dat").isFile());
        assertTrue(new File(restored, "region/r.0.0.mca").isFile());
        assertFalse(new File(restored, "session.lock").exists());

        // Safe delete deletes only inside saves/ and refuses outside paths
        WorldManager.deleteWorld(savesDir, restored);
        assertFalse(restored.exists());

        File outside = tmp.newFolder("outside_dir");
        try {
            WorldManager.deleteWorld(savesDir, outside);
            fail("Expected containment rejection when deleting outside saves/");
        } catch (IOException expected) {
            assertTrue(outside.isDirectory());
        }
    }

    private static void writeFile(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
