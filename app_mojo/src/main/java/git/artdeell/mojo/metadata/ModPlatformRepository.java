package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import git.artdeell.mojo.modding.modpacks.models.CurseManifest;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.ModrinthIndex;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.utils.GsonJsonUtils;
import git.artdeell.mojo.utils.ZyroxHttpClient;

/**
 * Single unified repository layer for all Modrinth API v2 and CurseForge Core API v1 queries,
 * response normalization, manifest compatibility verification, hash-based update checks, and
 * dependency resolution across {@code metadata/}, {@code modding/modpacks/api/}, and
 * {@code content/}.
 *
 * <p>Backed by {@link ZyroxHttpClient} (HTTP/2 connection pooling + rate-limit handling) with a
 * bounded TTL in-memory JSON cache to eliminate duplicate network calls when switching between the
 * Mod Browser, Content Manager, and Modpack Installer.
 */
public class ModPlatformRepository {
    public static final String MODRINTH_API_BASE = ModrinthProvider.OFFICIAL_MODRINTH_API_URL;
    public static final String CURSEFORGE_API_BASE = CurseForgeProvider.OFFICIAL_CURSEFORGE_API_URL;

    public static final int CURSEFORGE_MC_GAME_ID = 432;
    public static final int CURSEFORGE_MODPACK_CLASS_ID = 4471;
    public static final int CURSEFORGE_MOD_CLASS_ID = 6;
    public static final int CURSEFORGE_SORT_RELEVANCY = 1;
    public static final int DEFAULT_PAGE_SIZE = 50;

    private static final int MAX_CACHE_ENTRIES = 64;
    private static final long CACHE_TTL_MS = 3 * 60 * 1000L; // 3 minutes
    private static final Gson GSON = new Gson();

    private static volatile ModPlatformRepository sInstance;

    /** Normalized paginated search result returned by both Modrinth and CurseForge queries. */
    public static final class SearchPage {
        @NonNull public final ModItem[] items;
        public final int rawCount;
        public final int nextOffset;
        public final int totalHits;

        public SearchPage(@NonNull ModItem[] items, int rawCount, int nextOffset, int totalHits) {
            this.items = items;
            this.rawCount = rawCount;
            this.nextOffset = nextOffset;
            this.totalHits = totalHits;
        }
    }

    private static final class CacheEntry {
        final String payload;
        final long expiresAtMs;

