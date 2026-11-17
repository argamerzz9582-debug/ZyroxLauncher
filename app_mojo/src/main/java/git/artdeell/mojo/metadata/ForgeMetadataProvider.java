package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import git.artdeell.mojo.modding.forge.ForgelikeVersionListHandler;
import git.artdeell.mojo.utils.DownloadUtils;

/**
 * Official Minecraft Forge metadata provider backed by Forge Maven
 * ({@code maven-metadata.xml}) and official Forge promotions ({@code promotions_slim.json}).
 *
 * <p>Supports classic {@code 1.x.y} and year-based {@code 26.x+} Minecraft releases, prevents
 * prefix collisions when resolving exact loader versions, exposes recommended Forge builds per
 * Minecraft version, and hardens XML parsing against XXE.
 */
public class ForgeMetadataProvider {
    public static final String OFFICIAL_FORGE_METADATA_URL =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    public static final String OFFICIAL_FORGE_PROMOTIONS_URL =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    public static final String OFFICIAL_FORGE_INSTALLER_URL_FMT =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/%1$s/forge-%1$s-installer.jar";

    public static final String CACHE_KEY_VERSIONS = "forge_versions";
    public static final String CACHE_KEY_PROMOTIONS = "forge_promotions";
    private static final long FORGE_REVALIDATE_COOLDOWN_MS = 5 * 60_000L;
    private static final String RECOMMENDED_SUFFIX = "-recommended";

    private final LocalMetadataCache mCache;

    public ForgeMetadataProvider(@NonNull LocalMetadataCache cache) {
        mCache = cache;
    }

    @NonNull
    public List<String> getVersions(boolean forceRefresh) throws IOException {
        try {
            return mCache.sync(
                    CACHE_KEY_VERSIONS,
                    OFFICIAL_FORGE_METADATA_URL,
                    forceRefresh,
                    FORGE_REVALIDATE_COOLDOWN_MS,
                    ForgeMetadataProvider::parseForgeMavenMetadata
            ).data;
        } catch (DownloadUtils.ParseException e) {
            throw new IOException("Invalid Forge metadata response", e);
        }
    }

    /**
     * Fetches official Forge recommended loader versions keyed by Minecraft version
     * (e.g. {@code "1.20.1" -> "47.4.0"}). Never fails installer flows if promotions are unreachable.
     */
    @NonNull
    public Map<String, String> getRecommendedLoaderMap(boolean forceRefresh) {
        try {
            return mCache.sync(
                    CACHE_KEY_PROMOTIONS,
                    OFFICIAL_FORGE_PROMOTIONS_URL,
                    forceRefresh,
                    FORGE_REVALIDATE_COOLDOWN_MS,
                    ForgeMetadataProvider::parsePromotionsSlim
            ).data;
        } catch (IOException | DownloadUtils.ParseException ignored) {
            Map<String, String> cached = mCache.readCached(
                    CACHE_KEY_PROMOTIONS, ForgeMetadataProvider::parsePromotionsSlim);
            return cached != null ? cached : Collections.emptyMap();
        }
    }

