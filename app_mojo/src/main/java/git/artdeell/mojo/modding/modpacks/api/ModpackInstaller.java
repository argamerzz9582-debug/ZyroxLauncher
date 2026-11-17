package git.artdeell.mojo.modding.modpacks.api;

import android.widget.Toast;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.InstanceInstaller;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.utils.lifecycle.ContextExecutor;
import git.artdeell.mojo.modding.modpacks.api.modloader.LoaderInstaller;
import git.artdeell.mojo.modding.modpacks.imagecache.ModIconCache;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.progresskeeper.DownloaderProgressWrapper;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class ModpackInstaller {

    public static LoaderInstaller installModpack(String modpackName, String title, File modpackFile, String icon, InstallFunction installFunction) throws IOException {
        // Build a new minecraft instance, folder first
        LoaderInstaller loaderInstaller;
        String safeTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Modpack";
        String safeSlug = (modpackName != null && !modpackName.isEmpty()) ? modpackName : "modpack";
        Instance instance = Instances.createInstance(i-> i.name = safeTitle, safeSlug.substring(0, Math.min(16, safeSlug.length())));
        try {
            // Install the modpack
            loaderInstaller = installFunction.installModpack(modpackFile, instance.getGameDirectory());

            if(loaderInstaller == null) throw new IOException("Unknown modpack mod loader information");

            if(loaderInstaller.requiresGuiInstallation()) {
                InstanceInstaller instanceInstaller = loaderInstaller.createInstaller();
                if(instanceInstaller == null) throw new IOException("Failed to prepare data for instance installation");
                instance.installer = instanceInstaller;
            } else {
                String versionId = loaderInstaller.installHeadlessly();
                if(versionId == null) throw new IOException("Unknown mod loader version");
                instance.versionId = versionId;
            }
            instance.write();
            ModIconCache.writeInstanceImage(instance, icon);

            Instances.setSelectedInstance(instance);
            if(loaderInstaller.requiresGuiInstallation()) {
                instance.installer.start();
            }
            else ContextExecutor.executeActivity(activity -> Toast.makeText(activity, R.string.modpack_install_toast_success, Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            Instances.removeInstance(instance);
            throw e;
        } finally {
            modpackFile.delete();
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }

        return loaderInstaller;
    }

    public static LoaderInstaller downloadModpack(ModDetail modDetail, int selectedVersion, InstallFunction installFunction) throws IOException {
        if (modDetail == null || modDetail.versionUrls == null
                || selectedVersion < 0 || selectedVersion >= modDetail.versionUrls.length) {
            throw new IOException("Selected modpack version is invalid or unavailable");
        }
        String versionUrl = modDetail.versionUrls[selectedVersion];
        if (versionUrl == null || !versionUrl.startsWith("https://")) {
            throw new IOException("Blocked invalid or non-HTTPS modpack download URL");
        }
        String versionHash = (modDetail.versionHashes != null && selectedVersion < modDetail.versionHashes.length)
                ? modDetail.versionHashes[selectedVersion]
                : null;
        String safeTitle = modDetail.title != null ? modDetail.title : "modpack";
        String safeVerName = (modDetail.versionNames != null && selectedVersion < modDetail.versionNames.length
                && modDetail.versionNames[selectedVersion] != null)
                ? modDetail.versionNames[selectedVersion]
                : "latest";
        String modpackName = FileUtils.escapeFileName(safeTitle.toLowerCase(Locale.ROOT) + " " + safeVerName);
        String name = safeTitle;
        String icon = modDetail.getIconCacheTag();

        if (versionHash != null && !versionHash.isEmpty()) {
            modpackName += "_" + FileUtils.escapeFileName(versionHash);
        }

        // Keep filename safely below ext4/f2fs 255-byte limit including ".cf" extension
        if (modpackName.length() > 200) {
            modpackName = modpackName.substring(0, 200);
        }

        File modpackFile = FileUtils.resolveInsideDirectory(Tools.DIR_CACHE, modpackName + ".cf");

        byte[] downloadBuffer = new byte[8192];
        try {
            DownloadUtils.ensureSha1(modpackFile, versionHash, () -> {
                DownloadUtils.downloadFileMonitored(versionUrl, modpackFile, downloadBuffer,
                        new DownloaderProgressWrapper(R.string.modpack_download_downloading_metadata,
                                ProgressLayout.INSTALL_MODPACK
                        )
                );
            });
        } catch (IOException e) {
            modpackFile.delete();
            throw e;
        }

        return installModpack(modpackName, name, modpackFile, icon, installFunction);
    }

    public interface InstallFunction {
        LoaderInstaller installModpack(File modpackFile, File instanceDestination) throws IOException;
    }
}
