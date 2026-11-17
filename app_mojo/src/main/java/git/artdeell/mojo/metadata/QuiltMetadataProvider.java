package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;

import com.google.gson.JsonObject;

/**
 * Official Quilt Meta v3 metadata provider ({@code https://meta.quiltmc.org/v3}).
 * Handles Quilt's loader metadata format (where loader objects omit the explicit {@code stable}
 * field and encode pre-release channels as {@code -beta.N} / {@code -alpha.N} / {@code -rc.N})
 * while sharing conditional ETag caching, deduplication, and strict profile compatibility checks.
 */
public class QuiltMetadataProvider extends FabricMetadataProvider {
    public static final String OFFICIAL_QUILT_API_URL = "https://meta.quiltmc.org/v3";

    public QuiltMetadataProvider(@NonNull LocalMetadataCache cache) {
        super(cache, OFFICIAL_QUILT_API_URL, "quilt", "Quilt");
    }

    @Override
    protected boolean inferStability(@NonNull JsonObject obj, @NonNull String version) {
        if (obj.has("stable") && !obj.get("stable").isJsonNull()) {
            try {
                return obj.get("stable").getAsBoolean();
            } catch (RuntimeException ignored) {
                // Fall through to Quilt version suffix classification
            }
        }
        return !isPreReleaseTag(version);
    }
}
