package git.artdeell.mojo.ai;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.ai.AiFriendConfigStore.ConfigData;
import git.artdeell.mojo.ai.AiFriendModels.ModelLoadState;
import git.artdeell.mojo.ai.AiFriendModels.ResultCallback;
import git.artdeell.mojo.ai.AiFriendModels.StateListener;
import git.artdeell.mojo.ai.AiFriendModels.TransferProgressListener;
import git.artdeell.mojo.ai.GgufRamAdvisor.RamTier;
import git.artdeell.mojo.ai.LlamaCppRuntimeBridge.ActiveModelSession;
import git.artdeell.mojo.ai.LlamaCppRuntimeBridge.GgufHeaderInfo;
import git.artdeell.mojo.utils.NetworkPolicyHelper;
import git.artdeell.mojo.utils.ZyroxHttpClient;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Complete on-device {@code .gguf} model manager for Zyrox AI Friend:
 * <ul>
 *   <li><b>Storage Import:</b> streams {@link Uri} documents from {@code OpenDocument} / SAF into
 *   {@code files/ai_gguf_models/}, verifying the {@code "GGUF"} magic header up-front and committing
 *   atomically from {@code .part} to {@code .gguf}.</li>
 *   <li><b>RAM-Based Model Advisor:</b> integrates {@link GgufRamAdvisor} to highlight safe vs.
 *   high-risk models for the user's phone RAM.</li>
 *   <li><b>Built-In Resumable Downloader:</b> curated HTTPS GGUF catalog with HTTP {@code Range}
 *   resume ({@code .part}), byte/percent/speed progress, cancellation, and retry recovery.</li>
 *   <li><b>Explicit Load / Unload + Auto-Unload on Game Launch:</b> loads models into
 *   {@link LlamaCppRuntimeBridge} on demand and frees native/JVM memory immediately on Unload or
 *   prior to launching {@code GameActivity}.</li>
 * </ul>
 */
public final class GgufModelManager {

    private static final String TAG = "GgufModelManager";
    private static final int STREAM_BUFFER_BYTES = 64 * 1024;

    /** Entry in the built-in curated GGUF catalog. */
    public static final class CatalogModel {
        @NonNull public final String id;
        @NonNull public final String title;
        @NonNull public final String fileName;
        @NonNull public final String parameterBadge;
        @NonNull public final String quantization;
        public final long approximateBytes;
        @NonNull public final RamTier minimumTier;
        @NonNull public final String description;
        @NonNull public final String downloadUrl;

        public CatalogModel(@NonNull String id,
                            @NonNull String title,
                            @NonNull String fileName,
                            @NonNull String parameterBadge,
                            @NonNull String quantization,
                            long approximateBytes,
                            @NonNull RamTier minimumTier,
                            @NonNull String description,
                            @NonNull String downloadUrl) {
            this.id = id;
            this.title = title;
            this.fileName = fileName;
            this.parameterBadge = parameterBadge;
            this.quantization = quantization;
            this.approximateBytes = approximateBytes;
            this.minimumTier = minimumTier;
            this.description = description;
            this.downloadUrl = downloadUrl;
        }
    }

    /** Installed local {@code .gguf} file discovered inside {@code files/ai_gguf_models/}. */
    public static final class LocalModelEntry {
        @NonNull public final File file;
        @NonNull public final String fileName;
        public final long sizeBytes;
        public final long lastModifiedMs;
        public final boolean selected;
        public final boolean loaded;
        @Nullable public final CatalogModel matchedCatalog;

        public LocalModelEntry(@NonNull File file,
                               boolean selected,
                               boolean loaded,
                               @Nullable CatalogModel matchedCatalog) {
            this.file = file;
            this.fileName = file.getName();
            this.sizeBytes = file.length();
            this.lastModifiedMs = file.lastModified();
            this.selected = selected;
            this.loaded = loaded;
            this.matchedCatalog = matchedCatalog;
        }
    }

    /** Live download progress snapshot for UI cards. */
    public static final class DownloadStateSnapshot {
        @NonNull public final String modelId;
        public final boolean active;
        public final int percent;
        public final long downloadedBytes;
        public final long totalBytes;
        public final long bytesPerSecond;
        @Nullable public final String lastError;

