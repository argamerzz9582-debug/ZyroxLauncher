package git.artdeell.mojo.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.utils.maven.MavenName;
import git.artdeell.mojo.value.LibrarySubstitution;
import git.artdeell.mojo.value.SubstitutionMap;

public class VersionMetadataRepositoryAndSubstitutionTest {

    /** Deterministic clock: cache freshness must never depend on the CI machine's wall time. */
    private static final long FIXED_NOW_MS = 1_767_225_600_000L; // 2026-01-01T00:00:00Z
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void repository_twoPhaseStartupRefreshOfflineAndLoaderCompatibility_workEndToEnd() throws Exception {
        String manifestV1 = "{\"latest\":{\"release\":\"26.1\"},\"versions\":["
                + "{\"id\":\"26.1\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/26.1.json\"}]}";
        String manifestV2 = "{\"latest\":{\"release\":\"26.2\"},\"versions\":["
                + "{\"id\":\"26.2\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/26.2.json\"},"
                + "{\"id\":\"26.1\",\"type\":\"release\",\"url\":\"https://piston-meta.mojang.com/v1/26.1.json\"}]}";
        String neoXml = "<metadata><versioning><versions>"
                + "<version>26.1.0.15</version><version>26.2.0.1</version>"
                + "</versions></versioning></metadata>";
        String fabricLoaders = "[{\"loader\":{\"version\":\"0.16.12\",\"stable\":true}}]";

        AtomicReference<String> activeManifest = new AtomicReference<>(manifestV1);
        AtomicBoolean offline = new AtomicBoolean(false);

        LocalMetadataCache cache = new LocalMetadataCache(
                tempFolder.newFolder("repo_cache"),
                (url, headers, cto, rto) -> {
                    if (offline.get()) {
                        throw new IOException("Device is offline");
                    }
                    if (url.equals(MojangVersionProvider.OFFICIAL_MANIFEST_V2_URL)) {
                        return new LocalMetadataCache.HttpResponse(
                                200, activeManifest.get(), "\"m-" + activeManifest.get().length() + "\"", null, null, "OK");
                    }
                    if (url.equals(NeoForgeMetadataProvider.OFFICIAL_NEOFORGE_METADATA_URL)) {
                        return new LocalMetadataCache.HttpResponse(200, neoXml, "\"n1\"", null, null, "OK");
                    }
                    if (url.contains("meta.fabricmc.net/v2/versions/loader/26.2")) {
                        return new LocalMetadataCache.HttpResponse(200, fabricLoaders, "\"fl\"", null, null, "OK");
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

        // Synchronous executor keeps startup & manual refresh tests completely deterministic
        VersionMetadataRepository repo = new VersionMetadataRepository(
                cache, () -> MojangVersionProvider.OFFICIAL_MANIFEST_V2_URL, Runnable::run);

        // 1. First startup with empty cache fetches v1 (26.1)
        List<String> startupReleases = new ArrayList<>();
        repo.syncMinecraftVersionsOnStartup((catalog, source, changed) ->
                startupReleases.add(MojangVersionProvider.findLatestByType(catalog, "release")));
        assertEquals(1, startupReleases.size());
        assertEquals("26.1", startupReleases.get(0));

        // 2. Second startup when Mojang released 26.2: emits cached 26.1 first, then updates to 26.2
        activeManifest.set(manifestV2);
        startupReleases.clear();
        repo.syncMinecraftVersionsOnStartup((catalog, source, changed) ->
                startupReleases.add(MojangVersionProvider.findLatestByType(catalog, "release")));
        assertEquals(2, startupReleases.size());
        assertEquals("26.1", startupReleases.get(0));
        assertEquals("26.2", startupReleases.get(1));

        // 3. Offline manual refresh preserves valid cached 26.2 catalog without crashing or duplicating
        offline.set(true);
        AtomicReference<LocalMetadataCache.SyncSource> offlineSource = new AtomicReference<>();
        repo.refreshMinecraftVersions((catalog, source, changed) -> {
            offlineSource.set(source);
            assertEquals(2, catalog.versions.length);
            assertEquals("26.2", catalog.versions[0].id);
        });
        assertEquals(LocalMetadataCache.SyncSource.OFFLINE_CACHE_FALLBACK, offlineSource.get());

        // 4. Online cross-loader compatibility verification for 26.2
        offline.set(false);
        assertTrue(repo.verifyLoaderCompatibility(VersionMetadataRepository.LoaderType.NEOFORGE, "26.2", "26.2.0.1"));
        assertFalse(repo.verifyLoaderCompatibility(VersionMetadataRepository.LoaderType.NEOFORGE, "26.2", "26.1.0.15"));
        assertTrue(repo.verifyLoaderCompatibility(VersionMetadataRepository.LoaderType.FABRIC, "26.2", "0.16.12"));
        assertFalse(repo.verifyLoaderCompatibility(VersionMetadataRepository.LoaderType.FABRIC, "26.2", "0.99.0"));

        // 5. Clear cache purges local memory & disk entries cleanly
        assertTrue(repo.clearAllCaches());
        assertNull(repo.getCurrentCatalog());
    }

    @Test
    public void substitutionMap_futureLwjglVersions_skipsDesktopNativesAndSelectsBestAndroidModule() {
        SubstitutionMap map = new SubstitutionMap();
        map.libraries = new SubstitutionMap.LibraryMap();

        LibrarySubstitution sub333 = new LibrarySubstitution();
        sub333.name = MavenName.parse("org.lwjgl:lwjgl:3.3.3-android");
        LibrarySubstitution sub341 = new LibrarySubstitution();
        sub341.name = MavenName.parse("org.lwjgl:lwjgl:3.4.1-android");
        LibrarySubstitution sub343 = new LibrarySubstitution();
        sub343.name = MavenName.parse("org.lwjgl:lwjgl:3.4.3-android");

        map.libraries.put(MavenName.parse("org.lwjgl:lwjgl:3.3.3"), sub333);
        map.libraries.put(MavenName.parse("org.lwjgl:lwjgl:3.4.1"), sub341);
        map.libraries.put(MavenName.parse("org.lwjgl:lwjgl:3.4.3"), sub343);

        // Exact match preserved
        assertSame(sub341, map.findSubstitution(MavenName.parse("org.lwjgl:lwjgl:3.4.1")));

        // Future LWJGL patch in same 3.4.x minor picks highest 3.4.x Android build (3.4.3)
        assertSame(sub343, map.findSubstitution(MavenName.parse("org.lwjgl:lwjgl:3.4.5")));
        // Future LWJGL minor in same 3.x major (e.g. 3.5.0 for MC 27.x) picks highest 3.x Android build (3.4.3)
        assertSame(sub343, map.findSubstitution(MavenName.parse("org.lwjgl:lwjgl:3.5.0")));
        // Future LWJGL 3.3.6 picks highest 3.3.x Android build (3.3.3)
        assertSame(sub333, map.findSubstitution(MavenName.parse("org.lwjgl:lwjgl:3.3.6")));

        // Desktop native classifier jars are strictly skipped for any future LWJGL version
        LibrarySubstitution linuxNative = map.findSubstitution(MavenName.parse("org.lwjgl:lwjgl:3.5.0:natives-linux"));
        assertNotNull(linuxNative);
        assertTrue(linuxNative.skip);

        LibrarySubstitution winArmNative = map.findSubstitution(
                MavenName.parse("org.lwjgl:lwjgl-glfw:3.5.0:natives-windows-arm64"));
        assertNotNull(winArmNative);
        assertTrue(winArmNative.skip);

        LibrarySubstitution macModuleNative = map.findSubstitution(
                MavenName.parse("org.lwjgl:lwjgl-jemalloc-natives-macos:3.5.0"));
        assertNotNull(macModuleNative);
        assertTrue(macModuleNative.skip);

        // Unrelated libraries are untouched
        assertNull(map.findSubstitution(MavenName.parse("com.google.guava:guava:33.3.1-jre")));
    }
}
