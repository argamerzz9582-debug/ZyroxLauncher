package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.CurseManifest;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.utils.GsonJsonUtils;

/**
 * Official CurseForge Core API v1 metadata & manifest compatibility provider
 * ({@code https://api.curseforge.com/v1}).
 *
 * <p>Safely handles nullable {@code downloadUrl} / {@code logo} / {@code allowModDistribution}
 * fields, recognizes classic {@code 1.x.y}, year-based {@code 26.x+}, and snapshot Minecraft
 * versions, supports {@code forge}, {@code neoforge}, {@code fabric}, and {@code quilt} modpack
 * manifests, and rejects unsupported loaders without silent substitution.
 */
public class CurseForgeProvider {
    public static final String OFFICIAL_CURSEFORGE_API_URL = "https://api.curseforge.com/v1";
    private static final int ALGO_SHA_1 = 1;

    /** Matches both classic (1.7.10, 1.21, 1.21.11) and 2026+ year-based (26.1, 26.1.1, 27.2) MC releases. */
    private static final Pattern RELEASE_MC_PATTERN =
            Pattern.compile("^(?:1\\.[0-9]+(?:\\.[0-9]+)?|[2-9][0-9]\\.[0-9]+(?:\\.[0-9]+)?)$");

    /** Matches official weekly and year-based snapshots / pre-releases / release candidates. */
    private static final Pattern SNAPSHOT_MC_PATTERN =
            Pattern.compile("^(?:[0-9]{2}w[0-9]{2}[a-z]|(?:1\\.[0-9]+|[2-9][0-9]\\.[0-9]+)(?:\\.[0-9]+)?-(?:snapshot|pre|rc)-?[0-9]+)$");

    public enum CurseLoaderType {
        FORGE,
        NEOFORGE,
        FABRIC,
        QUILT
    }

    public static final class ResolvedCurseSpec {
        @NonNull public final String minecraftVersion;
        @NonNull public final CurseLoaderType loaderType;
        @NonNull public final String loaderVersion;

        public ResolvedCurseSpec(@NonNull String minecraftVersion,
                                 @NonNull CurseLoaderType loaderType,
                                 @NonNull String loaderVersion) {
            this.minecraftVersion = minecraftVersion;
            this.loaderType = loaderType;
            this.loaderVersion = loaderVersion;
        }
    }

    private static final long DEFAULT_RATE_LIMIT_COOLDOWN_MS = 60_000L;
    private static final long AUTH_FAILURE_COOLDOWN_MS = 10 * 60_000L;
    private static volatile long sCooldownUntilMs = 0L;
    private static volatile int sLastFailureHttpCode = 0;

    public static boolean isApiKeyConfigured(@Nullable String apiKey) {
        if (apiKey == null) return false;
        String clean = apiKey.trim();
        return clean.length() >= 8
                && !"DUMMY".equalsIgnoreCase(clean)
                && !"NONE".equalsIgnoreCase(clean)
                && !"NULL".equalsIgnoreCase(clean);
    }

    /** Returns {@code true} when CurseForge has a valid API key AND is not inside a 429/403 cooldown. */
    public static boolean canQueryCurseForge(@Nullable String apiKey) {
        return isApiKeyConfigured(apiKey) && System.currentTimeMillis() >= sCooldownUntilMs;
    }

    /** Returns {@code true} when CurseForge is temporarily cooling down due to HTTP 429/401/403. */
    public static boolean isRateLimitedOrBlocked() {
        return System.currentTimeMillis() < sCooldownUntilMs;
    }

    /** Records an upstream HTTP error so subsequent queries skip CurseForge and fall back cleanly. */
    public static void recordUpstreamFailure(int httpStatusCode, long retryAfterMs) {
        sLastFailureHttpCode = httpStatusCode;
        long now = System.currentTimeMillis();
        if (httpStatusCode == 429) {
            long wait = retryAfterMs > 0L
                    ? Math.min(300_000L, retryAfterMs)
                    : DEFAULT_RATE_LIMIT_COOLDOWN_MS;
            sCooldownUntilMs = now + wait;
        } else if (httpStatusCode == 401 || httpStatusCode == 403) {
            sCooldownUntilMs = now + AUTH_FAILURE_COOLDOWN_MS;
        } else if (httpStatusCode >= 500) {
            sCooldownUntilMs = now + 20_000L;
        }
    }

    /** Resets transient rate-limit cooldown state (called on success or manual retry). */
    public static void resetRateLimitState() {
        sCooldownUntilMs = 0L;
        sLastFailureHttpCode = 0;
    }

    /** Returns a friendly, non-intrusive status banner string when falling back to Modrinth. */
    @NonNull
    public static String buildModrinthFallbackNotice(@Nullable String apiKey) {
        if (!isApiKeyConfigured(apiKey)) {
            return "CurseForge API key not configured - seamlessly showing verified Modrinth results.";
        }
        if (sLastFailureHttpCode == 429) {
            return "CurseForge is rate-limited right now - automatically showing Modrinth results.";
        }
        if (sLastFailureHttpCode == 401 || sLastFailureHttpCode == 403) {
            return "CurseForge API access unavailable - automatically showing Modrinth results.";
        }
        return "CurseForge temporarily unreachable - automatically showing Modrinth results.";
    }

