package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.ModrinthIndex;

/**
 * Official Modrinth API v2 metadata & {@code .mrpack} compatibility provider
 * ({@code https://api.modrinth.com/v2}).
 *
 * <p>Normalizes search hits and version lists, selects official {@code primary} files, filters out
 * server-only ({@code env.client == "unsupported"}) files during {@code .mrpack} installation, and
 * verifies Minecraft + mod loader compatibility without ever silently substituting loaders.
 */
public class ModrinthProvider {
    public static final String OFFICIAL_MODRINTH_API_URL = "https://api.modrinth.com/v2";

    public enum ModpackLoader {
        FABRIC("fabric-loader"),
        QUILT("quilt-loader"),
        FORGE("forge"),
        NEOFORGE("neoforge"),
        VANILLA("vanilla");

        @NonNull public final String dependencyKey;

        ModpackLoader(@NonNull String dependencyKey) {
            this.dependencyKey = dependencyKey;
        }
    }

    public static final class ResolvedMrpackSpec {
        @NonNull public final String minecraftVersion;
        @NonNull public final ModpackLoader loader;
        @Nullable public final String loaderVersion;

        public ResolvedMrpackSpec(@NonNull String minecraftVersion,
                                  @NonNull ModpackLoader loader,
                                  @Nullable String loaderVersion) {
            this.minecraftVersion = minecraftVersion;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
        }
    }

    /** Allowed client mod/modpack loader identifiers published by Modrinth v2. */
    private static final Set<String> SUPPORTED_MODPACK_LOADERS;
    static {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        s.add("fabric");
        s.add("quilt");
        s.add("forge");
        s.add("neoforge");
        s.add("minecraft");
        s.add("iris");
        s.add("optifine");
        s.add("canvas");
        s.add("vanilla");
        s.add("datapack");
        SUPPORTED_MODPACK_LOADERS = Collections.unmodifiableSet(s);
    }

