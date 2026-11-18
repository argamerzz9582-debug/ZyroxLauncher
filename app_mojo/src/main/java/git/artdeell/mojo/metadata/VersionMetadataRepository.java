package git.artdeell.mojo.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.tasks.mojson.mirrors.DownloadMirror;
import git.artdeell.mojo.utils.DownloadUtils;

/**
 * Central, UI-independent repository coordinating version, mod loader, and modpack metadata across:
 * <ul>
 *   <li>{@link MojangVersionProvider} (official {@code version_manifest_v2.json})</li>
 *   <li>{@link FabricMetadataProvider} (official Fabric Meta v2 &amp; Legacy Fabric v2)</li>
 *   <li>{@link QuiltMetadataProvider} (official Quilt Meta v3)</li>
 *   <li>{@link ForgeMetadataProvider} (official Forge Maven &amp; promotions)</li>
 *   <li>{@link NeoForgeMetadataProvider} (official NeoForge Maven, 1.20.x–1.21.x &amp; 26.x+)</li>
 *   <li>{@link ModrinthProvider} (official Modrinth v2 &amp; {@code .mrpack} compatibility)</li>
 *   <li>{@link CurseForgeProvider} (official CurseForge v1 &amp; manifest compatibility)</li>
 *   <li>{@link LocalMetadataCache} (atomic persistence, ETag/Last-Modified 304, retry, offline fallback)</li>
 * </ul>
 *
 * <p>Adapts cleanly into existing launcher downloaders, installers, and selectors so newly released
 * Minecraft versions, loaders, and modpacks appear purely as data with zero UI or code changes.
 */
public class VersionMetadataRepository {
    public enum LoaderType {
        FABRIC,
        LEGACY_FABRIC,
        QUILT,
        FORGE,
        NEOFORGE
    }

    public interface CatalogCallback {
        void onCatalogAvailable(@NonNull JVersionList catalog,
                                @NonNull LocalMetadataCache.SyncSource source,
                                boolean changed);

        default void onSyncError(@NonNull Exception error, boolean hasCachedCatalog) {
        }
    }

    private static volatile VersionMetadataRepository sDefaultInstance;

    private final LocalMetadataCache mCache;
    private final MojangVersionProvider mMojangProvider;
    private final FabricMetadataProvider mFabricProvider;
    private final FabricMetadataProvider mLegacyFabricProvider;
    private final QuiltMetadataProvider mQuiltProvider;
    private final ForgeMetadataProvider mForgeProvider;
    private final NeoForgeMetadataProvider mNeoForgeProvider;
    private final ModPlatformRepository mModPlatformRepository;
    private final Executor mSyncExecutor;

    private final AtomicReference<JVersionList> mLatestCatalog = new AtomicReference<>();
    private final AtomicBoolean mManualSyncInFlight = new AtomicBoolean(false);
    private final Object mSyncMonitor = new Object();

    public VersionMetadataRepository(@NonNull LocalMetadataCache cache,
                                     @NonNull MojangVersionProvider.EndpointResolver endpointResolver,
                                     @NonNull Executor syncExecutor) {
        mCache = cache;
        mMojangProvider = new MojangVersionProvider(cache, endpointResolver);
        mFabricProvider = new FabricMetadataProvider(cache);
        mLegacyFabricProvider = new FabricMetadataProvider(
                cache,
                FabricMetadataProvider.OFFICIAL_LEGACY_FABRIC_API_URL,
                "legacy_fabric",
                "Legacy Fabric"
        );
        mQuiltProvider = new QuiltMetadataProvider(cache);
        mForgeProvider = new ForgeMetadataProvider(cache);
        mNeoForgeProvider = new NeoForgeMetadataProvider(cache);
        mModPlatformRepository = ModPlatformRepository.getInstance();
        mSyncExecutor = syncExecutor;
    }

