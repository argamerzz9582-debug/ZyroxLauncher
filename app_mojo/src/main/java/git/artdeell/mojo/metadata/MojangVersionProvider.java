package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.utils.DownloadUtils;

/**
 * Official Mojang Minecraft version catalog provider backed by
 * {@code https://piston-meta.mojang.com/mc/game/version_manifest_v2.json}.
 *
 * <p>Supports every official Minecraft channel ({@code release}, {@code snapshot},
 * {@code old_beta}, {@code old_alpha}, and any future Mojang category) across classic {@code 1.x.y}
 * and year-based {@code 26.x+} numbering without a single hardcoded latest version string.
 */
public class MojangVersionProvider {
    public static final String OFFICIAL_MANIFEST_V2_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String CACHE_KEY = "version_list";

    public static final String TYPE_RELEASE = "release";
    public static final String TYPE_SNAPSHOT = "snapshot";
    public static final String TYPE_OLD_BETA = "old_beta";
    public static final String TYPE_OLD_ALPHA = "old_alpha";

    private static final Gson GSON = new Gson();

    public interface EndpointResolver {
        @NonNull
        String resolveManifestUrl() throws IOException;
    }

    private final LocalMetadataCache mCache;
    private final EndpointResolver mEndpointResolver;

    public MojangVersionProvider(@NonNull LocalMetadataCache cache,
                                 @NonNull EndpointResolver endpointResolver) {
        mCache = cache;
        mEndpointResolver = endpointResolver;
    }

    public MojangVersionProvider(@NonNull LocalMetadataCache cache) {
        this(cache, () -> OFFICIAL_MANIFEST_V2_URL);
    }

    /** Loads cached Mojang manifest immediately from local disk without touching the network. */
    @Nullable
    public JVersionList getCachedManifest() {
        return mCache.readCached(CACHE_KEY, MojangVersionProvider::parseAndValidateManifest);
    }

    /**
     * Synchronizes the Mojang version manifest using conditional HTTP validation, falling back to
     * official {@code piston-meta.mojang.com} if a custom mirror fails, and to local cache if offline.
     */
    @NonNull
    public LocalMetadataCache.SyncResult<JVersionList> syncManifest(boolean forceRefresh)
            throws IOException, DownloadUtils.ParseException {
        String primaryUrl = OFFICIAL_MANIFEST_V2_URL;
        try {
            String resolved = mEndpointResolver.resolveManifestUrl();
            if (resolved != null && !resolved.trim().isEmpty()) {
                primaryUrl = resolved.trim();
            }
        } catch (IOException ignored) {
            primaryUrl = OFFICIAL_MANIFEST_V2_URL;
        }

        try {
            return mCache.sync(
                    CACHE_KEY,
                    primaryUrl,
                    forceRefresh,
                    LocalMetadataCache.DEFAULT_REVALIDATE_COOLDOWN_MS,
                    MojangVersionProvider::parseAndValidateManifest
            );
        } catch (IOException | DownloadUtils.ParseException primaryError) {
            // If a third-party mirror was active and failed with no cache, fall back to official Mojang.
            if (!OFFICIAL_MANIFEST_V2_URL.equals(primaryUrl)) {
                return mCache.sync(
                        CACHE_KEY,
                        OFFICIAL_MANIFEST_V2_URL,
                        true,
                        LocalMetadataCache.DEFAULT_REVALIDATE_COOLDOWN_MS,
                        MojangVersionProvider::parseAndValidateManifest
                );
            }
            throw primaryError;
        }
    }

    /**
     * Parses, validates, normalizes, and deduplicates an official Mojang {@code version_manifest_v2.json}.
     * Preserves Mojang's official newest-first chronological ordering while eliminating duplicates and
     * malformed/non-HTTPS records.
     */
    @NonNull
    public static JVersionList parseAndValidateManifest(@NonNull String rawJson)
            throws DownloadUtils.ParseException {
        if (rawJson.trim().isEmpty()) {
            throw new DownloadUtils.ParseException(new IllegalArgumentException("Empty version manifest"));
        }
        final JVersionList parsed;
        try {
            parsed = GSON.fromJson(rawJson, JVersionList.class);
        } catch (JsonSyntaxException e) {
            throw new DownloadUtils.ParseException(e);
        }
        if (parsed == null || parsed.versions == null || parsed.versions.length == 0) {
            throw new DownloadUtils.ParseException(
                    new IllegalArgumentException("Mojang manifest contains no version entries"));
        }

        // Deduplicate by exact version ID while preserving newest-first manifest order.
        LinkedHashMap<String, JVersionList.Version> uniqueVersions =
                new LinkedHashMap<>(parsed.versions.length);
        String firstReleaseId = null;
        String firstSnapshotId = null;

        for (JVersionList.Version entry : parsed.versions) {
            if (!isValidManifestVersionEntry(entry)) continue;
            entry.id = entry.id.trim();
            entry.type = entry.type.trim();
            entry.url = entry.url.trim();
            if (entry.sha1 != null) {
                String trimmedSha = entry.sha1.trim().toLowerCase(java.util.Locale.ROOT);
                entry.sha1 = isValidSha1(trimmedSha) ? trimmedSha : null;
            }
            if (uniqueVersions.containsKey(entry.id)) {
                continue;
            }
            uniqueVersions.put(entry.id, entry);
            if (firstReleaseId == null && TYPE_RELEASE.equals(entry.type)) {
                firstReleaseId = entry.id;
            }
            if (firstSnapshotId == null && TYPE_SNAPSHOT.equals(entry.type)) {
                firstSnapshotId = entry.id;
            }
        }

        if (uniqueVersions.isEmpty()) {
            throw new DownloadUtils.ParseException(
                    new IllegalArgumentException("No valid HTTPS version entries found in manifest"));
        }

        JVersionList normalized = new JVersionList();
        normalized.versions = uniqueVersions.values().toArray(new JVersionList.Version[0]);

        Map<String, String> latest = new HashMap<>();
        if (parsed.latest != null) {
            for (Map.Entry<String, String> item : parsed.latest.entrySet()) {
                if (item.getKey() != null && item.getValue() != null && !item.getValue().trim().isEmpty()) {
                    String candidateId = item.getValue().trim();
                    if (uniqueVersions.containsKey(candidateId)) {
                        latest.put(item.getKey().trim(), candidateId);
                    }
                }
            }
        }
        // Derive latest release/snapshot directly from catalog if absent or stale — never hardcode.
        if (!latest.containsKey(TYPE_RELEASE) && firstReleaseId != null) {
            latest.put(TYPE_RELEASE, firstReleaseId);
        }
        if (!latest.containsKey(TYPE_SNAPSHOT)) {
            String fallbackSnapshot = firstSnapshotId != null
                    ? firstSnapshotId
                    : normalized.versions[0].id;
            latest.put(TYPE_SNAPSHOT, fallbackSnapshot);
        }
        normalized.latest = latest;
        return normalized;
    }