    /**
     * Returns the full Forge artifact version marked as recommended for {@code minecraftVersion}
     * (verified to exist in the Maven version catalog), or {@code null} when none is declared.
     */
    @Nullable
    public String getRecommendedFullVersion(@NonNull String minecraftVersion, boolean forceRefresh) {
        try {
            List<String> allVersions = getVersions(forceRefresh);
            Map<String, String> recommended = getRecommendedLoaderMap(forceRefresh);
            String recLoader = recommended.get(minecraftVersion.trim());
            if (recLoader == null) return null;
            return findCompatibleFullVersion(allVersions, minecraftVersion, recLoader);
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Resolves and verifies an exact compatible full Forge artifact version for
     * {@code (minecraftVersion, forgeLoaderVersion)}, avoiding partial-prefix mismatches
     * (e.g. {@code 47.1.3} will never match {@code 47.1.30}) while supporting legacy suffixed
     * coordinates such as {@code 1.7.10-10.13.4.1614-1.7.10}.
     */
    @Nullable
    public String resolveCompatibleFullVersion(@NonNull String minecraftVersion,
                                               @NonNull String forgeLoaderVersion) throws IOException {
        List<String> versions = getVersions(false);
        String match = findCompatibleFullVersion(versions, minecraftVersion, forgeLoaderVersion);
        if (match == null) {
            // Newly released Forge build may have landed since last sync — revalidate once.
            versions = getVersions(true);
            match = findCompatibleFullVersion(versions, minecraftVersion, forgeLoaderVersion);
        }
        return match;
    }

    @Nullable
    public static String findCompatibleFullVersion(@Nullable List<String> catalog,
                                                   @NonNull String minecraftVersion,
                                                   @NonNull String forgeLoaderVersion) {
        if (catalog == null || catalog.isEmpty()) return null;
        String cleanMc = minecraftVersion.trim();
        String cleanLoader = forgeLoaderVersion.trim();
        if (cleanMc.isEmpty() || cleanLoader.isEmpty()) return null;

        // If caller passed an already-prefixed full version, verify MC compatibility first.
        String exactCanonical = cleanLoader.startsWith(cleanMc + "-")
                ? cleanLoader
                : (cleanMc + "-" + cleanLoader);
        String legacyBranchPrefix = exactCanonical + "-";

        // Pass 1: exact canonical match (e.g. "1.20.1-47.1.3" or "26.1-60.0.1")
        for (String candidate : catalog) {
            if (exactCanonical.equals(candidate)) {
                return candidate;
            }
        }
        // Pass 2: legacy Forge branch-suffixed match (e.g. "1.7.10-10.13.4.1614-1.7.10")
        // Requires an explicit '-' delimiter so "47.1.3" NEVER matches "47.1.30".
        for (String candidate : catalog) {
            if (candidate != null && candidate.startsWith(legacyBranchPrefix)) {
                String resolvedMc = extractMinecraftVersion(candidate);
                if (cleanMc.equals(resolvedMc)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Extracts the Minecraft version portion from a Forge version coordinate, or {@code null} if malformed. */
    @Nullable
    public static String extractMinecraftVersion(@Nullable String fullForgeVersion) {
        if (fullForgeVersion == null) return null;
        String trimmed = fullForgeVersion.trim();
        int dashIndex = trimmed.indexOf('-');
        if (dashIndex <= 0 || dashIndex >= trimmed.length() - 1) {
            return null;
        }
        // Reject path-unsafe tokens
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            return null;
        }
        String mcPart = trimmed.substring(0, dashIndex).trim();
        return mcPart.isEmpty() ? null : mcPart;
    }

    @NonNull
    public String fetchInstallerSha1(@NonNull String installerUrl) throws IOException {
        String raw = mCache.fetchDirect(installerUrl + ".sha1");
        String sha1 = extractSha1Hash(raw);
        if (sha1 == null) {
            throw new IOException("Invalid SHA-1 checksum received for " + installerUrl);
        }
        return sha1;
    }

    /** Extracts a verified 40-hex SHA-1 digest from a Maven {@code .sha1} file body. */
    @Nullable
    public static String extractSha1Hash(@Nullable String rawSha1Body) {
        if (rawSha1Body == null) return null;
        String trimmed = rawSha1Body.trim();
        if (trimmed.isEmpty()) return null;
        // Handle both raw "<40hex>\n" and GNU coreutils "<40hex>  filename.jar"
        int space = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                space = i;
                break;
            }
        }
        String token = (space > 0 ? trimmed.substring(0, space) : trimmed).toLowerCase(Locale.ROOT);
        return MojangVersionProvider.isValidSha1(token) ? token : null;
    }

    @NonNull
    public static List<String> parseForgeMavenMetadata(@NonNull String rawXml)
            throws DownloadUtils.ParseException {
        List<String> rawVersions = parseMavenMetadataVersions(rawXml);
        LinkedHashSet<String> validUnique = new LinkedHashSet<>(rawVersions.size());
        for (String v : rawVersions) {
            if (extractMinecraftVersion(v) != null) {
                validUnique.add(v.trim());
            }
        }
        if (validUnique.isEmpty()) {
            throw new DownloadUtils.ParseException(
                    new IllegalArgumentException("No valid Forge versions found in maven-metadata.xml"));
        }
        return Collections.unmodifiableList(new ArrayList<>(validUnique));
    }

    @NonNull
    static List<String> parseMavenMetadataVersions(@NonNull String rawXml)
            throws DownloadUtils.ParseException {
        if (rawXml.trim().isEmpty()) {
            throw new DownloadUtils.ParseException(new IllegalArgumentException("Empty Maven metadata XML"));
        }
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            configureSecureSaxFactory(factory);
            SAXParser parser = factory.newSAXParser();
            ForgelikeVersionListHandler handler = new ForgelikeVersionListHandler();
            parser.parse(new InputSource(new StringReader(rawXml)), handler);
            List<String> versions = handler.getVersions();
            if (versions == null || versions.isEmpty()) {
                throw new DownloadUtils.ParseException(
                        new IllegalArgumentException("Maven metadata contains zero versions"));
            }
            return versions;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new DownloadUtils.ParseException(e);
        }
    }

    @NonNull
    public static Map<String, String> parsePromotionsSlim(@NonNull String rawJson)
            throws DownloadUtils.ParseException {
        try {
            JsonElement root = new JsonParser().parse(rawJson);
            if (!root.isJsonObject()) {
                throw new DownloadUtils.ParseException(new IllegalArgumentException("Expected JSON object"));
            }
            JsonObject promos = root.getAsJsonObject().getAsJsonObject("promos");
            if (promos == null) {
                return Collections.emptyMap();
            }
            Map<String, String> recommendedByMc = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : promos.entrySet()) {
                String key = entry.getKey();
                JsonElement val = entry.getValue();
                if (key == null || val == null || val.isJsonNull()) continue;
                if (key.endsWith(RECOMMENDED_SUFFIX) && key.length() > RECOMMENDED_SUFFIX.length()) {
                    String mcVer = key.substring(0, key.length() - RECOMMENDED_SUFFIX.length()).trim();
                    String loaderVer = val.getAsString().trim();
                    if (!mcVer.isEmpty() && !loaderVer.isEmpty()) {
                        recommendedByMc.put(mcVer, loaderVer);
                    }
                }
            }
            return Collections.unmodifiableMap(recommendedByMc);
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new DownloadUtils.ParseException(e);
        }
    }

    private static void configureSecureSaxFactory(@NonNull SAXParserFactory factory) {
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setNamespaceAware(false);
    }

    private static void trySetFeature(@NonNull SAXParserFactory factory,
                                      @NonNull String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | SAXException ignored) {
            // Feature unsupported by this SAX implementation (e.g., Android ExpatReader — already XXE-safe).
        }
    }
}
