package git.artdeell.mojo.fragments;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.fabric.FabriclikeUtils;
import git.artdeell.mojo.modding.fabric.FabricVersion;
import git.artdeell.mojo.modding.ModloaderDownloadListener;
import git.artdeell.mojo.modding.ModloaderListenerProxy;
import git.artdeell.mojo.modding.modpacks.SelfReferencingFuture;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Future;

public abstract class FabriclikeInstallFragment extends Fragment implements ModloaderDownloadListener, CompoundButton.OnCheckedChangeListener {
    private final FabriclikeUtils mFabriclikeUtils;
    private final String mExtraTag;
    private Spinner mGameVersionSpinner;
    private FabricVersion[] mGameVersionArray;
    private Future<?> mGameVersionFuture;
    private String mSelectedGameVersion;
    private Spinner mLoaderVersionSpinner;
    private FabricVersion[] mLoaderVersionArray;
    private Future<?> mLoaderVersionFuture;
    private String mSelectedLoaderVersion;
    private ProgressBar mProgressBar;
    private Button mStartButton;
    private View mRetryView;
    private CheckBox mOnlyStableCheckbox;
    protected FabriclikeInstallFragment(FabriclikeUtils mFabriclikeUtils, String mFragmentTag) {
        super(R.layout.fragment_fabric_install);
        this.mFabriclikeUtils = mFabriclikeUtils;
        this.mExtraTag = mFragmentTag + "_proxy";
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStartButton = view.findViewById(R.id.fabric_installer_start_button);
        mStartButton.setOnClickListener(this::onClickStart);
        mGameVersionSpinner = view.findViewById(R.id.fabric_installer_game_ver_spinner);
        mGameVersionSpinner.setOnItemSelectedListener(new GameVersionSelectedListener());
        mLoaderVersionSpinner = view.findViewById(R.id.fabric_installer_loader_ver_spinner);
        mLoaderVersionSpinner.setOnItemSelectedListener(new LoaderVersionSelectedListener());
        mProgressBar = view.findViewById(R.id.fabric_installer_progress_bar);
        mRetryView = view.findViewById(R.id.fabric_installer_retry_layout);
        mOnlyStableCheckbox = view.findViewById(R.id.fabric_installer_only_stable_checkbox);
        mOnlyStableCheckbox.setOnCheckedChangeListener(this);
        view.findViewById(R.id.fabric_installer_retry_button).setOnClickListener(this::onClickRetry);
        ((TextView)view.findViewById(R.id.fabric_installer_label_loader_ver)).setText(getString(R.string.fabric_dl_loader_version, mFabriclikeUtils.getName()));
        ModloaderListenerProxy proxy = getListenerProxy();
        if(proxy != null) {
            mStartButton.setEnabled(false);
            proxy.attachListener(this);
        }
        updateGameVersions();
    }

    @Override
    public void onStop() {
        cancelFutureChecked(mGameVersionFuture);
        cancelFutureChecked(mLoaderVersionFuture);
        ModloaderListenerProxy proxy = getListenerProxy();
        if(proxy != null) {
            proxy.detachListener();
        }
        super.onStop();
    }

    private void onClickStart(View v) {
        if(!ProgressLayout.ensureNoOngoingTasks(getContext())) return;
        ModloaderListenerProxy proxy = new ModloaderListenerProxy();
        proxy.attachListener(this);
        setListenerProxy(proxy);
        mStartButton.setEnabled(false);
        MojoApplication.sExecutorService.execute(this::performInstallation);
    }

    private void performInstallation() {
        String gameVer = mSelectedGameVersion;
        String loaderVer = mSelectedLoaderVersion;
        if (gameVer == null || loaderVer == null) {
            ModloaderListenerProxy proxy = getListenerProxy();
            if (proxy != null) proxy.onDataNotAvailable();
            return;
        }
        try {
            String versionId = mFabriclikeUtils.install(gameVer, loaderVer);
            ModloaderListenerProxy proxy = getListenerProxy();
            if(versionId == null) {
                if (proxy != null) proxy.onDataNotAvailable();
                return;
            }
            Instances.createInstance((i)->{
                i.name = mFabriclikeUtils.getName();
                i.icon = mFabriclikeUtils.getIconName();
                i.versionId = versionId;
            }, versionId);
            if (proxy != null) proxy.onDownloadFinished(null);
        }catch (IOException e) {
            ModloaderListenerProxy proxy = getListenerProxy();
            if (proxy != null) proxy.onDownloadError(e);
            else Tools.showErrorRemote(e);
        }
    }

