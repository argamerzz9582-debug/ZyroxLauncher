package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;

import git.artdeell.mojo.modding.fabric.FabricVersion;
import git.artdeell.mojo.utils.DownloadUtils;

/**
 * Official Fabric Meta v2 metadata provider ({@code https://meta.fabricmc.net/v2}).
 * Discovers compatible Minecraft game versions, compatible Fabric loader versions, recommended
 * stable loader versions, and verifies profile JSON compatibility prior to installation.
 */
public class FabricMetadataProvider {
    public static final String OFFICIAL_FABRIC_API_URL = "https://meta.fabricmc.net/v2";
    public static final String OFFICIAL_LEGACY_FABRIC_API_URL = "https://meta.legacyfabric.net/v2";

    private static final String GAME_VERSIONS_FMT = "%s/versions/game";
    private static final String LOADER_VERSIONS_FMT = "%s/versions/loader/%s";
    private static final String PROFILE_JSON_FMT = "%s/versions/loader/%s/%s/profile/json";
    private static final long LOADER_REVALIDATE_COOLDOWN_MS = 5 * 60_000L; // 5 minutes

    private final LocalMetadataCache mCache;
    private final String mApiBaseUrl;
    private final String mCachePrefix;
    private final String mDisplayName;

    public FabricMetadataProvider(@NonNull LocalMetadataCache cache) {
        this(cache, OFFICIAL_FABRIC_API_URL, "fabric", "Fabric");
    }

    public FabricMetadataProvider(@NonNull LocalMetadataCache cache,
                                  @NonNull String apiBaseUrl,
                                  @NonNull String cachePrefix,
                                  @NonNull String displayName) {
        mCache = cache;
        mApiBaseUrl = stripTrailingSlash(apiBaseUrl);
        mCachePrefix = cachePrefix;
        mDisplayName = displayName;
    }

    @NonNull
    public String getApiBaseUrl() {
        return mApiBaseUrl;
    }

    @NonNull
    public String getDisplayName() {
        return mDisplayName;
    }

    @Nullable
    public FabricVersion[] getCachedGameVersions() {
        return mCache.readCached(mCachePrefix + "_game_versions", this::parseGameVersions);
    }

    @NonNull
    public FabricVersion[] getGameVersions(boolean forceRefresh)
            throws IOException, DownloadUtils.ParseException {
        String url = String.format(Locale.ROOT, GAME_VERSIONS_FMT, mApiBaseUrl);
        return mCache.sync(
                mCachePrefix + "_game_versions",
                url,
                forceRefresh,
                LOADER_REVALIDATE_COOLDOWN_MS,
                this::parseGameVersions
        ).data;
    }

    @NonNull
    public FabricVersion[] getLoaderVersions(@NonNull String minecraftVersion, boolean forceRefresh)
            throws IOException, DownloadUtils.ParseException {
        String cleanMc = requireValidVersionToken(minecraftVersion, "Minecraft version");
        String encoded = urlEncode(cleanMc);
        String url = String.format(Locale.ROOT, LOADER_VERSIONS_FMT, mApiBaseUrl, encoded);
        return mCache.sync(
                mCachePrefix + "_loader_versions." + encoded,
                url,
                forceRefresh,
                LOADER_REVALIDATE_COOLDOWN_MS,
                this::parseLoaderVersions
        ).data;
    }

    /**
     * Returns the recommended (highest-priority stable) loader version for the given Minecraft
     * version, or the first verified compatible loader if only pre-release builds exist yet, or
     * {@code null} when no loader supports that Minecraft version.
     */
    @Nullable
    public FabricVersion getRecommendedLoaderVersion(@NonNull String minecraftVersion)
            throws IOException, DownloadUtils.ParseException {
        FabricVersion[] loaders = getLoaderVersions(minecraftVersion, false);
        return selectRecommended(loaders);
    }

    @Nullable
    public static FabricVersion selectRecommended(@Nullable FabricVersion[] loaders) {
        if (loaders == null || loaders.length == 0) return null;
        for (FabricVersion candidate : loaders) {
            if (candidate != null && candidate.recommended) return candidate;
        }
        for (FabricVersion candidate : loaders) {
            if (candidate != null && candidate.stable) return candidate;
        }
        return loaders[0];
    }

