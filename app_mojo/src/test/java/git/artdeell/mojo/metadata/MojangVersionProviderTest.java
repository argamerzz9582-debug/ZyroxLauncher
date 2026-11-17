package git.artdeell.mojo.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.atomic.AtomicInteger;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.utils.DownloadUtils;

public class MojangVersionProviderTest {
    private static final String SHA_A = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA_B = "fedcba9876543210fedcba9876543210fedcba98";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void parseAndValidateManifest_supportsClassicAndYearBased26Plus_deduplicatesAndFiltersUnsafe() throws Exception {
        String raw = "{"
                + "\"latest\":{\"release\":\"26.1.1\",\"snapshot\":\"26.2-snapshot-1\"},"
                + "\"versions\":["
                + "{\"id\":\"26.2-snapshot-1\",\"type\":\"snapshot\",\"url\":\"https://piston-meta.mojang.com/v1/packages/a/26.2-snapshot-1.json\",\"sha1\":\"" + SHA_A.toUpperCase() + "\",\"releaseTime\":\"2026-06-10T12:00:00+00:00\"},"
                + "{\"id\":\"26.1.1\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/packages/b/26.1.1.json\",\"sha1\":\"" + SHA_B + "\",\"releaseTime\":\"2026-04-02T12:00:00+00:00\"},"
                + "{\"id\":\"26.1.1\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/packages/dup/26.1.1.json\",\"sha1\":\"" + SHA_A + "\"},"
                + "{\"id\":\"26.1\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/packages/c/26.1.json\",\"sha1\":\"not-a-sha1\"},"
                + "{\"id\":\"1.21.4\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/packages/d/1.21.4.json\",\"sha1\":\"" + SHA_A + "\"},"
                + "{\"id\":\"insecure\",\"type\":\"release\",\"url\":\"http://piston-meta.mojang.com/insecure.json\"},"
                + "{\"id\":\"../traversal\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/evil.json\"},"
                + "{\"id\":\"b1.7.3\",\"type\":\"old_beta\",\"url\":\"https://piston-meta.mojang.com/v1/packages/e/b1.7.3.json\"},"
                + "{\"id\":\"a1.2.6\",\"type\":\"old_alpha\",\"url\":\"https://piston-meta.mojang.com/v1/packages/f/a1.2.6.json\"}"
                + "]}";

        JVersionList list = MojangVersionProvider.parseAndValidateManifest(raw);
        assertEquals(6, list.versions.length);
        assertEquals("26.2-snapshot-1", list.versions[0].id);
        assertEquals("26.1.1", list.versions[1].id);
        assertEquals("26.1", list.versions[2].id);
        assertEquals("1.21.4", list.versions[3].id);
        assertEquals("b1.7.3", list.versions[4].id);
        assertEquals("a1.2.6", list.versions[5].id);

        // SHA-1 normalized to lowercase; invalid SHA-1 stripped safely to null
        assertEquals(SHA_A, list.versions[0].sha1);
        assertEquals(SHA_B, list.versions[1].sha1);
        assertNull(list.versions[2].sha1);

        // Symbolic latest resolution works dynamically for future 26.x+ releases
        assertEquals("26.1.1", MojangVersionProvider.resolveSymbolicVersionId("latest-release", list));
        assertEquals("26.2-snapshot-1", MojangVersionProvider.resolveSymbolicVersionId("latest-snapshot", list));
        assertEquals("1.21.4", MojangVersionProvider.resolveSymbolicVersionId("1.21.4", list));
        assertNotNull(MojangVersionProvider.findVersion(list, "26.1.1"));
        assertNull(MojangVersionProvider.findVersion(list, "insecure"));
    }

    @Test
    public void parseAndValidateManifest_derivesLatestWhenMissing_andSupportsFuture27Plus() throws Exception {
        String futureManifest = "{\"versions\":["
                + "{\"id\":\"27.3\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/packages/z/27.3.json\"},"
                + "{\"id\":\"27w40a\",\"type\":\"snapshot\",\"url\":\"https://piston-meta.mojang.com/v1/packages/y/27w40a.json\"}"
                + "]}";
        JVersionList parsed = MojangVersionProvider.parseAndValidateManifest(futureManifest);
        assertEquals("27.3", MojangVersionProvider.resolveSymbolicVersionId("latest-release", parsed));
        assertEquals("27w40a", MojangVersionProvider.resolveSymbolicVersionId("latest-snapshot", parsed));
    }

