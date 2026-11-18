package git.artdeell.mojo.modding.modpacks.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipFile;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.downloader.Downloader;
import git.artdeell.mojo.downloader.TaskMetadata;
import git.artdeell.mojo.metadata.ModPlatformRepository;
import git.artdeell.mojo.metadata.ModrinthProvider;
import git.artdeell.mojo.metadata.VersionMetadataRepository;
import git.artdeell.mojo.modding.fabric.FabriclikeUtils;
import git.artdeell.mojo.modding.forge.ForgelikeUtils;
import git.artdeell.mojo.modding.lwjgl3ify.Lwjgl3ifyUtils;
import git.artdeell.mojo.modding.modpacks.api.modloader.FabriclikeLoaderInstaller;
import git.artdeell.mojo.modding.modpacks.api.modloader.ForgelikeLoaderInstaller;
import git.artdeell.mojo.modding.modpacks.api.modloader.LoaderInstaller;
import git.artdeell.mojo.modding.modpacks.api.modloader.Lwjgl3ifyLoaderInstaller;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.ModrinthIndex;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.modding.modpacks.models.SearchResult;
import git.artdeell.mojo.tasks.mojson.mirrors.DownloadMirror;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.GsonJsonUtils;
import git.artdeell.mojo.utils.ZipUtils;

public class ModrinthApi implements ModpackApi {
    private final ModPlatformRepository mRepository;

    public ModrinthApi() {
        mRepository = VersionMetadataRepository.getInstance().getModPlatformRepository();
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        ModrinthSearchResult modrinthSearchResult = (ModrinthSearchResult) previousPageResult;

        if (modrinthSearchResult != null
                && modrinthSearchResult.previousOffset >= modrinthSearchResult.totalResultCount) {
            ModrinthSearchResult emptyResult = new ModrinthSearchResult();
            emptyResult.results = new ModItem[0];
            emptyResult.totalResultCount = modrinthSearchResult.totalResultCount;
            emptyResult.previousOffset = modrinthSearchResult.previousOffset;
            return emptyResult;
        }

        int offset = modrinthSearchResult != null ? modrinthSearchResult.previousOffset : 0;
        ModPlatformRepository.SearchPage page = mRepository.searchModrinth(
                searchFilters, offset, ModPlatformRepository.DEFAULT_PAGE_SIZE);
        if (page == null) return null;

        if (modrinthSearchResult == null) modrinthSearchResult = new ModrinthSearchResult();
        modrinthSearchResult.results = page.items;
        modrinthSearchResult.previousOffset = page.nextOffset;
        modrinthSearchResult.totalResultCount = page.totalHits;
        return modrinthSearchResult;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        return mRepository.getModrinthDetails(item);
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException {
        return ModpackInstaller.downloadModpack(modDetail, selectedVersion, this::installMrpack);
    }

    @Override
    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        return ModpackInstaller.installModpack(modpackName, modpackName, modpackFile, icon, this::installMrpack);
    }

    private LoaderInstaller createVerifiedLoaderInstaller(ModrinthProvider.ResolvedMrpackSpec spec,
                                                          File installDestination) throws IOException {
        switch (spec.loader) {
            case FORGE:
                return new ForgelikeLoaderInstaller(
                        ForgelikeUtils.FORGE_UTILS, spec.minecraftVersion, spec.loaderVersion);
            case NEOFORGE:
                return new ForgelikeLoaderInstaller(
                        ForgelikeUtils.NEOFORGE_UTILS, spec.minecraftVersion, spec.loaderVersion);
            case FABRIC:
                return new FabriclikeLoaderInstaller(
                        FabriclikeUtils.FABRIC_UTILS, spec.minecraftVersion, spec.loaderVersion);
            case QUILT:
                return new FabriclikeLoaderInstaller(
                        FabriclikeUtils.QUILT_UTILS, spec.minecraftVersion, spec.loaderVersion);
            case VANILLA: {
                // Check for lwjgl3ify packs (e.g. GT:NH); otherwise install plain target MC release
                File lwjgl3ifyJar = Lwjgl3ifyUtils.detectLwjgl3ifyJar(installDestination);
                if (lwjgl3ifyJar != null) {
                    return new Lwjgl3ifyLoaderInstaller(lwjgl3ifyJar);
                }
                return new LoaderInstaller() {
                    @Override
                    public boolean requiresGuiInstallation() {
                        return false;
                    }

                    @Override
                    public git.artdeell.mojo.instances.InstanceInstaller createInstaller() {
                        throw new UnsupportedOperationException("Vanilla modpack installs headlessly");
                    }

                    @Override
                    public String installHeadlessly() {
                        return spec.minecraftVersion;
                    }
                };
            }
            default:
                return null;
        }
    }

    private LoaderInstaller installMrpack(File mrpackFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(mrpackFile)) {
            InputStream indexStream = ZipUtils.getEntryStream(modpackZipFile, "modrinth.index.json");
            if (indexStream == null) {
                throw new IOException("Invalid .mrpack archive: modrinth.index.json not found");
            }
            ModrinthIndex modrinthIndex = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(indexStream), ModrinthIndex.class);
            // Verify manifest schema & MC/loader compatibility BEFORE downloading hundreds of mods
            ModrinthProvider.ResolvedMrpackSpec spec = mRepository.verifyModrinthIndex(modrinthIndex);

            if (modrinthIndex.files != null && modrinthIndex.files.length > 0) {
                try {
                    new ModrinthDownloader().startDownloads(modrinthIndex.files, instanceDestination);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Modpack download interrupted", e);
                }
            }
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.modpack_download_applying_overrides, 1, 2);
            ZipUtils.zipExtract(modpackZipFile, "overrides/", instanceDestination);
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 50, R.string.modpack_download_applying_overrides, 2, 2);
            ZipUtils.zipExtract(modpackZipFile, "client-overrides/", instanceDestination);
            return createVerifiedLoaderInstaller(spec, instanceDestination);
        }
    }

    static class ModrinthSearchResult extends SearchResult {
        int previousOffset;
    }

    static class ModrinthDownloader extends Downloader {
        public ModrinthDownloader() {
            super(ProgressLayout.INSTALL_MODPACK);
        }

        protected void startDownloads(ModrinthIndex.ModrinthIndexFile[] indexFiles,
                                      File instanceDestination) throws IOException, InterruptedException {
            FileUtils.ensureDirectory(instanceDestination);
            ArrayList<TaskMetadata> taskMetadatas = new ArrayList<>(indexFiles.length);
            for (ModrinthIndex.ModrinthIndexFile file : indexFiles) {
                // Skip strictly server-only mods (env.client == "unsupported") per official mrpack spec
                if (!ModrinthProvider.shouldInstallFileOnClient(file)) {
                    continue;
                }
                File targetPath = FileUtils.resolveInsideDirectory(instanceDestination, file.path);
                URL downloadUrl = ModrinthProvider.selectVerifiedDownloadUrl(file);
                String sha1 = (file.hashes != null) ? file.hashes.sha1 : null;
                FileUtils.ensureParentDirectory(targetPath);
                taskMetadatas.add(new TaskMetadata(
                        targetPath,
                        downloadUrl,
                        file.fileSize,
                        sha1,
                        DownloadMirror.DOWNLOAD_CLASS_NONE
                ));
            }
            if (!taskMetadatas.isEmpty()) {
                runDownloads(taskMetadatas);
            }
        }
    }
}
