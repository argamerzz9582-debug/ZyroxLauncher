package git.artdeell.mojo.modding.modpacks.api;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipFile;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.downloader.AcquireableTaskMetadata;
import git.artdeell.mojo.downloader.Downloader;
import git.artdeell.mojo.metadata.CurseForgeProvider;
import git.artdeell.mojo.metadata.ModPlatformRepository;
import git.artdeell.mojo.metadata.VersionMetadataRepository;
import git.artdeell.mojo.modding.fabric.FabriclikeUtils;
import git.artdeell.mojo.modding.forge.ForgelikeUtils;
import git.artdeell.mojo.modding.modpacks.api.modloader.FabriclikeLoaderInstaller;
import git.artdeell.mojo.modding.modpacks.api.modloader.ForgelikeLoaderInstaller;
import git.artdeell.mojo.modding.modpacks.api.modloader.LoaderInstaller;
import git.artdeell.mojo.modding.modpacks.models.CurseManifest;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.modding.modpacks.models.SearchResult;
import git.artdeell.mojo.tasks.mojson.mirrors.DownloadMirror;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.GsonJsonUtils;
import git.artdeell.mojo.utils.ZipUtils;

public class CurseforgeApi implements ModpackApi {
    private final String mApiKey;
    private final ModPlatformRepository mRepository;

    public CurseforgeApi(String apiKey) {
        mApiKey = apiKey;
        mRepository = VersionMetadataRepository.getInstance().getModPlatformRepository();
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        CurseforgeSearchResult curseforgeSearchResult = (CurseforgeSearchResult) previousPageResult;
        int offset = curseforgeSearchResult != null ? curseforgeSearchResult.previousOffset : 0;

        ModPlatformRepository.SearchPage page = mRepository.searchCurseForge(
                mApiKey, searchFilters, offset, ModPlatformRepository.DEFAULT_PAGE_SIZE);
        if (page == null) return null;

        if (curseforgeSearchResult == null) curseforgeSearchResult = new CurseforgeSearchResult();
        curseforgeSearchResult.results = page.items;
        curseforgeSearchResult.previousOffset = page.nextOffset;
        curseforgeSearchResult.totalResultCount = page.totalHits;
        return curseforgeSearchResult;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        return mRepository.getCurseForgeDetails(mApiKey, item);
    }

    @Override
    public ModDetail getModDetails(ModItem item,
                                   String cleanMcVersion,
                                   String loader,
                                   String projectType,
                                   boolean strictCompatibility) {
        return mRepository.getCurseForgeDetails(
                mApiKey, item, cleanMcVersion, loader, projectType, strictCompatibility);
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException {
        return ModpackInstaller.downloadModpack(modDetail, selectedVersion, this::installCurseforgeZip);
    }

    @Override
    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        return ModpackInstaller.installModpack(modpackName, modpackName, modpackFile, icon, this::installCurseforgeZip);
    }

    private LoaderInstaller installCurseforgeZip(File zipFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(zipFile)) {
            InputStream manifestStream = ZipUtils.getEntryStream(modpackZipFile, "manifest.json");
            if (manifestStream == null) {
                throw new IOException("Invalid CurseForge modpack: manifest.json not found");
            }
            CurseManifest curseManifest = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(manifestStream), CurseManifest.class);
            // Verify manifest schema & MC/loader compatibility BEFORE downloading pack files
            CurseForgeProvider.ResolvedCurseSpec spec = mRepository.verifyCurseForgeManifest(curseManifest);

