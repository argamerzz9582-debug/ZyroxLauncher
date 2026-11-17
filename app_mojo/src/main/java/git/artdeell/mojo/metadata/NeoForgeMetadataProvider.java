package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.utils.DownloadUtils;

/**
 * Official NeoForge metadata provider backed by
 * {@code https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml}.
 *
 * <p>Handles BOTH official NeoForge versioning eras without hardcoded latest versions:
 * <ul>
 *   <li><b>1.20.2 – 1.21.x era</b> ({@code major < 26}): {@code <mcMinor>.<mcPatch>.<build>[-beta]}
 *       (e.g. {@code 20.4.251 -> 1.20.4}, {@code 21.0.167 -> 1.21}, {@code 21.1.215 -> 1.21.1},
 *       {@code 21.11.38-beta -> 1.21.11}).</li>
 *   <li><b>2026+ year-based Minecraft era</b> ({@code major >= 26}):
 *       {@code <year>.<drop>.<hotfix>.<build>[-beta|+snapshot]} (e.g. {@code 26.1.0.12 -> 26.1},
 *       {@code 26.1.1.5 -> 26.1.1}, {@code 26.2.0.3-beta -> 26.2}, {@code 27.3.2.8 -> 27.3.2},
 *       plus 3-part {@code 26.1.4 -> 26.1}).</li>
 * </ul>
 */
public class NeoForgeMetadataProvider {
    public static final String OFFICIAL_NEOFORGE_METADATA_URL =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    public static final String OFFICIAL_NEOFORGE_INSTALLER_URL_FMT =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/%1$s/neoforge-%1$s-installer.jar";
    public static final String CACHE_KEY_VERSIONS = "neoforge_versions";

    /** First year-based Minecraft major version introduced by Mojang in 2026. */
    private static final int FIRST_YEAR_BASED_MC_MAJOR = 26;
    private static final long NEOFORGE_REVALIDATE_COOLDOWN_MS = 5 * 60_000L;

    private final LocalMetadataCache mCache;

    public NeoForgeMetadataProvider(@NonNull LocalMetadataCache cache) {
        mCache = cache;
    }

    @NonNull
    public List<String> getVersions(boolean forceRefresh) throws IOException {
        try {
            return mCache.sync(
                    CACHE_KEY_VERSIONS,
                    OFFICIAL_NEOFORGE_METADATA_URL,
                    forceRefresh,
                    NEOFORGE_REVALIDATE_COOLDOWN_MS,
                    NeoForgeMetadataProvider::parseNeoForgeMavenMetadata
            ).data;
        } catch (DownloadUtils.ParseException e) {
            throw new IOException("Invalid NeoForge metadata response", e);
        }
    }

    /**
     * Returns the recommended (latest stable, non-beta/alpha) NeoForge version compatible with
     * {@code minecraftVersion}, falling back to the newest published build if only beta builds exist.
     */
    @Nullable
    public String getRecommendedVersion(@NonNull String minecraftVersion, boolean forceRefresh) {
        try {
            List<String> allVersions = getVersions(forceRefresh);
            return selectRecommendedVersion(allVersions, minecraftVersion);
        } catch (IOException ignored) {
            return null;
        }
    }

    @Nullable
    public static String selectRecommendedVersion(@Nullable List<String> ascendingCatalog,
                                                  @NonNull String minecraftVersion) {
        if (ascendingCatalog == null || ascendingCatalog.isEmpty()) return null;
        String targetMc = minecraftVersion.trim();
        String latestAny = null;
        // NeoForge maven-metadata.xml is ordered oldest -> newest; walk from end for newest first.
        for (int i = ascendingCatalog.size() - 1; i >= 0; i--) {
            String v = ascendingCatalog.get(i);
            if (shouldSkipVersion(v)) continue;
            if (!targetMc.equals(extractMinecraftVersion(v))) continue;
            if (latestAny == null) {
                latestAny = v;
            }
            if (isStableVersion(v)) {
                return v;
            }
        }
        return latestAny;
    }

    /**
     * Resolves and verifies that {@code neoForgeVersion} exists in official NeoForge metadata AND
     * targets {@code minecraftVersion}. Never returns an incompatible loader version.
     */
    @Nullable
    public String resolveCompatibleFullVersion(@NonNull String minecraftVersion,
                                               @NonNull String neoForgeVersion) throws IOException {
        List<String> versions = getVersions(false);
        String resolved = findCompatibleFullVersion(versions, minecraftVersion, neoForgeVersion);
        if (resolved == null) {
            versions = getVersions(true);
            resolved = findCompatibleFullVersion(versions, minecraftVersion, neoForgeVersion);
        }
        return resolved;
    }

