package git.artdeell.mojo.modding.modpacks.api;

import android.util.Log;

import androidx.annotation.NonNull;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.metadata.CurseForgeProvider;
import git.artdeell.mojo.modding.modpacks.api.modloader.LoaderInstaller;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.modding.modpacks.models.SearchResult;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Group all apis under the same umbrella, as another layer of abstraction
 */
public class CommonApi implements ModpackApi {

    private final ModpackApi mCurseforgeApi;
    private final ModpackApi mModrinthApi;
    private final ModpackApi[] mModpackApis;
    private final String mCurseforgeApiKey;

    public static final byte PACK_MODRINTH = 1;
    public static final byte PACK_CURSEFORGE = 2;
    public static final byte PACK_UNDEFINED = 0;

    public CommonApi(String curseforgeApiKey) {
        mCurseforgeApiKey = curseforgeApiKey;
        mModrinthApi = new ModrinthApi();
        if (!CurseForgeProvider.isApiKeyConfigured(curseforgeApiKey)) {
            mCurseforgeApi = null;
            mModpackApis = new ModpackApi[]{mModrinthApi};
        } else {
            mCurseforgeApi = new CurseforgeApi(curseforgeApiKey);
            mModpackApis = new ModpackApi[]{mModrinthApi, mCurseforgeApi};
        }
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        // Explicit CurseForge tab selection: check API key & rate-limit window, then seamlessly
        // fall back to Modrinth with a non-intrusive status banner instead of failing or emptying.
        if (searchFilters != null && searchFilters.platform == SearchFilters.PLATFORM_CURSEFORGE) {
            CommonApiSearchResult prevCommon = (previousPageResult instanceof CommonApiSearchResult)
                    ? (CommonApiSearchResult) previousPageResult : null;
            if (mCurseforgeApi != null && CurseForgeProvider.canQueryCurseForge(mCurseforgeApiKey)
                    && searchFilters.projectTypeOverride == null) {
                SearchResult prevCf = (prevCommon != null && prevCommon.searchResults.length > 1)
                        ? prevCommon.searchResults[1] : null;
                try {
                    SearchResult cfRes = mCurseforgeApi.searchMod(searchFilters, prevCf);
                    if (cfRes != null && cfRes.results != null) {
                        CommonApiSearchResult wrapped = prevCommon != null ? prevCommon : new CommonApiSearchResult();
                        wrapped.searchResults = new SearchResult[]{null, cfRes};
                        wrapped.results = cfRes.results;
                        wrapped.totalResultCount = cfRes.totalResultCount;
                        wrapped.fallbackNotice = null;
                        return wrapped;
                    }
                } catch (Exception e) {
                    Log.w("CommonApi", "CurseForge search failed, falling back to Modrinth: " + e.getMessage());
                }
            }
            SearchFilters mrFilters = copyFiltersForModrinth(searchFilters);
            SearchResult prevMr = (prevCommon != null && prevCommon.searchResults.length > 0)
                    ? prevCommon.searchResults[0] : null;
            SearchResult mrRes = mModrinthApi.searchMod(mrFilters, prevMr);
            if (mrRes == null) return null;
            CommonApiSearchResult wrapped = prevCommon != null ? prevCommon : new CommonApiSearchResult();
            wrapped.searchResults = new SearchResult[]{mrRes};
            wrapped.results = mrRes.results != null ? mrRes.results : new ModItem[0];
            wrapped.totalResultCount = mrRes.totalResultCount;
            wrapped.fallbackNotice = CurseForgeProvider.buildModrinthFallbackNotice(mCurseforgeApiKey);
            return wrapped;
        }

        CommonApiSearchResult commonApiSearchResult = (previousPageResult instanceof CommonApiSearchResult)
                ? (CommonApiSearchResult) previousPageResult : null;
        SearchResult[] results = (commonApiSearchResult == null
                || commonApiSearchResult.searchResults.length != mModpackApis.length)
                ? new SearchResult[mModpackApis.length] : commonApiSearchResult.searchResults;

        int totalSize = 0;
        boolean curseForgeDegraded = false;
        boolean allowCurseForge = searchFilters == null
                || (searchFilters.platform == SearchFilters.PLATFORM_ALL
                && searchFilters.projectTypeOverride == null);

        Future<?>[] futures = new Future<?>[mModpackApis.length];
        for (int i = 0; i < mModpackApis.length; i++) {
            ModpackApi api = mModpackApis[i];
            if (searchFilters != null) {
                if (searchFilters.platform == SearchFilters.PLATFORM_MODRINTH && api != mModrinthApi) continue;
                if (searchFilters.projectTypeOverride != null && api != mModrinthApi) continue;
            }
            if (api == mCurseforgeApi && !CurseForgeProvider.canQueryCurseForge(mCurseforgeApiKey)) {
                curseForgeDegraded = allowCurseForge && CurseForgeProvider.isRateLimitedOrBlocked();
                continue;
            }
            if (results[i] != null && results[i].results != null && results[i].results.length == 0) continue;
            if (previousPageResult != null && results[i] == null) continue;
            futures[i] = MojoApplication.sExecutorService.submit(
                    new ApiDownloadTask(i, searchFilters, results[i]));
        }

        if (Thread.interrupted()) {
            cancelAllFutures(futures);
            return null;
        }
        boolean hasSuccessful = false;
        for (int i = 0; i < mModpackApis.length; i++) {
            Future<?> future = futures[i];
            if (future == null) continue;
            try {
                SearchResult searchResult = results[i] = (SearchResult) future.get();
                if (searchResult != null && searchResult.results != null) {
                    hasSuccessful = true;
                    totalSize += searchResult.totalResultCount;
                } else if (mModpackApis[i] == mCurseforgeApi && allowCurseForge) {
                    curseForgeDegraded = true;
                }
            } catch (InterruptedException ie) {
                cancelAllFutures(futures);
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                results[i] = null;
                Log.w("CommonApi", "Mod platform query degraded: " + e.getMessage());
                if (mModpackApis[i] == mCurseforgeApi && allowCurseForge) {
                    curseForgeDegraded = true;
                }
            }
        }
        if (!hasSuccessful) {
            return null;
        }
        ArrayList<ModItem[]> filteredResults = new ArrayList<>(results.length);
        for (SearchResult result : results) {
            if (result == null || result.results == null || result.results.length == 0) continue;
            filteredResults.add(result.results);
        }
        filteredResults.trimToSize();
        if (Thread.interrupted()) return null;

        ModItem[] concatenatedItems = buildFusedResponse(filteredResults);
        if (Thread.interrupted()) return null;
        if (commonApiSearchResult == null) commonApiSearchResult = new CommonApiSearchResult();
        commonApiSearchResult.searchResults = results;
        commonApiSearchResult.totalResultCount = totalSize;
        commonApiSearchResult.results = concatenatedItems;
        commonApiSearchResult.fallbackNotice = curseForgeDegraded
                ? CurseForgeProvider.buildModrinthFallbackNotice(mCurseforgeApiKey) : null;
        return commonApiSearchResult;
    }