        public DownloadStateSnapshot(@NonNull String modelId,
                                     boolean active,
                                     int percent,
                                     long downloadedBytes,
                                     long totalBytes,
                                     long bytesPerSecond,
                                     @Nullable String lastError) {
            this.modelId = modelId;
            this.active = active;
            this.percent = percent;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.bytesPerSecond = bytesPerSecond;
            this.lastError = lastError;
        }
    }

    /** Curated quantized GGUF models covering 4 GB -> 12 GB+ Android devices over HTTPS. */
    public static final List<CatalogModel> BUILTIN_CATALOG = Collections.unmodifiableList(Arrays.asList(
            new CatalogModel(
                    "qwen2.5-0.5b-q4_k_m",
                    "Qwen 2.5 · 0.5B Instruct",
                    "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                    "0.5B",
                    "Q4_K_M",
                    491L * 1024L * 1024L,
                    RamTier.ULTRA_LIGHT_4GB,
                    "Ultra-lightweight & fast JSON command following — ideal for <= 4 GB RAM phones.",
                    "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
            ),
            new CatalogModel(
                    "llama3.2-1b-q4_k_m",
                    "Llama 3.2 · 1B Instruct",
                    "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                    "1B",
                    "Q4_K_M",
                    808L * 1024L * 1024L,
                    RamTier.ULTRA_LIGHT_4GB,
                    "Compact Meta Llama 3.2 1B quantized for 4 GB–6 GB devices.",
                    "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
            ),
            new CatalogModel(
                    "qwen2.5-1.5b-q4_k_m",
                    "Qwen 2.5 · 1.5B Instruct",
                    "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                    "1.5B",
                    "Q4_K_M",
                    1120L * 1024L * 1024L,
                    RamTier.BALANCED_6GB,
                    "Sweet-spot 6 GB RAM model with strong structured JSON adherence and survival knowledge.",
                    "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
            ),
            new CatalogModel(
                    "llama3.2-3b-q4_k_m",
                    "Llama 3.2 · 3B Instruct",
                    "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                    "3B",
                    "Q4_K_M",
                    2020L * 1024L * 1024L,
                    RamTier.CAPABLE_8GB,
                    "Rich roleplay & tactical reasoning for 6 GB–8 GB+ devices.",
                    "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
            ),
            new CatalogModel(
                    "qwen2.5-3b-q4_k_m",
                    "Qwen 2.5 · 3B Instruct",
                    "qwen2.5-3b-instruct-q4_k_m.gguf",
                    "3B",
                    "Q4_K_M",
                    2105L * 1024L * 1024L,
                    RamTier.CAPABLE_8GB,
                    "Crisp JSON formatting and detailed Minecraft mechanics for 8 GB+ devices.",
                    "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"
            ),
            new CatalogModel(
                    "qwen2.5-7b-q4_k_m",
                    "Qwen 2.5 · 7B Instruct",
                    "qwen2.5-7b-instruct-q4_k_m.gguf",
                    "7B",
                    "Q4_K_M",
                    4680L * 1024L * 1024L,
                    RamTier.FLAGSHIP_12GB_PLUS,
                    "Desktop-class 7B assistant for 12 GB+ flagship phones (unload before heavy modpacks).",
                    "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf"
            )
    ));

    private static final class ActiveDownloadControl {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile Call activeCall;
    }

    private final File modelsDir;
    private final AiFriendConfigStore configStore;
    private final LlamaCppRuntimeBridge runtimeBridge;
    private final ExecutorService executor;
    @Nullable private final StateListener stateNotifier;

    private final Map<String, ActiveDownloadControl> activeDownloads = new ConcurrentHashMap<>();
    private final Map<String, DownloadStateSnapshot> downloadSnapshots = new ConcurrentHashMap<>();

    private final Object lifecycleLock = new Object();
    @NonNull private volatile ModelLoadState loadState = ModelLoadState.UNLOADED;
    @NonNull private volatile String statusMessage = "No local model loaded";

    public GgufModelManager(@NonNull File baseDataDir,
                            @NonNull AiFriendConfigStore configStore,
                            @NonNull LlamaCppRuntimeBridge runtimeBridge,
                            @NonNull ExecutorService executor,
                            @Nullable StateListener stateNotifier) {
        this.modelsDir = new File(baseDataDir, "ai_gguf_models");
        if (!this.modelsDir.exists() && !this.modelsDir.mkdirs()) {
            Log.w(TAG, "Unable to create ai_gguf_models directory");
        }
        this.configStore = configStore;
        this.runtimeBridge = runtimeBridge;
        this.executor = executor;
        this.stateNotifier = stateNotifier;
    }