    @SuppressWarnings("unused")
    private void onClickRetry(View v) {
        mStartButton.setEnabled(false);
        mRetryView.setVisibility(View.GONE);
        mLoaderVersionSpinner.setAdapter(null);
        if(mGameVersionArray == null) {
            mGameVersionSpinner.setAdapter(null);
            updateGameVersions(true);
            return;
        }
        updateLoaderVersions(true);
    }

    @Override
    public void onDownloadFinished(File downloadedFile) {
        Tools.runOnUiThread(()->{
            ModloaderListenerProxy proxy = getListenerProxy();
            if (proxy != null) proxy.detachListener();
            setListenerProxy(null);
            if (!isAdded()) return;
            mStartButton.setEnabled(true);
            getParentFragmentManager().popBackStackImmediate();
        });
    }

    @Override
    public void onDataNotAvailable() {
        Tools.runOnUiThread(()->{
            ModloaderListenerProxy proxy = getListenerProxy();
            if (proxy != null) proxy.detachListener();
            setListenerProxy(null);
            if (!isAdded()) return;
            Context context = requireContext();
            mStartButton.setEnabled(true);
            Tools.dialog(context,
                    context.getString(R.string.global_error),
                    context.getString(R.string.fabric_dl_cant_read_meta, mFabriclikeUtils.getName()));
        });
    }

    @Override
    public void onDownloadError(Exception e) {
        Tools.runOnUiThread(()-> {
            ModloaderListenerProxy proxy = getListenerProxy();
            if (proxy != null) proxy.detachListener();
            setListenerProxy(null);
            if (!isAdded()) return;
            Context context = requireContext();
            mStartButton.setEnabled(true);
            Tools.showError(context, e);
        });
    }

    private void cancelFutureChecked(Future<?> future) {
        if(future != null && !future.isCancelled()) future.cancel(true);
    }

    private void startLoading() {
        mProgressBar.setVisibility(View.VISIBLE);
        mStartButton.setEnabled(false);
    }

    private void stopLoading() {
        mProgressBar.setVisibility(View.GONE);
        // The "visibility on" is managed by the spinners
    }

