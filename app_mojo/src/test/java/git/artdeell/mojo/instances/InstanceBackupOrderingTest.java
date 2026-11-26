package git.artdeell.mojo.instances;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Regression tests for backup naming and ordering. Archives created in the same second used to
 * overwrite each other, and pruning sorted by file mtime, so it could delete the newest backup on
 * filesystems with coarse timestamps.
 */
public class InstanceBackupOrderingTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void monotonicStampsAreStrictlyIncreasingEvenInATightLoop() {
        long prev = InstanceBackupService.nextMonotonicStampMs();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            long next = InstanceBackupService.nextMonotonicStampMs();
            assertTrue(next > prev);
            assertTrue("formatted stamps must be unique", names.add(InstanceBackupService.formatStamp(next)));
            prev = next;
        }
    }

    @Test
    public void uniqueArchiveFileNeverReusesAnExistingOrInFlightName() throws Exception {
        File dir = tmp.newFolder("backups");
        File first = InstanceBackupService.uniqueArchiveFile(dir, "World-20261126-101010-123", ".zip");
        assertEquals("World-20261126-101010-123.zip", first.getName());
        touch(first, 1_000L);
        File second = InstanceBackupService.uniqueArchiveFile(dir, "World-20261126-101010-123", ".zip");
        assertEquals("World-20261126-101010-123-2.zip", second.getName());
        touch(new File(dir, second.getName() + ".tmp"), 1_000L); // another writer is mid-flight
        File third = InstanceBackupService.uniqueArchiveFile(dir, "World-20261126-101010-123", ".zip");
        assertEquals("World-20261126-101010-123-3.zip", third.getName());
    }

    @Test
    public void ordersByEmbeddedStampNotByFileMtime() throws Exception {
        File dir = tmp.newFolder("snaps");
        String p = InstanceBackupService.PRE_MOD_SNAPSHOT_PREFIX;
        // Identical mtimes (FAT/sdcardfs granularity) and a copied old file with a NEWER mtime.
        File oldest = touch(new File(dir, p + "20261126-101010-001_sodium.zip"), 5_000L);
        File middle = touch(new File(dir, p + "20261126-101010-002_iris.zip"), 5_000L);
        File newest = touch(new File(dir, p + "20261126-101010-002-2_lithium.zip"), 5_000L);
        File legacy = touch(new File(dir, p + "20261125-090000_fabric-api.zip"), 99_000L);

        File[] files = {legacy, oldest, newest, middle};
        Arrays.sort(files, InstanceBackupService.newestFirst(p));
        assertEquals(Arrays.asList(newest, middle, oldest, legacy), Arrays.asList(files));
    }

    @Test
    public void legacySecondPrecisionNamesStillSortChronologically() {
        String a = InstanceBackupService.stampSortKey("Survival-20261125-235959.zip", "Survival-");
        String b = InstanceBackupService.stampSortKey("Survival-20261126-000000-000.zip", "Survival-");
        assertTrue(b.compareTo(a) > 0);
        assertNotEquals(a, b);
    }

    @Test
    public void pruneKeepsTheNewestArchivesWhenMtimesTie() throws Exception {
        File dir = tmp.newFolder("prune");
        String p = "Test-";
        File[] all = new File[5];
        for (int i = 0; i < all.length; i++) {
            all[i] = touch(new File(dir, p + "20261126-120000-00" + i + ".zip"), 42_000L);
            touch(new File(dir, all[i].getName() + ".sha256"), 42_000L);
        }
        InstanceBackupService.pruneOldBackups(dir, p, 2);
        assertTrue(all[4].isFile());
        assertTrue(all[3].isFile());
        for (int i = 0; i < 3; i++) {
            assertFalse("old archive " + i + " must be pruned", all[i].exists());
            assertFalse("its checksum must be pruned too", new File(dir, all[i].getName() + ".sha256").exists());
        }
    }

    private static File touch(File f, long mtime) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(f.getName().getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(f.setLastModified(mtime));
        return f;
    }
}
