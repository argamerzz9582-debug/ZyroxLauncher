package git.artdeell.mojo.downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;

import java.net.URL;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import git.artdeell.mojo.tasks.mojson.mirrors.DownloadMirror;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.HashUtils;
import git.artdeell.mojo.utils.NetworkPolicyHelper;

public class DownloadFileTask extends DownloaderTask implements BytesCopiedListener {
    private static final int MAX_RETRIES = 5;

    private long mBytesDownloaded = 0L;
    private final File mPartFile;

    DownloadFileTask(TaskMetadata mMetadata, Downloader mHostDownloader) {
        super(mMetadata, mHostDownloader);
        mPartFile = new File(Downloader.partFileDir(), partFileName());
    }

    @Override
    protected void performTask() throws IOException {
        try {
            ReentrantLock pathLock = Downloader.lockForPath(mMetadata.path);
            try {
                pathLock.lockInterruptibly();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                InterruptedIOException ioe = new InterruptedIOException("Canceled while waiting for file lock");
                ioe.initCause(ie);
                throw ioe;
            }
            try {
                tryDownload(0, true);
                movePartFileAtomically();
            } finally {
                pathLock.unlock();
            }
            mDownloader.submitFileForRecheck(mMetadata);
        } catch (InterruptedIOException e) {
            // Keep resumable .part only if paused; remove corrupt/zero-byte leftovers
            if (mPartFile.exists() && mPartFile.length() == 0L) {
                //noinspection ResultOfMethodCallIgnored
                mPartFile.delete();
            }
            throw e;
        }
    }

    /**
     * Removes this task's bytes from the shared progress counter. Every byte reported through
     * {@link #onBytesCopied} or the resume pre-credit is tracked in {@link #mBytesDownloaded}, so
     * subtracting exactly that amount keeps the batch counter consistent across retries and can
     * never drive it below the bytes other tasks have contributed.
     */
    private void rollbackProgress() {
        if (mBytesDownloaded > 0L) {
            mDownloader.addSize(-mBytesDownloaded);
        }
        mBytesDownloaded = 0L;
    }