    @Nullable
    public static String findCompatibleFullVersion(@Nullable List<String> catalog,
                                                   @NonNull String minecraftVersion,
                                                   @NonNull String neoForgeVersion) {
        if (catalog == null || catalog.isEmpty()) return null;
        String cleanMc = minecraftVersion.trim();
        String cleanLoader = neoForgeVersion.trim();
        if (cleanMc.isEmpty() || cleanLoader.isEmpty()) return null;

        // Pass 1: exact version match verified against target Minecraft version
        for (String candidate : catalog) {
            if (cleanLoader.equals(candidate) && cleanMc.equals(extractMinecraftVersion(candidate))) {
                return candidate;
            }
        }
        // Pass 2: match explicit qualifier (e.g. modpack says "21.1.50" while Maven lists "21.1.50-beta")
        // Requires '-' or '+' separator so "21.1.5" NEVER matches "21.1.50".
        for (String candidate : catalog) {
            if (candidate == null) continue;
            if ((candidate.startsWith(cleanLoader + "-") || candidate.startsWith(cleanLoader + "+"))
                    && cleanMc.equals(extractMinecraftVersion(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    @NonNull
    public String fetchInstallerSha1(@NonNull String installerUrl) throws IOException {
        String raw = mCache.fetchDirect(installerUrl + ".sha1");
        String sha1 = ForgeMetadataProvider.extractSha1Hash(raw);
        if (sha1 == null) {
            throw new IOException("Invalid SHA-1 checksum received for " + installerUrl);
        }
        return sha1;
    }

    public static boolean isStableVersion(@Nullable String neoVersion) {
        if (neoVersion == null || neoVersion.isEmpty()) return false;
        String lower = neoVersion.toLowerCase(Locale.ROOT);
        return !lower.contains("-beta")
                && !lower.contains("-alpha")
                && !lower.contains("snapshot")
                && !lower.contains("+");
    }

    public static boolean shouldSkipVersion(@Nullable String neoVersion) {
        if (neoVersion == null) return true;
        String trimmed = neoVersion.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("0")) return true;
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) return true;
        return extractMinecraftVersion(trimmed) == null;
    }

    /**
     * Maps a NeoForge artifact version to its exact target Minecraft release/snapshot ID, or
     * {@code null} when the version string is malformed or an experimental {@code 0.*} build.
     */
    @Nullable
    public static String extractMinecraftVersion(@Nullable String neoVersion) {
        if (neoVersion == null) return null;
        String trimmed = neoVersion.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("0")) return null;
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) return null;

        // Strip Maven pre-release / snapshot build qualifiers (e.g. "-beta", "-alpha.1", "+snapshot-1")
        String core = trimmed;
        int plusIdx = core.indexOf('+');
        String buildMeta = null;
        if (plusIdx > 0) {
            buildMeta = core.substring(plusIdx + 1);
            core = core.substring(0, plusIdx);
        }
        int dashIdx = core.indexOf('-');
        if (dashIdx > 0) {
            core = core.substring(0, dashIdx);
        }

        String[] parts = core.split("\\.");
        if (parts.length < 3) return null;

        final int p0;
        final int p1;
        final int p2;
        try {
            p0 = Integer.parseInt(parts[0]);
            p1 = Integer.parseInt(parts[1]);
            p2 = Integer.parseInt(parts[2]);
            if (parts.length >= 4) {
                // Ensure the 4th segment is also a valid non-negative build integer
                if (Integer.parseInt(parts[3]) < 0) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (p0 <= 0 || p1 < 0 || p2 < 0) return null;

        if (p0 >= FIRST_YEAR_BASED_MC_MAJOR) {
            // 2026+ year-based Minecraft numbering:
            //   4-segment: <year>.<drop>.<hotfix>.<neobuild> -> "<year>.<drop>" (if hotfix==0) else "<year>.<drop>.<hotfix>"
            //   3-segment: <year>.<drop>.<neobuild>          -> "<year>.<drop>"
            String base;
            if (parts.length >= 4 && p2 > 0) {
                base = p0 + "." + p1 + "." + p2;
            } else {
                base = p0 + "." + p1;
            }
            if (buildMeta != null && !buildMeta.isEmpty()) {
                if (buildMeta.startsWith(base + "-")) {
                    return buildMeta;
                }
                if (buildMeta.startsWith("snapshot-") || buildMeta.startsWith("pre-") || buildMeta.startsWith("rc-")) {
                    return base + "-" + buildMeta;
                }
            }
            return base;
        }

        // Classic 1.20.x – 1.21.x era: <mcMinor>.<mcPatch>.<neobuild> -> "1.<mcMinor>" or "1.<mcMinor>.<mcPatch>"
        if (p1 == 0) {
            return "1." + p0;
        }
        return "1." + p0 + "." + p1;
    }

    @NonNull
    public static List<String> parseNeoForgeMavenMetadata(@NonNull String rawXml)
            throws DownloadUtils.ParseException {
        List<String> rawVersions = ForgeMetadataProvider.parseMavenMetadataVersions(rawXml);
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>(rawVersions.size());
        for (String v : rawVersions) {
            if (v == null) continue;
            String trimmed = v.trim();
            if (!trimmed.isEmpty() && !trimmed.contains("/") && !trimmed.contains("\\") && !trimmed.contains("..")) {
                deduplicated.add(trimmed);
            }
        }
        if (deduplicated.isEmpty()) {
            throw new DownloadUtils.ParseException(
                    new IllegalArgumentException("No valid NeoForge versions found in maven-metadata.xml"));
        }
        return Collections.unmodifiableList(new ArrayList<>(deduplicated));
    }
}
