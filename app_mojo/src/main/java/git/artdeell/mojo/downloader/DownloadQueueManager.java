package git.artdeell.mojo.downloader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.utils.NetworkPolicyHelper;

/**
 * Centralized, thread-safe Zyrox Download Manager coordinator tracking active, paused, completed,
 * failed, and canceled download batches across Minecraft files, loaders, JREs, mods, and modpacks.
 */
public final class DownloadQueueManager {
    public static final int MIN_CONCURRENCY = 1;
    public static final int MAX_CONCURRENCY = 8;
    public static final int DEFAULT_CONCURRENCY = 6;
    public static final int MAX_HISTORY_ENTRIES = 40;

    public enum Status {
        QUEUED,
        RUNNING,
        PAUSED,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELED
    }

    public enum Category {
        MINECRAFT,
        MODLOADER,
        MOD,
        MODPACK,
        RUNTIME,
        OTHER
    }

    public static final class DownloadRecord {
        @NonNull public final String id;
        @NonNull public final String dedupKey;
        @NonNull public final String title;
        @NonNull public final Category category;
        @NonNull public volatile Status status;
        public volatile long downloadedBytes;
        public volatile long totalBytes;
        public volatile int completedFiles;
        public volatile int totalFiles;
        public volatile double speedBytesPerSec;
        public volatile boolean supportsResume;
        public volatile boolean checksumVerified;
        @Nullable public volatile String redactedError;
        @Nullable public volatile File targetFile;
        public final long createdAtMs;
        public volatile long updatedAtMs;
        final AtomicBoolean pauseRequested = new AtomicBoolean(false);
        final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        @Nullable volatile Runnable retryAction;

        DownloadRecord(@NonNull String id,
                       @NonNull String dedupKey,
                       @NonNull String title,
                       @NonNull Category category,
                       boolean supportsResume,
                       @Nullable File targetFile) {
            this.id = id;
            this.dedupKey = dedupKey;
            this.title = title;
            this.category = category;
            this.status = Status.QUEUED;
            this.supportsResume = supportsResume;
            this.targetFile = targetFile;
            this.createdAtMs = System.currentTimeMillis();
            this.updatedAtMs = this.createdAtMs;
        }

        public int getProgressPercent() {
            if (status == Status.COMPLETED) return 100;
            if (totalBytes > 0L) {
                return (int) Math.max(0L, Math.min(100L, (downloadedBytes * 100L) / totalBytes));
            }
            if (totalFiles > 0) {
                return Math.max(0, Math.min(100, (completedFiles * 100) / totalFiles));
            }
            return 0;
        }

        @NonNull
        public String getFormattedEta() {
            if (status == Status.PAUSED) return "Paused";
            if (status == Status.COMPLETED) return "Done";
            if (status != Status.RUNNING) return "--";
            if (totalBytes <= 0L || downloadedBytes >= totalBytes) return "--";
            return NetworkPolicyHelper.formatEtaSeconds(totalBytes - downloadedBytes, speedBytesPerSec);
        }

        @NonNull
        public String getSummaryLine() {
            StringBuilder sb = new StringBuilder(96);
            sb.append(status.name());
            if (totalBytes > 0L) {
                sb.append("  •  ")
                        .append(NetworkPolicyHelper.formatBytes(downloadedBytes))
                        .append(" / ")
                        .append(NetworkPolicyHelper.formatBytes(totalBytes));
            } else if (totalFiles > 0) {
                sb.append("  •  ").append(completedFiles).append(" / ").append(totalFiles).append(" files");
            }
            if (status == Status.RUNNING && speedBytesPerSec > 0) {
                sb.append("  •  ").append(NetworkPolicyHelper.formatBytes(Math.round(speedBytesPerSec))).append("/s");
                String eta = getFormattedEta();
                if (!"--".equals(eta)) {
                    sb.append("  •  ETA ").append(eta);
                }
            }
            if (checksumVerified) {
                sb.append("  •  SHA Verified");
            }
            if (status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELED) {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(updatedAtMs));
                sb.append("  •  ").append(time);
            }
            return sb.toString();
        }

        public boolean isPauseRequested() {
            return pauseRequested.get();
        }

        public boolean isCancelRequested() {
            return cancelRequested.get();
        }

