package git.artdeell.mojo.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Host tests used to see {@code Build.VERSION.SDK_INT == 0}, so every {@code SDK_INT >= O} guard
 * was false and the symlink defence in {@link ArchiveSecurity} never ran. These tests pin real
 * API levels through {@link SdkLevel} and exercise the modern branches.
 */
public class SdkLevelAwareSecurityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void resetSdk() {
        SdkLevel.resetForTests();
    }

    @Test
    public void overrideIsVisibleAndResettable() {
        SdkLevel.overrideForTests(34);
        assertEquals(34, SdkLevel.get());
        assertTrue(SdkLevel.atLeast(26));
        assertTrue(SdkLevel.atLeast(34));
        assertFalse(SdkLevel.atLeast(35));
        SdkLevel.resetForTests();
        assertEquals("host JVM falls back to the stub value", 0, SdkLevel.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeOverrideIsRejected() {
        SdkLevel.overrideForTests(-5);
    }

    @Test
    public void symlinkInsideDestinationIsBlockedOnModernApis() throws Exception {
        File root = tmp.newFolder("extract");
        File real = new File(root, "real");
        assertTrue(real.mkdirs());
        File link = new File(root, "link");
        assumeTrue("filesystem must support symlinks", trySymlink(link, real));

        for (int sdk : new int[]{26, 29, 31, 34, 36}) {
            SdkLevel.overrideForTests(sdk);
            try {
                ArchiveSecurity.resolveSafeEntry(root, "link/payload.so");
                fail("sdk " + sdk + ": extraction through a symlink must be rejected");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("symbolic link"));
            }
        }
    }

    @Test
    public void plainEntriesStillResolveOnModernApis() throws Exception {
        File root = tmp.newFolder("extract-plain");
        SdkLevel.overrideForTests(34);
        File resolved = ArchiveSecurity.resolveSafeEntry(root, "mods/sodium.jar");
        assertEquals(new File(root.getCanonicalFile(), "mods/sodium.jar"), resolved);
    }

    @Test
    public void zipSlipIsBlockedRegardlessOfApiLevel() throws Exception {
        File root = tmp.newFolder("extract-slip");
        for (int sdk : new int[]{21, 26, 34}) {
            SdkLevel.overrideForTests(sdk);
            try {
                ArchiveSecurity.resolveSafeEntry(root, "../../etc/passwd");
                fail("sdk " + sdk + ": traversal must be rejected");
            } catch (IOException expected) {
                // ok
            }
        }
    }

    private static boolean trySymlink(File link, File target) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
            return Files.isSymbolicLink(link.toPath());
        } catch (Exception e) {
            return false;
        }
    }
}
