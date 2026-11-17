package git.artdeell.mojo.tasks.mojson;

import androidx.annotation.Nullable;

import java.io.File;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.metadata.MojangVersionProvider;
import git.artdeell.mojo.metadata.VersionMetadataRepository;

public class MoJsonExtras {
    @Nullable
    private static JVersionList getActiveManifest() {
        JVersionList inExtra = (JVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if (inExtra != null && inExtra.versions != null) {
            return inExtra;
        }
        JVersionList cached = VersionMetadataRepository.getInstance().getCurrentCatalog();
        if (cached != null) {
            ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, cached);
        }
        return cached;
    }

    public static String normalizeVersionId(String versionString) {
        if (versionString == null) return null;
        return MojangVersionProvider.resolveSymbolicVersionId(versionString, getActiveManifest());
    }

    public static JVersionList.Version getListedVersion(String normalizedVersionString) {
        return MojangVersionProvider.findVersion(getActiveManifest(), normalizedVersionString);
    }

    public interface DoneListener {
        void onDownloadDone(File[] classpath);
        void onDownloadFailed(Throwable throwable);
    }
}
