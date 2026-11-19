package git.artdeell.mojo.modding.fabric;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.metadata.FabricMetadataProvider;
import git.artdeell.mojo.metadata.VersionMetadataRepository;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.FileUtils;

/**
 * Adapter connecting existing Fabric, Quilt, and Legacy Fabric installer flows to
 * {@link VersionMetadataRepository}'s {@link FabricMetadataProvider} and
 * {@link git.artdeell.mojo.metadata.QuiltMetadataProvider}.
 */
public class FabriclikeUtils {
    private enum Kind {
        FABRIC,
        QUILT,
        LEGACY_FABRIC
    }

    public static final FabriclikeUtils FABRIC_UTILS =
            new FabriclikeUtils(Kind.FABRIC, "Fabric", "fabric");
    public static final FabriclikeUtils QUILT_UTILS =
            new FabriclikeUtils(Kind.QUILT, "Quilt", "quilt");
    public static final FabriclikeUtils LEGACY_FABRIC_UTILS =
            new FabriclikeUtils(Kind.LEGACY_FABRIC, "Legacy Fabric", "fabric");

    private final Kind mKind;
    private final String mName;
    private final String mIconName;

    private FabriclikeUtils(@NonNull Kind kind, @NonNull String name, @NonNull String iconName) {
        this.mKind = kind;
        this.mName = name;
        this.mIconName = iconName;
    }

    @NonNull
    public FabricMetadataProvider getProvider() {
        VersionMetadataRepository repo = VersionMetadataRepository.getInstance();
        switch (mKind) {
            case QUILT:
                return repo.getQuiltProvider();
            case LEGACY_FABRIC:
                return repo.getLegacyFabricProvider();
            case FABRIC:
            default:
                return repo.getFabricProvider();
        }
    }

    @Nullable
    public FabricVersion[] downloadGameVersions() throws IOException {
        return downloadGameVersions(false);
    }

    @Nullable
    public FabricVersion[] downloadGameVersions(boolean forceRefresh) throws IOException {
        try {
            return getProvider().getGameVersions(forceRefresh);
        } catch (DownloadUtils.ParseException ignored) {
            return null;
        }
    }

    @Nullable
    public FabricVersion[] downloadLoaderVersions(String gameVersion) throws IOException {
        return downloadLoaderVersions(gameVersion, false);
    }

    @Nullable
    public FabricVersion[] downloadLoaderVersions(String gameVersion, boolean forceRefresh) throws IOException {
        if (gameVersion == null || gameVersion.trim().isEmpty()) return null;
        try {
            return getProvider().getLoaderVersions(gameVersion, forceRefresh);
        } catch (DownloadUtils.ParseException | IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    public FabricVersion getRecommendedLoaderVersion(String gameVersion) throws IOException {
        if (gameVersion == null || gameVersion.trim().isEmpty()) return null;
        try {
            return getProvider().getRecommendedLoaderVersion(gameVersion);
        } catch (DownloadUtils.ParseException | IllegalArgumentException ignored) {
            return null;
        }
    }

    public String createJsonDownloadUrl(String gameVersion, String loaderVersion) {
        return getProvider().buildProfileJsonUrl(gameVersion, loaderVersion);
    }

    public String getName() {
        return mName;
    }

    public String getIconName() {
        return mIconName;
    }

    /**
     * Resolves the recommended stable loader build for {@code gameVersion} and installs its
     * profile JSON under {@code versions/<versionId>/<versionId>.json}.
     */
    @NonNull
    public String installLatestForGameVersion(@NonNull String gameVersion) throws IOException {
        String cleanVer = gameVersion.trim();
        FabricVersion recommended = getRecommendedLoaderVersion(cleanVer);
        if (recommended == null || recommended.version == null || recommended.version.trim().isEmpty()) {
            FabricVersion[] all = downloadLoaderVersions(cleanVer, true);
            if (all != null && all.length > 0) {
                recommended = all[0];
            }
        }
        if (recommended == null || recommended.version == null || recommended.version.trim().isEmpty()) {
            throw new IOException("No " + mName + " loader release available for Minecraft " + cleanVer);
        }
        String installedId = install(cleanVer, recommended.version.trim());
        if (installedId == null || installedId.trim().isEmpty()) {
            throw new IOException("Failed to install " + mName + " profile for Minecraft " + cleanVer);
        }
        return installedId;
    }

    /**
     * Verifies {@code (gameVersion, loaderVersion)} compatibility against official loader metadata,
     * downloads and validates the resulting profile JSON, and installs it under
     * {@code versions/<versionId>/<versionId>.json}.
     */
    @Nullable
    public String install(String gameVersion, String loaderVersion) throws IOException {
        if (gameVersion == null || gameVersion.trim().isEmpty()
                || loaderVersion == null || loaderVersion.trim().isEmpty()) {
            throw new IOException("Missing " + mName + " game or loader version");
        }
        FabricMetadataProvider provider = getProvider();
        // Verify compatibility via official loader catalog (with one refresh for brand-new builds)
        FabricVersion[] compatible = downloadLoaderVersions(gameVersion, false);
        if (!FabricMetadataProvider.containsVersion(compatible, loaderVersion.trim())) {
            compatible = downloadLoaderVersions(gameVersion, true);
            if (compatible != null && !FabricMetadataProvider.containsVersion(compatible, loaderVersion.trim())) {
                throw new IOException(mName + " loader " + loaderVersion
                        + " is not compatible with Minecraft " + gameVersion);
            }
        }

        FabricMetadataProvider.ValidatedProfile profile =
                provider.fetchValidatedProfile(gameVersion, loaderVersion);
        File versionJsonDir = FileUtils.resolveInsideDirectory(
                new File(Tools.DIR_HOME_VERSION), profile.versionId);
        File versionJsonFile = new File(versionJsonDir, profile.versionId + ".json");
        FileUtils.ensureDirectory(versionJsonDir);
        Tools.write(versionJsonFile, profile.rawJson);
        return profile.versionId;
    }
}