    @NonNull
    public ModItem[] parseSearchHits(@Nullable JsonArray hits) {
        if (hits == null || hits.size() == 0) return new ModItem[0];
        ArrayList<ModItem> items = new ArrayList<>(hits.size());
        LinkedHashSet<String> seenIds = new LinkedHashSet<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            JsonElement el = hits.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject hit = el.getAsJsonObject();
            String projectId = getString(hit, "project_id");
            String title = getString(hit, "title");
            if (projectId == null || projectId.isEmpty() || title == null || title.isEmpty()) continue;
            if (!seenIds.add(projectId)) continue;

            String projectType = getString(hit, "project_type");
            String description = getString(hit, "description");
            String iconUrl = getString(hit, "icon_url");
            String author = getString(hit, "author");
            long downloads = getLong(hit, "downloads", -1L);

            items.add(new ModItem(
                    Constants.SOURCE_MODRINTH,
                    "modpack".equalsIgnoreCase(projectType),
                    projectId,
                    title,
                    description != null ? description : "",
                    (iconUrl != null && isHttpsUrl(iconUrl)) ? iconUrl : "",
                    author,
                    downloads
            ));
        }
        return items.toArray(new ModItem[0]);
    }

    /**
     * Parses Modrinth {@code /project/{id}/version} responses safely:
     * <ul>
     *   <li>Selects the official {@code "primary": true} file (defaulting to the first valid HTTPS file)</li>
     *   <li>Filters out incompatible server-only/plugin-only modpack releases</li>
     *   <li>Supports classic {@code 1.x.y}, year-based {@code 26.x+}, and snapshot Minecraft versions</li>
     *   <li>Deduplicates version entries and preserves SHA-1 checksums for integrity verification</li>
     * </ul>
     */
    @Nullable
    public ModDetail parseModDetails(@NonNull ModItem item, @Nullable JsonArray response) {
        if (response == null) return null;
        List<String> names = new ArrayList<>(response.size());
        List<String> mcNames = new ArrayList<>(response.size());
        List<String> urls = new ArrayList<>(response.size());
        List<String> hashes = new ArrayList<>(response.size());
        LinkedHashSet<String> seenUrls = new LinkedHashSet<>(response.size());

        for (int i = 0; i < response.size(); i++) {
            JsonElement el = response.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject version = el.getAsJsonObject();

            if (item.isModpack && !hasCompatibleClientLoader(version.getAsJsonArray("loaders"))) {
                continue;
            }

            JsonObject fileObj = selectPrimaryHttpsFile(version.getAsJsonArray("files"));
            if (fileObj == null) continue;
            String url = getString(fileObj, "url");
            if (url == null || !isHttpsUrl(url) || !seenUrls.add(url)) continue;

            String mcVersion = selectPrimaryGameVersion(version.getAsJsonArray("game_versions"));
            if (mcVersion == null || mcVersion.isEmpty()) continue;

            String displayName = getString(version, "name");
            if (displayName == null || displayName.isEmpty()) {
                displayName = getString(version, "version_number");
            }
            if (displayName == null || displayName.isEmpty()) {
                displayName = mcVersion;
            }

            String sha1 = null;
            if (fileObj.has("hashes") && fileObj.get("hashes").isJsonObject()) {
                String candidate = getString(fileObj.getAsJsonObject("hashes"), "sha1");
                if (MojangVersionProvider.isValidSha1(candidate)) {
                    sha1 = candidate.toLowerCase(Locale.ROOT);
                }
            }

            names.add(displayName);
            mcNames.add(mcVersion);
            urls.add(url);
            hashes.add(sha1);
        }

        return new ModDetail(
                item,
                names.toArray(new String[0]),
                mcNames.toArray(new String[0]),
                urls.toArray(new String[0]),
                hashes.toArray(new String[0])
        );
    }

    /**
     * Validates a {@code modrinth.index.json} manifest and resolves its exact Minecraft and loader
     * requirement. Rejects unknown or incompatible loaders with a descriptive {@link IOException}
     * instead of silently falling back to another loader.
     */
    @NonNull
    public ResolvedMrpackSpec verifyAndResolveIndex(@Nullable ModrinthIndex index) throws IOException {
        if (index == null) {
            throw new IOException("Invalid .mrpack archive: missing modrinth.index.json");
        }
        if (index.formatVersion != 1) {
            throw new IOException("Unsupported .mrpack formatVersion: " + index.formatVersion);
        }
        if (index.game != null && !"minecraft".equalsIgnoreCase(index.game.trim())) {
            throw new IOException("Unsupported modpack game target: " + index.game);
        }
        Map<String, String> deps = index.dependencies;
        if (deps == null || deps.isEmpty()) {
            throw new IOException("Invalid .mrpack manifest: missing dependencies section");
        }
        String mcVersion = normalizeDep(deps.get("minecraft"));
        if (mcVersion == null) {
            throw new IOException("Invalid .mrpack manifest: missing required Minecraft version");
        }
        if (mcVersion.contains("/") || mcVersion.contains("\\") || mcVersion.contains("..")) {
            throw new IOException("Invalid Minecraft version in .mrpack: " + mcVersion);
        }

        String forgeVer = normalizeDep(deps.get("forge"));
        if (forgeVer != null) {
            return new ResolvedMrpackSpec(mcVersion, ModpackLoader.FORGE, forgeVer);
        }
        String neoForgeVer = normalizeDep(deps.get("neoforge"));
        if (neoForgeVer != null) {
            return new ResolvedMrpackSpec(mcVersion, ModpackLoader.NEOFORGE, neoForgeVer);
        }
        String fabricVer = normalizeDep(deps.get("fabric-loader"));
        if (fabricVer != null) {
            return new ResolvedMrpackSpec(mcVersion, ModpackLoader.FABRIC, fabricVer);
        }
        String quiltVer = normalizeDep(deps.get("quilt-loader"));
        if (quiltVer != null) {
            return new ResolvedMrpackSpec(mcVersion, ModpackLoader.QUILT, quiltVer);
        }

        // Check whether an unsupported mod loader dependency was requested — NEVER substitute.
        for (Map.Entry<String, String> entry : deps.entrySet()) {
            String key = entry.getKey();
            if (key != null && !"minecraft".equalsIgnoreCase(key.trim())
                    && normalizeDep(entry.getValue()) != null) {
                throw new IOException("Unsupported modpack loader '" + key
                        + "' for Minecraft " + mcVersion);
            }
        }
        return new ResolvedMrpackSpec(mcVersion, ModpackLoader.VANILLA, null);
    }

    /**
     * Returns {@code true} if the {@code .mrpack} file entry should be installed on a Minecraft
     * client. Per the official Modrinth mrpack specification, entries declaring
     * {@code env.client == "unsupported"} are strictly server-only and must be skipped on clients.
     */
    public static boolean shouldInstallFileOnClient(@Nullable ModrinthIndex.ModrinthIndexFile file) {
        if (file == null || file.path == null || file.path.trim().isEmpty()) return false;
        if (file.env != null && file.env.client != null) {
            return !"unsupported".equalsIgnoreCase(file.env.client.trim());
        }
        return true;
    }

    /** Selects the first verified {@code https://} download URL from a mrpack file's mirror list. */
    @NonNull
    public static URL selectVerifiedDownloadUrl(@NonNull ModrinthIndex.ModrinthIndexFile file)
            throws IOException {
        if (file.downloads == null || file.downloads.length == 0) {
            throw new IOException("Missing download URL for modpack file: " + file.path);
        }
        for (String candidate : file.downloads) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            URL url = new URL(candidate.trim());
            if ("https".equalsIgnoreCase(url.getProtocol())
                    && url.getHost() != null && !url.getHost().isEmpty()) {
                return url;
            }
        }
        throw new IOException("Blocked non-HTTPS modpack download URL for: " + file.path);
    }

    private static boolean hasCompatibleClientLoader(@Nullable JsonArray loaders) {
        // If API omits loaders array, do not discard; verify during manifest parsing.
        if (loaders == null || loaders.size() == 0) return true;
        for (JsonElement el : loaders) {
            if (el == null || el.isJsonNull()) continue;
            String loader = el.getAsString().trim().toLowerCase(Locale.ROOT);
            if (SUPPORTED_MODPACK_LOADERS.contains(loader)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static JsonObject selectPrimaryHttpsFile(@Nullable JsonArray files) {
        if (files == null || files.size() == 0) return null;
        JsonObject firstValid = null;
        for (JsonElement el : files) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject file = el.getAsJsonObject();
            String url = getString(file, "url");
            if (url == null || !isHttpsUrl(url)) continue;
            if (firstValid == null) {
                firstValid = file;
            }
            if (file.has("primary") && !file.get("primary").isJsonNull()
                    && file.get("primary").getAsBoolean()) {
                return file;
            }
        }
        return firstValid;
    }

    @Nullable
    private static String selectPrimaryGameVersion(@Nullable JsonArray gameVersions) {
        if (gameVersions == null || gameVersions.size() == 0) return null;
        String firstAny = null;
        for (JsonElement el : gameVersions) {
            if (el == null || el.isJsonNull()) continue;
            String v = el.getAsString().trim();
            if (v.isEmpty()) continue;
            if (firstAny == null) firstAny = v;
            // Prefer release numbering (1.x.y or 26.x.y) over snapshot tags when multiple are listed
            if (CurseForgeProvider.isReleaseMinecraftVersion(v)) {
                return v;
            }
        }
        return firstAny;
    }

    @Nullable
    private static String normalizeDep(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    private static String getString(@NonNull JsonObject obj, @NonNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            String v = obj.get(key).getAsString();
            return v != null ? v.trim() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long getLong(@NonNull JsonObject obj, @NonNull String key, long fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static boolean isHttpsUrl(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return false;
        try {
            URL u = new URL(raw);
            return "https".equalsIgnoreCase(u.getProtocol())
                    && u.getHost() != null && !u.getHost().isEmpty();
        } catch (MalformedURLException e) {
            return false;
        }
    }
}
