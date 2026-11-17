package git.artdeell.mojo.downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;

import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.HashUtils;
import git.artdeell.mojo.utils.NetworkPolicyHelper;

public class DownloadFileTask extends DownloaderTask implements BytesCopiedListener {
    private static final int MAX_RETRIES = 5;

    private long mBytesDownloaded = 0L;
    private final File mPartFile;

    DownloadFileTask(TaskMetadata mMetadata, Downloader mHostDownloader) {
        super(mMetadata, mHostDownloader);
        mPartFile = new File(Downloader.sPartFileDir, partFileName());
    }

    @Override
    protected void performTask() throws IOException {
        try {
            tryDownload(0, true);
            movePartFileAtomically();
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

    private void performRetry(int attempt, boolean rangeAllowed) throws IOException {
        mDownloader.addSize(-mBytesDownloaded);
        mBytesDownloaded = 0L;
        if (!rangeAllowed && mPartFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mPartFile.delete();
        }
        try {
            NetworkPolicyHelper.sleepBackoff(attempt);
        } catch (InterruptedException ie) {
            InterruptedIOException ioe = new InterruptedIOException("Retry interrupted");
            ioe.initCause(ie);
            throw ioe;
        }
        tryDownload(attempt + 1, rangeAllowed);
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

    private void tryDownload(int attempt, boolean rangeAllowed) throws IOException {
        try {
            mDownloader.checkQueueControl();
            if (!mPartFile.exists() || !rangeAllowed) {
                mBytesDownloaded = 0L;
                mDownloader.downloadFile(mPartFile, mMetadata.url, this);
                return;
            }

            long alreadyDownloaded = mPartFile.length();
            // Discard corrupt oversized partial file or unknown-size partial file safely
            if (mMetadata.size <= 0L || alreadyDownloaded > mMetadata.size || alreadyDownloaded == 0L) {
                //noinspection ResultOfMethodCallIgnored
                mPartFile.delete();
                mBytesDownloaded = 0L;
                mDownloader.downloadFile(mPartFile, mMetadata.url, this);
                return;
            }

            mBytesDownloaded = alreadyDownloaded;
            mDownloader.addSize(alreadyDownloaded);
            if (alreadyDownloaded == mMetadata.size) {
                return;
            }

            boolean resumed = mDownloader.tryContinueDownload(mPartFile, mMetadata.size, mMetadata.url, this);
            if (!resumed) {
                performRetry(attempt, false);
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            if (attempt >= MAX_RETRIES) {
                throw e;
            }
            performRetry(attempt, rangeAllowed);
        }
    }

    @Override
    public void onBytesCopied(int nbytes) {
        mBytesDownloaded += nbytes;
        mDownloader.addSize(nbytes);
    }
}
