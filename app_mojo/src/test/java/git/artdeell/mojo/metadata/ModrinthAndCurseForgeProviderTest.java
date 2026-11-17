package git.artdeell.mojo.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

import git.artdeell.mojo.modding.modpacks.models.CurseManifest;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.ModrinthIndex;

public class ModrinthAndCurseForgeProviderTest {
    private static final String VALID_SHA1 = "0123456789abcdef0123456789abcdef01234567";

    @Test
    public void modrinthProvider_parsesSearchAndDetails_selectsPrimaryFile_andVerifiesMrpack() throws Exception {
        ModrinthProvider provider = new ModrinthProvider();

        JsonArray hits = new JsonParser().parse("["
                + "{\"project_id\":\"AANobbMI\",\"project_type\":\"modpack\",\"title\":\"Fabulously Optimized\","
                + "\"description\":\"Fast pack\",\"icon_url\":\"https://cdn.modrinth.com/icon.png\",\"author\":\"robotkoer\",\"downloads\":1250000},"
                + "{\"project_id\":\"AANobbMI\",\"project_type\":\"modpack\",\"title\":\"Duplicate\"},"
                + "{\"project_id\":\"httpicon\",\"project_type\":\"mod\",\"title\":\"Sodium\",\"icon_url\":\"http://insecure.example/a.png\"}"
                + "]").getAsJsonArray();
        ModItem[] items = provider.parseSearchHits(hits);
        assertEquals(2, items.length);
        assertTrue(items[0].isModpack);
        assertEquals("https://cdn.modrinth.com/icon.png", items[0].imageUrl);
        assertFalse(items[1].isModpack);
        assertEquals("", items[1].imageUrl);

        // Version list: primary=true file is second; server-only paper release filtered out; 26.1 supported
        JsonArray versions = new JsonParser().parse("["
                + "{\"name\":\"FO 7.0\",\"loaders\":[\"fabric\"],\"game_versions\":[\"26.1-snapshot-1\",\"26.1\"],"
                + "\"files\":["
                + "{\"primary\":false,\"url\":\"https://cdn.modrinth.com/fo-sources.jar\",\"hashes\":{\"sha1\":\"bad\"}},"
                + "{\"primary\":true,\"url\":\"https://cdn.modrinth.com/fo-26.1.mrpack\",\"hashes\":{\"sha1\":\"" + VALID_SHA1 + "\"}}"
                + "]},"
                + "{\"name\":\"Server Plugin\",\"loaders\":[\"paper\",\"purpur\"],\"game_versions\":[\"26.1\"],"
                + "\"files\":[{\"primary\":true,\"url\":\"https://cdn.modrinth.com/plugin.jar\"}]}"
                + "]").getAsJsonArray();

        ModDetail detail = provider.parseModDetails(items[0], versions);
        assertNotNull(detail);
        assertEquals(1, detail.versionUrls.length);
        assertEquals("https://cdn.modrinth.com/fo-26.1.mrpack", detail.versionUrls[0]);
        assertEquals("26.1", detail.mcVersionNames[0]);
        assertEquals(VALID_SHA1, detail.versionHashes[0]);

        // Client env filtering & HTTPS multi-mirror selection
        ModrinthIndex.ModrinthIndexFile serverOnly = new ModrinthIndex.ModrinthIndexFile();
        serverOnly.path = "mods/server-only.jar";
        serverOnly.env = new ModrinthIndex.ModrinthIndexFile.ModrinthIndexFileEnv();
        serverOnly.env.client = "unsupported";
        serverOnly.env.server = "required";
        assertFalse(ModrinthProvider.shouldInstallFileOnClient(serverOnly));

        ModrinthIndex.ModrinthIndexFile clientMod = new ModrinthIndex.ModrinthIndexFile();
        clientMod.path = "mods/sodium.jar";
        clientMod.env = new ModrinthIndex.ModrinthIndexFile.ModrinthIndexFileEnv();
        clientMod.env.client = "required";
        clientMod.downloads = new String[]{"http://insecure.example/sodium.jar", "https://cdn.modrinth.com/sodium.jar"};
        assertTrue(ModrinthProvider.shouldInstallFileOnClient(clientMod));
        URL chosen = ModrinthProvider.selectVerifiedDownloadUrl(clientMod);
        assertEquals("https://cdn.modrinth.com/sodium.jar", chosen.toString());

        // Manifest resolution for NeoForge 26.1, Quilt, and Vanilla; rejection of unknown loader
        ModrinthIndex neoPack = new ModrinthIndex();
        neoPack.formatVersion = 1;
        neoPack.game = "minecraft";
        neoPack.dependencies = new HashMap<>();
        neoPack.dependencies.put("minecraft", "26.1");
        neoPack.dependencies.put("neoforge", "26.1.0.15");
        ModrinthProvider.ResolvedMrpackSpec neoSpec = provider.verifyAndResolveIndex(neoPack);
        assertEquals("26.1", neoSpec.minecraftVersion);
        assertEquals(ModrinthProvider.ModpackLoader.NEOFORGE, neoSpec.loader);
        assertEquals("26.1.0.15", neoSpec.loaderVersion);

        ModrinthIndex vanillaPack = new ModrinthIndex();
        vanillaPack.formatVersion = 1;
        vanillaPack.game = "minecraft";
        vanillaPack.dependencies = Collections.singletonMap("minecraft", "26.1.1");
        assertEquals(ModrinthProvider.ModpackLoader.VANILLA, provider.verifyAndResolveIndex(vanillaPack).loader);

        ModrinthIndex unsupportedLoader = new ModrinthIndex();
        unsupportedLoader.formatVersion = 1;
        unsupportedLoader.game = "minecraft";
        unsupportedLoader.dependencies = new HashMap<>();
        unsupportedLoader.dependencies.put("minecraft", "1.12.2");
        unsupportedLoader.dependencies.put("liteloader", "1.12.2-SNAPSHOT");
        try {
            provider.verifyAndResolveIndex(unsupportedLoader);
            fail("Expected unsupported loader rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("liteloader"));
        }
    }

    @Test
    public void curseForgeProvider_handlesNullDownloadUrlAndYearBased26Plus_andVerifiesAllLoaders() throws Exception {
        assertFalse(CurseForgeProvider.isApiKeyConfigured(null));
        assertFalse(CurseForgeProvider.isApiKeyConfigured("   "));
        assertFalse(CurseForgeProvider.isApiKeyConfigured("DUMMY"));
        assertTrue(CurseForgeProvider.isApiKeyConfigured("$2a$10$realApiKeyValue"));

        assertTrue(CurseForgeProvider.isMinecraftVersionTag("1.21.4"));
        assertTrue(CurseForgeProvider.isMinecraftVersionTag("26.1"));
        assertTrue(CurseForgeProvider.isMinecraftVersionTag("26.1.1"));
        assertTrue(CurseForgeProvider.isMinecraftVersionTag("26.1-snapshot-1"));
        assertTrue(CurseForgeProvider.isMinecraftVersionTag("26w14a"));
        assertFalse(CurseForgeProvider.isMinecraftVersionTag("NeoForge"));
        assertFalse(CurseForgeProvider.isMinecraftVersionTag("Java 25"));

        CurseForgeProvider provider = new CurseForgeProvider();
        JsonArray searchData = new JsonParser().parse("["
                + "{\"id\":100,\"name\":\"ATM 11\",\"summary\":\"NeoForge 26.1 pack\",\"allowModDistribution\":true,\"logo\":null},"
                + "{\"id\":200,\"name\":\"Blocked Pack\",\"allowModDistribution\":false}"
                + "]").getAsJsonArray();
        ModItem[] found = provider.parseSearchItems(searchData, true);
        assertEquals(1, found.length);
        assertEquals("ATM 11", found[0].title);
        assertEquals("", found[0].imageUrl);

        JsonObject nullUrlFile = new JsonParser().parse("{"
                + "\"id\":5432109,"
                + "\"displayName\":\"ATM11-1.0\","
                + "\"fileName\":\"ATM11 1.0.zip\","
                + "\"downloadUrl\":null,"
                + "\"isServerPack\":false,"
                + "\"gameVersions\":[\"Client\",\"NeoForge\",\"Java 25\",\"26.1\"],"
                + "\"hashes\":[{\"algo\":2,\"value\":\"md5\"},{\"algo\":1,\"value\":\"" + VALID_SHA1.toUpperCase() + "\"}]"
                + "}").getAsJsonObject();
        JsonObject serverPack = new JsonParser().parse("{"
                + "\"id\":5432110,\"fileName\":\"server.zip\",\"isServerPack\":true,\"gameVersions\":[\"26.1\"]"
                + "}").getAsJsonObject();

        ModDetail detail = provider.parseModDetails(found[0], Arrays.asList(nullUrlFile, serverPack));
        assertEquals(1, detail.versionUrls.length);
        assertEquals("https://edge.forgecdn.net/files/5432/109/ATM11%201.0.zip", detail.versionUrls[0]);
        assertEquals("26.1", detail.mcVersionNames[0]);
        assertEquals(VALID_SHA1, detail.versionHashes[0]);

        // Verify Quilt, NeoForge, Fabric, Forge manifests — and reject unknown loaders
        CurseManifest quiltManifest = buildManifest("26.1", "forge-60.0.1", false, "quilt-0.28.1", true);
        CurseForgeProvider.ResolvedCurseSpec quiltSpec = provider.verifyAndResolveManifest(quiltManifest);
        assertEquals(CurseForgeProvider.CurseLoaderType.QUILT, quiltSpec.loaderType);
        assertEquals("0.28.1", quiltSpec.loaderVersion);
        assertEquals("26.1", quiltSpec.minecraftVersion);

        CurseManifest neoManifest = buildManifest("26.1.1", "neoforge-26.1.1.5", true, null, false);
        assertEquals(CurseForgeProvider.CurseLoaderType.NEOFORGE,
                provider.verifyAndResolveManifest(neoManifest).loaderType);

        CurseManifest unknownManifest = buildManifest("1.21.4", "jumploader-1.0.5", true, null, false);
        try {
            provider.verifyAndResolveManifest(unknownManifest);
            fail("Expected unsupported CurseForge loader rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("jumploader"));
        }
    }

    private static CurseManifest buildManifest(String mcVer, String id1, boolean primary1,
                                               String id2, boolean primary2) {
        CurseManifest m = new CurseManifest();
        m.manifestType = "minecraftModpack";
        m.manifestVersion = 1;
        m.minecraft = new CurseManifest.CurseMinecraft();
        m.minecraft.version = mcVer;
        CurseManifest.CurseModLoader l1 = new CurseManifest.CurseModLoader();
        l1.id = id1;
        l1.primary = primary1;
        if (id2 != null) {
            CurseManifest.CurseModLoader l2 = new CurseManifest.CurseModLoader();
            l2.id = id2;
            l2.primary = primary2;
            m.minecraft.modLoaders = new CurseManifest.CurseModLoader[]{l1, l2};
        } else {
            m.minecraft.modLoaders = new CurseManifest.CurseModLoader[]{l1};
        }
        return m;
    }
}
