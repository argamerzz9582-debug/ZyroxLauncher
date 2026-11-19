package git.artdeell.mojo.modding.modpacks.models;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Detailed downloadable version list for a Modrinth or CurseForge store item, enriched with
 * release channels, loader metadata, upstream version IDs (for automatic dependency resolution),
 * and the index of the top stable recommended release.
 */
public class ModDetail extends ModItem {
    /* A cheap way to map from one to another without creating a ton of small objects */
    public String[] versionNames;
    public String[] mcVersionNames;
    public String[] versionUrls;
    /** SHA-1 hashes; null entries mean a hash was unavailable upstream. */
    public String[] versionHashes;
    /** Upstream version/file IDs used to inspect required dependencies during install. */
    public String[] versionIds;
    /** Release channel per entry ({@code "release"}, {@code "beta"}, {@code "alpha"}). */
    public String[] versionTypes;
    /** Human-readable loader compatibility label per entry (e.g. {@code "Fabric"}). */
    public String[] versionLoaders;
    /** Index of the top recommended stable build, or {@code -1} if no version is available. */
    public int recommendedIndex;

    public ModDetail(ModItem item,
                     String[] versionNames,
                     String[] mcVersionNames,
                     String[] versionUrls,
                     String[] hashes) {
        this(item, versionNames, mcVersionNames, versionUrls, hashes,
                new String[versionNames != null ? versionNames.length : 0],
                defaultReleaseTypes(versionNames != null ? versionNames.length : 0),
                new String[versionNames != null ? versionNames.length : 0],
                (versionNames != null && versionNames.length > 0) ? 0 : -1);
    }

    public ModDetail(ModItem item,
                     String[] versionNames,
                     String[] mcVersionNames,
                     String[] versionUrls,
                     String[] hashes,
                     String[] versionIds,
                     String[] versionTypes,
                     String[] versionLoaders,
                     int recommendedIndex) {
        super(item.apiSource, item.isModpack, item.id, item.title, item.description,
                item.imageUrl, item.author, item.downloads);
        this.versionNames = versionNames != null ? versionNames : new String[0];
        this.mcVersionNames = mcVersionNames != null ? mcVersionNames : new String[0];
        this.versionUrls = versionUrls != null ? versionUrls : new String[0];
        this.versionHashes = hashes != null ? hashes : new String[0];
        this.versionIds = versionIds != null ? versionIds : new String[this.versionNames.length];
        this.versionTypes = versionTypes != null
                ? versionTypes : defaultReleaseTypes(this.versionNames.length);
        this.versionLoaders = versionLoaders != null
                ? versionLoaders : new String[this.versionNames.length];
        this.recommendedIndex = recommendedIndex;

        for (int i = 0; i < this.versionNames.length && i < this.mcVersionNames.length; i++) {
            String vn = this.versionNames[i];
            String mv = this.mcVersionNames[i];
            if (vn != null && mv != null && !mv.isEmpty() && !vn.contains(mv)) {
                this.versionNames[i] = vn + " - " + mv;
            }
        }
    }

    @NonNull
    private static String[] defaultReleaseTypes(int count) {
        String[] types = new String[Math.max(0, count)];
        for (int i = 0; i < types.length; i++) {
            types[i] = "release";
        }
        return types;
    }

    /**
     * Picks the index of the top stable {@code release} entry (preferring {@code preferredLoader}
     * when specified), falling back to {@code beta}, then {@code 0}, or {@code -1} when empty.
     */
    public static int computeRecommendedIndex(@Nullable String[] versionTypes,
                                              @Nullable String[] versionLoaders,
                                              @Nullable String preferredLoader) {
        if (versionTypes == null || versionTypes.length == 0) return -1;
        String pref = (preferredLoader != null && !preferredLoader.trim().isEmpty())
                ? preferredLoader.trim().toLowerCase(Locale.ROOT) : null;

        int firstReleaseAny = -1;
        int firstBetaPref = -1;
        int firstBetaAny = -1;

        for (int i = 0; i < versionTypes.length; i++) {
            String t = versionTypes[i] != null
                    ? versionTypes[i].trim().toLowerCase(Locale.ROOT) : "release";
            String ld = (versionLoaders != null && i < versionLoaders.length && versionLoaders[i] != null)
                    ? versionLoaders[i].toLowerCase(Locale.ROOT) : "";
            boolean loaderHit = pref == null || ld.isEmpty() || ld.contains(pref);

            if ("release".equals(t)) {
                if (loaderHit) return i;
                if (firstReleaseAny < 0) firstReleaseAny = i;
            } else if ("beta".equals(t)) {
                if (loaderHit && firstBetaPref < 0) firstBetaPref = i;
                if (firstBetaAny < 0) firstBetaAny = i;
            }
        }
        if (firstReleaseAny >= 0) return firstReleaseAny;
        if (firstBetaPref >= 0) return firstBetaPref;
        if (firstBetaAny >= 0) return firstBetaAny;
        return 0;
    }
}
