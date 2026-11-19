package git.artdeell.mojo.modding.optifine;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.InstanceInstaller;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.ZyroxHttpClient;

public class OptiFineUtils {
    private static final String TAG = "OptiFineUtils";
    private static final String[] OPTIFINE_DOWNLOAD_ENDPOINTS = new String[]{
            "https://optifine.net/downloads",
            "https://www.optifine.net/downloads"
    };

    @Nullable
    public static OptiFineVersions downloadOptiFineVersions() throws IOException {
        return downloadOptiFineVersions(false);
    }

    @Nullable
    public static OptiFineVersions downloadOptiFineVersions(boolean forceRefresh) {
        if (!forceRefresh) {
            try {
                OptiFineVersions cached = DownloadUtils.downloadStringCached(
                        OPTIFINE_DOWNLOAD_ENDPOINTS[0], "of_downloads_page", new OptiFineScraper());
                if (cached != null && cached.gameVersions != null && !cached.gameVersions.isEmpty()) {
                    return cached;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Primary cached OptiFine scrape failed, trying direct HTTPS fallback: "
                        + t.getMessage());
            }
        }
        for (String endpoint : OPTIFINE_DOWNLOAD_ENDPOINTS) {
            try {
                String html = ZyroxHttpClient.getHtmlOrThrow(endpoint);
                OptiFineVersions parsed = new OptiFineScraper().process(html);
                if (parsed != null && parsed.gameVersions != null && !parsed.gameVersions.isEmpty()) {
                    return parsed;
                }
            } catch (Throwable t) {
                Log.w(TAG, "OptiFine fallback endpoint failed (" + endpoint + "): " + t.getMessage());
            }
        }
        return null;
    }

    public static InstanceInstaller createInstaller(OptiFineVersion version) {
        int installerHash = Objects.hash(version.versionName, version.gameVersion);
        File installerLocation = new File(Tools.DIR_CACHE, "optifine-installer-" + installerHash + ".jar");
        InstanceInstaller instanceInstaller = new InstanceInstaller();
        instanceInstaller.installerUrlTransformer = "optifine";
        instanceInstaller.installerDownloadUrl = version.downloadUrl;
        instanceInstaller.installerJar = installerLocation.getAbsolutePath();
        instanceInstaller.commandLineArgs = Collections.singletonList(
                "-javaagent:" + Tools.DIR_DATA + "/forge_installer/forge_installer.jar=OF");
        return instanceInstaller;
    }

    public static class OptiFineVersions {
        public List<String> gameVersions;
        public List<List<OptiFineVersion>> optifineVersions;
    }

    public static class OptiFineVersion {
        public String gameVersion;
        public String versionName;
        public String downloadUrl;
    }
}
