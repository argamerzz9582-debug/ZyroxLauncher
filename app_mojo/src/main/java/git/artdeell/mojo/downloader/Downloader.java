package git.artdeell.mojo.downloader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.tasks.SpeedCalculator;
import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.ZyroxHttpClient;

import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class Downloader {
    private static final double ONE_MEGABYTE = (1024d * 1024d);
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_STRING_RESPONSE_BYTES = 32 * 1024 * 1024; // 32 MiB cap
    /** Maximum time between progress/UI refreshes; workers wake the loop early on completion/error. */
    private static final long PROGRESS_TICK_MS = 100L;
    /**
     * Striped locks keyed by destination path. Different {@link Downloader} batches can run at the
     * same time (e.g. a modpack install while the game downloads) and may target the same file, which
     * maps to the same .part file. Holding the stripe for that path serializes those writers.
     */
    private static final ReentrantLock[] sPathLocks = new ReentrantLock[256];
    static {
        for (int i = 0; i < sPathLocks.length; i++) sPathLocks[i] = new ReentrantLock();
    }

    private static final ThreadLocal<byte[]> sThreadLocalBuffer = new ThreadLocal<>();
    /**
     * Resolved on every call: a static final captured at class-load time would freeze the
     * pre-init placeholder root if this class loads before {@code Tools.initStorageConstants}.
     */
    @NonNull
    protected static File partFileDir() {
        return new File(Tools.DIR_GAME_HOME, "partfiles");
    }

    private final String mProgressKey;
    private final AtomicReference<IOException> mThreadException = new AtomicReference<>();
    private final AtomicInteger mDownloadedFileCounter = new AtomicInteger();
    private final AtomicLong mDownloadedSizeCounter = new AtomicLong();
    private final AtomicLong mInternetUsageCounter = new AtomicLong();
    private final AtomicBoolean mUseSizeProgress = new AtomicBoolean(true);
    private final SpeedCalculator mSpeedCalculator = new SpeedCalculator();
    private final Object mProgressSignal = new Object();
    private ExecutorService mDownloadService;
    private ExecutorService mVerifyService;
    private volatile DownloadQueueManager.DownloadRecord mQueueRecord;

    public Downloader(String mProgressKey) {
        this.mProgressKey = mProgressKey;
    }

    protected void runDownloads(ArrayList<? extends TaskMetadata> downloads) throws IOException, InterruptedException {
        FileUtils.ensureDirectory(partFileDir());
        ArrayList<TaskMetadata> deduplicated = deduplicateDownloads(downloads);
        if (deduplicated.isEmpty()) return;

        DownloadQueueManager queue = DownloadQueueManager.getInstance();
        mQueueRecord = queue.enqueue(
                "batch:" + mProgressKey,
                resolveBatchTitle(mProgressKey),
                resolveBatchCategory(mProgressKey),
                true,
                null,
                null
        );

        insertMetadata(deduplicated);
        try {
            performDownloads(deduplicated);
            queue.markCompleted(mQueueRecord.id, LauncherPreferences.PREF_VERIFY_FILES);
        } catch (InterruptedException e) {
            shutdownPoolsNow();
            if (mQueueRecord != null) queue.markCanceled(mQueueRecord.id);
            throw e;
        } catch (IOException e) {
            shutdownPoolsNow();
            if (mQueueRecord != null) {
                if (mQueueRecord.isCancelRequested()) {
                    queue.markCanceled(mQueueRecord.id);
                    throw new InterruptedException("Download canceled by user");
                }
                queue.markFailed(mQueueRecord.id, e);
            }
            throw e;
        } finally {
            mQueueRecord = null;
        }
    }

    private void shutdownPoolsNow() {
        if (mDownloadService != null) mDownloadService.shutdownNow();
        if (mVerifyService != null) mVerifyService.shutdownNow();
    }

    /** Deduplicates identical destination paths within a single batch to prevent file-write races. */
    private static ArrayList<TaskMetadata> deduplicateDownloads(ArrayList<? extends TaskMetadata> source) {
        if (source == null || source.isEmpty()) return new ArrayList<>(0);
        Map<String, TaskMetadata> unique = new LinkedHashMap<>(source.size());
        for (TaskMetadata item : source) {
            if (item == null || item.path == null) continue;
            unique.put(item.path.getAbsolutePath(), item);
        }
        return new ArrayList<>(unique.values());
    }

    private static String resolveBatchTitle(String progressKey) {
        if (ProgressLayout.DOWNLOAD_GAME.equals(progressKey)) return "Minecraft Game & Assets";
        if (ProgressLayout.INSTALL_MODPACK.equals(progressKey)) return "Modpack Installation";
        if (ProgressLayout.INSTANCE_INSTALL.equals(progressKey)) return "Mod Loader & Instance Setup";
        if (ProgressLayout.UNPACK_RUNTIME.equals(progressKey)) return "Java Runtime Setup";
        return "Zyrox Download Task";
    }

    private static DownloadQueueManager.Category resolveBatchCategory(String progressKey) {
        if (ProgressLayout.DOWNLOAD_GAME.equals(progressKey)) return DownloadQueueManager.Category.MINECRAFT;
        if (ProgressLayout.INSTALL_MODPACK.equals(progressKey)) return DownloadQueueManager.Category.MODPACK;
        if (ProgressLayout.INSTANCE_INSTALL.equals(progressKey)) return DownloadQueueManager.Category.MODLOADER;
        if (ProgressLayout.UNPACK_RUNTIME.equals(progressKey)) return DownloadQueueManager.Category.RUNTIME;
        return DownloadQueueManager.Category.OTHER;
    }

    private void performDownloads(ArrayList<TaskMetadata> metadata) throws IOException, InterruptedException {
        mThreadException.set(null);
        mDownloadedFileCounter.set(0);
        mDownloadedSizeCounter.set(0);
        int workerCount = DownloadQueueManager.clampConcurrency(LauncherPreferences.PREF_ZYROX_DOWNLOAD_THREADS);
        DownloadQueueManager.getInstance().setConcurrencyLimit(workerCount);
        mDownloadService = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "zyrox-dl-worker");
            t.setDaemon(true);
            return t;
        });
        int verifyThreads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        mVerifyService = Executors.newFixedThreadPool(verifyThreads, r -> {
            Thread thread = new Thread(r, "zyrox-verify-worker");
            thread.setPriority(Thread.NORM_PRIORITY + 1);
            thread.setDaemon(true);
            return thread;
        });
        long totalSize = 0L;
        int totalCount = metadata.size();
        boolean sizeCounter = mUseSizeProgress.get();
        for (TaskMetadata element : metadata) {
            if (element.size > 0L) totalSize += element.size;
            mVerifyService.submit(new CheckFileOnDiskTask(element, this));
        }
        if (totalSize <= 0L) sizeCounter = false;
        double totalMegabytes = totalSize / ONE_MEGABYTE;
        DownloadQueueManager queue = DownloadQueueManager.getInstance();

        while (mDownloadedFileCounter.get() < totalCount) {
            if (mQueueRecord != null && mQueueRecord.isCancelRequested()) {
                throw new InterruptedException("Download canceled by user");
            }
            IOException exception = mThreadException.get();
            if (exception != null) throw exception;

            double speedBytes = mSpeedCalculator.feed(mInternetUsageCounter.get());
            double speedMb = speedBytes / ONE_MEGABYTE;
            if (sizeCounter) {
                reportSizeProgress(totalMegabytes, speedMb);
            } else {
                reportCountProgress(R.string.newerdl_downloading_files_count, totalCount, speedMb);
            }
            if (mQueueRecord != null) {
                queue.updateProgress(
                        mQueueRecord.id,
                        DownloadQueueManager.Status.RUNNING,
                        mDownloadedSizeCounter.get(),
                        sizeCounter ? totalSize : -1L,
                        mDownloadedFileCounter.get(),
                        totalCount,
                        speedBytes
                );
            }
            awaitProgressSignal(totalCount);
        }
        mDownloadService.shutdown();
        mVerifyService.shutdown();
        mDownloadService.awaitTermination(5, TimeUnit.SECONDS);
        mVerifyService.awaitTermination(5, TimeUnit.SECONDS);
    }

    private void insertMetadata(ArrayList<TaskMetadata> metadata) throws IOException, InterruptedException {
        mThreadException.set(null);
        mDownloadedFileCounter.set(0);
        ArrayList<TaskMetadata> reducedList = new ArrayList<>();
        for (TaskMetadata element : metadata) {
            if (!CompleteMetadataTask.shouldCompleteMetadata(element)) continue;
            reducedList.add(element);
        }
        if (reducedList.isEmpty()) return;
        // Metadata completion is network-bound (HEAD + .sha1 requests): honor the user's thread setting.
        int metadataThreads = Math.min(reducedList.size(),
                DownloadQueueManager.clampConcurrency(LauncherPreferences.PREF_ZYROX_DOWNLOAD_THREADS));
        ExecutorService executorService = Executors.newFixedThreadPool(Math.max(1, metadataThreads), r -> {
            Thread t = new Thread(r, "zyrox-metadata-worker");
            t.setDaemon(true);
            return t;
        });
        try {
            for (TaskMetadata element : reducedList) {
                executorService.submit(new CompleteMetadataTask(element, this));
            }
            executorService.shutdown();
            while (!executorService.awaitTermination(PROGRESS_TICK_MS, TimeUnit.MILLISECONDS)) {
                if (mQueueRecord != null && mQueueRecord.isCancelRequested()) {
                    throw new InterruptedException("Download canceled by user");
                }
                IOException exception = mThreadException.get();
                if (exception != null) throw exception;
                double speedMb = mSpeedCalculator.feed(mInternetUsageCounter.get()) / ONE_MEGABYTE;
                reportCountProgress(R.string.newerdl_inserting_metadata_count, reducedList.size(), speedMb);
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    private void reportCountProgress(int resource, int total, double speedMb) {
        int downloadedCount = mDownloadedFileCounter.get();
        int progress = total > 0 ? (int) ((downloadedCount / (float) total) * 100f) : 0;
        ProgressLayout.setProgress(mProgressKey, progress, resource,
                downloadedCount, total, speedMb
        );
    }

    private void reportSizeProgress(double totalMegabytes, double speedMb) {
        double downloadedMegabytes = mDownloadedSizeCounter.get() / ONE_MEGABYTE;
        int progress = totalMegabytes > 0d
                ? (int) Math.min(100d, (downloadedMegabytes / totalMegabytes) * 100d)
                : 0;
        ProgressLayout.setProgress(mProgressKey, progress, R.string.newerdl_downloading_files_size,
                downloadedMegabytes, totalMegabytes, speedMb
        );
    }

    protected void taskException(IOException e) {
        mThreadException.compareAndSet(null, e);
        signalProgress();
    }

    /** Wakes the coordinator loop immediately (file finished, error raised). */
    private void signalProgress() {
        synchronized (mProgressSignal) {
            mProgressSignal.notifyAll();
        }
    }

    /**
     * Blocks the coordinator until a worker signals or {@link #PROGRESS_TICK_MS} elapses, replacing the
     * old fixed 40 ms sleep-poll: errors and completion are now handled instantly, and idle wakeups
     * drop from 25/s to 10/s.
     */
    private void awaitProgressSignal(int totalCount) throws InterruptedException {
        synchronized (mProgressSignal) {
            if (mDownloadedFileCounter.get() < totalCount && mThreadException.get() == null) {
                mProgressSignal.wait(PROGRESS_TICK_MS);
            }
        }
    }

    /**
     * Lock guarding all writes to {@code destination} (and its derived .part file). A
     * {@link ReentrantLock} (acquired with {@code lockInterruptibly}) rather than a monitor, so a
     * worker waiting on a busy path still responds to cancellation / {@code shutdownNow()}.
     */
    @NonNull
    static ReentrantLock lockForPath(@NonNull File destination) {
        int h = destination.getAbsolutePath().hashCode();
        h ^= (h >>> 16);
        return sPathLocks[h & (sPathLocks.length - 1)];
    }

    protected void disableSizeCounter() {
        mUseSizeProgress.lazySet(false);
    }

    protected void submitFileForDownload(TaskMetadata taskMetadata) {
        mDownloadService.submit(new DownloadFileTask(taskMetadata, this));
    }

    protected void submitFileForRecheck(TaskMetadata taskMetadata) {
        mVerifyService.submit(new CheckFileOnDiskTask(taskMetadata, this, true));
    }

    protected void fileComplete() {
        mDownloadedFileCounter.getAndIncrement();
        signalProgress();
    }

    protected void addSize(long bytes) {
        mDownloadedSizeCounter.getAndAdd(bytes);
    }

    protected void checkQueueControl() throws IOException {
        DownloadQueueManager.DownloadRecord rec = mQueueRecord;
        if (rec == null) {
            if (Thread.interrupted()) throw new InterruptedIOException();
            return;
        }
        try {
            DownloadQueueManager.getInstance().awaitIfPausedOrThrowIfCanceled(rec);
        } catch (InterruptedException e) {
            InterruptedIOException ioe = new InterruptedIOException(e.getMessage());
            ioe.initCause(e);
            throw ioe;
        }
    }

    private void copy(InputStream inputStream, OutputStream outputStream, BytesCopiedListener listener)
            throws IOException {
        byte[] buffer = getBuffer();
        int readLen;
        while ((readLen = inputStream.read(buffer)) != -1) {
            checkQueueControl();
            outputStream.write(buffer, 0, readLen);
            if (listener != null) listener.onBytesCopied(readLen);
            mInternetUsageCounter.getAndAdd(readLen);
        }
    }

    private static HttpURLConnection openConnection(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", DownloadUtils.USER_AGENT);
        connection.setDoInput(true);
        connection.setDoOutput(false);
        return connection;
    }

    protected void downloadToStream(HttpURLConnection connection,
                                    OutputStream outputStream,
                                    BytesCopiedListener listener) throws IOException {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " while downloading " + LogRedactor.redactUrl(connection.getURL()));
        }
        try (InputStream inputStream = connection.getInputStream()) {
            copy(inputStream, outputStream, listener);
        }
    }

    protected String downloadString(URL url) throws IOException {
        String urlStr = url.toString();
        if (ZyroxHttpClient.isStrictHttpsUrl(urlStr)) {
            try (Response resp = ZyroxHttpClient.openDownloadResponse(urlStr, 0L)) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new IOException("HTTP " + resp.code() + " while reading " + LogRedactor.redactUrl(url));
                }
                try (InputStream is = resp.body().byteStream()) {
                    byte[] bytes = ArchiveSecurity.readBytesBounded(is, MAX_STRING_RESPONSE_BYTES);
                    mInternetUsageCounter.getAndAdd(bytes.length);
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
        }
        HttpURLConnection connection = openConnection(url);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " while reading " + LogRedactor.redactUrl(url));
            }
            try (InputStream is = connection.getInputStream()) {
                byte[] bytes = ArchiveSecurity.readBytesBounded(is, MAX_STRING_RESPONSE_BYTES);
                mInternetUsageCounter.getAndAdd(bytes.length);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    protected void downloadFile(File file, URL url, BytesCopiedListener listener) throws IOException {
        checkQueueControl();
        FileUtils.ensureParentDirectory(file);
        String urlStr = url.toString();
        if (ZyroxHttpClient.isStrictHttpsUrl(urlStr)) {
            try (Response resp = ZyroxHttpClient.openDownloadResponse(urlStr, 0L)) {
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + " while downloading " + LogRedactor.redactUrl(url));
                }
                ResponseBody body = resp.body();
                if (body == null) {
                    throw new IOException("Empty response body for " + LogRedactor.redactUrl(url));
                }
                try (InputStream inputStream = body.byteStream();
                     FileOutputStream outputStream = new FileOutputStream(file, false)) {
                    copy(inputStream, outputStream, listener);
                    outputStream.flush();
                }
                return;
            }
        }
        HttpURLConnection connection = openConnection(url);
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            downloadToStream(connection, outputStream, listener);
            outputStream.flush();
        } finally {
            connection.disconnect();
        }
    }

    protected boolean tryContinueDownload(File file, long wantedLength, URL url, BytesCopiedListener listener)
            throws IOException {
        checkQueueControl();
        long currentLength = file.length();
        if (!DownloadQueueManager.canSafelyResumeRange(currentLength, wantedLength)) {
            return false;
        }
        String urlStr = url.toString();
        if (ZyroxHttpClient.isStrictHttpsUrl(urlStr)) {
            try (Response resp = ZyroxHttpClient.openRangeResponse(urlStr, currentLength, wantedLength - 1L)) {
                if (resp.code() != HttpURLConnection.HTTP_PARTIAL || resp.body() == null) {
                    return false;
                }
                checkQueueControl();
                try (InputStream inputStream = resp.body().byteStream();
                     FileOutputStream outputStream = new FileOutputStream(file, true)) {
                    copy(inputStream, outputStream, listener);
                    outputStream.flush();
                    return true;
                }
            }
        }
        HttpURLConnection connection = openConnection(url);
        String range = String.format(Locale.ENGLISH, "bytes=%d-%d", currentLength, wantedLength - 1L);
        connection.setRequestProperty("Range", range);
        try {
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_PARTIAL) {
                return false;
            }
            checkQueueControl();
            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(file, true)) {
                copy(inputStream, outputStream, listener);
                outputStream.flush();
                return true;
            }
        } finally {
            connection.disconnect();
        }
    }

    protected long getFileContentLength(URL url) throws IOException {
        String urlStr = url.toString();
        if (ZyroxHttpClient.isStrictHttpsUrl(urlStr)) {
            return ZyroxHttpClient.headContentLength(urlStr);
        }
        HttpURLConnection connection = openConnection(url);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("HEAD");
        try {
            connection.connect();
            int response = connection.getResponseCode();
            if (response >= 400) {
                return -1L;
            }
            return connection.getContentLengthLong();
        } finally {
            connection.disconnect();
        }
    }

    public static byte[] getBuffer() {
        byte[] buffer = sThreadLocalBuffer.get();
        if (buffer == null) {
            buffer = new byte[64 * 1024];
            sThreadLocalBuffer.set(buffer);
        }
        return buffer;
    }

    /**
     * Sidecar verification marker proving a data file previously passed SHA-1 verification. It stores
     * the expected hash plus the file's size and last-modified time, so Rapid Start can trust an
     * unchanged file without re-hashing it or re-fetching the server hash. Any modification
     * (size or last-modified change) invalidates the marker and forces a fresh integrity check,
     * which closes the previous gap where Rapid Start trusted files by byte-size alone.
     */
    @NonNull
    public static File verificationMarkerFor(@NonNull File dataFile) {
        File parent = dataFile.getParentFile();
        if (parent == null) parent = new File(".");
        return new File(parent, dataFile.getName() + ".zyrox.verified");
    }

    public static void writeVerifiedMarker(@NonNull File dataFile, @NonNull String sha1) throws IOException {
        File marker = verificationMarkerFor(dataFile);
        String payload = sha1 + " " + dataFile.length() + " " + dataFile.lastModified() + "\n";
        try (FileOutputStream out = new FileOutputStream(marker, false)) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    /** True only when a marker exists and the data file's size + last-modified time still match it. */
    public static boolean isVerifiedMarkerStillValid(@NonNull File dataFile) {
        File marker = verificationMarkerFor(dataFile);
        if (!marker.isFile()) return false;
        String line;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(marker), StandardCharsets.UTF_8))) {
            line = br.readLine();
        } catch (IOException e) {
            return false;
        }
        if (line == null) return false;
        String[] parts = line.trim().split(" ");
        if (parts.length < 2) return false;
        try {
            long len = Long.parseLong(parts[1]);
            long mtime = parts.length >= 3 ? Long.parseLong(parts[2]) : -1L;
            return len == dataFile.length()
                    && (mtime < 0L || mtime == dataFile.lastModified());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Reads the stored expected hash from a verification marker, or {@code null} if absent/unreadable. */
    @Nullable
    public static String readVerifiedMarkerHash(@NonNull File dataFile) {
        File marker = verificationMarkerFor(dataFile);
        if (!marker.isFile()) return null;
        String line;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(marker), StandardCharsets.UTF_8))) {
            line = br.readLine();
        } catch (IOException e) {
            return null;
        }
        if (line == null) return null;
        String[] parts = line.trim().split(" ");
        return parts.length >= 1 ? parts[0] : null;
    }

    public static double calculateCacheSizeMegabytes() {
        File[] files = Downloader.partFileDir().listFiles();
        if (files == null || files.length == 0) return 0d;
        long usedSpace = 0L;
        for (File f : files) {
            if (Thread.interrupted()) return -1d;
            if (!f.isFile()) continue;
            long fileLen = f.length();
            if (fileLen > 0L) usedSpace += fileLen;
        }
        return usedSpace / ONE_MEGABYTE;
    }

    public static void clearCache() throws IOException {
        File[] files = Downloader.partFileDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) continue;
            if (!f.delete() && f.exists()) {
                throw new IOException("Failed to remove partial cache file: " + f.getName());
            }
        }
    }
}