        public boolean canPause() {
            return status == Status.RUNNING && supportsResume;
        }

        public boolean canResume() {
            return status == Status.PAUSED;
        }

        public boolean canCancel() {
            return status == Status.QUEUED || status == Status.RUNNING
                    || status == Status.PAUSED || status == Status.VERIFYING;
        }

        public boolean canRetry() {
            return (status == Status.FAILED || status == Status.CANCELED) && retryAction != null;
        }
    }

    public interface QueueListener {
        void onQueueUpdated();
    }

    private static final DownloadQueueManager INSTANCE = new DownloadQueueManager();

    private final Object mLock = new Object();
    private final AtomicLong mIdSeq = new AtomicLong(1L);
    private final Map<String, DownloadRecord> mActiveById = new LinkedHashMap<>();
    private final Map<String, DownloadRecord> mActiveByDedup = new LinkedHashMap<>();
    private final LinkedList<DownloadRecord> mHistory = new LinkedList<>();
    private final List<QueueListener> mListeners = new CopyOnWriteArrayList<>();
    private volatile int mConcurrencyLimit = DEFAULT_CONCURRENCY;
    private volatile long mLastNotifyMs = 0L;

    public DownloadQueueManager() {
    }

    @NonNull
    public static DownloadQueueManager getInstance() {
        return INSTANCE;
    }

    public void setConcurrencyLimit(int limit) {
        mConcurrencyLimit = clampConcurrency(limit);
    }

    public int getConcurrencyLimit() {
        return mConcurrencyLimit;
    }

    public static int clampConcurrency(int limit) {
        return Math.max(MIN_CONCURRENCY, Math.min(MAX_CONCURRENCY, limit));
    }

    public void addListener(@NonNull QueueListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(@NonNull QueueListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Enqueues or deduplicates an active download task. If an identical {@code dedupKey} is already
     * queued, running, paused, or verifying, returns the existing record instead of duplicating work.
     */
    @NonNull
    public DownloadRecord enqueue(@NonNull String dedupKey,
                                  @NonNull String title,
                                  @NonNull Category category,
                                  boolean supportsResume,
                                  @Nullable File targetFile,
                                  @Nullable Runnable retryAction) {
        String safeKey = dedupKey.trim().isEmpty() ? ("auto-" + mIdSeq.get()) : dedupKey.trim();
        DownloadRecord record;
        synchronized (mLock) {
            DownloadRecord existing = mActiveByDedup.get(safeKey);
            if (existing != null && existing.canCancel()) {
                if (retryAction != null) existing.retryAction = retryAction;
                return existing;
            }
            String id = "dl-" + mIdSeq.getAndIncrement();
            record = new DownloadRecord(id, safeKey, title, category, supportsResume, targetFile);
            record.retryAction = retryAction;
            mActiveById.put(id, record);
            mActiveByDedup.put(safeKey, record);
        }
        notifyListeners(true);
        return record;
    }

    public void updateProgress(@NonNull String id,
                               @NonNull Status status,
                               long downloadedBytes,
                               long totalBytes,
                               int completedFiles,
                               int totalFiles,
                               double speedBytesPerSec) {
        synchronized (mLock) {
            DownloadRecord rec = mActiveById.get(id);
            if (rec == null) return;
            if (rec.pauseRequested.get() && status == Status.RUNNING) {
                rec.status = Status.PAUSED;
            } else {
                rec.status = status;
            }
            rec.downloadedBytes = Math.max(0L, downloadedBytes);
            rec.totalBytes = Math.max(-1L, totalBytes);
            rec.completedFiles = Math.max(0, completedFiles);
            rec.totalFiles = Math.max(0, totalFiles);
            rec.speedBytesPerSec = Math.max(0.0, speedBytesPerSec);
            rec.updatedAtMs = System.currentTimeMillis();
        }
        notifyListeners(false);
    }

    public void markCompleted(@NonNull String id, boolean checksumVerified) {
        synchronized (mLock) {
            DownloadRecord rec = mActiveById.remove(id);
            if (rec == null) return;
            mActiveByDedup.remove(rec.dedupKey);
            rec.status = Status.COMPLETED;
            if (rec.totalBytes > 0L) rec.downloadedBytes = rec.totalBytes;
            if (rec.totalFiles > 0) rec.completedFiles = rec.totalFiles;
            rec.checksumVerified = checksumVerified;
            rec.speedBytesPerSec = 0.0;
            rec.updatedAtMs = System.currentTimeMillis();
            pushHistoryLocked(rec);
        }
        notifyListeners(true);
    }

    public void markFailed(@NonNull String id, @Nullable Throwable error) {
        String raw = error != null
                ? (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName())
                : "Download failed";
        markFailedMessage(id, raw);
    }

    public void markFailedMessage(@NonNull String id, @Nullable String message) {
        synchronized (mLock) {
            DownloadRecord rec = mActiveById.remove(id);
            if (rec == null) return;
            mActiveByDedup.remove(rec.dedupKey);
            rec.status = rec.cancelRequested.get() ? Status.CANCELED : Status.FAILED;
            rec.redactedError = LogRedactor.redact(message != null ? message : "Download failed");
            rec.speedBytesPerSec = 0.0;
            rec.updatedAtMs = System.currentTimeMillis();
            pushHistoryLocked(rec);
        }
        notifyListeners(true);
    }

    public void markCanceled(@NonNull String id) {
        synchronized (mLock) {
            DownloadRecord rec = mActiveById.remove(id);
            if (rec == null) return;
            mActiveByDedup.remove(rec.dedupKey);
            rec.cancelRequested.set(true);
            rec.pauseRequested.set(false);
            rec.status = Status.CANCELED;
            rec.speedBytesPerSec = 0.0;
            rec.updatedAtMs = System.currentTimeMillis();
            pushHistoryLocked(rec);
            mLock.notifyAll();
        }
        notifyListeners(true);
    }

    public boolean requestPause(@NonNull String id) {
        synchronized (mLock) {
            DownloadRecord rec = mActiveById.get(id);
            if (rec == null || !rec.supportsResume || rec.status != Status.RUNNING) return false;
            rec.pauseRequested.set(true);
            rec.status = Status.PAUSED;
            rec.speedBytesPerSec = 0.0;
            rec.updatedAtMs = System.currentTimeMillis();
        }
        notifyListeners(true);
        return true;
    }

    public boolean requestResume(@NonNull String id) {
        synchronized (mLock) {
            DownloadRecord rec = mActiveById.get(id);
            if (rec == null || !rec.pauseRequested.get()) return false;
            rec.pauseRequested.set(false);
            rec.status = Status.RUNNING;
            rec.updatedAtMs = System.currentTimeMillis();
            mLock.notifyAll();
        }
        notifyListeners(true);
        return true;
    }

    public boolean requestCancel(@NonNull String id) {
        synchronized (mLock) {
            DownloadRecord rec = mActiveById.get(id);
            if (rec == null) return false;
            rec.cancelRequested.set(true);
            rec.pauseRequested.set(false);
            rec.updatedAtMs = System.currentTimeMillis();
            mLock.notifyAll();
        }
        notifyListeners(true);
        return true;
    }

    public boolean retryFromHistory(@NonNull String id) {
        Runnable action = null;
        synchronized (mLock) {
            Iterator<DownloadRecord> it = mHistory.iterator();
            while (it.hasNext()) {
                DownloadRecord rec = it.next();
                if (rec.id.equals(id) && rec.canRetry()) {
                    action = rec.retryAction;
                    it.remove();
                    break;
                }
            }
        }
        if (action == null) return false;
        notifyListeners(true);
        action.run();
        return true;
    }

    /**
     * Blocks a download worker cooperatively while paused, or throws {@link InterruptedException}
     * immediately when canceled.
     */
    public void awaitIfPausedOrThrowIfCanceled(@Nullable DownloadRecord rec) throws InterruptedException {
        if (rec == null) return;
        if (rec.cancelRequested.get() || Thread.interrupted()) {
            throw new InterruptedException("Download canceled by user");
        }
        if (!rec.pauseRequested.get()) return;
        synchronized (mLock) {
            while (rec.pauseRequested.get() && !rec.cancelRequested.get()) {
                mLock.wait(250L);
            }
            if (rec.cancelRequested.get()) {
                throw new InterruptedException("Download canceled by user");
            }
        }
    }

    @NonNull
    public List<DownloadRecord> getActiveDownloads() {
        synchronized (mLock) {
            return Collections.unmodifiableList(new ArrayList<>(mActiveById.values()));
        }
    }

    @NonNull
    public List<DownloadRecord> getHistory() {
        synchronized (mLock) {
            return Collections.unmodifiableList(new ArrayList<>(mHistory));
        }
    }

    public int getActiveCount() {
        synchronized (mLock) {
            return mActiveById.size();
        }
    }

    public void clearHistory() {
        synchronized (mLock) {
            mHistory.clear();
        }
        notifyListeners(true);
    }

    public boolean isPaused() {
        synchronized (mLock) {
            if (mActiveById.isEmpty()) return false;
            for (DownloadRecord rec : mActiveById.values()) {
                if (rec.isPauseRequested() || rec.status == Status.PAUSED) return true;
            }
            return false;
        }
    }

    public void pauseAll() {
        synchronized (mLock) {
            for (DownloadRecord rec : mActiveById.values()) {
                if (rec.canPause()) {
                    rec.pauseRequested.set(true);
                    rec.status = Status.PAUSED;
                    rec.updatedAtMs = System.currentTimeMillis();
                }
            }
        }
        notifyListeners(true);
    }

    public void resumeAll() {
        synchronized (mLock) {
            for (DownloadRecord rec : mActiveById.values()) {
                if (rec.canResume()) {
                    rec.pauseRequested.set(false);
                    rec.status = Status.RUNNING;
                    rec.updatedAtMs = System.currentTimeMillis();
                }
            }
            mLock.notifyAll();
        }
        notifyListeners(true);
    }

    public void cancelAll() {
        synchronized (mLock) {
            for (DownloadRecord rec : mActiveById.values()) {
                if (rec.canCancel()) {
                    rec.cancelRequested.set(true);
                    rec.pauseRequested.set(false);
                    rec.updatedAtMs = System.currentTimeMillis();
                }
            }
            mLock.notifyAll();
        }
        notifyListeners(true);
    }

    /**
     * Deletes orphaned {@code .part} and {@code .zyrox.tmp} files under {@code rootDir} when no
     * downloads are currently active. Bounded depth/count so cleanup is always fast and safe.
     */
    public static int cleanupPartialFiles(@Nullable File rootDir) {
        if (rootDir == null || !rootDir.isDirectory()) return 0;
        if (getInstance().getActiveCount() > 0) return 0;
        long staleCutoffMs = System.currentTimeMillis() - 60_000L;
        int[] deleted = new int[]{0};
        int[] visited = new int[]{0};
        cleanupPartialsRecursive(rootDir, 0, staleCutoffMs, deleted, visited);
        return deleted[0];
    }

    private static void cleanupPartialsRecursive(@NonNull File dir, int depth, long staleCutoffMs,
                                                 int[] deleted, int[] visited) {
        if (depth > 8 || visited[0] > 20_000) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (++visited[0] > 20_000) return;
            String name = child.getName();
            if (child.isDirectory()) {
                if ("saves".equals(name) || "backups".equals(name)) continue;
                cleanupPartialsRecursive(child, depth + 1, staleCutoffMs, deleted, visited);
            } else if ((name.endsWith(".part") || name.endsWith(".zyrox.tmp"))
                    && child.lastModified() < staleCutoffMs) {
                if (child.delete()) deleted[0]++;
            }
        }
    }

    /**
     * Evaluates whether HTTP Range resume is safe for the given server metadata and partial file.
     */
    public static boolean canSafelyResumeRange(long partLength, long expectedTotalSize) {
        return partLength > 0L && expectedTotalSize > 0L && partLength < expectedTotalSize;
    }

    private void pushHistoryLocked(@NonNull DownloadRecord rec) {
        mHistory.addFirst(rec);
        while (mHistory.size() > MAX_HISTORY_ENTRIES) {
            mHistory.removeLast();
        }
    }

    private void notifyListeners(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - mLastNotifyMs < 180L) return;
        mLastNotifyMs = now;
        for (QueueListener listener : mListeners) {
            try {
                listener.onQueueUpdated();
            } catch (Throwable ignored) {
            }
        }
    }
}
