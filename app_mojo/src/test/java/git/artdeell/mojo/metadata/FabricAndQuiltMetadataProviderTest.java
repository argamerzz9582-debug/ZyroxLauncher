package git.artdeell.mojo.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import git.artdeell.mojo.modding.fabric.FabricVersion;

public class FabricAndQuiltMetadataProviderTest {

    /** Deterministic clock: cache freshness must never depend on the CI machine's wall time. */
    private static final long FIXED_NOW_MS = 1_767_225_600_000L; // 2026-01-01T00:00:00Z
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void fabricProvider_discoversGameAndLoaderVersions_marksRecommendedAndValidatesProfile() throws Exception {
        String gameJson = "["
                + "{\"version\":\"26.1-snapshot-1\",\"stable\":false},"
                + "{\"version\":\"26.1\",\"stable\":true},"
                + "{\"version\":\"26.1\",\"stable\":true},"
                + "{\"version\":\"1.21.4\",\"stable\":true}"
                + "]";
        String loaders261 = "["
                + "{\"loader\":{\"version\":\"0.17.0-beta.1\",\"stable\":false}},"
                + "{\"loader\":{\"version\":\"0.16.12\",\"stable\":true}},"
                + "{\"loader\":{\"version\":\"0.16.11\",\"stable\":true}}"
                + "]";
        String validProfile = "{"
                + "\"id\":\"fabric-loader-0.16.12-26.1\","
                + "\"inheritsFrom\":\"26.1\","
                + "\"mainClass\":\"net.fabricmc.loader.impl.launch.knot.KnotClient\""
                + "}";

        LocalMetadataCache cache = new LocalMetadataCache(
                tempFolder.newFolder("fabric"),
                (url, headers, cto, rto) -> {
                    if (url.endsWith("/versions/game")) {
                        return new LocalMetadataCache.HttpResponse(200, gameJson, "\"g1\"", null, null, "OK");
                    }
                    if (url.endsWith("/versions/loader/26.1")) {
                        return new LocalMetadataCache.HttpResponse(200, loaders261, "\"l1\"", null, null, "OK");
                    }
                    if (url.endsWith("/versions/loader/26.1/0.16.12/profile/json")) {
                        return new LocalMetadataCache.HttpResponse(200, validProfile, null, null, null, "OK");
                    }
                    return new LocalMetadataCache.HttpResponse(200, "[]", "\"empty\"", null, null, "OK");
                },
                () -> FIXED_NOW_MS,
                ms -> {},
                "ZyroxTest/1.0.3",
                5_000,
                5_000,
                1
        );

        FabricMetadataProvider fabric = new FabricMetadataProvider(cache);
        FabricVersion[] games = fabric.getGameVersions(false);
        assertEquals(3, games.length);
        assertEquals("26.1-snapshot-1", games[0].version);
        assertFalse(games[0].stable);
        assertEquals("26.1", games[1].version);
        assertTrue(games[1].stable);

        FabricVersion[] loaders = fabric.getLoaderVersions("26.1", false);
        assertEquals(3, loaders.length);
        assertFalse(loaders[0].recommended);
        assertTrue(loaders[1].recommended);
        assertFalse(loaders[2].recommended);

        FabricVersion rec = fabric.getRecommendedLoaderVersion("26.1");
        assertNotNull(rec);
        assertEquals("0.16.12", rec.version);

        assertTrue(fabric.isCompatible("26.1", "0.16.12"));
        assertTrue(fabric.isCompatible("26.1", "0.17.0-beta.1"));
        assertFalse(fabric.isCompatible("26.1", "0.14.0"));
        assertFalse(fabric.isCompatible("29.9", "0.16.12"));

        FabricMetadataProvider.ValidatedProfile profile = fabric.fetchValidatedProfile("26.1", "0.16.12");
        assertEquals("fabric-loader-0.16.12-26.1", profile.versionId);
        assertEquals("26.1", profile.minecraftVersion);
    }

    @Test
    public void validateProfileJson_rejectsIncompatibleInheritsFromAndMissingMainClass() {
        String wrongMc = "{\"id\":\"fabric-loader-0.16.12-1.20.1\",\"inheritsFrom\":\"1.20.1\",\"mainClass\":\"a.b.Main\"}";
        try {
            FabricMetadataProvider.validateProfileJson(wrongMc, "26.1", "Fabric");
            fail("Expected mismatch IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("1.20.1"));
        }

        String missingMain = "{\"id\":\"fabric-loader-0.16.12-26.1\",\"inheritsFrom\":\"26.1\"}";
        try {
            FabricMetadataProvider.validateProfileJson(missingMain, "26.1", "Fabric");
            fail("Expected missing mainClass IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("mainClass"));
        }

        String traversalId = "{\"id\":\"../../pwn\",\"inheritsFrom\":\"26.1\",\"mainClass\":\"a.b.Main\"}";
        try {
            FabricMetadataProvider.validateProfileJson(traversalId, "26.1", "Fabric");
            fail("Expected invalid ID IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Invalid version ID"));
        }
    }

    @Test
    public void quiltProvider_infersStabilityWhenStableFlagOmitted_andSelectsStableRecommended() throws Exception {
        String quiltLoaders = "["
                + "{\"loader\":{\"version\":\"0.29.0-beta.3\"}},"
                + "{\"loader\":{\"version\":\"0.28.1\"}},"
                + "{\"loader\":{\"version\":\"0.28.0\"}}"
                + "]";
        LocalMetadataCache cache = new LocalMetadataCache(
                tempFolder.newFolder("quilt"),
                (url, headers, cto, rto) -> new LocalMetadataCache.HttpResponse(200, quiltLoaders, "\"q1\"", null, null, "OK"),
                () -> FIXED_NOW_MS,
                ms -> {},
                "ZyroxTest/1.0.3",
                5_000,
                5_000,
                1
        );

        QuiltMetadataProvider quilt = new QuiltMetadataProvider(cache);
        FabricVersion[] loaders = quilt.getLoaderVersions("26.1", false);
        assertEquals(3, loaders.length);
        assertFalse(loaders[0].stable);
        assertTrue(loaders[1].stable);
        assertTrue(loaders[1].recommended);
        assertEquals("0.28.1", quilt.getRecommendedLoaderVersion("26.1").version);
        assertTrue(quilt.isCompatible("26.1", "0.28.1"));
        assertFalse(quilt.isCompatible("26.1", "9.9.9"));
    }
}
