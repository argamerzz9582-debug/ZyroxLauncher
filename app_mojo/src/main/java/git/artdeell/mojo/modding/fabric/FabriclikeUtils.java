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

    /** Immutable descriptor for a resolved + locally installed latest-stable Fabric profile. */
    public static final class ResolvedFabricRelease {
        @NonNull public final String versionId;
        @NonNull public final String minecraftVersion;
        @NonNull public final String loaderVersion;

        public ResolvedFabricRelease(@NonNull String versionId,
                                     @NonNull String minecraftVersion,
                                     @NonNull String loaderVersion) {
            this.versionId = versionId;
            this.minecraftVersion = minecraftVersion;
            this.loaderVersion = loaderVersion;
        }
    }

    /**
     * Selects the newest stable Minecraft release from an official Fabric Meta v2 game list,
     * preferring {@code preferredMojangLatest} when Fabric already publishes stable support for it.
     */
    @Nullable
    public static String selectLatestStableGameVersion(@Nullable FabricVersion[] fabricGameVersions,
                                                       @Nullable String preferredMojangLatest) {
        if (fabricGameVersions == null || fabricGameVersions.length == 0) {
            return null;
        }
        String preferred = (preferredMojangLatest != null) ? preferredMojangLatest.trim() : "";
        if (!preferred.isEmpty()) {
            for (FabricVersion gv : fabricGameVersions) {
                if (gv != null && gv.stable && gv.version != null
                        && preferred.equals(gv.version.trim())) {
                    return preferred;
                }
            }
        }
        for (FabricVersion gv : fabricGameVersions) {
            if (gv != null && gv.stable && gv.version != null && !gv.version.trim().isEmpty()) {
                return gv.version.trim();
            }
        }
        return null;
    }

    /**
     * Queries official Fabric Meta v2 for the newest stable Minecraft release and its latest
     * recommended stable Fabric Loader, validates/writes {@code versions/<id>/<id>.json}, and
     * returns the installed {@link ResolvedFabricRelease} (zero hardcoded version strings).
     */
    @NonNull
    public ResolvedFabricRelease resolveAndInstallLatestStable() throws IOException {
        FabricVersion[] gameVersions = downloadGameVersions(false);
        if (gameVersions == null || gameVersions.length == 0) {
            gameVersions = downloadGameVersions(true);
        }
        if (gameVersions == null || gameVersions.length == 0) {
            throw new IOException("Unable to fetch " + mName + " game versions from official catalog");
        }

        // Collect ordered stable candidate Minecraft releases (Mojang's latest release first if supported)
        java.util.List<String> candidates = new java.util.ArrayList<>();
        git.artdeell.mojo.JVersionList mojangCatalog =
                VersionMetadataRepository.getInstance().getCurrentCatalog();
        String mojangLatest = git.artdeell.mojo.metadata.MojangVersionProvider.findLatestByType(
                mojangCatalog, git.artdeell.mojo.metadata.MojangVersionProvider.TYPE_RELEASE);
        String topChoice = selectLatestStableGameVersion(gameVersions, mojangLatest);
        if (topChoice != null) {
            candidates.add(topChoice);
        }
        for (FabricVersion gv : gameVersions) {
            if (gv != null && gv.stable && gv.version != null) {
                String v = gv.version.trim();
                if (!v.isEmpty() && !candidates.contains(v)) {
                    candidates.add(v);
                    if (candidates.size() >= 5) break;
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IOException("No stable Minecraft release found in " + mName + " catalog");
        }

        IOException lastError = null;
        for (String mcVer : candidates) {
            try {
                FabricVersion loader = getRecommendedLoaderVersion(mcVer);
                if (loader == null || loader.version == null || loader.version.trim().isEmpty()) {
                    FabricVersion[] allLoaders = downloadLoaderVersions(mcVer, true);
                    if (allLoaders != null && allLoaders.length > 0) {
                        loader = allLoaders[0];
                    }
                }
                if (loader == null || loader.version == null || loader.version.trim().isEmpty()) {
                    continue;
                }
                String loaderVer = loader.version.trim();
                String expectedId = buildExpectedLoaderVersionId(mcVer, loaderVer);
                if (expectedId != null && hasValidLocalProfileJson(expectedId)) {
                    return new ResolvedFabricRelease(expectedId, mcVer, loaderVer);
                }
                String installedId = install(mcVer, loaderVer);
                if (installedId != null && !installedId.trim().isEmpty()) {
                    return new ResolvedFabricRelease(installedId.trim(), mcVer, loaderVer);
                }
            } catch (IOException e) {
                lastError = e;
            }
        }
        if (lastError != null) throw lastError;
        throw new IOException("Failed to install latest stable " + mName + " loader profile");
    }

    /**
     * Ensures {@code versionId} resolves to a concrete, on-disk Fabric/Quilt/LegacyFabric profile JSON:
     * <ul>
     *   <li>Symbolic {@code "latest_fabric"} / {@code "latest-fabric"} installs and returns the
     *       newest stable Fabric release (e.g. {@code fabric-loader-<loader>-<mc>}).</li>
     *   <li>Explicit {@code fabric-loader-<loader>-<mc>}, {@code quilt-loader-<loader>-<mc>}, or
     *       {@code legacy-fabric-loader-<loader>-<mc>} self-heals its profile JSON from official
     *       upstream metadata if deleted or missing on disk.</li>
     *   <li>Any non-Fabric version ID is returned unchanged.</li>
     * </ul>
     */
    @NonNull
    public static String ensureProfileReadyForLaunch(@NonNull String versionId) throws IOException {
        String trimmed = versionId.trim();
        if (git.artdeell.mojo.instances.Instance.isLatestFabricId(trimmed)) {
            return FABRIC_UTILS.resolveAndInstallLatestStable().versionId;
        }
        if (hasValidLocalProfileJson(trimmed)) {
            return trimmed;
        }
        String[][] prefixes = new String[][]{
                {"fabric-loader-", "fabric"},
                {"quilt-loader-", "quilt"},
                {"legacy-fabric-loader-", "legacy"}
        };
        for (String[] entry : prefixes) {
            String prefix = entry[0];
            if (trimmed.startsWith(prefix) && trimmed.length() > prefix.length()) {
                String rest = trimmed.substring(prefix.length());
                int sep = rest.lastIndexOf('-');
                if (sep > 0 && sep < rest.length() - 1) {
                    String loaderVer = rest.substring(0, sep);
                    String mcVer = rest.substring(sep + 1);
                    if (!Character.isDigit(mcVer.charAt(0))) {
                        sep = rest.indexOf('-');
                        loaderVer = rest.substring(0, sep);
                        mcVer = rest.substring(sep + 1);
                    }
                    FabriclikeUtils target = "quilt".equals(entry[1]) ? QUILT_UTILS
                            : ("legacy".equals(entry[1]) ? LEGACY_FABRIC_UTILS : FABRIC_UTILS);
                    String installed = target.install(mcVer, loaderVer);
                    if (installed != null && !installed.trim().isEmpty()) {
                        return installed.trim();
                    }
                }
            }
        }
        return trimmed;
    }

    @Nullable
    private String buildExpectedLoaderVersionId(@NonNull String mcVer, @NonNull String loaderVer) {
        switch (mKind) {
            case FABRIC:
                return "fabric-loader-" + loaderVer + "-" + mcVer;
            case QUILT:
                return "quilt-loader-" + loaderVer + "-" + mcVer;
            case LEGACY_FABRIC:
                return "legacy-fabric-loader-" + loaderVer + "-" + mcVer;
            default:
                return null;
        }
    }

    /** Returns {@code true} if {@code versions/<versionId>/<versionId>.json} exists and is non-empty. */
    public static boolean hasValidLocalProfileJson(@Nullable String versionId) {
        if (versionId == null || versionId.trim().isEmpty() || Tools.DIR_HOME_VERSION == null) {
            return false;
        }
        File jsonFile = new File(Tools.DIR_HOME_VERSION, versionId + "/" + versionId + ".json");
        return jsonFile.isFile() && jsonFile.length() > 64L;
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
