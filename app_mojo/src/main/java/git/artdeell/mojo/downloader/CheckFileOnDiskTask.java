package git.artdeell.mojo.downloader;

import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.HashUtils;

import java.io.File;
import java.io.IOException;

public class CheckFileOnDiskTask extends DownloaderTask {
    private final boolean mAfterDownload;
    CheckFileOnDiskTask(TaskMetadata mMetadata, Downloader mHostDownloader) {
        super(mMetadata, mHostDownloader);
        this.mAfterDownload = false;
    }

    CheckFileOnDiskTask(TaskMetadata mMetadata, Downloader mHostDownloader, boolean mAfterDownload) {
        super(mMetadata, mHostDownloader);
        this.mAfterDownload = mAfterDownload;
    }

    protected void ensureUrlAndSubmit() throws IOException {
        if(mMetadata.url == null) throw new VerificationException("File "+mMetadata.path.getPath()+" does not have a download URL");
        mDownloader.submitFileForDownload(mMetadata);
    }

    @Override
    protected void performTask() throws IOException {
        boolean checkResult = checkFile();
        if(checkResult) {
            if(!mAfterDownload) mDownloader.addSize(mMetadata.size);
            mDownloader.fileComplete();
        }else {
            if(!mAfterDownload) ensureUrlAndSubmit();
            else throw new VerificationException("Failed to verify "+mMetadata.toString());
        }
    }

    private boolean checkFile() throws IOException {
        File localFile = mMetadata.path;
        if(!localFile.exists()) return false;
        if(!LauncherPreferences.PREF_VERIFY_FILES) return true;
        if(mMetadata.size != -1) {
            if(mMetadata.size != localFile.length()) return false;
        }
        // Nothing cryptographic to verify — accept (legacy behavior for files without a known hash).
        if(mMetadata.sha1Hash == null) {
            return true;
        }
        // Rapid Start: trust a file that was SHA-1 verified in a previous run and has not been
        // modified since (size + last-modified unchanged, and the marker records this exact hash).
        // This keeps startup fast without silently trusting arbitrary on-disk content by size alone.
        if(LauncherPreferences.PREF_RAPID_START && !mAfterDownload
                && Downloader.isVerifiedMarkerStillValid(localFile)) {
            String markerHash = Downloader.readVerifiedMarkerHash(localFile);
            if(markerHash != null && markerHash.equals(mMetadata.sha1Hash)) {
                return true;
            }
            // Marker is valid for size/mtime but records a different hash -> re-verify below.
        }
        boolean ok = HashUtils.compareSHA1(localFile, mMetadata.sha1Hash);
        if(ok && LauncherPreferences.PREF_RAPID_START) {
            try {
                Downloader.writeVerifiedMarker(localFile, mMetadata.sha1Hash);
            } catch (IOException ignored) {
                // Best-effort cache: a missing marker only means the next Rapid Start re-hashes.
            }
        }
        return ok;
    }

}