    private static void sleepBeforeRetry(int attempt) throws InterruptedIOException {
        try {
            NetworkPolicyHelper.sleepBackoff(attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            InterruptedIOException ioe = new InterruptedIOException("Retry interrupted");
            ioe.initCause(ie);
            throw ioe;
        }
    }

    private String partFileName() {
        String path = mMetadata.path.getPath();
        String longHashBase = new StringBuilder(55 + path.length())
                .append(mMetadata.sha1Hash)
                .append(Long.toHexString(mMetadata.size))
                .append(path)
                .toString();
        String longHash = HashUtils.longHash(longHashBase);
        return longHash + ".part";
    }

    private void movePartFileAtomically() throws IOException {
        File destination = mMetadata.path;
        FileUtils.ensureParentDirectory(destination);
        // Any previous Rapid Start verification marker describes the old file; drop it.
        //noinspection ResultOfMethodCallIgnored
        Downloader.verificationMarkerFor(destination).delete();
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Failed to replace existing file: " + destination.getName());
        }
        if (mPartFile.renameTo(destination)) {
            return;
        }
        // Fallback across filesystems: copy into sibling .tmp, sync, delete .part, then atomic rename.
        File siblingTemp = new File(destination.getParentFile(), destination.getName() + ".zyrox.tmp");
        byte[] buf = Downloader.getBuffer();
        try (FileInputStream in = new FileInputStream(mPartFile);
             FileOutputStream out = new FileOutputStream(siblingTemp, false)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            try {
                out.getFD().sync();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            siblingTemp.delete();
            throw e;
        }
        if (!siblingTemp.renameTo(destination)) {
            //noinspection ResultOfMethodCallIgnored
            siblingTemp.delete();
            throw new IOException("Failed to finalize downloaded file: " + destination.getName());
        }
        //noinspection ResultOfMethodCallIgnored
        mPartFile.delete();
    }

    private URL selectCandidateUrl(int attempt) {
        if (mMetadata.url == null) return null;
        try {
            List<String> candidates = DownloadMirror.buildFailoverCandidates(mMetadata.url.toString());
            if (!candidates.isEmpty()) {
                String chosen = candidates.get(Math.abs(attempt) % candidates.size());
                return new URL(chosen);
            }
        } catch (Exception ignored) {
        }
        return mMetadata.url;
    }

    /**
     * Downloads into the .part file with at most {@link #MAX_RETRIES} retries after the first
     * attempt. Iterative (not recursive) so a failure in a later attempt can never be re-caught by
     * an earlier frame and restart the retry budget.
     */
    private void tryDownload(int firstAttempt, boolean rangeAllowedInitially) throws IOException {
        boolean rangeAllowed = rangeAllowedInitially;
        int attempt = firstAttempt;
        while (true) {
            URL activeUrl = selectCandidateUrl(attempt);
            if (activeUrl == null) {
                throw new IOException("No download URL for " + mMetadata.path.getName());
            }
            try {
                mDownloader.checkQueueControl();
                if (rangeAllowed && mPartFile.exists()) {
                    ResumeOutcome outcome = tryResume(activeUrl);
                    if (outcome == ResumeOutcome.DONE) {
                        DownloadMirror.recordEndpointSuccess(activeUrl.toString());
                        return;
                    }
                    if (outcome == ResumeOutcome.UNSUPPORTED) {
                        // Server ignored the Range request; this is not a network failure, so fall
                        // straight back to a full download without consuming a retry or sleeping.
                        rollbackProgress();
                        rangeAllowed = false;
                        continue;
                    }
                    // outcome == FULL_DOWNLOAD_NEEDED: fall through
                }
                rollbackProgress();
                if (mPartFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    mPartFile.delete();
                }
                mDownloader.downloadFile(mPartFile, activeUrl, this);
                verifyDownloadedLength();
                DownloadMirror.recordEndpointSuccess(activeUrl.toString());
                return;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                DownloadMirror.recordEndpointFailure(activeUrl.toString());
                rollbackProgress();
                if (attempt - firstAttempt >= MAX_RETRIES) {
                    throw e;
                }
                sleepBeforeRetry(attempt);
                attempt++;
            }
        }
    }

    private enum ResumeOutcome { DONE, UNSUPPORTED, FULL_DOWNLOAD_NEEDED }

    private ResumeOutcome tryResume(URL activeUrl) throws IOException {
        long alreadyDownloaded = mPartFile.length();
        // Unknown size, empty, or oversized (corrupt) partial files cannot be resumed safely.
        if (mMetadata.size <= 0L || alreadyDownloaded == 0L || alreadyDownloaded > mMetadata.size) {
            return ResumeOutcome.FULL_DOWNLOAD_NEEDED;
        }
        rollbackProgress();
        mBytesDownloaded = alreadyDownloaded;
        mDownloader.addSize(alreadyDownloaded);
        if (alreadyDownloaded == mMetadata.size) {
            return ResumeOutcome.DONE;
        }
        if (!mDownloader.tryContinueDownload(mPartFile, mMetadata.size, activeUrl, this)) {
            return ResumeOutcome.UNSUPPORTED;
        }
        verifyDownloadedLength();
        return ResumeOutcome.DONE;
    }

    /** A truncated/oversized body is treated as a transient failure so it is retried within budget. */
    private void verifyDownloadedLength() throws IOException {
        if (mMetadata.size > 0L && mPartFile.length() != mMetadata.size) {
            long actual = mPartFile.length();
            //noinspection ResultOfMethodCallIgnored
            mPartFile.delete();
            throw new IOException("Size mismatch for " + mMetadata.path.getName()
                    + ": expected " + mMetadata.size + " bytes, got " + actual);
        }
    }

    @Override
    public void onBytesCopied(int nbytes) {
        mBytesDownloaded += nbytes;
        mDownloader.addSize(nbytes);
    }
}