        CacheEntry(@NonNull String payload, long expiresAtMs) {
            this.payload = payload;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final ModrinthProvider mModrinthProvider;
    private final CurseForgeProvider mCurseForgeProvider;
    private final Map<String, CacheEntry> mResponseCache = Collections.synchronizedMap(
            new LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    public ModPlatformRepository() {
        this(new ModrinthProvider(), new CurseForgeProvider());
    }

    public ModPlatformRepository(@NonNull ModrinthProvider modrinthProvider,
                                 @NonNull CurseForgeProvider curseForgeProvider) {
        mModrinthProvider = modrinthProvider;
        mCurseForgeProvider = curseForgeProvider;
    }

    @NonNull
    public static ModPlatformRepository getInstance() {
        ModPlatformRepository local = sInstance;
        if (local == null) {
            synchronized (ModPlatformRepository.class) {
                local = sInstance;
                if (local == null) {
                    local = new ModPlatformRepository();
                    sInstance = local;
                }
            }
        }
        return local;
    }

    @NonNull
    public ModrinthProvider getModrinthProvider() {
        return mModrinthProvider;
    }

    @NonNull
    public CurseForgeProvider getCurseForgeProvider() {
        return mCurseForgeProvider;
    }

    public void clearCache() {
        mResponseCache.clear();
    }

    // -------------------------------------------------------------------------
    // Unified Modrinth API v2 operations
    // -------------------------------------------------------------------------

    @Nullable
    public SearchPage searchModrinth(@NonNull SearchFilters filters, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(100, limit <= 0 ? DEFAULT_PAGE_SIZE : limit));

        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("facets", buildModrinthFacets(filters));
        params.put("query", filters.name != null ? filters.name : "");
        params.put("limit", Integer.toString(safeLimit));
        params.put("index", "relevance");
        if (safeOffset > 0) {
            params.put("offset", Integer.toString(safeOffset));
        }

        String url = buildUrl(MODRINTH_API_BASE, "search", params);
        JsonObject response = fetchJsonCached(url, null, JsonObject.class);
        if (response == null) return null;
        JsonArray hits = GsonJsonUtils.getJsonArraySafe(response, "hits");
        if (hits == null) return null;

        ModItem[] parsed = mModrinthProvider.parseSearchHits(hits);
        int nextOffset = safeOffset + hits.size();
        int totalHits = GsonJsonUtils.getIntSafe(response, "total_hits", nextOffset);
        return new SearchPage(parsed, hits.size(), nextOffset, totalHits);
    }

    @NonNull
    public static String buildModrinthFacets(@NonNull SearchFilters filters) {
        String projectType = (filters.projectTypeOverride != null && !filters.projectTypeOverride.isEmpty())
                ? filters.projectTypeOverride
                : (filters.isModpack ? "modpack" : "mod");
        StringBuilder facets = new StringBuilder(64);
        facets.append("[[\"project_type:").append(sanitizeFacetValue(projectType)).append("\"]");
        if (filters.category != null && !filters.category.isEmpty()) {
            facets.append(",[\"categories:").append(sanitizeFacetValue(filters.category)).append("\"]");
        }
        if (filters.mcVersion != null && !filters.mcVersion.isEmpty()) {
            facets.append(",[\"versions:").append(sanitizeFacetValue(filters.mcVersion)).append("\"]");
        }
        facets.append(']');
        return facets.toString();
    }

    @Nullable
    public ModDetail getModrinthDetails(@Nullable ModItem item) {
        if (item == null || item.id == null || item.id.trim().isEmpty()) return null;
        JsonArray versions = getModrinthProjectVersions(item.id.trim(), null, null);
        return mModrinthProvider.parseModDetails(item, versions);
    }

    @Nullable
    public JsonArray getModrinthProjectVersions(@NonNull String projectId,
                                                @Nullable String mcVersion,
                                                @Nullable String loader) {
        String safeId = encodePathSegment(projectId.trim());
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        if (mcVersion != null && !mcVersion.trim().isEmpty()) {
            params.put("game_versions", "[\"" + sanitizeFacetValue(mcVersion.trim()) + "\"]");
        }
        if (loader != null && !loader.trim().isEmpty()) {
            params.put("loaders", "[\"" + sanitizeFacetValue(loader.trim().toLowerCase(Locale.ROOT)) + "\"]");
        }
        String url = buildUrl(MODRINTH_API_BASE, "project/" + safeId + "/version", params);
        return fetchJsonCached(url, null, JsonArray.class);
    }

    @Nullable
    public JsonObject getModrinthVersion(@NonNull String versionId) {
        if (versionId.trim().isEmpty()) return null;
        String url = MODRINTH_API_BASE + "/version/" + encodePathSegment(versionId.trim());
        return fetchJsonCached(url, null, JsonObject.class);
    }

    @Nullable
    public JsonObject lookupModrinthVersionBySha1(@NonNull String sha1Hex) {
        if (!MojangVersionProvider.isValidSha1(sha1Hex)) return null;
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("algorithm", "sha1");
        String url = buildUrl(MODRINTH_API_BASE,
                "version_file/" + sha1Hex.toLowerCase(Locale.ROOT), params);
        return fetchJsonCached(url, null, JsonObject.class);
    }

    @NonNull
    public ModrinthProvider.ResolvedMrpackSpec verifyModrinthIndex(@Nullable ModrinthIndex index)
            throws IOException {
        return mModrinthProvider.verifyAndResolveIndex(index);
    }

    // -------------------------------------------------------------------------
    // Unified CurseForge Core API v1 operations
    // -------------------------------------------------------------------------

    @Nullable
    public SearchPage searchCurseForge(@Nullable String apiKey,
                                       @NonNull SearchFilters filters,
                                       int offset,
                                       int pageSize) {
        if (!CurseForgeProvider.isApiKeyConfigured(apiKey)) return null;
        int safeOffset = Math.max(0, offset);
        int safePage = Math.max(1, Math.min(50, pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize));

        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("gameId", Integer.toString(CURSEFORGE_MC_GAME_ID));
        params.put("classId", Integer.toString(
                filters.isModpack ? CURSEFORGE_MODPACK_CLASS_ID : CURSEFORGE_MOD_CLASS_ID));
        params.put("searchFilter", filters.name != null ? filters.name : "");
        params.put("sortField", Integer.toString(CURSEFORGE_SORT_RELEVANCY));
        params.put("sortOrder", "desc");
        params.put("pageSize", Integer.toString(safePage));
        if (filters.mcVersion != null && !filters.mcVersion.isEmpty()) {
            params.put("gameVersion", filters.mcVersion);
        }
        if (safeOffset > 0) {
            params.put("index", Integer.toString(safeOffset));
        }

        String url = buildUrl(CURSEFORGE_API_BASE, "mods/search", params);
        JsonObject response = fetchJsonCached(url, curseForgeHeaders(apiKey), JsonObject.class);
        if (response == null) return null;
        JsonArray data = GsonJsonUtils.getJsonArraySafe(response, "data");
        if (data == null) return null;
        JsonObject pagination = GsonJsonUtils.getJsonObjectSafe(response, "pagination");

        ModItem[] items = mCurseForgeProvider.parseSearchItems(data, filters.isModpack);
        int nextOffset = safeOffset + data.size();
        int totalCount = GsonJsonUtils.getIntSafe(pagination, "totalCount", nextOffset);
        return new SearchPage(items, data.size(), nextOffset, totalCount);
    }

    @Nullable
    public ModDetail getCurseForgeDetails(@Nullable String apiKey, @Nullable ModItem item) {
        if (!CurseForgeProvider.isApiKeyConfigured(apiKey)
                || item == null || item.id == null || item.id.trim().isEmpty()) {
            return null;
        }
        String modId = encodePathSegment(item.id.trim());
        Map<String, String> headers = curseForgeHeaders(apiKey);
        ArrayList<JsonObject> allFiles = new ArrayList<>();
        int index = 0;
        int maxPages = 10; // Safety ceiling (up to 500 file entries)
        for (int page = 0; page < maxPages; page++) {
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            params.put("index", Integer.toString(index));
            params.put("pageSize", Integer.toString(DEFAULT_PAGE_SIZE));
            String url = buildUrl(CURSEFORGE_API_BASE, "mods/" + modId + "/files", params);
            JsonObject response = fetchJsonCached(url, headers, JsonObject.class);
            JsonArray data = GsonJsonUtils.getJsonArraySafe(response, "data");
            if (data == null) return null;
            for (int i = 0; i < data.size(); i++) {
                JsonObject obj = GsonJsonUtils.getJsonObjectSafe(data.get(i));
                if (obj != null) allFiles.add(obj);
            }
            if (data.size() < DEFAULT_PAGE_SIZE) break;
            index += data.size();
        }
        return mCurseForgeProvider.parseModDetails(item, allFiles);
    }

    @Nullable
    public JsonObject getCurseForgeFileMetadata(@Nullable String apiKey, long projectId, long fileId) {
        if (!CurseForgeProvider.isApiKeyConfigured(apiKey) || projectId <= 0 || fileId <= 0) {
            return null;
        }
        String url = CURSEFORGE_API_BASE + "/mods/" + projectId + "/files/" + fileId;
        JsonObject root = fetchJsonCached(url, curseForgeHeaders(apiKey), JsonObject.class);
        return GsonJsonUtils.getJsonObjectSafe(root, "data");
    }

    @Nullable
    public String getCurseForgeFileDownloadUrl(@Nullable String apiKey, long projectId, long fileId) {
        if (!CurseForgeProvider.isApiKeyConfigured(apiKey) || projectId <= 0 || fileId <= 0) {
            return null;
        }
        String url = CURSEFORGE_API_BASE + "/mods/" + projectId + "/files/" + fileId + "/download-url";
        JsonObject root = fetchJsonCached(url, curseForgeHeaders(apiKey), JsonObject.class);
        if (root == null) return null;
        JsonElement data = root.get("data");
        if (data != null && data.isJsonPrimitive()) {
            String candidate = data.getAsString();
            if (ModrinthProvider.isHttpsUrl(candidate)) return candidate;
        }
        return null;
    }

    @Nullable
    public JsonObject getCurseForgeModMetadata(@Nullable String apiKey, long projectId) {
        if (!CurseForgeProvider.isApiKeyConfigured(apiKey) || projectId <= 0) return null;
        String url = CURSEFORGE_API_BASE + "/mods/" + projectId;
        JsonObject root = fetchJsonCached(url, curseForgeHeaders(apiKey), JsonObject.class);
        return GsonJsonUtils.getJsonObjectSafe(root, "data");
    }

    @NonNull
    public CurseForgeProvider.ResolvedCurseSpec verifyCurseForgeManifest(
            @Nullable CurseManifest manifest) throws IOException {
        return mCurseForgeProvider.verifyAndResolveManifest(manifest);
    }

    // -------------------------------------------------------------------------
    // Shared URL & TTL Cache helpers
    // -------------------------------------------------------------------------

    @NonNull
    public static String buildUrl(@NonNull String baseUrl,
                                  @NonNull String path,
                                  @Nullable Map<String, String> query) {
        StringBuilder sb = new StringBuilder(baseUrl.length() + path.length() + 64);
        sb.append(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        if (!path.startsWith("/")) sb.append('/');
        sb.append(path);
        if (query != null && !query.isEmpty()) {
            char sep = path.contains("?") ? '&' : '?';
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                sb.append(sep);
                sep = '&';
                sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
            }
        }
        return sb.toString();
    }

    @Nullable
    private <T> T fetchJsonCached(@NonNull String url,
                                  @Nullable Map<String, String> headers,
                                  @NonNull Class<T> type) {
        long now = System.currentTimeMillis();
        CacheEntry cached = mResponseCache.get(url);
        String raw = null;
        if (cached != null) {
            if (cached.expiresAtMs > now) {
                raw = cached.payload;
            } else {
                mResponseCache.remove(url);
            }
        }
        if (raw == null) {
            raw = ZyroxHttpClient.getString(url, headers);
            if (raw == null || raw.trim().isEmpty()) return null;
            if (raw.length() <= 512 * 1024) {
                mResponseCache.put(url, new CacheEntry(raw, now + CACHE_TTL_MS));
            }
        }
        try {
            return GSON.fromJson(raw, type);
        } catch (JsonSyntaxException e) {
            mResponseCache.remove(url);
            return null;
        }
    }

    @NonNull
    private static Map<String, String> curseForgeHeaders(@NonNull String apiKey) {
        return Collections.singletonMap("x-api-key", apiKey.trim());
    }

    @NonNull
    private static String sanitizeFacetValue(@NonNull String value) {
        return value.replace("\\", "").replace("\"", "").trim();
    }

    @NonNull
    private static String encodePathSegment(@NonNull String segment) {
        return urlEncode(segment).replace("+", "%20");
    }

    @NonNull
    private static String urlEncode(@NonNull String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