    @Test
    public void parseAndValidateManifest_rejectsCorruptAndEmptyPayloads() {
        for (String bad : new String[]{"", "   ", "{bad json", "{\"versions\":[]}",
                "{\"versions\":[{\"id\":\"1.0\",\"type\":\"release\",\"url\":\"http://cleartext.example/1.json\"}]}"}) {
            try {
                MojangVersionProvider.parseAndValidateManifest(bad);
                fail("Expected ParseException for: " + bad);
            } catch (DownloadUtils.ParseException expected) {
                // Expected
            }
        }
    }

    @Test
    public void resolveRequiredJavaVersion_supportsJava8_17_21_25AndFutureWithoutUpperCap() {
        assertEquals(8, MojangVersionProvider.resolveRequiredJavaVersion(null));

        JVersionList.Version legacy = new JVersionList.Version();
        legacy.javaVersion = new JVersionList.JavaVersionInfo();
        legacy.javaVersion.component = "jre-legacy";
        legacy.javaVersion.majorVersion = 8;
        assertEquals(8, MojangVersionProvider.resolveRequiredJavaVersion(legacy));

        for (int major : new int[]{16, 17, 21, 25, 29}) {
            JVersionList.Version v = new JVersionList.Version();
            v.javaVersion = new JVersionList.JavaVersionInfo();
            v.javaVersion.component = "java-runtime-delta";
            v.javaVersion.majorVersion = major;
            assertEquals(major, MojangVersionProvider.resolveRequiredJavaVersion(v));
        }

        // LabyMod-style fallback (`version` field instead of `majorVersion`)
        JVersionList.Version laby = new JVersionList.Version();
        laby.javaVersion = new JVersionList.JavaVersionInfo();
        laby.javaVersion.version = 21;
        assertEquals(21, MojangVersionProvider.resolveRequiredJavaVersion(laby));
    }

    @Test
    public void syncManifest_discoversNewlyPublishedVersionWithoutCodeChanges_andFallsBackFromBadMirror() throws Exception {
        String v1 = "{\"latest\":{\"release\":\"26.1\"},\"versions\":["
                + "{\"id\":\"26.1\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/26.1.json\",\"sha1\":\"" + SHA_A + "\"}]}";
        String v2 = "{\"latest\":{\"release\":\"26.2\"},\"versions\":["
                + "{\"id\":\"26.2\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/26.2.json\",\"sha1\":\"" + SHA_B + "\"},"
                + "{\"id\":\"26.1\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/26.1.json\",\"sha1\":\"" + SHA_A + "\"}]}";

        AtomicInteger revision = new AtomicInteger(1);
        LocalMetadataCache cache = new LocalMetadataCache(
                tempFolder.newFolder("mojang_cache"),
                (url, headers, cto, rto) -> {
                    if (url.contains("broken-mirror.example")) {
                        return new LocalMetadataCache.HttpResponse(502, null, null, null, null, "Bad Gateway");
                    }
                    String body = revision.get() == 1 ? v1 : v2;
                    return new LocalMetadataCache.HttpResponse(200, body, "\"rev-" + revision.get() + "\"", null, null, "OK");
                },
                System::currentTimeMillis,
                ms -> {},
                "ZyroxTest/1.0.3",
                5_000,
                5_000,
                1
        );

        MojangVersionProvider provider = new MojangVersionProvider(
                cache, () -> "https://broken-mirror.example/version_manifest_v2.json");

        JVersionList first = provider.syncManifest(false).data;
        assertEquals("26.1", MojangVersionProvider.findLatestByType(first, MojangVersionProvider.TYPE_RELEASE));
        assertFalse(MojangVersionProvider.hasManifestChanged(first, first));

        // Simulate Mojang publishing Minecraft 26.2 later that day
        revision.set(2);
        JVersionList second = provider.syncManifest(true).data;
        assertTrue(MojangVersionProvider.hasManifestChanged(first, second));
        assertEquals("26.2", MojangVersionProvider.findLatestByType(second, MojangVersionProvider.TYPE_RELEASE));
        assertEquals(2, second.versions.length);
    }
}
