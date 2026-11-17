package git.artdeell.mojo.fragments;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.ExpandableListAdapter;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.instances.InstanceInstaller;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.forge.ForgelikeUtils;
import git.artdeell.mojo.modding.forge.ForgelikeVersionListAdapter;
import git.artdeell.mojo.modding.ModloaderListenerProxy;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public abstract class ForgelikeInstallFragment extends ModVersionListFragment<List<String>> {
    private final ForgelikeUtils mUtils;
    private volatile Map<String, String> mRecommendedByMc = Collections.emptyMap();

    public ForgelikeInstallFragment(ForgelikeUtils utils, String mFragmentTag) {
        super(mFragmentTag);
        this.mUtils = utils;
    }

    @Override
    public List<String> loadVersionList() throws IOException {
        return loadVersionList(false);
    }

    @Override
    public List<String> loadVersionList(boolean forceRefresh) throws IOException {
        List<String> versions = mUtils.downloadVersions(forceRefresh);
        mRecommendedByMc = mUtils.downloadRecommendedVersions(forceRefresh);
        return versions;
    }

    @Override
    public Runnable createDownloadTask(Object selectedVersion, ModloaderListenerProxy listenerProxy) {
        return ()->createInstance((String) selectedVersion, listenerProxy);
    }

    @Override
    public ExpandableListAdapter createAdapter(List<String> versionList, LayoutInflater layoutInflater) {
        return new ForgelikeVersionListAdapter(versionList, layoutInflater, mUtils, mRecommendedByMc);
    }

    @Override
    public void onDownloadFinished(Context context, File downloadedFile) {
    }

    private void createInstance(String selectedVersion, ModloaderListenerProxy listenerProxy) {
        try {
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0);
            InstanceInstaller instanceInstaller = mUtils.createInstaller(selectedVersion);
            Instances.createInstance(instance -> {
                instance.name = mUtils.getName();
                instance.icon = mUtils.getIconName();
                instance.installer = instanceInstaller;
            }, selectedVersion);
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            instanceInstaller.start();
            listenerProxy.onDownloadFinished(null);
        }catch (IOException e) {
            listenerProxy.onDownloadError(e);
        }
    }
}