    private static SearchFilters copyFiltersForModrinth(@NonNull SearchFilters src) {
        SearchFilters copy = new SearchFilters();
        copy.isModpack = src.isModpack;
        copy.name = src.name;
        copy.mcVersion = src.mcVersion;
        copy.platform = SearchFilters.PLATFORM_MODRINTH;
        copy.category = src.category;
        copy.modloader = src.modloader;
        copy.projectTypeOverride = src.projectTypeOverride;
        return copy;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        if (item == null) return null;
        Log.i("CommonApi", "Invoking getModDetails on item.apiSource=" + item.apiSource + " item.title=" + item.title);
        ModpackApi api = getModpackApi(item.apiSource);
        return api != null ? api.getModDetails(item) : null;
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException {
        if (modDetail == null) throw new IOException("Modpack metadata is unavailable");
        ModpackApi api = getModpackApi(modDetail.apiSource);
        if (api == null) {
            throw new IOException("CurseForge API key is not configured for this build");
        }
        return api.installModpack(modDetail, selectedVersion);
    }

    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        short s = checkModpack(modpackFile);
        switch (s) {
            case PACK_MODRINTH:
                return mModrinthApi.installLocalModpack(modpackName, modpackFile, icon);
            case PACK_CURSEFORGE:
                if (mCurseforgeApi == null) {
                    throw new IOException("CurseForge API key is not configured for CurseForge modpack downloads");
                }
                return mCurseforgeApi.installLocalModpack(modpackName, modpackFile, icon);
            case PACK_UNDEFINED:
                modpackFile.delete();
                return null;
            default:
                return null;
        }
    }

    private ModpackApi getModpackApi(int apiSource) {
        switch (apiSource) {
            case Constants.SOURCE_MODRINTH:
                return mModrinthApi;
            case Constants.SOURCE_CURSEFORGE:
                return mCurseforgeApi;
            default:
                throw new UnsupportedOperationException("Unknown API source: " + apiSource);
        }
    }

    public static short checkModpack(File outFile) {
        try (ZipFile zipFile = new ZipFile(outFile)) {
            ZipEntry modrinth = zipFile.getEntry("modrinth.index.json");
            ZipEntry curseforge = zipFile.getEntry("manifest.json");
            if (modrinth != null) {
                return CommonApi.PACK_MODRINTH;
            }
            if (curseforge != null) {
                return CommonApi.PACK_CURSEFORGE;
            }
            return CommonApi.PACK_UNDEFINED; // return this if no modpack was detected
        } catch (Exception e) {
            return -1;
        }
    }

    /** Fuse the arrays in a way that's fair for every endpoint */
    private ModItem[] buildFusedResponse(List<ModItem[]> modMatrix){
        int totalSize = 0;

        // Calculate the total size of the merged array
        for (ModItem[] array : modMatrix) {
            totalSize += array.length;
        }

        ModItem[] fusedItems = new ModItem[totalSize];

        int mergedIndex = 0;
        int maxLength = 0;

        // Find the maximum length of arrays
        for (ModItem[] array : modMatrix) {
            if (array.length > maxLength) {
                maxLength = array.length;
            }
        }

        // Populate the merged array
        for (int i = 0; i < maxLength; i++) {
            for (ModItem[] matrix : modMatrix) {
                if (i < matrix.length) {
                    fusedItems[mergedIndex] = matrix[i];
                    mergedIndex++;
                }
            }
        }

        return fusedItems;
    }

    private void cancelAllFutures(Future<?>[] futures) {
        for(Future<?> future : futures) {
            if(future == null) continue;
            future.cancel(true);
        }
    }

    private class ApiDownloadTask implements Callable<SearchResult> {
        private final int mModApi;
        private final SearchFilters mSearchFilters;
        private final SearchResult mPreviousPageResult;

        private ApiDownloadTask(int modApi, SearchFilters searchFilters, SearchResult previousPageResult) {
            this.mModApi = modApi;
            this.mSearchFilters = searchFilters;
            this.mPreviousPageResult = previousPageResult;
        }

        @Override
        public SearchResult call() {
            return mModpackApis[mModApi].searchMod(mSearchFilters, mPreviousPageResult);
        }
    }

    class CommonApiSearchResult extends SearchResult {
        SearchResult[] searchResults = new SearchResult[mModpackApis.length];
    }
}