    @NonNull
    public File getModelsDirectory() {
        return modelsDir;
    }

    @NonNull
    public ModelLoadState getLoadState() {
        return loadState;
    }

    @NonNull
    public String getStatusMessage() {
        return statusMessage;
    }

    @Nullable
    public String getLoadedModelFileName() {
        ActiveModelSession s = runtimeBridge.getActiveSession();
        return s != null ? s.modelFile.getName() : null;
    }

    @NonNull
    public LlamaCppRuntimeBridge getRuntimeBridge() {
        return runtimeBridge;
    }

    @Nullable
    public DownloadStateSnapshot getDownloadSnapshot(@NonNull String modelId) {
        return downloadSnapshots.get(modelId);
    }

    public boolean isDownloading(@NonNull String modelId) {
        return activeDownloads.containsKey(modelId);
    }

    public boolean hasResumablePartialDownload(@NonNull CatalogModel model) {
        File part = new File(modelsDir, model.fileName + ".part");
        return part.isFile() && part.length() > 0L;
    }

    public long getPartialDownloadBytes(@NonNull CatalogModel model) {
        File part = new File(modelsDir, model.fileName + ".part");
        return part.isFile() ? part.length() : 0L;
    }

    /** Lists all valid {@code .gguf} files currently stored in {@code files/ai_gguf_models/}. */
    @NonNull
    public List<LocalModelEntry> listInstalledModels() {
        List<LocalModelEntry> entries = new ArrayList<>();
        File[] files = modelsDir.listFiles();
        if (files == null) return entries;

        String selectedName = configStore.load().selectedGgufFileName;
        String loadedName = getLoadedModelFileName();

        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) continue;
            CatalogModel matched = findCatalogByFileName(name);
            boolean isSelected = name.equals(selectedName);
            boolean isLoaded = name.equals(loadedName) && runtimeBridge.isModelLoaded();
            entries.add(new LocalModelEntry(f, isSelected, isLoaded, matched));
        }

        Collections.sort(entries, new Comparator<LocalModelEntry>() {
            @Override
            public int compare(LocalModelEntry a, LocalModelEntry b) {
                if (a.loaded != b.loaded) return a.loaded ? -1 : 1;
                if (a.selected != b.selected) return a.selected ? -1 : 1;
                return a.fileName.compareToIgnoreCase(b.fileName);
            }
        });
        return entries;
    }

    public boolean isCatalogModelInstalled(@NonNull CatalogModel model) {
        File target = new File(modelsDir, model.fileName);
        return target.isFile() && target.length() >= 24L;
    }

    public void selectModel(@NonNull String fileName) {
        ConfigData cfg = configStore.load();
        cfg.selectedGgufFileName = fileName;
        configStore.save(cfg);
        notifyStateChangedOnMain();
    }

    public boolean deleteModel(@NonNull String fileName) {
        String safeName = sanitizeGgufFileName(fileName);
        String loaded = getLoadedModelFileName();
        if (safeName.equals(loaded)) {
            unloadModelSync("Unloaded deleted model " + safeName);
        }
        File mainFile = new File(modelsDir, safeName);
        File partFile = new File(modelsDir, safeName + ".part");
        boolean ok = true;
        if (mainFile.exists() && !mainFile.delete()) ok = false;
        if (partFile.exists() && !partFile.delete()) ok = false;

        ConfigData cfg = configStore.load();
        if (safeName.equals(cfg.selectedGgufFileName)) {
            List<LocalModelEntry> remaining = listInstalledModels();
            cfg.selectedGgufFileName = remaining.isEmpty() ? "" : remaining.get(0).fileName;
            configStore.save(cfg);
        }
        notifyStateChangedOnMain();
        return ok;
    }

    /**
     * Imports a user-picked {@code .gguf} file via SAF ({@code OpenDocument}) onto internal app
     * storage on a background thread with magic verification and atomic {@code .part} rename.
     */
    public void importModelFromUriAsync(@NonNull final Context appContext,
                                        @NonNull final Uri documentUri,
                                        @Nullable final TransferProgressListener progressListener,
                                        @NonNull final ResultCallback<LocalModelEntry> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    LocalModelEntry imported = importModelFromUriBlocking(
                            appContext, documentUri, progressListener);
                    postSuccess(callback, imported);
                    notifyStateChangedOnMain();
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Failed importing .gguf file";
                    postError(callback, msg);
                }
            }
        });
    }

    @NonNull
    LocalModelEntry importModelFromUriBlocking(@NonNull Context context,
                                               @NonNull Uri uri,
                                               @Nullable final TransferProgressListener progressListener) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        DocumentMeta meta = queryDocumentMeta(resolver, uri);
        String targetName = sanitizeGgufFileName(meta.displayName);

        if (meta.sizeBytes > 0L && !NetworkPolicyHelper.hasEnoughDiskSpace(modelsDir, meta.sizeBytes)) {
            throw new IOException("Not enough internal storage space to import "
                    + targetName + " (" + GgufRamAdvisor.formatBytes(meta.sizeBytes) + ").");
        }

        File tempPart = new File(modelsDir, targetName + ".import.part");
        File finalTarget = new File(modelsDir, targetName);
        if (tempPart.exists() && !tempPart.delete()) {
            Log.w(TAG, "Overwriting stale import part file");
        }

        boolean committed = false;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Android storage provider returned an empty stream for the selected file.");
            }
            try (FileOutputStream out = new FileOutputStream(tempPart)) {
                byte[] buf = new byte[STREAM_BUFFER_BYTES];
                long copied = 0L;
                boolean checkedMagic = false;
                byte[] headerCheck = new byte[8];
                int headerCheckLen = 0;

                long startMs = System.currentTimeMillis();
                long lastUiUpdateMs = 0L;

                int n;
                while ((n = in.read(buf)) != -1) {
                    if (!checkedMagic) {
                        int take = Math.min(8 - headerCheckLen, n);
                        if (take > 0) {
                            System.arraycopy(buf, 0, headerCheck, headerCheckLen, take);
                            headerCheckLen += take;
                        }
                        if (headerCheckLen >= 8) {
                            checkedMagic = true;
                            if (!LlamaCppRuntimeBridge.hasValidGgufPrefix(headerCheck, headerCheckLen)) {
                                throw new IOException("Selected file is not a valid GGUF v1–v3 model (missing 'GGUF' header).");
                            }
                        }
                    }

                    out.write(buf, 0, n);
                    copied += n;

                    long now = System.currentTimeMillis();
                    if (progressListener != null && now - lastUiUpdateMs >= 120L) {
                        lastUiUpdateMs = now;
                        long elapsedSec = Math.max(1L, (now - startMs) / 1000L);
                        final long speed = copied / elapsedSec;
                        final int pct = meta.sizeBytes > 0L
                                ? (int) Math.min(99L, (copied * 100L) / meta.sizeBytes)
                                : -1;
                        final long copiedSnap = copied;
                        final long totalSnap = meta.sizeBytes;
                        final String idSnap = targetName;
                        postOnMain(new Runnable() {
                            @Override
                            public void run() {
                                progressListener.onProgress(idSnap, pct, copiedSnap, totalSnap, speed);
                            }
                        });
                    }
                }
                out.getFD().sync();
            }

            GgufHeaderInfo header = LlamaCppRuntimeBridge.inspectGgufFile(tempPart);
            if (!header.valid) {
                throw new IOException(header.diagnostic);
            }

            if (finalTarget.exists() && !finalTarget.delete()) {
                throw new IOException("Unable to replace existing model file " + targetName);
            }
            if (!tempPart.renameTo(finalTarget)) {
                throw new IOException("Failed committing imported GGUF file into app storage");
            }
            committed = true;

            ConfigData cfg = configStore.load();
            cfg.selectedGgufFileName = finalTarget.getName();
            configStore.save(cfg);

            return new LocalModelEntry(
                    finalTarget,
                    true,
                    finalTarget.getName().equals(getLoadedModelFileName()),
                    findCatalogByFileName(finalTarget.getName())
            );
        } finally {
            if (!committed && tempPart.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempPart.delete();
            }
        }
    }

    /**
     * Downloads (or resumes from {@code .part}) a built-in catalog GGUF model over HTTPS.
     */
    public void downloadCatalogModelAsync(@NonNull final CatalogModel model,
                                          @Nullable final TransferProgressListener progressListener,
                                          @NonNull final ResultCallback<LocalModelEntry> callback) {
        if (activeDownloads.containsKey(model.id)) {
            postError(callback, model.title + " is already downloading.");
            return;
        }

        final ActiveDownloadControl control = new ActiveDownloadControl();
        activeDownloads.put(model.id, control);
        updateDownloadSnapshot(model.id, true, 0, getPartialDownloadBytes(model), model.approximateBytes, 0L, null);
        notifyStateChangedOnMain();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                File partFile = new File(modelsDir, model.fileName + ".part");
                File finalFile = new File(modelsDir, model.fileName);
                try {
                    long existing = partFile.isFile() ? partFile.length() : 0L;
                    long needed = Math.max(64L * 1024L * 1024L, model.approximateBytes - existing);
                    if (!NetworkPolicyHelper.hasEnoughDiskSpace(modelsDir, needed)) {
                        throw new IOException("Insufficient free storage space for " + model.title
                                + " (needs ~" + GgufRamAdvisor.formatBytes(needed) + ").");
                    }

                    Request.Builder reqBuilder = new Request.Builder()
                            .url(model.downloadUrl)
                            .header("User-Agent", ZyroxHttpClient.USER_AGENT);
                    if (existing > 0L) {
                        reqBuilder.header("Range", "bytes=" + existing + "-");
                    }

                    Call call = ZyroxHttpClient.downloadClient().newCall(reqBuilder.build());
                    control.activeCall = call;

                    try (Response response = call.execute()) {
                        int code = response.code();
                        if (code == 416 && existing > 0L) {
                            // Range out of bounds -> reset stale .part and inform user to retry cleanly
                            //noinspection ResultOfMethodCallIgnored
                            partFile.delete();
                            throw new IOException("Cleared stale partial download; tap Download again to restart cleanly.");
                        }
                        if (code < 200 || code >= 300) {
                            throw new IOException("Download failed with HTTP " + code);
                        }

                        boolean append = (code == 206 && existing > 0L);
                        long baseBytes = append ? existing : 0L;
                        ResponseBody body = response.body();
                        if (body == null) {
                            throw new IOException("Server returned an empty response body");
                        }
                        long contentLength = body.contentLength();
                        final long totalBytes = contentLength > 0L
                                ? (baseBytes + contentLength)
                                : model.approximateBytes;

                        try (InputStream in = body.byteStream();
                             FileOutputStream out = new FileOutputStream(partFile, append)) {
                            byte[] buf = new byte[STREAM_BUFFER_BYTES];
                            long transferred = baseBytes;
                            long startMs = System.currentTimeMillis();
                            long sessionRead = 0L;
                            long lastNotifyMs = 0L;

                            int n;
                            while ((n = in.read(buf)) != -1) {
                                if (control.cancelled.get()) {
                                    throw new IOException("Download cancelled (partial progress saved for resume).");
                                }
                                out.write(buf, 0, n);
                                transferred += n;
                                sessionRead += n;

                                long now = System.currentTimeMillis();
                                if (now - lastNotifyMs >= 150L) {
                                    lastNotifyMs = now;
                                    long elapsedMs = Math.max(1L, now - startMs);
                                    final long bps = (sessionRead * 1000L) / elapsedMs;
                                    final int pct = totalBytes > 0L
                                            ? (int) Math.min(99L, (transferred * 100L) / totalBytes)
                                            : 0;
                                    final long dlSnap = transferred;
                                    updateDownloadSnapshot(model.id, true, pct, dlSnap, totalBytes, bps, null);
                                    if (progressListener != null) {
                                        postOnMain(new Runnable() {
                                            @Override
                                            public void run() {
                                                progressListener.onProgress(model.id, pct, dlSnap, totalBytes, bps);
                                            }
                                        });
                                    }
                                    notifyStateChangedOnMain();
                                }
                            }
                            out.getFD().sync();
                        }
                    }

                    GgufHeaderInfo header = LlamaCppRuntimeBridge.inspectGgufFile(partFile);
                    if (!header.valid) {
                        // Corrupt payload — remove invalid .part so next retry is clean
                        //noinspection ResultOfMethodCallIgnored
                        partFile.delete();
                        throw new IOException("Downloaded file failed GGUF verification: " + header.diagnostic);
                    }

                    if (finalFile.exists() && !finalFile.delete()) {
                        throw new IOException("Unable to overwrite existing file " + finalFile.getName());
                    }
                    if (!partFile.renameTo(finalFile)) {
                        throw new IOException("Failed renaming downloaded .part file to " + finalFile.getName());
                    }

                    ConfigData cfg = configStore.load();
                    if (cfg.selectedGgufFileName.isEmpty()) {
                        cfg.selectedGgufFileName = finalFile.getName();
                        configStore.save(cfg);
                    }

                    updateDownloadSnapshot(model.id, false, 100, finalFile.length(), finalFile.length(), 0L, null);
                    LocalModelEntry installed = new LocalModelEntry(finalFile,
                            finalFile.getName().equals(configStore.load().selectedGgufFileName),
                            false, model);
                    postSuccess(callback, installed);
                } catch (Exception e) {
                    String err = e.getMessage() != null ? e.getMessage() : "Download failed";
                    long partial = partFile.isFile() ? partFile.length() : 0L;
                    int pct = model.approximateBytes > 0L
                            ? (int) Math.min(99L, (partial * 100L) / model.approximateBytes)
                            : 0;
                    updateDownloadSnapshot(model.id, false, pct, partial, model.approximateBytes, 0L, err);
                    postError(callback, err);
                } finally {
                    activeDownloads.remove(model.id);
                    notifyStateChangedOnMain();
                }
            }
        });
    }

    public void cancelDownload(@NonNull String modelId) {
        ActiveDownloadControl ctrl = activeDownloads.get(modelId);
        if (ctrl != null) {
            ctrl.cancelled.set(true);
            Call c = ctrl.activeCall;
            if (c != null) {
                c.cancel();
            }
        }
    }

    /** Explicitly loads the selected (or specified) {@code .gguf} model on a background thread. */
    public void loadModelAsync(@Nullable final String optionalFileName,
                               @Nullable final ResultCallback<ActiveModelSession> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ActiveModelSession session = loadModelBlocking(optionalFileName);
                    if (callback != null) postSuccess(callback, session);
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Failed loading GGUF model";
                    if (callback != null) postError(callback, msg);
                }
            }
        });
    }

    @NonNull
    public ActiveModelSession loadModelBlocking(@Nullable String optionalFileName) throws IOException {
        synchronized (lifecycleLock) {
            ConfigData cfg = configStore.load();
            String targetName = (optionalFileName != null && !optionalFileName.trim().isEmpty())
                    ? sanitizeGgufFileName(optionalFileName)
                    : cfg.selectedGgufFileName;

            if (targetName == null || targetName.trim().isEmpty()) {
                List<LocalModelEntry> installed = listInstalledModels();
                if (installed.isEmpty()) {
                    loadState = ModelLoadState.UNLOADED;
                    statusMessage = "No .gguf model installed yet";
                    notifyStateChangedOnMain();
                    throw new IOException("No .gguf model found. Download a built-in model or import a .gguf file first.");
                }
                targetName = installed.get(0).fileName;
            }

            File modelFile = new File(modelsDir, targetName);
            if (!modelFile.isFile()) {
                loadState = ModelLoadState.ERROR;
                statusMessage = "Missing file: " + targetName;
                notifyStateChangedOnMain();
                throw new IOException("Model file does not exist: " + targetName);
            }

            loadState = ModelLoadState.LOADING;
            statusMessage = "Loading " + targetName + "...";
            notifyStateChangedOnMain();

            try {
                ActiveModelSession session = runtimeBridge.loadModel(
                        modelFile,
                        cfg.ggufContextSize,
                        cfg.ggufThreads,
                        cfg.localLoopbackServerPort
                );
                cfg = configStore.load();
                cfg.selectedGgufFileName = modelFile.getName();
                configStore.save(cfg);

                loadState = ModelLoadState.LOADED;
                statusMessage = "Loaded " + modelFile.getName() + " (" + session.header.diagnostic + ")";
                notifyStateChangedOnMain();
                return session;
            } catch (IOException e) {
                loadState = ModelLoadState.ERROR;
                statusMessage = "Load failed: " + e.getMessage();
                notifyStateChangedOnMain();
                throw e;
            }
        }
    }

    /** Explicitly unloads the active {@code .gguf} model asynchronously to free RAM. */
    public void unloadModelAsync(@Nullable final ResultCallback<Boolean> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean released = unloadModelSync("Unloaded by user — RAM freed");
                if (callback != null) {
                    postSuccess(callback, released);
                }
            }
        });
    }

    /** Synchronous unload used by Auto-Unload on Game Launch and OS low-memory callbacks. */
    public boolean unloadModelSync(@NonNull String reason) {
        synchronized (lifecycleLock) {
            if (!runtimeBridge.isModelLoaded() && loadState == ModelLoadState.UNLOADED) {
                return false;
            }
            loadState = ModelLoadState.UNLOADING;
            notifyStateChangedOnMain();

            boolean released = runtimeBridge.unloadModel();
            loadState = ModelLoadState.UNLOADED;
            statusMessage = reason;
            notifyStateChangedOnMain();
            return released;
        }
    }

    /**
     * Triggered right before {@code GameActivity} launches: honors the {@code Auto-Unload on Game Launch}
     * preference so Minecraft's JVM receives maximum free device RAM.
     */
    public boolean autoUnloadForGameLaunchIfEnabled() {
        ConfigData cfg = configStore.load();
        if (!cfg.autoUnloadOnGameLaunch) {
            return false;
        }
        if (!runtimeBridge.isModelLoaded()) {
            return false;
        }
        Log.i(TAG, "Auto-unloading local GGUF model prior to Minecraft launch to free device RAM");
        return unloadModelSync("Auto-unloaded before Minecraft launch");
    }

    @Nullable
    public static CatalogModel findCatalogByFileName(@Nullable String fileName) {
        if (fileName == null) return null;
        for (CatalogModel c : BUILTIN_CATALOG) {
            if (c.fileName.equalsIgnoreCase(fileName)) {
                return c;
            }
        }
        return null;
    }

    @NonNull
    static String sanitizeGgufFileName(@Nullable String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._\\-]", "_");
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            name = "imported_model.gguf";
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            name = name + ".gguf";
        }
        return name;
    }

    private void updateDownloadSnapshot(@NonNull String modelId,
                                        boolean active,
                                        int pct,
                                        long downloaded,
                                        long total,
                                        long bps,
                                        @Nullable String error) {
        downloadSnapshots.put(modelId, new DownloadStateSnapshot(
                modelId, active, pct, downloaded, total, bps, error));
    }

    @NonNull
    private static DocumentMeta queryDocumentMeta(@NonNull ContentResolver resolver, @NonNull Uri uri) {
        String name = "imported_model.gguf";
        long size = -1L;
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    String candidate = cursor.getString(nameIdx);
                    if (candidate != null && !candidate.trim().isEmpty()) {
                        name = candidate.trim();
                    }
                }
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx);
                }
            }
        } catch (Exception ignored) {
        }
        if ("imported_model.gguf".equals(name)) {
            String seg = uri.getLastPathSegment();
            if (seg != null && !seg.trim().isEmpty()) {
                name = seg.trim();
            }
        }
        return new DocumentMeta(name, size);
    }

    private void notifyStateChangedOnMain() {
        final StateListener listener = stateNotifier;
        if (listener == null) return;
        postOnMain(new Runnable() {
            @Override
            public void run() {
                listener.onAiFriendStateChanged();
            }
        });
    }

    private static <T> void postSuccess(@NonNull final ResultCallback<T> cb, @NonNull final T result) {
        postOnMain(new Runnable() {
            @Override
            public void run() {
                cb.onSuccess(result);
            }
        });
    }

    private static void postError(@NonNull final ResultCallback<?> cb, @NonNull final String message) {
        postOnMain(new Runnable() {
            @Override
            public void run() {
                cb.onError(message);
            }
        });
    }

    private static void postOnMain(@NonNull Runnable r) {
        if (Tools.MAIN_HANDLER != null) {
            Tools.MAIN_HANDLER.post(r);
        } else {
            r.run();
        }
    }

    private static final class DocumentMeta {
        @NonNull final String displayName;
        final long sizeBytes;

        DocumentMeta(@NonNull String displayName, long sizeBytes) {
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
        }
    }
}