    /**
     * Verifies against official provider metadata that {@code loaderVersion} is genuinely
     * published as compatible with {@code minecraftVersion}. Never guesses or substitutes.
     */
    public boolean isCompatible(@NonNull String minecraftVersion, @NonNull String loaderVersion) {
        if (minecraftVersion.trim().isEmpty() || loaderVersion.trim().isEmpty()) return false;
        try {
            FabricVersion[] compatibleLoaders = getLoaderVersions(minecraftVersion, false);
            return containsVersion(compatibleLoaders, loaderVersion.trim());
        } catch (IOException | DownloadUtils.ParseException e) {
            return false;
        }
    }

    @NonNull
    public String buildProfileJsonUrl(@NonNull String minecraftVersion,
                                      @NonNull String loaderVersion) {
        String cleanMc = requireValidVersionToken(minecraftVersion, "Minecraft version");
        String cleanLoader = requireValidVersionToken(loaderVersion, "Loader version");
        return String.format(Locale.ROOT, PROFILE_JSON_FMT,
                mApiBaseUrl, urlEncode(cleanMc), urlEncode(cleanLoader));
    }

    /**
     * Downloads and strictly validates the official loader profile JSON for
     * {@code (minecraftVersion, loaderVersion)}, ensuring {@code id}, {@code mainClass}, and
     * {@code inheritsFrom == minecraftVersion} so an incompatible or corrupt profile can never be installed.
     */
    @NonNull
    public ValidatedProfile fetchValidatedProfile(@NonNull String minecraftVersion,
                                                  @NonNull String loaderVersion) throws IOException {
        String cleanMc = requireValidVersionToken(minecraftVersion, "Minecraft version");
        String cleanLoader = requireValidVersionToken(loaderVersion, "Loader version");
        String profileUrl = buildProfileJsonUrl(cleanMc, cleanLoader);
        String rawJson = mCache.fetchDirect(profileUrl);
        return validateProfileJson(rawJson, cleanMc, mDisplayName);
    }

    @NonNull
    public static ValidatedProfile validateProfileJson(@NonNull String rawJson,
                                                       @NonNull String expectedMinecraftVersion,
                                                       @NonNull String loaderDisplayName) throws IOException {
        if (rawJson.trim().isEmpty()) {
            throw new IOException("Empty " + loaderDisplayName + " profile metadata");
        }
        final JsonObject obj;
        try {
            JsonElement root = new JsonParser().parse(rawJson);
            if (!root.isJsonObject()) {
                throw new IOException("Invalid " + loaderDisplayName + " profile format");
            }
            obj = root.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new IOException("Corrupt " + loaderDisplayName + " profile JSON", e);
        }

        String id = getOptionalString(obj, "id");
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IOException("Invalid version ID in " + loaderDisplayName + " profile metadata");
        }

        String inheritsFrom = getOptionalString(obj, "inheritsFrom");
        if (inheritsFrom != null && !inheritsFrom.isEmpty()
                && !expectedMinecraftVersion.equals(inheritsFrom)) {
            throw new IOException(loaderDisplayName + " profile targets Minecraft " + inheritsFrom
                    + " instead of requested " + expectedMinecraftVersion);
        }

        String mainClass = getOptionalString(obj, "mainClass");
        if (mainClass == null || mainClass.isEmpty()) {
            throw new IOException("Missing mainClass in " + loaderDisplayName + " profile metadata");
        }