    public static boolean isReleaseMinecraftVersion(@Nullable String version) {
        return version != null && RELEASE_MC_PATTERN.matcher(version.trim()).matches();
    }

    public static boolean isMinecraftVersionTag(@Nullable String version) {
        if (version == null) return false;
        String trimmed = version.trim();
        return RELEASE_MC_PATTERN.matcher(trimmed).matches()
                || SNAPSHOT_MC_PATTERN.matcher(trimmed).matches();
    }

    @NonNull
    public ModItem[] parseSearchItems(@Nullable JsonArray dataArray, boolean isModpack) {
        if (dataArray == null || dataArray.size() == 0) return new ModItem[0];
        ArrayList<ModItem> results = new ArrayList<>(dataArray.size());
        LinkedHashSet<String> seenIds = new LinkedHashSet<>(dataArray.size());

        for (int i = 0; i < dataArray.size(); i++) {
            JsonObject element = GsonJsonUtils.getJsonObjectSafe(dataArray.get(i));
            if (element == null) continue;

            // Skip projects that explicitly forbid third-party launcher distribution
            JsonElement allowDist = element.get("allowModDistribution");
            if (allowDist != null && !allowDist.isJsonNull()) {
                try {
                    if (!allowDist.getAsBoolean()) continue;
                } catch (RuntimeException ignored) {
                }
            }

            String id = GsonJsonUtils.getStringSafe(element, "id");
            String name = GsonJsonUtils.getStringSafe(element, "name");
            if (id == null || id.isEmpty() || name == null || name.isEmpty()) continue;
            if (!seenIds.add(id)) continue;

            String summary = GsonJsonUtils.getStringSafe(element, "summary");
            String thumbUrl = "";
            JsonObject logo = GsonJsonUtils.getJsonObjectSafe(element, "logo");
            if (logo != null) {
                String candidate = GsonJsonUtils.getStringSafe(logo, "thumbnailUrl");
                if (candidate != null && ModrinthProvider.isHttpsUrl(candidate)) {
                    thumbUrl = candidate;
                }
            }

            results.add(new ModItem(
                    Constants.SOURCE_CURSEFORGE,
                    isModpack,
                    id,
                    name,
                    summary != null ? summary : "",
                    thumbUrl
            ));
        }
        return results.toArray(new ModItem[0]);
    }

    /**
     * Converts paginated CurseForge file metadata objects into a clean {@link ModDetail}, safely
     * handling {@code downloadUrl: null} entries (falling back to official HTTPS {@code edge.forgecdn.net}
     * when {@code id} + {@code fileName} are available, and skipping unresolvable entries instead of crashing).
     */
    @NonNull
    public ModDetail parseModDetails(@NonNull ModItem item, @NonNull List<JsonObject> rawFiles) {
        List<String> versionNames = new ArrayList<>(rawFiles.size());
        List<String> mcVersionNames = new ArrayList<>(rawFiles.size());
        List<String> versionUrls = new ArrayList<>(rawFiles.size());
        List<String> hashes = new ArrayList<>(rawFiles.size());
        LinkedHashSet<String> seenUrls = new LinkedHashSet<>(rawFiles.size());

        for (JsonObject fileObj : rawFiles) {
            if (fileObj == null) continue;
            JsonElement isServerPack = fileObj.get("isServerPack");
            if (isServerPack != null && !isServerPack.isJsonNull() && isServerPack.getAsBoolean()) {
                continue;
            }

            String url = resolveFileDownloadUrl(fileObj);
            if (url == null || !ModrinthProvider.isHttpsUrl(url) || !seenUrls.add(url)) {
                continue;
            }

            String mcVersion = extractMinecraftVersion(GsonJsonUtils.getJsonArraySafe(fileObj, "gameVersions"));
            if (mcVersion == null) continue;

            String displayName = GsonJsonUtils.getStringSafe(fileObj, "displayName");
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = GsonJsonUtils.getStringSafe(fileObj, "fileName");
            }
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = mcVersion;
            }

