package git.artdeell.mojo.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import git.artdeell.mojo.utils.DownloadUtils;

public class ForgeAndNeoForgeMetadataProviderTest {

    /** Deterministic clock: cache freshness must never depend on the CI machine's wall time. */
    private static final long FIXED_NOW_MS = 1_767_225_600_000L; // 2026-01-01T00:00:00Z
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void forgeProvider_parsesMavenAndPromotions_preventsPrefixCollision_andBlocksXxe() throws Exception {
        String forgeXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<metadata><versioning><versions>"
                + "<version>26.1-60.0.2</version>"
                + "<version>26.1-60.0.1</version>"
                + "<version>1.21.4-54.1.0</version>"
                + "<version>1.20.1-47.3.0</version>"
                + "<version>1.20.1-47.1.30</version>"
                + "<version>1.20.1-47.1.3</version>"
                + "<version>1.7.10-10.13.4.1614-1.7.10</version>"
                + "<version>malformed_without_dash</version>"
                + "</versions></versioning></metadata>";
        String promosJson = "{\"promos\":{"
                + "\"26.1-recommended\":\"60.0.1\","
                + "\"26.1-latest\":\"60.0.2\","
                + "\"1.20.1-recommended\":\"47.3.0\""
                + "}}";
        String shaBody = "A94A8FE5CCB19BA61C4C0873D391E987982FBBD3  forge-26.1-60.0.1-installer.jar\n";

        LocalMetadataCache cache = new LocalMetadataCache(
                tempFolder.newFolder("forge"),
                (url, headers, cto, rto) -> {
                    if (url.equals(ForgeMetadataProvider.OFFICIAL_FORGE_METADATA_URL)) {
                        return new LocalMetadataCache.HttpResponse(200, forgeXml, "\"fx1\"", null, null, "OK");
                    }
                    if (url.equals(ForgeMetadataProvider.OFFICIAL_FORGE_PROMOTIONS_URL)) {
                        return new LocalMetadataCache.HttpResponse(200, promosJson, "\"fp1\"", null, null, "OK");
                    }
                    if (url.endsWith(".sha1")) {
                        return new LocalMetadataCache.HttpResponse(200, shaBody, null, null, null, "OK");
                    }
                    return new LocalMetadataCache.HttpResponse(404, null, null, null, null, "Not Found");
                },
                () -> FIXED_NOW_MS,
                ms -> {},
                "ZyroxTest/1.0.3",
                5_000,
                5_000,
                1
        );

        ForgeMetadataProvider forge = new ForgeMetadataProvider(cache);
        List<String> versions = forge.getVersions(false);
        assertEquals(7, versions.size());
        assertEquals("26.1", ForgeMetadataProvider.extractMinecraftVersion("26.1-60.0.2"));
        assertEquals("1.7.10", ForgeMetadataProvider.extractMinecraftVersion("1.7.10-10.13.4.1614-1.7.10"));
        assertNull(ForgeMetadataProvider.extractMinecraftVersion("no_dash"));

        // Exact matching: 47.1.3 must NOT match 47.1.30
        assertEquals("1.20.1-47.1.3", ForgeMetadataProvider.findCompatibleFullVersion(versions, "1.20.1", "47.1.3"));
        assertNull(ForgeMetadataProvider.findCompatibleFullVersion(
                Arrays.asList("1.20.1-47.1.30", "1.20.1-47.3.0"), "1.20.1", "47.1.3"));
        // Legacy suffixed Forge version resolves accurately
        assertEquals("1.7.10-10.13.4.1614-1.7.10",
                ForgeMetadataProvider.findCompatibleFullVersion(versions, "1.7.10", "10.13.4.1614"));
        // Incompatible MC/Forge combination returns null
        assertNull(ForgeMetadataProvider.findCompatibleFullVersion(versions, "26.1", "47.3.0"));

        // Official recommended loader discovery
        Map<String, String> recMap = forge.getRecommendedLoaderMap(false);
        assertEquals("60.0.1", recMap.get("26.1"));
        assertEquals("26.1-60.0.1", forge.getRecommendedFullVersion("26.1", false));

        // SHA-1 extraction handles GNU coreutils format + uppercase normalization
        assertEquals("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
                forge.fetchInstallerSha1("https://maven.minecraftforge.net/net/minecraftforge/forge/26.1-60.0.1/forge-26.1-60.0.1-installer.jar"));
        assertNull(ForgeMetadataProvider.extractSha1Hash("not-a-valid-sha1"));

        // XXE payload must be strictly rejected
        String xxePayload = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE lolz [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<metadata><version>&xxe;</version></metadata>";
        try {
            ForgeMetadataProvider.parseForgeMavenMetadata(xxePayload);
            fail("Expected XXE DOCTYPE payload to be rejected");
        } catch (DownloadUtils.ParseException expected) {
            // Expected
        }
    }

    @Test
    public void neoForgeProvider_mapsClassic120_121AndYearBased26PlusAndSnapshots_andVerifiesCompatibility() throws Exception {
        // Classic 1.20.x – 1.21.x era
        assertEquals("1.20.2", NeoForgeMetadataProvider.extractMinecraftVersion("20.2.88"));
        assertEquals("1.20.4", NeoForgeMetadataProvider.extractMinecraftVersion("20.4.251"));
        assertEquals("1.21", NeoForgeMetadataProvider.extractMinecraftVersion("21.0.167"));
        assertEquals("1.21.1", NeoForgeMetadataProvider.extractMinecraftVersion("21.1.215"));
        assertEquals("1.21.4", NeoForgeMetadataProvider.extractMinecraftVersion("21.4.88-beta"));
        assertEquals("1.21.11", NeoForgeMetadataProvider.extractMinecraftVersion("21.11.38-beta"));

        // 2026+ year-based Minecraft numbering era (major >= 26)
        assertEquals("26.1", NeoForgeMetadataProvider.extractMinecraftVersion("26.1.0.1-beta"));
        assertEquals("26.1", NeoForgeMetadataProvider.extractMinecraftVersion("26.1.0.15"));
        assertEquals("26.1", NeoForgeMetadataProvider.extractMinecraftVersion("26.1.4"));
        assertEquals("26.1.1", NeoForgeMetadataProvider.extractMinecraftVersion("26.1.1.5"));
        assertEquals("26.2", NeoForgeMetadataProvider.extractMinecraftVersion("26.2.0.3-beta"));
        assertEquals("27.3.2", NeoForgeMetadataProvider.extractMinecraftVersion("27.3.2.8"));

        // Official NeoForge 26.x+ snapshot / pre-release / rc build metadata
        assertEquals("26.1-snapshot-1",
                NeoForgeMetadataProvider.extractMinecraftVersion("26.1.0.0-alpha.1+26.1-snapshot-1"));
        assertEquals("26.1-snapshot-2",
                NeoForgeMetadataProvider.extractMinecraftVersion("26.1.0.0-alpha.4+snapshot-2"));
        assertEquals("26.1.1-pre-1",
                NeoForgeMetadataProvider.extractMinecraftVersion("26.1.1.0-alpha.2+pre-1"));
        assertEquals("26.2-rc-1",
                NeoForgeMetadataProvider.extractMinecraftVersion("26.2.0.0-alpha.1+rc-1"));

        // Experimental April Fools 0.* versions skipped cleanly
        assertNull(NeoForgeMetadataProvider.extractMinecraftVersion("0.25w14craftmine.3-beta"));
        assertTrue(NeoForgeMetadataProvider.shouldSkipVersion("0.25w14craftmine.3-beta"));
        assertFalse(NeoForgeMetadataProvider.shouldSkipVersion("26.1.0.15"));

        // Recommended & compatibility checks
        List<String> catalog = Arrays.asList(
                "0.25w14craftmine.3-beta",
                "21.1.50",
                "21.1.215",
                "26.1.0.12",
                "26.1.0.15",
                "26.1.0.16-beta",
                "26.2.0.1-beta",
                "26.2.0.2-beta"
        );
        // Prefers newest stable over newer beta
        assertEquals("26.1.0.15", NeoForgeMetadataProvider.selectRecommendedVersion(catalog, "26.1"));
        // Falls back to newest beta when no stable build exists yet
        assertEquals("26.2.0.2-beta", NeoForgeMetadataProvider.selectRecommendedVersion(catalog, "26.2"));
        assertNull(NeoForgeMetadataProvider.selectRecommendedVersion(catalog, "29.1"));

        // Exact & qualifier matching without prefix collision or cross-MC mismatch
        assertEquals("26.1.0.15", NeoForgeMetadataProvider.findCompatibleFullVersion(catalog, "26.1", "26.1.0.15"));
        assertEquals("26.2.0.2-beta", NeoForgeMetadataProvider.findCompatibleFullVersion(catalog, "26.2", "26.2.0.2"));
        assertNull(NeoForgeMetadataProvider.findCompatibleFullVersion(catalog, "1.21.1", "21.1.5"));
        assertNull(NeoForgeMetadataProvider.findCompatibleFullVersion(catalog, "26.1", "21.1.215"));
    }
}