        return new ValidatedProfile(id, expectedMinecraftVersion, rawJson);
    }

    @NonNull
    public FabricVersion[] parseGameVersions(@NonNull String rawJson)
            throws DownloadUtils.ParseException {
        JsonArray arr = parseJsonArray(rawJson);
        LinkedHashMap<String, FabricVersion> unique = new LinkedHashMap<>(arr.size());
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String version = getOptionalString(obj, "version");
            if (version == null || version.isEmpty()) continue;
            if (version.contains("/") || version.contains("\\") || version.contains("..")) continue;
            if (unique.containsKey(version)) continue;

            FabricVersion fv = new FabricVersion();
            fv.version = version;
            fv.stable = inferStability(obj, version);
            unique.put(version, fv);
        }
        if (unique.isEmpty()) {
            throw new DownloadUtils.ParseException(
                    new IllegalArgumentException("No supported game versions in " + mDisplayName + " metadata"));
        }
        return unique.values().toArray(new FabricVersion[0]);
    }

    @NonNull
    public FabricVersion[] parseLoaderVersions(@NonNull String rawJson)
            throws DownloadUtils.ParseException {
        JsonArray arr = parseJsonArray(rawJson);
        // Note: empty array is valid when a brand-new Minecraft version has zero published loaders yet.
        LinkedHashMap<String, FabricVersion> unique = new LinkedHashMap<>(arr.size());
        boolean recommendedAssigned = false;

        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            JsonObject loaderObj = entry.has("loader") && entry.get("loader").isJsonObject()
                    ? entry.getAsJsonObject("loader")
                    : entry;
            String version = getOptionalString(loaderObj, "version");
            if (version == null || version.isEmpty()) continue;
            if (version.contains("/") || version.contains("\\") || version.contains("..")) continue;
            if (unique.containsKey(version)) continue;

            FabricVersion fv = new FabricVersion();
            fv.version = version;
            fv.stable = inferStability(loaderObj, version);
            if (!recommendedAssigned && fv.stable) {
                fv.recommended = true;
                recommendedAssigned = true;
            }
            unique.put(version, fv);
        }
        return unique.values().toArray(new FabricVersion[0]);
    }

    protected boolean inferStability(@NonNull JsonObject obj, @NonNull String version) {
        if (obj.has("stable") && !obj.get("stable").isJsonNull()) {
            try {
                return obj.get("stable").getAsBoolean();
            } catch (RuntimeException ignored) {
                // Fall through to version string classification
            }
        }
        return !isPreReleaseTag(version);
    }

    static boolean isPreReleaseTag(@NonNull String version) {
        String lower = version.toLowerCase(Locale.ROOT);
        return lower.contains("beta")
                || lower.contains("alpha")
                || lower.contains("-pre")
                || lower.contains("-rc")
                || lower.contains("snapshot");
    }

    public static boolean containsVersion(@Nullable FabricVersion[] list, @NonNull String version) {
        if (list == null) return false;
        for (FabricVersion item : list) {
            if (item != null && version.equals(item.version)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static JsonArray parseJsonArray(@NonNull String rawJson) throws DownloadUtils.ParseException {
        if (rawJson.trim().isEmpty()) {
            throw new DownloadUtils.ParseException(new IllegalArgumentException("Empty JSON array"));
        }
        try {
            JsonElement root = new JsonParser().parse(rawJson);
            if (!root.isJsonArray()) {
                throw new DownloadUtils.ParseException(new IllegalArgumentException("Expected JSON array"));
            }
            return root.getAsJsonArray();
        } catch (JsonSyntaxException e) {
            throw new DownloadUtils.ParseException(e);
        }
    }

    @Nullable
    private static String getOptionalString(@NonNull JsonObject obj, @NonNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            String value = obj.get(key).getAsString();
            return value != null ? value.trim() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @NonNull
    private static String requireValidVersionToken(@Nullable String raw, @NonNull String label) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        String clean = raw.trim();
        if (clean.contains("/") || clean.contains("\\") || clean.contains("..")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + raw);
        }
        return clean;
    }

    @NonNull
    private static String urlEncode(@NonNull String input) {
        try {
            return URLEncoder.encode(input, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 required", e);
        }
    }

    @NonNull
    private static String stripTrailingSlash(@NonNull String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static final class ValidatedProfile {
        @NonNull public final String versionId;
        @NonNull public final String minecraftVersion;
        @NonNull public final String rawJson;

        public ValidatedProfile(@NonNull String versionId,
                                @NonNull String minecraftVersion,
                                @NonNull String rawJson) {
            this.versionId = versionId;
            this.minecraftVersion = minecraftVersion;
            this.rawJson = rawJson;
        }
    }
}
