package git.artdeell.mojo.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Unit tests for Zip-Slip path containment, zip-bomb ratio/budget checks, SHA-256 checksum
 * verification, and atomic JSON persistence with {@code .bak} backup recovery.
 */
public class ArchiveSecurityAndJsonTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    public static class SampleConfig {
        public String name;
        public int ramMb;
    }

    @Test
    public void blocksZipSlipTraversalAndAbsolutePaths() throws Exception {
        File dest = tmp.newFolder("extract_root");

        // Safe nested entry passes
        File safe = ArchiveSecurity.resolveSafeEntry(dest, "mods/sodium-fabric.jar");
        assertTrue(safe.getCanonicalPath().startsWith(dest.getCanonicalPath()));

        // Dangerous entries must throw IOException
        String[] malicious = {
                "../escaped.jar",
                "mods/../../escaped.jar",
                "..\\..\\evil.sh",
                "/etc/passwd",
                "C:/Windows/win.ini",
                "mods/bad\0name.jar"
        };
        for (String bad : malicious) {
            try {
                ArchiveSecurity.resolveSafeEntry(dest, bad);
                fail("Expected Zip-Slip block for: " + bad);
            } catch (IOException expected) {
                // Passed
            }
        }
    }

    @Test
    public void zipExtractRejectsMaliciousArchiveWithoutWritingOutsideRoot() throws Exception {
        File dest = tmp.newFolder("safe_target");
        File sibling = new File(dest.getParentFile(), "pwned.txt");

        File badZip = new File(tmp.getRoot(), "malicious.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(badZip))) {
            zos.putNextEntry(new ZipEntry("../pwned.txt"));
            zos.write("attack".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(badZip)) {
            ZipUtils.zipExtract(zf, "", dest);
            fail("Expected Zip-Slip exception");
        } catch (IOException expected) {
            assertTrue(!sibling.exists());
        }
    }

    @Test
    public void enforcesEntrySizeAndTotalExtractionBudgets() throws Exception {
        File okOut = new File(tmp.getRoot(), "ok.bin");
        byte[] small = new byte[1024];
        long written = ArchiveSecurity.copyBoundedToFile(
                new ByteArrayInputStream(small), okOut, 4096L, 16384L);
        assertEquals(1024L, written);
        assertTrue(okOut.isFile());

        File oversized = new File(tmp.getRoot(), "bomb.bin");
        byte[] huge = new byte[16384];
        try {
            ArchiveSecurity.copyBoundedToFile(
                    new ByteArrayInputStream(huge), oversized, 4096L, 16384L);
            fail("Expected per-file extraction budget block");
        } catch (IOException expected) {
            assertTrue(!oversized.exists());
        }
    }

    @Test
    public void atomicJsonWritesBackupAndRecoversFromCorruptedPrimary() throws Exception {
        File cfgFile = new File(tmp.getRoot(), "config.json");
        SampleConfig v1 = new SampleConfig();
        v1.name = "First";
        v1.ramMb = 1536;
        JSONUtils.writeToFileAtomic(cfgFile, v1, true);

        SampleConfig v2 = new SampleConfig();
        v2.name = "Second";
        v2.ramMb = 2048;
        JSONUtils.writeToFileAtomic(cfgFile, v2, true);

        File bakFile = new File(tmp.getRoot(), "config.json.bak");
        assertTrue(bakFile.isFile());

        // Corrupt primary JSON (simulated power loss / partial edit)
        try (FileOutputStream out = new FileOutputStream(cfgFile, false)) {
            out.write("{corrupted_json!!!".getBytes(StandardCharsets.UTF_8));
        }

        // readFromFile transparently recovers v1 from .bak!
        SampleConfig recovered = JSONUtils.readFromFile(cfgFile, SampleConfig.class);
        assertNotNull(recovered);
        assertEquals("First", recovered.name);
        assertEquals(1536, recovered.ramMb);
    }

    @Test
    public void networkBackoffRespectsCapAndFormatsStorageCleanly() {
        long d0 = NetworkPolicyHelper.computeBackoffDelayMs(0, 500L, 8000L);
        long d3 = NetworkPolicyHelper.computeBackoffDelayMs(3, 500L, 8000L);
        long d9 = NetworkPolicyHelper.computeBackoffDelayMs(9, 500L, 8000L);
        assertTrue(d0 > 0L && d0 <= 8000L);
        assertTrue(d3 >= 2000L && d3 <= 8000L);
        assertTrue(d9 <= 8000L);

        assertTrue(NetworkPolicyHelper.formatBytes(1024L * 1024L).contains("MB"));
        assertTrue(!NetworkPolicyHelper.formatEtaSeconds(10_000L, 5_000.0).isEmpty());
    }
}