    private ArrayAdapter<FabricVersion> createAdapter(FabricVersion[] fabricVersions, boolean onlyStable) {
        ArrayList<FabricVersion> filteredVersions = new ArrayList<>(fabricVersions.length);
        for(FabricVersion fabricVersion : fabricVersions) {
            if(!onlyStable || fabricVersion.stable) filteredVersions.add(fabricVersion);
        }
        filteredVersions.trimToSize();
        return new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, filteredVersions);
    }

    private void onException(Future<?> myFuture, Exception e) {
        Tools.runOnUiThread(()->{
            if(myFuture.isCancelled() || !isAdded()) return;
            stopLoading();
            if(e != null) Tools.showError(requireContext(), e);
            mRetryView.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onCheckedChanged(@NonNull CompoundButton compoundButton, boolean b) {
        updateGameSpinner();
        updateLoaderSpinner();
    }

    class LoaderVersionSelectedListener implements AdapterView.OnItemSelectedListener {

        @Override
        public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
            mSelectedLoaderVersion = ((FabricVersion) adapterView.getAdapter().getItem(i)).version;
            mStartButton.setEnabled(mSelectedGameVersion != null && mSelectedLoaderVersion != null);
        }

        @Override
        public void onNothingSelected(AdapterView<?> adapterView) {
            mSelectedLoaderVersion = null;
            mStartButton.setEnabled(false);
        }
    }

    class LoadLoaderVersionsTask implements SelfReferencingFuture.FutureInterface {
        private final String mGameVersion;
        private final boolean mForceRefresh;

        LoadLoaderVersionsTask(String gameVersion, boolean forceRefresh) {
            mGameVersion = gameVersion;
            mForceRefresh = forceRefresh;
        }

        @Override
        public void run(Future<?> myFuture) {
            Log.i("LoadLoaderVersions", "Starting...");
            try {
                FabricVersion[] loaded = mFabriclikeUtils.downloadLoaderVersions(mGameVersion, mForceRefresh);
                if(loaded != null) {
                    mLoaderVersionArray = loaded;
                    onFinished(myFuture);
                } else {
                    onException(myFuture, null);
                }
            }catch (IOException e) {
                onException(myFuture, e);
            }
        }
        private void onFinished(Future<?> myFuture) {
            Tools.runOnUiThread(()->{
                if(myFuture.isCancelled() || !isAdded()) return;
                stopLoading();
                updateLoaderSpinner();
            });
        }
    }

    private void updateLoaderVersions() {
        updateLoaderVersions(false);
    }

    private void updateLoaderVersions(boolean forceRefresh) {
        if (mSelectedGameVersion == null) {
            mSelectedLoaderVersion = null;
            mStartButton.setEnabled(false);
            return;
        }
        startLoading();
        mLoaderVersionFuture = new SelfReferencingFuture(
                new LoadLoaderVersionsTask(mSelectedGameVersion, forceRefresh))
                .startOnExecutor(MojoApplication.sExecutorService);
    }

    private void updateLoaderSpinner() {
        if(mLoaderVersionArray == null || !isAdded()) return;
        ArrayAdapter<FabricVersion> adapter = createAdapter(mLoaderVersionArray, mOnlyStableCheckbox.isChecked());
        mLoaderVersionSpinner.setAdapter(adapter);
        if (adapter.getCount() == 0) {
            mSelectedLoaderVersion = null;
            mStartButton.setEnabled(false);
            return;
        }
        // Pre-select the verified recommended loader version when present
        int recommendedIdx = 0;
        for (int i = 0; i < adapter.getCount(); i++) {
            FabricVersion item = adapter.getItem(i);
            if (item != null && item.recommended) {
                recommendedIdx = i;
                break;
            }
        }
        mLoaderVersionSpinner.setSelection(recommendedIdx);
    }

    class GameVersionSelectedListener implements AdapterView.OnItemSelectedListener {
        @Override
        public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
            mSelectedGameVersion = ((FabricVersion) adapterView.getAdapter().getItem(i)).version;
            mSelectedLoaderVersion = null;
            cancelFutureChecked(mLoaderVersionFuture);
            updateLoaderVersions(false);
        }

        @Override
        public void onNothingSelected(AdapterView<?> adapterView) {
            mSelectedGameVersion = null;
            mSelectedLoaderVersion = null;
            mStartButton.setEnabled(false);
            if(mLoaderVersionFuture != null) mLoaderVersionFuture.cancel(true);
            adapterView.setAdapter(null);
        }

    }

    class LoadGameVersionsTask implements SelfReferencingFuture.FutureInterface {
        private final boolean mForceRefresh;

        LoadGameVersionsTask(boolean forceRefresh) {
            mForceRefresh = forceRefresh;
        }

        @Override
        public void run(Future<?> myFuture) {
            try {
                FabricVersion[] loaded = mFabriclikeUtils.downloadGameVersions(mForceRefresh);
                if(loaded != null) {
                    mGameVersionArray = loaded;
                    onFinished(myFuture);
                } else {
                    onException(myFuture, null);
                }
            }catch (IOException e) {
                onException(myFuture, e);
            }
        }
        private void onFinished(Future<?> myFuture) {
            Tools.runOnUiThread(()->{
                if(myFuture.isCancelled() || !isAdded()) return;
                stopLoading();
                updateGameSpinner();
            });
        }
    }

    private void updateGameVersions() {
        updateGameVersions(false);
    }

    private void updateGameVersions(boolean forceRefresh) {
        startLoading();
        mGameVersionFuture = new SelfReferencingFuture(new LoadGameVersionsTask(forceRefresh))
                .startOnExecutor(MojoApplication.sExecutorService);
    }

    private void updateGameSpinner() {
        if(mGameVersionArray == null || !isAdded()) return;
        ArrayAdapter<FabricVersion> adapter = createAdapter(mGameVersionArray, mOnlyStableCheckbox.isChecked());
        mGameVersionSpinner.setAdapter(adapter);
        if (adapter.getCount() == 0) {
            mSelectedGameVersion = null;
            mSelectedLoaderVersion = null;
            mStartButton.setEnabled(false);
        }
    }

    private ModloaderListenerProxy getListenerProxy() {
        return (ModloaderListenerProxy) ExtraCore.getValue(mExtraTag);
    }
    private void setListenerProxy(ModloaderListenerProxy listenerProxy) {
        ExtraCore.setValue(mExtraTag, listenerProxy);
    }
}