    @NonNull
    public static VersionMetadataRepository getInstance() {
        VersionMetadataRepository instance = sDefaultInstance;
        if (instance == null) {
            synchronized (VersionMetadataRepository.class) {
                instance = sDefaultInstance;
                if (instance == null) {
                    File cacheRoot = new File(Tools.DIR_CACHE, "string_cache");
                    LocalMetadataCache cache = new LocalMetadataCache(cacheRoot);
                    MojangVersionProvider.EndpointResolver resolver = () ->
                            DownloadMirror.getMirrorMapping(
                                    DownloadMirror.DOWNLOAD_CLASS_METADATA,
                                    MojangVersionProvider.OFFICIAL_MANIFEST_V2_URL
                            );
                    Executor executor = Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "zyrox-metadata-sync");
                        t.setDaemon(true);
                        return t;
                    });
                    instance = new VersionMetadataRepository(cache, resolver, executor);
                    sDefaultInstance = instance;
                }
            }
        }
        return instance;
    }

    @NonNull
    public LocalMetadataCache getCache() {
        return mCache;
    }

    @NonNull
    public MojangVersionProvider getMojangProvider() {
        return mMojangProvider;
    }

    @NonNull
    public FabricMetadataProvider getFabricProvider() {
        return mFabricProvider;
    }

    @NonNull
    public FabricMetadataProvider getLegacyFabricProvider() {
        return mLegacyFabricProvider;
    }

    @NonNull
    public QuiltMetadataProvider getQuiltProvider() {
        return mQuiltProvider;
    }

    @NonNull
    public ForgeMetadataProvider getForgeProvider() {
        return mForgeProvider;
    }

    @NonNull
    public NeoForgeMetadataProvider getNeoForgeProvider() {
        return mNeoForgeProvider;
    }

    @NonNull
    public ModPlatformRepository getModPlatformRepository() {
        return mModPlatformRepository;
    }

    @NonNull
    public ModrinthProvider getModrinthProvider() {
        return mModPlatformRepository.getModrinthProvider();
    }

    @NonNull
    public CurseForgeProvider getCurseForgeProvider() {
        return mModPlatformRepository.getCurseForgeProvider();
    }

    @Nullable
    public JVersionList getCurrentCatalog() {
        JVersionList inMemory = mLatestCatalog.get();
        if (inMemory != null) return inMemory;
        JVersionList onDisk = mMojangProvider.getCachedManifest();
        if (onDisk != null) {
            mLatestCatalog.compareAndSet(null, onDisk);
        }
        return mLatestCatalog.get();
    }

    /**
     * Two-phase non-blocking startup synchronization:
     * <ol>
     *   <li>Immediately loads any cached manifest from local storage and publishes it so the UI
     *       has instant access with zero network stall (including offline mode).</li>
     *   <li>Conditionally refreshes from official {@code version_manifest_v2.json} in the
     *       background (using {@code ETag}/{@code If-Modified-Since}) and publishes again only if
     *       new or updated versions are detected.</li>
     * </ol>
     */
    public void syncMinecraftVersionsOnStartup(@NonNull CatalogCallback callback) {
        mSyncExecutor.execute(() -> {
            // Phase 1: Immediate local cached catalog display
            JVersionList cached = mMojangProvider.getCachedManifest();
            if (cached != null) {
                JVersionList previous = mLatestCatalog.getAndSet(cached);
                boolean changed = MojangVersionProvider.hasManifestChanged(previous, cached);
                callback.onCatalogAvailable(cached, LocalMetadataCache.SyncSource.FRESH_CACHE, changed);
            }

            // Phase 2: Conditional background refresh against official Mojang manifest
            // Use forceRefresh=true on startup so brand-new releases published minutes ago appear
            // immediately, while conditional ETag/Last-Modified avoids re-downloading unchanged data.
            synchronized (mSyncMonitor) {
                try {
                    LocalMetadataCache.SyncResult<JVersionList> synced = mMojangProvider.syncManifest(true);
                    JVersionList previous = mLatestCatalog.getAndSet(synced.data);
                    boolean changed = MojangVersionProvider.hasManifestChanged(previous, synced.data);
                    if (cached == null || changed) {
                        callback.onCatalogAvailable(synced.data, synced.source, changed);
                    }
                    if (synced.networkWarning != null && cached != null) {
                        callback.onSyncError(synced.networkWarning, true);
                    }
                } catch (IOException | DownloadUtils.ParseException e) {
                    callback.onSyncError(e, mLatestCatalog.get() != null);
                }
            }
        });
    }

    /**
     * Explicit user-initiated catalog refresh: checks remote manifest immediately with conditional
     * headers, never duplicates version entries, never overwrites a valid cache on failure, and
     * reports the exact sync outcome to the caller.
     */
    public void refreshMinecraftVersions(@NonNull CatalogCallback callback) {
        if (!mManualSyncInFlight.compareAndSet(false, true)) {
            // Coalesce rapid repeated taps behind the active sync monitor without duplicating requests.
            mSyncExecutor.execute(() -> {
                synchronized (mSyncMonitor) {
                    JVersionList current = mLatestCatalog.get();
                    if (current != null) {
                        callback.onCatalogAvailable(current, LocalMetadataCache.SyncSource.NOT_MODIFIED, false);
                    }
                }
            });
            return;
        }

        mSyncExecutor.execute(() -> {
            try {
                synchronized (mSyncMonitor) {
                    LocalMetadataCache.SyncResult<JVersionList> synced = mMojangProvider.syncManifest(true);
                    JVersionList previous = mLatestCatalog.getAndSet(synced.data);
                    boolean changed = MojangVersionProvider.hasManifestChanged(previous, synced.data);
                    callback.onCatalogAvailable(synced.data, synced.source, changed);
                    if (synced.networkWarning != null) {
                        callback.onSyncError(synced.networkWarning, true);
                    }
                }
            } catch (IOException | DownloadUtils.ParseException e) {
                callback.onSyncError(e, mLatestCatalog.get() != null);
            } finally {
                mManualSyncInFlight.set(false);
            }
        });
    }

    /**
     * Verifies whether a {@code (loaderType, minecraftVersion, loaderVersion)} tuple is officially
     * compatible according to its authoritative upstream metadata provider.
     */
    public boolean verifyLoaderCompatibility(@NonNull LoaderType loaderType,
                                             @NonNull String minecraftVersion,
                                             @NonNull String loaderVersion) {
        try {
            switch (loaderType) {
                case FABRIC:
                    return mFabricProvider.isCompatible(minecraftVersion, loaderVersion);
                case LEGACY_FABRIC:
                    return mLegacyFabricProvider.isCompatible(minecraftVersion, loaderVersion);
                case QUILT:
                    return mQuiltProvider.isCompatible(minecraftVersion, loaderVersion);
                case FORGE:
                    return mForgeProvider.resolveCompatibleFullVersion(minecraftVersion, loaderVersion) != null;
                case NEOFORGE:
                    return mNeoForgeProvider.resolveCompatibleFullVersion(minecraftVersion, loaderVersion) != null;
                default:
                    return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Clears all locally cached metadata files and ETag records so the next sync fetches completely
     * fresh metadata across Minecraft, Fabric, Quilt, Forge, and NeoForge.
     */
    public boolean clearAllCaches() {
        synchronized (mSyncMonitor) {
            mLatestCatalog.set(null);
            return mCache.clearAll();
        }
    }
}