    /**
     * Resolves symbolic instance version IDs ({@code latest-release}, {@code latest-snapshot})
     * dynamically against the synced Mojang manifest with zero hardcoded version names.
     */
    @NonNull
    public static String resolveSymbolicVersionId(@NonNull String versionId,
                                                  @Nullable JVersionList manifest) {
        if (manifest == null) return versionId;
        if ("latest-release".equals(versionId)) {
            String resolved = findLatestByType(manifest, TYPE_RELEASE);
            return resolved != null ? resolved : versionId;
        }
        if ("latest-snapshot".equals(versionId)) {
            String resolved = findLatestByType(manifest, TYPE_SNAPSHOT);
            return resolved != null ? resolved : versionId;
        }
        return versionId;
    }

    @Nullable
    public static String findLatestByType(@Nullable JVersionList manifest, @NonNull String type) {
        if (manifest == null) return null;
        if (manifest.latest != null) {
            String tagged = manifest.latest.get(type);
            if (tagged != null && !tagged.trim().isEmpty()) {
                return tagged.trim();
            }
        }
        if (manifest.versions != null) {
            for (JVersionList.Version v : manifest.versions) {
                if (v != null && type.equals(v.type) && v.id != null && !v.id.isEmpty()) {
                    return v.id;
                }
            }
        }
        return null;
    }

    @Nullable
    public static JVersionList.Version findVersion(@Nullable JVersionList manifest,
                                                   @Nullable String versionId) {
        if (manifest == null || manifest.versions == null || versionId == null || versionId.isEmpty()) {
            return null;
        }
        for (JVersionList.Version version : manifest.versions) {
            if (version != null && versionId.equals(version.id)) {
                return version;
            }
        }
        return null;
    }

    /**
     * Returns {@code true} when any version entry, latest pointer, URL, SHA-1, or release timestamp
     * differs between two manifests so UI/cache consumers only update when actual changes occur.
     */
    public static boolean hasManifestChanged(@Nullable JVersionList previous,
                                             @NonNull JVersionList current) {
        if (previous == null || previous.versions == null) return true;
        if (!Objects.equals(previous.latest, current.latest)) return true;
        if (previous.versions.length != current.versions.length) return true;
        for (int i = 0; i < current.versions.length; i++) {
            JVersionList.Version a = previous.versions[i];
            JVersionList.Version b = current.versions[i];
            if (a == null || b == null) return true;
            if (!Objects.equals(a.id, b.id)
                    || !Objects.equals(a.type, b.type)
                    || !Objects.equals(a.url, b.url)
                    || !Objects.equals(a.sha1, b.sha1)
                    || !Objects.equals(a.releaseTime, b.releaseTime)
                    || !Objects.equals(a.time, b.time)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the minimum required Java major version declared by a Minecraft version's official
     * {@code javaVersion} block (e.g., 8 for legacy, 17 for 1.18–1.20.4, 21 for 1.20.5–1.21.x,
     * 25+ for 26.1+ and future releases) without imposing any upper cap.
     */
    public static int resolveRequiredJavaVersion(@Nullable JVersionList.Version versionInfo) {
        if (versionInfo == null || versionInfo.javaVersion == null) {
            return 8;
        }
        if ("jre-legacy".equalsIgnoreCase(versionInfo.javaVersion.component)) {
            return 8;
        }
        if (versionInfo.javaVersion.majorVersion > 0) {
            return versionInfo.javaVersion.majorVersion;
        }
        if (versionInfo.javaVersion.version > 0) {
            return versionInfo.javaVersion.version;
        }
        return 8;
    }

    private static boolean isValidManifestVersionEntry(@Nullable JVersionList.Version entry) {
        if (entry == null) return false;
        if (entry.id == null || entry.id.trim().isEmpty()) return false;
        if (entry.type == null || entry.type.trim().isEmpty()) return false;
        if (entry.url == null || entry.url.trim().isEmpty()) return false;
        // Guard against path traversal characters in version IDs
        String id = entry.id.trim();
        if (id.contains("/") || id.contains("\\") || id.contains("..")) return false;
        try {
            URL parsed = new URL(entry.url.trim());
            return "https".equalsIgnoreCase(parsed.getProtocol())
                    && parsed.getHost() != null
                    && !parsed.getHost().isEmpty();
        } catch (MalformedURLException e) {
            return false;
        }
    }

    public static boolean isValidSha1(@Nullable String candidate) {
        if (candidate == null || candidate.length() != 40) return false;
        for (int i = 0; i < 40; i++) {
            char c = candidate.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}