            versionNames.add(displayName.trim());
            mcVersionNames.add(mcVersion);
            versionUrls.add(url);
            hashes.add(extractSha1(fileObj));
        }

        return new ModDetail(
                item,
                versionNames.toArray(new String[0]),
                mcVersionNames.toArray(new String[0]),
                versionUrls.toArray(new String[0]),
                hashes.toArray(new String[0])
        );
    }

    @Nullable
    public static String resolveFileDownloadUrl(@NonNull JsonObject fileObj) {
        String direct = GsonJsonUtils.getStringSafe(fileObj, "downloadUrl");
        if (direct != null && ModrinthProvider.isHttpsUrl(direct.trim())) {
            return direct.trim();
        }
        return buildEdgeFallbackUrl(fileObj);
    }

    @Nullable
    public static String buildEdgeFallbackUrl(@NonNull JsonObject fileObj) {
        if (!fileObj.has("id") || fileObj.get("id").isJsonNull()) return null;
        String fileName = GsonJsonUtils.getStringSafe(fileObj, "fileName");
        if (fileName == null || fileName.trim().isEmpty()) return null;
        final long fileId;
        try {
            fileId = fileObj.get("id").getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
        if (fileId <= 0) return null;
        try {
            String encodedName = URLEncoder.encode(fileName.trim(), "UTF-8").replace("+", "%20");
            return String.format(Locale.ROOT, "https://edge.forgecdn.net/files/%d/%d/%s",
                    fileId / 1000L, fileId % 1000L, encodedName);
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    @Nullable
    public static String extractMinecraftVersion(@Nullable JsonArray gameVersions) {
        if (gameVersions == null || gameVersions.size() == 0) return null;
        String snapshotFallback = null;
        for (JsonElement el : gameVersions) {
            if (el == null || el.isJsonNull()) continue;
            String tag = el.getAsString().trim();
            if (RELEASE_MC_PATTERN.matcher(tag).matches()) {
                return tag;
            }
            if (snapshotFallback == null && SNAPSHOT_MC_PATTERN.matcher(tag).matches()) {
                snapshotFallback = tag;
            }
        }
        return snapshotFallback;
    }

    @Nullable
    public static String extractSha1(@NonNull JsonObject fileObj) {
        JsonArray hashes = GsonJsonUtils.getJsonArraySafe(fileObj, "hashes");
        if (hashes == null) return null;
        for (JsonElement element : hashes) {
            JsonObject hashObj = GsonJsonUtils.getJsonObjectSafe(element);
            if (hashObj != null && GsonJsonUtils.getIntSafe(hashObj, "algo", -1) == ALGO_SHA_1) {
                String value = GsonJsonUtils.getStringSafe(hashObj, "value");
                if (MojangVersionProvider.isValidSha1(value)) {
                    return value.toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    public static boolean isValidManifest(@Nullable CurseManifest manifest) {
        if (manifest == null) return false;
        if (!"minecraftModpack".equals(manifest.manifestType)) return false;
        if (manifest.manifestVersion != 1) return false;
        if (manifest.minecraft == null) return false;
        if (manifest.minecraft.version == null || manifest.minecraft.version.trim().isEmpty()) return false;
        String mcVer = manifest.minecraft.version.trim();
        if (mcVer.contains("/") || mcVer.contains("\\") || mcVer.contains("..")) return false;
        return manifest.minecraft.modLoaders != null && manifest.minecraft.modLoaders.length >= 1;
    }

    /**
     * Validates a CurseForge modpack manifest and resolves its primary loader specification.
     * Supports {@code forge}, {@code neoforge}, {@code fabric}, and {@code quilt}; throws a clear
     * {@link IOException} on unknown/malformed loaders without silently substituting another loader.
     */
    @NonNull
    public ResolvedCurseSpec verifyAndResolveManifest(@Nullable CurseManifest manifest) throws IOException {
        if (!isValidManifest(manifest)) {
            throw new IOException("Invalid or unsupported CurseForge manifest.json");
        }
        CurseManifest.CurseModLoader selected = null;
        for (CurseManifest.CurseModLoader loader : manifest.minecraft.modLoaders) {
            if (loader != null && loader.primary && loader.id != null && !loader.id.trim().isEmpty()) {
                selected = loader;
                break;
            }
        }
        if (selected == null) {
            for (CurseManifest.CurseModLoader loader : manifest.minecraft.modLoaders) {
                if (loader != null && loader.id != null && !loader.id.trim().isEmpty()) {
                    selected = loader;
                    break;
                }
            }
        }
        if (selected == null) {
            throw new IOException("CurseForge modpack manifest contains no valid mod loader entry");
        }

        String rawId = selected.id.trim();
        int dash = rawId.indexOf('-');
        if (dash <= 0 || dash >= rawId.length() - 1) {
            throw new IOException("Malformed CurseForge modLoader id: " + rawId);
        }
        String loaderName = rawId.substring(0, dash).trim().toLowerCase(Locale.ROOT);
        String loaderVersion = rawId.substring(dash + 1).trim();
        String mcVersion = manifest.minecraft.version.trim();

        switch (loaderName) {
            case "forge":
                return new ResolvedCurseSpec(mcVersion, CurseLoaderType.FORGE, loaderVersion);
            case "neoforge":
                return new ResolvedCurseSpec(mcVersion, CurseLoaderType.NEOFORGE, loaderVersion);
            case "fabric":
                return new ResolvedCurseSpec(mcVersion, CurseLoaderType.FABRIC, loaderVersion);
            case "quilt":
                return new ResolvedCurseSpec(mcVersion, CurseLoaderType.QUILT, loaderVersion);
            default:
                throw new IOException("Unsupported CurseForge mod loader '" + loaderName
                        + "' for Minecraft " + mcVersion);
        }
    }
}