            if (curseManifest.files != null && curseManifest.files.length > 0) {
                try {
                    new CurseDownloader().start(curseManifest, instanceDestination);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("CurseForge modpack download interrupted", e);
                }
            }
            String overridesDir = (curseManifest.overrides != null && !curseManifest.overrides.trim().isEmpty())
                    ? curseManifest.overrides.trim()
                    : "overrides";
            ZipUtils.zipExtract(modpackZipFile, overridesDir, instanceDestination);
            return createLoaderInstaller(spec);
        }
    }

    private LoaderInstaller createLoaderInstaller(CurseForgeProvider.ResolvedCurseSpec spec) {
        Log.i("CurseforgeApi", "Resolved CurseForge loader " + spec.loaderType
                + " " + spec.loaderVersion + " for MC " + spec.minecraftVersion);
        switch (spec.loaderType) {
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
            default:
                return null;
        }
    }

    private String getDownloadUrl(JsonObject fileMetadata) throws IOException {
        JsonElement modIdEl = fileMetadata.get("modId");
        JsonElement idEl = fileMetadata.get("id");
        if (modIdEl == null || modIdEl.isJsonNull() || idEl == null || idEl.isJsonNull()) {
            throw new IOException("Bad CurseForge file metadata schema");
        }
        long projectID = modIdEl.getAsLong();
        long fileID = idEl.getAsLong();

        // First try the direct downloadUrl if already populated
        String direct = GsonJsonUtils.getStringSafe(fileMetadata, "downloadUrl");
        if (direct != null && direct.startsWith("https://")) {
            return direct;
        }

        // Next query the official download-url endpoint through unified ModPlatformRepository
        String apiUrl = mRepository.getCurseForgeFileDownloadUrl(mApiKey, projectID, fileID);
        if (apiUrl != null && !apiUrl.trim().isEmpty()) {
            return apiUrl.trim();
        }

        // Otherwise fallback to official HTTPS edge CDN path
        String edgeUrl = CurseForgeProvider.buildEdgeFallbackUrl(fileMetadata);
        if (edgeUrl == null) {
            throw new IOException("CurseForge file " + fileID + " has no downloadable URL");
        }
        return edgeUrl;
    }

    private void checkRequiredFileFields(JsonObject fileMetadata) throws IOException {
        if (fileMetadata == null || fileMetadata.isJsonNull()) {
            throw new IOException("File metadata is null!");
        }
        boolean hasProjectId = fileMetadata.has("modId") && !fileMetadata.get("modId").isJsonNull();
        boolean hasFileId = fileMetadata.has("id") && !fileMetadata.get("id").isJsonNull();
        boolean hasLength = fileMetadata.has("fileLength") && !fileMetadata.get("fileLength").isJsonNull();
        if (!hasProjectId || !hasFileId || !hasLength) {
            StringBuilder builder = new StringBuilder("File metadata is missing the following fields:");
            if (!hasProjectId) builder.append(" modId");
            if (!hasFileId) builder.append(" id");
            if (!hasLength) builder.append(" fileLength");
            throw new IOException(builder.toString());
        }
    }

    private @Nullable JsonObject getFile(long projectID, long fileID) {
        return mRepository.getCurseForgeFileMetadata(mApiKey, projectID, fileID);
    }

    static class CurseforgeSearchResult extends SearchResult {
        int previousOffset;
    }

    class CurseDownloader extends Downloader {
        public CurseDownloader() {
            super(ProgressLayout.INSTALL_MODPACK);
        }

        public void start(CurseManifest curseManifest, File instanceDestination) throws IOException, InterruptedException {
            ArrayList<AcquireableTaskMetadata> taskMetadatas = new ArrayList<>(curseManifest.files.length);
            for (final CurseManifest.CurseFile file : curseManifest.files) {
                if (file == null) continue;
                taskMetadatas.add(new CurseTaskMetadata(file, instanceDestination));
            }
            if (!taskMetadatas.isEmpty()) {
                runDownloads(taskMetadatas);
            }
        }
    }

    class CurseTaskMetadata extends AcquireableTaskMetadata {
        private final CurseManifest.CurseFile mFile;
        private final File mInstanceDestination;

        public CurseTaskMetadata(CurseManifest.CurseFile mFile, File mInstanceDestination) {
            super(DownloadMirror.DOWNLOAD_CLASS_METADATA);
            this.mFile = mFile;
            this.mInstanceDestination = mInstanceDestination;
        }

        @Override
        public void acquireMetadata() throws IOException {
            JsonObject fileMetadata = getFile(mFile.projectID, mFile.fileID);
            checkRequiredFileFields(fileMetadata);
            String url = getDownloadUrl(fileMetadata);
            URL parsedUrl = new URL(url);
            if (!"https".equalsIgnoreCase(parsedUrl.getProtocol())) {
                throw new IOException("Blocked non-HTTPS CurseForge download URL");
            }
            String rawFileName = FileUtils.getFileName(url);
            String decodedName = rawFileName != null
                    ? FileUtils.getFileName(URLDecoder.decode(rawFileName, "UTF-8"))
                    : null;
            if (decodedName == null) {
                throw new IOException("Invalid mod file name in CurseForge metadata");
            }
            File modsDir = FileUtils.resolveInsideDirectory(mInstanceDestination, "mods");
            FileUtils.ensureDirectorySilently(modsDir);
            this.url = parsedUrl;
            this.path = FileUtils.resolveInsideDirectory(modsDir, decodedName);
            this.sha1Hash = CurseForgeProvider.extractSha1(fileMetadata);
            this.size = fileMetadata.get("fileLength").getAsLong();
        }
    }
}
