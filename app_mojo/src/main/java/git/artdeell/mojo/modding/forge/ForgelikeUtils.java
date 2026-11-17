package git.artdeell.mojo.modding.forge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.InstanceInstaller;
import git.artdeell.mojo.metadata.ForgeMetadataProvider;
import git.artdeell.mojo.metadata.NeoForgeMetadataProvider;
import git.artdeell.mojo.metadata.VersionMetadataRepository;
import git.artdeell.mojo.utils.FileUtils;

/**
 * Adapter bridging existing Forge and NeoForge installation screens &amp; modpack installers onto
 * {@link ForgeMetadataProvider} and {@link NeoForgeMetadataProvider}. Preserves the existing
 * {@code InstanceInstaller} GUI installer workflow while supporting classic {@code 1.x.y} and
 * year-based {@code 26.x+} Minecraft releases, recommended builds, and exact compatibility checks.
 */
public abstract class ForgelikeUtils {
    public static final ForgelikeUtils FORGE_UTILS = new ForgeUtils();
    public static final ForgelikeUtils NEOFORGE_UTILS = new NeoforgeUtils();

    private final String mName;
    private final String mCachePrefix;
    private final String mIconName;
    private final String mInstallerUrl;
    private final boolean mVersionOrderInversed;

    private ForgelikeUtils(String name, String cachePrefix, String iconName,
                           String installerUrl, boolean versionOrderInversed) {
        this.mName = name;
        this.mCachePrefix = cachePrefix;
        this.mIconName = iconName;
        this.mInstallerUrl = installerUrl;
        this.mVersionOrderInversed = versionOrderInversed;
    }

    public List<String> downloadVersions() throws IOException {
        return downloadVersions(false);
    }

    public abstract List<String> downloadVersions(boolean forceRefresh) throws IOException;

    @NonNull
    public Map<String, String> downloadRecommendedVersions(boolean forceRefresh) {
        return Collections.emptyMap();
    }

    @Nullable
    public abstract String resolveCompatibleVersion(@NonNull String gameVersion,
                                                    @NonNull String modLoaderVersion) throws IOException;

    @NonNull
    protected abstract String downloadInstallerSha1(@NonNull String downloadUrl) throws IOException;

    public String getInstallerUrl(String version) {
        return String.format(Locale.ROOT, mInstallerUrl, version);
    }

    @Nullable
    public InstanceInstaller createInstaller(String gameVersion, String modLoaderVersion) throws IOException {
        if (gameVersion == null || modLoaderVersion == null) return null;
        String verifiedFullVersion = resolveCompatibleVersion(gameVersion, modLoaderVersion);
        if (verifiedFullVersion == null) {
            throw new IOException(mName + " version " + modLoaderVersion
                    + " is not compatible with Minecraft " + gameVersion);
        }
        return createInstaller(verifiedFullVersion);
    }

    public InstanceInstaller createInstaller(String fullVersion) throws IOException {
        if (fullVersion == null || fullVersion.trim().isEmpty() || shouldSkipVersion(fullVersion)) {
            throw new IOException("Invalid " + mName + " installer version: " + fullVersion);
        }
        String cleanVersion = fullVersion.trim();
        String downloadUrl = getInstallerUrl(cleanVersion);
        String hash = downloadInstallerSha1(downloadUrl);
        String safeFileName = FileUtils.escapeFileName(mCachePrefix + "-installer-" + cleanVersion + ".jar");
        File installerLocation = new File(Tools.DIR_CACHE, safeFileName);
        InstanceInstaller instanceInstaller = new InstanceInstaller();
        instanceInstaller.commandLineArgs = Arrays.asList(
                "-Duser.language=en",
                "-Duser.country=US",
                "-javaagent:" + Tools.DIR_DATA + "/forge_installer/forge_installer.jar"
        );
        instanceInstaller.installerJar = installerLocation.getAbsolutePath();
        instanceInstaller.installerSha1 = hash;
        instanceInstaller.installerDownloadUrl = downloadUrl;
        return instanceInstaller;
    }

    public String getName() {
        return mName;
    }

    public String getIconName() {
        return mIconName;
    }

    @Nullable
    public abstract String processVersionString(String version);

    public abstract boolean shouldSkipVersion(String version);

    public boolean isVersionOrderInversed() {
        return mVersionOrderInversed;
    }

    private static class ForgeUtils extends ForgelikeUtils {
        public ForgeUtils() {
            super(
                    "Forge",
                    "forge",
                    "forge",
                    ForgeMetadataProvider.OFFICIAL_FORGE_INSTALLER_URL_FMT,
                    false
            );
        }

        @Override
        public List<String> downloadVersions(boolean forceRefresh) throws IOException {
            return VersionMetadataRepository.getInstance().getForgeProvider().getVersions(forceRefresh);
        }

        @NonNull
        @Override
        public Map<String, String> downloadRecommendedVersions(boolean forceRefresh) {
            return VersionMetadataRepository.getInstance().getForgeProvider().getRecommendedLoaderMap(forceRefresh);
        }

        @Nullable
        @Override
        public String resolveCompatibleVersion(@NonNull String gameVersion,
                                               @NonNull String modLoaderVersion) throws IOException {
            return VersionMetadataRepository.getInstance()
                    .getForgeProvider()
                    .resolveCompatibleFullVersion(gameVersion, modLoaderVersion);
        }

        @NonNull
        @Override
        protected String downloadInstallerSha1(@NonNull String downloadUrl) throws IOException {
            return VersionMetadataRepository.getInstance().getForgeProvider().fetchInstallerSha1(downloadUrl);
        }

        @Nullable
        @Override
        public String processVersionString(String version) {
            return ForgeMetadataProvider.extractMinecraftVersion(version);
        }

        @Override
        public boolean shouldSkipVersion(String version) {
            return ForgeMetadataProvider.extractMinecraftVersion(version) == null;
        }
    }

    private static class NeoforgeUtils extends ForgelikeUtils {
        public NeoforgeUtils() {
            super(
                    "NeoForge",
                    "neoforge",
                    "neoforge",
                    NeoForgeMetadataProvider.OFFICIAL_NEOFORGE_INSTALLER_URL_FMT,
                    true
            );
        }

        @Override
        public List<String> downloadVersions(boolean forceRefresh) throws IOException {
            return VersionMetadataRepository.getInstance().getNeoForgeProvider().getVersions(forceRefresh);
        }

        @Nullable
        @Override
        public String resolveCompatibleVersion(@NonNull String gameVersion,
                                               @NonNull String modLoaderVersion) throws IOException {
            return VersionMetadataRepository.getInstance()
                    .getNeoForgeProvider()
                    .resolveCompatibleFullVersion(gameVersion, modLoaderVersion);
        }

        @NonNull
        @Override
        protected String downloadInstallerSha1(@NonNull String downloadUrl) throws IOException {
            return VersionMetadataRepository.getInstance().getNeoForgeProvider().fetchInstallerSha1(downloadUrl);
        }

        @Nullable
        @Override
        public String processVersionString(String version) {
            return NeoForgeMetadataProvider.extractMinecraftVersion(version);
        }

        @Override
        public boolean shouldSkipVersion(String version) {
            return NeoForgeMetadataProvider.shouldSkipVersion(version);
        }
    }
}
