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
    private static final int CURSEFORGE_MC_GAME_ID = 432;
    private static final int CURSEFORGE_MODPACK_CLASS_ID = 4471;
    private static final int CURSEFORGE_MOD_CLASS_ID = 6;
    private static final int CURSEFORGE_SORT_RELEVANCY = 1;
    private static final int CURSEFORGE_PAGINATION_SIZE = 50;
    private static final int CURSEFORGE_PAGINATION_END_REACHED = -1;
    private static final int CURSEFORGE_PAGINATION_ERROR = -2;

    private final ApiHandler mApiHandler;
    private final CurseForgeProvider mProvider;

    public CurseforgeApi(String apiKey) {
        mApiHandler = new ApiHandler(CurseForgeProvider.OFFICIAL_CURSEFORGE_API_URL, apiKey);
        mProvider = VersionMetadataRepository.getInstance().getCurseForgeProvider();
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        CurseforgeSearchResult curseforgeSearchResult = (CurseforgeSearchResult) previousPageResult;

        HashMap<String, Object> params = new HashMap<>();
        params.put("gameId", CURSEFORGE_MC_GAME_ID);
        params.put("classId", searchFilters.isModpack ? CURSEFORGE_MODPACK_CLASS_ID : CURSEFORGE_MOD_CLASS_ID);
        params.put("searchFilter", searchFilters.name != null ? searchFilters.name : "");
        params.put("sortField", CURSEFORGE_SORT_RELEVANCY);
        params.put("sortOrder", "desc");
        if (searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty()) {
            params.put("gameVersion", searchFilters.mcVersion);
        }
        if (curseforgeSearchResult != null) {
            params.put("index", curseforgeSearchResult.previousOffset);
        }

        JsonObject response = mApiHandler.get("mods/search", params, JsonObject.class);
        if (response == null) return null;
        JsonArray dataArray = GsonJsonUtils.getJsonArraySafe(response, "data");
        if (dataArray == null) return null;
        JsonObject paginationInfo = GsonJsonUtils.getJsonObjectSafe(response, "pagination");

        ModItem[] items = mProvider.parseSearchItems(dataArray, searchFilters.isModpack);
        if (curseforgeSearchResult == null) curseforgeSearchResult = new CurseforgeSearchResult();
        curseforgeSearchResult.results = items;
        curseforgeSearchResult.previousOffset += dataArray.size();
        curseforgeSearchResult.totalResultCount = GsonJsonUtils.getIntSafe(
                paginationInfo, "totalCount", curseforgeSearchResult.previousOffset);
        return curseforgeSearchResult;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        if (item == null || item.id == null || item.id.trim().isEmpty()) return null;
        ArrayList<JsonObject> allModDetails = new ArrayList<>();
        int index = 0;
        while (index != CURSEFORGE_PAGINATION_END_REACHED
                && index != CURSEFORGE_PAGINATION_ERROR) {
            index = getPaginatedDetails(allModDetails, index, item.id.trim());
        }
        if (index == CURSEFORGE_PAGINATION_ERROR) return null;
        return mProvider.parseModDetails(item, allModDetails);
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException {
        return ModpackInstaller.downloadModpack(modDetail, selectedVersion, this::installCurseforgeZip);
    }

    @Override
    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        return ModpackInstaller.installModpack(modpackName, modpackName, modpackFile, icon, this::installCurseforgeZip);
    }

    private int getPaginatedDetails(ArrayList<JsonObject> objectList, int index, String modId) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("index", index);
        params.put("pageSize", CURSEFORGE_PAGINATION_SIZE);

        JsonObject response = mApiHandler.get("mods/" + modId + "/files", params, JsonObject.class);
        JsonArray data = GsonJsonUtils.getJsonArraySafe(response, "data");
        if (data == null) return CURSEFORGE_PAGINATION_ERROR;
        for (int i = 0; i < data.size(); i++) {
            JsonObject fileInfo = GsonJsonUtils.getJsonObjectSafe(data.get(i));
            if (fileInfo == null) continue;
            JsonElement isServerPack = fileInfo.get("isServerPack");
            if (isServerPack != null && !isServerPack.isJsonNull() && isServerPack.getAsBoolean()) {
                continue;
            }
            objectList.add(fileInfo);
        }
        if (data.size() < CURSEFORGE_PAGINATION_SIZE) {
            return CURSEFORGE_PAGINATION_END_REACHED;
        }
        return index + data.size();
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
            CurseForgeProvider.ResolvedCurseSpec spec = mProvider.verifyAndResolveManifest(curseManifest);

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

        // Next query the official download-url endpoint
        JsonObject response = mApiHandler.get("mods/" + projectID + "/files/" + fileID + "/download-url", JsonObject.class);
        String apiUrl = GsonJsonUtils.getStringSafe(response, "data");
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
        JsonObject response = mApiHandler.get("mods/" + projectID + "/files/" + fileID, JsonObject.class);
        return GsonJsonUtils.getJsonObjectSafe(response, "data");
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
